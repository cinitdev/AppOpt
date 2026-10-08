//! 只读的校准证据；进程详情读取保持每 2 秒最多 24 次，
//! 分组与联合分配仅在采集停止后执行一次。
use super::{accounting, demand, planner, topology};
#[path = "calibration_patterns.rs"]
mod patterns;
#[path = "calibration_windows.rs"]
mod windows;
use std::collections::{BTreeMap, HashMap};
use std::time::{Duration, Instant};

const MAX_CANDIDATES: usize = 24;
const HOT_CANDIDATES: usize = 16;
const TRACKER_TTL: Duration = Duration::from_secs(10);
const MAX_TRACKERS: usize = MAX_CANDIDATES * 6;
const MAX_EVIDENCE: usize = 256;
const MIN_RULE_AVERAGE_PCT: f64 = 5.0;
pub(crate) const MAX_DRAFT_ROWS: usize = 128;

#[derive(Clone)]
pub(crate) struct Observation {
    pub pid: i32,
    pub tid: i32,
    pub start: u64,
    pub ticks: u64,
    pub delta: u64,
    pub owner: String,
    pub name: String,
}
struct Tracker {
    before: demand::Residency,
    ticks: u64,
    time: Instant,
    owner: String,
    name: String,
}

impl Tracker {
    fn estimate(
        &self,
        item: &Observation,
        after: &demand::Residency,
        policies: &[demand::Policy],
        now: Instant,
        hz: f64,
    ) -> Option<demand::Demand> {
        // TID 与启动时间稳定，不代表线程职责稳定。线程改名
        // 或进程归属变化时，必须建立新的证据基线。
        if self.owner != item.owner || self.name != item.name {
            return None;
        }
        demand::estimate(
            &self.before,
            after,
            policies,
            item.ticks.checked_sub(self.ticks)?,
            now.duration_since(self.time).as_secs_f64(),
            hz,
        )
    }
}
#[derive(Clone, Default)]
struct Evidence {
    observations: usize,
    work: f64,
    busy: f64,
    serial: f64,
    allowed: u64,
    last_round: usize,
}
#[derive(Clone, Debug)]
pub(crate) struct ThreadLoad {
    pub owner: String,
    pub name: String,
    pub average: f64,
    pub peak: f64,
    pub activity: f64,
    /// 与历史共享的会话活跃证据，独立于规则入选条件。
    pub active: bool,
}
#[derive(Debug)]
pub(crate) struct Suggestion {
    pub owner: String,
    pub name: String,
    pub cpus: String,
    pub reason: String,
    pub average: f64,
    pub peak: f64,
    pub activity: f64,
    pub members: Vec<String>,
}

pub(crate) struct Analyzer {
    cores: Vec<topology::Core>,
    policies: Vec<demand::Policy>,
    limits: [Option<f64>; 64],
    candidates: Vec<Observation>,
    trackers: HashMap<(i32, i32, u64), Tracker>,
    evidence: BTreeMap<(String, String), Evidence>,
    windows: windows::Windows,
    round: usize,
    detail_round: bool,
    hz: f64,
    #[cfg(test)]
    fixture_online: Option<u64>,
}

