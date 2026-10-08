use crate::clock::monotonic_ns;
use crate::constants::{
    FRAME_STATS_DROP_LOG_INTERVAL_NS, FRAME_STATS_POLL_INTERVAL_NS, FRAME_STATS_PRUNE_INTERVAL_NS,
    FRAME_STATS_RETENTION_NS, RINGBUF_DROP_LOG_INTERVAL_NS,
};
use crate::context::{QixiaThreadsEbpfCtx, EventBackend, FrameStatsSnapshot};
use crate::fps_stream::FpsStream;
use crate::streams::{refresh_current_fps, stream_key_from_parts};

pub(crate) fn prune_frame_stats(ctx: &mut QixiaThreadsEbpfCtx) {
    if ctx.frame_stats.is_none() {
        return;
    }
    let now_ns = monotonic_ns();
    if now_ns > 0
        && ctx.frame_stats_last_prune_ns > 0
        && now_ns.saturating_sub(ctx.frame_stats_last_prune_ns) < FRAME_STATS_PRUNE_INTERVAL_NS
    {
        return;
    }
    if now_ns > 0 {
        ctx.frame_stats_last_prune_ns = now_ns;
    }

    let stale_keys = {
        let Some(stats) = ctx.frame_stats.as_ref() else {
            return;
        };
        stats
            .iter()
            .filter_map(Result::ok)
            .filter(|(key, value)| {
                !ctx.target_pids.contains(&key.pid)
                    || value.total_frames == 0
                    || value.last_ts == 0
                    || (now_ns > 0
                        && now_ns.saturating_sub(value.last_ts) > FRAME_STATS_RETENTION_NS)
            })
            .map(|(key, _)| key)
            .collect::<Vec<_>>()
    };
    if let Some(stats) = ctx.frame_stats.as_mut() {
        for key in &stale_keys {
            let _ = stats.remove(key);
        }
    }
    for key in stale_keys {
        if let Some(report) = ctx.frame_report.as_mut() { let _ = report.remove(&key); }
        if let Some(report) = ctx.frame_report_prev.as_mut() { let _ = report.remove(&key); }
        ctx.stat_snapshots.remove(&key);
        let stream = stream_key_from_parts(key.pid, key.tid, key.surface_ptr);
        ctx.streams.remove(&stream);
        if ctx.selected_stream == Some(stream) {
            ctx.selected_stream = None;
            ctx.cur_fps = 0.0;
        }
    }
}

pub(crate) fn poll_frame_stats(ctx: &mut QixiaThreadsEbpfCtx) -> Result<i32, String> {
    let now_ns = monotonic_ns();
    if now_ns > 0
        && ctx.frame_stats_last_poll_ns > 0
        && now_ns.saturating_sub(ctx.frame_stats_last_poll_ns) < FRAME_STATS_POLL_INTERVAL_NS
    {
        return Ok(0);
    }
    if now_ns > 0 {
        ctx.frame_stats_last_poll_ns = now_ns;
    }
    prune_frame_stats(ctx);
    let Some(stats) = ctx.frame_stats.as_ref() else {
        return Ok(0);
    };
    let mut updates = std::mem::take(&mut ctx.frame_stats_updates);
    updates.clear();
    for item in stats.iter() {
        let (key, value) = item.map_err(|e| e.to_string())?;
        if value.total_frames == 0 || value.last_ts == 0 {
            continue;
        }
        if !ctx.target_pids.contains(&key.pid) {
            continue;
        }
        updates.push((key, value));
    }
    let mut accepted = 0i32;
    let mut latest_ts = 0u64;

    for (key, value) in updates.iter().copied() {
        let prev = ctx.stat_snapshots.get(&key).copied().unwrap_or_default();
        ctx.stat_snapshots.insert(
            key,
            FrameStatsSnapshot {
                last_ts: value.last_ts,
                total_frames: value.total_frames,
            },
        );

        let stream_key = stream_key_from_parts(key.pid, key.tid, key.surface_ptr);
        // 先读取当前窗口：若两次查询之间发生滚动，上一窗口映射中
        // 就会包含刚完成的当前窗口及其后续出现的峰值。
        let current = ctx.frame_report.as_ref().and_then(|map| map.get(&key, 0).ok());
        let previous = ctx
            .frame_report_prev
            .as_ref()
            .and_then(|map| map.get(&key, 0).ok());
        if current.is_some() || previous.is_some() {
            let stream = ctx
                .streams
                .entry(stream_key)
                .or_insert_with(|| FpsStream::new(value.last_ts));
            if ctx.frame_report_prev.is_some() {
                stream.record_report_windows(now_ns, current, previous);
            } else if let Some(report) = current {
                // 兼容已安装的旧版探针。
                stream.record_report_interval(report.interval_ts, report.max_interval_ns);
            }
        }
        if prev.total_frames == 0 || prev.last_ts == 0 {
            ctx.streams
                .entry(stream_key)
                .or_insert_with(|| FpsStream::new(value.last_ts));
            accepted = accepted.saturating_add(1);
            latest_ts = latest_ts.max(value.last_ts);
            continue;
        }

        if value.total_frames <= prev.total_frames || value.last_ts <= prev.last_ts {
            continue;
        }

        let frames = value.total_frames - prev.total_frames;
        ctx.streams
            .entry(stream_key)
            .or_insert_with(|| FpsStream::new(prev.last_ts))
            .on_frame_batch(prev.last_ts, value.last_ts, frames);
        accepted = accepted.saturating_add(frames.min(i32::MAX as u64) as i32);
        latest_ts = latest_ts.max(value.last_ts);
    }

    if accepted > 0 {
        if latest_ts > 0 {
            refresh_current_fps(ctx, latest_ts);
        }
    }

    updates.clear();
    ctx.frame_stats_updates = updates;
    Ok(accepted)
}

