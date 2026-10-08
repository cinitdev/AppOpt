use super::{
    model::{RuleHealthEntry, RuleHealthLifecycle, RuleHealthStatus},
    RuleHealth,
};
use crate::{base_package, ProcHit, Rule, RuleSource};
use std::collections::{BTreeSet, HashMap, HashSet};

pub(super) fn rule_health_status_is_observable(status: RuleHealthStatus) -> bool {
    status == RuleHealthStatus::Pending
}

pub(super) fn rule_health_rule_in_scope(rule: &Rule, scope_pkg: Option<&str>) -> bool {
    scope_pkg.is_none_or(|pkg| base_package(&rule.owner) == Some(pkg))
}

pub(super) fn rule_health_entry_in_scope(entry: &RuleHealthEntry, scope_pkg: Option<&str>) -> bool {
    scope_pkg.is_none_or(|pkg| base_package(&entry.owner) == Some(pkg))
}

pub(super) fn pending_rule_health_keys_for_package(
    pkg: &str,
    lifecycle: RuleHealthLifecycle,
    current_boot_id: &str,
    state: &RuleHealth,
) -> BTreeSet<String> {
    state
        .entries
        .iter()
        .filter(|(_, entry)| {
            base_package(&entry.owner) == Some(pkg)
                && rule_health_status_is_observable(entry.status)
                && !rule_health_entry_checked_in_lifecycle(entry, lifecycle, current_boot_id)
        })
        .map(|(key, _)| key.clone())
        .collect()
}

pub(super) fn rule_health_entry_checked_in_lifecycle(
    entry: &RuleHealthEntry,
    lifecycle: RuleHealthLifecycle,
    current_boot_id: &str,
) -> bool {
    if !entry.last_checked_boot_id.is_empty() && entry.last_checked_lifecycle_elapsed_ms > 0 {
        return !current_boot_id.is_empty()
            && entry.last_checked_boot_id == current_boot_id
            && entry.last_checked_lifecycle_elapsed_ms == lifecycle.entered_elapsed_ms;
    }
    entry.last_checked_at > 0
        && entry.last_checked_at.saturating_mul(1000) >= lifecycle.entered_wall_ms
}

pub(super) fn reset_rule_health_packages(
    state: &mut RuleHealth,
    requested: &BTreeSet<String>,
) -> (usize, BTreeSet<String>) {
    if requested.is_empty() {
        return (0, BTreeSet::new());
    }
    let mut reset_count = 0usize;
    let mut reset_packages = BTreeSet::new();
    for entry in state.entries.values_mut() {
        let Some(pkg) = base_package(&entry.owner).map(str::to_owned) else {
            continue;
        };
        if !requested.contains(&pkg)
            || state.suspended_packages.contains(&pkg)
            || (entry.status == RuleHealthStatus::Valid
                || (entry.status == RuleHealthStatus::Pending && entry.miss_count == 0))
        {
            continue;
        }
        reset_rule_health_entry(entry);
        reset_count = reset_count.saturating_add(1);
        reset_packages.insert(pkg);
    }
    if reset_count == 0 {
        return (0, reset_packages);
    }
    state
        .sessions
        .retain(|pkg, _| !reset_packages.contains(pkg));
    state
        .foreground_scan_lifecycles
        .retain(|pkg, _| !reset_packages.contains(pkg));
    state.dirty = true;
    state.index_dirty = true;
    (reset_count, reset_packages)
}

pub(super) fn reset_rule_health_entry(entry: &mut RuleHealthEntry) {
    entry.status = RuleHealthStatus::Pending;
    entry.miss_count = 0;
    entry.first_observed_at = 0;
    entry.last_matched_at = 0;
    entry.last_checked_at = 0;
    entry.last_checked_boot_id.clear();
    entry.last_checked_lifecycle_elapsed_ms = 0;
}

