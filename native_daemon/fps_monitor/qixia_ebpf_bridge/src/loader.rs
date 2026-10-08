use crate::attach::{attach_process_candidate, load_uprobe_program};
use crate::clock::monotonic_ns;
use crate::constants::{LIBGUI_FRAME_SYMBOLS, RINGBUF_MIN_KERNEL};
use crate::context::{QixiaThreadsEbpfCtx, BackendKind, EventBackend, PidSymbolProbe};
use crate::diagnostics::{cstring_lossy, error_chain};
use crate::procfs::{process_starttime, resolve_libgui_path, task_starttime_snapshot};
use crate::symbols::{candidate_bit, candidate_range_mask, resolve_candidate_offsets};
use aya::Ebpf;
use aya::maps::Array as AyaArray;
use aya::maps::HashMap as AyaHashMap;
use aya::maps::perf::PerfEventArrayBuffer;
use aya::maps::{MapData, PerfEventArray, RingBuf};
use aya::util::online_cpus;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::os::raw::c_int;
use std::path::{Path, PathBuf};

pub(crate) fn parse_kernel_release_version(release: &str) -> Option<(u32, u32)> {
    let mut parts = release.trim().split('.');
    let major = parts.next()?.parse::<u32>().ok()?;
    let minor = parts
        .next()?
        .split(|ch: char| !ch.is_ascii_digit())
        .next()?
        .parse::<u32>()
        .ok()?;
    Some((major, minor))
}

pub(crate) fn ringbuf_kernel_note() -> Option<String> {
    let release = fs::read_to_string("/proc/sys/kernel/osrelease")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());
    let version = parse_kernel_release_version(release.as_deref()?)?;
    if version < RINGBUF_MIN_KERNEL {
        return Some(format!(
            "内核 {} 不支持 RingBuf（需要 >= {}.{}），已跳过 RingBuf 探测",
            release.as_deref().unwrap_or("unknown"),
            RINGBUF_MIN_KERNEL.0,
            RINGBUF_MIN_KERNEL.1
        ));
    }
    None
}

pub(crate) fn open_ring_buffer(bpf: &mut Ebpf) -> Result<RingBuf<MapData>, String> {
    RingBuf::try_from(
        bpf.take_map("events")
            .ok_or_else(|| "missing BPF map: events".to_string())?,
    )
    .map_err(|error| error_chain(&error))
}

