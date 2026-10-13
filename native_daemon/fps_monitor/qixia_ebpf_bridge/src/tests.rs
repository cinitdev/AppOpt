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
        let mut links = std::collections::HashMap::new();
        for tid in crate::procfs::collect_task_ids(pid).unwrap() {
            if let Ok(link) = crate::attach::attach_symbol_for_task(
                &mut state.bpf,
                pid,
                tid,
                &path,
                &symbol,
                offset,
            ) {
                links.insert(tid, vec![link]);
            }
        }
        assert!(!links.is_empty());
        state.pid_links.insert(pid, links);
        state.pid_symbol_offsets.insert(pid, offset);
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
    // 模拟无帧候选依次耗尽，验证暂停入口与目标集合的收尾一致。
    for _ in crate::constants::LIBGUI_FRAME_SYMBOLS {
        crate::probes::advance_symbol_probes(
            &mut ctx,
            monotonic_ns() + crate::constants::SYMBOL_PROBE_NS * 2,
        ).unwrap();
    }
    assert!(ctx.pid_symbol_probes[&pid].exhausted);
    assert!(ctx.target_tgids.get(&pid, 0).is_ok());
    crate::targets::sync_target_pids(&mut ctx, &[]).unwrap();
    assert_eq!(ctx.last_error.to_bytes(), b"");
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
        assert!(symbol.contains("BLASTBufferQueue19transactionCallback"));
        assert!(!symbol.contains("Committed"));
        let abi = crate::constants::frame_callback_abi(index);
        assert_eq!(symbol.contains("CallbackEx"), abi == 2);
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
pub extern "C" fn qixia_test_frame_callback(queue: usize, latch: i64) -> u64 {
    std::hint::black_box(queue as u64).wrapping_add(std::hint::black_box(latch as u64))
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
        let mut ctx = crate::loader::start_backend(&dir.with_file_name(filename), kind, pid as i32, None).unwrap();
        let links = ctx.pid_links.remove(&pid).unwrap();
        assert!(crate::attach::detach_pid_links(&mut ctx.bpf, pid, links).is_empty());
        let (sender, receiver) = std::sync::mpsc::channel::<(usize, i64)>();
        let (reply, replies) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            for (queue, latch) in receiver {
                qixia_test_frame_callback(queue, latch);
                reply.send(()).unwrap();
            }
        });
        // 把测试线程和新工作线程都挂上；不读取或改动任何应用的映射。
        let mut links = std::collections::HashMap::new();
        for tid in crate::procfs::collect_task_ids(pid).unwrap() {
            let result = crate::attach::attach_symbol_for_task(&mut ctx.bpf, pid, tid, &executable, "qixia_test_frame_callback", offset);
            // ARM64 Linux 5.15 原生不支持 AArch32 指令探测。明确验证拒绝，
            // 不将内核不支持伪装成已验证 32 位帧采集；正式链会按现有路径降级。
            if cfg!(target_arch = "arm") && result.as_ref().err().is_some_and(|error| error.contains("os error 95")) {
                drop(sender);
                worker.join().unwrap();
                println!("当前内核拒绝 AArch32 uprobe（EOPNOTSUPP）；32 位逐帧采集未验证，需要降级");
                return;
            }
            let link = result.unwrap();
            links.insert(tid, vec![link]);
        }
        ctx.pid_links.insert(pid, links);
        let base = monotonic_ns() - 3_000_000_000;
        ctx.pid_symbol_probes.get_mut(&pid).unwrap().candidate_started_ns = base;
        for (queue, fps) in [(0x1234, 165), (0x5678, 240)] {
            for index in 0..=fps * 2 {
                let timestamp = (base + index * 1_000_000_000 / fps) as i64;
                qixia_test_frame_callback(queue, timestamp);
                sender.send((queue, timestamp)).unwrap();
                replies.recv().unwrap();
                // 及时清空事件通道，防止此测试因故意的批量输入塞满 RingBuf。
                crate::events::poll_inner(&mut ctx).unwrap();
            }
            for invalid in [0, -1, (monotonic_ns() + 60_000_000_000) as i64, base as i64] {
                qixia_test_frame_callback(queue, invalid);
            }
            let value = ctx.frame_stats.as_ref().unwrap().get(&FrameStatsKey { pid, tid: 0, surface_ptr: queue as u64 }, 0).unwrap();
            assert_eq!(value.total_frames, fps * 2 + 1, "{filename}");
            assert_eq!(value.last_ts, base + 2_000_000_000, "{filename}");
        }
        drop(sender);
        worker.join().unwrap();
        println!("{filename}: 合成时间、跨线程去重、无效时间过滤通过");
    }
}
