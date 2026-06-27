use std::{
    collections::VecDeque,
    convert::TryInto,
    ffi::{CStr, CString},
    fs,
    num::NonZeroU32,
    os::raw::{c_char, c_double, c_int},
    panic::{catch_unwind, AssertUnwindSafe},
    path::Path,
    ptr,
    sync::Mutex,
};

use aya::{maps::RingBuf, programs::{uprobe::UProbeScope, UProbe}, Ebpf};

const FPS_WINDOW_NS: u64 = 1_000_000_000;
const MIN_FRAME_NS: u64 = 1_000_000;
const MAX_FRAME_NS: u64 = 200_000_000;

static LAST_START_ERROR: Mutex<Option<CString>> = Mutex::new(None);

const LIBGUI_FRAME_SYMBOLS: &[&str] = &[
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi",
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferiPNS_24SurfaceQueueBufferOutputE",
    "_ZN7android7Surface16hook_queueBufferEP13ANativeWindowP19ANativeWindowBufferi",
    "_ZN7android7Surface27hook_queueBuffer_DEPRECATEDEP13ANativeWindowP19ANativeWindowBuffer",
    "_ZN7android7Surface19queueBufferInternalEP13ANativeWindowP19ANativeWindowBufferi",
];

#[repr(C)]
#[derive(Clone, Copy)]
struct FrameEvent {
    timestamp_ns: u64,
    pid: u32,
    tid: u32,
    surface_ptr: u64,
}

#[repr(C)]
pub struct AppOptEbpfCtx {
    bpf: Ebpf,
    pid: i32,
    frame_times: VecDeque<u64>,
    frame_time_sum_ns: u64,
    last_ts: u64,
    cur_fps: f64,
    symbol: CString,
    last_error: CString,
    target_pkg: Option<String>,
}

fn cstring_lossy(s: impl AsRef<str>) -> CString {
    let cleaned = s.as_ref().replace('\0', " ");
    CString::new(cleaned).unwrap_or_else(|_| CString::new("unknown").unwrap())
}

fn set_last_start_error(err: impl AsRef<str>) {
    if let Ok(mut last) = LAST_START_ERROR.lock() {
        *last = Some(cstring_lossy(err));
    }
}

fn ptr_as_mut<'a>(ctx: *mut AppOptEbpfCtx) -> Option<&'a mut AppOptEbpfCtx> {
    if ctx.is_null() {
        None
    } else {
        Some(unsafe { &mut *ctx })
    }
}

unsafe fn read_frame_event(buf: &[u8]) -> Option<FrameEvent> {
    if buf.len() < std::mem::size_of::<FrameEvent>() {
        return None;
    }
    Some(unsafe { ptr::read_unaligned(buf.as_ptr().cast::<FrameEvent>()) })
}

fn pid_matches_pkg(pid: u32, pkg: &str) -> bool {
    if pkg.is_empty() {
        return true;
    }

    let path = format!("/proc/{pid}/cmdline");
    let Ok(cmdline) = fs::read(path) else {
        return false;
    };
    let name = cmdline
        .split(|b| *b == 0)
        .next()
        .unwrap_or_default();
    let name = match std::str::from_utf8(name) {
        Ok(s) => s.rsplit('/').next().unwrap_or(s),
        Err(_) => return false,
    };

    name == pkg
        || name
            .strip_prefix(pkg)
            .is_some_and(|suffix| suffix.starts_with(':'))
}

fn on_frame(ctx: &mut AppOptEbpfCtx, event: FrameEvent) -> bool {
    if ctx.pid > 0 && event.pid != ctx.pid as u32 {
        return false;
    }

    if ctx.pid <= 0 {
        if let Some(pkg) = ctx.target_pkg.as_deref() {
            if !pid_matches_pkg(event.pid, pkg) {
                return false;
            }
        }
        ctx.pid = event.pid as i32;
    }

    if ctx.last_ts != 0 {
        let delta = event.timestamp_ns.saturating_sub(ctx.last_ts);
        if (MIN_FRAME_NS..=MAX_FRAME_NS).contains(&delta) {
            ctx.frame_times.push_front(delta);
            ctx.frame_time_sum_ns = ctx.frame_time_sum_ns.saturating_add(delta);

            while ctx.frame_time_sum_ns > FPS_WINDOW_NS && ctx.frame_times.len() > 1 {
                if let Some(old) = ctx.frame_times.pop_back() {
                    ctx.frame_time_sum_ns = ctx.frame_time_sum_ns.saturating_sub(old);
                }
            }

            if ctx.frame_time_sum_ns > 0 {
                ctx.cur_fps =
                    (ctx.frame_times.len() as f64) * 1_000_000_000.0 / (ctx.frame_time_sum_ns as f64);
            }
        }
    }

    ctx.last_ts = event.timestamp_ns;
    true
}

