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
                .unwrap()
        );
    }
    assert_eq!(
        readable_symbol_from_raw("missing"),
        "Surface::queueBuffer(未知符号)"
    );
}

#[test]
fn probe_summary_groups_no_frame_and_stalled_candidates() {
    let probe = PidSymbolProbe {
        candidate_index: 3,
        candidate_started_ns: 9,
        round_started_ns: 1,
        confirmed: false,
        confirmed_once: true,
        exhausted: true,
        attachable_mask: candidate_bit(0) | candidate_bit(2) | candidate_bit(3),
        no_frame_mask: candidate_bit(0) | candidate_bit(2),
        stalled_mask: candidate_bit(3),
        unavailable_mask: candidate_bit(1) | candidate_bit(4),
    };
    assert_eq!(
        probe_candidate_results(probe),
        "queueBuffer=0 | hook_queueBuffer=0 | queueBufferInternal=停帧"
    );
    assert_eq!(probe.attachable_mask.count_ones(), 3);
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
