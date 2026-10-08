//! 单个线程失败时保留持久恢复意图，不中断其他线程的处理。
use super::*;

pub(super) fn apply(batch: &mut Batch, requests: &[Request], maintain: bool) -> io::Result<Changes> {
    if requests.len() > super::super::super::live_policy::MAX_ACTIVE { return Err(io::Error::other("活动线程批次过大")); }
    let wanted: BTreeMap<_, _> = requests.iter().map(|r| (r.identity, *r)).collect();
    if wanted.len() != requests.len() { return Err(io::Error::other("活动线程批次重复")); }
    // 快速归属检查不能接纳新线程，也不能仅因为日志项仍需恢复，
    // 就重新接管继承限制或正在退出的线程。
    let requests: Vec<_> = requests.iter().copied().filter(|request| !maintain ||
        batch.entries.get(&request.identity).is_some_and(|entry| entry.inherited_from.is_none())).collect();
    let wanted: BTreeMap<_, _> = requests.iter().map(|r| (r.identity, *r)).collect();
    let now = Instant::now();
    let mut changes = Changes::default();
    let mut dirty = false;
    let retiring: BTreeSet<_> = batch.entries.keys().filter(|key| !maintain && !wanted.contains_key(key)).copied().collect();
    if !retiring.is_empty() { inherited::discover(&mut batch.entries, Some(&retiring), &mut batch.history)?; }
    // 恢复操作彼此独立；失败项保留在日志中并继续重试，
    // 不能阻止下一个符合条件的线程被接管。
    for key in batch.entries.keys().filter(|key| !maintain && !wanted.contains_key(key)).copied().collect::<Vec<_>>() {
        if batch.retries.get(&key).is_some_and(|r| r.waiting(None, now)) { continue; }
        let entry = batch.entries[&key].clone();
        if restore_observed(&entry, &mut batch.history).is_err() {
            // 恢复过程已经记录本次失败；这里只进行退避，
            // 不为同一次尝试重复制造失败记录。
            batch.retry(key);
            changes.failed.push(key);
            continue;
        }
        let cleaned = if entry.original_cpuset.is_none() { Ok(true) }
            else { cpuset::empty_and_cleanup(&entry.owned_root, &key) };
        match cleaned {
            Ok(true) => { batch.forget(&key); dirty = true; }
            // 归属线程已恢复，但继承限制的子线程仍需等到本批次后续处理，
            // 因此继续在日志中保留其来源。
            Ok(false) => batch.retry(key),
            Err(_) => batch.failed(key, None, "cpuset_cleanup_failed", &mut changes),
        }
    }
    if dirty { persist(&batch.entries)?; }
    if requests.is_empty() {
        if !maintain {
            changes.pending = batch.entries.len();
            if batch.entries.is_empty() { batch.guard.take(); }
        }
        return Ok(changes);
    }
    let token = batch.guard.as_ref().map_or_else(String::new, |guard| guard.token.clone());
    let boot = boot_id()?;
    let available = online_mask().and_then(|online| cpuset::available(online).ok());
    let mut intended = batch.entries.clone();
    let mut plans = Vec::new();
    dirty = false;
    for request in &requests {
        let key = request.identity;
        let retry_target = Some(RetryTarget { desired: request.mask, available });
        if batch.retries.get(&key).is_some_and(|r| r.waiting(retry_target, now)) { continue; }
        let old = batch.entries.get(&key);
        let mut record = Record { boot: boot.clone(), token: token.clone(), pid: key.0, tid: key.1,
            start: key.2, original: request.original, written: request.mask };
        match same_identity(&record) {
            Ok(false) => {
                batch.history.record(key, "exit", "unknown", batch.mask(&key), None, "thread_exit");
                // 归属目录中可能仍有子线程；应保留恢复基线，
                // 直到退出或恢复流程清空整个目录树。
                batch.verified.remove(&key); continue;
            }
            Err(_) => { batch.failed_observed(key, None, None, "identity_unavailable", retry_target, &mut changes); continue; }
            Ok(true) => (),
        }
        if available.is_none() || request.mask == 0 || request.mask & !available.unwrap_or(0) != 0 {
            batch.failed_observed(key, None, None, "cpuset_unavailable", retry_target, &mut changes); continue;
        }
        let before = match affinity(key.1) { Ok(mask) => mask, Err(_) => {
            batch.failed_observed(key, None, None, "readback_failed", retry_target, &mut changes); continue;
        }};
        let group = match cpuset::current(key.0, key.1) { Ok(group) => group, Err(_) => {
            batch.failed_observed(key, Some(before), None, "cpuset_readback_failed", retry_target, &mut changes); continue;
        }};
        let inherited_parent = cpuset::origin(&group).and_then(|origin| batch.entries.get(&origin))
            .filter(|parent| cpuset::owned_root(&group) == Some(parent.owned_root.as_str()));
        let baseline_group;
        if let Some(old) = old {
            record.original = old.record.original;
            if old.original_cpuset.is_none() {
                batch.failed_observed(key, Some(before), None, "recovery_baseline_missing", retry_target, &mut changes); continue;
            }
            baseline_group = old.original_cpuset.clone().unwrap();
        } else {
            if let Some(parent) = inherited_parent.filter(|parent| parent.original_cpuset.is_some()) {
                record.original = parent.record.original;
                baseline_group = parent.original_cpuset.clone().unwrap();
            } else if cpuset::owned_path(&group) {
                batch.failed_observed(key, Some(before), None, "recovery_baseline_missing", retry_target, &mut changes); continue;
            } else {
                record.original = before;
                baseline_group = crate::affinity::cpuset::normalized_owned_restore_cpuset(&group);
                if baseline_group != group {
                    // 新线程继承的自建限制不是接管前的真实基线，与静态接管一样取退回组范围。
                    // 已有租约保存的原始掩码则保留，不因改名而一律扩大。
                    record.original = match cpuset::mask(&baseline_group) {
                        Ok(mask) => mask,
                        Err(_) => {
                            batch.failed_observed(key, Some(before), None, "recovery_baseline_missing", retry_target, &mut changes);
                            continue;
                        }
                    };
                }
            }
            if intended.len() >= inherited::MAX_RECOVERY {
                batch.failed_observed(key, Some(before), None, "capacity_pending", retry_target, &mut changes); continue;
            }
        }
        let new = old.is_none_or(|entry| entry.inherited_from.is_some());
        let changed = old.is_some_and(|e| e.record.written != request.mask);
        let entry = Entry { record, previous: old.and_then(|e| changed.then_some(e.record.written)),
            original_cpuset: Some(baseline_group), owned_root: cpuset::root(), inherited_from: None };
        if new || changed || old.is_some_and(|e| e.inherited_from.is_some()) { intended.insert(key, entry); dirty = true; }
        plans.push((key, before, group, new, changed));
    }
    // 首次写入 cgroup tasks 前，每个受影响线程的两份恢复基线都已持久化，
    // 之后任何阶段崩溃都可恢复。
    if !intended.is_empty() {
        batch.ensure_guard()?;
        let token = &batch.guard.as_ref().unwrap().token;
        for entry in intended.values_mut() {
            if entry.record.token != *token { entry.record.token = token.clone(); dirty = true; }
        }
    }
    if dirty { persist(&intended)?; }
    batch.entries = intended;
    dirty = false;
    for (key, before, group, new, changed) in plans {
        let entry = batch.entries[&key].clone();
        let desired = entry.record.written;
        let target = cpuset::target(&key, desired);
        let drift = group != target || before != desired;
        if !new && !changed && !drift {
            batch.verified.insert(key); batch.retries.remove(&key); continue;
        }
        let mut reason = "cpuset_failed";
        let mut actual = None;
        let result = (|| {
            if !same_identity(&entry.record)? { return Err(io::Error::other("线程已退出")); }
            cpuset::prepare(&key, desired)?;
            if !same_identity(&entry.record)? { return Err(io::Error::other("线程已退出")); }
            if cpuset::current(key.0, key.1)? != target { cpuset::move_thread(key.1, &target)?; }
            reason = "cpuset_readback_failed";
            if !same_identity(&entry.record)? || cpuset::current(key.0, key.1)? != target {
                return Err(io::Error::other("专属 cpuset 读回失败"));
            }
            reason = "write_failed";
            #[cfg(test)]
            if batch.fail_after_cpuset == Some(key) { return Err(io::Error::other("测试：迁入后写入失败")); }
            if affinity(key.1)? != desired { write_affinity(key.1, desired)?; }
            reason = "readback_failed";
            actual = Some(affinity(key.1)?);
            if !same_identity(&entry.record)? || actual != Some(desired) || cpuset::current(key.0, key.1)? != target {
                return Err(io::Error::other("专属范围读回失败"));
            }
            Ok(())
        })();
        if result.is_err() {
            if let (Some(pkg), Err(error)) = (batch.history_package.as_deref(), &result) {
                crate::auto_affinity::diagnostics::failure(pkg, key, &error.to_string());
            }
            #[cfg(test)]
            eprintln!("auto takeover failed: identity={key:?} phase={reason} error={:?} requested={desired:x} before={before:x} readback={actual:?} now_online={:?} now_affinity={:?} now_group={:?}",
                result.as_ref().err(), online_mask(), affinity(key.1), cpuset::current(key.0, key.1));
            if matches!(same_identity(&entry.record), Ok(false)) {
                batch.history.record(key, "exit", "unknown", Some(before), actual, "thread_exit");
                batch.verified.remove(&key);
            } else { batch.failed_observed(key, Some(before), actual, reason,
                Some(RetryTarget { desired, available }), &mut changes); }
            continue;
        }
        batch.history.record(key, "assign", "qixia", Some(before), actual,
            if new { "cpuset_takeover" } else if changed { "affinity_changed" } else { "control_reasserted" });
        if batch.entries.get_mut(&key).unwrap().previous.take().is_some() { dirty = true; }
        batch.verified.insert(key); batch.retries.remove(&key);
        // 删除已空的旧掩码组，保留仍含后代线程的组。
        let _ = cpuset::cleanup_empty_groups(&entry.owned_root, &key, Some(&target));
        changes.written.push(key);
    }
    if dirty { persist(&batch.entries)?; }
    if !maintain { batch.retries.retain(|key, _| wanted.contains_key(key) || batch.entries.contains_key(key)); }
    changes.pending = wanted.keys().filter(|key| !batch.verified.contains(key)).count()
        + if maintain { 0 } else { batch.entries.keys().filter(|key| !wanted.contains_key(key)).count() };
    if batch.entries.is_empty() { batch.guard.take(); }
    Ok(changes)
}
