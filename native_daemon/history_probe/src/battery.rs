use crate::{
    fs::{name, number, KernelFs},
    Sample,
};

pub struct Battery {
    pub packs: Vec<String>,
    pub external: Vec<String>,
}
impl Battery {
    pub fn discover(fs: &KernelFs) -> Self {
        let mut packs = Vec::new();
        let mut external = Vec::new();
        for p in fs.children("/sys/class/power_supply", 64) {
            let kind = fs
                .read(&format!("{p}/type"))
                .unwrap_or_default()
                .to_ascii_lowercase();
            let label = name(&p).to_ascii_lowercase();
            if kind == "battery" || matches!(label.as_str(), "battery" | "bms" | "batt") {
                // 不累加子电池组的电流，也不假定电池的串并联结构。
                if !label.contains("wireless") {
                    packs.push(p);
                }
            } else if fs.exists(&format!("{p}/online")) {
                external.push(p);
            }
        }
        packs.sort_by_key(|p| match name(p) {
            "battery" => 0,
            "bms" => 1,
            "batt" => 2,
            _ => 3,
        });
        Self { packs, external }
    }
    pub fn sample(&self, fs: &KernelFs, out: &mut Sample) {
        let online = self.external.iter().any(|p| {
            property(fs, p, "online")
                .as_deref()
                .and_then(number)
                .is_some_and(|v| v > 0.0)
        });
        let status = self
            .packs
            .iter()
            .find_map(|p| property(fs, p, "status").filter(|s| !s.is_empty()));
        out.charging = if online {
            Some(true)
        } else {
            match status.as_deref().map(str::to_ascii_lowercase).as_deref() {
                Some("discharging") => Some(false),
                Some("charging" | "full" | "not charging") => Some(true),
                _ => None,
            }
        };
        for (property_name, key, divisor, min, max) in [
            ("temp", "battery_c", 10.0, -10.0, 150.0),
            ("capacity", "battery_pct", 1.0, 0.0, 100.0),
            ("voltage_now", "battery_v", 1e6, 2.0, 20.0),
        ] {
            if let Some(value) = self
                .packs
                .iter()
                .filter_map(|p| numeric(fs, p, property_name))
                .map(|v| v / divisor)
                .find(|v| v.is_finite() && *v >= min && *v <= max)
            {
                out.add(key, value, min, max);
            }
        }
        if out.charging != Some(false) {
            return;
        }
        // 两项测量必须来自同一电池组，并遵循 power_supply ABI 规定的单位。
        for pack in &self.packs {
            let Some(voltage) =
                numeric(fs, pack, "voltage_now").filter(|v| (2e6..=20e6).contains(v))
            else {
                continue;
            };
            let Some(current) = numeric(fs, pack, "current_now").filter(|v| v.abs() <= 30e6) else {
                continue;
            };
            let power = current.abs() * voltage / 1e12;
            if power > 100.0 {
                continue;
            }
            out.add("battery_v", voltage / 1e6, 2.0, 20.0);
            out.add("battery_ma", current.abs() / 1000.0, 0.0, 30000.0);
            out.add("power_w", power, 0.0, 100.0);
            break;
        }
    }
}
fn numeric(fs: &KernelFs, pack: &str, key: &str) -> Option<f64> {
    property(fs, pack, key).as_deref().and_then(number)
}
fn property(fs: &KernelFs, pack: &str, key: &str) -> Option<String> {
    fs.read(&format!("{pack}/{key}"))
        .filter(|s| !s.is_empty())
        .or_else(|| {
            let prefix = format!("POWER_SUPPLY_{}=", key.to_ascii_uppercase());
            fs.read(&format!("{pack}/uevent"))?
                .lines()
                .find_map(|s| s.strip_prefix(&prefix).map(str::to_owned))
        })
}
