//! 观察窗口只使用调用方提供的前台状态与扫描依据。
use super::model::{CompletedScan, ObservationSession, RuleHealthLifecycle, RuleHealthStatus};
use super::rules::{
    pending_rule_health_keys_for_package, rule_health_entry_checked_in_lifecycle,
    rule_health_status_is_observable,
};
use super::{ForegroundState, RuleHealth};
use crate::runtime_clock::elapsed_realtime_ms;
use crate::{base_package, FullScanEvidence, RULE_HEALTH_FULL_SCAN_RETRY_MS};
use std::collections::{BTreeSet, HashSet};

const RULE_HEALTH_OBSERVE_SECS: u64 = 30;

#[allow(clippy::too_many_arguments)]
pub(super) fn update_rule_health_observation(
    pending_packages: &BTreeSet<String>,
    new_keys: &HashSet<String>,
    full_scan_evidence: Option<&FullScanEvidence>,
    foreground: &ForegroundState,
    current_boot_id: &str,
    now_wall: u64,
    now_elapsed: u64,
    state: &mut RuleHealth,
    changed: &mut bool,
) {
    // 迁移旧版九列记录时，保留已检查过的生命周期判断。
    // 后续判断使用系统启动标识与单调时间身份。
    for (pkg, lifecycle) in &foreground.lifecycle_packages {
        if !pending_packages.contains(pkg) {
            continue;
        }
        for entry in state.entries.values_mut().filter(|entry| {
            base_package(&entry.owner) == Some(pkg.as_str())
                && rule_health_status_is_observable(entry.status)
                && entry.last_checked_boot_id.is_empty()
                && entry.last_checked_lifecycle_elapsed_ms == 0
                && entry.last_checked_at > 0
                && entry.last_checked_at.saturating_mul(1000) >= lifecycle.entered_wall_ms
        }) {
            if !current_boot_id.is_empty() {
                entry.last_checked_boot_id = current_boot_id.to_string();
                entry.last_checked_lifecycle_elapsed_ms = lifecycle.entered_elapsed_ms;
                *changed = true;
            }
        }
    }

    if let Some(evidence) = full_scan_evidence {
        for (pkg, session) in &mut state.sessions {
            let deadline = rule_health_observation_deadline(session.started);
            if evidence.completed_at < deadline
                || session
                    .full_scan
                    .as_ref()
                    .is_some_and(|scan| scan.at >= deadline)
                || !pending_packages.contains(pkg)
                || session.checked
                || evidence
                    .scanned_packages
                    .as_ref()
                    .is_some_and(|packages| !packages.contains(pkg))
            {
                continue;
            }
            if evidence.global_complete && !evidence.incomplete_packages.contains(pkg) {
                session.full_scan = Some(CompletedScan {
                    at: evidence.completed_at,
                    observed_owners: evidence
                        .observed_owners
                        .iter()
                        .filter(|owner| base_package(owner) == Some(pkg.as_str()))
                        .cloned()
                        .collect(),
                });
                session.full_scan_attempted = false;
            } else if !session.full_scan_attempted {
                session.full_scan_attempted = true;
                log_info!(
                    "[RS] 规则健康观察保留: 应用={} 原因=到期全扫不完整，本生命周期不再强制重扫",
                    pkg
                );
            }
        }
    }

    // 使用一张映射表管理整个生命周期，移除时不会在其他并行表中
    // 遗留待检查键、已完成的依据或已检查标志。
    state.sessions.retain(|pkg, session| {
        if !foreground.reliable {
            if !session.checked {
                session.checked = true;
                session.full_scan = None;
                session.eligible_keys.clear();
                log_info!("[RS] 规则健康观察取消: 应用={} 原因=前台 helper 暂不可用，本次启动不累计未命中", pkg);
            }
            return true;
        }
        let lifecycle_changed = Some(session.lifecycle) != foreground.lifecycle(pkg);
        let exited = foreground.exited_at(pkg).filter(|exited| {
            *exited > session.lifecycle.entered_elapsed_ms && *exited > session.started
        });
        if pending_packages.contains(pkg) && foreground.contains(pkg)
            && !lifecycle_changed && exited.is_none() { return true; }
        if !session.checked {
            let reason = if exited.is_some() { "前台生命周期中断" }
                else if lifecycle_changed { "前台生命周期已变化" }
                else { "应用已离开可靠前台范围" };
            log_info!("[RS] 规则健康观察取消: 应用={} 原因={}", pkg, reason);
        }
        false
    });

    for pkg in pending_packages {
        let Some(lifecycle) = foreground.lifecycle(pkg) else {
            continue;
        };
        let new_pkg_keys = new_keys
            .iter()
            .filter(|key| {
                state.entries.get(*key).is_some_and(|entry| {
                    base_package(&entry.owner) == Some(pkg.as_str())
                        && rule_health_status_is_observable(entry.status)
                        && !rule_health_entry_checked_in_lifecycle(
                            entry,
                            lifecycle,
                            current_boot_id,
                        )
                })
            })
            .cloned()
            .collect::<BTreeSet<_>>();
        if !state.sessions.contains_key(pkg) && foreground.can_start(pkg) {
            let eligible =
                pending_rule_health_keys_for_package(pkg, lifecycle, current_boot_id, state);
            *changed |= start_rule_health_observation(
                pkg,
                lifecycle,
                eligible,
                now_wall,
                now_elapsed,
                state,
            );
        } else if state.sessions.contains_key(pkg)
            && !new_pkg_keys.is_empty()
            && foreground.contains(pkg)
        {
            let mut eligible = state.sessions[pkg].eligible_keys.clone();
            eligible.extend(new_pkg_keys);
            *changed |= start_rule_health_observation(
                pkg,
                lifecycle,
                eligible,
                now_wall,
                now_elapsed,
                state,
            );
        }
    }

    let due = state
        .sessions
        .iter()
        .filter(|(pkg, session)| {
            let deadline = rule_health_observation_deadline(session.started);
            rule_health_observation_complete(session.started, now_elapsed)
                && foreground.contains(pkg)
                && !session.checked
                && session.full_scan.as_ref().is_some_and(|scan| {
                    scan.at >= deadline && foreground.updated_elapsed_ms >= scan.at
                })
        })
        .map(|(pkg, _)| pkg.clone())
        .collect::<Vec<_>>();
    for pkg in due {
        let (eligible, owners, lifecycle) = {
            let session = state
                .sessions
                .get_mut(&pkg)
                .expect("session selected above");
            let owners = session
                .full_scan
                .take()
                .map(|scan| scan.observed_owners)
                .unwrap_or_default();
            session.full_scan_attempted = false;
            session.checked = true;
            (
                std::mem::take(&mut session.eligible_keys),
                owners,
                session.lifecycle,
            )
        };
        let (first_miss, confirmed_miss) = finish_rule_health_observation(
            &pkg,
            &eligible,
            &owners,
            now_wall,
            Some(lifecycle),
            current_boot_id,
            state,
        );
        if first_miss + confirmed_miss > 0 {
            *changed = true;
            log_info!(
                "[RS] 规则健康观察结束: 应用={} 首次待复核={} 连续未命中={}，会话保持到应用离开",
                pkg, first_miss, confirmed_miss
            );
        }
    }
}

