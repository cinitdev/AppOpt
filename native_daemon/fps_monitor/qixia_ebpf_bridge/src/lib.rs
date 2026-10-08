//! eBPF 桥接层：为加载、挂载和采集模块提供稳定的 C ABI。
#[macro_use]
mod logging;
mod attach;
mod clock;
mod constants;
mod context;
mod diagnostics;
mod events;
mod ffi;
mod fps_stream;
mod frame_metrics;
mod loader;
mod probes;
mod procfs;
mod stats;
mod streams;
mod symbols;
mod targets;
#[cfg(test)]
mod tests;

pub use context::QixiaThreadsEbpfCtx;
pub use logging::set_log_sink;
pub use ffi::{
    qixia_ebpf_take_frame_max,
    qixia_ebpf_backend, qixia_ebpf_backend_note, qixia_ebpf_get, qixia_ebpf_last_error,
    qixia_ebpf_last_start_error, qixia_ebpf_metrics, qixia_ebpf_pid, qixia_ebpf_poll,
    qixia_ebpf_probe_state, qixia_ebpf_retry_symbols, qixia_ebpf_set_detailed_logging,
    qixia_ebpf_set_target_pids, qixia_ebpf_start, qixia_ebpf_start_for_package,
    qixia_ebpf_startup_note, qixia_ebpf_stop, qixia_ebpf_symbol, qixia_ebpf_symbol_display,
};
pub use frame_metrics::QixiaThreadsFrameMetrics;
