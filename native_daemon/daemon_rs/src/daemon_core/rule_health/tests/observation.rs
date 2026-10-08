use super::model::RuleHealthLifecycle;
use super::observation::{rule_health_scan_pending_packages_at, update_rule_health_observation};
use super::rules::{rule_health_entry_from_rule, rule_health_entry_key};
use super::*;

const PKG: &str = "com.example";

fn add_rule(state: &mut RuleHealth, name: &str) -> String {
    let entry = rule_health_entry_from_rule(&Rule {
        owner: PKG.into(),
        thread: Some(name.into()),
        cpus: "0-3".into(),
        auto: false,
    })
    .unwrap();
    let key = rule_health_entry_key(&entry);
    state.entries.insert(key.clone(), entry);
    key
}

fn foreground(entered: u64, updated: u64) -> ForegroundState {
    ForegroundState {
        reliable: true,
        observable: true,
        interactive: Some(true),
        focused_package: PKG.into(),
        selection: "focused".into(),
        updated_elapsed_ms: updated,
        lifecycle_packages: HashMap::from([(
            PKG.into(),
            RuleHealthLifecycle {
                entered_elapsed_ms: entered,
                entered_wall_ms: 100_000 + entered,
            },
        )]),
        ..Default::default()
    }
}

fn evidence(at: u64) -> FullScanEvidence {
    FullScanEvidence {
        completed_at: at,
        global_complete: true,
        incomplete_packages: BTreeSet::new(),
        scanned_packages: None,
        observed_owners: BTreeSet::new(),
    }
}

fn tick(
    state: &mut RuleHealth,
    fg: &ForegroundState,
    now: u64,
    scan: Option<&FullScanEvidence>,
    new_keys: HashSet<String>,
) {
    let mut changed = false;
    let pending = BTreeSet::from([PKG.into()]);
    update_rule_health_observation(
        &pending,
        &new_keys,
        scan,
        fg,
        "test-boot",
        100 + now / 1000,
        now,
        state,
        &mut changed,
    );
}

