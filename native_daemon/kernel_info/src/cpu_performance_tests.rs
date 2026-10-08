use super::*;

fn cluster(
    first: usize,
    count: usize,
    capacity: u64,
    maximum: u64,
    architecture: u64,
) -> Vec<Core> {
    (first..first + count)
        .map(|id| Core {
            id,
            capacity: Some(capacity),
            max_khz: Some(maximum),
            architecture: Some(architecture),
        })
        .collect()
}

#[test]
fn k70_groups_a715_and_a710_mid_cores_by_their_equal_capability() {
    let mut cores = cluster(0, 3, 427, 2_016_000, 0x4100_d460);
    cores.extend(cluster(3, 2, 855, 2_803_200, 0x4100_d4d0));
    cores.extend(cluster(5, 2, 855, 2_803_200, 0x4100_d470));
    cores.extend(cluster(7, 1, 1024, 3_187_200, 0x4100_d4e0));
    let result = performance_groups(&cores);
    assert_eq!(
        result.groups,
        vec![vec![0, 1, 2], vec![3, 4, 5, 6], vec![7]]
    );
    assert!(result.complete);
    // 其他调用方仍可通过旧 API 区分架构。
    assert_eq!(groups(&cores).len(), 4);
}

#[test]
fn three_tier_870_and_same_midr_two_tier_oryon_do_not_depend_on_cpu_names() {
    let mut three = cluster(0, 4, 256, 1_804_800, 0x4100_d050);
    three.extend(cluster(4, 3, 768, 2_419_200, 0x4100_d0b0));
    three.extend(cluster(7, 1, 1024, 3_187_200, 0x4100_d0b0));
    assert_eq!(
        performance_groups(&three).groups,
        vec![vec![0, 1, 2, 3], vec![4, 5, 6], vec![7]]
    );
    let mut two = cluster(0, 6, 780, 3_530_000, 0x5100_0010);
    two.extend(cluster(6, 2, 1024, 4_320_000, 0x5100_0010));
    assert_eq!(
        performance_groups(&two).groups,
        vec![vec![0, 1, 2, 3, 4, 5], vec![6, 7]]
    );
    assert!(performance_groups(&two).complete);
    // 即使容量值相同，也要保留已确认的硬件最高频率档位。
    for core in &mut two {
        core.capacity = Some(1024);
    }
    assert_eq!(
        performance_groups(&two).groups,
        vec![vec![0, 1, 2, 3, 4, 5], vec![6, 7]]
    );
}

#[test]
fn generic_four_tiers_and_ten_core_three_tiers_keep_every_present_cpu() {
    let mut four = cluster(0, 2, 250, 1_800_000, 0x4100_d050);
    four.extend(cluster(2, 2, 600, 2_300_000, 0x4100_d470));
    four.extend(cluster(4, 3, 790, 2_800_000, 0x4100_d470));
    four.extend(cluster(7, 1, 1024, 3_300_000, 0x4100_d4e0));
    assert_eq!(
        performance_groups(&four).groups,
        vec![vec![0, 1], vec![2, 3], vec![4, 5, 6], vec![7]]
    );
    let mut ten = cluster(0, 4, 300, 1_800_000, 0x4100_d050);
    ten.extend(cluster(4, 4, 700, 2_400_000, 0x4100_d470));
    ten.extend(cluster(8, 2, 1024, 3_200_000, 0x4100_d4e0));
    assert_eq!(
        performance_groups(&ten).groups,
        vec![vec![0, 1, 2, 3], vec![4, 5, 6, 7], vec![8, 9]]
    );
}

#[test]
fn performance_sort_uses_capacity_not_cpu_id_or_midr_part_numbers() {
    let mut cores = cluster(0, 1, 1024, 3_000_000, 1);
    cores.extend(cluster(1, 2, 250, 2_000_000, u32::MAX as u64));
    cores.extend(cluster(3, 1, 700, 2_500_000, 10));
    cores.reverse();
    assert_eq!(
        performance_groups(&cores).groups,
        vec![vec![1, 2], vec![3], vec![0]]
    );
    // 容量和最高频率均已确认相同的核心，不会因缺少 MIDR 而拆组。
    cores.iter_mut().for_each(|core| core.architecture = None);
    assert_eq!(
        performance_groups(&cores).groups,
        vec![vec![1, 2], vec![3], vec![0]]
    );
    assert!(performance_groups(&cores).complete);
}

