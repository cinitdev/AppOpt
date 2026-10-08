//! 可选的前台历史记录；本模块不修改亲和性，也不启动校准。
//! 关闭记录后只保留配置事件监听器。
mod core_events;
#[cfg(any(target_os = "android", target_os = "linux"))]
mod runtime;
mod storage;
mod window;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) use runtime::{
    core_events_for, current_core_session, publish_core_events, publish_fps, publish_threads,
    recording_for, recording_package, start, wants_threads,
};

pub(crate) const CONFIG: &str = "/data/adb/modules/QixiaThreads/config/calib_policy.conf";
pub(crate) const STATUS: &str = "/data/adb/modules/QixiaThreads/config/auto_history.state";

fn config_enabled(text: &str) -> bool {
    let mut enabled = None;
    let mut version = None;
    for line in text.lines() {
        let line = line.split('#').next().unwrap_or_default().trim();
        if line.is_empty() {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            return false;
        };
        if key.trim() == "auto_history_version" {
            if version.is_some() || value.trim() != "1" {
                return false;
            }
            version = Some(1);
        } else if key.trim() == "auto_history_enabled" {
            // 重复、损坏或包含未知值的设置一律按关闭处理。
            if enabled.is_some() || !matches!(value.trim(), "0" | "1") {
                return false;
            }
            enabled = Some(value.trim() == "1");
        }
    }
    version == Some(1) && enabled == Some(true)
}

fn eligible_target(
    config: &str,
    calibration: &str,
    foreground: &str,
    selected: &str,
    now_ms: u64,
    boot: &str,
) -> Option<String> {
    if !config_enabled(config) || calibration.trim_start().starts_with("sampling ") {
        return None;
    }
    let package = crate::auto_affinity::foreground_package(foreground, now_ms, boot)?;
    crate::auto_affinity::packages(selected)
        .contains(&package)
        .then_some(package)
}

#[derive(Clone, Debug)]
pub(crate) struct ThreadSample {
    pub pid: i32,
    pub tid: i32,
    pub start: u64,
    pub name: String,
    pub percent: f64,
}

#[derive(Default)]
pub(crate) struct ThreadBatch {
    pub package: String,
    pub timestamp_ms: u64,
    pub threads: Vec<ThreadSample>,
    pub changes: Vec<((i32, i32, u64), u64)>,
}

/// `source` 描述允许 CPU 掩码的归属；`running_cpu` 只表示
/// 最近一次采样观察到的 CPU，不是完整的迁核轨迹。
#[derive(Clone, Debug)]
pub(crate) struct CoreEvent {
    pub timestamp_ms: u64,
    pub pid: i32,
    pub tid: i32,
    pub start: u64,
    pub name: String,
    pub kind: String,
    pub source: String,
    pub before: Option<u64>,
    pub after: Option<u64>,
    pub running_cpu: Option<u32>,
    pub average: Option<f64>,
    pub reason: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    const ENABLED: &str = "auto_history_version=1\nauto_history_enabled=1";
    const DISABLED: &str = "auto_history_version=1\nauto_history_enabled=0";
    #[test]
    fn recording_is_strictly_opt_in() {
        for text in [
            "",
            "enabled=0",
            "enabled=1",
            DISABLED,
            "auto_history_enabled=1",
            "auto_history_version=2\nauto_history_enabled=1",
            "auto_history_version=1\nauto_history_version=1\nauto_history_enabled=1",
            "auto_history_version=1\nauto_history_enabled=yes",
            "auto_history_version=1\nauto_history_enabled=1\nauto_history_enabled=0",
            "auto_history_version=1\nauto_history_enabled=0\nauto_history_enabled=1",
            "auto_history_version=1\nauto_history_enabled=1\nbroken",
        ] {
            assert!(!config_enabled(text), "{text}");
        }
        assert!(config_enabled(ENABLED));
        assert!(config_enabled("version=2\n# opt-in\nauto_history_version = 1\nauto_history_enabled = 1 # on\ncpuset_name=Custom\nenabled=0\ndetected_all=0-7\n"));
    }
    #[test]
    fn recording_requires_enabled_selected_foreground_and_no_calibration() {
        let foreground = "status=ok\ninteractive=1\nfocused_visible=1\nboot_id=abc\nupdated_elapsed_ms=1000\nfocused_package=com.game\n";
        assert_eq!(
            eligible_target(ENABLED, "idle", foreground, "com.game", 2000, "abc"),
            Some("com.game".into())
        );
        assert!(
            eligible_target(DISABLED, "idle", foreground, "com.game", 2000, "abc").is_none()
        );
        assert!(eligible_target(
            ENABLED,
            "sampling com.game",
            foreground,
            "com.game",
            2000,
            "abc"
        )
        .is_none());
        assert!(
            eligible_target(ENABLED, "idle", foreground, "com.other", 2000, "abc").is_none()
        );
        assert!(eligible_target(
            ENABLED,
            "idle",
            &foreground.replace("interactive=1", "interactive=0"),
            "com.game",
            2000,
            "abc"
        )
        .is_none());
        assert!(
            eligible_target(ENABLED, "idle", foreground, "com.game", 30_000, "abc").is_none()
        );
    }
}
