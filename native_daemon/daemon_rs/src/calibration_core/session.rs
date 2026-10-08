use super::*;
// 单次校准采样会话。
//
// 采样单位是 /proc 的 utime+stime delta：
// - 主进程：按线程名聚合 delta，生成线程级 LoadRecord。
// - 子进程：把所有线程 delta 汇总成一个进程级 LoadRecord。
// - 子进程线程明细：保存在 child_threads，用于历史和待确认规则建议。
//
// 注意：comm 线程名最多 15 字节，Android 会截断，所以这里按读取到的 comm 聚合，
// App 侧展示和规则生成都必须接受这个截断现实。
impl CalibSession {
    pub(super) fn new(pkg: String, processes: Vec<ProcInfo>) -> Self {
        Self {
            pkg,
            storage: None,
            analyzer: crate::auto_affinity::calibration::Analyzer::new(),
            processes: processes
                .into_iter()
                .map(|proc_info| (proc_info.pid, proc_info.owner))
                .collect(),
            prev_ticks: HashMap::new(),
            records: HashMap::new(),
            child_threads: HashMap::new(),
            scratch_processes: HashMap::new(),
            scratch_observed_tids: HashSet::new(),
            scratch_child_round_deltas: HashMap::new(),
            scratch_grouped_delta: HashMap::new(),
            rounds: 0,
            started_at: Instant::now(),
            last_sample: None,
            last_process_scan: None,
            active_duration: Duration::ZERO,
            main_active_since: None,
            main_missing_since: None,
        }
    }

    pub(super) fn sample_once(&mut self) -> bool {
        self.analyzer.begin_round(self.rounds);
        // 目标 App 运行过程中可能拉起新的子进程，每隔 PROCESS_REFRESH_ROUNDS 轮补扫一次。
        if self.rounds.is_multiple_of(PROCESS_REFRESH_ROUNDS) {
            self.refresh_processes();
        }

        let now = Instant::now();
        let elapsed = self
            .last_sample
            .map(|last| now.duration_since(last).as_secs_f64())
            .unwrap_or(0.0);
        self.last_sample = Some(now);

        let mut current_processes = std::mem::take(&mut self.processes);
        let mut alive = std::mem::take(&mut self.scratch_processes);
        alive.clear();
        let mut main_alive = false;
        let mut observed_tids = std::mem::take(&mut self.scratch_observed_tids);
        observed_tids.clear();
        let mut child_round_deltas = std::mem::take(&mut self.scratch_child_round_deltas);
        child_round_deltas.clear();
        for (pid, owner) in current_processes.drain() {
            // PID 可能在两轮之间被复用，只有 cmdline 仍与原 owner 一致才继续采样。
            if read_cmdline(pid).ok().as_deref() != Some(owner.as_str()) {
                continue;
            }
            if owner == self.pkg {
                main_alive = true;
                self.sample_main_threads(pid, &owner, elapsed, &mut observed_tids);
            } else {
                self.sample_child_process(
                    pid,
                    &owner,
                    elapsed,
                    &mut child_round_deltas,
                    &mut observed_tids,
                );
            }
            alive.insert(pid, owner);
        }
        self.processes = alive;
        self.scratch_processes = current_processes;
        self.prev_ticks.retain(|key, _| observed_tids.contains(key));
        self.scratch_observed_tids = observed_tids;
        self.analyzer.finish_round();

        // 常驻子进程只参与负载统计，不能单独维持校准会话。主进程热重启或
        // /proc 瞬时不可读时保留一个短暂窗口，避免把一次重建误判为最终退出。
        if !main_alive {
            if let Some(started) = self.main_active_since.take() {
                self.active_duration += now.saturating_duration_since(started);
            }
            self.refresh_processes();
            let missing_since = self.main_missing_since.get_or_insert(now);
            self.scratch_child_round_deltas = child_round_deltas;
            return now.saturating_duration_since(*missing_since) < CALIB_MAIN_RESTART_GRACE;
        }
        self.main_missing_since = None;
        self.main_active_since.get_or_insert(now);
        if elapsed > 0.0 {
            self.record_child_thread_summaries(&mut child_round_deltas, elapsed);
            self.fill_missing_record_samples();
            self.analyzer.finish_window(elapsed);
            self.rounds += 1;
        }
        self.scratch_child_round_deltas = child_round_deltas;
        true
    }

    pub(super) fn sampled_duration(&self) -> Duration {
        self.active_duration
            + self
                .main_active_since
                .map(|started| Instant::now().saturating_duration_since(started))
                .unwrap_or_default()
    }

