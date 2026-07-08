// 常驻守护主循环。
//
// 这里负责把“规则文件 -> 扫描计划 -> 进程/线程命中 -> sched_setaffinity”串起来。
// Rust 版和 C 版最大的结构差异就在这里：不是每一轮都全量扫 /proc，而是优先使用
// DaemonState.known_pids 缓存；只有配置变化、缓存失效、周期到达时才全量枚举 /proc/<pid>。
//
// 这个文件只关心调度节奏和日志摘要，具体规则解析/扫描/绑核分别在 config.rs、scan.rs、
// affinity.rs 中实现。
fn daemon_loop(args: &Args) -> io::Result<()> {
    println!("[RS] 启动 AppOpt Rust 守护 v{VERSION}");
    println!("[RS] 配置文件: {}", args.config.display());
    println!("[RS] 包名 UID 映射: {}", args.uid_map.display());
    println!("[RS] 检查间隔: {} 秒", args.interval_secs);
    println!(
        "[RS] 目标范围: {}",
        args.target_pkg.as_deref().unwrap_or("全部配置应用")
    );

    start_daemon_socket_thread();
    calibration::start_calibration_thread(args.config.clone());
    fps::start_fps_thread();
    let mut state = DaemonState::default();

    loop {
        if let Err(err) = run_daemon_round(args, &mut state) {
            eprintln!("[RS] 守护轮询失败: {err}");
        }
        thread::sleep(Duration::from_secs(args.interval_secs));
    }
}

fn run_daemon_round(args: &Args, state: &mut DaemonState) -> io::Result<()> {
    let round_start = Instant::now();
    let rules = parse_config(&args.config)?;
    let uid_map = parse_uid_map(&args.uid_map)?;
    let plan = build_scan_plan(&rules, &uid_map, args.target_pkg.as_deref());
    let config_key = file_key(&args.config);
    let uid_key = file_key(&args.uid_map);
    let config_changed = state.last_config_key != config_key || state.last_uid_map_key != uid_key;
    if config_changed {
        log_config_summary(&rules, &uid_map, &plan);
    }

    let proc_total = system_process_count();
    let proc_count_grew = matches!(
        (state.last_proc_total, proc_total),
        (Some(last), Some(current)) if current > last
    );
    let cache_empty = state.known_pids.is_empty();
    let periodic_rescan = state.rounds_since_full_scan >= FULL_RESCAN_ROUNDS;

    // Rust 版的核心优化点：
    // - 配置刚变化时必须全量扫，因为规则目标可能完全变了。
    // - 第一次启动或缓存为空时必须全量扫，因为还不知道目标 PID。
    // - 系统进程数增长时全量扫，及时发现新启动的目标 App。
    // - 缓存连续跑一段时间后周期性全量扫，补捉极端情况下漏掉的子进程。
    // - 如果缓存扫描没有任何命中，立刻全量扫，避免 App 重启后 PID 变化导致长期失效。
    let mut full_scan = config_changed || cache_empty || proc_count_grew || periodic_rescan;
    let mut scan_reason = if config_changed {
        "配置变更"
    } else if cache_empty {
        "缓存为空"
    } else if proc_count_grew {
        "进程数增长"
    } else if periodic_rescan {
        "周期校验"
    } else {
        "PID缓存"
    };
    let scan_started = Instant::now();
    let previous_known_pids = state.known_pids.clone();
    let mut hits = if full_scan {
        scan_proc(&rules, &plan)?
    } else {
        scan_known_pids(&rules, &plan, &mut state.known_pids)
    };

    if !full_scan && hits.is_empty() && !plan.is_empty() {
        full_scan = true;
        println!(
            "[RS] PID缓存未命中，触发全量扫描: 已知PID={} 目标包={}",
            state.known_pids.len(),
            plan.package_count()
        );
        scan_reason = "缓存未命中";
        hits = scan_proc(&rules, &plan)?;
    }
    let scan_elapsed = scan_started.elapsed();
    state.last_proc_total = proc_total;

    if full_scan {
        state.known_pids.clear();
        state.known_pids.extend(hits.iter().map(|hit| hit.pid));
        state.rounds_since_full_scan = 0;
        state.last_config_key = config_key;
        state.last_uid_map_key = uid_key;
    } else {
        state.rounds_since_full_scan += 1;
    }

    let known_pids = state.known_pids.len();
    let processes = hits.len();
    let has_new_hit_pid = hits
        .iter()
        .any(|hit| !previous_known_pids.contains(&hit.pid));
    let detail_log = config_changed
        || !state.logged_round_once
        || has_new_hit_pid
        || known_pids != state.last_logged_known_pids
        || processes != state.last_logged_processes
        || (state.round_index + 1) % ROUND_SUMMARY_EVERY == 0;

    let apply_started = Instant::now();
    let stats = apply_hits(&hits, detail_log);
    let apply_elapsed = apply_started.elapsed();
    state.round_index = state.round_index.saturating_add(1);
    let scanned_threads = hits.iter().map(|hit| hit.scanned_threads).sum::<usize>();
    let actions = hits.iter().map(|hit| hit.actions.len()).sum::<usize>();
    let process_actions = hits
        .iter()
        .flat_map(|hit| hit.actions.iter())
        .filter(|action| action.source == RuleSource::Process)
        .count();
    let thread_actions = actions.saturating_sub(process_actions);
    let process_rules = hits
        .iter()
        .map(|hit| hit.process_rules.len())
        .sum::<usize>();
    let should_log = detail_log;
    if should_log {
        println!(
            "[RS] 运行摘要: 轮次={} 模式={} 原因={} 配置变更={} 目标包={} 已知PID={} 命中进程={} 扫描线程={} 进程规则={} 线程规则命中={} 进程规则应用={} 已应用={} 已跳过={} 失败={} 无效规则={} 抢写={} 扫描耗时={}ms 应用耗时={}ms 总耗时={}ms",
            state.round_index,
            if full_scan { "全量扫描" } else { "PID缓存" },
            scan_reason,
            if config_changed { "是" } else { "否" },
            plan.package_count(),
            known_pids,
            processes,
            scanned_threads,
            process_rules,
            thread_actions,
            process_actions,
            stats.applied,
            stats.skipped,
            stats.failed,
            stats.invalid_rules,
            stats.mismatched,
            scan_elapsed.as_millis(),
            apply_elapsed.as_millis(),
            round_start.elapsed().as_millis()
        );
        if stats.cpuset_failed > 0 {
            println!("[RS] cpuset辅助写入失败: {}", stats.cpuset_failed);
        }
        if !hits.is_empty() {
            log_hit_preview(&hits, 5, &previous_known_pids);
        } else if !plan.is_empty() {
            println!(
                "[RS] 未命中任何进程: UID过滤包={} 命令行兜底包={}",
                plan.by_uid.values().map(BTreeSet::len).sum::<usize>(),
                plan.fallback_pkgs.len()
            );
        }
        state.logged_round_once = true;
        state.last_logged_known_pids = known_pids;
        state.last_logged_processes = processes;
    }
    Ok(())
}

