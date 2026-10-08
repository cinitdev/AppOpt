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

#[cfg(test)]
mod tests {
    use super::*;
    fn policy(cpu: usize, capacity: f64, max_khz: u64) -> Policy {
        Policy {
            cpu,
            members: 1 << cpu,
            capacity,
            max_khz,
        }
    }
    #[test]
    fn identical_work_at_half_frequency_has_the_same_normalized_demand() {
        let policies = [policy(4, 800.0, 2000)];
        let zero = Residency::parse("cpu4\n1000 0\n2000 0").unwrap();
        let fast = Residency::parse("cpu4\n1000 0\n2000 200").unwrap();
        let slow = Residency::parse("cpu4\n1000 400\n2000 0").unwrap();
        let a = estimate(&zero, &fast, &policies, 200, 5.0, 100.0).unwrap();
        let b = estimate(&zero, &slow, &policies, 400, 5.0, 100.0).unwrap();
        assert!((a.units - b.units).abs() < 2.0);
        assert_eq!((a.busy, b.busy), (0.4, 0.8));
    }
    #[test]
    fn migration_integrates_both_clusters_instead_of_using_the_last_cpu() {
        let policies = [policy(0, 300.0, 1000), policy(7, 1000.0, 2000)];
        let zero = Residency::parse("cpu0\n1000 0\ncpu7\n2000 0").unwrap();
        let end = Residency::parse("cpu0\n1000 100\ncpu7\n2000 100").unwrap();
        let demand = estimate(&zero, &end, &policies, 200, 5.0, 100.0).unwrap();
        assert!((demand.units - 262.6).abs() < 0.001);
    }
    #[test]
    fn incompatible_or_missing_residency_cannot_be_used_as_zero_demand() {
        let policies = [policy(0, 300.0, 1000)];
        let a = Residency::parse("cpu0\n1000 100").unwrap();
        let reset = Residency::parse("cpu0\n1000 99").unwrap();
        assert!(estimate(&a, &reset, &policies, 80, 1.0, 100.0).is_none());
        assert!(estimate(&a, &a, &policies, 80, 1.0, 100.0).is_none());
        let wrong = Residency::parse("cpu0\n1000 900").unwrap();
        assert!(estimate(&a, &wrong, &policies, 80, 1.0, 100.0).is_none());
        for text in [
            "1000 1",
            "cpu0\n1000 1\n1000 2",
            "cpu64\n1000 1",
            "cpu0\n0 1",
            "cpu0\ncpu1\n1000 1",
        ] {
            assert!(Residency::parse(text).is_none());
        }
    }

    #[test]
    fn noncontiguous_policy_aliases_are_counted_once() {
        let policies = [Policy {
            cpu: 0,
            members: 5,
            max_khz: 1000,
            capacity: 800.0,
        }];
        let zero = Residency::parse("cpu0\n1000 0\ncpu2\n1000 0").unwrap();
        let end = Residency::parse("cpu0\n1000 100\ncpu2\n1000 100").unwrap();
        let result = estimate(&zero, &end, &policies, 100, 5.0, 100.0).unwrap();
        assert!((result.units - 161.6).abs() < 0.001);
        let mismatch = Residency::parse("cpu0\n1000 100\ncpu2\n1000 101").unwrap();
        assert!(estimate(&zero, &mismatch, &policies, 100, 5.0, 100.0).is_none());
    }
}
