use crate::QixiaThreadsFrameMetrics;
use crate::clock::monotonic_ns;
use crate::context::{QixiaThreadsEbpfCtx, BackendKind, EventBackend};
use crate::diagnostics::{backend_selection_report, cstring_lossy};
use crate::events::poll_inner;
use crate::loader::{perf_fallback_path, ringbuf_kernel_note, start_backend, stats_fallback_path};
use crate::probes::{advance_symbol_probes, confirm_observed_symbols, retry_confirmed_symbols};
use crate::symbols::readable_symbol_cstr_from_raw;
use crate::targets::sync_target_pids;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_double, c_int};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::ptr;
use std::sync::Mutex;

static LAST_START_ERROR: Mutex<Option<CString>> = Mutex::new(None);
fn set_last_start_error(err: impl AsRef<str>) {
    if let Ok(mut last) = LAST_START_ERROR.lock() {
        *last = Some(cstring_lossy(err));
    }
}

fn ptr_as_mut<'a>(ctx: *mut QixiaThreadsEbpfCtx) -> Option<&'a mut QixiaThreadsEbpfCtx> {
    if ctx.is_null() {
        None
    } else {
        Some(unsafe { &mut *ctx })
    }
}

fn start_impl(
    pid: c_int,
    bpf_obj_path: *const c_char,
    target_pkg: *const c_char,
) -> *mut QixiaThreadsEbpfCtx {
    match catch_unwind(AssertUnwindSafe(|| {
        if bpf_obj_path.is_null() {
            return Err("null bpf object path".to_string());
        }

        let path = unsafe { CStr::from_ptr(bpf_obj_path) }
            .to_str()
            .map_err(|e| e.to_string())?;
        let target_pkg = if target_pkg.is_null() {
            None
        } else {
            let pkg = unsafe { CStr::from_ptr(target_pkg) }
                .to_str()
                .map_err(|e| e.to_string())?
                .to_string();
            if pkg.is_empty() { None } else { Some(pkg) }
        };

        let ring_path = Path::new(path);
        let ring_skip_note = ringbuf_kernel_note();
        let ring_attempt = match ring_skip_note.clone() {
            Some(note) => Err(note),
            None => start_backend(ring_path, BackendKind::RingBuf, pid, target_pkg.clone()),
        };
        let ctx = match ring_attempt {
            Ok(mut ctx) => {
                ctx.backend_selection_note = cstring_lossy(backend_selection_report(
                    "成功",
                    None,
                    "未尝试",
                    None,
                    "未尝试",
                ));
                ctx
            }
            Err(ring_err) => {
                // 4.19 等旧内核不支持 RingBuf，先尝试只依赖 HashMap 的 StatsMap。
                let stats_path = stats_fallback_path(ring_path);
                match start_backend(&stats_path, BackendKind::StatsMap, pid, target_pkg.clone()) {
                    Ok(mut ctx) => {
                        let ring_state = if ring_skip_note.is_some() {
                            "跳过"
                        } else {
                            "失败"
                        };
                        ctx.backend_selection_note = cstring_lossy(backend_selection_report(
                            ring_state,
                            Some(&ring_err),
                            "成功",
                            None,
                            "未尝试",
                        ));
                        ctx
                    }
                    Err(stats_err) => {
                        let perf_path = perf_fallback_path(ring_path);
                        match start_backend(&perf_path, BackendKind::PerfEvent, pid, target_pkg) {
                            Ok(mut ctx) => {
                                let ring_state = if ring_skip_note.is_some() {
                                    "跳过"
                                } else {
                                    "失败"
                                };
                                ctx.backend_selection_note =
                                    cstring_lossy(backend_selection_report(
                                        ring_state,
                                        Some(&ring_err),
                                        "失败",
                                        Some(&stats_err),
                                        "成功",
                                    ));
                                ctx
                            }
                            Err(perf_err) => {
                                return Err(format!(
                                    "RingBuf failed: {ring_err}; StatsMap failed: {stats_err}; PerfEvent failed: {perf_err}"
                                ));
                            }
                        }
                    }
                }
            }
        };

        set_last_start_error("");
        Ok::<_, String>(ctx)
    })) {
        Ok(Ok(ctx)) => Box::into_raw(ctx),
        Ok(Err(err)) => {
            set_last_start_error(err);
            ptr::null_mut()
        }
        Err(_) => {
            set_last_start_error("panic while starting eBPF bridge");
            ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_start(pid: c_int, bpf_obj_path: *const c_char) -> *mut QixiaThreadsEbpfCtx {
    start_impl(pid, bpf_obj_path, ptr::null())
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_start_for_package(
    pid: c_int,
    bpf_obj_path: *const c_char,
    target_pkg: *const c_char,
) -> *mut QixiaThreadsEbpfCtx {
    start_impl(pid, bpf_obj_path, target_pkg)
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_last_start_error() -> *const c_char {
    match LAST_START_ERROR.lock() {
        Ok(last) => last.as_ref().map_or(ptr::null(), |err| err.as_ptr()),
        Err(_) => ptr::null(),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_set_target_pids(
    ctx: *mut QixiaThreadsEbpfCtx,
    pids: *const c_int,
    len: usize,
) -> c_int {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(ctx) = ptr_as_mut(ctx) else {
            return -1;
        };
        if len > 128 || (len > 0 && pids.is_null()) {
            ctx.last_error = cstring_lossy("目标 PID 参数无效");
            return -1;
        }
        let pids = if len == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(pids, len) }
        };
        match sync_target_pids(ctx, pids) {
            Ok(count) => count.min(c_int::MAX as usize) as c_int,
            Err(err) => {
                ctx.last_error = cstring_lossy(err);
                -1
            }
        }
    }))
    .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_poll(ctx: *mut QixiaThreadsEbpfCtx) -> c_int {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(ctx) = ptr_as_mut(ctx) else {
            return -1;
        };

        match poll_inner(ctx) {
            Ok(consumed) => {
                confirm_observed_symbols(ctx);
                match advance_symbol_probes(ctx, monotonic_ns()) {
                    Ok(()) => consumed,
                    Err(err) => {
                        ctx.last_error = cstring_lossy(err);
                        -1
                    }
                }
            }
            Err(err) => {
                ctx.last_error = cstring_lossy(err);
                -1
            }
        }
    }))
    .unwrap_or(-1)
}

