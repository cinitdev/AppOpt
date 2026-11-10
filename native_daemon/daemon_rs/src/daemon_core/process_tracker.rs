use super::*;
use std::sync::{Arc, OnceLock, RwLock};
// 轻量进程索引。
//
// 守护进程启动后把 pid_cache.tsv 加载到内存，日常只枚举 /proc 下的数字 PID：
// - 新 PID 和出生后 6 秒内的候选会读取 stat/comm/cmdline；
// - 稳定的非目标 PID 不再每 2 秒读取 stat；
// - 真正生成规则动作或 CLI 返回结果前仍重新校验 starttime 和 cmdline。
//
// 索引变更只标记 dirty，由主循环在本轮候选更新结束后统一原子写盘一次。
pub(super) const PROCESS_INDEX_NAME_MAX_BYTES: usize = 127;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ProcessIndexEntry {
    pub(super) pid: i32,
    pub(super) starttime: u64,
    pub(super) first_seen_elapsed_ms: u64,
    pub(super) comm: String,
    pub(super) cmdline: String,
}

#[derive(Debug, Default)]
pub(super) struct ProcessIndex {
    pub(super) entries: BTreeMap<i32, ProcessIndexEntry>,
    pub(super) loaded: bool,
    pub(super) dirty: bool,
    pub(super) snapshot_complete: bool,
    pub(super) last_flush_elapsed_ms: Option<u64>,
}

/// 核心分配和 FPS 使用主循环当前的内存索引，不依赖限频写入的磁盘缓存。
/// 读取方持有不可变 Arc；读取 procfs 时不持锁，
/// 也不会阻塞守护进程发布下一代索引。
#[derive(Default)]
struct SharedProcessIndex {
    entries: RwLock<Option<Arc<BTreeMap<i32, ProcessIndexEntry>>>>,
}

impl SharedProcessIndex {
    fn publish(&self, entries: &BTreeMap<i32, ProcessIndexEntry>) {
        let snapshot = Arc::new(entries.clone());
        let previous = self.entries.write().unwrap_or_else(|error| error.into_inner())
            .replace(snapshot);
        // 释放发布锁后，再销毁上一代索引。
        drop(previous);
    }

    fn snapshot(&self) -> Option<Arc<BTreeMap<i32, ProcessIndexEntry>>> {
        self.entries.read().unwrap_or_else(|error| error.into_inner()).clone()
    }
}

fn shared_process_index() -> &'static SharedProcessIndex {
    static SHARED: OnceLock<SharedProcessIndex> = OnceLock::new();
    SHARED.get_or_init(SharedProcessIndex::default)
}

pub(super) fn publish_process_index(index: &ProcessIndex) {
    if index.loaded {
        shared_process_index().publish(&index.entries);
    }
}

#[derive(Debug, Default)]
pub(super) struct ProcessIndexView {
    pub(super) current_pids: BTreeSet<i32>,
    pub(super) candidate_pids: BTreeSet<i32>,
    pub(super) added: usize,
    pub(super) exited: usize,
    pub(super) refreshed: bool,
    pub(super) loaded: bool,
}

#[derive(Debug, Default)]
pub(super) struct ProcessIndexUpdate {
    pub(super) candidate_pids: BTreeSet<i32>,
    pub(super) added: usize,
    pub(super) exited: usize,
    pub(super) changed: bool,
    pub(super) complete: bool,
}

pub(super) fn ensure_process_index_loaded(index: &mut ProcessIndex) -> io::Result<()> {
    if index.loaded {
        return Ok(());
    }
    match load_process_index() {
        Ok(entries) => index.entries = entries,
        Err(err)
            if matches!(
                err.kind(),
                io::ErrorKind::NotFound | io::ErrorKind::InvalidData
            ) =>
        {
            // 缓存缺失、损坏或来自其他启动周期时，从本轮 /proc 快照重建。
            index.entries.clear();
            index.dirty = true;
        }
        Err(err) => return Err(err),
    }
    index.loaded = true;
    Ok(())
}

