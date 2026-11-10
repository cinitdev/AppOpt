use super::super::demand::{self, Demand, Policy, Residency};
use super::ThreadStat;
use std::collections::{BTreeSet, VecDeque};
use std::time::{Duration, Instant};

const MAX_WINDOW: Duration = Duration::from_secs(12);

/// 准确跳过本次已访问的条目，包括已经选中的条目。
/// 固定步长会在优先线程占满额度时不断跳过列表尾部。
pub(super) fn rotate_scan(
    threads: &[(i32, i32)],
    selected: &mut BTreeSet<(i32, i32)>,
    cursor: &mut usize,
    limit: usize,
) {
    if threads.is_empty() {
        *cursor = 0;
        return;
    }
    *cursor %= threads.len();
    for _ in 0..threads.len() {
        if selected.len() >= limit {
            break;
        }
        selected.insert(threads[*cursor]);
        *cursor = (*cursor + 1) % threads.len();
    }
}

/// 最久未尝试的条目优先，不受负载排名变化影响。
/// 读取失败也要记录尝试时间，防止持续占用 IO 额度。
pub(super) fn oldest_details(
    candidates: impl Iterator<Item = ((i32, i32, u64), Option<Instant>)>,
    limit: usize,
) -> Vec<(i32, i32, u64)> {
    let mut candidates: Vec<_> = candidates.collect();
    candidates.sort_unstable_by_key(|(key, last)| (*last, *key));
    candidates
        .into_iter()
        .take(limit)
        .map(|(key, _)| key)
        .collect()
}

#[derive(Default)]
pub(super) struct DemandWindow {
    points: VecDeque<(Instant, ThreadStat, Residency)>,
}

impl DemandWindow {
    pub(super) fn push(
        &mut self,
        now: Instant,
        stat: ThreadStat,
        residency: Residency,
        policies: &[Policy],
        hz: f64,
    ) -> Option<Demand> {
        if self.points.back().is_some_and(|(time, old, _)| {
            stat.start != old.start
                || stat.ticks < old.ticks
                || now <= *time
                || now.saturating_duration_since(*time) > MAX_WINDOW
        }) {
            self.points.clear();
        }
        self.points.push_back((now, stat, residency));
        while self.points.len() > 7
            || self
                .points
                .front()
                .is_some_and(|(time, _, _)| now.saturating_duration_since(*time) > MAX_WINDOW)
        {
            self.points.pop_front();
        }
        // 估计器验证经过时间和计数量化误差。稀疏详情读取只需两个端点，
        // 不要求可能永远凑不齐的四个点。
        if self.points.len() < 2 {
            return None;
        }
        let (time, end, counters) = self.points.back()?;
        let evaluate = |point: &(Instant, ThreadStat, Residency)| {
            demand::estimate(
                &point.2,
                counters,
                policies,
                end.ticks.checked_sub(point.1.ticks)?,
                time.saturating_duration_since(point.0).as_secs_f64(),
                hz,
            )
        };
        let long = evaluate(self.points.front()?);
        let recent = evaluate(&self.points[self.points.len() - 2]);
        // 工作量与忙碌时间必须来自同一区间。把近期突发的工作量
        // 与长窗口中较低的忙碌时间组合，会虚构更高执行速度，
        // 导致后续轮次不必要地提高线程性能档位。
        match (long, recent) {
            (Some(long), Some(recent)) => Some(if recent.units > long.units {
                recent
            } else {
                long
            }),
            (long, recent) => long.or(recent),
        }
    }
}
