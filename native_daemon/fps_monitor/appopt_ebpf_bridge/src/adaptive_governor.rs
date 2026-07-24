//! 基于 sched_switch 利用率的短时 CPU 调速器。
//!
//! 该模块只在用户明确为应用开启卡顿增强且应用位于前台时工作。正常状态
//! 不挂载 eBPF，也不轮询 `/proc/stat`；进入卡顿档后才按需启动采样后端。

use std::sync::{
    Arc, Condvar, Mutex,
    atomic::{AtomicBool, AtomicI32, Ordering},
};
use std::thread::{self, JoinHandle};
use std::time::Duration;

#[cfg(any(target_os = "android", target_os = "linux"))]
mod platform {
    use super::*;
    use aya::{
        Ebpf, Pod,
        maps::{MapData, PerCpuArray},
        programs::TracePoint,
    };
    use std::collections::{BTreeMap, BTreeSet};
    use std::convert::TryInto;
    use std::fs;
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;
    use std::path::{Path, PathBuf};
    use std::time::Instant;

    const SAMPLE_INTERVAL: Duration = Duration::from_millis(320);
    const PROC_INTERVAL: Duration = Duration::from_millis(500);
    const RESTORE_WAIT: Duration = Duration::from_millis(1200);
    const EBPF_EMPTY_SAMPLE_LIMIT: u32 = 3;
    const RESTORE_FILE: &str = "/data/adb/modules/AppOpt/config/adaptive_governor.restore";

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct CpuUtilSample {
        last_ts: u64,
        busy_ns: u64,
        total_ns: u64,
    }

    unsafe impl Pod for CpuUtilSample {}

    #[derive(Clone)]
    struct PolicyTarget {
        min_path: PathBuf,
        available: Vec<u64>,
        min_freq: u64,
        max_freq: u64,
        cpus: Vec<usize>,
        original: String,
        last_written: String,
    }

    #[derive(Clone, Copy)]
    struct CpuPairState {
        original: u64,
        last_written: u64,
    }

    struct VendorTarget {
        path: PathBuf,
        cpus: BTreeMap<usize, CpuPairState>,
    }

    enum UtilBackend {
        Ebpf {
            _bpf: Box<Ebpf>,
            util: Box<PerCpuArray<MapData, CpuUtilSample>>,
            previous: Vec<(u64, u64)>,
        },
        Proc {
            previous: Vec<CpuTimes>,
        },
    }

    #[derive(Clone, Copy)]
    struct CpuTimes {
        id: usize,
        total: u64,
        idle: u64,
    }

    #[derive(Default)]
    struct UtilSample {
        values: Vec<u32>,
        total_delta: u64,
        busy_delta: u64,
    }

    struct GovernorWorker {
        backend: Option<UtilBackend>,
        policies: Vec<PolicyTarget>,
        vendor: Option<VendorTarget>,
        shared: Arc<Shared>,
        ebpf_empty_samples: u32,
    }

    struct Shared {
        stop: AtomicBool,
        level: AtomicI32,
        applied_level: AtomicI32,
        restore_ok: AtomicBool,
        wake: Mutex<()>,
        condvar: Condvar,
        status: Mutex<String>,
    }

    pub struct AdaptiveGovernor {
        shared: Arc<Shared>,
        thread: Option<JoinHandle<()>>,
        startup: Option<String>,
    }

    impl AdaptiveGovernor {
        pub fn new() -> Self {
            let _ = recover_stale();
            let policies = policy_targets();
            let vendor = vendor_target();
            let policy_note = if policies.is_empty() {
                "没有可用 CPU policy 节点".to_string()
            } else if vendor.is_some() {
                format!("CPU policy 数={}，高通节点按 CPU 独立恢复", policies.len())
            } else {
                format!("CPU policy 数={}，使用通用 cpufreq", policies.len())
            };
            let startup = format!(
                "CPU 调速器按需启动；{}；正常状态不挂载采样器，GPU 由系统 Power HAL 管理",
                policy_note
            );
            let shared = Arc::new(Shared {
                stop: AtomicBool::new(false),
                level: AtomicI32::new(0),
                applied_level: AtomicI32::new(0),
                restore_ok: AtomicBool::new(true),
                wake: Mutex::new(()),
                condvar: Condvar::new(),
                status: Mutex::new(startup.clone()),
            });
            let worker_shared = Arc::clone(&shared);
            let thread = thread::Builder::new()
                .name("appopt-cpu-governor".to_string())
                .spawn(move || {
                    GovernorWorker {
                        backend: None,
                        policies,
                        vendor,
                        shared: worker_shared,
                        ebpf_empty_samples: 0,
                    }
                    .run()
                })
                .ok();
            Self {
                shared,
                thread,
                startup: Some(startup),
            }
        }

