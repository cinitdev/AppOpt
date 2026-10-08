use super::*;
// /proc 扫描与规则命中。
//
// 扫描分两层：
// 1. 进程层：先用 /proc/<pid> 目录所有者的 UID 和 cmdline 判断是否属于目标包。
// 2. 线程层：只有进程命中后，才进入 /proc/<pid>/task 读取 comm 并匹配线程规则。
//
// 这样保留基于 /proc 的通用兼容路径，同时避免无意义地读取全系统所有线程。
// 这里不要引入 cmd/pm/dumpsys 这类外部命令，守护进程长期运行时创建子进程的成本太高。
pub(super) enum ProcessScanOutcome {
    Hit(ProcHit),
    Gone,
    NotTarget,
    Unreadable,
}

#[derive(Debug, Default)]
pub(super) struct CandidateScanResult {
    pub(super) hits: Vec<ProcHit>,
    pub(super) gone_pids: BTreeSet<i32>,
}

pub(super) fn enumerate_proc_pids() -> io::Result<BTreeSet<i32>> {
    let mut pids = BTreeSet::new();
    for entry in fs::read_dir("/proc")? {
        let entry = entry?;
        if let Some(pid) = parse_pid(&entry.file_name()) {
            pids.insert(pid);
        }
    }
    Ok(pids)
}

pub(super) fn scan_candidate_pids(
    rules: &[Rule],
    index: &RuntimeRuleIndex,
    candidates: &BTreeSet<i32>,
) -> CandidateScanResult {
    if index.plan.is_empty() || candidates.is_empty() {
        return CandidateScanResult::default();
    }

    let mut result = CandidateScanResult::default();
    for pid in candidates.iter().copied() {
        let proc_path = PathBuf::from(format!("/proc/{pid}"));
        match scan_process_path(pid, &proc_path, rules, index) {
            ProcessScanOutcome::Hit(hit) => result.hits.push(hit),
            ProcessScanOutcome::Gone => {
                result.gone_pids.insert(pid);
            }
            // Zygote fork 后 cmdline 可能稍晚才改成应用进程名；未命中和瞬时读取失败
            // 都交给主循环在短时间窗口内复查，不立即判定为无关进程。
            ProcessScanOutcome::NotTarget | ProcessScanOutcome::Unreadable => {}
        }
    }
    result
}

pub(super) fn scan_proc(
    rules: &[Rule],
    index: &RuntimeRuleIndex,
    known_pids: &BTreeSet<i32>,
    process_index: &ProcessIndex,
) -> io::Result<ProcScanResult> {
    scan_proc_indexed(rules, index, known_pids, process_index, None)
}

pub(super) fn scan_proc_packages(
    rules: &[Rule],
    index: &RuntimeRuleIndex,
    known_pids: &BTreeSet<i32>,
    process_index: &ProcessIndex,
    packages: &BTreeSet<String>,
) -> io::Result<ProcScanResult> {
    if packages.is_empty() {
        return Ok(ProcScanResult {
            hits: Vec::new(),
            complete: true,
            health_incomplete_packages: BTreeSet::new(),
        });
    }
    scan_proc_indexed(rules, index, known_pids, process_index, Some(packages))
}

