use crate::affinity::refresh_managed_tid_cache;
use crate::affinity::sync_managed_tid_journal;
use crate::affinity::{apply_hits, ApplyPolicy};
use crate::affinity::{verify_managed_affinity, AffinityVerifyPolicy};
use crate::daemon_loop::model::{AppliedRound, PreparedRound, ScannedRound};
use crate::{
    Args, DaemonState, RuntimeInputsCache, RUNTIME_CHANGE_LOG_INTERVAL_MS,
    RUNTIME_SUMMARY_LOG_INTERVAL_MS,
};
use std::path::Path;
use std::time::Instant;

pub(super) fn execute_round(
    args: &Args,
    state: &mut DaemonState,
    runtime: &RuntimeInputsCache,
    prepared: &PreparedRound,
    scanned: &ScannedRound,
) -> AppliedRound {
    let PreparedRound {
        scan_clock,
        config_changed,
        rule_config_changed,
        full_scan,
        ..
    } = *prepared;
    let hits = &scanned.hits;
    let previous_known_pids = &scanned.previous_known_pids;
    let health_incomplete_packages = &scanned.health_incomplete_packages;
    let full_scan_evidence = &scanned.full_scan_evidence;
    let priority_pids = &scanned.priority_pids;
    let targeted_scan_packages = &prepared.targeted_scan_packages;
    let foreground_state = &prepared.foreground_state;
    let scan_complete = scanned.scan_complete;
    let scan_finished_at = scanned.scan_finished_at;
    let rules = &runtime.rules;
    let managed_journal_changed = refresh_managed_tid_cache(
        state,
        &hits,
        prepared.index_rebuilt && scan_complete && health_incomplete_packages.is_empty(),
        &args.cpuset_name,
    );
    state.managed_tid_journal_dirty |= managed_journal_changed;
    let managed_journal_synced = match sync_managed_tid_journal(state, &args.cpuset_name, false) {
        Ok(()) => true,
        Err(err) => {
            // 已持久化的旧线程仍可继续验证/恢复；本轮新记录保持 restore_persisted=false，
            // apply 阶段只跳过这些新线程，下一轮写盘成功后自动开始接管。
            log_error!("[RS] 线程恢复基线写入失败，新线程等待下轮重试: {err}");
            false
        }
    };

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
            scan_clock < last || scan_clock.saturating_sub(last) >= RUNTIME_CHANGE_LOG_INTERVAL_MS
        });
    let periodic_summary_due = last_summary.is_some_and(|last| {
        scan_clock < last || scan_clock.saturating_sub(last) >= RUNTIME_SUMMARY_LOG_INTERVAL_MS
    });
    let detail_log = forced_summary || state_change_summary_due || periodic_summary_due;
    let hit_preview_log = config_changed || first_summary;

    if let Err(err) = state.rule_health.update(
        rules,
        &hits,
        full_scan_evidence.as_ref(),
        args.target_pkg.as_deref(),
        rule_config_changed,
        &foreground_state,
        scan_clock,
    ) {
        log_error!("[RS] 规则健康状态更新失败: {err}");
    }

    let apply_started = Instant::now();
    let base_cpuset = Path::new("/dev/cpuset").join(&args.cpuset_name);
    let interactive = state.interactive;
    let managed_count_before_apply = state.managed_tids.len();
    let mut stats = apply_hits(
        &hits,
        &mut state.managed_tids,
        ApplyPolicy {
            detail_log,
            cpuset_name: &args.cpuset_name,
            now_elapsed: scan_finished_at,
            foreground_pids: &priority_pids,
            interactive,
            require_restore_baseline: true,
        },
    );
    let (verify_stats, next_verify_cursor) = verify_managed_affinity(
        &mut state.managed_tids,
        AffinityVerifyPolicy {
            foreground_pids: &priority_pids,
            interactive,
            now_elapsed: scan_finished_at,
            detail_log,
            base_cpuset: &base_cpuset,
            cpuset_name: &args.cpuset_name,
            start_cursor: state.affinity_verify_cursor,
        },
    );
    state.affinity_verify_cursor = next_verify_cursor;
    stats.merge(verify_stats);
    if state.managed_tids.len() != managed_count_before_apply {
        state.managed_tid_journal_dirty = true;
    }
    if managed_journal_synced {
        if let Err(err) = sync_managed_tid_journal(state, &args.cpuset_name, false) {
            // 这里只会清理已经退出的旧 TID；旧文件保留其超集仍可安全恢复。
            log_error!("[RS] 线程恢复基线收尾写入失败，将在下轮重试: {err}");
        }
    }
    let apply_elapsed = apply_started.elapsed();
    AppliedRound {
        stats,
        apply_elapsed,
        detail_log,
        hit_preview_log,
        known_pids,
        processes,
    }
}
