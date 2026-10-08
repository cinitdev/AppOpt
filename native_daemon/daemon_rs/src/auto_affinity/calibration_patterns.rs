//! 生成稳定的线程族，与 CPU 分配策略独立。
use std::collections::{BTreeMap, BTreeSet};

pub(super) fn exact_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && name.trim() == name
        && !name
            .chars()
            .any(|c| c.is_control() || "{}[]=*?/\\".contains(c))
}

struct Parts {
    literals: Vec<String>,
    digits: Vec<String>,
    spans: Vec<(usize, usize)>,
}

fn parse(name: &str) -> Parts {
    let (mut literals, mut digits, mut spans) = (Vec::new(), Vec::new(), Vec::new());
    let (mut cursor, mut literal) = (0, 0);
    while cursor < name.len() {
        let ch = name[cursor..].chars().next().unwrap();
        if !ch.is_ascii_digit() {
            cursor += ch.len_utf8();
            continue;
        }
        literals.push(name[literal..cursor].to_string());
        let start = cursor;
        while cursor < name.len() && name.as_bytes()[cursor].is_ascii_digit() {
            cursor += 1;
        }
        digits.push(name[start..cursor].to_string());
        spans.push((start, cursor));
        literal = cursor;
    }
    literals.push(name[literal..].to_string());
    Parts {
        literals,
        digits,
        spans,
    }
}

fn delimiter(c: char) -> bool {
    matches!(c, ' ' | '\t' | '-' | '_')
}

/// 采集结束时按进程归属分别调用一次。保留固定数字（如 GL/Vulkan/编解码版本）；
/// 只有分隔符后的序号或同一数字位置的实测变化，
/// 才可作为生成通配符的依据。
pub(super) fn canonical(names: &[String]) -> Vec<Option<String>> {
    let parts: Vec<_> = names.iter().map(|n| parse(n)).collect();
    let mut shapes = BTreeMap::<Vec<String>, Vec<BTreeSet<String>>>::new();
    for p in &parts {
        let values = shapes
            .entry(p.literals.clone())
            .or_insert_with(|| vec![BTreeSet::new(); p.digits.len()]);
        for (i, value) in p.digits.iter().enumerate() {
            values[i].insert(value.clone());
        }
    }
    let bases: Vec<Option<String>> = names
        .iter()
        .zip(&parts)
        .map(|(name, p)| {
            if !exact_name(name) {
                return None;
            }
            // Binder 后缀包含进程 ID 和工作线程序号，整个后缀都会变化；
            // 继续沿用现有的简单线程族规则。
            if name.starts_with("Binder:") {
                return Some("Binder:*".into());
            }
            let anchors = p
                .literals
                .iter()
                .flat_map(|s| s.chars())
                .filter(|c| c.is_alphabetic())
                .count();
            if anchors < 2 {
                return Some(name.clone());
            }
            let values = &shapes[&p.literals];
            let dynamic: Vec<_> = p
                .spans
                .iter()
                .enumerate()
                .map(|(i, &(start, end))| {
                    (name[..start].chars().next_back().is_some_and(delimiter)
                        && name[end..].chars().next().is_none_or(delimiter))
                        || values[i].len() > 1
                })
                .collect();
            let mut result = String::new();
            let mut cursor = 0;
            for (i, &(start, end)) in p.spans.iter().enumerate() {
                result.push_str(&name[cursor..start]);
                if dynamic[i] {
                    // 保留原有末尾序号形式。中间序号至少需要匹配一位数字，
                    // 使用守护进程已有的 glob 语法表达。
                    if end == name.len()
                        && dynamic.iter().filter(|&&d| d).count() == 1
                        && name[..start].chars().next_back().is_some_and(delimiter)
                    {
                        result.truncate(result.trim_end_matches([' ', '\t']).len());
                        result.push('*');
                    } else {
                        result.push_str("[0-9]*");
                    }
                } else {
                    result.push_str(&name[start..end]);
                }
                cursor = end;
            }
            result.push_str(&name[cursor..]);
            Some(if result.len() < 32 {
                result
            } else {
                name.clone()
            })
        })
        .collect();
    let patterns: BTreeSet<_> = bases
        .iter()
        .flatten()
        .filter(|p| p.contains('*'))
        .cloned()
        .collect();
    let coverage: Vec<_> = patterns
        .iter()
        .map(|p| {
            (
                p,
                names
                    .iter()
                    .enumerate()
                    .filter(|(_, n)| crate::glob_match(p, n))
                    .map(|(i, _)| i)
                    .collect::<BTreeSet<_>>(),
            )
        })
        .collect();
    // 重叠线程族保留稳定名称。调用方为相连的线程族分配共享 CPU 池，
    // 每个成员只计入一次容量预算。
    names
        .iter()
        .enumerate()
        .map(|(i, name)| {
            if bases[i].is_none() {
                return None;
            }
            coverage
                .iter()
                .filter(|(_, c)| c.contains(&i))
                .max_by(|(pa, a), (pb, b)| {
                    a.len()
                        .cmp(&b.len())
                        .then_with(|| required_atoms(pb).cmp(&required_atoms(pa)))
                        .then_with(|| pb.chars().count().cmp(&pa.chars().count()))
                        .then_with(|| pb.cmp(pa))
                })
                .map(|(p, _)| (*p).clone())
                .or_else(|| Some(name.clone()))
        })
        .collect()
}

