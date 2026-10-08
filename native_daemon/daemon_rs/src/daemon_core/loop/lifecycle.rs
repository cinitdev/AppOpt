use crate::affinity::restore_all_managed_tids;
use crate::affinity::{
    load_managed_tid_journal, managed_tid_starttime_cutoff, sync_managed_tid_journal,
};
use crate::daemon_loop::cadence::regular_scan_wait_timeout;
use crate::daemon_loop::device::print_startup_device_info;
use crate::daemon_loop::round::run_daemon_round;
#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::shutdown_event_fd;
use crate::{
    calibration, fps, install_shutdown_handlers, shutdown_requested, start_daemon_socket_thread,
    Args, DaemonState, RuntimeFileChanges, RuntimeFileMonitor, RuntimeInputsCache, STATE_DIR,
    VERSION,
};
use std::time::Duration;
use std::{fs, io, thread};

pub(crate) fn daemon_loop(args: &Args) -> io::Result<()> {
    fs::create_dir_all(STATE_DIR)?;
    install_shutdown_handlers()?;
    if let Err(error) = crate::affinity::cleanup_owned_cpuset_dirs() {
        log_warn!("[RS] 旧 cpuset 空组回收未完成，保留目录: {error}");
    }
    log_info!("[RS] 启动 QixiaThreads Rust 守护 v{VERSION}");
    log_info!("[RS] 作者: 一只小柒夏");
    log_info!("[RS] 配置文件: {}", args.config.display());
    log_info!("[RS] 包名 UID 映射: {}", args.uid_map.display());
    log_info!("[RS] 检查间隔: {} 秒", args.interval_secs);
    log_info!("[RS] cpuset 运行组: /dev/cpuset/{}", args.cpuset_name);
    log_info!(
        "[RS] 目标范围: {}",
        args.target_pkg.as_deref().unwrap_or("全部配置应用")
    );
    print_startup_device_info();
    calibration::print_version_diagnostics(VERSION);
    let mut policy_topology = calibration::PolicyTopologySync::new();
    policy_topology.refresh(true);

    let mut file_monitor = RuntimeFileMonitor::new(&args.config, &args.uid_map).ok();
    log_info!(
        "[RS] 配置文件监控模式: {}",
        if file_monitor.is_some() {
            "inotify 事件通知 + 60 秒内容校验"
        } else {
            "元数据变化轮询 + 内容指纹校验"
        }
    );
    if start_daemon_socket_thread() {
        log_info!("[RS] 启用守护进程验证 socket");
    }
    let calibration_thread = calibration::start_calibration_thread();
    if calibration_thread.is_some() {
        log_info!("[RS] 启用自动校准线程");
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    let affinity_thread = crate::auto_affinity::platform::worker::start(&args.cpuset_name)?;
    #[cfg(any(target_os = "android", target_os = "linux"))]
    let history_thread = crate::auto_history::start()?;
    let fps_thread = fps::start_fps_thread();
    if fps_thread.is_some() {
        log_info!("[RS] 启用真实帧率监测线程 (多进程 eBPF / SF fallback)");
    }
    let mut state = DaemonState::default();
    match load_managed_tid_journal(&args.cpuset_name) {
        Ok(load) => {
            if let Some(warning) = load.warning.as_deref() {
                log_warn!("[RS] {warning}");
            }
            if !load.entries.is_empty() {
                log_info!(
                    "[RS] 已续接上次守护进程的线程恢复基线: {} 条",
                    load.entries.len()
                );
            }
            state.managed_tids = load.entries;
            state.managed_tid_quarantine_before_starttime =
                load.quarantine_existing.then(managed_tid_starttime_cutoff);
            // 首轮强制重写一次，用于清理同一 boot 内已经退出的旧 TID，并把头部
            // cpuset 名称同步为本次配置。
            state.managed_tid_journal_dirty = true;
            state.managed_tid_journal_loaded = true;
        }
        Err(err) => {
            log_error!("[RS] 线程恢复基线读取失败，本次仅接管可安全记录的新线程: {err}");
        }
    }
    let mut runtime = RuntimeInputsCache::default();
    let mut file_changes = RuntimeFileChanges::all();

    while !shutdown_requested() {
        policy_topology.refresh(file_changes.policy || file_changes.overflowed);
        if let Err(err) = run_daemon_round(
            args,
            &mut state,
            &mut runtime,
            file_changes,
            file_monitor.is_some(),
        ) {
            log_error!("[RS] 守护轮询失败: {err}");
        }
        if shutdown_requested() {
            break;
        }
        if let Err(err) = wait_for_daemon_wake(
            file_monitor.as_ref(),
            regular_scan_wait_timeout(args.interval_secs, &state),
        ) {
            log_error!("[RS] 守护事件等待失败，本轮退回定时检查: {err}");
            thread::sleep(Duration::from_secs(args.interval_secs));
        }
        file_changes = match file_monitor.as_mut().map(RuntimeFileMonitor::drain) {
            Some(Ok(changes)) => changes,
            Some(Err(err)) => {
                log_error!("[RS] inotify 读取失败，已转为元数据轮询: {err}");
                file_monitor = None;
                RuntimeFileChanges::all()
            }
            None => RuntimeFileChanges::default(),
        };
        if file_changes.monitor_invalidated {
            file_monitor = RuntimeFileMonitor::new(&args.config, &args.uid_map).ok();
            if file_monitor.is_none() {
                log_error!("[RS] inotify 监听已失效，后续使用元数据轮询");
            }
        }
    }

    // 校准结束并发布历史或草稿之前，样本一直保存在内存中。
    // 写入尚未完成时，不能退出进程。
    if let Some(thread) = calibration_thread {
        if thread.join().is_err() {
            log_error!("[CALIB] 校准线程退出时发生 panic");
        }
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if affinity_thread.join().is_err() {
        log_error!("[auto] 自动分配线程退出时发生 panic");
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if history_thread.join().is_err() {
        log_error!("[history] 自动记录线程退出时发生 panic");
    }
    if let Some(thread) = fps_thread {
        if thread.join().is_err() {
            log_error!("[FPS] 帧率监测线程退出时发生 panic");
        }
    }
    let (restored, pending) = restore_all_managed_tids(&mut state.managed_tids, &args.cpuset_name);
    state.managed_tid_journal_dirty = true;
    if let Err(err) = sync_managed_tid_journal(&mut state, &args.cpuset_name, true) {
        log_error!("[RS] 守护退出时保存待恢复线程失败: {err}");
    }
    if pending == 0 {
        if let Err(error) = crate::affinity::cleanup_owned_cpuset_dirs() {
            log_warn!("[RS] cpuset 空组回收未完成，保留目录: {error}");
        }
    }
    log_info!(
        "[RS] 守护进程已停止: 已恢复线程={} 待下次重试={}",
        restored, pending
    );
    Ok(())
}

pub(crate) fn wait_for_daemon_wake(
    file_monitor: Option<&RuntimeFileMonitor>,
    timeout: Duration,
) -> io::Result<()> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let monitor_fd = file_monitor
            .map(RuntimeFileMonitor::as_raw_fd)
            .unwrap_or(-1);
        let shutdown_fd = shutdown_event_fd().unwrap_or(-1);
        if monitor_fd < 0 && shutdown_fd < 0 {
            thread::sleep(timeout);
            return Ok(());
        }
        let mut poll_fds = [
            libc::pollfd {
                fd: monitor_fd,
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: shutdown_fd,
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        let timeout_ms = timeout.as_millis().min(i32::MAX as u128) as i32;
        let result = unsafe { libc::poll(poll_fds.as_mut_ptr(), poll_fds.len() as _, timeout_ms) };
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
