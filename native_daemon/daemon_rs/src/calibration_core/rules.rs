// 校准结束、规则生成与线程名通配符推导。
//
// 规则生成原则：
// - 主进程最重线程可进入 best_thread 档。
// - 主进程其他重负载线程进入 group_high/group_mid 档。
// - 子进程只生成 com.pkg:proc=cpus，不生成 com.pkg:proc{thread}=cpus。
// - 最后总是追加主包名兜底规则，避免没有单独命中的线程跑到未指定核心。
//
// 这里的目标不是“生成越多规则越好”，而是生成用户能理解且守护进程能稳定执行的规则。
fn finish_session(session: CalibSession, config_file: &Path) -> io::Result<()> {
    // 60 轮 * 500ms 约等于 30 秒，采样太短时负载峰值很容易误判。
    if session.rounds < CALIB_MIN_ROUNDS {
        println!(
            "[CALIB] 采样时长不足: pkg={} 轮次={} 最少需要={}",
            session.pkg, session.rounds, CALIB_MIN_ROUNDS
        );
        write_state(&format!("done {};reason=short", session.pkg))?;
        return Ok(());
    }

    let mut records: Vec<LoadRecord> = session
        .records
        .values()
        .filter(|record| !record.samples.is_empty())
        .cloned()
        .collect();
    records.sort_by(|a, b| {
        b.avg()
            .partial_cmp(&a.avg())
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    if records.is_empty() {
        println!("[CALIB] 未检测到明显负载: pkg={}", session.pkg);
        write_state(&format!("done {};reason=no_load", session.pkg))?;
        return Ok(());
    }

    // 历史记录优先落盘；即使规则生成或写回失败，App 仍可导入这次采样数据辅助排查。
    if let Err(err) = write_history(
        &session.pkg,
        session.rounds,
        &records,
        &session.child_threads,
    ) {
        eprintln!("[CALIB] 历史记录写入失败: pkg={} err={}", session.pkg, err);
    } else {
        println!(
            "[CALIB] 历史记录已写入: pkg={} 轮次={} 负载项={} 子进程线程摘要={} Top=[{}]",
            session.pkg,
            session.rounds,
            records.len(),
            session.child_threads.len(),
            top_record_summary(records.iter(), 8)
        );
    }
    if !records.iter().any(|record| record.sum_pct > 0.0) {
        println!("[CALIB] 未生成规则: pkg={} reason=no_load", session.pkg);
        write_state(&format!("done {};reason=no_load", session.pkg))?;
        return Ok(());
    }
    let rules = generate_rules(&session.pkg, &records);
    if rules.is_empty() {
        println!("[CALIB] 未生成规则: pkg={} reason=no_load", session.pkg);
        write_state(&format!("done {};reason=no_load", session.pkg))?;
        return Ok(());
    }

    if write_rules_back(config_file, &session.pkg, &rules) {
        println!(
            "[CALIB] 已生成规则: pkg={} 行数={}\n{}",
            session.pkg,
            rules.len(),
            rules.join("\n")
        );
        write_state(&format!("done {}", session.pkg))?;
    } else {
        eprintln!("[CALIB] 规则写回配置文件失败: pkg={}", session.pkg);
        write_state(&format!("done {};reason=write_fail", session.pkg))?;
    }
    Ok(())
}

fn generate_rules(pkg: &str, records: &[LoadRecord]) -> Vec<String> {
    let topo = CpuTiers::detect();
    let policy = CalibPolicy::load(&topo);
    let mut rules = Vec::new();
    let mut used = HashSet::new();

    // 主进程按线程负载生成线程规则；子进程只在下面生成进程级规则。
    let mut main_threads: Vec<&LoadRecord> = records
        .iter()
        .filter(|record| !record.is_process && record.owner == pkg)
        .collect();
    main_threads.sort_by(|a, b| {
        load_score(b)
            .partial_cmp(&load_score(a))
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| {
                b.avg()
                    .partial_cmp(&a.avg())
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| {
                b.max_pct
                    .partial_cmp(&a.max_pct)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| a.name.cmp(&b.name))
    });

    let mut prepared_threads = prepare_thread_rules(&main_threads);
    assign_canonical_bases(&mut prepared_threads);

    let mut groups = Vec::<GeneratedThreadGroup>::new();
    for prepared in &prepared_threads {
        let record = prepared.record;
        let base = &prepared.canonical_base;
        let is_wild = base.contains('*');
        let index = groups
            .iter()
            .position(|group| group.base == *base)
            .unwrap_or_else(|| {
                groups.push(GeneratedThreadGroup {
                    base: base.clone(),
                    avg_pct: 0.0,
                    max_pct: 0.0,
                    score: 0.0,
                    is_wild,
                    tier_rank: 0,
                });
                groups.len() - 1
            });
        let group = &mut groups[index];
        let avg = record.avg();
        if group.is_wild && policy.wildcard_group == WildcardGroup::MaxMember {
            group.avg_pct = group.avg_pct.max(avg);
        } else {
            group.avg_pct += avg;
        }
        group.max_pct = group.max_pct.max(record.max_pct);
    }
    for group in &mut groups {
        group.avg_pct = group.avg_pct.min(100.0);
        group.score = load_score_values(group.avg_pct, group.max_pct);
        group.tier_rank = generated_group_tier_rank(group, &policy);
    }

    let group_coverages = observed_group_coverages(&groups, &prepared_threads);
    normalize_overlapping_group_tiers(&mut groups, &group_coverages);

    let best = prepared_threads.iter().find(|prepared| {
        let record = prepared.record;
        record.avg() >= policy.best_avg
            && record.max_pct >= policy.best_max
            && !best_group_is_ambiguous(
                &prepared.canonical_base,
                &groups,
                &group_coverages,
            )
    });
    let best_overlapping_bases = best
        .map(|best| {
            overlapping_group_bases(&best.canonical_base, &groups, &group_coverages)
        })
        .unwrap_or_default();
    if let Some(best) = best {
        // 最高负载线程若被归并进动态通配组，整组保留最高档；
        // 交叉覆盖有歧义时仍由 best_group_is_ambiguous 阻止误晋级。
        push_rule(
            &mut rules,
            &mut used,
            format!(
                "{pkg}{{{}}}={}",
                best.canonical_base, policy.best_cores
            ),
        );
    }

    groups.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| {
                b.avg_pct
                    .partial_cmp(&a.avg_pct)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| {
                b.max_pct
                    .partial_cmp(&a.max_pct)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| a.base.cmp(&b.base))
    });

    let mut thread_rule_count = rules.len();
    for tier_rank in [2u8, 1u8] {
        // 同档位先输出精确线程，再输出通配组，保持稳定的规则顺序。
        for wild_pass in [false, true] {
            for group in groups.iter().filter(|group| group.is_wild == wild_pass) {
                if thread_rule_count >= policy.max_thread_rules {
                    break;
                }
                if group.tier_rank != tier_rank {
                    continue;
                }
                if best_overlapping_bases.contains(&group.base) {
                    continue;
                }
                let cpus = match tier_rank {
                    2 => &policy.high_cores,
                    _ => &policy.mid_cores,
                };
                let previous = rules.len();
                push_rule(
                    &mut rules,
                    &mut used,
                    format!("{pkg}{{{}}}={}", group.base, cpus),
                );
                if rules.len() > previous {
                    thread_rule_count += 1;
                }
            }
        }
    }

    // 子进程线程名通常过碎且规则语法不支持 com.app:proc{thread}，这里只绑定子进程整体。
    for tier in [RuleTier::High, RuleTier::Mid] {
        for record in records
            .iter()
            .filter(|record| record.is_process && record.owner != pkg)
        {
            let avg = record.avg();
            let max = record.max_pct;
            let pass = match tier {
                RuleTier::High => avg >= policy.high_avg && max >= policy.high_max,
                RuleTier::Mid => avg >= policy.mid_avg && max >= policy.mid_max,
            };
            if !pass {
                continue;
            }
            let cpus = match tier {
                RuleTier::High => &policy.high_cores,
                RuleTier::Mid => &policy.mid_cores,
            };
            push_rule(&mut rules, &mut used, format!("{}={}", record.owner, cpus));
        }
    }

    push_rule(
        // 最后一条永远写主进程兜底规则，保证未单独命中的线程不会跑到未指定核心。
        &mut rules,
        &mut used,
        format!("{pkg}={}", policy.fallback_cores),
    );
    format_generated_rules(pkg, rules, policy.rule_output_format)
}

fn process_preview(processes: &[ProcInfo], limit: usize) -> String {
    if processes.is_empty() {
        return "-".to_string();
    }
    let mut rows = processes
        .iter()
        .take(limit)
        .map(|proc_info| format!("{}:{}", proc_info.owner, proc_info.pid))
        .collect::<Vec<_>>()
        .join(", ");
    if processes.len() > limit {
        rows.push_str(&format!(" ... +{}", processes.len() - limit));
    }
    rows
}

fn top_record_summary<'a>(
    records: impl IntoIterator<Item = &'a LoadRecord>,
    limit: usize,
) -> String {
    let mut rows = records.into_iter().collect::<Vec<_>>();
    if rows.is_empty() {
        return "-".to_string();
    }
    rows.sort_by(|a, b| {
        load_score(b)
            .partial_cmp(&load_score(a))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut out = rows
        .iter()
        .take(limit)
        .map(|record| {
            let name = if record.is_process {
                record.owner.as_str()
            } else {
                record.name.as_str()
            };
            format!("{name} avg={:.1}% max={:.1}%", record.avg(), record.max_pct)
        })
        .collect::<Vec<_>>()
        .join("; ");
    if rows.len() > limit {
        out.push_str(&format!(" ... +{}", rows.len() - limit));
    }
    out
}

fn push_rule(rules: &mut Vec<String>, used: &mut HashSet<String>, rule: String) {
    let key = rule
        .split_once('=')
        .map(|(left, _)| left.to_string())
        .unwrap_or_else(|| rule.clone());
    if used.insert(key) {
        rules.push(rule);
    }
}

fn load_score(record: &LoadRecord) -> f64 {
    load_score_values(record.avg(), record.max_pct)
}

fn load_score_values(avg_pct: f64, max_pct: f64) -> f64 {
    avg_pct * 0.65 + max_pct * 0.35
}

struct GeneratedThreadGroup {
    base: String,
    avg_pct: f64,
    max_pct: f64,
    score: f64,
    is_wild: bool,
    // 2=高负载档，1=中负载档，0=不单独生成。
    tier_rank: u8,
}

struct PreparedThreadRule<'a> {
    record: &'a LoadRecord,
    own_base: String,
    canonical_base: String,
}

struct ParsedThreadName {
    literals: Vec<String>,
    digits: Vec<String>,
    digit_spans: Vec<(usize, usize)>,
}

struct PatternCoverage {
    base: String,
    matches: Vec<bool>,
    count: usize,
    required_atoms: usize,
    char_len: usize,
}

fn prepare_thread_rules<'a>(records: &[&'a LoadRecord]) -> Vec<PreparedThreadRule<'a>> {
    let parsed = records
        .iter()
        .map(|record| parse_thread_name(&record.name))
        .collect::<Vec<_>>();

    // literal 序列就是数字名称的“形状”。只有同形状、同位置出现不同数字，
    // 才能给 Worker1/Worker2 这类无分隔数字提供动态证据。
    let mut shape_values = HashMap::<Vec<String>, Vec<HashSet<String>>>::new();
    for parts in parsed.iter().flatten() {
        let values = shape_values
            .entry(parts.literals.clone())
            .or_insert_with(|| vec![HashSet::new(); parts.digits.len()]);
        for (index, digits) in parts.digits.iter().enumerate() {
            values[index].insert(digits.clone());
        }
    }

    records
        .iter()
        .zip(parsed.iter())
        .filter_map(|(record, parts)| {
            let parts = parts.as_ref()?;
            let values = shape_values.get(&parts.literals)?;
            let own_base = render_thread_base(&record.name, parts, values)?;
            Some(PreparedThreadRule {
                record,
                canonical_base: own_base.clone(),
                own_base,
            })
        })
        .collect()
}

