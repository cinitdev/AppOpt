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
    domains: Vec<String>,
    pub(super) domains_complete: bool,
    pub(super) complete: bool,
}
impl CpuTiers {
    pub(super) fn detect() -> Self {
        let root = Path::new("/");
        Self::from_cores(&qixia_kernel_info::cpu::cores(root), &qixia_kernel_info::cpu::policies(root))
    }

    fn from_cores(cores: &[qixia_kernel_info::cpu::Core], policies: &[qixia_kernel_info::cpu::Policy]) -> Self {
        use qixia_kernel_info::cpu::{format_ids, performance_groups, frequency_domains};
        let detected = performance_groups(cores);
        let frequency = frequency_domains(cores, policies);
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
            domains: frequency.domains.iter().map(|ids| format_ids(ids)).collect(),
            domains_complete: frequency.complete,
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
    block.push_str(&format!("detected_freq_complete={}\ndetected_freq_domains={}\n",
        u8::from(topo.domains_complete), topo.domains.len()));
    for (index, cpus) in topo.domains.iter().enumerate() {
        block.push_str(&format!("detected_freq_domain_{index}={cpus}\n"));
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
