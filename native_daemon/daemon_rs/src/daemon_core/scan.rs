// /proc 扫描与规则命中。
//
// 扫描分两层：
// 1. 进程层：先用 /proc/<pid> 目录 owner UID 和 cmdline 判断是否属于目标包。
// 2. 线程层：只有进程命中后，才进入 /proc/<pid>/task 读取 comm 并匹配线程规则。
//
// 这样保留了 C 版基于 /proc 的兼容性，同时避免无意义地读取全系统所有线程。
// 这里不要引入 cmd/pm/dumpsys 这类外部命令，守护进程长期运行时 fork 成本太高。
enum ProcessScanOutcome {
    Hit(ProcHit),
    Gone,
    NotTarget,
    Unreadable,
}

fn scan_proc(
    rules: &[Rule],
    plan: &ScanPlan,
    known_pids: &BTreeSet<i32>,
) -> io::Result<ProcScanResult> {
    if plan.is_empty() {
        return Ok(ProcScanResult {
            hits: Vec::new(),
            complete: true,
            health_incomplete_packages: BTreeSet::new(),
        });
    }
    // 全量扫描只枚举 /proc/<pid> 目录，不会直接扫全系统线程。
    // 只有 PID 通过 appId 快路径或严格 cmdline 包名兜底后，才进入 /proc/<pid>/task 扫线程。
    let rules_by_owner = build_rules_by_owner(rules);
    let mut hits = Vec::new();
    let mut complete = true;
    let mut health_incomplete_packages = BTreeSet::new();
    let proc_dir = Path::new("/proc");
    for entry in fs::read_dir(proc_dir)? {
        let entry = match entry {
            Ok(entry) => entry,
            Err(_) => {
                complete = false;
                continue;
            }
        };
        let file_name = entry.file_name();
        let Some(pid) = parse_pid(&file_name) else {
            continue;
        };
        match scan_process_path(pid, &entry.path(), &rules_by_owner, plan) {
            ProcessScanOutcome::Hit(hit) => {
                if !hit.health_scan_complete {
                    if let Some(pkg) = base_package(&hit.cmdline) {
                        health_incomplete_packages.insert(pkg.to_string());
                    }
                }
                hits.push(hit);
            }
            ProcessScanOutcome::Gone | ProcessScanOutcome::Unreadable
                if known_pids.contains(&pid) =>
            {
                complete = false;
            }
            ProcessScanOutcome::Gone
            | ProcessScanOutcome::NotTarget
            | ProcessScanOutcome::Unreadable => {}
        }
    }

    Ok(ProcScanResult {
        hits,
        complete,
        health_incomplete_packages,
    })
}

fn scan_known_pids(
    rules: &[Rule],
    plan: &ScanPlan,
    known_pids: &mut BTreeSet<i32>,
) -> ProcScanResult {
    // 缓存扫描只访问上轮已经命中过的 PID，主要降低常驻 daemon 的 open/read 次数。
    // 如果进程退出或规则不再匹配，会从 known_pids 里剔除。
    let rules_by_owner = build_rules_by_owner(rules);
    let mut hits = Vec::new();
    let mut alive = BTreeSet::new();
    let mut complete = true;
    let mut health_incomplete_packages = BTreeSet::new();

    for pid in known_pids.iter().copied() {
        let proc_path = PathBuf::from(format!("/proc/{pid}"));
        match scan_process_path(pid, &proc_path, &rules_by_owner, plan) {
            ProcessScanOutcome::Hit(hit) => {
                alive.insert(pid);
                if !hit.health_scan_complete {
                    if let Some(pkg) = base_package(&hit.cmdline) {
                        health_incomplete_packages.insert(pkg.to_string());
                    }
                }
                hits.push(hit);
            }
            ProcessScanOutcome::Unreadable => {
                // /proc 是瞬时视图；已确认过的目标 PID 本轮读取失败时先保留，
                // 避免多个分身/进程中仅一个短暂失败就从缓存消失到下次 60 秒全扫。
                alive.insert(pid);
                complete = false;
            }
            ProcessScanOutcome::Gone | ProcessScanOutcome::NotTarget => {}
        }
    }

    *known_pids = alive;
    ProcScanResult {
        hits,
        complete,
        health_incomplete_packages,
    }
}

