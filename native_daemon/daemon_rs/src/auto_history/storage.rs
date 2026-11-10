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
/// 线程身份上限：已确认的线程与正在观察的候选共用这个额度。
const MAX_IDENTITIES: usize = 4096;

/// 线程进入历史记录的确认门槛。
///
/// 采样是轮换的，线程越多单个线程被采到的间隔越长，因此这里用“它自己被采到的
/// 次数”做分母（等价于占空比），而不是整段轮数。运行期间只活跃几下的线程
/// （线程池临时线程、GC、加载解压）不会进历史；长时间反复有负载的线程才写行。
#[derive(Clone, Copy)]
pub(super) struct GatePolicy {
    /// 有负载的采样次数下限（约 3 秒）。
    pub min_samples: u32,
    /// 有负载采样占比下限（百分比）。
    pub min_percent: u32,
    /// 存在期下限（毫秒）。
    pub min_span_ms: u64,
}

/// 生产口径：6 次有负载采样、占比 20%、存在期 30 秒。
pub(super) const HISTORY_GATE: GatePolicy = GatePolicy {
    min_samples: 6,
    min_percent: 20,
    min_span_ms: 30_000,
};


#[derive(Clone, Copy)]
struct ThreadGate {
    observed: u32,
    positive: u32,
    /// 第一次观测到有负载的时刻；存在期从这里开始算。
    first_ms: Option<u64>,
}
// 与 CoreTimelineParser.MAX_EVENTS 一致，确保每个有效分段都能完整读取。
const MAX_CORE_EVENTS: u64 = 20_000;
const STAGING_BYTES: u64 = 64 * 1024 * 1024;
const HEADER: &str = "QIXIA_AUTO_HISTORY\t1\n";
#[path = "storage_compression.rs"]
mod compression;
use compression::{completed_path, read_prefix};
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
    /// 已确认持续活跃的身份；确认后每轮都写行（含 0%），保证曲线连续。
    active: BTreeSet<(i32, i32, u64)>,
    /// 尚未确认的候选身份及其活跃证据。
    gates: BTreeMap<(i32, i32, u64), ThreadGate>,
    policy: GatePolicy,
    device_fields: Vec<(String, String)>,
    observed: BTreeMap<(i32, i32, u64), (String, String, Option<u64>, Option<u32>)>,
    core_dropped: u64,
    core_events: u64,
    core_incomplete: bool,
    _open: OpenFile,
}