#[test]
fn incomplete_capacity_retains_architecture_and_never_ranks_by_midr() {
    let mut cores = cluster(0, 2, 700, 2_800_000, 0x4100_d4d0);
    cores.extend(cluster(2, 2, 700, 2_800_000, 0x4100_d470));
    for core in &mut cores {
        core.capacity = None;
    }
    let result = performance_groups(&cores);
    assert!(!result.complete);
    // A715 的部件编号更大，不代表应排在 A710 后面，也不能据此合并两者。
    assert_eq!(result.groups, vec![vec![0, 1], vec![2, 3]]);
    cores[1].architecture = None;
    let result = performance_groups(&cores);
    assert!(!result.complete);
    assert_eq!(result.groups, vec![vec![0], vec![1], vec![2, 3]]);
}

#[test]
fn insufficient_single_field_evidence_stays_incomplete_without_asserting_uniform_hardware() {
    let unknown: Vec<_> = (0..8)
        .map(|id| Core {
            id,
            capacity: None,
            max_khz: None,
            architecture: None,
        })
        .collect();
    for evidence in 0..4 {
        let mut cores = unknown.clone();
        for core in &mut cores {
            match evidence {
                1 => core.architecture = Some(0x5100_0010),
                2 => core.max_khz = Some(2_000_000),
                3 => core.capacity = Some(700),
                _ => {}
            }
        }
        let result = performance_groups(&cores);
        assert!(!result.complete);
        assert_eq!(result.groups, (0..8).map(|id| vec![id]).collect::<Vec<_>>());
    }
    assert_eq!(
        performance_groups(&[]),
        PerformanceGroups {
            groups: vec![],
            complete: false
        }
    );
}

#[test]
fn duplicate_ids_and_zero_capacity_are_not_complete_evidence() {
    let mut cores = cluster(0, 2, 700, 2_800_000, 0x4100_d470);
    cores.push(cores[0].clone());
    let result = performance_groups(&cores);
    assert!(!result.complete);
    assert_eq!(result.groups, vec![vec![0, 1]]);
    cores.pop();
    cores[0].capacity = Some(0);
    assert!(!performance_groups(&cores).complete);
}

#[test]
fn separate_policies_offline_cores_and_dynamic_frequency_changes_do_not_split_peers() {
    let fs = super::tests::Fixture::new();
    fs.put("present", "0-7");
    fs.put("online", "0-3,7");
    for (first, count, cap, max, midr) in [
        (0, 3, 427, 2_016_000, "0x410fd460"),
        (3, 2, 855, 2_803_200, "0x410fd4d0"),
        (5, 2, 855, 2_803_200, "0x410fd470"),
        (7, 1, 1024, 3_187_200, "0x410fd4e0"),
    ] {
        // 厂商的频率策略节点与各 CPU 的别名节点可能使用不同路径。
        fs.put(
            &format!("cpufreq/policy{first}/related_cpus"),
            &format_ids(&(first..first + count).collect::<Vec<_>>()),
        );
        fs.put(
            &format!("cpufreq/policy{first}/cpuinfo_max_freq"),
            &max.to_string(),
        );
        for id in first..first + count {
            fs.put(&format!("cpu{id}/cpu_capacity"), &cap.to_string());
            fs.put(&format!("cpu{id}/regs/identification/midr_el1"), midr);
            fs.put(&format!("cpu{id}/cpufreq/scaling_cur_freq"), "300000");
            fs.put(&format!("cpu{id}/cpufreq/scaling_max_freq"), "800000");
        }
    }
    let before = performance_groups(&cores(&fs.0));
    assert_eq!(
        before.groups,
        vec![vec![0, 1, 2], vec![3, 4, 5, 6], vec![7]]
    );
    assert!(before.complete);
    fs.put("online", "0-7");
    fs.put("cpu4/cpufreq/scaling_cur_freq", "2803200");
    fs.put("cpu4/cpufreq/scaling_max_freq", "2803200");
    fs.put("cpufreq/policy5/scaling_cur_freq", "300000");
    fs.put("cpufreq/policy5/scaling_max_freq", "400000");
    assert_eq!(performance_groups(&cores(&fs.0)), before);
}

#[test]
fn dynamic_frequency_without_hardware_maximum_is_not_complete_evidence() {
    let fs = super::tests::Fixture::new();
    fs.put("present", "0-1");
    for id in 0..2 {
        fs.put(&format!("cpu{id}/cpu_capacity"), "700");
        fs.put(&format!("cpu{id}/cpufreq/scaling_cur_freq"), "2400000");
        fs.put(&format!("cpu{id}/cpufreq/scaling_max_freq"), "2400000");
    }
    let cores = cores(&fs.0);
    assert!(cores.iter().all(|core| core.max_khz.is_none()));
    assert!(!performance_groups(&cores).complete);
}
