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
    threshold_percent: f64,
) -> &str {
    if let Some(reason) = stop {
        return reason;
    }
    if average_percent.is_none() {
        "sample_unavailable"
    } else if inactive {
        "inactive"
    } else if average_percent.is_some_and(|average| average < threshold_percent) {
        "low_average"
    } else if unrestricted {
        "unrestricted"
    } else {
        "not_selected"
    }
}
