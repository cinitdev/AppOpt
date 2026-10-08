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

#[cfg(test)]
mod tests {
    use super::*;

    fn cores(capacities: &[u64]) -> Vec<Core> {
        capacities
            .iter()
            .enumerate()
            .map(|(id, &capacity)| Core { id, capacity })
            .collect()
    }
    fn demand(units: f64) -> Demand {
        Demand {
            serial: units,
            total: units,
            parallel: 1,
            allowed: 255,
        }
    }
    #[test]
    fn two_heavy_threads_use_separate_870_cores() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        assert_eq!(b.assign(&demand(550.0)).0, 1 << 7);
        assert_eq!(b.assign(&demand(480.0)).0, 1 << 4);
        let next = b.assign(&demand(450.0)).0;
        assert_eq!(next, 1 << 5);
    }
    #[test]
    fn dual_prime_noncontiguous_and_all_big_cores_need_no_soc_table() {
        let mut c = cores(&[800, 800, 800, 800, 800, 800, 1024, 1024]);
        c[0].id = 7;
        c[7].id = 0;
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        assert_eq!(b.assign(&demand(600.0)).0, 1);
        assert_eq!(b.assign(&demand(600.0)).0, 1 << 6);
        assert_eq!(b.assign(&demand(400.0)).0, 1 << 1);
    }
    #[test]
    fn wildcard_group_and_oversubscribed_bursts_never_collapse_to_one_prime() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        let grouped = Demand {
            serial: 300.0,
            total: 900.0,
            parallel: 3,
            allowed: 255,
        };
        assert_eq!(b.assign(&grouped).0, 112);
        let heavy = b.assign(&demand(650.0)).0;
        let heavy2 = b.assign(&demand(600.0)).0;
        assert_eq!(heavy, 128);
        assert!(heavy2.count_ones() > 1);
        assert_ne!(heavy2, heavy);
    }
    #[test]
    fn missing_capacity_is_safe_and_reduced_limits_keep_a_restricted_strong_pool() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let limits = [Some(0.4); 64];
        let mut b = Budget::new(&c, &limits);
        assert_eq!(b.assign(&demand(600.0)).0, 128 | 16);
        let unknown = [None; 64];
        assert_eq!(Budget::new(&c, &unknown).assign(&demand(600.0)).0, 255);
    }
    #[test]
    fn reduced_policy_limits_still_allocate_and_respect_external_pressure() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let mut limits = [Some(0.489); 64];
        for id in 4..7 {
            limits[id] = Some(0.294);
        }
        limits[7] = Some(0.265);
        let mut b = Budget::new(&c, &limits);
        let mut loads = [Some(0.0); 64];
        loads[4] = Some(0.9);
        b.reserve_external(&loads);
        assert_eq!(b.assign(&demand(170.0)).0, 1 << 7);
        assert_eq!(b.assign(&demand(145.0)).0, 1 << 5);
        assert_eq!(b.assign(&demand(140.0)).0, 1 << 6);
    }
    #[test]
    fn retained_placement_is_charged_before_allocating_the_next_thread() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        b.reserve_placement(&demand(450.0), 1 << 5);
        assert_eq!(b.assign(&demand(450.0)).0, 1 << 4);
        assert_eq!(b.assign(&demand(450.0)).0, 1 << 6);
    }
    #[test]
    fn all_core_layouts_can_be_used_without_a_processor_name_or_fixed_core_ids() {
        let layouts: &[(&[u64], &[f64])] = &[
            (
                &[313, 313, 313, 313, 777, 777, 777, 1024],
                &[600., 450., 450., 450., 150., 150., 150., 150.],
            ),
            (
                &[800, 800, 800, 800, 800, 800, 1024, 1024],
                &[650., 650., 400., 400., 400., 400., 400., 400.],
            ),
            (&[1024; 8], &[600.; 8]),
            (
                &[400, 400, 400, 400, 700, 700, 950, 1024],
                &[660., 600., 400., 400., 200., 200., 200., 200.],
            ),
        ];
        let limits = [Some(1.0); 64];
        for &(capacities, loads) in layouts {
            let c = cores(capacities);
            let mut b = Budget::new(&c, &limits);
            let mut used = 0;
            for &load in loads {
                let selected = b.assign(&demand(load)).0;
                assert_eq!(selected.count_ones(), 1, "{capacities:?}, {load}");
                assert_eq!(used & selected, 0, "heavy workers must not crowd one lane");
                used |= selected;
            }
            assert_eq!(used, 255);
        }
    }
    #[test]
    fn a_single_heavy_worker_does_not_add_prime_capacities_together() {
        let c = cores(&[800, 800, 800, 800, 800, 800, 1024, 1024]);
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        // 600 单位加上余量可由一颗超大核承载，另一颗保持可用。
        assert_eq!(b.assign(&demand(600.0)).0, 64);
        assert_eq!(b.assign(&demand(600.0)).0, 128);
        // 900 单位的突发缺少单通道余量，不能声称把两颗超大核容量相加
        // 就能让此线程并行执行。
        assert_eq!(b.assign(&demand(900.0)).0, 192);
    }
    #[test]
    fn steady_placements_keep_their_lane_and_real_pressure_can_move_them() {
        let c = cores(&[800, 800, 800, 800, 800, 800, 1024, 1024]);
        let limits = [Some(1.0); 64];
        let mut loads = [Some(0.0); 64];
        loads[7] = Some(0.03);
        for _ in 0..500 {
            let mut b = Budget::new(&c, &limits);
            b.reserve_external(&loads);
            assert!(b.fits(&demand(600.), 128));
            assert_eq!(b.assign_preferred(&demand(600.), 128).0, 128);
        }
        loads[7] = Some(0.6);
        let mut b = Budget::new(&c, &limits);
        b.reserve_external(&loads);
        assert!(!b.fits(&demand(600.), 128));
        assert_eq!(b.assign_preferred(&demand(600.), 128).0, 64);
    }
    #[test]
    fn measured_light_work_does_not_inherit_the_fastest_cores_full_rate() {
        let light = workload(0.2, 0.3, 1024., 250., 55.);
        assert_eq!(light, 75.);
        let heavy = workload(0.8, 0.95, 1024., 120., 120.);
        assert!((heavy - 655.36).abs() < 0.01);
        assert_eq!(workload(0.2, 0.3, 1024., 1024., 0.), 307.2);
        // 活跃度连续增长，不使用按 SoC 名称或负载档位划分的边界。
        for n in 1..=100 {
            let low = (n - 1) as f64 / 100.;
            let high = n as f64 / 100.;
            assert!(workload(high, high, 1024., 250., 0.) >= workload(low, low, 1024., 250., 0.));
        }
    }
    #[test]
    fn performance_margin_promotes_borderline_work_without_spending_two_primes() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        // 按旧余量计算，210 单位几乎占满小核。应在大核保留延迟余量；
        // 真正轻量的 100 单位任务仍能由小核承载。
        assert_eq!(b.assign(&demand(210.)).0, 1 << 4);
        assert_eq!(b.assign(&demand(100.)).0, 1);
        let dual = cores(&[800, 800, 800, 800, 800, 800, 1024, 1024]);
        let mut b = Budget::new(&dual, &limits);
        assert_eq!(b.assign(&demand(650.)).0, 64);
        assert_eq!(b.assign(&demand(650.)).0, 128);
    }
    #[test]
    fn migration_gain_uses_serial_service_and_keeps_real_escape_routes() {
        let c = cores(&[313, 777, 777, 1024]);
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        let mut loads = [Some(0.0); 64];
        loads[1] = Some(0.50);
        loads[2] = Some(0.48);
        b.reserve_external(&loads);
        let job = Demand { allowed: 15, ..demand(600.) };
        assert!(!b.materially_better(&job, 2, 4));
        assert!(!b.materially_better(&job, 2, 6), "another busy lane is not extra serial capacity");
        assert!(b.materially_better(&job, 1, 8));
        assert!(b.materially_better(&job, 2, 8));
        assert!(b.materially_better(&Demand { allowed: 8, ..job }, 2, 8));
    }
    #[test]
    fn reaching_the_full_budget_bypasses_the_migration_noise_gate() {
        let c = cores(&[777, 1024]);
        let limits = [Some(1.0); 64];
        let mut b = Budget::new(&c, &limits);
        b.reserved[0] = 50.0;
        b.reserved[1] = 180.0;
        let job = Demand { allowed: 3, ..demand(500.) };
        assert!(!b.fits(&job, 1));
        assert!(b.fits(&job, 2));
        assert!(b.materially_better(&job, 1, 2));
        let mut placement = crate::auto_affinity::live_policy::Placement::default();
        assert_eq!(placement.choose(std::time::Instant::now(), 1, 2, false,
            b.materially_better(&job, 1, 2)), Some(2));
    }
    #[test]
    fn serial_choices_never_invent_an_extra_core_or_escape_the_allowed_set() {
        let c = cores(&[1024, 800, 313, 800, 1024, 313]);
        for allowed in 1..64 {
            for limit in [0.25, 0.6, 1.0] {
                let limits = [Some(limit); 64];
                let mut b = Budget::new(&c, &limits);
                for units in [20., 80., 150., 300., 550., 800., 1024.] {
                    let selected = b
                        .assign(&Demand {
                            allowed,
                            ..demand(units)
                        })
                        .0;
                    assert_eq!(selected & !allowed, 0);
                    assert!((1..=2).contains(&selected.count_ones()));
                    let fastest = c
                        .iter()
                        .filter(|c| allowed & (1 << c.id) != 0)
                        .map(|c| c.capacity as f64 * limit)
                        .fold(0.0, f64::max);
                    let nominal = c
                        .iter()
                        .filter(|c| allowed & (1 << c.id) != 0)
                        .map(|c| c.capacity as f64)
                        .fold(0.0, f64::max);
                    for core in c.iter().filter(|c| selected & (1 << c.id) != 0) {
                        if fastest >= units {
                            assert!(core.capacity as f64 * limit >= units);
                        } else {
                            assert!(core.capacity as f64 >= units.min(nominal));
                        }
                    }
                }
            }
        }
    }
    #[test]
    fn contention_pools_exclude_weak_cores_and_budget_each_worker_once() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let limits = [Some(1.0); 64];
        let mut loads = [Some(0.95); 64];
        loads[4..7].fill(Some(0.5));
        loads[7] = Some(0.8);
        let mut b = Budget::new(&c, &limits);
        b.reserve_external(&loads);
        let before: f64 = b.reserved.iter().sum();
        assert_eq!(b.assign(&demand(450.)).0, (1 << 4) | (1 << 5));
        let after: f64 = b.reserved.iter().sum();
        assert!((after - before - 450. * 1.15).abs() < 1e-6);
        assert_eq!(b.assign(&demand(450.)).0, (1 << 6) | (1 << 7));
    }
    #[test]
    fn low_frequency_ceilings_do_not_funnel_every_heavy_thread_into_the_only_prime() {
        let c = cores(&[313, 313, 313, 313, 777, 777, 777, 1024]);
        let limits = [Some(0.4); 64];
        let mut b = Budget::new(&c, &limits);
        let first = b.assign(&demand(600.)).0;
        let second = b.assign(&demand(600.)).0;
        assert_eq!(first, (1 << 7) | (1 << 4));
        assert_eq!(second, (1 << 5) | (1 << 6));
        assert_eq!(first & second, 0);
        assert_eq!((first | second) & 15, 0);
    }
    #[test]
    fn fallback_pool_resists_small_pressure_noise_then_moves_for_real_contention() {
        let c = cores(&[1024; 8]);
        let limits = [Some(1.0); 64];
        let mut loads = [Some(0.5); 64];
        loads[0] = Some(0.55);
        loads[1] = Some(0.55);
        for _ in 0..500 {
            let mut b = Budget::new(&c, &limits);
            b.reserve_external(&loads);
            assert_eq!(b.assign_preferred(&demand(600.), 3).0, 3);
        }
        loads[0] = Some(0.99);
        loads[1] = Some(0.99);
        let mut b = Budget::new(&c, &limits);
        b.reserve_external(&loads);
        assert_eq!(b.assign_preferred(&demand(600.), 3).0, 12);
        // 余量恢复后，同一线程再次只需一条执行通道。
        let mut b = Budget::new(&c, &limits);
        assert_eq!(b.assign_preferred(&demand(600.), 12).0, 4);
    }
    #[test]
    fn no_contention_or_uniform_layout_can_make_serial_fallback_expand_to_all_eight() {
        for capacities in [
            [313, 313, 313, 313, 777, 777, 777, 1024],
            [800, 800, 800, 800, 800, 800, 1024, 1024],
            [1024; 8],
        ] {
            let c = cores(&capacities);
            let limits = [Some(1.0); 64];
            for load in [0., 0.5, 0.95, 1.0] {
                let mut b = Budget::new(&c, &limits);
                b.reserve_external(&[Some(load); 64]);
                for units in [80., 300., 600., 900., 1400.] {
                    let chosen = b.assign(&demand(units)).0;
                    assert!((1..=2).contains(&chosen.count_ones()));
                    assert_ne!(chosen, 255);
                }
            }
        }
    }
}
