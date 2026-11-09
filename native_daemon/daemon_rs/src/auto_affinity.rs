//! 仅在前台为活跃线程分配 QixiaThreads 自有 cpuset，不写入频率、
//! 优先级或 core_ctl。负载采样不依赖 FPS 采集器。
use std::collections::{BTreeMap, BTreeSet};
mod accounting;
pub mod custom;
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

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AppOptions {
    pub automatic: bool,
    pub fallback: bool,
    pub recording: bool,
    pub custom: Vec<custom::Rule>,
}

/// 空选项沿用自动分配；record 单独使用时只记录，fallback 包含自动分配。
pub fn app_options(text: &str) -> BTreeMap<String, AppOptions> {
    let mut result: BTreeMap<String, AppOptions> = BTreeMap::new();
    for line in text.lines() {
        let parts: Vec<_> = line.split('#').next().unwrap_or_default().trim().split(',').map(str::trim).collect();
        let pkg = parts[0];
        let valid = pkg.len() < 128 && pkg.contains('.') && pkg.split('.').all(|part|
            !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_'));
        let flags = &parts[1..];
        let custom_flags: Vec<_> = flags.iter().filter_map(|flag| flag.strip_prefix("custom=")).collect();
        if custom_flags.len() > 1 { continue; }
        let custom = match custom_flags.first() {
            Some(value) => match custom::parse(value) { Some(rules) => Some(rules), None => continue },
            None => None,
        };
        if valid && flags.iter().all(|flag| matches!(*flag, "auto" | "fallback" | "record") || flag.starts_with("custom=")) {
            let options = result.entry(pkg.to_owned()).or_default();
            options.automatic |= flags.is_empty() || flags.contains(&"auto") || flags.contains(&"fallback") || custom.is_some();
            options.fallback |= flags.contains(&"fallback");
            options.recording |= flags.contains(&"record");
            if let Some(custom) = custom { options.custom = custom; }
        }
    }
    result
}

pub fn modes(text: &str) -> BTreeMap<String, bool> {
    app_options(text).into_iter().filter(|(_, options)| options.automatic)
        .map(|(pkg, options)| (pkg, options.fallback)).collect()
}

/// 各调用方仅使用包名，不把选项传给进程查询或黑名单。
pub fn packages(text: &str) -> BTreeSet<String> {
    modes(text).into_keys().collect()
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
    fn custom_flags_merge_with_independent_modes_and_last_valid_custom_wins() {
        let text = "com.game,custom=2:0-3|30:4-7,record\ncom.game,fallback\ncom.video,record";
        let options = app_options(text);
        let game = &options["com.game"];
        assert!(game.automatic && game.fallback && game.recording);
        assert_eq!(game.custom, custom::parse("2:0-3|30:4-7").unwrap());
        assert_eq!(packages(text), BTreeSet::from(["com.game".into()]));
        assert!(!options["com.video"].automatic);
        assert!(app_options("com.game,custom=2:0,custom=5:1,record").is_empty());
        assert_eq!(app_options("com.game,custom=2:0\ncom.game,record\ncom.game,custom=10:1\ncom.game,custom=broken")["com.game"].custom,
            custom::parse("10:1").unwrap());
    }
    #[test]
    fn recording_is_independent_and_all_combinations_keep_scheduling_scope() {
        let text = "com.video,record\ncom.game,auto,record\ncom.full,fallback,record\ncom.plain";
        assert_eq!(packages(text), BTreeSet::from(["com.game".into(), "com.full".into(), "com.plain".into()]));
        assert_eq!(modes(text).get("com.full"), Some(&true));
        assert_eq!(app_options(text).get("com.video"), Some(&AppOptions { automatic: false, fallback: false, recording: true, custom: vec![] }));
        for text in ["com.game\ncom.game,record", "com.game,record\ncom.game", "com.game,record,auto"] {
            assert_eq!(app_options(text).get("com.game"), Some(&AppOptions { automatic: true, fallback: false, recording: true, custom: vec![] }));
        }
        for text in ["com.game,record,unknown", "com.game,record,", "com.game:child,record", "bad..name,record"] {
            assert!(app_options(text).is_empty(), "{text}");
        }
    }
    #[test]
    fn fallback_option_is_separate_from_package_and_duplicate_order() {
        for text in ["com.game\ncom.game,fallback", "com.game,fallback\ncom.game"] {
            assert_eq!(modes(text), BTreeMap::from([("com.game".into(), true)]));
            assert_eq!(packages(text), BTreeSet::from(["com.game".into()]));
        }
        assert_eq!(modes("com.game , fallback # 主进程\ncom.light\ncom.bad,fallback,extra\ncom.a:child,fallback"),
            BTreeMap::from([("com.game".into(), true), ("com.light".into(), false)]));
    }
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