pub(super) fn rule_health_scan_due_packages(state: &RuleHealth) -> BTreeSet<String> {
    let now = elapsed_realtime_ms();
    if state
        .last_health_full_scan_attempt_elapsed_ms
        .is_some_and(|last| now < last || now.saturating_sub(last) < RULE_HEALTH_FULL_SCAN_RETRY_MS)
    {
        return BTreeSet::new();
    }
    rule_health_scan_pending_packages_at(state, now)
}

pub(super) fn rule_health_scan_pending_packages_at(
    state: &RuleHealth,
    now: u64,
) -> BTreeSet<String> {
    state
        .sessions
        .iter()
        .filter(|(_, session)| {
            let deadline = rule_health_observation_deadline(session.started);
            now >= deadline
                && !session.checked
                && !session.full_scan_attempted
                && session
                    .full_scan
                    .as_ref()
                    .is_none_or(|scan| scan.at < deadline)
        })
        .map(|(pkg, _)| pkg.clone())
        .collect()
}

fn rule_health_observation_deadline(started: u64) -> u64 {
    started.saturating_add(RULE_HEALTH_OBSERVE_SECS.saturating_mul(1000))
}

fn rule_health_observation_complete(started: u64, ended: u64) -> bool {
    ended >= started && ended - started >= RULE_HEALTH_OBSERVE_SECS * 1000
}

