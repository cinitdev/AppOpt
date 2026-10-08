use crate::{
    fs::{ids_key, KernelFs},
    Sample,
};
use std::collections::BTreeMap;

pub struct Policy {
    pub cores: Vec<u32>,
    pub frequencies: Vec<String>,
}
pub fn policies(fs: &KernelFs) -> Vec<Policy> {
    qixia_kernel_info::cpu::policies(&fs.0)
        .into_iter()
        .filter(|p| p.members.iter().all(|id| *id < 64))
        .map(|p| Policy {
            cores: p.members.into_iter().map(|id| id as u32).collect(),
            frequencies: p
                .paths
                .into_iter()
                .flat_map(|path| {
                    ["scaling_cur_freq", "cpuinfo_cur_freq"]
                        .into_iter()
                        .map(move |key| format!("{path}/{key}"))
                })
                .filter(|p| fs.exists(p))
                .collect(),
        })
        .collect()
}
#[derive(Default)]
pub struct Usage {
    previous: BTreeMap<String, [u64; 8]>,
    time: Option<u64>,
}
impl Usage {
    pub fn sample(&mut self, text: &str, now_ms: u64, policies: &[Policy], sample: &mut Sample) {
        let mut current = BTreeMap::new();
        for line in text.lines().take(65) {
            let mut fields = line.split_whitespace();
            let Some(label) = fields.next() else { continue };
            if label != "cpu"
                && label
                    .strip_prefix("cpu")
                    .and_then(|id| id.parse::<u32>().ok())
                    .filter(|id| *id < 64)
                    .is_none()
            {
                continue;
            }
            let fields: Option<Vec<u64>> = fields.take(8).map(|n| n.parse().ok()).collect();
            if let Some(values) = fields.and_then(|v| <[u64; 8]>::try_from(v).ok()) {
                current.insert(label.to_owned(), values);
            }
        }
        let mut deltas = BTreeMap::new();
        if self.time.is_some_and(|t| now_ms > t && now_ms - t <= 6000) {
            for (key, value) in &current {
                let Some(old) = self.previous.get(key) else {
                    continue;
                };
                let delta: Option<Vec<_>> = value
                    .iter()
                    .zip(old)
                    .map(|(a, b)| a.checked_sub(*b))
                    .collect();
                let Some(delta) = delta else { continue };
                let Some(total) = delta
                    .iter()
                    .try_fold(0u64, |sum, n| sum.checked_add(*n))
                    .filter(|v| *v > 0)
                else {
                    continue;
                };
                let busy = total - delta[3] - delta[4];
                let metric = if key == "cpu" {
                    "cpu_usage".to_owned()
                } else {
                    format!("cpu_core.{}", &key[3..])
                };
                sample.add(&metric, busy as f64 * 100.0 / total as f64, 0.0, 100.0);
                deltas.insert(key.clone(), (busy as f64, total as f64));
            }
            for policy in policies {
                let members: Option<Vec<_>> = policy
                    .cores
                    .iter()
                    .map(|id| deltas.get(&format!("cpu{id}")))
                    .collect();
                if let Some(members) = members {
                    let (busy, total) = members
                        .into_iter()
                        .fold((0.0, 0.0), |s, v| (s.0 + v.0, s.1 + v.1));
                    sample.add(
                        &format!("cpu_cluster.{}", ids_key(&policy.cores)),
                        busy * 100.0 / total,
                        0.0,
                        100.0,
                    );
                }
            }
        }
        self.previous = current;
        self.time = Some(now_ms);
    }
}
