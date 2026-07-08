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
    Ebpf, Pod,
    maps::{
        HashMap as AyaHashMap, MapData, PerfEventArray, RingBuf,
        perf::{PerfEvent, PerfEventArrayBuffer},
    },
    programs::{UProbe, uprobe::UProbeScope},
    util::online_cpus,
};

// Rust daemon 通过 C ABI 调用这个 bridge。
// 这里负责加载 aya eBPF 对象、附加 libgui uprobe、读取 RingBuf/PerfEvent 帧事件，
// 并把当前 FPS、后端名称、命中的符号和错误信息暴露给 daemon 日志。
const FPS_WINDOW_NS: u64 = 1_000_000_000;
// 过滤明显异常的帧间隔：过小通常是重复/异常事件，过大不适合计入实时 FPS。
const MIN_FRAME_NS: u64 = 1_000_000;
const MAX_FRAME_NS: u64 = 200_000_000;
// 单个 Surface 超过这个时间没新帧就认为失活，避免旧 Surface 继续参与 FPS 计算。
const STREAM_STALE_NS: u64 = 2_000_000_000;
const MAX_STREAMS: usize = 32;

static LAST_START_ERROR: Mutex<Option<CString>> = Mutex::new(None);

// Android 不同版本和厂商 ROM 的 libgui queueBuffer 符号不完全一致，按常见符号顺序尝试。
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
    // 必须和 bpf/queuebuffer_probe*.bpf.c 中写入 events map 的结构体布局一致。
    timestamp_ns: u64,
    pid: u32,
    tid: u32,
    surface_ptr: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Default, Eq, Hash, PartialEq)]
struct FrameStatsKey {
    pid: u32,
    tid: u32,
    surface_ptr: u64,
}

unsafe impl Pod for FrameStatsKey {}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct FrameStatsValue {
    last_ts: u64,
    total_frames: u64,
}

unsafe impl Pod for FrameStatsValue {}

#[derive(Clone, Copy, Default)]
struct FrameStatsSnapshot {
    last_ts: u64,
    total_frames: u64,
}

enum EventBackend {
    // 优先使用 RingBuf；部分 Android/内核组合 mmap 会失败，此时自动切到 PerfEvent。
    RingBuf(RingBuf<MapData>),
    PerfEvent(Vec<PerfEventArrayBuffer<MapData>>),
}

struct FpsStream {
    // 保存最近约 1 秒的帧间隔，用滑动窗口计算实时 FPS。
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
        if self.last_ts != 0 && timestamp_ns <= self.last_ts {
            // PerfEvent 是每 CPU 一个 ring，用户态合并时可能读到迟到的旧样本。
            // 旧时间戳不能参与滑动窗口，也不能把 last_ts 倒回去，否则 FPS 会被拉低或跳动。
            self.last_seen_ns = self.last_seen_ns.max(timestamp_ns);
            return;
        }