fn start_rule_health_observation(
    pkg: &str,
    lifecycle: RuleHealthLifecycle,
    eligible_keys: BTreeSet<String>,
    now_wall: u64,
    now_elapsed: u64,
    state: &mut RuleHealth,
) -> bool {
    let checked = eligible_keys.is_empty();
    state.sessions.insert(
        pkg.to_owned(),
        ObservationSession {
            started: now_elapsed,
            lifecycle,
            checked,
            full_scan: None,
            full_scan_attempted: false,
            eligible_keys: eligible_keys.clone(),
        },
    );
    if checked {
        log_info!(
            "[RS] 规则健康本生命周期已结算: 应用={}，等待下一次启动",
            pkg
        );
        return false;
    }
    let mut changed = false;
    for entry in state
        .entries
        .iter_mut()
        .filter(|(key, entry)| {
            eligible_keys.contains(*key)
                && base_package(&entry.owner) == Some(pkg)
                && rule_health_status_is_observable(entry.status)
        })
        .map(|(_, entry)| entry)
    {
        if entry.first_observed_at == 0 {
            entry.first_observed_at = now_wall;
            changed = true;
        }
    }
    log_info!(
        "[RS] 规则健康观察开始: 应用={} 窗口={}秒",
        pkg, RULE_HEALTH_OBSERVE_SECS
    );
    changed
}

pub(super) fn finish_rule_health_observation(
    pkg: &str,
    eligible_keys: &BTreeSet<String>,
    observed_owners: &BTreeSet<String>,
    now_wall: u64,
    lifecycle: Option<RuleHealthLifecycle>,
    current_boot_id: &str,
    state: &mut RuleHealth,
) -> (usize, usize) {
    let mut first_miss = 0usize;
    let mut confirmed_miss = 0usize;
    let deferred_child_threads = state
        .entries
        .iter()
        .filter(|(key, entry)| {
            eligible_keys.contains(*key)
                && base_package(&entry.owner) == Some(pkg)
                && rule_health_status_is_observable(entry.status)
                && entry.kind == 'T'
                && entry.owner.contains(':')
                && !observed_owners.contains(&entry.owner)
        })
        .count();
    if deferred_child_threads > 0 {
        log_info!(
            "[RS] 规则健康观察保留: 应用={} 子进程未出现，延后检查子线程规则={}条",
            pkg, deferred_child_threads
        );
    }
    for entry in state
        .entries
        .iter_mut()
        .filter(|(key, entry)| {
            eligible_keys.contains(*key)
                && base_package(&entry.owner) == Some(pkg)
                && rule_health_status_is_observable(entry.status)
                && (entry.kind != 'T'
                    || !entry.owner.contains(':')
                    || observed_owners.contains(&entry.owner))
        })
        .map(|(_, entry)| entry)
    {
        entry.miss_count = entry.miss_count.saturating_add(1);
        entry.status = if entry.miss_count >= 2 {
            confirmed_miss += 1;
            log_info!("[RS] 规则健康已停用: {}", entry.rule_line);
            RuleHealthStatus::Missed
        } else {
            first_miss += 1;
            RuleHealthStatus::Pending
        };
        entry.last_checked_at = now_wall;
        if let Some(lifecycle) = lifecycle.filter(|_| !current_boot_id.is_empty()) {
            entry.last_checked_boot_id = current_boot_id.to_string();
            entry.last_checked_lifecycle_elapsed_ms = lifecycle.entered_elapsed_ms;
        }
    }
    (first_miss, confirmed_miss)
}
