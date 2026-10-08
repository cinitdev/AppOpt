use super::*;
use crate::command_protocol::{CommandFile, FpsCommand, FpsRequest};

// FPS 命令循环。
//
// App 写入 fps.cmd，daemon 原子认领后读取并删除：
// - start <pkg> [socket token]
// - stop
//
// socket/token 是 App 侧实时 FPS 通道；如果没有提供或连接失败，就回落到 files/fps。
// 这里不解析更多复杂参数，避免命令文件变成半套 IPC 协议。
pub(super) fn fps_loop() -> io::Result<()> {
    let mut monitor: Option<FpsMonitor> = None;
    let mut manual: Option<FpsRequest> = None;
    let mut recent = crate::recent_usage::Tracker::default();
    let mut boot = fs::read_to_string("/proc/sys/kernel/random/boot_id").unwrap_or_default();
    let mut calibrating = false;
    let mut calibration_checked: Option<Instant> = None;

    let mut manual_fallback = super::manual_gate::ManualFallback::default();
    let mut last_check = Instant::now() - Duration::from_secs(60);
    let mut commands = CommandFile::new(FPS_CMD_FILE);
    let mut command_monitor = crate::CommandFileMonitor::new(&[
        FPS_CMD_FILE, commands.claimed_path().to_str().expect("static command path"),

        FOREGROUND_TASK_STATE_FILE,
        crate::auto_history::STATUS,
        "/data/adb/modules/QixiaThreads/config/calibrate.state",
    ]).ok();
    let mut last_error: Option<Instant> = None;
    while !crate::shutdown_requested() {
        let command = match commands.read(FpsCommand::parse) {
            Ok(value) => value,
            Err(err) => {
                if last_error.is_none_or(|t| t.elapsed() >= Duration::from_secs(30)) {
                    log_error!("[FPS] 读取命令失败: {err}"); last_error = Some(Instant::now());
                }
                None
            }
        };
        if let Some(command) = command {
            manual_fallback.reset();
            calibration_checked = None;
            match command {
                FpsCommand::Start(request) => {
                    // 为已在监测的应用打开悬浮窗时，只改变输出通道，
                    // 不改变已有的 BPF 挂载。
                    if let Some(active) = monitor.as_mut().filter(|m| m.pkg == request.pkg) {
                        active.socket = FpsSocket::new(request.socket.clone(), request.token.clone());
                    }
                    manual = Some(request);
                }
                FpsCommand::Stop => {
                    manual = None;
                }
            }
            last_check = Instant::now() - Duration::from_secs(60);
        }
        let interval = if monitor.is_some() || recent.active() { Duration::from_millis(500) } else { Duration::from_secs(10) };
        let event = command_monitor.as_mut().is_some_and(|m|m.drain().unwrap_or(false));
        let managed_changed = crate::recent_usage::drain_managed_signal();
        if event || managed_changed || last_check.elapsed() >= interval {
            last_check = Instant::now();
            if boot.is_empty() { boot = fs::read_to_string("/proc/sys/kernel/random/boot_id").unwrap_or_default(); }
            if event || calibration_checked.is_none_or(|last| last.elapsed() >= Duration::from_secs(2)) {
                calibrating = calibration_sampling();
                calibration_checked = Some(Instant::now());
            }
            let elapsed = crate::elapsed_realtime_ms();
            let foreground = read_recent_foreground(elapsed, boot.trim());
            let mode = match &foreground {
                crate::recent_usage::Foreground::Package(package) if !calibrating => crate::recent_usage::managed_mode(package),
                _ => None,
            };
            recent.observe(foreground, mode, epoch_ms(), elapsed);
            if let Err(error) = recent.persist(elapsed, false) {
                log_warn!("[FPS] 最近使用摘要暂未保存，将重试: {error}");
            }
            let manual_pkg = manual.as_ref().filter(|request| {
                match foreground_helper_state(&request.pkg) {
                    ForegroundHelperState::Target => { manual_fallback.reset(); true },
                    ForegroundHelperState::Other => { manual_fallback.reset(); false },
                    ForegroundHelperState::Unavailable => manual_fallback.allows(Instant::now(), || {
                        manual_owner_alive(&request.pkg) && crate::app_top_state_check(&request.pkg).target_top_app
                    }),
                }
            }).map(|request| request.pkg.clone());
            // 摘要监测不增加 CPU、温度采集或历史写入；
            // 用户明确请求的手动监测或校准输出始终优先。
            let wanted = manual_pkg.or_else(|| crate::auto_history::recording_package()
                .filter(|pkg| foreground_helper_state(pkg) == ForegroundHelperState::Target))
                .or_else(|| recent.target(elapsed).map(str::to_owned));
            let mut started = false;
            if monitor.as_ref().map(|m|m.pkg.as_str()) != wanted.as_deref() {
                if let Some(mut old) = monitor.take() { old.stop("目标离开前台或状态失效"); }
                monitor = wanted.as_ref().map(|pkg| {
                    let request = manual.as_ref().filter(|m| &m.pkg == pkg);
                    FpsMonitor::start(pkg.clone(), request.and_then(|m|m.socket.clone()), request.and_then(|m|m.token.clone()))
                });
                started = monitor.is_some();
            }
            if let Some(active) = monitor.as_mut() {
                active.set_output_enabled(manual.as_ref().is_some_and(|m|m.pkg == active.pkg));
                active.summary_enabled = recent.target(elapsed) == Some(active.pkg.as_str());
            }
            // 查找 PID 或挂载监测器可能短暂阻塞；读取新监测器的采样前，
            // 需要重新确认目标应用仍在前台。
            if started { last_check = Instant::now() - Duration::from_secs(60); continue; }
        }
        if let Some(active) = monitor.as_mut() {
            active.poll();
            if let Some(fps) = active.summary_sample.take() { recent.sample(&active.pkg, fps); }
        }
        crate::auto_affinity::platform::worker::publish_feedback(
            monitor.as_ref().filter(|m| m.output_enabled).map(|m| m.pkg.as_str()),
            monitor.as_ref().filter(|m| m.output_enabled).and_then(FpsMonitor::affinity_feedback),
        );
        if monitor.is_none() {
            let timeout = if recent.active() { Duration::from_millis(500) } else { Duration::from_secs(10) };
            let result = command_monitor.as_mut().map(|m| m.wait_with_signal(commands.wait_timeout(timeout), crate::recent_usage::managed_signal()));
            if matches!(result, Some(Err(_))) { command_monitor = None; }
            if command_monitor.is_none() { thread::sleep(timeout.min(Duration::from_secs(1))); }
            // 等待过程会消费 inotify 事件；即使下一次读取已无剩余事件，
            // 唤醒后也要重新读取来源状态。
            calibration_checked = None;
            last_check = Instant::now() - Duration::from_secs(60);
        }
    }
    if let Some(mut active) = monitor.take() { active.stop("守护进程退出"); }
    recent.finish(Some(epoch_ms()));
    if let Err(error) = recent.persist(crate::elapsed_realtime_ms(), true) { log_warn!("[FPS] 退出时保存最近使用摘要失败: {error}"); }
    crate::auto_affinity::platform::worker::publish_feedback(None, None);
    Ok(())
}

