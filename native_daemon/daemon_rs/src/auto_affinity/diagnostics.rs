//! 只读诊断缓存。复用分配器已有样本和操作结果，不新增磁盘文件或调度决策。
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::sync::{Mutex, OnceLock};
use std::time::{Instant, Duration, SystemTime, UNIX_EPOCH};

type Key = (i32, i32, u64);
#[derive(Clone)]
pub(crate) struct Row {
    pub key: Key,
    pub name: String,
    pub average: f64,
    pub desired: Option<u64>,
    pub expected_group: String,
}
#[derive(Clone, Default)]
struct Snapshot {
    package: String,
    sampled_ms: u64,
    sampled: Option<Instant>,
    status: String,
    detail: String,
    total: usize,
    rows: Vec<Row>,
    operations: BTreeMap<Key, (u64, String, String)>,
    failures: BTreeMap<Key, String>,
}
#[derive(Default)]
struct Store { snapshots: VecDeque<Snapshot> }
impl Store {
    fn entry(&mut self, pkg: &str) -> &mut Snapshot {
        let index = self.snapshots.iter().position(|s| s.package == pkg);
        let entry = index.and_then(|index| self.snapshots.remove(index))
            .unwrap_or_else(|| Snapshot { package: pkg.into(), ..Snapshot::default() });
        self.snapshots.push_back(entry);
        while self.snapshots.len() > 4 { self.snapshots.pop_front(); }
        self.snapshots.back_mut().unwrap()
    }
}
fn store() -> &'static Mutex<Store> {
    static STORE: OnceLock<Mutex<Store>> = OnceLock::new();
    STORE.get_or_init(Default::default)
}
fn epoch_ms() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 }

