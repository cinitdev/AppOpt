// 校准策略读取。
//
// calib_policy.conf 允许用户覆盖阈值和核心范围，例如：
// - best_thread: avg/max/cores
// - group_high: avg/max/cores
// - group_mid: avg/max/cores
// - max_thread_rules
// - rule_output_format: 选择校准规则写回格式，执行语义不随排版改变
// - fallback
//
// 如果配置缺失或写 auto，就回到 CPU 拓扑自动推导。这里不要因为一行策略写错而中止校准，
// 错误项应该回退默认值，保证用户还能得到基础规则。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum WildcardGroup {
    MaxMember,
    Sum,
}

impl WildcardGroup {
    fn from_wire(value: &str) -> Self {
        if value.trim().eq_ignore_ascii_case("sum") {
            Self::Sum
        } else {
            Self::MaxMember
        }
    }

    fn wire(self) -> &'static str {
        match self {
            Self::MaxMember => "max_member",
            Self::Sum => "sum",
        }
    }
}

struct CalibPolicy {
    best_avg: f64,
    best_max: f64,
    best_cores: String,
    high_avg: f64,
    high_max: f64,
    high_cores: String,
    mid_avg: f64,
    mid_max: f64,
    mid_cores: String,
    max_thread_rules: usize,
    wildcard_group: WildcardGroup,
    rule_output_format: RuleOutputFormat,
    fallback_cores: String,
}

pub(crate) fn print_version_diagnostics(version: &str) {
    let topo = CpuTiers::detect();
    println!(
        "CPU 拓扑识别: {} 个性能簇:\n全部=[{}] 低性能=[{}] 主性能=[{}] 高性能=[{}] 最高性能=[{}] 非最高=[{}]",
        topo.clusters,
        topo.all,
        topo.low,
        topo.mid,
        topo.high,
        topo.highest,
        topo.fallback
    );

    sync_policy_topology(&topo);
    let policy_text = fs::read_to_string(CALIB_POLICY_FILE).ok();
    let policy = policy_text
        .as_deref()
        .map(|text| parse_policy_for_diagnostics(&topo, text))
        .unwrap_or_else(|| CalibPolicy::default(&topo));

    if let Ok(metadata) = fs::metadata(CALIB_POLICY_FILE) {
        let (sec, nsec) = metadata_mtime_parts(&metadata);
        println!(
            "[校准策略] 已读取配置文件: {} (size={}, mtime={}.{:09})",
            CALIB_POLICY_FILE,
            metadata.len(),
            sec,
            nsec
        );
    } else {
        println!(
            "[校准策略] 未读取到 {}, 使用内置默认策略",
            CALIB_POLICY_FILE
        );
    }

    println!(
        "[校准策略] CPU 拓扑: {} 个性能簇, 低性能={}, 主性能={}, 高性能={}, 最高性能={}, 非最高={}, 全部={}",
        topo.clusters,
        dash_if_empty(&topo.low),
        dash_if_empty(&topo.mid),
        dash_if_empty(&topo.high),
        dash_if_empty(&topo.highest),
        dash_if_empty(&topo.fallback),
        dash_if_empty(&topo.all)
    );
    print_policy_rule(
        "best_thread",
        "只挑主进程中负载最高且平均与峰值都达到阈值的 1 个线程生成第一条单独线程规则",
        policy.best_avg,
        policy.best_max,
        "最高性能核心",
        &policy.best_cores,
        policy_core_source(policy_text.as_deref(), "best_thread", &topo),
    );
    print_policy_rule(
        "group_high",
        "主进程较重线程或相似线程组平均与峰值都达到阈值后生成第二档线程规则",
        policy.high_avg,
        policy.high_max,
        "高性能核心",
        &policy.high_cores,
        policy_core_source(policy_text.as_deref(), "group_high", &topo),
    );
    print_policy_rule(
        "group_mid",
        "主进程中等负载线程或相似线程组平均与峰值都达到阈值后生成第三档线程规则",
        policy.mid_avg,
        policy.mid_max,
        "主性能核心",
        &policy.mid_cores,
        policy_core_source(policy_text.as_deref(), "group_mid", &topo),
    );
    println!(
        "[校准策略] child_process: 汇总子进程全部线程负载, 使用 group_high/group_mid 阈值生成进程级规则, 不参与 best_thread"
    );
    println!(
        "[校准策略] wildcard_group: {}; {}",
        policy.wildcard_group.wire(),
        if policy.wildcard_group == WildcardGroup::Sum {
            "通配线程组按组内成员平均负载相加后判断档位"
        } else {
            "通配线程组只按组内最高单线程平均负载判断, 避免低负载线程累加误升档"
        }
    );
    println!(
        "[校准策略] max_thread_rules: {}; 最多生成 {} 条线程级规则, 其余线程走包名兜底",
        policy.max_thread_rules,
        policy.max_thread_rules
    );
    println!(
        "[校准策略] rule_output_format: {}; 只改变规则写回格式, 不改变绑核效果",
        policy.rule_output_format.wire()
    );
    println!(
        "[校准策略] fallback: 没有单独线程规则的线程使用包名兜底; 档位=非最高性能核心; 核心={} ({})",
        policy.fallback_cores,
        fallback_core_source(policy_text.as_deref(), &topo)
    );
    println!("AppOpt 版本 {version}");
}

