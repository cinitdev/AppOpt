use super::model::{RuleHealthLifecycle, RuleHealthStatus};
use super::observation::*;
use super::rules::*;
use super::*;

fn pending_health_entry(owner: &str, thread: Option<&str>) -> RuleHealthEntry {
    let rule = Rule {
        owner: owner.to_string(),
        thread: thread.map(str::to_string),
        cpus: "4-7".to_string(),
        auto: false,
    };
    rule_health_entry_from_rule(&rule).unwrap()
}

#[test]
fn child_thread_miss_waits_until_its_process_owner_is_observed() {
    let main = pending_health_entry("com.example", Some("RenderThread"));
    let child_process = pending_health_entry("com.example:worker", None);
    let child_thread = pending_health_entry("com.example:worker", Some("Worker-*"));
    let main_key = rule_health_entry_key(&main);
    let child_process_key = rule_health_entry_key(&child_process);
    let child_thread_key = rule_health_entry_key(&child_thread);
    let eligible = BTreeSet::from([
        main_key.clone(),
        child_process_key.clone(),
        child_thread_key.clone(),
    ]);
    let mut state = RuleHealth::default();
    state.entries.insert(main_key.clone(), main);
    state
        .entries
        .insert(child_process_key.clone(), child_process);
    state.entries.insert(child_thread_key.clone(), child_thread);

    let result = finish_rule_health_observation(
        "com.example",
        &eligible,
        &BTreeSet::new(),
        100,
        None,
        "",
        &mut state,
    );

    assert_eq!(result, (2, 0));
    assert_eq!(state.entries[&main_key].miss_count, 1);
    assert_eq!(state.entries[&child_process_key].miss_count, 1);
    assert_eq!(state.entries[&child_thread_key].miss_count, 0);
    assert_eq!(
        state.entries[&child_thread_key].status,
        RuleHealthStatus::Pending
    );
}

#[test]
fn observed_child_process_allows_its_missing_thread_to_be_checked() {
    let child_thread = pending_health_entry("com.example:worker", Some("Worker-*"));
    let child_thread_key = rule_health_entry_key(&child_thread);
    let mut state = RuleHealth::default();
    state.entries.insert(child_thread_key.clone(), child_thread);

    let result = finish_rule_health_observation(
        "com.example",
        &BTreeSet::from([child_thread_key.clone()]),
        &BTreeSet::from(["com.example:worker".to_string()]),
        100,
        None,
        "",
        &mut state,
    );

    assert_eq!(result, (1, 0));
    assert_eq!(state.entries[&child_thread_key].miss_count, 1);
}

#[test]
fn cpu_range_change_reactivates_a_missed_rule() {
    let old_rule = Rule {
        owner: "com.example".to_string(),
        thread: Some("RenderThread".to_string()),
        cpus: "4-5".to_string(),
        auto: false,
    };
    let new_rule = Rule {
        cpus: "4-7".to_string(),
        ..old_rule.clone()
    };
    let mut existing = rule_health_entry_from_rule(&old_rule).unwrap();
    existing.status = RuleHealthStatus::Missed;
    existing.miss_count = 2;
    existing.last_checked_at = 123;
    let fresh = rule_health_entry_from_rule(&new_rule).unwrap();

    assert!(refresh_rule_health_entry(&mut existing, &fresh));
    assert_eq!(existing.status, RuleHealthStatus::Pending);
    assert_eq!(existing.miss_count, 0);
    assert_eq!(existing.last_checked_at, 0);
    assert_eq!(existing.rule_line, "com.example{RenderThread}=4-7");
}

#[test]
fn cpu_range_change_keeps_a_valid_rule_valid() {
    let old_rule = Rule {
        owner: "com.example".to_string(),
        thread: Some("RenderThread".to_string()),
        cpus: "4-5".to_string(),
        auto: false,
    };
    let new_rule = Rule {
        cpus: "4-7".to_string(),
        ..old_rule.clone()
    };
    let mut existing = rule_health_entry_from_rule(&old_rule).unwrap();
    existing.status = RuleHealthStatus::Valid;
    existing.last_matched_at = 123;
    existing.last_checked_at = 456;
    let fresh = rule_health_entry_from_rule(&new_rule).unwrap();

    assert!(refresh_rule_health_entry(&mut existing, &fresh));
    assert_eq!(existing.status, RuleHealthStatus::Valid);
    assert_eq!(existing.last_matched_at, 123);
    assert_eq!(existing.last_checked_at, 456);
    assert_eq!(existing.rule_line, "com.example{RenderThread}=4-7");
}

