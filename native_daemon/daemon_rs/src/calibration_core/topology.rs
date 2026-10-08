use super::*;
// 设置页的拓扑元数据与核心分配使用相同的硬件识别依据。
// 汇总核心范围供诊断使用；group_N 描述互不重叠的性能组，
// 只有 complete 为 true 时才表示分组已完整确认。
pub(super) struct CpuTiers {
    clusters: usize,
    low: String,
    highest: String,
    high: String,
    mid: String,
    fallback: String,
    all: String,
    groups: Vec<String>,
    pub(super) complete: bool,
}
impl CpuTiers {
    pub(super) fn detect() -> Self {
        Self::from_cores(&qixia_kernel_info::cpu::cores(Path::new("/")))
    }

    fn from_cores(cores: &[qixia_kernel_info::cpu::Core]) -> Self {
        use qixia_kernel_info::cpu::{format_ids, performance_groups};
        let detected = performance_groups(cores);
        let groups: Vec<_> = detected.groups.iter().map(|ids| format_ids(ids)).collect();
        let all = format_ids(&cores.iter().map(|c| c.id).collect::<Vec<_>>());
        let low = groups.first().cloned().unwrap_or_default();
        let highest = groups.last().cloned().unwrap_or_default();
        let high = groups
            .get(groups.len().saturating_sub(2))
            .cloned()
            .unwrap_or_else(|| low.clone());
        let mid = if groups.len() >= 3 {
            format_ids(&detected.groups[1..groups.len() - 1].iter().flatten().copied().collect::<Vec<_>>())
        } else {
            low.clone()
        };
        let fallback = if groups.len() > 1 {
            format_ids(&detected.groups[..groups.len() - 1].iter().flatten().copied().collect::<Vec<_>>())
        } else {
            all.clone()
        };
        Self {
            clusters: groups.len(),
            low,
            highest,
            high,
            mid,
            fallback,
            all,
            groups,
            complete: detected.complete,
        }
    }
}
pub(super) fn sync_policy_topology(topo: &CpuTiers) -> bool {
    // 设置页读取 detected_* 展示性能分组；用锁和整块替换避免半写入。
    let _lock = match PolicyLock::try_acquire() {
        Some(lock) => lock,
        None => return false,
    };
    sync_policy_topology_at(Path::new(CALIB_POLICY_FILE), topo)
}

fn sync_policy_topology_at(path: &Path, topo: &CpuTiers) -> bool {
    let old = match fs::read_to_string(path) {
        Ok(old) => old,
        Err(err) if err.kind() == io::ErrorKind::NotFound => String::new(),
        Err(_) => return false,
    };
    let Some(next) = policy_with_topology(&old, topo) else {
        log_error!("[CALIB] 检测到未闭合的 CPU 拓扑区块，已保留 calib_policy.conf 原内容");
        return true;
    };
    if next == old { return true; }
    let tmp = path.with_extension("conf.rust.tmp");
    if let Err(err) = fs::write(&tmp, next).and_then(|_| fs::rename(&tmp, path)) {
        let _ = fs::remove_file(&tmp);
        log_error!("[CALIB] CPU 拓扑写入校准策略失败: {err}");
        false
    } else {
        log_info!("[CALIB] CPU 拓扑已写入校准策略");
        true
    }
}

fn policy_with_topology(old: &str, topo: &CpuTiers) -> Option<String> {
    let cleaned = policy_without_generated_topology(old)?;
    // 较早版本的应用保存设置时会遗漏运行参数；在同一把锁内只补齐缺失项，
    // 绝不重置用户已经设置的开关。
    let mut next = policy_with_runtime_defaults(&cleaned).trim_end().to_string();
    if !next.is_empty() {
        next.push('\n');
    }
    let block = format!(
        "\n{CALIB_TOPO_BEGIN}\n\
         # CPU 拓扑识别: {} 个性能组, 全部=[{}] 低性能=[{}] 主性能=[{}] 高性能=[{}] 最高性能=[{}] 非最高=[{}]\n\
         detected_clusters={}\n\
         detected_low={}\n\
         detected_main={}\n\
         detected_high={}\n\
         detected_non_top={}\n\
         detected_top={}\n\
         detected_all={}\n\
         {CALIB_TOPO_END}\n",
        topo.clusters,
        topo.all,
        topo.low,
        topo.mid,
        topo.high,
        topo.highest,
        topo.fallback,
        topo.clusters,
        topo.low,
        topo.mid,
        topo.high,
        topo.fallback,
        topo.highest,
        topo.all
    );
    let mut block = block.replace(CALIB_TOPO_END, "");
    block.push_str(&format!("detected_complete={}\n", u8::from(topo.complete)));
    for (index, cpus) in topo.groups.iter().enumerate() {
        block.push_str(&format!("detected_group_{index}={cpus}\n"));
    }
    block.push_str(CALIB_TOPO_END);
    block.push('\n');
    next.push_str(&block);

    Some(next)
}

