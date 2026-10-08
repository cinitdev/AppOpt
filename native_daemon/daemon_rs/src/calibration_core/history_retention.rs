use super::*;
use std::io::Read;

/// 与 DaemonBridge 的认领和删除操作共用；已认领文件仍由应用管理。
pub(super) struct HistoryDirectoryLock {
    path: PathBuf,
    owner: String,
}

impl HistoryDirectoryLock {
    pub(super) fn acquire(directory: &Path) -> io::Result<Self> {
        let path = directory.join(".history.lock");
        let owner = format!(
            "{}:{}",
            std::process::id(),
            process_start(std::process::id()).unwrap_or(0)
        );
        for _ in 0..100 {
            match fs::create_dir(&path) {
                Ok(()) => {
                    if let Err(error) = fs::write(path.join("owner"), &owner) {
                        let _ = fs::remove_dir(&path);
                        return Err(error);
                    }
                    return Ok(Self { path, owner });
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                    recover_abandoned_lock(&path);
                    thread::sleep(Duration::from_millis(50));
                }
                Err(error) => return Err(error),
            }
        }
        Err(io::Error::new(
            io::ErrorKind::TimedOut,
            "history directory is busy",
        ))
    }
}

impl Drop for HistoryDirectoryLock {
    fn drop(&mut self) {
        if fs::read_to_string(self.path.join("owner")).is_ok_and(|owner| owner == self.owner) {
            let _ = fs::remove_file(self.path.join("owner"));
            let _ = fs::remove_dir(&self.path);
        }
    }
}

fn process_start(pid: u32) -> Option<u64> {
    let text = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    text.rsplit_once(") ")?
        .1
        .split_whitespace()
        .nth(19)?
        .parse()
        .ok()
}

fn recover_abandoned_lock(path: &Path) {
    let owner_path = path.join("owner");
    let owner = fs::read_to_string(&owner_path).unwrap_or_default();
    let abandoned = if let Some((pid, start)) = owner
        .trim()
        .split_once(':')
        .and_then(|(pid, start)| Some((pid.parse::<u32>().ok()?, start.parse::<u64>().ok()?)))
    {
        // 即使 I/O 缓慢，也不能仅凭锁的存续时间抢占仍在工作的写入或导入进程。
        process_start(pid).map_or_else(
            || {
                cfg!(any(target_os = "android", target_os = "linux"))
                    && !Path::new(&format!("/proc/{pid}")).exists()
            },
            |current| current != start,
        )
    } else {
        // 若在创建锁目录后、发布持有者信息前崩溃，将没有可供核验的持有者身份。
        fs::metadata(path)
            .and_then(|metadata| metadata.modified())
            .ok()
            .and_then(|time| SystemTime::now().duration_since(time).ok())
            .is_some_and(|age| age > Duration::from_secs(30))
    };
    if abandoned && fs::read_to_string(&owner_path).unwrap_or_default() == owner {
        if !owner.is_empty() {
            let _ = fs::remove_file(&owner_path);
        }
        let _ = fs::remove_dir(path);
    }
}

/// 仅重写已完成且未被认领的校准 .log 文件，未知格式保持原样。
pub(super) fn prune(directory: &Path, limit: usize) -> io::Result<()> {
    let mut files = Vec::new();
    let mut sessions = Vec::new();
    let mut entries = fs::read_dir(directory)?;
    for entry in entries.by_ref().take(1000) {
        let entry = entry?;
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) != Some("log")
            || !entry.file_type()?.is_file()
        {
            continue;
        }
        let mut text = String::new();
        if let Err(error) = fs::File::open(&path)
            .and_then(|file| file.take(64 * 1024 * 1024 + 1).read_to_string(&mut text))
        {
            log_warn!(
                "[校准历史] 跳过不可读的历史文件 {}: {error}",
                path.display()
            );
            continue;
        }
        if text.len() > 64 * 1024 * 1024 {
            continue;
        }
        let Some(parts) = session_ranges(&text) else {
            continue;
        };
        let file_index = files.len();
        for (index, (epoch, _, _)) in parts.iter().enumerate() {
            sessions.push((*epoch, path.clone(), file_index, index));
        }
        files.push((path, parts));
    }
    if entries.next().is_some() {
        return Err(io::Error::other(
            "calibration history directory exceeds entry limit",
        ));
    }
    sessions.sort_by(|a, b| {
        b.0.cmp(&a.0)
            .then_with(|| b.1.cmp(&a.1))
            .then_with(|| b.3.cmp(&a.3))
    });
    let keep = sessions
        .into_iter()
        .take(limit)
        .map(|(_, _, file, row)| (file, row))
        .collect::<HashSet<_>>();
    for (file_index, (path, parts)) in files.into_iter().enumerate() {
        let retained_count = (0..parts.len())
            .filter(|index| keep.contains(&(file_index, *index)))
            .count();
        if retained_count == parts.len() {
            continue;
        }
        if retained_count == 0 {
            fs::remove_file(path)?;
            continue;
        }
        // 内存中最多保留一个完整文件，大部分文件无需重写。
        let mut text = String::new();
        fs::File::open(&path)?
            .take(64 * 1024 * 1024 + 1)
            .read_to_string(&mut text)?;
        if text.len() > 64 * 1024 * 1024 || session_ranges(&text).as_ref() != Some(&parts) {
            log_warn!(
                "[校准历史] 文件已变化，保留等待下次清理: {}",
                path.display()
            );
            continue;
        }
        let retained = parts
            .iter()
            .enumerate()
            .filter(|(index, _)| keep.contains(&(file_index, *index)))
            .map(|(_, (_, start, end))| &text[*start..*end])
            .collect::<String>();
        let pending = path.with_extension("log.retention.tmp");
        let mut output = fs::File::create(&pending)?;
        output.write_all(retained.as_bytes())?;
        output.sync_data()?;
        drop(output);
        fs::rename(pending, path)?;
    }
    Ok(())
}