    pub(super) fn refresh_processes(&mut self) {
        let full = self.last_process_scan.is_none_or(|t| t.elapsed() >= Duration::from_secs(30));
        self.processes.extend(crate::package_processes::discover(&self.pkg, self.processes.keys().copied(), full));
        if full { self.last_process_scan = Some(Instant::now()); }
    }

    pub(super) fn sample_main_threads(
        &mut self,
        pid: i32,
        owner: &str,
        elapsed: f64,
        observed_tids: &mut HashSet<TidKey>,
    ) {
        // 主进程线程按 comm 名称聚合。
        // 同名线程可能有多个 TID，这里合并 delta，避免线程重建导致历史曲线断裂。
        let task_dir = PathBuf::from(format!("/proc/{pid}/task"));
        let tasks = match fs::read_dir(task_dir) {
            Ok(tasks) => tasks,
            Err(_) => return,
        };

        let mut grouped_delta = std::mem::take(&mut self.scratch_grouped_delta);
        grouped_delta.clear();
        for task in tasks.flatten() {
            let Some(tid) = task.file_name().to_str().and_then(parse_pid_text) else {
                continue;
            };
            let stat_path = format!("/proc/{pid}/task/{tid}/stat");
            let Some((name, ticks, starttime)) = read_thread_stat(&stat_path) else {
                continue;
            };
            let tid_key = TidKey {
                pid,
                tid,
                starttime,
            };
            observed_tids.insert(tid_key);
            let delta = self.tid_delta(tid_key, ticks).unwrap_or(0);
            if self.analyzer.wants_detail() {
                self.analyzer.observe(crate::auto_affinity::calibration::Observation {
                    pid, tid, start: starttime, ticks, delta, owner: owner.to_string(), name: name.clone(),
                });
            }
            if delta > 0 {
                *grouped_delta.entry(name).or_default() += delta;
            }
        }

        if elapsed > 0.0 {
            for (name, delta) in grouped_delta.drain() {
                self.analyzer.observe_load(owner, &name, delta_to_pct(delta, elapsed));
                let key = TrackKey {
                    owner: owner.to_string(),
                    name,
                    is_process: false,
                };
                self.record_pct(key, delta_to_pct(delta, elapsed));
            }
        } else {
            grouped_delta.clear();
        }
        self.scratch_grouped_delta = grouped_delta;
    }

    pub(super) fn sample_child_process(
        &mut self,
        pid: i32,
        owner: &str,
        elapsed: f64,
        child_round_deltas: &mut HashMap<ChildThreadKey, u64>,
        observed_tids: &mut HashSet<TidKey>,
    ) {
        // 子进程只累计总 delta 生成进程级负载，同时保留线程 delta 给 history 展示。
        // 子进程线程单独存入 child_threads；活跃与 5% 门槛在各自使用边界判断。
        let task_dir = PathBuf::from(format!("/proc/{pid}/task"));
        let tasks = match fs::read_dir(task_dir) {
            Ok(tasks) => tasks,
            Err(_) => return,
        };

        let mut total_delta = 0u64;
        for task in tasks.flatten() {
            let Some(tid) = task.file_name().to_str().and_then(parse_pid_text) else {
                continue;
            };
            let stat_path = format!("/proc/{pid}/task/{tid}/stat");
            let Some((name, ticks, starttime)) = read_thread_stat(&stat_path) else {
                continue;
            };
            let tid_key = TidKey {
                pid,
                tid,
                starttime,
            };
            observed_tids.insert(tid_key);
            let delta = self.tid_delta(tid_key, ticks).unwrap_or(0);
            total_delta += delta;
            if self.analyzer.wants_detail() {
                self.analyzer.observe(crate::auto_affinity::calibration::Observation {
                    pid, tid, start: starttime, ticks, delta,
                    owner: owner.to_string(), name: name.clone(),
                });
            }
            if delta > 0 {
                if elapsed > 0.0 {
                    self.analyzer.observe_load(owner, &name, delta_to_pct(delta, elapsed));
                }
                let key = ChildThreadKey {
                    owner: owner.to_string(),
                    name,
                };
                *child_round_deltas.entry(key).or_default() += delta;
            }
        }

        if elapsed <= 0.0 {
            return;
        }
        let key = TrackKey {
            owner: owner.to_string(),
            name: String::new(),
            is_process: true,
        };
        self.record_pct(key, delta_to_pct(total_delta, elapsed));
    }

