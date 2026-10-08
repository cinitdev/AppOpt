use crate::context::{QixiaThreadsEbpfCtx, FrameEvent};
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
    const STREAM_SWITCH_SCORE_MARGIN: f64 = 120.0;
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
    ctx.selected_stream = match (current, best) {
        (_, None) => None,
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
    ctx.cur_fps = ctx
        .selected_stream
        .and_then(|key| ctx.streams.get(&key))
        .map_or(0.0, |stream| stream.cur_fps);
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
    if !ctx.target_pids.contains(&event.pid) {
        return false;
    }

    let key = stream_key(event);
    ctx.streams
        .entry(key)
        .or_insert_with(|| FpsStream::new(event.timestamp_ns))
        .on_frame(event.timestamp_ns);
    true
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
            if let Some(report) = ctx.frame_report.as_mut() { let _ = report.remove(&key); }
            if let Some(report) = ctx.frame_report_prev.as_mut() { let _ = report.remove(&key); }
        }
    }
    if ctx.selected_stream.is_some_and(|key| key.pid == pid) {
        ctx.selected_stream = None;
        ctx.cur_fps = 0.0;
    }
}
