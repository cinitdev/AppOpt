// 线程/子进程规则健康状态。
//
// 健康检查复用 daemon 每轮已经得到的 ProcHit，不额外遍历 /proc：
// - 子进程规则通过命中的 cmdline 判断；
// - 线程规则通过 ThreadAction 中已经命中的原始规则行判断。
//
// 负向观察窗口只跟随 ActivityTaskManager helper 写入的可靠前台状态。helper
// 不可用时仍接受真实命中，但不累计 miss，避免后台存活的主进程造成误报。
// 规则身份只包含 owner + 线程/子进程名，不包含 CPU 范围，因此只修改核心范围会保留
// 既有状态，修改线程名/子进程名才会创建新的 Pending 状态并重新观察。
#[derive(Debug, Default)]
struct RuleHealthForegroundState {
    reliable: bool,
    observable: bool,
    selection: String,
    focused_package: String,
    visible_packages: BTreeSet<String>,
    lifecycle_packages: HashMap<String, RuleHealthLifecycle>,
    exited_packages: HashMap<String, u64>,
    updated_elapsed_ms: u64,
}

impl RuleHealthForegroundState {
    fn can_start(&self, pkg: &str) -> bool {
        self.observable
            && matches!(self.selection.as_str(), "focused" | "default-visible")
            && self.focused_package == pkg
            && self.lifecycle_packages.contains_key(pkg)
    }

    fn contains(&self, pkg: &str) -> bool {
        self.observable
            && self.lifecycle_packages.contains_key(pkg)
            && (self.focused_package == pkg || self.visible_packages.contains(pkg))
    }

    fn lifecycle(&self, pkg: &str) -> Option<RuleHealthLifecycle> {
        self.reliable
            .then(|| self.lifecycle_packages.get(pkg).copied())
            .flatten()
    }

    fn exited_at(&self, pkg: &str) -> Option<u64> {
        self.reliable
            .then(|| self.exited_packages.get(pkg).copied())
            .flatten()
    }
}

fn rule_health_status_is_observable(status: RuleHealthStatus) -> bool {
    status == RuleHealthStatus::Pending
}

fn rule_health_rule_in_scope(rule: &Rule, scope_pkg: Option<&str>) -> bool {
    scope_pkg.is_none_or(|pkg| base_package(&rule.owner) == Some(pkg))
}

fn rule_health_entry_in_scope(entry: &RuleHealthEntry, scope_pkg: Option<&str>) -> bool {
    scope_pkg.is_none_or(|pkg| base_package(&entry.owner) == Some(pkg))
}

fn pending_rule_health_keys_for_package(
    pkg: &str,
    lifecycle: RuleHealthLifecycle,
    current_boot_id: &str,
    state: &DaemonState,
) -> BTreeSet<String> {
    state
        .rule_health
        .iter()
        .filter(|(_, entry)| {
            base_package(&entry.owner) == Some(pkg)
                && rule_health_status_is_observable(entry.status)
                && !rule_health_entry_checked_in_lifecycle(
                    entry,
                    lifecycle,
                    current_boot_id,
                )
        })
        .map(|(key, _)| key.clone())
        .collect()
}

fn rule_health_entry_checked_in_lifecycle(
    entry: &RuleHealthEntry,
    lifecycle: RuleHealthLifecycle,
    current_boot_id: &str,
) -> bool {
    if !entry.last_checked_boot_id.is_empty()
        && entry.last_checked_lifecycle_elapsed_ms > 0
    {
        return !current_boot_id.is_empty()
            && entry.last_checked_boot_id == current_boot_id
            && entry.last_checked_lifecycle_elapsed_ms == lifecycle.entered_elapsed_ms;
    }
    entry.last_checked_at > 0
        && entry.last_checked_at.saturating_mul(1000) >= lifecycle.entered_wall_ms
}

fn finish_rule_health_update(changed: bool, state: &mut DaemonState) -> io::Result<()> {
    if changed {
        state.rule_health_dirty = true;
    }
    if !state.rule_health_dirty {
        return Ok(());
    }
    write_rule_health(Path::new(RULE_HEALTH_FILE), &state.rule_health)?;
    state.rule_health_dirty = false;
    Ok(())
}

