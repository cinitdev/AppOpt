use std::{
    collections::{HashMap, VecDeque},
    convert::TryInto,
    ffi::{CStr, CString},
    fs,
    num::NonZeroU32,
    os::raw::{c_char, c_double, c_int},
    panic::{AssertUnwindSafe, catch_unwind},
    path::{Path, PathBuf},
    ptr,
    sync::Mutex,
};

use aya::{
    Ebpf,
    maps::{
        MapData, PerfEventArray, RingBuf,
        perf::{PerfEvent, PerfEventArrayBuffer},
    },
    programs::{UProbe, uprobe::UProbeScope},
    util::online_cpus,
};

const FPS_WINDOW_NS: u64 = 1_000_000_000;
const MIN_FRAME_NS: u64 = 1_000_000;
const MAX_FRAME_NS: u64 = 200_000_000;
const STREAM_STALE_NS: u64 = 2_000_000_000;
const MAX_STREAMS: usize = 32;

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

enum EventBackend {
    RingBuf(RingBuf<MapData>),
    PerfEvent(Vec<PerfEventArrayBuffer<MapData>>),
}

struct FpsStream {
    frame_times: VecDeque<u64>,
    frame_time_sum_ns: u64,
    last_ts: u64,
    last_seen_ns: u64,
    cur_fps: f64,
}

impl FpsStream {
    fn new(timestamp_ns: u64) -> Self {
        Self {
            frame_times: VecDeque::with_capacity(144),
            frame_time_sum_ns: 0,
            last_ts: 0,
            last_seen_ns: timestamp_ns,
            cur_fps: 0.0,
        }
    }

    fn on_frame(&mut self, timestamp_ns: u64) {
        if self.last_ts != 0 {
            let delta = timestamp_ns.saturating_sub(self.last_ts);
            if (MIN_FRAME_NS..=MAX_FRAME_NS).contains(&delta) {
                self.frame_times.push_front(delta);
                self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_add(delta);

                while self.frame_time_sum_ns > FPS_WINDOW_NS && self.frame_times.len() > 1 {
                    if let Some(old) = self.frame_times.pop_back() {
                        self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_sub(old);
                    }
                }

                if self.frame_time_sum_ns > 0 {
                    self.cur_fps = (self.frame_times.len() as f64) * 1_000_000_000.0
                        / (self.frame_time_sum_ns as f64);
                }
            }
        }

        self.last_ts = timestamp_ns;
        self.last_seen_ns = timestamp_ns;
    }
}

#[derive(Clone, Copy)]
enum BackendKind {
    RingBuf,
    PerfEvent,
}

impl BackendKind {
    fn label(self) -> &'static str {
        match self {
            Self::RingBuf => "RingBuf",
            Self::PerfEvent => "PerfEvent",
        }
    }
}

