//! 唯一的归属目录使继承限制的线程能够明确恢复来源。
use super::*;
pub(super) const MAX_RECOVERY: usize = 4096;
const MAX_DISCOVER: usize = 512;

fn identity(tid: i32) -> io::Result<Identity> {
    let status = fs::read(format!("/proc/{tid}/status"))?;
    let status = String::from_utf8_lossy(&status);
    let pid: i32 = status.lines().find_map(|line| line.strip_prefix("Tgid:"))
        .and_then(|pid| pid.trim().parse().ok()).filter(|pid| *pid > 0)
        .ok_or_else(|| io::Error::other("线程进程归属不可读"))?;
    let stat = fs::read(format!("/proc/{pid}/task/{tid}/stat"))?;
    let start = super::super::parse_stat(&stat).map(|(start, _)| start)
        .filter(|start| *start > 0).ok_or_else(|| io::Error::other("线程身份不可读"))?;
    Ok((pid, tid, start))
}

// 首次恢复前统一提交所有新子线程的恢复意图。
// 子线程离开父线程组后，由自身日志行保障恢复。
pub(super) fn adopt(entries: &mut BTreeMap<Identity, Entry>, identities: &[Identity], history: &mut EventLog) -> io::Result<Vec<Identity>> {
    if entries.is_empty() { return Ok(Vec::new()); }
    let mut additions = BTreeMap::new();
    for &key in identities.iter().take(MAX_DISCOVER) {
        if entries.contains_key(&key) || additions.contains_key(&key) { continue; }
        let group = match cpuset::current(key.0, key.1) { Ok(group) => group, Err(_) => continue };
        let Some(origin) = cpuset::origin(&group) else { continue; };
        let Some(parent) = entries.get(&origin).filter(|entry| entry.original_cpuset.is_some()
            && cpuset::owned_root(&group) == Some(entry.owned_root.as_str())) else {
            history.record(key, "error", "unknown", None, None, "inherited_baseline_missing"); continue;
        };
        if entries.len() + additions.len() >= MAX_RECOVERY { break; }
        let mut entry = parent.clone();
        entry.record.pid = key.0; entry.record.tid = key.1; entry.record.start = key.2;
        entry.previous = None;
        entry.inherited_from = Some(origin);
        if !matches!(same_identity(&entry.record), Ok(true)) { continue; }
        let Ok(mask) = affinity(key.1) else { continue; };
        entry.record.written = mask;
        if !matches!(same_identity(&entry.record), Ok(true))
            || cpuset::current(key.0, key.1).ok().as_deref() != Some(group.as_str()) { continue; }
        additions.insert(key, entry);
    }
    let keys = additions.keys().copied().collect::<Vec<_>>();
    if !additions.is_empty() {
        let mut intended = entries.clone(); intended.extend(additions);
        persist(&intended)?; *entries = intended;
    }
    Ok(keys)
}

pub(super) fn discover(entries: &mut BTreeMap<Identity, Entry>, retiring: Option<&BTreeSet<Identity>>, history: &mut EventLog) -> io::Result<()> {
    let mut identities = BTreeSet::new();
    let boot = boot_id()?;
    for (&key, entry) in entries.iter() {
        if entry.record.boot != boot || entry.original_cpuset.is_none() || retiring.is_some_and(|retiring| !retiring.contains(&key)) { continue; }
        let groups = match cpuset::groups_at(&entry.owned_root, &key) {
            Ok(groups) => groups,
            Err(_) => { history.record(key, "error", "unknown", None, None, "cpuset_readback_failed"); continue; }
        };
        for group in groups {
            let members = match cpuset::members(&group) {
                Ok(members) => members,
                Err(_) => { history.record(key, "error", "unknown", None, None, "cpuset_readback_failed"); continue; }
            };
            for tid in members {
                match identity(tid) {
                    Ok(key) if !entries.contains_key(&key) => { identities.insert(key); },
                    Ok(_) => (),
                    Err(error) if error.kind() == io::ErrorKind::NotFound => (),
                    Err(_) => { history.record(key, "error", "unknown", None, None, "identity_unavailable"); },
                }
                if identities.len() >= MAX_DISCOVER { break; }
            }
            if identities.len() >= MAX_DISCOVER { break; }
        }
        if identities.len() >= MAX_DISCOVER { break; }
    }
    adopt(entries, &identities.into_iter().collect::<Vec<_>>(), history)?;
    Ok(())
}
