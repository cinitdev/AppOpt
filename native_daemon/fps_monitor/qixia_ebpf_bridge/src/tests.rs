use crate::constants::{
    LIBGUI_FRAME_SYMBOL_NAME_CSTRS, LIBGUI_FRAME_SYMBOL_NAMES, LIBGUI_FRAME_SYMBOLS,
};
use crate::context::PidSymbolProbe;
use crate::diagnostics::backend_selection_report;
use crate::loader::{
    parse_kernel_release_version, perf_fallback_path, perf_stats_mode_value, stats_fallback_path,
};
use crate::symbols::{
    candidate_bit, compact_symbol_name, probe_candidate_results, readable_symbol_from_raw,
    readable_symbol_name,
};
use std::path::{Path, PathBuf};

// 仅按需在已授权的测试手机运行，不由常规测试触发，也不写入用户历史。
#[test]
#[ignore = "需要显式指定手机 PID、BPF 对象与 Root 权限"]
fn diagnose_live_streams() {
    use crate::clock::monotonic_ns;
    use crate::ffi::*;
    use std::ffi::{CStr, CString};
    use std::time::{Duration, Instant};
    let pid = std::env::var("QIXIA_TEST_PID")
        .unwrap()
        .parse::<i32>()
        .unwrap();
    let path = CString::new(std::env::var("QIXIA_TEST_BPF").unwrap()).unwrap();
    let ctx = qixia_ebpf_start(pid, path.as_ptr());
    assert!(
        !ctx.is_null(),
        "{}",
        unsafe { CStr::from_ptr(qixia_ebpf_last_start_error()) }.to_string_lossy()
    );
    if let Ok(symbol) = std::env::var("QIXIA_TEST_SYMBOL") {
        use object::{Object, ObjectSection, ObjectSymbol};
        let state = unsafe { &mut *ctx };
        let pid = pid as u32;
        let path = state.pid_libgui_paths[&pid].clone();
        let data = std::fs::read(&path).unwrap();
        let object = object::read::File::parse(data.as_slice()).unwrap();
        let sym = object
            .dynamic_symbols()
            .find(|item| item.name() == Ok(symbol.as_str()))
            .unwrap();
        let section = object
            .section_by_index(sym.section_index().unwrap())
            .unwrap();
        let offset = crate::symbols::code_address(object.architecture(), sym.address()) - section.address() + section.file_range().unwrap().0;
        let links = state.pid_links.remove(&pid).unwrap();
        assert!(crate::attach::detach_pid_links(&mut state.bpf, pid, links).is_empty());
        crate::streams::clear_pid_samples(state, pid);
        let link = crate::attach::attach_symbol_for_process(&mut state.bpf, pid, &path, &symbol, offset).unwrap();
        state.pid_links.insert(pid, vec![link]);
        state.pid_symbol_probes.get_mut(&pid).unwrap().confirmed = true;
        state.pid_symbols.insert(pid, CString::new(symbol).unwrap());
    }
    let start = Instant::now();
    let mut last_print = Instant::now();
    let mut count = 0;
    let seconds = std::env::var("QIXIA_TEST_SECONDS")
        .ok().and_then(|value| value.parse::<u64>().ok()).unwrap_or(40).min(600);
    while start.elapsed() < Duration::from_secs(seconds) {
        count += qixia_ebpf_poll(ctx).max(0);
        if last_print.elapsed() >= Duration::from_secs(1) {
            let now = monotonic_ns();
            let state = unsafe { &*ctx };
            let symbol =
                unsafe { CStr::from_ptr(qixia_ebpf_symbol_display(ctx)) }.to_string_lossy();
            println!(
                "t={:.1} events={count} fps={:.2} symbol={symbol} state={}",
                start.elapsed().as_secs_f64(),
                state.cur_fps,
                qixia_ebpf_probe_state(ctx)
            );
            for (key, stream) in &state.streams {
                println!(
                    "  surface={:x} tid={} age_ms={} samples={} fps={:.2} selected={}",
                    key.surface_ptr,
                    key.tid,
                    now.saturating_sub(stream.last_seen_ns) / 1_000_000,
                    stream.frame_times.len(),
                    stream.cur_fps,
                    state.selected_stream == Some(*key)
                );
            }
            count = 0;
            last_print = Instant::now();
        }
        std::thread::sleep(Duration::from_millis(80));
    }
    qixia_ebpf_stop(ctx);
}

