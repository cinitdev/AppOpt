use crate::daemon_loop::model::{AppliedRound, PreparedRound, ScannedRound};
use crate::{base_package, DaemonState, ProcHit, Rule, RuleSource, RuntimeInputsCache, ScanPlan};
use std::collections::{BTreeSet, HashMap};

pub(super) fn report_round(
    state: &mut DaemonState,
    runtime: &RuntimeInputsCache,
    prepared: &PreparedRound,
    scanned: &ScannedRound,
    applied: &AppliedRound,
) {
    let PreparedRound {
        scan_clock,
        config_changed,
        full_scan,
        ..
    } = *prepared;
    let AppliedRound {
        detail_log,
        hit_preview_log,
        known_pids,
        processes,
        apply_elapsed,
        ..
    } = *applied;
    let stats = &applied.stats;
    let plan = &runtime.index.plan;
    let hits = &scanned.hits;
    let previous_known_pids = &scanned.previous_known_pids;
    let targeted_scan_packages = &prepared.targeted_scan_packages;
    let scan_complete = scanned.scan_complete;
    let scan_reason = scanned.scan_reason;
    let scan_elapsed = scanned.scan_elapsed;
    let round_start = prepared.round_start;
    state.round_index = state.round_index.saturating_add(1);
    let scanned_threads = hits.iter().map(|hit| hit.scanned_threads).sum::<usize>();
    let actions = hits.iter().map(|hit| hit.actions.len()).sum::<usize>();
    let process_actions = hits
        .iter()
        .flat_map(|hit| hit.actions.iter())
        .filter(|action| action.source == RuleSource::Process)
        .count();
    let thread_actions = actions.saturating_sub(process_actions);
    let process_rules = hits
        .iter()
        .map(|hit| hit.process_rules.len())
        .sum::<usize>();
    let should_log = detail_log;
    if should_log {
        let level = if stats.failed > 0 || stats.invalid_rules > 0 {
            crate::event_log::Level::Error
        } else if !scan_complete || stats.mismatched > 0 || stats.restricted > 0 {
            crate::event_log::Level::Warning
        } else { crate::event_log::Level::Info };
        crate::event_log::emit(level, format_args!(
            "[RS] 运行摘要: 轮次={} 模式={} 扫描完整={} 原因={} 配置变更={} 目标包={} 已知PID={} 命中进程={} 扫描线程={} 进程规则={} 线程规则命中={} 进程规则应用={} 已应用={} 已跳过={} 系统限制={} 失败={} 无效规则={} 抢写={} 扫描耗时={}ms 应用耗时={}ms 总耗时={}ms",
            state.round_index,
            if full_scan {
                "全量扫描"
            } else if !targeted_scan_packages.is_empty() {
                "包级扫描"
            } else {
                "PID缓存"
            },
            if scan_complete { "是" } else { "否" },
            scan_reason,
            if config_changed { "是" } else { "否" },
            plan.package_count(),
            known_pids,
            processes,
            scanned_threads,
            process_rules,
            thread_actions,
            process_actions,
            stats.applied,
            stats.skipped,
            stats.restricted,
            stats.failed,
            stats.invalid_rules,
            stats.mismatched,
            scan_elapsed.as_millis(),
            apply_elapsed.as_millis(),
            round_start.elapsed().as_millis()
        ));
        if stats.cpuset_failed > 0 {
            log_warn!("[RS] cpuset辅助写入失败: {}", stats.cpuset_failed);
        }
        if hit_preview_log && !hits.is_empty() {
            log_hit_preview(&hits, 5, &previous_known_pids);
        } else if hit_preview_log && !plan.is_empty() {
            log_info!(
                "[RS] 未命中任何进程: appId映射包={} 缺少映射包={}",
                plan.by_app_id.values().map(BTreeSet::len).sum::<usize>(),
                plan.fallback_pkgs.len()
            );
        }
        state.logged_round_once = true;
        state.last_runtime_summary_log_elapsed_ms = Some(scan_clock);
        state.last_logged_known_pids = known_pids;
        state.last_logged_processes = processes;
    }
}

pub(crate) fn log_config_summary(rules: &[Rule], uid_map: &HashMap<String, u32>, plan: &ScanPlan) {
    let active_rules = rules.iter().filter(|rule| !rule.auto).count();
    let auto_rules = rules.iter().filter(|rule| rule.auto).count();
    let mut owners = BTreeSet::new();
    let mut base_pkgs = BTreeSet::new();
    for rule in rules {
        owners.insert(rule.owner.as_str());
        if let Some(base) = base_package(&rule.owner) {
            base_pkgs.insert(base);
        }
    }
    let app_id_bound_pkgs = plan.by_app_id.values().map(BTreeSet::len).sum::<usize>();
    log_info!(
        "[RS] 规则加载完成: 规则={} auto={} 应用/进程={} 基础包={}",
        active_rules,
        auto_rules,
        owners.len(),
        base_pkgs.len()
    );
    log_info!(
        "[RS] 包名 UID 映射: 已加载 {} 个, appId快路径 {} 个, 缺少映射 {} 个",
        uid_map.len(),
        app_id_bound_pkgs,
        plan.fallback_pkgs.len()
    );
    log_info!(
        "[RS] 扫描计划: appId快路径=[{}] 缺少映射=[{}]",
        plan_app_id_preview(plan, 8),
        preview_set(&plan.fallback_pkgs, 8)
    );
}

pub(crate) fn plan_app_id_preview(plan: &ScanPlan, limit: usize) -> String {
    let mut rows = Vec::new();
    for (app_id, pkgs) in &plan.by_app_id {
        for pkg in pkgs {
            rows.push(format!("{pkg}:{app_id}"));
        }
    }
    rows.sort();
    preview_list(&rows, limit)
}

pub(crate) fn preview_set(values: &BTreeSet<String>, limit: usize) -> String {
    let rows = values.iter().cloned().collect::<Vec<_>>();
    preview_list(&rows, limit)
}

pub(crate) fn preview_list(values: &[String], limit: usize) -> String {
    if values.is_empty() {
        return "-".to_string();
    }
    let mut out = values
        .iter()
        .take(limit)
        .cloned()
        .collect::<Vec<_>>()
        .join(", ");
    if values.len() > limit {
        out.push_str(&format!(" ... +{}", values.len() - limit));
    }
    out
}

pub(crate) fn log_hit_preview(hits: &[ProcHit], limit: usize, previous_known_pids: &BTreeSet<i32>) {
    let shown = hits.len().min(limit);
    if hits.len() > limit {
        log_info!("[RS] 命中详情: 显示 {shown}/{} 个进程", hits.len());
    } else {
        log_info!("[RS] 命中详情: {} 个进程", hits.len());
    }
    let mut rows = hits.iter().collect::<Vec<_>>();
    rows.sort_by_key(|hit| (previous_known_pids.contains(&hit.pid), hit.pid));
    for hit in rows.into_iter().take(limit) {
        let process_actions = hit
            .actions
            .iter()
            .filter(|action| action.source == RuleSource::Process)
            .count();
        let thread_actions = hit.actions.len().saturating_sub(process_actions);
        log_info!(
            "[RS]   {}pid={} uid={} 进程={} 扫描线程={} 进程规则={} 线程规则={} 兜底线程={}",
            if previous_known_pids.contains(&hit.pid) {
                ""
            } else {
                "新进程 "
            },
            hit.pid,
            hit.uid,
            hit.cmdline,
            hit.scanned_threads,
            hit.process_rules.len(),
            thread_actions,
            process_actions
        );
    }
}
