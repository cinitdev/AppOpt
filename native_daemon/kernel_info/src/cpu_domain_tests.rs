use super::*;
use super::tests::Fixture;

fn fixture(groups: &[(usize, usize, u64, u64)]) -> Fixture {
    let fs = Fixture::new();
    let count: usize = groups.iter().map(|(_, count, _, _)| count).sum();
    fs.put("present", &format!("0-{}", count - 1));
    for &(first, count, capacity, maximum) in groups {
        fs.put(&format!("cpufreq/policy{first}/related_cpus"),
            &format_ids(&(first..first + count).collect::<Vec<_>>()));
        fs.put(&format!("cpufreq/policy{first}/cpuinfo_max_freq"), &maximum.to_string());
        for id in first..first + count {
            fs.put(&format!("cpu{id}/cpu_capacity"), &capacity.to_string());
        }
    }
    fs
}

#[test]
fn dimensity_8100_keeps_three_domains_inside_two_performance_groups() {
    let fs = fixture(&[(0, 4, 274, 2_000_000), (4, 3, 1024, 2_850_000), (7, 1, 1024, 2_850_000)]);
    let cores = cores(&fs.0);
    let result = frequency_domains(&cores, &policies(&fs.0));
    assert!(result.complete);
    assert_eq!(result.domains, [vec![0, 1, 2, 3], vec![4, 5, 6], vec![7]]);
    assert_eq!(performance_groups(&cores).groups, [vec![0, 1, 2, 3], vec![4, 5, 6, 7]]);
    // 离线和当前限频不改变真实调频域，也不凭当前频率拆性能组。
    fs.put("online", "0-3,7");
    fs.put("cpufreq/policy4/affected_cpus", "");
    fs.put("cpufreq/policy7/scaling_max_freq", "1700000");
    fs.put("cpufreq/policy4/scaling_max_freq", "1600000");
    assert_eq!(frequency_domains(&cores, &policies(&fs.0)), result);
    fs.put("cpu7/cpufreq/related_cpus", "7");
    assert_eq!(frequency_domains(&cores, &policies(&fs.0)), result);
}

#[test]
fn different_soc_layouts_never_assume_eight_cpus_or_three_domains() {
    for layout in [
        vec![(0, 6, 780, 3_530_000), (6, 2, 1024, 4_320_000)],
        vec![(0, 3, 427, 2_016_000), (3, 2, 855, 2_803_200), (5, 2, 855, 2_803_200), (7, 1, 1024, 3_187_200)],
        vec![(0, 4, 300, 1_800_000), (4, 4, 700, 2_400_000), (8, 2, 1024, 3_200_000)],
    ] {
        let fs = fixture(&layout);
        let result = frequency_domains(&cores(&fs.0), &policies(&fs.0));
        assert!(result.complete);
        assert_eq!(result.domains.len(), layout.len());
        assert_eq!(result.domains.iter().flatten().count(), layout.iter().map(|x| x.1).sum());
    }
}

#[test]
fn missing_overlapping_foreign_and_affected_only_members_are_not_complete() {
    let fs = fixture(&[(0, 4, 274, 2_000_000), (4, 4, 1024, 2_850_000)]);
    let cores = cores(&fs.0);
    let original = policies(&fs.0);
    for members in [vec![4, 5, 6], vec![3, 4, 5, 6, 7], vec![4, 5, 6, 7, 8], vec![]] {
        let mut changed = original.clone();
        changed[1].members = members;
        assert!(!frequency_domains(&cores, &changed).complete);
    }
    fs.put("cpufreq/policy4/related_cpus", "");
    fs.put("cpufreq/policy4/affected_cpus", "4-7");
    assert!(!frequency_domains(&cores, &policies(&fs.0)).complete);
    // 稍后完整节点出现时可以恢复，不能永久保留首次读取的残缺结果。
    fs.put("cpu4/cpufreq/related_cpus", "4-7");
    assert!(frequency_domains(&cores, &policies(&fs.0)).complete);
    assert!(!frequency_domains(&[], &[]).complete);
    assert!(!frequency_domains(&cores, &[]).complete);
}

#[test]
fn sparse_members_and_duplicate_aliases_keep_domain_identity() {
    let fs = Fixture::new();
    fs.put("present", "0,2,6,9");
    fs.put("cpufreq/policy0/related_cpus", "0 6");
    fs.put("cpufreq/policy2/related_cpus", "2 9");
    fs.put("cpu6/cpufreq/related_cpus", "6 0");
    let result = frequency_domains(&cores(&fs.0), &policies(&fs.0));
    assert!(result.complete);
    assert_eq!(result.domains, [vec![0, 6], vec![2, 9]]);
    // 缺少能力信息不妨碍确认调频域，两种完整性独立判断。
    assert!(!performance_groups(&cores(&fs.0)).complete);
}
