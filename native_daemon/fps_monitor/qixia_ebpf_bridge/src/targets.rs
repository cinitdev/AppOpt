use crate::attach::{attach_process_candidate, detach_pid_links, sync_process_tasks};
use crate::clock::monotonic_ns;
use crate::context::{QixiaThreadsEbpfCtx, PidSymbolProbe};
use crate::diagnostics::{compact_error_details, cstring_lossy};
use crate::procfs::{
    pid_matches_pkg, process_starttime, resolve_libgui_path, task_starttime_snapshot,
};
use crate::symbols::{cached_candidate_offsets, candidate_bit, candidate_range_mask};
use std::collections::HashSet;
use std::os::raw::c_int;

pub(crate) fn sync_target_pids(ctx: &mut QixiaThreadsEbpfCtx, pids: &[c_int]) -> Result<usize, String> {
    let mut desired = HashSet::new();
    for pid in pids.iter().copied().filter(|pid| *pid > 0) {
        let pid = pid as u32;
        if ctx
            .target_pkg
            .as_deref()
            .is_some_and(|pkg| !pid_matches_pkg(pid, pkg))
        {
            continue;
        }
        desired.insert(pid);
    }

    let previous = ctx.target_pids.clone();
    for pid in previous.intersection(&desired).copied() {
        if !ctx.pid_starttimes.contains_key(&pid)
            && let Some(starttime) = process_starttime(pid)
        {
            ctx.pid_starttimes.insert(pid, starttime);
        }
    }
    let reused = previous
        .intersection(&desired)
        .copied()
        .filter(|pid| {
            ctx.pid_starttimes
                .get(pid)
                .zip(process_starttime(*pid))
                .is_some_and(|(old, current)| old != &current)
        })
        .collect::<HashSet<_>>();
    let mut removed = previous.difference(&desired).copied().collect::<Vec<_>>();
    removed.extend(reused.iter().copied());
    let mut update_errors = Vec::new();
    for pid in &removed {
        if let Err(err) = ctx.target_tgids.remove(pid) {
            update_errors.push(format!("移除 target_tgids[{pid}] 失败: {err}"));
        }
        if let Some(links) = ctx.pid_links.remove(pid) {
            update_errors.extend(detach_pid_links(&mut ctx.bpf, *pid, links));
        }
        ctx.pid_libgui_paths.remove(pid);
        ctx.pid_starttimes.remove(pid);
        ctx.pid_task_starttimes.remove(pid);
        ctx.pid_symbols.remove(pid);
        ctx.pid_symbol_offsets.remove(pid);
        ctx.pid_symbol_probes.remove(pid);
    }

    let mut effective = previous
        .intersection(&desired)
        .copied()
        .filter(|pid| !reused.contains(pid))
        .collect::<HashSet<_>>();
    for pid in effective.iter().copied().collect::<Vec<_>>() {
        if !ctx.pid_libgui_paths.contains_key(&pid) {
            match resolve_libgui_path(pid as i32) {
                Ok(path) => {
                    ctx.pid_libgui_paths.insert(pid, path);
                }
                Err(err) => {
                    update_errors.push(err);
                    continue;
                }
            }
        }
        let Some(path) = ctx.pid_libgui_paths.get(&pid) else {
            continue;
        };
        if let Some(task_links) = ctx.pid_links.get_mut(&pid) {
            let selected_symbol = ctx
                .pid_symbols
                .get(&pid)
                .unwrap_or(&ctx.symbol)
                .to_string_lossy()
                .into_owned();
            let Some(offset) = ctx.pid_symbol_offsets.get(&pid).copied() else {
                update_errors.push(format!("pid={pid} 缺少已解析的 uprobe 符号偏移"));
                continue;
            };
            let task_starttimes = ctx.pid_task_starttimes.entry(pid).or_default();
            let sync = sync_process_tasks(
                &mut ctx.bpf,
                pid,
                path,
                &selected_symbol,
                offset,
                task_links,
                task_starttimes,
            );
            // 新线程沿用当前候选符号即可；不能因为线程频繁增删而反复重置候选
            // 计时，否则错误符号在持续创建线程的游戏里会永不轮换。
            update_errors.extend(sync.errors);
        }
    }
    let added_pids = desired.difference(&effective).copied().collect::<Vec<_>>();
    for pid in added_pids {
        let path = match resolve_libgui_path(pid as i32) {
            Ok(path) => path,
            Err(err) => {
                update_errors.push(err);
                continue;
            }
        };
        let offsets = match cached_candidate_offsets(ctx, &path) {
            Ok(offsets) => offsets,
            Err(err) => {
                update_errors.push(err);
                continue;
            }
        };
        let (candidate_index, pid_symbol, symbol_offset, task_links) =
            match attach_process_candidate(&mut ctx.bpf, pid, &path, &offsets, 0) {
                Ok(attached) => attached,
                Err(err) => {
                    update_errors.push(err);
                    continue;
                }
            };
        if let Err(err) = ctx.target_tgids.insert(pid, 1, 0) {
            update_errors.push(format!("更新 target_tgids[{pid}] 失败: {err}"));
            update_errors.extend(detach_pid_links(&mut ctx.bpf, pid, task_links));
            continue;
        }
        ctx.pid_libgui_paths.insert(pid, path);
        ctx.pid_links.insert(pid, task_links);
        if let Some(starttime) = process_starttime(pid) {
            ctx.pid_starttimes.insert(pid, starttime);
        }
        ctx.pid_task_starttimes.insert(
            pid,
            task_starttime_snapshot(pid, ctx.pid_links[&pid].keys().copied()),
        );
        ctx.pid_symbols.insert(pid, pid_symbol);
        ctx.pid_symbol_offsets.insert(pid, symbol_offset);
        let probe_started_ns = monotonic_ns();
        ctx.pid_symbol_probes.insert(
            pid,
            PidSymbolProbe {
                candidate_index,
                candidate_started_ns: probe_started_ns,
                round_started_ns: probe_started_ns,
                confirmed: false,
                confirmed_once: false,
                exhausted: false,
                attachable_mask: candidate_bit(candidate_index),
                no_frame_mask: 0,
                stalled_mask: 0,
                unavailable_mask: candidate_range_mask(0, candidate_index),
            },
        );
        effective.insert(pid);
    }

    ctx.target_pids = effective;
    ctx.streams
        .retain(|key, _| ctx.target_pids.contains(&key.pid));
    ctx.stat_snapshots
        .retain(|key, _| ctx.target_pids.contains(&key.pid));
    if let Some(stats) = ctx.frame_stats.as_mut() {
        let stale_keys = stats
            .keys()
            .filter_map(Result::ok)
            .filter(|key| !ctx.target_pids.contains(&key.pid))
            .collect::<Vec<_>>();
        for key in stale_keys {
            let _ = stats.remove(&key);
            if let Some(report) = ctx.frame_report.as_mut() { let _ = report.remove(&key); }
            if let Some(report) = ctx.frame_report_prev.as_mut() { let _ = report.remove(&key); }
        }
    }
    if ctx
        .selected_stream
        .is_some_and(|key| !ctx.target_pids.contains(&key.pid))
    {
        ctx.selected_stream = None;
        ctx.pending_stream = None;
        ctx.pending_stream_since_ns = 0;
        ctx.cur_fps = 0.0;
        ctx.pid = -1;
    }
    if ctx.selected_stream.is_none()
        && (ctx.pid <= 0 || !ctx.target_pids.contains(&(ctx.pid as u32)))
    {
        ctx.pid = ctx
            .target_pids
            .iter()
            .copied()
            .next()
            .map_or(-1, |pid| pid as i32);
    }

    if update_errors.is_empty() {
        ctx.last_error = cstring_lossy("");
    } else {
        let message = compact_error_details(&update_errors);
        ctx.last_error = cstring_lossy(&message);
    }
    // 线程级挂载或读取失败只是局部且可重试的问题。
    // 返回实际有效的 PID 数量，避免守护进程将单个短命线程的问题
    // 视为整个目标同步失败，进而在每轮轮询中
    // 重复遍历全部 /proc。
    Ok(ctx.target_pids.len())
}