        pub fn set_level(&self, level: i32) {
            let _guard = self.shared.wake.lock().ok();
            self.shared
                .level
                .store(level.clamp(0, 3), Ordering::Release);
            self.shared.condvar.notify_all();
        }

        pub fn restore(&self) -> bool {
            if self.thread.is_none() {
                return true;
            }
            {
                let _guard = self.shared.wake.lock().ok();
                self.shared.applied_level.store(-1, Ordering::Release);
                self.shared.level.store(0, Ordering::Release);
                self.shared.condvar.notify_all();
            }
            let deadline = Instant::now() + RESTORE_WAIT;
            let mut guard = match self.shared.wake.lock() {
                Ok(guard) => guard,
                Err(_) => return false,
            };
            while self.shared.applied_level.load(Ordering::Acquire) != 0 {
                let now = Instant::now();
                if now >= deadline {
                    return false;
                }
                let Ok((next, timeout)) = self
                    .shared
                    .condvar
                    .wait_timeout(guard, deadline.saturating_duration_since(now))
                else {
                    return false;
                };
                guard = next;
                if timeout.timed_out() && self.shared.applied_level.load(Ordering::Acquire) != 0 {
                    return false;
                }
            }
            self.shared.restore_ok.load(Ordering::Acquire)
        }

        pub fn take_startup_message(&mut self) -> Option<String> {
            self.startup.take()
        }
    }

    impl Drop for AdaptiveGovernor {
        fn drop(&mut self) {
            let _ = self.restore();
            {
                let _guard = self.shared.wake.lock().ok();
                self.shared.stop.store(true, Ordering::Release);
                self.shared.condvar.notify_all();
            }
            if let Some(thread) = self.thread.take() {
                let _ = thread.join();
            }
        }
    }

    impl GovernorWorker {
        fn run(mut self) {
            let mut last_level = 0;
            let mut last_sample = Instant::now();
            let mut latest_util = Vec::new();
            loop {
                if self.shared.stop.load(Ordering::Acquire) {
                    let restored = self.restore();
                    self.backend = None;
                    self.publish_restore(restored);
                    break;
                }

                let level = self.shared.level.load(Ordering::Acquire);
                if level == 0 {
                    let restored = self.restore();
                    self.backend = None;
                    latest_util.clear();
                    self.ebpf_empty_samples = 0;
                    last_level = 0;
                    self.publish_restore(restored);
                    self.wait_for_wake(None, 0);
                    continue;
                }

                if self.backend.is_none() {
                    self.refresh_originals();
                    let (backend, label) = load_backend();
                    self.backend = Some(backend);
                    self.ebpf_empty_samples = 0;
                    last_sample = Instant::now();
                    println!("[boost] CPU 利用率采样器已按需启动：{label}");
                }

                if level != last_level {
                    self.apply_util(&latest_util, level);
                    self.publish_level(level);
                    last_level = level;
                }

                let interval = match self.backend {
                    Some(UtilBackend::Ebpf { .. }) => SAMPLE_INTERVAL,
                    Some(UtilBackend::Proc { .. }) => PROC_INTERVAL,
                    None => PROC_INTERVAL,
                };
                if last_sample.elapsed() >= interval {
                    let sample = self.sample_util();
                    if matches!(self.backend, Some(UtilBackend::Ebpf { .. }))
                        && (sample.total_delta == 0 || sample.busy_delta == 0)
                    {
                        self.ebpf_empty_samples = self.ebpf_empty_samples.saturating_add(1);
                        if self.ebpf_empty_samples >= EBPF_EMPTY_SAMPLE_LIMIT {
                            let reason = if sample.total_delta == 0 {
                                "没有调度时间增量"
                            } else {
                                "持续没有忙碌时间增量"
                            };
                            let mut previous = Vec::new();
                            let _ = read_proc_stat(&mut previous);
                            self.backend = Some(UtilBackend::Proc { previous });
                            self.ebpf_empty_samples = 0;
                            latest_util.clear();
                            last_sample = Instant::now();
                            println!(
                                "[boost] eBPF CPU 利用率采样异常（{reason}），已切换 /proc/stat"
                            );
                            continue;
                        }
                    } else {
                        self.ebpf_empty_samples = 0;
                    }
                    latest_util = sample.values;
                    self.apply_util(&latest_util, level);
                    last_sample = Instant::now();
                }
                self.wait_for_wake(Some(interval.saturating_sub(last_sample.elapsed())), level);
            }
        }

