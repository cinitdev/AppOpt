use crate::clock::monotonic_ns;
use crate::constants::{
    MAX_PERF_BUFFERS_PER_POLL, MAX_PERF_EVENTS_PER_POLL, MAX_RING_EVENTS_PER_POLL,
    PERF_LOST_LOG_INTERVAL_NS,
};
use crate::context::{QixiaThreadsEbpfCtx, EventBackend, FrameEvent};
use crate::stats::{
    poll_frame_stats, poll_frame_stats_drop_count, poll_ringbuf_drop_count, prune_frame_stats,
};
use crate::streams::{on_frame, refresh_current_fps};
use aya::maps::perf::PerfEvent;
use std::ops::ControlFlow;
use std::ptr;

pub(crate) unsafe fn read_frame_event(buf: &[u8]) -> Option<FrameEvent> {
    // eBPF 映射中存放的是紧凑字节数据，不能假设对齐，所以用 read_unaligned。
    if buf.len() < std::mem::size_of::<FrameEvent>() {
        return None;
    }
    Some(unsafe { ptr::read_unaligned(buf.as_ptr().cast::<FrameEvent>()) })
}

pub(crate) fn read_split_frame_event(head: &[u8], tail: &[u8]) -> Option<FrameEvent> {
    // PerfEvent 可能把一条样本分成 head/tail 两段，需要拼回完整 FrameEvent。
    let size = std::mem::size_of::<FrameEvent>();
    if head.len().saturating_add(tail.len()) < size {
        return None;
    }
    if head.len() >= size {
        return unsafe { read_frame_event(head) };
    }

    let mut buf = [0u8; std::mem::size_of::<FrameEvent>()];
    let head_len = head.len();
    buf[..head_len].copy_from_slice(head);
    buf[head_len..].copy_from_slice(&tail[..(size - head_len)]);
    unsafe { read_frame_event(&buf) }
}