fn poll_inner(ctx: &mut AppOptEbpfCtx) -> Result<i32, String> {
    let mut events = Vec::new();
    {
        let mut ring = RingBuf::try_from(
            ctx.bpf
                .map_mut("events")
                .ok_or_else(|| "missing BPF map: events".to_string())?,
        )
        .map_err(|e| e.to_string())?;

        while let Some(item) = ring.next() {
            if let Some(event) = unsafe { read_frame_event(&item) } {
                events.push(event);
            }
        }
    }

    let mut accepted = 0;
    for event in events {
        if on_frame(ctx, event) {
            accepted += 1;
        }
    }

    Ok(accepted)
}

fn attach_first_symbol(bpf: &mut Ebpf, pid: i32) -> Result<CString, String> {
    let program: &mut UProbe = bpf
        .program_mut("on_queue_buffer")
        .ok_or_else(|| "missing BPF program: on_queue_buffer".to_string())?
        .try_into()
        .map_err(|e: aya::programs::ProgramError| e.to_string())?;

    program.load().map_err(|e| e.to_string())?;

    let mut last_error = String::new();
    let scope = if pid > 0 {
        UProbeScope::OneProcess(
            NonZeroU32::new(pid as u32).ok_or_else(|| "invalid target pid".to_string())?,
        )
    } else {
        UProbeScope::AllProcesses
    };

    for symbol in LIBGUI_FRAME_SYMBOLS {
        match program.attach(*symbol, Path::new("libgui.so"), scope) {
            Ok(_) => return Ok(cstring_lossy(*symbol)),
            Err(err) => last_error = err.to_string(),
        }
    }

    Err(if last_error.is_empty() {
        "all libgui symbols failed".to_string()
    } else {
        last_error
    })
}

fn start_impl(
    pid: c_int,
    bpf_obj_path: *const c_char,
    target_pkg: *const c_char,
) -> *mut AppOptEbpfCtx {
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

        let mut bpf = Ebpf::load_file(path).map_err(|e| e.to_string())?;
        let symbol = attach_first_symbol(&mut bpf, pid)?;

        let ctx = Box::new(AppOptEbpfCtx {
            bpf,
            pid,
            frame_times: VecDeque::with_capacity(144),
            frame_time_sum_ns: 0,
            last_ts: 0,
            cur_fps: 0.0,
            symbol,
            last_error: cstring_lossy(""),
            target_pkg,
        });

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
pub extern "C" fn appopt_ebpf_start(pid: c_int, bpf_obj_path: *const c_char) -> *mut AppOptEbpfCtx {
    start_impl(pid, bpf_obj_path, ptr::null())
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_start_for_package(
    pid: c_int,
    bpf_obj_path: *const c_char,
    target_pkg: *const c_char,
) -> *mut AppOptEbpfCtx {
    start_impl(pid, bpf_obj_path, target_pkg)
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_last_start_error() -> *const c_char {
    match LAST_START_ERROR.lock() {
        Ok(last) => last.as_ref().map_or(ptr::null(), |err| err.as_ptr()),
        Err(_) => ptr::null(),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_poll(ctx: *mut AppOptEbpfCtx) -> c_int {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(ctx) = ptr_as_mut(ctx) else {
            return -1;
        };

        match poll_inner(ctx) {
            Ok(consumed) => consumed,
            Err(err) => {
                ctx.last_error = cstring_lossy(err);
                -1
            }
        }
    }))
    .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_get(ctx: *const AppOptEbpfCtx) -> c_double {
    if ctx.is_null() {
        return 0.0;
    }
    let ctx = unsafe { &*ctx };
    ctx.cur_fps
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_pid(ctx: *const AppOptEbpfCtx) -> c_int {
    if ctx.is_null() {
        return -1;
    }
    let ctx = unsafe { &*ctx };
    ctx.pid
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_symbol(ctx: *const AppOptEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.symbol.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_last_error(ctx: *const AppOptEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.last_error.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_stop(ctx: *mut AppOptEbpfCtx) {
    if ctx.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        drop(Box::from_raw(ctx));
    }));
}
