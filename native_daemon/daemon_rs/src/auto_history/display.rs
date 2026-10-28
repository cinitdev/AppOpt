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

#[cfg(test)]
mod tests {
    use super::*;

    fn expected(width: u32, height: u32, hz: &str) -> Vec<(String, String)> {
        vec![("resolution".into(), format!("{width} × {height}")), ("refresh".into(), format!("{hz} Hz"))]
    }

    #[test]
    fn android12_modes_follow_active_id_instead_of_highest_rate() {
        let dump = "mBaseDisplayInfo=DisplayInfo{\"内置屏幕\", displayId 0\", real 1080 x 2400, mode 1, defaultMode 2, modes [{id=1, width=1080, height=2400, fps=60.000004, alternativeRefreshRates=[90.0, 120.00001]}, {id=2, width=1080, height=2400, fps=120.00001}], state ON}";
        assert_eq!(metadata(dump), expected(1080, 2400, "60"));
    }

    #[test]
    fn android15_ignores_render_rate_and_logical_resolution() {
        let dump = "mBaseDisplayInfo=DisplayInfo{\"内置屏幕\", displayId 0, real 3200 x 1440, mode 2, renderFrameRate 30.0, supportedModes [{id=1, width=1440, height=3200, fps=60.000004, vsync=60.000004}, {id=2, width=1440, height=3200, fps=120.00001, vsync=120.00001, alternativeRefreshRates=[60.000004, 90.0]}], state ON}";
        assert_eq!(metadata(dump), expected(1440, 3200, "120"));
    }

    #[test]
    fn android17_extra_mode_fields_do_not_change_selection() {
        let dump = "mBaseDisplayInfo=DisplayInfo{\"内置屏幕\", displayId 0, mode 1, renderFrameRate 30.0, supportedRefreshRates [60.000004, 30.000002, 20.0], supportedModes [{id=1, parentModeId=-1, sfModeId=0, flags=, width=1280, height=2856, fps=60.000004, vsync=60.000004, alternativeRefreshRates=[], supportedHdrTypes=[]}], state ON}";
        assert_eq!(metadata(dump), expected(1280, 2856, "60"));
    }

    #[test]
    fn high_refresh_rates_are_not_capped_or_truncated() {
        for (rate, expected_rate) in [(144.0, "144"), (164.99999, "165"), (239.76, "239.8")] {
            let dump = format!("mBaseDisplayInfo=DisplayInfo{{\"内置屏幕\", displayId 0, mode 12, supportedModes [{{id=1, width=1080, height=2400, fps=60.0}}, {{id=12, width=1904, height=3040, fps={rate}}}]}}");
            assert_eq!(metadata(&dump), expected(1904, 3040, expected_rate));
        }
    }

    #[test]
    fn missing_primary_or_current_mode_is_not_guessed() {
        let external = "mBaseDisplayInfo=DisplayInfo{\"外接屏幕\", displayId 1, mode 1, supportedModes [{id=1, width=1920, height=1080, fps=165.0}]}";
        let missing = "mBaseDisplayInfo=DisplayInfo{\"内置屏幕\", displayId 0, mode 2, supportedModes [{id=1, width=1080, height=2400, fps=60.0}]}";
        assert!(metadata(external).is_empty());
        assert!(metadata(missing).is_empty());
        assert!(metadata("mBaseDisplayInfo=DisplayInfo{displayId 0, mode 1, supportedModes [").is_empty());
        let invalid = missing.replace("mode 2", "mode 1").replace("fps=60.0", "fps=NaN");
        assert_eq!(metadata(&invalid), vec![("resolution".into(), "1080 × 2400".into())]);
    }
}
