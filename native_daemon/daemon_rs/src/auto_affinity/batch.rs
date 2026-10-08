//! 所有当前受管线程共用一个崩溃守卫和一份持久日志。
use super::{
    affinity, atomic_write, boot_id, online_mask, same_identity, write_affinity, Record, JOURNAL,
};
use super::core_history::EventLog;
use super::cpuset;
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

pub(super) type Identity = (i32, i32, u64);
const MAX_JOURNAL_BYTES: usize = 4 * 1024 * 1024;
#[derive(Clone)]
pub(super) struct Entry {
    pub record: Record,
    // 预写意图在 sched_setaffinity 完成前也拥有旧掩码；
    // 日志写入与系统调用之间崩溃时，必须恢复该掩码。
    pub(super) previous: Option<u64>,
    pub(super) original_cpuset: Option<String>,
    // 持久化真实根路径，崩溃守卫执行前设置可能已经变化。
    pub(super) owned_root: String,
    // 未入选但继承限制的子线程，只能在仍位于原归属目录树中，
    // 或处于原恢复流程中途时执行恢复。
    pub(super) inherited_from: Option<Identity>,
}
impl Entry {
    fn encode(&self) -> String {
        if let Some(path) = &self.original_cpuset {
            let encoded: String = path.bytes().map(|b| format!("{b:02x}")).collect();
            let root: String = self.owned_root.bytes().map(|b| format!("{b:02x}")).collect();
            format!("{} {} {} {} {}\n", self.record.encode().trim_end().replacen("v1 ", "v4 ", 1),
                self.previous.map_or_else(|| "-".into(), |m| format!("{m:x}")), encoded,
                self.inherited_from.map_or_else(|| "-".into(), |key| format!("{}-{}-{}", key.0, key.1, key.2)), root)
        } else if let Some(previous) = self.previous {
            format!(
                "{} {:x}\n",
                self.record.encode().trim_end().replacen("v1 ", "v2 ", 1),
                previous
            )
        } else {
            self.record.encode()
        }
    }
}

fn decode_path(hex: &str) -> Option<String> {
    if hex.len() > 512 || hex.len() % 2 != 0 { return None; }
    let bytes: Option<Vec<u8>> = hex.as_bytes().chunks_exact(2).map(|pair| {
        std::str::from_utf8(pair).ok().and_then(|s| u8::from_str_radix(s, 16).ok())
    }).collect();
    String::from_utf8(bytes?).ok().filter(|path| cpuset::valid_path(path))
}

fn decode(text: &str) -> io::Result<BTreeMap<Identity, Entry>> {
    if text.len() > MAX_JOURNAL_BYTES {
        return Err(io::Error::other("恢复记录过大"));
    }
    let mut entries = BTreeMap::new();
    let mut token = None;
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        let fields: Vec<_> = line.split_whitespace().collect();
        let is_v4 = fields.first() == Some(&"v4");
        let has_cpuset = is_v4;
        let (record, previous, original_cpuset) = if has_cpuset && fields.len() == 12 {
            let mut old = fields[..8].join(" ");
            old.replace_range(..2, "v1");
            let path = decode_path(fields[9]).filter(|path| !cpuset::owned_path(path));
            (Record::decode_fields(&old, true), u64::from_str_radix(fields[8], 16).ok(), path)
        } else if fields.first() == Some(&"v2") && fields.len() == 9 {
            let mut old = fields[..8].join(" ");
            old.replace_range(..2, "v1");
            (
                Record::decode(&old),
                u64::from_str_radix(fields[8], 16).ok(),
                None,
            )
        } else {
            (Record::decode(line), None, None)
        };
        let record = record.ok_or_else(|| io::Error::other("自动分配恢复记录损坏"))?;
        let owned_root = if is_v4 {
            decode_path(fields[11]).filter(|root| cpuset::valid_root(root))
                .ok_or_else(|| io::Error::other("自动分配恢复根组无效"))?
        } else { cpuset::root() };
        let inherited_from = if has_cpuset && fields[10] != "-" {
            cpuset::identity(fields[10])
        } else { None };
        if has_cpuset && (original_cpuset.is_none() || (fields[8] != "-" && previous.is_none()))
            || has_cpuset && fields[10] != "-" && inherited_from.is_none()
            || fields.first() == Some(&"v2") && previous.is_none()
            || previous.is_some_and(|mask| mask == 0 || (!has_cpuset && mask & !record.original != 0))
            || token.as_ref().is_some_and(|t| t != &record.token)
        {
            return Err(io::Error::other("自动分配恢复记录不一致"));
        }
        token = Some(record.token.clone());
        let key = (record.pid, record.tid, record.start);
        if entries.insert(key, Entry { record, previous, original_cpuset, owned_root, inherited_from }).is_some() || entries.len() > inherited::MAX_RECOVERY {
            return Err(io::Error::other("自动分配恢复记录重复或过多"));
        }
    }
    if entries.is_empty() {
        return Err(io::Error::other("自动分配恢复记录为空"));
    }
    Ok(entries)
}

