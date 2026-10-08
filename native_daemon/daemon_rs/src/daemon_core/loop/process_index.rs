use crate::daemon_loop::cadence::pid_snapshot_interval_ms;
use crate::{
    base_package, load_process_index_view, publish_process_index, refresh_process_index, CandidateScanResult, DaemonState,
    ProcScanResult, ProcessIndexView,
};
use std::io;

#[derive(Debug, Default)]
pub(crate) struct ProcessIndexRound {
    pub(super) view: ProcessIndexView,
}

pub(crate) fn prepare_process_index_round(
    state: &mut DaemonState,
    now_elapsed: u64,
    force: bool,
    rebuild_all: bool,
) -> io::Result<ProcessIndexRound> {
    let interval = pid_snapshot_interval_ms(state);
    let due = force
        || !state.process_index_initialized
        || state
            .last_pid_snapshot_elapsed_ms
            .is_none_or(|last| now_elapsed >= last && now_elapsed.saturating_sub(last) >= interval);
    let view = if due {
        refresh_process_index(
            &mut state.process_index,
            now_elapsed,
            rebuild_all || !state.process_index_initialized,
        )?
    } else if state.process_index_has_candidates {
        match load_process_index_view(&mut state.process_index, now_elapsed) {
            Ok(view) => view,
            Err(_) => refresh_process_index(&mut state.process_index, now_elapsed, true)?,
        }
    } else {
        ProcessIndexView::default()
    };
    let round = ProcessIndexRound { view };
    if round.view.refreshed {
        publish_process_index(&state.process_index);
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

pub(crate) fn merge_candidate_hits(
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

pub(crate) fn merge_proc_scan_result(
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
