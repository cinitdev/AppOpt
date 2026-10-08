use super::*;
// 一次性调试命令。
//
// --scan-once 只输出会命中的进程/线程和规则，不写亲和性，适合真机诊断。
// --apply-once 会执行一次绑核后退出，适合排查某条规则是否能正常写入。
//
// 注意：这里会先从当前 /proc 快照完整重建临时索引，再只扫描预筛出的目标 PID。
// 临时索引不会写盘，调试结果仍反映当前系统状态，也避免重复枚举整个 /proc。
pub(super) fn run_once(args: &Args, apply: bool) -> io::Result<()> {
    let rules = parse_config(&args.config)?;
    let uid_map = parse_uid_map(&args.uid_map)?;
    let mut health_state = DaemonState::default();
    // 单次诊断命令必须遵循与常驻守护进程相同的包管理归属规则。
    // 尤其是 --apply-once，不能用已保存的静态规则
    // 覆盖自动分配正在管理的线程。
    health_state.auto_affinity_packages = match fs::read_to_string(auto_affinity::CONFIG) {
        Ok(text) => auto_affinity::packages(&text),
        Err(err) if err.kind() == io::ErrorKind::NotFound => BTreeSet::new(),
        Err(err) => return Err(err),
    };
    health_state.rule_health.suspend_packages(&health_state.auto_affinity_packages);
    if let Err(err) = health_state.rule_health.ensure_loaded() {
        eprintln!("[RS] 规则健康状态读取失败，本次调试不禁用任何规则: {err}");
    }
    let index = build_runtime_rule_index(
        &rules,
        &uid_map,
        args.target_pkg.as_deref(),
        &health_state,
    );

    println!(
        "[RS] 调试参数: 配置={} 运行规则={}/{} UID映射={} 目标包数={} 指定目标={} 模式={}",
        args.config.display(),
        index.active_rule_indices.len(),
        rules.len(),
        args.uid_map.display(),
        index.plan.package_count(),
        args.target_pkg.as_deref().unwrap_or("*"),
        if apply {
            "执行一次绑核"
        } else {
            "仅扫描"
        }
    );

    // --scan-once/--apply-once 只使用本次快照，不依赖或改写常驻缓存。
    let mut process_index = ProcessIndex {
        loaded: true,
        ..ProcessIndex::default()
    };
    let now_elapsed = elapsed_realtime_ms();
    let _ = refresh_process_index(&mut process_index, now_elapsed, true)?;
    let scan_result = scan_proc(
        &rules,
        &index,
        &BTreeSet::new(),
        &process_index,
    )?;
    if !scan_result.complete {
        eprintln!("[RS] 本次扫描存在瞬时读取缺口，正向命中可用，不能用于健康负向结论");
    }
    if !scan_result.health_incomplete_packages.is_empty() {
        eprintln!(
            "[RS] 本次扫描的包级健康读取缺口: {}",
            scan_result
                .health_incomplete_packages
                .iter()
                .cloned()
                .collect::<Vec<_>>()
                .join(", ")
        );
    }
    let hits = scan_result.hits;
    print_hits(&hits, apply);

    if apply {
        let mut managed_tids = HashMap::new();
        let stats = apply_hits(
            &hits,
            &mut managed_tids,
            ApplyPolicy {
                detail_log: true,
                cpuset_name: &args.cpuset_name,
                now_elapsed: elapsed_realtime_ms(),
                foreground_pids: &BTreeSet::new(),
                interactive: true,
                require_restore_baseline: false,
            },
        );
        println!(
            "[RS] 执行汇总: 命中进程={} 已应用={} 已跳过={} 系统限制={} 失败={} 无效规则={} 被系统改写={}",
            hits.len(),
            stats.applied,
            stats.skipped,
            stats.restricted,
            stats.failed,
            stats.invalid_rules,
            stats.mismatched
        );
    } else {
        println!("[RS] 命中进程数={}", hits.len());
    }

    Ok(())
}

pub(super) fn print_hits(hits: &[ProcHit], apply: bool) {
    if apply {
        return;
    }

    for hit in hits {
        let process_actions = hit
            .actions
            .iter()
            .filter(|action| action.source == RuleSource::Process)
            .count();
        let thread_actions = hit
            .actions
            .iter()
            .filter(|action| action.source == RuleSource::Thread)
            .count();

        println!(
            "[RS] 进程={} UID={} 名称={} 进程规则={} 进程规则线程={} 已扫描线程={} 线程规则命中={}",
            hit.pid,
            hit.uid,
            hit.cmdline,
            hit.process_rules.len(),
            process_actions,
            hit.scanned_threads,
            thread_actions
        );

        for rule in &hit.process_rules {
            println!("  进程规则 {rule} 作用线程={process_actions}");
        }
        for action in hit
            .actions
            .iter()
            .filter(|action| action.source == RuleSource::Thread)
        {
            println!(
                "  线程规则 线程={} 线程名={} 规则={} 核心={}",
                action.tid, action.name, action.rule, action.cpus
            );
        }
    }
}
