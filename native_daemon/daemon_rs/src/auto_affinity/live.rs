//! 有界的前台线程采样；休眠线程不写入亲和性。
use super::super::{
    accounting, demand,
    live_policy::{self, Activity, Placement},
    planner, Sample,
};
use super::batch::{Batch, Identity, Request};
use super::core_history::{self, EventLog, ObservationProbe, ObservedCores};
use super::{affinity, cpu_loads, cpu_times, online_mask, sampling, topology};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::time::{Duration, Instant};

struct Tracked {
    ticks: u64,
    time: Instant,
    interval_start: Instant,
    seen: Instant,
    name: String,
    activity: Activity,
    latest_busy: f64,
    placement: Placement,
    demand: sampling::DemandWindow,
    last_detail: Option<Instant>,
    measured: Option<(Instant, demand::Demand)>,
    running_cpu: Option<u32>,
    observed_cores: ObservationProbe,
    inheritance_checked: bool,
    inheritance_retry: Option<Instant>,
}

pub(super) struct Controller {
    pkg: String,
    cores: Vec<super::Core>,
    policies: Vec<demand::Policy>,
    hz: f64,
    pids: BTreeSet<i32>,
    process_scan: Option<Instant>,
    full_process_scan: Option<Instant>,
    tracked: BTreeMap<Identity, Tracked>,
    scan_cursor: usize,
    cpu_previous: Vec<(u64, u64)>,
    batch: Batch,
    paused_until: Option<Instant>,
    frame_reference: Option<Sample>,
    bad_frames: u8,
    last_frame_ns: u64,
    feedback_pressure: live_policy::FeedbackPressure,
    history: Option<crate::auto_history::ThreadBatch>,
    core_history: EventLog,
    history_session: Option<u64>,
    // 两次采样之间，只能重新确认最新且负载达标的请求。
    requests: Vec<Request>,
}

impl Controller {
    pub(super) fn package(&self) -> &str {
        &self.pkg
    }
    pub(super) fn publish_diagnostics(&self) {
        use super::super::diagnostics::{self, Row};
        if !diagnostics::due(&self.pkg) { return; }
        let requests: BTreeMap<_, _> = self.requests.iter().map(|r| (r.identity, r.mask)).collect();
        let rows = self.tracked.iter().filter(|(_, entry)| entry.activity.average() > 0.0)
            .map(|(key, entry)| {
                let desired = requests.get(key).copied();
                Row { key: *key, name: entry.name.clone(), average: entry.activity.average() * 100.0, desired,
                    expected_group: desired.map(|mask| super::cpuset::target(key, mask)).unwrap_or_default() }
            }).collect();
        diagnostics::publish(&self.pkg, rows);
    }
    pub(super) fn new(pkg: &str) -> Self {
        let cores = topology();
        let policies = accounting::policies(&cores).unwrap_or_default();
        Self {
            pkg: pkg.into(),
            cores,
            policies,
            hz: unsafe { libc::sysconf(libc::_SC_CLK_TCK) } as f64,
            pids: BTreeSet::new(),
            process_scan: None,
            full_process_scan: None,
            tracked: BTreeMap::new(),
            scan_cursor: 0,
            cpu_previous: Vec::new(),
            batch: Batch::default(),
            paused_until: None,
            frame_reference: None,
            bad_frames: 0,
            last_frame_ns: 0,
            feedback_pressure: live_policy::FeedbackPressure::default(),
            history: None,
            core_history: EventLog::default(),
            history_session: None,
            requests: Vec::new(),
        }
    }
    pub(super) fn release(&mut self, reason: &str) -> bool {
        self.requests.clear();
        self.capture_core_history();
        let restored = self.batch.release();
        self.collect_core_events(Some(reason), &BTreeSet::new());
        if !restored {
            self.core_history.record((0, 0, 0), "error", "unknown", None, None, "recovery_failed");
        }
        self.tracked.clear();
        self.cpu_previous.clear();
        self.frame_reference = None;
        self.bad_frames = 0;
        self.last_frame_ns = 0;
        self.feedback_pressure = live_policy::FeedbackPressure::default();
        // 调用方仍须发布本轮样本及释放事件。
        restored
    }

