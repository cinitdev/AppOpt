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
            // 异常读数不代表线程变为空闲；有效样本的时效由调用方单独检查。
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
    pub(super) fn eligible_at(&self, threshold: f64) -> bool {
        // 入选判断应与报告和释放判断所用均值一致。围绕阈值比较
        // 还能保护恒定 5% 不受除法舍入影响，
        // 两种表示都不能拒绝对方恰好达到边界的情况。
        !self.samples.is_empty()
            && (self.average() >= threshold
                || self.samples.iter().map(|v| (v.0 - threshold) * v.1).sum::<f64>() >= 0.0)
    }
    pub(super) fn dormant(&self) -> bool {
        self.quiet >= 2
    }
    pub(super) fn keep_owned_at(&self, age: Duration, threshold: f64) -> bool {
        self.eligible_at(threshold) && age <= MISSING_SAMPLE_GRACE
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
