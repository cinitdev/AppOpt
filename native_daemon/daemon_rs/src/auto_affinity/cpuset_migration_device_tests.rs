//! 使用本测试私有线程执行串行、显式启用的 cpuset 配置检查。
//! 生产租约可执行文件启动时不预设 cpuset 名称。
use super::*;
use crate::affinity::cpuset as shared;

fn guard_restores_root(root: &str, cpus: u64, mems: &str) {
    assert!(!std::path::Path::new(JOURNAL).exists(), "must not touch a live app journal");
    let worker = Worker::new();
    let original_group = cpuset::current(worker.identity.0, worker.identity.1).unwrap();
    assert!(!cpuset::owned_path(&original_group));
    let available = cpus & worker.original & online_mask().unwrap() & !cpuset::parked().unwrap();
    assert_ne!(available, 0, "private worker needs an available test CPU");
    let selected = available & available.wrapping_neg();
    let owner = format!("{root}/{}-{}-{}", worker.identity.0, worker.identity.1, worker.identity.2);
    let target = cpuset::target_at(root, &worker.identity, selected);
    assert!(!shared::cpuset_path(&owner).unwrap().exists(), "test worker must have a new owner directory");
    for (group, mask) in [(&owner, cpus), (&target, selected)] {
        shared::ensure_cpuset_dir(&shared::cpuset_path(group).unwrap(),
            &crate::CpuMask::from_low64(mask).to_list(), mems).unwrap();
    }

    let mut guard = Guard::start().unwrap();
    let entry = Entry {
        record: Record { boot: boot_id().unwrap(), token: guard.token.clone(),
            pid: worker.identity.0, tid: worker.identity.1, start: worker.identity.2,
            original: worker.original, written: selected },
        previous: None, original_cpuset: Some(original_group.clone()),
        owned_root: root.to_owned(), inherited_from: None,
    };
    let encoded = entry.encode();
    assert_eq!(decode(&encoded).unwrap()[&worker.identity].owned_root, root);
    atomic_write(JOURNAL, &encoded, true).unwrap();
    shared::move_tid_to_existing_cpuset(worker.identity.1, &target).unwrap();
    write_affinity(worker.identity.1, selected).unwrap();
    assert_eq!(worker.mask(), selected);
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), target);

    guard.close();
    assert!(guard.child.wait().unwrap().success(), "production guard must recover this journal version");
    cpuset::assert_restored_for_test(worker.original, worker.mask());
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), original_group);
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(!shared::cpuset_path(&owner).unwrap().exists(), "cleanup must use the journal's root");
}

#[test]
#[ignore = "Explicit Android integration: automatic allocation off, no journal, production guard, run serially"]
fn production_guard_recovers_custom_v4_without_current_name() {
    assert!(!std::path::Path::new(JOURNAL).exists(), "must not touch a live app journal");
    assert!(std::env::var_os("QIXIA_TEST_GUARD_EXE").is_some());
    assert_eq!(cpuset::root(), format!("/{}/auto", crate::DEFAULT_CPUSET_NAME));
    let configured = fs::read_to_string("/data/adb/modules/QixiaThreads/config/auto_affinity.conf").unwrap_or_default();
    assert!(!configured.lines().map(str::trim).any(|line| !line.is_empty() && !line.starts_with('#')),
        "disable automatic allocation before the shared-journal integration test");

    let name = format!("QixiaThreadsCpusetTest-{}", std::process::id());
    let parent = format!("/{name}");
    let root = format!("{parent}/auto");
    let parent_path = shared::cpuset_path(&parent).unwrap();
    assert!(!parent_path.exists(), "do not reuse an existing user cpuset");
    let (mask, mems) = shared::prepare_base_cpuset(&name).unwrap();
    let allowed = mask.to_low64().unwrap() & online_mask().unwrap() & !cpuset::parked().unwrap()
        & affinity(unsafe { libc::syscall(libc::SYS_gettid) as i32 }).unwrap();
    assert_ne!(allowed, 0);
    let restricted = allowed & allowed.wrapping_neg();
    let restricted_text = crate::CpuMask::from_low64(restricted).to_list();
    // 只收窄新建的测试父组。已有自定义父组必须按现状读取，
    // 即使其范围与控制器根组不同。
    shared::ensure_cpuset_dir(&parent_path, &restricted_text, &mems).unwrap();
    let (readback, same_mems) = shared::prepare_base_cpuset(&name).unwrap();
    assert_eq!(readback.to_low64(), Some(restricted));
    assert_eq!(same_mems, mems);
    shared::ensure_cpuset_dir(&shared::cpuset_path(&root).unwrap(), &restricted_text, &mems).unwrap();
    guard_restores_root(&root, restricted, &mems);
    assert_eq!(fs::read_to_string(parent_path.join("cpus")).unwrap().trim(), restricted_text);
    fs::remove_dir(shared::cpuset_path(&root).unwrap()).unwrap();
    fs::remove_dir(&parent_path).unwrap();

    // 测试真实配置的分配路径，不只是重放日志夹具。
    // 本测试使用独立进程，因为生产守护进程生命周期内
    // NAME 被设计为不可变。
    assert!(!parent_path.exists());
    cpuset::configure(&name).unwrap();
    assert_eq!(cpuset::root(), root);
    let worker = Worker::new();
    let original_group = cpuset::current(worker.identity.0, worker.identity.1).unwrap();
    let available = worker.original & cpuset::available(online_mask().unwrap()).unwrap();
    assert_ne!(available, 0);
    let selected = available & available.wrapping_neg();
    let mut batch = Batch::default();
    assert_eq!(batch.apply(&[worker.request(selected)]).unwrap().written, vec![worker.identity]);
    assert_eq!(worker.mask(), selected);
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(),
        cpuset::target_at(&root, &worker.identity, selected));
    let journal = fs::read_to_string(JOURNAL).unwrap();
    assert!(journal.starts_with("v4 "));
    assert_eq!(decode(&journal).unwrap()[&worker.identity].owned_root, root);
    assert!(batch.release());
    cpuset::assert_restored_for_test(worker.original, worker.mask());
    assert_eq!(cpuset::current(worker.identity.0, worker.identity.1).unwrap(), original_group);
    assert!(!std::path::Path::new(JOURNAL).exists());
    assert!(cpuset::groups_at(&root, &worker.identity).unwrap().is_empty());
    assert!(!shared::cpuset_path(&cpuset::owner(&worker.identity)).unwrap().exists());
    fs::remove_dir(shared::cpuset_path(&root).unwrap()).unwrap();
    fs::remove_dir(&parent_path).unwrap();
}
