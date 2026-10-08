use crate::affinity::ensure_managed_tid_journal_loaded;
use crate::daemon_loop::cadence::{
    periodic_full_scan_due, regular_scan_due, update_automatic_foreground, update_interactive_mode,
};
use crate::daemon_loop::device::system_process_count;
use crate::daemon_loop::model::PreparedRound;
use crate::daemon_loop::report::log_config_summary;
use crate::{
    elapsed_realtime_ms, rule_health, Args, DaemonState, RuntimeFileChanges, RuntimeInputsCache,
    PID_GROWTH_HINT_MIN_MS, RULE_HEALTH_FULL_SCAN_RETRY_MS,
};
use std::collections::BTreeSet;
use std::io;
use std::time::Instant;

pub(super) fn prepare_round(
    args: &Args,
    state: &mut DaemonState,
    runtime: &mut RuntimeInputsCache,
    file_changes: RuntimeFileChanges,
    monitor_active: bool,
    automatic_changed: bool,
) -> io::Result<Option<PreparedRound>> {
    let round_start = Instant::now();
    ensure_managed_tid_journal_loaded(state, &args.cpuset_name)?;
    state.rule_health.suspend_packages(&state.auto_affinity_packages);
    if let Err(err) = state.rule_health.ensure_loaded() {
        log_error!("[RS] 规则健康状态读取失败，本轮不禁用任何规则: {err}");
    }
    let reset_rule_health_packages = match state.rule_health.consume_reset_request() {
        Ok(packages) => packages,
        Err(err) => {
            log_error!("[RS] 规则健康重新检测请求处理失败，将保留请求重试: {err}");
            BTreeSet::new()
        }
    };
    let rule_health_reset = !reset_rule_health_packages.is_empty();
    let scan_clock = elapsed_realtime_ms();
    let foreground_state = rule_health::read_foreground_state(scan_clock);
    // 助手首次启动或状态文件尚未写完时保守按亮屏处理；一旦拿到过可信值，
    // 助手状态短暂过期期间沿用最后状态，避免息屏被误判为亮屏而恢复 2 秒扫描。
    update_interactive_mode(state, foreground_state.interactive());
    let focused_package = foreground_state
        .focused_package()
        .filter(|_| state.interactive)
        .map(str::to_owned);
    update_automatic_foreground(state, focused_package.as_deref());
    let regular_scan_due = regular_scan_due(args.interval_secs, state, scan_clock);
    let refresh = runtime.refresh(args, state, file_changes, monitor_active, scan_clock)?;
    if refresh.index_rebuilt {
        // 规则健康停用/恢复不会改变配置文件指纹，但动作集合已经变化。清掉线程
        // 指纹快路径，确保本轮真实重建 actions，并能恢复刚失去规则的线程。
        state.process_scan_stamps.clear();
    }
    let rules = &runtime.rules;
    let uid_map = &runtime.uid_map;
    let index = &runtime.index;
    let plan = &index.plan;
    let config_key = runtime
        .config_key
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "配置文件没有可用内容指纹"))?;
    let uid_key = runtime.uid_map_key;
    let rule_config_changed = state.last_config_key != Some(config_key);
    let config_changed =
        rule_config_changed || state.last_uid_map_key != uid_key || automatic_changed;
    let foreground_event_round = file_changes.foreground && focused_package.is_some();
    if config_changed {
        log_config_summary(rules, uid_map, plan);
    }
    if rule_config_changed {
        for rule_line in state.rule_health.disabled_lines(rules) {
            log_warn!("[RS] 规则健康已停用: {rule_line}");
        }
    }
    let cache_uninitialized = !state.proc_scan_initialized;
    if !regular_scan_due
        && !config_changed
        && !cache_uninitialized
        && !rule_health_reset
        && !foreground_event_round
    {
        return Ok(None);
    }

    // 前台切换事件只触发目标包发现，不重置固定扫描节奏。
    if regular_scan_due || config_changed || cache_uninitialized || rule_health_reset {
        state.last_regular_scan_elapsed_ms = Some(scan_clock);
    }

    let proc_total = system_process_count();
    let proc_count_grew = matches!(
        (state.last_proc_total, proc_total),
        (Some(last), Some(current)) if current > last
    );
    let growth_hint_allowed = state.last_proc_growth_scan_elapsed_ms.is_none_or(|last| {
        scan_clock >= last && scan_clock.saturating_sub(last) >= PID_GROWTH_HINT_MIN_MS
    });
    if proc_count_grew && growth_hint_allowed {
        state.proc_growth_scan_pending = true;
    }
    let full_scan_retry_pending = state.last_full_scan_attempt_elapsed_ms.is_some();
    let full_scan_retry_allowed = state.last_full_scan_attempt_elapsed_ms.is_none_or(|last| {
        scan_clock >= last && scan_clock.saturating_sub(last) >= RULE_HEALTH_FULL_SCAN_RETRY_MS
    });
    let health_scan_packages = state.rule_health.scan_due_packages();
    let foreground_discovery_pkg = state.rule_health.discovery_scan_due(
        args.target_pkg.as_deref(),
        &plan.all_pkgs,
        &foreground_state,
        scan_clock,
        state.last_full_scan_elapsed_ms,
    );
    let mut targeted_scan_packages = health_scan_packages.clone();
    targeted_scan_packages.extend(reset_rule_health_packages.iter().cloned());
    if let Some(pkg) = &foreground_discovery_pkg {
        targeted_scan_packages.insert(pkg.clone());
    }
    let periodic_full_scan_due = periodic_full_scan_due(state, scan_clock);

    // Rust 版的核心优化点：
    // - 配置刚变化时必须全量扫，因为规则目标可能完全变了。
    // - 第一次启动时必须全量扫；全扫结果为空后也视为缓存已经初始化。
    // - 系统进程数增长只要求立即刷新轻量 PID 快照，不再因此全量读取 cmdline。
    // - 规则健康和前台生命周期只扫描对应包；PID 快照和短期候选复查覆盖日常进程变化。
    // - 已知进程按 10/30 秒节奏校验
    //   TID 指纹，集合未变化时不读取全部线程名和亲和性。
    // - 已确认空结果不会每轮重扫；新进程由 PID 快照差集和短期复查发现。
    let full_scan = config_changed
        || cache_uninitialized
        || ((full_scan_retry_pending || periodic_full_scan_due) && full_scan_retry_allowed);
    let scan_reason = if config_changed {
        "配置变更"
    } else if cache_uninitialized {
        "初始扫描"
    } else if rule_health_reset {
        "规则健康重新检测"
    } else if full_scan_retry_pending && full_scan {
        "不完整全扫重试"
    } else if periodic_full_scan_due && full_scan {
        if state.interactive {
            "亮屏周期恢复扫描"
        } else {
            "息屏周期恢复扫描"
        }
    } else if !health_scan_packages.is_empty() {
        "健康观察包级复核"
    } else if foreground_discovery_pkg.is_some() {
        "前台生命周期包级发现"
    } else {
        "PID缓存"
    };
    Ok(Some(PreparedRound {
        round_start,
        scan_clock,
        foreground_state,
        focused_package,
        config_key,
        uid_key,
        rule_config_changed,
        config_changed,
        index_rebuilt: refresh.index_rebuilt,
        foreground_event_round,
        proc_total,
        health_scan_packages,
        targeted_scan_packages,
        full_scan,
        scan_reason,
    }))
}
