use crate::constants::{
    LIBGUI_FRAME_SYMBOL_NAME_CSTRS, LIBGUI_FRAME_SYMBOL_NAMES, LIBGUI_FRAME_SYMBOLS,
    UNKNOWN_FRAME_SYMBOL_CSTR,
};
use crate::context::{QixiaThreadsEbpfCtx, PidSymbolProbe};
use object::{Object, ObjectSection, ObjectSymbol};
use std::fs;
use std::os::raw::c_char;
use std::path::Path;

pub(crate) fn candidate_bit(index: usize) -> u32 {
    1u32.checked_shl(index as u32).unwrap_or(0)
}

pub(crate) fn candidate_range_mask(start: usize, end: usize) -> u32 {
    (start.min(LIBGUI_FRAME_SYMBOLS.len())..end.min(LIBGUI_FRAME_SYMBOLS.len()))
        .fold(0, |mask, index| mask | candidate_bit(index))
}

pub(crate) fn readable_symbol_name(index: usize) -> &'static str {
    LIBGUI_FRAME_SYMBOL_NAMES
        .get(index)
        .copied()
        .unwrap_or("Surface::queueBuffer(未知候选)")
}

pub(crate) fn readable_symbol_from_raw(raw: &str) -> &'static str {
    LIBGUI_FRAME_SYMBOLS
        .iter()
        .position(|candidate| *candidate == raw)
        .map(readable_symbol_name)
        .unwrap_or("Surface::queueBuffer(未知符号)")
}

pub(crate) fn compact_symbol_name(index: usize) -> &'static str {
    readable_symbol_name(index)
        .strip_prefix("Surface::")
        .unwrap_or_else(|| readable_symbol_name(index))
}

pub(crate) fn readable_symbol_cstr_from_raw(raw: &str) -> *const c_char {
    LIBGUI_FRAME_SYMBOLS
        .iter()
        .position(|candidate| *candidate == raw)
        .and_then(|index| LIBGUI_FRAME_SYMBOL_NAME_CSTRS.get(index))
        .map_or(
            UNKNOWN_FRAME_SYMBOL_CSTR.as_ptr() as *const c_char,
            |value| value.as_ptr() as *const c_char,
        )
}

pub(crate) fn probe_candidate_results(probe: PidSymbolProbe) -> String {
    let mut results = Vec::new();
    for index in 0..LIBGUI_FRAME_SYMBOLS.len() {
        let bit = candidate_bit(index);
        if probe.no_frame_mask & bit != 0 {
            results.push(format!("{}=0", compact_symbol_name(index)));
        } else if probe.stalled_mask & bit != 0 {
            results.push(format!("{}=停帧", compact_symbol_name(index)));
        }
    }
    if results.is_empty() {
        "没有形成有效帧率的可挂载候选".to_string()
    } else {
        results.join(" | ")
    }
}

pub(crate) fn resolve_candidate_offsets(path: &Path) -> Result<Vec<Option<u64>>, String> {
    let data = fs::read(path).map_err(|err| format!("读取 {} 失败: {err}", path.display()))?;
    let object = object::read::File::parse(data.as_slice())
        .map_err(|err| format!("解析 {} 失败: {err}", path.display()))?;
    let mut offsets = vec![None; LIBGUI_FRAME_SYMBOLS.len()];

    for symbol in object.dynamic_symbols().chain(object.symbols()) {
        let Ok(name) = symbol.name() else {
            continue;
        };
        let Some(index) = LIBGUI_FRAME_SYMBOLS
            .iter()
            .position(|candidate| *candidate == name)
        else {
            continue;
        };
        let Some(section_index) = symbol.section_index() else {
            continue;
        };
        let section = object
            .section_by_index(section_index)
            .map_err(|err| format!("读取 {name} 段信息失败: {err}"))?;
        let Some((section_offset, _)) = section.file_range() else {
            continue;
        };
        let Some(relative) = symbol.address().checked_sub(section.address()) else {
            continue;
        };
        offsets[index] = relative.checked_add(section_offset);
    }

    Ok(offsets)
}

pub(crate) fn cached_candidate_offsets(
    ctx: &mut QixiaThreadsEbpfCtx,
    path: &Path,
) -> Result<Vec<Option<u64>>, String> {
    if let Some(offsets) = ctx.libgui_symbol_offsets.get(path) {
        return Ok(offsets.clone());
    }
    let offsets = resolve_candidate_offsets(path)?;
    ctx.libgui_symbol_offsets
        .insert(path.to_path_buf(), offsets.clone());
    Ok(offsets)
}
