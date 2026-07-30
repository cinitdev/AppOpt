//! eBPF 进程/线程发现加速层。
//!
//! 内核事件只提供“需要复查哪个 PID/TGID”的提示；包名、UID、starttime 和规则命中
//! 始终由 daemon 重新读取 `/proc` 确认，不能把 RingBuf/PerfEvent 当成最终事实。

#[cfg(any(target_os = "android", target_os = "linux"))]
mod platform {
    use aya::{
        Ebpf, EbpfLoader, Pod,
        maps::{
            Array, HashMap as AyaHashMap, MapData, MapError, PerCpuArray, PerfEventArray, RingBuf,
            perf::{PerfEvent, PerfEventArrayBuffer},
        },
        programs::TracePoint,
        util::online_cpus,
    };
    use std::collections::BTreeSet;
    use std::convert::TryInto;
    use std::fs;
    use std::mem;
    use std::os::fd::AsRawFd;
    use std::path::{Path, PathBuf};

    const EVENT_EXEC: u32 = 1;
    const EVENT_FORK: u32 = 2;
    const EVENT_RENAME: u32 = 3;
    const EVENT_EXIT: u32 = 4;
    const MIN_TARGET_ENTRIES: u32 = 64;
    const MAX_TARGET_ENTRIES: u32 = 8192;
    const MIN_TRACKED_ENTRIES: u32 = 256;
    const MAX_TRACKED_ENTRIES: u32 = 32768;
    const MIN_RING_BYTES: u32 = 16 * 1024;
    const MAX_RING_BYTES: u32 = 256 * 1024;

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct RawProcessEvent {
        kind: u32,
        tgid: u32,
        pid: u32,
        parent_tgid: u32,
    }

    unsafe impl Pod for RawProcessEvent {}

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct TraceOffsets {
        child_pid: u32,
    }

    unsafe impl Pod for TraceOffsets {}

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct ProcessEventStats {
        submitted: u64,
        dropped: u64,
    }

    unsafe impl Pod for ProcessEventStats {}

    #[derive(Debug, Default)]
    pub struct ProcessEventBatch {
        pub process_pids: BTreeSet<i32>,
        pub thread_tgids: BTreeSet<i32>,
        pub renamed_tgids: BTreeSet<i32>,
        pub exited_tids: BTreeSet<i32>,
        pub exited_tgids: BTreeSet<i32>,
        pub submitted: u64,
        pub dropped: u64,
    }

    enum ProcessEventBackend {
        RingBuf(RingBuf<MapData>),
        PerfEvent(Vec<PerfEventArrayBuffer<MapData>>),
    }

    #[derive(Clone, Copy)]
    enum BackendKind {
        RingBuf,
        PerfEvent,
    }

    pub struct ProcessEventMonitor {
        _bpf: Box<Ebpf>,
        events: ProcessEventBackend,
        target_tgids: AyaHashMap<MapData, u32, u8>,
        tracked_tids: AyaHashMap<MapData, u32, u8>,
        _trace_offsets: Array<MapData, TraceOffsets>,
        stats: PerCpuArray<MapData, ProcessEventStats>,
        targets: BTreeSet<u32>,
        tracked: BTreeSet<u32>,
        target_capacity: u32,
        tracked_capacity: u32,
        last_submitted: u64,
        last_dropped: u64,
        startup_note: String,
    }

    impl ProcessEventMonitor {
        pub fn start(
            path: &Path,
            requested_targets: usize,
            requested_tracked_tids: usize,
        ) -> Result<Self, String> {
            // 旧内核按 UID 汇总 BPF map 锁页；Android 系统自身的 pinned map 可能已经
            // 吃满默认 64MB。只提高当前进程上限，不预分配内存，失败时仍正常尝试加载。
            let _ = raise_memlock_limit();
            let target_capacity = recommended_target_capacity(requested_targets);
            let tracked_capacity = recommended_tracked_capacity(requested_tracked_tids);
            let ring_bytes = recommended_ring_bytes(target_capacity, tracked_capacity);
            let trace_offsets = read_trace_offsets()?;
            match start_backend(
                path,
                target_capacity,
                tracked_capacity,
                ring_bytes,
                trace_offsets,
                BackendKind::RingBuf,
            ) {
                Ok(monitor) => Ok(monitor),
                Err(ring_error) => {
                    let perf_path = perf_fallback_path(path);
                    let mut monitor = start_backend(
                        &perf_path,
                        target_capacity,
                        tracked_capacity,
                        ring_bytes,
                        trace_offsets,
                        BackendKind::PerfEvent,
                    )
                    .map_err(|perf_error| {
                        format!("RingBuf failed: {ring_error}; PerfEvent failed: {perf_error}")
                    })?;
                    monitor.startup_note = format!(
                        "{}；RingBuf 不可用，已降级为 PerfEvent",
                        monitor.startup_note
                    );
                    Ok(monitor)
                }
            }
        }

