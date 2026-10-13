//! FPS 采集与自动亲和性分配共用的帧观测数据。
use std::ffi::c_double;

/// 轻量帧源快照；停帧判断不必每次排序逐帧耗时，也不使用其他图层的事件数。
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct QixiaThreadsFrameSource {
    pub generation: u64,
    pub last_frame_ns: u64,
    pub surface: u64,
    pub pid: i32,
    pub tid: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct QixiaThreadsFrameMetrics {
    pub fps: c_double,
    pub median_interval_ns: u64,
    pub p95_interval_ns: u64,
    pub max_interval_ns: u64,
    pub frame_count: u32,
    pub flags: u32,
    pub source_pid: i32,
    pub source_tid: i32,
    pub source_surface: u64,
    pub last_frame_ns: u64,
}