impl Recording {
    fn create_source(directory: &Path, package: &str, start_ms: u64, prune_before_start: bool, source: &str) -> io::Result<Self> {
        fs::create_dir_all(directory)?;
        // 记录器是唯一写入方，调用前上一分段已关闭。
        // 除了守护启动时，也在此重试恢复，避免反复同步或重命名失败
        // 不断积累未计入额度的 .tmp 文件。
        recover(directory)?;
        if prune_before_start {
            prune(directory, start_ms)?;
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
            "{HEADER}source\t{source}\npackage\t{package}\nstart_ms\t{start_ms}\ncore_events\t1\n"
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
            gates: BTreeMap::new(),
            policy: HISTORY_GATE,
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
        for thread in threads.iter().take(MAX_IDENTITIES) {
            if !self.confirm_thread(thread, timestamp_ms) {
                continue;
            }
            self.row(timestamp_ms, &thread_row(timestamp_ms, thread))?;
        }
        Ok(())
    }
    /// 记录一次采样证据，并在达到门槛后把线程加入持续活跃集合。
    fn confirm_thread(&mut self, thread: &ThreadSample, timestamp_ms: u64) -> bool {
        confirm_thread(
            &mut self.gates,
            &mut self.active,
            thread,
            timestamp_ms,
            self.policy,
        )
    }
    fn threads_fit(&self, timestamp_ms: u64, threads: &[ThreadSample]) -> bool {
        let mut active = self.active.clone();
        let mut gates = self.gates.clone();
        let mut bytes = self.bytes;
        let mut rows = self.rows;
        for thread in threads.iter().take(MAX_IDENTITIES) {
            let id = (thread.pid, thread.tid, thread.start);
            if valid_thread(thread)
                && thread.percent > 0.0
                && !active.contains(&id)
                && !gates.contains_key(&id)
                && gates.len() + active.len() >= MAX_IDENTITIES
            {
                return false;
            }
            if confirm_thread(
                &mut gates,
                &mut active,
                thread,
                timestamp_ms,
                self.policy,
            ) {
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

/// 采样证据累积与确认。
///
/// 线程必须同时满足样本数、占比与存在期三个条件才会被写进历史；确认前
/// 不写任何行，确认后由调用方每轮写行（含 0%）以保持曲线连续。
fn confirm_thread(
    gates: &mut BTreeMap<(i32, i32, u64), ThreadGate>,
    active: &mut BTreeSet<(i32, i32, u64)>,
    thread: &ThreadSample,
    timestamp_ms: u64,
    policy: GatePolicy,
) -> bool {
    if !valid_thread(thread) {
        return false;
    }
    let id = (thread.pid, thread.tid, thread.start);
    if active.contains(&id) {
        return true;
    }
    // 只有新身份才受额度限制：已在候选表里的线程必须能继续累积证据。
    if !gates.contains_key(&id) && gates.len() + active.len() >= MAX_IDENTITIES {
        return false;
    }
    let gate = gates.entry(id).or_insert(ThreadGate {
        observed: 0,
        positive: 0,
        first_ms: None,
    });
    gate.observed = gate.observed.saturating_add(1);
    if thread.percent > 0.0 {
        gate.positive = gate.positive.saturating_add(1);
        gate.first_ms.get_or_insert(timestamp_ms);
    }
    // 存在期从第一次有负载开始算，不能因记录刚启动而缩短门槛。
    let span_ok = gate
        .first_ms
        .is_some_and(|first_ms| timestamp_ms.saturating_sub(first_ms) >= policy.min_span_ms);
    let share_ok =
        gate.positive.saturating_mul(100) >= policy.min_percent.saturating_mul(gate.observed);
    if gate.positive >= policy.min_samples && share_ok && span_ok {
        gates.remove(&id);
        active.insert(id);
        return true;
    }
    false
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
    let entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e),
    };
    // 限制单轮恢复工作量，但已完成归档不占名额，避免记录多时遗漏待恢复文件。
    for path in entries.flatten().map(|entry| entry.path())
        .filter(|path| owned_file(path, "tmp") && !is_open(path)).take(400) {
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
        }
    }
    Ok(())
}

/// 按完整会话的最新结束时间清理；任何活跃或无法确认时间的分段都保护整组。
pub(super) fn prune(directory: &Path, now_ms: u64) -> io::Result<()> {
    #[derive(Default)]
    struct Group { files: Vec<PathBuf>, latest: u64, protected: bool }
    let mut groups = BTreeMap::<String, Group>::new();
    let entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    for entry in entries {
        let entry = entry?;
        let path = entry.path();
        if !entry.file_type()?.is_file() || (!owned_file(&path, "log") && !owned_file(&path, "tmp")) { continue; }
        let key = match capture::retention_key(&path) {
            Ok(key) => key,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        let group = groups.entry(key).or_default();
        group.protected |= is_open(&path) || owned_file(&path, "tmp");
        let start = path.file_name().and_then(|name| name.to_str())
            .and_then(|name| name.strip_prefix("auto_"))
            .and_then(|name| name.split('_').next()).and_then(|stamp| stamp.parse::<u64>().ok());
        if let Some(start) = start.filter(|start| !crate::history_retention::expired(*start, now_ms)) {
            // 七天内开始的分段必未过期，不解压整条历史。
            group.latest = group.latest.max(start);
        } else if !group.protected {
            let bytes = read_prefix(&path, MAX_BYTES + 4096)?;
            let end = String::from_utf8_lossy(&bytes).lines()
                .filter_map(|line| line.strip_prefix("end_ms\t")?.parse::<u64>().ok()).max();
            if let Some(end) = end.filter(|end| *end > 0) { group.latest = group.latest.max(end); }
            else { group.protected = true; }
        }
        group.files.push(path);
    }
    for group in groups.into_values() {
        if group.protected || !crate::history_retention::expired(group.latest, now_ms) { continue; }
        for path in group.files {
            match fs::remove_file(path) {
                Ok(()) => {},
                Err(error) if error.kind() == io::ErrorKind::NotFound => {},
                Err(error) => return Err(error),
            }
        }
    }
    Ok(())
}
