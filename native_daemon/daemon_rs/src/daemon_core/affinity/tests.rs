use super::cpuset::normalized_restore_cpuset;
use super::mask::CpuMask;
use super::restore::restore_affinity_target;
use super::state::{managed_action_can_defer, managed_restore_baseline_ready};
use crate::ManagedTidEntry;

pub(super) fn managed_entry(cpuset_synced: bool) -> ManagedTidEntry {
    ManagedTidEntry {
        tgid: 1,
        tgid_starttime: Some(1),
        starttime: Some(1),
        last_seen_round: 1,
        cpuset_synced,
        cpuset_failure_count: 0,
        cpuset_retry_after_elapsed_ms: 0,
        desired_mask_low64: Some(0xff),
        verified_mask_low64: Some(0xff),
        last_affinity_check_elapsed_ms: 1,
        next_affinity_check_elapsed_ms: 1,
        original_mask_low64: Some(0xff),
        original_cpuset: Some("/top-app".to_string()),
        restore_persisted: true,
        restore_pending: false,
        restore_failure_count: 0,
        restore_retry_after_elapsed_ms: 0,
    }
}

#[test]
fn restore_path_leaves_the_previous_qixia_mask_child() {
    assert_eq!(normalized_restore_cpuset("/QiXiaRs/4-5", "QiXiaRs"), "/");
    assert_eq!(
        normalized_restore_cpuset("/top-app/4-5", "top-app"),
        "/top-app"
    );
    assert_eq!(
        normalized_restore_cpuset("/top-app", "QiXiaRs"),
        "/top-app"
    );
}

#[test]
fn restore_affinity_is_clipped_to_the_current_allowed_mask() {
    let original = CpuMask::parse("0-7").unwrap();
    let current = CpuMask::parse("4-7").unwrap();
    let target = restore_affinity_target(&original, &current).unwrap();
    assert_eq!(target.to_list(), "4-7");

    let disjoint = CpuMask::parse("0-3").unwrap();
    assert!(restore_affinity_target(&disjoint, &current).is_none());
}

#[test]
fn wider_mask_is_not_equal_to_its_old_subset() {
    let old = CpuMask::parse("4-5").unwrap();
    let wider = CpuMask::parse("4-7").unwrap();
    assert!(old.is_subset_of(&wider));
    assert_ne!(old, wider);
}

#[test]
fn only_unchanged_synced_actions_defer_to_the_verify_queue() {
    let synced = managed_entry(true);
    let unsynced = managed_entry(false);

    assert!(managed_action_can_defer(Some(&synced), false));
    assert!(!managed_action_can_defer(Some(&synced), true));
    assert!(!managed_action_can_defer(Some(&unsynced), false));
    assert!(!managed_action_can_defer(None, false));
}

#[test]
fn transient_journal_write_failure_only_blocks_unpersisted_entries() {
    let persisted = managed_entry(true);
    assert!(managed_restore_baseline_ready(&persisted));

    let mut pending_write = persisted.clone();
    pending_write.restore_persisted = false;
    assert!(!managed_restore_baseline_ready(&pending_write));
}
#[cfg(any(target_os = "android", target_os = "linux"))]
#[test]
fn syscall_boundary_restores_a_disposable_threads_original_affinity() {
    // 仅操作本测试新建的工作线程，不操作游戏或守护进程。
    std::thread::spawn(|| {
        use super::syscall::{read_allowed_mask_syscall, set_affinity};
        let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
        let original = read_allowed_mask_syscall(tid).unwrap();
        struct Restore(i32, CpuMask);
        impl Drop for Restore {
            fn drop(&mut self) {
                let _ = set_affinity(self.0, &self.1);
            }
        }
        let _restore = Restore(tid, original.clone());
        let cpu = (0..crate::CPU_MASK_WORDS * 64)
            .find(|cpu| original.contains(*cpu))
            .expect("worker has an allowed CPU");
        let restricted = CpuMask::parse(&cpu.to_string()).unwrap();
        for _ in 0..8 {
            set_affinity(tid, &restricted).unwrap();
            assert_eq!(read_allowed_mask_syscall(tid).unwrap(), restricted);
            set_affinity(tid, &original).unwrap();
            assert_eq!(read_allowed_mask_syscall(tid).unwrap(), original);
        }
    })
    .join()
    .unwrap();
}
