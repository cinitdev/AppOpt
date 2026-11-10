use super::*;
use std::fs;
use std::io::{self, Write};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};
#[path = "auto_affinity/batch.rs"]
mod batch;
#[path = "auto_affinity/core_history.rs"]
mod core_history;
#[path = "auto_affinity/cpuset.rs"]
pub(in crate::auto_affinity) mod cpuset;
#[path = "auto_affinity/live.rs"]
mod live;
#[path = "auto_affinity/sampling.rs"]
mod sampling;
#[path = "auto_affinity/worker.rs"]
pub(crate) mod worker;
use live::Controller;

const JOURNAL: &str = "/data/adb/modules/QixiaThreads/config/state/auto_affinity.restore";
const LEASE_MS: u64 = 120_000;
const OWNERSHIP_INTERVAL: Duration = Duration::from_millis(250);

fn work_delay(now: Instant, sampled: Option<Instant>, maintained: Option<Instant>) -> Duration {
    let remaining = |last: Option<Instant>, interval: Duration| last.map_or(Duration::ZERO,
        |last| interval.saturating_sub(now.saturating_duration_since(last)));
    remaining(sampled, super::live_policy::SAMPLE_INTERVAL)
        .min(remaining(maintained, OWNERSHIP_INTERVAL))
}

#[derive(Default)]
pub struct Runtime {
    pub selected: BTreeSet<String>,
    fallback_packages: BTreeSet<String>,
    fallback_masks: BTreeMap<String, u64>,
    custom_rules: BTreeMap<String, Vec<custom::Rule>>,
    pub ready: bool,
    config_valid: bool,
    controller: Option<Controller>,
    config_stamp: Option<(u64, Option<std::time::SystemTime>)>,
    status: String,
    last_sample: Option<Instant>,
    last_maintenance: Option<Instant>,
    pending_history: Vec<crate::auto_history::ThreadBatch>,
    pending_core_events: Vec<(String, Vec<crate::auto_history::CoreEvent>)>,
    recovery_package: Option<String>,
}

pub fn lock() -> MutexGuard<'static, Runtime> {
    static RUNTIME: OnceLock<Mutex<Runtime>> = OnceLock::new();
    // 互斥锁中毒的控制器仍可能持有租约，需保留以便恢复。
    RUNTIME
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

pub(crate) fn recovery_cpuset_paths() -> io::Result<BTreeSet<String>> {
    batch::recovery_cpuset_paths()
}

impl Runtime {
    pub(super) fn config_handoff_pending(&self) -> bool {
        fs::metadata(CONFIG).ok().map(|m| (m.len(), m.modified().ok())) != self.config_stamp
    }

    pub fn refresh_config(&mut self) -> bool {
        let stamp = fs::metadata(CONFIG)
            .ok()
            .map(|m| (m.len(), m.modified().ok()));
        if stamp == self.config_stamp {
            return false;
        }
        self.config_stamp = stamp;
        let options = match fs::read_to_string(CONFIG) {
            Ok(text) if text.len() <= 64 * 1024 => app_options(&text),
            Err(e) if e.kind() == io::ErrorKind::NotFound => BTreeMap::new(),
            _ => {
                self.config_valid = false;
                self.config_stamp = None;
                self.stop("配置不可读，已暂停");
                self.ready = false;
                return false;
            }
        };
        let selected: BTreeSet<String> = options.iter().filter(|(_, opt)| opt.automatic).map(|(pkg, _)| pkg.clone()).collect();
        let fallback_packages = options.iter().filter(|(_, opt)| opt.automatic && opt.fallback).map(|(pkg, _)| pkg.clone()).collect();
        let custom_rules = options.into_iter().filter(|(_, opt)| opt.automatic && !opt.custom.is_empty())
            .map(|(pkg, opt)| (pkg, opt.custom)).collect();
        self.config_valid = true;
        if selected == self.selected {
            if self.fallback_packages == fallback_packages && self.custom_rules == custom_rules { return false; }
            self.fallback_packages = fallback_packages;
            self.custom_rules = custom_rules;
            self.last_sample = None;
            return true;
        }
        let reason = if self.controller.as_ref().is_some_and(|controller| !selected.contains(controller.package())) {
            "mode_disabled"
        } else { "mode_changed" };
        if !self.stop_with_reason("模式切换，恢复系统调度", reason) {
            self.config_stamp = None;
            self.ready = false;
            return false;
        }
        self.selected = selected;
        self.fallback_packages = fallback_packages;
        self.custom_rules = custom_rules;
        self.last_sample = None;
        self.ready = false;
        true
    }