        fn wait_for_wake(&self, timeout: Option<Duration>, expected_level: i32) {
            let Ok(guard) = self.shared.wake.lock() else {
                thread::sleep(Duration::from_millis(20));
                return;
            };
            if self.shared.stop.load(Ordering::Acquire)
                || self.shared.level.load(Ordering::Acquire) != expected_level
            {
                return;
            }
            if let Some(timeout) = timeout {
                let _ = self.shared.condvar.wait_timeout(guard, timeout);
            } else {
                let _guard = self.shared.condvar.wait(guard);
            }
        }

        fn publish_restore(&self, restored: bool) {
            let _guard = self.shared.wake.lock().ok();
            self.shared.restore_ok.store(restored, Ordering::Release);
            self.shared.applied_level.store(0, Ordering::Release);
            if let Ok(mut status) = self.shared.status.lock() {
                *status = if restored {
                    "正常状态：采样器已卸载，频率参数已恢复".to_string()
                } else {
                    "正常状态：部分频率参数恢复失败，已保留恢复记录".to_string()
                };
            }
            self.shared.condvar.notify_all();
        }

        fn publish_level(&self, level: i32) {
            self.shared.applied_level.store(level, Ordering::Release);
            let status = self
                .shared
                .status
                .lock()
                .map(|value| value.clone())
                .unwrap_or_default();
            let label = match level {
                3 => "严重卡顿",
                2 => "中度卡顿",
                1 => "普通卡顿",
                _ => "正常监测",
            };
            println!("[boost] CPU 调速器已应用{label}档：{status}");
        }

        fn sample_util(&mut self) -> UtilSample {
            match self.backend.as_mut() {
                Some(UtilBackend::Ebpf { util, previous, .. }) => {
                    let mut result = UtilSample::default();
                    if let Ok(values) = util.get(&0, 0) {
                        if previous.len() != values.len() {
                            previous.resize(values.len(), (0, 0));
                        }
                        for (index, value) in values.iter().enumerate() {
                            let (old_busy, old_total) = previous[index];
                            let busy_delta = value.busy_ns.saturating_sub(old_busy);
                            let total_delta = value.total_ns.saturating_sub(old_total);
                            result.busy_delta = result.busy_delta.saturating_add(busy_delta);
                            result.total_delta = result.total_delta.saturating_add(total_delta);
                            result.values.push(
                                busy_delta
                                    .saturating_mul(100)
                                    .checked_div(total_delta)
                                    .unwrap_or(0)
                                    .min(100) as u32,
                            );
                            previous[index] = (value.busy_ns, value.total_ns);
                        }
                    }
                    result
                }
                Some(UtilBackend::Proc { previous }) => {
                    let mut current = Vec::new();
                    if !read_proc_stat(&mut current) {
                        return UtilSample::default();
                    }
                    let mut result = UtilSample {
                        values: vec![
                            0;
                            current.iter().map(|value| value.id).max().unwrap_or(0) + 1
                        ],
                        ..UtilSample::default()
                    };
                    for now in &current {
                        if let Some(old) = previous.iter().find(|value| value.id == now.id) {
                            let total_delta = now.total.saturating_sub(old.total);
                            let idle_delta = now.idle.saturating_sub(old.idle);
                            let busy_delta = total_delta.saturating_sub(idle_delta);
                            result.total_delta = result.total_delta.saturating_add(total_delta);
                            result.busy_delta = result.busy_delta.saturating_add(busy_delta);
                            result.values[now.id] = busy_delta
                                .saturating_mul(100)
                                .checked_div(total_delta)
                                .unwrap_or(0)
                                .min(100)
                                as u32;
                        }
                    }
                    *previous = current;
                    result
                }
                None => UtilSample::default(),
            }
        }

