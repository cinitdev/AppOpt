// CPU 拓扑识别。
//
// 这里根据 /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 分簇：
// - 频率最低的一簇视为 low。
// - 最后一簇视为 highest。
// - 三簇以上时，中间簇作为 mid/high 的来源。
//
// 这个结果会写到 calib_policy.conf 的 detected_* 区块，App 设置页也会读取这些默认核心范围。
enum RuleTier {
    High,
    Mid,
}

struct CpuTiers {
    clusters: usize,
    low: String,
    highest: String,
    high: String,
    mid: String,
    fallback: String,
    all: String,
    cpu_count: usize,
}

impl CpuTiers {
    fn detect() -> Self {
        // 按 cpuinfo_max_freq 分簇，保持和 C 版/设置页自动核心分配一致。
        let present = present_cpus();
        let count = present.last().copied().unwrap_or(0) + 1;
        let clusters = cpu_clusters(&present);
        let all = format_cpu_list(&present).unwrap_or_else(|| "0".to_string());

        if clusters.len() <= 1 {
            return Self {
                clusters: clusters.len().max(1),
                low: all.clone(),
                highest: all.clone(),
                high: all.clone(),
                mid: all.clone(),
                fallback: all.clone(),
                all,
                cpu_count: count.max(1),
            };
        }

        let low = format_cpu_list(&clusters[0].cpus).unwrap_or_else(|| all.clone());
        let highest =
            format_cpu_list(&clusters[clusters.len() - 1].cpus).unwrap_or_else(|| all.clone());
        let mut mid_cpus = Vec::new();
        if clusters.len() >= 3 {
            for cluster in &clusters[1..clusters.len() - 1] {
                mid_cpus.extend(cluster.cpus.iter().copied());
            }
        }
        if mid_cpus.is_empty() {
            mid_cpus.extend(clusters[0].cpus.iter().copied());
        }
        let mid = format_cpu_list(&mid_cpus).unwrap_or_else(|| low.clone());

        let high = if clusters.len() >= 3 {
            format_cpu_list(&clusters[clusters.len() - 2].cpus).unwrap_or_else(|| mid.clone())
        } else {
            low.clone()
        };

        let mut fallback_cpus = Vec::new();
        for cluster in &clusters[..clusters.len() - 1] {
            fallback_cpus.extend(cluster.cpus.iter().copied());
        }
        let fallback = format_cpu_list(&fallback_cpus).unwrap_or_else(|| all.clone());

        Self {
            clusters: clusters.len(),
            low,
            highest,
            high,
            mid,
            fallback,
            all,
            cpu_count: count.max(1),
        }
    }
}

struct CpuCluster {
    max_freq: u64,
    cpus: Vec<usize>,
}

fn cpu_clusters(present: &[usize]) -> Vec<CpuCluster> {
    if present.is_empty() {
        return vec![CpuCluster {
            max_freq: 0,
            cpus: vec![0],
        }];
    }

    let mut raw = Vec::new();
    let mut index = 0usize;
    while index < present.len() {
        let first = present[index];
        let freq = cpu_max_freq(first);
        let mut cpus = vec![first];
        index += 1;
        while index < present.len()
            && present[index] == cpus.last().copied().unwrap_or(first) + 1
            && cpu_max_freq(present[index]) == freq
        {
            cpus.push(present[index]);
            index += 1;
        }
        raw.push(CpuCluster {
            max_freq: freq,
            cpus,
        });
    }
    raw.sort_by_key(|cluster| cluster.max_freq);
    raw
}

fn cpu_max_freq(cpu: usize) -> u64 {
    fs::read_to_string(format!(
        "/sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq"
    ))
    .ok()
    .and_then(|text| text.trim().parse::<u64>().ok())
    .unwrap_or(0)
}

fn log_detected_topology(topo: &CpuTiers) {
    println!(
        "[RS] CPU 拓扑识别: {} 个性能簇, 全部=[{}] 低性能=[{}] 主性能=[{}] 高性能=[{}] 最高性能=[{}] 非最高=[{}]",
        topo.clusters,
        topo.all,
        topo.low,
        topo.mid,
        topo.high,
        topo.highest,
        topo.fallback
    );
}

fn sync_policy_topology(topo: &CpuTiers) {
    // 设置页读取 detected_* 作为默认核心建议；这里用锁和整块替换避免半写入。
    let _lock = match PolicyLock::acquire() {
        Some(lock) => lock,
        None => return,
    };
    let Ok(old) = fs::read_to_string(CALIB_POLICY_FILE) else {
        return;
    };

    let mut cleaned = Vec::new();
    let mut in_block = false;
    for raw in old.lines() {
        let line = raw.trim();
        if line == CALIB_TOPO_BEGIN {
            in_block = true;
            continue;
        }
        if in_block {
            if line == CALIB_TOPO_END {
                in_block = false;
            }
            continue;
        }
        if line.starts_with("detected_") || line.starts_with("# CPU 拓扑识别:") {
            continue;
        }
        cleaned.push(raw);
    }

    let mut next = cleaned.join("\n").trim_end().to_string();
    if !next.is_empty() {
        next.push('\n');
    }
    let block = format!(
        "\n{CALIB_TOPO_BEGIN}\n\
         # CPU 拓扑识别: {} 个性能簇, 全部=[{}] 低性能=[{}] 主性能=[{}] 高性能=[{}] 最高性能=[{}] 非最高=[{}]\n\
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
    next.push_str(&block);

    if next == old {
        return;
    }

    let tmp = PathBuf::from(format!("{CALIB_POLICY_FILE}.rust.tmp"));
    if let Err(err) = fs::write(&tmp, next).and_then(|_| fs::rename(&tmp, CALIB_POLICY_FILE)) {
        let _ = fs::remove_file(&tmp);
        eprintln!("[CALIB] CPU 拓扑写入校准策略失败: {err}");
    } else {
        println!("[CALIB] CPU 拓扑已写入校准策略");
    }
}