#[test]
#[ignore = "需要手机 BPF 权限，仅为测试进程创建独立映射"]
fn kernel_map_retirement_preserves_live_surface_and_symbol_epoch() {
    use crate::clock::monotonic_ns;
    use crate::context::{FrameEvent, FrameStatsKey, FrameStatsValue};
    use crate::fps_stream::FpsStream;
    use crate::streams::{on_frame, refresh_current_fps, stream_key_from_parts};
    use std::ffi::CString;
    let pid = std::process::id();
    let path = CString::new(std::env::var("QIXIA_TEST_BPF").unwrap()).unwrap();
    let raw = crate::ffi::qixia_ebpf_start(pid as i32, path.as_ptr());
    assert!(!raw.is_null());
    // 本测试的映射和挂载只属于测试进程，离开作用域自动释放。
    let mut ctx = unsafe { Box::from_raw(raw) };
    let now = monotonic_ns();
    let old_key = FrameStatsKey {
        pid,
        tid: 100,
        surface_ptr: 0x1234,
    };
    let live_key = FrameStatsKey {
        tid: 101,
        ..old_key
    };
    let map = ctx.frame_stats.as_mut().unwrap();
    map.insert(
        old_key,
        FrameStatsValue {
            last_ts: now - 11_000_000_000,
            total_frames: 12,
        },
        0,
    )
    .unwrap();
    map.insert(
        live_key,
        FrameStatsValue {
            last_ts: now,
            total_frames: 120,
        },
        0,
    )
    .unwrap();
    let key = stream_key_from_parts(pid, 101, 0x1234);
    let mut stream = FpsStream::new(now - 2_000_000_000);
    for index in 0..=120 {
        stream.on_frame(now - 2_000_000_000 + index * 16_666_666);
    }
    ctx.streams.insert(key, stream);
    ctx.selected_stream = Some(key);
    crate::stats::prune_frame_stats(&mut ctx);
    assert!(ctx.frame_stats.as_ref().unwrap().get(&old_key, 0).is_err());
    assert!(ctx.frame_stats.as_ref().unwrap().get(&live_key, 0).is_ok());
    assert_eq!(ctx.streams[&key].frame_times.len(), 61);
    refresh_current_fps(&mut ctx, now);
    assert!(ctx.cur_fps > 59.0 && ctx.cur_fps <= 60.1);
    let start = ctx.pid_symbol_probes[&pid].candidate_started_ns;
    let event = FrameEvent {
        pid,
        tid: 102,
        surface_ptr: 0x5678,
        timestamp_ns: start - 1,
    };
    assert!(!on_frame(&mut ctx, event));
    assert!(
        !ctx.streams
            .contains_key(&stream_key_from_parts(pid, 102, 0x5678))
    );
    assert!(on_frame(
        &mut ctx,
        FrameEvent {
            timestamp_ns: start + 2_000_000,
            ..event
        }
    ));
    assert!(!on_frame(
        &mut ctx,
        FrameEvent {
            timestamp_ns: start + 2_500_000,
            ..event
        }
    ));
    // 静止超过探测时间仍保留已挂载符号，避免随后滑动落入重试空窗。
    let link_count = ctx.pid_links[&pid].len();
    assert_eq!(link_count, 1, "同一进程的帧入口不能按线程重复挂载");
    for _ in crate::constants::LIBGUI_FRAME_SYMBOLS {
        crate::probes::advance_symbol_probes(
            &mut ctx,
            monotonic_ns() + crate::constants::SYMBOL_PROBE_NS * 2,
        ).unwrap();
    }
    assert!(!ctx.pid_symbol_probes[&pid].exhausted);
    assert!(ctx.pid_symbol_probes[&pid].waiting);
    assert_eq!(ctx.pid_links[&pid].len(), link_count);
    assert_eq!(crate::ffi::qixia_ebpf_probe_state(&*ctx), 4);
    assert!(ctx.target_tgids.get(&pid, 0).is_ok());
    assert!(on_frame(&mut ctx, FrameEvent { timestamp_ns: now + 20_000_000, ..event }));
    // 静止时不换入口；确实收到无效回调参数后，才切到时间戳更新入口。
    let previous = ctx.pid_symbol_probes[&pid].candidate_index;
    let offsets = crate::symbols::resolve_candidate_offsets(&ctx.pid_libgui_paths[&pid]).unwrap();
    let next = offsets.iter().enumerate().skip(previous + 1).find_map(|(index, offset)| offset.map(|_| index));
    ctx.invalid_latch.insert(pid, 1, 0).unwrap();
    crate::probes::advance_symbol_probes(&mut ctx, monotonic_ns() + crate::constants::SYMBOL_PROBE_NS * 2).unwrap();
    // 不同系统导出的兼容符号不同，按设备实际可用的下一入口断言。
    assert_eq!(ctx.pid_symbol_probes[&pid].candidate_index, next.unwrap_or(previous));
    if next.is_some() { assert!(ctx.invalid_latch.get(&pid, 0).is_err()); }
    else { assert!(ctx.pid_symbol_probes[&pid].waiting); }
    crate::targets::sync_target_pids(&mut ctx, &[]).unwrap();
    assert_eq!(ctx.last_error.to_bytes(), b"");
}

