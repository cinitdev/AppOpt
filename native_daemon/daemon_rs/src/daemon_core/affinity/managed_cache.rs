use crate::affinity::diagnostics::{error_text_zh, is_thread_gone_error};
use crate::affinity::mask::CpuMask;
use crate::affinity::restore::{capture_tid_restore_state, restore_managed_tid};
use crate::{elapsed_realtime_ms, DaemonState, ManagedTidEntry, ProcHit, MAX_ERROR_DETAILS_PER_ROUND, MAX_MANAGED_TIDS};
use std::collections::{HashMap, HashSet};
use std::io;

enum RestoreAttempt {
    Deferred,
    Removed,
    Failed(io::Error),
}

fn retry_managed_restore(
    entries: &mut HashMap<i32, ManagedTidEntry>,
    tid: i32,
    now: u64,
    confirmed_reuse: bool,
    restore: impl FnOnce(&ManagedTidEntry) -> io::Result<()>,
) -> RestoreAttempt {
    let Some(entry) = entries.get_mut(&tid) else { return RestoreAttempt::Deferred; };
    if entry.restore_pending && !confirmed_reuse && now < entry.restore_retry_after_elapsed_ms {
        return RestoreAttempt::Deferred;
    }
    match restore(entry) {
        Ok(()) => { entries.remove(&tid); RestoreAttempt::Removed }
        Err(error) if is_thread_gone_error(&error) => {
            entries.remove(&tid);
            RestoreAttempt::Removed
        }
        Err(error) => {
            entry.restore_pending = true;
            entry.restore_failure_count = entry.restore_failure_count.saturating_add(1);
            let delay = (2_000u64 << entry.restore_failure_count.saturating_sub(1).min(5)).min(60_000);
            entry.restore_retry_after_elapsed_ms = now.saturating_add(delay);
            // 验证阶段不能继续重新应用已删除的规则。
            entry.desired_mask_low64 = None;
            entry.verified_mask_low64 = None;
            entry.cpuset_synced = false;
            entry.next_affinity_check_elapsed_ms = 0;
            RestoreAttempt::Failed(error)
        }
    }
}

