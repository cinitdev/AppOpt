use crate::attach::{attach_process_candidate, detach_pid_links};
use crate::clock::monotonic_ns;
use crate::constants::{LIBGUI_FRAME_SYMBOLS, SYMBOL_PROBE_NS};
use crate::context::QixiaThreadsEbpfCtx;
use crate::diagnostics::{compact_error_details, cstring_lossy, pid_role_label};
use crate::procfs::{resolve_libgui_path, task_starttime_snapshot};
use crate::streams::clear_pid_samples;
use crate::symbols::{
    cached_candidate_offsets, candidate_bit, candidate_range_mask, probe_candidate_results,
    readable_symbol_from_raw, readable_symbol_name,
};

pub(crate) fn switch_pid_symbol(
    ctx: &mut QixiaThreadsEbpfCtx,
    pid: u32,
    now_ns: u64,
) -> Result<bool, String> {
    let Some(probe) = ctx.pid_symbol_probes.get(&pid).copied() else {
        return Ok(false);
    };
    if probe.confirmed || probe.exhausted {
        return Ok(false);
    }

    let next_start_index = probe.candidate_index.saturating_add(1);
    let relocking_confirmed_source = probe.confirmed_once;
    let mut detach_errors = ctx
        .pid_links
        .remove(&pid)
        .map(|links| detach_pid_links(&mut ctx.bpf, pid, links))
        .unwrap_or_default();
    ctx.pid_task_starttimes.remove(&pid);
    clear_pid_samples(ctx, pid);

    let path = ctx
        .pid_libgui_paths
        .get(&pid)
        .cloned()
        .map_or_else(|| resolve_libgui_path(pid as i32), Ok)?;
    ctx.pid_libgui_paths.insert(pid, path.clone());
    let offsets = cached_candidate_offsets(ctx, &path)?;
    match attach_process_candidate(&mut ctx.bpf, pid, &path, &offsets, next_start_index) {
        Ok((candidate_index, symbol, offset, task_links)) => {
            let task_starttimes = task_starttime_snapshot(pid, task_links.keys().copied());
            let mut next_probe = probe;
            if probe.confirmed_once {
                next_probe.stalled_mask |= candidate_bit(probe.candidate_index);
            } else {
                next_probe.no_frame_mask |= candidate_bit(probe.candidate_index);
            }
            next_probe.candidate_index = candidate_index;
            next_probe.candidate_started_ns = now_ns;
            next_probe.confirmed = false;
            next_probe.attachable_mask |= candidate_bit(candidate_index);
            next_probe.unavailable_mask |= candidate_range_mask(next_start_index, candidate_index);
            ctx.pid_links.insert(pid, task_links);
            ctx.pid_task_starttimes.insert(pid, task_starttimes);
            ctx.pid_symbols.insert(pid, symbol.clone());
            ctx.pid_symbol_offsets.insert(pid, offset);
            ctx.pid_symbol_probes.insert(pid, next_probe);
            ctx.symbol = symbol;
            if relocking_confirmed_source {
                log_info!(
                    "[FPS] 符号重锁: PID={pid} 剩余候选={} 当前尝试={}",
                    LIBGUI_FRAME_SYMBOLS
                        .len()
                        .saturating_sub(candidate_index + 1),
                    readable_symbol_name(candidate_index),
                );
            }
            if !detach_errors.is_empty() {
                ctx.last_error = cstring_lossy(compact_error_details(&detach_errors));
            }
            Ok(true)
        }
        Err(err) => {
            let mut exhausted_probe = probe;
            if probe.confirmed_once {
                exhausted_probe.stalled_mask |= candidate_bit(probe.candidate_index);
            } else {
                exhausted_probe.no_frame_mask |= candidate_bit(probe.candidate_index);
            }
            exhausted_probe.unavailable_mask |=
                candidate_range_mask(next_start_index, LIBGUI_FRAME_SYMBOLS.len());
            exhausted_probe.exhausted = true;
            ctx.pid_symbol_probes.insert(pid, exhausted_probe);
            ctx.pid_symbols.remove(&pid);
            ctx.pid_symbol_offsets.remove(&pid);
            detach_errors.push(err);
            let error = compact_error_details(&detach_errors);
            ctx.last_error = cstring_lossy(&error);
            let role = pid_role_label(pid, ctx.target_pkg.as_deref());
            let active_pid = ctx
                .selected_stream
                .map(|key| key.pid)
                .filter(|active| *active != pid);
            let action = active_pid.map_or_else(
                || "处理=等待其他帧源或降级判断".to_string(),
                |active| format!("处理=忽略；当前帧源PID={active}继续工作"),
            );
            let duration = now_ns.saturating_sub(probe.round_started_ns) as f64 / 1_000_000_000.0;
            if ctx.detailed_logging {
                log_info!(
                    "[FPS] 进程探测完成: PID={pid} 角色={role} 结果={} 可挂载候选={}/{} 耗时={duration:.1}秒 {action}",
                    if probe.confirmed_once {
                        "重锁失败"
                    } else {
                        "无帧"
                    },
                    exhausted_probe.attachable_mask.count_ones(),
                    LIBGUI_FRAME_SYMBOLS.len(),
                );
                log_info!("[FPS]   {}", probe_candidate_results(exhausted_probe));
                if let Some(active_pid) = active_pid {
                    log_info!(
                        "[FPS] 多进程状态: 主帧源PID={active_pid}继续工作；无帧子进程PID={pid}已忽略，不影响当前FPS"
                    );
                }
            }
            Ok(false)
        }
    }
}

