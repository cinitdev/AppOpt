use super::*;

fn state(cpu: u32) -> ObservedCores {
    ObservedCores::new(Some(255), Some(cpu), None)
}

#[test]
fn active_migrations_probe_io_four_times_less_often_without_the_five_percent_gate() {
    let now = Instant::now();
    let mut log = EventLog::default();
    log.enable(true);
    let mut probe = ObservationProbe::default();
    let mut io = 0;
    // 保持 500 ms 分配周期，模拟 200 秒。即使仅 0.2% 的轻线程
    // 也能被观察，但不再每轮增加 IO。
    for round in 0..400 {
        log.probe(
            (1, 2, 3),
            "light worker",
            0.2,
            &mut probe,
            now + Duration::from_millis(round * 500),
            true,
            false,
            || {
                io += 1;
                state((round / 4 % 8) as u32)
            },
        );
    }
    let events = log.take();
    assert_eq!(io, 100);
    assert_eq!(events.len(), 100);
    assert!(events
        .iter()
        .all(|event| event.kind == "observe" && event.average == Some(0.2)));
    assert_eq!(events[0].reason, "initial");
    assert!(events[1..]
        .iter()
        .all(|event| event.reason == "cpu_changed"));
}

#[test]
fn stable_observations_do_not_become_periodic_heartbeat_records() {
    let now = Instant::now();
    let mut log = EventLog::default();
    log.enable(true);
    let mut probe = ObservationProbe::default();
    let mut io = 0;
    for round in 0..40 {
        log.probe(
            (1, 2, 3),
            "stable",
            20.0,
            &mut probe,
            now + Duration::from_millis(round * 500),
            true,
            true,
            || {
                io += 1;
                state(3)
            },
        );
    }
    assert_eq!(io, 10);
    assert_eq!(log.take().len(), 1);
}

#[test]
fn sleeping_unowned_threads_probe_every_five_seconds_but_owned_threads_use_two() {
    let now = Instant::now();
    let mut log = EventLog::default();
    log.enable(true);
    let mut idle = ObservationProbe::default();
    let mut owned = ObservationProbe::default();
    let mut never_active = ObservationProbe::default();
    let mut idle_io = 0;
    let mut owned_io = 0;
    for round in 0..20 {
        let stamp = now + Duration::from_millis(round * 500);
        log.probe(
            (1, 2, 3),
            "sleeping",
            0.0,
            &mut idle,
            stamp,
            round == 0,
            false,
            || {
                idle_io += 1;
                state(2)
            },
        );
        log.probe(
            (1, 4, 5),
            "owned",
            0.0,
            &mut owned,
            stamp,
            false,
            true,
            || {
                owned_io += 1;
                state(4)
            },
        );
        log.probe(
            (1, 6, 7),
            "never active",
            0.0,
            &mut never_active,
            stamp,
            false,
            false,
            || panic!("Never-active workers must not add any history probe IO"),
        );
    }
    assert_eq!(idle_io, 2);
    assert_eq!(owned_io, 5);
    assert!(never_active.probed_at.is_none());
    // 唤醒后使用从上次实际探测起算的两秒周期，
    // 不必等待之前安排的休眠探测期限。
    log.probe(
        (1, 2, 3),
        "awake",
        0.1,
        &mut idle,
        now + Duration::from_secs(9),
        true,
        false,
        || {
            idle_io += 1;
            state(3)
        },
    );
    assert_eq!(idle_io, 3);
}

#[test]
fn probe_deadline_uses_monotonic_time_not_wall_timestamps_or_a_backwards_sample() {
    let now = Instant::now();
    let mut probe = ObservationProbe::default();
    assert!(probe.due(now, true, false));
    assert!(!probe.due(now - Duration::from_secs(1), true, false));
    assert!(!probe.due(now + Duration::from_millis(1999), true, false));
    let mut log = EventLog::operations();
    log.record((1, 2, 3), "assign", "qixia", Some(255), Some(8), "initial");
    let mut event = log.take().pop().unwrap();
    for wall_time in [1, u64::MAX] {
        event.timestamp_ms = wall_time;
        probe.operation(&event);
        assert!(!probe.due(now + Duration::from_millis(1999), true, true));
    }
    assert!(probe.due(now + Duration::from_secs(2), true, true));
}