pub(crate) fn refresh_managed_tid_cache(
    state: &mut DaemonState,
    hits: &[ProcHit],
    reconcile_all: bool,
    cpuset_name: &str,
) -> bool {
    let seen_round = state.round_index.saturating_add(1);
    let mut journal_changed = false;
    let observed = hits
        .iter()
        .flat_map(|hit| {
            hit.actions.iter().map(move |action| {
                (
                    action.tid,
                    (hit.pid, hit.pid_starttime, action.tid_starttime),
                )
            })
        })
        .collect::<HashMap<_, _>>();
    let complete_tgids = hits
        .iter()
        .filter(|hit| hit.health_scan_complete)
        .map(|hit| hit.pid)
        .collect::<HashSet<_>>();
    let removed = state
        .managed_tids
        .iter()
        .filter(|(tid, entry)| {
            let observation = observed.get(tid);
            let still_observed = observation.is_some_and(|observation| {
                managed_identity_matches_observation(entry, *observation)
            });
            let confirmed_reuse = observation.is_some_and(|observation| {
                managed_identity_conflicts_with_observation(entry, *observation)
            });
            !still_observed
                && (confirmed_reuse
                    || entry.restore_pending
                    || reconcile_all
                    || complete_tgids.contains(&entry.tgid)
                    || !state.known_pids.contains(&entry.tgid))
        })
        .map(|(tid, entry)| (*tid, entry.clone()))
        .collect::<Vec<_>>();
    let mut restore_retries = 0usize;
    let now = elapsed_realtime_ms();
    for (tid, entry) in removed {
        let reused = observed.get(&tid).is_some_and(|value|
            managed_identity_conflicts_with_observation(&entry, *value));
        match retry_managed_restore(&mut state.managed_tids, tid, now, reused,
            |entry| restore_managed_tid(tid, entry, cpuset_name)) {
            RestoreAttempt::Deferred => {}
            RestoreAttempt::Removed => journal_changed = true,
            RestoreAttempt::Failed(err) => {
                if restore_retries < MAX_ERROR_DETAILS_PER_ROUND {
                    log_error!(
                        "[RS] 规则移除后恢复未完成，保留基线并退避重试 进程={} 线程={} 错误={}",
                        entry.tgid, tid, error_text_zh(&err)
                    );
                }
                restore_retries = restore_retries.saturating_add(1);
            }
        }
    }
    if restore_retries > MAX_ERROR_DETAILS_PER_ROUND {
        log_error!(
            "[RS] 规则移除后恢复线程状态失败: 本轮共 {} 条保留基线重试, 仅显示前 {} 条明细",
            restore_retries, MAX_ERROR_DETAILS_PER_ROUND
        );
    }

    let quarantine_before_starttime = state.managed_tid_quarantine_before_starttime;
    let managed_tids = &mut state.managed_tids;
    let mut capacity_skips = 0usize;

    for hit in hits {
        for action in &hit.actions {
            let cached = managed_tids.get(&action.tid).cloned().filter(|current| {
                managed_identity_matches_observation(
                    current,
                    (hit.pid, hit.pid_starttime, action.tid_starttime),
                )
            });
            if managed_tid_identity_quarantined(
                cached.is_some(),
                action.tid_starttime,
                quarantine_before_starttime,
            ) {
                continue;
            }
            if cached.is_none() && managed_tids.contains_key(&action.tid) {
                // 同一个数值 TID 仍有无法确认身份的旧恢复记录时，先保留旧记录等待
                // 完整扫描或身份复核，不能用新观察覆盖唯一基线。
                continue;
            }
            if hit.pid_starttime.is_none() || action.tid_starttime.is_none() {
                // starttime 是防 PID/TID 复用的唯一稳定身份。瞬时读不到时只续期已有
                // 记录，绝不能把当前已受控状态重新当成“接管前基线”。
                if let Some(current) = managed_tids.get_mut(&action.tid).filter(|current| {
                    managed_identity_matches_observation(
                        current,
                        (hit.pid, hit.pid_starttime, action.tid_starttime),
                    )
                }) {
                    current.last_seen_round = seen_round;
                }
                continue;
            }
            if cached.is_none() && !managed_tid_capacity_available(managed_tids, action.tid) {
                // 达到保护上限后宁可暂缓新线程，也不能丢弃仍在使用的恢复基线。
                // 下一轮旧线程退出或规则移除后会自然释放容量。
                capacity_skips = capacity_skips.saturating_add(1);
                continue;
            }
            let next_mask_low64 = CpuMask::parse(&action.cpus).and_then(|mask| mask.to_low64());
            let cpuset_synced = cached.as_ref().is_some_and(|current| {
                current.tgid == hit.pid
                    && current.starttime == action.tid_starttime
                    && current.cpuset_synced
                    && current.desired_mask_low64 == next_mask_low64
            });
            let restore_state = cached
                .as_ref()
                .map(|current| (current.original_mask_low64, current.original_cpuset.clone()))
                .or_else(|| capture_tid_restore_state(hit, action, cpuset_name));
            let Some((original_mask_low64, original_cpuset)) = restore_state else {
                // 无法可靠记录恢复基线时不把该线程纳入 managed_tids；apply 阶段也会
                // 因缺少受管记录而跳过，避免产生无法回滚的半接管状态。
                continue;
            };
            let next = ManagedTidEntry {
                tgid: hit.pid,
                tgid_starttime: hit.pid_starttime,
                starttime: action.tid_starttime,
                last_seen_round: seen_round,
                cpuset_synced,
                cpuset_failure_count: cached
                    .as_ref()
                    .map_or(0, |current| current.cpuset_failure_count),
                cpuset_retry_after_elapsed_ms: cached
                    .as_ref()
                    .map_or(0, |current| current.cpuset_retry_after_elapsed_ms),
                desired_mask_low64: cached
                    .as_ref()
                    .and_then(|current| current.desired_mask_low64),
                verified_mask_low64: cached
                    .as_ref()
                    .and_then(|current| current.verified_mask_low64),
                last_affinity_check_elapsed_ms: cached
                    .as_ref()
                    .map_or(0, |current| current.last_affinity_check_elapsed_ms),
                next_affinity_check_elapsed_ms: cached
                    .as_ref()
                    .map_or(0, |current| current.next_affinity_check_elapsed_ms),
                original_mask_low64,
                original_cpuset,
                restore_persisted: cached
                    .as_ref()
                    .is_some_and(|current| current.restore_persisted),
                restore_pending: false,
                restore_failure_count: 0,
                restore_retry_after_elapsed_ms: 0,
            };
            let should_update = managed_tids.get(&action.tid).is_none_or(|current| {
                current.tgid != next.tgid
                    || current.tgid_starttime != next.tgid_starttime
                    || current.starttime != next.starttime
            });
            if should_update {
                managed_tids.insert(action.tid, next);
                journal_changed = true;
            } else if let Some(current) = managed_tids.get_mut(&action.tid) {
                current.last_seen_round = seen_round;
                current.restore_pending = false;
                current.restore_failure_count = 0;
                current.restore_retry_after_elapsed_ms = 0;
            }
        }
    }

    if capacity_skips > 0 {
        log_error!(
            "[RS] 受管线程已达到安全上限 {}，本轮暂缓接管 {} 个新线程",
            MAX_MANAGED_TIDS, capacity_skips
        );
    }
    journal_changed
}

pub(crate) fn managed_identity_matches_observation(
    entry: &ManagedTidEntry,
    observation: (i32, Option<u64>, Option<u64>),
) -> bool {
    let (tgid, tgid_starttime, tid_starttime) = observation;
    entry.tgid == tgid
        && tgid_starttime.is_none_or(|value| entry.tgid_starttime == Some(value))
        && tid_starttime.is_none_or(|value| entry.starttime == Some(value))
}

