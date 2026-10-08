use super::*;

// 会话文件发布后保持不变；新会话发布成功后替换同一应用的旧草稿。
// 只有用户在应用中明确点击保存时才写入规则。
pub(super) const DRAFT_CAPABILITY: &str =
    "/data/adb/modules/QixiaThreads/config/calibration_draft.version";
const MAX_DRAFT_PACKAGES: usize = 64;
const MAX_DRAFT_STAMP: u64 = 999_999_999_999_999_999;

fn lock_draft_directory(dir: &Path) -> io::Result<fs::File> {
    fs::create_dir_all(dir)?;
    // 关闭此句柄会释放系统锁，进程退出时也会释放；不能删除锁文件。
    let lock = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(dir.join(".draft.lock"))?;
    // 较早版本的 Rust 标准库在 Android 上调用 File::lock 会返回 Unsupported。
    // 直接调用 Android 的 flock，并保持关闭句柄即释放锁的生命周期。
    #[cfg(target_os = "android")]
    {
        use std::os::fd::AsRawFd;

        loop {
            if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX) } == 0 {
                break;
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    lock.lock()?;
    Ok(lock)
}

fn next_draft_stamp(dir: &Path, now: u64) -> io::Result<u64> {
    use std::io::Read;

    // 独立于待确认草稿持久化预留编号：应用会记住已确认的会话 ID，
    // 因此守护进程重启后即使时钟回拨，也不能再次使用较旧的时间戳。
    let _lock = lock_draft_directory(dir)?;
    let stamp_path = dir.join(".draft.stamp");
    let mut previous = match fs::File::open(&stamp_path) {
        Ok(file) => {
            let mut text = String::new();
            file.take(21).read_to_string(&mut text)?;
            let value = text.trim();
            if !(1..=18).contains(&value.len()) || !value.bytes().all(|b| b.is_ascii_digit()) {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid draft timestamp watermark",
                ));
            }
            value.parse::<u64>().map_err(|_| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid draft timestamp watermark",
                )
            })?
        }
        Err(err) if err.kind() == io::ErrorKind::NotFound => 0,
        Err(err) => return Err(err),
    };
    // 从已有旧草稿初始化，同时允许升级后存在有效但过时的编号高水位。
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        match entry.file_type() {
            Ok(kind) if kind.is_file() => {}
            Ok(_) => continue,
            Err(err) if err.kind() == io::ErrorKind::NotFound => continue,
            Err(err) => return Err(err),
        }
        if let Some((_, stamp, _)) = entry.file_name().to_str().and_then(draft_identity) {
            previous = previous.max(stamp);
        }
    }
    let stamp = now.max(
        previous
            .checked_add(1)
            .ok_or_else(|| io::Error::other("draft session ID exhausted"))?,
    );
    if stamp > MAX_DRAFT_STAMP {
        return Err(io::Error::other("draft session ID exhausted"));
    }
    let temporary = dir.join(".draft.stamp.tmp");
    // 该预留暂存路径由同一把锁保护，崩溃可能在此遗留文件；
    // 预留下一个时间戳前只能清理该文件，不能删除目录。
    match fs::remove_file(&temporary) {
        Ok(()) => {}
        Err(err) if err.kind() == io::ErrorKind::NotFound => {}
        Err(err) => return Err(err),
    }
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)?;
    let result = (|| {
        writeln!(file, "{stamp}")?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, &stamp_path)?;
        sync_draft_directory(dir)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result?;
    Ok(stamp)
}