#[test]
#[ignore = "需要 Root，仅使用独立测试进程的映射验证多帧源选择"]
fn kernel_sparse_secondary_stream_does_not_suppress_live_frames() {
    use crate::clock::monotonic_ns;
    use crate::context::BackendKind;
    use crate::fps_stream::FpsStream;
    use crate::streams::{refresh_current_fps, stream_key_from_parts};
    let dir = PathBuf::from(std::env::var("QIXIA_TEST_BPF").unwrap());
    let pid = std::process::id();
    let make_stream = |end: u64, hz: u64| {
        let mut stream = FpsStream::new(end - 3_000_000_000);
        for index in 0..=hz * 3 {
            stream.on_frame(end - 3_000_000_000 + index * 1_000_000_000 / hz);
        }
        stream
    };
    for (filename, kind) in [
        ("queuebuffer_probe.bpf.o", BackendKind::RingBuf),
        ("queuebuffer_probe_stats.bpf.o", BackendKind::StatsMap),
        ("queuebuffer_probe_perf.bpf.o", BackendKind::PerfEvent),
    ] {
        let mut ctx = crate::loader::start_backend(
            &dir.with_file_name(filename), kind, pid as i32, None,
        ).unwrap();
        let live = stream_key_from_parts(pid, 1, 0x1234);
        let secondary = stream_key_from_parts(pid, 2, 0x5678);
        let warming = stream_key_from_parts(pid, 3, 0x9abc);
        for hz in [24, 30, 60, 120, 144, 165, 240, 480] {
            let now = monotonic_ns();
            ctx.streams.clear();
            ctx.pending_stream = None;
            ctx.selected_stream = Some(secondary);
            ctx.streams.insert(secondary, make_stream(now, 5));
            ctx.streams.insert(live, make_stream(now, hz));
            refresh_current_fps(&mut ctx, now);
            // 连续帧源胜出仍需经过原有等待，避免来回跳动；等待结束后必须离开低频队列。
            assert_eq!(ctx.selected_stream, Some(secondary), "{filename}, {hz}");
            let next = now + 610_000_000;
            ctx.streams.insert(secondary, make_stream(next, 5));
            ctx.streams.insert(live, make_stream(next, hz));
            refresh_current_fps(&mut ctx, next);
            assert_eq!(ctx.selected_stream, Some(live), "{filename}, {hz}");
            assert!((ctx.cur_fps - hz as f64).abs() < 0.2);
            let mut partial = FpsStream::new(next - 50_000_000);
            for timestamp in [next - 50_000_000, next - 25_000_000, next] {
                partial.on_frame(timestamp);
            }
            ctx.streams.insert(warming, partial);
            refresh_current_fps(&mut ctx, next);
            assert_eq!(ctx.selected_stream, Some(live));
            assert!((ctx.cur_fps - hz as f64).abs() < 0.2);
            // 持续画面结束后，真正仅有 5 FPS 的场景仍按实测值输出。
            ctx.streams.remove(&live);
            ctx.streams.remove(&warming);
            refresh_current_fps(&mut ctx, next);
            assert_eq!(ctx.selected_stream, Some(secondary));
            assert!((ctx.cur_fps - 5.0).abs() < 0.1);
        }
        println!("{filename}: 多帧源选择、预热隔离、切换等待和真实低帧保留通过");
    }
}

#[test]
fn perf_events_remain_enabled_without_stats_map() {
    assert_eq!(perf_stats_mode_value(false), 0);
}

#[test]
fn perf_events_are_suppressed_after_stats_map_is_confirmed() {
    assert_eq!(perf_stats_mode_value(true), 1);
}

#[test]
fn fallback_objects_are_resolved_next_to_ring_object() {
    let ring = Path::new("/module/config/ebpf/queuebuffer_probe.bpf.o");
    assert_eq!(
        stats_fallback_path(ring),
        PathBuf::from("/module/config/ebpf/queuebuffer_probe_stats.bpf.o")
    );
    assert_eq!(
        perf_fallback_path(ring),
        PathBuf::from("/module/config/ebpf/queuebuffer_probe_perf.bpf.o")
    );
}