#[repr(C)]
pub struct AppOptEbpfCtx {
    bpf: Ebpf,
    backend: EventBackend,
    pid: i32,
    streams: HashMap<u64, FpsStream>,
    cur_fps: f64,
    symbol: CString,
    backend_label: CString,
    startup_note: CString,
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

fn read_split_frame_event(head: &[u8], tail: &[u8]) -> Option<FrameEvent> {
    let size = std::mem::size_of::<FrameEvent>();
    if head.len().saturating_add(tail.len()) < size {
        return None;
    }
    if head.len() >= size {
        return unsafe { read_frame_event(head) };
    }

    let mut buf = [0u8; std::mem::size_of::<FrameEvent>()];
    let head_len = head.len();
    buf[..head_len].copy_from_slice(head);
    buf[head_len..].copy_from_slice(&tail[..(size - head_len)]);
    unsafe { read_frame_event(&buf) }
}

fn pid_matches_pkg(pid: u32, pkg: &str) -> bool {
    if pkg.is_empty() {
        return true;
    }

    let path = format!("/proc/{pid}/cmdline");
    let Ok(cmdline) = fs::read(path) else {
        return false;
    };
    let name = cmdline.split(|b| *b == 0).next().unwrap_or_default();
    let name = match std::str::from_utf8(name) {
        Ok(s) => s.rsplit('/').next().unwrap_or(s),
        Err(_) => return false,
    };

    name == pkg
        || name
            .strip_prefix(pkg)
            .is_some_and(|suffix| suffix.starts_with(':'))
}

fn stream_key(event: FrameEvent) -> u64 {
    if event.surface_ptr != 0 {
        event.surface_ptr
    } else {
        (1u64 << 63) | u64::from(event.tid)
    }
}

fn refresh_current_fps(ctx: &mut AppOptEbpfCtx, now_ns: u64) {
    ctx.streams
        .retain(|_, stream| now_ns.saturating_sub(stream.last_seen_ns) <= STREAM_STALE_NS);

    if ctx.streams.len() > MAX_STREAMS {
        let mut streams = ctx
            .streams
            .iter()
            .map(|(key, stream)| (*key, stream.last_seen_ns))
            .collect::<Vec<_>>();
        streams.sort_by_key(|(_, last_seen_ns)| *last_seen_ns);
        for (key, _) in streams.into_iter().take(ctx.streams.len() - MAX_STREAMS) {
            ctx.streams.remove(&key);
        }
    }

    ctx.cur_fps = ctx
        .streams
        .values()
        .filter(|stream| now_ns.saturating_sub(stream.last_seen_ns) <= STREAM_STALE_NS)
        .map(|stream| stream.cur_fps)
        .fold(0.0, f64::max);
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

    let key = stream_key(event);
    ctx.streams
        .entry(key)
        .or_insert_with(|| FpsStream::new(event.timestamp_ns))
        .on_frame(event.timestamp_ns);
    refresh_current_fps(ctx, event.timestamp_ns);
    true
}

fn poll_inner(ctx: &mut AppOptEbpfCtx) -> Result<i32, String> {
    let mut events = Vec::new();

    match &mut ctx.backend {
        EventBackend::RingBuf(ring) => {
            while let Some(item) = ring.next() {
                if let Some(event) = unsafe { read_frame_event(&item) } {
                    events.push(event);
                }
            }
        }
        EventBackend::PerfEvent(perf_buffers) => {
            for perf_buf in perf_buffers {
                perf_buf.for_each(|event| {
                    if let PerfEvent::Sample { head, tail } = event {
                        if let Some(frame) = read_split_frame_event(head, tail) {
                            events.push(frame);
                        }
                    }
                });
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

fn open_ring_buffer(bpf: &mut Ebpf) -> Result<RingBuf<MapData>, String> {
    RingBuf::try_from(
        bpf.take_map("events")
            .ok_or_else(|| "missing BPF map: events".to_string())?,
    )
    .map_err(|e| e.to_string())
}

fn open_perf_buffers(bpf: &mut Ebpf) -> Result<Vec<PerfEventArrayBuffer<MapData>>, String> {
    let mut perf_array = PerfEventArray::try_from(
        bpf.take_map("events")
            .ok_or_else(|| "missing BPF map: events".to_string())?,
    )
    .map_err(|e| e.to_string())?;

    let cpus = online_cpus().map_err(|(_, err)| err.to_string())?;
    let mut buffers = Vec::with_capacity(cpus.len());
    for cpu in cpus {
        let buffer = perf_array
            .open(cpu, Some(2))
            .map_err(|e| format!("open perf buffer cpu {cpu}: {e}"))?;
        buffers.push(buffer);
    }

    if buffers.is_empty() {
        return Err("no online CPUs for perf event array".to_string());
    }
    Ok(buffers)
}

fn perf_fallback_path(path: &Path) -> PathBuf {
    path.with_file_name("queuebuffer_probe_perf.bpf.o")
}

fn start_backend(
    path: &Path,
    kind: BackendKind,
    pid: c_int,
    target_pkg: Option<String>,
) -> Result<Box<AppOptEbpfCtx>, String> {
    let mut bpf = Ebpf::load_file(path).map_err(|e| format!("{}: {e}", path.display()))?;

    let backend = match kind {
        BackendKind::RingBuf => EventBackend::RingBuf(open_ring_buffer(&mut bpf)?),
        BackendKind::PerfEvent => EventBackend::PerfEvent(open_perf_buffers(&mut bpf)?),
    };
    let symbol = attach_first_symbol(&mut bpf, pid)?;

    Ok(Box::new(AppOptEbpfCtx {
        bpf,
        backend,
        pid,
        streams: HashMap::new(),
        cur_fps: 0.0,
        symbol,
        backend_label: cstring_lossy(kind.label()),
        startup_note: cstring_lossy(""),
        last_error: cstring_lossy(""),
        target_pkg,
    }))
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

        let ring_path = Path::new(path);
        let ctx = match start_backend(ring_path, BackendKind::RingBuf, pid, target_pkg.clone()) {
            Ok(ctx) => ctx,
            Err(ring_err) => {
                let perf_path = perf_fallback_path(ring_path);
                match start_backend(&perf_path, BackendKind::PerfEvent, pid, target_pkg) {
                    Ok(mut ctx) => {
                        ctx.startup_note = cstring_lossy(format!("RingBuf 不可用: {ring_err}"));
                        ctx
                    }
                    Err(perf_err) => {
                        return Err(format!(
                            "RingBuf failed: {ring_err}; PerfEvent failed: {perf_err}"
                        ));
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
pub extern "C" fn appopt_ebpf_backend(ctx: *const AppOptEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.backend_label.as_ptr()
}

#[unsafe(no_mangle)]
pub extern "C" fn appopt_ebpf_startup_note(ctx: *const AppOptEbpfCtx) -> *const c_char {
    if ctx.is_null() {
        return ptr::null();
    }
    let ctx = unsafe { &*ctx };
    ctx.startup_note.as_ptr()
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
