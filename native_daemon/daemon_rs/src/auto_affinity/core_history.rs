//! 记录亲和性操作与有界观测证据，不使用 sched_switch。
use crate::auto_history::CoreEvent;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub(super) type Identity = (i32, i32, u64);

pub(super) fn epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct ObservedCores {
    pub allowed: Option<u64>,
    pub running_cpu: Option<u32>,
    pub source: &'static str,
}

impl ObservedCores {
    pub(super) fn verified(
        allowed: Option<u64>,
        running_cpu: Option<u32>,
        intent: Option<u64>,
        ownership: Option<bool>,
    ) -> Self {
        let mut observed = Self::new(allowed, running_cpu, ownership.filter(|owned| *owned).and(intent));
        if ownership.is_none() { observed.source = "unknown"; }
        observed
    }

    pub(super) fn new(allowed: Option<u64>, running_cpu: Option<u32>, owned: Option<u64>) -> Self {
        let source = match allowed {
            Some(mask) if owned == Some(mask) => "qixia",
            Some(_) => "system",
            None => "unknown",
        };
        Self {
            allowed,
            running_cpu,
            source,
        }
    }
}

/// 仅用于历史的 IO 周期；分配采样与归属维护有独立时钟，
/// 绝不能等待本探测器。
#[derive(Default)]
pub(super) struct ObservationProbe {
    pub recorded: Option<ObservedCores>,
    probed_at: Option<Instant>,
}

impl ObservationProbe {
    fn due(&mut self, now: Instant, active: bool, managed: bool) -> bool {
        // 从未活跃且未接管的线程，暂时没有历史需要跟踪。
        if !active && !managed && self.recorded.is_none() {
            return false;
        }
        let interval = Duration::from_secs(if active || managed { 2 } else { 5 });
        if self.probed_at.is_some_and(|last| now.saturating_duration_since(last) < interval) {
            return false;
        }
        self.probed_at = Some(now);
        true
    }

    pub(super) fn operation(&mut self, event: &CoreEvent) {
        if !matches!(event.kind.as_str(), "assign" | "release" | "external" | "error") {
            return;
        }
        self.recorded = Some(ObservedCores {
            allowed: event.after,
            // 掩码操作通常不输出执行 CPU 样本，只保留实际写入事件的证据，
            // 不能使用最新 procfs 数值代替，
            // 否则下一次到期观测可能被错误去重。
            running_cpu: event.running_cpu.or_else(|| self.recorded.and_then(|old| old.running_cpu)),
            source: match event.source.as_str() {
                "qixia" => "qixia",
                "system" => "system",
                _ => "unknown",
            },
        });
    }
}

#[derive(Default)]
pub(super) struct EventLog {
    enabled: bool,
    events: Vec<CoreEvent>,
}

impl EventLog {
    /// 操作本身已有独立 IO，其少量证据批次
    /// 不能依赖操作发生前采样到的会话标志。
    pub(super) fn operations() -> Self {
        Self {
            enabled: true,
            events: Vec::new(),
        }
    }
    pub(super) fn enable(&mut self, enabled: bool) {
        self.enabled = enabled;
    }
    pub(super) fn enabled(&self) -> bool {
        self.enabled
    }

    pub(super) fn record(
        &mut self,
        identity: Identity,
        kind: &str,
        source: &str,
        before: Option<u64>,
        after: Option<u64>,
        reason: &str,
    ) {
        if !self.enabled {
            return;
        }
        self.events.push(CoreEvent {
            timestamp_ms: epoch_ms(),
            pid: identity.0,
            tid: identity.1,
            start: identity.2,
            name: String::new(),
            kind: kind.into(),
            source: source.into(),
            before,
            after,
            running_cpu: None,
            average: None,
            reason: reason.into(),
        });
    }

    #[cfg(test)]
    pub(super) fn assignment(
        &mut self,
        identity: Identity,
        before: Option<u64>,
        requested: u64,
        actual: Option<u64>,
        migration: bool,
    ) {
        let Some(actual) = actual else {
            self.record(
                identity,
                "error",
                "unknown",
                before,
                None,
                "readback_failed",
            );
            return;
        };
        if actual != 0 && actual & !requested == 0 {
            self.record(
                identity,
                "assign",
                "qixia",
                before,
                Some(actual),
                if actual != requested {
                    "mask_filtered"
                } else if migration {
                    "affinity_changed"
                } else {
                    "initial"
                },
            );
        }
        if actual != requested {
            self.record(
                identity,
                "error",
                "unknown",
                before,
                Some(actual),
                "mask_filtered",
            );
        }
    }

