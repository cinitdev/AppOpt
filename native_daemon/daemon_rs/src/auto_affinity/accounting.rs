use super::demand::{Policy, Residency};
use super::topology::Core;
use std::fs;
use std::io::{self, Read};

fn number(path: &str) -> Option<u64> {
    fs::read_to_string(path).ok()?.trim().parse().ok()
}

pub(super) fn policies(cores: &[Core]) -> Option<Vec<Policy>> {
    let mut result: Vec<Policy> = Vec::new();
    for domain in qixia_kernel_info::cpu::policies(std::path::Path::new("/")) {
        if domain.members.iter().any(|id| *id >= 64) {
            return None;
        }
        let members = domain
            .members
            .iter()
            .fold(0u64, |mask, id| mask | (1u64 << id));
        let core = cores.iter().find(|c| domain.members.contains(&c.id))?;
        if members == 0
            || members & (1u64 << core.id) == 0
            || result.iter().any(|p| p.members & members != 0)
        {
            return None;
        }
        let max_khz = domain.max_khz?;
        if max_khz == 0 || core.capacity == 0 {
            return None;
        }
        let mut known = 0;
        for member in cores.iter().filter(|c| members & (1u64 << c.id) != 0) {
            // 频率域总计无法区分域内的异构核心。
            if member.capacity != core.capacity {
                return None;
            }
            known |= 1u64 << member.id;
        }
        if known != members {
            return None;
        }
        result.push(Policy {
            cpu: members.trailing_zeros() as usize,
            members,
            max_khz,
            capacity: core.capacity as f64,
        });
    }
    (!result.is_empty()).then_some(result)
}

pub(super) fn limits(policies: &[Policy]) -> [Option<f64>; 64] {
    let mut result = [None; 64];
    for policy in policies {
        let limit = number(&format!(
            "/sys/devices/system/cpu/cpu{}/cpufreq/scaling_max_freq",
            policy.cpu
        ))
        .filter(|value| *value > 0)
        .map(|value| (value as f64 / policy.max_khz as f64).min(1.0));
        for (id, slot) in result.iter_mut().enumerate() {
            if policy.members & (1u64 << id) != 0 {
                *slot = limit;
            }
        }
    }
    result
}

pub(super) fn residency(pid: i32, tid: i32) -> io::Result<Residency> {
    let file = fs::File::open(format!("/proc/{pid}/task/{tid}/time_in_state"))?;
    let mut text = String::new();
    file.take(65537).read_to_string(&mut text)?;
    Residency::parse(&text).ok_or_else(|| io::Error::other("频率驻留统计格式不支持"))
}