        pub fn event_fds(&self) -> Vec<i32> {
            match &self.events {
                ProcessEventBackend::RingBuf(events) => vec![events.as_raw_fd()],
                ProcessEventBackend::PerfEvent(events) => {
                    events.iter().map(AsRawFd::as_raw_fd).collect()
                }
            }
        }

        pub fn target_capacity(&self) -> usize {
            self.target_capacity as usize
        }

        pub fn tracked_capacity(&self) -> usize {
            self.tracked_capacity as usize
        }

        pub fn startup_note(&self) -> &str {
            &self.startup_note
        }

        pub fn sync_target_tgids<I>(&mut self, tgids: I) -> Result<(), String>
        where
            I: IntoIterator<Item = i32>,
        {
            let requested = tgids
                .into_iter()
                .filter_map(|pid| u32::try_from(pid).ok())
                .filter(|pid| *pid > 0)
                .collect::<BTreeSet<_>>();
            if requested.len() > self.target_capacity as usize {
                return Err(format!(
                    "目标TGID数量 {} 超过 map 容量 {}",
                    requested.len(),
                    self.target_capacity
                ));
            }
            for stale in self
                .targets
                .difference(&requested)
                .copied()
                .collect::<Vec<_>>()
            {
                remove_hash_key(&mut self.target_tgids, &stale)?;
            }
            for added in requested.difference(&self.targets).copied() {
                self.target_tgids
                    .insert(added, 1, 0)
                    .map_err(|error| error.to_string())?;
            }
            self.targets = requested;
            Ok(())
        }

        pub fn sync_tracked_tids<I>(&mut self, tids: I) -> Result<(), String>
        where
            I: IntoIterator<Item = i32>,
        {
            let requested = tids
                .into_iter()
                .filter_map(|tid| u32::try_from(tid).ok())
                .filter(|tid| *tid > 0)
                .collect::<BTreeSet<_>>();
            if requested.len() > self.tracked_capacity as usize {
                return Err(format!(
                    "受控TID数量 {} 超过 map 容量 {}",
                    requested.len(),
                    self.tracked_capacity
                ));
            }
            for stale in self
                .tracked
                .difference(&requested)
                .copied()
                .collect::<Vec<_>>()
            {
                remove_hash_key(&mut self.tracked_tids, &stale)?;
            }
            for added in requested.difference(&self.tracked).copied() {
                self.tracked_tids
                    .insert(added, 1, 0)
                    .map_err(|error| error.to_string())?;
            }
            self.tracked = requested;
            Ok(())
        }

        pub fn drain(&mut self) -> Result<ProcessEventBatch, String> {
            let mut batch = ProcessEventBatch::default();
            match &mut self.events {
                ProcessEventBackend::RingBuf(events) => {
                    while let Some(item) = events.next() {
                        if let Some(event) = read_process_event(&item, &[]) {
                            collect_process_event(event, &mut batch);
                        }
                    }
                }
                ProcessEventBackend::PerfEvent(events) => {
                    for buffer in events {
                        buffer.for_each(|event| match event {
                            PerfEvent::Sample { head, tail } => {
                                if let Some(event) = read_process_event(head, tail) {
                                    collect_process_event(event, &mut batch);
                                }
                            }
                            PerfEvent::Lost { count } => {
                                batch.dropped = batch.dropped.saturating_add(count);
                            }
                        });
                    }
                }
            }
            let totals = self
                .stats
                .get(&0, 0)
                .map_err(|error| error.to_string())?
                .iter()
                .fold(ProcessEventStats::default(), |mut total, item| {
                    total.submitted = total.submitted.saturating_add(item.submitted);
                    total.dropped = total.dropped.saturating_add(item.dropped);
                    total
                });
            batch.submitted = totals.submitted.saturating_sub(self.last_submitted);
            batch.dropped = batch
                .dropped
                .saturating_add(totals.dropped.saturating_sub(self.last_dropped));
            self.last_submitted = totals.submitted;
            self.last_dropped = totals.dropped;
            Ok(batch)
        }
    }

