use super::cadence::{pid_snapshot_interval_ms, regular_scan_interval_ms, update_automatic_foreground, update_interactive_mode};
use crate::{DaemonState, DEFAULT_INTERVAL_SECS, PID_SNAPSHOT_ACTIVE_MS, PID_SNAPSHOT_IDLE_MS, SCREEN_OFF_SCAN_INTERVAL_MS};

#[test]
fn stale_helper_keeps_last_known_screen_off_state() {
    let mut state = DaemonState::default();
    update_interactive_mode(&mut state, None);
    assert!(state.interactive);
    assert!(!state.interactive_known);

    update_interactive_mode(&mut state, Some(false));
    update_interactive_mode(&mut state, None);
    assert!(!state.interactive);
    assert!(state.interactive_known);

    update_interactive_mode(&mut state, Some(true));
    assert!(state.interactive);
}

#[test]
fn idle_daemon_uses_slow_scan_until_a_target_or_candidate_exists() {
    let mut state = DaemonState {
        interactive: true,
        ..DaemonState::default()
    };
    assert_eq!(
        regular_scan_interval_ms(DEFAULT_INTERVAL_SECS, &state),
        SCREEN_OFF_SCAN_INTERVAL_MS
    );

    state.process_index_has_candidates = true;
    assert_eq!(
        regular_scan_interval_ms(DEFAULT_INTERVAL_SECS, &state),
        DEFAULT_INTERVAL_SECS * 1000
    );

    state.process_index_has_candidates = false;
    state.known_pids.insert(42);
    assert_eq!(
        regular_scan_interval_ms(DEFAULT_INTERVAL_SECS, &state),
        DEFAULT_INTERVAL_SECS * 1000
    );
}

#[test]
fn automatic_foreground_refreshes_the_index_without_static_rule_hits() {
    let mut state = DaemonState {
        interactive: true,
        auto_affinity_packages: ["com.example".into()].into(),
        ..DaemonState::default()
    };
    update_automatic_foreground(&mut state, Some("com.example"));
    assert!(state.known_pids.is_empty());
    assert!(!state.process_index_has_candidates);
    assert_eq!(regular_scan_interval_ms(10, &state), 1_000);
    assert_eq!(pid_snapshot_interval_ms(&state), 1_000);

    for foreground in [Some("com.other"), None] {
        update_automatic_foreground(&mut state, foreground);
        assert!(!state.auto_affinity_foreground);
        assert_eq!(regular_scan_interval_ms(10, &state), SCREEN_OFF_SCAN_INTERVAL_MS);
        assert_eq!(pid_snapshot_interval_ms(&state), PID_SNAPSHOT_ACTIVE_MS);
    }
    state.known_pids.insert(42);
    assert_eq!(regular_scan_interval_ms(DEFAULT_INTERVAL_SECS, &state), DEFAULT_INTERVAL_SECS * 1000);
}

#[test]
fn screen_off_or_disabled_automatic_mode_restores_normal_discovery_cadence() {
    let mut state = DaemonState {
        interactive: true,
        auto_affinity_packages: ["com.example".into()].into(),
        ..DaemonState::default()
    };
    update_automatic_foreground(&mut state, Some("com.example"));
    update_interactive_mode(&mut state, Some(false));
    // 即使下次前台更新尚未清除标志，也应优先遵循亮灭屏状态。
    assert_eq!(regular_scan_interval_ms(1, &state), SCREEN_OFF_SCAN_INTERVAL_MS);
    assert_eq!(pid_snapshot_interval_ms(&state), PID_SNAPSHOT_IDLE_MS);
    update_automatic_foreground(&mut state, Some("com.example"));
    assert!(!state.auto_affinity_foreground);

    update_interactive_mode(&mut state, Some(true));
    update_automatic_foreground(&mut state, Some("com.example"));
    assert!(state.auto_affinity_foreground);
    state.auto_affinity_packages.clear();
    update_automatic_foreground(&mut state, Some("com.example"));
    assert!(!state.auto_affinity_foreground);
    assert_eq!(regular_scan_interval_ms(1, &state), SCREEN_OFF_SCAN_INTERVAL_MS);
    assert_eq!(pid_snapshot_interval_ms(&state), PID_SNAPSHOT_ACTIVE_MS);
}