pub(super) fn refresh_process_index(
    index: &mut ProcessIndex,
    now_elapsed: u64,
    rebuild_all: bool,
) -> io::Result<ProcessIndexView> {
    ensure_process_index_loaded(index)?;
    let current_pids = enumerate_proc_pids()?;
    let update = reconcile_process_index(
        &mut index.entries,
        &current_pids,
        now_elapsed,
        rebuild_all,
        |pid, old| read_process_index_entry(pid, now_elapsed, old),
    );
    index.dirty |= update.changed;
    if rebuild_all && !Path::new(PROCESS_CACHE_FILE).is_file() {
        index.dirty = true;
    }
    index.snapshot_complete = next_snapshot_completeness(
        index.snapshot_complete,
        rebuild_all,
        update.complete,
    );
    Ok(ProcessIndexView {
        current_pids,
        candidate_pids: update.candidate_pids,
        added: update.added,
        exited: update.exited,
        refreshed: true,
        loaded: true,
    })
}

pub(super) fn next_snapshot_completeness(
    previous_complete: bool,
    rebuild_all: bool,
    refresh_complete: bool,
) -> bool {
    if rebuild_all {
        refresh_complete
    } else {
        previous_complete && refresh_complete
    }
}

pub(super) fn load_process_index_view(
    index: &mut ProcessIndex,
    now_elapsed: u64,
) -> io::Result<ProcessIndexView> {
    ensure_process_index_loaded(index)?;
    Ok(ProcessIndexView {
        current_pids: index.entries.keys().copied().collect(),
        candidate_pids: process_index_candidate_pids(&index.entries, now_elapsed),
        loaded: true,
        ..ProcessIndexView::default()
    })
}

pub(super) fn reconcile_process_index<F>(
    entries: &mut BTreeMap<i32, ProcessIndexEntry>,
    current_pids: &BTreeSet<i32>,
    now_elapsed: u64,
    rebuild_all: bool,
    mut read_entry: F,
) -> ProcessIndexUpdate
where
    F: FnMut(i32, Option<&ProcessIndexEntry>) -> io::Result<ProcessIndexEntry>,
{
    let added = current_pids
        .iter()
        .filter(|pid| !entries.contains_key(pid))
        .count();
    let exited = entries
        .keys()
        .filter(|pid| !current_pids.contains(pid))
        .count();
    entries.retain(|pid, _| current_pids.contains(pid));
    let mut changed = exited > 0;
    let mut complete = true;

    for pid in current_pids.iter().copied() {
        let should_refresh = rebuild_all
            || entries.get(&pid).is_none_or(|entry| {
                now_elapsed.saturating_sub(entry.first_seen_elapsed_ms)
                    <= PID_DISCOVERY_RETRY_MS
            });
        if !should_refresh {
            continue;
        }

        let previous_starttime = entries.get(&pid).map(|entry| entry.starttime);
        let mut entry = match read_entry(pid, entries.get(&pid)) {
            Ok(entry) => entry,
            Err(err) => {
                // 进程在快照后退出是正常竞态；其他错误意味着本轮索引存在真实读取缺口，
                // 后续扫描仍保留正向命中，但不能据此产生规则健康负向结论。
                if err.kind() != io::ErrorKind::NotFound {
                    complete = false;
                }
                continue;
            }
        };
        if let Some(old) = entries.get(&pid) {
            if previous_starttime == Some(entry.starttime) {
                entry.first_seen_elapsed_ms = old.first_seen_elapsed_ms;
            } else {
                entry.first_seen_elapsed_ms = now_elapsed;
            }
        }
        if entries.get(&pid).is_none_or(|old| old != &entry) {
            changed = true;
        }
        entries.insert(pid, entry);
    }

    ProcessIndexUpdate {
        candidate_pids: process_index_candidate_pids(entries, now_elapsed),
        added,
        exited,
        changed,
        complete,
    }
}

pub(super) fn process_index_candidate_pids(
    entries: &BTreeMap<i32, ProcessIndexEntry>,
    now_elapsed: u64,
) -> BTreeSet<i32> {
    entries
        .values()
        .filter(|entry| {
            now_elapsed.saturating_sub(entry.first_seen_elapsed_ms) <= PID_DISCOVERY_RETRY_MS
        })
        .map(|entry| entry.pid)
        .collect()
}

