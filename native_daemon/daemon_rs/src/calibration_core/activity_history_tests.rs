use super::*;

fn thread_key(name: &str) -> TrackKey {
    TrackKey {
        owner: "com.example".into(),
        name: name.into(),
        is_process: false,
    }
}

fn loaded_record(key: &TrackKey, pct: f64, rounds: usize) -> LoadRecord {
    let mut record = LoadRecord::new(key, 0);
    for _ in 0..rounds {
        record.push(pct);
    }
    record
}

#[test]
fn ten_minute_thread_churn_saves_active_threads_and_keeps_rule_statistics() {
    let mut session = CalibSession::new("com.example".into(), Vec::new());
    for index in 0..400 {
        session.record_pct(thread_key(&format!("sleep-{index}")), 0.0);
    }
    assert!(
        session.records.is_empty(),
        "sleeping threads need no load curves"
    );

    for round in 0..1200 {
        session.rounds = round;
        for index in 0..16 {
            session.record_pct(thread_key(&format!("active-{index:02}")), 10.0);
        }
        // 2,400 个不同名称的短生命周期线程，各自仅出现一次短暂唤醒。
        for index in 0..2 {
            session.record_pct(thread_key(&format!("wake-{round}-{index}")), 2.0);
        }
        if (40..46).contains(&round) {
            session.record_pct(thread_key("scene-loader"), 40.0);
        }
        session.fill_missing_record_samples();
    }
    let records = session.records.into_values().collect::<Vec<_>>();
    assert!(records.len() <= CALIB_MAX_TRACKED_RECORDS);
    let before = records
        .iter()
        .map(|r| (r.sum_pct, r.max_pct, r.sample_count))
        .collect::<Vec<_>>();
    let saved = select_history_records(&records);
    assert_eq!(saved.len(), 17);
    assert!(saved
        .iter()
        .all(|r| r.name.starts_with("active-") || r.name == "scene-loader"));
    let active = saved.iter().find(|r| r.name == "active-00").unwrap();
    assert_eq!(
        (active.avg(), active.max_pct, active.sample_count),
        (10.0, 10.0, 1200)
    );
    let burst = saved.iter().find(|r| r.name == "scene-loader").unwrap();
    assert_eq!(
        (burst.avg(), burst.max_pct, burst.sample_count),
        (0.2, 40.0, 1200)
    );
    assert_eq!(
        before,
        records
            .iter()
            .map(|r| (r.sum_pct, r.max_pct, r.sample_count))
            .collect::<Vec<_>>()
    );
    assert!(
        records.iter().any(|r| r.name.starts_with("wake-")),
        "history filtering must not prune rule candidates"
    );
    let (history, count) = format_history(123, 1200, 1200, &saved, &HashMap::new())
        .unwrap()
        .unwrap();
    assert_eq!(count, 17);
    assert_eq!(history.lines().count(), 18);
    assert!(!history.contains("wake-") && !history.contains("sleep-"));
    assert!(history.len() < 120_000);
}

#[test]
fn late_activity_retains_zero_baseline_and_sleep_afterwards() {
    let mut session = CalibSession::new("com.example".into(), Vec::new());
    let key = thread_key("late-worker");
    session.rounds = 100;
    session.record_pct(key.clone(), 0.0);
    assert!(session.records.is_empty());
    for round in 100..103 {
        session.rounds = round;
        session.record_pct(key.clone(), 30.0);
    }
    session.rounds = 103;
    session.fill_missing_record_samples();
    let record = &session.records[&key];
    assert_eq!(record.sample_count, 104);
    assert_eq!(record.avg(), 90.0 / 104.0);
    assert!(record.activity.worth_saving(record.avg()));
    let series = record.series_values();
    assert!(series.iter().take(100).all(|v| *v == 0.0));
    assert_eq!(series.back(), Some(&0.0));
}

#[test]
fn filter_precedes_capacity_and_selection_is_stable() {
    let mut records = Vec::new();
    for index in 0..100 {
        records.push(loaded_record(
            &TrackKey {
                owner: format!("com.example:sleep-{index}"),
                name: String::new(),
                is_process: true,
            },
            2.0,
            1,
        ));
        records.push(loaded_record(
            &thread_key(&format!("active-{index:03}")),
            10.0,
            60,
        ));
    }
    let names = select_history_records(&records)
        .iter()
        .map(|r| r.name.clone())
        .collect::<Vec<_>>();
    assert_eq!(names.len(), 100);
    assert!(names.iter().all(|name| name.starts_with("active-")));
    records.reverse();
    assert_eq!(
        names,
        select_history_records(&records)
            .iter()
            .map(|r| r.name.clone())
            .collect::<Vec<_>>()
    );
}

#[test]
fn child_history_filters_incidental_wakes_and_preserves_all_active_members() {
    let mut records = Vec::new();
    let mut children = HashMap::new();
    for owner in 0..6 {
        let owner = format!("com.example:worker-{owner}");
        if records.len() < 5 {
            records.push(loaded_record(
                &TrackKey {
                    owner: owner.clone(),
                    name: String::new(),
                    is_process: true,
                },
                100.0,
                1200,
            ));
        }
        for index in 0..40 {
            let key = ChildThreadKey {
                owner: owner.clone(),
                name: format!("active-{index:02}"),
            };
            let mut summary = ChildThreadSummary::new(&key);
            for _ in 0..1200 {
                summary.push(2.0);
            }
            children.insert(key, summary);
        }
        let key = ChildThreadKey {
            owner: owner.clone(),
            name: "incidental-wake".into(),
        };
        let mut summary = ChildThreadSummary::new(&key);
        summary.push(100.0);
        children.insert(key, summary);
    }
    let saved = select_history_records(&records);
    let selected = select_history_child_threads(&saved, &children, 1200);
    assert_eq!(selected.len(), 200);
    assert!(selected
        .iter()
        .all(|r| r.name.starts_with("active-") && !r.owner.ends_with("-5")));
    for record in &saved {
        assert!(
            selected.iter().filter(|r| r.owner == record.owner).count()
                == 40
        );
    }
    let (text, rows) = format_history(123, 1200, 1200, &saved, &children)
        .unwrap()
        .unwrap();
    assert_eq!(rows, 5);
    assert_eq!(
        text.matches(",2.00,2.00").count(),
        200
    );
    assert!(!text.contains("incidental-wake"));
    assert!(text.contains("|v3p:"));
}

#[test]
fn idle_session_produces_no_empty_history() {
    let records = [loaded_record(&thread_key("occasional-wake"), 2.0, 1)];
    assert!(select_history_records(&records).is_empty());
    assert!(format_history(123, 60, 60, &[&records[0]], &HashMap::new())
        .unwrap()
        .is_none());
    // 空会话不尝试创建目录或重写已有历史。
    assert_eq!(
        write_history("com.example.empty", 60, 60, &[], &HashMap::new(), None).unwrap(),
        0
    );
}
