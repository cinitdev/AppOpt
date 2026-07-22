// 常驻守护主循环。
//
// 这里负责把“规则文件 -> 扫描计划 -> 进程/线程命中 -> sched_setaffinity”串起来。
// 日常轮次优先使用 DaemonState.known_pids，并以数字 PID 快照发现新进程；只有配置变化、
// 健康观察或周期校验到达时才完整读取 /proc/<pid>。
//
// 这个文件只关心调度节奏和日志摘要，具体规则解析/扫描/绑核分别在 config.rs、scan.rs、
// affinity.rs 中实现。
fn daemon_loop(args: &Args) -> io::Result<()> {
    println!("[RS] 启动 AppOpt Rust 守护 v{VERSION}");
    println!("[RS] 作者: suto & 一只小柒夏");
    println!("[RS] 配置文件: {}", args.config.display());
    println!("[RS] 包名 UID 映射: {}", args.uid_map.display());
    println!("[RS] 检查间隔: {} 秒", args.interval_secs);
    println!(
        "[RS] 目标范围: {}",
        args.target_pkg.as_deref().unwrap_or("全部配置应用")
    );
    print_startup_device_info();
    calibration::print_version_diagnostics(VERSION);

    println!("[RS] 配置文件监控模式: 守护主循环轮询");
    if start_daemon_socket_thread() {
        println!("[RS] 启用守护进程验证 socket");
    }
    if calibration::start_calibration_thread(args.config.clone()) {
        println!("[RS] 启用自动校准线程");
    }
    if fps::start_fps_thread() {
        println!("[RS] 启用真实帧率监测线程 (eBPF/SF fallback)");
    }
    let mut state = DaemonState::default();

    loop {
        if let Err(err) = run_daemon_round(args, &mut state) {
            eprintln!("[RS] 守护轮询失败: {err}");
        }
        thread::sleep(Duration::from_secs(args.interval_secs));
    }
}

// 启动时输出与 C 版一致的设备诊断，便于用户反馈日志时确认运行环境。
fn print_startup_device_info() {
    let properties = read_android_properties();
    let android_version = first_property(
        &properties,
        &[
            "ro.build.version.release",
            "ro.system.build.version.release",
        ],
    );
    let api_level = first_property(
        &properties,
        &["ro.build.version.sdk", "ro.system.build.version.sdk"],
    );
    if let Some(version) = android_version {
        if let Some(api) = api_level {
            println!("Android 版本: {version} (API {api})");
        } else {
            println!("Android 版本: {version}");
        }
    }

    let brand = first_property(
        &properties,
        &[
            "ro.product.brand",
            "ro.product.system.brand",
            "ro.product.vendor.brand",
            "ro.product.odm.brand",
            "ro.product.product.brand",
        ],
    );
    let market_model = first_property(
        &properties,
        &[
            "ro.product.marketname",
            "ro.product.vendor.marketname",
            "ro.product.odm.marketname",
            "ro.product.system.marketname",
            "ro.product.product.marketname",
            "ro.vendor.product.marketname",
            "ro.config.marketing_name",
            "ro.vendor.oplus.market.name",
            "ro.oplus.market.name",
        ],
    );
    let certified_model = first_property(
        &properties,
        &[
            "ro.product.model",
            "ro.product.vendor.model",
            "ro.product.odm.model",
            "ro.product.system.model",
            "ro.product.product.model",
        ],
    );
    if let Some(brand) = brand {
        if let Some(model) = market_model.or(certified_model) {
            println!("设备品牌: {brand} {model}");
        } else {
            println!("设备品牌: {brand}");
        }
    } else if let Some(model) = market_model.or(certified_model) {
        println!("设备型号: {model}");
    }

    if let Ok(release) = fs::read_to_string("/proc/sys/kernel/osrelease") {
        let release = release.trim();
        if !release.is_empty() {
            println!("内核版本: Linux {release}");
        }
    }
}

fn read_android_properties() -> HashMap<String, String> {
    let output = Command::new("/system/bin/getprop")
        .output()
        .or_else(|_| Command::new("getprop").output());
    let Ok(output) = output else {
        return HashMap::new();
    };
    let text = String::from_utf8_lossy(&output.stdout);
    let mut properties = HashMap::new();
    for line in text.lines() {
        let Some(separator) = line.find("]: [") else {
            continue;
        };
        if !line.starts_with('[') {
            continue;
        }
        let key = &line[1..separator];
        let value = line[separator + 4..].strip_suffix(']').unwrap_or(&line[separator + 4..]);
        if !key.is_empty() && !value.is_empty() {
            properties.insert(key.to_string(), value.to_string());
        }
    }
    properties
}

