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
pub(super) fn retained_text(text: &str, now_secs: u64) -> String {
    let Some(parts) = session_ranges(text) else { return text.to_owned() };
    parts.iter().filter(|(epoch, _, _)| !crate::history_retention::expired(epoch.saturating_mul(1000), now_secs.saturating_mul(1000)))
        .map(|(_, start, end)| &text[*start..*end]).collect()
}

pub(super) fn prune(directory: &Path, now_secs: u64) -> io::Result<()> {
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) != Some("log") || !entry.file_type()?.is_file() { continue; }
        let mut text = String::new();
        if fs::File::open(&path).and_then(|file| file.take(64 * 1024 * 1024 + 1).read_to_string(&mut text)).is_err()
            || text.len() > 64 * 1024 * 1024 { continue; }
        let retained = retained_text(&text, now_secs);
        if retained == text { continue; }
        if retained.is_empty() { fs::remove_file(&path)?; continue; }
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