pub(crate) fn poll_inner(ctx: &mut QixiaThreadsEbpfCtx) -> Result<i32, String> {
    // RingBuf/PerfEvent 逐帧模式也会让内核同步维护 frame_stats；定期清理
    // 已退出 PID、失效 Surface 和长时间停帧的键，避免线程频繁增删填满映射。
    prune_frame_stats(ctx);
    let mut events = Vec::new();
    let prefer_stats = matches!(ctx.backend, EventBackend::StatsMap)
        || (matches!(ctx.backend, EventBackend::PerfEvent(_)) && ctx.use_frame_stats);
    let prefer_stats = prefer_stats && ctx.frame_stats.is_some();
    let ringbuf_backend = matches!(&ctx.backend, EventBackend::RingBuf(_));

    if !ctx.frame_mode_reported {
        if ctx.detailed_logging {
            let source = match &ctx.backend {
                EventBackend::RingBuf(_) => "eBPF RingBuf逐帧事件",
                EventBackend::StatsMap => "eBPF frame_stats map",
                EventBackend::PerfEvent(_) if prefer_stats => {
                    "eBPF frame_stats map（PerfEvent省流）"
                }
                EventBackend::PerfEvent(_) => "eBPF PerfEvent逐帧事件",
            };
            log_info!(
                "[FPS] 计帧模式: 后端={} 来源={source}",
                ctx.backend_label.to_string_lossy()
            );
        }
        ctx.frame_mode_reported = true;
    }

    match &mut ctx.backend {
        EventBackend::RingBuf(ring) => {
            // RingBuf 是首选后端，事件直接从共享环形缓冲区中取出。
            for _ in 0..MAX_RING_EVENTS_PER_POLL {
                let Some(item) = ring.next() else {
                    break;
                };
                if let Some(event) = unsafe { read_frame_event(&item) } {
                    events.push(event);
                }
            }
        }
        EventBackend::StatsMap => {}
        EventBackend::PerfEvent(perf_buffers) => {
            // PerfEvent 是 Android/内核不支持 RingBuf mmap 时的备用后端。
            // 省流模式下 BPF 已停止写 events，只读 frame_stats 即可。继续扫描
            // 各 CPU 的空缓冲区会在 FPS 线程中制造没有收益的固定轮询开销。
            if prefer_stats {
                let result = poll_frame_stats(ctx);
                if let Err(error) = poll_frame_stats_drop_count(ctx) {
                    if ctx.detailed_logging {
                        log_warn!("[FPS] frame_stats 丢失计数读取失败，已停用该诊断: {error}");
                    }
                    ctx.frame_stats_drops = None;
                }
                return result;
            }
            // 逐帧兼容模式下每个在线 CPU 都有一个缓冲区，需要逐个读取并清空。
            let mut lost_samples = 0u64;
            let mut remaining_events = MAX_PERF_EVENTS_PER_POLL;
            let buffer_count = perf_buffers.len();
            let poll_count = buffer_count.min(MAX_PERF_BUFFERS_PER_POLL);
            let start = if buffer_count == 0 {
                0
            } else {
                ctx.perf_buffer_cursor % buffer_count
            };
            for offset in 0..poll_count {
                let index = (start + offset) % buffer_count;
                let Some(perf_buf) = perf_buffers.get_mut(index) else {
                    continue;
                };
                if remaining_events == 0 {
                    break;
                }
                let _ = perf_buf.try_fold((), |(), event| {
                    if remaining_events == 0 {
                        return ControlFlow::Break(());
                    }
                    remaining_events = remaining_events.saturating_sub(1);
                    match event {
                        PerfEvent::Sample { head, tail } => {
                            if let Some(frame) = read_split_frame_event(head, tail) {
                                events.push(frame);
                            }
                        }
                        PerfEvent::Lost { count } => {
                            lost_samples = lost_samples.saturating_add(count);
                        }
                    }
                    ControlFlow::Continue(())
                });
            }
            if buffer_count > 0 {
                ctx.perf_buffer_cursor = (start + poll_count) % buffer_count;
            }
            if lost_samples > 0 {
                ctx.perf_lost_pending = ctx.perf_lost_pending.saturating_add(lost_samples);
                let now_ns = monotonic_ns();
                if ctx.perf_lost_last_log_ns == 0
                    || now_ns == 0
                    || now_ns.saturating_sub(ctx.perf_lost_last_log_ns) >= PERF_LOST_LOG_INTERVAL_NS
                {
                    log_warn!("[FPS] PerfEvent 丢弃样本: {}", ctx.perf_lost_pending);
                    ctx.perf_lost_pending = 0;
                    ctx.perf_lost_last_log_ns = now_ns;
                }
            }
        }
    }

    if ringbuf_backend {
        if let Err(error) = poll_ringbuf_drop_count(ctx) {
            if ctx.detailed_logging {
                log_warn!("[FPS] RingBuf 丢帧计数读取失败，已停用该诊断: {error}");
            }
            ctx.ringbuf_drops = None;
        }
    }

    if let Err(error) = poll_frame_stats_drop_count(ctx) {
        if ctx.detailed_logging {
            log_warn!("[FPS] frame_stats 丢失计数读取失败，已停用该诊断: {error}");
        }
        ctx.frame_stats_drops = None;
    }

    if prefer_stats {
        return poll_frame_stats(ctx);
    }

    // PerfEvent 为各 CPU 分别维护队列，按 CPU 读取会破坏全局时间顺序；先按时间戳合并，
    // 再进入 Surface/TID 分流的滑动窗口。RingBuf 原本就是有序的，排序不会改变语义。
    events.sort_by_key(|event| event.timestamp_ns);

    let mut accepted = 0;
    let mut latest_ts = 0u64;
    for event in events {
        if on_frame(ctx, event) {
            accepted += 1;
            latest_ts = latest_ts.max(event.timestamp_ns);
        }
    }
    if latest_ts > 0 {
        // 一轮积压事件全部入窗后只重算一次活动 Surface，避免“事件数 × 帧流数”
        // 的重复评分把高帧率场景变成用户态 CPU 热点。
        refresh_current_fps(ctx, latest_ts);
    }

    Ok(accepted)
}