fn first_property<'a>(properties: &'a HashMap<String, String>, keys: &[&str]) -> Option<&'a str> {
    keys.iter()
        .find_map(|key| properties.get(*key).map(String::as_str))
        .filter(|value| !value.is_empty())
}

#[derive(Debug, Default)]
struct ProcessIndexRound {
    view: ProcessIndexView,
    entered_idle_backoff: bool,
}

fn pid_snapshot_interval_ms(state: &DaemonState) -> u64 {
    if state.process_index_has_candidates
        || state.stable_pid_snapshot_rounds < 3
    {
        PID_SNAPSHOT_ACTIVE_MS
    } else {
        PID_SNAPSHOT_IDLE_MS
    }
}

fn pid_snapshot_log_due(state: &mut DaemonState, now_elapsed: u64) -> bool {
    let due = state.last_pid_snapshot_log_elapsed_ms.is_none_or(|last| {
        now_elapsed >= last
            && now_elapsed.saturating_sub(last) >= PID_SNAPSHOT_LOG_INTERVAL_MS
    });
    if due {
        state.last_pid_snapshot_log_elapsed_ms = Some(now_elapsed);
    }
    due
}

fn prepare_process_index_round(
    state: &mut DaemonState,
    now_elapsed: u64,
    force: bool,
    rebuild_all: bool,
) -> io::Result<ProcessIndexRound> {
    let interval = pid_snapshot_interval_ms(state);
    let due = force
        || !state.process_index_initialized
        || state.last_pid_snapshot_elapsed_ms.is_none_or(|last| {
            now_elapsed >= last && now_elapsed.saturating_sub(last) >= interval
        });
    let view = if due {
        refresh_process_index(
            now_elapsed,
            rebuild_all || !state.process_index_initialized,
        )?
    } else if state.process_index_has_candidates {
        load_process_index_view(now_elapsed)
            .or_else(|_| refresh_process_index(now_elapsed, true))?
    } else {
        ProcessIndexView::default()
    };
    let mut round = ProcessIndexRound {
        view,
        ..ProcessIndexRound::default()
    };
    if round.view.refreshed {
        if state.process_index_initialized {
            if round.view.added == 0 && round.view.exited == 0 {
                let previous = state.stable_pid_snapshot_rounds;
                state.stable_pid_snapshot_rounds = previous.saturating_add(1);
                round.entered_idle_backoff = previous < 3
                    && state.stable_pid_snapshot_rounds >= 3
                    && round.view.candidate_pids.is_empty();
            } else {
                state.stable_pid_snapshot_rounds = 0;
            }
        } else {
            state.process_index_initialized = true;
            state.stable_pid_snapshot_rounds = 0;
        }
        state.last_pid_snapshot_elapsed_ms = Some(now_elapsed);
    }
    if round.view.loaded {
        state.process_index_has_candidates = !round.view.candidate_pids.is_empty();
        state
            .known_pids
            .retain(|pid| round.view.current_pids.contains(pid));
    }
    Ok(round)
}

fn merge_candidate_hits(
    scan_result: &mut ProcScanResult,
    candidate_result: CandidateScanResult,
    state: &mut DaemonState,
) {
    for pid in candidate_result.gone_pids {
        state.known_pids.remove(&pid);
    }
    for hit in candidate_result.hits {
        let pid = hit.pid;
        state.known_pids.insert(pid);
        if !hit.health_scan_complete {
            if let Some(pkg) = base_package(&hit.cmdline) {
                scan_result
                    .health_incomplete_packages
                    .insert(pkg.to_string());
            }
        }
        if let Some(existing) = scan_result.hits.iter_mut().find(|item| item.pid == pid) {
            *existing = hit;
        } else {
            scan_result.hits.push(hit);
        }
    }
}