pub(super) fn scan_proc_indexed(
    rules: &[Rule],
    index: &RuntimeRuleIndex,
    known_pids: &BTreeSet<i32>,
    process_index: &ProcessIndex,
    scope_packages: Option<&BTreeSet<String>>,
) -> io::Result<ProcScanResult> {
    if index.plan.is_empty() {
        return Ok(ProcScanResult {
            hits: Vec::new(),
            complete: true,
            health_incomplete_packages: BTreeSet::new(),
        });
    }
    // 本轮数字 PID 快照已经重建/刷新了内存索引。这里直接按缓存 cmdline 预筛，
    // 不再第二次枚举整个 /proc；真正使用前仍由 scan_process_path_scoped 重新读取
    // UID、cmdline 和 starttime，缓存身份绝不会直接生成亲和性动作。
    let packages = scope_packages.unwrap_or(&index.plan.all_pkgs);
    let mut candidate_pids = process_index_cached_package_pids(process_index, packages);
    if scope_packages.is_none() {
        // 已知目标即使本轮索引身份读取出现瞬时缺口，也必须进入最终身份复核。
        candidate_pids.extend(known_pids.iter().copied());
    }
    let scoped_known_pids = known_pids
        .intersection(&candidate_pids)
        .copied()
        .collect::<BTreeSet<_>>();
    let mut hits = Vec::new();
    let mut complete = process_index.snapshot_complete;
    let mut health_incomplete_packages = BTreeSet::new();
    for pid in candidate_pids {
        let indexed_package = indexed_candidate_package(process_index, pid, packages);
        let proc_path = PathBuf::from(format!("/proc/{pid}"));
        match scan_process_path_scoped(
            pid,
            &proc_path,
            rules,
            index,
            scope_packages,
        ) {
            ProcessScanOutcome::Hit(hit) => {
                if !hit.health_scan_complete {
                    if let Some(pkg) = base_package(&hit.cmdline) {
                        health_incomplete_packages.insert(pkg.to_string());
                    }
                }
                hits.push(hit);
            }
            ProcessScanOutcome::Gone if scoped_known_pids.contains(&pid) => {
                complete = false;
            }
            ProcessScanOutcome::Unreadable => {
                // 索引已经把该 PID 归属到目标包；即使它还没进入 known_pids，瞬时
                // 读不到 UID/cmdline 也不能被当作“目标不存在”的完整负向证据。
                complete = false;
                if let Some(pkg) = indexed_package {
                    health_incomplete_packages.insert(pkg);
                }
            }
            ProcessScanOutcome::Gone | ProcessScanOutcome::NotTarget => {}
        }
    }

    Ok(ProcScanResult {
        hits,
        complete,
        health_incomplete_packages,
    })
}

pub(super) fn indexed_candidate_package(
    process_index: &ProcessIndex,
    pid: i32,
    packages: &BTreeSet<String>,
) -> Option<String> {
    process_index.entries.get(&pid).and_then(|entry| {
        let base = entry
            .cmdline
            .split_once(':')
            .map_or(entry.cmdline.as_str(), |(pkg, _)| pkg);
        packages.contains(base).then_some(base.to_string())
    })
}

pub(super) struct KnownPidScanPolicy<'a> {
    pub(super) now_elapsed: u64,
    pub(super) deep_scan_interval_ms: u64,
    pub(super) priority_pids: &'a BTreeSet<i32>,
    pub(super) background_budget: Duration,
}

