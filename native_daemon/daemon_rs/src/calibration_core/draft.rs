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
        "QIXIA_CALIBRATION_DRAFT\t3\nmeta\t{id}\t{pkg}\t{stamp}\t{}\t{omitted}\n",
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
            "thread\t{}\t{}\t{:.2}\t{:.2}\t{:.2}\t{cpus}\t{}\t{}\t{}\t{}\t{}",
            draft_hex(&s.owner),
            draft_hex(&s.name),
            s.average,
            s.peak,
            s.activity,
            draft_hex(&s.reason),
            s.members.len(),
            draft_hex(&preview),
            s.active_threads.unwrap_or(0),
            u8::from(s.peak_bounded)
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
