use std::collections::VecDeque;

const FPS_WINDOW_NS: u64 = 1_000_000_000;
const MIN_FRAME_NS: u64 = 1_000_000;
const MAX_RECORDED_FRAME_NS: u64 = 1_000_000_000;
// Android 输入 ANR 的常见边界是 5 秒；低于该值仍可能是真实严重卡顿，不能当暂停丢掉。
const PAUSE_FRAME_NS: u64 = 5_000_000_000;
const MIN_STREAM_INTERVALS: usize = 8;
const MIN_STREAM_LIFETIME_NS: u64 = FPS_WINDOW_NS - MIN_FRAME_NS;
// 根据最小可计量帧间隔限制窗口大小，不把任何屏幕刷新率当作 FPS 上限。
const MAX_WINDOW_INTERVALS: u64 = FPS_WINDOW_NS / MIN_FRAME_NS;

pub(crate) const STREAM_STALE_NS: u64 = 2_000_000_000;
pub(crate) const MAX_STREAMS: usize = 32;

/// 当前窗口与已完成窗口的 BPF 报告映射共用的 24 字节布局。
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub(crate) struct FrameReportValue {
    pub(crate) interval_ts: u64,
    pub(crate) max_interval_ns: u64,
    pub(crate) window_ts: u64,
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub(crate) struct FrameStreamKey {
    pub(crate) pid: u32,
    pub(crate) tid: u32,
    pub(crate) surface_ptr: u64,
}

pub(crate) struct FpsStream {
    pub(crate) frame_times: VecDeque<u64>,
    frame_time_sum_ns: u64,
    last_ts: u64,
    first_seen_ns: u64,
    pub(crate) last_seen_ns: u64,
    pub(crate) cur_fps: f64,
    warmed_up: bool,
    warmup_intervals: usize,
    report_max: u64,
    report_seen_ns: u64,
    report_ready_ns: u64,
}

impl FpsStream {
    pub(crate) fn new(timestamp_ns: u64) -> Self {
        Self {
            frame_times: VecDeque::with_capacity(144),
            frame_time_sum_ns: 0,
            last_ts: 0,
            first_seen_ns: timestamp_ns,
            last_seen_ns: timestamp_ns,
            cur_fps: 0.0,
            warmed_up: false,
            warmup_intervals: 0,
            report_max: 0,
            report_seen_ns: 0,
            report_ready_ns: 0,
        }
    }

    pub(crate) fn on_frame(&mut self, timestamp_ns: u64) -> bool {
        if self.last_ts != 0 && timestamp_ns <= self.last_ts {
            return false;
        }

        if self.last_ts != 0 {
            let delta = timestamp_ns.saturating_sub(self.last_ts);
            if delta > PAUSE_FRAME_NS {
                self.reset_after_pause(timestamp_ns);
            } else if delta < MIN_FRAME_NS {
                // 丢弃重复事件时必须保留上一有效帧的时间戳，
                // 否则下一帧的间隔会被截短，造成 FPS 虚高和帧耗时偏低。
                return false;
            } else {
                self.warmup_intervals = (self.warmup_intervals + 1).min(MIN_STREAM_INTERVALS);
                self.record_report_interval(timestamp_ns, delta);
                let recorded = delta.min(MAX_RECORDED_FRAME_NS);
                self.frame_times.push_front(recorded);
                self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_add(recorded);
                self.trim_window();
                self.update_fps();
            }
        }

        self.last_ts = timestamp_ns;
        self.last_seen_ns = timestamp_ns;
        self.update_warmup();
        true
    }

    pub(crate) fn on_frame_batch(&mut self, prev_ts: u64, timestamp_ns: u64, frames: u64) {
        if frames == 0 || timestamp_ns <= prev_ts || timestamp_ns <= self.last_ts {
            return;
        }

        let delta = (timestamp_ns - prev_ts) / frames;
        if delta > PAUSE_FRAME_NS {
            self.reset_after_pause(timestamp_ns);
            self.last_ts = timestamp_ns;
            self.last_seen_ns = timestamp_ns;
            return;
        }
        if delta < MIN_FRAME_NS {
            return;
        }
        let delta = delta.min(MAX_RECORDED_FRAME_NS);

        self.warmup_intervals = (self.warmup_intervals
            + frames.min(MIN_STREAM_INTERVALS as u64) as usize).min(MIN_STREAM_INTERVALS);
        for _ in 0..frames.min(MAX_WINDOW_INTERVALS) {
            self.frame_times.push_front(delta);
            self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_add(delta);
        }
        self.trim_window();
        self.update_fps();
        self.last_ts = timestamp_ns;
        self.last_seen_ns = timestamp_ns;
        self.update_warmup();
    }

    // 独立的报告观测数据，不参与调度反馈或 FPS 计算。
    pub(crate) fn record_report_windows(
        &mut self,
        now_ns: u64,
        current: Option<FrameReportValue>,
        previous: Option<FrameReportValue>,
    ) {
        // 只消费已完成的窗口；过早消费当前窗口的最大值，
        // 会掩盖稍后出现、属于下一次 UI 输出周期的较小峰值。
        // 停帧时不会滚动窗口，因此也要结束已超时的当前窗口。
        let current =
            current.filter(|report| now_ns.saturating_sub(report.window_ts) >= FPS_WINDOW_NS);
        let mut reports = [current, previous];
        reports.sort_unstable_by_key(|report| report.map(|report| report.interval_ts));
        for report in reports.into_iter().flatten() {
            if report.interval_ts <= self.report_seen_ns {
                continue;
            }
            self.record_report_interval(report.interval_ts, report.max_interval_ns);
            if self.report_seen_ns == report.interval_ts {
                // 已完成窗口会有意延迟约一秒上报；
                // 新鲜度从该窗口可以结束的时刻开始计算。
                self.report_ready_ns = report.window_ts.saturating_add(FPS_WINDOW_NS);
            }
        }
    }

    pub(crate) fn record_report_interval(&mut self, timestamp_ns: u64, interval_ns: u64) {
        if timestamp_ns <= self.report_seen_ns
            || !(MIN_FRAME_NS..=PAUSE_FRAME_NS).contains(&interval_ns)
        {
            return;
        }
        if timestamp_ns.saturating_sub(self.report_seen_ns) > STREAM_STALE_NS {
            self.report_max = 0;
        }
        self.report_seen_ns = timestamp_ns;
        self.report_ready_ns = timestamp_ns;
        self.report_max = self.report_max.max(interval_ns);
    }

    pub(crate) fn take_report_max(&mut self, now_ns: u64) -> u64 {
        let maximum = std::mem::take(&mut self.report_max);
        if now_ns.saturating_sub(self.report_ready_ns) <= STREAM_STALE_NS {
            maximum
        } else {
            0
        }
    }

    pub(crate) fn discard_report_before(&mut self, timestamp_ns: u64) {
        self.report_max = 0;
        self.report_seen_ns = self.report_seen_ns.max(timestamp_ns);
        self.report_ready_ns = timestamp_ns;
    }

    pub(crate) fn selection_score(&self, now_ns: u64) -> f64 {
        if !self.is_stable() {
            return f64::NEG_INFINITY;
        }
        let age_ns = now_ns
            .saturating_sub(self.last_seen_ns)
            .min(STREAM_STALE_NS);
        let freshness = 1.0 - age_ns as f64 / STREAM_STALE_NS as f64;
        let coverage = self.frame_time_sum_ns.min(FPS_WINDOW_NS) as f64 / FPS_WINDOW_NS as f64;
        // 单次界面刷新不能不断打断视频帧源的切换等待。以完整窗口内的
        // 持续出帧为主，新鲜度只作次级权重，避免偶发 UI 帧压住正在播放的视频。
        freshness * 200.0 + coverage * 400.0 + self.fps_at(now_ns) * 4.0
    }

    pub(crate) fn fps_at(&self, now_ns: u64) -> f64 {
        let age = now_ns.saturating_sub(self.last_seen_ns);
        if age >= FPS_WINDOW_NS || !self.is_stable() {
            return 0.0;
        }
        // 轮询时必须把最后一帧到现在的空闲时间算进去，不能沿用突发
        // 末尾的高帧率。保留跨越窗口起点的完整间隔，和逐帧路径一致。
        let mut duration = self.frame_time_sum_ns.saturating_add(age);
        let mut count = self.frame_times.len();
        for interval in self.frame_times.iter().rev() {
            let remaining = duration.saturating_sub(*interval);
            if remaining < FPS_WINDOW_NS {
                break;
            }
            duration = remaining;
            count = count.saturating_sub(1);
        }
        if duration == 0 {
            0.0
        } else {
            count as f64 * FPS_WINDOW_NS as f64 / duration as f64
        }
    }

    fn trim_window(&mut self) {
        while let Some(&old) = self.frame_times.back() {
            // 保留跨越窗口起点的完整间隔。否则停帧后的一个短间隔
            // 就会挤掉整段停顿时间，把瞬时间隔误算成整秒 FPS。
            let remaining = self.frame_time_sum_ns.saturating_sub(old);
            if remaining < FPS_WINDOW_NS {
                break;
            }
            self.frame_times.pop_back();
            self.frame_time_sum_ns = remaining;
        }
    }

    fn update_fps(&mut self) {
        if self.frame_time_sum_ns > 0 {
            self.cur_fps =
                self.frame_times.len() as f64 * 1_000_000_000.0 / self.frame_time_sum_ns as f64;
        }
    }

    fn update_warmup(&mut self) {
        // 预热统计累计收到的有效间隔；真实低帧场景的一秒窗口可能永远装不下 8 帧。
        self.warmed_up |= self.warmup_intervals >= MIN_STREAM_INTERVALS
            // 创建帧流后的等待时间不能代替实际采集到的有效时间。
            && self.frame_time_sum_ns >= MIN_STREAM_LIFETIME_NS
            && self.last_seen_ns.saturating_sub(self.first_seen_ns) >= MIN_STREAM_LIFETIME_NS;
    }

    fn reset_after_pause(&mut self, timestamp_ns: u64) {
        self.discard_report_before(timestamp_ns);
        self.frame_times.clear();
        self.frame_time_sum_ns = 0;
        self.cur_fps = 0.0;
        self.first_seen_ns = timestamp_ns;
        self.warmed_up = false;
        self.warmup_intervals = 0;
    }

    fn is_stable(&self) -> bool {
        self.warmed_up
            && self.frame_time_sum_ns >= MIN_STREAM_LIFETIME_NS
            && self.cur_fps.is_finite()
            && (1.0..=MAX_WINDOW_INTERVALS as f64).contains(&self.cur_fps)
    }
}
