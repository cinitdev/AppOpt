//! 共享的联合容量预算；校准与运行时采用同一分配方式。
use super::topology::Core;

// 为突发负载和唤醒延迟多预留一些处理能力。
// 这里只改变可行性判断，不修改硬件频率或内核功耗策略。
const SERVICE_FRACTION: f64 = 0.75;
const DEMAND_MARGIN: f64 = 1.15;

#[derive(Clone, Debug)]
pub(super) struct Demand {
    pub serial: f64,
    pub total: f64,
    pub parallel: usize,
    pub allowed: u64,
}

/// 将实测工作量归一化到内核容量尺度。接近饱和时，已完成工作量
/// 可能低估实际需求，低频时尤为明显，因此增加连续的占用率下限。
/// 对占用率取平方，避免把轻忙碌线程的全部 CPU 时间
/// 都当作在最快核心上执行。
/// 这是保守的负载启发式估计，不是物理功耗模型。
pub(super) fn workload(average: f64, peak: f64, maximum: f64, rate: f64, observed: f64) -> f64 {
    let busy = average.clamp(0.0, 1.0);
    (peak.clamp(0.0, 1.0) * rate.clamp(0.0, maximum))
        .max(busy * busy * maximum)
        .max(observed)
        .min(maximum)
}


#[derive(Clone)]
pub(super) struct Budget<'a> {
    cores: &'a [Core],
    limits: &'a [Option<f64>; 64],
    reserved: [f64; 64],
    service_fraction: f64,
    demand_margin: f64,
}

impl<'a> Budget<'a> {
    pub(super) fn new(cores: &'a [Core], limits: &'a [Option<f64>; 64]) -> Self {
        Self {
            cores,
            limits,
            reserved: [0.0; 64],
            service_fraction: SERVICE_FRACTION,
            demand_margin: DEMAND_MARGIN,
        }
    }

    pub(super) fn reserve_external(&mut self, loads: &[Option<f64>; 64]) {
        for core in self.cores {
            self.reserved[core.id] = core.capacity as f64
                * self.limits[core.id].unwrap_or(1.0)
                * loads[core.id].unwrap_or(1.0).clamp(0.0, 1.0);
        }
    }

    /// 按运行时滞回机制实际保留的核心池计入预算，
    /// 防止后续线程消耗仅在提议方案中才空闲的容量。
    pub(super) fn reserve_placement(&mut self, demand: &Demand, mask: u64) {
        let selected: Vec<_> = self
            .cores
            .iter()
            .filter(|core| mask & demand.allowed & (1u64 << core.id) != 0)
            .collect();
        if !selected.is_empty() {
            self.charge(&selected, demand.total.max(demand.serial) * self.demand_margin);
        }
    }

    pub(super) fn assign(&mut self, demand: &Demand) -> (u64, &'static str) {
        self.assign_preferred(demand, 0)
    }

    /// 预留当前线程前使用：只有执行通道确实不足时才可跳过迁移滞回。
    /// 策略上限仍是真实容量约束，
    /// 不涉及温度测量或因过热而否决。
    pub(super) fn fits(&self, demand: &Demand, current: u64) -> bool {
        if current == 0 || current & !demand.allowed != 0 {
            return false;
        }
        let mut free = 0.0;
        let mut largest: f64 = 0.0;
        let mut lanes = 0;
        for c in self.cores.iter().filter(|c| current & (1u64 << c.id) != 0) {
            let Some(limit) = self.limits[c.id] else {
                return false;
            };
            let room = (c.capacity as f64 * limit * self.service_fraction - self.reserved[c.id]).max(0.0);
            free += room;
            largest = largest.max(room);
            lanes += 1;
        }
        largest >= demand.serial * self.demand_margin && free >= demand.total * self.demand_margin && lanes >= demand.parallel
    }

    /// 比较单条执行通道，不累加亲和性池的容量。负载归属尚不明确时，
    /// 这里只识别真实的硬件或可用处理能力提升，
    /// 不猜测其他线程的 CPU 时间。
    pub(super) fn stronger_lane(&self, allowed: u64, current: u64, desired: u64) -> bool {
        let service = |mask: u64| self.cores.iter()
            .filter(|core| mask & allowed & (1u64 << core.id) != 0)
            .filter_map(|core| self.limits[core.id].map(|limit| core.capacity as f64 * limit))
            .fold(0.0, f64::max);
        service(desired) > service(current)
    }


