use super::*;

fn directory(tag: &str) -> PathBuf {
    let unique = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let directory = std::env::temp_dir().join(format!(
        "qixia-capture-{tag}-{}-{unique}",
        std::process::id()
    ));
    fs::create_dir_all(&directory).unwrap();
    directory
}
fn logs(directory: &Path) -> Vec<PathBuf> {
    let mut paths: Vec<_> = fs::read_dir(directory)
        .unwrap()
        .flatten()
        .map(|e| e.path())
        .filter(|path| owned_file(path, "log"))
        .collect();
    paths.sort();
    paths
}
fn usage(capture: &mut Capture, ms: u64) {
    capture.usage.stopped_ms = Some(ms);
}

#[test]
fn rotation_keeps_pending_short_lived_threads_in_the_original_segment() {
    let dir = directory("pending-window");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    let mut pending = crate::auto_history::window::Window::default();
    pending.observe(
        180_900,
        vec![ThreadSample {
            pid: 1,
            tid: 2,
            start: 3,
            name: "brief".into(),
            percent: 12.5,
        }],
    );
    usage(&mut capture, 181_000);
    capture
        .rotate_with_threads(&dir, 182_000, &mut pending)
        .unwrap();
    assert!(pending.take().is_none());
    capture.fps(183_000, 60.0, 16.7).unwrap();
    capture.finish(184_000).unwrap();
    let completed = logs(&dir);
    assert_eq!(completed.len(), 2);
    let old = read_text(&completed[0]).unwrap();
    let new = read_text(&completed[1]).unwrap();
    assert!(old.contains("T\t180900\t1\t2\t3\t6272696566\t12.5000\n"));
    assert!(!new.contains("T\t180900\t"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn nearly_full_segment_preserves_all_4096_pending_threads_in_the_next_segment() {
    for force_rotation in [false, true] {
        let dir = directory(&format!("pending-hard-limit-{force_rotation}"));
        let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
        let filler = ThreadSample {
            pid: 1,
            tid: 2,
            start: 3,
            name: "x".repeat(128),
            percent: 5.0,
        };
        let row_bytes = thread_row(2_000, &filler).len() as u64;
        // 使用有效数据行填满真实负载，不只修改虚构的字节计数。
        while capture.recording.bytes + row_bytes <= MAX_BYTES - 1536 {
            capture
                .threads(2_000, std::slice::from_ref(&filler))
                .unwrap();
        }
        capture.recording.output.flush().unwrap();
        assert!(fs::metadata(&capture.recording.path).unwrap().len() > MAX_BYTES - 2048);
        let threads: Vec<_> = (0..4096)
            .map(|index| ThreadSample {
                pid: 10,
                tid: 10_000 + index,
                start: 99,
                name: format!("{}{:04}", "w".repeat(124), index),
                percent: 12.5,
            })
            .collect();
        let mut pending = crate::auto_history::window::Window::default();
        for chunk in threads.chunks(512) {
            pending.observe(180_900, chunk.to_vec());
        }
        usage(&mut capture, 181_000);
        if force_rotation {
            capture
                .rotate_with_threads(&dir, 182_000, &mut pending)
                .unwrap();
        } else {
            let (timestamp, batch) = pending.take().unwrap();
            capture.write_threads(&dir, timestamp, &batch).unwrap();
        }
        assert!(pending.take().is_none());
        capture.finish(184_000).unwrap();
        let completed = logs(&dir);
        assert_eq!(completed.len(), 2);
        let old = read_text(&completed[0]).unwrap();
        let new = read_text(&completed[1]).unwrap();
        assert!(old.len() as u64 <= MAX_BYTES);
        assert!(new.len() as u64 <= MAX_BYTES);
        assert!(new.contains("start_ms\t180900\n"));
        let saved: BTreeSet<_> = new.lines().filter(|line| line.starts_with("T\t")).collect();
        assert_eq!(saved.len(), 4096);
        for thread in &threads {
            let expected = thread_row(180_900, thread);
            assert!(
                saved.contains(expected.trim_end()),
                "missing tid {}",
                thread.tid
            );
        }
        assert!(!old.contains("T\t180900\t"));
        fs::remove_dir_all(dir).unwrap();
    }
}

#[test]
fn identity_capacity_preserves_the_entire_new_batch_during_both_flush_paths() {
    for (old_count, new_count) in [(4096, 4096), (4090, 100)] {
        for force_rotation in [false, true] {
            let dir = directory(&format!(
                "identities-{old_count}-{new_count}-{force_rotation}"
            ));
            let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
            let old: Vec<_> = (0..old_count)
                .map(|index| ThreadSample {
                    pid: 10,
                    tid: index + 1,
                    start: 99,
                    name: format!("old{index}"),
                    percent: 1.0,
                })
                .collect();
            capture.threads(2_000, &old).unwrap();
            assert_eq!(capture.recording.active.len(), old_count as usize);
            assert!(
                !capture.full(),
                "identity exhaustion must not rely on byte/time full()"
            );
            let new: Vec<_> = (0..new_count)
                .map(|index| ThreadSample {
                    pid: 10,
                    tid: index + 10_000,
                    start: 100,
                    name: format!("new{index}"),
                    percent: 12.5,
                })
                .collect();
            usage(&mut capture, 181_000);
            if force_rotation {
                let mut pending = crate::auto_history::window::Window::default();
                for chunk in new.chunks(512) {
                    pending.observe(180_900, chunk.to_vec());
                }
                capture
                    .rotate_with_threads(&dir, 182_000, &mut pending)
                    .unwrap();
                assert!(pending.take().is_none());
            } else {
                capture.write_threads(&dir, 180_900, &new).unwrap();
            }
            capture.finish(184_000).unwrap();
            let completed = logs(&dir);
            assert_eq!(completed.len(), 2);
            let saved = read_text(&completed[1]).unwrap();
            let rows: BTreeSet<_> = saved
                .lines()
                .filter(|line| line.starts_with("T\t"))
                .collect();
            assert_eq!(rows.len(), new_count as usize);
            for thread in &new {
                let expected = thread_row(180_900, thread);
                assert!(
                    rows.contains(expected.trim_end()),
                    "missing tid {}",
                    thread.tid
                );
            }
            fs::remove_dir_all(dir).unwrap();
        }
    }
}

#[test]
fn capacity_rotation_keeps_known_zero_samples_without_carrying_absent_identities() {
    let dir = directory("identities-known-zero");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    let old: Vec<_> = (1..=4096)
        .map(|tid| ThreadSample {
            pid: 10,
            tid,
            start: 99,
            name: format!("old{tid}"),
            percent: 1.0,
        })
        .collect();
    capture.threads(2_000, &old).unwrap();
    usage(&mut capture, 181_000);
    let batch = vec![
        ThreadSample {
            percent: 0.0,
            ..old[0].clone()
        },
        ThreadSample {
            pid: 10,
            tid: 10_000,
            start: 100,
            name: "new".into(),
            percent: 12.5,
        },
    ];
    capture.write_threads(&dir, 180_900, &batch).unwrap();
    assert_eq!(capture.recording.active.len(), 2);
    capture.finish(184_000).unwrap();
    let completed = logs(&dir);
    assert_eq!(completed.len(), 2);
    let saved = read_text(&completed[1]).unwrap();
    for thread in &batch {
        assert!(saved.contains(&thread_row(180_900, thread)));
    }
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn close_grace_does_not_qualify_a_short_visit_or_extend_its_duration() {
    let started = Instant::now();
    let mut clock = UsageClock {
        started,
        stopped_ms: None,
    };
    clock.stop(started + Duration::from_millis(179_999));
    assert_eq!(clock.at(started + Duration::from_millis(183_000)), 179_999);
    clock.stop(started + Duration::from_secs(200));
    assert_eq!(clock.at(started + Duration::from_secs(203)), 179_999);

    let dir = directory("grace");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    capture.fps(2_000, 60.0, 16.7).unwrap();
    usage(&mut capture, 179_999);
    // 应用离开前台后，释放操作仍可能到达。
    let mut release = crate::auto_history::core_events::event(183_999, "release");
    release.reason = "foreground_left".into();
    capture.core_event(&release).unwrap();
    capture.finish(184_000).unwrap();
    assert!(logs(&dir).is_empty());
    assert_eq!(fs::read_dir(&dir).unwrap().count(), 0);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn admission_is_strictly_more_than_three_minutes_and_keeps_the_beginning() {
    for elapsed in [179_999, 180_000, 180_001] {
        let dir = directory(&format!("boundary-{elapsed}"));
        let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
        capture.fps(2_000, 60.0, 16.7).unwrap();
        usage(&mut capture, elapsed);
        capture.finish(1_000 + elapsed).unwrap();
        let completed = logs(&dir);
        assert_eq!(completed.len(), usize::from(elapsed > 180_000));
        if elapsed > 180_000 {
            let text = read_text(&completed[0]).unwrap();
            assert!(text.contains("start_ms\t1000\n"));
            assert!(text.contains("F\t2000\t60.000\t16.700\n"));
            assert!(text.contains("foreground_ms\t180001\n"));
            assert!(text.contains("duration_ms\t180001\n"));
        } else {
            assert_eq!(fs::read_dir(&dir).unwrap().count(), 0);
        }
        fs::remove_dir_all(dir).unwrap();
    }
}

#[test]
fn short_rotations_share_the_visit_gate_and_are_published_together() {
    let dir = directory("segments");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    capture.fps(2_000, 60.0, 16.7).unwrap();
    usage(&mut capture, 60_000);
    capture.rotate(&dir, 61_000).unwrap();
    capture.fps(62_000, 59.0, 17.0).unwrap();
    usage(&mut capture, 120_000);
    capture.rotate(&dir, 121_000).unwrap();
    capture.fps(122_000, 58.0, 18.0).unwrap();
    assert!(logs(&dir).is_empty());
    recover(&dir).unwrap(); // 另一个应用启动时不能恢复仍开启的阶段文件。
    assert!(logs(&dir).is_empty());
    usage(&mut capture, 180_001);
    capture.checkpoint().unwrap();
    assert_eq!(logs(&dir).len(), 2);
    capture.finish(184_001).unwrap();
    let completed = logs(&dir);
    assert_eq!(completed.len(), 3);
    for path in completed {
        let text = read_text(path).unwrap();
        assert!(text.contains("foreground_ms\t180001\n"));
        assert!(!text.contains("duration_ms\t180001\n"));
    }
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn short_visit_discards_all_staged_segments_without_rotating_old_history() {
    let dir = directory("no-eviction");
    let mut previous = Vec::new();
    for index in 0..MAX_RECORDINGS {
        let path = dir.join(format!("auto_{}_1_0.log", index + 1));
        fs::write(&path, format!("old-{index}")).unwrap();
        previous.push((path, format!("old-{index}")));
    }
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    capture.fps(2_000, 60.0, 16.7).unwrap();
    usage(&mut capture, 80_000);
    capture.rotate(&dir, 81_000).unwrap();
    capture.fps(82_000, 60.0, 16.7).unwrap();
    usage(&mut capture, 180_000);
    capture.finish(184_000).unwrap();
    assert_eq!(logs(&dir).len(), MAX_RECORDINGS);
    assert_eq!(fs::read_dir(&dir).unwrap().count(), MAX_RECORDINGS);
    for (path, expected) in previous {
        assert_eq!(read_text(path).unwrap(), expected);
    }
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn staging_budget_failure_never_prunes_existing_records() {
    let dir = directory("budget");
    for index in 0..MAX_RECORDINGS {
        let path = dir.join(format!("auto_{}_1_0.log", index + 1));
        File::create(path).unwrap().set_len(MAX_BYTES).unwrap();
    }
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    capture.fps(2_000, 60.0, 16.7).unwrap();
    capture.output.flush().unwrap();
    usage(&mut capture, 60_000);
    assert!(capture.rotate(&dir, 61_000).is_err());
    capture.finish(64_000).unwrap();
    assert_eq!(logs(&dir).len(), MAX_RECORDINGS);
    assert!(logs(&dir)
        .iter()
        .all(|path| path.metadata().unwrap().len() == MAX_BYTES));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn recovery_uses_persisted_foreground_evidence_across_short_segments() {
    for elapsed in [179_999, 180_000, 180_001] {
        let dir = directory(&format!("recovery-{elapsed}"));
        let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
        capture.fps(2_000, 60.0, 16.7).unwrap();
        usage(&mut capture, 60_000);
        capture.rotate(&dir, 61_000).unwrap();
        capture.fps(62_000, 60.0, 16.7).unwrap();
        // 模拟崩溃前最后一个持久检查点，不发布文件。
        write_usage(&mut capture.recording, elapsed).unwrap();
        capture.output.flush().unwrap();
        drop(capture);
        recover(&dir).unwrap();
        assert_eq!(logs(&dir).len(), if elapsed > 180_000 { 2 } else { 0 });
        if elapsed > 180_000 {
            for path in logs(&dir) {
                let text = read_text(path).unwrap();
                assert!(text.contains("foreground_ms\t180001\n"));
                assert!(text.contains("core_events_incomplete\t1\n"));
            }
        }
        assert_eq!(fs::read_dir(&dir).unwrap().count(), logs(&dir).len());
        fs::remove_dir_all(dir).unwrap();
    }
}

#[test]
fn recovery_does_not_infer_usage_from_wall_clock_or_shutdown_release_events() {
    let dir = directory("recovery-clock");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    // 系统校时或延迟释放不能凭空增加前台时长。
    capture.fps(2_000_000, 60.0, 16.7).unwrap();
    write_usage(&mut capture.recording, 179_999).unwrap();
    capture.output.flush().unwrap();
    drop(capture);
    recover(&dir).unwrap();
    assert!(logs(&dir).is_empty());
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn interrupted_finish_keeps_the_proof_when_the_last_segment_is_empty() {
    let dir = directory("finish-proof");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    capture.fps(2_000, 60.0, 16.7).unwrap();
    usage(&mut capture, 60_000);
    capture.rotate(&dir, 61_000).unwrap();
    capture.fps(62_000, 60.0, 16.7).unwrap();
    usage(&mut capture, 120_000);
    capture.rotate(&dir, 121_000).unwrap();
    // 最后一段尚未收到数据行。在第一阶段完成后立即停止发布，
    // 此时第二阶段尚未收到自身的使用时长证据。
    let first_log = capture.pending[0].0.with_extension("log.gz");
    let second = capture.pending[1].0.clone();
    let held = second.with_extension("held");
    fs::rename(&second, &held).unwrap();
    usage(&mut capture, 180_001);
    assert!(capture.finish(184_001).is_err());
    assert!(first_log.exists());
    // App 可能已经导入并确认了首个已发布文件。
    fs::remove_file(first_log).unwrap();
    fs::rename(held, &second).unwrap();
    recover(&dir).unwrap();
    let text = read_text(second.with_extension("log.gz")).unwrap();
    assert!(text.contains("foreground_ms\t180001\n"));
    assert!(text.contains("F\t62000\t60.000\t16.700\n"));
    assert_eq!(logs(&dir).len(), 1);
    assert_eq!(fs::read_dir(&dir).unwrap().count(), 1);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn retry_after_published_archive_does_not_append_to_its_retained_source() {
    let dir = directory("published-retry");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    capture.fps(2_000, 60.0, 16.7).unwrap();
    usage(&mut capture, 60_000);
    capture.rotate(&dir, 61_000).unwrap();
    let source = capture.pending[0].0.clone();
    let archive = completed_path(&source);
    publish_sealed(&source, 200_000).unwrap();
    let compressed = fs::read(&archive).unwrap();
    // 重命名成功、明文源文件删除失败后，恰好会留下此状态。
    fs::write(&source, read_text(&archive).unwrap()).unwrap();
    usage(&mut capture, 210_000);
    capture.checkpoint().unwrap();
    assert!(capture.pending.is_empty() && !source.exists());
    assert_eq!(compressed, fs::read(&archive).unwrap());
    capture.finish(211_000).unwrap();
    assert_eq!(logs(&dir).len(), 1);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn more_than_seven_segments_of_one_capture_do_not_evict_its_beginning() {
    let dir = directory("long-capture");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    for segment in 0..10u64 {
        capture.fps(2_000 + segment * 200_000, 60.0, 16.7).unwrap();
        usage(&mut capture, (segment + 1) * 200_000);
        if segment < 9 {
            capture
                .rotate(&dir, 1_000 + (segment + 1) * 200_000)
                .unwrap();
        }
    }
    capture.finish(2_001_000).unwrap();
    let completed = logs(&dir);
    assert_eq!(completed.len(), 10);
    assert!(completed
        .iter()
        .any(|path| read_text(path).unwrap().contains("F\t2000\t")));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn retention_counts_captures_and_evicts_all_the_oldest_captures_segments() {
    let dir = directory("capture-retention");
    let mut first = Vec::new();
    for visit in 0..8u64 {
        let start = 1_000 + visit * 1_000_000;
        let mut capture = Capture::new(&dir, "com.game", start).unwrap();
        capture.fps(start + 1_000, 60.0, 16.7).unwrap();
        if visit == 0 {
            first.push(capture.path.with_extension("log.gz"));
        }
        usage(&mut capture, 181_000);
        capture.rotate(&dir, start + 181_000).unwrap();
        capture.fps(start + 181_100, 60.0, 16.7).unwrap();
        if visit == 0 {
            first.push(capture.path.with_extension("log.gz"));
        }
        usage(&mut capture, 182_000);
        capture.finish(start + 182_000).unwrap();
    }
    assert_eq!(logs(&dir).len(), 14);
    assert!(first.iter().all(|path| !path.exists()));
    let mut captures = BTreeMap::new();
    for path in logs(&dir) {
        *captures.entry(retention_key(&path).unwrap()).or_insert(0) += 1;
    }
    assert_eq!(captures.len(), 7);
    assert!(captures.values().all(|count| *count == 2));
    fs::remove_dir_all(dir).unwrap();
}

fn segment_file(dir: &Path, stamp: u64, capture: &str, extension: &str, bytes: u64) -> PathBuf {
    let path = dir.join(format!("auto_{stamp}_1_0.{extension}"));
    let text = format!("{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t{stamp}\nminimum_usage_ms\t180000\ncapture_id\t{capture}\nforeground_ms\t200000\nF\t{}\t60\t16.7\nend_ms\t{}\nduration_ms\t1000\n", stamp + 1, stamp + 1_000);
    fs::write(&path, &text).unwrap();
    if bytes > text.len() as u64 {
        OpenOptions::new()
            .write(true)
            .open(&path)
            .unwrap()
            .set_len(bytes)
            .unwrap();
    }
    path
}

#[test]
fn byte_budget_evicts_whole_old_captures_instead_of_only_enough_segments() {
    let dir = directory("capture-byte-retention");
    let old_a = segment_file(&dir, 1_000, "old_capture", "log", MAX_BYTES / 2);
    let old_b = segment_file(&dir, 2_000, "old_capture", "log", MAX_BYTES / 2);
    for index in 0..7 {
        segment_file(&dir, 10_000 + index, "new_capture", "log", MAX_BYTES);
    }
    segment_file(&dir, 20_000, "latest_capture", "log", 0);
    prune(&dir, false).unwrap();
    assert!(!old_a.exists() && !old_b.exists());
    assert_eq!(logs(&dir).len(), 8);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn a_full_active_capture_stops_rotation_without_erasing_its_saved_segments() {
    let dir = directory("active-capture-budget");
    let mut capture = Capture::new(&dir, "com.game", 1_000).unwrap();
    capture.fps(2_000, 60.0, 16.7).unwrap();
    let mut saved = Vec::new();
    for index in 0..7 {
        saved.push(segment_file(
            &dir,
            10_000 + index,
            &capture.capture_id,
            "log",
            MAX_BYTES - 1_024,
        ));
    }
    usage(&mut capture, 200_000);
    assert!(capture.rotate(&dir, 201_000).is_err());
    assert!(saved.iter().all(|path| path.exists()));
    // 运行时通过同一错误路径关闭未完整完成的采集。
    capture.core_loss(0, true);
    let tail = capture.path.with_extension("log.gz");
    capture.finish(201_000).unwrap();
    assert!(saved.iter().all(|path| path.exists()));
    assert!(read_text(tail)
        .unwrap()
        .contains("core_events_incomplete\t1\n"));
    let total: u64 = logs(&dir)
        .iter()
        .map(|path| path.metadata().unwrap().len())
        .sum();
    assert!(total <= TOTAL_BYTES);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn recovery_keeps_all_segments_of_one_admitted_capture() {
    let dir = directory("recover-many-segments");
    for index in 0..10 {
        segment_file(&dir, 1_000 + index, "one_capture", "tmp", 0);
    }
    recover(&dir).unwrap();
    assert_eq!(logs(&dir).len(), 10);
    assert!(logs(&dir).iter().all(|path| read_text(path)
        .unwrap()
        .contains("core_events_incomplete\t1\n")));
    fs::remove_dir_all(dir).unwrap();
}
