//! 按包名保存小型摘要，与完整历史记录及其采集器独立。
use std::{collections::BTreeMap, io, sync::{Mutex, OnceLock}};

const HEADER: &str = "# qixia_recent_usage=1";
pub(crate) const MAX_BYTES: usize = 64 * 1024;
const MAX_PACKAGES: usize = 24;
const UNKNOWN_GRACE_MS: u64 = 5_000;
const START_GRACE_MS: u64 = 1_000;
const MIN_SESSION_MS: u64 = 180_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum Mode { Auto, Rules }
impl Mode {
    fn text(self) -> &'static str { match self { Self::Auto => "auto", Self::Rules => "rules" } }
}

fn valid_package(package: &str) -> bool {
    package.len() < 128 && package.contains('.') && package.split('.').all(|part| {
        !part.is_empty() && part.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
    })
}

fn managed<'a>(rules: impl Iterator<Item = &'a str>, automatic: impl Iterator<Item = &'a str>) -> BTreeMap<String, Mode> {
    let mut result = BTreeMap::new();
    for (package, mode) in rules.map(|p| (p, Mode::Rules)).chain(automatic.map(|p| (p, Mode::Auto))) {
        if valid_package(package) { result.insert(package.into(), mode); }
    }
    result
}

static MANAGED: OnceLock<Mutex<BTreeMap<String, Mode>>> = OnceLock::new();
pub(crate) fn publish_managed<'a>(rules: impl Iterator<Item = &'a str>, automatic: impl Iterator<Item = &'a str>) {
    let next = managed(rules, automatic);
    let mut slot = MANAGED.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner());
    if *slot == next { return; }
    *slot = next;
    drop(slot);
    #[cfg(any(target_os = "android", target_os = "linux"))] {
        let fd = managed_signal();
        if fd >= 0 {
            let value: u64 = 1;
            unsafe { libc::write(fd, (&value as *const u64).cast(), 8); }
        }
    }
}
pub(crate) fn managed_mode(package: &str) -> Option<Mode> {
    MANAGED.get()?.lock().unwrap_or_else(|e| e.into_inner()).get(package).copied()
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn managed_signal() -> i32 {
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    static SIGNAL: OnceLock<Option<OwnedFd>> = OnceLock::new();
    SIGNAL.get_or_init(|| {
        let fd = unsafe { libc::eventfd(0, libc::EFD_NONBLOCK | libc::EFD_CLOEXEC) };
        (fd >= 0).then(|| unsafe { OwnedFd::from_raw_fd(fd) })
    }).as_ref().map_or(-1, AsRawFd::as_raw_fd)
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn drain_managed_signal() -> bool {
    let fd = managed_signal();
    let mut value: u64 = 0;
    fd >= 0 && unsafe { libc::read(fd, (&mut value as *mut u64).cast(), 8) } == 8
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) enum Foreground { Package(String), Other, Unknown }

/// 助手状态缺失或过期，不能证明应用已经关闭。
pub(crate) fn foreground(text: &str, now_ms: u64, boot: &str) -> Foreground {
    if text.len() > 16 * 1024 { return Foreground::Unknown; }
    let mut fields = BTreeMap::new();
    for line in text.lines() {
        let Some((key, value)) = line.split_once('=') else { return Foreground::Unknown; };
        if fields.insert(key, value).is_some() { return Foreground::Unknown; }
    }
    let Some(updated) = fields.get("updated_elapsed_ms").and_then(|v| v.parse::<u64>().ok()) else { return Foreground::Unknown; };
    if updated == 0 || updated > now_ms || now_ms - updated > 25_000
        || boot.is_empty() || fields.get("boot_id") != Some(&boot) { return Foreground::Unknown; }
    if fields.get("interactive") == Some(&"0") || fields.get("status") == Some(&"empty") {
        return Foreground::Other;
    }
    match crate::auto_affinity::foreground_package(text, now_ms, boot).filter(|p| valid_package(p)) {
        Some(package) => Foreground::Package(package),
        None => Foreground::Unknown,
    }
}

#[derive(Clone, Debug, PartialEq)]
struct Row {
    package: String,
    mode: Mode,
    started_ms: u64,
    ended_ms: u64,
    average: Option<f64>,
    peak: Option<f64>,
    samples: u32,
}

fn invalid() -> io::Error { io::Error::new(io::ErrorKind::InvalidData, "invalid recent usage summary") }
fn decode(text: &str) -> io::Result<Vec<Row>> {
    if text.len() > MAX_BYTES { return Err(invalid()); }
    let mut lines = text.lines();
    if lines.next() != Some(HEADER) { return Err(invalid()); }
    let mut result = Vec::new();
    for line in lines {
        if result.len() == MAX_PACKAGES { return Err(invalid()); }
        let parts: Vec<_> = line.split('\t').collect();
        if parts.len() != 7 || !valid_package(parts[0]) { return Err(invalid()); }
        let mode = match parts[1] { "auto" => Mode::Auto, "rules" => Mode::Rules, _ => return Err(invalid()) };
        let timestamp = |value: &str| -> io::Result<u64> {
            if !value.bytes().all(|c| c.is_ascii_digit()) { return Err(invalid()); }
            value.parse::<u64>().ok().filter(|v| *v > 0 && *v <= i64::MAX as u64).ok_or_else(invalid)
        };
        let started_ms = timestamp(parts[2])?;
        let ended_ms = timestamp(parts[3])?;
        if ended_ms < started_ms { return Err(invalid()); }
        let fps = |value: &str| -> io::Result<Option<f64>> {
            if value == "-" { return Ok(None); }
            value.parse::<f64>().ok().and_then(crate::fps::valid_fps_sample).map(Some).ok_or_else(invalid)
        };
        let average = fps(parts[4])?;
        let peak = fps(parts[5])?;
        if !parts[6].bytes().all(|c| c.is_ascii_digit()) { return Err(invalid()); }
        let samples = parts[6].parse::<u32>().map_err(|_| invalid())?;
        if (samples == 0 && (average.is_some() || peak.is_some()))
            || (samples > 0 && (average.is_none() || peak.is_none() || average > peak))
            || result.iter().any(|row: &Row| row.package == parts[0]) { return Err(invalid()); }
        result.push(Row { package: parts[0].into(), mode, started_ms, ended_ms, average, peak, samples });
    }
    Ok(result)
}

fn encode(rows: &[Row]) -> String {
    let mut text = format!("{HEADER}\n");
    for row in rows {
        let fps = |value: Option<f64>| value.map(|v| format!("{v:.2}")).unwrap_or_else(|| "-".into());
        text.push_str(&format!("{}\t{}\t{}\t{}\t{}\t{}\t{}\n", row.package, row.mode.text(),
            row.started_ms, row.ended_ms, fps(row.average), fps(row.peak), row.samples));
    }
    text
}

fn insert(rows: &mut Vec<Row>, row: Row) {
    rows.retain(|old| old.package != row.package);
    rows.insert(0, row);
    rows.truncate(MAX_PACKAGES);
}

struct Session {
    row: Row,
    started_elapsed_ms: u64,
    last_seen_elapsed_ms: u64,
    ready_since: u64,
    last_seen_ms: u64,
    unknown_since: Option<u64>,
}

#[derive(Default)]
pub(crate) struct Tracker {
    session: Option<Session>,
    pending: Vec<Row>,
    last_save_attempt: Option<u64>,
}

impl Tracker {
    pub(crate) fn active(&self) -> bool { self.session.is_some() }

    /// None 表示已确认前台不受管理；Unknown 会短暂保留当前身份。
    pub(crate) fn observe(&mut self, foreground: Foreground, mode: Option<Mode>, epoch_ms: u64, elapsed_ms: u64) {
        if self.session.as_ref().is_some_and(|s| s.unknown_since.is_some_and(|since| elapsed_ms.saturating_sub(since) >= UNKNOWN_GRACE_MS)) {
            self.finish_at(None, elapsed_ms);
        }
        let target = match foreground {
            Foreground::Unknown => {
                if let Some(session) = self.session.as_mut() { session.unknown_since.get_or_insert(elapsed_ms); }
                return;
            },
            Foreground::Package(package) => mode.map(|mode| (package, mode)),
            Foreground::Other => None,
        };
        if self.session.as_ref().map(|s| (s.row.package.as_str(), s.row.mode)) != target.as_ref().map(|(p,m)| (p.as_str(), *m)) {
            self.finish_at(Some(epoch_ms), elapsed_ms);
            if let Some((package, mode)) = target.filter(|(p,_)| valid_package(p) && epoch_ms > 0 && epoch_ms <= i64::MAX as u64) {
                self.session = Some(Session { row: Row { package, mode, started_ms: epoch_ms, ended_ms: epoch_ms,
                    average: None, peak: None, samples: 0 }, started_elapsed_ms: elapsed_ms, last_seen_elapsed_ms: elapsed_ms,
                    ready_since: elapsed_ms, last_seen_ms: epoch_ms, unknown_since: None });
            }
        }
        if let Some(session) = self.session.as_mut() {
            session.last_seen_ms = session.last_seen_ms.max(epoch_ms);
            session.last_seen_elapsed_ms = session.last_seen_elapsed_ms.max(elapsed_ms);
            if session.unknown_since.take().is_some() { session.ready_since = elapsed_ms; }
        }
    }

    pub(crate) fn target(&self, elapsed_ms: u64) -> Option<&str> {
        self.session.as_ref().filter(|s| s.unknown_since.is_none()
            && elapsed_ms.saturating_sub(s.ready_since) >= START_GRACE_MS).map(|s| s.row.package.as_str())
    }

    pub(crate) fn sample(&mut self, package: &str, fps: f64) {
        let Some(fps) = crate::fps::valid_fps_sample(fps) else { return; };
        let Some(session) = self.session.as_mut().filter(|s| s.unknown_since.is_none() && s.row.package == package) else { return; };
        let row = &mut session.row;
        if row.samples == u32::MAX { return; }
        row.samples += 1;
        row.average = Some(row.average.map_or(fps, |average| average + (fps - average) / row.samples as f64));
        row.peak = Some(row.peak.map_or(fps, |peak| peak.max(fps)));
    }

    pub(crate) fn finish(&mut self, ended_ms: Option<u64>) {
        self.finish_at(ended_ms, crate::elapsed_realtime_ms());
    }

    fn finish_at(&mut self, ended_ms: Option<u64>, elapsed_ms: u64) {
        if let Some(mut session) = self.session.take() {
            // 门槛使用单调时钟，修改系统时间不能把短会话变成合格记录。
            // 前台未知后的等待时间不算作已确认使用时长。
            let end_elapsed = if session.unknown_since.is_some() || ended_ms.is_none() { session.last_seen_elapsed_ms }
                else { elapsed_ms.max(session.last_seen_elapsed_ms) };
            if end_elapsed.saturating_sub(session.started_elapsed_ms) <= MIN_SESSION_MS { return; }
            session.row.ended_ms = if session.unknown_since.is_some() { session.last_seen_ms }
                else { ended_ms.unwrap_or(session.last_seen_ms).max(session.last_seen_ms) };
            // 现有协议由 App 按 epoch 起止筛选；回拨后跨度不足时保留上一条合格摘要。
            if session.row.ended_ms.saturating_sub(session.row.started_ms) <= MIN_SESSION_MS { return; }
            insert(&mut self.pending, session.row);
        }
    }

    /// 仅在状态切换时写入，或在存储不可用后进行有限重试。
    pub(crate) fn persist(&mut self, elapsed_ms: u64, force: bool) -> io::Result<()> {
        self.persist_with(elapsed_ms, force, |pending| {
            let storage = crate::private_storage::resolve()?;
            let mut rows = match storage.read_recent_usage(MAX_BYTES) {
                Ok(text) => decode(&text).unwrap_or_default(),
                Err(error) if matches!(error.kind(), io::ErrorKind::NotFound | io::ErrorKind::InvalidData) => Vec::new(),
                Err(error) => return Err(error),
            };
            for row in pending.iter().rev() { insert(&mut rows, row.clone()); }
            storage.write_recent_usage(encode(&rows).as_bytes())
        })
    }

    fn persist_with(&mut self, elapsed_ms: u64, force: bool, write: impl FnOnce(&[Row]) -> io::Result<()>) -> io::Result<()> {
        if self.pending.is_empty() || (!force && self.last_save_attempt.is_some_and(|last| elapsed_ms.saturating_sub(last) < 30_000)) { return Ok(()); }
        self.last_save_attempt = Some(elapsed_ms);
        write(&self.pending)?;
        self.pending.clear();
        self.last_save_attempt = None;
        Ok(())
    }
}