pub(super) fn process_index_mark_candidates(
    index: &mut ProcessIndex,
    pids: impl IntoIterator<Item = i32>,
    now_elapsed: u64,
) -> io::Result<()> {
    ensure_process_index_loaded(index)?;
    for pid in pids {
        if let Some(entry) = index.entries.get_mut(&pid) {
            if process_index_candidate_timestamp_should_refresh(
                entry.first_seen_elapsed_ms,
                now_elapsed,
            ) {
                entry.first_seen_elapsed_ms = now_elapsed;
                index.dirty = true;
            }
            continue;
        }
        if let Ok(entry) = read_process_index_entry(pid, now_elapsed, None) {
            index.entries.insert(pid, entry);
            index.dirty = true;
        }
    }
    Ok(())
}

pub(super) fn process_index_candidate_timestamp_should_refresh(first_seen: u64, now_elapsed: u64) -> bool {
    now_elapsed < first_seen
        || now_elapsed.saturating_sub(first_seen) > PID_DISCOVERY_RETRY_MS
}

pub(super) fn flush_process_index(index: &mut ProcessIndex, now_elapsed: u64) -> io::Result<()> {
    if !index.dirty {
        return Ok(());
    }
    if !process_index_flush_due(index.last_flush_elapsed_ms, now_elapsed) {
        // 进程频繁增删时先保留内存索引，最多延后十秒写盘；下一轮仍会重试，
        // 不影响当前规则命中，也避免频繁整份重写约 50 KB 的缓存文件。
        return Ok(());
    }
    write_process_index(&index.entries, now_elapsed)?;
    index.dirty = false;
    index.last_flush_elapsed_ms = Some(now_elapsed);
    Ok(())
}

pub(super) fn process_index_flush_due(last_flush_elapsed_ms: Option<u64>, now_elapsed: u64) -> bool {
    last_flush_elapsed_ms.is_none_or(|last| {
        now_elapsed < last || now_elapsed.saturating_sub(last) >= PID_CACHE_WRITE_MIN_MS
    })
}

pub(super) fn read_process_index_entry(
    pid: i32,
    now_elapsed: u64,
    old: Option<&ProcessIndexEntry>,
) -> io::Result<ProcessIndexEntry> {
    let proc_path = PathBuf::from(format!("/proc/{pid}"));
    let starttime = read_proc_starttime(&proc_path)?;
    let comm = truncate_process_index_name(&read_comm(&proc_path)?);
    let cmdline = truncate_process_index_name(&read_cmdline(pid)?);
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

pub(super) fn load_process_index() -> io::Result<BTreeMap<i32, ProcessIndexEntry>> {
    let content = fs::read_to_string(PROCESS_CACHE_FILE)?;
    let current_boot_id = fs::read_to_string(BOOT_ID_FILE).unwrap_or_default();
    parse_process_index_content(&content, current_boot_id.trim())
}

pub(super) fn parse_process_index_content(
    content: &str,
    current_boot_id: &str,
) -> io::Result<BTreeMap<i32, ProcessIndexEntry>> {
    let mut lines = content.lines();
    let header = lines
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "进程索引缺少头部"))?;
    let mut header_fields = header.split('\t');
    if header_fields.next() != Some(PROCESS_INDEX_MAGIC) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "进程索引版本不兼容",
        ));
    }
    let stored_boot_id = header_fields.next().unwrap_or_default();
    if stored_boot_id.is_empty()
        || current_boot_id.is_empty()
        || stored_boot_id != current_boot_id
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "进程索引属于其他启动周期",
        ));
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

pub(super) fn write_process_index(
    entries: &BTreeMap<i32, ProcessIndexEntry>,
    now_elapsed: u64,
) -> io::Result<()> {
    let boot_id = fs::read_to_string(BOOT_ID_FILE).unwrap_or_default();
    let boot_id = boot_id.trim();
    if boot_id.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "无法读取 boot_id",
        ));
    }
    fs::create_dir_all(STATE_DIR)?;
    let output = serialize_process_index(entries, boot_id, now_elapsed);
    let temporary = format!("{PROCESS_CACHE_FILE}.{}.tmp", std::process::id());
    fs::write(&temporary, output)?;
    fs::rename(&temporary, PROCESS_CACHE_FILE).inspect_err(|_| {
        let _ = fs::remove_file(&temporary);
    })
}

