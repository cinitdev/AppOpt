//! 用于保存校准历史的线程活跃依据，与规则生成条件相互独立。

const MIN_AVERAGE_PCT: f64 = 0.5;
const MIN_ACTIVE_SAMPLES: usize = 3;
const BURST_PCT: f64 = 20.0;
const MIN_BURST_SAMPLES: usize = 6;

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

    pub(super) fn worth_saving(&self, session_average: f64) -> bool {
        // 不能仅凭一次调度时钟计数，就把其余时间都休眠的线程判为活跃。
        // 也要保留有意义的突发活动：加载或场景工作线程之后可能转为空闲。
        session_average.is_finite()
            && ((self.active_samples >= MIN_ACTIVE_SAMPLES && session_average >= MIN_AVERAGE_PCT)
                || self.burst_samples >= MIN_BURST_SAMPLES)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn sustained_load_and_later_idle_bursts_are_kept() {
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
}
