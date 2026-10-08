use super::{model::RuleHealthLifecycle, RuleHealth};
use crate::runtime_clock::current_boot_id;
use crate::FOREGROUND_TASK_STATE_FILE;
use std::collections::{BTreeSet, HashMap};
use std::fs;

const FOREGROUND_TASK_MAX_AGE_MS: u64 = 25_000;
const FOREGROUND_DISCOVERY_DELAY_MS: u64 = 2_000;
const FOREGROUND_DISCOVERY_COOLDOWN_MS: u64 = 10_000;

#[derive(Debug, Default)]
pub(crate) struct ForegroundState {
    pub(super) interactive: Option<bool>,
    pub(super) reliable: bool,
    pub(super) observable: bool,
    pub(super) selection: String,
    pub(super) focused_package: String,
    pub(super) visible_packages: BTreeSet<String>,
    pub(super) lifecycle_packages: HashMap<String, RuleHealthLifecycle>,
    pub(super) exited_packages: HashMap<String, u64>,
    pub(super) updated_elapsed_ms: u64,
}

impl ForegroundState {
    pub(crate) fn interactive(&self) -> Option<bool> {
        self.interactive
    }

    pub(crate) fn focused_package(&self) -> Option<&str> {
        (self.reliable && self.observable && !self.focused_package.is_empty())
            .then_some(self.focused_package.as_str())
    }

    pub(super) fn can_start(&self, pkg: &str) -> bool {
        self.observable
            && matches!(self.selection.as_str(), "focused" | "default-visible")
            && self.focused_package == pkg
            && self.lifecycle_packages.contains_key(pkg)
    }

    pub(super) fn contains(&self, pkg: &str) -> bool {
        self.observable
            && self.lifecycle_packages.contains_key(pkg)
            && (self.focused_package == pkg || self.visible_packages.contains(pkg))
    }

    pub(super) fn lifecycle(&self, pkg: &str) -> Option<RuleHealthLifecycle> {
        self.reliable
            .then(|| self.lifecycle_packages.get(pkg).copied())
            .flatten()
    }

    pub(super) fn exited_at(&self, pkg: &str) -> Option<u64> {
        self.reliable
            .then(|| self.exited_packages.get(pkg).copied())
            .flatten()
    }
}

pub(crate) fn read_foreground_state(now_elapsed: u64) -> ForegroundState {
    let Ok(raw) = fs::read_to_string(FOREGROUND_TASK_STATE_FILE) else {
        return ForegroundState::default();
    };
    parse_foreground_state(&raw, now_elapsed, current_boot_id(now_elapsed))
}