fn ensure_rule_health_loaded(state: &mut DaemonState) -> io::Result<()> {
    if state.rule_health_loaded {
        return Ok(());
    }
    state.rule_health = load_rule_health(Path::new(RULE_HEALTH_FILE))?;
    state.rule_health_loaded = true;
    Ok(())
}

fn runtime_rule_health_rules(rules: &[Rule], state: &DaemonState) -> Vec<Rule> {
    rules
        .iter()
        .filter(|rule| {
            let Some(entry) = rule_health_entry_from_rule(rule) else {
                return true;
            };
            let key = rule_health_entry_key(&entry);
            !state
                .rule_health
                .get(&key)
                .is_some_and(|health| health.status == RuleHealthStatus::Missed)
        })
        .cloned()
        .collect()
}

fn update_rule_health(
    rules: &[Rule],
    hits: &[ProcHit],
    full_scan_evidence: Option<&FullScanEvidence>,
    scope_pkg: Option<&str>,
    state: &mut DaemonState,
) -> io::Result<()> {
    ensure_rule_health_loaded(state)?;

    let now_wall = unix_now_secs();
    let now_elapsed = elapsed_realtime_ms();
    let mut active_rules = HashMap::<String, RuleHealthEntry>::new();
    for rule in rules
        .iter()
        .filter(|rule| !rule.auto && rule_health_rule_in_scope(rule, scope_pkg))
    {
        let Some(entry) = rule_health_entry_from_rule(rule) else {
            continue;
        };
        active_rules.insert(rule_health_entry_key(&entry), entry);
    }

    let old_keys = state
        .rule_health
        .iter()
        .filter(|(_, entry)| rule_health_entry_in_scope(entry, scope_pkg))
        .map(|(key, _)| key.clone())
        .collect::<HashSet<_>>();
    let new_keys = active_rules
        .keys()
        .filter(|key| !old_keys.contains(*key))
        .cloned()
        .collect::<HashSet<_>>();
    let mut changed = old_keys.len() != active_rules.len();

    state.rule_health.retain(|key, entry| {
        !rule_health_entry_in_scope(entry, scope_pkg) || active_rules.contains_key(key)
    });
    for (key, fresh) in active_rules {
        match state.rule_health.get_mut(&key) {
            Some(existing) => {
                // CPU 范围变化只更新展示行，不改变状态或重新计时。
                if existing.rule_line != fresh.rule_line {
                    existing.rule_line = fresh.rule_line;
                    changed = true;
                }
            }
            None => {
                state.rule_health.insert(key, fresh);
                changed = true;
            }
        }
    }

    if !state.rule_health.values().any(|entry| {
        rule_health_entry_in_scope(entry, scope_pkg)
            && rule_health_status_is_observable(entry.status)
    }) {
        state.health_active_packages.clear();
        state.health_session_started.clear();
        state.health_session_checked.clear();
        state.health_session_full_scan_at.clear();
        state.health_session_full_scan_attempted.clear();
        state.health_session_lifecycle.clear();
        state.health_session_eligible_keys.clear();
        return finish_rule_health_update(changed, state);
    }

    // 真实命中不要求必须位于观察窗口内，但只确认仍处于 pending 的规则。连续两次
    // 未命中进入 missed 后即为终态，后续启动不再重复观察或自动恢复。
    let matched_keys = collect_matched_rule_health_keys(hits);
    for key in matched_keys {
        let Some(entry) = state.rule_health.get_mut(&key) else {
            continue;
        };
        if !rule_health_entry_in_scope(entry, scope_pkg)
            || !rule_health_status_is_observable(entry.status)
        {
            continue;
        }
        println!("[RS] 规则健康已确认: {}", entry.rule_line);
        entry.status = RuleHealthStatus::Valid;
        entry.miss_count = 0;
        entry.last_matched_at = now_wall;
        entry.last_checked_at = now_wall;
        changed = true;
    }

    let pending_packages = state
        .rule_health
        .values()
        .filter(|entry| {
            rule_health_entry_in_scope(entry, scope_pkg)
                && rule_health_status_is_observable(entry.status)
        })
        .filter_map(|entry| base_package(&entry.owner).map(str::to_string))
        .collect::<BTreeSet<_>>();

    // 全部规则都已 valid/missed 时停止读取前台状态文件，也清掉仅用于观察的会话内存。
    if pending_packages.is_empty() {
        state.health_active_packages.clear();
        state.health_session_started.clear();
        state.health_session_checked.clear();
        state.health_session_full_scan_at.clear();
        state.health_session_full_scan_attempted.clear();
        state.health_session_lifecycle.clear();
        state.health_session_eligible_keys.clear();
        return finish_rule_health_update(changed, state);
    }

    let foreground = read_rule_health_foreground_state(now_elapsed);
    let current_boot_id = rule_health_current_boot_id(now_elapsed)
        .unwrap_or_default()
        .to_string();

    // 旧版 9 列状态没有单调时钟生命周期身份。首次遇到仍被旧墙上时间判定为
    // 已检查的生命周期时完成迁移，避免系统时间回拨后永久跳过后续启动。
    for (pkg, lifecycle) in &foreground.lifecycle_packages {
        if !pending_packages.contains(pkg) {
            continue;
        }
        for entry in state.rule_health.values_mut().filter(|entry| {
            base_package(&entry.owner) == Some(pkg.as_str())
                && rule_health_status_is_observable(entry.status)
                && entry.last_checked_boot_id.is_empty()
                && entry.last_checked_lifecycle_elapsed_ms == 0
                && entry.last_checked_at > 0
                && entry.last_checked_at.saturating_mul(1000) >= lifecycle.entered_wall_ms
        }) {
            if !current_boot_id.is_empty() {
                entry.last_checked_boot_id = current_boot_id.clone();
                entry.last_checked_lifecycle_elapsed_ms = lifecycle.entered_elapsed_ms;
                changed = true;
            }
        }
    }

    // 只有观察截止点之后完成的全量扫描才能作为负向结论的证据。证据与本次
    // session 绑定；helper 快照稍晚到达时可以复用，避免到期后每 2 秒全扫 /proc。
    if let Some(evidence) = full_scan_evidence {
        let scanned_at = evidence.completed_at;
        for (pkg, started) in &state.health_session_started {
            let deadline = rule_health_observation_deadline(*started);
            let already_complete = state
                .health_session_full_scan_at
                .get(pkg)
                .is_some_and(|existing| *existing >= deadline);
            if scanned_at < deadline
                || already_complete
                || !pending_packages.contains(pkg)
                || state.health_session_checked.contains(pkg)
            {
                continue;
            }
            if evidence.global_complete && !evidence.incomplete_packages.contains(pkg) {
                state
                    .health_session_full_scan_at
                    .entry(pkg.clone())
                    .or_insert(scanned_at);
                state.health_session_full_scan_attempted.remove(pkg);
            } else if state.health_session_full_scan_attempted.insert(pkg.clone()) {
                println!(
                    "[RS] 规则健康观察保留: 应用={} 原因=到期全扫不完整，本生命周期不再强制重扫",
                    pkg
                );
            }
        }
    }

    // 生命周期变化、可靠退出或 scope 离开一律取消未结算窗口。helper 的 callback
    // 时间只是“观察到退出”的时间，不是实际 transition 上界，因此退出路径绝不结算 miss。
    for pkg in state.health_active_packages.clone() {
        let started = state.health_session_started.get(&pkg).copied();
        if !foreground.reliable {
            // helper 暂时不可用只能取消本轮负向结论，不能等同于应用已经退出。
            // 保留 active + started 作为本次前台生命周期门闩；恢复后仍在前台时
            // 不会重开窗口，直到可靠 exit 或明确不在前台后才允许下一生命周期观察。
            if started.is_some() && !state.health_session_checked.contains(&pkg) {
                state.health_session_checked.insert(pkg.clone());
                state.health_session_full_scan_at.remove(&pkg);
                state.health_session_eligible_keys.remove(&pkg);
                println!(
                    "[RS] 规则健康观察取消: 应用={} 原因=前台 helper 暂不可用，本次启动不累计未命中",
                    pkg
                );
            }
            continue;
        }
        let session_lifecycle = state.health_session_lifecycle.get(&pkg).copied();
        let current_lifecycle = foreground.lifecycle(&pkg);
        let lifecycle_changed = session_lifecycle != current_lifecycle;
        let exited = session_lifecycle.and_then(|lifecycle| {
            foreground.exited_at(&pkg).filter(|exited| {
                *exited > lifecycle.entered_elapsed_ms
                    && started.is_none_or(|started| *exited > started)
            })
        });
        let still_present =
            pending_packages.contains(&pkg) && foreground.contains(&pkg) && !lifecycle_changed;
        if still_present && exited.is_none() {
            continue;
        }
        let started = state.health_session_started.remove(&pkg);
        let checked = state.health_session_checked.remove(&pkg);
        state.health_active_packages.remove(&pkg);
        state.health_session_eligible_keys.remove(&pkg);
        state.health_session_full_scan_at.remove(&pkg);
        state.health_session_full_scan_attempted.remove(&pkg);
        state.health_session_lifecycle.remove(&pkg);
        if started.is_some() && !checked {
            let reason = if exited.is_some() {
                "前台生命周期中断"
            } else if lifecycle_changed {
                "前台生命周期已变化"
            } else {
                "应用已离开可靠前台范围"
            };
            println!("[RS] 规则健康观察取消: 应用={} 原因={}", pkg, reason);
        }
    }

    // 进入会话时只接受 helper 的 focused_package；visible_packages 只用于分屏/浮窗场景
    // 维持已开始的会话。helper 不可用时不产生负向观察结果。
    for pkg in &pending_packages {
        let Some(lifecycle) = foreground.lifecycle(pkg) else {
            continue;
        };
        let should_enter = foreground.can_start(pkg);
        let in_scope = foreground.contains(pkg);
        let new_pkg_keys = new_keys
            .iter()
            .filter(|key| {
                state.rule_health.get(*key).is_some_and(|entry| {
                    base_package(&entry.owner) == Some(pkg.as_str())
                        && rule_health_status_is_observable(entry.status)
                && !rule_health_entry_checked_in_lifecycle(
                    entry,
                    lifecycle,
                    &current_boot_id,
                )
                })
            })
            .cloned()
            .collect::<BTreeSet<_>>();

        if !state.health_active_packages.contains(pkg) && should_enter {
            state.health_active_packages.insert(pkg.clone());
            let eligible = pending_rule_health_keys_for_package(
                pkg,
                lifecycle,
                &current_boot_id,
                state,
            );
            if start_rule_health_observation(pkg, lifecycle, eligible, now_wall, now_elapsed, state)
            {
                changed = true;
            }
        } else if state.health_active_packages.contains(pkg) && !new_pkg_keys.is_empty() && in_scope
        {
            // 同一生命周期内新增规则时，从新增时重新给足窗口；未结算的旧规则可随窗口
            // 一起延后，但已经结算过的旧规则不会再次进入 eligible 集合。
            let mut eligible = state
                .health_session_eligible_keys
                .get(pkg)
                .cloned()
                .unwrap_or_default();
            eligible.extend(new_pkg_keys);
            if start_rule_health_observation(pkg, lifecycle, eligible, now_wall, now_elapsed, state)
            {
                changed = true;
            }
        }
    }

    let due_packages = state
        .health_session_started
        .iter()
        .filter(|(pkg, started)| {
            let deadline = rule_health_observation_deadline(**started);
            let full_scan_at = state.health_session_full_scan_at.get(*pkg).copied();
            let has_full_scan_evidence =
                full_scan_at.is_some_and(|scanned_at| scanned_at >= deadline);
            // 正常到期结算必须由全扫之后生成的 helper 快照证明应用仍在前台范围。
            // 否则应用可能已在全扫前退出，而 daemon 仍读到了旧的 contains=true 快照。
            let snapshot_covers_full_scan =
                full_scan_at.is_some_and(|scanned_at| foreground.updated_elapsed_ms >= scanned_at);
            rule_health_observation_complete(**started, now_elapsed)
                && foreground.contains(pkg)
                && snapshot_covers_full_scan
                && has_full_scan_evidence
                && !state.health_session_checked.contains(*pkg)
        })
        .map(|(pkg, _)| pkg.clone())
        .collect::<Vec<_>>();

    for pkg in due_packages {
        let eligible = state
            .health_session_eligible_keys
            .remove(&pkg)
            .unwrap_or_default();
        let lifecycle = state.health_session_lifecycle.get(&pkg).copied();
        let (first_miss, confirmed_miss) = finish_rule_health_observation(
            &pkg,
            &eligible,
            now_wall,
            lifecycle,
            &current_boot_id,
            state,
        );
        // 保留本次前台生命周期的 started。即使已经完成一次观察，helper 后续记录的
        // 快速退出再进入事件仍需结束旧生命周期，才能在下一次启动重新观察 miss=1 规则。
        state.health_session_full_scan_at.remove(&pkg);
        state.health_session_full_scan_attempted.remove(&pkg);
        state.health_session_checked.insert(pkg.clone());
        if first_miss + confirmed_miss > 0 {
            changed = true;
            println!(
                "[RS] 规则健康观察结束: 应用={} 首次待复核={} 连续未命中={}，会话保持到应用离开",
                pkg, first_miss, confirmed_miss
            );
        }
    }

    finish_rule_health_update(changed, state)
}

