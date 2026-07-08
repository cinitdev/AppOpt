#[cfg(any(target_os = "android", target_os = "linux"))]
mod imp {
    // Android/Linux FPS 实现聚合入口。
    //
    // 这里保持一个 imp 模块包住全部实现，是为了让非 Android 主机也能 cargo check：
    // Windows/其他平台会走下面的空 start_fps_thread，不编译 binder/eBPF 代码。
    include!("fps_core/preamble.rs");
    include!("fps_core/monitor.rs");
    include!("fps_core/command.rs");
    include!("fps_core/fallback.rs");
    include!("fps_core/binder.rs");
    include!("fps_core/socket.rs");
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
mod imp {
    pub fn start_fps_thread() {}
}

pub use imp::start_fps_thread;