    pub(super) fn observe(
        &mut self,
        identity: Identity,
        name: &str,
        average: f64,
        previous: &mut Option<ObservedCores>,
        current: ObservedCores,
    ) {
        if !self.enabled || *previous == Some(current) {
            return;
        }
        let before = previous.and_then(|old| old.allowed);
        let reason = match *previous {
            None => "initial",
            Some(old) if old.allowed != current.allowed => "affinity_changed",
            Some(old) if old.running_cpu != current.running_cpu => "cpu_changed",
            Some(_) => "source_changed",
        };
        self.record(
            identity,
            "observe",
            current.source,
            before,
            current.allowed,
            reason,
        );
        let event = self.events.last_mut().unwrap();
        event.name = name.into();
        event.running_cpu = current.running_cpu;
        event.average = Some(average);
        *previous = Some(current);
    }

    /// 闭包包含额外 sched_getaffinity/cpuset IO，只有单调时钟上的
    /// 历史期限到达后才会执行，极轻负载线程也不例外。
    pub(super) fn probe(
        &mut self,
        identity: Identity,
        name: &str,
        average: f64,
        observation: &mut ObservationProbe,
        now: Instant,
        active: bool,
        managed: bool,
        read: impl FnOnce() -> ObservedCores,
    ) {
        if !self.enabled || !observation.due(now, active, managed) {
            return;
        }
        self.observe(identity, name, average, &mut observation.recorded, read());
    }

    pub(super) fn exit(
        &mut self,
        identity: Identity,
        name: &str,
        previous: ObservedCores,
        reason: &str,
    ) {
        if !self.enabled {
            return;
        }
        self.record(identity, "exit", "unknown", previous.allowed, None, reason);
        self.events.last_mut().unwrap().name = name.into();
    }

    pub(super) fn extend(&mut self, events: Vec<CoreEvent>) {
        self.events.extend(events);
    }
    pub(super) fn take(&mut self) -> Vec<CoreEvent> {
        std::mem::take(&mut self.events)
    }
}

/// 第 39 字段是最近观察到的 CPU，不是执行轨迹。comm 字段本身
/// 可能含空格和括号，因此应从最后一个 ')' 之后开始分割。
pub(super) fn running_cpu(stat: &str) -> Option<u32> {
    stat.rsplit_once(')')?
        .1
        .split_ascii_whitespace()
        .nth(36)?
        .parse()
        .ok()
}

pub(super) fn refresh_observation_session<'a>(
    previous: &mut Option<u64>,
    current: Option<u64>,
    observations: impl Iterator<Item = &'a mut ObservationProbe>,
) {
    if *previous == current {
        return;
    }
    *previous = current;
    for observation in observations {
        *observation = ObservationProbe::default();
    }
}

