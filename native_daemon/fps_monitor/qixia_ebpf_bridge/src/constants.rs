// 只把合成完成回调作为 eBPF FPS 来源，提交缓冲区不等于实际合成一帧。
// LP64 的 nsecs_t 编码为 l，32 位 Android 编码为 x；按符号确认参数 ABI。
// 没有可用完成回调时走原有 SurfaceFlinger 降级，不退回易虚高的提交计数。
pub(crate) const LIBGUI_FRAME_SYMBOLS: &[&str] = &[
    "_ZN7android16BLASTBufferQueue19transactionCallbackElRKNS_2spINS_5FenceEEERKNSt3__16vectorINS_19SurfaceControlStatsENS6_9allocatorIS8_EEEE",
    "_ZN7android16BLASTBufferQueue19transactionCallbackExRKNS_2spINS_5FenceEEERKNSt3__16vectorINS_19SurfaceControlStatsENS6_9allocatorIS8_EEEE",
];
pub(crate) const LIBGUI_FRAME_SYMBOL_NAMES: &[&str] = &[
    "BLASTBufferQueue::transactionCallback(64位)",
    "BLASTBufferQueue::transactionCallback(32位)",
];
pub(crate) const LIBGUI_FRAME_SYMBOL_NAME_CSTRS: &[&[u8]] = &[
    b"BLASTBufferQueue::transactionCallback(LP64)\0",
    b"BLASTBufferQueue::transactionCallback(ILP32)\0",
];
pub(crate) const UNKNOWN_FRAME_SYMBOL_CSTR: &[u8] = b"unknown frame callback\0";

pub(crate) fn frame_callback_abi(candidate_index: usize) -> u32 {
    if candidate_index == 1 { 2 } else { 1 }
}
pub(crate) const SYMBOL_PROBE_NS: u64 = 3_000_000_000;
pub(crate) const FRAME_STATS_RETENTION_NS: u64 = 10_000_000_000;
pub(crate) const FRAME_STATS_POLL_INTERVAL_NS: u64 = 250_000_000;
pub(crate) const FRAME_STATS_PRUNE_INTERVAL_NS: u64 = 1_000_000_000;
pub(crate) const MAX_RING_EVENTS_PER_POLL: usize = 2048;
pub(crate) const MAX_PERF_BUFFERS_PER_POLL: usize = 8;
pub(crate) const MAX_PERF_EVENTS_PER_POLL: usize = 4096;
pub(crate) const PERF_LOST_LOG_INTERVAL_NS: u64 = 30_000_000_000;
pub(crate) const RINGBUF_DROP_LOG_INTERVAL_NS: u64 = 30_000_000_000;
pub(crate) const FRAME_STATS_DROP_LOG_INTERVAL_NS: u64 = 30_000_000_000;
pub(crate) const MAX_ERROR_DETAILS: usize = 8;
pub(crate) const RINGBUF_MIN_KERNEL: (u32, u32) = (5, 8);
