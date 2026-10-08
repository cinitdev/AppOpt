use super::*;
// 提供设置页的拓扑元数据，校准建议不读取旧负载档位。
pub(crate) fn print_version_diagnostics(version: &str) {
    log_info!("[校准] 活跃线程采集 → 核心能力建议 → App 确认保存；不自动写入规则");
    log_info!("[校准] 平均负载达到 5% 的活跃线程参与建议，保留通配合并；不读取旧负载档位与指定核心");
    for core in qixia_kernel_info::cpu::cores(Path::new("/")) {
        log_info!("[校准] CPU {} capacity={:?}", core.id, core.capacity);
    }
    log_info!("QixiaThreads 版本 {version}");
}

pub(crate) struct PolicyTopologySync {
    topology: CpuTiers,
    next_check: Instant,
    next_detection: Instant,
}

impl PolicyTopologySync {
    pub(crate) fn new() -> Self {
        let now = Instant::now();
        Self { topology: CpuTiers::detect(), next_check: now,
            next_detection: now + Duration::from_secs(60) }
    }

    pub(crate) fn refresh(&mut self, changed: bool) {
        let now = Instant::now();
        if !changed && now < self.next_check { return; }
        // 同次开机期间硬件拓扑保持稳定，只有启动时证据不完整才需重新探测
        // sysfs；正常检查只读取一个小文件。
        if !self.topology.complete && now >= self.next_detection {
            self.topology = CpuTiers::detect();
            self.next_detection = now + Duration::from_secs(60);
        }
        let synced = sync_policy_topology(&self.topology);
        self.next_check = now + Duration::from_secs(if synced { 60 } else { 2 });
    }
}
