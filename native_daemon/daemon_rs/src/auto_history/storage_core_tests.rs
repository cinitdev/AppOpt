use super::*;
use crate::auto_history::core_events::{arm, event, Queue};

fn directory(tag: &str) -> PathBuf {
    let unique = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let directory =
        std::env::temp_dir().join(format!("qixia-core-{tag}-{}-{unique}", std::process::id()));
    fs::create_dir_all(&directory).unwrap();
    directory
}

#[test]
fn operations_during_storage_initialization_use_the_same_capture_start() {
    let dir = directory("arming");
    let queue = Mutex::new(Queue::default());
    let arming_ms = 1000;
    let (id, mut recording) = arm(&queue, "app", arming_ms, || {
        // 目录恢复和文件创建尚未完成时，调度器也能发布事件；
        // 从共同的准备时刻起，这些事件就属于有效记录。
        queue
            .try_lock()
            .unwrap()
            .publish("app", vec![event(arming_ms, "assign")]);
        Recording::new(&dir, "app", arming_ms)
    })
    .unwrap();
    assert_eq!(recording.start_ms(), arming_ms);
    let path = recording.path.with_extension("log.gz");
    let batch = queue.lock().unwrap().close(id);
    assert_eq!(batch.events.len(), 1);
    for event in batch.events {
        recording.core_event(&event).unwrap();
    }
    recording.finish(1200).unwrap();
    let text = read_text(path).unwrap();
    assert!(text.contains("start_ms\t1000\n"));
    assert!(text.contains("E\t1000\t1\t2\t3\t"));
    assert!(text.contains("\tassign\tqixia\t"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn core_event_limit_rotates_inside_a_batch_without_losing_its_tail() {
    let dir = directory("event-limit");
    let mut recording = Recording::new(&dir, "app", 1000).unwrap();
    let first_path = recording.path.with_extension("log.gz");
    // 批次跨越读取端上限。运行时逐个事件检查 full()，
    // 因此超出上限的首个事件应写入下一个文件。
    for index in 0..MAX_CORE_EVENTS + 3 {
        let event = event(
            1000 + index,
            if index % 2 == 0 { "assign" } else { "release" },
        );
        if recording.full() {
            assert_eq!(index, MAX_CORE_EVENTS);
            recording.rotate(&dir, event.timestamp_ms).unwrap();
        }
        recording.core_event(&event).unwrap();
    }
    let next_path = recording.path.with_extension("log.gz");
    assert_ne!(first_path, next_path);
    recording.finish(1000 + MAX_CORE_EVENTS + 3).unwrap();
    for (path, count) in [(first_path, MAX_CORE_EVENTS as usize), (next_path, 3)] {
        let text = read_text(path).unwrap();
        assert_eq!(
            text.lines().filter(|line| line.starts_with("E\t")).count(),
            count
        );
        assert!(text.contains("core_events_dropped\t0\n"));
        assert!(!text.contains("core_events_incomplete"));
    }
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn repeated_observations_do_not_consume_the_event_limit_and_missed_rotation_is_explicit() {
    let dir = directory("event-limit-guard");
    let mut recording = Recording::new(&dir, "app", 1000).unwrap();
    let observed = event(1100, "observe");
    recording.core_event(&observed).unwrap();
    recording.core_event(&observed).unwrap();
    assert_eq!(recording.core_events, 1);
    recording.core_events = MAX_CORE_EVENTS;
    assert!(recording.full());
    recording.core_event(&event(1200, "release")).unwrap();
    assert_eq!(recording.core_events, MAX_CORE_EVENTS);
    assert_eq!(recording.core_dropped, 1);
    assert!(recording.core_incomplete);
    recording.finish(1300).unwrap();
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn every_assignment_and_release_is_saved_with_explicit_masks_and_controller_errors() {
    let dir = directory("operations");
    let mut recording = Recording::new(&dir, "app", 1000).unwrap();
    let path = recording.path.with_extension("log.gz");
    recording.core_event(&event(1100, "assign")).unwrap();
    recording.core_event(&event(1150, "assign")).unwrap();
    let mut release = event(1200, "release");
    release.before = Some(128);
    release.after = Some(255);
    release.reason = "foreground_exit".into();
    recording.core_event(&release).unwrap();
    let mut error = event(1300, "error");
    error.pid = 0;
    error.tid = 0;
    error.start = 0;
    error.name.clear();
    error.before = None;
    error.after = None;
    error.running_cpu = None;
    error.average = None;
    error.source = "unknown".into();
    error.reason = "journal_failed".into();
    recording.core_event(&error).unwrap();
    recording.finish(1400).unwrap();
    let text = read_text(path).unwrap();
    assert!(text.starts_with(HEADER) && text.contains("core_events\t1\n"));
    assert_eq!(
        text.lines().filter(|line| line.starts_with("E\t")).count(),
        4
    );
    assert!(text.contains("\trelease\tqixia\t7\t0,1,2,3,4,5,6,7\t7\t12.5000\tforeground_exit\n"));
    assert!(text.contains("E\t1300\t0\t0\t0\t\terror\tunknown\t-\t-\t-\t-\tjournal_failed\n"));
    assert!(text.contains("core_events_dropped\t0\n"));
    assert!(!text.contains("core_events_incomplete"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn observations_deduplicate_only_unchanged_observations_and_do_not_suppress_operations() {
    let dir = directory("observations");
    let mut recording = Recording::new(&dir, "app", 1000).unwrap();
    let path = recording.path.with_extension("log.gz");
    let mut observed = event(1100, "observe");
    observed.source = "system".into();
    observed.name = "worker\t汉\n".into();
    recording.core_event(&observed).unwrap();
    observed.timestamp_ms = 1200;
    recording.core_event(&observed).unwrap();
    observed.running_cpu = Some(6);
    recording.core_event(&observed).unwrap();
    observed.source = "qixia".into();
    recording.core_event(&observed).unwrap();
    recording.core_event(&event(1300, "assign")).unwrap();
    recording.core_event(&observed).unwrap();
    recording.finish(1400).unwrap();
    let text = read_text(path).unwrap();
    assert_eq!(
        text.lines().filter(|line| line.starts_with("E\t")).count(),
        5
    );
    assert!(text.contains(&hex("worker\t汉\n".as_bytes())));
    assert!(text.contains("\tobserve\tqixia\t"));
    assert!(!text.contains("core_events_incomplete"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn queued_release_and_overflow_count_survive_session_close_to_disk() {
    let dir = directory("queued-close");
    let mut recording = Recording::new(&dir, "app", 1000).unwrap();
    let path = recording.path.with_extension("log.gz");
    let mut queue = Queue::default();
    let id = queue.open("app", 1000);
    queue.publish("app", (0..2050).map(|_| event(1100, "observe")).collect());
    queue.publish("app", vec![event(1200, "release")]);
    let batch = queue.close(id);
    recording.core_loss(batch.dropped, false);
    for event in batch.events {
        recording.core_event(&event).unwrap();
    }
    recording.finish(1300).unwrap();
    let text = read_text(path).unwrap();
    assert!(text.contains("\trelease\tqixia\t"));
    assert!(text.contains("core_events_dropped\t3\ncore_events_incomplete\t1\n"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn storage_capacity_rejection_is_visible_instead_of_silent() {
    let dir = directory("capacity");
    let mut recording = Recording::new(&dir, "app", 1000).unwrap();
    let path = recording.path.with_extension("log.gz");
    recording.core_event(&event(1100, "assign")).unwrap();
    recording.bytes = MAX_BYTES - 1025;
    recording.core_event(&event(1200, "release")).unwrap();
    recording.finish(1300).unwrap();
    let text = read_text(path).unwrap();
    assert_eq!(
        text.lines().filter(|line| line.starts_with("E\t")).count(),
        1
    );
    assert!(text.contains("core_events_dropped\t1\ncore_events_incomplete\t1\n"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn recovery_preserves_legacy_files_and_marks_partial_event_rows_incomplete() {
    let dir = directory("recover-core");
    let legacy = dir.join("auto_1000_1_0.tmp");
    fs::write(
        &legacy,
        format!("{HEADER}package\tapp\nstart_ms\t1000\nA\t1100\t1\t2\t3\t7\n"),
    )
    .unwrap();
    let modern = dir.join("auto_1000_1_1.tmp");
    fs::write(&modern, format!("{HEADER}package\tapp\nstart_ms\t1000\ncore_events\t1\nE\t1100\t1\t2\t3\t61\tassign\tqixia\t0,1\t1\t1\t5.0\tdemand\nE\t1200\tpartial")).unwrap();
    recover(&dir).unwrap();
    let old = read_text(legacy.with_extension("log.gz")).unwrap();
    assert!(old.contains("A\t1100") && !old.contains("core_events\t1"));
    let new = read_text(modern.with_extension("log.gz")).unwrap();
    assert!(!new.contains("partial"));
    assert!(new.contains("core_events_dropped\t1\ncore_events_incomplete\t1\n"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn starting_another_capture_does_not_recover_or_delete_a_still_open_closing_file() {
    let dir = directory("open-file");
    let mut old = Recording::new(&dir, "old", 1000).unwrap();
    old.core_event(&event(1100, "assign")).unwrap();
    let old_path = old.path.clone();
    let next = Recording::new(&dir, "new", 1200).unwrap();
    assert!(old_path.exists() && !old_path.with_extension("log.gz").exists());
    old.core_event(&event(1300, "release")).unwrap();
    old.finish(1400).unwrap();
    next.finish(1400).unwrap();
    assert!(read_text(old_path.with_extension("log.gz"))
        .unwrap()
        .contains("\trelease\tqixia\t"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn rotation_keeps_delayed_operation_timestamps_instead_of_dropping_queued_records() {
    let dir = directory("rotation");
    let mut recording = Recording::new(&dir, "app", 1000).unwrap();
    recording
        .device(&[("model".into(), "test model".into())])
        .unwrap();
    recording.core_event(&event(1100, "assign")).unwrap();
    let old_path = recording.path.with_extension("log.gz");
    recording.rotate(&dir, 2000).unwrap();
    let path = recording.path.with_extension("log.gz");
    recording.core_event(&event(1900, "release")).unwrap();
    recording.finish(2200).unwrap();
    assert!(read_text(old_path)
        .unwrap()
        .contains("\tassign\tqixia\t"));
    let text = read_text(path).unwrap();
    assert!(text.contains("start_ms\t2000\n"));
    assert!(text.contains("D\tmodel\ttest model\n"));
    assert!(text.contains("E\t1900\t1\t2\t3\t"));
    assert!(text.contains("\trelease\tqixia\t"));
    assert!(!text.contains("core_events_incomplete"));
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn recovery_keeps_a_rotated_segment_containing_only_a_delayed_release() {
    let dir = directory("recover-delayed-release");
    let delayed = dir.join("auto_2000_1_0.tmp");
    fs::write(&delayed, format!("{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t2000\ncore_events\t1\nE\t1900\t1\t2\t3\t61\trelease\tsystem\t7\t0,1,2,3,4,5,6,7\t-\t-\tforeground_left\n")).unwrap();
    let invalid_sample = dir.join("auto_2000_1_1.tmp");
    fs::write(
        &invalid_sample,
        format!("{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t2000\nF\t1900\t60\t17\n"),
    )
    .unwrap();
    let ancient = dir.join("auto_2000000_1_0.tmp");
    fs::write(&ancient, format!("{HEADER}source\tauto\npackage\tcom.game\nstart_ms\t2000000\ncore_events\t1\nE\t1900\t1\t2\t3\t61\trelease\tsystem\t7\t0\t-\t-\tforeground_left\n")).unwrap();
    recover(&dir).unwrap();
    let text = read_text(delayed.with_extension("log.gz")).unwrap();
    assert!(text.contains("E\t1900\t1\t2\t3\t61\trelease\tsystem"));
    assert!(text.contains("end_ms\t2001\nduration_ms\t1\nrecovered\t1"));
    assert!(text.contains("core_events_incomplete\t1\n"));
    assert!(!invalid_sample.exists() && !invalid_sample.with_extension("log.gz").exists());
    assert!(!ancient.exists() && !ancient.with_extension("log.gz").exists());
    fs::remove_dir_all(dir).unwrap();
}
