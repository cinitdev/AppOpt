use super::rules::{rule_health_entry_from_rule, rule_health_entry_key};
use super::storage::{load_rule_health, write_rule_health};
use super::*;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

struct Fixture {
    dir: PathBuf,
    health: RuleHealth,
}
impl Fixture {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "qixia-health-{}-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&dir).unwrap();
        let health = RuleHealth {
            paths: storage::Paths {
                state: dir.join("health.tsv"),
                reset: dir.join("health.reset"),
                claim: dir.join("health.reset.processing"),
            },
            ..Default::default()
        };
        Self { dir, health }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        for entry in fs::read_dir(&self.dir).unwrap().flatten() {
            let _ = fs::remove_file(entry.path());
            let _ = fs::remove_dir(entry.path());
        }
        let _ = fs::remove_dir(&self.dir);
    }
}

fn rule() -> Rule {
    Rule {
        owner: "com.example".into(),
        thread: Some("RenderThread".into()),
        cpus: "0-3".into(),
        auto: false,
    }
}

#[test]
fn old_rows_migrate_and_escaped_fields_round_trip_in_the_same_tsv_format() {
    let fixture = Fixture::new();
    let path = &fixture.health.paths.state;
    fs::write(
        path,
        "T\tcom.example\tRenderThread\tpending\t1\t10\t0\t11\tcom.example{RenderThread}=0-3\n",
    )
    .unwrap();
    let mut entries = load_rule_health(path).unwrap();
    let entry = entries.values_mut().next().unwrap();
    assert_eq!(entry.miss_count, 1);
    assert!(entry.last_checked_boot_id.is_empty());
    assert_eq!(entry.last_checked_lifecycle_elapsed_ms, 0);
    entry.rule_line = "com.example{Thread\\\t\n}=0-3".into();
    entry.last_checked_boot_id = "test-boot".into();
    entry.last_checked_lifecycle_elapsed_ms = 1234;
    write_rule_health(path, &entries).unwrap();
    let bytes = fs::read(path).unwrap();
    let reloaded = load_rule_health(path).unwrap();
    let row = reloaded.values().next().unwrap();
    assert_eq!(row.rule_line, "com.example{Thread\\\t\n}=0-3");
    assert_eq!(row.last_checked_boot_id, "test-boot");
    assert_eq!(row.last_checked_lifecycle_elapsed_ms, 1234);
    write_rule_health(path, &reloaded).unwrap();
    assert_eq!(fs::read(path).unwrap(), bytes);
}

#[test]
fn failed_reset_commit_keeps_the_request_and_index_invalidation_until_retry() {
    let mut fixture = Fixture::new();
    let state = &mut fixture.health;
    state.loaded = true;
    let mut entry = rule_health_entry_from_rule(&rule()).unwrap();
    entry.status = RuleHealthStatus::Missed;
    entry.miss_count = 2;
    state.entries.insert(rule_health_entry_key(&entry), entry);
    state
        .foreground_scan_lifecycles
        .insert("com.example".into(), 100);
    fs::write(&state.paths.reset, "com.example\n").unwrap();
    fs::create_dir(&state.paths.state).unwrap(); // 原子重命名必须失败。
    assert!(state.consume_reset_request().is_err());
    assert!(state.paths.claim.exists());
    assert!(state.dirty && state.index_dirty());
    assert!(!state.is_disabled(&rule()));
    assert!(state.foreground_scan_lifecycles.is_empty());
    fs::remove_dir(&state.paths.state).unwrap();
    state.consume_reset_request().unwrap();
    assert!(!state.paths.claim.exists());
    assert!(!state.dirty);
    assert!(state.index_dirty());
    assert_eq!(
        load_rule_health(&state.paths.state)
            .unwrap()
            .values()
            .next()
            .unwrap()
            .status,
        RuleHealthStatus::Pending
    );
    state.index_rebuilt();
    assert!(!state.index_dirty());
}

#[test]
fn an_unread_reset_claim_is_processed_before_a_new_request() {
    let mut fixture = Fixture::new();
    let state = &mut fixture.health;
    state.loaded = true;
    for owner in ["com.example", "com.other"] {
        let mut r = rule();
        r.owner = owner.into();
        let mut entry = rule_health_entry_from_rule(&r).unwrap();
        entry.status = RuleHealthStatus::Missed;
        state.entries.insert(rule_health_entry_key(&entry), entry);
    }
    fs::write(&state.paths.claim, "com.example\n").unwrap();
    fs::write(&state.paths.reset, "com.other\n").unwrap();
    assert_eq!(
        state.consume_reset_request().unwrap(),
        BTreeSet::from(["com.example".into()])
    );
    assert_eq!(
        fs::read_to_string(&state.paths.reset).unwrap(),
        "com.other\n"
    );
    assert_eq!(
        state.consume_reset_request().unwrap(),
        BTreeSet::from(["com.other".into()])
    );
}