pub(super) fn recovery_cpuset_paths() -> io::Result<BTreeSet<String>> {
    use std::io::Read;
    let file = match fs::File::open(JOURNAL) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(BTreeSet::new()),
        Err(error) => return Err(error),
    };
    let mut text = String::new();
    file.take(MAX_JOURNAL_BYTES as u64 + 1).read_to_string(&mut text)?;
    recovery_paths_from_text(&text)
}

fn recovery_paths_from_text(text: &str) -> io::Result<BTreeSet<String>> {
    // 租约本身及继承线程仍依赖旧根，恢复目的地保留原值而非临时归一的副本。
    Ok(decode(text)?.into_values().flat_map(|entry|
        entry.original_cpuset.into_iter().chain(std::iter::once(entry.owned_root))).collect())
}

#[cfg(test)]
pub(super) fn restore(entry: &Entry) -> io::Result<()> {
    restore_observed(entry, &mut EventLog::default())
}

fn written_mask_matches(current: u64, written: u64, previous: Option<u64>, online: u64, parked: u64) -> bool {
    current != 0 && [Some(written), previous].into_iter().flatten().any(|mask| {
        current == mask & online || current == mask & online & !parked
    })
}

fn restore_observed(entry: &Entry, history: &mut EventLog) -> io::Result<()> {
    let mut normalized;
    let entry = if let Some(original) = entry.original_cpuset.as_deref() {
        let group = crate::affinity::cpuset::normalized_owned_restore_cpuset(original);
        if group != original {
            normalized = entry.clone();
            normalized.original_cpuset = Some(group);
            &normalized
        } else { entry }
    } else { entry };
    let r = &entry.record;
    let key = (r.pid, r.tid, r.start);
    let mut before = None;
    let mut after = None;
    let mut kind = "release";
    let mut source = "system";
    let mut reason = "released";
    let mut changed = false;
    let result = (|| {
        if boot_id()? != r.boot || !same_identity(r)? {
            kind = "exit";
            source = "unknown";
            reason = "thread_exit";
            return Ok(());
        }
        let online = online_mask().ok_or_else(|| io::Error::other("在线核心不可读"))?;
        let current = affinity(r.tid)?;
        before = Some(current);
        if let Some(origin) = entry.inherited_from {
            let group = cpuset::current(r.pid, r.tid)?;
            if (cpuset::origin(&group) != Some(origin) || cpuset::owned_root(&group) != Some(entry.owned_root.as_str()))
                && entry.original_cpuset.as_deref() != Some(group.as_str())
                && entry.original_cpuset.as_deref() != Some(crate::affinity::cpuset::normalized_owned_restore_cpuset(&group).as_str()) {
                kind = "external"; source = if cpuset::owned_path(&group) { "unknown" } else { "system" };
                after = Some(current); reason = "inherited_external"; return Ok(());
            }
            reason = "inherited_release";
        }
        if entry.original_cpuset.is_none() && current != r.written & online && !entry.previous.is_some_and(|old| current == old & online) {
            kind = "external";
            source = "system";
            before = Some(r.written);
            after = Some(current);
            reason = "external_override";
            return Ok(());
        }
        if entry.original_cpuset.is_some() {
            let actual_group = cpuset::current(r.pid, r.tid)?;
            // 释放时 ROM 配置可能已同时替换我们接管的两个维度。
            // 此时应保留更新后的系统策略，不能反复写入旧基线。
            // 如果仍保留我们接管的掩码，即使线程已离开自有 cpuset，
            // 也仍需恢复该掩码。
            if !cpuset::owned_path(&actual_group)
                && crate::affinity::cpuset::normalized_owned_restore_cpuset(&actual_group) == actual_group
                && !written_mask_matches(current, r.written, entry.previous,
                    online & cpuset::mask(&actual_group)?, cpuset::parked()?)
            {
                kind = "external"; source = "system";
                before = Some(r.written); after = Some(current);
                reason = "external_override";
                return Ok(());
            }
        }
        let mut original = r.original & online;
        if let Some(group) = entry.original_cpuset.as_deref() {
            // 恢复亲和性前，任何仍由我们接管的维度都必须先返回已保存的组；
            // 部分释放不能视为恢复完成。
            if !same_identity(r)? { kind = "exit"; source = "unknown"; reason = "thread_exit"; return Ok(()); }
            let actual_group = cpuset::current(r.pid, r.tid)?;
            if entry.inherited_from.is_some_and(|origin| cpuset::origin(&actual_group) != Some(origin)
                || cpuset::owned_root(&actual_group) != Some(entry.owned_root.as_str())) && actual_group != group
                && crate::affinity::cpuset::normalized_owned_restore_cpuset(&actual_group) != group {
                kind = "external"; source = if cpuset::owned_path(&actual_group) { "unknown" } else { "system" };
                reason = "inherited_external"; after = affinity(r.tid).ok(); return Ok(());
            }
            if actual_group != group { cpuset::move_thread(r.tid, group)?; changed = true; }
            if !same_identity(r)? { kind = "exit"; source = "unknown"; reason = "thread_exit"; return Ok(()); }
            if cpuset::current(r.pid, r.tid)? != group { return Err(io::Error::other("原 cpuset 恢复读回失败")); }
            original &= cpuset::mask(group)?;
        }
        if original == 0 { return Err(io::Error::other("原核心暂不可用")); }
        if !same_identity(r)? {
            kind = "exit";
            source = "unknown";
            reason = "thread_exit";
            return Ok(());
        }
        if entry.inherited_from.is_some() {
            let group = cpuset::current(r.pid, r.tid)?;
            if Some(group.as_str()) != entry.original_cpuset.as_deref() {
                kind = "external"; source = if cpuset::owned_path(&group) { "unknown" } else { "system" };
                reason = "inherited_external"; after = affinity(r.tid).ok(); return Ok(());
            }
        }
        if affinity(r.tid)? != original { write_affinity(r.tid, original)?; changed = true; }
        let actual = affinity(r.tid)?;
        after = Some(actual);
        let mask_restored = cpuset::restored_mask_matches(original, actual, cpuset::parked()?);
        if !mask_restored || !same_identity(r)?
            || entry.original_cpuset.as_deref().is_some_and(|group| cpuset::current(r.pid, r.tid).ok().as_deref() != Some(group)) {
            return Err(io::Error::other("恢复读回失败"));
        }
        Ok(())
    })();
    if result.is_err() {
        kind = "error";
        source = "unknown";
        reason = "restore_failed";
    }
    if changed || kind != "release" { history.record(key, kind, source, before, after, reason); }
    result
}