pub(crate) fn poll_ringbuf_drop_count(ctx: &mut QixiaThreadsEbpfCtx) -> Result<(), String> {
    let Some(drops) = ctx.ringbuf_drops.as_ref() else {
        return Ok(());
    };
    let total = drops.get(&0, 0).map_err(|error| error.to_string())?;
    if total > ctx.ringbuf_drops_seen {
        ctx.ringbuf_drops_pending = ctx
            .ringbuf_drops_pending
            .saturating_add(u64::from(total - ctx.ringbuf_drops_seen));
        ctx.ringbuf_drops_seen = total;
    }
    let now_ns = monotonic_ns();
    if ctx.detailed_logging
        && ctx.ringbuf_drops_pending > 0
        && (ctx.ringbuf_drops_last_log_ns == 0
            || now_ns == 0
            || now_ns.saturating_sub(ctx.ringbuf_drops_last_log_ns) >= RINGBUF_DROP_LOG_INTERVAL_NS)
    {
        log_warn!(
            "[FPS] RingBuf 缓冲区已满，丢弃帧事件={}；当前 FPS 可能短时偏低",
            ctx.ringbuf_drops_pending
        );
        ctx.ringbuf_drops_pending = 0;
        ctx.ringbuf_drops_last_log_ns = now_ns;
    }
    Ok(())
}

pub(crate) fn poll_frame_stats_drop_count(ctx: &mut QixiaThreadsEbpfCtx) -> Result<(), String> {
    let Some(drops) = ctx.frame_stats_drops.as_ref() else {
        return Ok(());
    };
    let total = drops.get(&0, 0).map_err(|error| error.to_string())?;
    let delta = total.wrapping_sub(ctx.frame_stats_drops_seen);
    if delta == 0 {
        return Ok(());
    }
    ctx.frame_stats_drops_seen = total;
    ctx.frame_stats_drops_pending = ctx
        .frame_stats_drops_pending
        .saturating_add(u64::from(delta));

    // PerfEvent 对象原本会在 frame_stats 可读后关闭逐帧事件。若映射已满，
    // 立即恢复事件通道，避免继续静默丢帧；StatsMap 后端则继续依赖过期键清理。
    if matches!(ctx.backend, EventBackend::PerfEvent(_))
        && ctx.use_frame_stats
        && let Some(mode) = ctx.perf_stats_only.as_mut()
    {
        if mode.set(0, 0, 0).is_ok() {
            ctx.use_frame_stats = false;
            ctx.frame_mode_reported = false;
            if ctx.detailed_logging {
                log_info!(
                    "[FPS] frame_stats map 已满: 丢失={}；PerfEvent 已恢复逐帧事件",
                    ctx.frame_stats_drops_pending
                );
            }
        }
    }

    let now_ns = monotonic_ns();
    if ctx.detailed_logging
        && ctx.frame_stats_drops_pending > 0
        && (ctx.frame_stats_drops_last_log_ns == 0
            || now_ns == 0
            || now_ns.saturating_sub(ctx.frame_stats_drops_last_log_ns)
                >= FRAME_STATS_DROP_LOG_INTERVAL_NS)
    {
        log_warn!(
            "[FPS] frame_stats map 记录失败={}；已清理过期帧源，当前 FPS 可能短时偏低",
            ctx.frame_stats_drops_pending
        );
        ctx.frame_stats_drops_pending = 0;
        ctx.frame_stats_drops_last_log_ns = now_ns;
    }
    Ok(())
}