fn epoch_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

fn read_recent_foreground(elapsed_ms: u64, boot: &str) -> crate::recent_usage::Foreground {
    let read = || -> io::Result<String> {
        let mut text = String::new();
        fs::File::open(FOREGROUND_TASK_STATE_FILE)?.take(16 * 1024 + 1).read_to_string(&mut text)?;
        Ok(text)
    };
    read().map(|text| crate::recent_usage::foreground(&text, elapsed_ms, boot))
        .unwrap_or(crate::recent_usage::Foreground::Unknown)
}

fn calibration_sampling() -> bool {
    let mut text = String::new();
    fs::File::open("/data/adb/modules/QixiaThreads/config/calibrate.state")
        .and_then(|file| file.take(4096).read_to_string(&mut text)).is_ok()
        && text.trim_start().starts_with("sampling ")
}

pub(super) fn manual_owner_alive(pkg: &str) -> bool {
    let Ok(raw) = fs::read_to_string("/data/adb/modules/QixiaThreads/config/state/floating_ball.lease") else { return false; };
    let fields: std::collections::BTreeMap<_, _> = raw.lines().filter_map(|l| l.split_once('=')).collect();
    if fields.get("target_package") != Some(&pkg) { return false; }
    let Ok(boot) = fs::read_to_string("/proc/sys/kernel/random/boot_id") else { return false; };
    if fields.get("boot_id") != Some(&boot.trim()) { return false; }
    let Some(pid) = fields.get("pid").and_then(|v| v.parse::<i32>().ok()).filter(|p| *p > 0) else { return false; };
    if read_cmdline(pid).ok().as_deref() != Some("top.qixia.threads") { return false; }
    let Some(expected) = fields.get("process_starttime").and_then(|v| v.parse::<u64>().ok()) else { return false; };
    fs::read_to_string(format!("/proc/{pid}/stat")).ok().and_then(|stat| {
        stat.rsplit_once(')').and_then(|(_, rest)| rest.split_whitespace().nth(19)).and_then(|v| v.parse::<u64>().ok())
    }) == Some(expected)
}