fn run_daemon_round(args: &Args, state: &mut DaemonState) -> io::Result<()> {
    let round_start = Instant::now();
    let (rules, config_key) = parse_config_with_key(&args.config)?;
    let (uid_map, uid_key) = parse_uid_map_with_key(&args.uid_map)?;
    if let Err(err) = ensure_rule_health_loaded(state) {
        eprintln!("[RS] 规则健康状态读取失败，本轮不禁用任何规则: {err}");
    }
    let runtime_rules = runtime_rule_health_rules(&rules, state);
    let plan = build_scan_plan(&runtime_rules, &uid_map, args.target_pkg.as_deref());
    let config_changed =
        state.last_config_key != Some(config_key) || state.last_uid_map_key != uid_key;
    if config_changed {
        log_config_summary(&rules, &uid_map, &plan);
        let disabled = rules.len().saturating_sub(runtime_rules.len());
        if disabled > 0 {
            println!("[RS] 规则健康已停用: {} 条连续两次未检测到的规则", disabled);
        }
    }

    let proc_total = system_process_count();
    let proc_count_grew = matches!(
        (state.last_proc_total, proc_total),
        (Some(last), Some(current)) if current > last
    );
    if proc_count_grew {
        state.proc_growth_scan_pending = true;
    }
    let cache_uninitialized = !state.proc_scan_initialized;
    let scan_clock = elapsed_realtime_ms();
    let periodic_rescan = state.last_full_scan_elapsed_ms.is_some_and(|last| {
        scan_clock >= last && scan_clock.saturating_sub(last) >= FULL_RESCAN_MAX_MS
    });
    let full_scan_retry_pending = state.last_full_scan_attempt_elapsed_ms.is_some();
    let full_scan_retry_allowed = state
        .last_full_scan_attempt_elapsed_ms
        .is_none_or(|last| {
            scan_clock >= last
                && scan_clock.saturating_sub(last) >= RULE_HEALTH_FULL_SCAN_RETRY_MS
        });
    let health_due = rule_health_full_scan_due(state);
    let foreground_discovery_due =
        foreground_discovery_scan_due(args.target_pkg.as_deref(), state);

    // Rust 版的核心优化点：
    // - 配置刚变化时必须全量扫，因为规则目标可能完全变了。
    // - 第一次启动时必须全量扫；全扫结果为空后也视为缓存已经初始化。
    // - 系统进程数增长只要求立即刷新轻量 PID 快照，不再因此全量读取 cmdline。
    // - 缓存连续跑一段时间后周期性全量扫，补捉极端情况下漏掉的子进程。
    // - 已确认空结果不会每轮重扫；新进程由 PID 快照差集和短期复查发现。
    let full_scan_requested = config_changed
        || cache_uninitialized
        || full_scan_retry_pending
        || periodic_rescan
        || health_due
        || foreground_discovery_due;
    let full_scan = full_scan_requested && full_scan_retry_allowed;
    let mut scan_reason = if config_changed {
        "配置变更"
    } else if cache_uninitialized {
        "初始扫描"
    } else if full_scan_retry_pending {
        "不完整全扫重试"
    } else if periodic_rescan {
        "周期校验"
    } else if health_due {
        "健康观察到期"
    } else if foreground_discovery_due {
        "前台生命周期进程发现"
    } else {
        "PID缓存"
    };
    let scan_started = Instant::now();
    let previous_known_pids = state.known_pids.clone();
    let mut process_index_round = match prepare_process_index_round(
        state,
        scan_clock,
        full_scan_requested || state.proc_growth_scan_pending,
        full_scan_requested,
    ) {
        Ok(update) => {
            if update.view.refreshed {
                state.proc_growth_scan_pending = false;
            }
            update
        }
        Err(err) => {
            if pid_snapshot_log_due(state, scan_clock) {
                eprintln!("[RS] PID快照刷新失败，保留现有缓存并等待下轮重试: {err}");
            }
            ProcessIndexRound::default()
        }
    };
    if (process_index_round.view.added > 0 || process_index_round.view.exited > 0)
        && pid_snapshot_log_due(state, scan_clock)
    {
        println!(
            "[RS] 进程索引变化: 新增={} 退出={} 待确认={}",
            process_index_round.view.added,
            process_index_round.view.exited,
            process_index_round.view.candidate_pids.len()
        );
    } else if process_index_round.entered_idle_backoff {
        println!("[RS] 进程索引长时间无变化，空闲时退避到 10 秒");
        state.last_pid_snapshot_log_elapsed_ms = Some(scan_clock);
    }
    if !full_scan && process_index_round.view.added > 0 {
        scan_reason = "进程索引发现";
    }
    let mut scan_result = if full_scan {
        match scan_proc(&runtime_rules, &plan, &state.known_pids) {
            Ok(result) => result,
            Err(err) => {
                eprintln!("[RS] 全量扫描失败，本轮仅保留正向结果并等待冷却重试: {err}");
                ProcScanResult::default()
            }
        }
    } else {
        scan_known_pids(&runtime_rules, &plan, &mut state.known_pids)
    };

    if !full_scan {
        let dropped_pids = previous_known_pids
            .difference(&state.known_pids)
            .copied()
            .collect::<Vec<_>>();
        for pid in dropped_pids {
            if process_index_round.view.current_pids.contains(&pid) {
                if let Err(err) = process_index_mark_candidate(pid, scan_clock) {
                    if pid_snapshot_log_due(state, scan_clock) {
                        eprintln!("[RS] 进程索引复查标记写入失败: {err}");
                    }
                }
                process_index_round.view.candidate_pids.insert(pid);
                state.process_index_has_candidates = true;
            }
        }
    }

    let already_scanned = scan_result
        .hits
        .iter()
        .map(|hit| hit.pid)
        .collect::<BTreeSet<_>>();
    process_index_round.view.candidate_pids.retain(|pid| {
        !state.known_pids.contains(pid) && !already_scanned.contains(pid)
    });

    let candidate_result = scan_candidate_pids(
        &runtime_rules,
        &plan,
        &process_index_round.view.candidate_pids,
    );
    merge_candidate_hits(&mut scan_result, candidate_result, state);
    let scan_finished_at = elapsed_realtime_ms();
    let ProcScanResult {
        hits,
        complete: scan_complete,
        health_incomplete_packages,
    } = scan_result;
    let full_scan_evidence = full_scan.then_some(FullScanEvidence {
        completed_at: scan_finished_at,
        global_complete: scan_complete,
        incomplete_packages: health_incomplete_packages,
    });
    if full_scan {
        state.last_health_full_scan_attempt_elapsed_ms = Some(scan_finished_at);
    }
    let scan_elapsed = scan_started.elapsed();
    state.last_proc_total = proc_total;

    if full_scan {
        if scan_complete {
            state.known_pids.clear();
        } else {
            state.known_pids = previous_known_pids.clone();
        }
        state.known_pids.extend(hits.iter().map(|hit| hit.pid));
        state.proc_scan_initialized = true;
        state.last_full_scan_attempt_elapsed_ms = (!scan_complete).then_some(scan_finished_at);
        if scan_complete {
            state.last_full_scan_elapsed_ms = Some(scan_finished_at);
            state.proc_growth_scan_pending = false;
        }
        state.last_config_key = Some(config_key);
        state.last_uid_map_key = uid_key;
    }

    let known_pids = state.known_pids.len();
    let processes = hits.len();
    let has_new_hit_pid = hits
        .iter()
        .any(|hit| !previous_known_pids.contains(&hit.pid));
    let detail_log = config_changed
        || !state.logged_round_once
        || has_new_hit_pid
        || (full_scan && !scan_complete)
        || known_pids != state.last_logged_known_pids
        || processes != state.last_logged_processes
        || (state.round_index + 1) % ROUND_SUMMARY_EVERY == 0;

    if let Err(err) = update_rule_health(
        &rules,
        &hits,
        full_scan_evidence.as_ref(),
        args.target_pkg.as_deref(),
        state,
    ) {
        eprintln!("[RS] 规则健康状态更新失败: {err}");
    }

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
            "[RS] 运行摘要: 轮次={} 模式={} 扫描完整={} 原因={} 配置变更={} 目标包={} 已知PID={} 命中进程={} 扫描线程={} 进程规则={} 线程规则命中={} 进程规则应用={} 已应用={} 已跳过={} 系统限制={} 失败={} 无效规则={} 抢写={} 扫描耗时={}ms 应用耗时={}ms 总耗时={}ms",
            state.round_index,
            if full_scan { "全量扫描" } else { "PID缓存" },
            if scan_complete { "是" } else { "否" },
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
            stats.restricted,
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
                "[RS] 未命中任何进程: appId映射包={} 缺少映射包={}",
                plan.by_app_id.values().map(BTreeSet::len).sum::<usize>(),
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
    let app_id_bound_pkgs = plan.by_app_id.values().map(BTreeSet::len).sum::<usize>();
    println!(
        "[RS] 规则加载完成: 规则={} auto={} 应用/进程={} 基础包={}",
        active_rules,
        auto_rules,
        owners.len(),
        base_pkgs.len()
    );
    println!(
        "[RS] 包名 UID 映射: 已加载 {} 个, appId快路径 {} 个, 缺少映射 {} 个",
        uid_map.len(),
        app_id_bound_pkgs,
        plan.fallback_pkgs.len()
    );
    println!(
        "[RS] 扫描计划: appId快路径=[{}] 缺少映射=[{}]",
        plan_app_id_preview(plan, 8),
        preview_set(&plan.fallback_pkgs, 8)
    );
}

fn plan_app_id_preview(plan: &ScanPlan, limit: usize) -> String {
    let mut rows = Vec::new();
    for (app_id, pkgs) in &plan.by_app_id {
        for pkg in pkgs {
            rows.push(format!("{pkg}:{app_id}"));
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