// 0=无目标，1=仍在验证候选，2=已有真实帧确认，3=所有目标候选均已试完。
#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_probe_state(ctx: *const QixiaThreadsEbpfCtx) -> c_int {
    if ctx.is_null() {
        return 0;
    }
    let ctx = unsafe { &*ctx };
    if ctx.pid_symbol_probes.is_empty() {
        return 0;
    }
    if ctx.pid_symbol_probes.values().any(|probe| probe.confirmed) {
        return 2;
    }
    if ctx.pid_symbol_probes.values().any(|probe| !probe.exhausted) {
        return 1;
    }
    3
}

// 已确认的符号后续停止出帧时，由守护进程请求继续尝试剩余候选。
// 返回值：>0 已切换的目标数，0 没有剩余候选，-1 切换失败。
#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_retry_symbols(ctx: *mut QixiaThreadsEbpfCtx) -> c_int {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(ctx) = ptr_as_mut(ctx) else {
            return -1;
        };
        match retry_confirmed_symbols(ctx) {
            Ok(count) => count.min(c_int::MAX as usize) as c_int,
            Err(err) => {
                ctx.last_error = cstring_lossy(err);
                -1
            }
        }
    }))
    .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_get(ctx: *const QixiaThreadsEbpfCtx) -> c_double {
    if ctx.is_null() {
        return 0.0;
    }
    let ctx = unsafe { &*ctx };
    ctx.cur_fps
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_take_frame_max(ctx: *mut QixiaThreadsEbpfCtx) -> u64 {
    if ctx.is_null() { return 0; }
    let ctx = unsafe { &mut *ctx };
    ctx.selected_stream.and_then(|key| ctx.streams.get_mut(&key))
        .map_or(0, |stream| stream.take_report_max(crate::clock::monotonic_ns()))
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_metrics(
    ctx: *const QixiaThreadsEbpfCtx,
    out: *mut QixiaThreadsFrameMetrics,
) -> c_int {
    if ctx.is_null() || out.is_null() {
        return 0;
    }
    let ctx = unsafe { &*ctx };
    let Some(stream) = ctx.selected_stream.and_then(|key| ctx.streams.get(&key)) else {
        return 0;
    };
    if stream.frame_times.is_empty() {
        return 0;
    }
    let mut intervals = stream.frame_times.iter().copied().collect::<Vec<_>>();
    intervals.sort_unstable();
    let percentile = |percent: usize| -> u64 {
        let index = ((intervals.len() - 1) * percent + 99) / 100;
        intervals[index.min(intervals.len() - 1)]
    };
    unsafe {
        *out = QixiaThreadsFrameMetrics {
            source_pid: ctx.selected_stream.map_or(0, |key| key.pid as i32),
            source_tid: ctx.selected_stream.map_or(0, |key| key.tid as i32),
            source_surface: ctx.selected_stream.map_or(0, |key| key.surface_ptr),
            last_frame_ns: stream.last_seen_ns,
            fps: stream.cur_fps,
            median_interval_ns: percentile(50),
            p95_interval_ns: percentile(95),
            max_interval_ns: intervals.last().copied().unwrap_or(0),
            frame_count: intervals.len().min(u32::MAX as usize) as u32,
            flags: if (matches!(&ctx.backend, EventBackend::StatsMap)
                || (matches!(&ctx.backend, EventBackend::PerfEvent(_)) && ctx.use_frame_stats))
                && ctx.frame_stats.is_some()
            {
                1
            } else {
                0
            },
        };
    }
    1
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_pid(ctx: *const QixiaThreadsEbpfCtx) -> c_int {
    if ctx.is_null() {
        return -1;
    }
    let ctx = unsafe { &*ctx };
    ctx.pid
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_symbol(ctx: *const QixiaThreadsEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    if let Some(symbol) = ctx
        .selected_stream
        .and_then(|key| ctx.pid_symbols.get(&key.pid))
    {
        return symbol.as_ptr();
    }
    ctx.symbol.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_symbol_display(ctx: *const QixiaThreadsEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    let symbol = ctx
        .selected_stream
        .and_then(|key| ctx.pid_symbols.get(&key.pid))
        .unwrap_or(&ctx.symbol);
    readable_symbol_cstr_from_raw(symbol.to_str().unwrap_or_default())
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_backend(ctx: *const QixiaThreadsEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.backend_label.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_backend_note(ctx: *const QixiaThreadsEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.backend_selection_note.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_set_detailed_logging(
    ctx: *mut QixiaThreadsEbpfCtx,
    enabled: c_int,
) -> c_int {
    let Some(ctx) = ptr_as_mut(ctx) else {
        return -1;
    };
    ctx.detailed_logging = enabled != 0;
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_startup_note(ctx: *const QixiaThreadsEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.startup_note.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_last_error(ctx: *const QixiaThreadsEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.last_error.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn qixia_ebpf_stop(ctx: *mut QixiaThreadsEbpfCtx) {
    if ctx.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        drop(Box::from_raw(ctx));
    }));
}