        if self.last_ts != 0 {
            let delta = timestamp_ns.saturating_sub(self.last_ts);
            if (MIN_FRAME_NS..=MAX_FRAME_NS).contains(&delta) {
                self.frame_times.push_front(delta);
                self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_add(delta);

                // 窗口按时间长度裁剪，不按固定帧数裁剪，才能兼容 60/90/120/144/165Hz。
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

    fn on_frame_batch(&mut self, prev_ts: u64, timestamp_ns: u64, frames: u64) {
        if frames == 0 || timestamp_ns <= prev_ts || timestamp_ns <= self.last_ts {
            self.last_seen_ns = self.last_seen_ns.max(timestamp_ns);
            return;
        }

        let span = timestamp_ns - prev_ts;
        let delta = span / frames.max(1);
        if !(MIN_FRAME_NS..=MAX_FRAME_NS).contains(&delta) {
            self.last_ts = timestamp_ns;
            self.last_seen_ns = timestamp_ns;
            return;
        }

        for _ in 0..frames.min(300) {
            self.frame_times.push_front(delta);
            self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_add(delta);
        }

        while self.frame_time_sum_ns > FPS_WINDOW_NS && self.frame_times.len() > 1 {
            if let Some(old) = self.frame_times.pop_back() {
                self.frame_time_sum_ns = self.frame_time_sum_ns.saturating_sub(old);
            }
        }

        if self.frame_time_sum_ns > 0 {
            self.cur_fps =
                (self.frame_times.len() as f64) * 1_000_000_000.0 / (self.frame_time_sum_ns as f64);
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
    // bpf 必须和 backend/program 一起持有，ctx 生命周期结束前不能释放。
    bpf: Ebpf,
    backend: EventBackend,
    frame_stats: Option<AyaHashMap<MapData, FrameStatsKey, FrameStatsValue>>,
    // pid > 0 时只接受这个 PID 的帧；pid <= 0 时先按包名过滤，首个命中 PID 会被锁定。
    pid: i32,
    // key 是 Surface 指针；没有 Surface 指针时退化为 TID key。
    streams: HashMap<u64, FpsStream>,
    stat_snapshots: HashMap<FrameStatsKey, FrameStatsSnapshot>,
    stats_reported: bool,
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
    // eBPF map 里是 packed bytes，不能假设对齐，所以用 read_unaligned。
    if buf.len() < std::mem::size_of::<FrameEvent>() {
        return None;
    }
    Some(unsafe { ptr::read_unaligned(buf.as_ptr().cast::<FrameEvent>()) })
}

fn read_split_frame_event(head: &[u8], tail: &[u8]) -> Option<FrameEvent> {
    // PerfEvent 可能把一条 sample 分成 head/tail 两段，需要拼回完整 FrameEvent。
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
    // 备用包名过滤：即使将来放开多 PID 探测，也不能把其它应用的 libgui 帧算进去。
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
    // 优先按 Surface 分流，避免一个进程里多个 Surface 的帧事件互相污染。
    stream_key_from_parts(event.pid, event.tid, event.surface_ptr)
}

fn stream_key_from_parts(pid: u32, tid: u32, surface_ptr: u64) -> u64 {
    if surface_ptr != 0 {
        surface_ptr ^ ((pid as u64) << 32)
    } else {
        (1u64 << 63) | ((pid as u64) << 32) | u64::from(tid)
    }
}

fn refresh_current_fps(ctx: &mut AppOptEbpfCtx, now_ns: u64) {
    // 一个进程可能有多个 Surface，取最近仍活跃 Surface 的最高 FPS，避免短视频/弹幕叠加过高。
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
    // OneProcess attach 下 pid 已经固定；保留 pid<=0 分支只是为了兼容旧调用。
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

fn poll_frame_stats(ctx: &mut AppOptEbpfCtx) -> Result<i32, String> {
    let Some(stats) = ctx.frame_stats.as_ref() else {
        return Ok(0);
    };

    let mut updates = Vec::new();
    for item in stats.iter() {
        let (key, value) = item.map_err(|e| e.to_string())?;
        if value.total_frames == 0 || value.last_ts == 0 {
            continue;
        }
        if ctx.pid > 0 && key.pid != ctx.pid as u32 {
            continue;
        }
        if ctx.pid <= 0 {
            if let Some(pkg) = ctx.target_pkg.as_deref() {
                if !pid_matches_pkg(key.pid, pkg) {
                    continue;
                }
            }
        }
        updates.push((key, value));
    }
    updates.sort_by_key(|(_, value)| value.last_ts);

    let mut accepted = 0i32;
    let mut latest_ts = 0u64;
    let mut latest_pid = ctx.pid;

    for (key, value) in updates {
        let prev = ctx.stat_snapshots.get(&key).copied().unwrap_or_default();
        ctx.stat_snapshots.insert(
            key,
            FrameStatsSnapshot {
                last_ts: value.last_ts,
                total_frames: value.total_frames,
            },
        );

        let stream_key = stream_key_from_parts(key.pid, key.tid, key.surface_ptr);
        if prev.total_frames == 0 || prev.last_ts == 0 {
            ctx.streams
                .entry(stream_key)
                .or_insert_with(|| FpsStream::new(value.last_ts));
            accepted = accepted.saturating_add(1);
            latest_ts = latest_ts.max(value.last_ts);
            latest_pid = key.pid as i32;
            continue;
        }

        if value.total_frames <= prev.total_frames || value.last_ts <= prev.last_ts {
            continue;
        }

        let frames = value.total_frames - prev.total_frames;
        ctx.streams
            .entry(stream_key)
            .or_insert_with(|| FpsStream::new(prev.last_ts))
            .on_frame_batch(prev.last_ts, value.last_ts, frames);
        accepted = accepted.saturating_add(frames.min(i32::MAX as u64) as i32);
        latest_ts = latest_ts.max(value.last_ts);
        latest_pid = key.pid as i32;
    }

    if accepted > 0 {
        if ctx.pid <= 0 && latest_pid > 0 {
            ctx.pid = latest_pid;
        }
        if latest_ts > 0 {
            refresh_current_fps(ctx, latest_ts);
        }
    }

    Ok(accepted)
}

fn poll_inner(ctx: &mut AppOptEbpfCtx) -> Result<i32, String> {
    let mut events = Vec::new();
    let prefer_stats =
        matches!(ctx.backend, EventBackend::PerfEvent(_)) && ctx.frame_stats.is_some();

    match &mut ctx.backend {
        EventBackend::RingBuf(ring) => {
            // RingBuf 是首选后端，事件直接从共享 ring 中取出。
            while let Some(item) = ring.next() {
                if let Some(event) = unsafe { read_frame_event(&item) } {
                    events.push(event);
                }
            }
        }
        EventBackend::PerfEvent(perf_buffers) => {
            // PerfEvent 是 Android/内核不支持 RingBuf mmap 时的备用后端。
            // 每个 online CPU 都有一个 buffer，需要逐个 drain。
            let mut lost_samples = 0u64;
            for perf_buf in perf_buffers {
                perf_buf.for_each(|event| match event {
                    PerfEvent::Sample { head, tail } => {
                        if !prefer_stats {
                            if let Some(frame) = read_split_frame_event(head, tail) {
                                events.push(frame);
                            }
                        }
                    }
                    PerfEvent::Lost { count } => {
                        lost_samples = lost_samples.saturating_add(count);
                    }
                });
            }
            if lost_samples > 0 {
                eprintln!("[FPS] PerfEvent 丢弃样本: {lost_samples}");
            }
        }
    }

    if prefer_stats {
        if !ctx.stats_reported {
            println!("[FPS] PerfEvent 使用 eBPF frame_stats 计数 map 计算帧率");
            ctx.stats_reported = true;
        }
        return poll_frame_stats(ctx);
    }

    // PerfEvent 是 per-CPU 队列，按 CPU 读取会破坏全局时间顺序；先按时间戳合并，
    // 再进入 Surface/TID 分流的滑动窗口。RingBuf 原本就是有序的，排序不会改变语义。
    events.sort_by_key(|event| event.timestamp_ns);

    let mut accepted = 0;
    for event in events {
        if on_frame(ctx, event) {
            accepted += 1;
        }
    }

    Ok(accepted)
}

fn attach_symbols(bpf: &mut Ebpf, pid: i32) -> Result<CString, String> {
    if pid <= 0 {
        return Err("Android eBPF 需要锁定具体 PID, 已跳过全进程 uprobe".to_string());
    }

    let program: &mut UProbe = bpf
        .program_mut("on_queue_buffer")
        .ok_or_else(|| "missing BPF program: on_queue_buffer".to_string())?
        .try_into()
        .map_err(|e: aya::programs::ProgramError| e.to_string())?;

    program.load().map_err(|e| e.to_string())?;

    let mut last_error = String::new();
    let scope = UProbeScope::OneProcess(
        NonZeroU32::new(pid as u32).ok_or_else(|| "invalid target pid".to_string())?,
    );

    // 同一个 Android 版本/ROM 里可能同时存在多个 queueBuffer 变体。
    // attach 成功只说明符号存在，不代表目标 App 持续走这条热路径。
    // 所以这里挂上所有可用候选符号，再由用户态按 PID/Surface/TID 去重统计。
    let mut attached = Vec::new();
    for symbol in LIBGUI_FRAME_SYMBOLS {
        match program.attach(*symbol, Path::new("libgui.so"), scope) {
            Ok(_) => attached.push(*symbol),
            Err(err) => last_error = err.to_string(),
        }
    }

    if attached.is_empty() {
        Err(if last_error.is_empty() {
            "all libgui symbols failed".to_string()
        } else {
            last_error
        })
    } else {
        Ok(cstring_lossy(attached.join(",")))
    }
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
            .open(cpu, Some(8))
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
    // 每次启动只加载一种 BPF 对象：RingBuf 对象或 PerfEvent 备用对象。
    // RingBuf 和 PerfEvent map 类型不同，不能在同一个 bpf.o 里运行时互换。
    let mut bpf = Ebpf::load_file(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let frame_stats = match bpf.take_map("frame_stats") {
        Some(map) => Some(AyaHashMap::try_from(map).map_err(|e| e.to_string())?),
        None => None,
    };

    let backend = match kind {
        BackendKind::RingBuf => EventBackend::RingBuf(open_ring_buffer(&mut bpf)?),
        BackendKind::PerfEvent => EventBackend::PerfEvent(open_perf_buffers(&mut bpf)?),
    };
    let symbol = attach_symbols(&mut bpf, pid)?;

    Ok(Box::new(AppOptEbpfCtx {
        bpf,
        backend,
        frame_stats,
        pid,
        streams: HashMap::new(),
        stat_snapshots: HashMap::new(),
        stats_reported: false,
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
                // RingBuf 初始化失败通常发生在 mmap 或内核支持差异上，直接尝试 PerfEvent。
                // startup_note 保留 RingBuf 失败原因，daemon 日志会显示实际后端和回退原因。
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