#[test]
fn miss_requires_complete_scan_and_a_newer_foreground_snapshot() {
    let mut state = RuleHealth::default();
    let key = add_rule(&mut state, "RenderThread");
    tick(
        &mut state,
        &foreground(1_000, 1_000),
        1_000,
        None,
        HashSet::new(),
    );
    tick(
        &mut state,
        &foreground(1_000, 30_000),
        31_000,
        Some(&evidence(31_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&key].miss_count, 0);
    assert!(state.sessions[PKG].full_scan.is_some());
    tick(
        &mut state,
        &foreground(1_000, 32_000),
        32_000,
        None,
        HashSet::new(),
    );
    assert_eq!(state.entries[&key].miss_count, 1);
    tick(
        &mut state,
        &foreground(1_000, 70_000),
        70_000,
        Some(&evidence(70_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&key].miss_count, 1);
    tick(
        &mut state,
        &foreground(80_000, 80_000),
        80_000,
        None,
        HashSet::new(),
    );
    tick(
        &mut state,
        &foreground(80_000, 110_000),
        110_000,
        Some(&evidence(110_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&key].miss_count, 2);
    assert_eq!(state.entries[&key].status, RuleHealthStatus::Missed);
}

#[test]
fn incomplete_scans_do_not_miss_or_force_repeated_scans() {
    for global_failure in [true, false] {
        let mut state = RuleHealth::default();
        let key = add_rule(&mut state, "RenderThread");
        tick(
            &mut state,
            &foreground(1_000, 1_000),
            1_000,
            None,
            HashSet::new(),
        );
        assert_eq!(
            rule_health_scan_pending_packages_at(&state, 31_000),
            BTreeSet::from([PKG.into()])
        );
        let mut scan = evidence(31_000);
        if global_failure {
            scan.global_complete = false;
        } else {
            scan.incomplete_packages.insert(PKG.into());
        }
        tick(
            &mut state,
            &foreground(1_000, 31_000),
            31_000,
            Some(&scan),
            HashSet::new(),
        );
        assert_eq!(state.entries[&key].miss_count, 0);
        assert!(rule_health_scan_pending_packages_at(&state, 35_000).is_empty());
        // 后续常规完整扫描仍可完成本次窗口。
        tick(
            &mut state,
            &foreground(1_000, 40_000),
            40_000,
            Some(&evidence(40_000)),
            HashSet::new(),
        );
        assert_eq!(state.entries[&key].miss_count, 1);
    }
}

#[test]
fn another_packages_scan_cannot_complete_this_window() {
    let mut state = RuleHealth::default();
    let key = add_rule(&mut state, "RenderThread");
    tick(
        &mut state,
        &foreground(1_000, 1_000),
        1_000,
        None,
        HashSet::new(),
    );
    let mut scan = evidence(31_000);
    scan.scanned_packages = Some(BTreeSet::from(["com.other".into()]));
    tick(
        &mut state,
        &foreground(1_000, 31_000),
        31_000,
        Some(&scan),
        HashSet::new(),
    );
    assert_eq!(state.entries[&key].miss_count, 0);
    assert!(state.sessions[PKG].full_scan.is_none());
    assert!(!rule_health_scan_pending_packages_at(&state, 31_000).is_empty());
}

#[test]
fn unreliable_helper_cancels_the_window_until_a_new_lifecycle() {
    let mut state = RuleHealth::default();
    let key = add_rule(&mut state, "RenderThread");
    tick(
        &mut state,
        &foreground(1_000, 1_000),
        1_000,
        None,
        HashSet::new(),
    );
    tick(
        &mut state,
        &ForegroundState::default(),
        31_000,
        Some(&evidence(31_000)),
        HashSet::new(),
    );
    assert!(state.sessions[PKG].checked);
    assert!(state.sessions[PKG].full_scan.is_none());
    assert!(state.sessions[PKG].eligible_keys.is_empty());
    tick(
        &mut state,
        &foreground(1_000, 32_000),
        32_000,
        Some(&evidence(32_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&key].miss_count, 0);
    tick(
        &mut state,
        &foreground(40_000, 40_000),
        40_000,
        None,
        HashSet::new(),
    );
    assert!(!state.sessions[PKG].checked);
    assert_eq!(state.sessions[PKG].started, 40_000);
}

#[test]
fn reliable_exit_removes_all_session_evidence_without_a_miss() {
    let mut state = RuleHealth::default();
    let key = add_rule(&mut state, "RenderThread");
    tick(
        &mut state,
        &foreground(1_000, 1_000),
        1_000,
        None,
        HashSet::new(),
    );
    let exited = ForegroundState {
        reliable: true,
        exited_packages: HashMap::from([(PKG.into(), 31_000)]),
        ..Default::default()
    };
    tick(
        &mut state,
        &exited,
        32_000,
        Some(&evidence(31_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&key].miss_count, 0);
    assert!(!state.sessions.contains_key(PKG));
}

#[test]
fn new_rules_do_not_recount_already_checked_rules_in_the_same_lifecycle() {
    let mut state = RuleHealth::default();
    let old = add_rule(&mut state, "OldThread");
    tick(
        &mut state,
        &foreground(1_000, 1_000),
        1_000,
        None,
        HashSet::new(),
    );
    tick(
        &mut state,
        &foreground(1_000, 31_000),
        31_000,
        Some(&evidence(31_000)),
        HashSet::new(),
    );
    let new = add_rule(&mut state, "NewThread");
    tick(
        &mut state,
        &foreground(1_000, 40_000),
        40_000,
        None,
        HashSet::from([new.clone()]),
    );
    assert_eq!(
        state.sessions[PKG].eligible_keys,
        BTreeSet::from([new.clone()])
    );
    tick(
        &mut state,
        &foreground(1_000, 70_000),
        70_000,
        Some(&evidence(70_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&old].miss_count, 1);
    assert_eq!(state.entries[&new].miss_count, 1);
}

#[test]
fn adding_rules_during_observation_restarts_the_full_window() {
    let mut state = RuleHealth::default();
    let old = add_rule(&mut state, "OldThread");
    tick(
        &mut state,
        &foreground(1_000, 1_000),
        1_000,
        None,
        HashSet::new(),
    );
    let new = add_rule(&mut state, "NewThread");
    tick(
        &mut state,
        &foreground(1_000, 20_000),
        20_000,
        None,
        HashSet::from([new.clone()]),
    );
    assert_eq!(
        state.sessions[PKG].eligible_keys,
        BTreeSet::from([old.clone(), new.clone()])
    );
    tick(
        &mut state,
        &foreground(1_000, 31_000),
        31_000,
        Some(&evidence(31_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&old].miss_count, 0);
    tick(
        &mut state,
        &foreground(1_000, 50_000),
        50_000,
        Some(&evidence(50_000)),
        HashSet::new(),
    );
    assert_eq!(state.entries[&old].miss_count, 1);
    assert_eq!(state.entries[&new].miss_count, 1);
}

#[test]
fn migrated_wall_clock_rows_allow_a_new_lifecycle_after_clock_rollback() {
    let mut state = RuleHealth::default();
    let key = add_rule(&mut state, "RenderThread");
    state.entries.get_mut(&key).unwrap().last_checked_at = 200;
    tick(
        &mut state,
        &foreground(1_000, 1_000),
        1_000,
        None,
        HashSet::new(),
    );
    assert!(state.sessions[PKG].checked);
    assert_eq!(state.entries[&key].last_checked_boot_id, "test-boot");
    let mut next = foreground(40_000, 40_000);
    next.lifecycle_packages
        .get_mut(PKG)
        .unwrap()
        .entered_wall_ms = 90_000;
    tick(&mut state, &next, 40_000, None, HashSet::new());
    assert!(!state.sessions[PKG].checked);
    assert!(state.sessions[PKG].eligible_keys.contains(&key));
}
