// /proc 扫描与规则命中。
//
// 扫描分两层：
// 1. 进程层：先用 /proc/<pid> 目录 owner UID 和 cmdline 判断是否属于目标包。
// 2. 线程层：只有进程命中后，才进入 /proc/<pid>/task 读取 comm 并匹配线程规则。
//
// 这样保留了 C 版基于 /proc 的兼容性，同时避免无意义地读取全系统所有线程。
// 这里不要引入 cmd/pm/dumpsys 这类外部命令，守护进程长期运行时 fork 成本太高。
fn scan_proc(rules: &[Rule], plan: &ScanPlan) -> io::Result<Vec<ProcHit>> {
    // 全量扫描只枚举 /proc/<pid> 目录，不会直接扫全系统线程。
    // 只有 PID 先通过 UID/cmdline 包名过滤后，才进入 /proc/<pid>/task 扫线程。
    let rules_by_owner = build_rules_by_owner(rules);
    let mut hits = Vec::new();
    let proc_dir = Path::new("/proc");
    for entry in fs::read_dir(proc_dir)? {
        let entry = match entry {
            Ok(entry) => entry,
            Err(_) => continue,
        };
        let file_name = entry.file_name();
        let Some(pid) = parse_pid(&file_name) else {
            continue;
        };
        if let Some(hit) = scan_process_path(pid, &entry.path(), &rules_by_owner, plan) {
            hits.push(hit);
        }
    }

    Ok(hits)
}

fn scan_known_pids(
    rules: &[Rule],
    plan: &ScanPlan,
    known_pids: &mut BTreeSet<i32>,
) -> Vec<ProcHit> {
    // 缓存扫描只访问上轮已经命中过的 PID，主要降低常驻 daemon 的 open/read 次数。
    // 如果进程退出或规则不再匹配，会从 known_pids 里剔除。
    let rules_by_owner = build_rules_by_owner(rules);
    let mut hits = Vec::new();
    let mut alive = BTreeSet::new();

    for pid in known_pids.iter().copied() {
        let proc_path = PathBuf::from(format!("/proc/{pid}"));
        if !proc_path.exists() {
            continue;
        }
        if let Some(hit) = scan_process_path(pid, &proc_path, &rules_by_owner, plan) {
            alive.insert(pid);
            hits.push(hit);
        }
    }

    *known_pids = alive;
    hits
}

fn scan_process_path(
    pid: i32,
    proc_path: &Path,
    rules_by_owner: &HashMap<&str, Vec<&Rule>>,
    plan: &ScanPlan,
) -> Option<ProcHit> {
    // 先看 /proc/<pid> 目录 owner uid，这是比读 cmdline 更便宜的预过滤。
    // 注意 Linux/Android 内核没有“包名”概念，最终仍要读取 cmdline 确认主进程/子进程名。
    let uid = metadata_uid(proc_path)?;

    let cmdline = match read_cmdline(pid) {
        Ok(cmdline) if !cmdline.is_empty() => cmdline,
        _ => return None,
    };

    let matched_base = matched_plan_package(uid, &cmdline, plan)?;

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
    let inherited_base_rules = if exact_owner_rules.is_empty() && cmdline != matched_base {
        rules_by_owner
            .get(matched_base)
            .map(|rules| rules.as_slice())
            .unwrap_or(&[])
    } else {
        &[]
    };

    let process_rules: Vec<&Rule> = exact_owner_rules
        .iter()
        .copied()
        .filter(|rule| rule.thread.is_none())
        .chain(
            inherited_base_rules
                .iter()
                .copied()
                .filter(|rule| rule.thread.is_none()),
        )
        .collect();
    let thread_rules: Vec<&Rule> = exact_owner_rules
        .iter()
        .copied()
        .filter(|rule| rule.thread.is_some())
        .collect();

    let (actions, scanned_threads) = scan_threads(proc_path, &process_rules, &thread_rules);
    if process_rules.is_empty() && actions.is_empty() {
        return None;
    }

    Some(ProcHit {
        pid,
        uid,
        cmdline,
        process_rules: process_rules.iter().map(|rule| rule.line()).collect(),
        actions,
        scanned_threads,
    })
}

fn matched_plan_package<'a>(uid: u32, cmdline: &str, plan: &'a ScanPlan) -> Option<&'a str> {
    // UID 命中只说明“同一个应用 UID”，还不能证明具体是哪个包。
    // 多开、sharedUserId 或同 UID 组件场景下，仍需要 cmdline 以 base 包名校验。
    if let Some(pkgs) = plan.by_uid.get(&uid) {
        if let Some(pkg) = pkgs
            .iter()
            .find(|pkg| process_belongs_to_uid_package(cmdline, pkg))
        {
            return Some(pkg.as_str());
        }
    }
    plan.fallback_pkgs
        .iter()
        .find(|pkg| process_belongs_to_uid_package(cmdline, pkg))
        .map(String::as_str)
}

fn scan_threads(
    proc_path: &Path,
    process_rules: &[&Rule],
    thread_rules: &[&Rule],
) -> (Vec<ThreadAction>, usize) {
    // Linux 线程名来自 /proc/<pid>/task/<tid>/comm，最多 15 字节，会被内核截断。
    // 因此规则匹配必须接受用户写的截断名或通配符，例如 Thread-*、binder:*。
    let task_dir = proc_path.join("task");
    let tasks = match fs::read_dir(task_dir) {
        Ok(tasks) => tasks,
        Err(_) => return (Vec::new(), 0),
    };

    let mut actions = Vec::new();
    let mut scanned = 0;
    let process_rule = combine_rules(process_rules);

    for task in tasks {
        let task = match task {
            Ok(task) => task,
            Err(_) => continue,
        };
        let Some(tid) = parse_pid(&task.file_name()) else {
            continue;
        };
        let name = match read_comm(&task.path()) {
            Ok(name) if !name.is_empty() => name,
            _ => continue,
        };
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
                name,
                rule: rule.line,
                cpus: rule.cpus,
                source: RuleSource::Thread,
            });
            continue;
        }

        // 没命中线程规则时，才应用进程级兜底规则。
        if let Some(rule) = &process_rule {
            actions.push(ThreadAction {
                tid,
                name,
                rule: rule.line.clone(),
                cpus: rule.cpus.clone(),
                source: RuleSource::Process,
            });
        }
    }

    (actions, scanned)
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