pub(super) fn scan_known_pids(
    rules: &[Rule],
    index: &RuntimeRuleIndex,
    known_pids: &mut BTreeSet<i32>,
    process_scan_stamps: &mut HashMap<i32, ProcessScanStamp>,
    policy: KnownPidScanPolicy<'_>,
) -> ProcScanResult {
    // 缓存扫描只访问上轮已经命中过的 PID，主要降低常驻守护进程打开和读取文件的次数。
    // 如果进程退出或规则不再匹配，会从 known_pids 里剔除。
    let mut hits = Vec::new();
    let mut alive = BTreeSet::new();
    let mut complete = true;
    let mut health_incomplete_packages = BTreeSet::new();
    let background_started = Instant::now();
    for pid in known_pids.iter().copied() {
        let proc_path = PathBuf::from(format!("/proc/{pid}"));
        let pid_starttime = read_proc_starttime(&proc_path).ok();
        let stamp = process_scan_stamps.get(&pid).copied();
        let identity_matches = stamp.is_some_and(|stamp| {
            pid_starttime.is_some_and(|pid_starttime| stamp.pid_starttime == pid_starttime)
        });
        let deep_scan_due = stamp.is_none_or(|stamp| {
            policy.now_elapsed < stamp.last_deep_scan_elapsed_ms
                || policy.now_elapsed >= stamp.next_deep_scan_elapsed_ms
        });
        if identity_matches && !deep_scan_due {
            alive.insert(pid);
            continue;
        }
        let reached_hard_deadline = stamp.is_none_or(|stamp| {
            policy.now_elapsed < stamp.last_deep_scan_elapsed_ms
                || policy
                    .now_elapsed
                    .saturating_sub(stamp.last_deep_scan_elapsed_ms)
                    >= policy.deep_scan_interval_ms
        });
        if identity_matches
            && !policy.priority_pids.contains(&pid)
            && !reached_hard_deadline
            && background_started.elapsed() >= policy.background_budget
        {
            alive.insert(pid);
            continue;
        }
        if identity_matches {
            let fingerprint_unchanged = stamp.is_some_and(|stamp| {
                read_thread_set_fingerprint(&proc_path)
                    .is_ok_and(|fingerprint| stamp.thread_fingerprint == fingerprint)
            });
            if fingerprint_unchanged {
                if let Some(stamp) = process_scan_stamps.get_mut(&pid) {
                    stamp.last_deep_scan_elapsed_ms = policy.now_elapsed;
                    stamp.next_deep_scan_elapsed_ms = next_deep_scan_slot(
                        pid,
                        stamp.pid_starttime,
                        policy.now_elapsed,
                        policy.deep_scan_interval_ms,
                    );
                }
                alive.insert(pid);
                continue;
            }
        }

        match scan_process_path(pid, &proc_path, rules, index) {
            ProcessScanOutcome::Hit(hit) => {
                alive.insert(pid);
                update_process_scan_stamp(
                    process_scan_stamps,
                    &hit,
                    policy.now_elapsed,
                    policy.deep_scan_interval_ms,
                );
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
            ProcessScanOutcome::Gone | ProcessScanOutcome::NotTarget => {
                process_scan_stamps.remove(&pid);
            }
        }
    }

    *known_pids = alive;
    ProcScanResult {
        hits,
        complete,
        health_incomplete_packages,
    }
}

pub(super) fn update_process_scan_stamp(
    process_scan_stamps: &mut HashMap<i32, ProcessScanStamp>,
    hit: &ProcHit,
    now_elapsed: u64,
    deep_scan_interval_ms: u64,
) {
    let Some(pid_starttime) = hit.pid_starttime else {
        process_scan_stamps.remove(&hit.pid);
        return;
    };
    let Some(thread_fingerprint) = hit.thread_fingerprint else {
        process_scan_stamps.remove(&hit.pid);
        return;
    };
    process_scan_stamps.insert(
        hit.pid,
        ProcessScanStamp {
            pid_starttime,
            thread_fingerprint,
            last_deep_scan_elapsed_ms: now_elapsed,
            next_deep_scan_elapsed_ms: next_deep_scan_slot(
                hit.pid,
                pid_starttime,
                now_elapsed,
                deep_scan_interval_ms,
            ),
        },
    );
}

pub(super) fn next_deep_scan_slot(
    pid: i32,
    starttime: u64,
    now_elapsed: u64,
    interval_ms: u64,
) -> u64 {
    if interval_ms == 0 {
        return now_elapsed;
    }
    let mut value = (pid as u64) ^ starttime.rotate_left(17);
    value = value.wrapping_add(0x9e37_79b9_7f4a_7c15);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    value ^= value >> 31;
    let phase = value % interval_ms;
    let base = now_elapsed - (now_elapsed % interval_ms);
    let candidate = base.saturating_add(phase);
    if candidate > now_elapsed {
        candidate
    } else {
        candidate.saturating_add(interval_ms)
    }
}

pub(super) fn read_thread_set_fingerprint(proc_path: &Path) -> io::Result<ThreadSetFingerprint> {
    let mut fingerprint = ThreadSetFingerprint::default();
    for task in fs::read_dir(proc_path.join("task"))? {
        let task = task?;
        if let Some(tid) = parse_pid(&task.file_name()) {
            fingerprint.add_tid(tid);
        }
    }
    Ok(fingerprint)
}

impl ThreadSetFingerprint {
    pub(super) fn add_tid(&mut self, tid: i32) {
        let mut value = tid as u64;
        value = value.wrapping_add(0x9e37_79b9_7f4a_7c15);
        value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        value ^= value >> 31;
        self.count = self.count.saturating_add(1);
        self.xor_hash ^= value;
        self.sum_hash = self.sum_hash.wrapping_add(value);
    }
}

pub(super) fn scan_process_path(
    pid: i32,
    proc_path: &Path,
    rules: &[Rule],
    index: &RuntimeRuleIndex,
) -> ProcessScanOutcome {
    scan_process_path_scoped(pid, proc_path, rules, index, None)
}

pub(super) fn scan_process_path_scoped(
    pid: i32,
    proc_path: &Path,
    rules: &[Rule],
    index: &RuntimeRuleIndex,
    scope_packages: Option<&BTreeSet<String>>,
) -> ProcessScanOutcome {
    // UID 的 appId 用于优先缩小候选包集合；厂商分身或隔离 UID 仍可走严格包名兜底。
    // Linux/Android 内核没有“包名”概念，最终必须读取 cmdline 确认主进程/子进程名。
    let uid = match metadata_uid(proc_path) {
        Ok(uid) => uid,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return ProcessScanOutcome::Gone,
        Err(_) => return ProcessScanOutcome::Unreadable,
    };

    let cmdline = match read_cmdline(pid) {
        Ok(cmdline) if !cmdline.is_empty() => cmdline,
        // 已存在的 Android 进程在 exec/退出竞态中可能短暂读到空 cmdline；这不是
        // “已确认不属于目标包”的证据，按不可读保留到下一轮复核。
        Ok(_) => return ProcessScanOutcome::Unreadable,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return ProcessScanOutcome::Gone,
        Err(_) => return ProcessScanOutcome::Unreadable,
    };

    let Some(matched_base) = matched_plan_package(uid, &cmdline, &index.plan) else {
        return ProcessScanOutcome::NotTarget;
    };
    if scope_packages.is_some_and(|packages| !packages.contains(matched_base)) {
        return ProcessScanOutcome::NotTarget;
    }
    let pid_starttime = read_proc_starttime(proc_path).ok();

    // 规则匹配分三层：
    // 1. 精确 owner 规则：cmdline 完全等于规则 owner，例如 com.app:push。
    // 2. 子进程继承主进程进程级兜底：子进程无独立规则时，可吃到 com.app=0-3。
    // 3. 线程规则只对精确 owner 生效，不给子进程继承主进程线程规则。
    //
    // 这么做是为了避免 com.app{RenderThread}=7 错绑到 com.app:push 里的同名线程；
    // 同时也保留“没有单独子进程规则时，子进程至少跟随主包兜底核心”的旧行为。
    let exact_owner_rule_indices = index
        .rules_by_owner
        .get(cmdline.as_str())
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    let exact_process_rules = exact_owner_rule_indices
        .iter()
        .filter_map(|rule_index| rules.get(*rule_index))
        .filter(|rule| rule.thread.is_none())
        .collect::<Vec<_>>();
    // 子进程只有在没有精确进程级规则时才继承基础主包的进程级规则。即使精确
    // owner 只有线程规则，也仍保留主包兜底；多级子进程不会继承中间 owner。
    let inherited_base_process_rules = if exact_process_rules.is_empty() && cmdline != matched_base
    {
        index
            .rules_by_owner
            .get(matched_base)
            .into_iter()
            .flat_map(|rule_indices| rule_indices.iter())
            .filter_map(|rule_index| rules.get(*rule_index))
            .filter(|rule| rule.thread.is_none())
            .collect::<Vec<_>>()
    } else {
        Vec::new()
    };

    let process_rules: Vec<&Rule> = exact_process_rules
        .into_iter()
        .chain(inherited_base_process_rules.iter().copied())
        .collect();
    let thread_rules: Vec<&Rule> = exact_owner_rule_indices
        .iter()
        .filter_map(|rule_index| rules.get(*rule_index))
        .filter(|rule| rule.thread.is_some())
        .collect();
    let health_owner_rule_indices = index
        .health_rules_by_owner
        .get(cmdline.as_str())
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    let health_thread_rules = health_owner_rule_indices
        .iter()
        .filter_map(|rule_index| rules.get(*rule_index))
        .filter(|rule| rule.thread.is_some())
        .collect::<Vec<_>>();
    let has_exact_process_health_rule = health_owner_rule_indices
        .iter()
        .filter_map(|rule_index| rules.get(*rule_index))
        .any(|rule| rule.thread.is_none());

    let has_app_health_rules = index.health_rule_packages.contains(matched_base);
    let needs_thread_scan = !process_rules.is_empty()
        || !thread_rules.is_empty()
        || !health_thread_rules.is_empty();
    let (
        actions,
        matched_rule_health_keys,
        scanned_threads,
        threads_complete,
        thread_fingerprint,
    ) = if needs_thread_scan {
        scan_threads(
            proc_path,
            &process_rules,
            &thread_rules,
            &health_thread_rules,
        )
    } else {
        (Vec::new(), Vec::new(), 0, true, None)
    };
    // 缓存基础主进程可避免“只有尚未出现的健康目标”时每轮全量扫 /proc；缓存含线程
    // 规则的精确 owner，则能继续复用 task 扫描捕获稍后才出现的目标线程。
    // 这些保留项只服务扫描缓存，不参与前台生命周期判断。
    let keep_main_for_health = cmdline == matched_base && has_app_health_rules;
    let keep_owner_for_thread_observation = !health_thread_rules.is_empty();
    let keep_process_for_health = cmdline.contains(':') && has_exact_process_health_rule;
    if process_rules.is_empty()
        && actions.is_empty()
        && !keep_main_for_health
        && !keep_owner_for_thread_observation
        && !keep_process_for_health
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
        matched_rule_health_keys,
        scanned_threads,
        health_scan_complete: pid_starttime.is_some() && threads_complete,
        thread_fingerprint: threads_complete.then_some(thread_fingerprint).flatten(),
    })
}