pub(super) fn serialize_process_index(
    entries: &BTreeMap<i32, ProcessIndexEntry>,
    boot_id: &str,
    now_elapsed: u64,
) -> String {
    let mut output = format!("{PROCESS_INDEX_MAGIC}\t{boot_id}\t{now_elapsed}\n");
    for entry in entries.values() {
        output.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\n",
            entry.pid,
            entry.starttime,
            entry.first_seen_elapsed_ms,
            encode_process_index_hex(&truncate_process_index_name(&entry.comm)),
            encode_process_index_hex(&truncate_process_index_name(&entry.cmdline))
        ));
    }
    output
}

pub(super) fn load_process_index_for_cli() -> io::Result<ProcessIndex> {
    let mut index = ProcessIndex::default();
    ensure_process_index_loaded(&mut index)?;
    if index.dirty {
        let now_elapsed = elapsed_realtime_ms();
        let _ = refresh_process_index(&mut index, now_elapsed, true)?;
        flush_process_index(&mut index, now_elapsed)?;
    }
    Ok(index)
}

pub(super) fn process_index_find_pids(name: &str) -> io::Result<Vec<i32>> {
    let mut index = load_process_index_for_cli()?;
    // --find-pid 必须返回同名进程的完整集合。缓存命中一个旧助手进程不能提前结束，
    // 否则同名的第二个/分身进程会被静默漏掉；CLI 是按需调用，全量刷新不影响常驻循环。
    let now_elapsed = elapsed_realtime_ms();
    let _ = refresh_process_index(&mut index, now_elapsed, true)?;
    flush_process_index(&mut index, now_elapsed)?;
    let mut pids = index
        .entries
        .values()
        .filter(|entry| {
            process_index_name_matches(entry, name)
                && process_index_current_name_matches(entry, name)
        })
        .map(|entry| entry.pid)
        .collect::<Vec<_>>();
    pids.sort_unstable();
    pids.dedup();
    Ok(pids)
}

pub(super) fn process_index_verified_package_pids(index: &ProcessIndex, pkg: &str) -> BTreeSet<i32> {
    process_index_verified_entries(&index.entries, pkg)
}

fn process_index_verified_entries(entries: &BTreeMap<i32, ProcessIndexEntry>, pkg: &str) -> BTreeSet<i32> {
    verify_package_entries(entries, pkg, |pid| {
        let proc_path = PathBuf::from(format!("/proc/{pid}"));
        let before = read_proc_starttime(&proc_path)?;
        let cmdline = read_cmdline(pid)?;
        let after = read_proc_starttime(&proc_path)?;
        Ok((before, cmdline, after))
    })
}

fn verify_package_entries(
    entries: &BTreeMap<i32, ProcessIndexEntry>,
    pkg: &str,
    mut identity: impl FnMut(i32) -> io::Result<(u64, String, u64)>,
) -> BTreeSet<i32> {
    let mut pids = BTreeSet::new();
    for entry in entries.values() {
        if !process_belongs_to_uid_package(&entry.cmdline, pkg) {
            continue;
        }
        let Ok((before, current_cmdline, after)) = identity(entry.pid) else {
            continue;
        };
        if before != entry.starttime || after != entry.starttime {
            continue;
        }
        if process_belongs_to_uid_package(&current_cmdline, pkg) {
            pids.insert(entry.pid);
        }
    }
    pids
}

// 进程内工作线程始终使用最新发布的索引，即使尚未到写盘时间。
// 独立 CLI 或助手进程没有索引发布者，仍保留磁盘缓存兜底。
// 已初始化的空快照就是确切结果，不能当作缓存未命中。
#[cfg_attr(not(any(target_os = "android", target_os = "linux")), allow(dead_code))]
pub(super) fn process_index_find_package_pids(pkg: &str) -> io::Result<BTreeSet<i32>> {
    if let Some(entries) = shared_process_index().snapshot() {
        return Ok(process_index_verified_entries(&entries, pkg));
    }
    let index = load_process_index_for_cli()?;
    Ok(process_index_verified_package_pids(&index, pkg))
}