#[test]
fn unchanged_definitions_do_not_rebuild_rule_health_entries() {
    let rules = vec![Rule {
        owner: "com.example".to_string(),
        thread: Some("RenderThread".to_string()),
        cpus: "4-7".to_string(),
        auto: false,
    }];
    let mut state = RuleHealth::default();

    let (first_changed, first_new) = sync_rule_health_definitions(&rules, None, &mut state);
    assert!(first_changed);
    assert_eq!(first_new.len(), 1);

    let key = first_new.iter().next().unwrap().clone();
    let entry = state.entries.get_mut(&key).unwrap();
    entry.status = RuleHealthStatus::Valid;
    entry.last_matched_at = 123;

    let (second_changed, second_new) = sync_rule_health_definitions(&rules, None, &mut state);
    assert!(!second_changed);
    assert!(second_new.is_empty());
    let entry = state.entries.get(&key).unwrap();
    assert_eq!(entry.status, RuleHealthStatus::Valid);
    assert_eq!(entry.last_matched_at, 123);
}

#[test]
fn reset_request_only_reactivates_requested_missed_rules() {
    let make_entry = |owner: &str, status: RuleHealthStatus, miss_count: u32| {
        let rule = Rule {
            owner: owner.to_string(),
            thread: Some("RenderThread".to_string()),
            cpus: "4-7".to_string(),
            auto: false,
        };
        let mut entry = rule_health_entry_from_rule(&rule).unwrap();
        entry.status = status;
        entry.miss_count = miss_count;
        entry.first_observed_at = 11;
        entry.last_matched_at = 12;
        entry.last_checked_at = 22;
        entry.last_checked_boot_id = "boot".to_string();
        entry.last_checked_lifecycle_elapsed_ms = 33;
        entry
    };
    let requested_entry = make_entry("com.requested", RuleHealthStatus::Missed, 2);
    let pending_entry = make_entry("com.requested:pending", RuleHealthStatus::Pending, 1);
    let other_entry = make_entry("com.other", RuleHealthStatus::Missed, 2);
    let valid_entry = make_entry("com.requested:worker", RuleHealthStatus::Valid, 0);
    let requested_key = rule_health_entry_key(&requested_entry);
    let pending_key = rule_health_entry_key(&pending_entry);
    let other_key = rule_health_entry_key(&other_entry);
    let valid_key = rule_health_entry_key(&valid_entry);
    let mut state = RuleHealth::default();
    state.entries.insert(requested_key.clone(), requested_entry);
    state.entries.insert(pending_key.clone(), pending_entry);
    state.entries.insert(other_key.clone(), other_entry);
    state.entries.insert(valid_key.clone(), valid_entry);
    state.sessions.insert(
        "com.requested".into(),
        ObservationSession {
            started: 100,
            lifecycle: RuleHealthLifecycle {
                entered_elapsed_ms: 1,
                entered_wall_ms: 1,
            },
            checked: true,
            full_scan: None,
            full_scan_attempted: false,
            eligible_keys: BTreeSet::new(),
        },
    );

    let requested = BTreeSet::from(["com.requested".to_string()]);
    let (count, packages) = reset_rule_health_packages(&mut state, &requested);

    assert_eq!(count, 2);
    assert_eq!(packages, requested);
    let reset = state.entries.get(&requested_key).unwrap();
    assert_eq!(reset.status, RuleHealthStatus::Pending);
    assert_eq!(reset.miss_count, 0);
    assert_eq!(reset.first_observed_at, 0);
    assert_eq!(reset.last_matched_at, 0);
    assert_eq!(reset.last_checked_at, 0);
    assert!(reset.last_checked_boot_id.is_empty());
    assert_eq!(reset.last_checked_lifecycle_elapsed_ms, 0);
    assert_eq!(
        state.entries.get(&pending_key).unwrap().status,
        RuleHealthStatus::Pending
    );
    assert_eq!(state.entries.get(&pending_key).unwrap().miss_count, 0);
    assert_eq!(
        state.entries.get(&other_key).unwrap().status,
        RuleHealthStatus::Missed
    );
    assert_eq!(
        state.entries.get(&valid_key).unwrap().status,
        RuleHealthStatus::Valid
    );
    assert!(!state.sessions.contains_key("com.requested"));
    assert!(state.dirty);
    assert!(state.index_dirty);
}

#[test]
fn missed_rule_stays_in_read_only_health_index() {
    let rule = crate::parse_rule_key("com.example{RenderThread}", "4-7").unwrap();
    let mut state = crate::DaemonState::default();
    let mut health = rule_health_entry_from_rule(&rule).unwrap();
    health.status = RuleHealthStatus::Missed;
    state
        .rule_health
        .entries
        .insert(rule_health_entry_key(&health), health);

    let index = crate::build_runtime_rule_index(&[rule], &HashMap::new(), None, &state);
    assert!(index.active_rule_indices.is_empty());
    assert!(index.rules_by_owner.is_empty());
    assert_eq!(
        index.health_rules_by_owner.get("com.example").map(Vec::len),
        Some(1)
    );
    assert!(index.plan.all_pkgs.contains("com.example"));
}
