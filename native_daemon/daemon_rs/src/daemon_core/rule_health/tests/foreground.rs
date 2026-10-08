use super::foreground::parse_foreground_state;
use super::*;

fn raw() -> String {
    "version=2\nboot_id=boot\nmode=listener\nstatus=ok\ninteractive=1\nselection=focused\nfocused_package=com.example\nvisible_packages=com.visible\nlifecycle_packages=com.example@1000@101000,com.visible@900@100900\nupdated_elapsed_ms=2000\n".into()
}

#[test]
fn stale_wrong_boot_and_unreliable_foreground_never_open_observation_windows() {
    let good = raw();
    for (text, now, boot) in [
        (good.clone(), 28_000, Some("boot")),
        (good.clone(), 1_999, Some("boot")),
        (good.clone(), 2_000, Some("other")),
        (good.clone(), 2_000, None),
        (good.replace("version=2", "version=1"), 2_000, Some("boot")),
        (
            good.replace("mode=listener", "mode=polling"),
            2_000,
            Some("boot"),
        ),
        (
            good.replace("status=ok", "status=error"),
            2_000,
            Some("boot"),
        ),
    ] {
        let fg = parse_foreground_state(&text, now, boot);
        assert!(!fg.reliable);
        assert!(!fg.can_start("com.example"));
        assert!(fg.focused_package().is_none());
    }
}

#[test]
fn visible_apps_can_continue_but_only_the_selected_app_can_start() {
    let fg = parse_foreground_state(&raw(), 2_000, Some("boot"));
    assert!(fg.can_start("com.example"));
    assert!(fg.contains("com.visible"));
    assert!(!fg.can_start("com.visible"));
    let off = raw()
        .replace("status=ok", "status=empty")
        .replace("interactive=1", "interactive=0")
        .replace("focused_package=com.example", "focused_package=");
    let fg = parse_foreground_state(&off, 2_000, Some("boot"));
    assert_eq!(fg.interactive(), Some(false));
    assert!(fg.reliable);
    assert!(!fg.observable);
}

#[test]
fn foreground_discovery_remains_once_per_lifecycle_with_a_global_cooldown() {
    let mut health = RuleHealth::default();
    let packages = BTreeSet::from(["com.example".into()]);
    let fg = parse_foreground_state(&raw(), 2_000, Some("boot"));
    assert_eq!(
        health.discovery_scan_due(None, &packages, &fg, 2_999, None),
        None
    );
    assert_eq!(
        health.discovery_scan_due(None, &packages, &fg, 3_000, None),
        Some("com.example".into())
    );
    assert_eq!(
        health.discovery_scan_due(None, &packages, &fg, 4_000, None),
        None
    );
    let next = raw()
        .replace("com.example@1000@101000", "com.example@5000@105000")
        .replace("updated_elapsed_ms=2000", "updated_elapsed_ms=5000");
    let fg = parse_foreground_state(&next, 5_000, Some("boot"));
    assert_eq!(
        health.discovery_scan_due(None, &packages, &fg, 7_000, None),
        None
    );
    assert_eq!(
        health.discovery_scan_due(None, &packages, &fg, 13_000, None),
        Some("com.example".into())
    );
    let mut fresh = RuleHealth::default();
    assert_eq!(
        fresh.discovery_scan_due(None, &packages, &fg, 13_000, Some(8_000)),
        None
    );
    assert_eq!(
        fresh.discovery_scan_due(None, &packages, &fg, 20_000, None),
        None
    );
}
