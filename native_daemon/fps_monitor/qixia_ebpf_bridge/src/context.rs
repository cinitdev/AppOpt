use crate::fps_stream::{FpsStream, FrameStreamKey};
pub(crate) use crate::fps_stream::FrameReportValue;
use aya::maps::Array as AyaArray;
use aya::maps::HashMap as AyaHashMap;
use aya::maps::perf::PerfEventArrayBuffer;
use aya::maps::{MapData, RingBuf};
use aya::programs::uprobe::UProbeLinkId;
use aya::{Ebpf, Pod};
use std::collections::{HashMap, HashSet};
use std::ffi::CString;
use std::path::PathBuf;

#[repr(C)]
#[derive(Clone, Copy)]
pub(crate) struct FrameEvent {
    // 必须和 bpf/queuebuffer_probe*.bpf.c 中写入 events 映射的结构体布局一致。
    pub(crate) timestamp_ns: u64,
    pub(crate) pid: u32,
    pub(crate) tid: u32,
    pub(crate) surface_ptr: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Default, Eq, Hash, PartialEq)]
pub(crate) struct FrameStatsKey {
    pub(crate) pid: u32,
    pub(crate) tid: u32,
    pub(crate) surface_ptr: u64,
}

unsafe impl Pod for FrameStatsKey {}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub(crate) struct FrameStatsValue {
    pub(crate) last_ts: u64,
    pub(crate) total_frames: u64,
}

unsafe impl Pod for FrameStatsValue {}

unsafe impl Pod for FrameReportValue {}

#[derive(Clone, Copy, Default)]
pub(crate) struct FrameStatsSnapshot {
    pub(crate) last_ts: u64,
    pub(crate) total_frames: u64,
}

#[derive(Clone, Copy)]
pub(crate) struct PidSymbolProbe {
    pub(crate) candidate_index: usize,
    pub(crate) candidate_started_ns: u64,
    pub(crate) round_started_ns: u64,
    pub(crate) confirmed: bool,
    pub(crate) confirmed_once: bool,
    pub(crate) exhausted: bool,
    pub(crate) attachable_mask: u32,
    pub(crate) no_frame_mask: u32,
    pub(crate) stalled_mask: u32,
    pub(crate) unavailable_mask: u32,
}

pub(crate) enum EventBackend {
    // 优先使用 RingBuf；旧内核使用只读计数映射，PerfEvent 保留为最后一条 eBPF 兼容链。
    RingBuf(RingBuf<MapData>),
    StatsMap,
    PerfEvent(Vec<PerfEventArrayBuffer<MapData>>),
}

#[derive(Clone, Copy)]
pub(crate) enum BackendKind {
    RingBuf,
    StatsMap,
    PerfEvent,
}

impl BackendKind {
    pub(crate) fn label(self) -> &'static str {
        match self {
            Self::RingBuf => "RingBuf",
            Self::StatsMap => "StatsMap",
            Self::PerfEvent => "PerfEvent",
        }
    }
}

#[repr(C)]
pub struct QixiaThreadsEbpfCtx {
    // bpf 必须和 backend/program 一起持有，ctx 生命周期结束前不能释放。
    pub(crate) bpf: Ebpf,
    pub(crate) backend: EventBackend,
    pub(crate) target_tgids: AyaHashMap<MapData, u32, u32>,
    pub(crate) frame_stats: Option<AyaHashMap<MapData, FrameStatsKey, FrameStatsValue>>,
    pub(crate) frame_report: Option<AyaHashMap<MapData, FrameStatsKey, FrameReportValue>>,
    pub(crate) frame_report_prev: Option<AyaHashMap<MapData, FrameStatsKey, FrameReportValue>>,
    pub(crate) use_frame_stats: bool,
    // StatsMap 溢出时，PerfEvent 后端可以关闭省流开关并回到逐帧事件。
    pub(crate) perf_stats_only: Option<AyaArray<MapData, u32>>,
    pub(crate) frame_stats_drops: Option<AyaArray<MapData, u32>>,
    pub(crate) frame_stats_drops_seen: u32,
    pub(crate) frame_stats_drops_pending: u64,
    pub(crate) frame_stats_drops_last_log_ns: u64,
    pub(crate) ringbuf_drops: Option<AyaArray<MapData, u32>>,
    pub(crate) ringbuf_drops_seen: u32,
    pub(crate) ringbuf_drops_pending: u64,
    pub(crate) ringbuf_drops_last_log_ns: u64,
    // 每个目标 PID 单独挂载 uprobe，target_tgids 再在内核侧做一次身份过滤。
    pub(crate) target_pids: HashSet<u32>,
    pub(crate) pid: i32,
    pub(crate) streams: HashMap<FrameStreamKey, FpsStream>,
    pub(crate) selected_stream: Option<FrameStreamKey>,
    pub(crate) pending_stream: Option<FrameStreamKey>,
    pub(crate) pending_stream_since_ns: u64,
    pub(crate) stat_snapshots: HashMap<FrameStatsKey, FrameStatsSnapshot>,
    pub(crate) frame_stats_updates: Vec<(FrameStatsKey, FrameStatsValue)>,
    pub(crate) frame_stats_last_poll_ns: u64,
    pub(crate) frame_stats_last_prune_ns: u64,
    pub(crate) pid_links: HashMap<u32, HashMap<u32, Vec<UProbeLinkId>>>,
    pub(crate) pid_starttimes: HashMap<u32, u64>,
    pub(crate) pid_task_starttimes: HashMap<u32, HashMap<u32, u64>>,
    pub(crate) pid_libgui_paths: HashMap<u32, PathBuf>,
    pub(crate) libgui_symbol_offsets: HashMap<PathBuf, Vec<Option<u64>>>,
    pub(crate) pid_symbols: HashMap<u32, CString>,
    pub(crate) pid_symbol_offsets: HashMap<u32, u64>,
    pub(crate) pid_symbol_probes: HashMap<u32, PidSymbolProbe>,
    pub(crate) detailed_logging: bool,
    pub(crate) frame_mode_reported: bool,
    pub(crate) perf_lost_pending: u64,
    pub(crate) perf_lost_last_log_ns: u64,
    pub(crate) perf_buffer_cursor: usize,
    pub(crate) cur_fps: f64,
    pub(crate) symbol: CString,
    pub(crate) backend_label: CString,
    pub(crate) backend_selection_note: CString,
    pub(crate) startup_note: CString,
    pub(crate) last_error: CString,
    pub(crate) target_pkg: Option<String>,
}