#[test]
fn fps_symbols_have_stable_readable_names() {
    assert_eq!(LIBGUI_FRAME_SYMBOLS.len(), LIBGUI_FRAME_SYMBOL_NAMES.len());
    assert_eq!(
        LIBGUI_FRAME_SYMBOLS.len(),
        LIBGUI_FRAME_SYMBOL_NAME_CSTRS.len()
    );
    for (index, raw) in LIBGUI_FRAME_SYMBOLS.iter().enumerate() {
        assert_eq!(readable_symbol_from_raw(raw), readable_symbol_name(index));
        assert_eq!(
            compact_symbol_name(index),
            readable_symbol_name(index)
                .strip_prefix("Surface::")
                .unwrap_or(readable_symbol_name(index))
        );
    }
    assert_eq!(
        readable_symbol_from_raw("missing"),
        "帧合成完成(未知符号)"
    );
}

#[test]
fn probe_summary_groups_no_frame_and_stalled_candidates() {
    let probe = PidSymbolProbe {
        candidate_index: 1,
        candidate_started_ns: 9,
        round_started_ns: 1,
        confirmed: false,
        confirmed_once: true,
        exhausted: true,
        waiting: false,
        attachable_mask: candidate_bit(0) | candidate_bit(1),
        no_frame_mask: candidate_bit(0),
        stalled_mask: candidate_bit(1),
        unavailable_mask: 0,
    };
    assert_eq!(
        probe_candidate_results(probe),
        "BLASTBufferQueue::transactionCallback(64位)=0 | BLASTBufferQueue::transactionCallback(32位)=停帧"
    );
    assert_eq!(probe.attachable_mask.count_ones(), 2);
    let probe = PidSymbolProbe {
        exhausted: false,
        ..probe
    };
    assert!(!probe.accepts_timestamp(8));
    assert!(probe.accepts_timestamp(9));
    assert!(probe.should_advance(4_000_000_009, 9));
    assert!(!probe.should_advance(4_000_000_009, 3_900_000_000));
    let confirmed = PidSymbolProbe {
        confirmed: true,
        ..probe
    };
    assert!(!confirmed.should_advance(u64::MAX, 9));
    let exhausted = PidSymbolProbe {
        exhausted: true,
        ..probe
    };
    assert!(!exhausted.accepts_timestamp(100));
    let waiting = PidSymbolProbe { waiting: true, ..probe };
    assert!(!waiting.should_advance(u64::MAX, 9));
    assert!(waiting.accepts_timestamp(100));
}

