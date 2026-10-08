use crate::{
    fs::{canonical, name, number, KernelFs},
    Sample,
};
use std::collections::BTreeSet;

#[derive(Clone, Copy)]
enum Format {
    Scale(f64),
    Percent,
    BusyPair,
    GedFrequency,
    PixelClock,
    DdrDump,
}
struct Source {
    path: String,
    format: Format,
}
impl Source {
    fn read(&self, fs: &KernelFs) -> Option<f64> {
        let text = fs.read(&self.path)?;
        match self.format {
            Format::Scale(scale) => number(&text).map(|n| n * scale),
            Format::Percent => number(text.split_whitespace().next()?.trim_end_matches('%')),
            Format::BusyPair => {
                let mut parts = text.split_whitespace();
                let busy = number(parts.next()?)?;
                let total = number(parts.next()?)?;
                (busy >= 0.0 && busy <= total && total > 0.0).then_some(busy * 100.0 / total)
            }
            Format::GedFrequency => {
                let mut parts = text.split_whitespace();
                parts.next()?.parse::<u32>().ok()?;
                number(parts.next()?).map(|n| n / 1000.0)
            }
            Format::PixelClock => text.lines().find_map(|line| {
                if !line.contains("(shaders)") {
                    return None;
                }
                frequency_with_unit(line.split_once(':')?.1)
                    .filter(|(_, unit)| *unit == "MHz")
                    .map(|v| v.0)
            }),
            Format::DdrDump => None, // DDR 的频率与数据传输率使用不同单位，须分别处理。
        }
    }
}
#[derive(Default)]
pub struct Sensors {
    frequency: Vec<Source>,
    usage: Vec<Source>,
    ddr: Vec<Source>,
    cpu_temps: Vec<String>,
    soc_temps: Vec<String>,
    gpu_temps: Vec<String>,
}
fn push(fs: &KernelFs, to: &mut Vec<Source>, path: impl Into<String>, format: Format) {
    let path = path.into();
    if fs.exists(&path) && !to.iter().any(|s| s.path == path) && to.len() < 16 {
        to.push(Source { path, format });
    }
}
impl Sensors {
    pub fn discover(fs: &KernelFs) -> Self {
        let mut out = Self::default();
        let kgsl = "/sys/class/kgsl/kgsl-3d0";
        for p in [format!("{kgsl}/devfreq/cur_freq"), format!("{kgsl}/gpuclk")] {
            push(fs, &mut out.frequency, p, Format::Scale(1e-6));
        }
        push(
            fs,
            &mut out.usage,
            format!("{kgsl}/gpu_busy_percentage"),
            Format::Percent,
        );
        push(
            fs,
            &mut out.usage,
            format!("{kgsl}/gpubusy"),
            Format::BusyPair,
        );
        for ged in ["/sys/kernel/ged/hal", "/sys/kernel/debug/ged/hal"] {
            push(
                fs,
                &mut out.frequency,
                format!("{ged}/current_freqency"),
                Format::GedFrequency,
            );
            push(
                fs,
                &mut out.usage,
                format!("{ged}/gpu_utilization"),
                Format::Percent,
            );
        }
        // 上游 Mali、Qualcomm 及厂商内核均使用通用 devfreq 接口。
        let mut gpu_devices = Vec::new();
        for p in fs.children("/sys/class/devfreq", 128) {
            let identity = format!(
                "{} {} {}",
                p,
                fs.read(&format!("{p}/name")).unwrap_or_default(),
                canonical(&fs.path(&p)).to_string_lossy()
            )
            .to_ascii_lowercase();
            if ["gpu", "mali", "kgsl", "g3d"]
                .iter()
                .any(|s| identity.contains(s))
            {
                push(
                    fs,
                    &mut out.frequency,
                    format!("{p}/cur_freq"),
                    Format::Scale(1e-6),
                );
                gpu_devices.push(format!("{p}/device"));
            }
            if name(&p).contains("mtk-dvfsrc-devfreq") {
                push(
                    fs,
                    &mut out.ddr,
                    format!("{p}/cur_freq"),
                    Format::Scale(0.5e-6),
                );
            }
        }
        // Pixel/Tensor 的 clock_info 单位为 kHz，利用率单位为百分比；不依赖固定 SoC 地址。
        for dir in [
            "/sys/devices/platform",
            "/sys/devices/platform/soc",
            "/sys/class/misc",
        ] {
            gpu_devices.extend(fs.children(dir, 256).into_iter().filter(|p| {
                let n = name(p).to_ascii_lowercase();
                n.contains("mali") || n.ends_with(".gpu") || n == "gpu" || n == "mali0"
            }));
        }
        gpu_devices.sort();
        gpu_devices.dedup();
        gpu_devices.truncate(16);
        for p in gpu_devices {
            for base in [p.clone(), format!("{p}/device")] {
                push(
                    fs,
                    &mut out.frequency,
                    format!("{base}/clock_info"),
                    Format::PixelClock,
                );
                if fs.exists(&format!("{base}/clock_info")) {
                    push(
                        fs,
                        &mut out.usage,
                        format!("{base}/utilization"),
                        Format::Percent,
                    );
                }
                for dv in fs.children(&format!("{base}/devfreq"), 4) {
                    push(
                        fs,
                        &mut out.frequency,
                        format!("{dv}/cur_freq"),
                        Format::Scale(1e-6),
                    );
                }
            }
        }
        push(
            fs,
            &mut out.ddr,
            "/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq",
            Format::Scale(1e-3),
        );
        let mut ddr_dirs = vec![
            "/sys/kernel/helio-dvfsrc".to_owned(),
            "/sys/devices/platform/10012000.dvfsrc/helio-dvfsrc".to_owned(),
            "/sys/devices/platform/1c00f000.dvfsrc/1c00f000.dvfsrc:dvfsrc-helper".to_owned(),
        ];
        for dir in ["/sys/devices/platform", "/sys/devices/platform/soc"] {
            for p in fs
                .children(dir, 256)
                .into_iter()
                .filter(|p| name(p).contains("dvfsrc"))
            {
                ddr_dirs.push(p.clone());
                ddr_dirs.extend(
                    fs.children(&p, 16)
                        .into_iter()
                        .filter(|p| name(p).contains("dvfsrc")),
                );
            }
        }
        for p in ddr_dirs {
            push(
                fs,
                &mut out.ddr,
                format!("{p}/dvfsrc_dump"),
                Format::DdrDump,
            );
        }
        let mut seen = BTreeSet::new();
        for zone in fs
            .children("/sys/class/thermal", 256)
            .into_iter()
            .filter(|p| name(p).starts_with("thermal_zone"))
        {
            let Some(kind) = fs.read(&format!("{zone}/type")) else {
                continue;
            };
            let temp = format!("{zone}/temp");
            if !seen.insert(canonical(&fs.path(&temp))) {
                continue;
            }
            let k = kind.to_ascii_lowercase();
            let group = if k == "cpu"
                || [
                    "cpu-", "cpu_", "cpuss", "mtktscpu", "tscpu", "cluster", "big", "little", "mid",
                ]
                .iter()
                .any(|s| k.starts_with(s))
                || k.strip_prefix("cpu")
                    .is_some_and(|s| s.starts_with(|c: char| c.is_ascii_digit()))
                || k.starts_with("apc-") && k.ends_with("-max-step")
            {
                &mut out.cpu_temps
            } else if ["gpu", "gpuss", "mtktsgpu", "mali", "g3d"]
                .iter()
                .any(|s| k.starts_with(s))
            {
                &mut out.gpu_temps
            } else if matches!(
                k.as_str(),
                "soc" | "soc-thermal" | "soc_thermal" | "soc_max"
            ) {
                &mut out.soc_temps
            } else {
                continue;
            };
            if group.len() < 16 {
                group.push(temp);
            }
        }
        out
    }
    pub fn sample(&self, fs: &KernelFs, out: &mut Sample) {
        for (sources, key, max) in [
            (&self.frequency, "gpu_mhz", 10000.0),
            (&self.usage, "gpu_usage", 100.0),
        ] {
            if let Some(v) = sources
                .iter()
                .filter_map(|s| s.read(fs))
                .find(|v| v.is_finite() && *v >= 0.0 && *v <= max)
            {
                out.add(key, v, 0.0, max);
            }
        }
        for source in &self.ddr {
            let reading = if matches!(source.format, Format::DdrDump) {
                fs.read(&source.path).and_then(|t| {
                    t.lines()
                        .filter(|l| l.trim_start().starts_with("DDR"))
                        .find_map(|l| l.split_once(':').and_then(|(_, v)| frequency_with_unit(v)))
                })
            } else {
                source.read(fs).map(|v| (v, "MHz"))
            };
            if let Some((value, unit)) = reading {
                let (key, max) = if unit == "Mbps" {
                    ("ddr_mbps", 30000.0)
                } else {
                    ("ddr_mhz", 10000.0)
                };
                if value.is_finite() && value >= 0.0 && value <= max {
                    out.add(key, value, 0.0, max);
                    break;
                }
            }
        }
        let max_temp = |paths: &[String]| {
            paths
                .iter()
                .filter_map(|p| fs.read(p).as_deref().and_then(number))
                .map(|v| v / 1000.0)
                .filter(|v| (-10.0..=150.0).contains(v))
                .max_by(f64::total_cmp)
        };
        if let Some(v) = max_temp(&self.cpu_temps).or_else(|| max_temp(&self.soc_temps)) {
            out.add("cpu_c", v, -10.0, 150.0);
        }
        if let Some(v) = max_temp(&self.gpu_temps) {
            out.add("gpu_c", v, -10.0, 150.0);
        }
    }
}
pub fn frequency_with_unit(text: &str) -> Option<(f64, &'static str)> {
    let text = text.trim().to_ascii_lowercase();
    let split = text.find(|c: char| !c.is_ascii_digit() && c != '.')?;
    let value = number(&text[..split])?;
    match text[split..].trim() {
        "khz" => Some((value / 1000.0, "MHz")),
        "mhz" => Some((value, "MHz")),
        "hz" => Some((value / 1e6, "MHz")),
        "mbps" | "mt/s" => Some((value, "Mbps")),
        _ => None,
    }
}