pub(super) fn recover(token: Option<&str>) -> io::Result<()> {
    recover_observed(token, &mut EventLog::default())
}

pub(super) fn recover_observed(token: Option<&str>, history: &mut EventLog) -> io::Result<()> {
    let text = match fs::read_to_string(JOURNAL) {
        Ok(text) => text,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e),
    };
    let mut entries = decode(&text)?;
    if token.is_some_and(|token| entries.values().any(|entry| entry.record.token != token)) {
        return Ok(());
    }
    // 日志中的归属线程也是来源依据，用于追踪继承其旧掩码组的所有线程，
    // 包括派生的子进程。
    inherited::discover(&mut entries, None, history)?;
    let committed = fs::read_to_string(JOURNAL)?;
    let current_boot = boot_id()?;
    let mut failure = None;
    let mut remaining = BTreeMap::new();
    for (&key, entry) in &entries {
        if let Err(error) = restore_observed(entry, history) {
            failure = Some(error);
            remaining.insert(key, entry.clone());
        }
    }
    for (&key, entry) in &entries {
        if entry.original_cpuset.is_some() && entry.record.boot == current_boot && !remaining.contains_key(&key) {
            match cpuset::empty_and_cleanup(&entry.owned_root, &key) {
                Ok(true) => (),
                result => {
                    remaining.insert(key, entry.clone());
                    failure = Some(result.err().unwrap_or_else(|| io::Error::new(io::ErrorKind::WouldBlock, "继承线程仍待恢复")));
                }
            }
        }
    }
    // 即使其他线程恢复被拒绝，也要移除已经完成的恢复项。
    // 先比较日志，避免旧守卫替换新控制器的日志。
    if fs::read_to_string(JOURNAL).ok().as_deref() == Some(&committed) { persist(&remaining)?; }
    if let Some(error) = failure {
        return Err(error);
    }
    Ok(())
}