#[test]
#[ignore = "需要 Root，仅操作独立测试进程的 eBPF 映射"]
fn kernel_loss_and_source_switch_do_not_reuse_old_windows() {
    use crate::clock::monotonic_ns;
    use crate::fps_stream::FpsStream;
    use crate::streams::{refresh_current_fps, stream_key_from_parts};
    use crate::frame_metrics::QixiaThreadsFrameSource;
    use std::ffi::CString;
    let pid = std::process::id();
    let path = CString::new(std::env::var("QIXIA_TEST_BPF").unwrap()).unwrap();
    let raw = crate::ffi::qixia_ebpf_start(pid as i32, path.as_ptr());
    assert!(!raw.is_null());
    let mut ctx = unsafe { Box::from_raw(raw) };
    let now = monotonic_ns();
    let make_stream = |end: u64, hz: u64| {
        let mut stream = FpsStream::new(end - 2_000_000_000);
        for index in 0..=hz * 2 {
            stream.on_frame(end - 2_000_000_000 + index * 1_000_000_000 / hz);
        }
        stream
    };
    let first = stream_key_from_parts(pid, 1, 0x1234);
    let second = stream_key_from_parts(pid, 2, 0x5678);
    ctx.streams.insert(first, make_stream(now, 60));
    refresh_current_fps(&mut ctx, now);
    let mut source = QixiaThreadsFrameSource::default();
    assert_eq!(crate::ffi::qixia_ebpf_source(&*ctx, &mut source), 1);
    let generation = source.generation;
    assert_eq!(source.surface, first.surface_ptr);
    ctx.streams.insert(second, make_stream(now, 165));
    refresh_current_fps(&mut ctx, now);
    assert_eq!(ctx.selected_stream, Some(first));
    // 新来源持续胜出后才切换；不能把原来暂存的峰值直接当作切换后的卡顿。
    refresh_current_fps(&mut ctx, now + 610_000_000);
    assert_eq!(ctx.selected_stream, Some(second));
    assert_eq!(crate::ffi::qixia_ebpf_source(&*ctx, &mut source), 1);
    assert!(source.generation > generation);
    assert_eq!(ctx.streams.get_mut(&second).unwrap().take_report_max(now + 610_000_000), 0);
    ctx.ringbuf_drops.as_mut().unwrap().set(0, 1, 0).unwrap();
    crate::events::poll_inner(&mut ctx).unwrap();
    assert!(ctx.use_frame_stats);
    assert_eq!(crate::ffi::qixia_ebpf_source(&*ctx, &mut source), -1);
    assert!(ctx.streams.is_empty());
    let before_reset = ctx.samples_since_ns - 1;
    assert!(!crate::streams::on_frame(&mut ctx, crate::context::FrameEvent {
        timestamp_ns: before_reset, pid, tid: 1, surface_ptr: 0x1234,
    }));
    // 用明确的单调时间和累计帧数模拟恢复后的完整窗口，验证仍是 165 FPS，
    // 最大帧间隔继续使用内核报告，不能被平均间隔覆盖。
    let base = monotonic_ns();
    ctx.samples_since_ns = base;
    ctx.pid_symbol_probes.get_mut(&pid).unwrap().candidate_started_ns = base;
    let key = crate::context::FrameStatsKey { pid, tid: 0, surface_ptr: 0x1234 };
    ctx.frame_stats.as_mut().unwrap().insert(key, crate::context::FrameStatsValue {
        last_ts: base, total_frames: 1,
    }, 0).unwrap();
    ctx.frame_stats_last_poll_ns = 0;
    crate::events::poll_inner(&mut ctx).unwrap();
    std::thread::sleep(std::time::Duration::from_millis(1050));
    let end = monotonic_ns();
    ctx.frame_stats.as_mut().unwrap().insert(key, crate::context::FrameStatsValue {
        last_ts: end, total_frames: (end - base) * 165 / 1_000_000_000 + 1,
    }, 0).unwrap();
    ctx.frame_report.as_mut().unwrap().insert(key, crate::context::FrameReportValue {
        interval_ts: end - 50_000_000, max_interval_ns: 49_000_000, window_ts: base,
    }, 0).unwrap();
    ctx.frame_stats_last_poll_ns = 0;
    crate::events::poll_inner(&mut ctx).unwrap();
    assert!((160.0..=165.1).contains(&ctx.cur_fps), "恢复后的 FPS={}", ctx.cur_fps);
    assert_eq!(crate::ffi::qixia_ebpf_source(&*ctx, &mut source), 1);
    // 切换边界会清空切换前的报告；下一份内核窗口保留真实峰值。
    std::thread::sleep(std::time::Duration::from_millis(1050));
    let next_end = monotonic_ns();
    ctx.frame_stats.as_mut().unwrap().insert(key, crate::context::FrameStatsValue {
        last_ts: next_end, total_frames: (next_end - base) * 165 / 1_000_000_000 + 1,
    }, 0).unwrap();
    ctx.frame_report.as_mut().unwrap().insert(key, crate::context::FrameReportValue {
        interval_ts: next_end - 50_000_000, max_interval_ns: 49_000_000, window_ts: end,
    }, 0).unwrap();
    crate::events::poll_inner(&mut ctx).unwrap();
    assert_eq!(crate::ffi::qixia_ebpf_take_frame_max(&mut *ctx), 49_000_000);
    let mut metrics = crate::frame_metrics::QixiaThreadsFrameMetrics::default();
    assert_eq!(crate::ffi::qixia_ebpf_metrics(&*ctx, &mut metrics), 1);
    assert_eq!(metrics.flags, 1);
    // 累计计数也出错时退回逐帧，两个计帧方式之间重新建窗。
    ctx.frame_stats_drops.as_mut().unwrap().set(0, 1, 0).unwrap();
    crate::stats::poll_frame_stats_drop_count(&mut ctx).unwrap();
    assert!(!ctx.use_frame_stats);
    assert!(ctx.recovering_samples);
}

#[test]
fn backend_report_keeps_attempt_order_and_compacts_errors() {
    assert_eq!(
        backend_selection_report(
            "跳过",
            Some("内核 4.19\n不支持 RingBuf"),
            "成功",
            None,
            "未尝试",
        ),
        "RingBuf=跳过（内核 4.19 不支持 RingBuf） | StatsMap=成功 | PerfEvent=未尝试 | SurfaceFlinger=待命"
    );
}