impl Analyzer {
    pub(crate) fn new() -> Self {
        let cores = topology::read_cores();
        let policies = accounting::policies(&cores).unwrap_or_default();
        #[cfg(any(target_os = "android", target_os = "linux"))]
        let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) } as f64;
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        let hz = 0.0;
        Self {
            cores,
            policies,
            limits: [None; 64],
            candidates: Vec::with_capacity(MAX_CANDIDATES),
            trackers: HashMap::new(),
            evidence: BTreeMap::new(),
            windows: windows::Windows::default(),
            round: 0,
            detail_round: false,
            hz,
            #[cfg(test)]
            fixture_online: None,
        }
    }
    pub(crate) fn begin_round(&mut self, round: usize) {
        self.round = round;
        self.windows.begin();
        self.candidates.clear();
        self.detail_round = round % 4 == 0 && self.hz > 0.0 && !self.policies.is_empty();
    }
    pub(crate) fn wants_detail(&self) -> bool {
        self.detail_round
    }
    pub(crate) fn observe_load(&mut self, owner: &str, name: &str, pct: f64) {
        self.windows.observe(owner, name, pct);
    }
    pub(crate) fn finish_window(&mut self, elapsed: f64) {
        self.windows.finish(elapsed, self.round);
    }
    pub(crate) fn observe(&mut self, mut item: Observation) {
        if !self.detail_round || item.delta == 0 {
            return;
        }
        if self.candidates.len() < HOT_CANDIDATES {
            self.candidates.push(item);
            return;
        }
        let (i, lowest) = self.candidates[..HOT_CANDIDATES]
            .iter()
            .enumerate()
            .min_by_key(|(_, c)| c.delta)
            .unwrap();
        if item.delta > lowest.delta {
            std::mem::swap(&mut self.candidates[i], &mut item);
        }
        // 其余八次读取在非热点工作线程间公平轮换；
        // 每次选择保持两轮详情采样，使计数能够配对。
        let epoch = self.round / 8;
        if self.candidates.len() < MAX_CANDIDATES {
            self.candidates.push(item);
        } else if let Some((i, highest)) = self.candidates[HOT_CANDIDATES..]
            .iter()
            .enumerate()
            .max_by_key(|(_, c)| fair_rank(c, epoch))
        {
            if fair_rank(&item, epoch) < fair_rank(highest, epoch) {
                self.candidates[HOT_CANDIDATES + i] = item;
            }
        }
    }
    pub(crate) fn finish_round(&mut self) {
        if !self.detail_round {
            return;
        }
        let now = Instant::now();
        for (old, new) in self
            .limits
            .iter_mut()
            .zip(accounting::limits(&self.policies))
        {
            if let Some(new) = new {
                *old = Some(old.map_or(new, |old| old.min(new)));
            }
        }
        // 某线程一轮未进入主要候选时，短暂保留其基线；
        // 即使 TID 频繁更替，内存和估计时效也保持有界。
        self.trackers
            .retain(|_, tracker| now.duration_since(tracker.time) <= TRACKER_TTL);
        for item in self.candidates.drain(..) {
            let identity = (item.pid, item.tid, item.start);
            let Ok(residency) = accounting::residency(item.pid, item.tid) else {
                continue;
            };
            if !identity_matches(item.pid, item.tid, item.start) {
                continue;
            }
            let allowed = allowed_cpus(item.pid, item.tid).unwrap_or(0);
            if let Some(old) = self.trackers.remove(&identity) {
                let elapsed = now.duration_since(old.time).as_secs_f64();
                let value = old.estimate(&item, &residency, &self.policies, now, self.hz);
                if let Some(value) = value.filter(|v| v.busy > 0.0 && allowed != 0) {
                    let key = (item.owner.clone(), item.name.clone());
                    if self.evidence.contains_key(&key) || self.evidence.len() < MAX_EVIDENCE {
                        let e = self.evidence.entry(key).or_default();
                        e.observations += 1;
                        e.work += value.units * elapsed;
                        e.busy += value.busy * elapsed;
                        e.serial = e.serial.max(value.units);
                        e.allowed |= allowed;
                        e.last_round = self.round;
                    }
                }
            }
            if self.trackers.len() >= MAX_TRACKERS {
                if let Some(oldest) = self
                    .trackers
                    .iter()
                    .min_by_key(|(_, t)| t.time)
                    .map(|(id, _)| *id)
                {
                    self.trackers.remove(&oldest);
                }
            }
            self.trackers.insert(
                identity,
                Tracker {
                    before: residency,
                    ticks: item.ticks,
                    time: now,
                    owner: item.owner,
                    name: item.name,
                },
            );
        }
        // 复用的 TID 启动时间不同，绝不共享基线。
    }
    pub(crate) fn cores(&self) -> Vec<(usize, u64)> {
        self.cores.iter().map(|c| (c.id, c.capacity)).collect()
    }

    pub(crate) fn suggest(&self, rows: &[ThreadLoad]) -> Vec<Suggestion> {
        let mut owners = BTreeMap::<&str, Vec<&ThreadLoad>>::new();
        for row in rows {
            owners.entry(&row.owner).or_default().push(row);
        }
        let present = self.cores.iter().fold(0, |m, c| m | (1u64 << c.id));
        #[cfg(any(target_os = "android", target_os = "linux"))]
        let online = std::fs::read_to_string("/sys/devices/system/cpu/online")
            .ok()
            .and_then(|s| crate::CpuMask::parse(s.trim()))
            .and_then(|m| m.to_low64())
            .map(|m| m & present)
            .filter(|m| *m != 0);
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        let online = Some(present);
        #[cfg(test)]
        let online = self.fixture_online.or(online);
        let full = online.unwrap_or(present);
        let available: Vec<_> = self
            .cores
            .iter()
            .filter(|c| full & (1u64 << c.id) != 0)
            .cloned()
            .collect();
        let limits = if online.is_some() {
            self.limits
        } else {
            [None; 64]
        };
        let maximum = self.cores.iter().map(|c| c.capacity).max().unwrap_or(0) as f64;
        let mut groups = Vec::new();
        for (owner, rows) in owners {
            let names = rows.iter().map(|r| r.name.clone()).collect::<Vec<_>>();
            let eligible = rows
                .iter()
                .map(|r| r.active && r.average.is_finite() && r.average >= MIN_RULE_AVERAGE_PCT)
                .collect::<Vec<_>>();
            for (patterns, covered) in patterns::families(&names, &eligible) {
                let members: Vec<_> = covered.iter().map(|&i| rows[i]).collect();
                let average: f64 = members.iter().map(|r| r.average).sum();
                let independent_peak: f64 = members.iter().map(|r| r.peak).sum();
                let activity = members.iter().map(|r| r.activity).fold(0.0, f64::max);
                let (mut serial, mut total, mut allowed, mut fallback) = (0.0f64, 0.0, 0u64, false);
                let mut rates = Vec::with_capacity(members.len());
                for row in &members {
                    let e = self.evidence.get(&(owner.to_string(), row.name.clone()));
                    // CPU 时间回退仍会为每个活跃线程族提供建议，
                    // 不能凭空按 MHz 比较不同 SoC。
                    let coverage = self.frequency_coverage(owner, &row.name, e);
                    let scale = if let Some(e) = e.filter(|_| coverage > 0.0) {
                        allowed |= e.allowed;
                        fallback |= coverage < 0.8;
                        // 未测量部分的 CPU 时间继续使用保守速率，
                        // 早期三个样本不能代表完整会话。
                        (e.work / e.busy).min(maximum) * coverage + maximum * (1.0 - coverage)
                    } else {
                        fallback = true;
                        allowed |= full;
                        maximum
                    };
                    let single = planner::workload(
                        row.average / 100.0,
                        row.peak / 100.0,
                        maximum,
                        scale,
                        e.map_or(0.0, |e| e.serial),
                    );
                    // 同时存在的同名工作线程在历史行中可能超过 100%，
                    // 需单独保留它们的总工作量。
                    let burst = (row.peak / 100.0 * scale).max(single);
                    if row.peak > 0.0 {
                        // 窗口权重也要带入占用率和串行性能下限；
                        // 同时饱和的多个线程，在汇总预算中
                        // 必须各自保留该性能下限。
                        rates.push((row.name.as_str(), burst / (row.peak / 100.0)));
                    }
                    serial = serial.max(single);
                    total += burst;
                }
                let joint = self.windows.joint_peak(owner, &rates);
                let peak = joint.as_ref().map_or(independent_peak, |joint| joint.pct);
                if let Some(joint) = &joint {
                    // 保留每个成员的串行负载高水位，
                    // 同时按同一组采样窗口计入总工作量。
                    total = joint.work.max(serial);
                }
                let window_bound = joint.as_ref().is_none_or(|joint| joint.bounded);
                // 窗口 CPU 时间表示吞吐需求，不是精确的可运行并发量；
                // 仅有两个名称不会自动增加一条执行通道。
                let parallel = ((peak / 100.0).ceil() as usize).max(1);
                let demand = planner::Demand {
                    serial,
                    total,
                    parallel,
                    allowed: if allowed & full == 0 {
                        full
                    } else {
                        allowed & full
                    },
                };
                groups.push((
                    patterns
                        .into_iter()
                        .map(|pattern| Suggestion {
                            owner: owner.to_string(),
                            name: pattern,
                            cpus: String::new(),
                            reason: String::new(),
                            average,
                            peak,
                            activity,
                            members: members.iter().map(|r| r.name.clone()).collect(),
                        })
                        .collect::<Vec<_>>(),
                    demand,
                    fallback,
                    window_bound,
                ));
            }
        }
        groups.sort_by(|(a, da, _, _), (b, db, _, _)| {
            db.serial
                .total_cmp(&da.serial)
                .then_with(|| db.total.total_cmp(&da.total))
                .then_with(|| (&a[0].owner, &a[0].name).cmp(&(&b[0].owner, &b[0].name)))
        });
        let mut budget = planner::Budget::new(&available, &limits);
        groups
            .into_iter()
            .flat_map(|(mut rows, demand, fallback, window_bound)| {
                let (mask, reason) = budget.assign(&demand);
                let overlapping = rows.len() > 1;
                for row in &mut rows {
                    row.cpus = (0..64)
                        .filter(|id| mask & (1u64 << id) != 0)
                        .map(|id| id.to_string())
                        .collect::<Vec<_>>()
                        .join(",");
                    row.reason = format!(
                        "{reason}{}{}{}",
                        if fallback {
                            "；部分线程频率证据覆盖不足，未测部分按 CPU 时间保守估算"
                        } else {
                            ""
                        },
                        if overlapping {
                            "；重叠通配规则共用核心池，成员负载只计一次"
                        } else {
                            ""
                        },
                        if window_bound && row.members.len() > 1 {
                            "；组负载含保守窗口上界，不代表精确并发数"
                        } else {
                            ""
                        }
                    );
                }
                rows
            })
            .collect()
    }

    fn frequency_coverage(&self, owner: &str, name: &str, evidence: Option<&Evidence>) -> f64 {
        let Some(e) = evidence.filter(|e| e.observations >= 3 && e.busy > 0.0) else {
            return 0.0;
        };
        let Some(stats) = self.windows.stats(owner, name) else {
            return 0.0;
        };
        if stats.busy_seconds <= 0.0 || stats.last_active_round.saturating_sub(e.last_round) > 24 {
            return 0.0;
        }
        (e.busy / stats.busy_seconds).clamp(0.0, 1.0)
    }
}