fn persist(entries: &BTreeMap<Identity, Entry>) -> io::Result<()> {
    if entries.is_empty() {
        match fs::remove_file(JOURNAL) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e),
        }
    } else {
        let encoded = entries.values().map(Entry::encode).collect::<String>();
        if encoded.len() > MAX_JOURNAL_BYTES || entries.len() > inherited::MAX_RECOVERY {
            return Err(io::Error::other("自动恢复日志达到安全容量上限"));
        }
        atomic_write(
            JOURNAL,
            &encoded,
            true,
        )
    }
}

struct Guard {
    child: Child,
    started: Instant,
    token: String,
}
impl Guard {
    fn start() -> io::Result<Self> {
        let token = format!("{}-{}", std::process::id(), crate::elapsed_realtime_ms());
        let executable = std::env::current_exe()?;
        // 只有集成测试可通过独立二进制运行生产租约守卫；
        // 发布构建不允许使用环境变量覆盖。
        #[cfg(test)]
        let executable = std::env::var_os("QIXIA_TEST_GUARD_EXE")
            .map(std::path::PathBuf::from)
            .unwrap_or(executable);
        let mut child = Command::new(executable)
            .args(["--auto-affinity-lease", &token])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()?;
        if child.try_wait()?.is_some() {
            return Err(io::Error::other("恢复监督进程不可用"));
        }
        Ok(Self {
            child,
            started: Instant::now(),
            token,
        })
    }
    fn close(&mut self) {
        self.child.stdin.take();
        let _ = self.child.wait();
    }
}
impl Drop for Guard {
    fn drop(&mut self) {
        self.close();
    }
}

#[derive(Clone, Copy)]
pub(super) struct Request {
    pub identity: Identity,
    pub original: u64,
    pub mask: u64,
}
#[derive(Default)]
pub(super) struct Changes {
    pub written: Vec<Identity>,
    pub failed: Vec<Identity>,
    pub pending: usize,
}

