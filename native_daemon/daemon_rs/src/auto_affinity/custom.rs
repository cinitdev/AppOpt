//! 自定义档位只描述平均负载到核心范围的映射，不执行设备 IO。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rule {
    pub threshold: u8,
    pub mask: u64,
}

fn number(value: &str, max: u8) -> Option<u8> {
    if value.is_empty() || value.len() > 3 || !value.bytes().all(|b| b.is_ascii_digit())
        || (value.len() > 1 && value.starts_with('0')) { return None; }
    value.parse::<u8>().ok().filter(|n| *n <= max)
}

pub fn parse(value: &str) -> Option<Vec<Rule>> {
    if value.len() > 2048 { return None; }
    let mut rules = Vec::new();
    for part in value.split('|') {
        if rules.len() >= 8 { return None; }
        let (threshold, cpus) = part.split_once(':')?;
        let threshold = number(threshold, 100).filter(|n| *n > 0)?;
        let mut mask = 0;
        for range in cpus.split('+') {
            let (first, last) = range.split_once('-').unwrap_or((range, range));
            let first = number(first, 63)?;
            let last = number(last, 63)?;
            if first > last { return None; }
            for cpu in first..=last { mask |= 1u64 << cpu; }
        }
        if mask == 0 || rules.iter().any(|rule: &Rule| rule.threshold == threshold) { return None; }
        rules.push(Rule { threshold, mask });
    }
    rules.sort_by_key(|rule| rule.threshold);
    Some(rules)
}

pub(super) fn threshold(rules: &[Rule]) -> f64 {
    rules.first().map_or(0.05, |rule| rule.threshold as f64 / 100.0)
}

/// 取最高已达标档位；目标无可用核心时不擅自使用其他档位或默认算法。
pub(super) fn target(rules: &[Rule], activity: &super::live_policy::Activity, available: u64) -> Option<u64> {
    rules.iter().rev().find(|rule| activity.eligible_at(rule.threshold as f64 / 100.0))
        .map(|rule| rule.mask & available).filter(|mask| *mask != 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auto_affinity::live_policy::Activity;

    #[test]
    fn validates_and_orders_ranges_without_assuming_a_device_topology() {
        let rules = parse("80:63|2:0-3+6|40:4-7").unwrap();
        assert_eq!(rules, vec![Rule { threshold: 2, mask: 79 }, Rule { threshold: 40, mask: 240 }, Rule { threshold: 80, mask: 1u64 << 63 }]);
        for bad in ["", "0:0", "101:0", "5.5:0", "05:0", "5:64", "5:3-1", "5:0|5:1", "5:", "5:0+", "5:0:1", "5:01", "5:0|", "5:0,1"] {
            assert!(parse(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn custom_threshold_bypasses_five_percent_and_uses_highest_matching_tier() {
        let rules = parse("2:0-3|20:4-6|60:7").unwrap();
        assert_eq!(threshold(&rules), 0.02);
        for (busy, expected) in [(0.01999, None), (0.02, Some(15)), (0.19999, Some(15)), (0.2, Some(112)), (0.6, Some(128)), (1.0, Some(128))] {
            let mut activity = Activity::default();
            activity.observe(busy, 0.5);
            assert_eq!(target(&rules, &activity, 255), expected, "busy={busy}");
        }
        let mut activity = Activity::default();
        activity.observe(0.8, 0.5);
        assert_eq!(target(&rules, &activity, 127), None);
        assert_eq!(target(&parse("2:0-3").unwrap(), &activity, 254), Some(14));
        assert_eq!(target(&rules, &Activity::default(), 255), None);
    }
}