pub(super) fn release_reason(
    stop: Option<&str>,
    average_percent: Option<f64>,
    inactive: bool,
    unrestricted: bool,
) -> &str {
    if let Some(reason) = stop {
        return reason;
    }
    if average_percent.is_none() {
        "sample_unavailable"
    } else if inactive {
        "inactive"
    } else if average_percent.is_some_and(|average| average < 5.0) {
        "low_average"
    } else if unrestricted {
        "unrestricted"
    } else {
        "not_selected"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn equal_masks_need_verified_membership_and_unreadable_membership_stays_unknown() {
        assert_eq!(ObservedCores::verified(Some(3), Some(0), Some(3), Some(true)).source, "qixia");
        assert_eq!(ObservedCores::verified(Some(3), Some(0), Some(3), Some(false)).source, "system");
        assert_eq!(ObservedCores::verified(Some(3), Some(0), Some(3), None).source, "unknown");
        assert_eq!(ObservedCores::verified(None, Some(0), Some(3), Some(true)).source, "unknown");
        assert_eq!(ObservedCores::verified(Some(4), Some(2), Some(3), Some(true)).source, "system");
    }

    #[test]
    fn baseline_and_cpu_or_ownership_changes_are_observations_only() {
        let mut log = EventLog::default();
        log.enable(true);
        let mut previous = None;
        let key = (1, 2, 3);
        let first = ObservedCores::new(Some(3), Some(0), None);
        log.observe(key, "light worker", 1.0, &mut previous, first);
        log.observe(key, "light worker", 2.0, &mut previous, first);
        log.observe(
            key,
            "light worker",
            2.0,
            &mut previous,
            ObservedCores::new(Some(3), Some(1), None),
        );
        log.observe(
            key,
            "light worker",
            2.0,
            &mut previous,
            ObservedCores::new(Some(3), Some(1), Some(3)),
        );
        // 放弃掩码后即由系统管理，即使掩码位和采样 CPU 没有改变；
        // 轻线程不能继续标记为 QixiaThreads 接管。
        log.observe(
            key,
            "light worker",
            1.0,
            &mut previous,
            ObservedCores::new(Some(3), Some(1), None),
        );
        let events = log.take();
        assert_eq!(events.len(), 4);
        assert!(events.iter().all(|event| event.kind == "observe"));
        assert_eq!(events[0].reason, "initial");
        assert_eq!(events[0].source, "system");
        assert_eq!(events[0].average, Some(1.0));
        assert_eq!(events[1].running_cpu, Some(1));
        assert_eq!(events[2].source, "qixia");
        assert_eq!(events[2].before, events[2].after);
        assert_eq!(events[3].source, "system");
        assert_eq!(events[3].reason, "source_changed");
    }

    #[test]
    fn disabling_capture_preserves_already_recorded_release_evidence() {
        let mut log = EventLog::default();
        log.enable(true);
        log.record(
            (1, 2, 3),
            "release",
            "system",
            Some(1),
            Some(3),
            "low_average",
        );
        log.enable(false);
        log.record((1, 2, 3), "assign", "qixia", Some(3), Some(1), "allocated");
        let events = log.take();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].reason, "low_average");
        assert_eq!(events[0].source, "system");
        assert!(log.take().is_empty());
    }

    #[test]
    fn proc_processor_parser_handles_complex_names_and_unknown_values() {
        let tail = (3..=39)
            .map(|field| if field == 39 { "7" } else { "0" })
            .collect::<Vec<_>>()
            .join(" ");
        assert_eq!(running_cpu(&format!("12 (worker ) name) {tail}")), Some(7));
        assert_eq!(running_cpu("12 (worker) 0 1"), None);
        assert_eq!(
            running_cpu(&format!(
                "12 (worker) {} -1",
                (0..36).map(|_| "0").collect::<Vec<_>>().join(" ")
            )),
            None
        );
    }

    #[test]
    fn assignment_events_use_only_verified_actual_masks_including_filtered_writes() {
        let mut log = EventLog::default();
        log.enable(true);
        let key = (1, 2, 3);
        log.assignment(key, Some(7), 3, Some(3), false);
        log.assignment(key, Some(3), 2, Some(2), true);
        log.assignment(key, Some(2), 3, Some(1), true);
        log.assignment(key, Some(1), 2, None, true);
        log.assignment(key, Some(1), 2, Some(4), true);
        let events = log.take();
        let assigned: Vec<_> = events
            .iter()
            .filter(|event| event.kind == "assign")
            .collect();
        assert_eq!(
            assigned.iter().map(|event| event.after).collect::<Vec<_>>(),
            vec![Some(3), Some(2), Some(1)]
        );
        assert_eq!(assigned[0].reason, "initial");
        assert_eq!(assigned[1].reason, "affinity_changed");
        assert_eq!(assigned[2].reason, "mask_filtered");
        assert_eq!(
            events.iter().filter(|event| event.kind == "error").count(),
            3
        );
        assert!(events
            .iter()
            .any(|event| event.reason == "readback_failed" && event.after.is_none()));
    }

    #[test]
    fn release_reasons_preserve_the_exact_five_percent_boundary_and_stop_context() {
        assert_eq!(
            release_reason(None, Some(4.999), false, false),
            "low_average"
        );
        assert_eq!(
            release_reason(None, Some(5.0), false, false),
            "not_selected"
        );
        assert_eq!(release_reason(None, Some(0.5), true, false), "inactive");
        assert_eq!(
            release_reason(None, Some(40.0), false, true),
            "unrestricted"
        );
        assert_eq!(
            release_reason(None, None, false, false),
            "sample_unavailable"
        );
        for reason in ["foreground_left", "mode_disabled", "paused", "shutdown"] {
            assert_eq!(release_reason(Some(reason), Some(1.0), true, false), reason);
        }
    }

    #[test]
    fn partial_batches_keep_completed_operations_before_errors_and_release() {
        let mut batch = EventLog::default();
        batch.enable(true);
        batch.assignment((1, 2, 3), Some(7), 1, Some(1), false);
        batch.record((1, 4, 5), "error", "unknown", Some(7), None, "write_failed");
        let mut controller = EventLog::default();
        controller.extend(batch.take());
        batch.record((1, 2, 3), "release", "system", Some(1), Some(7), "paused");
        controller.extend(batch.take());
        controller.enable(false);
        let events = controller.take();
        assert_eq!(
            events
                .iter()
                .map(|event| event.kind.as_str())
                .collect::<Vec<_>>(),
            vec!["assign", "error", "release"]
        );
        assert_eq!(events[2].after, Some(7));
        assert_eq!(events[2].source, "system");
    }

    #[test]
    fn a_new_session_gets_baselines_even_if_no_off_round_was_observed() {
        let state = ObservedCores::new(Some(3), Some(1), Some(3));
        let mut observations = [
            ObservationProbe { recorded: Some(state), probed_at: Some(Instant::now()) },
            ObservationProbe { recorded: Some(ObservedCores::new(Some(7), Some(2), None)),
                probed_at: Some(Instant::now()) },
        ];
        let mut session = Some(10);
        refresh_observation_session(&mut session, Some(10), observations.iter_mut());
        assert!(observations.iter().all(|probe| probe.recorded.is_some() && probe.probed_at.is_some()));
        // 两次分配之间可能完整发生一次快速关闭与开启。
        refresh_observation_session(&mut session, Some(11), observations.iter_mut());
        assert!(observations.iter().all(|probe| probe.recorded.is_none() && probe.probed_at.is_none()));
        let mut log = EventLog::default();
        log.enable(true);
        log.observe((1, 2, 3), "owned", 8.0, &mut observations[0].recorded, state);
        log.observe(
            (1, 4, 5),
            "light",
            1.0,
            &mut observations[1].recorded,
            ObservedCores::new(Some(7), Some(2), None),
        );
        let events = log.take();
        assert_eq!(events.len(), 2);
        assert!(events.iter().all(|event| event.reason == "initial"));
        assert_eq!(events[0].source, "qixia");
        assert_eq!(events[1].source, "system");
        refresh_observation_session(&mut session, None, observations.iter_mut());
        assert!(observations.iter().all(|probe| probe.recorded.is_none() && probe.probed_at.is_none()));
        refresh_observation_session(&mut session, Some(12), observations.iter_mut());
        assert_eq!(session, Some(12));
    }

    #[test]
    fn operation_evidence_survives_a_session_opening_after_the_round_started() {
        let mut operations = EventLog::operations();
        // 本轮开始时没有活跃的观测会话。
        let mut observations = EventLog::default();
        let mut previous = None;
        observations.observe(
            (1, 2, 3),
            "worker",
            20.0,
            &mut previous,
            ObservedCores::new(Some(7), Some(0), None),
        );
        operations.assignment((1, 2, 3), Some(7), 1, Some(1), false);
        operations.record(
            (1, 2, 3),
            "release",
            "system",
            Some(1),
            Some(7),
            "mode_disabled",
        );
        assert!(observations.take().is_empty());
        // 发布按操作真实发生时间，将时间戳路由到当时开启的流；
        // 不能因缓存的观测标志而丢弃操作。
        let events = operations.take();
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].kind, "assign");
        assert_eq!(events[1].kind, "release");
        assert!(operations.take().is_empty());
    }
}

#[cfg(test)]
#[path = "core_history_probe_tests.rs"]
mod probe_tests;
