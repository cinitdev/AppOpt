use super::cpuset::{move_tid_to_cpuset, read_present_cpus, CpusetRoundCache};
use super::diagnostics::{
    action_identity_is_current, error_text_zh, is_affinity_restricted_error,
    is_cpuset_expected_reject, is_thread_gone_error, log_limited_detail_count, should_log_detail,
};
use super::mask::CpuMask;
use super::state::{
    accepted_managed_mask, cpuset_retry_due, managed_action_can_defer,
    managed_restore_baseline_ready, mark_managed_affinity_checked, mark_managed_cpuset_failure,
    next_cpuset_retry,
};
use super::syscall::{read_allowed_mask, set_affinity};
use crate::{
    ApplyStats, ManagedTidEntry, ProcHit, ACTIVE_BACKGROUND_AFFINITY_VERIFY_MS,
    FOREGROUND_AFFINITY_VERIFY_MS, SCREEN_OFF_BACKGROUND_AFFINITY_VERIFY_MS,
};
use std::collections::{BTreeSet, HashMap};
use std::path::Path;

// CPU 亲和性写入与读回验证。
//
// 守护进程最终只做一件事：为命中的 TID 写入目标 CPU 掩码。
// 写入前优先通过 sched_getaffinity 读取当前掩码，系统不支持时才回退 /proc status；
// 写入后再读回一次，区分 cpuset 合法收窄与厂商服务抢写。
//
// mismatched 对移植系统很关键：有些 ROM/厂商服务会反复把线程绑回 4-7、6-7 之类的范围，
// 这时不是 QixiaThreads 规则没命中，而是外部调度服务在抢写。
pub(crate) struct ApplyPolicy<'a> {
    pub(crate) detail_log: bool,
    pub(crate) cpuset_name: &'a str,
    pub(crate) now_elapsed: u64,
    pub(crate) foreground_pids: &'a BTreeSet<i32>,
    pub(crate) interactive: bool,
    pub(crate) require_restore_baseline: bool,
}