pub(crate) fn open_perf_buffers(
    bpf: &mut Ebpf,
) -> Result<Vec<PerfEventArrayBuffer<MapData>>, String> {
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

pub(crate) fn perf_fallback_path(path: &Path) -> PathBuf {
    path.with_file_name("queuebuffer_probe_perf.bpf.o")
}

pub(crate) fn stats_fallback_path(path: &Path) -> PathBuf {
    path.with_file_name("queuebuffer_probe_stats.bpf.o")
}

pub(crate) fn start_backend(
    path: &Path,
    kind: BackendKind,
    pid: c_int,
    target_pkg: Option<String>,
) -> Result<Box<QixiaThreadsEbpfCtx>, String> {
    // 每次启动只加载一种 BPF 对象。三种对象的事件映射类型不同，不能在同一个
    // bpf.o 内运行时互换；StatsMap 对象完全不创建事件传输映射。
    let mut bpf = Ebpf::load_file(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let backend = match kind {
        BackendKind::RingBuf => EventBackend::RingBuf(open_ring_buffer(&mut bpf)?),
        BackendKind::StatsMap => EventBackend::StatsMap,
        BackendKind::PerfEvent => EventBackend::PerfEvent(open_perf_buffers(&mut bpf)?),
    };
    let target_pid = u32::try_from(pid).map_err(|_| format!("invalid target pid: {pid}"))?;
    if target_pid == 0 {
        return Err(format!("invalid target pid: {pid}"));
    }
    let libgui_path = resolve_libgui_path(pid)?;
    let libgui_offsets = resolve_candidate_offsets(&libgui_path)?;
    // 程序装载前不能取走或释放它引用的映射。Aya 已把映射的文件描述符重定位进指令，若此时
    // 提前关闭 perf_stats_only 等文件描述符，旧内核验证器会报 not pointing to valid bpf_map。
    load_uprobe_program(&mut bpf)?;
    let frame_stats = match bpf.take_map("frame_stats") {
        Some(map) => Some(AyaHashMap::try_from(map).map_err(|e| e.to_string())?),
        None => None,
    };
    // 已安装的旧版探针没有这份可选报告映射，也能继续采集 FPS。
    let frame_report = bpf.take_map("frame_report").and_then(|map| AyaHashMap::try_from(map).ok());
    let frame_report_prev = bpf.take_map("frame_report_prev").and_then(|map| AyaHashMap::try_from(map).ok());
    if matches!(kind, BackendKind::StatsMap) && frame_stats.is_none() {
        return Err("missing BPF map: frame_stats".to_string());
    }
    let frame_stats_drops = bpf
        .take_map("frame_stats_drops")
        .map(|map| AyaArray::<_, u32>::try_from(map).map_err(|e| e.to_string()))
        .transpose()?;
    let ringbuf_drops = if matches!(kind, BackendKind::RingBuf) {
        Some(
            AyaArray::<_, u32>::try_from(
                bpf.take_map("ringbuf_drops")
                    .ok_or_else(|| "missing BPF map: ringbuf_drops".to_string())?,
            )
            .map_err(|e| e.to_string())?,
        )
    } else {
        None
    };
    let mut use_frame_stats = frame_stats.is_some();
    let mut perf_mode_note = None;
    let mut perf_stats_only = None;
    if matches!(kind, BackendKind::PerfEvent) && frame_stats.is_some() {
        let stats_mode = match bpf.take_map("perf_stats_only") {
            Some(map) => match AyaArray::<_, u32>::try_from(map) {
                Ok(mut mode) => match mode.set(0, perf_stats_mode_value(true), 0) {
                    Ok(()) => {
                        perf_stats_only = Some(mode);
                        Ok(())
                    }
                    Err(error) => Err(error.to_string()),
                },
                Err(error) => Err(error.to_string()),
            },
            None => Err("missing BPF map: perf_stats_only".to_string()),
        };
        if let Err(error) = stats_mode {
            // 程序已经成功装载；配置映射异常时继续消费 PerfEvent 逐帧事件。
            use_frame_stats = false;
            perf_mode_note = Some(format!("frame_stats 省流模式不可用，保留逐帧事件: {error}"));
        }
    }
    let (candidate_index, symbol, symbol_offset, task_links) =
        attach_process_candidate(&mut bpf, target_pid, &libgui_path, &libgui_offsets, 0)?;
    let mut target_tgids = AyaHashMap::try_from(
        bpf.take_map("target_tgids")
            .ok_or_else(|| "missing BPF map: target_tgids".to_string())?,
    )
    .map_err(|e| e.to_string())?;
    target_tgids
        .insert(target_pid, 1, 0)
        .map_err(|e| format!("target_tgids[{target_pid}]: {e}"))?;
    let target_pids = HashSet::from([target_pid]);
    let task_count = task_links.len();
    let task_starttimes = task_starttime_snapshot(target_pid, task_links.keys().copied());
    let probe_started_ns = monotonic_ns();
    let frame_mode = match kind {
        BackendKind::RingBuf => "计帧=RingBuf逐帧事件",
        BackendKind::StatsMap => "计帧=frame_stats map",
        BackendKind::PerfEvent if use_frame_stats => "计帧=frame_stats map（PerfEvent省流）",
        BackendKind::PerfEvent => "计帧=PerfEvent逐帧事件",
    };
    let pid_links = HashMap::from([(target_pid, task_links)]);
    let pid_starttimes = process_starttime(target_pid)
        .map(|starttime| HashMap::from([(target_pid, starttime)]))
        .unwrap_or_default();
    let pid_task_starttimes = HashMap::from([(target_pid, task_starttimes)]);
    let pid_libgui_paths = HashMap::from([(target_pid, libgui_path.clone())]);
    let libgui_symbol_offsets = HashMap::from([(libgui_path.clone(), libgui_offsets)]);
    let pid_symbols = HashMap::from([(target_pid, symbol.clone())]);
    let pid_symbol_offsets = HashMap::from([(target_pid, symbol_offset)]);
    let pid_symbol_probes = HashMap::from([(
        target_pid,
        PidSymbolProbe {
            candidate_index,
            candidate_started_ns: probe_started_ns,
            round_started_ns: probe_started_ns,
            confirmed: false,
            confirmed_once: false,
            exhausted: false,
            attachable_mask: candidate_bit(candidate_index),
            no_frame_mask: 0,
            stalled_mask: 0,
            unavailable_mask: candidate_range_mask(0, candidate_index),
        },
    )]);

    Ok(Box::new(QixiaThreadsEbpfCtx {
        bpf,
        backend,
        frame_stats,
        frame_report,
        frame_report_prev,
        use_frame_stats,
        perf_stats_only,
        frame_stats_drops,
        frame_stats_drops_seen: 0,
        frame_stats_drops_pending: 0,
        frame_stats_drops_last_log_ns: 0,
        ringbuf_drops,
        ringbuf_drops_seen: 0,
        ringbuf_drops_pending: 0,
        ringbuf_drops_last_log_ns: 0,
        target_tgids,
        target_pids,
        pid,
        streams: HashMap::new(),
        selected_stream: None,
        pending_stream: None,
        pending_stream_since_ns: 0,
        stat_snapshots: HashMap::new(),
        frame_stats_updates: Vec::new(),
        frame_stats_last_poll_ns: 0,
        frame_stats_last_prune_ns: 0,
        pid_links,
        pid_starttimes,
        pid_task_starttimes,
        pid_libgui_paths,
        libgui_symbol_offsets,
        pid_symbols,
        pid_symbol_offsets,
        pid_symbol_probes,
        detailed_logging: true,
        frame_mode_reported: false,
        perf_lost_pending: 0,
        perf_lost_last_log_ns: 0,
        perf_buffer_cursor: 0,
        cur_fps: 0.0,
        symbol,
        backend_label: cstring_lossy(kind.label()),
        backend_selection_note: cstring_lossy("后端选择尚未完成"),
        startup_note: cstring_lossy(format!(
            "对象={} lib={} PID={} 挂载TID={} 候选符号={} {}{}",
            path.file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("queuebuffer_probe.bpf.o"),
            libgui_path.display(),
            target_pid,
            task_count,
            LIBGUI_FRAME_SYMBOLS.len(),
            frame_mode,
            perf_mode_note
                .as_deref()
                .map(|note| format!("；{note}"))
                .unwrap_or_default()
        )),
        last_error: cstring_lossy(""),
        target_pkg,
    }))
}

pub(crate) fn perf_stats_mode_value(frame_stats_available: bool) -> u32 {
    u32::from(frame_stats_available)
}
