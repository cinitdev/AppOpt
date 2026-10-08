use super::*;
use std::path::PathBuf;

struct Fixture { root: PathBuf, state: PathBuf }
impl Fixture {
    fn new() -> Self {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let sequence = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let root = std::env::temp_dir().join(format!("qixia-owned-cpuset-{}-{sequence}", std::process::id()));
        fs::create_dir(&root).unwrap();
        Self { state: root.join(STATE_NAME), root }
    }
    fn create(&self, registry: &mut Registry, relative: &str) {
        let path = self.root.join(relative.trim_start_matches('/'));
        registry.create(&path, relative, |_| Ok(identity_for(relative)),
            |registry| registry.save(&self.state, "boot-test")).unwrap();
    }
    fn identity(&self, path: &Path) -> io::Result<Identity> {
        existing_directory(path)?;
        let relative = format!("/{}", path.strip_prefix(&self.root).unwrap().to_str().unwrap().replace('\\', "/"));
        Ok(identity_for(&relative))
    }
    fn cleanup(&self, registry: &mut Registry) -> bool {
        registry.cleanup(&self.root, false, |path| self.identity(path), |path| fs::remove_dir(path))
    }
}
impl Drop for Fixture {
    fn drop(&mut self) { let _ = fs::remove_dir_all(&self.root); }
}

fn identity_for(relative: &str) -> Identity {
    Identity { device: 1, inode: relative.bytes().fold(17u64, |value, byte| value.wrapping_mul(31).wrapping_add(byte as u64)) }
}

#[test]
fn registry_rejects_unknown_paths_duplicate_entries_and_previous_boot_evidence() {
    for invalid in ["/", "/../0-3", "/QiXia/../auto", "/QiXia/auto/1-2-3", "/QiXia/custom", "/QiXia/0-3/child", "/QiXia/"] {
        assert!(!valid_relative(invalid), "{invalid}");
        assert!(Registry::parse(&format!("{MAGIC}\tboot-test\n{invalid}\t1\t2\n"), "boot-test").is_err());
    }
    let text = format!("{MAGIC}\tboot-test\n/QiXia\t1\t2\n/QiXia/0-3\t1\t3\n");
    assert_eq!(Registry::parse(&text, "boot-test").unwrap().entries.len(), 2);
    assert!(Registry::parse(&text, "another-boot").unwrap().entries.is_empty());
    assert!(Registry::parse(&format!("{text}/QiXia\t1\t2\n"), "boot-test").is_err());
    assert!(Registry::parse("invalid", "boot-test").is_err());
    assert!(!valid_relative("/QiXia/0-1,2-3"));
}

#[test]
fn only_new_directories_are_registered_and_saved_entries_survive_reloading() {
    let fixture = Fixture::new();
    let external = fixture.root.join("top-app");
    fs::create_dir(&external).unwrap();
    let mut registry = Registry::default();
    registry.create(&external, "/top-app", |_| panic!("已有目录不能登记"),
        |_| panic!("已有目录不能触发登记落盘")).unwrap();
    assert!(registry.entries.is_empty());
    fixture.create(&mut registry, "/QiXia");
    // 模拟下一个进程重新读文件后继续新增，不能丢失前一进程的凭据。
    let mut next = Registry::load(&fixture.state, "boot-test").unwrap();
    fixture.create(&mut next, "/QiXia/0-3");
    let saved = Registry::load(&fixture.state, "boot-test").unwrap();
    assert_eq!(saved.entries.len(), 2);
    assert_eq!(saved.entries["/QiXia"], identity_for("/QiXia"));
    assert!(!fixture.state.with_extension("tmp").exists());
}

#[test]
fn failed_registration_rolls_back_before_threads_can_enter() {
    let fixture = Fixture::new();
    let mut registry = Registry::default();
    let path = fixture.root.join("QiXia");
    let result = registry.create(&path, "/QiXia", |_| Ok(identity_for("/QiXia")),
        |_| Err(io::Error::new(io::ErrorKind::PermissionDenied, "测试写入失败")));
    assert!(result.is_err());
    assert!(!path.exists());
    assert!(registry.entries.is_empty());
}

#[test]
fn changing_names_reclaims_children_before_parent_and_keeps_unregistered_old_group() {
    let fixture = Fixture::new();
    fs::create_dir(fixture.root.join("QiXiaRs")).unwrap();
    let mut registry = Registry::default();
    for name in ["QiXia", "QiXiaRs2"] {
        fixture.create(&mut registry, &format!("/{name}"));
        fixture.create(&mut registry, &format!("/{name}/0-7"));
        fixture.create(&mut registry, &format!("/{name}/auto"));
        assert!(fixture.cleanup(&mut registry));
        assert!(!fixture.root.join(name).exists());
        registry.save(&fixture.state, "boot-test").unwrap();
        registry = Registry::load(&fixture.state, "boot-test").unwrap();
        assert!(registry.entries.is_empty());
    }
    assert!(fixture.root.join("QiXiaRs").is_dir());
}

