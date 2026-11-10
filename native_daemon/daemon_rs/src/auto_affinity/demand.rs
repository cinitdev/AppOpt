//! 积分各频率下的实际 CPU 时间，不使用区间结束时的单点频率。
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug)]
pub(super) struct Policy {
    pub cpu: usize,
    pub members: u64,
    pub max_khz: u64,
    pub capacity: f64,
}

#[derive(Clone, Debug)]
pub(super) struct Residency(pub BTreeMap<(usize, u64), u64>);

impl Residency {
    pub fn parse(text: &str) -> Option<Self> {
        if text.len() > 65536 {
            return None;
        }
        let mut bins = BTreeMap::new();
        let mut groups = BTreeSet::new();
        let mut cpu = None;
        for line in text.lines().map(str::trim).filter(|line| !line.is_empty()) {
            if let Some(id) = line.strip_prefix("cpu") {
                let id = id.parse::<usize>().ok().filter(|id| *id < 64)?;
                if !groups.insert(id) {
                    return None;
                }
                cpu = Some(id);
            } else {
                let mut fields = line.split_whitespace();
                let khz = fields
                    .next()?
                    .parse::<u64>()
                    .ok()
                    .filter(|f| *f > 0 && *f <= 20_000_000)?;
                let ticks = fields.next()?.parse::<u64>().ok()?;
                if fields.next().is_some()
                    || bins.insert((cpu?, khz), ticks).is_some()
                    || bins.len() > 2048
                {
                    return None;
                }
            }
        }
        if bins.is_empty()
            || groups
                .iter()
                .any(|cpu| !bins.keys().any(|key| key.0 == *cpu))
        {
            return None;
        }
        Some(Self(bins))
    }
}

#[derive(Clone, Copy, Debug)]
pub(super) struct Demand {
    /// 包含时钟节拍取整误差的上界估计，单位为内核容量。
    pub units: f64,
    pub busy: f64,
}

pub(super) fn estimate(
    before: &Residency,
    after: &Residency,
    policies: &[Policy],
    cpu_ticks: u64,
    elapsed: f64,
    hz: f64,
) -> Option<Demand> {
    if !elapsed.is_finite()
        || !(0.8..=12.0).contains(&elapsed)
        || !hz.is_finite()
        || hz <= 0.0
        || before.0.len() != after.0.len()
    {
        return None;
    }
    let busy = cpu_ticks as f64 / hz / elapsed;
    if !(0.0..=1.05).contains(&busy) {
        return None;
    }
    let max_capacity = policies.iter().map(|p| p.capacity).fold(0.0, f64::max);
    if max_capacity <= 0.0 {
        return None;
    }
    let mut work = 0.0;
    let mut observed_ticks = 0u64;
    let mut rounding_work = 0.0;
    let mut changed_bins = 0;
    let mut fastest: f64 = 0.0;
    let mut seen = BTreeMap::new();
    for (key, ticks) in &after.0 {
        let old = *before.0.get(key)?;
        let delta = ticks.checked_sub(old)?;
        let policy = policies
            .iter()
            .find(|policy| policy.members & (1u64 << key.0) != 0)?;
        // 非连续的频率策略可能在另一 CPU 编号下重复列出；
        // 重复频率域的计数必须一致，且绝不能重复累计。
        if let Some(previous) = seen.insert((policy.cpu, key.1), (old, *ticks)) {
            if previous != (old, *ticks) {
                return None;
            }
            continue;
        }
        if policy.max_khz == 0
            || key.1 > policy.max_khz
            || !policy.capacity.is_finite()
            || policy.capacity <= 0.0
        {
            return None;
        }
        if delta == 0 {
            continue;
        }
        let speed = policy.capacity * key.1 as f64 / policy.max_khz as f64;
        observed_ticks = observed_ticks.checked_add(delta)?;
        work += delta as f64 * speed;
        // 每个导出的频率档位都独立按时钟节拍取整。
        rounding_work += speed;
        changed_bins += 1;
        fastest = fastest.max(policy.capacity);
    }
    // 记账关闭或单位不兼容时，不能将其当作零负载。
    if cpu_ticks == 0
        || observed_ticks == 0
        || fastest == 0.0
        || cpu_ticks.abs_diff(observed_ticks) > changed_bins + 3
        || observed_ticks as f64 > elapsed * hz * 1.05 + changed_bins as f64
    {
        return None;
    }
    let missing = cpu_ticks.saturating_sub(observed_ticks) as f64;
    let upper = (work + rounding_work + missing * max_capacity) / hz / elapsed;
    Some(Demand {
        units: upper.min((busy + 1.0 / hz / elapsed) * max_capacity),
        busy,
    })
}