pub(crate) fn confirm_observed_symbols(ctx: &mut QixiaThreadsEbpfCtx) {
    // 必须形成通过预热、样本量和异常帧过滤的稳定 FPS 才确认候选。
    // 这样 probe_state=2 时守护进程可以直接输出，不会在窗口尚未形成时抢先写入 0。
    let now_ns = monotonic_ns();
    let observed = ctx
        .pid_symbol_probes
        .keys()
        .copied()
        .filter_map(|pid| {
            let selected = ctx
                .selected_stream
                .filter(|key| key.pid == pid)
                .and_then(|key| ctx.streams.get(&key));
            let best = selected.or_else(|| {
                ctx.streams
                    .iter()
                    .filter(|(key, _)| key.pid == pid)
                    .max_by(|(_, left), (_, right)| {
                        left.selection_score(now_ns)
                            .partial_cmp(&right.selection_score(now_ns))
                            .unwrap_or(std::cmp::Ordering::Equal)
                    })
                    .map(|(_, stream)| stream)
            })?;
            best.selection_score(now_ns)
                .is_finite()
                .then_some((pid, best.cur_fps))
        })
        .collect::<Vec<_>>();
    for (pid, fps) in observed {
        let Some(probe) = ctx.pid_symbol_probes.get_mut(&pid) else {
            continue;
        };
        if probe.confirmed {
            continue;
        }
        let recovered = probe.confirmed_once;
        probe.confirmed = true;
        probe.confirmed_once = true;
        probe.exhausted = false;
        if let Some(symbol) = ctx.pid_symbols.get(&pid).cloned() {
            ctx.symbol = symbol.clone();
            let raw_symbol = symbol.to_string_lossy();
            if recovered {
                log_info!(
                    "[FPS] 帧源恢复: PID={pid} 角色={} 候选={}/{} 符号={} FPS={fps:.1} 降级=未触发",
                    pid_role_label(pid, ctx.target_pkg.as_deref()),
                    probe.candidate_index + 1,
                    LIBGUI_FRAME_SYMBOLS.len(),
                    readable_symbol_from_raw(raw_symbol.as_ref()),
                );
            } else {
                log_info!(
                    "[FPS] 帧源确认: PID={pid} 角色={} 候选={}/{} 符号={} FPS={fps:.1}",
                    pid_role_label(pid, ctx.target_pkg.as_deref()),
                    probe.candidate_index + 1,
                    LIBGUI_FRAME_SYMBOLS.len(),
                    readable_symbol_from_raw(raw_symbol.as_ref()),
                );
            }
            log_info!("[FPS]   原始符号={raw_symbol}");
        }
    }
    if let Some(symbol) = ctx
        .selected_stream
        .and_then(|key| ctx.pid_symbols.get(&key.pid))
        .cloned()
    {
        ctx.symbol = symbol;
    }
}

pub(crate) fn advance_symbol_probes(ctx: &mut QixiaThreadsEbpfCtx, now_ns: u64) -> Result<(), String> {
    if now_ns == 0 {
        return Ok(());
    }
    let expired = ctx
        .pid_symbol_probes
        .iter()
        .filter_map(|(pid, probe)| {
            (!probe.confirmed
                && !probe.exhausted
                && now_ns.saturating_sub(probe.candidate_started_ns) >= SYMBOL_PROBE_NS)
                .then_some(*pid)
        })
        .collect::<Vec<_>>();
    let mut errors = Vec::new();
    for pid in expired {
        if let Err(err) = switch_pid_symbol(ctx, pid, now_ns) {
            if let Some(probe) = ctx.pid_symbol_probes.get_mut(&pid) {
                probe.exhausted = true;
            }
            errors.push(err);
        }
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors.join("; "))
    }
}

pub(crate) fn retry_confirmed_symbols(ctx: &mut QixiaThreadsEbpfCtx) -> Result<usize, String> {
    let now_ns = monotonic_ns();
    let confirmed = ctx
        .pid_symbol_probes
        .iter()
        .filter_map(|(pid, probe)| probe.confirmed.then_some(*pid))
        .collect::<Vec<_>>();
    let mut switched = 0usize;
    let mut errors = Vec::new();
    for pid in confirmed {
        if let Some(probe) = ctx.pid_symbol_probes.get_mut(&pid) {
            probe.confirmed = false;
            probe.round_started_ns = now_ns;
        }
        match switch_pid_symbol(ctx, pid, now_ns) {
            Ok(true) => switched += 1,
            Ok(false) => {}
            Err(err) => {
                if let Some(probe) = ctx.pid_symbol_probes.get_mut(&pid) {
                    probe.exhausted = true;
                }
                errors.push(err);
            }
        }
    }
    if errors.is_empty() {
        Ok(switched)
    } else {
        Err(errors.join("; "))
    }
}