    /// 归属检查不扫描线程池、不读取频率驻留时间，也不重新规划分配；
    /// 只有确认发生偏移后才真正写入。
    pub(super) fn maintain(&mut self) {
        self.capture_core_history();
        let now = Instant::now();
        self.requests.retain(|request| self.tracked.get(&request.identity).is_some_and(|entry|
            entry.activity.keep_owned_without_sample(now.saturating_duration_since(entry.seen))));
        if self.requests.is_empty() { return; }
        let result = self.batch.maintain(&self.requests);
        self.collect_core_events(None, &BTreeSet::new());
        // 完整负载轮次发布所有请求的状态，包括维护流程不能触碰的
        // 待接纳项与退出项。如果发布这个局部轮次，
        // 就会错误报告待处理数为零，并在接纳失败期间
        // 每 250 ms 重写一次状态文件。
        match result {
            Ok(changes) => {
                for key in changes.written {
                    if let Some(entry) = self.tracked.get_mut(&key) {
                        entry.placement.reasserted(now);
                    }
                }
            }
            Err(error) => {
                log_error!("[auto] {} 核心接管复核失败: {error}", self.pkg);
                self.core_history.record((0, 0, 0), "error", "unknown", None, None, "batch_failed");
            }
        }
    }

    fn pause(&mut self, detail: &str, seconds: u64) -> (&'static str, String) {
        if !self.release("paused") {
            return ("recovery_pending", "恢复尚未完成，已停止自动调整".into());
        }
        self.paused_until = Some(Instant::now() + Duration::from_secs(seconds));
        ("cooldown", detail.into())
    }

