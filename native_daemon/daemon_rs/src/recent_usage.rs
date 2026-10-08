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

#[cfg(test)]
mod tests {
    use super::*;

    fn visible(tracker: &mut Tracker, package: &str, mode: Option<Mode>, elapsed: u64) {
        tracker.observe(Foreground::Package(package.into()), mode, 10_000 + elapsed, elapsed);
    }
    fn row(package: &str, start: u64) -> Row {
        Row { package: package.into(), mode: Mode::Auto, started_ms: start, ended_ms: start + 2,
            average: None, peak: None, samples: 0 }
    }

    #[test]
    fn parsed_static_rules_exclude_placeholders_and_automatic_mode_wins() {
        let rules = crate::parse_config_text("com.placeholder=auto\ncom.rules{RenderThread}=0\ncom.child:render=0\ncom.both=0\n");
        let modes = managed(rules.iter().filter(|r| !r.auto).filter_map(|r| crate::base_package(&r.owner)),
            ["com.both", "com.automatic", "bad/package", "com..invalid"].into_iter());
        assert_eq!(modes, BTreeMap::from([("com.rules".into(), Mode::Rules), ("com.child".into(), Mode::Rules),
            ("com.both".into(), Mode::Auto), ("com.automatic".into(), Mode::Auto)]));
        assert!(!modes.contains_key("com.placeholder"));
    }

    #[test]
    fn foreground_requires_current_boot_fresh_visible_focus_and_unique_fields() {
        let text = "status=ok\ninteractive=1\nfocused_visible=1\nboot_id=boot\nupdated_elapsed_ms=1000\nfocused_package=com.game\n";
        assert_eq!(foreground(text, 2000, "boot"), Foreground::Package("com.game".into()));
        assert_eq!(foreground(text, 2000, "other"), Foreground::Unknown);
        assert_eq!(foreground(text, 26_001, "boot"), Foreground::Unknown);
        assert_eq!(foreground(text, 999, "boot"), Foreground::Unknown);
        assert_eq!(foreground(&(text.to_owned() + "status=ok\n"), 2000, "boot"), Foreground::Unknown);
        assert_eq!(foreground(&text.replace("focused_visible=1", "focused_visible=0"), 2000, "boot"), Foreground::Unknown);
        assert_eq!(foreground(&text.replace("interactive=1", "interactive=0"), 2000, "boot"), Foreground::Other);
        assert_eq!(foreground(&text.replace("status=ok", "status=empty"), 2000, "boot"), Foreground::Other);
    }

    #[test]
    fn unconfigured_manual_and_calibration_do_not_create_rule_summaries() {
        let mut tracker = Tracker::default();
        visible(&mut tracker, "com.manual", None, 1);
        tracker.sample("com.manual", 90.0);
        assert!(!tracker.active());
        visible(&mut tracker, "com.rules", Some(Mode::Rules), 2);
        tracker.sample("com.rules", 60.0);
        // 即使应用已有配置，校准期间也不记录普通受管模式摘要。
        visible(&mut tracker, "com.rules", None, 180_003);
        tracker.sample("com.rules", 120.0);
        assert_eq!(tracker.pending[0].average, Some(60.0));
        assert!(tracker.target(4000).is_none());
        visible(&mut tracker, "com.rules", Some(Mode::Rules), 184_001);
        assert_eq!(tracker.target(185_001), Some("com.rules"));
        assert_eq!(tracker.session.as_ref().unwrap().row.samples, 0);
    }

    #[test]
    fn only_valid_matching_samples_count_and_confirmed_stops_count_zero_each_second() {
        let mut tracker = Tracker::default();
        visible(&mut tracker, "com.game", Some(Mode::Auto), 100);
        assert_eq!(tracker.target(1099), None);
        assert_eq!(tracker.target(1100), Some("com.game"));
        for invalid in [f64::NAN, f64::INFINITY, -1.0, 301.0] { tracker.sample("com.game", invalid); }
        tracker.sample("com.other", 120.0);
        assert_eq!(tracker.session.as_ref().unwrap().row.average, None);
        tracker.sample("com.game", 60.0);
        for _ in 0..3 { tracker.sample("com.game", 0.0); }
        tracker.finish_at(Some(190_101), 180_101);
        assert_eq!((tracker.pending[0].average, tracker.pending[0].peak, tracker.pending[0].samples), (Some(15.0), Some(60.0), 4));
    }

    #[test]
    fn short_unknown_pauses_monitoring_without_splitting_usage() {
        let mut tracker = Tracker::default();
        visible(&mut tracker, "com.game", Some(Mode::Rules), 100);
        tracker.sample("com.game", 60.0);
        tracker.observe(Foreground::Unknown, None, 12_000, 2000);
        assert_eq!(tracker.target(3000), None);
        tracker.sample("com.game", 0.0);
        visible(&mut tracker, "com.game", Some(Mode::Rules), 6000);
        assert_eq!(tracker.target(6000), None);
        assert_eq!(tracker.target(7000), Some("com.game"));
        tracker.sample("com.game", 30.0);
        tracker.observe(Foreground::Other, None, 190_101, 180_101);
        assert_eq!(tracker.pending.len(), 1);
        let row = &tracker.pending[0];
        assert_eq!((row.started_ms, row.ended_ms, row.average, row.samples), (10_100, 190_101, Some(45.0), 2));
    }