fn rule_health_full_scan_due(state: &DaemonState) -> bool {
    let now_elapsed = elapsed_realtime_ms();
    let retry_allowed = state
        .last_health_full_scan_attempt_elapsed_ms
        .is_none_or(|last| {
            now_elapsed >= last
                && now_elapsed.saturating_sub(last) >= RULE_HEALTH_FULL_SCAN_RETRY_MS
        });
    if !retry_allowed {
        return false;
    }
    rule_health_full_scan_pending_at(state, now_elapsed)
}

fn rule_health_full_scan_pending_at(state: &DaemonState, now_elapsed: u64) -> bool {
    state.health_session_started.iter().any(|(pkg, started)| {
        let deadline = rule_health_observation_deadline(*started);
        now_elapsed >= deadline
            && !state.health_session_checked.contains(pkg)
            && !state.health_session_full_scan_attempted.contains(pkg)
            && state
                .health_session_full_scan_at
                .get(pkg)
                .is_none_or(|scanned_at| *scanned_at < deadline)
    })
}

fn rule_health_observation_deadline(started_elapsed: u64) -> u64 {
    started_elapsed.saturating_add(RULE_HEALTH_OBSERVE_SECS.saturating_mul(1000))
}

fn rule_health_observation_complete(started_elapsed: u64, ended_elapsed: u64) -> bool {
    ended_elapsed >= started_elapsed
        && ended_elapsed - started_elapsed >= RULE_HEALTH_OBSERVE_SECS * 1000
}

