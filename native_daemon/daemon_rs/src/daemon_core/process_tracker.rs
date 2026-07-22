// 文件化进程索引。
//
// 守护进程只在刷新时临时加载缓存，长期状态保存在 pid_cache.tsv；App 和脚本
// 通过同一二进制的查询参数读取它。查询结果返回前仍校验 /proc/<pid>/stat，避免 PID 复用。

// 当前磁盘缓存写入 pid_cache.tsv，旧缓存文件不迁移；查询返回前仍校验当前进程身份。
#[derive(Debug, Clone)]
struct ProcessIndexEntry {
    pid: i32,
    starttime: u64,
    first_seen_elapsed_ms: u64,
    comm: String,
    cmdline: String,
}

#[derive(Debug, Default)]
struct ProcessIndexView {
    current_pids: BTreeSet<i32>,
    candidate_pids: BTreeSet<i32>,
    added: usize,
    exited: usize,
    refreshed: bool,
    loaded: bool,
}

fn refresh_process_index(now_elapsed: u64, rebuild_all: bool) -> io::Result<ProcessIndexView> {
    let old_entries = load_process_index().unwrap_or_default();
    let current_pids = enumerate_proc_pids()?;
    let old_pids = old_entries.keys().copied().collect::<BTreeSet<_>>();
    let added = current_pids.difference(&old_pids).count();
    let exited = old_pids.difference(&current_pids).count();
    let mut entries = BTreeMap::new();
    let mut candidate_pids = BTreeSet::new();
    let mut changed = rebuild_all || added > 0 || exited > 0;

    for pid in current_pids.iter().copied() {
        let old = old_entries.get(&pid);
        let should_refresh = rebuild_all
            || old.is_none()
            || old.is_some_and(|entry| {
                now_elapsed.saturating_sub(entry.first_seen_elapsed_ms)
                    <= PID_DISCOVERY_RETRY_MS
            });
        let mut entry = if should_refresh {
            match read_process_index_entry(pid, now_elapsed, old) {
                Ok(entry) => entry,
                Err(_) => continue,
            }
        } else if let Some(old) = old {
            // 稳定缓存仍需读取一次 starttime，防止 PID 被快速复用后继续沿用旧身份。
            let proc_path = PathBuf::from(format!("/proc/{pid}"));
            match read_proc_starttime(&proc_path) {
                Ok(starttime) if starttime == old.starttime => old.clone(),
                Ok(_) => match read_process_index_entry(pid, now_elapsed, Some(old)) {
                    Ok(entry) => entry,
                    Err(_) => continue,
                },
                Err(_) => continue,
            }
        } else {
            continue;
        };

        if let Some(old) = old {
            if old.starttime == entry.starttime {
                entry.first_seen_elapsed_ms = old.first_seen_elapsed_ms;
            } else {
                entry.first_seen_elapsed_ms = now_elapsed;
                changed = true;
            }
            if old.comm != entry.comm || old.cmdline != entry.cmdline {
                changed = true;
            }
        }
        if now_elapsed.saturating_sub(entry.first_seen_elapsed_ms) <= PID_DISCOVERY_RETRY_MS {
            candidate_pids.insert(pid);
        }
        entries.insert(pid, entry);
    }

    if changed || !Path::new(PROCESS_CACHE_FILE).is_file() {
        write_process_index(&entries, now_elapsed)?;
    }
    Ok(ProcessIndexView {
        current_pids,
        candidate_pids,
        added,
        exited,
        refreshed: true,
        loaded: true,
    })
}

fn load_process_index_view(now_elapsed: u64) -> io::Result<ProcessIndexView> {
    let entries = load_process_index()?;
    Ok(ProcessIndexView {
        current_pids: entries.keys().copied().collect(),
        candidate_pids: entries
            .values()
            .filter(|entry| {
                now_elapsed.saturating_sub(entry.first_seen_elapsed_ms)
                    <= PID_DISCOVERY_RETRY_MS
            })
            .map(|entry| entry.pid)
            .collect(),
        loaded: true,
        ..ProcessIndexView::default()
    })
}

fn read_process_index_entry(
    pid: i32,
    now_elapsed: u64,
    old: Option<&ProcessIndexEntry>,
) -> io::Result<ProcessIndexEntry> {
    let proc_path = PathBuf::from(format!("/proc/{pid}"));
    let starttime = read_proc_starttime(&proc_path)?;
    let comm = read_comm(&proc_path).unwrap_or_default();
    let cmdline = read_cmdline(pid).unwrap_or_default();
    Ok(ProcessIndexEntry {
        pid,
        starttime,
        first_seen_elapsed_ms: old
            .filter(|entry| entry.starttime == starttime)
            .map_or(now_elapsed, |entry| entry.first_seen_elapsed_ms),
        comm,
        cmdline,
    })
}

fn load_process_index() -> io::Result<BTreeMap<i32, ProcessIndexEntry>> {
    let content = fs::read_to_string(PROCESS_CACHE_FILE)?;
    let mut lines = content.lines();
    let header = lines
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "进程索引缺少头部"))?;
    let mut header_fields = header.split('\t');
    if header_fields.next() != Some(PROCESS_INDEX_MAGIC) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "进程索引版本不兼容"));
    }
    let stored_boot_id = header_fields.next().unwrap_or_default();
    let current_boot_id = fs::read_to_string(BOOT_ID_FILE).unwrap_or_default();
    if stored_boot_id.is_empty() || stored_boot_id != current_boot_id.trim() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "进程索引属于其他启动周期"));
    }

    let mut entries = BTreeMap::new();
    for line in lines {
        let mut fields = line.split('\t');
        let Some(pid) = fields.next().and_then(|value| value.parse::<i32>().ok()) else {
            continue;
        };
        let Some(starttime) = fields.next().and_then(|value| value.parse::<u64>().ok()) else {
            continue;
        };
        let Some(first_seen_elapsed_ms) = fields.next().and_then(|value| value.parse::<u64>().ok())
        else {
            continue;
        };
        let Some(comm) = fields.next().and_then(decode_process_index_hex) else {
            continue;
        };
        let Some(cmdline) = fields.next().and_then(decode_process_index_hex) else {
            continue;
        };
        if pid > 0 {
            entries.insert(
                pid,
                ProcessIndexEntry {
                    pid,
                    starttime,
                    first_seen_elapsed_ms,
                    comm,
                    cmdline,
                },
            );
        }
    }
    Ok(entries)
}

