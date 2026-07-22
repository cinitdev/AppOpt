    // FPS 命令循环。
    //
    // App 写入 fps.cmd，daemon 读取后立即删除：
    // - start <pkg> [socket token]
    // - stop
    //
    // socket/token 是 App 侧实时 FPS 通道；如果没有提供或连接失败，就回落到 files/fps。
    // 这里不解析更多复杂参数，避免命令文件变成半套 IPC 协议。
    fn fps_loop() -> io::Result<()> {
        let mut monitor: Option<FpsMonitor> = None;
        let mut manual_pkg: Option<String> = None;
        let mut auto_pkg: Option<String> = None;
        let mut last_selected: Option<Vec<String>> = None;
        let mut selected = Vec::new();
        let mut last_auto_check = Instant::now() - Duration::from_secs(10);
        while !FPS_SHUTDOWN.load(Ordering::Relaxed) {
            let auto_check_interval = if selected.is_empty() {
                Duration::from_secs(10)
            } else {
                Duration::from_secs(1)
            };
            if last_auto_check.elapsed() >= auto_check_interval {
                last_auto_check = Instant::now();
                selected = read_jank_packages();
                if last_selected.as_ref() != Some(&selected) {
                    if selected.is_empty() {
                        println!("[boost] 卡顿时临时提速未配置应用");
                    } else {
                        let preview = selected.iter().take(5).cloned().collect::<Vec<_>>().join(", ");
                        let remaining = selected.len().saturating_sub(5);
                        println!(
                            "[boost] 已配置临时提速: {}{}；等待所选应用进入前台",
                            preview,
                            if remaining > 0 {
                                format!(" ... +{remaining}")
                            } else {
                                String::new()
                            }
                        );
                    }
                    last_selected = Some(selected.clone());
                }
                let target = selected_jank_foreground(&selected);
                if manual_pkg.is_none() && target != auto_pkg {
                    if let Some(old) = monitor.as_mut() {
                        old.set_adaptive(false);
                    }
                    monitor = target.clone().map(|pkg| {
                        let mut active = FpsMonitor::start(pkg, None, None);
                        active.set_output_enabled(false);
                        active.set_adaptive(true);
                        active
                    });
                    auto_pkg = target;
                } else if let (Some(manual), Some(active)) =
                    (manual_pkg.as_deref(), monitor.as_mut())
                {
                    active.set_adaptive(target.as_deref() == Some(manual));
                }
            }
            // fps.cmd 是简单命令文件，App 每次启动/停止 FPS 都会写入。
            // 读取后立即删除，和 C 版消费语义保持一致，避免 daemon 重启后重复执行旧命令。
            if let Some(cmd) = read_command()? {
                if let Some(rest) = cmd.strip_prefix("start ").map(str::trim) {
                    if let Some(mut old) = monitor.take() {
                        old.stop();
                    }
                    let parts = rest.split_whitespace().collect::<Vec<_>>();
                    if let Some(pkg) = parts.first().copied().filter(|pkg| !pkg.is_empty()) {
                        let socket_name = parts.get(1).map(|value| (*value).to_string());
                        let socket_token = parts.get(2).map(|value| (*value).to_string());
                        println!(
                            "[FPS] 开始监测: pkg={} 数据通道={}",
                            pkg,
                            if socket_name.is_some() {
                                "socket"
                            } else {
                                "文件"
                            }
                        );
                        monitor = Some(FpsMonitor::start(
                            pkg.to_string(),
                            socket_name,
                            socket_token,
                        ));
                        manual_pkg = Some(pkg.to_string());
                        auto_pkg = None;
                        selected = read_jank_packages();
                        if selected_jank_foreground(&selected).as_deref() == Some(pkg) {
                            if let Some(active) = monitor.as_mut() {
                                active.set_adaptive(true);
                            }
                        }
                    }
                } else if cmd == "stop" || cmd.starts_with("stop ") {
                    manual_pkg = None;
                    selected = read_jank_packages();
                    let target = selected_jank_foreground(&selected);
                    if let Some(pkg) = target {
                        if monitor.as_ref().is_none_or(|active| active.pkg != pkg) {
                            if let Some(mut old) = monitor.take() {
                                old.stop();
                            }
                            monitor = Some(FpsMonitor::start(pkg.clone(), None, None));
                        }
                        if let Some(active) = monitor.as_mut() {
                            active.set_output_enabled(false);
                            active.set_adaptive(true);
                        }
                        auto_pkg = Some(pkg);
                    } else if let Some(mut old) = monitor.take() {
                        old.stop();
                    }
                }
            }

            if let Some(active) = monitor.as_mut() {
                active.poll();
            } else {
                thread::sleep(Duration::from_millis(300));
            }
        }
        if let Some(mut active) = monitor.take() {
            active.stop();
        }
        Ok(())
    }

    fn read_jank_packages() -> Vec<String> {
        fs::read_to_string(JANK_BOOST_FILE)
            .unwrap_or_default()
            .lines()
            .filter_map(normalize_jank_package)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }

    fn normalize_jank_package(line: &str) -> Option<String> {
        let value = line.split('#').next()?.trim();
        let base = value.split(':').next()?.trim();
        if base.is_empty()
            || !base.contains('.')
            || !base
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'.')
        {
            return None;
        }
        Some(base.to_string())
    }

    fn selected_jank_foreground(selected: &[String]) -> Option<String> {
        if selected.is_empty() {
            return None;
        }
        read_jank_foreground()
            .filter(|pkg| selected.iter().any(|item| item == pkg))
            .or_else(|| {
                selected
                    .iter()
                    .find(|pkg| app_top_state_check(pkg).target_top_app)
                    .cloned()
            })
    }

    fn read_jank_foreground() -> Option<String> {
        let raw = fs::read_to_string(FOREGROUND_TASK_STATE_FILE).ok()?;
        let mut status = "";
        let mut focused = "";
        let mut updated = 0u64;
        for line in raw.lines() {
            let Some((key, value)) = line.split_once('=') else { continue; };
            match key.trim() {
                "status" => status = value.trim(),
                "focused_package" => focused = value.trim(),
                "updated_wall_ms" => updated = value.trim().parse().unwrap_or(0),
                _ => {}
            }
        }
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .ok()?
            .as_millis() as u64;
        if status != "ok"
            || focused.is_empty()
            || updated == 0
            || now < updated
            || now - updated > 12_000
        {
            return None;
        }
        normalize_jank_package(focused)
    }

    fn read_command() -> io::Result<Option<String>> {
        let before = match fs::metadata(FPS_CMD_FILE) {
            Ok(metadata) => metadata,
            Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(err) => return Err(err),
        };
        let text = match fs::read_to_string(FPS_CMD_FILE) {
            Ok(text) => text.trim().to_string(),
            Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(err) => return Err(err),
        };
        let after = match fs::metadata(FPS_CMD_FILE) {
            Ok(metadata) => metadata,
            Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(err) => return Err(err),
        };
        let stable = before.len() == after.len()
            && before.modified().ok() == after.modified().ok();
        if !stable {
            return Ok(None);
        }
        let valid = text.starts_with("start ") || text == "stop" || text.starts_with("stop ");
        if text.is_empty() || !valid {
            let stale = after
                .modified()
                .ok()
                .and_then(|modified| SystemTime::now().duration_since(modified).ok())
                .is_some_and(|age| age >= Duration::from_secs(2));
            if stale {
                match fs::remove_file(FPS_CMD_FILE) {
                    Ok(()) => {}
                    Err(err) if err.kind() == io::ErrorKind::NotFound => {}
                    Err(err) => return Err(err),
                }
            }
            return Ok(None);
        }
        match fs::remove_file(FPS_CMD_FILE) {
            Ok(()) => {}
            Err(err) if err.kind() == io::ErrorKind::NotFound => {}
            Err(err) => return Err(err),
        }
        Ok(Some(text))
    }

    #[derive(Clone, Debug)]
    struct PidChoice {
        pid: i32,
        is_main: bool,
        source: String,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum ForegroundHelperState {
        Target,
        Other,
        Unavailable,
    }

    fn wait_pkg_pid(pkg: &str, attempts: u32, delay: Duration) -> Option<PidChoice> {
        // 对齐 C 版 fps_wait_pkg_pid：启动目标应用后给系统一点时间创建进程，
        // 前几轮不允许子进程兜底，避免刚启动就锁到 push/MSF 等非渲染进程。
        for _ in 0..attempts {
            if let Some(choice) = find_preferred_pkg_pid(pkg, false) {
                if pid_ready_for_ebpf(choice.pid, pkg) {
                    return Some(choice);
                }
            }
            if !delay.is_zero() {
                thread::sleep(delay);
            }
        }
        find_preferred_pkg_pid(pkg, true)
            .filter(|choice| pid_ready_for_ebpf(choice.pid, pkg))
    }

    fn find_top_app_pid(pkg: &str) -> Option<PidChoice> {
        let state = crate::app_top_state_check(pkg);
        if state.target_top_app && state.target_pid > 0 {
            return Some(PidChoice {
                pid: state.target_pid,
                is_main: state.target_pid_is_main,
                source: if state.target_pid_is_main {
                    "cgroup 前台组主进程".to_string()
                } else {
                    "cgroup 前台组子进程".to_string()
                },
            });
        }
        None
    }

    fn find_preferred_pkg_pid(pkg: &str, allow_child: bool) -> Option<PidChoice> {
        // 只在启动、停帧重锁定和低频前台 PID 纠偏时调用，不在每轮 FPS poll 全量扫。
        // 优先级：ActivityTaskManager helper -> 前台 cgroup -> 包名主进程 -> 子进程兜底。
        // helper 状态新鲜时具有否决权：它明确说前台不是目标包，就不再用后台进程兜底。
        match foreground_helper_state(pkg) {
            ForegroundHelperState::Target => {
                if let Some(mut choice) = find_top_app_pid(pkg) {
                    if allow_child || choice.is_main {
                        choice.source = format!("前台助手+{}", choice.source);
                        return Some(choice);
                    }
                }
                if let Some(choice) = find_pkg_cmdline_pid(pkg, allow_child, "前台助手+") {
                    return Some(choice);
                }
                return None;
            }
            ForegroundHelperState::Other => return None,
            ForegroundHelperState::Unavailable => {}
        }

        if let Some(choice) = find_top_app_pid(pkg) {
            if allow_child || choice.is_main {
                return Some(choice);
            }
        }

        find_pkg_cmdline_pid(pkg, allow_child, "")
    }

    fn find_foreground_pkg_pid(pkg: &str) -> Option<PidChoice> {
        // 高频 FPS 校正只应该信“前台事实”，不能退到包名主进程。
        // helper 新鲜时优先由 helper 判断目标包是否仍在前台；helper 不可用时才信 cgroup。
        match foreground_helper_state(pkg) {
            ForegroundHelperState::Target => find_top_app_pid(pkg).map(|mut choice| {
                choice.source = format!("前台助手+{}", choice.source);
                choice
            }),
            ForegroundHelperState::Other => None,
            ForegroundHelperState::Unavailable => find_top_app_pid(pkg),
        }
    }

    fn pid_ready_for_ebpf(pid: i32, pkg: &str) -> bool {
        if pid <= 0 {
            return false;
        }
        let Ok(cmdline) = read_cmdline(pid) else {
            return false;
        };
        if cmdline != pkg
            && !cmdline
                .strip_prefix(pkg)
                .is_some_and(|rest| rest.starts_with(':'))
        {
            return false;
        }
        fs::read_to_string(format!("/proc/{pid}/maps"))
            .ok()
            .is_some_and(|maps| maps.lines().any(|line| line.contains("libgui.so")))
    }

    fn find_alternate_pkg_pid(pkg: &str, current_pid: i32) -> Option<PidChoice> {
        // eBPF 已经锁定一个 PID 但连续没有新帧时，尝试同包其它进程。
        // 这里仍然只返回具体 PID，避免 Android 上不可靠的全进程 uprobe。
        match foreground_helper_state(pkg) {
            ForegroundHelperState::Target => {
                if let Some(choice) = find_top_app_pid(pkg) {
                    if choice.pid > 0 && choice.pid != current_pid {
                        return Some(PidChoice {
                            pid: choice.pid,
                            is_main: choice.is_main,
                            source: format!("前台助手+{}", choice.source),
                        });
                    }
                }
                return find_alternate_pkg_cmdline_pid(pkg, current_pid, "前台助手+");
            }
            ForegroundHelperState::Other => return None,
            ForegroundHelperState::Unavailable => {}
        }

        if let Some(choice) = find_top_app_pid(pkg) {
            if choice.pid > 0 && choice.pid != current_pid {
                return Some(choice);
            }
        }

        find_alternate_pkg_cmdline_pid(pkg, current_pid, "")
    }

    fn find_alternate_pkg_cmdline_pid(
        pkg: &str,
        current_pid: i32,
        source_prefix: &str,
    ) -> Option<PidChoice> {
        let mut child_fallback = None;
        let entries = fs::read_dir("/proc").ok()?;
        for entry in entries.flatten() {
            let Some(pid) = entry
                .file_name()
                .to_str()
                .and_then(|text| text.parse::<i32>().ok())
            else {
                continue;
            };
            if pid == current_pid {
                continue;
            }

            let Ok(cmdline) = read_cmdline(pid) else {
                continue;
            };
            if cmdline == pkg {
                return Some(PidChoice {
                    pid,
                    is_main: true,
                    source: format!("{source_prefix}包名主进程重锁定"),
                });
            }
            if child_fallback.is_none()
                && cmdline
                    .strip_prefix(pkg)
                    .is_some_and(|rest| rest.starts_with(':'))
            {
                child_fallback = Some(PidChoice {
                    pid,
                    is_main: false,
                    source: format!("{source_prefix}包名子进程重锁定"),
                });
            }
        }
        child_fallback
    }

    fn find_pkg_cmdline_pid(pkg: &str, allow_child: bool, source_prefix: &str) -> Option<PidChoice> {
        let mut child_fallback = None;
        let entries = fs::read_dir("/proc").ok()?;
        for entry in entries.flatten() {
            let Some(pid) = entry
                .file_name()
                .to_str()
                .and_then(|text| text.parse::<i32>().ok())
            else {
                continue;
            };
            let Ok(cmdline) = read_cmdline(pid) else {
                continue;
            };
            if cmdline == pkg {
                return Some(PidChoice {
                    pid,
                    is_main: true,
                    source: format!("{source_prefix}包名主进程"),
                });
            }
            if child_fallback.is_none()
                && cmdline
                    .strip_prefix(pkg)
                    .is_some_and(|rest| rest.starts_with(':'))
            {
                child_fallback = Some(PidChoice {
                    pid,
                    is_main: false,
                    source: format!("{source_prefix}包名子进程回退"),
                });
            }
        }
        if allow_child { child_fallback } else { None }
    }

    fn foreground_helper_state(pkg: &str) -> ForegroundHelperState {
        let Ok(raw) = fs::read_to_string(FOREGROUND_TASK_STATE_FILE) else {
            return ForegroundHelperState::Unavailable;
        };
        let mut status = "";
        let mut focused = "";
        let mut visible = "";
        let mut updated_wall_ms = 0u64;

        for line in raw.lines() {
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            match key.trim() {
                "status" => status = value.trim(),
                "focused_package" => focused = value.trim(),
                "visible_packages" => visible = value.trim(),
                "updated_wall_ms" => updated_wall_ms = value.trim().parse().unwrap_or(0),
                _ => {}
            }
        }
        if status != "ok" {
            return ForegroundHelperState::Unavailable;
        }
        if updated_wall_ms > 0 {
            let now_ms = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|duration| duration.as_millis() as u64)
                .unwrap_or(0);
            if now_ms > updated_wall_ms
                && now_ms.saturating_sub(updated_wall_ms) > FOREGROUND_TASK_MAX_AGE_MS
            {
                return ForegroundHelperState::Unavailable;
            }
        }
        if focused == pkg || visible.split(',').any(|item| item.trim() == pkg) {
            ForegroundHelperState::Target
        } else {
            ForegroundHelperState::Other
        }
    }

    fn read_cmdline(pid: i32) -> io::Result<String> {
        let data = fs::read(format!("/proc/{pid}/cmdline"))?;
        let first = data.split(|byte| *byte == 0).next().unwrap_or_default();
        Ok(String::from_utf8_lossy(first).trim().to_string())
    }

    fn write_fps_file(fps: f64) {
        let _ = fs::create_dir_all(FPS_OUT_DIR);
        let path = PathBuf::from(FPS_OUT_FILE);
        let fresh = !path.exists();
        if fs::write(&path, format!("{fps:.1}")).is_ok() && fresh {
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o666));
            }
        }
    }
