use super::*;
// FPS 模块的公共导入和常量。
//
// 这个模块只在 Android/Linux 目标启用；Windows 主机 cargo check 会走 fps.rs 里的空实现。
// 真正的 Android 交叉编译由 build_module.sh no 验证。
//
// FPS 主路径是多进程 eBPF queueBuffer；RingBuf、StatsMap、PerfEvent 和
// SurfaceFlinger 组成按能力降级链。
pub(super) use std::collections::BTreeSet;
pub(super) use std::ffi::{CStr, CString};
pub(super) use std::fs;
pub(super) use std::io::{self, Read};
pub(super) use std::mem;
pub(super) use std::path::PathBuf;
pub(super) use std::ptr;
pub(super) use std::process::{Command, Stdio};
pub(super) use std::slice;
pub(super) use std::sync::mpsc;
pub(super) use std::thread;
pub(super) use std::time::{Duration, Instant};

pub(super) use qixia_ebpf_bridge::{
    qixia_ebpf_take_frame_max,
    qixia_ebpf_backend, qixia_ebpf_backend_note, qixia_ebpf_get, qixia_ebpf_last_error,
    qixia_ebpf_last_start_error, qixia_ebpf_metrics, qixia_ebpf_pid, qixia_ebpf_poll,
    qixia_ebpf_probe_state, qixia_ebpf_set_target_pids,
    qixia_ebpf_set_detailed_logging,
    qixia_ebpf_start_for_package, qixia_ebpf_symbol_display,
    qixia_ebpf_startup_note, qixia_ebpf_stop, QixiaThreadsEbpfCtx, QixiaThreadsFrameMetrics,
};
pub(super) use crate::process_index_find_package_pids;

// FPS 监测流程：
// 1. App 写 fps.cmd 请求开始监测某个包名。
// 2. Rust daemon 优先加载 RingBuf 对象；旧内核自动切 StatsMap 对象，仍然通过
//    libgui queueBuffer uprobe 在内核 map 中统计目标进程帧数。
// 3. 用户态为同包进程的现有/新增线程增量挂载 uprobe，并同步 TGID map，不重载 BPF 对象。
// 4. 只有 eBPF 无法加载或连续轮询报错才降级到 SurfaceFlinger Binder/CLI；
//    应用空闲、首帧尚未出现或暂时停帧都保留 eBPF 主路径。
// 5. 输出优先走 App 建立的 socket，socket 不可用时写 files/fps 兼容旧路径。
pub(super) const FPS_CMD_FILE: &str = "/data/adb/modules/QixiaThreads/config/fps.cmd";
pub(super) const FPS_OUT_DIR: &str = "/data/data/top.qixia.threads/files";
pub(super) const FPS_OUT_FILE: &str = "/data/data/top.qixia.threads/files/fps";
pub(super) const FPS_BPF_OBJ: &str = "/data/adb/modules/QixiaThreads/config/ebpf/queuebuffer_probe.bpf.o";
pub(super) const FOREGROUND_TASK_STATE_FILE: &str = "/data/adb/modules/QixiaThreads/config/foreground_task.state";
pub(super) const FOREGROUND_TASK_MAX_AGE_MS: u64 = 25_000;
pub(super) const FPS_WINDOW: Duration = Duration::from_millis(1000);
pub(super) const FPS_EBPF_STALE: Duration = Duration::from_millis(2500);
pub(super) const FPS_EBPF_TARGET_REFRESH: Duration = Duration::from_secs(5);
pub(super) const FPS_EBPF_PROBE_TARGET_REFRESH: Duration = Duration::from_secs(1);
pub(super) const FPS_EBPF_TARGET_FULL_SCAN: Duration = Duration::from_secs(60);
pub(super) const FPS_EBPF_RELOCK_CHECK: Duration = Duration::from_secs(1);
pub(super) const FPS_EBPF_FALLBACK_GRACE: Duration = Duration::from_secs(30);
pub(super) const FPS_EBPF_FALLBACK_FAILURES: u32 = 3;
pub(super) const FPS_ERROR_LOG_INTERVAL: Duration = Duration::from_secs(30);
pub(super) const FPS_EBPF_RETRY_LOG_INTERVAL: Duration = Duration::from_secs(60);
pub(super) const FPS_EBPF_PROBE_PENDING: i32 = 1;
pub(super) const FPS_EBPF_PROBE_EXHAUSTED: i32 = 3;
pub(super) const FPS_RELOCK_MISS: u32 = 3;
pub(super) const FPS_PROBE_FAIL: u32 = 5;
pub(super) const FPS_FRESH_NS: u64 = 5_000_000_000;
pub fn start_fps_thread() -> Option<thread::JoinHandle<()>> {
    match thread::Builder::new()
        .name("QiXiaRsFps".to_string())
        .spawn(|| {
            if let Err(err) = fps_loop() {
                log_error!("[FPS] 帧率监测线程已停止: {err}");
            }
        }) {
        Ok(handle) => Some(handle),
        Err(err) => {
            log_error!("[FPS] 帧率监测线程创建失败: {err}");
            None
        }
    }
}

pub(super) struct FpsMonitor {
    pub(super) pkg: String,
    // ctx 内部持有目标进程各线程的 libgui uprobe、TGID map 与 RingBuf/PerfEvent 后端。
    pub(super) ctx: *mut QixiaThreadsEbpfCtx,
    // 连续失败计数，防止偶发 poll 错误立刻重载 eBPF。
    pub(super) ebpf_failures: u32,
    // seen/stale 用于区分“从未收到帧”和“曾经有帧但后来停了”。
    pub(super) ebpf_seen_frames: bool,
    pub(super) ebpf_stale_zero_sent: bool,
    pub(super) ebpf_last_frame: Instant,
    pub(super) last_ebpf_fps: f64,
    // 低频刷新目标 TGID，避免每 80ms 扫 /proc。
    pub(super) ebpf_last_target_refresh: Instant,
    pub(super) ebpf_last_full_target_scan: Option<Instant>,
    pub(super) ebpf_last_restart: Instant,
    pub(super) ebpf_next_restart: Instant,
    pub(super) ebpf_restart_failures: u32,
    pub(super) ebpf_no_frame_retries: u32,
    pub(super) ebpf_retry_pid: i32,
    pub(super) ebpf_last_relock_check: Option<Instant>,
    pub(super) ebpf_last_target_error_log: Option<Instant>,
    pub(super) ebpf_last_retry_log: Option<Instant>,
    pub(super) ebpf_suppressed_retry_logs: u32,
    pub(super) ebpf_attempt_detailed: bool,
    pub(super) ebpf_retry_reported: bool,
    pub(super) ebpf_pending_recovery: bool,
    pub(super) ebpf_first_fps: bool,
    pub(super) target_pids: BTreeSet<i32>,
    pub(super) fallback: Option<SfFallback>,
    pub(super) fallback_state_reported: bool,
    // SurfaceFlinger 只允许在 eBPF 明确不可用时接管，不能因为应用空闲无帧就触发。
    pub(super) fallback_allowed: bool,
    pub(super) ebpf_no_pid_since: Option<Instant>,
    pub(super) socket: FpsSocket,
    pub(super) last_output: Option<Instant>,
    pub(super) target_pid: i32,
    pub(super) started_at: Instant,
    pub(super) last_frame_pid: i32,
    pub(super) backend_name: String,
    pub(super) confirmed_backend: Option<String>,
    pub(super) fallback_used: bool,
    pub(super) output_enabled: bool,
    pub(super) summary_enabled: bool,
    pub(super) summary_sample: Option<f64>,
}
