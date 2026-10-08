#[cfg(test)]
pub(super) use super::super::accounting::{limits, policies, residency};
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

#[cfg(test)]
mod tests {
    use super::*;


    fn test_policies() -> [Policy; 1] {
        [Policy {
            cpu: 0,
            members: 1,
            max_khz: 1000,
            capacity: 800.0,
        }]
    }

    fn point(
        window: &mut DemandWindow,
        now: Instant,
        ticks: u64,
        residency_ticks: u64,
    ) -> Option<Demand> {
        window.push(
            now,
            ThreadStat { start: 1, ticks },
            Residency::parse(&format!("cpu0\n1000 {residency_ticks}")).unwrap(),
            &test_policies(),
            100.0,
        )
    }

    #[test]
    fn priority_threads_do_not_hide_the_tail_of_a_large_scan() {
        let threads: Vec<_> = (0..1024).map(|tid| (1, tid)).collect();
        let priority: BTreeSet<_> = threads[..128].iter().copied().collect();
        let mut cursor = 0;
        let mut visited = BTreeSet::new();
        for _ in 0..3 {
            let mut selected = priority.clone();
            rotate_scan(&threads, &mut selected, &mut cursor, 512);
            assert_eq!(selected.len(), 512);
            assert!(priority.is_subset(&selected));
            visited.extend(selected);
        }
        assert_eq!(visited.len(), threads.len());
        assert!(visited.contains(&(1, 1023)));
    }

    #[test]
    fn scan_cursor_survives_a_smaller_or_empty_thread_list() {
        let mut selected = BTreeSet::new();
        let mut cursor = 1000;
        rotate_scan(&[(1, 1), (1, 2)], &mut selected, &mut cursor, 512);
        assert_eq!(selected, BTreeSet::from([(1, 1), (1, 2)]));
        rotate_scan(&[], &mut selected, &mut cursor, 512);
        assert_eq!(cursor, 0);
    }

    #[test]
    fn sparse_detail_windows_keep_updating_under_the_bounded_io_budget() {
        let start = Instant::now();
        for count in [24, 48, 72, 128] {
            let mut windows: Vec<_> = (0..count).map(|_| DemandWindow::default()).collect();
            let mut attempted = vec![None; count];
            let mut measurements = vec![None; count];
            let mut updates = vec![0; count];
            for round in 0..120u64 {
                let now = start + Duration::from_millis(round * 1100);
                // 反转或轮换输入，模拟负载顺序变化。
                let details = oldest_details(
                    (0..count).rev().map(|index| {
                        let id = (index + round as usize) % count;
                        ((1, id as i32, 1), attempted[id])
                    }),
                    24,
                );
                assert_eq!(details.len(), 24);
                for (_, id, _) in details {
                    let id = id as usize;
                    attempted[id] = Some(now);
                    let measured = point(&mut windows[id], now, round * 55, round * 55);
                    if updates[id] > 0 {
                        assert!(measured.is_some(), "stalled: count={count}, tid={id}");
                    }
                    if let Some(measured) = measured {
                        assert!((measured.busy - 0.5).abs() < 0.001);
                        measurements[id] = Some(now);
                        updates[id] += 1;
                    }
                    assert!(windows[id].points.len() <= 7);
                    assert!(windows[id].points.iter().all(|(time, _, _)| {
                        now.saturating_duration_since(*time) <= MAX_WINDOW
                    }));
                }
                if round >= 12 {
                    assert!(
                        measurements.iter().all(|time| {
                            time.is_some_and(|time| {
                                now.duration_since(time) < Duration::from_secs(8)
                            })
                        }),
                        "stale measurement: count={count}, round={round}"
                    );
                }
            }
            assert!(
                updates.iter().all(|updates| *updates >= 18),
                "count={count}"
            );
        }
    }

    #[test]
    fn failed_detail_reads_do_not_starve_other_threads() {
        let start = Instant::now();
        let mut attempted = [None; 128];
        for round in 0..6u64 {
            let now = start + Duration::from_millis(round * 1100);
            let details = oldest_details(
                attempted
                    .iter()
                    .enumerate()
                    .map(|(id, last)| ((1, id as i32, 1), *last)),
                24,
            );
            for (_, id, _) in details {
                // 即使频率驻留时间读取失败，也要推进此时间戳。
                attempted[id as usize] = Some(now);
            }
        }
        assert!(attempted.iter().all(Option::is_some));
    }

