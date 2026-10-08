use super::cpuset::{
    move_tid_to_existing_cpuset, normalized_restore_cpuset, read_existing_cpuset_mask,
    read_online_cpu_mask, read_present_cpus, valid_cpuset_relative_path,
};
use super::mask::CpuMask;
use super::diagnostics::is_thread_gone_error;
use super::state::{managed_tid_identity_status, ManagedTidIdentityStatus};
use super::syscall::{read_allowed_mask, set_affinity};
use crate::{proc_thread_identity_matches, ManagedTidEntry, ProcHit, ThreadAction};
use std::path::Path;
use std::{fs, io};

pub(crate) fn capture_tid_restore_state(
    hit: &ProcHit,
    action: &ThreadAction,
    cpuset_name: &str,
) -> Option<(Option<u64>, Option<String>)> {
    let pid = hit.pid;
    let tid = action.tid;
    let mut original_mask_low64 = read_allowed_mask(pid, tid)
        .ok()
        .flatten()
        .and_then(|mask| mask.to_low64());
    let mut original_cpuset = fs::read_to_string(format!("/proc/{pid}/task/{tid}/cpuset"))
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| valid_cpuset_relative_path(value));
    if let Some(current) = original_cpuset.as_deref() {
        let normalized = normalized_restore_cpuset(current, cpuset_name);
        if normalized != current {
            // 守护进程异常重启后，当前值可能就是上一次遗留的 QixiaThreads 掩码子组。
            // 此时不能把旧规则掩码当成“接管前状态”，改用父组/根组允许范围。
            let cpus_path = Path::new("/dev/cpuset")
                .join(normalized.trim_start_matches('/'))
                .join("cpus");
            original_mask_low64 = fs::read_to_string(cpus_path)
                .ok()
                .and_then(|cpus| CpuMask::parse(cpus.trim()))
                .and_then(|mask| mask.to_low64())
                .or_else(|| {
                    read_present_cpus()
                        .and_then(|cpus| CpuMask::parse(&cpus))
                        .and_then(|mask| mask.to_low64())
                });
            original_cpuset = Some(normalized);
        }
    }
    // 基线读取完成后重新对照扫描阶段记录的 starttime；期间如果发生 TID 复用，
    // 丢弃本次基线，避免把新线程状态写进旧线程记录。至少有一个可恢复维度时
    // 才允许进入 managed_tids。
    if proc_thread_identity_matches(hit, action).ok() != Some(true)
        || (original_mask_low64.is_none() && original_cpuset.is_none())
    {
        return None;
    }
    Some((original_mask_low64, original_cpuset))
}

pub(crate) fn restore_managed_tid(
    tid: i32,
    entry: &ManagedTidEntry,
    cpuset_name: &str,
) -> io::Result<()> {
    match managed_tid_identity_status(tid, entry) {
        ManagedTidIdentityStatus::Current => {}
        ManagedTidIdentityStatus::GoneOrReused => {
            return Err(io::Error::from_raw_os_error(3));
        }
        ManagedTidIdentityStatus::Unreadable => {
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "线程身份暂时无法读取",
            ));
        }
    }

    let mut cpuset_error = None;
    let mut restore_cpuset = None;
    if let Some(cpuset) = entry.original_cpuset.as_deref() {
        let cpuset = normalized_restore_cpuset(cpuset, cpuset_name);
        if let Err(err) = move_tid_to_existing_cpuset(tid, &cpuset) {
            cpuset_error = Some(err);
        } else {
            restore_cpuset = Some(cpuset);
        }
    }
    let mut affinity_error = None;
    if let Some(mask) = entry.original_mask_low64.filter(|mask| *mask != 0) {
        let original = CpuMask::from_low64(mask);
        // cpuset/任务画像可能在守护进程重启后收窄线程可用核心。必须读取迁回后
        // cpuset 的有效范围，而不能使用线程当前亲和性；后者仍可能是 QixiaThreads
        // 的旧绑定范围，会导致本应恢复到 0-7 的线程只保留在 4-7。
        let cpuset_allowed = restore_cpuset
            .as_deref()
            .and_then(read_existing_cpuset_mask)
            .or_else(|| read_present_cpus().and_then(|cpus| CpuMask::parse(&cpus)));
        let online = read_online_cpu_mask();
        let allowed = match (cpuset_allowed, online) {
            (Some(cpuset), Some(online)) => {
                let effective = cpuset.intersection(&online);
                (!effective.is_empty()).then_some(effective)
            }
            (Some(cpuset), None) => Some(cpuset),
            (None, Some(online)) => Some(online),
            (None, None) => None,
        };
        let target = allowed
            .map(|allowed| restore_affinity_target(&original, &allowed))
            .unwrap_or_else(|| Some(original.clone()));
        let Some(target) = target else {
            return Err(io::Error::from_raw_os_error(22));
        };
        if let Err(err) = set_affinity(tid, &target) {
            affinity_error = Some(err);
        }
    }
    // 只有 cpuset 与亲和性都恢复成功才算完成；如果 ROM 拒绝了其中一层，
    // 上层保留原基线并退避重试，不能把瞬时失败当成已恢复。
    match affinity_error.or(cpuset_error) {
        None => Ok(()),
        Some(error) => Err(classify_restore_error(error, || managed_tid_identity_status(tid, entry))),
    }
}

fn classify_restore_error(error: io::Error, identity: impl FnOnce() -> ManagedTidIdentityStatus) -> io::Error {
    if !is_thread_gone_error(&error) { return error; }
    // ENOENT 可能表示原 cpuset 目录不存在，而非线程已退出。
    // 只有重新检查线程身份后，才能决定是否丢弃其恢复基线。
    match identity() {
        ManagedTidIdentityStatus::GoneOrReused => io::Error::from_raw_os_error(3),
        ManagedTidIdentityStatus::Unreadable => io::Error::new(io::ErrorKind::WouldBlock, error),
        ManagedTidIdentityStatus::Current => io::Error::other(error),
    }
}

pub(crate) fn restore_affinity_target(original: &CpuMask, current: &CpuMask) -> Option<CpuMask> {
    let clipped = original.intersection(current);
    (!clipped.is_empty()).then_some(clipped)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_cpuset_is_not_evidence_that_a_live_thread_exited() {
        for status in [ManagedTidIdentityStatus::Current, ManagedTidIdentityStatus::Unreadable] {
            let error = classify_restore_error(io::Error::from_raw_os_error(2), || status);
            assert!(!is_thread_gone_error(&error));
        }
        let error = classify_restore_error(io::Error::from_raw_os_error(2), || ManagedTidIdentityStatus::GoneOrReused);
        assert!(is_thread_gone_error(&error));
    }
}
