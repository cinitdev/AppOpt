//! 规则健康状态的管理与公开入口。内部会话、记录行和持久化标志
//! 均不暴露给模块外部；调用方只需提供扫描依据。
mod foreground;
#[cfg(test)]
#[path = "tests/foreground.rs"]
mod foreground_tests;
mod model;
mod observation;
#[cfg(test)]
#[path = "tests/observation.rs"]
mod observation_tests;
mod rules;
mod storage;
#[cfg(test)]
#[path = "tests/storage.rs"]
mod storage_tests;
#[cfg(test)]
mod tests;

use crate::runtime_clock::{current_boot_id, unix_now_secs};
use crate::{base_package, FullScanEvidence, ProcHit, Rule};
use model::{ObservationSession, RuleHealthEntry, RuleHealthStatus};
use observation::{rule_health_scan_due_packages, update_rule_health_observation};
use rules::{
    collect_matched_rule_health_keys, rule_health_entry_in_scope, rule_health_status_is_observable,
    sync_rule_health_definitions,
};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::io;
use storage::{ensure_rule_health_loaded, finish_rule_health_update};

pub(crate) use foreground::{read_foreground_state, ForegroundState};
pub(crate) use rules::rule_health_key as key;

#[derive(Debug, Default)]
pub(crate) struct RuleHealth {
    entries: HashMap<String, RuleHealthEntry>,
    sessions: HashMap<String, ObservationSession>,
    suspended_packages: BTreeSet<String>,
    definitions_need_sync: bool,
    loaded: bool,
    dirty: bool,
    index_dirty: bool,
    last_health_full_scan_attempt_elapsed_ms: Option<u64>,
    foreground_scan_lifecycles: HashMap<String, u64>,
    last_foreground_discovery_scan_elapsed_ms: Option<u64>,
    paths: storage::Paths,
}

impl RuleHealth {
    /// 自动模式管理整个包，包括其后台进程和子进程。
    /// 取消进行中的观察，不计为未命中；
    /// 恢复静态规则时，仍可使用已保存的判断结果。
    pub(crate) fn suspend_packages(&mut self, packages: &BTreeSet<String>) {
        if self.suspended_packages == *packages {
            return;
        }
        self.suspended_packages.clone_from(packages);
        self.sessions.retain(|pkg, _| !packages.contains(pkg));
        self.foreground_scan_lifecycles
            .retain(|pkg, _| !packages.contains(pkg));
        // 检查暂停期间，规则定义可能已经被编辑。
        self.definitions_need_sync = true;
    }

    fn owner_suspended(&self, owner: &str) -> bool {
        base_package(owner).is_some_and(|pkg| self.suspended_packages.contains(pkg))
    }

    pub(crate) fn ensure_loaded(&mut self) -> io::Result<()> {
        ensure_rule_health_loaded(self)
    }

    pub(crate) fn consume_reset_request(&mut self) -> io::Result<BTreeSet<String>> {
        storage::consume_rule_health_reset_request(self)
    }

    pub(crate) fn index_dirty(&self) -> bool {
        self.index_dirty
    }

    pub(crate) fn index_rebuilt(&mut self) {
        self.index_dirty = false;
    }

    pub(crate) fn is_disabled(&self, rule: &Rule) -> bool {
        rules::rule_health_rule_disabled(rule, self)
    }

    pub(crate) fn disabled_lines(&self, rules: &[Rule]) -> Vec<String> {
        rules::disabled_rule_health_lines(rules, self)
    }

    pub(crate) fn scan_due_packages(&self) -> BTreeSet<String> {
        rule_health_scan_due_packages(self)
    }

    pub(crate) fn note_scan_attempt(&mut self, elapsed: u64) {
        self.last_health_full_scan_attempt_elapsed_ms = Some(elapsed);
    }

    pub(crate) fn discovery_scan_due(
        &mut self,
        scope: Option<&str>,
        configured: &BTreeSet<String>,
        foreground: &ForegroundState,
        now: u64,
        last_full_scan: Option<u64>,
    ) -> Option<String> {
        foreground::foreground_discovery_scan_due(
            last_full_scan,
            scope,
            configured,
            self,
            foreground,
            now,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn update(
        &mut self,
        rules: &[Rule],
        hits: &[ProcHit],
        full_scan: Option<&FullScanEvidence>,
        scope: Option<&str>,
        definitions_changed: bool,
        foreground: &ForegroundState,
        now: u64,
    ) -> io::Result<()> {
        update_rule_health(
            rules,
            hits,
            full_scan,
            scope,
            definitions_changed,
            foreground,
            now,
            self,
        )
    }
}

fn update_rule_health(
    rules: &[Rule],
    hits: &[ProcHit],
    full_scan_evidence: Option<&FullScanEvidence>,
    scope_pkg: Option<&str>,
    definitions_changed: bool,
    foreground: &ForegroundState,
    now_elapsed: u64,
    state: &mut RuleHealth,
) -> io::Result<()> {
    ensure_rule_health_loaded(state)?;

    let now_wall = unix_now_secs();
    let (mut changed, new_keys) = if definitions_changed || state.definitions_need_sync {
        state.definitions_need_sync = false;
        sync_rule_health_definitions(rules, scope_pkg, state)
    } else {
        (false, HashSet::new())
    };

    // 真实命中不要求位于观察窗口内。missed 仍保留只读匹配索引，因此目标以后
    // 重新出现时可以自动恢复；恢复后的下一轮才重新生成亲和性动作。
    let matched_keys = collect_matched_rule_health_keys(hits);
    for key in matched_keys {
        if state.entries.get(&key).is_some_and(|entry| state.owner_suspended(&entry.owner)) {
            continue;
        }
        let Some(entry) = state.entries.get_mut(&key) else {
            continue;
        };
        if !rule_health_entry_in_scope(entry, scope_pkg) || entry.status == RuleHealthStatus::Valid
        {
            continue;
        }
        if entry.status == RuleHealthStatus::Missed {
            log_info!("[RS] 规则健康已恢复: {}", entry.rule_line);
        } else {
            log_info!("[RS] 规则健康已确认: {}", entry.rule_line);
        }
        entry.status = RuleHealthStatus::Valid;
        entry.miss_count = 0;
        entry.last_matched_at = now_wall;
        entry.last_checked_at = now_wall;
        changed = true;
    }

    let pending_packages = state
        .entries
        .values()
        .filter(|entry| {
            rule_health_entry_in_scope(entry, scope_pkg)
                && !state.owner_suspended(&entry.owner)
                && rule_health_status_is_observable(entry.status)
        })
        .filter_map(|entry| base_package(&entry.owner).map(str::to_string))
        .collect::<BTreeSet<_>>();

    // 全部规则都已 valid/missed 时停止维护观察会话。
    if pending_packages.is_empty() {
        state.sessions.clear();
        return finish_rule_health_update(changed, state);
    }

    let current_boot_id = current_boot_id(now_elapsed).unwrap_or_default().to_string();

    update_rule_health_observation(
        &pending_packages,
        &new_keys,
        full_scan_evidence,
        foreground,
        &current_boot_id,
        now_wall,
        now_elapsed,
        state,
        &mut changed,
    );

    finish_rule_health_update(changed, state)
}
