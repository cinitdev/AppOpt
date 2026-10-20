use crate::context::{FrameEvent, QixiaThreadsEbpfCtx};
use crate::fps_stream::{FpsStream, FrameStreamKey, MAX_STREAMS, STREAM_STALE_NS};

pub(crate) fn stream_key(event: FrameEvent) -> FrameStreamKey {
    stream_key_from_parts(event.pid, event.tid, event.surface_ptr)
}

pub(crate) fn stream_key_from_parts(pid: u32, tid: u32, surface_ptr: u64) -> FrameStreamKey {
    FrameStreamKey {
        pid,
        tid: if surface_ptr == 0 { tid } else { 0 },
        surface_ptr,
    }
}

pub(crate) fn refresh_current_fps(ctx: &mut QixiaThreadsEbpfCtx, now_ns: u64) {
    const STREAM_SWITCH_SCORE_MARGIN: f64 = 64.0;
    const STREAM_SWITCH_HOLD_NS: u64 = 600_000_000;
    ctx.streams.retain(|key, stream| {
        ctx.target_pids.contains(&key.pid)
            && now_ns.saturating_sub(stream.last_seen_ns) <= STREAM_STALE_NS
    });

    if ctx.streams.len() > MAX_STREAMS {
        let mut streams = ctx
            .streams
            .iter()
            .map(|(key, stream)| (*key, stream.last_seen_ns))
            .collect::<Vec<_>>();
        streams.sort_by_key(|(_, last_seen_ns)| *last_seen_ns);
        for (key, _) in streams.into_iter().take(ctx.streams.len() - MAX_STREAMS) {
            ctx.streams.remove(&key);
        }
    }

    // 不按线程名推断画面类型，也不能把未识别的队列直接当作视频。
    // 所有完成预热的帧源参与比较，零散更新的队列不能强制排除持续合成的画面。
    let best = ctx
        .streams
        .iter()
        .filter_map(|(key, stream)| {
            let score = stream.selection_score(now_ns);
            score.is_finite().then_some((*key, score))
        })
        .max_by(|left, right| {
            left.1
                .partial_cmp(&right.1)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

    let current = ctx.selected_stream.and_then(|key| {
        ctx.streams
            .get(&key)
            .map(|stream| (key, stream.selection_score(now_ns)))
            .filter(|(_, score)| score.is_finite())
    });
    let previous = ctx.selected_stream;
    ctx.selected_stream = match (current, best) {
        (_, None) => {
            ctx.pending_stream = None;
            ctx.pending_stream_since_ns = 0;
            None
        },
        (None, Some((key, _))) => {
            ctx.pending_stream = None;
            ctx.pending_stream_since_ns = 0;
            Some(key)
        }
        (Some((current_key, _)), Some((best_key, _))) if current_key == best_key => {
            ctx.pending_stream = None;
            ctx.pending_stream_since_ns = 0;
            Some(current_key)
        }
        (Some((current_key, current_score)), Some((best_key, best_score))) => {
            if best_score < current_score + STREAM_SWITCH_SCORE_MARGIN {
                ctx.pending_stream = None;
                ctx.pending_stream_since_ns = 0;
                Some(current_key)
            } else if ctx.pending_stream == Some(best_key) {
                if now_ns.saturating_sub(ctx.pending_stream_since_ns) >= STREAM_SWITCH_HOLD_NS {
                    ctx.pending_stream = None;
                    ctx.pending_stream_since_ns = 0;
                    Some(best_key)
                } else {
                    Some(current_key)
                }
            } else {
                ctx.pending_stream = Some(best_key);
                ctx.pending_stream_since_ns = now_ns;
                Some(current_key)
            }
        }
    };
    if ctx.selected_stream != previous {
        if let Some(key) = ctx.selected_stream {
            if ctx.source_generation > 0 {
                // FPS 窗口属于新帧源自身；最大帧间隔另从切换时刻开始报告，
                // 避免把后台图层切换前积攒的卡顿归到当前画面。
                if let Some(stream) = ctx.streams.get_mut(&key) {
                    stream.discard_report_before(now_ns);
                }
            }
            ctx.source_generation = ctx.source_generation.saturating_add(1);
        }
    }
    ctx.cur_fps = ctx
        .selected_stream
        .and_then(|key| ctx.streams.get(&key))
        .map_or(0.0, |stream| stream.fps_at(now_ns));
    if ctx.cur_fps > 0.0 {
        ctx.recovering_samples = false;
    }
    ctx.pid = ctx.selected_stream.map_or_else(
        || {
            if ctx.pid > 0 && ctx.target_pids.contains(&(ctx.pid as u32)) {
                ctx.pid
            } else {
                ctx.target_pids
                    .iter()
                    .copied()
                    .next()
                    .map_or(-1, |pid| pid as i32)
            }
        },
        |key| key.pid as i32,
    );
    if let Some(symbol) = ctx
        .selected_stream
        .and_then(|key| ctx.pid_symbols.get(&key.pid))
        .cloned()
    {
        ctx.symbol = symbol;
    }
}

pub(crate) fn on_frame(ctx: &mut QixiaThreadsEbpfCtx, event: FrameEvent) -> bool {
    // 内核映射已经过滤一次；用户态再核对集合，防止目标切换时消费排队的旧事件。
    if event.timestamp_ns < ctx.samples_since_ns
        || !ctx.target_pids.contains(&event.pid)
        || !ctx
            .pid_symbol_probes
            .get(&event.pid)
            .is_some_and(|probe| probe.accepts_timestamp(event.timestamp_ns))
    {
        return false;
    }

    let key = stream_key(event);
    ctx.streams
        .entry(key)
        .or_insert_with(|| FpsStream::new(event.timestamp_ns))
        .on_frame(event.timestamp_ns)
}

pub(crate) fn clear_pid_samples(ctx: &mut QixiaThreadsEbpfCtx, pid: u32) {
    ctx.frame_stats_last_poll_ns = 0;
    ctx.frame_stats_last_prune_ns = 0;
    ctx.streams.retain(|key, _| key.pid != pid);
    ctx.stat_snapshots.retain(|key, _| key.pid != pid);
    if let Some(stats) = ctx.frame_stats.as_mut() {
        let keys = stats
            .keys()
            .filter_map(Result::ok)
            .filter(|key| key.pid == pid)
            .collect::<Vec<_>>();
        for key in keys {
            let _ = stats.remove(&key);
            if let Some(report) = ctx.frame_report.as_mut() {
                let _ = report.remove(&key);
            }
            if let Some(report) = ctx.frame_report_prev.as_mut() {
                let _ = report.remove(&key);
            }
        }
    }
    if ctx.selected_stream.is_some_and(|key| key.pid == pid) {
        ctx.selected_stream = None;
        ctx.cur_fps = 0.0;
    }
    if ctx.pending_stream.is_some_and(|key| key.pid == pid) {
        ctx.pending_stream = None;
        ctx.pending_stream_since_ns = 0;
    }
}

pub(crate) fn reset_sample_window(ctx: &mut QixiaThreadsEbpfCtx, now_ns: u64) {
    // 保留探针及内核累计计数，只重建用户态窗口；排队的旧事件不能跨过边界。
    ctx.streams.clear();
    ctx.stat_snapshots.clear();
    ctx.selected_stream = None;
    ctx.pending_stream = None;
    ctx.pending_stream_since_ns = 0;
    ctx.cur_fps = 0.0;
    ctx.frame_stats_last_poll_ns = 0;
    ctx.samples_since_ns = now_ns;
    ctx.recovering_samples = true;
}
