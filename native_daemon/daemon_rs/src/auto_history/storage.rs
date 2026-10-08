//! 带版本的追加式记录。已完成分段采用无损 gzip 压缩；
//! 活跃 .tmp 保持纯文本，便于异常关停后恢复完整数据行。
use super::{CoreEvent, ThreadSample};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{self, BufWriter, Read, Write},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};

pub(super) const MAX_BYTES: u64 = 8 * 1024 * 1024;
pub(super) const MAX_DURATION: Duration = Duration::from_secs(30 * 60);
const MAX_ROWS: u64 = 120_000;
// 与 CoreTimelineParser.MAX_EVENTS 一致，确保每个有效分段都能完整读取。
const MAX_CORE_EVENTS: u64 = 20_000;
const TOTAL_BYTES: u64 = 64 * 1024 * 1024;
const MAX_RECORDINGS: usize = 7;
const HEADER: &str = "QIXIA_AUTO_HISTORY\t1\n";
#[path = "storage_compression.rs"]
mod compression;
use compression::{completed_path, read_prefix};
#[cfg(test)]
use compression::read_text;
fn has_history_header(bytes: &[u8]) -> bool {
    bytes.starts_with(HEADER.as_bytes())
}
static OPEN_FILES: OnceLock<Mutex<BTreeSet<PathBuf>>> = OnceLock::new();
struct OpenFile(PathBuf);
impl Drop for OpenFile {
    fn drop(&mut self) {
        OPEN_FILES
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.0);
    }
}
fn is_open(path: &Path) -> bool {
    OPEN_FILES
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .contains(path)
}

pub(super) struct Recording {
    pub package: String,
    path: PathBuf,
    output: BufWriter<File>,
    started: Instant,
    start_ms: u64,
    last_ms: u64,
    last_flush: Instant,
    bytes: u64,
    rows: u64,
    samples: u64,
    active: BTreeSet<(i32, i32, u64)>,
    device_fields: Vec<(String, String)>,
    observed: BTreeMap<(i32, i32, u64), (String, String, Option<u64>, Option<u32>)>,
    core_dropped: u64,
    core_events: u64,
    core_incomplete: bool,
    _open: OpenFile,
}

impl Recording {
    #[cfg(test)]
    pub fn new(directory: &Path, package: &str, start_ms: u64) -> io::Result<Self> {
        Self::create(directory, package, start_ms, true)
    }
    fn create(
        directory: &Path,
        package: &str,
        start_ms: u64,
        prune_before_start: bool,
    ) -> io::Result<Self> {
        fs::create_dir_all(directory)?;
        // 记录器是唯一写入方，调用前上一分段已关闭。
        // 除了守护启动时，也在此重试恢复，避免反复同步或重命名失败
        // 不断积累未计入额度的 .tmp 文件。
        recover(directory)?;
        if prune_before_start {
            prune(directory, true)?;
        } else {
            capture::check_staging_budget(directory)?;
        }
        let mut index = 0u32;
        let (path, file) = loop {
            let name = format!("auto_{start_ms}_{}_{index}.tmp", std::process::id());
            let path = directory.join(name);
            if path.with_extension("log").exists() || completed_path(&path).exists() {
                index += 1;
                continue;
            }
            match OpenOptions::new().create_new(true).write(true).open(&path) {
                Ok(file) => break (path, file),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists && index < 1000 => {
                    index += 1
                }
                Err(error) => return Err(error),
            }
        };
        OPEN_FILES
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(path.clone());
        let open = OpenFile(path.clone());
        let header = format!(
            "{HEADER}source\tauto\npackage\t{package}\nstart_ms\t{start_ms}\ncore_events\t1\n"
        );
        let mut output = BufWriter::with_capacity(64 * 1024, file);
        output.write_all(header.as_bytes())?;
        Ok(Self {
            package: package.into(),
            path,
            output,
            started: Instant::now(),
            start_ms,
            last_ms: start_ms,
            last_flush: Instant::now(),
            bytes: header.len() as u64,
            rows: 0,
            samples: 0,
            active: BTreeSet::new(),
            device_fields: Vec::new(),
            observed: BTreeMap::new(),
            core_dropped: 0,
            core_events: 0,
            core_incomplete: false,
            _open: open,
        })
    }