    pub(super) fn tid_delta(&mut self, key: TidKey, ticks: u64) -> Option<u64> {
        // 首次看到某个 TID 时没有前一帧数据，必须等下一轮才有有效 delta。
        let prev_ticks = self.prev_ticks.insert(key, ticks)?;
        if ticks < prev_ticks {
            return None;
        }
        Some(ticks - prev_ticks)
    }

    pub(super) fn record_pct(&mut self, key: TrackKey, pct: f64) {
        if !pct.is_finite() {
            return;
        }
        let current_round = self.rounds;
        if let Some(record) = self.records.get_mut(&key) {
            record.last_seen_round = current_round;
            record.push(pct);
            return;
        }
        // 休眠或新建线程仅保留少量按 TID 索引的时钟计数基线。
        // 已有记录仍计入零负载轮次，以保持整个会话的平均值准确。
        if pct <= 0.0 {
            return;
        }
        if !self.reserve_record_slot(&key, pct) {
            return;
        }
        let mut record = LoadRecord::new(&key, current_round);
        record.backfill_zero(current_round);
        record.push(pct);
        self.records.insert(key, record);
    }

    pub(super) fn reserve_record_slot(&mut self, key: &TrackKey, pct: f64) -> bool {
        if self.records.len() < CALIB_MAX_TRACKED_RECORDS {
            return true;
        }
        let weakest = self
            .records
            .iter()
            .filter(|(_, record)| key.is_process || !record.is_process)
            .min_by(|left, right| {
                left.1
                    .is_process
                    .cmp(&right.1.is_process)
                    .then_with(|| {
                        left.1
                            .retention_score()
                            .partial_cmp(&right.1.retention_score())
                            .unwrap_or(std::cmp::Ordering::Equal)
                    })
                    .then_with(|| left.1.last_seen_round.cmp(&right.1.last_seen_round))
            })
            .map(|(candidate, record)| (candidate.clone(), record.retention_score()));
        let Some((weakest_key, weakest_score)) = weakest else {
            return false;
        };
        // 进程聚合项始终优先；普通线程只有当前负载足以超过最弱项时才换入。
        if !key.is_process && pct.clamp(0.0, 999.0) * 5.0 <= weakest_score {
            return false;
        }
        self.records.remove(&weakest_key);
        true
    }

    pub(super) fn fill_missing_record_samples(&mut self) {
        let current_round = self.rounds;
        for record in self.records.values_mut() {
            if record.last_seen_round != current_round {
                record.push(0.0);
            }
        }
    }

    pub(super) fn record_child_thread_summaries(
        &mut self,
        child_round_deltas: &mut HashMap<ChildThreadKey, u64>,
        elapsed: f64,
    ) {
        let total_samples = self.rounds + 1;
        // 先更新已经跟踪的线程，再处理新名称。这样达到容量上限时，淘汰评分与
        // 旧实现“全部已知项先完成本轮采样”的顺序保持一致。
        let known = &mut self.child_threads;
        child_round_deltas.retain(|key, delta| {
            let Some(summary) = known.get_mut(key) else {
                return true;
            };
            summary.push(delta_to_pct(*delta, elapsed));
            false
        });
        for (key, delta) in child_round_deltas.drain() {
            let pct = delta_to_pct(delta, elapsed);
            if !self.reserve_child_thread_slot(pct, total_samples) {
                continue;
            }
            let mut summary = ChildThreadSummary::new(&key);
            summary.push(pct);
            self.child_threads.insert(key, summary);
        }
    }

    pub(super) fn reserve_child_thread_slot(&mut self, pct: f64, total_samples: usize) -> bool {
        if self.child_threads.len() < CALIB_MAX_CHILD_THREAD_SUMMARIES {
            return true;
        }
        let weakest = self
            .child_threads
            .iter()
            .min_by(|left, right| {
                let left_score = left.1.avg(total_samples) * 4.0 + left.1.max_pct;
                let right_score = right.1.avg(total_samples) * 4.0 + right.1.max_pct;
                left_score
                    .partial_cmp(&right_score)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .map(|(key, summary)| {
                (
                    key.clone(),
                    summary.avg(total_samples) * 4.0 + summary.max_pct,
                )
            });
        let Some((weakest_key, weakest_score)) = weakest else {
            return false;
        };
        if pct.clamp(0.0, 999.0) * 5.0 <= weakest_score {
            return false;
        }
        self.child_threads.remove(&weakest_key);
        true
    }
}

pub(super) fn delta_to_pct(delta: u64, elapsed: f64) -> f64 {
    if elapsed <= 0.0 {
        0.0
    } else {
        ((delta as f64 / clock_ticks_per_second()) / elapsed * 100.0).clamp(0.0, 999.0)
    }
}

pub(super) fn clock_ticks_per_second() -> f64 {
    static CLK_TCK: OnceLock<f64> = OnceLock::new();
    *CLK_TCK.get_or_init(|| {
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            let value = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
            if value > 0 {
                value as f64
            } else {
                100.0
            }
        }
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        {
            100.0
        }
    })
}

#[cfg(test)]
mod session_capacity_tests {
    use super::*;