pub(crate) fn managed_identity_conflicts_with_observation(
    entry: &ManagedTidEntry,
    observation: (i32, Option<u64>, Option<u64>),
) -> bool {
    let (tgid, tgid_starttime, tid_starttime) = observation;
    entry.tgid != tgid
        || tgid_starttime.is_some_and(|value| entry.tgid_starttime.is_some_and(|old| old != value))
        || tid_starttime.is_some_and(|value| entry.starttime.is_some_and(|old| old != value))
}

pub(crate) fn managed_tid_capacity_available(
    managed_tids: &HashMap<i32, ManagedTidEntry>,
    tid: i32,
) -> bool {
    managed_tids.contains_key(&tid) || managed_tids.len() < MAX_MANAGED_TIDS
}

pub(crate) fn managed_tid_identity_quarantined(
    has_persisted_entry: bool,
    tid_starttime: Option<u64>,
    cutoff: Option<u64>,
) -> bool {
    !has_persisted_entry
        && tid_starttime.is_some_and(|starttime| cutoff.is_some_and(|cutoff| starttime <= cutoff))
}

pub(crate) fn restore_all_managed_tids(
    managed_tids: &mut HashMap<i32, ManagedTidEntry>,
    cpuset_name: &str,
) -> (usize, usize) {
    let entries = managed_tids
        .iter()
        .map(|(tid, entry)| (*tid, entry.clone()))
        .collect::<Vec<_>>();
    let mut restored = 0usize;
    let mut failures = 0usize;
    for (tid, entry) in entries {
        match restore_managed_tid(tid, &entry, cpuset_name) {
            Ok(()) => {
                managed_tids.remove(&tid);
                restored = restored.saturating_add(1);
            }
            Err(err) if is_thread_gone_error(&err) => {
                managed_tids.remove(&tid);
            }
            Err(err) => {
                failures = failures.saturating_add(1);
                if failures <= MAX_ERROR_DETAILS_PER_ROUND {
                    log_error!(
                        "[RS] 守护退出恢复线程失败 进程={} 线程={} 错误={}",
                        entry.tgid,
                        tid,
                        error_text_zh(&err)
                    );
                }
            }
        }
    }
    if failures > MAX_ERROR_DETAILS_PER_ROUND {
        log_error!(
            "[RS] 守护退出恢复线程失败: 共 {} 条，仅显示前 {} 条",
            failures, MAX_ERROR_DETAILS_PER_ROUND
        );
    }
    (restored, managed_tids.len())
}

#[cfg(test)]
mod retry_tests {
    use super::*;
    use crate::affinity::journal_format::{parse_managed_tid_journal, serialize_managed_tid_journal};
    use crate::affinity::tests::managed_entry;

    #[test]
    fn repeated_temporary_errors_keep_the_durable_baseline_and_bound_io() {
        let original = managed_entry(true);
        let mut entries = HashMap::from([(42, original.clone())]);
        let mut now = 1_000;
        for failure in 0..12 {
            let result = retry_managed_restore(&mut entries, 42, now, false,
                |_| Err(io::Error::new(io::ErrorKind::WouldBlock, "temporary identity read failure")));
            assert!(matches!(result, RestoreAttempt::Failed(_)));
            let pending = &entries[&42];
            assert_eq!(pending.original_mask_low64, original.original_mask_low64);
            assert_eq!(pending.original_cpuset, original.original_cpuset);
            assert!(pending.restore_pending && pending.restore_persisted);
            assert_eq!(pending.desired_mask_low64, None);
            let deadline = pending.restore_retry_after_elapsed_ms;
            let expected = (2_000u64 << failure.min(5)).min(60_000);
            assert_eq!(deadline - now, expected);
            for early in [now, now + 1, deadline - 1] {
                assert!(matches!(retry_managed_restore(&mut entries, 42, early, false,
                    |_| panic!("backoff must prevent all restore IO")), RestoreAttempt::Deferred));
            }
            // 守护进程重启后，仍须保留原始恢复目标。
            let encoded = serialize_managed_tid_journal(&entries, "boot-test", "QiXiaRs");
            let restored = parse_managed_tid_journal(&encoded, "boot-test", "QiXiaRs").unwrap();
            assert_eq!(restored[&42].original_mask_low64, original.original_mask_low64);
            assert_eq!(restored[&42].original_cpuset, original.original_cpuset);
            now = deadline;
        }
        assert!(matches!(retry_managed_restore(&mut entries, 42, now, false, |_| Ok(())), RestoreAttempt::Removed));
        assert!(entries.is_empty());
    }

    #[test]
    fn confirmed_identity_reuse_bypasses_backoff_without_restoring_a_new_thread() {
        let mut entries = HashMap::from([(42, managed_entry(true))]);
        retry_managed_restore(&mut entries, 42, 1_000, false,
            |_| Err(io::Error::new(io::ErrorKind::PermissionDenied, "temporarily denied")));
        assert!(matches!(retry_managed_restore(&mut entries, 42, 1_001, true, |old| {
            assert_eq!(old.starttime, Some(1));
            Err(io::Error::from_raw_os_error(3))
        }), RestoreAttempt::Removed));
        assert!(entries.is_empty());
    }
}