        fn apply_util(&mut self, utils: &[u32], level: i32) {
            let boost = match level {
                3 => 28,
                2 => 20,
                _ => 14,
            };
            let floor = match level {
                3 => 70,
                2 => 55,
                _ => 35,
            };
            let mut max_util = 0u32;
            let mut targets = Vec::new();
            for (index, policy) in self.policies.iter().enumerate() {
                let util = policy
                    .cpus
                    .iter()
                    .filter_map(|cpu| utils.get(*cpu))
                    .copied()
                    .max()
                    .unwrap_or_else(|| utils.iter().copied().max().unwrap_or(0));
                max_util = max_util.max(util);
                let target_percent = (util.saturating_add(boost).max(floor)).min(100) as u64;
                targets.push((
                    index,
                    policy.cpus.clone(),
                    policy_frequency(policy, target_percent),
                ));
            }

            let changed = if self.vendor.is_some() {
                self.apply_vendor_targets(&targets)
            } else {
                self.apply_policy_targets(&targets)
            };
            if let Ok(mut status) = self.shared.status.lock() {
                let util_summary = if utils.is_empty() {
                    "等待采样".to_string()
                } else {
                    format!("{max_util}%")
                };
                let target_summary = targets
                    .iter()
                    .map(|(_, _, value)| value.to_string())
                    .collect::<Vec<_>>()
                    .join("/");
                *status = format!(
                    "util={}; boost={}; policy_min={}{}",
                    util_summary,
                    boost,
                    if target_summary.is_empty() {
                        "不可用"
                    } else {
                        &target_summary
                    },
                    if changed {
                        "；已更新"
                    } else {
                        "；保持当前值"
                    }
                );
            }
        }

        fn apply_policy_targets(&mut self, targets: &[(usize, Vec<usize>, u64)]) -> bool {
            let mut changed = false;
            for (index, _, target) in targets {
                let Some(current) = current_value(&self.policies[*index].min_path) else {
                    continue;
                };
                reconcile_policy(&mut self.policies[*index], &current);
                let original = self.policies[*index]
                    .original
                    .parse::<u64>()
                    .unwrap_or(self.policies[*index].min_freq);
                let wanted = target.max(&original).to_string();
                if current == wanted {
                    continue;
                }

                let previous_written = self.policies[*index].last_written.clone();
                self.policies[*index].last_written = wanted.clone();
                if !write_restore(&self.policies, self.vendor.as_ref()) {
                    self.policies[*index].last_written = previous_written;
                    continue;
                }

                let path = self.policies[*index].min_path.clone();
                let write_ok = write_value(&path, &wanted);
                let after = current_value(&path);
                if let Some(now) = after.as_deref() {
                    let valid_frequency = now.parse::<u64>().ok().is_some_and(|value| {
                        value >= self.policies[*index].min_freq
                            && value <= self.policies[*index].max_freq
                            && (self.policies[*index].available.is_empty()
                                || self.policies[*index].available.contains(&value))
                    });
                    if now == wanted || (write_ok && now != current && valid_frequency) {
                        self.policies[*index].last_written = now.to_string();
                        let _ = write_restore(&self.policies, self.vendor.as_ref());
                        changed = true;
                        continue;
                    }
                }

                if let Some(now) = after {
                    self.policies[*index].original = now.clone();
                    self.policies[*index].last_written = now;
                }
                let _ = write_restore(&self.policies, self.vendor.as_ref());
            }
            changed
        }

