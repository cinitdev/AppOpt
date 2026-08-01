// 常驻守护主循环。
//
// 这里负责把“规则文件 -> 扫描计划 -> 进程/线程命中 -> sched_setaffinity”串起来。
// 日常轮次优先使用 DaemonState.known_pids，并以数字 PID 快照发现新进程；只有配置变化、
// 健康观察或前台生命周期发现时才完整读取 /proc/<pid>。
//
// 这个文件只关心调度节奏和日志摘要，具体规则解析/扫描/绑核分别在 config.rs、scan.rs、
// affinity.rs 中实现。
fn daemon_loop(args: &Args) -> io::Result<()> {
    fs::create_dir_all(STATE_DIR)?;
    println!("[RS] 启动 AppOpt Rust 守护 v{VERSION}");
    println!("[RS] 作者: suto & 一只小柒夏");
    println!("[RS] 配置文件: {}", args.config.display());
    println!("[RS] 包名 UID 映射: {}", args.uid_map.display());
    println!("[RS] 检查间隔: {} 秒", args.interval_secs);
    println!("[RS] cpuset 运行组: /dev/cpuset/{}", args.cpuset_name);
    println!(
        "[RS] 目标范围: {}",
        args.target_pkg.as_deref().unwrap_or("全部配置应用")
    );
    print_startup_device_info();
    calibration::print_version_diagnostics(VERSION);

    let mut file_monitor = RuntimeFileMonitor::new(&args.config, &args.uid_map).ok();
    println!(
        "[RS] 配置文件监控模式: {}",
        if file_monitor.is_some() {
            "inotify 事件通知 + 60 秒内容校验"
        } else {
            "元数据变化轮询 + 内容指纹校验"
        }
    );
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
    let mut runtime = RuntimeInputsCache::default();
    let mut file_changes = RuntimeFileChanges::all();

    loop {
        if let Err(err) = run_daemon_round(
            args,
            &mut state,
            &mut runtime,
            file_changes,
            file_monitor.is_some(),
        ) {
            eprintln!("[RS] 守护轮询失败: {err}");
        }
        if let Err(err) = wait_for_daemon_wake(
            file_monitor.as_ref(),
            regular_scan_wait_timeout(args.interval_secs, &state),
        ) {
            eprintln!("[RS] 守护事件等待失败，本轮退回定时检查: {err}");
            thread::sleep(Duration::from_secs(args.interval_secs));
        }
        file_changes = match file_monitor.as_mut().map(RuntimeFileMonitor::drain) {
            Some(Ok(changes)) => changes,
            Some(Err(err)) => {
                eprintln!("[RS] inotify 读取失败，已转为元数据轮询: {err}");
                file_monitor = None;
                RuntimeFileChanges::all()
            }
            None => RuntimeFileChanges::default(),
        };
        if file_changes.monitor_invalidated {
            file_monitor = RuntimeFileMonitor::new(&args.config, &args.uid_map).ok();
            if file_monitor.is_none() {
                eprintln!("[RS] inotify 监听已失效，后续使用元数据轮询");
            }
        }
    }
}

fn wait_for_daemon_wake(
    file_monitor: Option<&RuntimeFileMonitor>,
    timeout: Duration,
) -> io::Result<()> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let Some(monitor) = file_monitor else {
            thread::sleep(timeout);
            return Ok(());
        };
        let mut poll_fd = libc::pollfd {
            fd: monitor.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        let timeout_ms = timeout.as_millis().min(i32::MAX as u128) as i32;
        let result = unsafe { libc::poll(&mut poll_fd, 1, timeout_ms) };
        if result < 0 {
            let err = io::Error::last_os_error();
            if err.kind() != io::ErrorKind::Interrupted {
                return Err(err);
            }
        }
        Ok(())
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        let _ = file_monitor;
        thread::sleep(timeout);
        Ok(())
    }
}

// 启动时输出设备诊断，便于用户反馈日志时确认运行环境。
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
}

fn pid_snapshot_interval_ms(state: &DaemonState) -> u64 {
    if state.interactive {
        PID_SNAPSHOT_ACTIVE_MS
    } else {
        PID_SNAPSHOT_IDLE_MS
    }
}

fn regular_scan_interval_ms(interval_secs: u64, interactive: bool) -> u64 {
    if interactive {
        interval_secs.max(1).saturating_mul(1000)
    } else {
        SCREEN_OFF_SCAN_INTERVAL_MS
    }
}