pub(super) fn disabled_rule_health_lines(rules: &[Rule], state: &RuleHealth) -> Vec<String> {
    rules
        .iter()
        .filter(|rule| !state.owner_suspended(&rule.owner) && rule_health_rule_disabled(rule, state))
        .map(Rule::line)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

pub(super) fn rule_health_rule_disabled(rule: &Rule, state: &RuleHealth) -> bool {
    let Some(entry) = rule_health_entry_from_rule(rule) else {
        return false;
    };
    let key = rule_health_entry_key(&entry);
    state
        .entries
        .get(&key)
        .is_some_and(|health| health.status == RuleHealthStatus::Missed)
}

pub(super) fn sync_rule_health_definitions(
    rules: &[Rule],
    scope_pkg: Option<&str>,
    state: &mut RuleHealth,
) -> (bool, HashSet<String>) {
    let mut active_rules = HashMap::<String, RuleHealthEntry>::new();
    for rule in rules
        .iter()
        .filter(|rule| !rule.auto && rule_health_rule_in_scope(rule, scope_pkg)
            && !state.owner_suspended(&rule.owner))
    {
        let Some(entry) = rule_health_entry_from_rule(rule) else {
            continue;
        };
        active_rules.insert(rule_health_entry_key(&entry), entry);
    }

    let old_keys = state
        .entries
        .iter()
        .filter(|(_, entry)| rule_health_entry_in_scope(entry, scope_pkg)
            && !state.owner_suspended(&entry.owner))
        .map(|(key, _)| key.clone())
        .collect::<HashSet<_>>();
    let new_keys = active_rules
        .keys()
        .filter(|key| !old_keys.contains(*key))
        .cloned()
        .collect::<HashSet<_>>();
    let mut changed = old_keys.len() != active_rules.len();

    let suspended = &state.suspended_packages;
    state.entries.retain(|key, entry| {
        !rule_health_entry_in_scope(entry, scope_pkg)
            || base_package(&entry.owner).is_some_and(|pkg| suspended.contains(pkg))
            || active_rules.contains_key(key)
    });
    for (key, fresh) in active_rules {
        match state.entries.get_mut(&key) {
            Some(existing) => {
                if refresh_rule_health_entry(existing, &fresh) {
                    changed = true;
                }
            }
            None => {
                state.entries.insert(key, fresh);
                changed = true;
            }
        }
    }

    (changed, new_keys)
}

pub(super) fn refresh_rule_health_entry(
    existing: &mut RuleHealthEntry,
    fresh: &RuleHealthEntry,
) -> bool {
    if existing.rule_line == fresh.rule_line {
        return false;
    }
    // key 相同而规则行变化只可能是 CPU 范围变化。旧 missed 不能继续继承，
    // 否则用户修改核心范围后规则仍会被运行时索引过滤。
    existing.rule_line = fresh.rule_line.clone();
    if existing.status == RuleHealthStatus::Missed
        || (existing.status == RuleHealthStatus::Pending && existing.miss_count > 0)
    {
        reset_rule_health_entry(existing);
    }
    true
}

pub(super) fn rule_health_entry_from_rule(rule: &Rule) -> Option<RuleHealthEntry> {
    let (kind, target) = if let Some(thread) = &rule.thread {
        ('T', thread.clone())
    } else if rule.owner.contains(':') {
        ('P', String::new())
    } else {
        return None;
    };
    Some(RuleHealthEntry {
        kind,
        owner: rule.owner.clone(),
        target,
        status: RuleHealthStatus::Pending,
        miss_count: 0,
        first_observed_at: 0,
        last_matched_at: 0,
        last_checked_at: 0,
        last_checked_boot_id: String::new(),
        last_checked_lifecycle_elapsed_ms: 0,
        rule_line: rule.line(),
    })
}

pub(super) fn collect_matched_rule_health_keys(hits: &[ProcHit]) -> HashSet<String> {
    let mut keys = HashSet::new();
    for hit in hits {
        if hit.cmdline.contains(':') {
            keys.insert(rule_health_key('P', &hit.cmdline, ""));
        }
        keys.extend(hit.matched_rule_health_keys.iter().cloned());
        for action in hit
            .actions
            .iter()
            .filter(|action| action.source == RuleSource::Thread)
        {
            keys.extend(action.rule_health_keys.iter().cloned());
        }
    }
    keys
}

pub(super) fn rule_health_entry_key(entry: &RuleHealthEntry) -> String {
    rule_health_key(entry.kind, &entry.owner, &entry.target)
}

pub(crate) fn rule_health_key(kind: char, owner: &str, target: &str) -> String {
    format!(
        "{}\t{}\t{}",
        kind.to_ascii_uppercase(),
        owner.trim(),
        target.trim()
    )
}