pub(crate) fn apply_hits(
    hits: &[ProcHit],
    managed_tids: &mut HashMap<i32, ManagedTidEntry>,
    policy: ApplyPolicy<'_>,
) -> ApplyStats {
    let ApplyPolicy {
        detail_log,
        cpuset_name,
        now_elapsed,
        foreground_pids,
        interactive,
        require_restore_baseline,
    } = policy;
    let mut stats = ApplyStats::default();
    let mut invalid_details = 0usize;
    let mut restricted_details = 0usize;
    let mut read_failed_details = 0usize;
    let mut cpuset_failed_details = 0usize;
    let mut affinity_failed_details = 0usize;
    let mut mismatch_details = 0usize;
    let mut identity_details = 0usize;

    let present_mask = read_present_cpus().and_then(|cpus| CpuMask::parse(&cpus));
    let base_cpuset = Path::new("/dev/cpuset").join(cpuset_name);
    let mut cpuset_cache = CpusetRoundCache::default();

    for hit in hits {
        for action in &hit.actions {
            // cpus 解析失败不终止整个守护进程，只统计并打印错误，避免一条坏规则影响其他应用。
            let Some(requested_mask) = CpuMask::parse(&action.cpus) else {
                stats.invalid_rules += 1;
                if should_log_detail(detail_log, &mut invalid_details) {
                    log_error!(
                        "[RS] 无效CPU规则 进程={} 线程={} 线程名={} 规则={}",
                        hit.pid, action.tid, action.name, action.rule
                    );
                }
                continue;
            };
            if require_restore_baseline
                && !managed_tids.get(&action.tid).is_some_and(|entry| {
                    entry.tgid == hit.pid
                        && entry.tgid_starttime == hit.pid_starttime
                        && entry.starttime == action.tid_starttime
                        && managed_restore_baseline_ready(entry)
                })
            {
                stats.skipped += 1;
                if should_log_detail(detail_log, &mut read_failed_details) {
                    log_error!(
                        "[RS] 跳过未建立恢复基线的线程 进程={} 线程={} 线程名={}",
                        hit.pid, action.tid, action.name
                    );
                }
                continue;
            }
            let mask = if let Some(present) = &present_mask {
                let clipped = requested_mask.intersection(present);
                if clipped.is_empty() {
                    stats.restricted += 1;
                    if should_log_detail(detail_log, &mut restricted_details) {
                        log_error!(
                            "[RS] CPU规则不包含当前设备核心 进程={} 线程={} 线程名={} 规则={} 请求={} present={}",
                            hit.pid,
                            action.tid,
                            action.name,
                            action.rule,
                            action.cpus,
                            present.to_list()
                        );
                    }
                    continue;
                }
                if clipped != requested_mask {
                    stats.restricted += 1;
                    if should_log_detail(detail_log, &mut restricted_details) {
                        log_error!(
                            "[RS] CPU规则已裁剪到当前设备核心 进程={} 线程={} 线程名={} 规则={} 请求={} 实际={} present={}",
                            hit.pid,
                            action.tid,
                            action.name,
                            action.rule,
                            action.cpus,
                            clipped.to_list(),
                            present.to_list()
                        );
                    }
                }
                clipped
            } else {
                requested_mask
            };
            let effective_cpus = mask.to_list();
            let desired_mask_low64 = mask.to_low64();
            let verify_interval_ms = if foreground_pids.contains(&hit.pid) {
                FOREGROUND_AFFINITY_VERIFY_MS
            } else if interactive {
                ACTIVE_BACKGROUND_AFFINITY_VERIFY_MS
            } else {
                SCREEN_OFF_BACKGROUND_AFFINITY_VERIFY_MS
            };

            let cached = managed_tids.get(&action.tid).cloned().filter(|entry| {
                entry.tgid == hit.pid
                    && entry.tgid_starttime == hit.pid_starttime
                    && entry.starttime == action.tid_starttime
            });
            let desired_changed =
                cached.as_ref().and_then(|entry| entry.desired_mask_low64) != desired_mask_low64;
            let needs_cpuset_sync = cached.as_ref().is_none_or(|entry| {
                desired_changed || (!entry.cpuset_synced && cpuset_retry_due(entry, now_elapsed))
            });
            if needs_cpuset_sync {
                if !action_identity_is_current(
                    hit,
                    action,
                    "cpuset",
                    detail_log,
                    &mut identity_details,
                ) {
                    stats.skipped += 1;
                    continue;
                }
                let previous_cpuset_failure_count = cached
                    .as_ref()
                    .map_or(0, |entry| entry.cpuset_failure_count);
                let cpuset_move = move_tid_to_cpuset(
                    action.tid,
                    &mask,
                    &base_cpuset,
                    cpuset_name,
                    &mut cpuset_cache,
                );
                let (cpuset_synced, cpuset_failure_count, cpuset_retry_after_elapsed_ms) =
                    match cpuset_move {
                        Ok(()) => (true, 0, 0),
                        Err(err) if is_thread_gone_error(&err) => {
                            stats.skipped += 1;
                            continue;
                        }
                        Err(err) => {
                            let expected_reject = is_cpuset_expected_reject(&err);
                            if !expected_reject {
                                stats.cpuset_failed += 1;
                            }
                            if !expected_reject
                                && should_log_detail(detail_log, &mut cpuset_failed_details)
                            {
                                log_error!(
                                "[RS] cpuset辅助写入失败 进程={} 线程={} 线程名={} 规则={} 核心={} 错误={}",
                                hit.pid,
                                action.tid,
                                action.name,
                                action.rule,
                                effective_cpus,
                                error_text_zh(&err)
                            );
                            }
                            let (failure_count, retry_after) =
                                next_cpuset_retry(previous_cpuset_failure_count, now_elapsed);
                            (false, failure_count, retry_after)
                        }
                    };
                let last_seen_round = managed_tids
                    .get(&action.tid)
                    .map_or(0, |entry| entry.last_seen_round);
                managed_tids.insert(
                    action.tid,
                    ManagedTidEntry {
                        tgid: hit.pid,
                        tgid_starttime: hit.pid_starttime,
                        starttime: action.tid_starttime,
                        last_seen_round,
                        cpuset_synced,
                        cpuset_failure_count,
                        cpuset_retry_after_elapsed_ms,
                        desired_mask_low64: cached
                            .as_ref()
                            .and_then(|entry| entry.desired_mask_low64),
                        verified_mask_low64: cached
                            .as_ref()
                            .and_then(|entry| entry.verified_mask_low64),
                        last_affinity_check_elapsed_ms: cached
                            .as_ref()
                            .map_or(0, |entry| entry.last_affinity_check_elapsed_ms),
                        next_affinity_check_elapsed_ms: cached
                            .as_ref()
                            .map_or(0, |entry| entry.next_affinity_check_elapsed_ms),
                        original_mask_low64: cached
                            .as_ref()
                            .and_then(|entry| entry.original_mask_low64),
                        original_cpuset: cached
                            .as_ref()
                            .and_then(|entry| entry.original_cpuset.clone()),
                        restore_persisted: cached
                            .as_ref()
                            .is_some_and(|entry| entry.restore_persisted),
                        restore_pending: false,
                        restore_failure_count: 0,
                        restore_retry_after_elapsed_ms: 0,
                    },
                );
            }

            // 深扫会重新生成全部 action。身份、规则和 cpuset 都未变化的线程统一交给
            // 验证队列，避免 60 秒全扫在同一轮集中读回所有已管理线程。
            if managed_action_can_defer(cached.as_ref(), desired_changed) {
                stats.skipped += 1;
                continue;
            }

            let affinity_cache_fresh = desired_mask_low64.is_some_and(|desired| {
                managed_tids.get(&action.tid).is_some_and(|entry| {
                    entry.tgid == hit.pid
                        && entry.tgid_starttime == hit.pid_starttime
                        && entry.starttime == action.tid_starttime
                        && entry.desired_mask_low64 == Some(desired)
                        && now_elapsed >= entry.last_affinity_check_elapsed_ms
                        && now_elapsed.saturating_sub(entry.last_affinity_check_elapsed_ms)
                            < verify_interval_ms
                })
            });
            if affinity_cache_fresh {
                stats.skipped += 1;
                continue;
            }

            // 已经在目标核心上就不重复写亲和性，减少长期守护进程对系统的打扰。
            let mut observed_mask_low64 = None;
            match read_allowed_mask(hit.pid, action.tid) {
                Ok(Some(current))
                    if accepted_managed_mask(cached.as_ref(), &current, &mask)
                        && !desired_changed =>
                {
                    mark_managed_affinity_checked(
                        managed_tids,
                        action.tid,
                        desired_mask_low64,
                        current.to_low64(),
                        now_elapsed,
                        verify_interval_ms,
                    );
                    stats.skipped += 1;
                    continue;
                }
                Ok(Some(current)) => observed_mask_low64 = current.to_low64(),
                Ok(None) => {}
                Err(err) if is_thread_gone_error(&err) => {
                    stats.skipped += 1;
                    continue;
                }
                Err(err) => {
                    if should_log_detail(detail_log, &mut read_failed_details) {
                        log_error!(
                            "[RS] 读取绑核状态失败 进程={} 线程={} 线程名={} 错误={}",
                            hit.pid,
                            action.tid,
                            action.name,
                            error_text_zh(&err)
                        );
                    }
                }
            }

            if !action_identity_is_current(
                hit,
                action,
                "affinity",
                detail_log,
                &mut identity_details,
            ) {
                stats.skipped += 1;
                continue;
            }

            match set_affinity(action.tid, &mask) {
                Ok(()) => {
                    stats.applied += 1;
                    // 写入后读回一次，用于发现移植系统或厂商服务把线程核心抢写回去的情况。
                    match read_allowed_mask(hit.pid, action.tid) {
                        Ok(Some(current)) if current == mask => {
                            mark_managed_affinity_checked(
                                managed_tids,
                                action.tid,
                                desired_mask_low64,
                                current.to_low64(),
                                now_elapsed,
                                verify_interval_ms,
                            );
                        }
                        Ok(Some(current)) if current.is_subset_of(&mask) => {
                            // 写入成功但 cpuset/内核只允许目标子集，单独计为系统限制，
                            // 不能把旧范围是新范围子集误判成扩容已经完成。
                            stats.restricted += 1;
                            mark_managed_affinity_checked(
                                managed_tids,
                                action.tid,
                                desired_mask_low64,
                                current.to_low64(),
                                now_elapsed,
                                verify_interval_ms,
                            );
                            if desired_changed {
                                mark_managed_cpuset_failure(managed_tids, action.tid, now_elapsed);
                            }
                            if should_log_detail(detail_log, &mut restricted_details) {
                                log_error!(
                                    "[RS] CPU规则受系统限制 进程={} 线程={} 线程名={} 期望={} 实际={}",
                                    hit.pid,
                                    action.tid,
                                    action.name,
                                    effective_cpus,
                                    current.to_list()
                                );
                            }
                        }
                        Ok(Some(current)) => {
                            stats.mismatched += 1;
                            if should_log_detail(detail_log, &mut mismatch_details) {
                                log_error!(
                                    "[RS] 绑核被系统改写 进程={} 线程={} 线程名={} 规则={} 期望={} 实际={}",
                                    hit.pid,
                                    action.tid,
                                    action.name,
                                    action.rule,
                                    effective_cpus,
                                    current.to_list()
                                );
                            }
                        }
                        _ => {}
                    }
                }
                Err(err) => {
                    if is_thread_gone_error(&err) {
                        stats.skipped += 1;
                    } else if is_affinity_restricted_error(&err) {
                        stats.restricted += 1;
                        mark_managed_affinity_checked(
                            managed_tids,
                            action.tid,
                            desired_mask_low64,
                            observed_mask_low64,
                            now_elapsed,
                            verify_interval_ms,
                        );
                    } else {
                        stats.failed += 1;
                        mark_managed_affinity_checked(
                            managed_tids,
                            action.tid,
                            desired_mask_low64,
                            observed_mask_low64,
                            now_elapsed,
                            verify_interval_ms,
                        );
                        if should_log_detail(detail_log, &mut affinity_failed_details) {
                            log_error!(
                                "[RS] 绑核失败 进程={} 线程={} 线程名={} 规则={} 错误={}",
                                hit.pid,
                                action.tid,
                                action.name,
                                action.rule,
                                error_text_zh(&err)
                            );
                        }
                    }
                }
            }
        }
    }

    if detail_log {
        log_limited_detail_count("无效CPU规则", invalid_details);
        log_limited_detail_count("CPU规则受设备核心范围限制", restricted_details);
        log_limited_detail_count("读取绑核状态失败", read_failed_details);
        log_limited_detail_count("cpuset辅助写入失败", cpuset_failed_details);
        log_limited_detail_count("绑核失败", affinity_failed_details);
        log_limited_detail_count("绑核被系统改写", mismatch_details);
        log_limited_detail_count("进程/线程身份已变化", identity_details);
    }

    stats
}
