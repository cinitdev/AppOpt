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