    fn publish(&mut self, pkg: &str, state: &str, detail: &str) {
        super::diagnostics::status(pkg, state, detail);
        static IDENTITY: OnceLock<String> = OnceLock::new();
        let identity = IDENTITY.get_or_init(|| {
            let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id").unwrap_or_default();
            let stat = fs::read_to_string("/proc/self/stat").unwrap_or_default();
            let start = stat.rsplit_once(") ").and_then(|(_, tail)| tail.split_whitespace().nth(19)).unwrap_or("0");
            format!("version=1\nfallback_version=1\nrecording_version=1\ncustom_version=1\npid={}\nboot_id={}\nstart_ticks={start}\n", std::process::id(), boot.trim())
        });
        let value = format!("{identity}package={pkg}\nstate={state}\ndetail={detail}\n");
        if self.status == value {
            return;
        }
        // 即使运行状态未变化，发布失败后也要重试。
        if atomic_write(STATUS, &value, false).is_ok() { self.status = value.clone(); }
        let level = if matches!(state, "recovery_pending" | "external_policy" | "paused") {
            crate::event_log::Level::Warning
        } else {
            crate::event_log::Level::Info
        };
        crate::event_log::emit(
            level,
            format_args!("[auto] 状态更新: pkg={pkg} state={state}\n{detail}"),
        );
    }

    pub fn wants_allocation(&self, pkg: &str) -> bool {
        self.selected.contains(pkg)
    }

    /// 静态规则循环解析一次后交接；自动分配线程不反复读取整份规则。
    pub(crate) fn update_fallback_masks(&mut self, masks: BTreeMap<String, u64>) {
        if self.fallback_masks != masks {
            self.fallback_masks = masks;
            self.last_sample = None;
        }
    }

    pub fn next_sample_delay(&self) -> Duration {
        // 暂停、配置中或校准中的目标没有控制器；文件事件仍可唤醒工作线程，
        // 但不能因此以 20 Hz 的频率重试恢复。
        if self.controller.is_none() { return Duration::from_secs(1); }
        work_delay(Instant::now(), self.last_sample, self.last_maintenance)
    }
    pub(crate) fn take_history(&mut self) -> Option<crate::auto_history::ThreadBatch> {
        if !self.pending_history.is_empty() {
            Some(self.pending_history.remove(0))
        } else {
            self.controller.as_mut().and_then(Controller::take_history)
        }
    }
    pub(crate) fn take_core_events(&mut self) -> Vec<(String, Vec<crate::auto_history::CoreEvent>)> {
        let mut events = std::mem::take(&mut self.pending_core_events);
        if let Some(controller) = self.controller.as_mut() {
            let pending = controller.take_core_events();
            if !pending.is_empty() { events.push((controller.package().to_owned(), pending)); }
        }
        events
    }
    pub fn stop(&mut self, reason: &str) -> bool {
        let code = match reason {
            "守护进程退出" => "shutdown",
            "配置不可读，已暂停" => "config_unavailable",
            "模式切换，恢复系统调度" => "mode_changed",
            "手动校准期间暂停自动分配" => "calibration",
            "等待原规则恢复或配置恢复" => "paused",
            _ => "foreground_left",
        };
        self.stop_with_reason(reason, code)
    }
    pub(crate) fn stop_with_reason(&mut self, reason: &str, code: &str) -> bool {
        self.last_sample = None;
        self.last_maintenance = None;
        if let Some(mut controller) = self.controller.take() {
            let pkg = controller.package().to_owned();
            let restored = controller.release(code);
            if let Some(history) = controller.take_history() { self.pending_history.push(history); }
            let events = controller.take_core_events();
            if !events.is_empty() { self.pending_core_events.push((pkg.clone(), events)); }
            self.recovery_package = (!restored).then(|| pkg.clone());
            self.publish(
                &pkg,
                if restored { "idle" } else { "recovery_pending" },
                if restored {
                    reason
                } else {
                    "恢复尚未完成，已停止调整"
                },
            );
            restored
        } else {
            let mut events = core_history::EventLog::default();
            if let Some(package) = self.recovery_package.as_deref() {
                events.enable(crate::auto_history::core_events_for(package));
            }
            let restored = batch::recover_observed(None, &mut events).is_ok();
            if !restored { events.record((0, 0, 0), "error", "unknown", None, None, "recovery_failed"); }
            if let Some(package) = self.recovery_package.as_ref() {
                let mut pending = events.take();
                for event in &mut pending {
                    if event.reason == "released" { event.reason = code.into(); }
                }
                if !pending.is_empty() { self.pending_core_events.push((package.clone(), pending)); }
            }
            if restored {
                if let Some(package) = self.recovery_package.take() {
                    self.publish(&package, "idle", reason);
                } else if self.status.is_empty() {
                    // 持久化状态可能属于上一轮守护进程。
                    // 只有恢复确实成功后才能替换该状态。
                    self.publish("", "idle", reason);
                }
            } else if self.status.is_empty() {
                self.recovery_package = Some(String::new());
                self.publish("", "recovery_pending", "恢复尚未完成，已停止调整");
            }
            restored
        }
    }