#[test]
fn kernel_release_parser_handles_android_vendor_suffixes() {
    assert_eq!(
        parse_kernel_release_version("4.19.113-perf-g42cc20a57a7b"),
        Some((4, 19))
    );
    assert_eq!(
        parse_kernel_release_version("5.4.210-qgki-g123"),
        Some((5, 4))
    );
    assert_eq!(parse_kernel_release_version("5.8.0"), Some((5, 8)));
    assert_eq!(
        parse_kernel_release_version("6.6.66-android15-8"),
        Some((6, 6))
    );
    assert_eq!(parse_kernel_release_version("android-kernel"), None);
}
#[test]
fn frame_transport_layout_and_split_reads_preserve_bpf_event_bytes() {
    use crate::context::{FrameEvent, FrameStatsKey, FrameStatsValue};
    use crate::events::{read_frame_event, read_split_frame_event};
    assert_eq!(std::mem::size_of::<FrameEvent>(), 24);
    assert_eq!(std::mem::size_of::<FrameStatsKey>(), 16);
    assert_eq!(std::mem::size_of::<FrameStatsValue>(), 16);
    let mut bytes = Vec::new();
    bytes.extend(123456789u64.to_ne_bytes());
    bytes.extend(2001u32.to_ne_bytes());
    bytes.extend(2002u32.to_ne_bytes());
    bytes.extend(0x123456789abcdef0u64.to_ne_bytes());
    for split in 0..=bytes.len() {
        let event = read_split_frame_event(&bytes[..split], &bytes[split..]).unwrap();
        assert_eq!(event.timestamp_ns, 123456789);
        assert_eq!(event.pid, 2001);
        assert_eq!(event.tid, 2002);
        assert_eq!(event.surface_ptr, 0x123456789abcdef0);
    }
    for length in 0..bytes.len() {
        assert!(unsafe { read_frame_event(&bytes[..length]) }.is_none());
        assert!(read_split_frame_event(&bytes[..length], &[]).is_none());
    }
}

#[test]
fn same_surface_cross_thread_duplicates_keep_the_original_frame_rate() {
    use crate::fps_stream::FpsStream;
    use crate::streams::stream_key_from_parts;
    use std::collections::HashMap;

    for fps in [60, 120, 144, 165, 240, 360, 480] {
        let mut streams = HashMap::new();
        let interval = 1_000_000_000 / fps;
        // 同一 Surface 的短间隔事件可能来自不同线程，内核的逐线程去重无法覆盖。
        for index in 0..=fps * 2 {
            let timestamp = 1_000_000_000 + index * interval;
            for (tid, offset) in [(2001, 0), (2002, 800_000)] {
                streams
                    .entry(stream_key_from_parts(2000, tid, 0x1234))
                    .or_insert_with(|| FpsStream::new(timestamp))
                    .on_frame(timestamp + offset);
            }
        }
        assert_eq!(streams.len(), 1);
        let stream = streams.values().next().unwrap();
        assert!((stream.cur_fps - fps as f64).abs() < 0.2);
        assert!(stream.selection_score(stream.last_seen_ns).is_finite());
    }
}

#[test]
fn unrelated_surfaces_processes_and_unknown_surface_threads_stay_separate() {
    use crate::streams::stream_key_from_parts;
    let main = stream_key_from_parts(2000, 2001, 0x1234);
    assert_ne!(main, stream_key_from_parts(2000, 2001, 0x5678));
    assert_ne!(main, stream_key_from_parts(3000, 2001, 0x1234));
    assert_ne!(
        stream_key_from_parts(2000, 2001, 0),
        stream_key_from_parts(2000, 2002, 0),
    );
}

#[test]
fn completion_sources_only_use_known_timestamp_abis() {
    assert_eq!(crate::symbols::code_address(object::Architecture::Arm, 0x8793d), 0x8793c);
    assert_eq!(crate::symbols::code_address(object::Architecture::Arm, 0x8793c), 0x8793c);
    assert_eq!(crate::symbols::code_address(object::Architecture::Aarch64, 0xc945c), 0xc945c);
    for (index, symbol) in LIBGUI_FRAME_SYMBOLS.iter().enumerate() {
        assert!(symbol.contains(match index {
            0 | 1 => "BLASTBufferQueue19transactionCallback",
            2 | 3 => "BLASTBufferItemConsumer21updateFrameTimestamps",
            _ => "ConsumerFrameEventHistory8addLatch",
        }));
        assert!(!symbol.contains("Committed"));
        let abi = crate::constants::frame_callback_abi(index);
        assert_eq!(symbol.contains("CallbackEx") || symbol.ends_with("Eyx") || symbol.ends_with("Exx"), abi % 2 == 0);
    }
}