    fn refresh_processes(&mut self, now: Instant) {
        if self
            .process_scan
            .is_some_and(|last| now.duration_since(last) < Duration::from_secs(1))
        {
            return;
        }
        self.process_scan = Some(now);
        let full = self
            .full_process_scan
            .is_none_or(|last| now.duration_since(last) >= Duration::from_secs(30));
        self.pids = crate::package_processes::discover(&self.pkg, self.pids.iter().copied(), full)
            .into_keys()
            .collect();
        if full {
            self.full_process_scan = Some(now);
        }
    }
    fn scan(&mut self, now: Instant) -> Vec<Identity> {
        let observe_history = crate::auto_history::recording_for(&self.pkg);
        self.refresh_processes(now);
        self.pids.retain(|pid| owner_matches(*pid, &self.pkg));
        let mut threads = Vec::new();
        let mut fully_scanned = BTreeSet::new();
        for &pid in &self.pids {
            if let Ok(entries) = fs::read_dir(format!("/proc/{pid}/task")) {
                let mut complete = true;
                for entry in entries {
                    let Ok(entry) = entry else { complete = false; continue; };
                    if let Some(tid) = entry
                        .file_name()
                        .to_str()
                        .and_then(|s| s.parse::<i32>().ok())
                    {
                        threads.push((pid, tid));
                        if threads.len() >= live_policy::MAX_TRACKED {
                            complete = false;
                            break;
                        }
                    }
                }
                if complete { fully_scanned.insert(pid); }
            }
            if threads.len() >= live_policy::MAX_TRACKED {
                break;
            }
        }
        threads.sort_unstable();
        let present: BTreeSet<_> = threads.iter().copied().collect();
        let mut selected: BTreeSet<_> = live_policy::select_active(self
            .tracked
            .iter()
            .filter(|(key, t)| {
                present.contains(&(key.0, key.1))
                    && (self.batch.mask(key).is_some() || t.activity.eligible())
            })
            .map(|(key, entry)| ((key.0, key.1), self.batch.mask(key).is_some(), entry.activity.peak())))
            .into_iter()
            .collect();
        // 及时采样已知活跃线程，并轮换其余线程，
        // 防止大线程池永久遮蔽靠后的 TID。
        sampling::rotate_scan(
            &threads,
            &mut selected,
            &mut self.scan_cursor,
            live_policy::MAX_SCAN,
        );
        let retired: Vec<_> = self.tracked.iter().filter(|(key, t)| {
            (fully_scanned.contains(&key.0) && !present.contains(&(key.0, key.1)))
                || now.duration_since(t.seen) >= Duration::from_secs(30)
        }).map(|(key, _)| *key).collect();
        for key in retired {
            if let Some(entry) = self.tracked.remove(&key) {
                if fully_scanned.contains(&key.0) && !present.contains(&(key.0, key.1)) && self.batch.mask(&key).is_none() {
                    if let Some(previous) = entry.observed_cores.recorded {
                        self.core_history.exit(key, &entry.name, previous, "thread_exit");
                    }
                }
            }
        }
        let mut sampled = Vec::new();
        for (pid, tid) in selected {
            let Ok(bytes) = fs::read(format!("/proc/{pid}/task/{tid}/stat")) else {
                continue;
            };
            let Some(stat) = qixia_kernel_info::proc::parse_stat(&bytes) else {
                continue;
            };
            let (start, ticks) = (stat.start, stat.ticks);
            let identity = (pid, tid, start);
            // PID/TID 复用意味着新线程，不能延续旧负载。
            let reused: Vec<_> = self
                .tracked
                .range((pid, tid, 0)..=(pid, tid, u64::MAX))
                .map(|(key, _)| *key)
                .filter(|key| key.2 != start)
                .collect();
            for key in reused {
                if let Some(old) = self.tracked.remove(&key) {
                    if self.batch.mask(&key).is_none() {
                        if let Some(previous) = old.observed_cores.recorded {
                            self.core_history.exit(key, &old.name, previous, "identity_changed");
                        }
                    }
                }
            }
            let entry = self.tracked.entry(identity).or_insert_with(|| Tracked {
                ticks,
                time: now,
                interval_start: now,
                seen: now,
                name: String::new(),
                activity: Activity::default(),
                latest_busy: 0.0,
                placement: Placement::default(),
                demand: sampling::DemandWindow::default(),
                last_detail: None,
                measured: None,
                running_cpu: None,
                observed_cores: ObservationProbe::default(),
                inheritance_checked: false,
                inheritance_retry: None,
            });
            // TID 和启动时间不变时 comm 仍可能变化。保留分配证据
            // 以及原始恢复基线，但在负载样本和后续核心事件中
            // 发布当前线程名。
            if entry.name != stat.name {
                entry.name = stat.name;
                entry.observed_cores = ObservationProbe::default();
            }
            entry.running_cpu = if observe_history {
                core_history::running_cpu(&String::from_utf8_lossy(&bytes))
            } else { None };
            entry.interval_start = entry.time;
            let elapsed = now.saturating_duration_since(entry.time).as_secs_f64();
            if elapsed > 0.0 {
                let busy = ticks
                    .checked_sub(entry.ticks)
                    .map(|ticks| ticks as f64 / self.hz / elapsed);
                if let Some(busy) = busy.filter(|busy| entry.activity.observe(*busy, elapsed)) {
                    entry.latest_busy = busy;
                    sampled.push(identity);
                } else {
                    entry.activity = Activity::default();
                    entry.latest_busy = 0.0;
                    entry.demand = sampling::DemandWindow::default();
                    entry.last_detail = None;
                    entry.measured = None;
                }
            }
            entry.ticks = ticks;
            entry.time = now;
            entry.seen = now;
        }
        sampled
    }

