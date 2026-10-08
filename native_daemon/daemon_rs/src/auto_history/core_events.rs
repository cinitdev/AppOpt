//! 独立的有界操作队列。运行时的会话关闭与发布共用一把锁，
//! 因此最终排空不会与已接受的发布发生竞态。
use super::CoreEvent;
use std::{
    collections::{BTreeMap, VecDeque},
    sync::Mutex,
};

const CAPACITY: usize = 2048;
#[derive(Default)]
pub(super) struct Batch {
    pub events: Vec<CoreEvent>,
    pub dropped: u64,
}
struct Stream {
    package: String,
    start_ms: u64,
    dropped: u64,
}
#[derive(Default)]
pub(super) struct Queue {
    next_id: u64,
    active_id: Option<u64>,
    streams: BTreeMap<u64, Stream>,
    events: VecDeque<(u64, CoreEvent)>,
}
impl Queue {
    pub fn open(&mut self, package: &str, start_ms: u64) -> u64 {
        self.next_id = self.next_id.saturating_add(1);
        self.streams.insert(
            self.next_id,
            Stream {
                package: package.into(),
                start_ms,
                dropped: 0,
            },
        );
        self.active_id = Some(self.next_id);
        self.next_id
    }
    pub fn current(&self, package: &str) -> Option<u64> {
        let id = self.active_id?;
        (self.streams.get(&id)?.package == package).then_some(id)
    }
    pub fn deactivate(&mut self) {
        // 正在关闭的流仍接收操作证据，但不再决定
        // 新活跃采集会话的观测基线。
        self.active_id = None;
    }
    pub fn accepts(&self, package: &str) -> bool {
        self.streams.values().any(|s| s.package == package)
    }
    pub fn publish(&mut self, package: &str, events: Vec<CoreEvent>) {
        for event in events {
            let Some(id) = self
                .streams
                .iter()
                .rev()
                .find(|(_, s)| s.package == package && event.timestamp_ms >= s.start_ms)
                .map(|(&id, _)| id)
            else {
                continue;
            };
            if self.events.len() == CAPACITY {
                // 操作记录优先于重复观测。
                // 淘汰计数归属于丢失该记录的会话。
                let observation = if event.kind == "observe" {
                    None
                } else {
                    self.events
                        .iter()
                        .position(|(_, event)| event.kind == "observe")
                };
                if let Some(index) = observation {
                    let (lost, _) = self.events.remove(index).unwrap();
                    self.streams.get_mut(&lost).unwrap().dropped += 1;
                } else {
                    self.streams.get_mut(&id).unwrap().dropped += 1;
                    continue;
                }
            }
            self.events.push_back((id, event));
        }
    }
    pub fn drain(&mut self, id: u64, maximum: usize) -> Batch {
        let dropped = self
            .streams
            .get_mut(&id)
            .map_or(0, |s| std::mem::take(&mut s.dropped));
        let mut events = Vec::new();
        // 持锁时移出数据，避免调度器交接给写入线程时
        // 逐个克隆事件字符串。
        for _ in 0..self.events.len() {
            let (owner, event) = self.events.pop_front().unwrap();
            if owner == id && events.len() < maximum {
                events.push(event);
            } else {
                self.events.push_back((owner, event));
            }
        }
        Batch { events, dropped }
    }
    pub fn close(&mut self, id: u64) -> Batch {
        let result = self.drain(id, CAPACITY);
        self.streams.remove(&id);
        if self.active_id == Some(id) {
            self.active_id = None;
        }
        result
    }
}

/// 存储初始化前先开启事件接收。执行可能较慢的创建回调时不持有队列锁，
/// 因此亲和性发布不依赖文件系统操作。
/// 初始化失败时移除相应的流。
pub(super) fn arm<T, E>(
    queue: &Mutex<Queue>,
    package: &str,
    start_ms: u64,
    create: impl FnOnce() -> Result<T, E>,
) -> Result<(u64, T), E> {
    let id = queue
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .open(package, start_ms);
    match create() {
        Ok(value) => Ok((id, value)),
        Err(error) => {
            queue.lock().unwrap_or_else(|e| e.into_inner()).close(id);
            Err(error)
        }
    }
}