#[test]
fn custom_rom_parent_and_unknown_children_are_never_deleted() {
    let fixture = Fixture::new();
    fs::create_dir(fixture.root.join("top-app")).unwrap();
    let mut registry = Registry::default();
    fixture.create(&mut registry, "/top-app/0-3");
    fixture.create(&mut registry, "/QiXia");
    fixture.create(&mut registry, "/QiXia/auto");
    fs::create_dir(fixture.root.join("QiXia/auto/external-owner")).unwrap();
    assert!(fixture.cleanup(&mut registry));
    assert!(fixture.root.join("top-app").is_dir());
    assert!(!fixture.root.join("top-app/0-3").exists());
    assert!(fixture.root.join("QiXia/auto/external-owner").is_dir());
    assert_eq!(registry.entries.len(), 2);
}

#[test]
fn either_restore_journal_blocks_even_empty_directory_reclamation() {
    let fixture = Fixture::new();
    let static_journal = fixture.root.join("managed_tids.tsv");
    let auto_journal = fixture.root.join(AUTO_RESTORE_NAME);
    let mut registry = Registry::default();
    fixture.create(&mut registry, "/QiXia");
    assert!(restore_journals_absent(&static_journal, &auto_journal));
    for journal in [&static_journal, &auto_journal] {
        fs::write(journal, "尚有恢复基线").unwrap();
        let blocked = !restore_journals_absent(&static_journal, &auto_journal);
        assert!(blocked);
        assert!(!registry.cleanup(&fixture.root, blocked, |_| panic!("不能检查仍可能作为恢复目标的目录"),
            |_| panic!("不能删除仍可能作为恢复目标的目录")));
        assert_eq!(registry.entries.len(), 1);
        assert!(fixture.root.join("QiXia").exists());
        fs::remove_file(journal).unwrap();
    }
    assert!(fixture.cleanup(&mut registry));
}

#[test]
fn post_scan_cleanup_protects_raw_baselines_their_parents_and_the_entire_current_root() {
    let fixture = Fixture::new();
    let mut registry = Registry::default();
    for path in ["/QiXia", "/QiXia/7", "/QiXia/0-6", "/QiXia/auto",
        "/QiXiaRs2", "/QiXiaRs2/7", "/QiXiaRs", "/QiXiaRs/7", "/Unused", "/Unused/7"] {
        fixture.create(&mut registry, path);
    }
    // 分别模拟持久静态日志、live 旧基线及自动租约根；不可用归一后的 / 替代原路径。
    let references = BTreeSet::from(["/QiXia/7".into(), "/QiXiaRs2".into(), "/QiXia/auto".into(), "/".into()]);
    let candidates = registry.unreferenced_old_paths("QiXiaRs", &references);
    assert_eq!(candidates, vec!["/QiXia/0-6", "/QiXiaRs2/7", "/Unused", "/Unused/7"]);
    assert!(registry.cleanup_paths(&fixture.root, candidates, |path| fixture.identity(path), |path| fs::remove_dir(path)).changed);
    for retained in ["QiXia/7", "QiXia/auto", "QiXiaRs2", "QiXiaRs/7"] {
        assert!(fixture.root.join(retained).is_dir(), "{retained}");
    }
    assert!(!fixture.root.join("QiXia/0-6").exists());
    assert!(!fixture.root.join("Unused").exists());
    // 引用确实解除后，可在下一次既有轮次清掉这些旧根，不波及当前配置。
    let candidates = registry.unreferenced_old_paths("QiXiaRs", &BTreeSet::new());
    assert!(registry.cleanup_paths(&fixture.root, candidates, |path| fixture.identity(path), |path| fs::remove_dir(path)).changed);
    assert_eq!(registry.entries.keys().map(String::as_str).collect::<Vec<_>>(), vec!["/QiXiaRs", "/QiXiaRs/7"]);
}

