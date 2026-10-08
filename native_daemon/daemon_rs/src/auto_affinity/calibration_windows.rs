//! 有界的同步 CPU 时间窗口，与最终生成的 glob 线程族独立。
//! 可以准确丢弃被其他窗口完全覆盖的窗口：其中任意非负线程族之和
//! 都不会超过保留窗口中的值。容量满时将相邻窗口合并为保守上界，
//! 使长会话仍保留最重负载阶段。
use std::collections::BTreeMap;

const MAX_NAMES: usize = 1024;
const MAX_WINDOWS: usize = 128;
const MAX_VALUES: usize = 8192;

#[derive(Default)]
pub(super) struct NameStats {
    pub busy_seconds: f64,
    pub last_active_round: usize,
}

struct Window {
    loads: Vec<(u16, f32)>,
    envelope: bool,
}

#[derive(Default)]
pub(super) struct Windows {
    names: BTreeMap<String, BTreeMap<String, u16>>,
    stats: Vec<NameStats>,
    current: Vec<f64>,
    retained: Vec<Window>,
    values: usize,
}

pub(super) struct JointPeak {
    pub pct: f64,
    pub work: f64,
    pub bounded: bool,
}

impl Windows {
    pub fn begin(&mut self) {
        self.current.fill(0.0);
    }

    pub fn observe(&mut self, owner: &str, name: &str, pct: f64) {
        if !pct.is_finite() || pct <= 0.0 {
            return;
        }
        let id = match self.id(owner, name) {
            Some(id) => id,
            None if self.stats.len() < MAX_NAMES => {
                let id = self.stats.len();
                self.names
                    .entry(owner.into())
                    .or_default()
                    .insert(name.into(), id as u16);
                self.stats.push(NameStats::default());
                self.current.push(0.0);
                id
            }
            None => return,
        };
        self.current[id] += pct;
    }

    pub fn finish(&mut self, elapsed: f64, round: usize) {
        if !elapsed.is_finite() || elapsed <= 0.0 {
            return;
        }
        let window: Vec<_> = self
            .current
            .iter()
            .enumerate()
            .filter_map(|(id, &pct)| {
                if pct <= 0.0 {
                    return None;
                }
                self.stats[id].busy_seconds += pct * elapsed / 100.0;
                self.stats[id].last_active_round = round;
                // 向上取整，确保存储值不会低估实测窗口。
                Some((id as u16, (pct * 100.0).ceil() as f32 / 100.0))
            })
            .collect();
        if window.is_empty()
            || self
                .retained
                .iter()
                .any(|old| dominates(&old.loads, &window))
        {
            return;
        }
        self.retained.retain(|old| {
            if dominates(&window, &old.loads) {
                self.values -= old.loads.len();
                false
            } else {
                true
            }
        });
        self.values += window.len();
        self.retained.push(Window {
            loads: window,
            envelope: false,
        });
        while self.retained.len() > MAX_WINDOWS || self.values > MAX_VALUES {
            // 选择合并后上界膨胀最小的一对相邻窗口。
            // 这只是上界，不能标记为实测并发量。
            let index = self
                .retained
                .windows(2)
                .enumerate()
                .min_by(|(_, a), (_, b)| {
                    inflation(&a[0].loads, &a[1].loads)
                        .total_cmp(&inflation(&b[0].loads, &b[1].loads))
                })
                .map(|(i, _)| i)
                .unwrap();
            let right = self.retained.remove(index + 1);
            let left = &mut self.retained[index];
            self.values -= left.loads.len() + right.loads.len();
            left.loads = envelope(&left.loads, &right.loads);
            left.envelope = true;
            self.values += left.loads.len();
        }
    }

    fn id(&self, owner: &str, name: &str) -> Option<usize> {
        self.names.get(owner)?.get(name).copied().map(usize::from)
    }

    pub fn stats(&self, owner: &str, name: &str) -> Option<&NameStats> {
        self.stats.get(self.id(owner, name)?)
    }

    /// 速率表示单颗 CPU 满负载时的容量单位。500 ms 窗口内的 CPU 时间
    /// 给出吞吐量下界，并不等于同时可运行的 TID 数量。
    pub fn joint_peak(&self, owner: &str, members: &[(&str, f64)]) -> Option<JointPeak> {
        let mut rates = vec![None; self.stats.len()];
        for &(name, rate) in members {
            let id = self.id(owner, name)?;
            rates[id] = Some(rate);
        }
        let (mut pct, mut work) = (0.0f64, 0.0f64);
        let mut bounded = false;
        for window in &self.retained {
            let (mut window_pct, mut window_work) = (0.0, 0.0);
            for &(id, load) in &window.loads {
                if let Some(rate) = rates[id as usize] {
                    window_pct += load as f64;
                    window_work += load as f64 / 100.0 * rate;
                }
            }
            pct = pct.max(window_pct);
            work = work.max(window_work);
            bounded |= window.envelope && window_pct > 0.0;
        }
        Some(JointPeak { pct, work, bounded })
    }
}