#[derive(Clone, Debug)]
pub(super) struct PidChoice {
    pub(super) pid: i32,
    pub(super) is_main: bool,
    pub(super) source: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum ForegroundHelperState {
    Target,
    Other,
    Unavailable,
}

pub(super) fn wait_pkg_pid(pkg: &str, attempts: u32, delay: Duration) -> Option<PidChoice> {
    // 启动目标应用后给系统一点时间创建进程，
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

pub(super) fn find_top_app_pid(pkg: &str) -> Option<PidChoice> {
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

pub(super) fn find_preferred_pkg_pid(pkg: &str, allow_child: bool) -> Option<PidChoice> {
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

pub(super) fn pid_ready_for_ebpf(pid: i32, pkg: &str) -> bool {
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
    match fs::read_to_string(format!("/proc/{pid}/maps")) {
        Ok(maps) => maps.lines().any(|line| line.contains("libgui.so")),
        Err(err) if err.kind() == io::ErrorKind::PermissionDenied => {
            // Android 17 开始，即使 Magisk Root 也可能被 procfs/SELinux 禁止读取
            // 其他应用的 maps。bridge 仍能用系统绝对路径附加 libgui，因此这里
            // 只确认进程至少有可枚举线程，并且设备存在可用的系统 libgui。
            fs::read_dir(format!("/proc/{pid}/task"))
                .ok()
                .and_then(|mut entries| entries.next())
                .is_some()
                && process_libgui_exists(pid)
        }
        Err(_) => false,
    }
}

pub(super) fn process_libgui_exists(pid: i32) -> bool {
    let is_64_bit = (|| {
        let mut file = fs::File::open(format!("/proc/{pid}/exe")).ok()?;
        let mut header = [0u8; 5];
        file.read_exact(&mut header).ok()?;
        if header[..4] != *b"\x7fELF" {
            return None;
        }
        match header[4] {
            1 => Some(false),
            2 => Some(true),
            _ => None,
        }
    })();
    let paths = match is_64_bit {
        Some(false) => ["/system/lib/libgui.so", "/system_ext/lib/libgui.so"].as_slice(),
        _ => [
            "/system/lib64/libgui.so",
            "/system_ext/lib64/libgui.so",
        ]
        .as_slice(),
    };
    paths.iter().any(|path| fs::metadata(path).is_ok())
}

pub(super) fn collect_pkg_ebpf_pids(
    pkg: &str,
    known: &BTreeSet<i32>,
    allow_full_scan: bool,
) -> BTreeSet<i32> {
    // 常规刷新优先复用 daemon 已维护的 pid_cache.tsv，只对目标包候选做身份
    // 与 libgui 就绪检查。完整 /proc 遍历仅在缓存为空或低频恢复校验时执行。
    let mut candidates = process_index_find_package_pids(pkg).unwrap_or_default();
    for pid in known.iter().copied() {
        if read_cmdline(pid).ok().is_some_and(|cmdline| {
            cmdline == pkg
                || cmdline
                    .strip_prefix(pkg)
                    .is_some_and(|suffix| suffix.starts_with(':'))
        }) {
            candidates.insert(pid);
        }
    }
    if let Some(choice) = find_foreground_pkg_hint(pkg) {
        candidates.insert(choice.pid);
    }
    if allow_full_scan || candidates.is_empty() {
        candidates.extend(scan_pkg_ebpf_pids(pkg));
    }
    candidates
        .into_iter()
        .filter(|pid| known.contains(pid) || pid_ready_for_ebpf(*pid, pkg))
        .collect()
}

pub(super) fn find_foreground_pkg_hint(pkg: &str) -> Option<PidChoice> {
    // 高频目标集合刷新只读取 helper/cgroup，不在这里退回全 /proc。包名兜底由
    // pid_cache 和低频 scan_pkg_ebpf_pids 负责。
    match foreground_helper_state(pkg) {
        ForegroundHelperState::Target => find_top_app_pid(pkg).map(|mut choice| {
            choice.source = format!("前台助手+{}", choice.source);
            choice
        }),
        ForegroundHelperState::Other => None,
        ForegroundHelperState::Unavailable => find_top_app_pid(pkg),
    }
}

pub(super) fn scan_pkg_ebpf_pids(pkg: &str) -> BTreeSet<i32> {
    let mut pids = BTreeSet::new();
    let Ok(entries) = fs::read_dir("/proc") else {
        return pids;
    };
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
        let belongs_to_pkg = cmdline == pkg
            || cmdline
                .strip_prefix(pkg)
                .is_some_and(|suffix| suffix.starts_with(':'));
        if belongs_to_pkg && pid_ready_for_ebpf(pid, pkg) {
            pids.insert(pid);
        }
    }
    pids
}

pub(super) fn find_pkg_cmdline_pid(pkg: &str, allow_child: bool, source_prefix: &str) -> Option<PidChoice> {
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

pub(super) fn foreground_helper_state(pkg: &str) -> ForegroundHelperState {
    let Ok(raw) = fs::read_to_string(FOREGROUND_TASK_STATE_FILE) else {
        return ForegroundHelperState::Unavailable;
    };
    let mut status = "";
    let mut focused = "";
    let mut visible = "";
    let mut updated_elapsed_ms = 0u64;
    let mut interactive = "";
    let mut boot_id = "";

    for line in raw.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "status" => status = value.trim(),
            "focused_package" => focused = value.trim(),
            "visible_packages" => visible = value.trim(),
            "updated_elapsed_ms" => updated_elapsed_ms = value.trim().parse().unwrap_or(0),
            "interactive" => interactive = value.trim(),
            "boot_id" => boot_id = value.trim(),
            _ => {}
        }
    }
    let now_ms = crate::elapsed_realtime_ms();
    if updated_elapsed_ms == 0
        || now_ms == 0
        || updated_elapsed_ms > now_ms
        || now_ms - updated_elapsed_ms > FOREGROUND_TASK_MAX_AGE_MS
        || boot_id.is_empty()
        || fs::read_to_string("/proc/sys/kernel/random/boot_id").ok().is_none_or(|boot| boot.trim() != boot_id)
    {
        return ForegroundHelperState::Unavailable;
    }
    if interactive == "0" || status == "empty" { return ForegroundHelperState::Other; }
    if status != "ok" || interactive != "1" { return ForegroundHelperState::Unavailable; }
    if focused == pkg || visible.split(',').any(|item| item.trim() == pkg) {
        ForegroundHelperState::Target
    } else {
        ForegroundHelperState::Other
    }
}

pub(super) fn read_cmdline(pid: i32) -> io::Result<String> {
    let data = fs::read(format!("/proc/{pid}/cmdline"))?;
    let first = data.split(|byte| *byte == 0).next().unwrap_or_default();
    Ok(String::from_utf8_lossy(first).trim().to_string())
}

pub(super) fn write_fps_file(fps: f64) {
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