pub(super) fn process_index_cached_package_pids(
    index: &ProcessIndex,
    packages: &BTreeSet<String>,
) -> BTreeSet<i32> {
    index
        .entries
        .values()
        .filter(|entry| process_index_entry_matches_packages(entry, packages))
        .map(|entry| entry.pid)
        .collect()
}

pub(super) fn process_index_entry_matches_packages(
    entry: &ProcessIndexEntry,
    packages: &BTreeSet<String>,
) -> bool {
    let base = entry
        .cmdline
        .split_once(':')
        .map_or(entry.cmdline.as_str(), |(pkg, _)| pkg);
    packages.contains(base)
}

pub(super) fn process_index_find_names(names: &[String]) -> io::Result<Vec<String>> {
    let mut index = load_process_index_for_cli()?;
    let mut matched = vec![false; names.len()];
    for (position, name) in names.iter().enumerate() {
        matched[position] = index.entries.values().any(|entry| {
            process_index_name_matches(entry, name)
                && process_index_current_name_matches(entry, name)
        });
    }

    if matched.iter().any(|value| !value) {
        let now_elapsed = elapsed_realtime_ms();
        for pid in enumerate_proc_pids()? {
            let Ok(entry) = read_process_index_entry(pid, now_elapsed, None) else {
                continue;
            };
            for (position, name) in names.iter().enumerate() {
                if !matched[position]
                    && process_index_name_matches(&entry, name)
                    && process_index_current_name_matches(&entry, name)
                {
                    matched[position] = true;
                }
            }
            if index.entries.get(&pid) != Some(&entry) {
                index.entries.insert(pid, entry);
                index.dirty = true;
            }
            if matched.iter().all(|value| *value) {
                break;
            }
        }
        flush_process_index(&mut index, now_elapsed)?;
    }

    Ok(names
        .iter()
        .zip(matched)
        .filter(|(_, matched)| *matched)
        .map(|(name, _)| name.clone())
        .collect())
}

pub(super) fn process_index_print_pids(name: &str) -> io::Result<()> {
    let pids = process_index_find_pids(name)?;
    for pid in &pids {
        println!("{pid}");
    }
    // 完整扫描后没有匹配进程也是成功结果。只有扫描失败才返回错误，
    // 否则启动助手时会把正常的空集合误当成故障，再执行高开销的 shell 扫描。
    Ok(())
}

pub(super) fn process_index_print_names(names: &[String]) -> io::Result<()> {
    let found = process_index_find_names(names)?;
    for name in &found {
        println!("{name}");
    }
    Ok(())
}

pub(super) fn process_index_name_matches(entry: &ProcessIndexEntry, name: &str) -> bool {
    entry.comm == name
        || entry.cmdline == name
        || Path::new(&entry.cmdline)
            .file_name()
            .and_then(OsStr::to_str)
            .is_some_and(|base| base == name)
}

pub(super) fn process_index_current_name_matches(entry: &ProcessIndexEntry, name: &str) -> bool {
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

pub(super) fn encode_process_index_hex(value: &str) -> String {
    if value.is_empty() {
        return "-".to_string();
    }
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(value.len() * 2);
    for byte in value.as_bytes() {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

pub(super) fn truncate_process_index_name(value: &str) -> String {
    if value.len() <= PROCESS_INDEX_NAME_MAX_BYTES {
        return value.to_string();
    }
    let mut end = PROCESS_INDEX_NAME_MAX_BYTES;
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_string()
}

pub(super) fn decode_process_index_hex(value: &str) -> Option<String> {
    if value == "-" {
        return Some(String::new());
    }
    if !value.len().is_multiple_of(2) {
        return None;
    }
    let mut bytes = Vec::with_capacity(value.len() / 2);
    for pair in value.as_bytes().chunks_exact(2) {
        let text = std::str::from_utf8(pair).ok()?;
        bytes.push(u8::from_str_radix(text, 16).ok()?);
    }
    String::from_utf8(bytes).ok()
}
