use crate::constants::{LIBGUI_FRAME_SYMBOLS, MAX_ERROR_DETAILS};
use crate::diagnostics::{compact_error_details, cstring_lossy};
use crate::procfs::{collect_task_ids, task_starttime};
use aya::Ebpf;
use aya::programs::UProbe;
use aya::programs::uprobe::{UProbeLinkId, UProbeScope};
use std::collections::{HashMap, HashSet};
use std::ffi::CString;
use std::num::NonZeroU32;
use std::path::Path;

pub(crate) fn load_uprobe_program(bpf: &mut Ebpf) -> Result<(), String> {
    let program: &mut UProbe = bpf
        .program_mut("on_queue_buffer")
        .ok_or_else(|| "missing BPF program: on_queue_buffer".to_string())?
        .try_into()
        .map_err(|e: aya::programs::ProgramError| e.to_string())?;

    program.load().map_err(|e| e.to_string())
}

pub(crate) fn uprobe_program(bpf: &mut Ebpf) -> Result<&mut UProbe, String> {
    bpf.program_mut("on_queue_buffer")
        .ok_or_else(|| "missing BPF program: on_queue_buffer".to_string())?
        .try_into()
        .map_err(|e: aya::programs::ProgramError| e.to_string())
}

pub(crate) fn attach_symbol_for_task(
    bpf: &mut Ebpf,
    tgid: u32,
    tid: u32,
    path: &Path,
    symbol: &str,
    offset: u64,
) -> Result<UProbeLinkId, String> {
    let scope = UProbeScope::OneProcess(
        NonZeroU32::new(tid).ok_or_else(|| "invalid target tid".to_string())?,
    );
    let program = uprobe_program(bpf)?;
    program.attach(offset, path, scope).map_err(|err| {
        format!(
            "pid={tgid} tid={tid} symbol={symbol} {}: {err}",
            path.display()
        )
    })
}

pub(crate) fn detach_task_links(
    bpf: &mut Ebpf,
    tgid: u32,
    tid: u32,
    links: Vec<UProbeLinkId>,
) -> Vec<String> {
    let program = match uprobe_program(bpf) {
        Ok(program) => program,
        Err(err) => {
            return vec![format!("pid={tgid} tid={tid} 获取 uprobe 程序失败: {err}")];
        }
    };
    let mut errors = Vec::new();
    for link_id in links {
        if let Err(err) = program.detach(link_id) {
            errors.push(format!("pid={tgid} tid={tid} 解除 uprobe 失败: {err}"));
        }
    }
    errors
}

pub(crate) fn detach_pid_links(
    bpf: &mut Ebpf,
    tgid: u32,
    task_links: HashMap<u32, Vec<UProbeLinkId>>,
) -> Vec<String> {
    let mut errors = Vec::new();
    for (tid, links) in task_links {
        errors.extend(detach_task_links(bpf, tgid, tid, links));
    }
    errors
}

pub(crate) fn attach_process_candidate(
    bpf: &mut Ebpf,
    pid: u32,
    path: &Path,
    offsets: &[Option<u64>],
    start_index: usize,
) -> Result<(usize, CString, u64, HashMap<u32, Vec<UProbeLinkId>>), String> {
    let tids = collect_task_ids(pid)?;
    let mut symbol_errors = Vec::new();
    for (candidate_index, symbol) in LIBGUI_FRAME_SYMBOLS.iter().enumerate().skip(start_index) {
        let Some(offset) = offsets.get(candidate_index).copied().flatten() else {
            continue;
        };
        let mut task_links = HashMap::new();
        let mut attach_errors = Vec::new();
        let mut suppressed_errors = 0usize;
        for tid in tids.iter().copied() {
            match attach_symbol_for_task(bpf, pid, tid, path, symbol, offset) {
                Ok(link) => {
                    task_links.insert(tid, vec![link]);
                }
                Err(err) => {
                    if attach_errors.len() < MAX_ERROR_DETAILS {
                        attach_errors.push(err);
                    } else {
                        suppressed_errors = suppressed_errors.saturating_add(1);
                    }
                }
            }
        }
        if !task_links.is_empty() {
            return Ok((candidate_index, cstring_lossy(symbol), offset, task_links));
        }
        symbol_errors.extend(attach_errors);
        if suppressed_errors > 0 {
            symbol_errors.push(format!("另有 {suppressed_errors} 个线程挂载失败"));
        }
    }
    Err(if symbol_errors.is_empty() {
        format!("pid={pid} 没有剩余可挂载的 queueBuffer 候选")
    } else {
        compact_error_details(&symbol_errors)
    })
}

pub(crate) struct TaskSyncResult {
    pub(crate) errors: Vec<String>,
}

pub(crate) fn sync_process_tasks(
    bpf: &mut Ebpf,
    pid: u32,
    path: &Path,
    symbol: &str,
    offset: u64,
    task_links: &mut HashMap<u32, Vec<UProbeLinkId>>,
    task_starttimes: &mut HashMap<u32, u64>,
) -> TaskSyncResult {
    let desired = match collect_task_ids(pid) {
        Ok(tids) => tids,
        Err(err) => {
            return TaskSyncResult { errors: vec![err] };
        }
    };
    let current = task_links.keys().copied().collect::<HashSet<_>>();
    let mut errors = Vec::new();
    let mut suppressed_errors = 0usize;
    for tid in current.difference(&desired).copied().collect::<Vec<_>>() {
        if let Some(links) = task_links.remove(&tid) {
            for error in detach_task_links(bpf, pid, tid, links) {
                if errors.len() < MAX_ERROR_DETAILS {
                    errors.push(error);
                } else {
                    suppressed_errors = suppressed_errors.saturating_add(1);
                }
            }
        }
        task_starttimes.remove(&tid);
    }
    // 旧 uprobe 连接仍在记录中时，TID 编号可能已被复用。
    // 仅当 /proc 确认 starttime 已改变时重新挂载；
    // stat 暂时不可读时，有意保持原连接不变。
    let reused = desired
        .intersection(&current)
        .copied()
        .filter(|tid| {
            task_starttimes
                .get(tid)
                .zip(task_starttime(pid, *tid))
                .is_some_and(|(old, new)| old != &new)
        })
        .collect::<Vec<_>>();
    for tid in reused {
        if let Some(links) = task_links.remove(&tid) {
            errors.extend(detach_task_links(bpf, pid, tid, links));
        }
        task_starttimes.remove(&tid);
    }
    let mut attach = desired.difference(&current).copied().collect::<Vec<_>>();
    attach.extend(
        desired
            .intersection(&current)
            .copied()
            .filter(|tid| !task_links.contains_key(tid)),
    );
    for tid in attach {
        match attach_symbol_for_task(bpf, pid, tid, path, symbol, offset) {
            Ok(link) => {
                task_links.insert(tid, vec![link]);
                if let Some(starttime) = task_starttime(pid, tid) {
                    task_starttimes.insert(tid, starttime);
                }
            }
            Err(err) => {
                if errors.len() < MAX_ERROR_DETAILS {
                    errors.push(err);
                } else {
                    suppressed_errors = suppressed_errors.saturating_add(1);
                }
            }
        }
    }
    if suppressed_errors > 0 {
        errors.push(format!("另有 {suppressed_errors} 个线程同步错误已省略"));
    }
    TaskSyncResult { errors }
}
