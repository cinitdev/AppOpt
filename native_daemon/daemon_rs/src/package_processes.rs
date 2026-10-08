//! 复用守护进程的进程快照，并在每次交接时验证归属。
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
};

pub(crate) fn discover(
    pkg: &str,
    known: impl IntoIterator<Item = i32>,
    full_scan: bool,
) -> BTreeMap<i32, String> {
    let mut candidates = crate::process_index_find_package_pids(pkg).unwrap_or_default();
    candidates.extend(known);
    let mut found = verify(pkg, &candidates);
    if full_scan || found.is_empty() {
        if let Ok(entries) = fs::read_dir("/proc") {
            let pids = entries
                .flatten()
                .filter_map(|entry| entry.file_name().to_str()?.parse().ok())
                .collect();
            found.extend(verify(pkg, &pids));
        }
    }
    found
}

fn verify(pkg: &str, pids: &BTreeSet<i32>) -> BTreeMap<i32, String> {
    pids.iter()
        .filter_map(|pid| {
            let name = qixia_kernel_info::proc::read_cmdline(*pid).ok()?;
            crate::process_belongs_to_uid_package(&name, pkg).then_some((*pid, name))
        })
        .collect()
}