fn scan_process_path(
    pid: i32,
    proc_path: &Path,
    rules_by_owner: &HashMap<&str, Vec<&Rule>>,
    plan: &ScanPlan,
) -> ProcessScanOutcome {
    // UID 的 appId 用于优先缩小候选包集合；厂商分身/isolated UID 仍可走严格包名兜底。
    // Linux/Android 内核没有“包名”概念，最终必须读取 cmdline 确认主进程/子进程名。
    let uid = match metadata_uid(proc_path) {
        Ok(uid) => uid,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return ProcessScanOutcome::Gone,
        Err(_) => return ProcessScanOutcome::Unreadable,
    };

    let cmdline = match read_cmdline(pid) {
        Ok(cmdline) if !cmdline.is_empty() => cmdline,
        Ok(_) => return ProcessScanOutcome::NotTarget,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return ProcessScanOutcome::Gone,
        Err(_) => return ProcessScanOutcome::Unreadable,
    };

    let Some(matched_base) = matched_plan_package(uid, &cmdline, plan) else {
        return ProcessScanOutcome::NotTarget;
    };
    let pid_starttime = read_proc_starttime(proc_path).ok();

    // 规则匹配分三层：
    // 1. 精确 owner 规则：cmdline 完全等于规则 owner，例如 com.app:push。
    // 2. 子进程继承主进程进程级兜底：子进程无独立规则时，可吃到 com.app=0-3。
    // 3. 线程规则只对精确 owner 生效，不给子进程继承主进程线程规则。
    //
    // 这么做是为了避免 com.app{RenderThread}=7 错绑到 com.app:push 里的同名线程；
    // 同时也保留“没有单独子进程规则时，子进程至少跟随主包兜底核心”的旧行为。
    let exact_owner_rules = rules_by_owner
        .get(cmdline.as_str())
        .map(|rules| rules.as_slice())
        .unwrap_or(&[]);
    let exact_process_rules = exact_owner_rules
        .iter()
        .copied()
        .filter(|rule| rule.thread.is_none())
        .collect::<Vec<_>>();
    // 子进程只有在没有精确进程级规则时才继承基础主包的进程级规则。即使精确
    // owner 只有线程规则，也仍保留主包兜底；多级子进程不会继承中间 owner。
    let inherited_base_process_rules = if exact_process_rules.is_empty() && cmdline != matched_base
    {
        rules_by_owner
            .get(matched_base)
            .into_iter()
            .flat_map(|rules| rules.iter().copied())
            .filter(|rule| rule.thread.is_none())
            .collect::<Vec<_>>()
    } else {
        Vec::new()
    };

    let process_rules: Vec<&Rule> = exact_process_rules
        .into_iter()
        .chain(inherited_base_process_rules.iter().copied())
        .collect();
    let thread_rules: Vec<&Rule> = exact_owner_rules
        .iter()
        .copied()
        .filter(|rule| rule.thread.is_some())
        .collect();

    let has_app_health_rules = rules_by_owner.iter().any(|(owner, owner_rules)| {
        base_package(owner) == Some(matched_base)
            && owner_rules
                .iter()
                .any(|rule| rule.thread.is_some() || rule.owner.contains(':'))
    });
    let needs_thread_scan = !process_rules.is_empty() || !thread_rules.is_empty();
    let (actions, scanned_threads, threads_complete) = if needs_thread_scan {
        scan_threads(proc_path, &process_rules, &thread_rules)
    } else {
        (Vec::new(), 0, true)
    };
    // 缓存基础主进程可避免“只有尚未出现的健康目标”时每轮全量扫 /proc；缓存含线程
    // 规则的精确 owner，则能继续复用 task 扫描捕获稍后才出现的目标线程。
    // 这些保留项只服务扫描缓存，不参与前台生命周期判断。
    let keep_main_for_health = cmdline == matched_base && has_app_health_rules;
    let keep_owner_for_thread_observation = !thread_rules.is_empty();
    if process_rules.is_empty()
        && actions.is_empty()
        && !keep_main_for_health
        && !keep_owner_for_thread_observation
    {
        return ProcessScanOutcome::NotTarget;
    }

    ProcessScanOutcome::Hit(ProcHit {
        pid,
        pid_starttime,
        uid,
        cmdline,
        process_rules: process_rules.iter().map(|rule| rule.line()).collect(),
        actions,
        scanned_threads,
        health_scan_complete: pid_starttime.is_some() && threads_complete,
    })
}