    pub fn tick(&mut self, pkg: &str, sample: Option<Sample>) {
        // 静态规则循环负责配置交接。在处理禁用或配置修改期间，
        // 不能继续执行缓存中的接管操作。
        if self.config_handoff_pending() {
            // 等待静态规则循环交接，不再写入旧计划。
            return;
        }
        if !self.config_valid || !self.ready || !self.wants_allocation(pkg) {
            self.stop("等待原规则恢复或配置恢复");
            return;
        }
        // 写入前再次验证，包括屏幕状态和本次启动身份。
        let foreground =
            fs::read_to_string("/data/adb/modules/QixiaThreads/config/foreground_task.state")
                .ok()
                .zip(boot_id().ok())
                .and_then(|(raw, boot)| {
                    super::foreground_package(&raw, crate::elapsed_realtime_ms(), &boot)
                });
        if foreground.as_deref() != Some(pkg) {
            self.stop("目标离开前台，恢复系统调度");
            return;
        }
        if fs::read_to_string("/data/adb/modules/QixiaThreads/config/calibrate.state")
            .ok()
            .is_some_and(|s| s.trim_start().starts_with("sampling "))
        {
            self.stop("手动校准期间暂停自动分配");
            return;
        }
        if self.controller.as_ref().is_none_or(|c| c.package() != pkg) {
            self.stop("前台目标切换");
            if recover_journal(None).is_err() {
                if crate::auto_history::core_events_for(pkg) {
                    let mut history = core_history::EventLog::default();
                    history.enable(true);
                    history.record((0, 0, 0), "error", "unknown", None, None, "recovery_failed");
                    self.pending_core_events.push((pkg.into(), history.take()));
                }
                self.publish(pkg, "recovery_pending", "上次恢复未完成，保持系统调度");
                return;
            }
            self.controller = Some(Controller::new(pkg));
        }
        let fallback_enabled = self.fallback_packages.contains(pkg);
        let fallback_mask = if fallback_enabled { self.fallback_masks.get(pkg).copied().unwrap_or(0) } else { 0 };
        if let Some(controller) = self.controller.as_mut() {
            controller.set_fallback(fallback_mask);
            controller.set_custom(self.custom_rules.get(pkg).map(Vec::as_slice).unwrap_or_default());
        }
        let now = Instant::now();
        if self.last_sample.is_some_and(|last| now.saturating_duration_since(last) < super::live_policy::SAMPLE_INTERVAL) {
            if self.last_maintenance.is_none_or(|last| now.saturating_duration_since(last) >= OWNERSHIP_INTERVAL) {
                self.last_maintenance = Some(now);
                if let Some(controller) = self.controller.as_mut() { controller.maintain(); }
            }
            return;
        }
        self.last_sample = Some(Instant::now());
        self.last_maintenance = self.last_sample;
        let Some(controller) = self.controller.as_mut() else {
            return;
        };
        let (state, mut detail) = controller.step(sample);
        if fallback_enabled && fallback_mask == 0 {
            detail.push_str(" · 主进程兜底规则缺失，请选择兜底核心");
        }
        controller.publish_diagnostics();
        self.publish(pkg, state, &detail);
    }
}


#[derive(Clone, Debug)]
struct Record {
    boot: String,
    token: String,
    pid: i32,
    tid: i32,
    start: u64,
    original: u64,
    written: u64,
}
impl Record {
    fn encode(&self) -> String {
        format!(
            "v1 {} {} {} {} {} {:x} {:x}\n",
            self.boot, self.token, self.pid, self.tid, self.start, self.original, self.written
        )
    }
    fn decode(text: &str) -> Option<Self> {
        Self::decode_fields(text, false)
    }
    fn decode_fields(text: &str, cpuset_owner: bool) -> Option<Self> {
        let p: Vec<_> = text.split_whitespace().collect();
        if p.len() != 8
            || p[0] != "v1"
            || !p[1].bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
            || !p[2].bytes().all(|b| b.is_ascii_digit() || b == b'-')
        {
            return None;
        }
        let r = Self {
            boot: p[1].into(),
            token: p[2].into(),
            pid: p[3].parse().ok()?,
            tid: p[4].parse().ok()?,
            start: p[5].parse().ok()?,
            original: u64::from_str_radix(p[6], 16).ok()?,
            written: u64::from_str_radix(p[7], 16).ok()?,
        };
        (r.pid > 0
            && r.tid > 0
            && r.start > 0
            && r.written != 0
            && r.original != 0
            && (cpuset_owner || (r.written & !r.original == 0 && r.original != r.written)))
            .then_some(r)
    }
}