fn required_atoms(pattern: &str) -> usize {
    let mut count = 0;
    let mut chars = pattern.chars();
    while let Some(ch) = chars.next() {
        match ch {
            '*' => {}
            '[' => {
                count += 1;
                for ch in chars.by_ref() {
                    if ch == ']' {
                        break;
                    }
                }
            }
            _ => count += 1,
        }
    }
    count
}

/// 只有单独达到入选条件的线程才生成规则；被规则覆盖的已知低负载成员
/// 仍占用容量。分配前合并相交的覆盖范围，
/// 避免规则顺序造成互相冲突的绑定。
pub(super) fn families(names: &[String], eligible: &[bool]) -> Vec<(Vec<String>, BTreeSet<usize>)> {
    let selected: BTreeSet<_> = canonical(names)
        .into_iter()
        .zip(eligible)
        .filter_map(|(pattern, &yes)| if yes { pattern } else { None })
        .collect();
    let coverage: Vec<_> = selected
        .into_iter()
        .map(|pattern| {
            let covered: BTreeSet<_> = names
                .iter()
                .enumerate()
                .filter(|(_, name)| crate::glob_match(&pattern, name))
                .map(|(i, _)| i)
                .collect();
            (pattern, covered)
        })
        .collect();
    merge_coverage(coverage, names.len())
}

fn merge_coverage(
    coverage: Vec<(String, BTreeSet<usize>)>,
    count: usize,
) -> Vec<(Vec<String>, BTreeSet<usize>)> {
    fn root(parents: &mut [usize], mut i: usize) -> usize {
        while parents[i] != i {
            parents[i] = parents[parents[i]];
            i = parents[i];
        }
        i
    }
    let mut parents: Vec<_> = (0..coverage.len()).collect();
    let mut first = vec![None; count];
    for (i, (_, members)) in coverage.iter().enumerate() {
        for &member in members {
            if let Some(other) = first[member] {
                let a = root(&mut parents, i);
                let b = root(&mut parents, other);
                parents[a] = b;
            } else {
                first[member] = Some(i);
            }
        }
    }
    let mut result = BTreeMap::<usize, (Vec<String>, BTreeSet<usize>)>::new();
    for (i, (pattern, members)) in coverage.into_iter().enumerate() {
        let group = result.entry(root(&mut parents, i)).or_default();
        group.0.push(pattern);
        group.1.extend(members);
    }
    result.into_values().collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn transitive_overlap_has_one_budget_and_keeps_all_patterns() {
        let coverage = vec![
            ("first*".into(), [0, 1].into()),
            ("third*".into(), [2, 3].into()),
            ("bridge*".into(), [1, 2].into()),
            ("separate*".into(), [4].into()),
        ];
        let result = merge_coverage(coverage, 5);
        assert_eq!(result.len(), 2);
        let joined = result
            .iter()
            .find(|(patterns, _)| patterns.len() == 3)
            .unwrap();
        assert_eq!(joined.1, [0, 1, 2, 3].into());
    }
    #[test]
    fn restores_dynamic_indexes_without_erasing_codec_numbers() {
        let names = [
            "Binder:10534_7",
            "Binder:20488_9",
            "thread-shared-3",
            "thread-shared-9",
            "ffmpeg-ks265-02",
            "GLThread1",
            "GLThread2",
            "RenderThread",
            "Vulkan1",
        ]
        .map(String::from);
        let p = canonical(&names);
        assert_eq!(p[0].as_deref(), Some("Binder:*"));
        assert_eq!(p[0], p[1]);
        assert_eq!(p[2].as_deref(), Some("thread-shared-*"));
        assert_eq!(p[2], p[3]);
        assert_eq!(p[4].as_deref(), Some("ffmpeg-ks265-*"));
        assert_eq!(p[5], p[6]);
        assert_eq!(p[5].as_deref(), Some("GLThread[0-9]*"));
        assert_eq!(p[8].as_deref(), Some("Vulkan1"));
        assert!(crate::glob_match(p[0].as_ref().unwrap(), "Binder:9999_12"));
        assert!(crate::glob_match(
            p[2].as_ref().unwrap(),
            "thread-shared-27"
        ));
    }
    #[test]
    fn generated_patterns_obey_original_byte_limit() {
        let name = "abcdefghijklmnopqrstuvwxyza-123";
        assert!(canonical(&[name.into()])[0].as_ref().unwrap().len() < 32);
        assert_eq!(
            canonical(&["abcdefghijklmnopqrstuvwxyzabcdef-1".into()])[0].as_deref(),
            Some("abcdefghijklmnopqrstuvwxyzabcdef-1")
        );
        assert_eq!(
            canonical(&["Worker 1".into()])[0].as_deref(),
            Some("Worker*")
        );
        assert_eq!(
            canonical(&["GLThread1".into()])[0].as_deref(),
            Some("GLThread1")
        );
        assert_eq!(
            canonical(&["codec:265".into()])[0].as_deref(),
            Some("codec:265")
        );
    }
    #[test]
    fn wildcard_covers_all_observed_members_including_low_load_peers() {
        let p = canonical(&["worker-1".into(), "worker-2".into(), "worker-ui".into()]);
        assert!(p.iter().all(|p| p.as_deref() == Some("worker-*")));
        assert!(!exact_name("worker*=7"));
        assert!(!exact_name("x\ny"));
    }
}
