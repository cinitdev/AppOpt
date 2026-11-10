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
    pub active_threads: Option<usize>,
    pub peak_bounded: bool,
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
    pub(crate) fn pause_sampling(&mut self) {
        // 保留已完成的校准证据，但不跨主进程中断计算驻留时间和负载。
        self.trackers.clear();
        self.candidates.clear();
        self.windows.begin();
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
                let active_threads = members.iter().map(|row| {
                    self.windows.stats(owner, &row.name).map(|stats| stats.max_active_threads)
                }).collect::<Option<Vec<_>>>().map(|counts| counts.into_iter().sum());
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
                            active_threads,
                            peak_bounded: window_bound,
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
