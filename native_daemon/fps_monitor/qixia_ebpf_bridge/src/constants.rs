// 读取系统实际记录的合成时间；BLAST transactionCallback 在部分系统中不使用
// latchTime，优化后对应参数寄存器可能是无关值，不能仅凭导出符号原型读取。
// 回调参数无效时，优先读 updateFrameTimestamps，再尝试 addLatch。
// 部分视频生产者不订阅帧历史，addLatch 不一定执行，不能作为唯一兼容入口。
// 新系统可能不更新生产者帧历史，因此仍需保留参数有效的完成回调。
// 按完整符号确认 64/32 位 ABI；找不到时走现有降级，不退回缓冲区提交计数。
pub(crate) const LIBGUI_FRAME_SYMBOLS: &[&str] = &[
    "_ZN7android16BLASTBufferQueue19transactionCallbackElRKNS_2spINS_5FenceEEERKNSt3__16vectorINS_19SurfaceControlStatsENS6_9allocatorIS8_EEEE",
    "_ZN7android16BLASTBufferQueue19transactionCallbackExRKNS_2spINS_5FenceEEERKNSt3__16vectorINS_19SurfaceControlStatsENS6_9allocatorIS8_EEEE",
    "_ZN7android23BLASTBufferItemConsumer21updateFrameTimestampsEmlRKNS_2spINS_5FenceEEES5_S5_NS_16CompositorTimingEll",
    "_ZN7android23BLASTBufferItemConsumer21updateFrameTimestampsEyxRKNS_2spINS_5FenceEEES5_S5_NS_16CompositorTimingExx",
    "_ZN7android25ConsumerFrameEventHistory8addLatchEml",
    "_ZN7android25ConsumerFrameEventHistory8addLatchEyx",
];
pub(crate) const LIBGUI_FRAME_SYMBOL_NAMES: &[&str] = &[
    "BLASTBufferQueue::transactionCallback(64位)",
    "BLASTBufferQueue::transactionCallback(32位)",
    "BLASTBufferItemConsumer::updateFrameTimestamps(64位)",
    "BLASTBufferItemConsumer::updateFrameTimestamps(32位)",
    "ConsumerFrameEventHistory::addLatch(64位)",
    "ConsumerFrameEventHistory::addLatch(32位)",
];
pub(crate) const LIBGUI_FRAME_SYMBOL_NAME_CSTRS: &[&[u8]] = &[
    b"BLASTBufferQueue::transactionCallback(LP64)\0",
    b"BLASTBufferQueue::transactionCallback(ILP32)\0",
    b"BLASTBufferItemConsumer::updateFrameTimestamps(LP64)\0",
    b"BLASTBufferItemConsumer::updateFrameTimestamps(ILP32)\0",
    b"ConsumerFrameEventHistory::addLatch(LP64)\0",
    b"ConsumerFrameEventHistory::addLatch(ILP32)\0",
];
pub(crate) const UNKNOWN_FRAME_SYMBOL_CSTR: &[u8] = b"unknown frame callback\0";

pub(crate) fn frame_callback_abi(candidate_index: usize) -> u32 {
    // 1/2：完成回调，3/4：帧历史，5/6：时间戳更新；每组为 64/32 位。
    [1, 2, 5, 6, 3, 4][candidate_index]
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