fn regular_scan_due(interval_secs: u64, state: &DaemonState, now_elapsed: u64) -> bool {
    let interval = regular_scan_interval_ms(interval_secs, state.interactive);
    state.last_regular_scan_elapsed_ms.is_none_or(|last| {
        now_elapsed < last || now_elapsed.saturating_sub(last) >= interval
    })
}

fn periodic_full_scan_due(state: &DaemonState, now_elapsed: u64) -> bool {
    let interval = if state.interactive {
        ACTIVE_FULL_SCAN_INTERVAL_MS
    } else {
        SCREEN_OFF_FULL_SCAN_INTERVAL_MS
    };
    state.last_full_scan_elapsed_ms.is_none_or(|last| {
        now_elapsed < last || now_elapsed.saturating_sub(last) >= interval
    })
}

fn regular_scan_wait_timeout(interval_secs: u64, state: &DaemonState) -> Duration {
    let interval = regular_scan_interval_ms(interval_secs, state.interactive);
    let now_elapsed = elapsed_realtime_ms();
    // 首轮尚未建立扫描截止时间时仍保留配置间隔，避免配置读取失败后立即忙循环重试。
    let remaining = state.last_regular_scan_elapsed_ms.map_or(interval, |last| {
        if now_elapsed < last {
            0
        } else {
            interval.saturating_sub(now_elapsed.saturating_sub(last))
        }
    });
    Duration::from_millis(remaining)
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
        refresh_process_index(now_elapsed, rebuild_all || !state.process_index_initialized)?
    } else if state.process_index_has_candidates {
        load_process_index_view(now_elapsed)
            .or_else(|_| refresh_process_index(now_elapsed, true))?
    } else {
        ProcessIndexView::default()
    };
    let round = ProcessIndexRound { view };
    if round.view.refreshed {
        if !state.process_index_initialized {
            state.process_index_initialized = true;
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
        state.process_scan_stamps.remove(&pid);
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

fn merge_proc_scan_result(
    target: &mut ProcScanResult,
    incoming: ProcScanResult,
    state: &mut DaemonState,
) {
    target.complete &= incoming.complete;
    target
        .health_incomplete_packages
        .extend(incoming.health_incomplete_packages);
    for hit in incoming.hits {
        state.known_pids.insert(hit.pid);
        if let Some(existing) = target.hits.iter_mut().find(|item| item.pid == hit.pid) {
            *existing = hit;
        } else {
            target.hits.push(hit);
        }
    }
}

fn refresh_managed_tid_cache(state: &mut DaemonState, hits: &[ProcHit]) {
    let seen_round = state.round_index.saturating_add(1);
    let known_pids = &state.known_pids;
    let managed_tids = &mut state.managed_tids;
    managed_tids.retain(|_, entry| known_pids.contains(&entry.tgid));

    for hit in hits {
        // 完整线程扫描可以替换该 TGID 的旧集合；瞬时读取不完整时只合并正向结果，
        // 避免短暂 /proc 缺口把仍存活的受控线程从缓存中误删。
        if hit.health_scan_complete {
            let observed = hit.actions.iter().map(|action| action.tid).collect::<HashSet<_>>();
            managed_tids.retain(|tid, entry| entry.tgid != hit.pid || observed.contains(tid));
        }
        for action in &hit.actions {
            let cached = managed_tids.get(&action.tid).copied().filter(|current| {
                current.tgid == hit.pid
                    && current.tgid_starttime == hit.pid_starttime
                    && current.starttime == action.tid_starttime
            });
            let cpuset_synced = cached.is_some_and(|current| {
                current.tgid == hit.pid && current.starttime == action.tid_starttime &&
                    current.cpuset_synced
            });
            let next = ManagedTidEntry {
                tgid: hit.pid,
                tgid_starttime: hit.pid_starttime,
                starttime: action.tid_starttime,
                last_seen_round: seen_round,
                cpuset_synced,
                cpuset_failure_count: cached.map_or(0, |current| current.cpuset_failure_count),
                cpuset_retry_after_elapsed_ms: cached
                    .map_or(0, |current| current.cpuset_retry_after_elapsed_ms),
                desired_mask_low64: cached.and_then(|current| current.desired_mask_low64),
                verified_mask_low64: cached.and_then(|current| current.verified_mask_low64),
                last_affinity_check_elapsed_ms: cached
                    .map_or(0, |current| current.last_affinity_check_elapsed_ms),
                next_affinity_check_elapsed_ms: cached
                    .map_or(0, |current| current.next_affinity_check_elapsed_ms),
            };
            let should_update = managed_tids
                .get(&action.tid)
                .is_none_or(|current| {
                    current.tgid != next.tgid
                        || current.tgid_starttime != next.tgid_starttime
                        || current.starttime != next.starttime
                });
            if should_update {
                managed_tids.insert(action.tid, next);
            } else if let Some(current) = managed_tids.get_mut(&action.tid) {
                current.last_seen_round = seen_round;
            }
        }
    }

    if managed_tids.len() > MAX_MANAGED_TIDS {
        let remove_count = managed_tids.len() - MAX_MANAGED_TIDS;
        let mut oldest = managed_tids
            .iter()
            .map(|(tid, entry)| (*tid, entry.last_seen_round))
            .collect::<Vec<_>>();
        oldest.sort_unstable_by_key(|(tid, last_seen)| (*last_seen, *tid));
        for (tid, _) in oldest.into_iter().take(remove_count) {
            managed_tids.remove(&tid);
        }
    }
}

fn run_daemon_round(
    args: &Args,
    state: &mut DaemonState,
    runtime: &mut RuntimeInputsCache,
    file_changes: RuntimeFileChanges,
    monitor_active: bool,
) -> io::Result<()> {
    let round_start = Instant::now();
    if let Err(err) = ensure_rule_health_loaded(state) {
        eprintln!("[RS] 规则健康状态读取失败，本轮不禁用任何规则: {err}");
    }
    let scan_clock = elapsed_realtime_ms();
    state.interactive = read_foreground_interactive(scan_clock).unwrap_or(true);
    let foreground_state = read_rule_health_foreground_state(scan_clock);
    let focused_package = (state.interactive
        && foreground_state.reliable
        && foreground_state.observable
        && !foreground_state.focused_package.is_empty())
        .then(|| foreground_state.focused_package.clone());
    let regular_scan_due = regular_scan_due(args.interval_secs, state, scan_clock);
    let _refresh = runtime.refresh(
        args,
        state,
        file_changes,
        monitor_active,
        scan_clock,
    )?;
    let rules = &runtime.rules;
    let uid_map = &runtime.uid_map;
    let index = &runtime.index;
    let plan = &index.plan;
    let config_key = runtime.config_key.ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidData, "配置文件没有可用内容指纹")
    })?;
    let uid_key = runtime.uid_map_key;
    let rule_config_changed = state.last_config_key != Some(config_key);
    let config_changed = rule_config_changed || state.last_uid_map_key != uid_key;
    if config_changed {
        log_config_summary(rules, uid_map, plan);
    }
    if rule_config_changed {
        for rule_line in disabled_rule_health_lines(rules, state) {
            println!("[RS] 规则健康已停用: {rule_line}");
        }
    }
    let cache_uninitialized = !state.proc_scan_initialized;
    if !regular_scan_due && !config_changed && !cache_uninitialized {
        return Ok(());
    }

    // 配置变化或固定周期到期都算一次常规轮次；inotify 提前唤醒不会改变扫描节奏。
    state.last_regular_scan_elapsed_ms = Some(scan_clock);

    let proc_total = system_process_count();
    let proc_count_grew = matches!(
        (state.last_proc_total, proc_total),
        (Some(last), Some(current)) if current > last
    );
    let growth_hint_allowed = state.last_proc_growth_scan_elapsed_ms.is_none_or(|last| {
        scan_clock >= last && scan_clock.saturating_sub(last) >= PID_GROWTH_HINT_MIN_MS
    });
    if proc_count_grew && growth_hint_allowed {
        state.proc_growth_scan_pending = true;
    }
    let full_scan_retry_pending = state.last_full_scan_attempt_elapsed_ms.is_some();
    let full_scan_retry_allowed = state
        .last_full_scan_attempt_elapsed_ms
        .is_none_or(|last| {
            scan_clock >= last
                && scan_clock.saturating_sub(last) >= RULE_HEALTH_FULL_SCAN_RETRY_MS
        });
    let health_scan_packages = rule_health_scan_due_packages(state);
    let foreground_discovery_pkg = foreground_discovery_scan_due(
        args.target_pkg.as_deref(),
        &plan.all_pkgs,
        state,
    );
    let mut targeted_scan_packages = health_scan_packages.clone();
    if let Some(pkg) = &foreground_discovery_pkg {
        targeted_scan_packages.insert(pkg.clone());
    }
    let periodic_full_scan_due = periodic_full_scan_due(state, scan_clock);

    // Rust 版的核心优化点：
    // - 配置刚变化时必须全量扫，因为规则目标可能完全变了。
    // - 第一次启动时必须全量扫；全扫结果为空后也视为缓存已经初始化。
    // - 系统进程数增长只要求立即刷新轻量 PID 快照，不再因此全量读取 cmdline。
    // - 规则健康和前台生命周期只扫描对应包；PID 快照和短期候选复查覆盖日常进程变化。
    // - 已知进程按 10/30 秒节奏校验
    //   TID 指纹，集合未变化时不读取全部线程名和 affinity。
    // - 已确认空结果不会每轮重扫；新进程由 PID 快照差集和短期复查发现。
    let full_scan = config_changed
        || cache_uninitialized
        || ((full_scan_retry_pending || periodic_full_scan_due) && full_scan_retry_allowed);
    let mut scan_reason = if config_changed {
        "配置变更"
    } else if cache_uninitialized {
        "初始扫描"
    } else if full_scan_retry_pending && full_scan {
        "不完整全扫重试"
    } else if periodic_full_scan_due && full_scan {
        if state.interactive {
            "亮屏周期恢复扫描"
        } else {
            "息屏周期恢复扫描"
        }
    } else if !health_scan_packages.is_empty() {
        "健康观察包级复核"
    } else if foreground_discovery_pkg.is_some() {
        "前台生命周期包级发现"
    } else {
        "PID缓存"
    };
    let scan_started = Instant::now();
    let previous_known_pids = state.known_pids.clone();
    let growth_refresh_requested = state.proc_growth_scan_pending;
    let mut process_index_round = match prepare_process_index_round(
        state,
        scan_clock,
        full_scan || growth_refresh_requested,
        full_scan,
    ) {
        Ok(update) => {
            if update.view.refreshed {
                state.proc_growth_scan_pending = false;
                if growth_refresh_requested {
                    state.last_proc_growth_scan_elapsed_ms = Some(scan_clock);
                }
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
    }
    if !full_scan && process_index_round.view.added > 0 {
        scan_reason = "进程索引发现";
    }
    let mut priority_pids = focused_package
        .as_deref()
        .and_then(|pkg| process_index_find_package_pids(pkg).ok())
        .unwrap_or_default();
    let deep_scan_interval_ms = if state.interactive {
        ACTIVE_PROCESS_DEEP_SCAN_MS
    } else {
        SCREEN_OFF_PROCESS_DEEP_SCAN_MS
    };
    let mut scan_result = if full_scan {
        match scan_proc(rules, index, &state.known_pids) {
            Ok(result) => result,
            Err(err) => {
                eprintln!("[RS] 全量扫描失败，本轮仅保留正向结果并等待冷却重试: {err}");
                ProcScanResult::default()
            }
        }
    } else {
        scan_known_pids(
            rules,
            index,
            &mut state.known_pids,
            &mut state.process_scan_stamps,
            KnownPidScanPolicy {
                now_elapsed: scan_clock,
                deep_scan_interval_ms,
                priority_pids: &priority_pids,
                background_budget: Duration::from_millis(BACKGROUND_SCAN_BUDGET_MS),
            },
        )
    };

    let mut scoped_scan_evidence = None;
    if !full_scan && !targeted_scan_packages.is_empty() {
        match scan_proc_packages(
            rules,
            index,
            &state.known_pids,
            &targeted_scan_packages,
        ) {
            Ok(scoped_result) => {
                scoped_scan_evidence = Some((
                    scoped_result.complete,
                    scoped_result.health_incomplete_packages.clone(),
                ));
                merge_proc_scan_result(&mut scan_result, scoped_result, state);
            }
            Err(err) => {
                eprintln!("[RS] 包级扫描失败，本轮不产生规则健康负向证据: {err}");
                scoped_scan_evidence = Some((false, BTreeSet::new()));
                scan_result.complete = false;
            }
        }
    }

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
        rules,
        index,
        &process_index_round.view.candidate_pids,
    );
    merge_candidate_hits(&mut scan_result, candidate_result, state);
    if let Some(pkg) = focused_package.as_deref() {
        priority_pids.extend(
            scan_result
                .hits
                .iter()
                .filter(|hit| process_belongs_to_uid_package(&hit.cmdline, pkg))
                .map(|hit| hit.pid),
        );
    }
    let scan_finished_at = elapsed_realtime_ms();
    let ProcScanResult {
        hits,
        complete: scan_complete,
        health_incomplete_packages,
    } = scan_result;
    let full_scan_evidence = if full_scan {
        Some(FullScanEvidence {
            completed_at: scan_finished_at,
            global_complete: scan_complete,
            incomplete_packages: health_incomplete_packages.clone(),
            scanned_packages: None,
        })
    } else {
        scoped_scan_evidence.map(|(complete, incomplete_packages)| FullScanEvidence {
            completed_at: scan_finished_at,
            global_complete: complete,
            incomplete_packages,
            scanned_packages: Some(targeted_scan_packages.clone()),
        })
    };
    if full_scan || !health_scan_packages.is_empty() {
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
    for hit in &hits {
        update_process_scan_stamp(
            &mut state.process_scan_stamps,
            hit,
            scan_finished_at,
            deep_scan_interval_ms,
        );
    }
    state
        .process_scan_stamps
        .retain(|pid, _| state.known_pids.contains(pid));
    refresh_managed_tid_cache(state, &hits);

    let known_pids = state.known_pids.len();
    let processes = state.known_pids.len();
    let has_new_hit_pid = hits
        .iter()
        .any(|hit| !previous_known_pids.contains(&hit.pid));
    let first_summary = !state.logged_round_once;
    let forced_summary = config_changed || first_summary;
    let runtime_state_changed = has_new_hit_pid
        || known_pids != state.last_logged_known_pids
        || processes != state.last_logged_processes;
    let scan_incomplete = (full_scan || !targeted_scan_packages.is_empty()) && !scan_complete;
    let last_summary = state.last_runtime_summary_log_elapsed_ms;
    let state_change_summary_due = (runtime_state_changed || scan_incomplete)
        && last_summary.is_none_or(|last| {
            scan_clock < last
                || scan_clock.saturating_sub(last) >= RUNTIME_CHANGE_LOG_INTERVAL_MS
        });
    let periodic_summary_due = last_summary.is_some_and(|last| {
        scan_clock < last
            || scan_clock.saturating_sub(last) >= RUNTIME_SUMMARY_LOG_INTERVAL_MS
    });
    let detail_log = forced_summary || state_change_summary_due || periodic_summary_due;
    let hit_preview_log = config_changed || first_summary;

    if let Err(err) = update_rule_health(
        rules,
        &hits,
        full_scan_evidence.as_ref(),
        args.target_pkg.as_deref(),
        state,
    ) {
        eprintln!("[RS] 规则健康状态更新失败: {err}");
    }

    let apply_started = Instant::now();
    let base_cpuset = Path::new("/dev/cpuset").join(&args.cpuset_name);
    let interactive = state.interactive;
    let mut stats = apply_hits(
        &hits,
        detail_log,
        &args.cpuset_name,
        &mut state.managed_tids,
        scan_finished_at,
        &priority_pids,
        interactive,
    );
    stats.merge(verify_managed_affinity(
        &mut state.managed_tids,
        &priority_pids,
        interactive,
        scan_finished_at,
        detail_log,
        &base_cpuset,
        &args.cpuset_name,
    ));
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
            if full_scan {
                "全量扫描"
            } else if !targeted_scan_packages.is_empty() {
                "包级扫描"
            } else {
                "PID缓存"
            },
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
        if hit_preview_log && !hits.is_empty() {
            log_hit_preview(&hits, 5, &previous_known_pids);
        } else if hit_preview_log && !plan.is_empty() {
            println!(
                "[RS] 未命中任何进程: appId映射包={} 缺少映射包={}",
                plan.by_app_id.values().map(BTreeSet::len).sum::<usize>(),
                plan.fallback_pkgs.len()
            );
        }
        state.logged_round_once = true;
        state.last_runtime_summary_log_elapsed_ms = Some(scan_clock);
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