    #[test]
    fn an_invalid_long_window_does_not_discard_valid_recent_work() {
        let start = Instant::now();
        let mut window = DemandWindow::default();
        let mut result = None;
        for (second, (ticks, residency)) in [(0, 0), (10, 100), (20, 110), (30, 120)]
            .into_iter()
            .enumerate()
        {
            result = point(
                &mut window,
                start + Duration::from_secs(second as u64),
                ticks,
                residency,
            );
        }
        let measured = result.expect("the valid recent interval must survive");
        assert!((measured.busy - 0.1).abs() < 0.001);
        assert!((measured.units - 88.0).abs() < 0.001);
    }

    #[test]
    fn an_invalid_recent_window_keeps_a_valid_long_window() {
        let start = Instant::now();
        let mut window = DemandWindow::default();
        let mut result = None;
        for (second, (ticks, residency)) in [(0, 0), (10, 10), (20, 25), (30, 30)]
            .into_iter()
            .enumerate()
        {
            result = point(
                &mut window,
                start + Duration::from_secs(second as u64),
                ticks,
                residency,
            );
        }
        let measured = result.expect("the valid long interval must survive");
        assert!((measured.busy - 0.1).abs() < 0.001);
        assert!((measured.units - (248.0 / 3.0)).abs() < 0.001);
    }

    #[test]
    fn faster_sampling_uses_the_first_valid_elapsed_window() {
        let start = Instant::now();
        let mut window = DemandWindow::default();
        assert!(point(&mut window, start, 0, 0).is_none());
        assert!(point(&mut window, start + Duration::from_millis(600), 30, 30).is_none());
        let measured = point(&mut window, start + Duration::from_millis(1200), 60, 60)
            .expect("two short intervals form one valid measurement");
        assert!((measured.busy - 0.5).abs() < 0.001);
    }

    #[test]
    fn regressed_counters_and_time_restart_the_window() {
        let start = Instant::now();
        let mut window = DemandWindow::default();
        assert!(point(&mut window, start, 100, 100).is_none());
        assert!(point(&mut window, start + Duration::from_secs(1), 150, 150).is_some());
        assert!(point(&mut window, start + Duration::from_secs(2), 10, 10).is_none());
        assert_eq!(window.points.len(), 1);
        assert!(point(&mut window, start + Duration::from_secs(1), 20, 20).is_none());
        assert_eq!(window.points.len(), 1);
    }
    #[test]
    fn burst_work_and_busy_time_come_from_the_same_interval() {
        let policies = [Policy {
            cpu: 0,
            members: 1,
            max_khz: 1000,
            capacity: 800.,
        }];
        let start = Instant::now();
        let mut window = DemandWindow::default();
        let mut result = None;
        for (index, ticks) in [0, 10, 20, 100].into_iter().enumerate() {
            result = window.push(
                start + Duration::from_secs(index as u64),
                ThreadStat { start: 1, ticks },
                Residency::parse(&format!("cpu0\n500 {ticks}")).unwrap(),
                &policies,
                100.,
            );
        }
        let d = result.unwrap();
        assert!((d.busy - 0.8).abs() < 0.001);
        // 500/1000 * 800 = 400，外加最多一个节拍的取整误差。
        assert!((400.0..=411.0).contains(&(d.units / d.busy)));
    }
    #[test]
    fn identity_change_and_sampling_gap_discard_the_old_demand() {
        let policies = [Policy {
            cpu: 0,
            members: 1,
            max_khz: 1000,
            capacity: 800.0,
        }];
        let start = Instant::now();
        let mut window = DemandWindow::default();
        let mut result = None;
        for index in 0..4 {
            result = window.push(
                start + Duration::from_millis(index * 1100),
                ThreadStat {
                    start: 1,
                    ticks: index * 99,
                },
                Residency::parse(&format!("cpu0\n1000 {}", index * 99)).unwrap(),
                &policies,
                100.0,
            );
        }
        assert!(result.unwrap().busy >= 0.85);
        assert!(window
            .push(
                start + Duration::from_millis(4400),
                ThreadStat {
                    start: 2,
                    ticks: 396,
                },
                Residency::parse("cpu0\n1000 396").unwrap(),
                &policies,
                100.0
            )
            .is_none());
        assert_eq!(window.points.len(), 1);
        assert!(window
            .push(
                start + Duration::from_secs(20),
                ThreadStat {
                    start: 2,
                    ticks: 495,
                },
                Residency::parse("cpu0\n1000 495").unwrap(),
                &policies,
                100.0
            )
            .is_none());
        assert_eq!(window.points.len(), 1);
    }
}
