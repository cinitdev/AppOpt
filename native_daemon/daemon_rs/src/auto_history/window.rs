//! 合并已采样窗口，保留两秒一次历史写入之间的短时活跃。
//! 缺失的观测不能当作零负载样本。
use super::ThreadSample;
use std::collections::BTreeMap;

#[derive(Default)]
pub(super) struct Window {
    timestamp_ms: u64,
    threads: BTreeMap<(i32, i32, u64), (ThreadSample, u32)>,
}
impl Window {
    pub fn observe(&mut self, timestamp_ms: u64, samples: Vec<ThreadSample>) {
        self.timestamp_ms = self.timestamp_ms.max(timestamp_ms);
        for sample in samples.into_iter().take(512) {
            let key = (sample.pid, sample.tid, sample.start);
            if let Some((previous, count)) = self.threads.get_mut(&key) {
                previous.percent = (previous.percent * *count as f64 + sample.percent) / (*count + 1) as f64;
                previous.name = sample.name;
                *count += 1;
            } else if self.threads.len() < 4096 {
                self.threads.insert(key, (sample, 1));
            }
        }
    }
    pub fn take(&mut self) -> Option<(u64, Vec<ThreadSample>)> {
        if self.threads.is_empty() { return None; }
        Some((self.timestamp_ms, std::mem::take(&mut self.threads).into_values().map(|(sample, _)| sample).collect()))
    }
}