fn policy_with_runtime_defaults(old: &str) -> String {
    let mut next = old.to_owned();
    for (key, value) in [
        ("keep_all_cores_online", "0"),
        ("auto_history_version", "1"),
        ("auto_history_enabled", "0"),
    ] {
        let present = old.lines().any(|line| {
            line.split('#').next().unwrap_or_default().split_once('=')
                .is_some_and(|(name, _)| name.trim() == key)
        });
        if !present {
            if !next.is_empty() && !next.ends_with('\n') { next.push('\n'); }
            next.push_str(&format!("{key}={value}\n"));
        }
    }
    next
}

pub(super) fn policy_without_generated_topology(old: &str) -> Option<String> {
    let mut cleaned = Vec::new();
    let mut block_end = None;
    for raw in old.lines() {
        let line = raw.trim();
        let end = match line {
            CALIB_TOPO_BEGIN => Some(CALIB_TOPO_END),
            _ => None,
        };
        if let Some(end) = end {
            if block_end.is_some() {
                return None;
            }
            block_end = Some(end);
            continue;
        }
        if let Some(end) = block_end {
            if line == end {
                block_end = None;
            }
            continue;
        }
        if line.starts_with("detected_") || line.starts_with("# CPU 拓扑识别:") {
            continue;
        }
        cleaned.push(raw);
    }
    block_end.is_none().then(|| cleaned.join("\n"))
}

#[cfg(test)]
mod topology_policy_tests {
    use super::*;

    #[test]
    fn k70_metadata_combines_equal_performance_architectures_and_replaces_old_groups() {
        use qixia_kernel_info::cpu::Core;
        let cores: Vec<_> = (0..8).map(|id| {
            let (capacity, max_khz, architecture) = match id {
                0..=2 => (280, 2_016_000, 0x4100_d460),
                3..=4 => (855, 2_803_200, 0x4100_d4d0),
                5..=6 => (855, 2_803_200, 0x4100_d470),
                _ => (1024, 3_187_200, 0x4100_d4e0),
            };
            Core { id, capacity: Some(capacity), max_khz: Some(max_khz), architecture: Some(architecture) }
        }).collect();
        let topo = CpuTiers::from_cores(&cores);
        assert!(topo.complete);
        assert_eq!(topo.groups, ["0-2", "3-6", "7"]);
        assert_eq!(topo.mid, "3-6");
        assert_eq!(topo.high, "3-6");
        assert_eq!(topo.fallback, "0-6");
        let old = format!("auto_history_enabled=1\n{CALIB_TOPO_BEGIN}\ndetected_clusters=4\ndetected_group_0=0-2\ndetected_group_1=5-6\ndetected_group_2=3-4\ndetected_group_3=7\n{CALIB_TOPO_END}\n");
        let next = policy_with_topology(&old, &topo).unwrap();
        assert!(next.contains("auto_history_enabled=1\n"));
        assert!(next.contains("detected_clusters=3\n"));
        assert!(next.contains("detected_group_1=3-6\n"));
        assert!(!next.contains("detected_group_3="));
        assert_eq!(policy_with_topology(&next, &topo).unwrap(), next);
    }

    #[test]
    fn same_architecture_two_tiers_and_non_eight_core_variants_stay_distinct() {
        use qixia_kernel_info::cpu::Core;
        for count in [7, 8] {
            let mut cores: Vec<_> = (0..count).map(|id| Core {
                id,
                capacity: Some(if id < count - 2 { 800 } else { 1024 }),
                max_khz: Some(if id < count - 2 { 3_600_000 } else { 4_600_000 }),
                architecture: Some(0x5100_0010),
            }).collect();
            let topo = CpuTiers::from_cores(&cores);
            assert!(topo.complete);
            assert_eq!(topo.groups.len(), 2);
            assert_eq!(topo.groups[1], format!("{}-{}", count - 2, count - 1));
            cores[0].capacity = None;
            let partial = policy_with_topology("", &CpuTiers::from_cores(&cores)).unwrap();
            assert!(partial.contains("detected_complete=0\n"));
        }
    }

