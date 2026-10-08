use super::cpuset::{move_tid_to_cpuset, CpusetRoundCache};
use super::diagnostics::{
    error_text_zh, is_affinity_restricted_error, is_cpuset_expected_reject, is_thread_gone_error,
    log_limited_detail_count, should_log_detail,
};
use super::mask::CpuMask;
use super::state::{
    accepted_managed_mask, cpuset_retry_due, managed_tid_identity_status,
    mark_managed_affinity_checked, mark_managed_cpuset_failure, mark_managed_cpuset_success,
    set_managed_cpuset_synced, ManagedTidIdentityStatus,
};
use super::syscall::{read_allowed_mask, set_affinity};
use crate::{
    ApplyStats, ManagedTidEntry, ACTIVE_BACKGROUND_AFFINITY_VERIFY_MS,
    BACKGROUND_AFFINITY_BUDGET_MS, FOREGROUND_AFFINITY_VERIFY_MS,
    MAX_BACKGROUND_AFFINITY_CHECKS_PER_ROUND, MAX_FOREGROUND_AFFINITY_CHECKS_PER_ROUND,
    SCREEN_OFF_BACKGROUND_AFFINITY_VERIFY_MS,
};
use std::collections::{BTreeSet, HashMap};
use std::path::Path;
use std::time::{Duration, Instant};

pub(crate) struct AffinityVerifyPolicy<'a> {
    pub(crate) foreground_pids: &'a BTreeSet<i32>,
    pub(crate) interactive: bool,
    pub(crate) now_elapsed: u64,
    pub(crate) detail_log: bool,
    pub(crate) base_cpuset: &'a Path,
    pub(crate) cpuset_name: &'a str,
    pub(crate) start_cursor: usize,
}