fn atomic_write(path: &str, value: &str, durable: bool) -> io::Result<()> {
    let tmp = format!("{path}.{}.tmp", std::process::id());
    let result = (|| {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(value.as_bytes())?;
        if durable {
            f.sync_all()?;
        }
        fs::rename(&tmp, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(tmp);
    }
    result
}
fn boot_id() -> io::Result<String> {
    fs::read_to_string("/proc/sys/kernel/random/boot_id").map(|s| s.trim().into())
}
fn online_mask() -> Option<u64> {
    crate::CpuMask::parse(
        fs::read_to_string("/sys/devices/system/cpu/online")
            .ok()?
            .trim(),
    )?
    .to_low64()
}
pub(in crate::auto_affinity) fn affinity(tid: i32) -> io::Result<u64> {
    crate::affinity::read_allowed_mask_syscall(tid)?
        .to_low64()
        .filter(|m| *m != 0)
        .ok_or_else(|| io::Error::other("不支持的 CPU 范围"))
}
fn write_affinity(tid: i32, mask: u64) -> io::Result<()> {
    crate::affinity::set_affinity(tid, &crate::CpuMask::from_low64(mask))
}

#[derive(Clone, Copy, Default)]
struct ThreadStat {
    start: u64,
    ticks: u64,
}
fn parse_stat(bytes: &[u8]) -> Option<(u64, u64)> {
    let stat = qixia_kernel_info::proc::parse_stat(bytes)?;
    Some((stat.start, stat.ticks))
}
fn same_identity(r: &Record) -> io::Result<bool> {
    match fs::read(format!("/proc/{}/task/{}/stat", r.pid, r.tid)) {
        Ok(s) => Ok(parse_stat(&s).is_some_and(|(start, _)| start == r.start)),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e),
    }
}


pub fn recover_journal(token: Option<&str>) -> io::Result<()> {
    batch::recover(token)
}

// 子进程在父进程的私有管道上阻塞，且只有一次有界超时。
// 不使用心跳、Shell 轮询、唤醒锁或定期磁盘写入。SIGKILL 也会导致 EOF，
// 因此控制器异常死亡不会留下永久限制。
pub fn run_lease_guard(token: &str) -> io::Result<()> {
    if token.is_empty() || !token.bytes().all(|b| b.is_ascii_digit() || b == b'-') {
        return Err(io::Error::other("无效恢复令牌"));
    }
    let start = Instant::now();
    loop {
        let remaining = Duration::from_millis(LEASE_MS + 10_000).saturating_sub(start.elapsed());
        if remaining.is_zero() {
            break;
        }
        let mut fd = libc::pollfd {
            fd: 0,
            events: libc::POLLIN | libc::POLLHUP,
            revents: 0,
        };
        let rc = unsafe {
            libc::poll(
                &mut fd,
                1,
                remaining.as_millis().min(i32::MAX as u128) as i32,
            )
        };
        if rc >= 0 {
            break;
        }
        if io::Error::last_os_error().kind() != io::ErrorKind::Interrupted {
            break;
        }
    }
    let mut result = Ok(());
    // 父进程崩溃可能留下许多继承限制的子进程。每轮最多发现 512 个身份，
    // 通过有界的后续处理清理正常线程池，
    // 避免权限失败或持续派生进程造成忙循环。
    for _ in 0..8 {
        result = recover_journal(Some(token));
        if !result.as_ref().is_err_and(|error| error.kind() == io::ErrorKind::WouldBlock) { break; }
    }
    result
}

fn topology() -> Vec<Core> {
    super::topology::read_cores()
}
fn cpu_times() -> Vec<(u64, u64)> {
    let mut result = vec![(0, 0); 64];
    if let Ok(text) = fs::read_to_string("/proc/stat") {
        for line in text.lines() {
            let mut p = line.split_whitespace();
            let Some(id) = p
                .next()
                .and_then(|s| s.strip_prefix("cpu"))
                .and_then(|s| s.parse::<usize>().ok())
                .filter(|id| *id < 64)
            else {
                continue;
            };
            // guest 时间已经包含在 user/nice 中。
            let v: Vec<u64> = p.take(8).filter_map(|s| s.parse().ok()).collect();
            if v.len() >= 5 {
                result[id] = (v.iter().sum(), v[3] + v[4]);
            }
        }
    }
    result
}
fn cpu_loads(before: &[(u64, u64)], after: &[(u64, u64)]) -> [Option<f64>; 64] {
    std::array::from_fn(|id| {
        let (a, b) = (before.get(id)?, after.get(id)?);
        let total = b.0.checked_sub(a.0)?;
        let idle = b.1.checked_sub(a.1)?;
        (total > 0 && idle <= total).then(|| 1.0 - idle as f64 / total as f64)
    })
}