        fn apply_vendor_targets(&mut self, targets: &[(usize, Vec<usize>, u64)]) -> bool {
            let Some(path) = self.vendor.as_ref().map(|vendor| vendor.path.clone()) else {
                return false;
            };
            let Some(current_text) = current_value(&path) else {
                return false;
            };
            let current = parse_cpu_pairs(&current_text);
            let Some(vendor) = self.vendor.as_mut() else {
                return false;
            };
            reconcile_vendor(vendor, &current);

            let backup = vendor.cpus.clone();
            let mut planned = BTreeMap::new();
            for (_, cpus, target) in targets {
                for cpu in cpus {
                    let Some(state) = vendor.cpus.get_mut(cpu) else {
                        continue;
                    };
                    let wanted = (*target).max(state.original);
                    if current.get(cpu).copied() == Some(wanted) {
                        continue;
                    }
                    state.last_written = wanted;
                    planned.insert(*cpu, wanted);
                }
            }
            if planned.is_empty() {
                return false;
            }
            if !write_restore(&self.policies, self.vendor.as_ref()) {
                if let Some(vendor) = self.vendor.as_mut() {
                    vendor.cpus = backup;
                }
                return false;
            }

            let mut failed = false;
            for (cpu, wanted) in &planned {
                if !write_cpu_pair(&path, *cpu, *wanted)
                    || current_value(&path)
                        .and_then(|value| parse_cpu_pairs(&value).get(cpu).copied())
                        != Some(*wanted)
                {
                    failed = true;
                    break;
                }
            }
            if !failed {
                return true;
            }

            let current = current_value(&path)
                .map(|value| parse_cpu_pairs(&value))
                .unwrap_or_default();
            if let Some(vendor) = self.vendor.as_mut() {
                for (cpu, wanted) in planned {
                    let Some(state) = vendor.cpus.get_mut(&cpu) else {
                        continue;
                    };
                    match current.get(&cpu).copied() {
                        Some(now) if now == wanted => {
                            if write_cpu_pair(&path, cpu, state.original)
                                && current_value(&path)
                                    .and_then(|value| parse_cpu_pairs(&value).get(&cpu).copied())
                                    == Some(state.original)
                            {
                                state.last_written = state.original;
                            }
                        }
                        Some(now) => {
                            state.original = now;
                            state.last_written = now;
                        }
                        None => {}
                    }
                }
            }
            let _ = write_restore(&self.policies, self.vendor.as_ref());
            false
        }

        fn restore(&mut self) -> bool {
            self.restore_vendor();
            for index in 0..self.policies.len() {
                let policy = &mut self.policies[index];
                if policy.last_written == policy.original {
                    continue;
                }
                let Some(current) = current_value(&policy.min_path) else {
                    continue;
                };
                if current != policy.last_written {
                    policy.original = current.clone();
                    policy.last_written = current;
                    continue;
                }
                if write_value(&policy.min_path, &policy.original)
                    && current_value(&policy.min_path).as_deref() == Some(policy.original.as_str())
                {
                    policy.last_written = policy.original.clone();
                }
            }
            let journal_ok = write_restore(&self.policies, self.vendor.as_ref());
            journal_ok && !has_pending_overrides(&self.policies, self.vendor.as_ref())
        }

        fn restore_vendor(&mut self) {
            let Some(path) = self.vendor.as_ref().map(|vendor| vendor.path.clone()) else {
                return;
            };
            let Some(current_text) = current_value(&path) else {
                return;
            };
            let current = parse_cpu_pairs(&current_text);
            let Some(vendor) = self.vendor.as_mut() else {
                return;
            };
            for (cpu, state) in &mut vendor.cpus {
                if state.last_written == state.original {
                    continue;
                }
                match current.get(cpu).copied() {
                    Some(now) if now == state.last_written => {
                        if write_cpu_pair(&path, *cpu, state.original)
                            && current_value(&path)
                                .and_then(|value| parse_cpu_pairs(&value).get(cpu).copied())
                                == Some(state.original)
                        {
                            state.last_written = state.original;
                        }
                    }
                    Some(now) => {
                        state.original = now;
                        state.last_written = now;
                    }
                    None => {}
                }
            }
        }

        fn refresh_originals(&mut self) {
            if let Some(vendor) = &mut self.vendor
                && let Some(current) = current_value(&vendor.path)
            {
                let current = parse_cpu_pairs(&current);
                reconcile_vendor(vendor, &current);
            }
            for policy in &mut self.policies {
                if policy.last_written == policy.original
                    && let Some(current) = current_value(&policy.min_path)
                {
                    policy.original = current.clone();
                    policy.last_written = current;
                }
            }
        }
    }

