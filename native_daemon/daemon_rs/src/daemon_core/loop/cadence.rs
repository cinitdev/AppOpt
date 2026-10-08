use crate::{
    elapsed_realtime_ms, DaemonState, ACTIVE_FULL_SCAN_INTERVAL_MS, PID_SNAPSHOT_ACTIVE_MS,
    PID_SNAPSHOT_IDLE_MS, PID_SNAPSHOT_LOG_INTERVAL_MS, SCREEN_OFF_FULL_SCAN_INTERVAL_MS,
    SCREEN_OFF_SCAN_INTERVAL_MS,
};
use std::time::Duration;

pub(crate) fn pid_snapshot_interval_ms(state: &DaemonState) -> u64 {
    if state.interactive && state.auto_affinity_foreground {
        1_000
    } else if state.interactive {
        PID_SNAPSHOT_ACTIVE_MS
    } else {
        PID_SNAPSHOT_IDLE_MS
    }
}

pub(crate) fn update_automatic_foreground(state: &mut DaemonState, focused_package: Option<&str>) {
    state.auto_affinity_foreground = state.interactive
        && focused_package.is_some_and(|pkg| state.auto_affinity_packages.contains(pkg));
}

pub(crate) fn update_interactive_mode(state: &mut DaemonState, observed: Option<bool>) {
    if let Some(interactive) = observed {
        state.interactive = interactive;
        state.interactive_known = true;
    } else if !state.interactive_known {
        state.interactive = true;
    }
}

pub(crate) fn regular_scan_interval_ms(interval_secs: u64, state: &DaemonState) -> u64 {
    if state.interactive && state.auto_affinity_foreground {
        // 自动分配线程没有静态规则动作或 known_pids；
        // 应及时更新共享进程索引，不等待空闲时的磁盘缓存节奏。
        1_000
    } else if !state.interactive || (state.known_pids.is_empty() && !state.process_index_has_candidates) {
        // 没有存活的受管目标时，foreground_task.state 仍能通过 inotify
        // 唤醒线程，及时处理应用启动；无关后台进程的频繁增删
        // 则按空闲节奏统一检查。
        SCREEN_OFF_SCAN_INTERVAL_MS
    } else {
        interval_secs.max(1).saturating_mul(1000)
    }
}

pub(crate) fn regular_scan_due(interval_secs: u64, state: &DaemonState, now_elapsed: u64) -> bool {
    let interval = regular_scan_interval_ms(interval_secs, state);
    state
        .last_regular_scan_elapsed_ms
        .is_none_or(|last| now_elapsed < last || now_elapsed.saturating_sub(last) >= interval)
}

pub(crate) fn periodic_full_scan_due(state: &DaemonState, now_elapsed: u64) -> bool {
    let interval = if state.interactive {
        ACTIVE_FULL_SCAN_INTERVAL_MS
    } else {
        SCREEN_OFF_FULL_SCAN_INTERVAL_MS
    };
    state
        .last_full_scan_elapsed_ms
        .is_none_or(|last| now_elapsed < last || now_elapsed.saturating_sub(last) >= interval)
}

pub(crate) fn regular_scan_wait_timeout(interval_secs: u64, state: &DaemonState) -> Duration {
    let interval = regular_scan_interval_ms(interval_secs, state);
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

pub(crate) fn pid_snapshot_log_due(state: &mut DaemonState, now_elapsed: u64) -> bool {
    let due = state.last_pid_snapshot_log_elapsed_ms.is_none_or(|last| {
        now_elapsed >= last && now_elapsed.saturating_sub(last) >= PID_SNAPSHOT_LOG_INTERVAL_MS
    });
    if due {
        state.last_pid_snapshot_log_elapsed_ms = Some(now_elapsed);
    }
    due
}
