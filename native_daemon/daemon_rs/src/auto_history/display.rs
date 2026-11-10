//! 只读取主屏当前模式，不把应用逻辑帧率、支持列表最大值或外接屏当成采集值。

pub(super) fn metadata(dump: &str) -> Vec<(String, String)> {
    let Some(info) = dump.lines().find_map(|line| {
        let info = line.trim().strip_prefix("mBaseDisplayInfo=DisplayInfo{")?;
        (integer(info.split_once(", displayId ")?.1) == Some(0)).then_some(info)
    }) else { return Vec::new() };
    let Some(active) = info.split_once(", mode ").and_then(|(_, value)| integer(value))
    else { return Vec::new() };
    // Android 12 使用 modes，后续版本使用 supportedModes；数组内还有刷新率等嵌套数组。
    let Some((_, modes)) = info.split_once(", supportedModes [").or_else(|| info.split_once(", modes ["))
    else { return Vec::new() };
    let mut depth = 1usize;
    let Some(end) = modes.char_indices().find_map(|(index, ch)| {
        match ch { '[' => depth += 1, ']' => depth -= 1, _ => {} }
        (depth == 0).then_some(index)
    }) else { return Vec::new() };
    for entry in modes[..end].split("{id=").skip(1) {
        if integer(entry) != Some(active) { continue; }
        let field = |name: &str| entry.split(',').find_map(|item| {
            let (key, value) = item.trim().split_once('=')?;
            (key == name).then_some(value)
        });
        let mut result = Vec::new();
        if let (Some(width), Some(height)) = (field("width").and_then(integer), field("height").and_then(integer)) {
            if width > 0 && height > 0 {
                result.push(("resolution".into(), format!("{width} × {height}")));
            }
        }
        if let Some(hz) = field("fps").and_then(|value| {
            value.trim_end_matches('}').trim().parse::<f64>().ok()
        }).filter(|hz| hz.is_finite() && *hz > 0.0) {
            let hz = format!("{hz:.1}");
            result.push(("refresh".into(), format!("{} Hz", hz.strip_suffix(".0").unwrap_or(&hz))));
        }
        return result;
    }
    Vec::new()
}

fn integer(value: &str) -> Option<u32> {
    let value = value.trim_start();
    let end = value.bytes().take_while(u8::is_ascii_digit).count();
    value[..end].parse().ok()
}