#[test]
fn real_hits_reenable_missed_rules_and_invalidate_the_runtime_index() {
    let mut fixture = Fixture::new();
    let state = &mut fixture.health;
    state.loaded = true;
    let r = rule();
    let mut entry = rule_health_entry_from_rule(&r).unwrap();
    entry.status = RuleHealthStatus::Missed;
    entry.miss_count = 2;
    let key = rule_health_entry_key(&entry);
    state.entries.insert(key.clone(), entry);
    let hit = ProcHit {
        pid: 1,
        pid_starttime: Some(1),
        uid: 10000,
        cmdline: "com.example".into(),
        process_rules: Vec::new(),
        actions: Vec::new(),
        matched_rule_health_keys: vec![key.clone()],
        scanned_threads: 1,
        health_scan_complete: false,
        thread_fingerprint: None,
    };
    state
        .update(
            &[r.clone()],
            &[hit],
            None,
            None,
            false,
            &ForegroundState::default(),
            1_000,
        )
        .unwrap();
    assert!(!state.is_disabled(&r));
    assert!(state.index_dirty());
    assert_eq!(state.entries[&key].status, RuleHealthStatus::Valid);
    assert_eq!(state.entries[&key].miss_count, 0);
}

#[test]
fn unchanged_empty_state_does_not_write_or_repeat_index_rebuilds() {
    let mut fixture = Fixture::new();
    let state = &mut fixture.health;
    state.ensure_loaded().unwrap();
    assert!(state.index_dirty());
    state.index_rebuilt();
    state
        .update(
            &[],
            &[],
            None,
            None,
            false,
            &ForegroundState::default(),
            1_000,
        )
        .unwrap();
    assert!(!state.index_dirty());
    assert!(!state.paths.state.exists());
}

fn active_foreground(pkg: &str, entered: u64, updated: u64) -> ForegroundState {
    ForegroundState {
        reliable: true,
        observable: true,
        interactive: Some(true),
        focused_package: pkg.into(),
        selection: "focused".into(),
        updated_elapsed_ms: updated,
        lifecycle_packages: HashMap::from([(
            pkg.into(), model::RuleHealthLifecycle {
                entered_elapsed_ms: entered,
                entered_wall_ms: 100_000 + entered,
            },
        )]),
        ..Default::default()
    }
}

#[test]
fn automatic_mode_cancels_inflight_checks_without_misses_and_resumes_a_fresh_window() {
    let mut fixture = Fixture::new();
    let state = &mut fixture.health;
    let rules = [rule(), Rule { owner: "com.example:worker".into(), ..rule() }];
    state.update(&rules, &[], None, None, true,
        &active_foreground("com.example", 1_000, 1_000), 1_000).unwrap();
    assert!(state.sessions.contains_key("com.example"));
    let original_bytes = fs::read(&state.paths.state).unwrap();
    state.suspend_packages(&BTreeSet::from(["com.example".into()]));
    assert!(state.sessions.is_empty());
    let evidence = FullScanEvidence {
        completed_at: 90_000,
        global_complete: true,
        incomplete_packages: BTreeSet::new(),
        scanned_packages: None,
        observed_owners: BTreeSet::from(["com.example".into(), "com.example:worker".into()]),
    };
    state.update(&rules, &[], Some(&evidence), None, true,
        &active_foreground("com.example", 1_000, 90_000), 90_000).unwrap();
    // 无论前台还是后台经过多久，都不计未命中，也不删除记录行。
    state.update(&rules, &[], Some(&evidence), None, false,
        &ForegroundState::default(), 100_000).unwrap();
    assert!(state.scan_due_packages().is_empty());
    assert!(state.discovery_scan_due(None, &BTreeSet::from(["com.example".into()]),
        &active_foreground("com.example", 1_000, 100_000), 100_000, None).is_none());
    assert_eq!(fs::read(&state.paths.state).unwrap(), original_bytes);
    assert_eq!(state.entries.len(), 2);
    assert!(state.entries.values().all(|entry| entry.miss_count == 0));
    state.suspend_packages(&BTreeSet::new());
    state.update(&rules, &[], Some(&evidence), None, false,
        &active_foreground("com.example", 1_000, 110_000), 110_000).unwrap();
    assert_eq!(state.sessions["com.example"].started, 110_000);
    assert!(state.entries.values().all(|entry| entry.miss_count == 0));
}

