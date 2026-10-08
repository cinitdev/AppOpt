// Android 不同版本和厂商 ROM 的 libgui queueBuffer 符号不完全一致。
// 每次只挂一个候选，必须实际产生稳定 FPS 才确认；无帧时再切换下一个，避免同时挂载造成重复计帧。
pub(crate) const LIBGUI_FRAME_SYMBOLS: &[&str] = &[
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi",
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferiPNS_24SurfaceQueueBufferOutputE",
    "_ZN7android7Surface16hook_queueBufferEP13ANativeWindowP19ANativeWindowBufferi",
    "_ZN7android7Surface19queueBufferInternalEP13ANativeWindowP19ANativeWindowBufferi",
    "_ZN7android7Surface27hook_queueBuffer_DEPRECATEDEP13ANativeWindowP19ANativeWindowBuffer",
    // Android 17 的 queueBuffer 改用 GraphicBuffer/Fence 和新的输入结构体。
    // 放在旧候选之后作为兼容兜底，避免改变 Android 12-16 的探测顺序。
    "_ZN7android7Surface11queueBufferERKNS_2spINS_13GraphicBufferEEERKNS1_INS_5FenceEEEPNS_24SurfaceQueueBufferOutputE",
    "_ZN7android7Surface11queueBufferERKNS_2spINS_13GraphicBufferEEERKNS_23SurfaceQueueBufferInputEPNS_24SurfaceQueueBufferOutputE",
];
pub(crate) const LIBGUI_FRAME_SYMBOL_NAMES: &[&str] = &[
    "Surface::queueBuffer",
    "Surface::queueBuffer(Output)",
    "Surface::hook_queueBuffer",
    "Surface::queueBufferInternal",
    "Surface::hook_queueBuffer_DEPRECATED",
    "Surface::queueBuffer(GraphicBuffer,Fence)",
    "Surface::queueBuffer(GraphicBuffer,QueueInput)",
];
pub(crate) const LIBGUI_FRAME_SYMBOL_NAME_CSTRS: &[&[u8]] = &[
    b"Surface::queueBuffer\0",
    b"Surface::queueBuffer(Output)\0",
    b"Surface::hook_queueBuffer\0",
    b"Surface::queueBufferInternal\0",
    b"Surface::hook_queueBuffer_DEPRECATED\0",
    b"Surface::queueBuffer(GraphicBuffer,Fence)\0",
    b"Surface::queueBuffer(GraphicBuffer,QueueInput)\0",
];
pub(crate) const UNKNOWN_FRAME_SYMBOL_CSTR: &[u8] =
    b"Surface::queueBuffer(\xE6\x9C\xAA\xE7\x9F\xA5\xE7\xAC\xA6\xE5\x8F\xB7)\0";
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
