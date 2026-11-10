//! 有界的运行时事件，不创建工作线程、计时器，不执行 fsync 或逐帧轮询。
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Level { Info, Warning, Error }
impl Level {
    fn label(self) -> &'static str {
        match self { Self::Info => "INFO", Self::Warning => "WARN", Self::Error => "ERROR" }
    }
}

macro_rules! log_info { ($($arg:tt)*) => { $crate::event_log::emit($crate::event_log::Level::Info, format_args!($($arg)*)) }; }
macro_rules! log_warn { ($($arg:tt)*) => { $crate::event_log::emit($crate::event_log::Level::Warning, format_args!($($arg)*)) }; }
macro_rules! log_error { ($($arg:tt)*) => { $crate::event_log::emit($crate::event_log::Level::Error, format_args!($($arg)*)) }; }

const MAX_BYTES: u64 = 512 * 1024;
const REPEAT_WINDOW: Duration = Duration::from_secs(30);
const MAX_MESSAGE: usize = 8192;
static LOG: OnceLock<Mutex<EventLog>> = OnceLock::new();

pub(crate) fn init(config: &Path) {
    let Some(module) = config.parent().and_then(Path::parent) else { return };
    match EventLog::open(module.join("logs/Runtime.log"), MAX_BYTES) {
        Ok(log) => {
            let _ = LOG.set(Mutex::new(log));
            #[cfg(any(target_os = "android", target_os = "linux"))]
            qixia_ebpf_bridge::set_log_sink(|warning, message| {
                emit(if warning { Level::Warning } else { Level::Info }, format_args!("{message}"));
            });
        }
        Err(error) => { let _ = writeln!(io::stderr(), "[日志] 事件日志初始化失败，保留标准输出: {error}"); }
    }
}

pub(crate) fn emit(level: Level, args: fmt::Arguments<'_>) {
    let Some(log) = LOG.get() else {
        // CLI、版本和协议模式保留原有标准输出约定。
        if level == Level::Info { let _ = writeln!(io::stdout(), "{args}"); }
        else { let _ = writeln!(io::stderr(), "{args}"); }
        return;
    };
    let mut log = log.lock().unwrap_or_else(|p| p.into_inner());
    if log.retry_after.is_some_and(|after| Instant::now() < after) { return; }
    let message = bounded_message(args.to_string());
    let now = Instant::now();
    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    if let Err(error) = log.record(level, message, timestamp, now) {
        log.retry_after = Some(now + REPEAT_WINDOW);
        // 每个重试窗口只提示一次；磁盘写满不能中断调度，也不能刷屏标准错误输出。
        let _ = writeln!(io::stderr(), "[日志] 写入失败，30 秒后重试: {error}");
    } else { log.retry_after = None; }
}

pub(crate) fn flush() {
    if let Some(log) = LOG.get() {
        let _ = log.lock().unwrap_or_else(|p| p.into_inner()).flush_repeats();
    }
}

fn bounded_message(mut text: String) -> String {
    if text.len() > MAX_MESSAGE {
        let mut end = MAX_MESSAGE;
        while !text.is_char_boundary(end) { end -= 1; }
        text.truncate(end);
        text.push_str(" …[日志过长，已截断]");
    }
    text
}

fn escaped(text: &str) -> String {
    let mut output = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '\\' => output.push_str("\\\\"), '\t' => output.push_str("\\t"),
            '\n' => output.push_str("\\n"), '\r' => output.push_str("\\r"),
            c if c.is_control() => output.push(' '), c => output.push(c),
        }
    }
    output
}

fn encode(level: Level, text: &str, timestamp: u128, sequence: u64, count: u64) -> String {
    let (tag, message) = text.strip_prefix('[').and_then(|s| s.split_once(']'))
        .filter(|(tag, _)| !tag.is_empty() && tag.len() <= 64).unwrap_or(("RS", text));
    format!("@QIXIA/1\t{timestamp}\t{}:{sequence}\t{}\t{}\t{count}\t{}\n",
        std::process::id(), level.label(), escaped(tag), escaped(message.trim_start()))
}

struct Previous {
    level: Level, text: String, pending: u64, last_timestamp: u128, emitted_at: Instant,
}

struct EventLog {
    path: PathBuf, file: Option<File>, bytes: u64, limit: u64, sequence: u64,
    previous: Option<Previous>, retry_after: Option<Instant>,
}

impl EventLog {
    fn open(path: PathBuf, limit: u64) -> io::Result<Self> {
        if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
        let file = OpenOptions::new().create(true).append(true).open(&path)?;
        let bytes = file.metadata()?.len();
        Ok(Self { path, file: Some(file), bytes, limit, sequence: 0, previous: None, retry_after: None })
    }

    fn record(&mut self, level: Level, text: String, timestamp: u128, now: Instant) -> io::Result<()> {
        if let Some(previous) = &mut self.previous {
            if previous.level == level && previous.text == text {
                previous.pending = previous.pending.saturating_add(1);
                previous.last_timestamp = timestamp;
                if now.duration_since(previous.emitted_at) >= REPEAT_WINDOW {
                    self.flush_repeats()?;
                    if let Some(previous) = &mut self.previous { previous.emitted_at = now; }
                }
                return Ok(());
            }
        }
        self.flush_repeats()?;
        self.append(level, &text, timestamp, 1)?;
        self.previous = Some(Previous { level, text, pending: 0, last_timestamp: timestamp, emitted_at: now });
        Ok(())
    }

    fn flush_repeats(&mut self) -> io::Result<()> {
        if let Some(previous) = &self.previous {
            if previous.pending > 0 {
                let (level, text, timestamp, count) = (previous.level, previous.text.clone(), previous.last_timestamp, previous.pending);
                self.append(level, &text, timestamp, count)?;
                if let Some(previous) = &mut self.previous { previous.pending = 0; }
            }
        }
        Ok(())
    }

    fn archive(&self, suffix: &str) -> PathBuf {
        let mut name = self.path.as_os_str().to_os_string(); name.push(suffix); PathBuf::from(name)
    }

    fn append(&mut self, level: Level, text: &str, timestamp: u128, count: u64) -> io::Result<()> {
        self.sequence = self.sequence.saturating_add(1);
        let line = encode(level, text, timestamp, self.sequence, count);
        if self.bytes > 0 && self.bytes + line.len() as u64 > self.limit {
            self.file.take();
            match fs::remove_file(self.archive(".2")) { Ok(()) => (), Err(e) if e.kind() == io::ErrorKind::NotFound => (), Err(e) => return Err(e) }
            match fs::rename(self.archive(".1"), self.archive(".2")) { Ok(()) => (), Err(e) if e.kind() == io::ErrorKind::NotFound => (), Err(e) => return Err(e) }
            fs::rename(&self.path, self.archive(".1"))?;
            self.bytes = 0;
        }
        if self.file.is_none() {
            let file = OpenOptions::new().create(true).append(true).open(&self.path)?;
            self.bytes = file.metadata()?.len();
            self.file = Some(file);
        }
        self.file.as_mut().unwrap().write_all(line.as_bytes())?;
        self.bytes += line.len() as u64;
        Ok(())
    }
}