#[derive(Clone)]
struct Retry {
    failures: u32,
    due: Instant,
    target: Option<RetryTarget>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct RetryTarget {
    desired: u64,
    available: Option<u64>,
}
impl Retry {
    fn delay(failures: u32) -> Duration {
        Duration::from_millis(match failures { 0 | 1 => 250, 2 => 500, 3 => 1000, 4 => 2000, _ => 4000 })
    }
    fn waiting(&self, target: Option<RetryTarget>, now: Instant) -> bool {
        self.target == target && now < self.due
    }
}

pub(super) struct Batch {
    entries: BTreeMap<Identity, Entry>,
    guard: Option<Guard>,
    history: EventLog,
    history_package: Option<String>,
    verified: BTreeSet<Identity>,
    retries: BTreeMap<Identity, Retry>,
    #[cfg(test)]
    fail_after_cpuset: Option<Identity>,
}
impl Default for Batch {
    fn default() -> Self {
        Self {
            entries: BTreeMap::new(), guard: None,
            history: EventLog::operations(), history_package: None,
            verified: BTreeSet::new(), retries: BTreeMap::new(),
            #[cfg(test)]
            fail_after_cpuset: None,
        }
    }
}
impl Batch {
    pub(super) fn capture_events(&mut self, package: &str) {
        self.history_package = Some(package.into());
    }
    pub(super) fn take_events(&mut self) -> Vec<crate::auto_history::CoreEvent> {
        self.history.take()
    }
    pub(super) fn records(&self) -> impl Iterator<Item = (&Identity, &Record)> {
        self.entries.iter().map(|(key, entry)| (key, &entry.record))
    }
    pub(super) fn mask(&self, key: &Identity) -> Option<u64> {
        self.entries.get(key).map(|e| e.record.written)
    }
    pub(super) fn original(&self, key: &Identity) -> Option<u64> {
        self.entries.get(key).map(|e| e.record.original)
    }
    pub(super) fn len(&self) -> usize {
        self.verified.len()
    }
    pub(super) fn ownership(&self, key: &Identity) -> Option<bool> {
        let actual = cpuset::current(key.0, key.1).ok()?;
        let Some(entry) = self.entries.get(key) else { return (!cpuset::owned_path(&actual)).then_some(false); };
        if !self.verified.contains(key) { return None; }
        if actual != cpuset::target_at(&entry.owned_root, key, entry.record.written) { return (!cpuset::owned_path(&actual)).then_some(false); }
        Some(true)
    }
    pub(super) fn owns(&self, key: &Identity) -> bool { self.ownership(key) == Some(true) }
    pub(super) fn reconcile_inherited(&mut self, identities: &[Identity]) -> io::Result<Vec<Identity>> {
        if self.entries.is_empty() { return Ok(identities.iter().take(512).copied().collect()); }
        let mut confirmed = Vec::new();
        let mut inherited = Vec::new();
        for &key in identities.iter().take(512) {
            if self.entries.contains_key(&key) { confirmed.push(key); continue; }
            if let Ok(group) = cpuset::current(key.0, key.1) {
                if cpuset::owned_path(&group) { inherited.push(key); } else { confirmed.push(key); }
            }
        }
        let fresh = inherited::adopt(&mut self.entries, &inherited, &mut self.history)?;
        let mut dirty = false;
        for key in fresh {
            // 从此处起，apply/release 负责持久重试；
            // 实时扫描无需反复探测已继承限制的子线程。
            confirmed.push(key);
            let entry = self.entries[&key].clone();
            if restore_observed(&entry, &mut self.history).is_ok() {
                self.forget(&key); dirty = true;
            }
        }
        if dirty { persist(&self.entries)?; }
        Ok(confirmed)
    }
    fn failed(&mut self, key: Identity, before: Option<u64>, reason: &str, changes: &mut Changes) {
        self.failed_observed(key, before, None, reason, None, changes)
    }
    fn failed_observed(&mut self, key: Identity, before: Option<u64>, after: Option<u64>, reason: &str, target: Option<RetryTarget>, changes: &mut Changes) {
        self.retry_target(key, target);
        self.history.record(key, "error", "unknown", before, after, reason);
        changes.failed.push(key);
    }
    fn retry(&mut self, key: Identity) {
        self.retry_target(key, None);
    }
    fn retry_target(&mut self, key: Identity, target: Option<RetryTarget>) {
        let count = self.retries.get(&key).filter(|retry| retry.target == target)
            .map_or(1, |retry| retry.failures.saturating_add(1));
        self.retries.insert(key, Retry { failures: count, due: Instant::now() + Retry::delay(count), target });
        self.verified.remove(&key);
    }
    fn forget(&mut self, key: &Identity) {
        self.entries.remove(key); self.verified.remove(key); self.retries.remove(key);
    }