    fn load_backend() -> (UtilBackend, String) {
        match load_ebpf() {
            Ok(backend) => (backend, "eBPF sched_switch".to_string()),
            Err(error) => {
                let mut previous = Vec::new();
                let _ = read_proc_stat(&mut previous);
                let label = if previous.is_empty() {
                    format!("无可用利用率后端（eBPF: {error}）")
                } else {
                    format!("/proc/stat 降级（eBPF: {error}）")
                };
                (UtilBackend::Proc { previous }, label)
            }
        }
    }

    fn policy_frequency(policy: &PolicyTarget, percent: u64) -> u64 {
        let wanted = policy.max_freq.saturating_mul(percent).saturating_add(99) / 100;
        let wanted = wanted.clamp(policy.min_freq, policy.max_freq);
        if policy.available.is_empty() {
            return wanted;
        }
        policy
            .available
            .iter()
            .copied()
            .find(|freq| *freq >= wanted)
            .unwrap_or(policy.max_freq)
    }

    fn reconcile_policy(policy: &mut PolicyTarget, current: &str) {
        if current == policy.last_written {
            return;
        }
        policy.original = current.to_string();
        policy.last_written = current.to_string();
    }

    fn reconcile_vendor(vendor: &mut VendorTarget, current: &BTreeMap<usize, u64>) {
        for (cpu, value) in current {
            let state = vendor.cpus.entry(*cpu).or_insert(CpuPairState {
                original: *value,
                last_written: *value,
            });
            if *value != state.last_written {
                state.original = *value;
                state.last_written = *value;
            }
        }
    }

    fn has_pending_overrides(policies: &[PolicyTarget], vendor: Option<&VendorTarget>) -> bool {
        policies
            .iter()
            .any(|policy| policy.last_written != policy.original)
            || vendor.is_some_and(|vendor| {
                vendor
                    .cpus
                    .values()
                    .any(|state| state.last_written != state.original)
            })
    }

    fn load_ebpf() -> Result<UtilBackend, String> {
        let path = Path::new("/data/adb/modules/AppOpt/config/ebpf/cpu_util_monitor.bpf.o");
        if !path.is_file() {
            return Err("找不到 cpu_util_monitor.bpf.o".to_string());
        }
        load_ebpf_file(path).map_err(|error| format!("{}: {error}", path.display()))
    }

    fn load_ebpf_file(path: &Path) -> Result<UtilBackend, String> {
        let mut bpf = Ebpf::load_file(path).map_err(|error| error.to_string())?;
        let program: &mut TracePoint = bpf
            .program_mut("appopt_sched_switch")
            .ok_or("缺少 appopt_sched_switch 程序")?
            .try_into()
            .map_err(|error: aya::programs::ProgramError| error.to_string())?;
        program.load().map_err(|error| error.to_string())?;
        program
            .attach("sched", "sched_switch")
            .map_err(|error| error.to_string())?;
        let map = bpf.take_map("cpu_util").ok_or("缺少 cpu_util map")?;
        let util = PerCpuArray::try_from(map).map_err(|error| error.to_string())?;
        Ok(UtilBackend::Ebpf {
            _bpf: Box::new(bpf),
            util: Box::new(util),
            previous: Vec::new(),
        })
    }

    fn policy_targets() -> Vec<PolicyTarget> {
        let mut dirs = fs::read_dir("/sys/devices/system/cpu/cpufreq")
            .into_iter()
            .flatten()
            .flatten()
            .filter(|entry| entry.file_name().to_string_lossy().starts_with("policy"))
            .map(|entry| entry.path())
            .collect::<Vec<_>>();
        dirs.sort_by_key(|dir| {
            std::cmp::Reverse(
                current_value(&dir.join("cpuinfo_max_freq"))
                    .and_then(|value| value.parse::<u64>().ok())
                    .unwrap_or(0),
            )
        });
        dirs.truncate(2);
        dirs.into_iter()
            .filter_map(|dir| {
                let min_path = dir.join("scaling_min_freq");
                let original = current_value(&min_path)?;
                let original_value = original.parse::<u64>().ok()?;
                let min_freq = current_value(&dir.join("cpuinfo_min_freq"))
                    .and_then(|value| value.parse::<u64>().ok())
                    .unwrap_or(original_value);
                let max_freq = current_value(&dir.join("cpuinfo_max_freq"))
                    .and_then(|value| value.parse::<u64>().ok())?;
                if max_freq < min_freq {
                    return None;
                }
                let mut available = policy_frequencies(&dir);
                available.retain(|freq| *freq >= min_freq && *freq <= max_freq);
                available.sort_unstable();
                available.dedup();
                Some(PolicyTarget {
                    min_path,
                    available,
                    min_freq,
                    max_freq,
                    cpus: policy_cpus(&dir),
                    original: original.clone(),
                    last_written: original,
                })
            })
            .collect()
    }

