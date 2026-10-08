use crate::affinity::journal_format::{parse_managed_tid_journal, serialize_managed_tid_journal};
#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::elapsed_realtime_ms;
use crate::{DaemonState, ManagedTidEntry, BOOT_ID_FILE, MANAGED_TID_STATE_FILE, STATE_DIR};
use std::collections::HashMap;
use std::io::Write;
use std::path::Path;
use std::{fs, io};

pub(crate) fn ensure_managed_tid_journal_loaded(
    state: &mut DaemonState,
    cpuset_name: &str,
) -> io::Result<()> {
    if state.managed_tid_journal_loaded {
        return Ok(());
    }
    let load = load_managed_tid_journal(cpuset_name)?;
    if let Some(warning) = load.warning.as_deref() {
        log_warn!("[RS] {warning}");
    }
    state.managed_tids = load.entries;
    state.managed_tid_quarantine_before_starttime =
        load.quarantine_existing.then(managed_tid_starttime_cutoff);
    state.managed_tid_journal_dirty = true;
    state.managed_tid_journal_loaded = true;
    Ok(())
}

#[derive(Debug, Default)]
pub(crate) struct ManagedTidJournalLoad {
    pub(crate) entries: HashMap<i32, ManagedTidEntry>,
    pub(crate) quarantine_existing: bool,
    pub(crate) warning: Option<String>,
}

pub(crate) fn load_managed_tid_journal(cpuset_name: &str) -> io::Result<ManagedTidJournalLoad> {
    let content = match fs::read_to_string(MANAGED_TID_STATE_FILE) {
        Ok(content) => content,
        Err(err) if err.kind() == io::ErrorKind::NotFound => {
            return Ok(ManagedTidJournalLoad::default());
        }
        Err(err) => return Err(err),
    };
    let boot_id = read_managed_tid_boot_id()?;
    let load = decode_managed_tid_journal(&content, &boot_id, cpuset_name);
    if load.quarantine_existing {
        isolate_corrupt_managed_tid_journal();
    }
    Ok(load)
}

pub(crate) fn decode_managed_tid_journal(
    content: &str,
    current_boot_id: &str,
    cpuset_name: &str,
) -> ManagedTidJournalLoad {
    match parse_managed_tid_journal(content, current_boot_id, cpuset_name) {
        Ok(entries) => ManagedTidJournalLoad {
            entries,
            ..ManagedTidJournalLoad::default()
        },
        Err(err) => ManagedTidJournalLoad {
            entries: HashMap::new(),
            quarantine_existing: true,
            warning: Some(format!(
                "线程恢复基线已损坏并隔离；当前存活线程等待身份更新后再接管: {err}"
            )),
        },
    }
}

pub(crate) fn isolate_corrupt_managed_tid_journal() {
    let quarantine = format!("{MANAGED_TID_STATE_FILE}.corrupt.{}", std::process::id());
    let _ = fs::rename(MANAGED_TID_STATE_FILE, quarantine);
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn managed_tid_starttime_cutoff() -> u64 {
    let ticks_per_second = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
    if ticks_per_second <= 0 {
        return u64::MAX;
    }
    let ticks_per_second = ticks_per_second as u64;
    let uptime_ticks = fs::read_to_string("/proc/uptime")
        .ok()
        .and_then(|value| value.split_whitespace().next()?.parse::<f64>().ok())
        .filter(|value| value.is_finite() && *value >= 0.0)
        .map(|seconds| (seconds * ticks_per_second as f64) as u64)
        .unwrap_or_else(|| {
            elapsed_realtime_ms()
                .saturating_mul(ticks_per_second)
                .saturating_div(1000)
        });
    uptime_ticks
        // 给恢复日志隔离与首轮扫描之间的竞态留一秒余量。
        .saturating_add(ticks_per_second)
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub(crate) fn managed_tid_starttime_cutoff() -> u64 {
    u64::MAX
}

pub(crate) fn sync_managed_tid_journal(
    state: &mut DaemonState,
    cpuset_name: &str,
    force: bool,
) -> io::Result<()> {
    if !state.managed_tid_journal_loaded {
        return Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "线程恢复基线尚未成功读取",
        ));
    }
    if !force && !state.managed_tid_journal_dirty {
        return Ok(());
    }

    if state.managed_tids.is_empty() {
        match fs::remove_file(MANAGED_TID_STATE_FILE) {
            Ok(()) => {}
            Err(err) if err.kind() == io::ErrorKind::NotFound => {}
            Err(err) => return Err(err),
        }
        state.managed_tid_journal_dirty = false;
        return Ok(());
    }

    let boot_id = read_managed_tid_boot_id()?;
    let content = serialize_managed_tid_journal(&state.managed_tids, &boot_id, cpuset_name);
    fs::create_dir_all(STATE_DIR)?;
    write_managed_tid_journal_file(Path::new(MANAGED_TID_STATE_FILE), &content)?;
    for entry in state.managed_tids.values_mut() {
        entry.restore_persisted = true;
    }
    state.managed_tid_journal_dirty = false;
    Ok(())
}

pub(crate) fn write_managed_tid_journal_file(path: &Path, content: &str) -> io::Result<()> {
    let temporary = path.with_extension(format!("tmp.{}", std::process::id()));
    let commit_result = (|| -> io::Result<()> {
        let mut file = fs::OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&temporary)?;
        file.write_all(content.as_bytes())?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)
    })();
    if let Err(err) = commit_result {
        let _ = fs::remove_file(&temporary);
        return Err(err);
    }
    Ok(())
}

pub(crate) fn read_managed_tid_boot_id() -> io::Result<String> {
    fs::read_to_string(BOOT_ID_FILE)
        .map(|value| value.trim().to_string())
        .and_then(|value| {
            if value.is_empty() {
                Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "无法读取 boot_id",
                ))
            } else {
                Ok(value)
            }
        })
}