fn fair_rank(item: &Observation, epoch: usize) -> u64 {
    // 确定性的轮换采样集合不依赖 proc 目录顺序。
    let mut value = item.start
        ^ (item.pid as u64).rotate_left(17)
        ^ (item.tid as u64).rotate_left(33)
        ^ (epoch as u64).wrapping_mul(0x9e3779b97f4a7c15);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d049bb133111eb);
    value ^ (value >> 31)
}

fn allowed_cpus(pid: i32, tid: i32) -> Option<u64> {
    let text = std::fs::read_to_string(format!("/proc/{pid}/task/{tid}/status")).ok()?;
    let cpus = text
        .lines()
        .find_map(|line| line.strip_prefix("Cpus_allowed_list:"))?;
    crate::CpuMask::parse(cpus.trim())?.to_low64()
}
fn identity_matches(pid: i32, tid: i32, start: u64) -> bool {
    std::fs::read_to_string(format!("/proc/{pid}/task/{tid}/stat"))
        .ok()
        .and_then(|text| {
            text.rsplit_once(')')
                .and_then(|(_, tail)| tail.split_whitespace().nth(19))
                .and_then(|v| v.parse::<u64>().ok())
        })
        == Some(start)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn analyzer() -> Analyzer {
        let mut a = Analyzer::new();
        a.cores = [313, 313, 313, 313, 777, 777, 777, 1024]
            .iter()
            .enumerate()
            .map(|(id, &capacity)| topology::Core { id, capacity })
            .collect();
        a.limits = [Some(1.0); 64];
        // 合成的 870 拓扑不能继承模拟器的 CPU 集合。
        a.fixture_online = Some(255);
        a
    }
    fn row(name: &str, average: f64, peak: f64) -> ThreadLoad {
        ThreadLoad {
            owner: "com.test".into(),
            name: name.into(),
            average,
            peak,
            activity: 99.0,
            active: true,
        }
    }
    #[test]
    fn qualifying_screenshot_threads_and_dynamic_names_form_one_budget() {
        let a = analyzer();
        let rows = [
            row("kuaishou.nebula", 16.2, 72.1),
            row("thread-shared-2", 14.9, 44.1),
            row("RenderThread", 16.6, 29.7),
            row("thread-shared-1", 11.0, 31.7),
            row("thread-shared-3", 2.4, 21.7),
            row("thread-shared-9", 1.8, 17.8),
            row("low", 4.99, 80.0),
            row("boundary", 5.0, 12.0),
        ];
        let result = a.suggest(&rows);
        assert_eq!(result.len(), 4);
        assert!(result.iter().all(|r| !r.cpus.is_empty()));
        assert!(!result.iter().any(|r| r.name == "low"));
        assert!(result.iter().any(|r| r.name == "boundary"));
        let shared = result.iter().find(|r| r.name == "thread-shared-*").unwrap();
        assert_eq!(shared.members.len(), 4);
        assert!((shared.average - 30.1).abs() < 0.01);
        assert!(shared.cpus.split(',').count() >= 2);
    }
    #[test]
    fn changed_tids_or_many_unrelated_names_do_not_disable_recommendations() {
        let mut a = analyzer();
        a.detail_round = true;
        for tid in 1..10000 {
            a.observe(Observation {
                pid: 1,
                tid,
                start: tid as u64,
                ticks: 1,
                delta: tid as u64,
                owner: "com.test".into(),
                name: "RenderThread".into(),
            });
        }
        assert_eq!(a.candidates.len(), 24);
        assert!(!a.suggest(&[row("RenderThread", 16.6, 29.7)])[0]
            .cpus
            .is_empty());
    }
    #[test]
    fn busy_at_low_frequency_cannot_be_misread_as_a_light_thread() {
        let mut a = analyzer();
        a.evidence.insert(
            ("com.test".into(), "GameMain".into()),
            Evidence {
                observations: 20,
                work: 120.0,
                busy: 1.0,
                serial: 120.0,
                allowed: 255,
                last_round: 0,
            },
        );
        let result = a.suggest(&[row("GameMain", 80.0, 95.0)]);
        let cpus = crate::CpuMask::parse(&result[0].cpus)
            .unwrap()
            .to_low64()
            .unwrap();
        assert_eq!(
            cpus & 15,
            0,
            "A sustained heavy thread must not be confined to little cores"
        );
        assert!(cpus & 128 != 0);
    }
    #[test]
    fn groups_are_process_scoped_and_low_peers_are_budgeted() {
        let a = analyzer();
        let mut child = row("worker-3", 10.0, 25.0);
        child.owner = "com.test:child".into();
        let result = a.suggest(&[
            row("worker-1", 6.0, 20.0),
            row("worker-2", 1.0, 10.0),
            child,
        ]);
        assert_eq!(result.len(), 2);
        assert_eq!(
            result
                .iter()
                .find(|r| r.owner == "com.test")
                .unwrap()
                .average,
            7.0
        );
    }
    #[test]
    fn active_threads_need_a_five_percent_average_before_grouping() {
        let a = analyzer();
        let result = a.suggest(&[row("LightWorker", 0.8, 2.0), row("SceneLoader", 0.2, 40.0)]);
        assert!(result.is_empty());
        let families = a.suggest(&[row("worker-1", 3.0, 20.0), row("worker-2", 3.0, 15.0)]);
        assert!(
            families.is_empty(),
            "sub-threshold members cannot qualify through their sum"
        );
        let result = a.suggest(&[
            row("Below", 4.99, 90.0),
            row("Boundary", 5.0, 12.0),
            row("Invalid", f64::INFINITY, 100.0),
        ]);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].name, "Boundary");
        assert!(!result[0].cpus.is_empty());
    }
    #[test]
    fn incidental_wakeups_do_not_create_rules_even_with_a_high_average() {
        let a = analyzer();
        let mut wake = row("OneWake", 8.0, 90.0);
        wake.active = false;
        let mut sleeping = row("Sleeping", 0.0, 0.0);
        sleeping.active = false;
        assert!(a.suggest(&[wake.clone(), sleeping]).is_empty());
        let result = a.suggest(&[row("LightWorker", 5.0, 12.0), wake]);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].name, "LightWorker");
    }
    #[test]
    fn inactive_wildcard_peers_still_count_toward_the_shared_budget() {
        let a = analyzer();
        let mut peer = row("worker-2", 0.1, 15.0);
        peer.active = false;
        let result = a.suggest(&[row("worker-1", 5.0, 12.0), peer]);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].name, "worker-*");
        assert_eq!(result[0].members.len(), 2);
        assert!((result[0].average - 5.1).abs() < 1e-6);
    }
    #[test]
    fn wildcard_naming_alone_does_not_force_dual_prime_assignment() {
        let mut a = analyzer();
        for c in &mut a.cores {
            c.capacity = if c.id >= 6 { 1024 } else { 800 };
        }
        let result = a.suggest(&[row("worker-5", 50., 65.)]);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].name, "worker-*");
        assert_eq!(result[0].cpus, "6");
        let grouped = a.suggest(&[row("worker-5", 50., 65.), row("worker-7", 45., 60.)]);
        assert_eq!(grouped.len(), 1);
        assert!(grouped[0].cpus.split(',').count() >= 2);
    }
    #[test]
    fn high_load_suggestions_use_the_shared_restricted_pool_instead_of_all_cpus() {
        let mut a = analyzer();
        for c in &mut a.cores {
            c.capacity = if c.id >= 6 { 1024 } else { 800 };
        }
        let result = a.suggest(&[row("GameMain", 85., 95.), row("RenderThread", 80., 90.)]);
        assert_eq!(result.len(), 2);
        assert!(result.iter().all(|r| r.cpus == "6,7"));
        assert!(result.iter().all(|r| r.reason.contains("受限候选组")));
    }

    #[test]
    fn alternating_family_uses_shared_window_peak_without_forcing_a_second_lane() {
        let mut a = analyzer();
        for round in 0..40 {
            a.begin_round(round);
            a.observe_load(
                "com.test",
                if round % 2 == 0 {
                    "worker-1"
                } else {
                    "worker-2"
                },
                30.0,
            );
            a.finish_window(0.5);
        }
        let result = a.suggest(&[row("worker-1", 15.0, 30.0), row("worker-2", 15.0, 30.0)]);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].peak, 30.0);
        assert_eq!(result[0].cpus.split(',').count(), 1);
    }

    #[test]
    fn same_window_family_load_keeps_multicore_throughput_and_serial_peak() {
        let mut a = analyzer();
        a.begin_round(0);
        a.observe_load("com.test", "worker-1", 85.0);
        a.observe_load("com.test", "worker-2", 80.0);
        a.finish_window(0.5);
        let result = a.suggest(&[row("worker-1", 42.5, 85.0), row("worker-2", 40.0, 80.0)]);
        assert_eq!(result[0].peak, 165.0);
        assert!(result[0].cpus.split(',').count() >= 2);
        let alone = a.suggest(&[row("worker-1", 42.5, 85.0)]);
        assert!(
            alone[0].cpus.contains('7'),
            "a serial burst keeps the strongest candidate"
        );
    }

    #[test]
    fn incomplete_window_names_keep_the_independent_upper_bound() {
        let mut a = analyzer();
        a.begin_round(0);
        a.observe_load("com.test", "worker-1", 60.0);
        a.finish_window(0.5);
        let result = a.suggest(&[row("worker-1", 30.0, 60.0), row("worker-2", 25.0, 50.0)]);
        assert_eq!(result[0].peak, 110.0);
        assert!(result[0].reason.contains("保守窗口上界"));
    }

    #[test]
    fn early_frequency_samples_need_cpu_time_coverage_and_recent_activity() {
        let mut a = analyzer();
        for round in 0..80 {
            a.begin_round(round);
            a.observe_load("com.test", "GameMain", 50.0);
            a.finish_window(0.5);
        }
        let mut e = Evidence {
            observations: 3,
            work: 300.0,
            busy: 1.0,
            serial: 150.0,
            allowed: 255,
            last_round: 4,
        };
        assert_eq!(a.frequency_coverage("com.test", "GameMain", Some(&e)), 0.0);
        e.last_round = 79;
        assert!((a.frequency_coverage("com.test", "GameMain", Some(&e)) - 0.05).abs() < 1e-6);
        e.busy = 18.0;
        assert!((a.frequency_coverage("com.test", "GameMain", Some(&e)) - 0.9).abs() < 1e-6);
    }

    #[test]
    fn detail_budget_preserves_hot_workers_and_rotates_other_workers_in_pairs() {
        let mut a = analyzer();
        let mut selected = Vec::new();
        let mut seen = std::collections::BTreeSet::new();
        for detail in 0..20 {
            a.begin_round(detail * 4);
            a.detail_round = true;
            for tid in 1..=64 {
                a.observe(Observation {
                    pid: 1,
                    tid,
                    start: 1,
                    ticks: 100,
                    delta: if tid <= 16 { 100 } else { 5 },
                    owner: "com.test".into(),
                    name: format!("worker-{tid}"),
                });
            }
            assert_eq!(a.candidates.len(), 24);
            assert!(a.candidates[..16].iter().all(|c| c.tid <= 16));
            let set: std::collections::BTreeSet<_> =
                a.candidates[16..].iter().map(|c| c.tid).collect();
            seen.extend(set.iter().copied());
            if detail % 2 == 1 {
                assert_eq!(selected.last().unwrap(), &set);
            }
            selected.push(set);
        }
        assert!(
            seen.len() > 24,
            "low-load candidates must not stay permanently excluded"
        );
    }

    #[test]
    fn skipped_detail_round_keeps_a_recent_baseline_but_expires_an_old_one() {
        let mut a = analyzer();
        a.detail_round = true;
        a.policies.clear();
        let now = Instant::now();
        for (tid, age) in [(1, 2), (2, 11)] {
            a.trackers.insert(
                (1, tid, 1),
                Tracker {
                    before: demand::Residency(BTreeMap::new()),
                    ticks: 10,
                    time: now - Duration::from_secs(age),
                    owner: "com.test".into(),
                    name: "worker".into(),
                },
            );
        }
        a.finish_round();
        assert!(a.trackers.contains_key(&(1, 1, 1)));
        assert!(!a.trackers.contains_key(&(1, 2, 1)));
    }

    #[test]
    fn unchanged_tid_cannot_attribute_a_previous_roles_cpu_time_to_a_new_label() {
        let now = Instant::now();
        let policies = [demand::Policy {
            cpu: 0,
            members: 1,
            max_khz: 1000,
            capacity: 1024.0,
        }];
        let residency = |ticks| demand::Residency::parse(&format!("cpu0\n1000 {ticks}")).unwrap();
        let before = Tracker {
            before: residency(100),
            ticks: 100,
            time: now,
            owner: "com.test".into(),
            name: "Loader".into(),
        };
        let mut item = Observation {
            pid: 1,
            tid: 1,
            start: 1,
            ticks: 200,
            delta: 100,
            owner: "com.test".into(),
            name: "Loader".into(),
        };
        let second = now + Duration::from_secs(2);
        assert_eq!(
            before
                .estimate(&item, &residency(200), &policies, second, 100.0)
                .unwrap()
                .busy,
            0.5
        );
        item.name = "RenderThread".into();
        assert!(before
            .estimate(&item, &residency(200), &policies, second, 100.0)
            .is_none());
        item.name = "Loader".into();
        item.owner = "com.test:child".into();
        assert!(before
            .estimate(&item, &residency(200), &policies, second, 100.0)
            .is_none());

        // 重建基线后只允许累计新名称下完成的工作，
        // 旧的 100 个节拍不能抬高其 CPU 时间覆盖量。
        let reset = Tracker {
            before: residency(200),
            ticks: 200,
            time: second,
            owner: item.owner.clone(),
            name: item.name.clone(),
        };
        item.ticks = 220;
        let value = reset
            .estimate(
                &item,
                &residency(220),
                &policies,
                second + Duration::from_secs(2),
                100.0,
            )
            .unwrap();
        assert_eq!(value.busy, 0.1);
    }
}