fn envelope(a: &[(u16, f32)], b: &[(u16, f32)]) -> Vec<(u16, f32)> {
    let (mut ai, mut bi) = (0, 0);
    let mut result = Vec::with_capacity(a.len().max(b.len()));
    while ai < a.len() || bi < b.len() {
        if bi == b.len() || (ai < a.len() && a[ai].0 < b[bi].0) {
            result.push(a[ai]);
            ai += 1;
        } else if ai == a.len() || b[bi].0 < a[ai].0 {
            result.push(b[bi]);
            bi += 1;
        } else {
            result.push((a[ai].0, a[ai].1.max(b[bi].1)));
            ai += 1;
            bi += 1;
        }
    }
    result
}

fn inflation(a: &[(u16, f32)], b: &[(u16, f32)]) -> f64 {
    // L1 距离界定合并这对窗口所增加的负载上界。
    let (mut ai, mut bi, mut distance) = (0, 0, 0.0);
    while ai < a.len() || bi < b.len() {
        if bi == b.len() || (ai < a.len() && a[ai].0 < b[bi].0) {
            distance += a[ai].1 as f64;
            ai += 1;
        } else if ai == a.len() || b[bi].0 < a[ai].0 {
            distance += b[bi].1 as f64;
            bi += 1;
        } else {
            distance += (a[ai].1 - b[bi].1).abs() as f64;
            ai += 1;
            bi += 1;
        }
    }
    distance
}

fn dominates(a: &[(u16, f32)], b: &[(u16, f32)]) -> bool {
    let mut cursor = 0;
    for &(id, pct) in b {
        while cursor < a.len() && a[cursor].0 < id {
            cursor += 1;
        }
        if cursor == a.len() || a[cursor].0 != id || a[cursor].1 < pct {
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    fn add(w: &mut Windows, a: f64, b: f64, round: usize) {
        w.begin();
        w.observe("app", "a", a);
        w.observe("app", "b", b);
        w.finish(0.5, round);
    }
    #[test]
    fn alternating_workers_do_not_add_their_independent_peaks() {
        let mut w = Windows::default();
        for i in 0..10000 {
            add(
                &mut w,
                if i % 2 == 0 { 80.0 } else { 0.0 },
                if i % 2 == 1 { 80.0 } else { 0.0 },
                i,
            );
        }
        let peak = w.joint_peak("app", &[("a", 1000.0), ("b", 500.0)]).unwrap();
        assert_eq!((peak.pct, peak.work, peak.bounded), (80.0, 800.0, false));
        assert_eq!(w.retained.len(), 2);
        assert!((w.stats("app", "a").unwrap().busy_seconds - 2000.0).abs() < 1e-6);
    }
    #[test]
    fn coincident_loads_and_same_name_tids_are_summed_within_a_window() {
        let mut w = Windows::default();
        add(&mut w, 60.0, 70.0, 0);
        w.begin();
        w.observe("app", "a", 80.0);
        w.observe("app", "a", 60.0);
        w.finish(0.5, 1);
        assert_eq!(
            w.joint_peak("app", &[("a", 1000.0), ("b", 1000.0)])
                .unwrap()
                .pct,
            140.0
        );
    }
    #[test]
    fn compaction_is_bounded_and_cannot_underestimate_a_discarded_family_peak() {
        let mut w = Windows::default();
        for i in 1..400 {
            add(&mut w, i as f64, (400 - i) as f64, i);
        }
        assert!(w.retained.len() <= MAX_WINDOWS && w.values <= MAX_VALUES);
        let peak = w.joint_peak("app", &[("a", 1000.0), ("b", 800.0)]).unwrap();
        assert!(peak.pct >= 400.0 && peak.work >= 399.8);
        assert!(peak.bounded);
        assert!(w.joint_peak("app", &[("unobserved", 1000.0)]).is_none());
    }

    #[test]
    fn names_are_process_scoped_and_unknown_names_cannot_borrow_another_owners_evidence() {
        let mut w = Windows::default();
        w.observe("app", "worker", 30.0);
        w.observe("app:child", "worker", 90.0);
        w.finish(0.5, 1);
        assert_eq!(
            w.joint_peak("app", &[("worker", 1000.0)]).unwrap().pct,
            30.0
        );
        assert!(w.joint_peak("app:other", &[("worker", 1000.0)]).is_none());
        w.begin();
        for i in 0..MAX_NAMES {
            w.observe("app", &format!("worker-{i}"), 10.0);
        }
        w.finish(0.5, 2);
        assert_eq!(w.stats.len(), MAX_NAMES);
        assert!(w.joint_peak("app", &[("worker-1023", 1000.0)]).is_none());
        assert!(w.retained.len() <= MAX_WINDOWS && w.values <= MAX_VALUES);
    }
}
