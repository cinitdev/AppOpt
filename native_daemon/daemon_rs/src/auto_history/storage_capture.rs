//! 入选条件针对整次前台使用，不针对单个文件分段。
//! 前台使用超过 3 分钟前，临时分段保持为私有 .tmp 文件。
use super::*;
use std::ops::{Deref, DerefMut};

const MIN_FOREGROUND_MS: u64 = 180_000;

struct UsageClock {
    started: Instant,
    stopped_ms: Option<u64>,
}
impl UsageClock {
    fn at(&self, now: Instant) -> u64 {
        self.stopped_ms
            .unwrap_or_else(|| now.saturating_duration_since(self.started).as_millis() as u64)
    }
    fn stop(&mut self, now: Instant) {
        if self.stopped_ms.is_none() {
            self.stopped_ms = Some(self.at(now));
        }
    }
}

pub(in crate::auto_history) struct Capture {
    recording: Recording,
    capture_id: String,
    usage: UsageClock,
    segment_started_ms: u64,
    admitted: bool,
    // 采集存活期间，守卫会将已封存的临时文件排除在恢复和清理之外。
    // 记录器失败或退出后，这些文件即可恢复。
    pending: Vec<(PathBuf, OpenFile)>,
}
impl Deref for Capture {
    type Target = Recording;
    fn deref(&self) -> &Recording {
        &self.recording
    }
}
impl DerefMut for Capture {
    fn deref_mut(&mut self) -> &mut Recording {
        &mut self.recording
    }
}
impl Capture {
    pub fn new(directory: &Path, package: &str, start_ms: u64) -> io::Result<Self> {
        let started = Instant::now();
        let mut recording = Recording::create(directory, package, start_ms, false)?;
        let capture_id = recording
            .path
            .file_stem()
            .unwrap()
            .to_string_lossy()
            .into_owned();
        metadata(&mut recording, &capture_id, 0)?;
        Ok(Self {
            recording,
            capture_id,
            usage: UsageClock {
                started,
                stopped_ms: None,
            },
            segment_started_ms: 0,
            admitted: false,
            pending: Vec::new(),
        })
    }
    pub fn stop_usage(&mut self) {
        self.usage.stop(Instant::now());
    }
    pub fn checkpoint(&mut self) -> io::Result<()> {
        let usage = self.usage.at(Instant::now());
        write_usage(&mut self.recording, usage)?;
        self.publish_pending(usage)
    }
    pub fn rotate(&mut self, directory: &Path, start_ms: u64) -> io::Result<()> {
        let usage = self.usage.at(Instant::now());
        // 预留下一个有界暂存位置前，达到入选条件可先发布较早分段；
        // 未达到条件的使用记录绝不淘汰旧历史。
        self.publish_pending(usage)?;
        if usage > MIN_FOREGROUND_MS {
            prune(directory, true)?;
        }
        let mut next = Recording::create(directory, &self.recording.package, start_ms, false)?;
        metadata(&mut next, &self.capture_id, usage)?;
        next.device(&self.recording.device_fields)?;
        write_usage(&mut self.recording, usage)?;
        let old = std::mem::replace(&mut self.recording, next);
        let duration = usage.saturating_sub(self.segment_started_ms);
        self.segment_started_ms = usage;
        match old.seal(start_ms, Some(duration)) {
            Ok(Some(sealed)) => self.pending.push(sealed),
            Ok(None) => {}
            Err(error) => {
                self.recording.core_loss(0, true);
                return Err(error);
            }
        }
        self.publish_pending(usage)
    }
    pub fn rotate_with_threads(
        &mut self,
        directory: &Path,
        start_ms: u64,
        pending: &mut crate::auto_history::window::Window,
    ) -> io::Result<()> {
        // 优先写入接纳这些观测的分段。即使窗口有界，仍可能超过该段剩余空间，
        // 因此不能只依赖软预留额度。
        if let Some((timestamp_ms, threads)) = pending.take() {
            if self.write_thread_batch(directory, start_ms, timestamp_ms, &threads)? {
                return Ok(());
            }
        }
        self.rotate(directory, start_ms)
    }
    pub fn write_threads(&mut self, directory: &Path, timestamp_ms: u64, threads: &[ThreadSample]) -> io::Result<()> {
        self.write_thread_batch(directory, timestamp_ms, timestamp_ms, threads).map(|_| ())
    }
    fn write_thread_batch(&mut self, directory: &Path, rotation_ms: u64, timestamp_ms: u64, threads: &[ThreadSample]) -> io::Result<bool> {
        if !self.recording.accepts(timestamp_ms) {
            return Ok(false);
        }
        let rotate = !self.recording.threads_fit(timestamp_ms, threads);
        if rotate {
            // 只携带本有界窗口中实际出现的已知线程身份。
            // 这样换段后测得的零负载样本仍有意义，
            // 而本窗口未出现的旧身份不会占用容量。
            let known: BTreeSet<_> = threads.iter().take(4096)
                .filter(|thread| valid_thread(thread))
                .map(|thread| (thread.pid, thread.tid, thread.start))
                .filter(|id| self.recording.active.contains(id)).collect();
            self.rotate(directory, rotation_ms.min(timestamp_ms))?;
            self.recording.active.extend(known);
        }
        self.recording.threads(timestamp_ms, threads)?;
        Ok(rotate)
    }
    fn publish_pending(&mut self, usage: u64) -> io::Result<()> {
        if usage <= MIN_FOREGROUND_MS {
            return Ok(());
        }
        if !self.admitted {
            // 较早分段一经发布就可能被导入或删除。应先将入选证据
            // 持久化到当前仍活跃的分段，使其不依赖
            // 另一个文件在后续崩溃后仍然存在。
            write_usage(&mut self.recording, usage)?;
            self.recording.output.flush()?;
            self.recording.output.get_ref().sync_data()?;
            self.admitted = true;
        }
        let mut published = false;
        while let Some((path, _)) = self.pending.first() {
            publish_sealed(path, usage)?;
            self.pending.remove(0);
            published = true;
        }
        if published {
            prune(
                self.recording
                    .path
                    .parent()
                    .unwrap_or_else(|| Path::new(".")),
                false,
            )?;
        }
        Ok(())
    }
    pub fn finish(mut self, end_ms: u64) -> io::Result<()> {
        self.stop_usage();
        let usage = self.usage.at(Instant::now());
        write_usage(&mut self.recording, usage)?;
        // 在所有较早阶段发布完毕前，保留当前分段的持久入选证据。
        // seal() 会移除空的最后一段；如果先执行它，
        // 发布中途崩溃就会使短分段失去恢复依据，
        // 尤其是在 App 已经导入首个日志之后。
        self.publish_pending(usage)?;
        let duration = usage.saturating_sub(self.segment_started_ms);
        let directory = self
            .recording
            .path
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .to_owned();
        if let Some(sealed) = self.recording.seal(end_ms, Some(duration))? {
            self.pending.push(sealed);
        }
        if usage <= MIN_FOREGROUND_MS {
            // 这里只移除为本次前台使用创建的路径，不处理旧的完整历史，
            // 也不执行七条记录的保留策略。
            for (path, _) in self.pending {
                fs::remove_file(path)?;
            }
        } else {
            for (path, _) in self.pending {
                publish_sealed(&path, usage)?;
            }
            prune(&directory, false)?;
        }
        Ok(())
    }
}

