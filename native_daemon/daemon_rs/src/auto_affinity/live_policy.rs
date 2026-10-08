//! 有界的近期活跃统计与稳定分配决策，不执行设备 IO。
use std::collections::VecDeque;
use std::time::{Duration, Instant};

pub(super) const SAMPLE_INTERVAL: Duration = Duration::from_millis(500);
pub(super) const MAX_DETAILS: usize = 24;
pub(super) const MAX_SCAN: usize = 512;
pub(super) const MAX_TRACKED: usize = 4096;
pub(super) const MAX_ACTIVE: usize = MAX_TRACKED;
const MISSING_SAMPLE_GRACE: Duration = Duration::from_millis(3300);
const ACTIVITY_WINDOW_SECONDS: f64 = 6.0;

/// 帧反馈较差时，请求普通规划器临时增加余量；
/// 不撤销接管，也不改变活跃度入选门槛。
#[derive(Default)]
pub(super) struct FeedbackPressure {
    until: Option<Instant>,
    next_review: Option<Instant>,
}
impl FeedbackPressure {
    pub(super) fn activate(&mut self, now: Instant) -> bool {
        if self.next_review.is_some_and(|next| now < next) { return false; }
        self.until = Some(now + Duration::from_secs(8));
        self.next_review = Some(now + Duration::from_secs(16));
        true
    }
    pub(super) fn workload(&self, now: Instant, units: f64) -> f64 {
        if self.until.is_some_and(|until| now < until) { units * 1.25 } else { units }
    }
}

#[derive(Default)]
pub(super) struct Activity {
    samples: VecDeque<(f64, f64)>,
    quiet: u8,
}
impl Activity {
    pub(super) fn observe(&mut self, busy: f64, elapsed: f64) -> bool {
        if !busy.is_finite()
            || !(0.0..=1.05).contains(&busy)
            || !elapsed.is_finite()
            || !(0.25..=12.0).contains(&elapsed)
        {
            *self = Self::default();
            return false;
        }
        self.quiet = if busy < 0.01 {
            self.quiet.saturating_add(1)
        } else {
            0
        };
        self.samples.push_back((busy, elapsed));
        // 按经过时间保留样本，而不是固定样本数；加快维护周期
        // 不能缩短活跃窗口并导致反复释放。
        let mut excess = self.samples.iter().map(|v| v.1).sum::<f64>() - ACTIVITY_WINDOW_SECONDS;
        while excess > 0.0 {
            let Some((_, duration)) = self.samples.front_mut() else { break; };
            if *duration > excess {
                *duration -= excess;
                break;
            }
            excess -= *duration;
            self.samples.pop_front();
        }
        true
    }
    pub(super) fn average(&self) -> f64 {
        let duration: f64 = self.samples.iter().map(|v| v.1).sum();
        if duration > 0.0 {
            self.samples.iter().map(|v| v.0 * v.1).sum::<f64>() / duration
        } else {
            0.0
        }
    }
    pub(super) fn peak(&self) -> f64 {
        self.samples.iter().map(|v| v.0).fold(0.0, f64::max)
    }
    pub(super) fn eligible(&self) -> bool {
        // 入选判断应与报告和释放判断所用均值一致。围绕阈值比较
        // 还能保护恒定 5% 不受除法舍入影响，
        // 两种表示都不能拒绝对方恰好达到边界的情况。
        !self.samples.is_empty()
            && (self.average() >= 0.05
                || self.samples.iter().map(|v| (v.0 - 0.05) * v.1).sum::<f64>() >= 0.0)
    }
    pub(super) fn dormant(&self) -> bool {
        self.quiet >= 2
    }
    pub(super) fn keep_owned_without_sample(&self, age: Duration) -> bool {
        self.eligible() && age <= MISSING_SAMPLE_GRACE
    }
}

