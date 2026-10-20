use crate::constants::LIBGUI_FRAME_SYMBOLS;
use crate::diagnostics::{compact_error_details, cstring_lossy};
use aya::Ebpf;
use aya::programs::UProbe;
use aya::programs::uprobe::{UProbeLinkId, UProbeScope};
use std::ffi::CString;
use std::num::NonZeroU32;
use std::path::Path;

pub(crate) fn load_uprobe_program(bpf: &mut Ebpf) -> Result<(), String> {
    let program: &mut UProbe = bpf
        .program_mut("on_frame_latched")
        .ok_or_else(|| "missing BPF program: on_frame_latched（请同步更新 BPF 对象）".to_string())?
        .try_into()
        .map_err(|e: aya::programs::ProgramError| e.to_string())?;
    program.load().map_err(|e| e.to_string())
}

pub(crate) fn uprobe_program(bpf: &mut Ebpf) -> Result<&mut UProbe, String> {
    bpf.program_mut("on_frame_latched")
        .ok_or_else(|| "missing BPF program: on_frame_latched（请同步更新 BPF 对象）".to_string())?
        .try_into()
        .map_err(|e: aya::programs::ProgramError| e.to_string())
}

pub(crate) fn attach_symbol_for_process(
    bpf: &mut Ebpf,
    pid: u32,
    path: &Path,
    symbol: &str,
    offset: u64,
) -> Result<UProbeLinkId, String> {
    // 内核 uprobe 的 perf 过滤按目标地址空间生效，BPF 程序覆盖同进程的已有和新建线程。
    // 每个 TID 重复挂载会重复执行同一个探针，退出时还会逐次扫描映射并注销，制造 CPU 峰值。
    // 这里只绑定进程主 PID；仍由 target_tgids 在 BPF 中校验身份，不扩大为全系统探针。
    let scope = UProbeScope::OneProcess(
        NonZeroU32::new(pid).ok_or_else(|| "invalid target pid".to_string())?,
    );
    uprobe_program(bpf)?.attach(offset, path, scope).map_err(|err| {
        format!("pid={pid} symbol={symbol} {}: {}", path.display(), crate::diagnostics::error_chain(&err))
    })
}

pub(crate) fn detach_pid_links(bpf: &mut Ebpf, pid: u32, links: Vec<UProbeLinkId>) -> Vec<String> {
    let program = match uprobe_program(bpf) {
        Ok(program) => program,
        Err(err) => return vec![format!("pid={pid} 获取 uprobe 程序失败: {err}")],
    };
    links.into_iter().filter_map(|link| {
        program.detach(link).err().map(|err| format!("pid={pid} 解除 uprobe 失败: {err}"))
    }).collect()
}

pub(crate) fn attach_process_candidate(
    bpf: &mut Ebpf,
    pid: u32,
    path: &Path,
    offsets: &[Option<u64>],
    start_index: usize,
) -> Result<(usize, CString, Vec<UProbeLinkId>), String> {
    let mut errors = Vec::new();
    for (index, symbol) in LIBGUI_FRAME_SYMBOLS.iter().enumerate().skip(start_index) {
        let Some(offset) = offsets.get(index).copied().flatten() else { continue; };
        match attach_symbol_for_process(bpf, pid, path, symbol, offset) {
            Ok(link) => return Ok((index, cstring_lossy(symbol), vec![link])),
            Err(error) => errors.push(error),
        }
    }
    Err(if errors.is_empty() {
        format!("pid={pid} 没有剩余可挂载的帧合成完成候选")
    } else {
        compact_error_details(&errors)
    })
}
