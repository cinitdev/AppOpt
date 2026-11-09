//! 用于保存校准历史的线程活跃依据，与规则生成条件相互独立。
//!
//! 这里有两个口径，用途不同：
//! - [`Activity::worth_saving`]：待确认弹窗和规则候选沿用的宽松口径，保持既有行为。
//! - [`Activity::sustained`]：历史记录使用的“持续活跃”口径，只保留长时间
//!   反复有负载的线程，运行期间只活跃几下的线程不写进历史。

const MIN_AVERAGE_PCT: f64 = 0.5;
const MIN_ACTIVE_SAMPLES: usize = 3;
const BURST_PCT: f64 = 20.0;
const MIN_BURST_SAMPLES: usize = 6;

/// 历史口径：至少 6 个有负载采样（约 3 秒），避免一次唤醒就入选。
const HISTORY_MIN_ACTIVE_SAMPLES: usize = 6;
/// 历史口径：有负载的采样占该线程存在期的比例（百分比）。
const HISTORY_MIN_ACTIVE_SHARE: usize = 2;
/// 历史口径：存在期至少 60 轮（约 30 秒）。
const HISTORY_MIN_SPAN_ROUNDS: usize = 60;

#[derive(Debug, Clone, Default)]
pub(super) struct Activity {
    active_samples: usize,
    burst_samples: usize,
}

impl Activity {
    pub(super) fn percent(&self, rounds: usize) -> f64 {
        (self.active_samples as f64 * 100.0 / rounds.max(1) as f64).min(100.0)
    }
    pub(super) fn observe(&mut self, pct: f64) {
        if !pct.is_finite() {
            return;
        }
        if pct >= MIN_AVERAGE_PCT {
            self.active_samples += 1;
        }
        if pct >= BURST_PCT {
            self.burst_samples += 1;
        }
    }

    /// 规则候选与待确认弹窗的宽松口径，保持原有行为不变。
    pub(super) fn worth_saving(&self, session_average: f64) -> bool {
        // 不能仅凭一次调度时钟计数，就把其余时间都休眠的线程判为活跃。
        // 也要保留有意义的突发活动：加载或场景工作线程之后可能转为空闲。
        session_average.is_finite()
            && ((self.active_samples >= MIN_ACTIVE_SAMPLES && session_average >= MIN_AVERAGE_PCT)
                || self.burst_samples >= MIN_BURST_SAMPLES)
    }

    /// 历史记录的“持续活跃”口径。
    ///
    /// `observed_rounds` 是这个线程自己的存在期（从第一次被记录到会话结束的
    /// 采样轮数），用它做分母而不是整段轮数：校准会话可能持续很久，晚创建的
    /// 线程不应该因为出生晚就被判定为“不持续”。
    ///
    /// 入选条件（全部满足）：
    /// - 有负载采样 ≥ 6 次（约 3 秒）；
    /// - 有负载采样占比 ≥ 2%；
    /// - 存在期 ≥ 30 秒（不足 60 轮的短会话按会话时长的一半收敛）；
    /// - 全程平均负载 ≥ 0.5%，和规则建议的门槛保持一致。
    pub(super) fn sustained(
        &self,
        observed_rounds: usize,
        session_rounds: usize,
        session_average: f64,
    ) -> bool {
        if observed_rounds == 0
            || !session_average.is_finite()
            || session_average < MIN_AVERAGE_PCT
        {
            return false;
        }
        if self.active_samples < HISTORY_MIN_ACTIVE_SAMPLES {
            return false;
        }
        if self.active_samples * 100 < HISTORY_MIN_ACTIVE_SHARE * observed_rounds {
            return false;
        }
        // 存在期门槛不超过会话时长的一半，很短的会话不至于整份历史被清空。
        let required_span = HISTORY_MIN_SPAN_ROUNDS.min(session_rounds / 2).max(1);
        observed_rounds >= required_span
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn activity_of(samples: &[f64]) -> Activity {
        let mut activity = Activity::default();
        for pct in samples {
            activity.observe(*pct);
        }
        activity
    }

    #[test]
    fn sleep_and_a_single_wakeup_do_not_qualify() {
        let mut activity = Activity::default();
        activity.observe(0.0);
        assert!(!activity.worth_saving(0.0));
        activity.observe(2.0);
        assert!(!activity.worth_saving(2.0));
        assert!(!activity.worth_saving(2.0 / 1200.0));
    }

    #[test]
    fn sustained_load_and_later_idle_bursts_are_kept_by_the_rule_candidates() {
        let mut sustained = Activity::default();
        for _ in 0..60 {
            sustained.observe(1.0);
        }
        assert!(sustained.worth_saving(1.0));
        let mut burst = Activity::default();
        for _ in 0..6 {
            burst.observe(40.0);
        }
        assert!(burst.worth_saving(240.0 / 1200.0));
    }

    #[test]
    fn spikes_and_nonfinite_values_cannot_bypass_activity_evidence() {
        let mut activity = Activity::default();
        activity.observe(100.0);
        activity.observe(f64::NAN);
        activity.observe(f64::INFINITY);
        assert!(!activity.worth_saving(100.0));
        assert!(!activity.worth_saving(f64::NAN));
    }

    #[test]
    fn history_keeps_repeatedly_active_threads_and_drops_short_bursts() {
        // 20 分钟会话里的持续线程：1200 轮里 1200 次有负载。
        let steady = activity_of(&[8.0; 1200]);
        assert!(steady.sustained(1200, 1200, 8.0));

        // 同样的会话里只活跃 3 秒的加载线程：6 个样本、平均 0.2%。
        let mut burst = Activity::default();
        for _ in 0..6 {
            burst.observe(40.0);
        }
        assert!(burst.worth_saving(240.0 / 1200.0));
        assert!(!burst.sustained(1200, 1200, 0.2));

        // 只在会话末尾出现 3 秒的线程，存在期同样不足。
        assert!(!burst.sustained(6, 1200, 40.0));
    }

    #[test]
    fn history_span_rule_adapts_to_short_sessions() {
        // 30 秒会话（60 轮）里从第 10 轮才开始的重负载线程覆盖了大半会话。
        let late = activity_of(&[30.0; 50]);
        assert!(late.sustained(50, 60, 25.0));
        // 同样 50 轮出现在 20 分钟会话里则只有 25 秒，不够。
        assert!(!late.sustained(50, 2400, 25.0));
    }

    #[test]
    fn history_requires_a_minimum_number_of_active_samples() {
        // 存在期很长，但只有 5 次有负载：不够 3 秒的证据下限。
        let mut sparse = Activity::default();
        for _ in 0..5 {
            sparse.observe(30.0);
        }
        for _ in 0..1195 {
            sparse.observe(0.0);
        }
        assert!(!sparse.sustained(1200, 1200, 0.125));
    }
}