fn parse_thread_name(name: &str) -> Option<ParsedThreadName> {
    if !raw_thread_name_syntax_ok(name) {
        return None;
    }

    let mut literals = Vec::new();
    let mut digits = Vec::new();
    let mut digit_spans = Vec::new();
    let mut cursor = 0usize;
    let mut literal_start = 0usize;

    while cursor < name.len() {
        let ch = name[cursor..].chars().next()?;
        if !ch.is_ascii_digit() {
            cursor += ch.len_utf8();
            continue;
        }

        literals.push(name[literal_start..cursor].to_string());
        let digit_start = cursor;
        while cursor < name.len() {
            let digit = name[cursor..].chars().next()?;
            if !digit.is_ascii_digit() {
                break;
            }
            cursor += digit.len_utf8();
        }
        digits.push(name[digit_start..cursor].to_string());
        digit_spans.push((digit_start, cursor));
        literal_start = cursor;
    }
    literals.push(name[literal_start..].to_string());

    Some(ParsedThreadName {
        literals,
        digits,
        digit_spans,
    })
}

fn render_thread_base(
    name: &str,
    parts: &ParsedThreadName,
    shape_values: &[HashSet<String>],
) -> Option<String> {
    if parts.digits.is_empty() {
        return Some(name.to_string());
    }

    let direct = parts
        .digit_spans
        .iter()
        .map(|span| digit_run_has_direct_dynamic_boundary(name, *span))
        .collect::<Vec<_>>();
    let dynamic = direct
        .iter()
        .enumerate()
        .map(|(index, direct)| *direct || shape_values[index].len() >= 2)
        .collect::<Vec<_>>();

    if !dynamic.iter().any(|value| *value) || stable_anchor_count(parts) < 2 {
        return Some(name.to_string());
    }

    let dynamic_count = dynamic.iter().filter(|value| **value).count();
    let dynamic_index = dynamic.iter().position(|value| *value);
    if dynamic_count == 1 {
        let index = dynamic_index?;
        let (start, end) = parts.digit_spans[index];
        if direct[index] && end == name.len() {
            let prefix = name[..start].trim_end_matches(|ch| matches!(ch, ' ' | '\t'));
            let out = format!("{prefix}*");
            return Some(if wildcard_name_syntax_ok(&out) {
                out
            } else {
                name.to_string()
            });
        }
    }

    let mut out = String::with_capacity(name.len() + dynamic_count * 5);
    let mut cursor = 0usize;
    for (index, (start, end)) in parts.digit_spans.iter().copied().enumerate() {
        out.push_str(&name[cursor..start]);
        if dynamic[index] {
            out.push_str("[0-9]*");
        } else {
            out.push_str(&name[start..end]);
        }
        cursor = end;
    }
    out.push_str(&name[cursor..]);
    Some(if wildcard_name_syntax_ok(&out) {
        out
    } else {
        name.to_string()
    })
}