fn draft_identity(name: &str) -> Option<(&str, u64, u64)> {
    let (pkg, id) = name.strip_suffix(".draft")?.rsplit_once('.')?;
    if pkg.len() > 180
        || !pkg.contains('.')
        || !pkg.split('.').all(|part| {
            !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
    {
        return None;
    }
    let (stamp, pid) = id.split_once('-')?;
    if !(1..=18).contains(&stamp.len())
        || !(1..=10).contains(&pid.len())
        || !stamp.bytes().all(|b| b.is_ascii_digit())
        || !pid.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    Some((pkg, stamp.parse().ok()?, pid.parse().ok()?))
}

fn sync_draft_directory(dir: &Path) -> io::Result<()> {
    // 在 Android/Linux 上，必须先持久化新目录项，再清理前一次会话。
    #[cfg(unix)]
    fs::File::open(dir)?.sync_all()?;
    #[cfg(not(unix))]
    let _ = dir;
    Ok(())
}

pub(super) fn draft_hex(value: &str) -> String {
    value
        .as_bytes()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

pub(super) fn draft_thread_loads(
    records: &[LoadRecord],
    children: &HashMap<ChildThreadKey, ChildThreadSummary>,
    rounds: usize,
) -> Vec<crate::auto_affinity::calibration::ThreadLoad> {
    use crate::auto_affinity::calibration::ThreadLoad;
    // 保留历史活跃依据及同组全部线程，供通配规则的负载预算使用。
    // 分析器另行使用单线程 5% 平均负载门槛筛选规则。
    records
        .iter()
        .filter(|r| !r.is_process)
        .map(|r| ThreadLoad {
            owner: r.owner.clone(),
            name: r.name.clone(),
            average: r.avg(),
            peak: r.max_pct,
            activity: r.activity.percent(rounds),
            active: r.sample_count > 0 && r.activity.worth_saving(r.avg()),
        })
        .chain(children.values().map(|r| ThreadLoad {
            owner: r.owner.clone(),
            name: r.name.clone(),
            average: r.avg(rounds),
            peak: r.max_pct,
            activity: r.activity.percent(rounds),
            active: r.activity.worth_saving(r.avg(rounds)),
        }))
        .collect()
}

pub(super) fn write_calibration_draft(
    pkg: &str,
    duration: Duration,
    rounds: usize,
    records: &[LoadRecord],
    children: &HashMap<ChildThreadKey, ChildThreadSummary>,
    analyzer: &crate::auto_affinity::calibration::Analyzer,
    storage: &crate::private_storage::Storage,
) -> io::Result<Option<String>> {
    use crate::auto_affinity::calibration::MAX_DRAFT_ROWS;
    let rows = draft_thread_loads(records, children, rounds);
    let mut suggestions = analyzer.suggest(&rows);
    let omitted = suggestions.len().saturating_sub(MAX_DRAFT_ROWS);
    suggestions.truncate(MAX_DRAFT_ROWS);
    if suggestions.is_empty() {
        log_info!("[CALIB] 未生成核心建议: pkg={pkg}，没有平均负载达到 5% 的活跃线程；历史记录仍按活跃情况保留");
        return Ok(None);
    }
    let directory = storage.prepare("calibration_drafts")?;
    let stamp = next_draft_stamp(
        &directory,
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis()
            .try_into()
            .map_err(|_| io::Error::other("invalid draft timestamp"))?,
    )?;
    let id = format!("{stamp}-{}", std::process::id());
    let mut text = format!(
        "QIXIA_CALIBRATION_DRAFT\t2\nmeta\t{id}\t{pkg}\t{stamp}\t{}\t{omitted}\n",
        duration.as_millis()
    );
    for (id, capacity) in analyzer.cores() {
        let _ = writeln!(text, "cpu\t{id}\t{capacity}");
    }
    for s in suggestions {
        let cpus = if s.cpus.is_empty() { "-" } else { &s.cpus };
        let preview = s
            .members
            .iter()
            .take(16)
            .cloned()
            .collect::<Vec<_>>()
            .join("\n");
        let _ = writeln!(
            text,
            "thread\t{}\t{}\t{:.2}\t{:.2}\t{:.2}\t{cpus}\t{}\t{}\t{}",
            draft_hex(&s.owner),
            draft_hex(&s.name),
            s.average,
            s.peak,
            s.activity,
            draft_hex(&s.reason),
            s.members.len(),
            draft_hex(&preview)
        );
    }
    persist_draft(
        &directory,
        &format!("{pkg}.{id}.draft"),
        text.as_bytes(),
    )?;
    log_info!("[CALIB] 待确认结果已保存: pkg={pkg} id={id}，未修改生效配置");
    Ok(Some(id))
}

pub(super) fn persist_draft(dir: &Path, name: &str, bytes: &[u8]) -> io::Result<()> {
    let (pkg, stamp, pid) = draft_identity(name)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid draft filename"))?;
    if bytes.len() > 262144 {
        return Err(io::Error::other("draft too large"));
    }
    // 预留编号的锁已释放，发布时不会递归获取同一把锁。
    // 应用数量上限检查、发布和清理均由同一把系统锁保护。
    let _lock = lock_draft_directory(dir)?;
    let mut packages = HashSet::new();
    let mut previous = Vec::new();
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let kind = match entry.file_type() {
            Ok(kind) => kind,
            Err(err) if err.kind() == io::ErrorKind::NotFound => continue, // 应用已确认该会话。
            Err(err) => return Err(err),
        };
        if !kind.is_file() {
            continue;
        }
        let file_name = entry.file_name();
        let Some((owner, old_stamp, old_pid)) = file_name.to_str().and_then(draft_identity) else {
            continue;
        };
        packages.insert(owner.to_owned());
        if owner == pkg {
            if (old_stamp, old_pid) >= (stamp, pid) {
                // 延迟写入不能替换更新的会话，也不能复用已确认的会话 ID。
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "newer draft session already exists",
                ));
            }
            previous.push(entry.path());
        }
    }
    if packages.len() >= MAX_DRAFT_PACKAGES && !packages.contains(pkg) {
        return Err(io::Error::other(
            "请先在 App 处理待确认记录（最多 64 个应用）",
        ));
    }
    let destination = dir.join(name);
    match fs::symlink_metadata(&destination) {
        Ok(_) => {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "draft session already exists",
            ))
        }
        Err(err) if err.kind() == io::ErrorKind::NotFound => {}
        Err(err) => return Err(err),
    }
    let temporary = dir.join(format!("{name}.tmp"));
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)?;
    let result = (|| {
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, &destination)?;
        sync_draft_directory(dir)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result?;
    // 只有新文件完成持久化后，才可删除包名完全相同的旧会话；历史和规则
    // 位于其他位置并保持不变，确认操作仍指向各自唯一的会话路径。
    for old in previous {
        match fs::remove_file(old) {
            Ok(()) => {}
            Err(err) if err.kind() == io::ErrorKind::NotFound => {}
            Err(err) => return Err(err),
        }
    }
    sync_draft_directory(dir)
}