fn session_ranges(text: &str) -> Option<Vec<(u64, usize, usize)>> {
    let mut sessions = Vec::new();
    let mut offset = 0;
    for line in text.split_inclusive('\n') {
        if line.starts_with('#') {
            let mut fields = line[1..].split_whitespace();
            let epoch = fields.next()?.parse::<u64>().ok()?;
            let rounds = fields.next()?.parse::<usize>().ok()?;
            if epoch == 0 || rounds == 0 || fields.next().is_some() {
                return None;
            }
            if let Some((_, _, end)) = sessions.last_mut() {
                *end = offset;
            }
            sessions.push((epoch, offset, text.len()));
        } else if sessions.is_empty() && !line.trim().is_empty() {
            return None;
        }
        offset += line.len();
    }
    (!sessions.is_empty()).then_some(sessions)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retention_is_global_and_preserves_claims_drafts_and_in_progress_files() {
        let directory = std::env::temp_dir().join(format!(
            "qixia-calibration-retention-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&directory).unwrap();
        let record = |epoch| format!("# {epoch} 2\n1.0 2.0 e1:worker|1.0,2.0\n");
        for (name, epochs) in [("one", 1..=8), ("two", 9..=16)] {
            fs::write(
                directory.join(format!("{name}.log")),
                epochs.map(record).collect::<String>(),
            )
            .unwrap();
        }
        for name in [
            "one.log.qixia-importing",
            "three.log.rust.tmp",
            "pending.draft",
        ] {
            fs::write(directory.join(name), "preserve").unwrap();
        }
        fs::write(directory.join("broken.log"), [0xff, 0xfe]).unwrap();
        let lock = HistoryDirectoryLock::acquire(&directory).unwrap();
        prune(&directory, 10).unwrap();
        assert_eq!(
            fs::read_to_string(directory.join("one.log")).unwrap(),
            [record(7), record(8)].concat()
        );
        assert_eq!(
            session_ranges(&fs::read_to_string(directory.join("two.log")).unwrap())
                .unwrap()
                .len(),
            8
        );
        for name in [
            "one.log.qixia-importing",
            "three.log.rust.tmp",
            "pending.draft",
        ] {
            assert_eq!(
                fs::read_to_string(directory.join(name)).unwrap(),
                "preserve"
            );
        }
        drop(lock);
        assert!(!directory.join(".history.lock").exists());
        assert_eq!(
            fs::read(directory.join("broken.log")).unwrap(),
            [0xff, 0xfe]
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[cfg(any(target_os = "android", target_os = "linux"))]
    #[test]
    fn stale_timestamp_never_steals_live_owner_and_dead_owner_is_recovered() {
        let directory = std::env::temp_dir().join(format!(
            "qixia-history-lock-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&directory).unwrap();
        let lock = HistoryDirectoryLock::acquire(&directory).unwrap();
        fs::File::open(&lock.path)
            .unwrap()
            .set_modified(UNIX_EPOCH)
            .unwrap();
        recover_abandoned_lock(&lock.path);
        assert_eq!(
            fs::read_to_string(lock.path.join("owner")).unwrap(),
            lock.owner
        );
        drop(lock);
        let path = directory.join(".history.lock");
        fs::create_dir(&path).unwrap();
        fs::write(path.join("owner"), "4294967295:1").unwrap();
        recover_abandoned_lock(&path);
        assert!(!path.exists());
        fs::remove_dir_all(directory).unwrap();
    }
}