fn parse_policy_for_diagnostics(topo: &CpuTiers, text: &str) -> CalibPolicy {
    let mut policy = CalibPolicy::default(topo);
    for raw in text.lines() {
        let line = raw.split_once('#').map_or(raw, |(left, _)| left).trim();
        if line.is_empty() {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let key = key.trim();
        let value = value.trim();
        match key {
            "best_thread" => {
                policy.best_avg = policy_number(value, "avg", policy.best_avg);
                policy.best_max = policy_number(value, "max", policy.best_max);
                if let Some(cores) = policy_cores(value, topo.cpu_count) {
                    policy.best_cores = cores;
                }
            }
            "group_high" => {
                policy.high_avg = policy_number(value, "avg", policy.high_avg);
                policy.high_max = policy_number(value, "max", policy.high_max);
                if let Some(cores) = policy_cores(value, topo.cpu_count) {
                    policy.high_cores = cores;
                }
            }
            "group_mid" => {
                policy.mid_avg = policy_number(value, "avg", policy.mid_avg);
                policy.mid_max = policy_number(value, "max", policy.mid_max);
                if let Some(cores) = policy_cores(value, topo.cpu_count) {
                    policy.mid_cores = cores;
                }
            }
            "max_thread_rules" => {
                if let Ok(max) = value.parse::<usize>() {
                    if (1..=12).contains(&max) {
                        policy.max_thread_rules = max;
                    }
                }
            }
            "wildcard_group" => {
                policy.wildcard_group = WildcardGroup::from_wire(value);
            }
            "rule_output_format" => {
                policy.rule_output_format = RuleOutputFormat::from_wire(value);
            }
            "fallback" => {
                if let Some(cores) = policy_cores(value, topo.cpu_count)
                    .or_else(|| normalize_core_range(value, topo.cpu_count))
                {
                    policy.fallback_cores = cores;
                }
            }
            _ => {}
        }
    }
    policy
}

fn print_policy_rule(
    key: &str,
    meaning: &str,
    avg: f64,
    max: f64,
    tier: &str,
    cores: &str,
    source: &'static str,
) {
    println!(
        "[校准策略] {key}: {meaning}; 条件=AVG>={avg:.1}% 且 MAX>={max:.1}%; 档位={tier}; 核心={} ({source})",
        dash_if_empty(cores)
    );
}

fn policy_core_source(text: Option<&str>, key: &str, topo: &CpuTiers) -> &'static str {
    let Some(value) = policy_line_value(text, key) else {
        return "自动识别";
    };
    if policy_cores(&value, topo.cpu_count).is_some() {
        "用户指定"
    } else if value.contains("cores:") {
        "用户指定无效, 已回退"
    } else {
        "自动识别"
    }
}

fn fallback_core_source(text: Option<&str>, topo: &CpuTiers) -> &'static str {
    let Some(value) = policy_line_value(text, "fallback") else {
        return "自动识别";
    };
    if policy_cores(&value, topo.cpu_count)
        .or_else(|| normalize_core_range(&value, topo.cpu_count))
        .is_some()
    {
        "用户指定"
    } else {
        "用户指定无效, 已回退"
    }
}