    /// 比较竞争状态下最好的串行执行通道。两颗候选 CPU 互为备选，
    /// 并不意味着同一个线程可以在两条通道上并行执行。
    pub(super) fn materially_better(&self, demand: &Demand, current: u64, desired: u64) -> bool {
        if current == 0 || current & !demand.allowed != 0 { return true; }
        // 即使比例变化很小，达到完整处理预算也是真实改善。
        // 噪声门槛只适用于两个核心池
        // 都仍未达到所需余量的情况。
        if !self.fits(demand, current) && self.fits(demand, desired) { return true; }
        let score = |mask: u64| self.cores.iter()
            .filter(|core| mask & demand.allowed & (1u64 << core.id) != 0)
            .filter_map(|core| self.limits[core.id].map(|limit| {
                core.capacity as f64 * limit * self.service_fraction
                    / (self.reserved[core.id] + demand.serial * self.demand_margin).max(f64::EPSILON)
            }))
            .fold(0.0, f64::max);
        let previous = score(current);
        let proposed = score(desired);
        proposed > previous * 1.12
    }

    pub(super) fn assign_preferred(
        &mut self,
        demand: &Demand,
        preferred: u64,
    ) -> (u64, &'static str) {
        let available: Vec<_> = self
            .cores
            .iter()
            .filter(|c| demand.allowed & (1u64 << c.id) != 0)
            .collect();
        let full = available.iter().fold(0, |m, c| m | (1u64 << c.id));
        if full == 0 {
            return (0, "没有可用核心，请检查处理器状态");
        }
        if available
            .iter()
            .any(|c| c.capacity == 0 || self.limits[c.id].is_none())
        {
            return (full, "核心能力或频率信息不完整，建议保留全部可用核心");
        }
        // 容量是内核的相对尺度，不是 CPU 编号或宣传频率；
        // 需预留后台余量及测量不确定性。
        let supply = |c: &Core| c.capacity as f64 * self.limits[c.id].unwrap_or(1.0) * self.service_fraction;
        let serial = demand.serial * self.demand_margin;
        let total = demand.total.max(demand.serial) * self.demand_margin;
        let min_lanes = demand.parallel.min(available.len()).max(1);
        if min_lanes == 1 {
            // 亲和性掩码包含更多位，不会让串行工作变成并行。
            // 单条通道必须能够承载完整突发余量，
            // 不能为强行给出单核结果而降低安全余量。
            let mut fitting: Vec<_> = available
                .iter()
                .copied()
                .filter(|c| supply(c) - self.reserved[c.id] >= total)
                .collect();
            fitting.sort_by(|a, b| {
                a.capacity
                    .cmp(&b.capacity)
                    // 同等能力的当前核心仍能承载时，继续留在该核心；
                    // 微小负载波动不应引发缓存迁移。
                    .then_with(|| {
                        (preferred & (1u64 << b.id) != 0).cmp(&(preferred & (1u64 << a.id) != 0))
                    })
                    .then_with(|| {
                        (self.reserved[a.id] / supply(a))
                            .total_cmp(&(self.reserved[b.id] / supply(b)))
                    })
                    .then_with(|| a.id.cmp(&b.id))
            });
            if let Some(core) = fitting.first() {
                self.charge(&[*core], total);
                return (
                    1u64 << core.id,
                    "按实际核心能力与负载选择单核，已计入其他线程和突发余量",
                );
            }
            return self.serial_pool(&available, demand, preferred);
        }
        let mut capable: Vec<_> = available
            .iter()
            .copied()
            .filter(|c| supply(c) >= serial)
            .collect();
        capable.sort_by(|a, b| {
            a.capacity
                .cmp(&b.capacity)
                .then_with(|| self.reserved[a.id].total_cmp(&self.reserved[b.id]))
                .then_with(|| a.id.cmp(&b.id))
        });
        let mut selected = Vec::new();
        // 检查每个能力档位，预留实际剩余处理能力，
        // 不能按多核掩码平均分摊。对下一个线程而言，
        // 已经繁忙的超大核不能表现得像全新的空闲核心。
        for ceiling in capable.iter().map(|c| c.capacity) {
            let mut pool: Vec<_> = capable
                .iter()
                .copied()
                .filter(|c| c.capacity <= ceiling && supply(c) > self.reserved[c.id])
                .collect();
            pool.sort_by(|a, b| {
                (self.reserved[a.id] / supply(a))
                    .total_cmp(&(self.reserved[b.id] / supply(b)))
                    .then_with(|| a.capacity.cmp(&b.capacity))
                    .then_with(|| a.id.cmp(&b.id))
            });
            let mut free = 0.0;
            selected.clear();
            for core in pool {
                // 单线程需要一条通道承载整个串行突发；
                // 同一线程族的多个成员则允许跨通道并行运行。
                if min_lanes == 1 && supply(core) - self.reserved[core.id] < serial {
                    continue;
                }
                free += supply(core) - self.reserved[core.id];
                selected.push(core);
                if free >= total && selected.len() >= min_lanes {
                    break;
                }
            }
            if free >= total && selected.len() >= min_lanes {
                self.charge(&selected, total);
                return (
                    mask(&selected),
                    "按核心能力与本次负载联合分配，已计入其他线程组和突发余量",
                );
            }
        }
        // 把更多线程绑到同一超大核，无法解决超额突发需求。
        // 应从最强的可用核心开始扩大核心池，直到联合预算满足需求，
        // 再将池内具体调度交给内核。
        let mut ranked = available;
        ranked.sort_by(|a, b| {
            supply(b)
                .total_cmp(&supply(a))
                .then_with(|| a.id.cmp(&b.id))
        });
        selected.clear();
        let mut free = 0.0;
        let lanes = min_lanes;
        for core in ranked {
            free += (supply(core) - self.reserved[core.id]).max(0.0);
            selected.push(core);
            if free >= total && selected.len() >= lanes {
                break;
            }
        }
        self.charge(&selected, total);
        (
            mask(&selected),
            "并发或突发需求较高，扩大可用核心池，避免多个重线程挤在单核",
        )
    }