    fn policy_frequencies(dir: &Path) -> Vec<u64> {
        let values = fs::read_to_string(dir.join("scaling_available_frequencies"))
            .unwrap_or_default()
            .split_whitespace()
            .filter_map(|value| value.parse::<u64>().ok())
            .collect::<Vec<_>>();
        if !values.is_empty() {
            return values;
        }
        fs::read_to_string(dir.join("stats/time_in_state"))
            .unwrap_or_default()
            .lines()
            .filter_map(|line| line.split_whitespace().next())
            .filter_map(|value| value.parse::<u64>().ok())
            .collect()
    }

    fn policy_cpus(dir: &Path) -> Vec<usize> {
        for name in ["related_cpus", "affected_cpus"] {
            let cpus = fs::read_to_string(dir.join(name))
                .unwrap_or_default()
                .split_whitespace()
                .filter_map(|item| item.parse().ok())
                .collect::<Vec<_>>();
            if !cpus.is_empty() {
                return cpus;
            }
        }
        dir.file_name()
            .and_then(|name| name.to_str())
            .and_then(|name| name.strip_prefix("policy"))
            .and_then(|value| value.parse().ok())
            .into_iter()
            .collect()
    }

    fn vendor_target() -> Option<VendorTarget> {
        [
            "/sys/kernel/msm_performance/parameters/cpu_min_freq",
            "/sys/module/msm_performance/parameters/cpu_min_freq",
        ]
        .iter()
        .find_map(|path| {
            let path = PathBuf::from(path);
            let current = current_value(&path)?;
            let cpus = parse_cpu_pairs(&current)
                .into_iter()
                .map(|(cpu, value)| {
                    (
                        cpu,
                        CpuPairState {
                            original: value,
                            last_written: value,
                        },
                    )
                })
                .collect::<BTreeMap<_, _>>();
            (!cpus.is_empty()).then_some(VendorTarget { path, cpus })
        })
    }