    pub fn elapsed_ms(&self) -> u64 {
        self.started.elapsed().as_millis() as u64
    }
    #[cfg(test)]
    pub fn start_ms(&self) -> u64 {
        self.start_ms
    }
    #[cfg(test)]
    pub fn rotate(&mut self, directory: &Path, start_ms: u64) -> io::Result<()> {
        let mut next = Recording::new(directory, &self.package, start_ms)?;
        next.device(&self.device_fields)?;
        let old = std::mem::replace(self, next);
        if let Err(error) = old.finish(start_ms) {
            self.core_loss(0, true);
            return Err(error);
        }
        Ok(())
    }
    pub fn device(&mut self, fields: &[(String, String)]) -> io::Result<()> {
        for (key, value) in fields.iter().take(5) {
            if !matches!(
                key.as_str(),
                "platform" | "model" | "os" | "resolution" | "refresh"
            ) {
                continue;
            }
            let value: String = value
                .chars()
                .map(|c| if c.is_control() { ' ' } else { c })
                .take(160)
                .collect();
            let row = format!("D\t{key}\t{value}\n");
            self.output.write_all(row.as_bytes())?;
            self.bytes += row.len() as u64;
            self.device_fields.retain(|(existing, _)| existing != key);
            self.device_fields.push((key.clone(), value));
        }
        Ok(())
    }
    pub fn accepts(&self, timestamp_ms: u64) -> bool {
        timestamp_ms >= self.start_ms
    }
    pub fn full(&self) -> bool {
        self.bytes >= MAX_BYTES - 128 * 1024
            || self.rows >= MAX_ROWS
            || self.core_events >= MAX_CORE_EVENTS
            || self.started.elapsed() >= MAX_DURATION
    }
    fn row(&mut self, timestamp_ms: u64, row: &str) -> io::Result<bool> {
        if !self.accepts(timestamp_ms) {
            return Ok(false);
        }
        self.append_row(timestamp_ms, row)
    }
    fn append_row(&mut self, timestamp_ms: u64, row: &str) -> io::Result<bool> {
        // 单轮采样有界，但结束时仍可能超出软阈值。
        if self.bytes.saturating_add(row.len() as u64) > MAX_BYTES - 1024 {
            return Ok(false);
        }
        self.output.write_all(row.as_bytes())?;
        self.bytes += row.len() as u64;
        self.rows += 1;
        self.last_ms = self.last_ms.max(timestamp_ms);
        Ok(true)
    }
    pub fn fps(&mut self, timestamp_ms: u64, fps: f64, frame_max_ms: f64) -> io::Result<()> {
        if fps.is_finite()
            && (0.0..=1000.0).contains(&fps)
            && frame_max_ms.is_finite()
            && frame_max_ms >= 0.0
        {
            self.row(
                timestamp_ms,
                &format!("F\t{timestamp_ms}\t{fps:.3}\t{frame_max_ms:.3}\n"),
            )?;
        }
        Ok(())
    }
    pub fn metrics(
        &mut self,
        timestamp_ms: u64,
        sample: &qixia_history_probe::Sample,
    ) -> io::Result<()> {
        if sample.values.is_empty() {
            return Ok(());
        }
        let charging = match sample.charging {
            Some(true) => "1",
            Some(false) => "0",
            None => "?",
        };
        let mut row = format!("M\t{timestamp_ms}\t{charging}");
        for (key, value) in &sample.values {
            if value.is_finite() {
                row.push_str(&format!("\t{key}={value:.6}"));
            }
        }
        row.push('\n');
        self.row(timestamp_ms, &row).map(|_| ())
    }
    pub fn threads(&mut self, timestamp_ms: u64, threads: &[ThreadSample]) -> io::Result<()> {
        if !self.accepts(timestamp_ms) {
            return Ok(());
        }
        self.samples += 1;
        for thread in threads.iter().take(4096) {
            if !include_thread(thread, &mut self.active) {
                continue;
            }
            self.row(timestamp_ms, &thread_row(timestamp_ms, thread))?;
        }
        Ok(())
    }
    fn threads_fit(&self, timestamp_ms: u64, threads: &[ThreadSample]) -> bool {
        let mut active = self.active.clone();
        let mut bytes = self.bytes;
        let mut rows = self.rows;
        for thread in threads.iter().take(4096) {
            if valid_thread(thread) && thread.percent > 0.0
                && !active.contains(&(thread.pid, thread.tid, thread.start))
                && active.len() >= 4096
            {
                return false;
            }
            if include_thread(thread, &mut active) {
                bytes = bytes.saturating_add(thread_row(timestamp_ms, thread).len() as u64);
                rows = rows.saturating_add(1);
                if bytes > MAX_BYTES - 1024 || rows > MAX_ROWS {
                    return false;
                }
            }
        }
        true
    }
    pub fn changes(
        &mut self,
        timestamp_ms: u64,
        changes: &[((i32, i32, u64), u64)],
    ) -> io::Result<()> {
        for ((pid, tid, start), mask) in changes.iter().take(128) {
            let cpus = if *mask == 0 {
                "restore".into()
            } else {
                (0..64)
                    .filter(|id| mask & (1u64 << id) != 0)
                    .map(|id| id.to_string())
                    .collect::<Vec<_>>()
                    .join(",")
            };
            self.row(
                timestamp_ms,
                &format!("A\t{timestamp_ms}\t{pid}\t{tid}\t{start}\t{cpus}\n"),
            )?;
        }
        Ok(())
    }
    pub fn core_loss(&mut self, dropped: u64, incomplete: bool) {
        self.core_dropped = self.core_dropped.saturating_add(dropped);
        self.core_incomplete |= incomplete || dropped > 0;
    }
    pub fn core_event(&mut self, event: &CoreEvent) -> io::Result<()> {
        let controller_error =
            event.kind == "error" && event.pid == 0 && event.tid == 0 && event.start == 0;
        if (!controller_error && (event.pid <= 0 || event.tid <= 0 || event.start == 0))
            || !matches!(
                event.kind.as_str(),
                "assign" | "release" | "observe" | "external" | "exit" | "error"
            )
            || !matches!(event.source.as_str(), "qixia" | "system" | "unknown")
        {
            self.core_loss(1, true);
            return Ok(());
        }
        let id = (event.pid, event.tid, event.start);
        let observation = (
            event.name.clone(),
            event.source.clone(),
            event.after,
            event.running_cpu,
        );
        if event.kind == "observe" && self.observed.get(&id) == Some(&observation) {
            return Ok(());
        }
        // 运行时会在每条 E 记录前检查 full()，同一批次内也不例外。
        // 这里仍保留硬上限，确保漏掉换段时能够显式报错，
        // 而不是生成被 Android 读取端丢弃有效尾部的文件。
        if self.core_events >= MAX_CORE_EVENTS {
            self.core_loss(1, true);
            return Ok(());
        }
        let name = hex(event.name.as_bytes());
        let before = cpu_list(event.before);
        let after = cpu_list(event.after);
        let cpu = event
            .running_cpu
            .map_or_else(|| "-".into(), |c| c.to_string());
        let average = event
            .average
            .filter(|v| v.is_finite() && *v >= 0.0)
            .map_or_else(|| "-".into(), |v| format!("{v:.4}"));
        let reason: String = event
            .reason
            .chars()
            .take(80)
            .map(|c| {
                if c.is_ascii_alphanumeric() || "_-.:".contains(c) {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        let reason = if reason.is_empty() {
            "unknown"
        } else {
            &reason
        };
        let row = format!(
            "E\t{}\t{}\t{}\t{}\t{name}\t{}\t{}\t{before}\t{after}\t{cpu}\t{average}\t{reason}\n",
            event.timestamp_ms, event.pid, event.tid, event.start, event.kind, event.source
        );
        // 队列负责验证采集会话归属。与采样行不同，操作可能在换段后才到达；
        // 即使真实时间早于新段起点几毫秒，
        // 也要保留该时间戳。
        match self.append_row(event.timestamp_ms, &row) {
            Ok(true) => {
                self.core_events += 1;
                if event.kind == "observe" {
                    if self.observed.len() < 4096 || self.observed.contains_key(&id) {
                        self.observed.insert(id, observation);
                    }
                } else {
                    self.observed.remove(&id);
                }
                Ok(())
            }
            Ok(false) => {
                self.core_loss(1, true);
                Ok(())
            }
            Err(error) => {
                self.core_loss(1, true);
                Err(error)
            }
        }
    }
    pub fn flush_if_due(&mut self) -> io::Result<()> {
        if self.last_flush.elapsed() >= Duration::from_secs(10) {
            self.output.flush()?;
            self.last_flush = Instant::now();
        }
        Ok(())
    }
    fn seal(
        mut self,
        end_ms: u64,
        duration_ms: Option<u64>,
    ) -> io::Result<Option<(PathBuf, OpenFile)>> {
        if self.rows == 0 && self.core_dropped == 0 && !self.core_incomplete {
            let path = self.path.clone();
            drop(self.output);
            fs::remove_file(path)?;
            return Ok(None);
        }
        let elapsed = duration_ms.unwrap_or_else(|| self.elapsed_ms()).max(1);
        writeln!(
            self.output,
            "end_ms\t{}\nduration_ms\t{elapsed}\nthread_count\t{}\nsamples\t{}",
            end_ms
                .max(self.last_ms)
                .max(self.start_ms.saturating_add(1)),
            self.active.len(),
            self.samples
        )?;
        writeln!(self.output, "core_events_dropped\t{}", self.core_dropped)?;
        if self.core_incomplete {
            writeln!(self.output, "core_events_incomplete\t1")?;
        }
        self.output.flush()?;
        self.output.get_ref().sync_data()?;
        drop(self.output);
        Ok(Some((self.path, self._open)))
    }
    #[cfg(test)]
    pub fn finish(self, end_ms: u64) -> io::Result<()> {
        if let Some((path, open)) = self.seal(end_ms, None)? {
            compression::publish(&path)?;
            drop(open);
            prune(path.parent().unwrap_or_else(|| Path::new(".")), false)?;
        }
        Ok(())
    }
}

#[path = "storage_capture.rs"]
mod capture;
pub(super) use capture::Capture;

fn cpu_list(mask: Option<u64>) -> String {
    match mask.filter(|mask| *mask != 0) {
        Some(mask) => (0..64)
            .filter(|id| mask & (1u64 << id) != 0)
            .map(|id| id.to_string())
            .collect::<Vec<_>>()
            .join(","),
        None => "-".into(),
    }
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes.iter().take(128) {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

fn include_thread(thread: &ThreadSample, active: &mut BTreeSet<(i32, i32, u64)>) -> bool {
    if !valid_thread(thread) {
        return false;
    }
    let id = (thread.pid, thread.tid, thread.start);
    if thread.percent > 0.0 && active.len() < 4096 {
        active.insert(id);
    }
    active.contains(&id)
}

fn valid_thread(thread: &ThreadSample) -> bool {
    thread.pid > 0 && thread.tid > 0 && thread.start > 0
        && thread.percent.is_finite() && (0.0..=105.0).contains(&thread.percent)
}

fn thread_row(timestamp_ms: u64, thread: &ThreadSample) -> String {
    // 名称按 UTF-8 十六进制编码，避免任意 comm 字节插入 TSV 字段。
    let name = hex(thread.name.as_bytes());
    format!("T\t{timestamp_ms}\t{}\t{}\t{}\t{name}\t{:.4}\n",
        thread.pid, thread.tid, thread.start, thread.percent)
}

fn owned_file(path: &Path, extension: &str) -> bool {
    path.file_name().and_then(|s| s.to_str()).and_then(|name| {
        if extension == "log" {
            name.strip_suffix(".log.gz").or_else(|| name.strip_suffix(".log"))
        } else if extension == "gz.part" {
            name.strip_suffix(".gz.part")
        } else if extension == "tmp" {
            name.strip_suffix(".tmp")
        } else { None }
    }).is_some_and(|s| {
            s.starts_with("auto_") && s[5..].bytes().all(|c| c.is_ascii_digit() || c == b'_')
        })
        && fs::symlink_metadata(path).is_ok_and(|m| m.is_file() && !m.file_type().is_symlink())
}

pub(super) fn recover(directory: &Path) -> io::Result<()> {
    compression::remove_abandoned_parts(directory)?;
    let capture_usage = capture::recovery_usage(directory)?;
    let mut admitted_recovery = false;
    let entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e),
    };
    for entry in entries.take(400).flatten() {
        let path = entry.path();
        if !owned_file(&path, "tmp") || is_open(&path) {
            continue;
        }
        // 原子发布后、删除源文件前发生崩溃时，不能追加新的恢复行，
        // 也不能用两个名称发布同一分段。
        if compression::remove_published_source(&path)? { continue; }
        let mut bytes = Vec::new();
        File::open(&path)?
            .take(MAX_BYTES + 1)
            .read_to_end(&mut bytes)?;
        if bytes.len() as u64 > MAX_BYTES || !has_history_header(&bytes) {
            fs::remove_file(&path)?;
            continue;
        }
        let complete = bytes.iter().rposition(|b| *b == b'\n').map_or(0, |i| i + 1);
        let truncated_event = bytes
            .get(complete..)
            .is_some_and(|tail| tail.starts_with(b"E\t"));
        bytes.truncate(complete);
        let text = String::from_utf8_lossy(&bytes);
        if !capture::recoverable(&text, &capture_usage) {
            fs::remove_file(&path)?;
            continue;
        }
        let start = text.lines().find_map(|l| {
            l.strip_prefix("start_ms\t")
                .and_then(|s| s.parse::<u64>().ok())
        });
        let last = text
            .lines()
            .filter(|l| matches!(l.as_bytes().first(), Some(b'M' | b'F' | b'T' | b'A' | b'E')))
            .filter_map(|l| l.split('\t').nth(1)?.parse::<u64>().ok())
            .max();
        let (Some(start), Some(last)) = (start, last) else {
            fs::remove_file(&path)?;
            continue;
        };
        // 换段时可能追加此前已采集的操作，其真实时间早于该段起点。
        // 崩溃后应保留仅含 E 记录的分段，
        // 并采用与 Android 解析器相同的有界回溯范围。
        let late_operation = text.lines().any(|line| line == "core_events\t1")
            && text
                .lines()
                .filter(|line| line.starts_with("E\t"))
                .filter_map(|line| line.split('\t').nth(1)?.parse::<u64>().ok())
                .any(|time| {
                    time >= start.saturating_sub(MAX_DURATION.as_millis() as u64).max(1)
                        && time < start
                });
        if last < start && !late_operation {
            fs::remove_file(&path)?;
            continue;
        }
        let mut file = OpenOptions::new().write(true).open(&path)?;
        file.set_len(complete as u64)?;
        use std::io::Seek;
        file.seek(std::io::SeekFrom::End(0))?;
        if let Some(usage) = capture::proven_usage(&text, &capture_usage) {
            // 每个已发布分段都携带整次采集的证据，
            // 包括入选时间边界之前已换段的短分段。
            writeln!(file, "foreground_ms\t{usage}")?;
        }
        if !text.lines().any(|l| l.starts_with("end_ms\t")) {
            writeln!(
                file,
                "end_ms\t{}\nduration_ms\t{}\nrecovered\t1",
                last.max(start.saturating_add(1)),
                last.saturating_sub(start).max(1)
            )?;
        }
        // 恢复的文件不能声称排队中或尚未刷盘的操作完整无缺。
        // 明显截断的 E 行至少计为一条已知丢失事件。
        let dropped = text
            .lines()
            .filter_map(|l| l.strip_prefix("core_events_dropped\t")?.parse::<u64>().ok())
            .max()
            .unwrap_or(0);
        writeln!(
            file,
            "core_events_dropped\t{}\ncore_events_incomplete\t1",
            dropped.saturating_add(u64::from(truncated_event))
        )?;
        file.sync_data()?;
        drop(file);
        let completed = path.with_extension("log");
        if completed.exists() {
            fs::remove_file(path)?;
        } else {
            compression::publish(&path)?;
            admitted_recovery |= capture::proven_usage(&text, &capture_usage).is_some();
        }
    }
    if admitted_recovery {
        prune(directory, false)?;
    }
    Ok(())
}

fn prune(directory: &Path, reserve_new_file: bool) -> io::Result<()> {
    #[derive(Default)]
    struct Group {
        files: Vec<PathBuf>,
        bytes: u64,
        latest: u64,
        protected: bool,
        recorded: bool,
    }
    let mut groups = BTreeMap::<String, Group>::new();
    let mut entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    for entry in entries.by_ref().take(1000).flatten() {
        let path = entry.path();
        // 有界恢复流程尚未处理到的已关闭、不完整文件也要计入额度。
        // 清理失败时停止新采集，不能继续扩大占用。
        if !owned_file(&path, "log") && !owned_file(&path, "tmp") {
            continue;
        }
        let size = match entry.metadata() {
            Ok(metadata) => metadata.len(),
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        let stamp = path
            .file_stem()
            .and_then(|name| name.to_str())
            .and_then(|name| name.strip_prefix("auto_"))
            .and_then(|name| name.split('_').next())
            .and_then(|stamp| stamp.parse::<u64>().ok())
            .unwrap_or(0);
        let key = match capture::retention_key(&path) {
            Ok(key) => key,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        let open = is_open(&path);
        let group = groups.entry(key).or_default();
        // 预留写入方一个完整有界分段的空间，防止多个并行关闭的采集
        // 在检查后使占用超过全局额度。
        group.bytes = group
            .bytes
            .saturating_add(if open { size.max(MAX_BYTES) } else { size });
        group.latest = group.latest.max(stamp);
        group.protected |= open;
        group.recorded |= owned_file(&path, "log") || !open;
        group.files.push(path);
    }
    if entries.next().is_some() {
        return Err(io::Error::other(
            "auto history directory exceeds entry limit",
        ));
    }
    let mut groups: Vec<_> = groups.into_iter().collect();
    groups.sort_by(|a, b| a.1.latest.cmp(&b.1.latest).then_with(|| a.0.cmp(&b.0)));
    let mut total: u64 = groups.iter().map(|(_, group)| group.bytes).sum();
    let mut count = groups.iter().filter(|(_, group)| group.recorded).count();
    let reserved_bytes = if reserve_new_file { MAX_BYTES } else { 0 };
    for (_, group) in groups {
        // 一个活跃分段会保护同次前台使用的所有分段，
        // 不能悄悄只保留长记录的尾部。
        if group.protected {
            continue;
        }
        if total.saturating_add(reserved_bytes) <= TOTAL_BYTES && count <= MAX_RECORDINGS {
            continue;
        }
        for path in group.files {
            match fs::remove_file(path) {
                Ok(()) => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error),
            }
        }
        total = total.saturating_sub(group.bytes);
        count -= usize::from(group.recorded);
    }
    if total.saturating_add(reserved_bytes) > TOTAL_BYTES || count > MAX_RECORDINGS {
        // 单次活跃使用也可能耗尽额度。调用方此时应写入不完整标记并结束采集，
        // 而不是淘汰它较早的分段。
        return Err(io::Error::other(
            "auto history active capture storage budget exhausted",
        ));
    }
    Ok(())
}

#[cfg(test)]
#[path = "storage_core_tests.rs"]
mod core_tests;

#[cfg(test)]
mod tests {
    use super::*;
    fn directory(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "qixia-auto-history-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }
    #[test]
    fn inactive_threads_are_not_saved_but_low_load_and_later_zero_are() {
        let dir = directory("active");
        let mut run = Recording::new(&dir, "com.game", 1000).unwrap();
        let mut t = ThreadSample {
            pid: 1,
            tid: 2,
            start: 3,
            name: "worker\t汉".into(),
            percent: 0.0,
        };
        run.threads(1100, &[t.clone()]).unwrap();
        t.percent = 0.1;
        run.threads(2200, &[t.clone()]).unwrap();
        t.percent = 0.0;
        run.threads(3300, &[t]).unwrap();
        let path = run.path.with_extension("log.gz");
        assert!(!path.exists());
        run.finish(4000).unwrap();
        let text = read_text(&path).unwrap();
        assert_eq!(text.lines().filter(|l| l.starts_with("T\t")).count(), 2);
        assert!(text.contains("0.1000"));
        assert!(text.contains("thread_count\t1\n"));
        assert!(text.contains(&hex("worker\t汉".as_bytes())));
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn recovery_drops_partial_rows_and_never_imports_empty_runs() {
        let dir = directory("recovery");
        let good = dir.join("auto_1000_1_0.tmp");
        fs::write(&good, format!("{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t1000\nF\t2000\t60\t17\nT\t2500\tpartial")).unwrap();
        fs::write(dir.join("auto_1000_1_1.tmp"), HEADER).unwrap();
        recover(&dir).unwrap();
        let text = read_text(good.with_extension("log.gz")).unwrap();
        assert!(text.contains("end_ms\t2000\nduration_ms\t1000\nrecovered\t1"));
        assert!(!text.contains("partial"));
        assert!(!dir.join("auto_1000_1_1.tmp").exists());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn retains_raw_metrics_and_rejects_nonfinite_fps() {
        let dir = directory("metrics");
        let mut run = Recording::new(&dir, "com.game", 1000).unwrap();
        run.fps(2000, f64::NAN, 0.0).unwrap();
        let mut sample = qixia_history_probe::Sample::default();
        sample.charging = Some(false);
        sample.values.insert("battery_ma".into(), 1500.0);
        run.metrics(2000, &sample).unwrap();
        let path = run.path.with_extension("log.gz");
        run.finish(3000).unwrap();
        let text = read_text(path).unwrap();
        assert!(!text.contains("F\t"));
        assert!(text.contains("M\t2000\t0\tbattery_ma=1500.000000"));
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn segment_limits_and_retention_keep_recording_bounded() {
        let dir = directory("retention");
        for index in 0..35u64 {
            let mut run = Recording::new(&dir, "com.game", 1000 + index).unwrap();
            run.fps(2000 + index, 60.0, 16.6).unwrap();
            if index == 0 {
                run.bytes = MAX_BYTES - 128 * 1024;
                assert!(run.full());
                run.bytes = 100;
                run.started = Instant::now() - MAX_DURATION;
                assert!(run.full());
            }
            run.finish(3000 + index).unwrap();
        }
        assert_eq!(fs::read_dir(&dir).unwrap().count(), MAX_RECORDINGS);
        let mut run = Recording::new(&dir, "com.other", 9000).unwrap();
        run.changes(9100, &[((1, 2, 3), 0)]).unwrap();
        run.device(&[("model".into(), "sample\tmodel\n".into())])
            .unwrap();
        let path = run.path.with_extension("log.gz");
        run.finish(9000).unwrap();
        let text = read_text(path).unwrap();
        assert!(text.contains("A\t9100\t1\t2\t3\trestore\n"));
        assert!(text.contains("D\tmodel\tsample model \n"));
        assert!(!text.contains("duration_ms\t0\n"));
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn retry_recovers_closed_incomplete_runs_and_prune_budgets_them() {
        let dir = directory("retry");
        for index in 0..35 {
            fs::write(
                dir.join(format!("auto_1000_1_{index}.tmp")),
                format!(
                    "{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t1000\nF\t2000\t60\t17\n"
                ),
            )
            .unwrap();
        }
        // 超出有界恢复轮次的文件同样占用额度。
        prune(&dir, true).unwrap();
        assert_eq!(fs::read_dir(&dir).unwrap().count(), MAX_RECORDINGS);
        let run = Recording::new(&dir, "com.game", 3000).unwrap();
        assert_eq!(
            fs::read_dir(&dir)
                .unwrap()
                .filter_map(Result::ok)
                .filter(|e| owned_file(&e.path(), "log"))
                .count(),
            MAX_RECORDINGS
        );
        run.finish(4000).unwrap(); // 空重试不能生成可导入的记录。
        assert!(fs::read_dir(&dir)
            .unwrap()
            .filter_map(Result::ok)
            .all(|e| owned_file(&e.path(), "log")));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn retention_is_global_keeps_active_recording_and_waits_for_new_completion() {
        let dir = directory("global-retention");
        // 测试故意让数字时间戳跨越位数边界。
        for stamp in 6..=14 {
            let mut run = Recording::new(
                &dir,
                if stamp % 2 == 0 { "com.one" } else { "com.two" },
                stamp,
            )
            .unwrap();
            run.fps(stamp + 1, 60.0, 16.6).unwrap();
            run.finish(stamp + 2).unwrap();
        }
        let completed = || {
            fs::read_dir(&dir)
                .unwrap()
                .filter_map(Result::ok)
                .filter(|entry| owned_file(&entry.path(), "log"))
                .map(|entry| entry.path())
                .collect::<Vec<_>>()
        };
        assert_eq!(completed().len(), 7);
        assert!(completed().iter().all(|path| {
            path.file_stem()
                .unwrap()
                .to_str()
                .unwrap()
                .split('_')
                .nth(1)
                .unwrap()
                .parse::<u64>()
                .unwrap()
                >= 8
        }));
        let active = Recording::new(&dir, "com.three", 15).unwrap();
        let active_path = active.path.clone();
        prune(&dir, false).unwrap();
        assert!(active_path.exists());
        assert_eq!(completed().len(), 7);
        active.finish(16).unwrap(); // 空启动不能淘汰现有的完整记录。
        assert_eq!(completed().len(), 7);
        fs::remove_dir_all(dir).unwrap();
    }
}