#[test]
fn post_scan_static_references_use_existing_parser_and_fail_closed_on_corruption() {
    let fixture = Fixture::new();
    let journal = fixture.root.join("managed_tids.tsv");
    assert!(static_restore_paths(&journal, "boot-test", "QiXiaRs2").unwrap().is_empty());
    let mut entry = crate::affinity::tests::managed_entry(true);
    entry.original_cpuset = Some("/QiXia/7".into());
    let content = crate::affinity::journal_format::serialize_managed_tid_journal(
        &std::collections::HashMap::from([(1, entry)]), "boot-test", "QiXia");
    fs::write(&journal, content).unwrap();
    assert_eq!(static_restore_paths(&journal, "boot-test", "QiXiaRs2").unwrap(), BTreeSet::from(["/QiXia/7".into()]));
    fs::write(&journal, "不可作为无恢复记录的损坏内容").unwrap();
    assert!(static_restore_paths(&journal, "boot-test", "QiXiaRs2").is_err());
    assert!(static_restore_paths(&fixture.root, "boot-test", "QiXiaRs2").is_err());
}

#[test]
fn post_scan_cleanup_retries_late_empty_groups_only_on_three_completed_existing_rounds() {
    let fixture = Fixture::new();
    let mut registry = Registry::default();
    fixture.create(&mut registry, "/QiXia");
    fixture.create(&mut registry, "/QiXia/7");
    let mut attempts = 0;
    after_scan_attempt(&mut attempts, false, || panic!("未完成的扫描不消耗机会")).unwrap();
    assert_eq!(attempts, 0);
    let candidates = registry.unreferenced_old_paths("QiXiaRs2", &BTreeSet::new());
    after_scan_attempt(&mut attempts, true, || {
        assert!(!registry.cleanup_paths(&fixture.root, candidates, |path| fixture.identity(path),
            |_| Err(io::Error::from_raw_os_error(16))).changed);
        Ok(true)
    }).unwrap();
    assert_eq!(attempts, 1);
    let candidates = registry.unreferenced_old_paths("QiXiaRs2", &BTreeSet::new());
    after_scan_attempt(&mut attempts, true, || {
        // 后一轮正常接管已让旧组变空，回收本身不迁移任何未知线程。
        assert!(registry.cleanup_paths(&fixture.root, candidates, |path| fixture.identity(path), |path| fs::remove_dir(path)).changed);
        Ok(false)
    }).unwrap();
    assert_eq!(attempts, 3);
    after_scan_attempt(&mut attempts, true, || panic!("无旧组后不再读取文件")).unwrap();
    let mut failed_attempts = 0;
    for _ in 0..3 {
        assert!(after_scan_attempt(&mut failed_attempts, true, || Err(io::Error::other("读取失败"))).is_err());
    }
    after_scan_attempt(&mut failed_attempts, true, || panic!("不能形成无界错误重试")).unwrap();
}

#[test]
fn replacement_inode_is_not_owned_and_busy_or_unreadable_directories_are_retained() {
    let fixture = Fixture::new();
    let mut registry = Registry::default();
    fixture.create(&mut registry, "/QiXia");
    fixture.create(&mut registry, "/QiXiaRs2");
    let removed = std::cell::Cell::new(0);
    assert!(registry.cleanup(&fixture.root, false, |path| {
        if path.ends_with("QiXia") { Ok(Identity { device: 1, inode: 999 }) }
        else { fixture.identity(path) }
    }, |_| {
        removed.set(removed.get() + 1);
        Err(io::Error::from_raw_os_error(16))
    }));
    assert_eq!(removed.get(), 1);
    assert!(fixture.root.join("QiXia").exists());
    assert!(!registry.entries.contains_key("/QiXia"));
    assert!(registry.entries.contains_key("/QiXiaRs2"));
    assert!(!registry.cleanup(&fixture.root, false,
        |_| Err(io::Error::new(io::ErrorKind::PermissionDenied, "不可读取")),
        |_| panic!("身份不可读时不能删除")));
    assert!(fixture.cleanup(&mut registry));
    assert!(!fixture.root.join("QiXiaRs2").exists());
}

#[test]
fn ownership_cache_uses_fresh_inode_without_reloading_registration() {
    let mut registry = Registry::default();
    for path in ["/QiXia", "/QiXia/0-6", "/top-app/0-3"] {
        registry.entries.insert(path.to_owned(), identity_for(path));
    }
    let mut cache = OwnershipCache::default();
    cache.replace(&registry);
    let reads = std::cell::Cell::new(0);
    for _ in 0..4 {
        assert!(cache.owns("/QiXia/0-6", |path| {
            reads.set(reads.get() + 1);
            Ok(identity_for(path))
        }));
    }
    assert_eq!(reads.get(), 8);
    assert!(cache.owns("/QiXia", |path| {
        reads.set(reads.get() + 1);
        Ok(identity_for(path))
    }));
    assert_eq!(reads.get(), 9);
    assert!(!cache.owns("/top-app/0-3", |_| panic!("ROM 父组没有创建证据")));
    assert!(!cache.owns("/QiXia/foreign", |_| panic!("不能认领未知子组")));
    assert!(!cache.owns("/QiXia/0-6", |_| Ok(Identity { device: 1, inode: 999 })));
    cache.replace(&Registry::default());
    assert!(!cache.owns("/QiXia", |_| panic!("回收后的登记必须立即失效")));
}