fn log_config_summary(rules: &[Rule], uid_map: &HashMap<String, u32>, plan: &ScanPlan) {
    let active_rules = rules.iter().filter(|rule| !rule.auto).count();
    let auto_rules = rules.iter().filter(|rule| rule.auto).count();
    let mut owners = BTreeSet::new();
    let mut base_pkgs = BTreeSet::new();
    for rule in rules {
        owners.insert(rule.owner.as_str());
        if let Some(base) = base_package(&rule.owner) {
            base_pkgs.insert(base);
        }
    }
    let uid_bound_pkgs = plan.by_uid.values().map(BTreeSet::len).sum::<usize>();
    println!(
        "[RS] 规则加载完成: 规则={} auto={} 应用/进程={} 基础包={}",
        active_rules,
        auto_rules,
        owners.len(),
        base_pkgs.len()
    );
    println!(
        "[RS] 包名 UID 映射: 已加载 {} 个, UID过滤 {} 个, 命令行兜底 {} 个",
        uid_map.len(),
        uid_bound_pkgs,
        plan.fallback_pkgs.len()
    );
    println!(
        "[RS] 扫描计划: UID过滤=[{}] 命令行兜底=[{}]",
        plan_uid_preview(plan, 8),
        preview_set(&plan.fallback_pkgs, 8)
    );
}

fn plan_uid_preview(plan: &ScanPlan, limit: usize) -> String {
    let mut rows = Vec::new();
    for (uid, pkgs) in &plan.by_uid {
        for pkg in pkgs {
            rows.push(format!("{pkg}:{uid}"));
        }
    }
    rows.sort();
    preview_list(&rows, limit)
}

fn preview_set(values: &BTreeSet<String>, limit: usize) -> String {
    let rows = values.iter().cloned().collect::<Vec<_>>();
    preview_list(&rows, limit)
}

fn preview_list(values: &[String], limit: usize) -> String {
    if values.is_empty() {
        return "-".to_string();
    }
    let mut out = values
        .iter()
        .take(limit)
        .cloned()
        .collect::<Vec<_>>()
        .join(", ");
    if values.len() > limit {
        out.push_str(&format!(" ... +{}", values.len() - limit));
    }
    out
}

fn log_hit_preview(hits: &[ProcHit], limit: usize, previous_known_pids: &BTreeSet<i32>) {
    let shown = hits.len().min(limit);
    if hits.len() > limit {
        println!("[RS] 命中详情: 显示 {shown}/{} 个进程", hits.len());
    } else {
        println!("[RS] 命中详情: {} 个进程", hits.len());
    }
    let mut rows = hits.iter().collect::<Vec<_>>();
    rows.sort_by_key(|hit| (previous_known_pids.contains(&hit.pid), hit.pid));
    for hit in rows.into_iter().take(limit) {
        let process_actions = hit
            .actions
            .iter()
            .filter(|action| action.source == RuleSource::Process)
            .count();
        let thread_actions = hit.actions.len().saturating_sub(process_actions);
        println!(
            "[RS]   {}pid={} uid={} 进程={} 扫描线程={} 进程规则={} 线程规则={} 兜底线程={}",
            if previous_known_pids.contains(&hit.pid) {
                ""
            } else {
                "新进程 "
            },
            hit.pid,
            hit.uid,
            hit.cmdline,
            hit.scanned_threads,
            hit.process_rules.len(),
            thread_actions,
            process_actions
        );
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn system_process_count() -> Option<u64> {
    let mut info: libc::sysinfo = unsafe { mem::zeroed() };
    let rc = unsafe { libc::sysinfo(&mut info) };
    if rc == 0 {
        Some(info.procs as u64)
    } else {
        None
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn system_process_count() -> Option<u64> {
    None
}
