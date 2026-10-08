//! 显式启用的集成测试只修改测试自身的工作线程。请串行运行，
//! 关闭自动分配，并设置 QIXIA_TEST_GUARD_EXE。
use super::*;
use std::sync::mpsc;
use std::thread;

#[path = "cpuset_migration_device_tests.rs"]
mod migration;

struct Worker {
    identity: Identity,
    original: u64,
    stop: Option<mpsc::Sender<()>>,
    join: Option<thread::JoinHandle<()>>,
}
impl Worker {
    fn new() -> Self {
        let (send, receive) = mpsc::channel();
        let (stop, wait) = mpsc::channel();
        let join = thread::spawn(move || {
            let pid = std::process::id() as i32;
            let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
            let original = affinity(tid).unwrap();
            let start = super::super::thread_stat(pid, tid).unwrap().start;
            send.send(((pid, tid, start), original)).unwrap();
            let _ = wait.recv();
            let _ = write_affinity(tid, original);
        });
        let (identity, original) = receive.recv().unwrap();
        Self {
            identity,
            original,
            stop: Some(stop),
            join: Some(join),
        }
    }
    fn request(&self, mask: u64) -> Request {
        Request {
            identity: self.identity,
            original: self.original,
            mask,
        }
    }
    fn mask(&self) -> u64 {
        affinity(self.identity.1).unwrap()
    }
}
impl Drop for Worker {
    fn drop(&mut self) {
        self.stop.take();
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

#[test]
fn v3_release_preserves_external_policy_but_restores_remaining_owned_masks() {
    // 不使用 Batch 或共享日志，只修改本测试的私有工作线程。
    let worker = Worker::new();
    let allowed = worker.original & online_mask().unwrap() & !cpuset::parked().unwrap();
    if allowed.count_ones() < 2 { return; }
    let first = allowed & allowed.wrapping_neg();
    let remaining = allowed & !first;
    let second = remaining & remaining.wrapping_neg();
    let group = cpuset::current(worker.identity.0, worker.identity.1).unwrap();
    assert!(!cpuset::owned_path(&group));
    let mut entry = Entry {
        record: Record {
            boot: boot_id().unwrap(), token: "restore-test".into(),
            pid: worker.identity.0, tid: worker.identity.1, start: worker.identity.2,
            original: worker.original, written: first,
        },
        previous: None, original_cpuset: Some(group.clone()), owned_root: cpuset::root(), inherited_from: None,
    };
    write_affinity(worker.identity.1, second).unwrap();
    restore(&entry).unwrap();
    assert_eq!(worker.mask(), second, "preserve a newer system mask outside our tree");
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), group);
    entry.previous = Some(second);
    restore(&entry).unwrap();
    cpuset::assert_restored_for_test(worker.original, worker.mask());
    entry.previous = None;
    write_affinity(worker.identity.1, first).unwrap();
    restore(&entry).unwrap();
    cpuset::assert_restored_for_test(worker.original, worker.mask());
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn retirement_retry_records_one_error_per_failed_restore_attempt() {
    assert!(!std::path::Path::new(JOURNAL).exists(), "must not touch a live app journal");
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
    let worker = Worker::new();
    let available = worker.original & cpuset::available(online_mask().unwrap()).unwrap();
    assert_ne!(available, 0);
    let selected = available & available.wrapping_neg();
    let mut batch = Batch::default();
    assert_eq!(batch.apply(&[worker.request(selected)]).unwrap().written, vec![worker.identity]);
    let original_group = batch.entries[&worker.identity].original_cpuset.clone();
    let missing_group = format!("/QixiaThreadsMissingRestore-{}", std::process::id());
    assert!(!cpuset::path(&missing_group).unwrap().exists());
    // 只有内存中的本次尝试使用缺失路径；即使断言失败，
    // 持久守卫仍保留真实恢复基线。
    batch.entries.get_mut(&worker.identity).unwrap().original_cpuset = Some(missing_group);
    batch.take_events();
    let changes = batch.apply(&[]).unwrap();
    let events = batch.take_events();
    let retained = batch.entries.contains_key(&worker.identity);
    let backed_off = batch.retries.contains_key(&worker.identity);
    batch.entries.get_mut(&worker.identity).unwrap().original_cpuset = original_group;
    batch.retries.clear();
    assert!(batch.release(), "restore the test worker before checking evidence");
    assert_eq!(changes.failed, vec![worker.identity]);
    assert_eq!(changes.pending, 1);
    assert!(retained && backed_off);
    assert_eq!(events.iter().filter(|event| event.kind == "error").count(), 1, "{events:#?}");
    assert_eq!(events[0].reason, "restore_failed");
    cpuset::assert_restored_for_test(worker.original, worker.mask());
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn batch_writes_multiple_threads_renews_one_guard_and_restores_on_eof() {
    assert!(
        !std::path::Path::new(JOURNAL).exists(),
        "must not touch a live app journal"
    );
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
    let a = Worker::new();
    let b = Worker::new();
    assert!(a.original.count_ones() >= 3);
    let first = a.original & a.original.wrapping_neg();
    let rest = a.original & !first;
    let second = rest & rest.wrapping_neg();
    let mut batch = Batch::default();
    let changes = batch.apply(&[a.request(first), b.request(second)]).unwrap();
    assert_eq!(changes.written.len(), 2);
    assert_eq!((a.mask(), b.mask()), (first, second));
    let journal = fs::read_to_string(JOURNAL).unwrap();
    let stamp = fs::metadata(JOURNAL).unwrap().modified().unwrap();
    assert!(batch
        .apply(&[a.request(first), b.request(second)])
        .unwrap()
        .written
        .is_empty());
    assert_eq!(
        fs::metadata(JOURNAL).unwrap().modified().unwrap(),
        stamp,
        "unchanged rounds must not write to disk"
    );
    let old_guard = batch.guard.as_ref().unwrap().child.id();
    thread::sleep(Duration::from_millis(5));
    batch.guard.as_mut().unwrap().started = Instant::now() - Duration::from_secs(101);
    batch.apply(&[a.request(first), b.request(second)]).unwrap();
    assert_ne!(batch.guard.as_ref().unwrap().child.id(), old_guard);
    assert_ne!(fs::read_to_string(JOURNAL).unwrap(), journal);
    assert_eq!(
        (a.mask(), b.mask()),
        (first, second),
        "old guard must not undo renewed batch"
    );

    // ROM 任务配置先将线程移出，再分配另一颗 CPU；
    // 若仅调用不相交的 sched_setaffinity，会被专用 cpuset 拒绝。
    cpuset::move_thread(a.identity.1, batch.entries[&a.identity].original_cpuset.as_deref().unwrap()).unwrap();
    write_affinity(a.identity.1, second).unwrap();
    let changes = batch.apply(&[a.request(first), b.request(second)]).unwrap();
    assert!(changes.failed.is_empty());
    assert_eq!(changes.pending, 0);
    assert_eq!(changes.written, vec![a.identity]);
    assert_eq!(a.mask(), first);
    assert_eq!(batch.len(), 2);
    // 关闭同一私有管道；父进程收到 SIGKILL 时它也会自动关闭。
    batch.guard.as_mut().unwrap().close();
    cpuset::assert_restored_for_test(b.original, b.mask());
    cpuset::assert_restored_for_test(a.original, a.mask());
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(batch.release());
    println!(
        "PASS two real worker masks; unchanged IO; guard renewal; drift repair; EOF recovery"
    );
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal"]
fn write_ahead_recovery_handles_both_sides_of_a_multi_thread_transition() {
    assert!(!std::path::Path::new(JOURNAL).exists());
    let a = Worker::new();
    let b = Worker::new();
    assert!(a.original.count_ones() >= 3);
    let first = a.original & a.original.wrapping_neg();
    let rest = a.original & !first;
    let second = rest & rest.wrapping_neg();
    let mut entries = BTreeMap::new();
    for worker in [&a, &b] {
        entries.insert(
            worker.identity,
            Entry {
                record: Record {
                    boot: boot_id().unwrap(),
                    token: "1-2".into(),
                    pid: worker.identity.0,
                    tid: worker.identity.1,
                    start: worker.identity.2,
                    original: worker.original,
                    written: second,
                },
                previous: Some(first),
                original_cpuset: None,
                owned_root: cpuset::root(), inherited_from: None,
            },
        );
    }
    write_affinity(a.identity.1, first).unwrap(); // 模拟新系统调用之前崩溃
    write_affinity(b.identity.1, second).unwrap(); // 模拟新系统调用之后崩溃
    persist(&entries).unwrap();
    recover(Some("9-9")).unwrap();
    assert_eq!((a.mask(), b.mask()), (first, second));
    recover(Some("1-2")).unwrap();
    cpuset::assert_restored_for_test(a.original, a.mask());
    cpuset::assert_restored_for_test(b.original, b.mask());
    assert!(!std::path::Path::new(JOURNAL).exists());
    println!("PASS multi-thread write-ahead recovery before and after syscalls");
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn same_mask_takeover_cpuset_only_drift_and_outside_original_are_recoverable() {
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
    let worker = Worker::new();
    let original_group = cpuset::current(worker.identity.0, worker.identity.1).unwrap();
    // 即使原始单核基线已合适，也仍需取得 cpuset 归属。
    // 使用第一颗允许的 CPU，后续也以它作为收窄后的恢复目标。
    let unchanged = worker.original & worker.original.wrapping_neg();
    write_affinity(worker.identity.1, unchanged).unwrap();
    let mut batch = Batch::default();
    assert_eq!(batch.apply(&[worker.request(unchanged)]).unwrap().written, vec![worker.identity]);
    assert_eq!(worker.mask(), unchanged);
    assert_eq!(batch.original(&worker.identity), Some(unchanged));
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), cpuset::target(&worker.identity, unchanged));
    assert!(fs::read_to_string(JOURNAL).unwrap().starts_with("v4 "));
    cpuset::move_thread(worker.identity.1, &original_group).unwrap();
    write_affinity(worker.identity.1, unchanged).unwrap();
    assert!(!batch.owns(&worker.identity));
    assert_eq!(batch.apply(&[worker.request(unchanged)]).unwrap().written, vec![worker.identity]);
    assert!(batch.owns(&worker.identity));
    assert!(batch.release());
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), original_group);
    let narrow = worker.original & worker.original.wrapping_neg();
    let planned_online = online_mask().unwrap();
    let planned_root = cpuset::mask("/").unwrap();
    let broad = cpuset::available(planned_online).unwrap();
    assert!(broad & !narrow != 0);
    write_affinity(worker.identity.1, narrow).unwrap();
    let mut batch = Batch::default();
    let changes = batch.apply(&[worker.request(broad)]).unwrap();
    let events = batch.take_events();
    assert_eq!(changes.written, vec![worker.identity],
        "broad takeover: requested={broad:x} narrow={narrow:x} original={:x} planned_online={planned_online:x} planned_root={planned_root:x} now_online={:?} now_root={:?} actual_mask={:?} actual_group={:?} target_mask={:?} failed={:?} pending={} events={events:#?}",
        worker.original, online_mask(), cpuset::mask("/"), affinity(worker.identity.1),
        cpuset::current(worker.identity.0, worker.identity.1), cpuset::mask(&cpuset::target(&worker.identity, broad)),
        changes.failed, changes.pending);
    assert_eq!(worker.mask(), broad);
    assert_eq!(batch.original(&worker.identity), Some(narrow));
    batch.guard.as_mut().unwrap().close();
    assert_eq!(worker.mask(), narrow);
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), original_group);
    assert!(batch.release());
    write_affinity(worker.identity.1, worker.original).unwrap();
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn maintenance_immediately_repairs_cpuset_and_affinity_drift_without_cached_grace() {
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
    let a = Worker::new();
    let b = Worker::new();
    let available = a.original & b.original & cpuset::available(online_mask().unwrap()).unwrap();
    assert!(available.count_ones() >= 2);
    let first = available & available.wrapping_neg();
    let rest = available & !first;
    let second = rest & rest.wrapping_neg();
    let pair = first | second;
    let mut batch = Batch::default();
    let requests = [a.request(first), b.request(pair)];
    assert_eq!(batch.apply(&requests).unwrap().written.len(), 2);
    let original_group = batch.entries[&a.identity].original_cpuset.clone().unwrap();
    let stamp = fs::metadata(JOURNAL).unwrap().modified().unwrap();
    let contents = fs::read_to_string(JOURNAL).unwrap();
    batch.take_events();
    for _ in 0..3 {
        // 模拟 ROM 任务配置迁移，以及独立的仅亲和性覆盖；
        // 不清空状态，也不等待验证超时。
        cpuset::move_thread(a.identity.1, &original_group).unwrap();
        write_affinity(a.identity.1, second).unwrap();
        write_affinity(b.identity.1, first).unwrap();
        let repaired = batch.maintain(&requests).unwrap();
        assert_eq!(repaired.written.len(), 2);
        assert!(repaired.failed.is_empty());
        assert_eq!(repaired.pending, 0);
        assert_eq!((a.mask(), b.mask()), (first, pair));
        assert!(batch.owns(&a.identity) && batch.owns(&b.identity));
        let events = batch.take_events();
        assert_eq!(events.len(), 2, "{events:#?}");
        assert!(events.iter().all(|event| event.kind == "assign" && event.reason == "control_reasserted"));
        assert!(batch.maintain(&requests).unwrap().written.is_empty());
        assert!(batch.take_events().is_empty(), "unchanged ownership produces no duplicate operations");
        assert_eq!(fs::read_to_string(JOURNAL).unwrap(), contents);
        assert_eq!(fs::metadata(JOURNAL).unwrap().modified().unwrap(), stamp,
            "reasserting an unchanged durable intent must not rewrite the journal");
    }
    assert!(batch.release());
    cpuset::assert_restored_for_test(a.original, a.mask());
    cpuset::assert_restored_for_test(b.original, b.mask());
    println!("PASS immediate repeated cpuset/affinity repair; no cached grace or unchanged journal writes");
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn maintenance_never_admits_new_requests_or_revives_retiring_entries() {
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
    let retiring = Worker::new();
    let active = Worker::new();
    let new = Worker::new();
    let available = retiring.original & active.original & new.original & cpuset::available(online_mask().unwrap()).unwrap();
    assert_ne!(available, 0);
    let selected = available & available.wrapping_neg();
    let mut batch = Batch::default();
    assert_eq!(batch.apply(&[retiring.request(selected), active.request(selected)]).unwrap().written.len(), 2);
    let baseline = batch.entries[&retiring.identity].original_cpuset.clone();
    let missing_group = format!("/QixiaThreadsMissingMaintenanceRestore-{}", std::process::id());
    assert!(!cpuset::path(&missing_group).unwrap().exists());
    // 保留真实的持久基线，仅让内存中的本次恢复失败，
    // 留下快速路径不能再次接管的日志项。
    batch.entries.get_mut(&retiring.identity).unwrap().original_cpuset = Some(missing_group);
    assert_eq!(batch.apply(&[active.request(selected)]).unwrap().failed, vec![retiring.identity]);
    batch.retries.clear(); // 到期恢复仍只能由 apply/release 处理。
    batch.take_events();
    let new_group = cpuset::current(new.identity.0, new.identity.1).unwrap();
    let current_retiring_group = cpuset::current(retiring.identity.0, retiring.identity.1).unwrap();
    let result = batch.maintain(&[active.request(selected), new.request(selected)]).unwrap();
    assert!(result.written.is_empty() && result.failed.is_empty());
    assert_eq!(result.pending, 0);
    assert!(batch.take_events().is_empty());
    assert!(batch.entries.contains_key(&retiring.identity));
    assert!(!batch.entries.contains_key(&new.identity));
    assert_eq!(cpuset::current(new.identity.0, new.identity.1).unwrap(), new_group);
    assert_eq!(new.mask(), new.original);
    assert_eq!(cpuset::current(retiring.identity.0, retiring.identity.1).unwrap(), current_retiring_group);
    assert!(batch.maintain(&[]).unwrap().written.is_empty());
    assert!(batch.owns(&active.identity), "an empty maintenance list must not retire current owners");
    batch.entries.get_mut(&retiring.identity).unwrap().original_cpuset = baseline;
    assert!(batch.release());
    cpuset::assert_restored_for_test(retiring.original, retiring.mask());
    cpuset::assert_restored_for_test(active.original, active.mask());
    println!("PASS maintenance ignores new and retiring requests without performing recovery");
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal"]
fn v3_partial_cpuset_transition_and_partial_restore_failure_keep_only_pending_intent() {
    assert!(!std::path::Path::new(JOURNAL).exists());
    let a = Worker::new();
    let b = Worker::new();
    let group = cpuset::current(a.identity.0, a.identity.1).unwrap();
    let selected = a.original & a.original.wrapping_neg();
    let mut entries = BTreeMap::new();
    for worker in [&a, &b] {
        entries.insert(worker.identity, Entry { record: Record {
            boot: boot_id().unwrap(), token: "1-2".into(), pid: worker.identity.0, tid: worker.identity.1,
            start: worker.identity.2, original: worker.original, written: selected,
        }, previous: None, original_cpuset: Some(group.clone()), owned_root: cpuset::root(), inherited_from: None });
    }
    persist(&entries).unwrap();
    cpuset::prepare(&a.identity, selected).unwrap();
    cpuset::move_thread(a.identity.1, &cpuset::target(&a.identity, selected)).unwrap();
    // 模拟 tasks 迁移之后、写入亲和性之前失败。
    // 另一线程已离开自有目录树，但仍保留我们的掩码，且恢复路径丢失。
    // 如果系统已完全替换掩码，则该线程本应直接释放。
    write_affinity(b.identity.1, selected).unwrap();
    entries.get_mut(&b.identity).unwrap().original_cpuset = Some("/QixiaThreads-test-missing-baseline".into());
    persist(&entries).unwrap();
    assert!(recover(Some("1-2")).is_err());
    cpuset::assert_restored_for_test(a.original, a.mask());
    assert_eq!(cpuset::current(a.identity.0, a.identity.1).unwrap(), group);
    let mut pending = decode(&fs::read_to_string(JOURNAL).unwrap()).unwrap();
    assert_eq!(pending.len(), 1);
    assert!(pending.contains_key(&b.identity));
    pending.get_mut(&b.identity).unwrap().original_cpuset = Some(group);
    persist(&pending).unwrap();
    recover(Some("1-2")).unwrap();
    assert!(!std::path::Path::new(JOURNAL).exists());
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn failure_after_cpuset_move_keeps_intent_does_not_abort_peer_and_guard_restores() {
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
    let a = Worker::new();
    let b = Worker::new();
    let a_group = cpuset::current(a.identity.0, a.identity.1).unwrap();
    let b_group = cpuset::current(b.identity.0, b.identity.1).unwrap();
    let selected = a.original & a.original.wrapping_neg();
    let mut batch = Batch::default();
    batch.fail_after_cpuset = Some(a.identity);
    let changes = batch.apply(&[a.request(selected), b.request(selected)]).unwrap();
    assert_eq!(changes.failed, vec![a.identity]);
    assert_eq!(changes.written, vec![b.identity]);
    assert_eq!(changes.pending, 1);
    assert!(!batch.owns(&a.identity));
    assert!(batch.owns(&b.identity));
    assert_eq!(decode(&fs::read_to_string(JOURNAL).unwrap()).unwrap().len(), 2);
    let stamp = fs::metadata(JOURNAL).unwrap().modified().unwrap();
    let next = batch.apply(&[a.request(selected), b.request(selected)]).unwrap();
    assert!(next.failed.is_empty());
    assert_eq!(next.pending, 1);
    assert_eq!(fs::metadata(JOURNAL).unwrap().modified().unwrap(), stamp);
    batch.guard.as_mut().unwrap().close();
    cpuset::assert_restored_for_test(a.original, a.mask());
    cpuset::assert_restored_for_test(b.original, b.mask());
    assert_eq!(cpuset::current(a.identity.0, a.identity.1).unwrap(), a_group);
    assert_eq!(cpuset::current(b.identity.0, b.identity.1).unwrap(), b_group);
    assert!(cpuset::groups(&a.identity).unwrap().is_empty());
    assert!(cpuset::groups(&b.identity).unwrap().is_empty());
    assert!(batch.release());
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal"]
fn v3_reused_tid_cannot_restore_a_different_thread_identity() {
    assert!(!std::path::Path::new(JOURNAL).exists());
    let worker = Worker::new();
    let selected = worker.original & worker.original.wrapping_neg();
    let group = cpuset::current(worker.identity.0, worker.identity.1).unwrap();
    write_affinity(worker.identity.1, selected).unwrap();
    let stale = (worker.identity.0, worker.identity.1, worker.identity.2 + 1);
    let entry = Entry { record: Record {
        boot: boot_id().unwrap(), token: "1-2".into(), pid: stale.0, tid: stale.1, start: stale.2,
        original: worker.original, written: selected,
    }, previous: None, original_cpuset: Some(group.clone()), owned_root: cpuset::root(), inherited_from: None };
    persist(&BTreeMap::from([(stale, entry)])).unwrap();
    recover(Some("1-2")).unwrap();
    assert_eq!(worker.mask(), selected);
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), group);
    assert!(!std::path::Path::new(JOURNAL).exists());
    write_affinity(worker.identity.1, worker.original).unwrap();
}
