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
#[cfg(test)]
#[path = "auto_affinity/device_tests.rs"]
mod device_tests;
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
        let selected = match fs::read_to_string(CONFIG) {
            Ok(text) if text.len() <= 64 * 1024 => packages(&text),
            Err(e) if e.kind() == io::ErrorKind::NotFound => BTreeSet::new(),
            _ => {
                self.config_valid = false;
                self.config_stamp = None;
                self.stop("配置不可读，已暂停");
                self.ready = false;
                return false;
            }
        };
        self.config_valid = true;
        if selected == self.selected {
            return false;
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
            format!("version=1\npid={}\nboot_id={}\nstart_ticks={start}\n", std::process::id(), boot.trim())
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
        let (state, detail) = controller.step(sample);
        controller.publish_diagnostics();
        self.publish(pkg, state, &detail);
    }
}

#[cfg(test)]
mod cadence_tests {
    use super::*;

    #[test]
    fn suspended_controller_waits_for_events_without_busy_polling() {
        assert_eq!(Runtime::default().next_sample_delay(), Duration::from_secs(1));
    }

    #[test]
    fn ownership_wakes_between_load_samples_without_waiting_for_placement_dwell() {
        let start = Instant::now();
        assert_eq!(work_delay(start, None, None), Duration::ZERO);
        assert_eq!(work_delay(start, Some(start), Some(start)), Duration::from_millis(250));
        let check = start + Duration::from_millis(250);
        assert_eq!(work_delay(check, Some(start), Some(start)), Duration::ZERO);
        assert_eq!(work_delay(check, Some(start), Some(check)), Duration::from_millis(250));
        assert_eq!(work_delay(start + Duration::from_millis(500), Some(start), Some(check)), Duration::ZERO);
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
#[cfg(test)]
fn thread_stat(pid: i32, tid: i32) -> io::Result<ThreadStat> {
    let stat = qixia_kernel_info::proc::read_stat(format!("/proc/{pid}/task/{tid}/stat"))?;
    Ok(ThreadStat {
        start: stat.start,
        ticks: stat.ticks,
    })
}
fn same_identity(r: &Record) -> io::Result<bool> {
    match fs::read(format!("/proc/{}/task/{}/stat", r.pid, r.tid)) {
        Ok(s) => Ok(parse_stat(&s).is_some_and(|(start, _)| start == r.start)),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e),
    }
}

#[cfg(test)]
fn restore_record(r: &Record) -> io::Result<()> {
    batch::restore(&batch::Entry {
        record: r.clone(),
        previous: None,
        original_cpuset: None,
        owned_root: cpuset::root(),
        inherited_from: None,
    })
}

#[cfg(test)]
fn candidate_pool(
    cores: &[Core],
    original: u64,
    online: u64,
    loads: &[Option<f64>; 64],
    limits: &[Option<f64>; 64],
    measured: super::demand::Demand,
) -> Option<u64> {
    let demand = super::planner::Demand {
        allowed: original & online,
        total: measured.units,
        serial: measured.units,
        parallel: 1,
    };
    let mut budget = super::planner::Budget::new(cores, limits);
    budget.reserve_external(loads);
    let mask = budget.assign(&demand).0;
    (mask != 0 && mask != original & online).then_some(mask)
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "Bounded CPU probe: run explicitly on an Android device with time_in_state"]
    fn device_demand_probe_reads_real_frequency_residency() {
        let pid = std::process::id() as i32;
        let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
        let cores = topology();
        let policies = sampling::policies(&cores).expect("device CPU policies");
        let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) } as f64;
        let mut window = sampling::DemandWindow::default();
        let mut valid = 0;
        let mut before = cpu_times();
        log_info!("device capacities: {:?}", cores);
        for index in 0..7 {
            if index > 0 {
                let work = Instant::now();
                while work.elapsed() < Duration::from_millis(1100) {
                    std::hint::black_box(12345u64.wrapping_mul(6789));
                }
            }
            let stat = thread_stat(pid, tid).unwrap();
            let residency =
                sampling::residency(pid, tid).expect("real thread frequency accounting");
            let after = cpu_times();
            if let Some(demand) = window.push(Instant::now(), stat, residency, &policies, hz) {
                let pool = candidate_pool(
                    &cores,
                    affinity(tid).unwrap(),
                    online_mask().unwrap(),
                    &cpu_loads(&before, &after),
                    &sampling::limits(&policies),
                    demand,
                );
                log_info!(
                    "READ ONLY round={index} work={:.2} busy={:.3} candidate={pool:?}",
                    demand.units,
                    demand.busy
                );
                assert!(
                    demand.units > 0.0
                        && demand.units
                            <= 1.1 * cores.iter().map(|c| c.capacity).max().unwrap() as f64
                );
                assert!(demand.busy > 0.7);
                valid += 1;
            }
            before = after;
        }
        assert!(
            valid >= 2,
            "reliable demand estimates should be available after warmup"
        );
    }
    #[test]
    fn corrupt_recovery_records_are_rejected() {
        assert!(Record::decode("v1 abc 1-2 3 4 5 ff c0").is_some());
        for text in [
            "v1 abc 1-2 3 4 5 03 c0",
            "v1 abc 1-2 3 4 5 ff 0",
            "v1 abc x;id 3 4 5 ff c0",
            "v1 abc 1-2 -3 4 5 ff c0",
        ] {
            assert!(Record::decode(text).is_none());
        }
    }
    #[test]
    fn busy_or_missing_cpu_samples_prevent_a_trial() {
        assert!(cpu_loads(&[], &[]).iter().all(Option::is_none));
        let a = vec![(100, 20); 64];
        let mut b = vec![(200, 100); 64];
        assert!(cpu_loads(&a, &b)[6].unwrap() < 0.21);
        b[6] = (200, 21);
        assert!(cpu_loads(&a, &b)[6].unwrap() > 0.98);
        b[6] = (99, 21);
        assert!(cpu_loads(&a, &b)[6].is_none());
    }
    #[test]
    fn stat_parser_handles_parentheses_and_spaces_in_names() {
        let tail = (0..20)
            .map(|i| {
                if i == 11 {
                    "7"
                } else if i == 12 {
                    "8"
                } else if i == 19 {
                    "99"
                } else {
                    "0"
                }
            })
            .collect::<Vec<_>>()
            .join(" ");
        assert_eq!(
            parse_stat(format!("42 (worker ) name) {tail}").as_bytes()),
            Some((99, 15))
        );
    }
    #[test]
    fn non_utf8_thread_names_preserve_identity_and_reject_reuse() {
        std::thread::spawn(|| {
            let name = b"worker-\xff\0";
            assert_eq!(unsafe { libc::prctl(libc::PR_SET_NAME, name.as_ptr(), 0, 0, 0) }, 0);
            let pid = std::process::id() as i32;
            let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
            let bytes = fs::read(format!("/proc/{pid}/task/{tid}/stat")).unwrap();
            assert!(std::str::from_utf8(&bytes).is_err());
            let (start, _) = parse_stat(&bytes).unwrap();
            let mut record = Record {
                boot: String::new(), token: String::new(), pid, tid, start,
                original: 1, written: 1,
            };
            assert!(same_identity(&record).unwrap());
            record.start += 1;
            assert!(!same_identity(&record).unwrap());
        }).join().unwrap();
    }
    #[test]
    fn restore_checks_identity_and_respects_external_changes() {
        std::thread::spawn(|| {
            let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
            let pid = std::process::id() as i32;
            let original = affinity(tid).unwrap();
            if original.count_ones() < 3 {
                return;
            }
            let selected = original & original.wrapping_neg();
            let other = (original & !selected) & (original & !selected).wrapping_neg();
            let stat = thread_stat(pid, tid).unwrap();
            for written in [selected, selected | other] {
                let record = Record {
                    boot: boot_id().unwrap(),
                    token: "1-2".into(),
                    pid,
                    tid,
                    start: stat.start,
                    original,
                    written,
                };
                write_affinity(tid, written).unwrap();
                let mut stale = record.clone();
                stale.start += 1;
                restore_record(&stale).unwrap();
                assert_eq!(affinity(tid).unwrap(), written);
                restore_record(&record).unwrap();
                cpuset::assert_restored_for_test(original, affinity(tid).unwrap());
                let external = if written == selected { other } else { selected };
                write_affinity(tid, external).unwrap();
                restore_record(&record).unwrap();
                assert_eq!(affinity(tid).unwrap(), external);
                write_affinity(tid, original).unwrap();
            }
        })
        .join()
        .unwrap();
    }
}