    /// 串行线程分配一条执行通道；只有完整余量无法满足时，
    /// 才至多增加一个合适的迁移备选核心。
    /// 这些是调度备选，不是同一线程的并行容量。
    /// 更大的实测线程族使用上面的独立汇总规划器。
    fn serial_pool(
        &mut self,
        available: &[&Core],
        demand: &Demand,
        preferred: u64,
    ) -> (u64, &'static str) {
        let effective = |c: &Core| c.capacity as f64 * self.limits[c.id].unwrap();
        let strongest = available.iter().map(|c| effective(c)).fold(0.0, f64::max);
        let strongest_nominal = available.iter().map(|c| c.capacity).max().unwrap_or(0) as f64;
        // 策略上限降低时，不能把所有繁忙线程都挤到唯一的超大核上。
        // 若当前没有核心完全满足需求，仍保留标称能力足够的档位，
        // 按联合预算评估其当前处理能力并排序。
        // 这是受约束的过载选择，不表示容量已经足够。
        let current_can_fit = strongest >= demand.serial;
        let nominal_floor = demand.serial.min(strongest_nominal);
        let total = demand.total.max(demand.serial) * self.demand_margin;
        let score = |c: &Core| {
            let pressure = (self.reserved[c.id] + total) / (effective(c) * self.service_fraction).max(f64::EPSILON);
            // 微小压力变化不应让备选池在每次采样时重新排列。
            // 新成员必须具有明显优势；低于性能下限的核心，
            // 在执行这一偏好比较前就已排除。
            pressure
                * if preferred & (1u64 << c.id) != 0 {
                    1.0
                } else {
                    1.15
                }
        };
        let mut candidates: Vec<_> = available
            .iter()
            .copied()
            .filter(|c| {
                if current_can_fit {
                    effective(c) >= demand.serial
                } else {
                    c.capacity as f64 >= nominal_floor
                }
            })
            .collect();
        candidates.sort_by(|a, b| {
            score(a)
                .total_cmp(&score(b))
                .then_with(|| a.capacity.cmp(&b.capacity))
                .then_with(|| a.id.cmp(&b.id))
        });
        candidates.truncate(2);
        self.charge(&candidates, total);
        (
            mask(&candidates),
            if !current_can_fit {
                "当前频率下余量不足，按实际争用限定强核候选；候选核心不代表算力叠加"
            } else if candidates.len() > 1 {
                "单核余量不足，按核心能力与争用选择受限候选组，保留迁移空间"
            } else {
                "仅一颗核心满足当前需求，保留该核心；不加入能力不足的核心"
            },
        )
    }

    fn charge(&mut self, selected: &[&Core], total: f64) {
        let free: Vec<_> = selected
            .iter()
            .map(|c| {
                (c.capacity as f64 * self.limits[c.id].unwrap_or(1.0) * self.service_fraction - self.reserved[c.id])
                    .max(0.0)
            })
            .collect();
        let sum: f64 = free.iter().sum();
        for (core, free) in selected.iter().zip(free) {
            self.reserved[core.id] += if sum > 0.0 {
                total * free / sum
            } else {
                total / selected.len() as f64
            };
        }
    }
}
fn mask(cores: &[&Core]) -> u64 {
    cores.iter().fold(0, |m, c| m | (1u64 << c.id))
}