    fn start_backend(
        path: &Path,
        target_capacity: u32,
        tracked_capacity: u32,
        ring_bytes: u32,
        offsets: TraceOffsets,
        kind: BackendKind,
    ) -> Result<ProcessEventMonitor, String> {
        let mut loader = EbpfLoader::new();
        loader.map_max_entries("target_tgids", target_capacity);
        loader.map_max_entries("tracked_tids", tracked_capacity);
        if matches!(kind, BackendKind::RingBuf) {
            loader.map_max_entries("events", ring_bytes);
        }
        let mut bpf = loader
            .load_file(path)
            .map_err(|error| format!("{}: {error:?}", path.display()))?;
        let mut trace_offsets = Array::try_from(
            bpf.take_map("trace_offsets")
                .ok_or("缺少 trace_offsets map")?,
        )
        .map_err(|error| error.to_string())?;
        trace_offsets
            .set(0, offsets, 0)
            .map_err(|error| format!("写入 sched_process_fork 动态偏移失败: {error}"))?;
        let exec_error = attach_tracepoint(
            &mut bpf,
            "appopt_sched_process_exec",
            "sched",
            "sched_process_exec",
        )
        .err();
        let rename_error =
            attach_tracepoint(&mut bpf, "appopt_task_rename", "task", "task_rename").err();
        let exit_error = attach_tracepoint(
            &mut bpf,
            "appopt_sched_process_exit",
            "sched",
            "sched_process_exit",
        )
        .err();
        let fork_error = attach_tracepoint(
            &mut bpf,
            "appopt_sched_process_fork",
            "sched",
            "sched_process_fork",
        )
        .err();
        if exec_error.is_some() && fork_error.is_some() {
            return Err(format!(
                "exec={}；fork={}",
                exec_error.unwrap_or_default(),
                fork_error.unwrap_or_default()
            ));
        }

        let events = match kind {
            BackendKind::RingBuf => ProcessEventBackend::RingBuf(
                RingBuf::try_from(
                    bpf.take_map("events")
                        .ok_or("缺少 process events RingBuf map")?,
                )
                .map_err(|error| error.to_string())?,
            ),
            BackendKind::PerfEvent => ProcessEventBackend::PerfEvent(open_perf_buffers(&mut bpf)?),
        };
        let target_tgids = AyaHashMap::try_from(
            bpf.take_map("target_tgids")
                .ok_or("缺少 target_tgids map")?,
        )
        .map_err(|error| error.to_string())?;
        let tracked_tids = AyaHashMap::try_from(
            bpf.take_map("tracked_tids")
                .ok_or("缺少 tracked_tids map")?,
        )
        .map_err(|error| error.to_string())?;
        let stats =
            PerCpuArray::try_from(bpf.take_map("event_stats").ok_or("缺少 event_stats map")?)
                .map_err(|error| error.to_string())?;
        let backend_label = match kind {
            BackendKind::RingBuf => format!("RingBuf={}KB", ring_bytes / 1024),
            BackendKind::PerfEvent => "PerfEvent per-CPU".to_string(),
        };
        let attach_label = match (exec_error, fork_error) {
            (None, None) => "exec+目标TGID fork".to_string(),
            (Some(error), None) => format!("仅目标TGID fork；exec 不可用={error}"),
            (None, Some(error)) => format!("仅全局 exec；fork 不可用={error}"),
            (Some(_), Some(_)) => unreachable!(),
        };
        let mut optional_events = Vec::with_capacity(2);
        match rename_error {
            None => optional_events.push("rename".to_string()),
            Some(error) => optional_events.push(format!("rename不可用={error}")),
        }
        match exit_error {
            None => optional_events.push("exit".to_string()),
            Some(error) => optional_events.push(format!("exit不可用={error}")),
        }
        Ok(ProcessEventMonitor {
            _bpf: Box::new(bpf),
            events,
            target_tgids,
            tracked_tids,
            _trace_offsets: trace_offsets,
            stats,
            targets: BTreeSet::new(),
            tracked: BTreeSet::new(),
            target_capacity,
            tracked_capacity,
            last_submitted: 0,
            last_dropped: 0,
            startup_note: format!(
                "{attach_label}+{}；{backend_label}；target_map={target_capacity} tracked_map={tracked_capacity} fork.child_pid@{}",
                optional_events.join("+"),
                offsets.child_pid
            ),
        })
    }