fn digit_run_has_direct_dynamic_boundary(name: &str, (start, end): (usize, usize)) -> bool {
    let Some(previous) = name[..start].chars().next_back() else {
        return false;
    };
    if !is_direct_number_delimiter(previous) {
        return false;
    }
    name[end..]
        .chars()
        .next()
        .is_none_or(is_direct_number_delimiter)
}

fn is_direct_number_delimiter(ch: char) -> bool {
    matches!(ch, ' ' | '\t' | '-' | '_')
}

fn stable_anchor_count(parts: &ParsedThreadName) -> usize {
    parts
        .literals
        .iter()
        .flat_map(|literal| literal.chars())
        .filter(|ch| ch.is_ascii_alphabetic() || !ch.is_ascii())
        .count()
}

fn assign_canonical_bases(threads: &mut [PreparedThreadRule<'_>]) {
    let wildcard_bases = threads
        .iter()
        .filter(|thread| thread.own_base.contains('*'))
        .map(|thread| thread.own_base.clone())
        .collect::<HashSet<_>>();
    let coverages = wildcard_bases
        .into_iter()
        .map(|base| {
            let matches = threads
                .iter()
                .map(|thread| wildcard_match(&base, &thread.record.name))
                .collect::<Vec<_>>();
            PatternCoverage {
                count: matches.iter().filter(|value| **value).count(),
                required_atoms: wildcard_required_atoms(&base),
                char_len: base.chars().count(),
                base,
                matches,
            }
        })
        .collect::<Vec<_>>();

    for (index, thread) in threads.iter_mut().enumerate() {
        let mut widest: Option<&PatternCoverage> = None;
        for coverage in coverages.iter().filter(|coverage| coverage.matches[index]) {
            if widest.is_none_or(|current| pattern_is_wider(coverage, current)) {
                widest = Some(coverage);
            }
        }
        if let Some(widest) = widest {
            thread.canonical_base.clone_from(&widest.base);
        }
    }
}

fn pattern_is_wider(candidate: &PatternCoverage, current: &PatternCoverage) -> bool {
    candidate.count > current.count
        || (candidate.count == current.count
            && (candidate.required_atoms < current.required_atoms
                || (candidate.required_atoms == current.required_atoms
                    && (candidate.char_len < current.char_len
                        || (candidate.char_len == current.char_len
                            && candidate.base < current.base)))))
}

fn wildcard_required_atoms(pattern: &str) -> usize {
    let chars = pattern.chars().collect::<Vec<_>>();
    let mut index = 0usize;
    let mut required = 0usize;
    while index < chars.len() {
        match chars[index] {
            '*' => index += 1,
            '[' => {
                required += 1;
                index += 1;
                while index < chars.len() && chars[index] != ']' {
                    index += 1;
                }
                if index < chars.len() {
                    index += 1;
                }
            }
            _ => {
                required += 1;
                index += 1;
            }
        }
    }
    required
}

fn generated_group_tier_rank(group: &GeneratedThreadGroup, policy: &CalibPolicy) -> u8 {
    if group.avg_pct >= policy.high_avg && group.max_pct >= policy.high_max {
        2
    } else if group.avg_pct >= policy.mid_avg && group.max_pct >= policy.mid_max {
        1
    } else {
        0
    }
}

fn observed_group_coverages(
    groups: &[GeneratedThreadGroup],
    threads: &[PreparedThreadRule<'_>],
) -> Vec<Vec<bool>> {
    groups
        .iter()
        .map(|group| {
            threads
                .iter()
                .map(|thread| wildcard_match(&group.base, &thread.record.name))
                .collect()
        })
        .collect()
}

fn normalize_overlapping_group_tiers(
    groups: &mut [GeneratedThreadGroup],
    coverages: &[Vec<bool>],
) {
    if groups.len() < 2 {
        return;
    }

    // 同一个观察名称命中的组属于同一连通分量。一次记录只把其命中组并到首组，
    // 避免逐组、逐记录、再逐组的三层扫描。
    let mut parents = (0..groups.len()).collect::<Vec<_>>();
    let mut ranks = vec![0u8; groups.len()];
    let observed_count = coverages.first().map_or(0, Vec::len);
    for record_index in 0..observed_count {
        let mut first = None;
        for group_index in 0..groups.len() {
            if !coverages[group_index][record_index] {
                continue;
            }
            if let Some(first) = first {
                dsu_union(&mut parents, &mut ranks, first, group_index);
            } else {
                first = Some(group_index);
            }
        }
    }

    let mut highest = HashMap::<usize, u8>::new();
    for (index, group) in groups.iter().enumerate() {
        let root = dsu_find(&mut parents, index);
        highest
            .entry(root)
            .and_modify(|tier| *tier = (*tier).max(group.tier_rank))
            .or_insert(group.tier_rank);
    }
    for (index, group) in groups.iter_mut().enumerate() {
        let root = dsu_find(&mut parents, index);
        group.tier_rank = highest.get(&root).copied().unwrap_or(group.tier_rank);
    }
}

fn dsu_find(parents: &mut [usize], index: usize) -> usize {
    if parents[index] != index {
        parents[index] = dsu_find(parents, parents[index]);
    }
    parents[index]
}

fn dsu_union(parents: &mut [usize], ranks: &mut [u8], left: usize, right: usize) {
    let mut left_root = dsu_find(parents, left);
    let mut right_root = dsu_find(parents, right);
    if left_root == right_root {
        return;
    }
    if ranks[left_root] < ranks[right_root] {
        std::mem::swap(&mut left_root, &mut right_root);
    }
    parents[right_root] = left_root;
    if ranks[left_root] == ranks[right_root] {
        ranks[left_root] += 1;
    }
}

fn best_group_is_ambiguous(
    best_base: &str,
    groups: &[GeneratedThreadGroup],
    coverages: &[Vec<bool>],
) -> bool {
    let Some(best_index) = groups.iter().position(|group| group.base == best_base) else {
        return true;
    };

    groups.iter().enumerate().any(|(index, group)| {
        if index == best_index || group.base == best_base {
            return false;
        }
        let mut intersects = false;
        let mut best_only = false;
        let mut other_only = false;
        for (best_matches, other_matches) in coverages[best_index].iter().zip(&coverages[index]) {
            match (*best_matches, *other_matches) {
                (true, true) => intersects = true,
                (true, false) => best_only = true,
                (false, true) => other_only = true,
                (false, false) => {}
            }
        }
        intersects && best_only && other_only
    })
}

fn overlapping_group_bases(
    best_base: &str,
    groups: &[GeneratedThreadGroup],
    coverages: &[Vec<bool>],
) -> HashSet<String> {
    let Some(best_index) = groups.iter().position(|group| group.base == best_base) else {
        return HashSet::new();
    };
    groups
        .iter()
        .enumerate()
        .filter(|(index, _)| {
            coverages[best_index]
                .iter()
                .zip(&coverages[*index])
                .any(|(best_matches, group_matches)| *best_matches && *group_matches)
        })
        .map(|(_, group)| group.base.clone())
        .collect()
}

fn raw_thread_name_syntax_ok(name: &str) -> bool {
    !name.is_empty()
        && name.chars().all(|ch| {
            !matches!(
                ch,
                '{' | '}' | '=' | '/' | '\\' | '*' | '?' | '[' | ']' | '\n' | '\r'
            ) && (ch == '\t' || ch >= ' ')
        })
}

fn rule_pattern_syntax_ok(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|ch| !matches!(ch, '{' | '}' | '=' | '/' | '\\' | '\n' | '\r'))
}

fn wildcard_name_syntax_ok(name: &str) -> bool {
    name != "*" && name.len() < 32 && rule_pattern_syntax_ok(name) && name.contains('*')
}

fn wildcard_match(pattern: &str, text: &str) -> bool {
    let pattern: Vec<char> = pattern.chars().collect();
    let text: Vec<char> = text.chars().collect();
    let (mut pi, mut ti) = (0usize, 0usize);
    let mut star = None;
    let mut star_text = 0usize;

    while ti < text.len() {
        if pi < pattern.len() && pattern[pi] != '*' {
            if let Some(next_pi) = wildcard_atom_matches(&pattern, pi, text[ti]) {
                pi = next_pi;
                ti += 1;
                continue;
            }
        }
        if pi < pattern.len() && pattern[pi] == '*' {
            star = Some(pi);
            pi += 1;
            star_text = ti;
        } else if let Some(star_pos) = star {
            pi = star_pos + 1;
            star_text += 1;
            ti = star_text;
        } else {
            return false;
        }
    }
    while pi < pattern.len() && pattern[pi] == '*' {
        pi += 1;
    }
    pi == pattern.len()
}

fn wildcard_atom_matches(pattern: &[char], index: usize, ch: char) -> Option<usize> {
    match pattern[index] {
        '?' => Some(index + 1),
        '[' => wildcard_class_matches(pattern, index, ch),
        literal if literal == ch => Some(index + 1),
        _ => None,
    }
}

fn wildcard_class_matches(pattern: &[char], index: usize, ch: char) -> Option<usize> {
    let mut cursor = index + 1;
    let negated = cursor < pattern.len() && matches!(pattern[cursor], '!' | '^');
    if negated {
        cursor += 1;
    }
    let mut matched = false;

    while cursor < pattern.len() {
        if pattern[cursor] == ']' {
            return if matched != negated {
                Some(cursor + 1)
            } else {
                None
            };
        }
        if cursor + 2 < pattern.len()
            && pattern[cursor + 1] == '-'
            && pattern[cursor + 2] != ']'
        {
            if pattern[cursor] <= ch && ch <= pattern[cursor + 2] {
                matched = true;
            }
            cursor += 3;
        } else {
            if pattern[cursor] == ch {
                matched = true;
            }
            cursor += 1;
        }
    }
    None
}
