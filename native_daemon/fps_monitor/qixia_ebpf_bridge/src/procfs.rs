use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

pub(crate) fn pid_matches_pkg(pid: u32, pkg: &str) -> bool {
    // 目标集合更新时做一次身份确认；逐帧路径只依赖内核 target_tgids 映射。
    if pkg.is_empty() {
        return true;
    }

    let path = format!("/proc/{pid}/cmdline");
    let Ok(cmdline) = fs::read(path) else {
        return false;
    };
    let name = cmdline.split(|b| *b == 0).next().unwrap_or_default();
    let name = match std::str::from_utf8(name) {
        Ok(s) => s.rsplit('/').next().unwrap_or(s),
        Err(_) => return false,
    };

    name == pkg
        || name
            .strip_prefix(pkg)
            .is_some_and(|suffix| suffix.starts_with(':'))
}

pub(crate) fn resolve_libgui_path(pid: i32) -> Result<PathBuf, String> {
    if pid > 0 {
        if let Ok(maps) = fs::read_to_string(format!("/proc/{pid}/maps")) {
            for line in maps.lines() {
                let Some(raw_path) = line.split_whitespace().nth(5) else {
                    continue;
                };
                if !raw_path.ends_with("/libgui.so") {
                    continue;
                }
                let direct = PathBuf::from(raw_path);
                if direct.is_file() {
                    return Ok(fs::canonicalize(&direct).unwrap_or(direct));
                }
                let namespaced = PathBuf::from(format!("/proc/{pid}/root{raw_path}"));
                if namespaced.is_file() {
                    return Ok(fs::canonicalize(&namespaced).unwrap_or(namespaced));
                }
            }
        }
    }

    let paths_64_first = [
        "/system/lib64/libgui.so",
        "/system_ext/lib64/libgui.so",
        "/system/lib/libgui.so",
        "/system_ext/lib/libgui.so",
    ];
    let paths_32_first = [
        "/system/lib/libgui.so",
        "/system_ext/lib/libgui.so",
        "/system/lib64/libgui.so",
        "/system_ext/lib64/libgui.so",
    ];
    let paths = if process_is_64_bit(pid).is_some_and(|is_64_bit| !is_64_bit) {
        &paths_32_first
    } else {
        &paths_64_first
    };
    paths
        .iter()
        .copied()
        .map(PathBuf::from)
        .find(|path| path.is_file())
        .map(|path| fs::canonicalize(&path).unwrap_or(path))
        .ok_or_else(|| format!("未找到目标 PID {pid} 映射的 libgui.so"))
}

pub(crate) fn process_is_64_bit(pid: i32) -> Option<bool> {
    if pid <= 0 {
        return None;
    }
    let mut file = fs::File::open(format!("/proc/{pid}/exe")).ok()?;
    let mut header = [0u8; 5];
    file.read_exact(&mut header).ok()?;
    if header[..4] != *b"\x7fELF" {
        return None;
    }
    match header[4] {
        1 => Some(false),
        2 => Some(true),
        _ => None,
    }
}

pub(crate) fn collect_task_ids(pid: u32) -> Result<HashSet<u32>, String> {
    let entries = fs::read_dir(format!("/proc/{pid}/task"))
        .map_err(|err| format!("读取 pid={pid} 线程列表失败: {err}"))?;
    let mut tids = HashSet::new();
    for entry in entries.flatten() {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if let Ok(tid) = name.parse::<u32>() {
            if tid > 0 {
                tids.insert(tid);
            }
        }
    }
    if tids.is_empty() {
        return Err(format!("pid={pid} 没有可挂载线程"));
    }
    Ok(tids)
}

pub(crate) fn read_proc_starttime(path: &Path) -> Option<u64> {
    let content = fs::read_to_string(path).ok()?;
    let close = content.rfind(") ")?;
    // /proc/<pid>/stat 中，comm 后的第 3 字段是 state，starttime 是第 22 字段，
    // 因此它位于后半段按空白拆分后下标为 19 的位置。
    content[close + 2..]
        .split_whitespace()
        .nth(19)?
        .parse::<u64>()
        .ok()
}

pub(crate) fn process_starttime(pid: u32) -> Option<u64> {
    read_proc_starttime(Path::new(&format!("/proc/{pid}/stat")))
}

pub(crate) fn task_starttime(pid: u32, tid: u32) -> Option<u64> {
    read_proc_starttime(Path::new(&format!("/proc/{pid}/task/{tid}/stat")))
}

pub(crate) fn task_starttime_snapshot(
    pid: u32,
    tids: impl Iterator<Item = u32>,
) -> HashMap<u32, u64> {
    tids.filter_map(|tid| task_starttime(pid, tid).map(|start| (tid, start)))
        .collect()
}