/// 所有符合条件且已跟踪的线程都参与分配；除现有身份跟踪资源上限外，
/// 不再设置更小的接纳数量限制。
pub(super) fn select_active<T: Ord>(candidates: impl IntoIterator<Item = (T, bool, f64)>) -> Vec<T> {
    let mut candidates: Vec<_> = candidates.into_iter().collect();
    candidates.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| b.2.total_cmp(&a.2)).then(a.0.cmp(&b.0)));
    candidates.truncate(MAX_ACTIVE);
    candidates.into_iter().map(|(key, _, _)| key).collect()
}

#[derive(Default)]
pub(super) struct Placement {
    candidate: u64,
    rounds: u8,
    changed: Option<Instant>,
    accounting_changed: Option<Instant>,
    review_until: Option<Instant>,
}
impl Placement {
    pub(super) fn choose_available(
        &mut self,
        now: Instant,
        current: u64,
        desired: u64,
        available: u64,
        current_fits: bool,
        materially_better: bool,
    ) -> Option<u64> {
        let desired = desired & available;
        if desired == 0 { return None; }
        // 停核改变的是可行性，不是带噪声的负载排名。
        // 当前核心池含不可用 CPU 时，不能等待驻留期结束。
        if current & !available != 0 {
            self.candidate = desired;
            self.rounds = 0;
            return Some(desired);
        }
        self.choose(now, current, desired, current_fits, materially_better)
    }

    pub(super) fn choose(
        &mut self,
        now: Instant,
        current: u64,
        desired: u64,
        current_fits: bool,
        materially_better: bool,
    ) -> Option<u64> {
        if desired == 0 {
            return None;
        }
        // 活跃度已按近期平均负载达到 5% 检查。
        // 新接管线程没有需要防止迁移的旧分配；
        // 若等待排名完全一致，可能永远无法接纳。
        if current == 0 {
            self.candidate = desired;
            self.rounds = 0;
            return Some(desired);
        }
        if desired == current {
            self.candidate = desired;
            self.rounds = 0;
            return Some(current);
        }
        self.rounds = if self.candidate == desired {
            self.rounds.saturating_add(1)
        } else {
            1
        };
        self.candidate = desired;
        // 持续压力下，两个核心池都可能达不到完整余量；
        // 排名的小幅变化不足以成为持续迁移热缓存的理由。
        if current != 0 && !current_fits && !materially_better {
            self.rounds = 0;
            return Some(current);
        }
        // 及时离开容量不足的执行通道，包括迁往另一颗单核。
        // 保留新旧掩码并集会使驻留期内的范围无限扩大，
        // 不能作为有效的防振荡策略。
        if current != 0 && (!current_fits || desired & current == current) {
            return Some(desired);
        }
        if self.rounds < 2 {
            return (current != 0).then_some(current);
        }
        if self
            .changed
            .is_some_and(|time| now.saturating_duration_since(time) < Duration::from_secs(8))
            && self.review_until.is_none_or(|until| now >= until)
        {
            return (current != 0).then_some(current);
        }
        Some(desired)
    }
    pub(super) fn written(&mut self, now: Instant) {
        self.changed = Some(now);
        self.accounting_changed = Some(now);
        self.review_until = None;
    }
    pub(super) fn reasserted(&mut self, now: Instant) {
        // 发生偏移的区间可能包含另一 CPU 上的工作。重新接管同一分配
        // 会使固定核心记账失效，但不会重置迁移驻留期。
        self.accounting_changed = Some(now);
    }
    pub(super) fn settled_before(&self, start: Instant) -> bool {
        self.accounting_changed.is_some_and(|changed| changed < start)
    }
    pub(super) fn wait_for_accounting(&self, start: Instant, current: u64, available: u64, stronger_lane: bool) -> bool {
        // 刚写入后，/proc/stat 仍可能包含我们在上一颗 CPU 上的工作。
        // 保守并集上界不能证明另一条同等或更弱的执行通道确实更好。
        // 应保持一个完整的实测区间，
        // 避免反复追逐自身造成的 CPU 时间。
        current != 0 && current & !available == 0 && !stronger_lane && !self.settled_before(start)
    }
    pub(super) fn request_review(&mut self, now: Instant) {
        self.review_until = Some(now + Duration::from_secs(8));
    }
}

