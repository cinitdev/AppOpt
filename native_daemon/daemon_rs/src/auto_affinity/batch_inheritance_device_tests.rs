//! 真实线程继承检查；请关闭自动分配后串行运行。
use super::*;
use std::sync::mpsc;
use std::thread;

struct InheritedChild {
    identity: Identity,
    inherited_mask: u64,
    inherited_group: String,
    stop: Option<mpsc::Sender<()>>,
    join: Option<thread::JoinHandle<()>>,
}
impl InheritedChild {
    // 必须在受管父线程内部执行，不能在测试框架线程上执行。
    fn new() -> Self {
        let (ready, receive) = mpsc::channel();
        let (stop, wait) = mpsc::channel();
        let join = thread::spawn(move || {
            let pid = std::process::id() as i32;
            let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
            let start = super::super::thread_stat(pid, tid).unwrap().start;
            ready.send(((pid, tid, start), affinity(tid).unwrap(), cpuset::current(pid, tid).unwrap())).unwrap();
            let _ = wait.recv();
        });
        let (identity, inherited_mask, inherited_group) = receive.recv().unwrap();
        Self { identity, inherited_mask, inherited_group, stop: Some(stop), join: Some(join) }
    }
    fn mask(&self) -> u64 { affinity(self.identity.1).unwrap() }
    fn group(&self) -> String { cpuset::current(self.identity.0, self.identity.1).unwrap() }
}
impl Drop for InheritedChild {
    fn drop(&mut self) {
        self.stop.take();
        if let Some(join) = self.join.take() { let _ = join.join(); }
    }
}

struct Parent {
    identity: Identity,
    original: u64,
    original_group: String,
    commands: Option<mpsc::Sender<mpsc::Sender<InheritedChild>>>,
    join: Option<thread::JoinHandle<()>>,
}
impl Parent {
    fn new() -> Self {
        let (ready, receive) = mpsc::channel();
        let (commands, next) = mpsc::channel::<mpsc::Sender<InheritedChild>>();
        let join = thread::spawn(move || {
            let pid = std::process::id() as i32;
            let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
            let start = super::super::thread_stat(pid, tid).unwrap().start;
            ready.send(((pid, tid, start), affinity(tid).unwrap(), cpuset::current(pid, tid).unwrap())).unwrap();
            while let Ok(reply) = next.recv() { let _ = reply.send(InheritedChild::new()); }
        });
        let (identity, original, original_group) = receive.recv().unwrap();
        Self { identity, original, original_group, commands: Some(commands), join: Some(join) }
    }
    fn request(&self, mask: u64) -> Request { Request { identity: self.identity, original: self.original, mask } }
    fn spawn_child(&self) -> InheritedChild {
        let (send, receive) = mpsc::channel();
        self.commands.as_ref().unwrap().send(send).unwrap();
        receive.recv().unwrap()
    }
    fn exit(&mut self) {
        self.commands.take();
        if let Some(join) = self.join.take() { join.join().unwrap(); }
    }
}
impl Drop for Parent { fn drop(&mut self) { self.exit(); } }

fn preconditions() {
    assert!(!std::path::Path::new(JOURNAL).exists(), "must not touch a live app journal");
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
}
fn two_cpus(original: u64) -> (u64, u64) {
    assert!(original.count_ones() >= 2);
    let first = original & original.wrapping_neg();
    let remaining = original & !first;
    (first, remaining & remaining.wrapping_neg())
}
fn owner_removed(key: &Identity) {
    assert!(cpuset::groups(key).unwrap().is_empty());
    assert!(!std::path::Path::new("/dev/cpuset").join(cpuset::owner(key).trim_start_matches('/')).exists());
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn retirement_waits_for_inherited_children_without_false_restore_error() {
    preconditions();
    let parent = Parent::new();
    let available = parent.original & cpuset::available(online_mask().unwrap()).unwrap();
    assert_ne!(available, 0);
    let selected = available & available.wrapping_neg();
    let mut batch = Batch::default();
    assert_eq!(batch.apply(&[parent.request(selected)]).unwrap().written, vec![parent.identity]);
    let child = parent.spawn_child();
    assert!(parent.identity < child.identity, "parent must retire before its child in this test");
    batch.take_events();
    let changes = batch.apply(&[]).unwrap();
    let events = batch.take_events();
    cpuset::assert_restored_for_test(parent.original, affinity(parent.identity.1).unwrap());
    cpuset::assert_restored_for_test(parent.original, child.mask());
    assert_eq!(child.group(), parent.original_group);
    assert!(changes.failed.is_empty(), "{events:#?}");
    assert!(!events.iter().any(|event| event.kind == "error"), "{events:#?}");
    assert_eq!(changes.pending, 1, "retain the parent until its inherited group is emptied");
    assert!(batch.entries.contains_key(&parent.identity));
    assert!(batch.retries.contains_key(&parent.identity));
    batch.retries.clear();
    assert_eq!(batch.apply(&[]).unwrap().pending, 0);
    owner_removed(&parent.identity);
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(batch.release());
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn inherited_idle_child_restores_before_sampling_and_later_gets_its_own_baseline() {
    preconditions();
    let parent = Parent::new();
    let (first, second) = two_cpus(parent.original);
    let mut batch = Batch::default();
    assert_eq!(batch.apply(&[parent.request(first)]).unwrap().written, vec![parent.identity]);
    let child = parent.spawn_child();
    assert_eq!(child.inherited_mask, first);
    assert_eq!(child.inherited_group, cpuset::target(&parent.identity, first));
    assert!(batch.reconcile_inherited(&[child.identity]).unwrap().contains(&child.identity));
    cpuset::assert_restored_for_test(parent.original, child.mask());
    assert_eq!(child.group(), parent.original_group);
    assert!(batch.owns(&parent.identity));
    assert!(batch.mask(&child.identity).is_none());
    let child_original = child.mask();
    let request = Request { identity: child.identity, original: child_original, mask: second };
    assert_eq!(batch.apply(&[parent.request(first), request]).unwrap().written, vec![child.identity]);
    assert!(batch.owns(&child.identity));
    assert_eq!(batch.original(&child.identity), Some(child_original));
    assert_eq!(child.group(), cpuset::target(&child.identity, second));
    assert!(batch.release());
    cpuset::assert_restored_for_test(child_original, child.mask());
    assert_eq!(child.group(), parent.original_group);
    owner_removed(&parent.identity);
    owner_removed(&child.identity);
    assert!(!std::path::Path::new(JOURNAL).exists());
}

#[test]
#[ignore = "Explicit Android integration: requires no existing affinity journal and production guard executable"]
fn guard_restores_unsampled_child_in_old_group_after_parent_migrates_and_exits() {
    preconditions();
    let mut parent = Parent::new();
    let (first, second) = two_cpus(parent.original);
    let mut batch = Batch::default();
    assert_eq!(batch.apply(&[parent.request(first)]).unwrap().written, vec![parent.identity]);
    let child = parent.spawn_child();
    assert_eq!(child.inherited_mask, first);
    assert_eq!(batch.apply(&[parent.request(second)]).unwrap().written, vec![parent.identity]);
    assert_eq!(child.group(), cpuset::target(&parent.identity, first));
    assert!(!batch.entries.contains_key(&child.identity), "simulate a child the next scan has not seen");
    parent.exit();
    batch.guard.as_mut().unwrap().close();
    cpuset::assert_restored_for_test(parent.original, child.mask());
    assert_eq!(child.group(), parent.original_group);
    owner_removed(&parent.identity);
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(batch.release());
}