    #[cfg(any(target_os = "android", target_os = "linux"))]
    #[test]
    pub(super) fn live_proc_sampler_reads_the_current_process() {
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (stop_tx, stop_rx) = std::sync::mpsc::channel();
        let sleeper = thread::Builder::new().name("CalibIdleProbe".into()).spawn(move || {
            ready_tx.send(()).unwrap();
            let _ = stop_rx.recv();
        }).unwrap();
        ready_rx.recv().unwrap();
        let pid = std::process::id() as i32;
        let owner = read_cmdline(pid).expect("current process cmdline");
        let mut session = CalibSession::new(
            owner.clone(),
            vec![ProcInfo {
                pid,
                owner: owner.clone(),
            }],
        );

        assert!(session.sample_once());
        let busy_since = Instant::now();
        while busy_since.elapsed() < Duration::from_millis(550) {
            std::hint::black_box(12345u64.wrapping_mul(6789));
        }
        assert!(session.sample_once());
        assert_eq!(session.rounds, 1);
        assert!(!session.prev_ticks.is_empty());
        assert!(!session.records.is_empty());
        assert!(session.records.values().all(|record| record.owner == owner));
        assert!(session.records.values().all(|record| record.name != "CalibIdleProbe"));
        stop_tx.send(()).unwrap();
        sleeper.join().unwrap();
    }

    #[test]
    pub(super) fn active_round_cannot_exceed_record_limits() {
        let mut session = CalibSession::new("com.example".to_string(), Vec::new());
        for index in 0..(CALIB_MAX_TRACKED_RECORDS + 300) {
            session.record_pct(
                TrackKey {
                    owner: "com.example".to_string(),
                    name: format!("thread-{index}"),
                    is_process: false,
                },
                (index % 100) as f64,
            );
        }
        let process_key = TrackKey {
            owner: "com.example:worker".to_string(),
            name: String::new(),
            is_process: true,
        };
        session.record_pct(process_key.clone(), 1.0);
        assert!(session.records.len() <= CALIB_MAX_TRACKED_RECORDS);
        assert!(session.records.contains_key(&process_key));

        let mut child = HashMap::new();
        for index in 0..(CALIB_MAX_CHILD_THREAD_SUMMARIES + 200) {
            child.insert(
                ChildThreadKey {
                    owner: "com.example:worker".to_string(),
                    name: format!("child-{index}"),
                },
                (index + 1) as u64,
            );
        }
        session.record_child_thread_summaries(&mut child, 1.0);
        assert!(session.child_threads.len() <= CALIB_MAX_CHILD_THREAD_SUMMARIES);
        assert!(child.is_empty());
    }

    #[test]
    pub(super) fn missing_record_round_appends_one_zero_without_a_seen_key_set() {
        let mut session = CalibSession::new("com.example".to_string(), Vec::new());
        let key = TrackKey {
            owner: "com.example".to_string(),
            name: "RenderThread".to_string(),
            is_process: false,
        };
        session.record_pct(key.clone(), 10.0);
        session.rounds = 1;
        session.fill_missing_record_samples();

        let record = session.records.get(&key).unwrap();
        assert_eq!(
            record.series_values().iter().copied().collect::<Vec<_>>(),
            vec![10.0, 0.0]
        );
        assert_eq!(record.sample_count, 2);
        assert_eq!(record.avg(), 5.0);
    }

    #[test]
    pub(super) fn child_summary_average_keeps_implicit_zero_rounds() {
        let key = ChildThreadKey {
            owner: "com.example:worker".to_string(),
            name: "worker".to_string(),
        };
        let mut summary = ChildThreadSummary::new(&key);
        summary.push(50.0);
        assert_eq!(summary.avg(5), 10.0);
    }
}