    fn remove_hash_key(map: &mut AyaHashMap<MapData, u32, u8>, key: &u32) -> Result<(), String> {
        match map.remove(key) {
            Ok(()) | Err(MapError::KeyNotFound) => Ok(()),
            Err(MapError::SyscallError(error))
                if error.io_error.raw_os_error() == Some(libc::ENOENT) =>
            {
                Ok(())
            }
            Err(error) => Err(error.to_string()),
        }
    }

    fn raise_memlock_limit() -> Result<(), String> {
        let limit = libc::rlimit {
            rlim_cur: libc::RLIM_INFINITY,
            rlim_max: libc::RLIM_INFINITY,
        };
        let result = unsafe { libc::setrlimit(libc::RLIMIT_MEMLOCK, &limit) };
        if result == 0 {
            Ok(())
        } else {
            Err(std::io::Error::last_os_error().to_string())
        }
    }

    fn open_perf_buffers(bpf: &mut Ebpf) -> Result<Vec<PerfEventArrayBuffer<MapData>>, String> {
        let mut array = PerfEventArray::try_from(
            bpf.take_map("events")
                .ok_or("缺少 process events PerfEvent map")?,
        )
        .map_err(|error| error.to_string())?;
        let cpus = online_cpus().map_err(|(_, error)| error.to_string())?;
        let mut buffers = Vec::with_capacity(cpus.len());
        for cpu in cpus {
            buffers.push(
                array
                    .open(cpu, Some(8))
                    .map_err(|error| format!("open perf buffer cpu {cpu}: {error}"))?,
            );
        }
        if buffers.is_empty() {
            return Err("没有可用 online CPU PerfEvent buffer".to_string());
        }
        Ok(buffers)
    }

    fn perf_fallback_path(path: &Path) -> PathBuf {
        path.with_file_name("process_events_perf.bpf.o")
    }

