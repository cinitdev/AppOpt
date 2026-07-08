// 一次性调试命令。
//
// --scan-once 只输出会命中的进程/线程和规则，不写 affinity，适合在真机上和 C 版日志对照。
// --apply-once 会执行一次绑核后退出，适合排查某条规则是否能正常写入。
//
// 注意：这里故意直接调用 scan_proc 全量扫描，不使用 daemon 的 PID 缓存；调试命令要反映
// 当前系统瞬时状态，而不是依赖上一轮缓存。
fn run_once(args: &Args, apply: bool) -> io::Result<()> {
    let rules = parse_config(&args.config)?;
    let uid_map = parse_uid_map(&args.uid_map)?;
    let plan = build_scan_plan(&rules, &uid_map, args.target_pkg.as_deref());

    println!(
        "[RS] 调试参数: 配置={} 规则={} UID映射={} 目标包数={} 指定目标={} 模式={}",
        args.config.display(),
        rules.len(),
        args.uid_map.display(),
        plan.package_count(),
        args.target_pkg.as_deref().unwrap_or("*"),
        if apply { "执行一次绑核" } else { "仅扫描" }
    );

    let hits = scan_proc(&rules, &plan)?;
    print_hits(&hits, apply);

    if apply {
        let stats = apply_hits(&hits, true);
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

fn print_hits(hits: &[ProcHit], apply: bool) {
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