    fn current_value(path: &Path) -> Option<String> {
        fs::read_to_string(path)
            .ok()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty())
    }

    fn write_value(path: &Path, value: &str) -> bool {
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

    fn write_restore(policies: &[PolicyTarget], vendor: Option<&VendorTarget>) -> bool {
        let mut content = policies
            .iter()
            .filter(|policy| policy.last_written != policy.original)
            .map(|policy| {
                format!(
                    "F\t{}\t{}\t{}\n",
                    policy.min_path.display(),
                    policy.original,
                    policy.last_written
                )
            })
            .collect::<String>();
        if let Some(vendor) = vendor.filter(|vendor| {
            vendor
                .cpus
                .values()
                .any(|state| state.last_written != state.original)
        }) {
            let original = vendor
                .cpus
                .iter()
                .map(|(cpu, state)| (*cpu, state.original))
                .collect::<BTreeMap<_, _>>();
            let written = vendor
                .cpus
                .iter()
                .map(|(cpu, state)| (*cpu, state.last_written))
                .collect::<BTreeMap<_, _>>();
            content.push_str(&format!(
                "P\t{}\t{}\t{}\n",
                vendor.path.display(),
                format_cpu_pairs(&original),
                format_cpu_pairs(&written)
            ));
        }
        write_restore_content(&content)
    }

    fn write_restore_content(content: &str) -> bool {
        if content.is_empty() {
            let removed = match fs::remove_file(RESTORE_FILE) {
                Ok(()) => true,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
                Err(_) => false,
            };
            let _ = fs::remove_file(format!("{RESTORE_FILE}.tmp"));
            return removed;
        }
        let tmp = format!("{RESTORE_FILE}.tmp");
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
        if written && fs::rename(&tmp, RESTORE_FILE).is_ok() {
            true
        } else {
            let _ = fs::remove_file(tmp);
            false
        }
    }

    fn recover_stale() -> i32 {
        let Ok(content) = fs::read_to_string(RESTORE_FILE) else {
            return 0;
        };
        let mut recovered = 0i32;
        let mut retained = String::new();
        for line in content.lines() {
            let fields = line.split('\t').collect::<Vec<_>>();
            let done = if fields.len() == 4 && fields[0] == "F" {
                recover_value(Path::new(fields[1]), fields[2], fields[3])
            } else if fields.len() == 4 && fields[0] == "P" {
                recover_cpu_pairs(Path::new(fields[1]), fields[2], fields[3])
            } else if fields.len() == 3 {
                recover_value(Path::new(fields[0]), fields[1], fields[2])
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
        let _ = write_restore_content(&retained);
        recovered
    }

    fn recover_value(path: &Path, original: &str, written: &str) -> bool {
        let Some(current) = current_value(path) else {
            return false;
        };
        if current.trim() != written.trim() {
            return true;
        }
        write_value(path, original) && current_value(path).as_deref() == Some(original.trim())
    }

    fn recover_cpu_pairs(path: &Path, original: &str, written: &str) -> bool {
        let originals = parse_cpu_pairs(original);
        let written = parse_cpu_pairs(written);
        let Some(current_text) = current_value(path) else {
            return false;
        };
        let current = parse_cpu_pairs(&current_text);
        let mut recovered = true;
        let cpus = originals
            .keys()
            .chain(written.keys())
            .copied()
            .collect::<BTreeSet<_>>();
        for cpu in cpus {
            let (Some(original), Some(written), Some(current)) = (
                originals.get(&cpu).copied(),
                written.get(&cpu).copied(),
                current.get(&cpu).copied(),
            ) else {
                recovered = false;
                continue;
            };
            if original == written || current != written {
                continue;
            }
            if !write_cpu_pair(path, cpu, original)
                || current_value(path).and_then(|value| parse_cpu_pairs(&value).get(&cpu).copied())
                    != Some(original)
            {
                recovered = false;
            }
        }
        recovered
    }

    fn read_proc_stat(out: &mut Vec<CpuTimes>) -> bool {
        let Ok(content) = fs::read_to_string("/proc/stat") else {
            return false;
        };
        out.clear();
        for line in content.lines().filter(|line| {
            line.starts_with("cpu")
                && line
                    .as_bytes()
                    .get(3)
                    .is_some_and(|byte| byte.is_ascii_digit())
        }) {
            let mut fields = line.split_whitespace();
            let Some(id) = fields
                .next()
                .and_then(|value| value.trim_start_matches("cpu").parse::<usize>().ok())
            else {
                continue;
            };
            let values = fields
                .filter_map(|item| item.parse::<u64>().ok())
                .collect::<Vec<_>>();
            if values.len() < 4 {
                continue;
            }
            let idle = values[3].saturating_add(*values.get(4).unwrap_or(&0));
            let sum = values.iter().copied().sum::<u64>();
            out.push(CpuTimes {
                id,
                total: sum,
                idle,
            });
        }
        !out.is_empty()
    }

    fn parse_cpu_pairs(value: &str) -> BTreeMap<usize, u64> {
        value
            .split_whitespace()
            .filter_map(|pair| {
                let (cpu, value) = pair.split_once(':')?;
                Some((cpu.parse().ok()?, value.parse().ok()?))
            })
            .collect()
    }

    fn format_cpu_pairs(pairs: &BTreeMap<usize, u64>) -> String {
        pairs
            .iter()
            .map(|(cpu, value)| format!("{cpu}:{value}"))
            .collect::<Vec<_>>()
            .join(" ")
    }

    fn write_cpu_pair(path: &Path, cpu: usize, value: u64) -> bool {
        write_value(path, &format!("{cpu}:{value}"))
    }

    pub fn recover_stale_overrides() -> i32 {
        recover_stale()
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
mod platform {
    pub struct AdaptiveGovernor;
    impl AdaptiveGovernor {
        pub fn new() -> Self {
            Self
        }
        pub fn set_level(&self, _level: i32) {}
        pub fn restore(&self) -> bool {
            true
        }
        pub fn take_startup_message(&mut self) -> Option<String> {
            None
        }
    }
    pub fn recover_stale_overrides() -> i32 {
        0
    }
}

pub use platform::{AdaptiveGovernor, recover_stale_overrides};