    fn read_process_event(head: &[u8], tail: &[u8]) -> Option<RawProcessEvent> {
        let size = mem::size_of::<RawProcessEvent>();
        if head.len() >= size {
            return Some(unsafe {
                std::ptr::read_unaligned(head.as_ptr().cast::<RawProcessEvent>())
            });
        }
        if head.len().saturating_add(tail.len()) < size {
            return None;
        }
        let mut bytes = [0u8; mem::size_of::<RawProcessEvent>()];
        let first = head.len().min(size);
        bytes[..first].copy_from_slice(&head[..first]);
        bytes[first..].copy_from_slice(&tail[..size - first]);
        Some(unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast::<RawProcessEvent>()) })
    }

    fn collect_process_event(event: RawProcessEvent, batch: &mut ProcessEventBatch) {
        match event.kind {
            EVENT_EXEC if event.tgid > 0 => {
                if let Ok(pid) = i32::try_from(event.tgid) {
                    batch.process_pids.insert(pid);
                }
            }
            EVENT_FORK if event.tgid > 0 => {
                if let Ok(tgid) = i32::try_from(event.tgid) {
                    batch.thread_tgids.insert(tgid);
                }
                // child_pid 可能是新进程，也可能只是线程 TID；daemon 会先读取
                // /proc/<pid>/status 的 Tgid 规范化，不能直接写入 PID 缓存。
                if let Ok(pid) = i32::try_from(event.pid) {
                    batch.process_pids.insert(pid);
                }
            }
            EVENT_RENAME if event.tgid > 0 => {
                if let Ok(tgid) = i32::try_from(event.tgid) {
                    batch.renamed_tgids.insert(tgid);
                }
            }
            EVENT_EXIT if event.tgid > 0 && event.pid > 0 => {
                if let Ok(tid) = i32::try_from(event.pid) {
                    batch.exited_tids.insert(tid);
                }
                if event.pid == event.tgid {
                    if let Ok(tgid) = i32::try_from(event.tgid) {
                        batch.exited_tgids.insert(tgid);
                    }
                }
            }
            _ => {}
        }
    }

    pub fn recommended_target_capacity(requested_targets: usize) -> u32 {
        let requested = requested_targets
            .saturating_mul(2)
            .saturating_add(MIN_TARGET_ENTRIES as usize)
            .clamp(MIN_TARGET_ENTRIES as usize, MAX_TARGET_ENTRIES as usize);
        requested
            .next_power_of_two()
            .min(MAX_TARGET_ENTRIES as usize) as u32
    }

    pub fn recommended_tracked_capacity(requested_tids: usize) -> u32 {
        let requested = requested_tids
            .saturating_mul(2)
            .saturating_add(MIN_TRACKED_ENTRIES as usize)
            .clamp(MIN_TRACKED_ENTRIES as usize, MAX_TRACKED_ENTRIES as usize);
        requested
            .next_power_of_two()
            .min(MAX_TRACKED_ENTRIES as usize) as u32
    }

    fn recommended_ring_bytes(target_capacity: u32, tracked_capacity: u32) -> u32 {
        target_capacity
            .saturating_add(tracked_capacity)
            .saturating_mul(32)
            .clamp(MIN_RING_BYTES, MAX_RING_BYTES)
            .next_power_of_two()
            .min(MAX_RING_BYTES)
    }

    fn read_trace_offsets() -> Result<TraceOffsets, String> {
        let paths = [
            Path::new("/sys/kernel/tracing/events/sched/sched_process_fork/format"),
            Path::new("/sys/kernel/debug/tracing/events/sched/sched_process_fork/format"),
        ];
        let mut errors = Vec::new();
        for path in paths {
            match fs::read_to_string(path) {
                Ok(contents) => match parse_tracepoint_field_offset(&contents, "child_pid") {
                    Some(child_pid) if child_pid <= 512 => {
                        return Ok(TraceOffsets { child_pid });
                    }
                    Some(child_pid) => errors.push(format!(
                        "{}: child_pid offset {child_pid} 超出安全范围",
                        path.display()
                    )),
                    None => errors.push(format!("{}: 缺少 child_pid 字段", path.display())),
                },
                Err(error) => errors.push(format!("{}: {error}", path.display())),
            }
        }
        Err(format!(
            "无法读取 sched_process_fork 动态字段偏移: {}",
            errors.join("；")
        ))
    }

    fn parse_tracepoint_field_offset(contents: &str, field_name: &str) -> Option<u32> {
        for line in contents.lines() {
            let mut matches_field = false;
            let mut offset = None;
            for part in line.split(';').map(str::trim) {
                if let Some(declaration) = part.strip_prefix("field:") {
                    let declared_name = declaration
                        .split_ascii_whitespace()
                        .last()
                        .map(|name| name.trim_start_matches('*'))
                        .and_then(|name| name.split('[').next());
                    matches_field = declared_name == Some(field_name);
                } else if let Some(value) = part.strip_prefix("offset:") {
                    offset = value.trim().parse::<u32>().ok();
                }
            }
            if matches_field {
                return offset;
            }
        }
        None
    }

    fn attach_tracepoint(
        bpf: &mut Ebpf,
        program_name: &str,
        category: &str,
        tracepoint: &str,
    ) -> Result<(), String> {
        let program: &mut TracePoint = bpf
            .program_mut(program_name)
            .ok_or_else(|| format!("缺少 {program_name} 程序"))?
            .try_into()
            .map_err(|error: aya::programs::ProgramError| error.to_string())?;
        program.load().map_err(|error| error.to_string())?;
        program
            .attach(category, tracepoint)
            .map_err(|error| error.to_string())?;
        Ok(())
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
mod platform {
    use std::collections::BTreeSet;
    use std::path::Path;

    #[derive(Debug, Default)]
    pub struct ProcessEventBatch {
        pub process_pids: BTreeSet<i32>,
        pub thread_tgids: BTreeSet<i32>,
        pub renamed_tgids: BTreeSet<i32>,
        pub exited_tids: BTreeSet<i32>,
        pub exited_tgids: BTreeSet<i32>,
        pub submitted: u64,
        pub dropped: u64,
    }

    pub struct ProcessEventMonitor;

    impl ProcessEventMonitor {
        pub fn start(
            _path: &Path,
            _requested_targets: usize,
            _requested_tracked_tids: usize,
        ) -> Result<Self, String> {
            Err("当前平台不支持 eBPF 进程事件".to_string())
        }

        pub fn event_fds(&self) -> Vec<i32> {
            Vec::new()
        }

        pub fn target_capacity(&self) -> usize {
            0
        }

        pub fn tracked_capacity(&self) -> usize {
            0
        }

        pub fn startup_note(&self) -> &str {
            "不可用"
        }

        pub fn sync_target_tgids<I>(&mut self, _tgids: I) -> Result<(), String>
        where
            I: IntoIterator<Item = i32>,
        {
            Ok(())
        }

        pub fn sync_tracked_tids<I>(&mut self, _tids: I) -> Result<(), String>
        where
            I: IntoIterator<Item = i32>,
        {
            Ok(())
        }

        pub fn drain(&mut self) -> Result<ProcessEventBatch, String> {
            Ok(ProcessEventBatch::default())
        }
    }
}

pub use platform::{ProcessEventBatch, ProcessEventMonitor};
