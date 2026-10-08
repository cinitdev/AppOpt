use super::diagnostics::is_thread_gone_error;
use super::mask::CpuMask;
use crate::{read_proc_starttime, ManagedTidEntry, CPUSET_RETRY_INITIAL_MS, CPUSET_RETRY_MAX_MS};
use std::collections::HashMap;
use std::path::PathBuf;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ManagedTidIdentityStatus {
    Current,
    GoneOrReused,
    Unreadable,
}

pub(crate) fn managed_tid_identity_status(
    tid: i32,
    entry: &ManagedTidEntry,
) -> ManagedTidIdentityStatus {
    let (Some(expected_tgid_start), Some(expected_tid_start)) =
        (entry.tgid_starttime, entry.starttime)
    else {
        return ManagedTidIdentityStatus::Unreadable;
    };
    let process_path = PathBuf::from(format!("/proc/{}", entry.tgid));
    match read_proc_starttime(&process_path) {
        Ok(value) if value == expected_tgid_start => {}
        Ok(_) => return ManagedTidIdentityStatus::GoneOrReused,
        Err(err) if is_thread_gone_error(&err) => {
            return ManagedTidIdentityStatus::GoneOrReused;
        }
        Err(_) => return ManagedTidIdentityStatus::Unreadable,
    }
    let task_path = process_path.join("task").join(tid.to_string());
    match read_proc_starttime(&task_path) {
        Ok(value) if value == expected_tid_start => ManagedTidIdentityStatus::Current,
        Ok(_) => ManagedTidIdentityStatus::GoneOrReused,
        Err(err) if is_thread_gone_error(&err) => ManagedTidIdentityStatus::GoneOrReused,
        Err(_) => ManagedTidIdentityStatus::Unreadable,
    }
}

pub(crate) fn mark_managed_affinity_checked(
    managed_tids: &mut HashMap<i32, ManagedTidEntry>,
    tid: i32,
    desired_mask_low64: Option<u64>,
    verified_mask_low64: Option<u64>,
    now_elapsed: u64,
    interval_ms: u64,
) {
    let Some(entry) = managed_tids.get_mut(&tid) else {
        return;
    };
    entry.desired_mask_low64 = desired_mask_low64;
    entry.verified_mask_low64 = verified_mask_low64;
    entry.last_affinity_check_elapsed_ms = now_elapsed;
    entry.next_affinity_check_elapsed_ms =
        next_affinity_check_slot(tid, entry.starttime.unwrap_or(0), now_elapsed, interval_ms);
}

pub(crate) fn set_managed_cpuset_synced(
    managed_tids: &mut HashMap<i32, ManagedTidEntry>,
    tid: i32,
    synced: bool,
) {
    if let Some(entry) = managed_tids.get_mut(&tid) {
        entry.cpuset_synced = synced;
    }
}

pub(crate) fn cpuset_retry_due(entry: &ManagedTidEntry, now_elapsed: u64) -> bool {
    entry.cpuset_retry_after_elapsed_ms == 0 || now_elapsed >= entry.cpuset_retry_after_elapsed_ms
}

pub(crate) fn next_cpuset_retry(failure_count: u8, now_elapsed: u64) -> (u8, u64) {
    let next_count = failure_count.saturating_add(1);
    let exponent = next_count.saturating_sub(1).min(5) as u32;
    let delay = CPUSET_RETRY_INITIAL_MS
        .saturating_mul(1u64 << exponent)
        .min(CPUSET_RETRY_MAX_MS);
    (next_count, now_elapsed.saturating_add(delay))
}

pub(crate) fn mark_managed_cpuset_success(
    managed_tids: &mut HashMap<i32, ManagedTidEntry>,
    tid: i32,
) {
    if let Some(entry) = managed_tids.get_mut(&tid) {
        entry.cpuset_synced = true;
        entry.cpuset_failure_count = 0;
        entry.cpuset_retry_after_elapsed_ms = 0;
    }
}

pub(crate) fn mark_managed_cpuset_failure(
    managed_tids: &mut HashMap<i32, ManagedTidEntry>,
    tid: i32,
    now_elapsed: u64,
) {
    if let Some(entry) = managed_tids.get_mut(&tid) {
        entry.cpuset_synced = false;
        (
            entry.cpuset_failure_count,
            entry.cpuset_retry_after_elapsed_ms,
        ) = next_cpuset_retry(entry.cpuset_failure_count, now_elapsed);
    }
}

pub(crate) fn next_affinity_check_slot(
    tid: i32,
    starttime: u64,
    now_elapsed: u64,
    interval_ms: u64,
) -> u64 {
    let interval = interval_ms.max(1);
    let round = now_elapsed / interval;
    // 主循环约 2 秒一轮，单纯在同一个 2 秒窗内设置毫秒相位仍会在下一轮一起到期。
    // 按线程和当前轮次分成相邻两轮，既把读取错开，最迟也只延后到两个验证周期。
    let wait_rounds = 1 + ((affinity_slot_hash(tid, starttime) ^ round) & 1);
    now_elapsed.saturating_add(interval.saturating_mul(wait_rounds))
}

pub(crate) fn accepted_managed_mask(
    entry: Option<&ManagedTidEntry>,
    current: &CpuMask,
    expected: &CpuMask,
) -> bool {
    if current == expected {
        return true;
    }
    if !current.is_subset_of(expected) {
        return false;
    }
    let Some(entry) = entry else {
        return false;
    };
    let current_low64 = current.to_low64();
    current_low64.is_some()
        && current_low64 == entry.verified_mask_low64
        && entry.verified_mask_low64 != entry.desired_mask_low64
}

pub(crate) fn managed_action_can_defer(
    cached: Option<&ManagedTidEntry>,
    desired_changed: bool,
) -> bool {
    cached.is_some_and(|entry| !desired_changed && entry.cpuset_synced)
}

pub(crate) fn managed_restore_baseline_ready(entry: &ManagedTidEntry) -> bool {
    entry.restore_persisted
        && (entry.original_mask_low64.is_some() || entry.original_cpuset.is_some())
}

pub(crate) fn affinity_slot_hash(tid: i32, starttime: u64) -> u64 {
    let mut value = (tid as u64) ^ starttime.rotate_left(13);
    value = value.wrapping_add(0x9e37_79b9_7f4a_7c15);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value ^= value >> 27;
    value
}
