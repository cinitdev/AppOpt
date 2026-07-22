    // FPS 模块的公共导入和常量。
    //
    // 这个模块只在 Android/Linux 目标启用；Windows 主机 cargo check 会走 fps.rs 里的空实现。
    // 真正的 Android 交叉编译由 build_module.sh no 验证。
    //
    // FPS 主路径是 eBPF queueBuffer，fallback 是 SurfaceFlinger latency/timestats。
    use std::collections::BTreeSet;
    use std::ffi::{CStr, CString};
    use std::fs;
    use std::io;
    use std::mem;
    use std::path::PathBuf;
    use std::ptr;
    use std::slice;
    use std::thread;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

    use appopt_ebpf_bridge::{
        appopt_ebpf_backend, appopt_ebpf_get, appopt_ebpf_last_error, appopt_ebpf_last_start_error,
        appopt_ebpf_metrics, appopt_ebpf_pid, appopt_ebpf_poll, appopt_ebpf_start_for_package,
        appopt_ebpf_startup_note, appopt_ebpf_stop, appopt_ebpf_symbol, appopt_jank_create,
        appopt_jank_last_event, appopt_jank_recover, appopt_jank_stop, appopt_jank_update, AppOptEbpfCtx,
        AppOptFrameMetrics, AppOptJankCtx,
    };
    use crate::app_top_state_check;

    // FPS 监测流程：
    // 1. App 写 fps.cmd 请求开始监测某个包名。
    // 2. Rust daemon 优先用 appopt_ebpf_bridge 加载 queuebuffer_probe.bpf.o，
    //    直接在 libgui queueBuffer uprobe 上统计目标进程的帧事件。
    // 3. 如果 eBPF 不可用或连续轮询失败，降级到 SurfaceFlinger latency/timestats。
    // 4. 输出优先走 App 建立的 socket，socket 不可用时写 files/fps 兼容旧路径。
    const FPS_CMD_FILE: &str = "/data/adb/modules/AppOpt/config/fps.cmd";
    const FPS_OUT_DIR: &str = "/data/data/top.suto.appopt/files";
    const FPS_OUT_FILE: &str = "/data/data/top.suto.appopt/files/fps";
    const FPS_BPF_OBJ: &str = "/data/adb/modules/AppOpt/config/ebpf/queuebuffer_probe.bpf.o";
    const FOREGROUND_TASK_STATE_FILE: &str = "/data/adb/modules/AppOpt/config/foreground_task.state";
    const JANK_BOOST_FILE: &str = "/data/adb/modules/AppOpt/config/jank_boost.conf";
    const FOREGROUND_TASK_MAX_AGE_MS: u64 = 10_000;
    const FPS_WINDOW: Duration = Duration::from_millis(1000);
    const FPS_EBPF_STALE: Duration = Duration::from_millis(2500);
    const FPS_EBPF_PID_CHECK: Duration = Duration::from_millis(1000);
    const FPS_EBPF_RESTART_COOLDOWN: Duration = Duration::from_millis(3000);
    const FPS_EBPF_STALE_FALLBACK_CHECKS: u32 = 3;
    const FPS_RELOCK_MISS: u32 = 3;
    const FPS_PROBE_FAIL: u32 = 4;
    const FPS_FRESH_NS: u64 = 5_000_000_000;
    static FPS_SHUTDOWN: AtomicBool = AtomicBool::new(false);

    extern "C" fn fps_shutdown_handler(_signal: libc::c_int) {
        FPS_SHUTDOWN.store(true, Ordering::Relaxed);
    }

    pub fn start_fps_thread() -> bool {
        let recovered = appopt_jank_recover();
        if recovered > 0 {
            println!("[boost] 已恢复上次异常退出遗留的 {recovered} 项临时参数");
        }
        unsafe {
            libc::signal(libc::SIGTERM, fps_shutdown_handler as *const () as libc::sighandler_t);
            libc::signal(libc::SIGINT, fps_shutdown_handler as *const () as libc::sighandler_t);
            libc::signal(libc::SIGHUP, fps_shutdown_handler as *const () as libc::sighandler_t);
        }
        match thread::Builder::new()
            .name("AppOptRsFps".to_string())
            .spawn(|| {
            if let Err(err) = fps_loop() {
                eprintln!("[FPS] 帧率监测线程已停止: {err}");
            }
            if FPS_SHUTDOWN.load(Ordering::Relaxed) {
                std::process::exit(0);
            }
            }) {
            Ok(_) => true,
            Err(err) => {
                eprintln!("[FPS] 帧率监测线程创建失败: {err}");
                false
            }
        }
    }

    struct FpsMonitor {
        pkg: String,
        // 主路径直接调用 Rust/aya eBPF bridge；ctx 为空时才进入 SurfaceFlinger fallback。
        // 这个 ctx 内部持有 aya::Ebpf、RingBuf/PerfEvent 后端和已锁定的 libgui 符号。
        ctx: *mut AppOptEbpfCtx,
        // 连续失败计数，防止偶发 poll 错误直接切 fallback。
        ebpf_failures: u32,
        // seen/stale 用于区分“从未收到帧”和“曾经有帧但后来停了”。
        ebpf_seen_frames: bool,
        ebpf_stale_zero_sent: bool,
        ebpf_last_frame: Instant,
        // 停帧后按冷却时间检查目标 PID，避免每 80ms 扫 /proc。
        ebpf_last_pid_check: Option<Instant>,
        ebpf_last_restart: Instant,
        ebpf_pid_reported: bool,
        ebpf_first_fps: bool,
        ebpf_stale_checks: u32,
        // 固定 PID 连续无帧时，尝试一次同包其它 PID。
        // Android 上 aya 的全进程 uprobe 会走桌面 Linux 的 ld.so.cache 路径，不可靠。
        ebpf_alternate_pid_attempted: bool,
        fallback: Option<SfFallback>,
        socket: FpsSocket,
        last_output: Option<Instant>,
        target_pid: i32,
        adaptive_enabled: bool,
        jank: *mut AppOptJankCtx,
        output_enabled: bool,
    }
