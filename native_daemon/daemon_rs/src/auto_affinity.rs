//! 仅在前台为活跃线程分配 QixiaThreads 自有 cpuset，不写入频率、
//! 优先级或 core_ctl。负载采样不依赖 FPS 采集器。
use std::collections::BTreeSet;
mod accounting;
pub(crate) mod calibration;
mod demand;
pub(crate) mod diagnostics;
mod live_policy;
#[cfg(all(test, not(any(target_os = "android", target_os = "linux"))))]
#[path = "auto_affinity/core_history.rs"]
mod core_history_tests;
#[path = "auto_affinity/calibration_planner.rs"]
mod planner;
mod topology;
#[cfg(any(target_os = "android", target_os = "linux"))]
use topology::Core;

pub const CONFIG: &str = "/data/adb/modules/QixiaThreads/config/auto_affinity.conf";
pub const STATUS: &str = "/data/adb/modules/QixiaThreads/config/auto_affinity.state";

/// 每应用一行，只读取有效包名并去重。
pub fn packages(text: &str) -> BTreeSet<String> {
    text.lines().filter_map(|line| {
        let pkg = line.split('#').next()?.trim();
        let valid = pkg.len() < 128 && pkg.contains('.') && pkg.split('.').all(|part|
            !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_'));
        valid.then(|| pkg.to_owned())
    }).collect()
}

pub fn foreground_package(text: &str, now_ms: u64, boot: &str) -> Option<String> {
    let fields: std::collections::BTreeMap<_, _> =
        text.lines().filter_map(|l| l.split_once('=')).collect();
    let updated = fields.get("updated_elapsed_ms")?.parse::<u64>().ok()?;
    if fields.get("status") != Some(&"ok")
        || fields.get("interactive") != Some(&"1")
        || fields.get("focused_visible") != Some(&"1")
        || boot.is_empty()
        || fields.get("boot_id") != Some(&boot)
        || updated == 0
        || now_ms < updated
        || now_ms - updated > 25_000
    {
        return None;
    }
    let pkg = *fields.get("focused_package")?;
    packages(pkg).into_iter().find(|p| p == pkg)
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Sample {
    pub pid: i32,
    pub tid: i32,
    pub surface: u64,
    pub timestamp_ns: u64,
    pub fps: f64,
    pub median_ns: u64,
    pub p95_ns: u64,
    pub max_ns: u64,
    pub frames: u32,
    pub exact: bool,
}

impl Sample {
    fn feedback_valid(self, now_ns: u64) -> bool {
        self.exact && self.pid > 0 && self.tid > 0 && self.surface != 0
            && self.timestamp_ns > 0 && now_ns >= self.timestamp_ns
            && now_ns - self.timestamp_ns <= 500_000_000
            && self.fps.is_finite() && self.fps > 0.0 && self.fps <= 1000.0
            && self.frames >= 20 && self.median_ns > 0
            && self.p95_ns >= self.median_ns && self.max_ns >= self.p95_ns
    }

}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[path = "auto_affinity_runtime.rs"]
pub mod platform;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_plain_packages_are_selected_and_deduplicated() {
        let parsed = packages("com.game\ncom.game # 重复\ncom.light\ncom.bad,fast\ncom.bad,unknown,extra\nbad..name\ncom.a:worker\n");
        assert_eq!(parsed, BTreeSet::from(["com.game".into(), "com.light".into()]));
        assert!(packages("com.game,unknown").is_empty());
    }
    #[test]
    fn optional_video_feedback_must_be_fresh_and_consistent() {
        let sample = Sample { pid: 1, tid: 2, surface: 3, timestamp_ns: 1_000_000_000,
            fps: 24.0, median_ns: 40_000_000, p95_ns: 50_000_000,
            max_ns: 60_000_000, frames: 24, exact: true };
        assert!(sample.feedback_valid(1_100_000_000));
        assert!(!sample.feedback_valid(1_600_000_000));
        assert!(!sample.feedback_valid(900_000_000));
        assert!(!Sample { p95_ns: 0, ..sample }.feedback_valid(1_100_000_000));
    }
    #[test]
    fn accepts_only_exact_base_packages() {
        assert_eq!(
            packages("com.game\ncom.game # a\ncom.x:child\n*\ncom.a;id\n"),
            BTreeSet::from(["com.game".into()])
        );
    }
    #[test]
    fn screen_off_stale_and_previous_boot_never_start_monitoring() {
        let text="status=ok\ninteractive=1\nfocused_visible=1\nboot_id=abc\nupdated_elapsed_ms=1000\nfocused_package=com.game\n";
        assert_eq!(
            foreground_package(text, 2000, "abc"),
            Some("com.game".into())
        );
        assert_eq!(
            foreground_package(&text.replace("interactive=1", "interactive=0"), 2000, "abc"),
            None
        );
        assert_eq!(foreground_package(text, 26_001, "abc"), None);
        assert_eq!(foreground_package(text, 999, "abc"), None);
        assert_eq!(foreground_package(text, 2000, "old"), None);
    }
}