fn policy_line_value(text: Option<&str>, key: &str) -> Option<String> {
    let text = text?;
    for raw in text.lines() {
        let line = raw.split_once('#').map_or(raw, |(left, _)| left).trim();
        let Some((line_key, value)) = line.split_once('=') else {
            continue;
        };
        if line_key.trim() == key {
            return Some(value.trim().to_string());
        }
    }
    None
}

fn metadata_mtime_parts(metadata: &fs::Metadata) -> (u64, u32) {
    metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|duration| (duration.as_secs(), duration.subsec_nanos()))
        .unwrap_or((0, 0))
}

fn dash_if_empty(value: &str) -> &str {
    if value.is_empty() {
        "-"
    } else {
        value
    }
}

impl CalibPolicy {
    fn default(topo: &CpuTiers) -> Self {
        Self {
            best_avg: 18.0,
            best_max: 30.0,
            best_cores: topo.highest.clone(),
            high_avg: 13.0,
            high_max: 22.0,
            high_cores: topo.high.clone(),
            mid_avg: 8.0,
            mid_max: 18.0,
            mid_cores: topo.mid.clone(),
            max_thread_rules: MAX_THREAD_RULES,
            wildcard_group: WildcardGroup::MaxMember,
            rule_output_format: RuleOutputFormat::Legacy,
            fallback_cores: topo.fallback.clone(),
        }
    }

    fn load(topo: &CpuTiers) -> Self {
        sync_policy_topology(topo);
        let mut policy = Self::default(topo);
        let Ok(text) = fs::read_to_string(CALIB_POLICY_FILE) else {
            println!("[CALIB] 未找到校准策略，使用内置默认值");
            return policy;
        };

        for raw in text.lines() {
            let line = raw.split_once('#').map_or(raw, |(left, _)| left).trim();
            if line.is_empty() {
                continue;
            }
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            let key = key.trim();
            let value = value.trim();
            match key {
                "best_thread" => {
                    policy.best_avg = policy_number(value, "avg", policy.best_avg);
                    policy.best_max = policy_number(value, "max", policy.best_max);
                    if let Some(cores) = policy_cores(value, topo.cpu_count) {
                        policy.best_cores = cores;
                    }
                }
                "group_high" => {
                    policy.high_avg = policy_number(value, "avg", policy.high_avg);
                    policy.high_max = policy_number(value, "max", policy.high_max);
                    if let Some(cores) = policy_cores(value, topo.cpu_count) {
                        policy.high_cores = cores;
                    }
                }
                "group_mid" => {
                    policy.mid_avg = policy_number(value, "avg", policy.mid_avg);
                    policy.mid_max = policy_number(value, "max", policy.mid_max);
                    if let Some(cores) = policy_cores(value, topo.cpu_count) {
                        policy.mid_cores = cores;
                    }
                }
                "max_thread_rules" => {
                    if let Ok(max) = value.parse::<usize>() {
                        if (1..=12).contains(&max) {
                            policy.max_thread_rules = max;
                        }
                    }
                }
                "wildcard_group" => {
                    policy.wildcard_group = WildcardGroup::from_wire(value);
                }
                "rule_output_format" => {
                    policy.rule_output_format = RuleOutputFormat::from_wire(value);
                }
                "fallback" => {
                    if let Some(cores) = policy_cores(value, topo.cpu_count)
                        .or_else(|| normalize_core_range(value, topo.cpu_count))
                    {
                        policy.fallback_cores = cores;
                    }
                }
                _ => {}
            }
        }

        println!(
            "[CALIB] 校准策略: 最重线程 avg>={:.1} max>={:.1} 核心={} 高负载 avg>={:.1} max>={:.1} 核心={} 中负载 avg>={:.1} max>={:.1} 核心={} 线程规则上限={} 通配聚合={} 生成格式={} 兜底={}",
            policy.best_avg,
            policy.best_max,
            policy.best_cores,
            policy.high_avg,
            policy.high_max,
            policy.high_cores,
            policy.mid_avg,
            policy.mid_max,
            policy.mid_cores,
            policy.max_thread_rules,
            policy.wildcard_group.wire(),
            policy.rule_output_format.wire(),
            policy.fallback_cores
        );
        policy
    }
}