#[cfg(test)]
pub(super) fn event(time: u64, kind: &str) -> CoreEvent {
    CoreEvent {
        timestamp_ms: time,
        pid: 1,
        tid: 2,
        start: 3,
        name: "RenderThread".into(),
        kind: kind.into(),
        source: "qixia".into(),
        before: Some(255),
        after: Some(128),
        running_cpu: Some(7),
        average: Some(12.5),
        reason: "demand".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn current_session_excludes_closing_and_changes_for_same_package_restarts() {
        let mut q = Queue::default();
        assert_eq!(q.current("app"), None);
        let old = q.open("app", 1000);
        assert_eq!(q.current("app"), Some(old));
        assert_eq!(q.current("other"), None);
        q.deactivate();
        assert_eq!(q.current("app"), None);
        assert!(q.accepts("app"));
        q.publish("app", vec![event(1100, "release")]);
        let new = q.open("app", 1200);
        assert_ne!(new, old);
        assert_eq!(q.current("app"), Some(new));
        assert_eq!(q.close(old).events[0].kind, "release");
        assert_eq!(q.current("app"), Some(new));
        q.close(new);
        assert_eq!(q.current("app"), None);
        assert!(!q.accepts("app"));
    }

    #[test]
    fn arming_accepts_operations_without_holding_a_lock_during_storage_creation() {
        let queue = Mutex::new(Queue::default());
        let (id, ()) = arm(&queue, "app", 1000, || -> Result<(), ()> {
            let mut q = queue.try_lock().expect("storage must not block publishers");
            assert!(q.current("app").is_some());
            q.publish("app", vec![event(999, "assign"), event(1000, "assign")]);
            Ok(())
        })
        .unwrap();
        let batch = queue.lock().unwrap().close(id);
        assert_eq!(batch.events.len(), 1);
        assert_eq!(batch.events[0].timestamp_ms, 1000);
    }

    #[test]
    fn failed_creation_discards_armed_events_without_closing_an_older_stream() {
        let queue = Mutex::new(Queue::default());
        let old = queue.lock().unwrap().open("old", 500);
        queue.lock().unwrap().deactivate();
        let failed: Result<(u64, ()), _> = arm(&queue, "app", 1000, || {
            let mut q = queue.try_lock().unwrap();
            q.publish("app", vec![event(1100, "assign")]);
            q.publish("old", vec![event(1100, "release")]);
            Err("storage unavailable")
        });
        assert_eq!(failed, Err("storage unavailable"));
        let mut q = queue.lock().unwrap();
        assert_eq!(q.current("app"), None);
        assert!(!q.accepts("app"));
        assert!(q.accepts("old"));
        assert_eq!(q.events.len(), 1);
        assert_eq!(q.close(old).events[0].kind, "release");
    }

    #[test]
    fn operations_are_not_coalesced_and_old_session_accepts_release_until_final_drain() {
        let mut q = Queue::default();
        let old = q.open("old", 1000);
        q.publish("old", vec![event(1100, "assign"), event(1150, "assign")]);
        let new = q.open("new", 1200);
        assert!(q.accepts("old"));
        q.publish("old", vec![event(1300, "release")]);
        q.publish("new", vec![event(1250, "assign")]);
        let closed = q.close(old);
        assert_eq!(
            closed
                .events
                .iter()
                .map(|e| e.kind.as_str())
                .collect::<Vec<_>>(),
            ["assign", "assign", "release"]
        );
        assert!(!q.accepts("old"));
        assert_eq!(q.close(new).events.len(), 1);
    }
    #[test]
    fn capacity_loss_is_explicit_and_operations_can_displace_observations() {
        let mut q = Queue::default();
        let id = q.open("app", 1);
        q.publish("app", (0..CAPACITY).map(|_| event(10, "observe")).collect());
        q.publish("app", vec![event(20, "release")]);
        let batch = q.close(id);
        assert_eq!(batch.events.len(), CAPACITY);
        assert_eq!(batch.dropped, 1);
        assert_eq!(batch.events.last().unwrap().kind, "release");
        let id = q.open("app", 1);
        q.publish(
            "app",
            (0..CAPACITY + 4).map(|_| event(10, "assign")).collect(),
        );
        assert_eq!(q.close(id).dropped, 4);
    }
    #[test]
    fn inactive_packages_and_old_generations_do_not_pollute_another_session() {
        let mut q = Queue::default();
        let id = q.open("app", 1000);
        q.publish("other", vec![event(1100, "assign")]);
        q.publish("app", vec![event(900, "assign")]);
        assert!(q.drain(id, 10).events.is_empty());
        q.publish("app", vec![event(1100, "assign")]);
        assert_eq!(q.drain(id, 10).events.len(), 1);
        // 换段会取走批次，但有意保持流开启。
        q.publish("app", vec![event(1200, "release")]);
        assert_eq!(q.close(id).events[0].kind, "release");
    }
}