pub(crate) fn due(pkg: &str) -> bool {
    let guard = store().lock().unwrap_or_else(|e| e.into_inner());
    guard.snapshots.iter().find(|s| s.package == pkg)
        .and_then(|s| s.sampled).is_none_or(|time| time.elapsed() >= Duration::from_secs(2))
}
pub(crate) fn publish(pkg: &str, mut rows: Vec<Row>) {
    rows.sort_by(|a, b| b.average.total_cmp(&a.average).then(a.key.cmp(&b.key)));
    let total = rows.len();
    rows.truncate(512);
    let mut guard = store().lock().unwrap_or_else(|e| e.into_inner());
    let entry = guard.entry(pkg);
    entry.sampled_ms = epoch_ms(); entry.sampled = Some(Instant::now()); entry.total = total; entry.rows = rows;
    let retained: BTreeSet<_> = entry.rows.iter().map(|row| row.key).collect();
    entry.operations.retain(|key, _| retained.contains(key));
    entry.failures.retain(|key, _| retained.contains(key));
}
pub(crate) fn status(pkg: &str, state: &str, detail: &str) {
    if pkg.is_empty() { return; }
    let mut guard = store().lock().unwrap_or_else(|e| e.into_inner());
    let entry = guard.entry(pkg);
    entry.status = state.into(); entry.detail = detail.into();
}
pub(crate) fn operation(pkg: &str, event: &crate::auto_history::CoreEvent) {
    if event.pid <= 0 || event.tid <= 0 || event.kind == "observe" { return; }
    let mut guard = store().lock().unwrap_or_else(|e| e.into_inner());
    let entry = guard.entry(pkg);
    entry.operations.insert((event.pid, event.tid, event.start), (event.timestamp_ms, event.kind.clone(), event.reason.clone()));
    if event.kind != "error" { entry.failures.remove(&(event.pid, event.tid, event.start)); }
    if entry.operations.len() > 4096 {
        if let Some(key) = entry.operations.iter().min_by_key(|(_, value)| value.0).map(|(key, _)| *key) { entry.operations.remove(&key); }
    }
}
pub(crate) fn failure(pkg: &str, key: Key, error: &str) {
    let mut guard = store().lock().unwrap_or_else(|e| e.into_inner());
    let entry = guard.entry(pkg);
    if entry.failures.len() < 4096 || entry.failures.contains_key(&key) {
        entry.failures.insert(key, error.chars().take(256).collect());
    }
}
fn hex(value: &str) -> String { value.bytes().map(|b| format!("{b:02x}")).collect() }

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn query(pkg: &str) -> std::io::Result<String> {
    use super::platform::{affinity, cpuset};
    use std::io;
    if !super::packages(pkg).contains(pkg) || pkg.contains(['\n', '\r', '#']) { return Err(io::Error::other("无效包名")); }
    // 复制后立即释放诊断锁，procfs 读取不会阻塞分配器。
    let snapshot = store().lock().unwrap_or_else(|e| e.into_inner()).snapshots.iter()
        .find(|s| s.package == pkg).cloned().unwrap_or_else(|| Snapshot { package: pkg.into(), status: "empty".into(), ..Snapshot::default() });
    let mut output = format!("QIXIA_DIAG\t1\t{}\t{}\t{}\t{}\t{}\t{}\n", pkg,
        snapshot.sampled_ms, epoch_ms(), hex(&snapshot.status), hex(&snapshot.detail), snapshot.total);
    for row in snapshot.rows {
        let (pid, tid, start) = row.key;
        let path = format!("/proc/{pid}/task/{tid}/stat");
        let before = qixia_kernel_info::proc::read_stat(&path);
        let same = before.as_ref().is_ok_and(|stat| stat.start == start);
        let actual = same.then(|| affinity(tid).ok()).flatten();
        let group = same.then(|| cpuset::current(pid, tid).ok()).flatten();
        let after = qixia_kernel_info::proc::read_stat(&path);
        let alive = if before.as_ref().err().is_some_and(|e| e.kind() == io::ErrorKind::NotFound) ||
            after.as_ref().err().is_some_and(|e| e.kind() == io::ErrorKind::NotFound) { "exited" }
            else if before.as_ref().is_ok_and(|s| s.start != start) || after.as_ref().is_ok_and(|s| s.start != start) { "reused" }
            else if same && after.as_ref().is_ok_and(|s| s.start == start) { "alive" } else { "unknown" };
        let operation = snapshot.operations.get(&row.key);
        let mask = |value: Option<u64>| value.map(|v| format!("{v:x}")).unwrap_or_else(|| "-".into());
        output.push_str(&format!("T\t{pid}\t{tid}\t{start}\t{}\t{:.4}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{alive}\t{}\n",
            hex(&row.name), row.average, mask(row.desired), mask(if alive == "alive" { actual } else { None }),
            hex(if alive == "alive" { group.as_deref().unwrap_or("") } else { "" }), hex(&row.expected_group),
            operation.map_or(0, |v| v.0), hex(operation.map_or("", |v| v.1.as_str())), hex(operation.map_or("", |v| v.2.as_str())),
            hex(snapshot.failures.get(&row.key).map_or("", String::as_str))));
    }
    output.push_str("END\n");
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn cache_is_bounded_and_readable_fields_are_delimiter_safe() {
        let mut store = Store::default();
        for i in 0..8 { store.entry(&format!("com.test{i}")); }
        assert_eq!(store.snapshots.len(), 4);
        assert_eq!(store.snapshots.front().unwrap().package, "com.test4");
        store.entry("com.test4");
        assert_eq!(store.snapshots.back().unwrap().package, "com.test4");
        assert_eq!(hex("a\tb\n"), "6109620a");
    }

    #[test]
    #[cfg(any(target_os = "android", target_os = "linux"))]
    fn diagnostics_query_reads_identity_without_changing_affinity_or_cpuset() {
        use super::super::platform::{affinity, cpuset};
        let pid = std::process::id() as i32;
        let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
        let stat = qixia_kernel_info::proc::read_stat(&format!("/proc/{pid}/task/{tid}/stat")).unwrap();
        let before = affinity(tid).unwrap();
        let group = cpuset::current(pid, tid).unwrap();
        let pkg = "com.qixia.diagnosticstest";
        let row = Row { key: (pid, tid, stat.start), name: "测试\t线程".into(), average: 12.0,
            desired: Some(before), expected_group: group.clone() };
        publish(pkg, vec![row.clone()]);
        failure(pkg, row.key, "测试错误\nEPERM");
        let report = query(pkg).unwrap();
        let fields: Vec<_> = report.lines().nth(1).unwrap().split('\t').collect();
        assert_eq!(fields.len(), 15);
        assert_eq!(fields[13], "alive");
        assert_eq!(fields[7], format!("{before:x}"));
        assert_eq!(fields[8], hex(&group));
        assert_eq!(fields[14], hex("测试错误\nEPERM"));
        publish(pkg, vec![Row { key: (pid, tid, stat.start + 1), ..row }]);
        let reused = query(pkg).unwrap();
        let fields: Vec<_> = reused.lines().nth(1).unwrap().split('\t').collect();
        assert_eq!(fields[13], "reused");
        assert_eq!(fields[7], "-");
        assert_eq!(affinity(tid).unwrap(), before);
        assert_eq!(cpuset::current(pid, tid).unwrap(), group);
        assert!(query("com.qixia.bad\nrequest").is_err());
    }
}
