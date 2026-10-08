use crate::daemon_loop::execute::execute_round;
use crate::daemon_loop::prepare::prepare_round;
use crate::daemon_loop::report::report_round;
use crate::daemon_loop::scan::scan_round;
#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::{auto_affinity, base_package};
use crate::{Args, DaemonState, RuntimeFileChanges, RuntimeInputsCache};
use std::io;
#[cfg(any(target_os = "android", target_os = "linux"))]
use std::{collections::BTreeSet, fs};

pub(crate) fn run_daemon_round(
    args: &Args,
    state: &mut DaemonState,
    runtime: &mut RuntimeInputsCache,
    file_changes: RuntimeFileChanges,
    monitor_active: bool,
) -> io::Result<()> {
    // 模式交接与自动亲和性写入串行执行。先完成静态规则的恢复，
    // 前台试运行才能记录原始核心掩码。
    #[cfg(any(target_os = "android", target_os = "linux"))]
    let mut automatic = auto_affinity::platform::lock();
    #[cfg(any(target_os = "android", target_os = "linux"))]
    let automatic_stamp = fs::metadata(auto_affinity::CONFIG)
        .ok().map(|metadata| (metadata.len(), metadata.modified().ok()));
    #[cfg(any(target_os = "android", target_os = "linux"))]
    let automatic_changed = {
        let changed = automatic.refresh_config();
        if changed {
            state.auto_affinity_packages = automatic.selected.clone();
            state.runtime_rule_index_dirty = true;
            state.proc_scan_initialized = false;
        }
        changed
    };
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    let automatic_changed = false;
    // 配置交接已完成。进程发现和规则匹配均为只读操作，
    // 不能阻塞独立运行的亲和性分配线程。
    #[cfg(any(target_os = "android", target_os = "linux"))]
    drop(automatic);
    let Some(prepared) = prepare_round(
        args,
        state,
        runtime,
        file_changes,
        monitor_active,
        automatic_changed,
    )?
    else {
        return Ok(());
    };
    let scanned = scan_round(state, runtime, &prepared);
    // 只有实际写入或恢复静态规则时，才共用分配锁。
    #[cfg(any(target_os = "android", target_os = "linux"))]
    let mut automatic = auto_affinity::platform::lock();
    // 只读扫描期间模式文件可能改变。此时丢弃旧计划，
    // 由下一轮按既有的“先恢复后交接”流程处理；
    // 修改自动模式后，绝不能再写入过期的静态规则。
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if fs::metadata(auto_affinity::CONFIG)
        .ok().map(|metadata| (metadata.len(), metadata.modified().ok())) != automatic_stamp {
        return Ok(());
    }
    let applied = execute_round(args, state, runtime, &prepared, &scanned);
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if let Err(error) = crate::affinity::cleanup_owned_cpuset_dirs_after_scan(state, &args.cpuset_name,
        scanned.scan_complete && scanned.health_incomplete_packages.is_empty()) {
        log_warn!("[RS] 扫描后旧 cpuset 回收暂缓: {error}");
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if !automatic.ready && !automatic.selected.is_empty() {
        let mut blocked = state.managed_tid_quarantine_before_starttime.is_some();
        for pid in state
            .managed_tids
            .values()
            .map(|entry| entry.tgid)
            .collect::<BTreeSet<_>>()
        {
            match fs::read(format!("/proc/{pid}/cmdline")) {
                Ok(bytes) => {
                    let name = String::from_utf8_lossy(
                        bytes.split(|b| *b == 0).next().unwrap_or_default(),
                    );
                    blocked |=
                        base_package(&name).is_some_and(|pkg| automatic.selected.contains(pkg));
                }
                Err(err) if err.kind() == io::ErrorKind::NotFound => {}
                Err(_) => blocked = true,
            }
        }
        automatic.ready = !blocked;
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    drop(automatic);
    report_round(state, runtime, &prepared, &scanned, &applied);
    Ok(())
}