fn finish_rule_health_observation(
    pkg: &str,
    eligible_keys: &BTreeSet<String>,
    now_wall: u64,
    lifecycle: Option<RuleHealthLifecycle>,
    current_boot_id: &str,
    state: &mut DaemonState,
) -> (usize, usize) {
    let mut first_miss = 0usize;
    let mut confirmed_miss = 0usize;
    for entry in state
        .rule_health
        .iter_mut()
        .filter(|(key, entry)| {
            eligible_keys.contains(*key)
                && base_package(&entry.owner) == Some(pkg)
                && rule_health_status_is_observable(entry.status)
        })
        .map(|(_, entry)| entry)
    {
        entry.miss_count = entry.miss_count.saturating_add(1);
        entry.status = if entry.miss_count >= 2 {
            confirmed_miss += 1;
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

/* 配置应用进入新的可靠前台生命周期时只补一次全量发现扫描。
 * 这覆盖 sysinfo 进程总数净值不变的进程替换，又不会按 2 秒轮次重复遍历 /proc。 */
fn foreground_discovery_scan_due(
    rules: &[Rule],
    scope_pkg: Option<&str>,
    state: &mut DaemonState,
) -> bool {
    state.foreground_scan_lifecycles.retain(|pkg, _| {
        scope_pkg.is_none_or(|scope| scope == pkg)
            && rules
                .iter()
                .any(|rule| base_package(&rule.owner) == Some(pkg.as_str()))
    });
    if rules.is_empty() {
        return false;
    }
    let now_elapsed = elapsed_realtime_ms();
    let foreground = read_rule_health_foreground_state(now_elapsed);
    let pkg = foreground.focused_package.as_str();
    if pkg.is_empty()
        || !foreground.can_start(pkg)
        || scope_pkg.is_some_and(|scope| scope != pkg)
        || !rules.iter().any(|rule| base_package(&rule.owner) == Some(pkg))
    {
        return false;
    }
    let Some(lifecycle) = foreground.lifecycle(pkg) else {
        return false;
    };
    let pkg = pkg.to_string();
    if state
        .foreground_scan_lifecycles
        .get(&pkg)
        .is_some_and(|previous| *previous == lifecycle.entered_elapsed_ms)
    {
        return false;
    }
    let discovery_deadline = lifecycle
        .entered_elapsed_ms
        .saturating_add(FOREGROUND_DISCOVERY_DELAY_MS);
    if now_elapsed < discovery_deadline {
        return false;
    }
    if state
        .last_full_scan_elapsed_ms
        .is_some_and(|scanned_at| scanned_at >= discovery_deadline)
    {
        state
            .foreground_scan_lifecycles
            .insert(pkg, lifecycle.entered_elapsed_ms);
        return false;
    }
    if state
        .last_foreground_discovery_scan_elapsed_ms
        .is_some_and(|last| {
            now_elapsed >= last
                && now_elapsed.saturating_sub(last) < FOREGROUND_DISCOVERY_COOLDOWN_MS
        })
    {
        return false;
    }
    state
        .foreground_scan_lifecycles
        .insert(pkg, lifecycle.entered_elapsed_ms);
    state.last_foreground_discovery_scan_elapsed_ms = Some(now_elapsed);
    true
}

fn start_rule_health_observation(
    pkg: &str,
    lifecycle: RuleHealthLifecycle,
    eligible_keys: BTreeSet<String>,
    now_wall: u64,
    now_elapsed: u64,
    state: &mut DaemonState,
) -> bool {
    state
        .health_session_started
        .insert(pkg.to_string(), now_elapsed);
    state
        .health_session_lifecycle
        .insert(pkg.to_string(), lifecycle);
    state.health_session_full_scan_at.remove(pkg);
    state.health_session_full_scan_attempted.remove(pkg);
    if eligible_keys.is_empty() {
        state.health_session_checked.insert(pkg.to_string());
        state.health_session_eligible_keys.remove(pkg);
        println!(
            "[RS] 规则健康本生命周期已结算: 应用={}，等待下一次启动",
            pkg
        );
        return false;
    }

    state.health_session_checked.remove(pkg);
    state
        .health_session_eligible_keys
        .insert(pkg.to_string(), eligible_keys.clone());
    let mut changed = false;
    for entry in state
        .rule_health
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
    println!(
        "[RS] 规则健康观察开始: 应用={} 窗口={}秒",
        pkg, RULE_HEALTH_OBSERVE_SECS
    );
    changed
}

fn read_rule_health_foreground_state(now_elapsed: u64) -> RuleHealthForegroundState {
    let Ok(raw) = fs::read_to_string(FOREGROUND_TASK_STATE_FILE) else {
        return RuleHealthForegroundState::default();
    };
    let mut version = 0u32;
    let mut boot_id = String::new();
    let mut status = String::new();
    let mut mode = String::new();
    let mut selection = String::new();
    let mut focused_package = String::new();
    let mut visible_packages = BTreeSet::new();
    let mut lifecycle_packages = HashMap::new();
    let mut exited_packages = HashMap::new();
    let mut updated_elapsed_ms = 0u64;

    for line in raw.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "version" => version = value.trim().parse().unwrap_or(0),
            "boot_id" => boot_id = value.trim().to_string(),
            "status" => status = value.trim().to_string(),
            "mode" => mode = value.trim().to_string(),
            "selection" => selection = value.trim().to_string(),
            "focused_package" => focused_package = value.trim().to_string(),
            "visible_packages" => {
                visible_packages.extend(
                    value
                        .split(',')
                        .map(str::trim)
                        .filter(|pkg| !pkg.is_empty())
                        .map(str::to_string),
                );
            }
            "lifecycle_packages" => {
                for record in value
                    .split(',')
                    .map(str::trim)
                    .filter(|item| !item.is_empty())
                {
                    let Some((owner_and_elapsed, entered_wall)) = record.rsplit_once('@') else {
                        continue;
                    };
                    let Some((pkg, entered_elapsed)) = owner_and_elapsed.rsplit_once('@') else {
                        continue;
                    };
                    let (Ok(entered_elapsed_ms), Ok(entered_wall_ms)) =
                        (entered_elapsed.parse::<u64>(), entered_wall.parse::<u64>())
                    else {
                        continue;
                    };
                    if !pkg.is_empty() && entered_elapsed_ms > 0 && entered_wall_ms > 0 {
                        lifecycle_packages.insert(
                            pkg.to_string(),
                            RuleHealthLifecycle {
                                entered_elapsed_ms,
                                entered_wall_ms,
                            },
                        );
                    }
                }
            }
            "exited_packages" => {
                for record in value
                    .split(',')
                    .map(str::trim)
                    .filter(|item| !item.is_empty())
                {
                    let Some((pkg, elapsed)) = record.rsplit_once('@') else {
                        continue;
                    };
                    if let Ok(elapsed) = elapsed.parse::<u64>() {
                        exited_packages.insert(pkg.to_string(), elapsed);
                    }
                }
            }
            "updated_elapsed_ms" => updated_elapsed_ms = value.trim().parse().unwrap_or(0),
            _ => {}
        }
    }

    let fresh = updated_elapsed_ms > 0
        && now_elapsed >= updated_elapsed_ms
        && now_elapsed.saturating_sub(updated_elapsed_ms) <= FOREGROUND_TASK_MAX_AGE_MS;
    let boot_matches = rule_health_current_boot_id(now_elapsed) == Some(boot_id.as_str());
    let reliable = version >= 2
        && boot_matches
        && fresh
        && mode == "listener"
        && matches!(status.as_str(), "ok" | "empty");
    if !reliable {
        return RuleHealthForegroundState::default();
    }
    lifecycle_packages.retain(|_, lifecycle| {
        lifecycle.entered_elapsed_ms <= updated_elapsed_ms
            && lifecycle.entered_elapsed_ms <= now_elapsed
    });
    let observable = status == "ok"
        && !focused_package.is_empty()
        && matches!(
            selection.as_str(),
            "focused" | "default-visible" | "visible"
        );
    RuleHealthForegroundState {
        reliable: true,
        observable,
        selection,
        focused_package,
        visible_packages,
        lifecycle_packages,
        exited_packages,
        updated_elapsed_ms,
    }
}

fn rule_health_current_boot_id(now_elapsed: u64) -> Option<&'static str> {
    if let Some(value) = CURRENT_BOOT_ID.get() {
        return Some(value.as_str());
    }
    let retry_after = BOOT_ID_RETRY_AFTER_ELAPSED_MS.load(Ordering::Relaxed);
    if retry_after > 0 && now_elapsed < retry_after {
        return None;
    }
    let value = fs::read_to_string(BOOT_ID_FILE)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());
    if let Some(value) = value {
        let _ = CURRENT_BOOT_ID.set(value);
        return CURRENT_BOOT_ID.get().map(String::as_str);
    }
    BOOT_ID_RETRY_AFTER_ELAPSED_MS.store(
        now_elapsed.saturating_add(BOOT_ID_READ_RETRY_MS),
        Ordering::Relaxed,
    );
    None
}

