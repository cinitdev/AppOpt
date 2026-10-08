mod battery;
mod cpu;
pub mod fs;
pub mod pmu;
mod sensors;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod stream;

use fs::{ids_key, number, KernelFs};
use std::collections::BTreeMap;

#[derive(Default, Debug)]
pub struct Sample {
    pub charging: Option<bool>,
    pub values: BTreeMap<String, f64>,
}
impl Sample {
    pub fn add(&mut self, key: &str, value: f64, minimum: f64, maximum: f64) {
        if value.is_finite() && value >= minimum && value <= maximum {
            self.values.insert(key.to_owned(), value);
        }
    }
}
/// 节点发现范围有界且会缓存；sample() 不启动 shell、外部命令或额外线程。
pub struct Collector {
    fs: KernelFs,
    policies: Vec<cpu::Policy>,
    usage: cpu::Usage,
    battery: battery::Battery,
    sensors: sensors::Sensors,
    next_discovery: u64,
}
impl Collector {
    pub fn new(root: impl Into<std::path::PathBuf>) -> Self {
        let fs = KernelFs::new(root);
        Self {
            policies: cpu::policies(&fs),
            battery: battery::Battery::discover(&fs),
            sensors: sensors::Sensors::discover(&fs),
            usage: cpu::Usage::default(),
            fs,
            next_discovery: 60_000,
        }
    }
    pub fn sample(&mut self, elapsed_ms: u64) -> Sample {
        if elapsed_ms >= self.next_discovery {
            // 兼容延迟创建的厂商节点，避免每轮采样都遍历整个文件系统。
            self.policies = cpu::policies(&self.fs);
            self.battery = battery::Battery::discover(&self.fs);
            self.sensors = sensors::Sensors::discover(&self.fs);
            self.next_discovery = elapsed_ms.saturating_add(60_000);
        }
        let mut out = Sample::default();
        for policy in &self.policies {
            if let Some(v) = policy
                .frequencies
                .iter()
                .filter_map(|p| self.fs.read(p).as_deref().and_then(number))
                .map(|v| v / 1000.0)
                .find(|v| (0.0..=10000.0).contains(v))
            {
                out.add(
                    &format!("cpu_mhz.{}", ids_key(&policy.cores)),
                    v,
                    0.0,
                    10000.0,
                );
            }
        }
        self.usage.sample(
            &self.fs.read_limit("/proc/stat", 65536).unwrap_or_default(),
            elapsed_ms,
            &self.policies,
            &mut out,
        );
        self.battery.sample(&self.fs, &mut out);
        self.sensors.sample(&self.fs, &mut out);
        out
    }
    pub fn add_cycles(&self, out: &mut Sample, cycles: &BTreeMap<u32, f64>) {
        for (cpu, value) in cycles {
            out.add(&format!("cpu_cycles_core.{cpu}"), *value, 0.0, 20000.0);
        }
        for p in &self.policies {
            let counts: Option<Vec<_>> = p.cores.iter().map(|id| cycles.get(id)).collect();
            if let Some(values) = counts {
                out.add(
                    &format!("cpu_cycles.{}", ids_key(&p.cores)),
                    values.iter().map(|v| **v).sum::<f64>() / values.len() as f64,
                    0.0,
                    20000.0,
                );
            }
        }
    }
}

#[cfg(test)]
mod tests;