    fn two_cluster_topology() -> CpuTiers {
        CpuTiers { clusters: 2, low: "0-5".into(), highest: "6-7".into(),
            high: "0-5".into(), mid: "0-5".into(), fallback: "0-5".into(),
            all: "0-7".into(), groups: vec!["0-5".into(), "6-7".into()], complete: true }
    }

    #[test]
    fn repairs_missing_and_partial_metadata_without_resetting_user_settings() {
        let user = "version=2\ncpuset_name=Custom\nrule_output_format=yaml\nauto_history_enabled=1\nkeep_all_cores_online=1\n";
        let topo = two_cluster_topology();
        let repaired = policy_with_topology(user, &topo).unwrap();
        assert!(repaired.starts_with(user));
        assert!(repaired.contains("detected_clusters=2\n"));
        assert!(repaired.contains("detected_group_1=6-7\n"));
        assert!(!repaired.contains("detected_group_2="));
        assert_eq!(policy_with_topology(&repaired, &topo).unwrap(), repaired);
        let partial = repaired.replace("detected_group_1=6-7\n", "");
        assert_eq!(policy_with_topology(&partial, &topo).unwrap(), repaired);
    }

    #[test]
    fn deleted_policy_is_recreated_and_valid_policy_is_not_rewritten() {
        let dir = std::env::temp_dir().join(format!("qixia-topology-test-{}-{}",
            std::process::id(), SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));
        fs::create_dir(&dir).unwrap();
        let path = dir.join("calib_policy.conf");
        let topo = two_cluster_topology();
        assert!(sync_policy_topology_at(&path, &topo));
        let old = fs::read_to_string(&path).unwrap();
        let metadata = fs::metadata(&path).unwrap();
        assert!(sync_policy_topology_at(&path, &topo));
        assert_eq!(metadata.modified().unwrap(), fs::metadata(&path).unwrap().modified().unwrap());
        fs::write(&path, policy_without_generated_topology(&old).unwrap()).unwrap();
        assert!(sync_policy_topology_at(&path, &topo));
        assert_eq!(fs::read_to_string(&path).unwrap(), old);
        fs::remove_file(path).unwrap();
        fs::remove_dir(dir).unwrap();
    }

    #[test]
    fn missing_runtime_keys_are_repaired_once_with_recording_off() {
        let old = "version=2\ncpuset_name=Custom\nrule_output_format=legacy";
        let repaired = policy_with_runtime_defaults(old);
        assert_eq!(repaired, format!("{old}\nkeep_all_cores_online=0\nauto_history_version=1\nauto_history_enabled=0\n"));
        assert_eq!(policy_with_runtime_defaults(&repaired), repaired);
    }

    #[test]
    fn runtime_defaults_preserve_existing_switches_comments_and_unknown_fields() {
        let old = "keep_all_cores_online = 1 # user\nauto_history_enabled = 1\ncustom_setting=keep\n";
        let repaired = policy_with_runtime_defaults(old);
        assert_eq!(repaired, format!("{old}auto_history_version=1\n"));
        let unknown = "auto_history_version=2\nauto_history_enabled=1\nkeep_all_cores_online=1\n";
        assert_eq!(policy_with_runtime_defaults(unknown), unknown);
    }

    #[test]
    pub(super) fn closed_generated_block_is_removed_without_touching_surrounding_policy() {
        let input = format!(
            "version=1\n{CALIB_TOPO_BEGIN}\ndetected_all=0-7\n{CALIB_TOPO_END}\ncpuset_name=QiXiaRs\n"
        );
        assert_eq!(
            policy_without_generated_topology(&input).as_deref(),
            Some("version=1\ncpuset_name=QiXiaRs")
        );
    }

    #[test]
    pub(super) fn unterminated_generated_block_is_never_rewritten() {
        let input =
            format!("version=1\n{CALIB_TOPO_BEGIN}\ndetected_all=0-7\ncpuset_name=KeepMe\n");
        assert_eq!(policy_without_generated_topology(&input), None);
    }

}