#[test]
fn disable_and_new_session_reset_cadence_and_new_identity_has_its_own_baseline() {
    let now = Instant::now();
    let mut log = EventLog::default();
    let mut session = None;
    let mut probes = [ObservationProbe::default(), ObservationProbe::default()];
    // 关闭采集时既不执行闭包，也不启动计时器。
    log.probe(
        (1, 2, 3),
        "off",
        20.0,
        &mut probes[0],
        now,
        true,
        true,
        || panic!("disabled IO"),
    );
    assert!(probes[0].probed_at.is_none());
    refresh_observation_session(&mut session, Some(10), probes.iter_mut());
    log.enable(true);
    for (index, probe) in probes.iter_mut().enumerate() {
        log.probe(
            (1, 2, 3 + index as u64),
            "new identity",
            1.0,
            probe,
            now,
            true,
            false,
            || state(2),
        );
    }
    assert_eq!(log.take().len(), 2);
    // 两次分配之间快速关闭再开启会改变会话 ID，
    // 不能继承上一会话的两秒期限或去重状态。
    refresh_observation_session(&mut session, Some(11), probes.iter_mut());
    log.probe(
        (1, 2, 3),
        "new capture",
        1.0,
        &mut probes[0],
        now + Duration::from_millis(100),
        true,
        false,
        || state(2),
    );
    let events = log.take();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].reason, "initial");
    refresh_observation_session(&mut session, None, probes.iter_mut());
    log.enable(false);
    log.probe(
        (1, 2, 3),
        "off",
        20.0,
        &mut probes[0],
        now + Duration::from_secs(30),
        true,
        true,
        || panic!("disabled IO"),
    );
    assert!(probes
        .iter()
        .all(|probe| probe.recorded.is_none() && probe.probed_at.is_none()));
}

#[test]
fn equal_mask_operations_and_exit_are_never_throttled_with_observations() {
    let now = Instant::now();
    let key = (1, 2, 3);
    let mut log = EventLog::default();
    log.enable(true);
    let mut probe = ObservationProbe::default();
    log.probe(key, "worker", 20.0, &mut probe, now, true, false, || {
        state(0)
    });
    for _ in 0..8 {
        for kind in ["assign", "release", "external", "error"] {
            log.record(key, kind, "unknown", Some(255), Some(255), "same_mask");
        }
        log.exit(key, "worker", state(0), "thread_exit");
        log.probe(
            key,
            "worker",
            20.0,
            &mut probe,
            now + Duration::from_millis(250),
            true,
            false,
            || panic!("not due IO"),
        );
    }
    let events = log.take();
    assert_eq!(events.len(), 1 + 8 * 5);
    for kind in ["assign", "release", "external", "error", "exit"] {
        assert_eq!(events.iter().filter(|event| event.kind == kind).count(), 8);
    }
}

#[test]
fn mask_operation_does_not_swallow_an_execution_cpu_that_has_not_been_emitted() {
    let now = Instant::now();
    let key = (1, 2, 3);
    let mut log = EventLog::default();
    log.enable(true);
    let mut probe = ObservationProbe::default();
    log.probe(key, "worker", 20.0, &mut probe, now, true, false, || {
        state(0)
    });
    log.take();
    let mut operation = EventLog::operations();
    operation.record(key, "assign", "qixia", Some(255), Some(8), "new_mask");
    let event = operation.take().pop().unwrap();
    assert!(event.running_cpu.is_none());
    probe.operation(&event);
    assert_eq!(probe.recorded.unwrap().running_cpu, Some(0));
    // 控制器最新 stat 可能已显示 CPU 3，但操作行未输出该采样值；
    // 下一次到期探测仍须输出这一执行位置样本。
    log.probe(
        key,
        "worker",
        20.0,
        &mut probe,
        now + Duration::from_secs(2),
        true,
        true,
        || ObservedCores::new(Some(8), Some(3), Some(8)),
    );
    let events = log.take();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].running_cpu, Some(3));
    assert_eq!(events[0].reason, "cpu_changed");
    // 首次观测之前的操作同样不能伪造首个执行样本，
    // 也不能提前启动历史探测期限。
    let mut fresh = ObservationProbe::default();
    fresh.operation(&event);
    log.probe(key, "worker", 20.0, &mut fresh, now, true, true, || {
        ObservedCores::new(Some(8), Some(3), Some(8))
    });
    assert_eq!(log.take().len(), 1);
}