pub(super) fn parse_foreground_state(
    raw: &str,
    now_elapsed: u64,
    boot: Option<&str>,
) -> ForegroundState {
    let mut version = 0u32;
    let mut boot_id = String::new();
    let mut status = String::new();
    let mut mode = String::new();
    let mut selection = String::new();
    let mut focused_package = String::new();
    let mut visible_packages = BTreeSet::new();
    let mut lifecycle_packages = HashMap::new();
    let mut exited_packages = HashMap::new();
    let mut updated_elapsed_ms = 0u64;
    let mut interactive = None;

    for line in raw.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "version" => version = value.trim().parse().unwrap_or(0),
            "boot_id" => boot_id = value.trim().to_string(),
            "status" => status = value.trim().to_string(),
            "mode" => mode = value.trim().to_string(),
            "selection" => selection = value.trim().to_string(),
            "focused_package" => focused_package = value.trim().to_string(),
            "visible_packages" => {
                visible_packages.extend(
                    value
                        .split(',')
                        .map(str::trim)
                        .filter(|pkg| !pkg.is_empty())
                        .map(str::to_string),
                );
            }
            "lifecycle_packages" => {
                for record in value
                    .split(',')
                    .map(str::trim)
                    .filter(|item| !item.is_empty())
                {
                    let Some((owner_and_elapsed, entered_wall)) = record.rsplit_once('@') else {
                        continue;
                    };
                    let Some((pkg, entered_elapsed)) = owner_and_elapsed.rsplit_once('@') else {
                        continue;
                    };
                    let (Ok(entered_elapsed_ms), Ok(entered_wall_ms)) =
                        (entered_elapsed.parse::<u64>(), entered_wall.parse::<u64>())
                    else {
                        continue;
                    };
                    if !pkg.is_empty() && entered_elapsed_ms > 0 && entered_wall_ms > 0 {
                        lifecycle_packages.insert(
                            pkg.to_string(),
                            RuleHealthLifecycle {
                                entered_elapsed_ms,
                                entered_wall_ms,
                            },
                        );
                    }
                }
            }
            "exited_packages" => {
                for record in value
                    .split(',')
                    .map(str::trim)
                    .filter(|item| !item.is_empty())
                {
                    let Some((pkg, elapsed)) = record.rsplit_once('@') else {
                        continue;
                    };
                    if let Ok(elapsed) = elapsed.parse::<u64>() {
                        exited_packages.insert(pkg.to_string(), elapsed);
                    }
                }
            }
            "updated_elapsed_ms" => updated_elapsed_ms = value.trim().parse().unwrap_or(0),
            "interactive" => {
                interactive = match value.trim() {
                    "1" => Some(true),
                    "0" => Some(false),
                    _ => None,
                };
            }
            _ => {}
        }
    }

    let fresh = updated_elapsed_ms > 0
        && now_elapsed >= updated_elapsed_ms
        && now_elapsed.saturating_sub(updated_elapsed_ms) <= FOREGROUND_TASK_MAX_AGE_MS;
    let boot_matches = boot == Some(boot_id.as_str());
    let identity_reliable = version >= 2 && boot_matches && fresh;
    let reliable =
        identity_reliable && mode == "listener" && matches!(status.as_str(), "ok" | "empty");
    if !reliable {
        return ForegroundState {
            interactive: identity_reliable.then_some(interactive).flatten(),
            ..ForegroundState::default()
        };
    }
    lifecycle_packages.retain(|_, lifecycle| {
        lifecycle.entered_elapsed_ms <= updated_elapsed_ms
            && lifecycle.entered_elapsed_ms <= now_elapsed
    });
    let observable = status == "ok"
        && !focused_package.is_empty()
        && matches!(
            selection.as_str(),
            "focused" | "default-visible" | "visible"
        );
    ForegroundState {
        interactive,
        reliable: true,
        observable,
        selection,
        focused_package,
        visible_packages,
        lifecycle_packages,
        exited_packages,
        updated_elapsed_ms,
    }
}

pub(super) fn foreground_discovery_scan_due(
    last_full_scan_elapsed_ms: Option<u64>,
    scope_pkg: Option<&str>,
    configured_packages: &BTreeSet<String>,
    state: &mut RuleHealth,
    foreground: &ForegroundState,
    now_elapsed: u64,
) -> Option<String> {
    state.foreground_scan_lifecycles.retain(|pkg, _| {
        scope_pkg.is_none_or(|scope| scope == pkg) && configured_packages.contains(pkg)
    });
    if configured_packages.is_empty() {
        return None;
    }
    let pkg = foreground.focused_package.as_str();
    if pkg.is_empty()
        || !foreground.can_start(pkg)
        || scope_pkg.is_some_and(|scope| scope != pkg)
        || !configured_packages.contains(pkg)
        || state.suspended_packages.contains(pkg)
    {
        return None;
    }
    let lifecycle = foreground.lifecycle(pkg)?;
    let pkg = pkg.to_string();
    if state
        .foreground_scan_lifecycles
        .get(&pkg)
        .is_some_and(|previous| *previous == lifecycle.entered_elapsed_ms)
    {
        return None;
    }
    let discovery_deadline = lifecycle
        .entered_elapsed_ms
        .saturating_add(FOREGROUND_DISCOVERY_DELAY_MS);
    if now_elapsed < discovery_deadline {
        return None;
    }
    if last_full_scan_elapsed_ms.is_some_and(|scanned_at| scanned_at >= discovery_deadline) {
        state
            .foreground_scan_lifecycles
            .insert(pkg, lifecycle.entered_elapsed_ms);
        return None;
    }
    if state
        .last_foreground_discovery_scan_elapsed_ms
        .is_some_and(|last| {
            now_elapsed >= last
                && now_elapsed.saturating_sub(last) < FOREGROUND_DISCOVERY_COOLDOWN_MS
        })
    {
        return None;
    }
    state
        .foreground_scan_lifecycles
        .insert(pkg.clone(), lifecycle.entered_elapsed_ms);
    state.last_foreground_discovery_scan_elapsed_ms = Some(now_elapsed);
    Some(pkg)
}
