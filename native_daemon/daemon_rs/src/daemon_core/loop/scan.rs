use crate::daemon_loop::cadence::pid_snapshot_log_due;
use crate::daemon_loop::model::{PreparedRound, ScannedRound};
use crate::daemon_loop::process_index::{
    merge_candidate_hits, merge_proc_scan_result, prepare_process_index_round, ProcessIndexRound,
};
use crate::{
    base_package, elapsed_realtime_ms, flush_process_index, process_belongs_to_uid_package,
    process_index_mark_candidates, process_index_verified_package_pids, scan_candidate_pids,
    scan_known_pids, scan_proc, scan_proc_packages, update_process_scan_stamp, DaemonState,
    FullScanEvidence, KnownPidScanPolicy, ProcScanResult, RuntimeInputsCache,
    ACTIVE_PROCESS_DEEP_SCAN_MS, BACKGROUND_SCAN_BUDGET_MS, SCREEN_OFF_PROCESS_DEEP_SCAN_MS,
};
use std::collections::BTreeSet;
use std::time::{Duration, Instant};

pub(super) fn scan_round(
    state: &mut DaemonState,
    runtime: &RuntimeInputsCache,
    prepared: &PreparedRound,
) -> ScannedRound {
    let PreparedRound {
        scan_clock,
        config_key,
        uid_key,
        full_scan,
        foreground_event_round,
        proc_total,
        ..
    } = *prepared;
    let focused_package = &prepared.focused_package;
    let health_scan_packages = &prepared.health_scan_packages;
    let targeted_scan_packages = &prepared.targeted_scan_packages;
    let mut scan_reason = prepared.scan_reason;
    let rules = &runtime.rules;
    let index = &runtime.index;
    let scan_started = Instant::now();
    let previous_known_pids = state.known_pids.clone();
    let growth_refresh_requested = state.proc_growth_scan_pending;
    let mut process_index_round = match prepare_process_index_round(
        state,
        scan_clock,
        full_scan || growth_refresh_requested || foreground_event_round,
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
            // 后续仍可从旧索引保留正向命中，但本轮不能把它当成完整全扫证据。
            state.process_index.snapshot_complete = false;
            if pid_snapshot_log_due(state, scan_clock) {
                log_error!("[RS] PID快照刷新失败，保留现有缓存并等待下轮重试: {err}");
            }
            ProcessIndexRound::default()
        }
    };
    if (process_index_round.view.added > 0 || process_index_round.view.exited > 0)
        && pid_snapshot_log_due(state, scan_clock)
    {
        log_info!(
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
        .map(|pkg| process_index_verified_package_pids(&state.process_index, pkg))
        .unwrap_or_default();
    let deep_scan_interval_ms = if state.interactive {
        ACTIVE_PROCESS_DEEP_SCAN_MS
    } else {
        SCREEN_OFF_PROCESS_DEEP_SCAN_MS
    };
    let mut scan_result = if full_scan {
        match scan_proc(rules, index, &state.known_pids, &state.process_index) {
            Ok(result) => result,
            Err(err) => {
                log_error!("[RS] 全量扫描失败，本轮仅保留正向结果并等待冷却重试: {err}");
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
            &state.process_index,
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
                log_error!("[RS] 包级扫描失败，本轮不产生规则健康负向证据: {err}");
                scoped_scan_evidence = Some((false, BTreeSet::new()));
                scan_result.complete = false;
            }
        }
    }

    if !full_scan {
        let dropped_pids = previous_known_pids
            .difference(&state.known_pids)
            .filter(|pid| process_index_round.view.current_pids.contains(pid))
            .copied()
            .collect::<Vec<_>>();
        if !dropped_pids.is_empty() {
            if let Err(err) = process_index_mark_candidates(
                &mut state.process_index,
                dropped_pids.iter().copied(),
                scan_clock,
            ) {
                if pid_snapshot_log_due(state, scan_clock) {
                    log_error!("[RS] 进程索引复查标记失败: {err}");
                }
            }
            process_index_round.view.candidate_pids.extend(dropped_pids);
            state.process_index_has_candidates = true;
        }
    }

    // refresh 与本轮重新标记的候选共用一次原子写盘；失败时保留 dirty，下一轮重试。
    if process_index_round.view.loaded {
        if let Err(err) = flush_process_index(&mut state.process_index, scan_clock) {
            if pid_snapshot_log_due(state, scan_clock) {
                log_error!("[RS] 进程索引批量写入失败，保留内存索引等待重试: {err}");
            }
        }
    }

    let already_scanned = scan_result
        .hits
        .iter()
        .map(|hit| hit.pid)
        .collect::<BTreeSet<_>>();
    process_index_round
        .view
        .candidate_pids
        .retain(|pid| !state.known_pids.contains(pid) && !already_scanned.contains(pid));

    let candidate_result =
        scan_candidate_pids(rules, index, &process_index_round.view.candidate_pids);
    merge_candidate_hits(&mut scan_result, candidate_result, state);
    if let Some((_, scoped_incomplete_packages)) = scoped_scan_evidence.as_mut() {
        for hit in &scan_result.hits {
            let Some(pkg) = base_package(&hit.cmdline) else {
                continue;
            };
            if targeted_scan_packages.contains(pkg) && !hit.health_scan_complete {
                scoped_incomplete_packages.insert(pkg.to_string());
            }
        }
    }
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
    let observed_owners = hits
        .iter()
        .map(|hit| hit.cmdline.clone())
        .filter(|owner| owner.contains(':'))
        .collect::<BTreeSet<_>>();
    let full_scan_evidence = if full_scan {
        Some(FullScanEvidence {
            completed_at: scan_finished_at,
            global_complete: scan_complete,
            incomplete_packages: health_incomplete_packages.clone(),
            scanned_packages: None,
            observed_owners: observed_owners.clone(),
        })
    } else {
        scoped_scan_evidence.map(|(complete, incomplete_packages)| FullScanEvidence {
            completed_at: scan_finished_at,
            global_complete: complete,
            incomplete_packages,
            scanned_packages: Some(targeted_scan_packages.clone()),
            observed_owners: observed_owners.clone(),
        })
    };
    if full_scan || !health_scan_packages.is_empty() {
        state.rule_health.note_scan_attempt(scan_finished_at);
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
    ScannedRound {
        hits,
        previous_known_pids,
        priority_pids,
        scan_finished_at,
        scan_complete,
        health_incomplete_packages,
        full_scan_evidence,
        scan_elapsed,
        scan_reason,
    }
}
