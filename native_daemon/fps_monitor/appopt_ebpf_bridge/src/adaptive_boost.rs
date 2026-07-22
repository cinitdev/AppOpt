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
        let mut ctx = Box::from_raw(ctx);
        ctx.inner.restore();
    }
}

/// 守护启动时恢复上一次异常退出留下的临时调度参数。
#[unsafe(no_mangle)]
pub extern "C" fn appopt_jank_recover() -> c_int {
    platform::recover_stale_overrides()
}

#[cfg(any(target_os = "android", target_os = "linux"))]
mod platform {
    use super::AppOptFrameMetrics;
    use std::cmp::Ordering;
    use std::ffi::c_void;
    use std::fs;
    use std::io::Write;
    use std::mem;
    use std::path::{Path, PathBuf};
    use std::time::{Duration, Instant};

    const SAMPLE_INTERVAL: Duration = Duration::from_millis(900);
    const PULSE_DURATION: Duration = Duration::from_millis(1400);
    const RECOVERY_FILE: &str = "/data/adb/modules/AppOpt/config/jank_boost.restore";
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
        restore_attr: Option<SchedAttr>,
    }

    struct FileOverride {
        path: PathBuf,
        original: String,
        written: String,
        pulse: bool,
    }

    pub struct JankController {
        pkg: String,
        pid: i32,
        baseline_samples: Vec<f64>,
        baseline_fps: f64,
        low_count: u32,
        severe_count: u32,
        smooth_count: u32,
        stopped_count: u32,
        stable_low_count: u32,
        previous_fps: f64,
        boosted: bool,
        last_sample: Option<Instant>,
        pulse_until: Option<Instant>,
        threads: Vec<ThreadOverride>,
        files: Vec<FileOverride>,
    }

    impl JankController {
        pub fn new(pkg: String) -> Self {
            Self {
                pkg,
                pid: -1,
                baseline_samples: Vec::with_capacity(8),
                baseline_fps: 0.0,
                low_count: 0,
                severe_count: 0,
                smooth_count: 0,
                stopped_count: 0,
                stable_low_count: 0,
                previous_fps: 0.0,
                boosted: false,
                last_sample: None,
                pulse_until: None,
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
            self.restore_expired_pulses(now);
            if self
                .last_sample
                .is_some_and(|last| now.duration_since(last) < SAMPLE_INTERVAL)
            {
                return None;
            }
            self.last_sample = Some(now);

            if pid <= 0 || (self.pid > 0 && self.pid != pid) {
                self.restore();
                self.reset_learning();
                self.pid = pid;
            } else if self.pid <= 0 {
                self.pid = pid;
            }

            if !fps.is_finite() || fps <= 1.0 {
                self.stopped_count = self.stopped_count.saturating_add(1);
                if self.stopped_count >= 2 && self.boosted {
                    return Some(if self.restore() {
                        (3, "目标停帧，已恢复增强参数".to_string())
                    } else {
                        (4, "目标停帧，部分增强参数尚未恢复，将继续重试".to_string())
                    });
                }
                return None;
            }
            self.stopped_count = 0;
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

            let low = fps < self.baseline_fps * 0.75 && (irregular || fallback_like);
            let severe = fps < self.baseline_fps * 0.55
                || metrics.is_some_and(|value| {
                    value.median_interval_ns > 0
                        && value.max_interval_ns > value.median_interval_ns.saturating_mul(4)
                });
            if low
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
                let was_boosted = self.boosted;
                self.restore();
                self.baseline_fps = fps;
                self.low_count = 0;
                self.stable_low_count = 0;
                if was_boosted {
                    return Some(if !self.boosted {
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
            if low {
                self.low_count = self.low_count.saturating_add(1);
                self.smooth_count = 0;
                self.severe_count = if severe {
                    self.severe_count.saturating_add(1)
                } else {
                    0
                };
            } else {
                self.low_count = 0;
                self.severe_count = 0;
                self.smooth_count = if irregular {
                    0
                } else {
                    self.smooth_count.saturating_add(1)
                };
                if !self.boosted && !irregular {
                    self.baseline_fps = self.baseline_fps.max(fps * 0.98).min(fps * 1.05);
                }
            }

            if !self.boosted && self.low_count >= 2 {
                let (detail, applied) = self.apply_base_boost(pid);
                if !applied {
                    self.low_count = 0;
                    return Some((
                        4,
                        format!("{} 检测到卡顿，但未调整性能参数：{detail}", self.pkg),
                    ));
                }
                self.boosted = true;
                if severe {
                    let _ = self.apply_frequency_pulse(now);
                }
                return Some((1, format!("{} 连续卡顿，已临时增强：{detail}", self.pkg)));
            }
            if self.boosted && self.severe_count >= 2 {
                let frequency_count = self.apply_frequency_pulse(now);
                self.severe_count = 0;
                if frequency_count > 0 {
                    return Some((
                        2,
                        format!(
                            "持续严重卡顿，已短时提升 {} 个频率接口响应",
                            frequency_count
                        ),
                    ));
                }
                return Some((
                    4,
                    "持续严重卡顿，但当前设备没有可写的 CPU/GPU 频率接口".to_string(),
                ));
            }
            if self.boosted && self.smooth_count >= 3 {
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
            self.low_count = 0;
            self.severe_count = 0;
            self.smooth_count = 0;
            self.stopped_count = 0;
            self.stable_low_count = 0;
            self.previous_fps = 0.0;
        }

        fn apply_base_boost(&mut self, pid: i32) -> (String, bool) {
            self.restore();
            if !self.threads.is_empty() || !self.files.is_empty() {
                return ("上一次增强参数仍在恢复中".to_string(), false);
            }
            let mut uclamp_ok = 0usize;
            let mut latency_ok = 0usize;
            let mut nice_ok = 0usize;
            let mut uclamp_changed = 0usize;
            let mut latency_changed = 0usize;
            for tid in selected_tids(pid) {
                let Some(starttime) = thread_starttime(tid) else {
                    continue;
                };
                // 必须在修改 nice 之前保存调度属性，否则恢复 uclamp 时会把 -5 再写回去。
                let original_attr = sched_getattr(tid);
                let original_nice = unsafe { libc::getpriority(libc::PRIO_PROCESS, tid as u32) };
                let written_nice = original_nice.min(-5);
                let mut written_attr = None;
                let mut restore_attr = None;
                if let Some(original) = original_attr {
                    let mut attr = original;
                    attr.sched_flags |= SCHED_FLAG_UTIL_CLAMP_MIN | SCHED_FLAG_LATENCY_NICE;
                    attr.sched_util_min = attr.sched_util_min.max(512);
                    attr.sched_latency_nice = attr.sched_latency_nice.min(-10);
                    if sched_setattr_verified(tid, &attr, true, true) {
                        uclamp_ok += 1;
                        latency_ok += 1;
                        written_attr = Some(sched_getattr(tid).unwrap_or(attr));
                        restore_attr = Some(restore_attr_for(&attr, original));
                    } else {
                        let _ = sched_setattr(tid, &restore_attr_for(&attr, original));
                        let mut latency_only = attr;
                        latency_only.sched_flags &= !SCHED_FLAG_UTIL_CLAMP_MIN;
                        if sched_setattr_verified(tid, &latency_only, false, true) {
                            latency_ok += 1;
                            written_attr = Some(sched_getattr(tid).unwrap_or(latency_only));
                            restore_attr = Some(restore_attr_for(&latency_only, original));
                        } else {
                            let _ = sched_setattr(tid, &restore_attr_for(&latency_only, original));
                            let mut uclamp_only = attr;
                            uclamp_only.sched_flags &= !SCHED_FLAG_LATENCY_NICE;
                            if sched_setattr_verified(tid, &uclamp_only, true, false) {
                                uclamp_ok += 1;
                                written_attr = Some(sched_getattr(tid).unwrap_or(uclamp_only));
                                restore_attr = Some(restore_attr_for(&uclamp_only, original));
                            } else {
                                // 某些内核会接受调用但不应用字段，失败时主动撤销可能的部分写入。
                                let _ =
                                    sched_setattr(tid, &restore_attr_for(&uclamp_only, original));
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
                    continue;
                }
                self.threads.push(ThreadOverride {
                    tid,
                    starttime,
                    original_nice,
                    written_nice,
                    nice_written,
                    original_attr,
                    written_attr,
                    restore_attr,
                });
                if !sync_recovery_file(&self.files, &self.threads) {
                    let _ = self.restore();
                    return ("无法保存异常恢复状态".to_string(), false);
                }
            }
            let cgroup_uclamp = uclamp_ok == 0
                && [
                    "/dev/cpuctl/top-app/cpu.uclamp.min",
                    "/sys/fs/cgroup/top-app/cpu.uclamp.min",
                    "/sys/fs/cgroup/cpu/top-app/cpu.uclamp.min",
                ]
                .iter()
                .any(|path| self.apply_file_override(Path::new(path), "50", false));
            let latency_sensitive = latency_ok == 0
                && [
                    "/dev/cpuctl/top-app/cpu.uclamp.latency_sensitive",
                    "/sys/fs/cgroup/top-app/cpu.uclamp.latency_sensitive",
                    "/sys/fs/cgroup/cpu/top-app/cpu.uclamp.latency_sensitive",
                ]
                .iter()
                .any(|path| self.apply_file_override(Path::new(path), "1", false));
            let schedtune = uclamp_ok == 0
                && !cgroup_uclamp
                && (self.apply_file_override(
                    Path::new("/dev/stune/top-app/schedtune.boost"),
                    "20",
                    false,
                ) || self.apply_file_override(
                    Path::new("/sys/fs/cgroup/stune/top-app/schedtune.boost"),
                    "20",
                    false,
                ));
            let applied = nice_ok > 0
                || uclamp_changed > 0
                || latency_changed > 0
                || cgroup_uclamp
                || latency_sensitive
                || schedtune;
            let detail = if applied {
                format!(
                    "nice={} uclamp={} cgroup_uclamp={} latency_nice={} latency_sensitive={} schedtune={}",
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

        fn apply_frequency_pulse(&mut self, now: Instant) -> usize {
            self.restore_pulses();
            let mut applied = 0usize;
            for (path, value) in cpu_policy_targets() {
                if self.apply_file_override(&path, &value, true) {
                    applied += 1;
                }
            }
            for (path, value) in gpu_targets() {
                if self.apply_file_override(&path, &value, true) {
                    applied += 1;
                }
            }
            if applied > 0 {
                self.pulse_until = Some(now + PULSE_DURATION);
            }
            applied
        }

        fn apply_file_override(&mut self, path: &Path, value: &str, pulse: bool) -> bool {
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
                pulse,
            });
            if !sync_recovery_file(&self.files, &self.threads) {
                self.files.pop();
                return false;
            }
            if !write_value(path, value) {
                self.files.pop();
                let _ = sync_recovery_file(&self.files, &self.threads);
                return false;
            }
            if fs::read_to_string(path)
                .ok()
                .is_none_or(|current| !value_matches(&current, value))
            {
                let _ = write_value(path, &original);
                self.files.pop();
                let _ = sync_recovery_file(&self.files, &self.threads);
                return false;
            }
            true
        }

        fn restore_expired_pulses(&mut self, now: Instant) {
            if self.pulse_until.is_some_and(|deadline| now >= deadline) {
                self.restore_pulses();
            }
        }

        fn restore_pulses(&mut self) {
            restore_files(&mut self.files, true);
            let _ = sync_recovery_file(&self.files, &self.threads);
            self.pulse_until = None;
        }

        pub fn restore(&mut self) -> bool {
            self.restore_pulses();
            restore_files(&mut self.files, false);
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
                if let (Some(written), Some(restore_attr)) = (item.written_attr, item.restore_attr)
                {
                    if sched_getattr(item.tid)
                        .is_some_and(|current| sched_attr_matches(&current, &written))
                    {
                        if !sched_setattr(item.tid, &restore_attr)
                            || item.original_attr.is_some_and(|original| {
                                sched_getattr(item.tid)
                                    .is_none_or(|current| !sched_attr_matches(&current, &original))
                            })
                        {
                            restored = false;
                        }
                    }
                }
                if !restored {
                    retained_threads.push(item);
                }
            }
            self.threads = retained_threads;
            let _ = sync_recovery_file(&self.files, &self.threads);
            self.boosted = !self.threads.is_empty() || !self.files.is_empty();
            self.low_count = 0;
            self.severe_count = 0;
            self.smooth_count = 0;
            !self.boosted
        }
    }

    impl Drop for JankController {
        fn drop(&mut self) {
            self.restore();
        }
    }

    fn selected_tids(pid: i32) -> Vec<i32> {
        let mut preferred = Vec::new();
        let mut ordinary = Vec::new();
        let Ok(entries) = fs::read_dir(format!("/proc/{pid}/task")) else {
            return vec![pid];
        };
        for entry in entries.flatten() {
            let Some(tid) = entry
                .file_name()
                .to_str()
                .and_then(|value| value.parse::<i32>().ok())
            else {
                continue;
            };
            let name = fs::read_to_string(format!("/proc/{pid}/task/{tid}/comm"))
                .unwrap_or_default()
                .trim()
                .to_ascii_lowercase();
            if is_maintenance_thread(&name) {
                continue;
            }
            if tid == pid || is_render_thread(&name) {
                preferred.push(tid);
            } else {
                ordinary.push(tid);
            }
        }
        preferred.truncate(64);
        let remaining = 64usize.saturating_sub(preferred.len());
        preferred.extend(ordinary.into_iter().take(remaining));
        if preferred.is_empty() {
            preferred.push(pid);
        }
        preferred
    }

    fn is_render_thread(name: &str) -> bool {
        [
            "render",
            "unity",
            "gfx",
            "glthread",
            "vulkan",
            "rhi",
            "game",
            "ue4",
            "thread-shared",
        ]
        .iter()
        .any(|key| name.contains(key))
    }

    fn is_maintenance_thread(name: &str) -> bool {
        [
            "signal catcher",
            "heaptask",
            "gc",
            "finalizer",
            "referencequeue",
            "jit thread",
            "binder:",
        ]
        .iter()
        .any(|key| name.contains(key))
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

    fn restore_attr_for(written: &SchedAttr, original: SchedAttr) -> SchedAttr {
        let mut restore = original;
        // sched_setattr 只有在对应 flag 置位时才会写入 uclamp/latency_nice，
        // 因此恢复时必须带回实际修改过的 flag，不能直接复用原始属性。
        restore.sched_flags |=
            written.sched_flags & (SCHED_FLAG_UTIL_CLAMP_MIN | SCHED_FLAG_LATENCY_NICE);
        restore.sched_util_min = original.sched_util_min;
        restore.sched_latency_nice = original.sched_latency_nice;
        restore
    }

    fn sched_attr_matches(left: &SchedAttr, right: &SchedAttr) -> bool {
        left.sched_util_min == right.sched_util_min
            && left.sched_latency_nice == right.sched_latency_nice
    }

    fn write_value(path: &Path, value: &str) -> bool {
        fs::OpenOptions::new()
            .write(true)
            .open(path)
            .and_then(|mut file| file.write_all(value.as_bytes()))
            .is_ok()
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

    fn restore_files(files: &mut Vec<FileOverride>, pulse_only: bool) {
        let mut retained = Vec::new();
        for item in files.drain(..).rev() {
            if pulse_only && !item.pulse {
                retained.push(item);
                continue;
            }
            if fs::read_to_string(&item.path)
                .ok()
                .is_some_and(|value| value_matches(&value, &item.written))
            {
                if !write_value(&item.path, &item.original)
                    || fs::read_to_string(&item.path)
                        .ok()
                        .is_none_or(|value| !value_matches(&value, &item.original))
                {
                    retained.push(item);
                }
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
        let path = Path::new(RECOVERY_FILE);
        if content.is_empty() {
            let _ = fs::remove_file(path);
            return true;
        }
        let tmp = path.with_extension("restore.tmp");
        if fs::write(&tmp, content).is_ok() && fs::rename(&tmp, path).is_ok() {
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

    fn recover_file_override(target: &str, original: &str, written: &str) -> bool {
        let target = Path::new(target);
        let Ok(current) = fs::read_to_string(target) else {
            return false;
        };
        if !value_matches(&current, written) {
            return true;
        }
        write_value(target, original)
            && fs::read_to_string(target)
                .ok()
                .is_some_and(|value| value_matches(&value, original))
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
        if let (Some(original), Some(written)) = (original_attr, written_attr) {
            if sched_getattr(tid).is_some_and(|current| sched_attr_matches(&current, &written)) {
                let restore = restore_attr_for(&written, original);
                if !sched_setattr(tid, &restore)
                    || sched_getattr(tid)
                        .is_none_or(|current| !sched_attr_matches(&current, &original))
                {
                    restored = false;
                }
            }
        }
        restored
    }

    pub fn recover_stale_overrides() -> i32 {
        let path = Path::new(RECOVERY_FILE);
        let Ok(content) = fs::read_to_string(path) else {
            return 0;
        };
        let mut recovered = 0i32;
        let mut retained = String::new();
        for line in content.lines() {
            let fields = line.split('\t').collect::<Vec<_>>();
            let done = if fields.first() == Some(&"F") && fields.len() == 4 {
                recover_file_override(fields[1], fields[2], fields[3])
            } else if fields.first() == Some(&"T") {
                recover_thread_override(&fields)
            } else if fields.len() == 3 {
                // 兼容旧版仅保存文件覆盖的三列恢复记录。
                recover_file_override(fields[0], fields[1], fields[2])
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
        let _ = write_recovery_content(&retained);
        recovered
    }

    fn thread_starttime(tid: i32) -> Option<u64> {
        let stat = fs::read_to_string(format!("/proc/{tid}/stat")).ok()?;
        let close = stat.rfind(')')?;
        stat.get(close + 1..)?
            .split_whitespace()
            .nth(19)?
            .parse()
            .ok()
    }

    fn cpu_policy_targets() -> Vec<(PathBuf, String)> {
        let Ok(entries) = fs::read_dir("/sys/devices/system/cpu/cpufreq") else {
            return Vec::new();
        };
        let mut policies = entries
            .flatten()
            .filter(|entry| entry.file_name().to_string_lossy().starts_with("policy"))
            .filter_map(|entry| {
                let dir = entry.path();
                let max = fs::read_to_string(dir.join("scaling_max_freq"))
                    .ok()?
                    .trim()
                    .parse::<u64>()
                    .ok()?;
                Some((max, dir.join("scaling_min_freq"), max.to_string()))
            })
            .collect::<Vec<_>>();
        policies.sort_by_key(|(max, _, _)| *max);
        policies
            .into_iter()
            .rev()
            .take(2)
            .map(|(_, path, value)| (path, value))
            .collect()
    }

    fn gpu_targets() -> Vec<(PathBuf, String)> {
        let mut targets = fs::read_dir("/sys/class/devfreq")
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|entry| {
                let dir = entry.path();
                let identity = format!(
                    "{} {}",
                    entry.file_name().to_string_lossy(),
                    fs::read_to_string(dir.join("name")).unwrap_or_default()
                )
                .to_ascii_lowercase();
                if !["gpu", "kgsl", "mali"]
                    .iter()
                    .any(|key| identity.contains(key))
                {
                    return None;
                }
                let max = fs::read_to_string(dir.join("max_freq"))
                    .ok()?
                    .trim()
                    .parse::<u64>()
                    .ok()?;
                Some((dir.join("min_freq"), max.to_string()))
            })
            .collect::<Vec<_>>();
        for (min_path, max_path) in [
            (
                "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
                "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            ),
            (
                "/sys/class/kgsl/kgsl-3d0/min_gpuclk",
                "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            ),
        ] {
            let min_path = PathBuf::from(min_path);
            let Ok(max) = fs::read_to_string(max_path) else {
                continue;
            };
            if !targets.iter().any(|(path, _)| path == &min_path) {
                targets.push((min_path, max.trim().to_string()));
            }
        }
        targets
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
