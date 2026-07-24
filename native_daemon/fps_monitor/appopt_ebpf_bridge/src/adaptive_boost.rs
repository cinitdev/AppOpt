// 根据帧时间动态应用并恢复短时性能增强。
use std::ffi::{CStr, CString, c_char, c_double, c_int};
use std::ptr;

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct AppOptFrameMetrics {
    pub fps: c_double,
    pub median_interval_ns: u64,
    pub p95_interval_ns: u64,
    pub max_interval_ns: u64,
    pub frame_count: u32,
    pub flags: u32,
}

pub struct AppOptJankCtx {
    inner: platform::JankController,
    last_event: CString,
}

fn cstring_lossy(value: impl AsRef<str>) -> CString {
    CString::new(value.as_ref().replace('\0', " ")).unwrap_or_else(|_| CString::new("").unwrap())
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_jank_create(pkg: *const c_char) -> *mut AppOptJankCtx {
    if pkg.is_null() {
        return ptr::null_mut();
    }
    let pkg = unsafe { CStr::from_ptr(pkg) }
        .to_string_lossy()
        .trim()
        .to_string();
    if pkg.is_empty() {
        return ptr::null_mut();
    }
    Box::into_raw(Box::new(AppOptJankCtx {
        inner: platform::JankController::new(pkg),
        last_event: cstring_lossy(""),
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_jank_update(
    ctx: *mut AppOptJankCtx,
    pid: c_int,
    fps: c_double,
    metrics: *const AppOptFrameMetrics,
) -> c_int {
    if ctx.is_null() {
        return 0;
    }
    let ctx = unsafe { &mut *ctx };
    let metrics = if metrics.is_null() {
        None
    } else {
        Some(unsafe { &*metrics })
    };
    if let Some((code, message)) = ctx.inner.update(pid, fps, metrics) {
        ctx.last_event = cstring_lossy(message);
        code
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_jank_last_event(ctx: *const AppOptJankCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    unsafe { &*ctx }.last_event.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_jank_stop(ctx: *mut AppOptJankCtx) {
    if ctx.is_null() {
        return;
    }
    unsafe {
        drop(Box::from_raw(ctx));
    }
}

/// 守护启动时恢复上一次异常退出留下的临时调度参数。
#[unsafe(no_mangle)]
pub extern "C" fn appopt_jank_recover() -> c_int {
    platform::recover_stale_overrides() + crate::adaptive_governor::recover_stale_overrides()
}

#[cfg(any(target_os = "android", target_os = "linux"))]
mod platform {
    use super::AppOptFrameMetrics;
    use crate::adaptive_governor::AdaptiveGovernor;
    use std::cmp::Ordering;
    use std::collections::{BTreeMap, BTreeSet};
    use std::ffi::c_void;
    use std::fs;
    use std::io::Write;
    use std::mem;
    use std::os::unix::fs::PermissionsExt;
    use std::path::{Path, PathBuf};
    use std::time::{Duration, Instant};

    const SAMPLE_INTERVAL: Duration = Duration::from_millis(900);
    const MAX_BOOSTED_THREADS: usize = 16;
    const RECOVERY_FILE: &str = "/data/adb/modules/AppOpt/config/boost.restore";
    const SCHED_FLAG_UTIL_CLAMP_MIN: u64 = 0x20;
    const SCHED_FLAG_LATENCY_NICE: u64 = 0x80;

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct SchedAttr {
        size: u32,
        sched_policy: u32,
        sched_flags: u64,
        sched_nice: i32,
        sched_priority: u32,
        sched_runtime: u64,
        sched_deadline: u64,
        sched_period: u64,
        sched_util_min: u32,
        sched_util_max: u32,
        sched_latency_nice: i32,
    }

    struct ThreadOverride {
        tid: i32,
        starttime: u64,
        original_nice: i32,
        written_nice: i32,
        nice_written: bool,
        original_attr: Option<SchedAttr>,
        written_attr: Option<SchedAttr>,
    }

    struct FileOverride {
        path: PathBuf,
        original: String,
        written: String,
    }

    #[derive(Clone, Copy)]
    struct ThreadCpuSample {
        total_ticks: u64,
        starttime: u64,
    }

    pub struct JankController {
        pkg: String,
        pid: i32,
        baseline_samples: Vec<f64>,
        baseline_fps: f64,
        moderate_count: u32,
        smooth_count: u32,
        stopped_count: u32,
        stable_low_count: u32,
        previous_fps: f64,
        jank_level: i32,
        boosted: bool,
        restore_pending: bool,
        thread_boost_attempted: bool,
        thread_sample: Option<BTreeMap<i32, ThreadCpuSample>>,
        governor: AdaptiveGovernor,
        startup_message: Option<String>,
        last_sample: Option<Instant>,
        threads: Vec<ThreadOverride>,
        files: Vec<FileOverride>,
    }

    impl JankController {
        pub fn new(pkg: String) -> Self {
            let mut governor = AdaptiveGovernor::new();
            let startup_message = governor.take_startup_message();
            Self {
                pkg,
                pid: -1,
                baseline_samples: Vec::with_capacity(8),
                baseline_fps: 0.0,
                moderate_count: 0,
                smooth_count: 0,
                stopped_count: 0,
                stable_low_count: 0,
                previous_fps: 0.0,
                jank_level: 0,
                boosted: false,
                restore_pending: false,
                thread_boost_attempted: false,
                thread_sample: None,
                governor,
                startup_message,
                last_sample: None,
                threads: Vec::new(),
                files: Vec::new(),
            }
        }

        pub fn update(
            &mut self,
            pid: i32,
            fps: f64,
            metrics: Option<&AppOptFrameMetrics>,
        ) -> Option<(i32, String)> {
            let now = Instant::now();
            if self
                .last_sample
                .is_some_and(|last| now.duration_since(last) < SAMPLE_INTERVAL)
            {
                return None;
            }
            self.last_sample = Some(now);

            if let Some(message) = self.startup_message.take() {
                return Some((4, message));
            }

            if pid <= 0 {
                if self.pid > 0 || self.jank_level > 0 || self.boosted || self.restore_pending {
                    self.restore();
                    self.reset_learning();
                }
                self.pid = pid;
                return None;
            } else if self.pid > 0 && self.pid != pid {
                self.restore();
                self.reset_learning();
                self.pid = pid;
            } else if self.pid <= 0 {
                self.pid = pid;
            }

            if !fps.is_finite() || fps <= 1.0 {
                self.stopped_count = self.stopped_count.saturating_add(1);
                if self.stopped_count >= 2
                    && (self.jank_level > 0 || self.boosted || self.restore_pending)
                {
                    return Some(if self.restore() {
                        (3, "目标停帧，已恢复增强参数".to_string())
                    } else {
                        (4, "目标停帧，部分增强参数尚未恢复，将继续重试".to_string())
                    });
                }
                return None;
            }
            self.stopped_count = 0;
            let mild_irregular = metrics.is_some_and(|value| {
                value.frame_count >= 6
                    && value.median_interval_ns > 0
                    && (value.p95_interval_ns > value.median_interval_ns.saturating_mul(15) / 10
                        || value.max_interval_ns > value.median_interval_ns.saturating_mul(22) / 10)
            });
            let irregular = metrics.is_some_and(|value| {
                value.frame_count >= 6
                    && value.median_interval_ns > 0
                    && (value.p95_interval_ns > value.median_interval_ns.saturating_mul(18) / 10
                        || value.max_interval_ns > value.median_interval_ns.saturating_mul(28) / 10)
            });
            let fallback_like = metrics.is_none_or(|value| value.flags & 1 != 0);

            if self.baseline_fps <= 0.0 {
                self.baseline_samples.push(fps);
                if self.baseline_samples.len() < 5 {
                    return None;
                }
                self.baseline_samples
                    .sort_by(|left, right| left.partial_cmp(right).unwrap_or(Ordering::Equal));
                self.baseline_fps = self.baseline_samples[self.baseline_samples.len() / 2];
                return Some((4, format!("已学习帧率基线 {:.1} FPS", self.baseline_fps)));
            }

            let ordinary = fps < self.baseline_fps * 0.85 && (mild_irregular || fallback_like);
            let moderate = fps < self.baseline_fps * 0.75 && (irregular || fallback_like);
            let severe = fps < self.baseline_fps * 0.55
                || metrics.is_some_and(|value| {
                    value.median_interval_ns > 0
                        && value.max_interval_ns > value.median_interval_ns.saturating_mul(4)
                });
            if ordinary
                && fallback_like
                && self.previous_fps > 0.0
                && (fps - self.previous_fps).abs() <= (self.previous_fps * 0.05).max(1.0)
            {
                self.stable_low_count = self.stable_low_count.saturating_add(1);
            } else {
                self.stable_low_count = 0;
            }
            self.previous_fps = fps;
            if self.stable_low_count >= 5 {
                let was_active = self.jank_level > 0 || self.boosted || self.restore_pending;
                let restored = self.restore();
                self.baseline_fps = fps;
                self.moderate_count = 0;
                self.stable_low_count = 0;
                if was_active {
                    return Some(if restored {
                        (3, "检测到稳定帧率档位变化，已恢复增强参数".to_string())
                    } else {
                        (
                            4,
                            "检测到稳定帧率档位变化，部分增强参数尚未恢复".to_string(),
                        )
                    });
                }
                return Some((4, format!("已更新帧率基线为 {:.1} FPS", fps)));
            }

            if severe {
                let entering = self.jank_level < 3;
                self.moderate_count = 0;
                self.smooth_count = 0;
                self.jank_level = 3;
                self.restore_pending = false;
                self.governor.set_level(3);
                let (detail, applied) = if let Some(first) = self.thread_sample.take() {
                    self.thread_boost_attempted = true;
                    self.apply_thread_boost(pid, Some(first))
                } else if !self.thread_boost_attempted {
                    self.thread_sample = Some(snapshot_thread_cpu(pid));
                    self.apply_thread_boost(pid, None)
                } else if self.boosted {
                    ("沿用已增强线程".to_string(), true)
                } else {
                    ("线程增强接口不可用".to_string(), false)
                };
                self.boosted |= applied;
                if entering {
                    return Some((
                        2,
                        format!(
                            "{} 严重卡顿，已立即进入重度调速；线程增强：{detail}",
                            self.pkg
                        ),
                    ));
                }
                return None;
            }

            if ordinary {
                self.smooth_count = 0;
                self.moderate_count = if moderate {
                    self.moderate_count.saturating_add(1)
                } else {
                    if self.jank_level < 2 {
                        self.thread_sample = None;
                    }
                    0
                };
                if self.moderate_count == 1
                    && !self.thread_boost_attempted
                    && self.thread_sample.is_none()
                {
                    self.thread_sample = Some(snapshot_thread_cpu(pid));
                }
                if self.moderate_count >= 2 {
                    let entering = self.jank_level < 2;
                    self.jank_level = 2;
                    self.restore_pending = false;
                    self.governor.set_level(2);
                    let (detail, applied) = if let Some(first) = self.thread_sample.take() {
                        self.thread_boost_attempted = true;
                        self.apply_thread_boost(pid, Some(first))
                    } else if self.boosted {
                        ("沿用已增强线程".to_string(), true)
                    } else if self.thread_boost_attempted {
                        ("线程增强接口不可用".to_string(), false)
                    } else {
                        self.thread_boost_attempted = true;
                        self.apply_thread_boost(pid, None)
                    };
                    self.boosted |= applied;
                    if entering {
                        return Some((
                            1,
                            format!("{} 连续卡顿升级为中度档；线程增强：{detail}", self.pkg),
                        ));
                    }
                } else if self.jank_level == 0 {
                    self.jank_level = 1;
                    self.restore_pending = false;
                    self.governor.set_level(1);
                    return Some((
                        1,
                        format!("{} 检测到普通卡顿，已启用轻量 CPU 调速", self.pkg),
                    ));
                }
                return None;
            }

            self.moderate_count = 0;
            if self.jank_level == 0 {
                self.thread_sample = None;
            }
            self.smooth_count = if mild_irregular {
                0
            } else {
                self.smooth_count.saturating_add(1)
            };
            if self.jank_level == 0 && !mild_irregular {
                self.baseline_fps = self.baseline_fps.max(fps * 0.98).min(fps * 1.05);
            }
            if (self.jank_level > 0 || self.boosted || self.restore_pending)
                && self.smooth_count >= 3
            {
                return Some(if self.restore() {
                    (3, "帧率恢复稳定，已恢复增强参数".to_string())
                } else {
                    (
                        4,
                        "帧率恢复稳定，部分增强参数尚未恢复，将继续重试".to_string(),
                    )
                });
            }
            None
        }

        fn reset_learning(&mut self) {
            self.baseline_samples.clear();
            self.baseline_fps = 0.0;
            self.moderate_count = 0;
            self.smooth_count = 0;
            self.stopped_count = 0;
            self.stable_low_count = 0;
            self.previous_fps = 0.0;
            self.jank_level = 0;
            self.thread_boost_attempted = false;
            self.thread_sample = None;
        }

        fn apply_thread_boost(
            &mut self,
            pid: i32,
            first: Option<BTreeMap<i32, ThreadCpuSample>>,
        ) -> (String, bool) {
            let mut uclamp_ok =
                self.threads
                    .iter()
                    .filter(|item| {
                        item.original_attr.zip(item.written_attr).is_some_and(
                            |(original, written)| original.sched_util_min != written.sched_util_min,
                        )
                    })
                    .count();
            let mut latency_ok = self
                .threads
                .iter()
                .filter(|item| {
                    item.original_attr
                        .zip(item.written_attr)
                        .is_some_and(|(original, written)| {
                            original.sched_latency_nice != written.sched_latency_nice
                        })
                })
                .count();
            let mut nice_ok = 0usize;
            let mut uclamp_changed = 0usize;
            let mut latency_changed = 0usize;
            let selected = selected_tids_by_load(pid, first.as_ref());
            let selected_count = selected.len();
            let existing = self
                .threads
                .iter()
                .map(|item| item.tid)
                .collect::<BTreeSet<_>>();
            let original_thread_count = self.threads.len();
            for tid in selected {
                if existing.contains(&tid) {
                    continue;
                }
                let Some(starttime) = thread_starttime(tid) else {
                    continue;
                };
                let original_attr = sched_getattr(tid);
                let original_nice = unsafe { libc::getpriority(libc::PRIO_PROCESS, tid as u32) };
                let written_nice = original_nice.min(-5);
                let planned_attr = original_attr.map(|original| {
                    let mut attr = original;
                    attr.sched_flags |= SCHED_FLAG_UTIL_CLAMP_MIN | SCHED_FLAG_LATENCY_NICE;
                    attr.sched_util_min = attr.sched_util_min.max(512);
                    attr.sched_latency_nice = attr.sched_latency_nice.min(-10);
                    attr
                });
                let attr_planned =
                    original_attr
                        .zip(planned_attr)
                        .is_some_and(|(original, planned)| {
                            original.sched_util_min != planned.sched_util_min
                                || original.sched_latency_nice != planned.sched_latency_nice
                        });
                if original_nice == written_nice && !attr_planned {
                    continue;
                }

                // 恢复记录必须先于系统参数写入，进程即使在 syscall 后立刻退出也能恢复。
                self.threads.push(ThreadOverride {
                    tid,
                    starttime,
                    original_nice,
                    written_nice,
                    nice_written: original_nice != written_nice,
                    original_attr,
                    written_attr: planned_attr,
                });
            }
            if self.threads.len() > original_thread_count
                && !sync_recovery_file(&self.files, &self.threads)
            {
                self.threads.truncate(original_thread_count);
                return ("无法在增强前保存异常恢复状态".to_string(), false);
            }
            let had_pending_threads = self.threads.len() > original_thread_count;

            for index in original_thread_count..self.threads.len() {
                let tid = self.threads[index].tid;
                let original_attr = self.threads[index].original_attr;
                let original_nice = self.threads[index].original_nice;
                let written_nice = self.threads[index].written_nice;
                let planned_attr = self.threads[index].written_attr;
                let mut written_attr = None;
                if let Some(original) = original_attr {
                    let attr = planned_attr.unwrap_or(original);
                    if sched_setattr_verified(tid, &attr, true, true) {
                        uclamp_ok += 1;
                        latency_ok += 1;
                        written_attr = Some(sched_getattr(tid).unwrap_or(attr));
                    } else {
                        let _ = restore_sched_fields(tid, original, attr);
                        let mut latency_only = attr;
                        latency_only.sched_flags &= !SCHED_FLAG_UTIL_CLAMP_MIN;
                        if sched_setattr_verified(tid, &latency_only, false, true) {
                            latency_ok += 1;
                            written_attr = Some(sched_getattr(tid).unwrap_or(latency_only));
                        } else {
                            let _ = restore_sched_fields(tid, original, latency_only);
                            let mut uclamp_only = attr;
                            uclamp_only.sched_flags &= !SCHED_FLAG_LATENCY_NICE;
                            if sched_setattr_verified(tid, &uclamp_only, true, false) {
                                uclamp_ok += 1;
                                written_attr = Some(sched_getattr(tid).unwrap_or(uclamp_only));
                            } else {
                                // 某些内核会接受调用但不应用字段，失败时主动撤销可能的部分写入。
                                let _ = restore_sched_fields(tid, original, uclamp_only);
                            }
                        }
                    }
                }
                // sched_setattr 可能同时覆盖 sched_nice，因此 nice 必须最后写入。
                let nice_written = original_nice != written_nice
                    && unsafe { libc::setpriority(libc::PRIO_PROCESS, tid as u32, written_nice) }
                        == 0
                    && unsafe { libc::getpriority(libc::PRIO_PROCESS, tid as u32) } == written_nice;
                if nice_written {
                    nice_ok += 1;
                }
                self.threads[index].nice_written = nice_written;
                self.threads[index].written_attr = written_attr;
                let mut attr_changed = false;
                if let (Some(original), Some(written)) = (original_attr, written_attr) {
                    if original.sched_util_min != written.sched_util_min {
                        uclamp_changed += 1;
                        attr_changed = true;
                    }
                    if original.sched_latency_nice != written.sched_latency_nice {
                        latency_changed += 1;
                        attr_changed = true;
                    }
                }
                if !nice_written && !attr_changed {
                    self.threads[index].written_attr = None;
                }
            }
            self.threads.retain(|item| {
                item.nice_written
                    || item.original_attr.zip(item.written_attr).is_some_and(
                        |(original, written)| {
                            original.sched_util_min != written.sched_util_min
                                || original.sched_latency_nice != written.sched_latency_nice
                        },
                    )
            });
            if had_pending_threads && !sync_recovery_file(&self.files, &self.threads) {
                let _ = self.restore();
                return ("无法保存异常恢复状态".to_string(), false);
            }
            let cgroup_uclamp = uclamp_ok == 0
                && [
                    "/dev/cpuctl/top-app/cpu.uclamp.min",
                    "/sys/fs/cgroup/top-app/cpu.uclamp.min",
                    "/sys/fs/cgroup/cpu/top-app/cpu.uclamp.min",
                ]
                .iter()
                .any(|path| self.apply_file_override(Path::new(path), "50"));
            let latency_sensitive = latency_ok == 0
                && [
                    "/dev/cpuctl/top-app/cpu.uclamp.latency_sensitive",
                    "/sys/fs/cgroup/top-app/cpu.uclamp.latency_sensitive",
                    "/sys/fs/cgroup/cpu/top-app/cpu.uclamp.latency_sensitive",
                ]
                .iter()
                .any(|path| self.apply_file_override(Path::new(path), "1"));
            let schedtune = uclamp_ok == 0
                && !cgroup_uclamp
                && (self
                    .apply_file_override(Path::new("/dev/stune/top-app/schedtune.boost"), "20")
                    || self.apply_file_override(
                        Path::new("/sys/fs/cgroup/stune/top-app/schedtune.boost"),
                        "20",
                    ));
            let applied = nice_ok > 0
                || uclamp_changed > 0
                || latency_changed > 0
                || cgroup_uclamp
                || latency_sensitive
                || schedtune;
            let detail = if applied {
                format!(
                    "采样线程={} nice={} uclamp={} cgroup_uclamp={} latency_nice={} latency_sensitive={} schedtune={}",
                    selected_count,
                    nice_ok,
                    uclamp_changed,
                    if cgroup_uclamp { "50" } else { "未启用" },
                    latency_changed,
                    if latency_sensitive { "1" } else { "未启用" },
                    if schedtune { "20" } else { "未启用" }
                )
            } else if uclamp_ok > 0 || latency_ok > 0 {
                "线程当前已处于目标增强状态".to_string()
            } else {
                "当前设备没有可用的增强接口".to_string()
            };
            (detail, applied)
        }

        fn apply_file_override(&mut self, path: &Path, value: &str) -> bool {
            let Ok(original) = fs::read_to_string(path) else {
                return false;
            };
            let original = original.trim().to_string();
            if original.is_empty() {
                return false;
            }
            if value_matches(&original, value) {
                return false;
            }
            self.files.push(FileOverride {
                path: path.to_path_buf(),
                original: original.clone(),
                written: value.to_string(),
            });
            if !sync_recovery_file(&self.files, &self.threads) {
                self.files.pop();
                return false;
            }
            if !write_value(path, value) {
                let keep_record = match fs::read_to_string(path) {
                    Ok(current) if value_matches(&current, value) => {
                        !write_value(path, &original)
                            || fs::read_to_string(path)
                                .ok()
                                .is_none_or(|current| !value_matches(&current, &original))
                    }
                    Ok(_) => false,
                    Err(_) => true,
                };
                if !keep_record {
                    self.files.pop();
                }
                let _ = sync_recovery_file(&self.files, &self.threads);
                return false;
            }
            if fs::read_to_string(path)
                .ok()
                .is_none_or(|current| !value_matches(&current, value))
            {
                let restored = match fs::read_to_string(path) {
                    Ok(current) if value_matches(&current, value) => {
                        write_value(path, &original)
                            && fs::read_to_string(path)
                                .ok()
                                .is_some_and(|current| value_matches(&current, &original))
                    }
                    Ok(_) => true,
                    Err(_) => false,
                };
                if restored {
                    self.files.pop();
                }
                let _ = sync_recovery_file(&self.files, &self.threads);
                return false;
            }
            true
        }

        pub fn restore(&mut self) -> bool {
            let governor_restored = self.governor.restore();
            restore_files(&mut self.files);
            let mut retained_threads = Vec::new();
            for item in self.threads.drain(..) {
                let proc_path = PathBuf::from(format!("/proc/{}", item.tid));
                if !proc_path.exists() {
                    continue;
                }
                let Some(current_starttime) = thread_starttime(item.tid) else {
                    retained_threads.push(item);
                    continue;
                };
                if current_starttime != item.starttime {
                    // TID 已被新线程复用，不能把旧线程参数写到新线程上。
                    continue;
                }
                let mut restored = true;
                let current_nice =
                    unsafe { libc::getpriority(libc::PRIO_PROCESS, item.tid as u32) };
                if item.nice_written && current_nice == item.written_nice {
                    let result = unsafe {
                        libc::setpriority(libc::PRIO_PROCESS, item.tid as u32, item.original_nice)
                    };
                    if result != 0
                        || unsafe { libc::getpriority(libc::PRIO_PROCESS, item.tid as u32) }
                            != item.original_nice
                    {
                        restored = false;
                    }
                }
                if let (Some(original), Some(written)) = (item.original_attr, item.written_attr)
                    && !restore_sched_fields(item.tid, original, written)
                {
                    restored = false;
                }
                if !restored {
                    retained_threads.push(item);
                }
            }
            self.threads = retained_threads;
            let recovery_synced = sync_recovery_file(&self.files, &self.threads);
            self.boosted = !self.threads.is_empty() || !self.files.is_empty();
            self.restore_pending = !governor_restored || !recovery_synced;
            self.jank_level = 0;
            self.thread_boost_attempted = self.boosted;
            self.thread_sample = None;
            self.moderate_count = 0;
            self.smooth_count = 0;
            !self.restore_pending && !self.boosted
        }
    }

    impl Drop for JankController {
        fn drop(&mut self) {
            self.restore();
        }
    }

    fn selected_tids_by_load(pid: i32, first: Option<&BTreeMap<i32, ThreadCpuSample>>) -> Vec<i32> {
        let second = snapshot_thread_cpu(pid);

        let mut ranked = second
            .iter()
            .filter_map(|(tid, current)| {
                let previous = first?.get(tid)?;
                (current.starttime == previous.starttime).then_some((
                    *tid,
                    current.total_ticks.saturating_sub(previous.total_ticks),
                ))
            })
            .filter(|(_, delta)| *delta > 0)
            .collect::<Vec<_>>();
        ranked.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.0.cmp(&right.0)));

        let mut selected = Vec::with_capacity(MAX_BOOSTED_THREADS);
        if pid > 0 {
            selected.push(pid);
        }
        for (tid, _) in ranked {
            if selected.len() >= MAX_BOOSTED_THREADS {
                break;
            }
            if tid == pid {
                continue;
            }
            selected.push(tid);
        }
        selected
    }

    fn snapshot_thread_cpu(pid: i32) -> BTreeMap<i32, ThreadCpuSample> {
        let mut snapshot = BTreeMap::new();
        let Ok(entries) = fs::read_dir(format!("/proc/{pid}/task")) else {
            return snapshot;
        };
        for entry in entries.flatten() {
            let Some(tid) = entry
                .file_name()
                .to_str()
                .and_then(|value| value.parse::<i32>().ok())
            else {
                continue;
            };
            if let Some(sample) = read_thread_cpu_sample(pid, tid) {
                snapshot.insert(tid, sample);
            }
        }
        snapshot
    }

    fn read_thread_cpu_sample(pid: i32, tid: i32) -> Option<ThreadCpuSample> {
        let stat = fs::read_to_string(format!("/proc/{pid}/task/{tid}/stat")).ok()?;
        parse_thread_cpu_sample(&stat)
    }

    fn parse_thread_cpu_sample(stat: &str) -> Option<ThreadCpuSample> {
        let close = stat.rfind(')')?;
        let fields = stat
            .get(close + 1..)?
            .split_whitespace()
            .collect::<Vec<_>>();
        let utime = fields.get(11)?.parse::<u64>().ok()?;
        let stime = fields.get(12)?.parse::<u64>().ok()?;
        let starttime = fields.get(19)?.parse::<u64>().ok()?;
        Some(ThreadCpuSample {
            total_ticks: utime.saturating_add(stime),
            starttime,
        })
    }

    fn sched_getattr(tid: i32) -> Option<SchedAttr> {
        let mut attr = SchedAttr {
            size: mem::size_of::<SchedAttr>() as u32,
            ..Default::default()
        };
        let result = unsafe {
            libc::syscall(
                libc::SYS_sched_getattr,
                tid,
                &mut attr as *mut SchedAttr,
                mem::size_of::<SchedAttr>(),
                0,
            )
        };
        (result == 0).then_some(attr)
    }

    fn sched_setattr(tid: i32, attr: &SchedAttr) -> bool {
        unsafe {
            libc::syscall(
                libc::SYS_sched_setattr,
                tid,
                attr as *const SchedAttr as *const c_void,
                0,
            ) == 0
        }
    }

    fn sched_setattr_verified(
        tid: i32,
        attr: &SchedAttr,
        expect_uclamp: bool,
        expect_latency: bool,
    ) -> bool {
        if !sched_setattr(tid, attr) {
            return false;
        }
        let Some(current) = sched_getattr(tid) else {
            return false;
        };
        (!expect_uclamp || current.sched_util_min == attr.sched_util_min)
            && (!expect_latency || current.sched_latency_nice == attr.sched_latency_nice)
    }

    fn restore_sched_fields(tid: i32, original: SchedAttr, written: SchedAttr) -> bool {
        let Some(current) = sched_getattr(tid) else {
            return false;
        };
        let mut restore = current;
        let mut restore_uclamp = false;
        let mut restore_latency = false;
        if written.sched_util_min != original.sched_util_min
            && current.sched_util_min == written.sched_util_min
        {
            restore.sched_flags |= SCHED_FLAG_UTIL_CLAMP_MIN;
            restore.sched_util_min = original.sched_util_min;
            restore_uclamp = true;
        }
        if written.sched_latency_nice != original.sched_latency_nice
            && current.sched_latency_nice == written.sched_latency_nice
        {
            restore.sched_flags |= SCHED_FLAG_LATENCY_NICE;
            restore.sched_latency_nice = original.sched_latency_nice;
            restore_latency = true;
        }
        if !restore_uclamp && !restore_latency {
            return true;
        }
        if !sched_setattr(tid, &restore) {
            return false;
        }
        let Some(after) = sched_getattr(tid) else {
            return false;
        };
        (!restore_uclamp || after.sched_util_min == original.sched_util_min)
            && (!restore_latency || after.sched_latency_nice == original.sched_latency_nice)
    }

    fn write_value(path: &Path, value: &str) -> bool {
        // msm_performance 参数是“逐个 CPU:频率”命令节点，整行写回只会处理第一个参数。
        // 因此目标值和恢复值都必须逐项写入。
        if path
            .to_string_lossy()
            .contains("/msm_performance/parameters/cpu_")
            && value.split_whitespace().count() > 1
        {
            let mut wrote = false;
            for pair in ordered_cpu_pairs(value) {
                if !write_single_value(path, pair) {
                    return false;
                }
                wrote = true;
            }
            return wrote;
        }
        write_single_value(path, value)
    }

    fn ordered_cpu_pairs(value: &str) -> Vec<&str> {
        let mut by_cpu = BTreeMap::new();
        let mut remaining = Vec::new();
        for pair in value.split_whitespace() {
            let Some((cpu, _)) = pair.split_once(':') else {
                remaining.push(pair);
                continue;
            };
            let Ok(cpu) = cpu.parse::<usize>() else {
                remaining.push(pair);
                continue;
            };
            by_cpu.insert(cpu, pair);
        }
        let mut ordered = Vec::with_capacity(by_cpu.len() + remaining.len());
        for pair in by_cpu.values().rev() {
            ordered.push(*pair);
        }
        ordered.extend(remaining);
        ordered
    }

    fn write_single_value(path: &Path, value: &str) -> bool {
        let write = || {
            fs::OpenOptions::new()
                .write(true)
                .open(path)
                .and_then(|mut file| file.write_all(value.as_bytes()))
                .is_ok()
        };
        if write() {
            return true;
        }

        // 部分厂商把频率节点暴露成 0444，Scene daemon 会临时放开权限后再恢复。
        // 这里也只在普通写入失败时尝试，避免改变节点的长期权限。
        let Ok(metadata) = fs::metadata(path) else {
            return false;
        };
        let original_mode = metadata.permissions().mode();
        let writable_mode = original_mode | 0o600;
        if writable_mode == original_mode
            || fs::set_permissions(path, fs::Permissions::from_mode(writable_mode)).is_err()
        {
            return false;
        }
        let written = write();
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(original_mode));
        written
    }

    fn value_matches(current: &str, expected: &str) -> bool {
        let current = current.trim();
        let expected = expected.trim();
        current == expected
            || current
                .parse::<f64>()
                .ok()
                .zip(expected.parse::<f64>().ok())
                .is_some_and(|(left, right)| (left - right).abs() < 0.001)
    }

    fn cpu_pairs_match(current: &str, expected: &str) -> bool {
        let current = current.split_whitespace().collect::<BTreeSet<_>>();
        let expected = expected.split_whitespace().collect::<Vec<_>>();
        !expected.is_empty() && expected.into_iter().all(|pair| current.contains(pair))
    }

    fn override_matches(current: &str, expected: &str, cpu_pairs: bool) -> bool {
        if cpu_pairs {
            cpu_pairs_match(current, expected)
        } else {
            value_matches(current, expected)
        }
    }

    fn restore_files(files: &mut Vec<FileOverride>) {
        let mut retained = Vec::new();
        for item in files.drain(..).rev() {
            let Ok(current) = fs::read_to_string(&item.path) else {
                retained.push(item);
                continue;
            };
            if !value_matches(&current, &item.written) {
                continue;
            }
            if !write_value(&item.path, &item.original)
                || fs::read_to_string(&item.path)
                    .ok()
                    .is_none_or(|value| !value_matches(&value, &item.original))
            {
                retained.push(item);
            }
        }
        retained.reverse();
        *files = retained;
    }

    fn encode_sched_attr(attr: Option<SchedAttr>) -> String {
        let Some(attr) = attr else {
            return "-".to_string();
        };
        format!(
            "{},{},{},{},{},{},{},{},{},{},{}",
            attr.size,
            attr.sched_policy,
            attr.sched_flags,
            attr.sched_nice,
            attr.sched_priority,
            attr.sched_runtime,
            attr.sched_deadline,
            attr.sched_period,
            attr.sched_util_min,
            attr.sched_util_max,
            attr.sched_latency_nice
        )
    }

    fn decode_sched_attr(value: &str) -> Option<SchedAttr> {
        if value == "-" {
            return None;
        }
        let mut fields = value.split(',');
        let attr = SchedAttr {
            size: fields.next()?.parse().ok()?,
            sched_policy: fields.next()?.parse().ok()?,
            sched_flags: fields.next()?.parse().ok()?,
            sched_nice: fields.next()?.parse().ok()?,
            sched_priority: fields.next()?.parse().ok()?,
            sched_runtime: fields.next()?.parse().ok()?,
            sched_deadline: fields.next()?.parse().ok()?,
            sched_period: fields.next()?.parse().ok()?,
            sched_util_min: fields.next()?.parse().ok()?,
            sched_util_max: fields.next()?.parse().ok()?,
            sched_latency_nice: fields.next()?.parse().ok()?,
        };
        fields.next().is_none().then_some(attr)
    }

    fn write_recovery_content(content: &str) -> bool {
        write_recovery_content_at(Path::new(RECOVERY_FILE), content)
    }

    fn write_recovery_content_at(path: &Path, content: &str) -> bool {
        if content.is_empty() {
            let removed = match fs::remove_file(path) {
                Ok(()) => true,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
                Err(_) => false,
            };
            let _ = fs::remove_file(path.with_extension("restore.tmp"));
            return removed;
        }
        let tmp = path.with_extension("restore.tmp");
        let written = fs::OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&tmp)
            .and_then(|mut file| {
                file.write_all(content.as_bytes())?;
                file.sync_all()
            })
            .is_ok();
        if written && fs::rename(&tmp, path).is_ok() {
            true
        } else {
            let _ = fs::remove_file(tmp);
            false
        }
    }

    fn sync_recovery_file(files: &[FileOverride], threads: &[ThreadOverride]) -> bool {
        let mut content = String::new();
        for item in files {
            content.push_str(&format!(
                "F\t{}\t{}\t{}\n",
                item.path.display(),
                item.original,
                item.written
            ));
        }
        for item in threads {
            if !item.nice_written && item.written_attr.is_none() {
                continue;
            }
            content.push_str(&format!(
                "T\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
                item.tid,
                item.starttime,
                item.original_nice,
                item.written_nice,
                if item.nice_written { 1 } else { 0 },
                encode_sched_attr(item.original_attr),
                encode_sched_attr(item.written_attr)
            ));
        }
        write_recovery_content(&content)
    }

    fn recover_file_override(target: &str, original: &str, written: &str, cpu_pairs: bool) -> bool {
        let target = Path::new(target);
        let Ok(current) = fs::read_to_string(target) else {
            return false;
        };
        if !override_matches(&current, written, cpu_pairs) {
            return true;
        }
        write_value(target, original)
            && fs::read_to_string(target)
                .ok()
                .is_some_and(|value| override_matches(&value, original, cpu_pairs))
    }

    fn recover_thread_override(fields: &[&str]) -> bool {
        if fields.len() != 8 {
            return true;
        }
        let Some(tid) = fields[1].parse::<i32>().ok().filter(|tid| *tid > 0) else {
            return true;
        };
        let Some(starttime) = fields[2].parse::<u64>().ok() else {
            return true;
        };
        let proc_path = PathBuf::from(format!("/proc/{tid}"));
        if !proc_path.exists() {
            return true;
        }
        let Some(current_starttime) = thread_starttime(tid) else {
            return false;
        };
        if current_starttime != starttime {
            return true;
        }
        let Some(original_nice) = fields[3].parse::<i32>().ok() else {
            return true;
        };
        let Some(written_nice) = fields[4].parse::<i32>().ok() else {
            return true;
        };
        let nice_written = fields[5] == "1";
        let original_attr = decode_sched_attr(fields[6]);
        let written_attr = decode_sched_attr(fields[7]);
        let mut restored = true;
        if nice_written
            && unsafe { libc::getpriority(libc::PRIO_PROCESS, tid as u32) } == written_nice
        {
            restored =
                unsafe { libc::setpriority(libc::PRIO_PROCESS, tid as u32, original_nice) == 0 }
                    && unsafe { libc::getpriority(libc::PRIO_PROCESS, tid as u32) }
                        == original_nice;
        }
        if let (Some(original), Some(written)) = (original_attr, written_attr)
            && !restore_sched_fields(tid, original, written)
        {
            restored = false;
        }
        restored
    }

    fn recover_stale_file(path: &Path) -> i32 {
        let Ok(content) = fs::read_to_string(path) else {
            return 0;
        };
        let mut recovered = 0i32;
        let mut retained = String::new();
        for line in content.lines() {
            let fields = line.split('\t').collect::<Vec<_>>();
            let done = if fields.first() == Some(&"F") && fields.len() == 4 {
                recover_file_override(fields[1], fields[2], fields[3], false)
            } else if fields.first() == Some(&"P") && fields.len() == 4 {
                recover_file_override(fields[1], fields[2], fields[3], true)
            } else if fields.first() == Some(&"T") {
                recover_thread_override(&fields)
            } else if fields.len() == 3 {
                // 兼容旧版仅保存文件覆盖的三列恢复记录。
                recover_file_override(fields[0], fields[1], fields[2], false)
            } else {
                true
            };
            if done {
                recovered = recovered.saturating_add(1);
            } else {
                retained.push_str(line);
                retained.push('\n');
            }
        }
        let _ = write_recovery_content_at(path, &retained);
        recovered
    }

    pub fn recover_stale_overrides() -> i32 {
        recover_stale_file(Path::new(RECOVERY_FILE))
    }

    fn thread_starttime(tid: i32) -> Option<u64> {
        let stat = fs::read_to_string(format!("/proc/{tid}/stat")).ok()?;
        parse_thread_cpu_sample(&stat).map(|sample| sample.starttime)
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
mod platform {
    use super::AppOptFrameMetrics;

    pub struct JankController;

    impl JankController {
        pub fn new(_pkg: String) -> Self {
            Self
        }
        pub fn update(
            &mut self,
            _pid: i32,
            _fps: f64,
            _metrics: Option<&AppOptFrameMetrics>,
        ) -> Option<(i32, String)> {
            None
        }
        pub fn restore(&mut self) {}
    }

    pub fn recover_stale_overrides() -> i32 {
        0
    }
}