    #[test]
    fn prolonged_unknown_and_shutdown_end_at_last_confirmed_foreground() {
        for shutdown in [false, true] {
            let mut tracker = Tracker::default();
            visible(&mut tracker, "com.game", Some(Mode::Auto), 100);
            visible(&mut tracker, "com.game", Some(Mode::Auto), 180_101);
            tracker.observe(Foreground::Unknown, None, 191_000, 181_000);
            if shutdown { tracker.finish(Some(196_000)); }
            else { tracker.observe(Foreground::Unknown, None, 197_000, 187_000); }
            assert!(!tracker.active());
            assert_eq!((tracker.pending[0].started_ms, tracker.pending[0].ended_ms), (10_100, 190_101));
            visible(&mut tracker, "com.game", Some(Mode::Auto), 188_000);
            assert_eq!(tracker.session.as_ref().unwrap().row.started_ms, 198_000);
        }
    }

    #[test]
    fn mode_change_closes_old_mode_and_resets_fps() {
        let mut tracker = Tracker::default();
        visible(&mut tracker, "com.game", Some(Mode::Rules), 100);
        tracker.sample("com.game", 120.0);
        visible(&mut tracker, "com.game", Some(Mode::Auto), 180_101);
        assert_eq!(tracker.pending[0].mode, Mode::Rules);
        assert_eq!(tracker.pending[0].ended_ms, 190_101);
        tracker.sample("com.game", 30.0);
        tracker.finish_at(Some(370_102), 360_102);
        assert_eq!(tracker.pending.len(), 1);
        assert_eq!((tracker.pending[0].mode, tracker.pending[0].average, tracker.pending[0].started_ms), (Mode::Auto, Some(30.0), 190_101));
    }

    #[test]
    fn storage_failure_retains_latest_bounded_rows_and_does_not_retry_on_every_switch() {
        let mut tracker = Tracker::default();
        visible(&mut tracker, "com.one", Some(Mode::Rules), 1);
        tracker.finish_at(Some(190_002), 180_002);
        assert!(tracker.persist_with(180_002, false, |_| Err(io::Error::other("offline"))).is_err());
        visible(&mut tracker, "com.two", Some(Mode::Auto), 180_003);
        tracker.finish_at(Some(190_004), 180_004);
        tracker.persist_with(180_004, false, |_| panic!("must honor backoff")).unwrap();
        tracker.persist_with(210_002, false, |rows| {
            assert_eq!(rows.len(), 1);
            assert_eq!(rows[0].package, "com.one");
            Ok(())
        }).unwrap();
        assert!(tracker.pending.is_empty());
        visible(&mut tracker, "com.three", Some(Mode::Rules), 210_003);
        tracker.finish_at(Some(400_004), 390_004);
        tracker.persist_with(390_004, false, |rows| { assert_eq!(rows[0].package, "com.three"); Ok(()) }).unwrap();
    }

    #[test]
    fn only_sessions_strictly_longer_than_three_minutes_enter_pending_and_storage() {
        for mode in [Mode::Auto, Mode::Rules] {
            for duration in [179_999, 180_000, 180_001] {
                let mut tracker = Tracker::default();
                visible(&mut tracker, "com.game", Some(mode), 100);
                tracker.observe(Foreground::Other, None, 10_100 + duration, 100 + duration);
                let qualifies = duration > MIN_SESSION_MS;
                assert_eq!(tracker.pending.len(), usize::from(qualifies), "{mode:?}: {duration}");
                assert!(!tracker.active());
                let mut writes = 0;
                tracker.persist_with(100 + duration, false, |rows| {
                    writes += 1;
                    assert_eq!(rows[0].ended_ms - rows[0].started_ms, duration);
                    Ok(())
                }).unwrap();
                assert_eq!(writes, usize::from(qualifies));
            }
        }
    }

    #[test]
    fn a_short_same_package_session_neither_replaces_qualified_pending_nor_rewrites_saved_summary() {
        let mut tracker = Tracker::default();
        visible(&mut tracker, "com.game", Some(Mode::Rules), 100);
        tracker.sample("com.game", 60.0);
        tracker.finish_at(Some(190_101), 180_101);
        let qualified = tracker.pending[0].clone();
        visible(&mut tracker, "com.game", Some(Mode::Auto), 181_000);
        tracker.sample("com.game", 120.0);
        tracker.finish_at(Some(192_000), 182_000);
        assert_eq!(tracker.pending, vec![qualified.clone()]);
        let mut saved = Vec::new();
        tracker.persist_with(182_000, false, |rows| { saved = rows.to_vec(); Ok(()) }).unwrap();
        visible(&mut tracker, "com.game", Some(Mode::Auto), 183_000);
        tracker.finish_at(Some(373_000), 363_000);
        tracker.persist_with(363_000, true, |_| panic!("短会话不能触发磁盘写入")).unwrap();
        assert_eq!(saved, vec![qualified]);
    }

