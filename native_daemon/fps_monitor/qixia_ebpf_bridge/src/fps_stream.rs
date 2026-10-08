use std::collections::VecDeque;

const FPS_WINDOW_NS: u64 = 1_000_000_000;
const MIN_FRAME_NS: u64 = 1_000_000;
const MAX_RECORDED_FRAME_NS: u64 = 1_000_000_000;
// Android 输入 ANR 的常见边界是 5 秒；低于该值仍可能是真实严重卡顿，不能当暂停丢掉。
const PAUSE_FRAME_NS: u64 = 5_000_000_000;
const MIN_STREAM_INTERVALS: usize = 8;
const MIN_STREAM_LIFETIME_NS: u64 = 150_000_000;
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
            report_max: 0,
            report_seen_ns: 0,
            report_ready_ns: 0,
        }
    }

    pub(crate) fn on_frame(&mut self, timestamp_ns: u64) {
        if self.last_ts != 0 && timestamp_ns <= self.last_ts {
            return;
        }

        if self.last_ts != 0 {
            let delta = timestamp_ns.saturating_sub(self.last_ts);
            if delta > PAUSE_FRAME_NS {
                self.reset_after_pause(timestamp_ns);
            } else if delta < MIN_FRAME_NS {
                // 丢弃重复事件时必须保留上一有效帧的时间戳，
                // 否则下一帧的间隔会被截短，造成 FPS 虚高和帧耗时偏低。
                return;
            } else {
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

    pub(crate) fn selection_score(&self, now_ns: u64) -> f64 {
        if !self.is_stable() {
            return f64::NEG_INFINITY;
        }
        let age_ns = now_ns
            .saturating_sub(self.last_seen_ns)
            .min(STREAM_STALE_NS);
        let freshness = 1.0 - age_ns as f64 / STREAM_STALE_NS as f64;
        let coverage = self.frame_time_sum_ns.min(FPS_WINDOW_NS) as f64 / FPS_WINDOW_NS as f64;
        freshness * 1_000.0
            + coverage * 400.0
            + self.frame_times.len().min(300) as f64
            + self.cur_fps / 10.0
    }

    fn trim_window(&mut self) {
        while self.frame_time_sum_ns > FPS_WINDOW_NS && self.frame_times.len() > 1 {
            if let Some(old) = self.frame_times.pop_back() {
                self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_sub(old);
            }
        }
    }

    fn update_fps(&mut self) {
        if self.frame_time_sum_ns > 0 {
            self.cur_fps =
                self.frame_times.len() as f64 * 1_000_000_000.0 / self.frame_time_sum_ns as f64;
        }
    }

    fn update_warmup(&mut self) {
        self.warmed_up |= self.frame_times.len() >= MIN_STREAM_INTERVALS
            && self.last_seen_ns.saturating_sub(self.first_seen_ns) >= MIN_STREAM_LIFETIME_NS;
    }

    fn reset_after_pause(&mut self, timestamp_ns: u64) {
        self.frame_times.clear();
        self.frame_time_sum_ns = 0;
        self.cur_fps = 0.0;
        self.first_seen_ns = timestamp_ns;
        self.warmed_up = false;
    }

    fn is_stable(&self) -> bool {
        self.warmed_up
            && !self.frame_times.is_empty()
            && self.cur_fps.is_finite()
            && (1.0..=MAX_WINDOW_INTERVALS as f64).contains(&self.cur_fps)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn report_max_is_exact_consumed_and_independent_from_fps_window() {
        let mut stream = FpsStream::new(1_000_000_000);
        stream.on_frame(1_000_000_000);
        stream.on_frame(2_500_000_000);
        assert_eq!(stream.take_report_max(2_600_000_000), 1_500_000_000);
        assert_eq!(stream.take_report_max(2_600_000_001), 0);
        assert_eq!(stream.frame_times.front(), Some(&1_000_000_000));
        stream.record_report_interval(3_000_000_000, 20_000_000);
        stream.record_report_interval(2_900_000_000, 80_000_000);
        assert_eq!(stream.take_report_max(3_100_000_000), 20_000_000);
        stream.record_report_interval(4_000_000_000, 30_000_000);
        assert_eq!(stream.take_report_max(7_000_000_000), 0);
    }

    #[test]
    fn completed_window_preserves_boundary_peak_without_reporting_it_twice() {
        let mut stream = FpsStream::new(1_000_000_000);
        let report = |window_ts, interval_ts, max_interval_ns| FrameReportValue {
            window_ts,
            interval_ts,
            max_interval_ns,
        };
        stream.record_report_windows(
            1_860_000_000,
            Some(report(1_000_000_000, 1_850_000_000, 16_000_000)),
            None,
        );
        assert_eq!(stream.take_report_max(1_860_000_000), 0);
        // 上次轮询后出现一帧耗时 100 毫秒的画面，用户态还未读到其峰值，
        // 下一帧就已推动内核窗口滚动。
        let previous = Some(report(1_000_000_000, 1_980_000_000, 100_000_000));
        let current = Some(report(2_013_000_000, 2_013_000_000, 16_000_000));
        stream.record_report_windows(2_100_000_000, current, previous);
        assert_eq!(stream.take_report_max(2_100_000_000), 100_000_000);
        stream.record_report_windows(2_200_000_000, current, previous);
        assert_eq!(stream.take_report_max(2_200_000_000), 0);
        // 下一窗口中较小的最大值必须独立保留。
        stream.record_report_windows(
            3_100_000_000,
            Some(report(3_050_000_000, 3_050_000_000, 16_000_000)),
            Some(report(2_013_000_000, 2_250_000_000, 17_000_000)),
        );
        assert_eq!(stream.take_report_max(3_100_000_000), 17_000_000);
    }

    #[test]
    fn rollover_between_current_and_previous_reads_keeps_the_later_peak() {
        let mut stream = FpsStream::new(1_000_000_000);
        stream.record_report_windows(
            2_100_000_000,
            Some(FrameReportValue {
                window_ts: 1_000_000_000,
                interval_ts: 1_850_000_000,
                max_interval_ns: 16_000_000,
            }),
            Some(FrameReportValue {
                window_ts: 1_000_000_000,
                interval_ts: 1_980_000_000,
                max_interval_ns: 100_000_000,
            }),
        );
        assert_eq!(stream.take_report_max(2_100_000_000), 100_000_000);
        stream.record_report_windows(
            2_200_000_000,
            Some(FrameReportValue {
                window_ts: 2_013_000_000,
                interval_ts: 2_013_000_000,
                max_interval_ns: 16_000_000,
            }),
            Some(FrameReportValue {
                window_ts: 1_000_000_000,
                interval_ts: 1_980_000_000,
                max_interval_ns: 100_000_000,
            }),
        );
        assert_eq!(stream.take_report_max(2_200_000_000), 0);
    }

    #[test]
    fn stopped_current_window_finishes_once_and_remains_fresh_for_output() {
        let mut stream = FpsStream::new(1_000_000_000);
        let current = Some(FrameReportValue {
            window_ts: 1_000_000_000,
            interval_ts: 1_020_000_000,
            max_interval_ns: 20_000_000,
        });
        stream.record_report_windows(1_900_000_000, current, None);
        assert_eq!(stream.take_report_max(1_900_000_000), 0);
        stream.record_report_windows(2_200_000_000, current, None);
        // FPS 与报告输出的时机相互独立；即使峰值的原始时间戳
        // 已过去两秒以上，这份报告仍然是新鲜的。
        assert_eq!(stream.take_report_max(3_200_000_000), 20_000_000);
        stream.record_report_windows(3_250_000_000, current, None);
        assert_eq!(stream.take_report_max(3_300_000_000), 0);
        assert_eq!(std::mem::size_of::<FrameReportValue>(), 24);
    }

    fn stream_at_fps(fps: u64, frames: usize) -> FpsStream {
        let mut stream = FpsStream::new(1_000_000_000);
        let delta = 1_000_000_000 / fps;
        for index in 0..frames {
            stream.on_frame(1_000_000_000 + delta * index as u64);
        }
        stream
    }

    #[test]
    fn stable_refresh_rates_are_accepted() {
        for fps in [
            24, 30, 60, 90, 120, 144, 165, 180, 240, 360, 480, 540, 600, 1000,
        ] {
            let stream = stream_at_fps(fps, fps as usize + 1);
            assert!(stream.selection_score(stream.last_seen_ns).is_finite());
            assert!((stream.cur_fps - fps as f64).abs() < 0.2);
        }
    }

    #[test]
    fn high_rate_startup_still_requires_a_real_warmup_window() {
        let stream = stream_at_fps(600, 80);
        assert_eq!(
            stream.selection_score(stream.last_seen_ns),
            f64::NEG_INFINITY
        );
    }

    #[test]
    fn short_duplicate_events_do_not_shorten_real_frame_intervals() {
        for fps in [60, 120, 144, 165, 240, 360, 480, 600, 1000] {
            let mut stream = FpsStream::new(FPS_WINDOW_NS);
            let interval = FPS_WINDOW_NS / fps;
            let mut last_frame = 0;
            for index in 0..=fps * 2 {
                last_frame = FPS_WINDOW_NS + index * interval;
                stream.on_frame(last_frame);
                stream.on_frame(last_frame + 200_000);
                stream.on_frame(last_frame + 800_000);
            }
            assert!(
                (stream.cur_fps - fps as f64).abs() < 0.2,
                "{fps}: {}",
                stream.cur_fps
            );
            assert_eq!(stream.last_ts, last_frame);
            assert_eq!(stream.last_seen_ns, last_frame);
            assert_eq!(stream.frame_times.front(), Some(&interval));
            assert_eq!(stream.take_report_max(last_frame + 800_000), interval);
            assert!(stream.selection_score(last_frame).is_finite());
        }
    }

    #[test]
    fn ignored_events_cannot_complete_warmup_or_refresh_the_stream() {
        let start = FPS_WINDOW_NS;
        let mut stream = FpsStream::new(start);
        for index in 0..=8 {
            stream.on_frame(start + index * 18_687_500);
        }
        let accepted = start + 149_500_000;
        for timestamp in [accepted, accepted - 1, accepted + 800_000] {
            stream.on_frame(timestamp);
        }
        assert_eq!(stream.last_ts, accepted);
        assert_eq!(stream.last_seen_ns, accepted);
        assert_eq!(stream.frame_times.len(), 8);
        assert_eq!(
            stream.selection_score(accepted + 800_000),
            f64::NEG_INFINITY
        );
    }

    #[test]
    fn refresh_rate_changes_follow_timestamps_without_a_fixed_display_cap() {
        let mut stream = FpsStream::new(FPS_WINDOW_NS);
        let mut timestamp = FPS_WINDOW_NS;
        stream.on_frame(timestamp);
        for fps in [120, 165, 60, 144, 240, 480, 90] {
            let interval = FPS_WINDOW_NS / fps;
            for _ in 0..fps * 2 {
                timestamp += interval;
                stream.on_frame(timestamp);
                stream.on_frame(timestamp + 800_000);
            }
            assert!(
                (stream.cur_fps - fps as f64).abs() < 0.2,
                "{fps}: {}",
                stream.cur_fps
            );
            assert!(stream.selection_score(timestamp).is_finite());
        }
    }

    #[test]
    fn ignored_batch_does_not_move_accepted_timestamp_or_freshness() {
        let mut stream = stream_at_fps(120, 121);
        let accepted = stream.last_ts;
        let previous_fps = stream.cur_fps;
        stream.on_frame_batch(accepted, accepted + 50_000_000, 0);
        stream.on_frame_batch(accepted, accepted + 50_000_000, 100);
        stream.on_frame_batch(accepted + 1, accepted, 1);
        assert_eq!(stream.last_ts, accepted);
        assert_eq!(stream.last_seen_ns, accepted);
        assert_eq!(stream.cur_fps, previous_fps);
        stream.on_frame(accepted + 100_000_000);
        assert_eq!(stream.frame_times.front(), Some(&100_000_000));
    }

    #[test]
    fn high_refresh_batches_keep_the_full_one_second_window() {
        for fps in [60, 120, 144, 165, 240, 360, 480, 600, 1000] {
            let mut stream = FpsStream::new(FPS_WINDOW_NS);
            let interval = FPS_WINDOW_NS / fps;
            stream.on_frame_batch(FPS_WINDOW_NS, FPS_WINDOW_NS + fps * interval, fps);
            assert!((stream.cur_fps - fps as f64).abs() < 0.2);
            assert_eq!(stream.frame_times.len(), fps as usize);
            assert!(stream.selection_score(stream.last_seen_ns).is_finite());
        }
    }

    #[test]
    fn oversized_batch_remains_bounded_to_the_measurement_window() {
        let mut stream = FpsStream::new(FPS_WINDOW_NS);
        stream.on_frame_batch(FPS_WINDOW_NS, u64::MAX, u64::MAX / MIN_FRAME_NS - 1000);
        assert!(stream.frame_times.len() <= (FPS_WINDOW_NS / MIN_FRAME_NS) as usize);
        assert!(stream.cur_fps.is_finite());
    }

    #[test]
    fn stream_needs_a_real_warmup_window() {
        let stream = stream_at_fps(120, 8);
        assert_eq!(
            stream.selection_score(stream.last_seen_ns),
            f64::NEG_INFINITY
        );
    }

    #[test]
    fn fresh_stream_outranks_stale_stream() {
        let fresh = stream_at_fps(120, 121);
        let stale = stream_at_fps(120, 121);
        let now = fresh.last_seen_ns;
        assert!(
            fresh.selection_score(now)
                > stale.selection_score(now.saturating_add(STREAM_STALE_NS / 2))
        );
    }

    #[test]
    fn stream_keys_do_not_collide_across_processes() {
        let left = FrameStreamKey {
            pid: 100,
            tid: 0,
            surface_ptr: 0x1234,
        };
        let right = FrameStreamKey { pid: 101, ..left };
        assert_ne!(left, right);
    }

    #[test]
    fn long_jank_frame_is_kept_after_warmup() {
        let mut stream = stream_at_fps(60, 70);
        let next = stream.last_seen_ns + 450_000_000;
        stream.on_frame(next);
        assert_eq!(stream.frame_times.front().copied(), Some(450_000_000));
        assert!(stream.selection_score(next).is_finite());
        assert!(stream.cur_fps < 45.0);
    }

    #[test]
    fn real_pause_starts_a_new_warmup_window() {
        let mut stream = stream_at_fps(60, 70);
        let next = stream.last_seen_ns + PAUSE_FRAME_NS + 1;
        stream.on_frame(next);
        assert!(stream.frame_times.is_empty());
        assert_eq!(stream.selection_score(next), f64::NEG_INFINITY);
    }

    #[test]
    fn multi_second_jank_is_not_mistaken_for_a_pause() {
        let mut stream = stream_at_fps(60, 70);
        let next = stream.last_seen_ns + 2_500_000_000;
        stream.on_frame(next);
        assert_eq!(
            stream.frame_times.front().copied(),
            Some(MAX_RECORDED_FRAME_NS)
        );
        assert!(stream.selection_score(next).is_finite());
    }
}
