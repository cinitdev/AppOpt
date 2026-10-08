//! 针对生产分配器的有界设备检查，仅修改测试线程。
use super::*;

struct RestoreOwnThread {
    tid: i32,
    original: u64,
}
impl Drop for RestoreOwnThread {
    fn drop(&mut self) {
        let _ = write_affinity(self.tid, self.original);
    }
}

#[test]
#[ignore = "Requires Android capacities and per-thread frequency accounting; uses only this test thread"]
fn measured_demand_uses_production_budget_and_restores_test_thread() {
    let pid = std::process::id() as i32;
    let tid = unsafe { libc::syscall(libc::SYS_gettid) as i32 };
    let original = affinity(tid).unwrap();
    let _restore = RestoreOwnThread { tid, original };
    let cores = topology();
    let policies = sampling::policies(&cores).expect("reliable CPU policies");
    let online = online_mask().unwrap();
    let available = original & online;
    let source = cores
        .iter()
        .filter(|c| available & (1 << c.id) != 0)
        .min_by_key(|c| c.capacity)
        .expect("an available test core");
    let start = thread_stat(pid, tid).unwrap().start;
    write_affinity(tid, 1 << source.id).unwrap();
    assert_eq!(affinity(tid).unwrap(), 1 << source.id);
    let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) } as f64;
    let mut window = sampling::DemandWindow::default();
    let mut before = cpu_times();
    let mut last = None;
    for round in 0..7 {
        if round > 0 {
            let work = Instant::now();
            while work.elapsed() < Duration::from_millis(1100) {
                std::hint::black_box(12345u64.wrapping_mul(6789));
            }
        }
        let stat = thread_stat(pid, tid).unwrap();
        assert_eq!(stat.start, start);
        let residency = sampling::residency(pid, tid).unwrap();
        let after = cpu_times();
        last = window
            .push(Instant::now(), stat, residency, &policies, hz)
            .map(|demand| (demand, cpu_loads(&before, &after)));
        before = after;
    }
    let (demand, loads) = last.expect("real frequency-normalized demand");
    assert!(demand.busy >= 0.85);
    write_affinity(tid, original).unwrap();
    let effective = affinity(tid).unwrap();
    let mask = candidate_pool(
        &cores,
        effective,
        online_mask().unwrap(),
        &loads,
        &sampling::limits(&policies),
        demand,
    )
    .expect("available capacity for test demand");
    assert_ne!(mask, 0);
    assert_eq!(mask & !effective, 0);
    let record = Record {
        boot: boot_id().unwrap(),
        token: "1-2".into(),
        pid,
        tid,
        start,
        original: effective,
        written: mask,
    };
    assert!(same_identity(&record).unwrap());
    write_affinity(tid, mask).unwrap();
    assert_eq!(affinity(tid).unwrap(), mask);
    restore_record(&record).unwrap();
    let restored = affinity(tid).unwrap();
    assert_ne!(restored, 0);
    assert_eq!(restored & !effective, 0);
    assert_ne!(restored, mask);
    println!(
        "TEST ONLY: work={:.2} busy={:.3} source={} assigned={mask:x} restored={restored:x}",
        demand.units, demand.busy, source.id
    );
}