/// 仅扣除已确认在整个采样区间都位于同一受管 CPU 上的工作；
/// 多 CPU 掩码继续保留现有的不确定性上界。
pub(super) fn external_with_pinned(
    mut raw: [Option<f64>; 64],
    pinned: &[f64; 64],
    mut uncertain: u64,
    mut uncertain_busy: f64,
) -> [Option<f64>; 64] {
    for (cpu, &busy) in pinned.iter().enumerate() {
        if busy <= 0.0 { continue; }
        if let Some(load) = raw[cpu].filter(|load| busy.is_finite() && busy <= *load + 0.05) {
            raw[cpu] = Some((load - busy).max(0.0));
        } else {
            uncertain |= 1u64 << cpu;
            uncertain_busy += busy;
        }
    }
    external_loads(raw, uncertain, uncertain_busy)
}

/// 入选线程的 CPU 时间已包含在 /proc/stat 中。只在已验证的允许范围并集内
/// 扣除这些时间，并为每颗核心保留保守的外部负载上界，
/// 避免后续轮次重复计算自身工作量。
pub(super) fn external_loads(raw: [Option<f64>; 64], allowed: u64, busy: f64) -> [Option<f64>; 64] {
    let mut result = raw;
    let mut total = 0.0;
    for (cpu, load) in raw
        .iter()
        .enumerate()
        .filter(|(cpu, _)| allowed & (1u64 << cpu) != 0)
    {
        let Some(load) = load.filter(|v| v.is_finite() && (0.0..=1.0).contains(v)) else {
            return raw;
        };
        let _ = cpu;
        total += load;
    }
    if !busy.is_finite() || busy < 0.0 || busy > total + 0.05 {
        return raw;
    }
    let external = (total - busy).max(0.0);
    for (cpu, load) in result.iter_mut().enumerate() {
        if allowed & (1u64 << cpu) != 0 {
            *load = load.map(|v| v.min(external));
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn active_average_not_frame_rate_or_two_quiet_samples_controls_selection() {
        let mut active = Activity::default();
        for _ in 0..3 {
            active.observe(0.05, 1.1);
        }
        assert!(active.eligible());
        active.observe(0.0, 1.1);
        active.observe(0.0, 1.1);
        assert!(active.dormant());
        assert!(!active.eligible());
        let mut light = Activity::default();
        for _ in 0..100 {
            light.observe(0.049, 1.1);
        }
        assert!(!light.eligible());
        assert_eq!(light.samples.len(), 6);
        let mut burst = Activity::default();
        for _ in 0..3 { burst.observe(0.3, 1.1); }
        burst.observe(0.0, 1.1);
        burst.observe(0.0, 1.1);
        assert!(burst.dormant());
        assert!(burst.average() >= 0.05);
        assert!(burst.eligible());
        for _ in 0..4 { burst.observe(0.0, 1.1); }
        assert!(burst.average() < 0.05);
        assert!(!burst.eligible());
    }
    #[test]
    fn gaps_and_invalid_counters_cannot_keep_old_activity_alive() {
        let mut active = Activity::default();
        for _ in 0..3 {
            active.observe(0.8, 1.1);
        }
        assert!(active.eligible());
        assert!(!active.observe(0.8, 15.0));
        assert!(!active.eligible());
        assert!(!active.observe(f64::NAN, 1.1));
    }
    #[test]
    fn a_missing_sample_briefly_preserves_an_active_owner_without_admitting_light_work() {
        let mut active = Activity::default();
        for _ in 0..3 { active.observe(0.05, 1.1); }
        assert!(active.keep_owned_without_sample(Duration::from_millis(3300)));
        assert!(!active.keep_owned_without_sample(Duration::from_millis(3301)));
        active.observe(0.0, 1.1);
        assert!(!active.keep_owned_without_sample(Duration::ZERO));
        let mut sleeping = Activity::default();
        for _ in 0..3 { sleeping.observe(0.8, 1.1); }
        sleeping.observe(0.0, 1.1);
        sleeping.observe(0.0, 1.1);
        assert!(sleeping.average() >= 0.05);
        assert!(sleeping.keep_owned_without_sample(Duration::ZERO));
    }
    #[test]
    fn all_eligible_tracked_threads_participate_beyond_the_old_admission_quota() {
        let eligible = select_active((0..129).map(|key| (key, key == 0, 0.05)));
        assert_eq!(eligible.len(), 129);
        assert!(eligible.contains(&128));
        assert_eq!(MAX_ACTIVE, MAX_TRACKED);
        let selected = select_active((0..MAX_ACTIVE + 2).map(|key| {
            (key, key == 0, if key == 0 { 0.05 } else { 0.9 })
        }));
        assert_eq!(selected.len(), MAX_ACTIVE);
        assert_eq!(selected[0], 0);
        assert!(selected.contains(&(MAX_ACTIVE - 1)));
        assert!(!selected.contains(&MAX_ACTIVE));
    }
    #[test]
    fn frame_pressure_expires_and_reconsiders_placement_without_releasing_it() {
        let now = Instant::now();
        let mut pressure = FeedbackPressure::default();
        assert_eq!(pressure.workload(now, 400.0), 400.0);
        assert!(pressure.activate(now));
        assert!(!pressure.activate(now + Duration::from_secs(7)));
        assert_eq!(pressure.workload(now + Duration::from_secs(7), 400.0), 500.0);
        assert_eq!(pressure.workload(now + Duration::from_secs(8), 400.0), 400.0);
        assert!(!pressure.activate(now + Duration::from_secs(15)));
        assert!(pressure.activate(now + Duration::from_secs(16)));
        let mut placement = Placement::default();
        placement.written(now);
        placement.request_review(now);
        assert!(placement.settled_before(now + Duration::from_millis(1)));
        assert_eq!(placement.choose(now, 1, 2, true, true), Some(1));
        assert_eq!(placement.choose(now, 1, 2, true, true), Some(2));
    }
    #[test]
    fn promotions_expand_promptly_but_oscillation_does_not_pinball_between_cores() {
        let now = Instant::now();
        let mut p = Placement::default();
        assert_eq!(p.choose(now, 0, 16, false, true), Some(16));
        p.written(now);
        assert_eq!(p.choose(now, 16, 144, false, true), Some(144));
        assert_eq!(p.choose(now, 144, 128, true, false), Some(144));
        assert_eq!(p.choose(now, 144, 128, true, false), Some(144));
        assert_eq!(
            p.choose(now + Duration::from_secs(9), 144, 128, true, false),
            Some(128)
        );
    }
    #[test]
    fn sibling_changes_never_accumulate_a_dual_prime_mask() {
        let now = Instant::now();
        let mut p = Placement::default();
        p.written(now);
        for second in 0..8 {
            assert_eq!(
                p.choose(now + Duration::from_secs(second), 64, 128, true, true),
                Some(64)
            );
        }
        assert_eq!(
            p.choose(now + Duration::from_secs(8), 64, 128, true, true),
            Some(128)
        );
        p.written(now + Duration::from_secs(8));
        // 真实容量不足时跳过驻留等待，不保留 CPU 7。
        assert_eq!(
            p.choose(now + Duration::from_secs(9), 128, 64, false, true),
            Some(64)
        );
    }

    #[test]
    fn reassertion_invalidates_pinned_accounting_without_restarting_migration_dwell() {
        let now = Instant::now();
        let mut placement = Placement::default();
        assert!(!placement.settled_before(now));
        placement.written(now);
        assert!(placement.settled_before(now + Duration::from_secs(1)));
        placement.reasserted(now + Duration::from_secs(4));
        assert!(!placement.settled_before(now + Duration::from_secs(2)));
        assert!(!placement.settled_before(now + Duration::from_secs(4)));
        assert!(placement.settled_before(now + Duration::from_millis(4001)));

        assert_eq!(placement.choose(now + Duration::from_secs(7), 1, 2, true, true), Some(1));
        placement.reasserted(now + Duration::from_millis(7500));
        assert_eq!(placement.changed, Some(now));
        assert_eq!(placement.choose(now + Duration::from_secs(8), 1, 2, true, true), Some(2));
        placement.request_review(now + Duration::from_secs(9));
        let review = placement.review_until;
        placement.reasserted(now + Duration::from_secs(10));
        assert_eq!(placement.review_until, review);
        placement.written(now + Duration::from_secs(11));
        assert_eq!(placement.accounting_changed, placement.changed);
        assert!(placement.review_until.is_none());
    }
    #[test]
    fn a_cross_write_sample_cannot_chase_its_own_load_to_an_equal_core() {
        use super::super::{planner, topology::Core};
        let now = Instant::now();
        let cores: Vec<_> = (0..4).map(|id| Core { id, capacity: 512 }).collect();
        let limits = [Some(1.0); 64];
        let demand = planner::Demand { serial: 204.8, total: 204.8, parallel: 1, allowed: 15 };
        let mut raw = [Some(0.0); 64];
        raw[0] = Some(0.60); // 40% 自身工作量 + 20% 实际外部工作量。
        for load in &mut raw[1..4] { *load = Some(0.26); }
        let mut placement = Placement::default();
        placement.written(now);

        // 首个完整区间结束前，其他不确定线程会扩大并集，
        // 其安全上界仍包含我们在 CPU 0 上的工作。
        let uncertain = external_loads(raw, 15, 0.58);
        let mut budget = planner::Budget::new(&cores, &limits);
        budget.reserve_external(&uncertain);
        let before = budget.clone();
        let desired = budget.assign_preferred(&demand, 1).0;
        assert!(!before.fits(&demand, 1));
        assert_ne!(desired, 1);
        assert!(before.materially_better(&demand, 1, desired), "the old path would immediately migrate");
        assert!(placement.wait_for_accounting(now, 1, 15, before.stronger_lane(15, 1, desired)));

        // 保留一个完整区间即可获得真实的固定核心证据，无需虚构扣减量，
        // 同一物理执行通道便能再次满足需求。
        let mut pinned = [0.0; 64];
        pinned[0] = 0.40;
        let external = external_with_pinned(raw, &pinned, 14, 0.18);
        for round in 2..=5 {
            let interval_start = now + Duration::from_millis(500 * (round - 1));
            let mut stable = planner::Budget::new(&cores, &limits);
            stable.reserve_external(&external);
            assert!(stable.fits(&demand, 1));
            assert_eq!(stable.assign_preferred(&demand, 1).0, 1);
            assert!(!placement.wait_for_accounting(interval_start, 1, 15, false));
        }
    }
    #[test]
    fn unsettled_accounting_never_delays_real_capacity_upgrade_or_core_parking() {
        use super::super::{planner, topology::Core};
        let now = Instant::now();
        let cores = [Core { id: 0, capacity: 512 }, Core { id: 1, capacity: 512 },
            Core { id: 2, capacity: 1024 }, Core { id: 3, capacity: 1024 }];
        let mut limits = [Some(1.0); 64];
        limits[2] = Some(0.5);
        let budget = planner::Budget::new(&cores, &limits);
        let mut placement = Placement::default();
        placement.written(now);
        assert!(!budget.stronger_lane(15, 1, 6), "two equal service alternatives are not a stronger serial lane");
        assert!(placement.wait_for_accounting(now, 1, 15, budget.stronger_lane(15, 1, 6)));
        assert!(budget.stronger_lane(15, 1, 8));
        assert!(!placement.wait_for_accounting(now, 1, 15, budget.stronger_lane(15, 1, 8)));
        assert!(!placement.wait_for_accounting(now, 1, 14, false), "parked current CPU escapes immediately");
        assert!(!placement.wait_for_accounting(now, 0, 15, false), "new eligible threads are admitted immediately");
        placement.reasserted(now + Duration::from_millis(250));
        assert!(placement.wait_for_accounting(now, 1, 15, false));
        assert!(!placement.wait_for_accounting(now + Duration::from_millis(500), 1, 15, false));
    }
    #[test]
    fn owned_work_is_not_reserved_twice_and_external_core_pressure_is_retained() {
        let mut raw = [Some(0.0); 64];
        raw[4] = Some(0.5);
        raw[7] = Some(0.9);
        let other = external_loads(raw, 16, 0.45);
        assert!((other[4].unwrap() - 0.05).abs() < 1e-6);
        assert_eq!(other[7], Some(0.9));
        assert_eq!(external_loads(raw, 16, 0.9), raw);
    }

    #[test]
    fn the_first_valid_average_at_five_percent_is_eligible() {
        assert_eq!(SAMPLE_INTERVAL, Duration::from_millis(500));
        for elapsed in [0.25, 0.5, 1.1, 6.0, 12.0] {
            let mut active = Activity::default();
            assert!(!active.eligible());
            assert!(active.observe(0.05, elapsed));
            assert!(active.eligible(), "first interval={elapsed}");
            let mut light = Activity::default();
            assert!(light.observe(0.04999, elapsed));
            assert!(!light.eligible());
        }
    }

    #[test]
    fn activity_window_is_time_weighted_and_clips_only_the_expired_segment() {
        let mut activity = Activity::default();
        assert!(activity.observe(0.4, 2.0));
        assert!(activity.observe(0.1, 3.0));
        assert!(activity.observe(0.0, 2.0));
        assert_eq!(activity.samples.front(), Some(&(0.4, 1.0)));
        assert_eq!(activity.samples.iter().map(|v| v.1).sum::<f64>(), 6.0);
        assert!((activity.average() - 0.7 / 6.0).abs() < 1e-12);
        assert!(activity.eligible());
        assert!(activity.observe(0.0, 1.0));
        assert_eq!(activity.samples.front(), Some(&(0.1, 3.0)));
        assert!(activity.eligible());
        assert!(activity.observe(0.0, 0.25));
        assert!(activity.average() < 0.05);
        assert!(!activity.eligible());
    }

    #[test]
    fn irregular_intervals_do_not_round_a_five_percent_thread_out_of_ownership() {
        let mut activity = Activity::default();
        let intervals = [0.37, 0.63, 0.499, 0.501, 1.1, 0.9];
        for round in 0..1000 {
            assert!(activity.observe(0.05, intervals[round % intervals.len()]));
            assert!(activity.eligible(), "round={round}");
            assert!(activity.keep_owned_without_sample(Duration::ZERO));
            assert!((activity.average() - 0.05).abs() < 1e-12);
        }
        activity.observe(0.04999, 0.5);
        assert!(!activity.eligible());
    }

    #[test]
    fn mixed_samples_at_five_percent_cannot_disagree_with_the_release_average() {
        let mut activity = Activity::default();
        activity.observe(0.04, 0.5);
        activity.observe(0.06, 0.5);
        assert_eq!(activity.average(), 0.05);
        // 分别从两个输入扣除边界值会在这里引入微小负误差，
        // 尽管普通加权均值恰好等于 5%。
        assert!(activity.samples.iter().map(|v| (v.0 - 0.05) * v.1).sum::<f64>() < 0.0);
        assert!(activity.eligible());
        assert!(activity.keep_owned_without_sample(Duration::ZERO));
        activity.observe(0.04999, 0.5);
        assert!(activity.average() < 0.05);
        assert!(!activity.eligible());
        assert!(!activity.keep_owned_without_sample(Duration::ZERO));
    }

    #[test]
    fn faster_sampling_preserves_the_same_six_second_average_and_release_time() {
        for interval in [0.25, 0.5, 1.0] {
            let mut activity = Activity::default();
            for _ in 0..(3.0 / interval) as usize { activity.observe(0.2, interval); }
            for _ in 0..(3.0 / interval) as usize { activity.observe(0.0, interval); }
            assert!((activity.average() - 0.1).abs() < 1e-12);
            assert!(activity.dormant());
            assert!(activity.eligible());
            for _ in 0..(2.0 / interval) as usize { activity.observe(0.0, interval); }
            assert!((activity.average() - 0.2 / 6.0).abs() < 1e-12);
            assert!(!activity.eligible());
        }
    }

    #[test]
    fn long_intervals_replace_stale_activity_without_unbounded_samples() {
        let mut activity = Activity::default();
        for _ in 0..1000 { assert!(activity.observe(0.3, 0.25)); }
        assert_eq!(activity.samples.len(), 24);
        assert!(activity.observe(0.01, 12.0));
        assert_eq!(activity.samples.len(), 1);
        assert_eq!(activity.samples.front(), Some(&(0.01, 6.0)));
        assert!(!activity.eligible());
        assert!(!activity.observe(0.3, 0.249));
        assert!(activity.samples.is_empty());
    }

    #[test]
    fn overloaded_lanes_move_for_real_gain_not_small_ranking_noise() {
        let now = Instant::now();
        let mut placement = Placement::default();
        placement.written(now);
        for second in 1..20 {
            assert_eq!(placement.choose(now + Duration::from_secs(second), 3, 12, false, false), Some(3));
        }
        assert_eq!(placement.choose(now + Duration::from_secs(20), 3, 12, false, true), Some(12));
        assert!(!placement.settled_before(now));
        assert!(placement.settled_before(now + Duration::from_secs(1)));
    }

    #[test]
    fn parked_held_cores_escape_immediately_even_without_fresh_load_or_ranking_gain() {
        let now = Instant::now();
        let mut placement = Placement::default();
        placement.written(now);
        // core_ctl 停用 CPU 7 和 CPU 4 时，它们可能仍显示在线。
        let available = 0x6f;
        assert_eq!(placement.choose_available(now, 0x80, 0x40, available, false, false), Some(0x40));
        // 部分停核的核心池也需要替换不可用成员。
        assert_eq!(placement.choose_available(now, 0x50, 0x60, available, false, false), Some(0x60));
        // 任何请求都不能保留已停核的位，也不能变成空掩码。
        assert_eq!(placement.choose_available(now, 0x80, 0xc0, available, false, false), Some(0x40));
        assert_eq!(placement.choose_available(now, 0x80, 0x80, available, false, false), None);
    }

    #[test]
    fn availability_check_preserves_owned_migration_hysteresis() {
        let now = Instant::now();
        let mut placement = Placement::default();
        assert_eq!(placement.choose_available(now, 0, 0x40, 0x6f, false, false), Some(0x40));
        placement.written(now);
        assert_eq!(placement.choose_available(now, 0x40, 0x20, 0x6f, false, false), Some(0x40));
        assert_eq!(placement.choose_available(now, 0x40, 0x20, 0x6f, true, true), Some(0x40));
        assert_eq!(placement.choose_available(now + Duration::from_secs(9), 0x40, 0x20, 0x6f, true, true), Some(0x20));
    }

    #[test]
    fn changing_rankings_cannot_starve_a_new_eligible_owner() {
        let now = Instant::now();
        let mut activity = Activity::default();
        activity.observe(0.05, 0.5);
        assert!(activity.eligible());
        let mut placement = Placement::default();
        for second in 0..12 {
            let desired = if second % 2 == 0 { 0x10 } else { 0x20 };
            assert_eq!(placement.choose_available(now + Duration::from_secs(second), 0,
                desired, 0x3f, false, true), Some(desired));
        }
        assert_eq!(placement.choose_available(now, 0, 0x40, 0x3f, false, true), None);
        activity.observe(0.0, 1.1);
        assert!(!activity.eligible());
    }

    #[test]
    fn known_single_cpu_work_is_subtracted_once_without_hiding_external_hotspots() {
        let mut raw = [Some(0.0); 64];
        raw[0] = Some(0.5);
        raw[1] = Some(0.5);
        raw[7] = Some(0.9);
        let mut pinned = [0.0; 64];
        pinned[0] = 0.4;
        pinned[1] = 0.4;
        let loads = external_with_pinned(raw, &pinned, 0, 0.0);
        assert!((loads[0].unwrap() + loads[1].unwrap() - 0.2).abs() < 1e-9);
        assert_eq!(loads[7], Some(0.9));
        pinned[0] = 0.9; // 计数不匹配不能成为抹去热点负载的理由。
        assert_eq!(external_with_pinned(raw, &pinned, 0, 0.0)[0], Some(0.5));
    }
}