    fn ensure_guard(&mut self) -> io::Result<()> {
        if let Some(guard) = self.guard.as_mut() {
            if guard.child.try_wait()?.is_some() {
                return Err(io::Error::other("恢复监督进程已退出"));
            }
            if guard.started.elapsed() < Duration::from_secs(100) {
                return Ok(());
            }
        }
        let next = Guard::start()?;
        let mut records = self.entries.clone();
        for entry in records.values_mut() {
            entry.record.token = next.token.clone();
        }
        // 关闭旧管道前先提交新令牌，确保旧守卫既不能恢复新批次，
        // 也不能打断已分配的线程。
        if !records.is_empty() {
            persist(&records)?;
        }
        self.entries = records;
        self.guard = Some(next);
        Ok(())
    }

    pub(super) fn apply(&mut self, requests: &[Request]) -> io::Result<Changes> {
        apply::apply(self, requests, false)
    }

    /// 只重新确认最新且符合条件的归属线程。接纳、退出及发现继承限制的
    /// 子线程，仍由负载采样器负责。
    pub(super) fn maintain(&mut self, requests: &[Request]) -> io::Result<Changes> {
        apply::apply(self, requests, true)
    }

    pub(super) fn release(&mut self) -> bool {
        let mut result = Ok(());
        for _ in 0..8 {
            result = recover_observed(self.guard.as_ref().map(|g| g.token.as_str()), &mut self.history);
            if !result.as_ref().is_err_and(|error| error.kind() == io::ErrorKind::WouldBlock) { break; }
        }
        if result.is_err() {
            for (&key, entry) in &self.entries {
                self.history.record(key, "error", "unknown", Some(entry.record.written), None, "recovery_failed");
            }
        }
        self.guard.take();
        if result.is_ok() || !std::path::Path::new(JOURNAL).exists() {
            self.entries.clear();
            self.verified.clear(); self.retries.clear();
        } else if let Ok(text) = fs::read_to_string(JOURNAL) {
            if let Ok(remaining) = decode(&text) { self.entries = remaining; }
            self.verified.clear();
        }
        result.is_ok() || !std::path::Path::new(JOURNAL).exists()
    }
}

#[path = "batch_apply.rs"]
mod apply;
#[path = "batch_inherited.rs"]
mod inherited;
impl Drop for Batch {
    fn drop(&mut self) {
        self.release();
        // 正常情况下 Controller 已排空这些事件。最终重试或栈展开时的恢复
        // 也要保留；发布只入队，绝不执行 IO。
        if let Some(package) = self.history_package.as_deref() {
            let mut events = self.history.take();
            for event in &mut events {
                if event.reason == "released" { event.reason = "unrestricted".into(); }
            }
            if !events.is_empty() { crate::auto_history::publish_core_events(package, events); }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn release_keeps_written_and_previous_masks_including_kernel_filters() {
        assert!(written_mask_matches(0x20, 0x20, None, 0xff, 0x90));
        assert!(written_mask_matches(0x20, 0xa0, None, 0xff, 0x90));
        assert!(written_mask_matches(0x20, 0xa0, None, 0x7f, 0));
        assert!(written_mask_matches(0x20, 0x04, Some(0xa0), 0xff, 0x90));
        assert!(written_mask_matches(0x80, 0x80, None, 0xff, 0x80));
        assert!(!written_mask_matches(0x6f, 0x04, None, 0xff, 0x90));
        assert!(!written_mask_matches(0x1f, 0x20, None, 0xff, 0));
        assert!(!written_mask_matches(0, 0x80, None, 0xff, 0x80));
    }
    #[test]
    fn affinity_only_single_record_and_multi_record_intents_remain_recoverable() {
        let old = "v1 abc 1-2 3 4 5 ff c0\n";
        assert_eq!(decode(old).unwrap().len(), 1);
        let batch = format!("{old}v2 abc 1-2 3 6 7 ff 80 10\n");
        let entries = decode(&batch).unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[&(3, 6, 7)].previous, Some(16));
        let again = entries.values().map(Entry::encode).collect::<String>();
        assert_eq!(decode(&again).unwrap().len(), 2);
        assert!(decode(&(old.to_owned() + old)).is_err());
        assert!(decode(&(old.to_owned() + "v1 abc 8-9 3 6 7 ff 80\n")).is_err());
        assert!(decode("v2 abc 1-2 3 4 5 ff 80 100\n").is_err());
    }
    #[test]
    fn cpuset_journal_accepts_equal_or_expanded_masks_but_requires_safe_baseline() {
        for (original, desired) in [(0xff, 0xff), (1, 0xc0)] {
            let text = format!("v4 abc 1-2 3 4 5 {original:x} {desired:x} - 2f746f702d617070 - 2f516958696152732f6175746f\n");
            let entries = decode(&text).unwrap();
            assert_eq!(entries[&(3, 4, 5)].original_cpuset.as_deref(), Some("/top-app"));
            let encoded = entries.values().next().unwrap().encode();
            assert!(encoded.starts_with("v4 "));
            assert_eq!(decode(&encoded).unwrap()[&(3, 4, 5)].owned_root, "/QiXiaRs/auto");
        }
        for path in ["2f2e2e2f78", "2f516958696152732f6175746f2f30", "zz", "2f0a"] {
            assert!(decode(&format!("v4 abc 1-2 3 4 5 1 c0 - {path} - 2f516958696152732f6175746f\n")).is_err());
        }
        assert!(decode("v1 abc 1-2 3 4 5 1 c0\n").is_err());
        assert!(decode("v2 abc 1-2 3 4 5 ff ff 1\n").is_err());
        let child = decode("v4 abc 1-2 3 6 7 ff 1 - 2f 3-4-5 2f516958696152732f6175746f\n").unwrap();
        assert_eq!(child[&(3, 6, 7)].inherited_from, Some((3, 4, 5)));
        assert!(decode("v4 abc 1-2 3 6 7 ff 1 - 2f 3-4-0 2f516958696152732f6175746f\n").is_err());
        assert!(decode("v3 abc 1-2 3 6 7 ff 1 - 2f 3-4-5\n").is_err());
    }
    #[test]
    fn v4_keeps_original_root_across_configuration_changes_and_rejects_unsafe_roots() {
        let previous = decode("v4 abc 1-2 3 4 5 ff 1 - 2f746f702d617070 - 2f516958696152732f6175746f\n").unwrap();
        let mut entry = previous[&(3, 4, 5)].clone();
        for root in ["/EarlierSetting/auto", "/top-app/auto", "/QiXiaRs/auto"] {
            entry.owned_root = root.into();
            let encoded = entry.encode();
            let restored = decode(&encoded).unwrap();
            assert_eq!(restored[&(3, 4, 5)].owned_root, root);
            assert_eq!(restored[&(3, 4, 5)].encode(), encoded);
        }
        for root in ["/", "/top-app", "/../auto", "/a/b/auto", "/a/auto/"] {
            entry.owned_root = root.into();
            assert!(decode(&entry.encode()).is_err());
        }
        for malformed in ["v4", "v4 abc 1-2 3 4 5 ff 1 - 2f -", "v3 abc"] {
            assert!(decode(malformed).is_err());
        }
    }
    #[test]
    fn cleanup_references_preserve_raw_old_baseline_and_lease_root() {
        let previous = decode("v4 abc 1-2 3 4 5 ff 1 - 2f - 2f516958696152732f6175746f\n").unwrap();
        let mut entry = previous[&(3, 4, 5)].clone();
        entry.original_cpuset = Some("/QiXia/7".into());
        entry.owned_root = "/EarlierSetting/auto".into();
        assert_eq!(recovery_paths_from_text(&entry.encode()).unwrap(),
            BTreeSet::from(["/QiXia/7".into(), "/EarlierSetting/auto".into()]));
        let mut child = entry.clone();
        child.record.tid = 6;
        child.record.start = 7;
        child.inherited_from = Some((3, 4, 5));
        assert_eq!(recovery_paths_from_text(&(entry.encode() + &child.encode())).unwrap(),
            recovery_paths_from_text(&entry.encode()).unwrap());
        for malformed in ["", "corrupt", "v4 abc"] {
            assert!(recovery_paths_from_text(malformed).is_err());
        }
    }
    #[test]
    fn thread_failures_back_off_independently_and_remain_bounded() {
        let mut batch = Batch::default();
        let mut changes = Changes::default();
        for _ in 0..8 { batch.failed((1, 2, 3), None, "write_failed", &mut changes); }
        batch.failed((1, 4, 5), None, "write_failed", &mut changes);
        assert_eq!(Retry::delay(batch.retries[&(1, 2, 3)].failures), Duration::from_secs(4));
        assert_eq!(Retry::delay(batch.retries[&(1, 4, 5)].failures), Duration::from_millis(250));
        // 防止此纯逻辑测试尝试恢复生产环境日志。
        batch.history_package = None;
        std::mem::forget(batch);
    }
    #[test]
    fn new_requested_or_available_mask_bypasses_old_failure_backoff() {
        let now = Instant::now();
        let target = RetryTarget { desired: 0x30, available: Some(0xff) };
        let retry = Retry { failures: 8, due: now + Duration::from_secs(4), target: Some(target) };
        assert!(retry.waiting(Some(target), now));
        assert!(!retry.waiting(Some(RetryTarget { desired: 0x40, ..target }), now));
        assert!(!retry.waiting(Some(RetryTarget { available: Some(0x7f), ..target }), now));
        assert!(!retry.waiting(Some(RetryTarget { available: None, ..target }), now));
        assert!(!retry.waiting(None, now), "retirement must not wait for an allocation retry");
        assert!(!retry.waiting(Some(target), now + Duration::from_secs(4)));
    }
    #[test]
    fn maximum_recovery_batch_with_long_paths_round_trips_within_byte_limit() {
        let path = format!("/{}", "a".repeat(255));
        let entries: BTreeMap<_, _> = (1..=inherited::MAX_RECOVERY).map(|index| {
            let key = (1, index as i32, index as u64);
            (key, Entry { record: Record { boot: "abcdef-1234".into(), token: "1-123456789".into(),
                pid: key.0, tid: key.1, start: key.2, original: 0xff, written: 1 },
                previous: None, original_cpuset: Some(path.clone()), owned_root: format!("/{}/auto", "b".repeat(48)), inherited_from: Some((1, 2, 3)) })
        }).collect();
        let text = entries.values().map(Entry::encode).collect::<String>();
        assert!(text.len() < MAX_JOURNAL_BYTES);
        assert_eq!(decode(&text).unwrap().len(), inherited::MAX_RECOVERY);
    }
}

#[cfg(test)]
#[path = "batch_device_tests.rs"]
mod device_tests;

#[cfg(test)]
#[path = "batch_inheritance_device_tests.rs"]
mod inheritance_device_tests;