// 合成时间覆盖两秒，但所有通知在一次轮询中批量到达；同一帧还可能
// 在其他 Binder 线程被重复通知。时间戳必须保留，不能改成通知到达时间。
#[test]
fn batched_completed_callbacks_preserve_high_refresh_timing() {
    use crate::fps_stream::FpsStream;
    for fps in [60, 90, 120, 144, 165, 240, 360, 480] {
        let base = 10_000_000_000;
        let mut stream = FpsStream::new(base);
        for index in 0..=fps * 2 {
            let timestamp = base + index * 1_000_000_000 / fps;
            assert!(stream.on_frame(timestamp));
            assert!(!stream.on_frame(timestamp));
            assert!(!stream.on_frame(timestamp - 1));
        }
        assert!((stream.cur_fps - fps as f64).abs() < 0.01);
        // 帧仍按原合成时间老化，积压通知不能重置成新鲜帧。
        assert_eq!(stream.fps_at(base + 3_000_000_000), 0.0);
    }
}

#[unsafe(no_mangle)]
#[inline(never)]
pub extern "C" fn qixia_test_frame_callback(queue: usize, frame_number: u64, latch: i64) -> u64 {
    std::hint::black_box(queue as u64)
        .wrapping_add(std::hint::black_box(frame_number))
        .wrapping_add(std::hint::black_box(latch as u64))
}

#[test]
#[ignore = "需要 Root，仅验证测试进程的单个 uprobe 是否覆盖已有和新建线程"]
fn kernel_process_probe_covers_existing_and_new_threads() {
    use crate::context::{BackendKind, FrameStatsKey};
    use object::{Object, ObjectSection, ObjectSymbol};
    let path = PathBuf::from(std::env::var("QIXIA_TEST_BPF").unwrap());
    let executable = std::fs::read_link("/proc/self/exe").unwrap();
    let bytes = std::fs::read(&executable).unwrap();
    let object = object::File::parse(bytes.as_slice()).unwrap();
    let symbol = object.symbols().find(|symbol| symbol.name() == Ok("qixia_test_frame_callback")).unwrap();
    let section = object.section_by_index(symbol.section_index().unwrap()).unwrap();
    let offset = crate::symbols::code_address(object.architecture(), symbol.address()) - section.address() + section.file_range().unwrap().0;
    let pid = std::process::id();
    let (send, recv) = std::sync::mpsc::channel::<()>();
    let (done, received) = std::sync::mpsc::channel();
    let existing = std::thread::spawn(move || {
        recv.recv().unwrap();
        qixia_test_frame_callback(0x1111, 1, crate::clock::monotonic_ns() as i64);
        done.send(()).unwrap();
    });
    let mut ctx = crate::loader::start_backend(&path, BackendKind::StatsMap, pid as i32, None).unwrap();
    assert_eq!(ctx.pid_links[&pid].len(), 1);
    let links = ctx.pid_links.remove(&pid).unwrap();
    assert!(crate::attach::detach_pid_links(&mut ctx.bpf, pid, links).is_empty());
    let link = crate::attach::attach_symbol_for_process(&mut ctx.bpf, pid, &executable, "qixia_test_frame_callback", offset).unwrap();
    ctx.pid_links.insert(pid, vec![link]);
    ctx.target_tgids.insert(pid, if cfg!(target_pointer_width = "32") { 4 } else { 3 }, 0).unwrap();
    send.send(()).unwrap();
    received.recv().unwrap();
    existing.join().unwrap();
    std::thread::spawn(|| { qixia_test_frame_callback(0x2222, 2, crate::clock::monotonic_ns() as i64); }).join().unwrap();
    qixia_test_frame_callback(0x3333, 3, crate::clock::monotonic_ns() as i64);
    for queue in [0x1111, 0x2222, 0x3333] {
        let value = ctx.frame_stats.as_ref().unwrap().get(&FrameStatsKey { pid, tid: 0, surface_ptr: queue }, 0);
        assert!(value.is_ok(), "进程级探针未覆盖线程 queue={queue:x}: {:?}", value.as_ref().err());
        assert_eq!(value.unwrap().total_frames, 1);
    }
    assert_eq!(ctx.pid_links[&pid].len(), 1);
    crate::targets::sync_target_pids(&mut ctx, &[pid as i32]).unwrap();
    assert_eq!(ctx.pid_links[&pid].len(), 1, "目标刷新不能重新按线程挂载");
    ctx.target_tgids.remove(&pid).unwrap();
    qixia_test_frame_callback(0x4444, 4, crate::clock::monotonic_ns() as i64);
    assert!(ctx.frame_stats.as_ref().unwrap().get(&FrameStatsKey { pid, tid: 0, surface_ptr: 0x4444 }, 0).is_err());
    println!("单个进程探针覆盖已有线程、新建线程和测试线程");
}