fn rule_health_entry_from_rule(rule: &Rule) -> Option<RuleHealthEntry> {
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

fn collect_matched_rule_health_keys(hits: &[ProcHit]) -> HashSet<String> {
    let mut keys = HashSet::new();
    for hit in hits {
        if hit.cmdline.contains(':') {
            keys.insert(rule_health_key('P', &hit.cmdline, ""));
        }
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

fn rule_health_entry_key(entry: &RuleHealthEntry) -> String {
    rule_health_key(entry.kind, &entry.owner, &entry.target)
}

fn rule_health_key(kind: char, owner: &str, target: &str) -> String {
    format!(
        "{}\t{}\t{}",
        kind.to_ascii_uppercase(),
        owner.trim(),
        target.trim()
    )
}

fn unix_now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn elapsed_realtime_ms() -> u64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    let rc = unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut ts) };
    if rc != 0 || ts.tv_sec < 0 || ts.tv_nsec < 0 {
        return 0;
    }
    ts.tv_sec as u64 * 1000 + ts.tv_nsec as u64 / 1_000_000
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn elapsed_realtime_ms() -> u64 {
    // 非 Android/Linux 仅用于宿主机编译检查，不会读取设备 helper 状态。
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn load_rule_health(path: &Path) -> io::Result<HashMap<String, RuleHealthEntry>> {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(HashMap::new()),
        Err(err) => return Err(err),
    };
    let mut entries = HashMap::new();
    for raw in text.lines() {
        let parts = raw.split('\t').collect::<Vec<_>>();
        if parts.len() < 9 {
            continue;
        }
        let Some(kind) = parts[0].chars().next() else {
            continue;
        };
        let owner = unescape_rule_health_field(parts[1]);
        let target = unescape_rule_health_field(parts[2]);
        let status = match parts[3] {
            "valid" => RuleHealthStatus::Valid,
            "missed" => RuleHealthStatus::Missed,
            _ => RuleHealthStatus::Pending,
        };
        let has_lifecycle_identity = parts.len() >= 11;
        let entry = RuleHealthEntry {
            kind,
            owner,
            target,
            status,
            miss_count: parts[4].parse().unwrap_or(0),
            first_observed_at: parts[5].parse().unwrap_or(0),
            last_matched_at: parts[6].parse().unwrap_or(0),
            last_checked_at: parts[7].parse().unwrap_or(0),
            last_checked_boot_id: if has_lifecycle_identity {
                unescape_rule_health_field(parts[8])
            } else {
                String::new()
            },
            last_checked_lifecycle_elapsed_ms: if has_lifecycle_identity {
                parts[9].parse().unwrap_or(0)
            } else {
                0
            },
            rule_line: unescape_rule_health_field(
                &parts[if has_lifecycle_identity { 10 } else { 8 }..].join("\t"),
            ),
        };
        entries.insert(rule_health_entry_key(&entry), entry);
    }
    Ok(entries)
}

fn write_rule_health(path: &Path, entries: &HashMap<String, RuleHealthEntry>) -> io::Result<()> {
    let mut rows = entries.values().collect::<Vec<_>>();
    rows.sort_by_key(|entry| (entry.owner.as_str(), entry.kind, entry.target.as_str()));
    let mut output = String::new();
    for entry in rows {
        let status = match entry.status {
            RuleHealthStatus::Pending => "pending",
            RuleHealthStatus::Valid => "valid",
            RuleHealthStatus::Missed => "missed",
        };
        output.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            entry.kind,
            escape_rule_health_field(&entry.owner),
            escape_rule_health_field(&entry.target),
            status,
            entry.miss_count,
            entry.first_observed_at,
            entry.last_matched_at,
            entry.last_checked_at,
            escape_rule_health_field(&entry.last_checked_boot_id),
            entry.last_checked_lifecycle_elapsed_ms,
            escape_rule_health_field(&entry.rule_line)
        ));
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = path.with_extension(format!("tsv.tmp.{}", std::process::id()));
    let commit_result = (|| -> io::Result<()> {
        let mut file = fs::OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&temp)?;
        file.write_all(output.as_bytes())?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temp, path)
    })();
    if let Err(err) = commit_result {
        let _ = fs::remove_file(&temp);
        return Err(err);
    }
    if let Some(parent) = path.parent() {
        // 部分 Android 特殊挂载不支持目录 fsync；rename 已提交后不应因此每轮重写。
        let _ = fs::File::open(parent).and_then(|dir| dir.sync_all());
    }
    Ok(())
}

fn escape_rule_health_field(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '\\' => output.push_str("\\\\"),
            '\t' => output.push_str("\\t"),
            '\n' => output.push_str("\\n"),
            _ => output.push(ch),
        }
    }
    output
}

fn unescape_rule_health_field(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(ch) = chars.next() {
        if ch != '\\' {
            output.push(ch);
            continue;
        }
        match chars.next() {
            Some('t') => output.push('\t'),
            Some('n') => output.push('\n'),
            Some('\\') => output.push('\\'),
            Some(other) => {
                output.push('\\');
                output.push(other);
            }
            None => output.push('\\'),
        }
    }
    output
}