fn publish_sealed(path: &Path, usage: u64) -> io::Result<()> {
    // 源文件删除失败前，rename 可能已经成功。追加新的使用标记前，
    // 先验证已发布文件与源文件是否一致，否则无害的重试
    // 也会让原本相同的两份数据出现差异。
    if compression::remove_published_source(path)? { return Ok(()); }
    // 发布前先同步入选证据，不依赖当前活跃分段的缓冲区
    // 是否能在崩溃后保留。
    let mut output = OpenOptions::new().append(true).open(path)?;
    writeln!(output, "foreground_ms\t{usage}")?;
    output.sync_data()?;
    drop(output);
    compression::publish(path)
}

fn metadata(recording: &mut Recording, capture_id: &str, usage: u64) -> io::Result<()> {
    let text = format!(
        "minimum_usage_ms\t{MIN_FOREGROUND_MS}\ncapture_id\t{capture_id}\nforeground_ms\t{usage}\n"
    );
    recording.output.write_all(text.as_bytes())?;
    recording.bytes += text.len() as u64;
    // 在后续换段或其他正在关闭的会话检查存储额度前，
    // 保留策略必须已识别当前活跃采集。
    recording.output.flush()?;
    Ok(())
}
fn write_usage(recording: &mut Recording, usage: u64) -> io::Result<()> {
    let text = format!("foreground_ms\t{usage}\n");
    recording.output.write_all(text.as_bytes())?;
    recording.bytes += text.len() as u64;
    Ok(())
}