#[test]
#[ignore = "需要 Root，仅挂载测试程序自身并验证三种 BPF 后端"]
fn kernel_completed_callbacks_use_latch_and_deduplicate_across_threads() {
    use crate::clock::monotonic_ns;
    use crate::context::{BackendKind, FrameStatsKey};
    use object::{Object, ObjectSection, ObjectSymbol};
    let dir = PathBuf::from(std::env::var("QIXIA_TEST_BPF").unwrap());
    let executable = std::fs::read_link("/proc/self/exe").unwrap();
    let bytes = std::fs::read(&executable).unwrap();
    let object = object::File::parse(bytes.as_slice()).unwrap();
    let symbol = object.symbols().find(|symbol| symbol.name() == Ok("qixia_test_frame_callback")).unwrap();
    let section = object.section_by_index(symbol.section_index().unwrap()).unwrap();
    let offset = crate::symbols::code_address(object.architecture(), symbol.address()) - section.address() + section.file_range().unwrap().0;
    let pid = std::process::id();
    for (filename, kind) in [
        ("queuebuffer_probe.bpf.o", BackendKind::RingBuf),
        ("queuebuffer_probe_stats.bpf.o", BackendKind::StatsMap),
        ("queuebuffer_probe_perf.bpf.o", BackendKind::PerfEvent),
    ] {
        if matches!(kind, BackendKind::RingBuf) && crate::loader::ringbuf_kernel_note().is_some() {
            println!("{filename}: 当前内核不支持 RingBuf，验证 StatsMap 和 PerfEvent");
            continue;
        }
        let mut ctx = crate::loader::start_backend(&dir.with_file_name(filename), kind, pid as i32, None).unwrap();
        let links = ctx.pid_links.remove(&pid).unwrap();
        assert!(crate::attach::detach_pid_links(&mut ctx.bpf, pid, links).is_empty());
        let (sender, receiver) = std::sync::mpsc::channel::<(usize, i64)>();
        let (reply, replies) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            for (queue, latch) in receiver {
                qixia_test_frame_callback(queue, 0x1234_5678_9abc_def0, latch);
                reply.send(()).unwrap();
            }
        });
        // 单个进程探针必须覆盖不同线程，不读取或改动任何应用的映射。
        {
            let result = crate::attach::attach_symbol_for_process(&mut ctx.bpf, pid, &executable, "qixia_test_frame_callback", offset);
            // ARM64 Linux 5.15 原生不支持 AArch32 指令探测。明确验证拒绝，
            // 不将内核不支持伪装成已验证 32 位帧采集；正式链会按现有路径降级。
            if cfg!(target_arch = "arm") && result.as_ref().err().is_some_and(|error| error.contains("os error 95")) {
                drop(sender);
                worker.join().unwrap();
                println!("当前内核拒绝 AArch32 uprobe（EOPNOTSUPP）；32 位逐帧采集未验证，需要降级");
                return;
            }
            let link = result.unwrap();
            ctx.pid_links.insert(pid, vec![link]);
        }
        ctx.target_tgids.insert(pid, if cfg!(target_pointer_width = "32") { 4 } else { 3 }, 0).unwrap();
        let base = monotonic_ns() - 3_000_000_000;
        ctx.pid_symbol_probes.get_mut(&pid).unwrap().candidate_started_ns = base;
        for (queue, fps) in [(0x1234, 165), (0x5678, 240)] {
            for index in 0..=fps * 2 {
                let timestamp = (base + index * 1_000_000_000 / fps) as i64;
                qixia_test_frame_callback(queue, 0x1234_5678_9abc_def0, timestamp);
                sender.send((queue, timestamp)).unwrap();
                replies.recv().unwrap();
                // 及时清空事件通道，防止此测试因故意的批量输入塞满 RingBuf。
                crate::events::poll_inner(&mut ctx).unwrap();
            }
            for invalid in [0, -1, (monotonic_ns() + 60_000_000_000) as i64, base as i64] {
                qixia_test_frame_callback(queue, 0x1234_5678_9abc_def0, invalid);
            }
            let value = ctx.frame_stats.as_ref().unwrap().get(&FrameStatsKey { pid, tid: 0, surface_ptr: queue as u64 }, 0).unwrap();
            assert_eq!(value.total_frames, fps * 2 + 1, "{filename}");
            assert_eq!(value.last_ts, base + 2_000_000_000, "{filename}");
            let report = ctx.frame_report.as_ref().unwrap().get(&FrameStatsKey { pid, tid: 0, surface_ptr: queue as u64 }, 0).unwrap();
            assert!((1_000_000_000 / fps..=1_000_000_000 / fps + 1).contains(&report.max_interval_ns), "{filename}");
        }
        drop(sender);
        worker.join().unwrap();
        println!("{filename}: 合成时间、跨线程去重、无效时间过滤通过");
    }
}