fn policy_number(rule: &str, name: &str, fallback: f64) -> f64 {
    let Some(value) = policy_text(rule, name) else {
        return fallback;
    };
    match value.parse::<f64>() {
        Ok(number) if (0.0..=100.0).contains(&number) => number,
        _ => fallback,
    }
}

fn policy_cores(rule: &str, cpu_count: usize) -> Option<String> {
    policy_text(rule, "cores").and_then(|value| normalize_core_range(&value, cpu_count))
}

fn policy_text(rule: &str, name: &str) -> Option<String> {
    let marker = format!("{name}:");
    let start = rule.find(&marker)? + marker.len();
    let rest = &rule[start..];
    let end = rest.find(',').unwrap_or(rest.len());
    let value = rest[..end].trim();
    if value.is_empty() {
        None
    } else {
        Some(value.to_string())
    }
}

fn normalize_core_range(value: &str, cpu_count: usize) -> Option<String> {
    let value = value.trim().to_ascii_lowercase();
    if value.is_empty() || value == "auto" {
        return None;
    }

    let mut seen = vec![false; cpu_count.max(1)];
    let mut min_cpu = usize::MAX;
    let mut max_cpu = 0usize;
    let mut count = 0usize;
    for part in value.split(',') {
        let part = part.trim();
        if part.is_empty() {
            return None;
        }
        let (start, end) = if let Some((left, right)) = part.split_once('-') {
            let start = left.trim().parse::<usize>().ok()?;
            let end = right.trim().parse::<usize>().ok()?;
            if start > end {
                return None;
            }
            (start, end)
        } else {
            let cpu = part.parse::<usize>().ok()?;
            (cpu, cpu)
        };
        if end >= seen.len() {
            return None;
        }
        for cpu in start..=end {
            if !seen[cpu] {
                seen[cpu] = true;
                count += 1;
                min_cpu = min_cpu.min(cpu);
                max_cpu = max_cpu.max(cpu);
            }
        }
    }
    if count == 0 || count != max_cpu - min_cpu + 1 {
        return None;
    }
    if min_cpu == max_cpu {
        Some(min_cpu.to_string())
    } else {
        Some(format!("{min_cpu}-{max_cpu}"))
    }
}

fn present_cpus() -> Vec<usize> {
    for path in [
        "/sys/devices/system/cpu/present",
        "/sys/devices/system/cpu/possible",
    ] {
        if let Ok(text) = fs::read_to_string(path) {
            let cpus = parse_cpu_list(&text);
            if !cpus.is_empty() {
                return cpus;
            }
        }
    }
    (0..8).collect()
}

fn parse_cpu_list(text: &str) -> Vec<usize> {
    let mut out = Vec::new();
    for part in text.trim().split(',') {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        let Some((start, end)) = parse_cpu_range(part) else {
            continue;
        };
        for cpu in start..=end {
            if !out.contains(&cpu) {
                out.push(cpu);
            }
        }
    }
    out.sort_unstable();
    out
}

fn parse_cpu_range(text: &str) -> Option<(usize, usize)> {
    if let Some((left, right)) = text.split_once('-') {
        let start = left.trim().parse::<usize>().ok()?;
        let end = right.trim().parse::<usize>().ok()?;
        Some(if start <= end {
            (start, end)
        } else {
            (end, start)
        })
    } else {
        let cpu = text.trim().parse::<usize>().ok()?;
        Some((cpu, cpu))
    }
}

fn format_cpu_list(cpus: &[usize]) -> Option<String> {
    if cpus.is_empty() {
        return None;
    }
    let mut sorted = cpus.to_vec();
    sorted.sort_unstable();
    sorted.dedup();

    let mut out = Vec::new();
    let mut index = 0usize;
    while index < sorted.len() {
        let start = sorted[index];
        let mut end = start;
        index += 1;
        while index < sorted.len() && sorted[index] == end + 1 {
            end = sorted[index];
            index += 1;
        }
        if start == end {
            out.push(start.to_string());
        } else {
            out.push(format!("{start}-{end}"));
        }
    }
    Some(out.join(","))
}