pub(super) fn matched_plan_package<'a>(uid: u32, cmdline: &str, plan: &'a ScanPlan) -> Option<&'a str> {
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
    // 部分厂商分身或隔离进程可能不保留宿主 appId。这里仍要求 cmdline 的
    // 基础包名完全存在于配置集合中，只放宽 UID，不放宽包名边界。
    let cmdline_base = cmdline.split_once(':').map_or(cmdline, |(pkg, _)| pkg);
    plan.all_pkgs.get(cmdline_base).map(String::as_str)
}

pub(super) fn scan_threads(
    proc_path: &Path,
    process_rules: &[&Rule],
    thread_rules: &[&Rule],
    health_thread_rules: &[&Rule],
) -> (
    Vec<ThreadAction>,
    Vec<String>,
    usize,
    bool,
    Option<ThreadSetFingerprint>,
) {
    // Linux 线程名来自 /proc/<pid>/task/<tid>/comm，最多 15 字节，会被内核截断。
    // 因此规则匹配必须接受用户写的截断名或通配符，例如 Thread-*、binder:*。
    let task_dir = proc_path.join("task");
    let tasks = match fs::read_dir(task_dir) {
        Ok(tasks) => tasks,
        Err(_) => return (Vec::new(), Vec::new(), 0, false, None),
    };

    let mut actions = Vec::new();
    let mut matched_rule_health_keys = BTreeSet::new();
    let mut scanned = 0;
    let mut complete = true;
    let mut fingerprint = ThreadSetFingerprint::default();
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
        fingerprint.add_tid(tid);
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
        for rule in health_thread_rules.iter().copied().filter(|rule| {
            rule.thread
                .as_deref()
                .is_some_and(|pattern| glob_match(pattern, &name))
        }) {
            matched_rule_health_keys.insert(rule_health::key(
                'T',
                &rule.owner,
                rule.thread.as_deref().unwrap_or_default(),
            ));
        }

        if let Some(rule) = combine_rules(&matched_thread_rules) {
            actions.push(ThreadAction {
                tid,
                tid_starttime,
                name,
                rule: rule.line,
                rule_health_keys: matched_thread_rules
                    .iter()
                    .map(|rule| {
                        rule_health::key(
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

    (
        actions,
        matched_rule_health_keys.into_iter().collect(),
        scanned,
        complete,
        Some(fingerprint),
    )
}

#[derive(Debug, Clone)]
pub(super) struct CombinedRule {
    pub(super) cpus: String,
    pub(super) line: String,
}

pub(super) fn combine_rules(rules: &[&Rule]) -> Option<CombinedRule> {
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

#[cfg(test)]
mod scan_evidence_tests {
    use super::*;

    #[test]
    fn unreadable_indexed_child_is_attributed_to_its_exact_base_package() {
        let process_index = ProcessIndex {
            entries: BTreeMap::from([(
                42,
                ProcessIndexEntry {
                    pid: 42,
                    starttime: 7,
                    first_seen_elapsed_ms: 1,
                    comm: "worker".to_string(),
                    cmdline: "com.example:worker".to_string(),
                },
            )]),
            ..ProcessIndex::default()
        };
        let packages = BTreeSet::from(["com.example".to_string()]);

        assert_eq!(
            indexed_candidate_package(&process_index, 42, &packages).as_deref(),
            Some("com.example")
        );
        assert!(indexed_candidate_package(
            &process_index,
            42,
            &BTreeSet::from(["com.example.extra".to_string()])
        )
        .is_none());
    }
}
