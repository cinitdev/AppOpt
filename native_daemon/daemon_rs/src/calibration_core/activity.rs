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