#[test]
fn retry_backoff_is_bounded_and_stops_when_recovery_becomes_pending() {
    let mut pauses = Vec::new();
    let mut rounds = 0;
    let result = bounded_cleanup(|| true, |selected| {
        rounds += 1;
        if rounds > 1 { assert!(selected.unwrap().contains("/QiXia/7")); }
        CleanupPass { changed: rounds == 2, retryable: BTreeSet::from(["/QiXia/7".to_owned()]),
            last_error: Some("测试内核暂时占用".to_owned()) }
    }, |duration| pauses.push(duration));
    assert_eq!(rounds, 4);
    assert!(result.changed);
    assert_eq!(pauses, [100, 250, 500].map(Duration::from_millis));

    let pending = std::cell::Cell::new(false);
    let mut rounds = 0;
    bounded_cleanup(|| !pending.get(), |_| {
        rounds += 1;
        CleanupPass { retryable: BTreeSet::from(["/QiXia/7".to_owned()]), ..CleanupPass::default() }
    }, |_| pending.set(true));
    assert_eq!(rounds, 1);
}

#[test]
fn only_empty_busy_leaf_and_its_ancestors_are_retried() {
    let fixture = Fixture::new();
    let mut registry = Registry::default();
    for path in ["/QiXia", "/QiXia/7", "/QiXiaRs2"] { fixture.create(&mut registry, path); }
    fs::write(fixture.root.join("QiXia/7/tasks"), "").unwrap();
    fs::write(fixture.root.join("QiXiaRs2/tasks"), "123\n").unwrap();
    let first = registry.cleanup_pass(&fixture.root, false, None, |path| fixture.identity(path),
        |_| Err(io::Error::from_raw_os_error(16)));
    assert_eq!(first.retryable, BTreeSet::from(["/QiXia/7".to_owned()]));
    let mut attempts = Vec::new();
    let second = registry.cleanup_pass(&fixture.root, false, Some(&first.retryable), |path| fixture.identity(path),
        |path| {
            attempts.push(path.to_path_buf());
            if path.ends_with("7") { fs::remove_file(path.join("tasks"))?; }
            fs::remove_dir(path)
        });
    assert!(second.changed);
    assert!(second.retryable.is_empty());
    assert_eq!(attempts, vec![fixture.root.join("QiXia/7"), fixture.root.join("QiXia")]);
    assert!(fixture.root.join("QiXiaRs2").is_dir());
}

#[cfg(unix)]
#[test]
#[ignore = "仅在明确授权的设备上以 Root 运行，不使用生产登记或恢复日志"]
fn device_private_cpuset_cleanup_preserves_busy_thread_and_external_parent() {
    use std::sync::mpsc;
    let (identity_tx, identity_rx) = mpsc::channel();
    let (stop_tx, stop_rx) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
        identity_tx.send(tid).unwrap();
        let _ = stop_rx.recv();
    });
    let tid = identity_rx.recv().unwrap();
    let original = fs::read_to_string(format!("/proc/{}/task/{tid}/cpuset", std::process::id())).unwrap();
    let name = format!("QixiaOwnedTest-{}", std::process::id());
    let root = Path::new(ROOT);
    let parent = root.join(&name);
    assert!(!parent.exists());
    let mut registry = Registry::default();
    let mut create = |relative: &str| {
        let path = root.join(relative.trim_start_matches('/'));
        registry.create(&path, relative, directory_identity, |_| Ok(())).unwrap();
        fs::write(path.join("mems"), fs::read(root.join("mems")).unwrap()).unwrap();
        fs::write(path.join("cpus"), fs::read(root.join("cpus")).unwrap()).unwrap();
    };
    create(&format!("/{name}"));
    create(&format!("/{name}/0"));
    super::super::cpuset::move_tid_to_existing_cpuset(tid, &format!("/{name}/0")).unwrap();
    assert!(!registry.cleanup(root, false, directory_identity, |path| fs::remove_dir(path)));
    assert!(parent.is_dir());
    super::super::cpuset::move_tid_to_existing_cpuset(tid, original.trim()).unwrap();
    stop_tx.send(()).unwrap();
    worker.join().unwrap();
    assert!(registry.cleanup(root, false, directory_identity, |path| fs::remove_dir(path)));
    assert!(!parent.exists());
    // 测试使用私有内存登记，不读取或改写活跃守护的任何恢复文件。
}
