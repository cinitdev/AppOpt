//! 仅在前台为活跃线程分配 QixiaThreads 自有 cpuset，不写入频率、
//! 优先级或 core_ctl。负载采样不依赖 FPS 采集器。
use std::collections::{BTreeMap, BTreeSet};
mod accounting;
pub mod custom;
pub(crate) mod calibration;
mod demand;
pub(crate) mod diagnostics;
mod live_policy;
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