    #[test]
    fn wall_clock_jumps_cannot_qualify_short_sessions_and_unknown_grace_does_not_extend_usage() {
        let mut tracker = Tracker::default();
        tracker.observe(Foreground::Package("com.game".into()), Some(Mode::Auto), 1_000_000, 100);
        tracker.finish_at(Some(9_000_000), 1_100);
        assert!(tracker.pending.is_empty());
        tracker.observe(Foreground::Package("com.game".into()), Some(Mode::Auto), 9_000_000, 2_000);
        tracker.finish_at(Some(1_000_000), 182_001);
        assert!(tracker.pending.is_empty(), "回拨后的 epoch 跨度也必须满足现有 App 读取门槛");

        for last_confirmed in [180_100, 180_101] {
            let mut tracker = Tracker::default();
            visible(&mut tracker, "com.game", Some(Mode::Rules), 100);
            visible(&mut tracker, "com.game", Some(Mode::Rules), last_confirmed);
            tracker.observe(Foreground::Unknown, None, 191_000, 181_000);
            tracker.observe(Foreground::Unknown, None, 197_000, 187_000);
            assert_eq!(tracker.pending.len(), usize::from(last_confirmed > 180_100));
        }
    }

    #[test]
    fn backward_clock_correction_preserves_qualified_pending_and_saved_summary() {
        let mut tracker = Tracker::default();
        visible(&mut tracker, "com.game", Some(Mode::Rules), 100);
        tracker.sample("com.game", 60.0);
        tracker.finish_at(Some(190_101), 180_101);
        let qualified = tracker.pending[0].clone();
        tracker.observe(Foreground::Package("com.game".into()), Some(Mode::Auto), 1_000_000, 200_000);
        tracker.finish_at(Some(900_000), 380_001);
        assert_eq!(tracker.pending, vec![qualified.clone()]);
        let mut saved = Vec::new();
        tracker.persist_with(380_001, false, |rows| { saved = rows.to_vec(); Ok(()) }).unwrap();
        for (index, epoch_duration) in [179_999, 180_000].into_iter().enumerate() {
            let start_elapsed = 400_000 + index as u64 * 200_000;
            tracker.observe(Foreground::Package("com.game".into()), Some(Mode::Auto), 1_000_000, start_elapsed);
            tracker.finish_at(Some(1_000_000 + epoch_duration), start_elapsed + 180_001);
            tracker.persist_with(start_elapsed + 180_001, true, |_| panic!("epoch 不合格的会话不能覆盖已保存摘要")).unwrap();
        }
        assert_eq!(saved, vec![qualified]);
    }

    #[test]
    fn latest_per_package_and_file_size_are_bounded_even_across_clock_correction() {
        let mut rows = Vec::new();
        for i in 0..30 { insert(&mut rows, row(&format!("com.{}{}", "x".repeat(110), i), 100 + i)); }
        assert_eq!(rows.len(), 24);
        let package = rows[10].package.clone();
        insert(&mut rows, row(&package, 1));
        assert_eq!(rows[0].started_ms, 1);
        assert_eq!(rows.iter().filter(|r| r.package == package).count(), 1);
        let encoded = encode(&rows);
        assert!(encoded.len() < 8 * 1024);
        assert_eq!(decode(&encoded).unwrap(), rows);
    }

    #[test]
    fn strict_protocol_rejects_invalid_fields_and_sample_inconsistency() {
        let valid = "com.game\tauto\t100\t200\t60.00\t90.00\t2";
        assert_eq!(decode(&format!("{HEADER}\n{valid}\n")).unwrap()[0].average, Some(60.0));
        for line in [valid.replace("auto", "manual"), valid.replace("com.game", "com..game"),
            valid.replace("100", "-100"), valid.replace("200", "99"), valid.replace("60.00", "NaN"),
            valid.replace("90.00", "301"), valid.replace("60.00", "100"), valid.replace("60.00", "-"),
            valid.replace("\t2", "\t0"), valid.replace("\t2", "\t4294967296"), format!("{valid}\textra"),
            format!("{valid}\n{valid}")] {
            assert!(decode(&format!("{HEADER}\n{line}\n")).is_err(), "accepted {line:?}");
        }
        assert!(decode(&format!("# wrong\n{valid}\n")).is_err());
        assert!(decode(&"x".repeat(MAX_BYTES + 1)).is_err());
        assert!(decode(&format!("{HEADER}\n{}", (0..25).map(|i| format!("com.p{i}\trules\t1\t2\t-\t-\t0\n")).collect::<String>())).is_err());
    }
}