fn write_process_index(
    entries: &BTreeMap<i32, ProcessIndexEntry>,
    now_elapsed: u64,
) -> io::Result<()> {
    let boot_id = fs::read_to_string(BOOT_ID_FILE).unwrap_or_default();
    let boot_id = boot_id.trim();
    if boot_id.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "无法读取 boot_id"));
    }
    let mut output = format!("{PROCESS_INDEX_MAGIC}\t{boot_id}\t{now_elapsed}\n");
    for entry in entries.values() {
        output.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\n",
            entry.pid,
            entry.starttime,
            entry.first_seen_elapsed_ms,
            encode_process_index_hex(&entry.comm),
            encode_process_index_hex(&entry.cmdline)
        ));
    }
    let temporary = format!("{PROCESS_CACHE_FILE}.{}.tmp", std::process::id());
    fs::write(&temporary, output)?;
    fs::rename(&temporary, PROCESS_CACHE_FILE).or_else(|err| {
        let _ = fs::remove_file(&temporary);
        Err(err)
    })
}

fn process_index_find_pids(name: &str) -> io::Result<Vec<i32>> {
    let mut pids = Vec::new();
    for entry in load_process_index()?.values() {
        if !process_index_name_matches(entry, name)
            || !process_index_current_name_matches(entry, name)
        {
            continue;
        }
        pids.push(entry.pid);
    }
    Ok(pids)
}

fn process_index_mark_candidate(pid: i32, now_elapsed: u64) -> io::Result<()> {
    let mut entries = load_process_index()?;
    if let Some(entry) = entries.get_mut(&pid) {
        entry.first_seen_elapsed_ms = now_elapsed;
    } else {
        let entry = read_process_index_entry(pid, now_elapsed, None)?;
        entries.insert(pid, entry);
    }
    write_process_index(&entries, now_elapsed)
}

fn process_index_find_names(names: &[String]) -> io::Result<Vec<String>> {
    let entries = load_process_index()?;
    let current_pids = enumerate_proc_pids()?;
    let cached_pids = entries.keys().copied().collect::<BTreeSet<_>>();
    if current_pids != cached_pids {
        return Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "进程索引落后于当前 PID 快照",
        ));
    }
    let mut found = Vec::new();
    for name in names {
        if entries.values().any(|entry| {
            process_index_name_matches(entry, name)
                && process_index_current_name_matches(entry, name)
        }) {
            found.push(name.clone());
        }
    }
    Ok(found)
}

fn process_index_print_pids(name: &str) -> io::Result<()> {
    let pids = process_index_find_pids(name)?;
    for pid in &pids {
        println!("{pid}");
    }
    if pids.is_empty() {
        Err(io::Error::new(io::ErrorKind::NotFound, "进程索引未命中"))
    } else {
        Ok(())
    }
}

fn process_index_print_names(names: &[String]) -> io::Result<()> {
    let found = process_index_find_names(names)?;
    for name in &found {
        println!("{name}");
    }
    // 索引文件有效时，未命中只表示该名称当前不存在，不应迫使 App 再次全量遍历 /proc。
    Ok(())
}

fn process_index_name_matches(entry: &ProcessIndexEntry, name: &str) -> bool {
    entry.comm == name
        || entry.cmdline == name
        || Path::new(&entry.cmdline)
            .file_name()
            .and_then(OsStr::to_str)
            .is_some_and(|base| base == name)
}

fn process_index_current_name_matches(entry: &ProcessIndexEntry, name: &str) -> bool {
    let proc_path = PathBuf::from(format!("/proc/{}", entry.pid));
    if !read_proc_starttime(&proc_path).is_ok_and(|starttime| starttime == entry.starttime) {
        return false;
    }
    let current = ProcessIndexEntry {
        pid: entry.pid,
        starttime: entry.starttime,
        first_seen_elapsed_ms: entry.first_seen_elapsed_ms,
        comm: read_comm(&proc_path).unwrap_or_default(),
        cmdline: read_cmdline(entry.pid).unwrap_or_default(),
    };
    process_index_name_matches(&current, name)
}

fn encode_process_index_hex(value: &str) -> String {
    if value.is_empty() {
        return "-".to_string();
    }
    let mut output = String::with_capacity(value.len() * 2);
    for byte in value.as_bytes() {
        output.push_str(&format!("{byte:02x}"));
    }
    output
}

fn decode_process_index_hex(value: &str) -> Option<String> {
    if value == "-" {
        return Some(String::new());
    }
    if value.len() % 2 != 0 {
        return None;
    }
    let mut bytes = Vec::with_capacity(value.len() / 2);
    for pair in value.as_bytes().chunks_exact(2) {
        let text = std::str::from_utf8(pair).ok()?;
        bytes.push(u8::from_str_radix(text, 16).ok()?);
    }
    String::from_utf8(bytes).ok()
}