pub(crate) fn verify_managed_affinity(
    managed_tids: &mut HashMap<i32, ManagedTidEntry>,
    policy: AffinityVerifyPolicy<'_>,
) -> (ApplyStats, usize) {
    let AffinityVerifyPolicy {
        foreground_pids,
        interactive,
        now_elapsed,
        detail_log,
        base_cpuset,
        cpuset_name,
        start_cursor,
    } = policy;
    let mut stats = ApplyStats::default();
    let mut mismatch_details = 0usize;
    let mut failed_details = 0usize;
    let background_started = Instant::now();
    // 这里只复制轻量 TID。ManagedTidEntry 含有原 cpuset 字符串，先克隆整张表会让
    // 每个常规轮次都产生与受控线程数成正比的字符串分配。
    // HashMap 的键顺序不需要排序；轮转游标已经保证每轮从不同位置开始，
    // 这里避免数千 TID 的 O(N log N) 排序和额外比较。
    let mut snapshot = managed_tids.keys().copied().collect::<Vec<_>>();
    let snapshot_len = snapshot.len();
    if snapshot_len > 0 {
        let offset = start_cursor % snapshot_len;
        snapshot.rotate_left(offset);
    }
    let mut stale_tids = Vec::new();
    let mut foreground_checked = 0usize;
    let mut background_checked = 0usize;
    let mut cpuset_cache = CpusetRoundCache::default();

    for tid in snapshot {
        let Some(entry) = managed_tids.get(&tid) else {
            continue;
        };
        let Some(expected_low64) = entry.desired_mask_low64 else {
            continue;
        };
        let foreground = foreground_pids.contains(&entry.tgid);
        let interval_ms = if foreground {
            FOREGROUND_AFFINITY_VERIFY_MS
        } else if interactive {
            ACTIVE_BACKGROUND_AFFINITY_VERIFY_MS
        } else {
            SCREEN_OFF_BACKGROUND_AFFINITY_VERIFY_MS
        };
        let due = entry.next_affinity_check_elapsed_ms == 0
            || now_elapsed < entry.last_affinity_check_elapsed_ms
            || now_elapsed >= entry.next_affinity_check_elapsed_ms;
        if !due {
            continue;
        }
        if foreground {
            if foreground_checked >= MAX_FOREGROUND_AFFINITY_CHECKS_PER_ROUND {
                continue;
            }
            foreground_checked += 1;
        } else {
            if background_checked >= MAX_BACKGROUND_AFFINITY_CHECKS_PER_ROUND
                || background_started.elapsed()
                    >= Duration::from_millis(BACKGROUND_AFFINITY_BUDGET_MS)
            {
                continue;
            }
            background_checked += 1;
        }
        // 只克隆本轮真正到期且未被预算挡住的记录。
        let entry = entry.clone();

        let expected = CpuMask::from_low64(expected_low64);
        match read_allowed_mask(entry.tgid, tid) {
            Ok(Some(current)) if accepted_managed_mask(Some(&entry), &current, &expected) => {
                mark_managed_affinity_checked(
                    managed_tids,
                    tid,
                    Some(expected_low64),
                    current.to_low64(),
                    now_elapsed,
                    interval_ms,
                );
            }
            Ok(Some(current)) => {
                match managed_tid_identity_status(tid, &entry) {
                    ManagedTidIdentityStatus::Current => {}
                    ManagedTidIdentityStatus::GoneOrReused => {
                        stale_tids.push(tid);
                        continue;
                    }
                    ManagedTidIdentityStatus::Unreadable => {
                        // /proc 的瞬时读取失败不是线程退出证据。保留恢复基线并延后复核，
                        // 绝不能因为一次 EACCES/EIO 就把仍受控线程从缓存中丢掉。
                        mark_managed_affinity_checked(
                            managed_tids,
                            tid,
                            Some(expected_low64),
                            current.to_low64(),
                            now_elapsed,
                            interval_ms,
                        );
                        continue;
                    }
                }
                // 掩码漂移通常意味着 Android 任务画像或 cpuset 已重新接管线程。
                // 不依赖前台助手是否识别成功：和原版 affinity_sync 一样，先把
                // TID 迁回目标 cpuset，再写 sched_setaffinity。
                let should_retry_cpuset =
                    entry.cpuset_synced || cpuset_retry_due(&entry, now_elapsed);
                set_managed_cpuset_synced(managed_tids, tid, false);
                if should_retry_cpuset {
                    match move_tid_to_cpuset(
                        tid,
                        &expected,
                        base_cpuset,
                        cpuset_name,
                        &mut cpuset_cache,
                    ) {
                        Ok(()) => mark_managed_cpuset_success(managed_tids, tid),
                        Err(err) if is_thread_gone_error(&err) => {
                            stale_tids.push(tid);
                            continue;
                        }
                        Err(err) => {
                            mark_managed_cpuset_failure(managed_tids, tid, now_elapsed);
                            if !is_cpuset_expected_reject(&err)
                                && should_log_detail(detail_log, &mut failed_details)
                            {
                                log_error!(
                                    "[RS] cpuset 重新同步失败 进程={} 线程={} 期望={} 错误={}",
                                    entry.tgid,
                                    tid,
                                    expected.to_list(),
                                    error_text_zh(&err)
                                );
                            }
                        }
                    }
                }
                match set_affinity(tid, &expected) {
                    Ok(()) => {
                        stats.applied += 1;
                        match read_allowed_mask(entry.tgid, tid) {
                            Ok(Some(restored))
                                if restored == expected || restored.is_subset_of(&expected) =>
                            {
                                mark_managed_affinity_checked(
                                    managed_tids,
                                    tid,
                                    Some(expected_low64),
                                    restored.to_low64(),
                                    now_elapsed,
                                    interval_ms,
                                );
                                let recovered =
                                    current != restored || !current.is_subset_of(&expected);
                                if recovered {
                                    stats.mismatched += 1;
                                }
                                if recovered && should_log_detail(detail_log, &mut mismatch_details)
                                {
                                    log_info!(
                                        "[RS] 绑核抢写已恢复 进程={} 线程={} 期望={} 原值={}",
                                        entry.tgid,
                                        tid,
                                        expected.to_list(),
                                        current.to_list()
                                    );
                                }
                            }
                            Ok(Some(restored)) => {
                                stats.mismatched += 1;
                                mark_managed_affinity_checked(
                                    managed_tids,
                                    tid,
                                    Some(expected_low64),
                                    restored.to_low64(),
                                    now_elapsed,
                                    interval_ms,
                                );
                                if should_log_detail(detail_log, &mut mismatch_details) {
                                    log_error!(
                                        "[RS] 绑核抢写恢复后仍不一致 进程={} 线程={} 期望={} 实际={}",
                                        entry.tgid,
                                        tid,
                                        expected.to_list(),
                                        restored.to_list()
                                    );
                                }
                            }
                            Ok(None) | Err(_) => {
                                mark_managed_affinity_checked(
                                    managed_tids,
                                    tid,
                                    Some(expected_low64),
                                    entry.verified_mask_low64,
                                    now_elapsed,
                                    interval_ms,
                                );
                            }
                        }
                    }
                    Err(err) if is_thread_gone_error(&err) => stale_tids.push(tid),
                    Err(err) if is_affinity_restricted_error(&err) => {
                        stats.restricted += 1;
                        set_managed_cpuset_synced(managed_tids, tid, false);
                        mark_managed_affinity_checked(
                            managed_tids,
                            tid,
                            Some(expected_low64),
                            current.to_low64(),
                            now_elapsed,
                            interval_ms,
                        );
                    }
                    Err(err) => {
                        stats.failed += 1;
                        mark_managed_affinity_checked(
                            managed_tids,
                            tid,
                            Some(expected_low64),
                            current.to_low64(),
                            now_elapsed,
                            interval_ms,
                        );
                        if should_log_detail(detail_log, &mut failed_details) {
                            log_error!(
                                "[RS] 绑核抢写恢复失败 进程={} 线程={} 期望={} 错误={}",
                                entry.tgid,
                                tid,
                                expected.to_list(),
                                error_text_zh(&err)
                            );
                        }
                    }
                }
            }
            Ok(None) => {}
            Err(err) if is_thread_gone_error(&err) => stale_tids.push(tid),
            Err(_) => {
                mark_managed_affinity_checked(
                    managed_tids,
                    tid,
                    Some(expected_low64),
                    entry.verified_mask_low64,
                    now_elapsed,
                    interval_ms,
                );
            }
        }
    }

    for tid in stale_tids {
        managed_tids.remove(&tid);
    }
    let next_cursor = if snapshot_len > 0 {
        start_cursor.saturating_add(foreground_checked.saturating_add(background_checked))
            % snapshot_len
    } else {
        0
    };
    if detail_log {
        log_limited_detail_count("绑核抢写恢复", mismatch_details);
        log_limited_detail_count("绑核抢写恢复失败", failed_details);
    }
    (stats, next_cursor)
}