/// 即使临时采集耗尽有限磁盘额度，也要保留现有记录。
/// 只有达到入选条件后才允许淘汰历史，不能在应用启动时就淘汰。
pub(super) fn check_staging_budget(directory: &Path) -> io::Result<()> {
    let mut entries = fs::read_dir(directory)?;
    let mut bytes = 0u64;
    for entry in entries.by_ref().take(1000).flatten() {
        let path = entry.path();
        if owned_file(&path, "log") || owned_file(&path, "tmp") {
            let size = entry.metadata()?.len();
            bytes = bytes.saturating_add(if is_open(&path) {
                size.max(MAX_BYTES)
            } else {
                size
            });
        }
    }
    if entries.next().is_some() || bytes.saturating_add(MAX_BYTES) > TOTAL_BYTES {
        return Err(io::Error::other(
            "auto history provisional storage budget exhausted",
        ));
    }
    Ok(())
}

fn capture_id(text: &str) -> Option<&str> {
    text.lines()
        .find_map(|line| line.strip_prefix("capture_id\t"))
        .filter(|id| {
            !id.is_empty()
                && id.len() <= 100
                && id.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
        })
}
pub(super) fn retention_key(path: &Path) -> io::Result<String> {
    let bytes = read_prefix(path, 4096)?;
    let text = String::from_utf8_lossy(&bytes);
    if has_history_header(&bytes) {
        if let Some(id) = capture_id(&text) {
            let package = text
                .lines()
                .find_map(|line| line.strip_prefix("package\t"))
                .unwrap_or_default();
            return Ok(format!("capture:{package}:{id}"));
        }
    }
    Ok(format!(
        "file:{}",
        path.file_stem().unwrap_or_default().to_string_lossy()
    ))
}
fn marked(text: &str) -> bool {
    text.lines()
        .any(|line| line.starts_with("minimum_usage_ms\t"))
}
fn recorded_usage(text: &str) -> u64 {
    text.lines()
        .filter_map(|line| line.strip_prefix("foreground_ms\t")?.parse::<u64>().ok())
        .max()
        .unwrap_or(0)
}
pub(super) fn recovery_usage(directory: &Path) -> io::Result<BTreeMap<String, u64>> {
    let entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(BTreeMap::new()),
        Err(error) => return Err(error),
    };
    let mut usage = BTreeMap::new();
    for entry in entries.take(400).flatten() {
        let path = entry.path();
        if !owned_file(&path, "tmp") || is_open(&path) {
            continue;
        }
        let mut bytes = Vec::new();
        File::open(&path)?
            .take(MAX_BYTES + 1)
            .read_to_end(&mut bytes)?;
        if bytes.len() as u64 > MAX_BYTES || !has_history_header(&bytes) {
            continue;
        }
        bytes.truncate(bytes.iter().rposition(|b| *b == b'\n').map_or(0, |i| i + 1));
        let text = String::from_utf8_lossy(&bytes);
        if !marked(&text) {
            continue;
        }
        if let Some(id) = capture_id(&text) {
            let maximum = usage.entry(id.to_owned()).or_insert(0);
            *maximum = (*maximum).max(recorded_usage(&text));
        }
    }
    Ok(usage)
}
pub(super) fn proven_usage(text: &str, usage: &BTreeMap<String, u64>) -> Option<u64> {
    if !marked(text) {
        return None;
    }
    capture_id(text).and_then(|id| usage.get(id).copied())
}
pub(super) fn recoverable(text: &str, usage: &BTreeMap<String, u64>) -> bool {
    // 早于本策略生成的文件沿用原恢复行为。
    !marked(text) || proven_usage(text, usage).is_some_and(|ms| ms > MIN_FOREGROUND_MS)
}

#[cfg(test)]
#[path = "storage_capture_tests.rs"]
mod tests;
