use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
};

pub const CPU_ROOT: &str = "/sys/devices/system/cpu";

pub fn ids(text: &str) -> Vec<usize> {
    let mut result = Vec::new();
    for item in text
        .split(|c: char| c == ',' || c.is_whitespace())
        .filter(|s| !s.is_empty())
    {
        let mut parts = item.split('-');
        let Some(start) = parts.next().and_then(|v| v.parse::<usize>().ok()) else {
            return vec![];
        };
        let end = match parts.next() {
            Some(v) => match v.parse::<usize>() {
                Ok(n) => n,
                Err(_) => return vec![],
            },
            None => start,
        };
        if parts.next().is_some() || start > end || end >= 4096 {
            return vec![];
        }
        result.extend(start..=end);
    }
    result.sort_unstable();
    result.dedup();
    result
}

pub fn format_ids(values: &[usize]) -> String {
    let mut ids = values.to_vec();
    ids.sort_unstable();
    ids.dedup();
    let mut groups = Vec::new();
    let mut cursor = 0;
    while cursor < ids.len() {
        let start = ids[cursor];
        let mut end = start;
        cursor += 1;
        while cursor < ids.len() && ids[cursor] == end + 1 {
            end = ids[cursor];
            cursor += 1;
        }
        groups.push(if start == end {
            start.to_string()
        } else {
            format!("{start}-{end}")
        });
    }
    groups.join(",")
}

fn path(root: &Path, relative: &str) -> PathBuf {
    root.join(relative.trim_start_matches('/'))
}
fn read(root: &Path, relative: &str) -> Option<String> {
    fs::read_to_string(path(root, relative)).ok()
}
fn number(root: &Path, relative: &str) -> Option<u64> {
    read(root, relative)?.trim().parse().ok().filter(|v| *v > 0)
}

#[derive(Clone, Debug)]
pub struct Policy {
    /// 内核接口的绝对路径，不受测试样例根目录影响。
    pub paths: Vec<String>,
    pub members: Vec<usize>,
    pub max_khz: Option<u64>,
}

pub fn policies(root: &Path) -> Vec<Policy> {
    let mut paths: Vec<String> = fs::read_dir(path(root, &format!("{CPU_ROOT}/cpufreq")))
        .into_iter()
        .flatten()
        .take(4096)
        .filter_map(Result::ok)
        .filter_map(|e| {
            let name = e.file_name().into_string().ok()?;
            name.strip_prefix("policy")?.parse::<usize>().ok()?;
            Some(format!("{CPU_ROOT}/cpufreq/{name}"))
        })
        .collect();
    // 各 CPU 的别名节点也能兼容频率策略目录不完整的厂商内核。
    paths.extend(
        present(root)
            .iter()
            .map(|id| format!("{CPU_ROOT}/cpu{id}/cpufreq")),
    );
    let mut result: BTreeMap<Vec<usize>, Policy> = BTreeMap::new();
    for p in paths {
        let members = ["related_cpus", "affected_cpus"]
            .iter()
            .filter_map(|key| read(root, &format!("{p}/{key}")))
            .map(|s| ids(&s))
            .find(|v| !v.is_empty())
            .unwrap_or_default();
        if members.is_empty() {
            continue;
        }
        let max_khz = number(root, &format!("{p}/cpuinfo_max_freq"));
        let policy = result.entry(members.clone()).or_insert_with(|| Policy {
            paths: Vec::new(),
            members,
            max_khz: None,
        });
        policy.paths.push(p);
        policy.max_khz = policy.max_khz.or(max_khz);
    }
    result.into_values().collect()
}