fn matched_plan_package<'a>(uid: u32, cmdline: &str, plan: &'a ScanPlan) -> Option<&'a str> {
    // 完整 UID 的高位是 Android userId；分身/工作资料与原应用共享低位 appId。
    // appId 命中仍只作为预过滤，最终必须用 cmdline 精确确认包名或其 :子进程。
    if let Some(pkgs) = plan.by_app_id.get(&android_app_id(uid)) {
        if let Some(pkg) = pkgs
            .iter()
            .find(|pkg| process_belongs_to_uid_package(cmdline, pkg))
        {
            return Some(pkg.as_str());
        }
    }
    // 部分厂商分身或 isolated 进程可能不保留宿主 appId。这里仍要求 cmdline 的
    // 基础包名完全存在于配置集合中，只放宽 UID，不放宽包名边界。
    let cmdline_base = cmdline.split_once(':').map_or(cmdline, |(pkg, _)| pkg);
    plan.all_pkgs.get(cmdline_base).map(String::as_str)
}

fn scan_threads(
    proc_path: &Path,
    process_rules: &[&Rule],
    thread_rules: &[&Rule],
) -> (Vec<ThreadAction>, usize, bool) {
    // Linux 线程名来自 /proc/<pid>/task/<tid>/comm，最多 15 字节，会被内核截断。
    // 因此规则匹配必须接受用户写的截断名或通配符，例如 Thread-*、binder:*。
    let task_dir = proc_path.join("task");
    let tasks = match fs::read_dir(task_dir) {
        Ok(tasks) => tasks,
        Err(_) => return (Vec::new(), 0, false),
    };

    let mut actions = Vec::new();
    let mut scanned = 0;
    let mut complete = true;
    let process_rule = combine_rules(process_rules);

    for task in tasks {
        let task = match task {
            Ok(task) => task,
            Err(_) => {
                complete = false;
                continue;
            }
        };
        let Some(tid) = parse_pid(&task.file_name()) else {
            continue;
        };
        let name = match read_comm(&task.path()) {
            Ok(name) if !name.is_empty() => name,
            _ => {
                complete = false;
                continue;
            }
        };
        let tid_starttime = read_proc_starttime(&task.path()).ok();
        complete &= tid_starttime.is_some();
        scanned += 1;

        // 后面的同 owner 规则优先级更高，和配置文件“后写覆盖前写”的直觉保持一致。
        let matched_thread_rules = thread_rules
            .iter()
            .copied()
            .filter(|rule| {
                rule.thread
                    .as_deref()
                    .is_some_and(|pattern| glob_match(pattern, &name))
            })
            .collect::<Vec<_>>();

        if let Some(rule) = combine_rules(&matched_thread_rules) {
            actions.push(ThreadAction {
                tid,
                tid_starttime,
                name,
                rule: rule.line,
                rule_health_keys: matched_thread_rules
                    .iter()
                    .map(|rule| {
                        rule_health_key(
                            'T',
                            &rule.owner,
                            rule.thread.as_deref().unwrap_or_default(),
                        )
                    })
                    .collect(),
                cpus: rule.cpus,
                source: RuleSource::Thread,
            });
            continue;
        }

        // 没命中线程规则时，才应用进程级兜底规则。
        if let Some(rule) = &process_rule {
            actions.push(ThreadAction {
                tid,
                tid_starttime,
                name,
                rule: rule.line.clone(),
                rule_health_keys: Vec::new(),
                cpus: rule.cpus.clone(),
                source: RuleSource::Process,
            });
        }
    }

    (actions, scanned, complete)
}

#[derive(Debug, Clone)]
struct CombinedRule {
    cpus: String,
    line: String,
}

fn combine_rules(rules: &[&Rule]) -> Option<CombinedRule> {
    let mut mask = CpuMask::empty();
    let mut lines = Vec::new();
    let mut any = false;

    for rule in rules {
        let Some(rule_mask) = CpuMask::parse(&rule.cpus) else {
            continue;
        };
        mask.or_assign(&rule_mask);
        lines.push(rule.line());
        any = true;
    }

    if any {
        Some(CombinedRule {
            cpus: mask.to_list(),
            line: lines.join(" | "),
        })
    } else {
        None
    }
}