#[cfg(test)]
mod draft_tests {
    use super::*;

    struct DraftTestDir(PathBuf);

    impl DraftTestDir {
        fn new(label: &str) -> Self {
            let stamp = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let path = std::env::temp_dir().join(format!(
                "qixia-draft-{label}-{}-{stamp}",
                std::process::id()
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }

    impl std::ops::Deref for DraftTestDir {
        type Target = Path;
        fn deref(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for DraftTestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn draft_names(dir: &Path) -> Vec<String> {
        let mut names = fs::read_dir(dir)
            .unwrap()
            .map(Result::unwrap)
            .filter(|entry| entry.file_type().unwrap().is_file())
            .filter_map(|entry| entry.file_name().into_string().ok())
            .filter(|name| name.ends_with(".draft"))
            .collect::<Vec<_>>();
        names.sort();
        names
    }

    #[cfg(target_os = "android")]
    #[test]
    fn android_draft_lock_excludes_other_handles_and_releases_on_close() {
        use std::os::fd::AsRawFd;

        let dir = DraftTestDir::new("android-lock");
        let holder = lock_draft_directory(&dir).unwrap();
        let lock_path = dir.join(".draft.lock");
        let contender = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&lock_path)
            .unwrap();
        // 独立打开的句柄必须能观察到真实系统锁，不能退化为空操作。
        assert_eq!(
            unsafe { libc::flock(contender.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) },
            -1
        );
        assert_eq!(io::Error::last_os_error().kind(), io::ErrorKind::WouldBlock);

        drop(holder);
        assert_eq!(
            unsafe { libc::flock(contender.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) },
            0,
            "closing the holder must release the OS lock"
        );
        drop(contender);
        assert!(lock_path.is_file(), "lock files must never be unlinked");
        drop(lock_draft_directory(&dir).unwrap());
    }

    #[test]
    pub(super) fn rule_threshold_does_not_filter_main_or_child_history() {
        let mut records = Vec::new();
        for (name, load, is_process) in [
            ("MainLight", 1.0, false),
            ("MainBelow", 4.99, false),
            ("MainBoundary", 5.0, false),
            ("", 12.0, true),
        ] {
            let key = TrackKey {
                owner: if is_process {
                    "com.test:child"
                } else {
                    "com.test"
                }
                .into(),
                name: name.into(),
                is_process,
            };
            let mut record = LoadRecord::new(&key, 0);
            for _ in 0..60 {
                record.push(load);
            }
            records.push(record);
        }
        let mut children = HashMap::new();
        for (name, load) in [
            ("ChildLight", 1.0),
            ("ChildBelow", 4.99),
            ("ChildBoundary", 5.0),
        ] {
            let key = ChildThreadKey {
                owner: "com.test:child".into(),
                name: name.into(),
            };
            let mut summary = ChildThreadSummary::new(&key);
            for _ in 0..60 {
                summary.push(load);
            }
            children.insert(key, summary);
        }
        let rows = draft_thread_loads(&records, &children, 60);
        let analyzer = crate::auto_affinity::calibration::Analyzer::new();
        let suggestions = analyzer.suggest(&rows);
        let names = suggestions
            .iter()
            .map(|r| r.name.as_str())
            .collect::<HashSet<_>>();
        assert_eq!(names, HashSet::from(["MainBoundary", "ChildBoundary"]));

        let saved = select_history_records(&records);
        assert_eq!(saved.len(), 4);
        assert_eq!(select_history_child_threads(&saved, &children, 60).len(), 3);
        let (history, _) = format_history(123, 60, 60, &saved, &children)
            .unwrap()
            .unwrap();
        for name in [
            "MainLight",
            "MainBelow",
            "MainBoundary",
            "ChildLight",
            "ChildBelow",
            "ChildBoundary",
        ] {
            assert!(history.contains(name), "history must retain {name}");
        }
    }
    #[test]
    pub(super) fn draft_uses_history_activity_for_main_and_child_threads() {
        let mut records = Vec::new();
        for (name, load, samples) in [
            ("LightWorker", 1.0, 60),
            ("OneWake", 100.0, 1),
            ("Sleeping", 0.0, 60),
        ] {
            let key = TrackKey {
                owner: "com.test".into(),
                name: name.into(),
                is_process: false,
            };
            let mut record = LoadRecord::new(&key, 0);
            for _ in 0..samples {
                record.push(load);
            }
            records.push(record);
        }
        let mut children = HashMap::new();
        for (name, samples) in [("ChildLight", 60), ("ChildWake", 1)] {
            let key = ChildThreadKey {
                owner: "com.test:child".into(),
                name: name.into(),
            };
            let mut summary = ChildThreadSummary::new(&key);
            for _ in 0..samples {
                summary.push(1.0);
            }
            children.insert(key, summary);
        }
        let rows = draft_thread_loads(&records, &children, 60);
        let selected = rows
            .iter()
            .filter(|r| r.active)
            .map(|r| r.name.as_str())
            .collect::<HashSet<_>>();
        assert_eq!(selected, HashSet::from(["LightWorker", "ChildLight"]));
        assert!(rows.iter().filter(|r| r.active).all(|r| r.average < 5.0));
        assert_eq!(
            rows.len(),
            5,
            "Inactive peers remain available for wildcard budgeting"
        );
    }
    #[test]
    pub(super) fn meaningful_bursts_survive_a_low_whole_session_average() {
        let key = TrackKey {
            owner: "com.test".into(),
            name: "SceneLoader".into(),
            is_process: false,
        };
        let mut record = LoadRecord::new(&key, 0);
        for _ in 0..6 {
            record.push(40.0);
        }
        for _ in 6..1200 {
            record.push(0.0);
        }
        let rows = draft_thread_loads(&[record], &HashMap::new(), 1200);
        assert!(rows[0].active);
        assert!((rows[0].average - 0.2).abs() < 1e-6);
    }
    #[test]
    pub(super) fn replacement_removes_only_exact_package_drafts_and_keeps_history() {
        let dir = DraftTestDir::new("replacement");
        fs::write(dir.join("applist.conf"), "com.test{RenderThread}=7\n").unwrap();
        fs::write(
            dir.join("com.test.history.log"),
            "all active thread samples",
        )
        .unwrap();
        // 同时清理此前允许多个草稿的实现遗留的重复会话。
        fs::write(dir.join("com.test.1-1.draft"), b"old one").unwrap();
        fs::write(dir.join("com.test.2-1.draft"), b"old two").unwrap();
        persist_draft(&dir, "com.test.child.100-1.draft", b"another package").unwrap();
        persist_draft(&dir, "com.test2.100-1.draft", b"similar package").unwrap();
        persist_draft(&dir, "com.test.3-1.draft", b"new result").unwrap();
        assert_eq!(
            draft_names(&dir),
            [
                "com.test.3-1.draft",
                "com.test.child.100-1.draft",
                "com.test2.100-1.draft"
            ]
        );
        assert_eq!(
            fs::read(dir.join("com.test.3-1.draft")).unwrap(),
            b"new result"
        );
        assert_eq!(
            fs::read(dir.join("com.test.child.100-1.draft")).unwrap(),
            b"another package"
        );
        // 延迟到达的确认仍指向旧会话，不能作用于替换后的新会话。
        let _ = fs::remove_file(dir.join("com.test.2-1.draft"));
        assert!(dir.join("com.test.3-1.draft").exists());
        assert_eq!(
            fs::read_to_string(dir.join("applist.conf")).unwrap(),
            "com.test{RenderThread}=7\n"
        );
        assert_eq!(
            fs::read_to_string(dir.join("com.test.history.log")).unwrap(),
            "all active thread samples"
        );
        assert!(!dir.join("com.test.3-1.draft.tmp").exists());
    }

    #[test]
    fn failed_write_keeps_the_previous_draft() {
        let dir = DraftTestDir::new("write-failure");
        persist_draft(&dir, "com.test.1-1.draft", b"previous result").unwrap();
        assert!(persist_draft(&dir, "com.test.2-1.draft", &vec![0; 262145]).is_err());
        // 暂存路径受阻时，应在发布前产生真实的文件系统错误。
        fs::create_dir(dir.join("com.test.2-1.draft.tmp")).unwrap();
        assert!(persist_draft(&dir, "com.test.2-1.draft", b"new result").is_err());
        assert!(
            dir.join("com.test.2-1.draft.tmp").is_dir(),
            "failed creation must not delete an unowned temporary path"
        );
        assert_eq!(draft_names(&dir), ["com.test.1-1.draft"]);
        assert_eq!(
            fs::read(dir.join("com.test.1-1.draft")).unwrap(),
            b"previous result"
        );
    }

    #[test]
    fn full_application_limit_still_allows_replacing_legacy_duplicates() {
        let dir = DraftTestDir::new("limit");
        for i in 0..MAX_DRAFT_PACKAGES {
            persist_draft(&dir, &format!("com.test.app{i}.1-1.draft"), b"result").unwrap();
        }
        fs::write(dir.join("com.test.app0.2-1.draft"), b"legacy duplicate").unwrap();
        persist_draft(&dir, "com.test.app0.3-1.draft", b"replacement").unwrap();
        assert_eq!(draft_names(&dir).len(), MAX_DRAFT_PACKAGES);
        assert_eq!(
            fs::read(dir.join("com.test.app0.3-1.draft")).unwrap(),
            b"replacement"
        );
        assert!(!dir.join("com.test.app0.1-1.draft").exists());
        assert!(!dir.join("com.test.app0.2-1.draft").exists());
        let error = persist_draft(&dir, "com.another.app.1-1.draft", b"overflow").unwrap_err();
        assert!(error.to_string().contains("64 个应用"));
        assert_eq!(draft_names(&dir).len(), MAX_DRAFT_PACKAGES);
        assert!(!dir.join("com.another.app.1-1.draft.tmp").exists());
    }

    #[test]
    fn session_paths_cannot_be_reused_or_replaced_by_older_writers() {
        let dir = DraftTestDir::new("immutable");
        persist_draft(&dir, "com.test.2-1.draft", b"newer").unwrap();
        for name in ["com.test.2-1.draft", "com.test.1-1.draft"] {
            assert_eq!(
                persist_draft(&dir, name, b"must not replace")
                    .unwrap_err()
                    .kind(),
                io::ErrorKind::AlreadyExists
            );
        }
        assert_eq!(draft_names(&dir), ["com.test.2-1.draft"]);
        assert_eq!(fs::read(dir.join("com.test.2-1.draft")).unwrap(), b"newer");
    }

    #[test]
    fn persisted_timestamp_survives_restart_clock_rollback_and_all_acknowledgements() {
        let dir = DraftTestDir::new("timestamp-restart");
        fs::write(dir.join(".draft.stamp"), b"4000\n").unwrap();
        persist_draft(&dir, "com.test.9000-1.draft", b"legacy result").unwrap();
        // 新分配器仅读取磁盘状态，与刚重启的守护进程保持一致。
        let stamp = next_draft_stamp(&dir, 100).unwrap();
        assert_eq!(stamp, 9001);
        persist_draft(&dir, &format!("com.test.{stamp}-2.draft"), b"new result").unwrap();
        fs::remove_file(dir.join(format!("com.test.{stamp}-2.draft"))).unwrap();
        assert!(draft_names(&dir).is_empty());
        assert_eq!(next_draft_stamp(&dir, 1).unwrap(), 9002);
        assert_eq!(next_draft_stamp(&dir, 12000).unwrap(), 12000);
        assert_eq!(next_draft_stamp(&dir, 0).unwrap(), 12001);
        assert_eq!(
            fs::read_to_string(dir.join(".draft.stamp")).unwrap(),
            "12001\n"
        );
    }

    #[test]
    fn timestamp_reservation_failure_preserves_watermark_and_pending_draft() {
        let dir = DraftTestDir::new("timestamp-failure");
        assert_eq!(next_draft_stamp(&dir, 100).unwrap(), 100);
        persist_draft(&dir, "com.test.100-1.draft", b"previous result").unwrap();
        fs::create_dir(dir.join(".draft.stamp.tmp")).unwrap();
        assert!(next_draft_stamp(&dir, 200).is_err());
        assert_eq!(
            fs::read_to_string(dir.join(".draft.stamp")).unwrap(),
            "100\n"
        );
        assert_eq!(
            fs::read(dir.join("com.test.100-1.draft")).unwrap(),
            b"previous result"
        );
        assert!(dir.join(".draft.stamp.tmp").is_dir());
        fs::remove_dir(dir.join(".draft.stamp.tmp")).unwrap();
        // 预留过程崩溃留下的旧暂存文件不能阻碍后续操作。
        fs::write(dir.join(".draft.stamp.tmp"), b"partial").unwrap();
        assert_eq!(next_draft_stamp(&dir, 1).unwrap(), 101);
        fs::write(dir.join(".draft.stamp"), b"broken").unwrap();
        assert!(next_draft_stamp(&dir, 300).is_err());
        assert_eq!(
            fs::read(dir.join("com.test.100-1.draft")).unwrap(),
            b"previous result"
        );
        assert_eq!(fs::read(dir.join(".draft.stamp")).unwrap(), b"broken");
    }

    #[test]
    fn concurrent_timestamp_reservations_are_unique_and_persisted() {
        let dir = DraftTestDir::new("timestamp-concurrent");
        let barrier = std::sync::Barrier::new(8);
        let mut stamps = thread::scope(|scope| {
            let jobs = (0..8)
                .map(|_| {
                    let dir = &dir;
                    let barrier = &barrier;
                    scope.spawn(move || {
                        barrier.wait();
                        next_draft_stamp(dir, 100).unwrap()
                    })
                })
                .collect::<Vec<_>>();
            jobs.into_iter()
                .map(|job| job.join().unwrap())
                .collect::<Vec<_>>()
        });
        stamps.sort_unstable();
        assert_eq!(stamps, (100..108).collect::<Vec<_>>());
        assert_eq!(next_draft_stamp(&dir, 1).unwrap(), 108);
    }

    #[test]
    fn concurrent_same_millisecond_sessions_leave_only_the_newest_draft() {
        let dir = DraftTestDir::new("concurrent");
        let names = (0..8)
            .map(|_| {
                let stamp = next_draft_stamp(&dir, 100).unwrap();
                format!("com.test.{stamp}-{}.draft", std::process::id())
            })
            .collect::<Vec<_>>();
        assert_eq!(names.iter().collect::<HashSet<_>>().len(), names.len());
        let latest = names.last().unwrap();
        let barrier = std::sync::Barrier::new(names.len());
        thread::scope(|scope| {
            let jobs = names
                .iter()
                .map(|name| {
                    let dir = &dir;
                    let barrier = &barrier;
                    scope.spawn(move || {
                        barrier.wait();
                        match persist_draft(dir, name, name.as_bytes()) {
                            Ok(()) => {}
                            Err(err) => assert_eq!(err.kind(), io::ErrorKind::AlreadyExists),
                        }
                    })
                })
                .collect::<Vec<_>>();
            for job in jobs {
                job.join().unwrap();
            }
        });
        assert_eq!(draft_names(&dir), [latest.clone()]);
        assert_eq!(fs::read(dir.join(latest)).unwrap(), latest.as_bytes());
    }

    #[test]
    fn concurrent_new_packages_cannot_exceed_the_application_limit() {
        let dir = DraftTestDir::new("concurrent-limit");
        for i in 0..MAX_DRAFT_PACKAGES - 1 {
            persist_draft(&dir, &format!("com.test.app{i}.1-1.draft"), b"result").unwrap();
        }
        let barrier = std::sync::Barrier::new(2);
        let successes = thread::scope(|scope| {
            let jobs = ["com.new.first.1-1.draft", "com.new.second.1-1.draft"]
                .into_iter()
                .map(|name| {
                    let dir = &dir;
                    let barrier = &barrier;
                    scope.spawn(move || {
                        barrier.wait();
                        persist_draft(dir, name, b"result").is_ok()
                    })
                })
                .collect::<Vec<_>>();
            jobs.into_iter()
                .map(|job| usize::from(job.join().unwrap()))
                .sum::<usize>()
        });
        assert_eq!(successes, 1);
        assert_eq!(draft_names(&dir).len(), MAX_DRAFT_PACKAGES);
    }
    #[test]
    pub(super) fn wire_names_cannot_inject_rows() {
        assert_eq!(draft_hex("a\tb\n"), "6109620a");
        assert_eq!(draft_hex("线程"), "e7babfe7a88b");
    }
}