    pub(super) fn step(&mut self, sample: Option<Sample>) -> (&'static str, String) {
        self.history = None;
        self.capture_core_history();
        let now = Instant::now();
        if self.paused_until.is_some_and(|until| now < until) {
            return ("cooldown", "暂由系统调度，稍后重新评估活跃线程".into());
        }
        self.paused_until = None;
        let Some(online) = online_mask()
            .filter(|mask| super::super::topology::has_capacity_info(&self.cores, *mask))
        else {
            self.cores = topology();
            self.policies = accounting::policies(&self.cores).unwrap_or_default();
            return self.pause("核心能力信息暂不可用，正在重新识别", 2);
        };
        if self.hz <= 0.0 {
            return self.pause("线程计时信息不可用，正在重试", 2);
        }
        let limits = accounting::limits(&self.policies);
        if self.policies.is_empty()
            || self
                .cores
                .iter()
                .any(|c| online & (1 << c.id) != 0 && limits[c.id].is_none())
        {
            self.policies = accounting::policies(&self.cores).unwrap_or_default();
            return self.pause("核心频率信息暂不可用，正在重试", 2);
        }
        let available = match super::cpuset::available(online) {
            Ok(mask) if mask != 0 => mask,
            _ => return self.pause("QixiaThreads 核心分组暂不可用，正在重试", 2),
        };
        let sampled = self.scan(now);
        // 新线程在消耗 CPU 前就可能继承父线程的 QixiaThreads 组。
        // 每个身份先检查一次；不确定的 IO 失败只低频重试，
        // 并复用现有的有界 stat 扫描。
        let inherited: Vec<_> = self.tracked.iter()
            .filter(|(_, entry)| entry.seen == now && !entry.inheritance_checked
                && entry.inheritance_retry.is_none_or(|retry| now >= retry))
            .map(|(key, _)| *key).take(live_policy::MAX_SCAN).collect();
        if !inherited.is_empty() {
            for key in &inherited {
                self.tracked.get_mut(key).unwrap().inheritance_retry = Some(now + Duration::from_secs(10));
            }
            let reconciled = self.batch.reconcile_inherited(&inherited);
            self.collect_core_events(None, &BTreeSet::new());
            match reconciled {
                Ok(confirmed) => for key in confirmed {
                    if let Some(entry) = self.tracked.get_mut(&key) {
                        entry.inheritance_checked = true;
                        entry.inheritance_retry = None;
                    }
                },
                Err(error) => {
                    log_error!("[auto] {} 继承核心分组恢复失败: {error}", self.pkg);
                    return self.pause("继承核心分组恢复记录异常，正在重试", 2);
                }
            }
        }
        // 历史探测独立于 500 ms 分配器：活跃或已接管线程每 2 秒探测，
        // 此前见过的休眠且未接管线程每 5 秒探测。
        // 任何正活跃度都计入，包括低于 5% 门槛的线程。
        if self.core_history.enabled() && crate::auto_history::recording_for(&self.pkg) {
            for key in &sampled {
                let entry = self.tracked.get_mut(key).unwrap();
                let owned = self.batch.mask(key);
                let running_cpu = entry.running_cpu;
                self.core_history.probe(*key, &entry.name, entry.activity.average() * 100.0,
                    &mut entry.observed_cores, now, entry.latest_busy > 0.0, owned.is_some(), || {
                        ObservedCores::verified(affinity(key.1).ok(), running_cpu,
                            owned, self.batch.ownership(key))
                    });
            }
        }
        if crate::auto_history::wants_threads() {
            // 复用执行 5% 分配过滤前的原始观测窗口。
            // 发布在 worker.rs 中释放分配互斥锁后进行。
            self.history = Some(crate::auto_history::ThreadBatch {
                package: self.pkg.clone(),
                timestamp_ms: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64,
                threads: sampled.iter().map(|key| {
                    let entry = &self.tracked[key];
                    crate::auto_history::ThreadSample { pid: key.0, tid: key.1, start: key.2, name: entry.name.clone(), percent: entry.latest_busy * 100.0 }
                }).collect(),
                changes: Vec::new(),
            });
        }
        let cpu_after = cpu_times();
        let raw_loads = cpu_loads(&self.cpu_previous, &cpu_after);
        self.cpu_previous = cpu_after;
        let sampled: BTreeSet<_> = sampled.into_iter().collect();
        let active = live_policy::select_active(self.tracked.iter().filter_map(|(key, entry)| {
            let managed = self.batch.mask(key).is_some();
            let eligible = if sampled.contains(key) {
                entry.activity.eligible()
            } else {
                managed && entry.activity.keep_owned_without_sample(now.saturating_duration_since(entry.seen))
            };
            eligible.then_some((*key, managed, entry.activity.peak()))
        }));

        // 系统迁移不会取消符合条件线程的期望分配。
        // Batch 会验证归属，并对各次写入进行退避重试。
        let mut current_masks = BTreeMap::new();
        for (key, _) in self.batch.records() {
            let current = affinity(key.1).ok();
            current_masks.insert(*key, current);
        }
        if !active.is_empty() && !self.policies.is_empty() {
            let details = sampling::oldest_details(
                active.iter().filter(|key| sampled.contains(*key)
                    && self.tracked[*key].last_detail.is_none_or(|last| now.duration_since(last) >= Duration::from_secs(1)))
                    .map(|key| (*key, self.tracked[key].last_detail)),
                live_policy::MAX_DETAILS,
            );
            for key in details {
                let entry = self.tracked.get_mut(&key).unwrap();
                entry.last_detail = Some(now);
                if let Ok(residency) = accounting::residency(key.0, key.1) {
                    let stat = super::ThreadStat {
                        start: key.2,
                        ticks: entry.ticks,
                    };
                    if let Some(measured) =
                        entry
                            .demand
                            .push(now, stat, residency, &self.policies, self.hz)
                    {
                        entry.measured = Some((now, measured));
                    }
                }
            }
        }
        let maximum = self.cores.iter().map(|c| c.capacity).max().unwrap_or(0) as f64;
        let mut demands = Vec::new();
        let mut current_union = 0;
        let mut current_busy = 0.0;
        let mut pinned_busy = [0.0; 64];
        for key in &active {
            let current = *current_masks
                .entry(*key)
                .or_insert_with(|| affinity(key.1).ok());
            let Some(original) = self.batch.original(key).or(current) else { continue; };
            // 继承的掩码只是恢复基线，不能成为
            // QixiaThreads 专用 cpuset 的永久上限。
            let allowed = available;
            let entry = &self.tracked[key];
            let measured = entry
                .measured
                .filter(|(time, _)| now.duration_since(*time) <= Duration::from_secs(8))
                .map(|(_, d)| d);
            // 仅在完整区间内被我们固定到单核时，扣除该核上已确认的自身负载。
            // /proc/stat 的最后运行核心不能证明整个区间都在那颗 CPU 上。
            let settled_pin = current.filter(|mask| mask.count_ones() == 1
                && self.batch.mask(key) == Some(*mask)
                && entry.placement.settled_before(entry.interval_start) && self.batch.owns(key));
            let scale = measured.map_or(maximum,
                |d| (d.units / d.busy.max(0.01)).min(maximum));
            let units = self.feedback_pressure.workload(now, planner::workload(entry.activity.average(),
                entry.activity.peak(), maximum, scale, measured.map_or(0.0, |d| d.units)));
            if let Some(current) = current.filter(|_| sampled.contains(key)) {
                if settled_pin == Some(current) {
                    pinned_busy[current.trailing_zeros() as usize] += entry.latest_busy;
                } else {
                    current_union |= current;
                    current_busy += entry.latest_busy;
                }
            }
            demands.push((
                *key,
                original,
                planner::Demand {
                    serial: units,
                    total: units,
                    parallel: 1,
                    allowed,
                },
            ));
        }
        demands.sort_by(|a, b| b.2.serial.total_cmp(&a.2.serial).then(a.0.cmp(&b.0)));
        let external = live_policy::external_with_pinned(raw_loads, &pinned_busy, current_union, current_busy);
        let mut budget = planner::Budget::new(&self.cores, &limits);
        budget.reserve_external(&external);
        let mut requests = Vec::new();
        for (key, original, demand) in &demands {
            let before = budget.clone();
            let held = self.batch.mask(key).unwrap_or(0);
            if !sampled.contains(key) && held != 0 && held & !available == 0 {
                budget.reserve_placement(demand, held);
                requests.push(Request { identity: *key, original: *original, mask: held });
                continue;
            }
            let current_fits = budget.fits(demand, held);
            let desired = budget.assign_preferred(demand, held).0;
            let materially_better = before.materially_better(demand, held, desired);
            // 需求突增时可立即离开容量不足的窄核心池；
            // 驻留期结束后再依据稳定证据选择新核心池。
            let entry = self.tracked.get_mut(key).unwrap();
            if entry.placement.wait_for_accounting(entry.interval_start, held, available,
                before.stronger_lane(available, held, desired)) {
                budget = before;
                budget.reserve_placement(demand, held);
                requests.push(Request { identity: *key, original: *original, mask: held });
                continue;
            }
            if let Some(mask) = entry.placement.choose_available(now, held, desired, available, current_fits, materially_better) {
                if mask != desired {
                    budget = before;
                    budget.reserve_placement(demand, mask);
                }
                requests.push(Request {
                    identity: *key,
                    original: *original,
                    mask: mask & available,
                });
            }
            // 待生效分配仍占用所提议核心池的预算。否则每个新线程
            // 都会反复提议同一颗首选核心，
            // 导致每个预热窗口只能接纳一个线程。
        }

        // 只有已经为用户采集 FPS 时，才将其作为可选反馈。
        // 视频帧率、缺帧和 Surface 变化都不能阻塞负载采样。
        let mut ts = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        let clock_ok = unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) } == 0;
        let now_ns = (ts.tv_sec as u64)
            .saturating_mul(1_000_000_000)
            .saturating_add(ts.tv_nsec as u64);
        if let Some(s) = sample
            .filter(|s| clock_ok && s.feedback_valid(now_ns) && s.timestamp_ns > self.last_frame_ns)
        {
            self.last_frame_ns = s.timestamp_ns;
            if let Some(base) = self
                .frame_reference
                .filter(|b| b.pid == s.pid && b.tid == s.tid && b.surface == s.surface)
            {
                let bad = self.batch.len() > 0
                    && s.fps < base.fps * 0.75
                    && s.p95_ns as f64 > base.p95_ns as f64 * 1.5;
                self.bad_frames = if bad {
                    self.bad_frames.saturating_add(1)
                } else {
                    0
                };
                if self.bad_frames >= 2 {
                    if self.feedback_pressure.activate(now) {
                        for key in &active {
                            if let Some(entry) = self.tracked.get_mut(key) {
                                entry.placement.request_review(now);
                            }
                        }
                    }
                    self.bad_frames = 0;
                }
            } else {
                self.frame_reference = Some(s);
                self.bad_frames = 0;
            }
        }
        self.requests = requests;
        let result = self.batch.apply(&self.requests);
        self.collect_core_events(None, &BTreeSet::new());
        let changes = match result {
            Ok(changes) => changes,
            Err(error) => {
                log_error!("[auto] {} 批次失败: {error}", self.pkg);
                self.core_history.record((0, 0, 0), "error", "unknown", None, None, "batch_failed");
                return self.pause("亲和性写入或恢复监督异常，正在重试", 2);
            }
        };
        let written_count = changes.written.len();
        let pending = changes.pending;
        if let Some(history) = self.history.as_mut() {
            history.changes = changes.written.iter().map(|key| (*key, self.batch.mask(key).unwrap_or(0))).collect();
        }
        let mut log_details = Vec::new();
        for key in changes.written {
            if let Some(entry) = self.tracked.get_mut(&key) {
                entry.placement.written(now);
                if log_details.len() < 8 {
                    let mask = self.batch.mask(&key).unwrap_or(0);
                    let cpus = (0..64)
                        .filter(|cpu| mask & (1u64 << cpu) != 0)
                        .map(|cpu| cpu.to_string())
                        .collect::<Vec<_>>()
                        .join(",");
                    log_details.push(format!(
                        "tid={} name={} avg={:.1}% CPU=[{}]",
                        key.1,
                        entry.name,
                        entry.activity.average() * 100.0,
                        cpus
                    ));
                }
            }
        }
        if written_count > 0 {
            log_info!(
                "[auto] 核心分配已更新: pkg={} 本轮调整={} 明细={}/{}\n{}",
                self.pkg,
                written_count,
                log_details.len(),
                written_count,
                log_details.join("\n")
            );
        }
        let placed = self.batch.len();
        if placed > 0 {
            (
                "active",
                if pending > 0 {
                    format!("已分配 {placed} 个活跃线程 · {pending} 个线程等待重新接管")
                } else {
                    format!("已分配 {placed} 个活跃线程 · 持续按负载评估")
                },
            )
        } else if pending > 0 {
            (
                "observing",
                format!("{pending} 个活跃线程等待重新接管，稍后重试"),
            )
        } else if active.is_empty() {
            (
                "observing",
                "正在观察线程负载，仅分配平均占用达到 5% 的活跃线程".into(),
            )
        } else {
            (
                "observing",
                format!(
                    "检测到 {} 个活跃线程，正在评估可用核心与运行余量",
                    active.len()
                ),
            )
        }
    }

    pub(super) fn take_history(&mut self) -> Option<crate::auto_history::ThreadBatch> {
        self.history.take()
    }

    fn capture_core_history(&mut self) {
        core_history::refresh_observation_session(&mut self.history_session,
            crate::auto_history::current_core_session(&self.pkg),
            self.tracked.values_mut().map(|entry| &mut entry.observed_cores));
        let enabled = crate::auto_history::core_events_for(&self.pkg);
        self.core_history.enable(enabled);
        self.batch.capture_events(&self.pkg);
    }

    fn collect_core_events(&mut self, release_reason: Option<&str>, unrestricted: &BTreeSet<Identity>) {
        let mut events = self.batch.take_events();
        for event in &mut events {
            let key = (event.pid, event.tid, event.start);
            let entry = self.tracked.get_mut(&key);
            if event.kind == "release" && event.reason == "released" {
                event.reason = core_history::release_reason(release_reason,
                    entry.as_ref().map(|entry| entry.activity.average() * 100.0),
                    entry.as_ref().is_some_and(|entry| entry.activity.dormant()),
                    unrestricted.contains(&key)).into();
            }
            if let Some(entry) = entry {
                event.name = entry.name.clone();
                event.average = Some(entry.activity.average() * 100.0);
                entry.observed_cores.operation(event);
            }
            super::super::diagnostics::operation(&self.pkg, event);
        }
        self.core_history.extend(events);
    }

    pub(super) fn take_core_events(&mut self) -> Vec<crate::auto_history::CoreEvent> {
        self.core_history.take()
    }
}

fn owner_matches(pid: i32, pkg: &str) -> bool {
    fs::read(format!("/proc/{pid}/cmdline"))
        .ok()
        .is_some_and(|bytes| {
            let name = bytes.split(|b| *b == 0).next().unwrap_or_default();
            name == pkg.as_bytes()
                || name
                    .strip_prefix(pkg.as_bytes())
                    .is_some_and(|suffix| suffix.starts_with(b":"))
        })
}