pub fn present(root: &Path) -> Vec<usize> {
    for key in ["present", "possible"] {
        if let Some(text) = read(root, &format!("{CPU_ROOT}/{key}")) {
            let cpus = ids(&text);
            if !cpus.is_empty() {
                return cpus;
            }
        }
    }
    let mut cpus: Vec<_> = fs::read_dir(path(root, CPU_ROOT))
        .into_iter()
        .flatten()
        .take(8192)
        .filter_map(Result::ok)
        .filter_map(|e| {
            e.file_name()
                .to_str()?
                .strip_prefix("cpu")?
                .parse::<usize>()
                .ok()
        })
        .filter(|id| *id < 4096)
        .collect();
    cpus.sort_unstable();
    cpus.dedup();
    cpus
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Core {
    pub id: usize,
    pub capacity: Option<u64>,
    pub max_khz: Option<u64>,
    pub architecture: Option<u64>,
}

pub fn cores(root: &Path) -> Vec<Core> {
    let policies = policies(root);
    present(root)
        .into_iter()
        .map(|id| {
            let base = format!("{CPU_ROOT}/cpu{id}");
            let capacity =
                number(root, &format!("{base}/cpu_capacity")).filter(|v| *v <= 1_000_000);
            let max_khz = policies
                .iter()
                .find(|p| p.members.contains(&id))
                .and_then(|p| p.max_khz)
                .or_else(|| number(root, &format!("{base}/cpufreq/cpuinfo_max_freq")));
            let architecture = read(root, &format!("{base}/regs/identification/midr_el1"))
                .and_then(|s| u64::from_str_radix(s.trim().trim_start_matches("0x"), 16).ok())
                .map(|midr| midr & 0xff00_fff0); // 保留实现厂商、架构和部件编号；忽略修订版本与变体
            Core {
                id,
                capacity,
                max_khz,
                architecture,
            }
        })
        .collect()
}

/// 根据确切硬件信息分组，保留架构差异。设置卡片若需展示能力相当的核心，
/// 应调用 `performance_groups`。
pub fn groups(cores: &[Core]) -> Vec<Vec<usize>> {
    let mut result = BTreeMap::new();
    for c in cores {
        // 缺少依据时，不能默认所有 CPU 能力相同。
        let unknown = (c.capacity.is_none() && c.max_khz.is_none() && c.architecture.is_none())
            .then_some(c.id);
        result
            .entry((c.capacity, c.max_khz, c.architecture, unknown))
            .or_insert_with(Vec::new)
            .push(c.id);
    }
    result.into_values().collect()
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PerformanceGroups {
    /// 信息完整时，按容量和硬件最高频率从弱到强排序。
    /// 信息不完整时，按 CPU 编号排序，不据此推断性能高低。
    pub groups: Vec<Vec<usize>>,
    /// 所有现有 CPU 是否都具备容量和硬件最高频率信息。
    /// 为 false 时，分组数量不能视为已确认的性能档位数。
    pub complete: bool,
}

/// 按测得的硬件能力分组，不依据型号名称、MIDR 编号顺序或频率策略归属。
/// 此视图不会改变调度器使用的各 CPU 硬件信息。
pub fn performance_groups(cores: &[Core]) -> PerformanceGroups {
    let mut by_id = BTreeMap::new();
    let mut complete = !cores.is_empty();
    for core in cores {
        if by_id.insert(core.id, core).is_some() {
            complete = false;
        }
    }
    let mut grouped = BTreeMap::new();
    for core in by_id.values() {
        let capacity = core.capacity.filter(|value| (1..=1_000_000).contains(value));
        let maximum = core.max_khz.filter(|value| *value > 0);
        let full = capacity.is_some() && maximum.is_some();
        complete &= full;
        // 能力信息完整且相同时，会有意将 A710/A715 同等核心合并。
        // 缺少这些信息时，仅凭频率不足以认定性能相当。
        let architecture = if full { None } else { core.architecture };
        let enough_partial = architecture.is_some() && (capacity.is_some() || maximum.is_some());
        let unknown = (!full && !enough_partial).then_some(core.id);
        grouped.entry((capacity, maximum, architecture, unknown))
            .or_insert_with(Vec::new).push(core.id);
    }
    // BTreeMap 对完整键先按容量、再按硬件最高频率遍历。
    // MIDR 编号不参与性能排序；信息不完整时只按 CPU 编号排序，
    // 因为缺少容量信息时，无法仅凭频率比较不同架构的性能。
    let mut groups: Vec<Vec<usize>> = grouped.into_values().collect();
    if !complete {
        groups.sort_by_key(|members| members[0]);
    }
    PerformanceGroups { groups, complete }
}

#[cfg(test)]
#[path = "cpu_performance_tests.rs"]
mod performance_tests;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn groups_are_disjoint_and_support_two_three_four_and_unknown_tiers() {
        for capacities in [
            vec![313, 313, 313, 313, 777, 777, 777, 1024],
            vec![800, 800, 800, 800, 800, 800, 1024, 1024],
            vec![300, 300, 600, 600, 800, 800, 1024, 1024],
        ] {
            let input: Vec<_> = capacities
                .iter()
                .enumerate()
                .map(|(id, &c)| Core {
                    id,
                    capacity: Some(c),
                    max_khz: Some(c * 1000),
                    architecture: None,
                })
                .collect();
            let groups = groups(&input);
            assert_eq!(
                groups.len(),
                capacities
                    .iter()
                    .collect::<std::collections::BTreeSet<_>>()
                    .len()
            );
            let mut flattened: Vec<_> = groups.into_iter().flatten().collect();
            flattened.sort();
            assert_eq!(flattened, (0..8).collect::<Vec<_>>());
        }
        let unknown: Vec<_> = (0..2)
            .map(|id| Core {
                id,
                capacity: None,
                max_khz: None,
                architecture: None,
            })
            .collect();
        assert_eq!(groups(&unknown).len(), 2);
    }
    #[test]
    fn lists_accept_policy_whitespace_without_guessing_on_invalid_input() {
        assert_eq!(ids("0-3 6,7"), vec![0, 1, 2, 3, 6, 7]);
        assert!(ids("0-999999999").is_empty());
        assert!(ids("3-0").is_empty());
        assert_eq!(format_ids(&[7, 0, 1, 3, 7]), "0-1,3,7");
    }

    pub(super) struct Fixture(pub(super) PathBuf);
    impl Fixture {
        pub(super) fn new() -> Self {
            static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
            Self(std::env::temp_dir().join(format!(
                "qixia-topology-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            )))
        }
        pub(super) fn put(&self, relative: &str, text: &str) {
            let file = path(&self.0, &format!("{CPU_ROOT}/{relative}"));
            fs::create_dir_all(file.parent().unwrap()).unwrap();
            fs::write(file, text).unwrap();
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn incomplete_policy_keeps_aliases_and_hardware_maximum() {
        let fs = Fixture::new();
        fs.put("present", "0-1");
        fs.put("cpufreq/policy0/related_cpus", "0 1");
        fs.put("cpu0/cpufreq/affected_cpus", "0-1");
        fs.put("cpu0/cpufreq/cpuinfo_max_freq", "2400000");
        let policy = policies(&fs.0);
        assert_eq!(policy.len(), 1);
        assert_eq!(policy[0].members, vec![0, 1]);
        assert_eq!(policy[0].paths.len(), 2);
        assert_eq!(policy[0].max_khz, Some(2400000));
        assert!(cores(&fs.0)
            .iter()
            .all(|c| c.max_khz == Some(2400000) && c.capacity.is_none()));
    }

    #[test]
    fn core_groups_keep_architectures_but_ignore_midr_revisions() {
        let fs = Fixture::new();
        fs.put("present", "0-3");
        for (id, midr) in ["0x410fd050", "0x411fd051", "0x410fd0b0", "0x410fd0b1"]
            .iter()
            .enumerate()
        {
            fs.put(&format!("cpu{id}/regs/identification/midr_el1"), midr);
            fs.put(&format!("cpu{id}/cpufreq/cpuinfo_max_freq"), "2000000");
        }
        assert_eq!(groups(&cores(&fs.0)), vec![vec![0, 1], vec![2, 3]]);
        assert!(cores(&Fixture::new().0).is_empty());
    }

    #[test]
    fn ten_cores_can_span_non_contiguous_domains_without_duplicates() {
        let input: Vec<_> = (0..10)
            .map(|id| Core {
                id,
                capacity: Some(if id < 4 {
                    300
                } else if id < 8 {
                    700
                } else {
                    1024
                }),
                max_khz: None,
                architecture: None,
            })
            .collect();
        assert_eq!(
            groups(&input),
            vec![vec![0, 1, 2, 3], vec![4, 5, 6, 7], vec![8, 9]]
        );
    }
}