#[test]
fn suspended_checks_ignore_stale_hits_and_recheck_requests_without_touching_saved_decisions() {
    let mut fixture = Fixture::new();
    let state = &mut fixture.health;
    state.loaded = true;
    let rules = [rule(), Rule { owner: "com.other".into(), ..rule() }];
    for r in &rules {
        let mut entry = rule_health_entry_from_rule(r).unwrap();
        entry.status = RuleHealthStatus::Missed;
        entry.miss_count = 2;
        state.entries.insert(rule_health_entry_key(&entry), entry);
    }
    state.suspend_packages(&BTreeSet::from(["com.example".into()]));
    let hit = ProcHit {
        pid: 1, pid_starttime: Some(1), uid: 10000, cmdline: "com.example".into(),
        process_rules: Vec::new(), actions: Vec::new(),
        matched_rule_health_keys: vec![rule_health_entry_key(&rule_health_entry_from_rule(&rules[0]).unwrap())],
        scanned_threads: 1, health_scan_complete: false, thread_fingerprint: None,
    };
    state.update(&rules, &[hit], None, None, false, &ForegroundState::default(), 1_000).unwrap();
    assert!(state.is_disabled(&rules[0]));
    assert!(state.disabled_lines(&rules).iter().all(|line| !line.starts_with("com.example")));
    fs::write(&state.paths.reset, "com.example\ncom.other\n").unwrap();
    assert_eq!(state.consume_reset_request().unwrap(), BTreeSet::from(["com.other".into()]));
    let saved = state.entries.get(&rule_health_entry_key(&rule_health_entry_from_rule(&rules[0]).unwrap())).unwrap();
    assert_eq!(saved.status, RuleHealthStatus::Missed);
    assert_eq!(saved.miss_count, 2);
    assert!(!state.is_disabled(&rules[1]));
}

#[test]
fn static_definition_edits_wait_for_automatic_mode_to_end() {
    let mut fixture = Fixture::new();
    let state = &mut fixture.health;
    let original = rule();
    state.update(&[original.clone()], &[], None, None, true, &ForegroundState::default(), 1_000).unwrap();
    state.suspend_packages(&BTreeSet::from(["com.example".into()]));
    let edited = Rule { thread: Some("NewWorker".into()), cpus: "4-7".into(), ..rule() };
    state.update(&[edited.clone()], &[], None, None, true, &ForegroundState::default(), 2_000).unwrap();
    assert_eq!(state.entries.values().next().unwrap().rule_line, original.line());
    state.suspend_packages(&BTreeSet::new());
    // 文件指纹未再次变化，恢复检查时仍应同步此前的编辑。
    state.update(&[edited.clone()], &[], None, None, false, &ForegroundState::default(), 3_000).unwrap();
    assert_eq!(state.entries.len(), 1);
    assert_eq!(state.entries.values().next().unwrap().rule_line, edited.line());
}

#[test]
fn health_reset_rebuilds_the_cached_runtime_index_once_without_a_config_change() {
    let mut fixture = Fixture::new();
    let config = fixture.dir.join("applist.conf");
    let uid_map = fixture.dir.join("uid.map");
    fs::write(&config, "com.example{RenderThread}=0-3\n").unwrap();
    fs::write(&uid_map, "com.example=10000\n").unwrap();
    let args = crate::entry::parse_args(
        vec![
            "-c".into(),
            config.display().to_string(),
            "--uid-map".into(),
            uid_map.display().to_string(),
        ]
        .into_iter(),
    )
    .unwrap();
    fixture.health.loaded = true;
    let mut entry = rule_health_entry_from_rule(&rule()).unwrap();
    entry.status = RuleHealthStatus::Missed;
    entry.miss_count = 2;
    fixture
        .health
        .entries
        .insert(rule_health_entry_key(&entry), entry);
    let mut state = crate::DaemonState {
        rule_health: std::mem::take(&mut fixture.health),
        ..Default::default()
    };
    let mut cache = crate::RuntimeInputsCache::default();
    cache
        .refresh(
            &args,
            &mut state,
            crate::RuntimeFileChanges::default(),
            true,
            1_000,
        )
        .unwrap();
    assert!(cache.index.active_rule_indices.is_empty());
    fs::write(&state.rule_health.paths.reset, "com.example\n").unwrap();
    state.rule_health.consume_reset_request().unwrap();
    let refreshed = cache
        .refresh(
            &args,
            &mut state,
            crate::RuntimeFileChanges::default(),
            true,
            2_000,
        )
        .unwrap();
    assert!(!refreshed.config_changed);
    assert!(refreshed.index_rebuilt);
    assert_eq!(cache.index.active_rule_indices, vec![0]);
    assert!(
        !cache
            .refresh(
                &args,
                &mut state,
                crate::RuntimeFileChanges::default(),
                true,
                3_000
            )
            .unwrap()
            .index_rebuilt
    );
}
