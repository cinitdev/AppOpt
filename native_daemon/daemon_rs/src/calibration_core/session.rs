// 单次校准采样会话。
//
// 采样单位是 /proc 的 utime+stime delta：
// - 主进程：按线程名聚合 delta，生成线程级 LoadRecord。
// - 子进程：把所有线程 delta 汇总成一个进程级 LoadRecord。
// - 子进程线程明细：单独保存在 child_threads，只写历史，不参与规则生成。
//
// 注意：comm 线程名最多 15 字节，Android 会截断，所以这里按读取到的 comm 聚合，
// App 侧展示和规则生成都必须接受这个截断现实。
impl CalibSession {
    fn new(pkg: String, processes: Vec<ProcInfo>) -> Self {
        Self {
            pkg,
            processes: processes
                .into_iter()
                .map(|proc_info| (proc_info.pid, proc_info.owner))
                .collect(),
            prev_ticks: HashMap::new(),
            records: HashMap::new(),
            child_threads: HashMap::new(),
            rounds: 0,
            last_sample: None,
        }
    }

    fn sample_once(&mut self) -> bool {
        // 目标 App 运行过程中可能拉起新的子进程，每隔 PROCESS_REFRESH_ROUNDS 轮补扫一次。
        if self.rounds % PROCESS_REFRESH_ROUNDS == 0 {
            self.refresh_processes();
        }

        let now = Instant::now();
        let elapsed = self
            .last_sample
            .map(|last| now.duration_since(last).as_secs_f64())
            .unwrap_or(0.0);
        self.last_sample = Some(now);

        let mut alive = HashMap::new();
        let mut observed_records: HashSet<TrackKey> = HashSet::new();
        let mut child_round_deltas: HashMap<ChildThreadKey, u64> = HashMap::new();
        for (pid, owner) in self.processes.clone() {
            if !PathBuf::from(format!("/proc/{pid}")).exists() {
                continue;
            }
            alive.insert(pid, owner.clone());
            if owner == self.pkg {
                self.sample_main_threads(pid, &owner, elapsed, &mut observed_records);
            } else {
                self.sample_child_process(
                    pid,
                    &owner,
                    elapsed,
                    &mut observed_records,
                    &mut child_round_deltas,
                );
            }
        }
        self.processes = alive;

        if self.processes.is_empty() {
            return false;
        }
        if elapsed > 0.0 {
            self.record_child_thread_summaries(child_round_deltas, elapsed);
            self.fill_missing_record_samples(&observed_records);
            self.rounds += 1;
        }
        true
    }

    fn refresh_processes(&mut self) {
        for proc_info in collect_pkg_processes(&self.pkg) {
            self.processes.insert(proc_info.pid, proc_info.owner);
        }
    }

    fn sample_main_threads(
        &mut self,
        pid: i32,
        owner: &str,
        elapsed: f64,
        observed_records: &mut HashSet<TrackKey>,
    ) {
        // 主进程线程按 comm 名称聚合。
        // 同名线程可能有多个 TID，这里合并 delta，避免线程重建导致历史曲线断裂。
        let task_dir = PathBuf::from(format!("/proc/{pid}/task"));
        let tasks = match fs::read_dir(task_dir) {
            Ok(tasks) => tasks,
            Err(_) => return,
        };

        let mut grouped_delta: HashMap<String, u64> = HashMap::new();
        for task in tasks.flatten() {
            let Some(tid) = task.file_name().to_str().and_then(parse_pid_text) else {
                continue;
            };
            let name = match fs::read_to_string(task.path().join("comm")) {
                Ok(name) => name.trim().to_string(),
                Err(_) => continue,
            };
            if name.is_empty() {
                continue;
            }
            let stat_path = format!("/proc/{pid}/task/{tid}/stat");
            let Some(ticks) = read_stat_ticks(&stat_path) else {
                continue;
            };
            let delta = self.tid_delta(pid, tid, ticks).unwrap_or(0);
            *grouped_delta.entry(name).or_default() += delta;
        }

        if elapsed <= 0.0 {
            return;
        }
        for (name, delta) in grouped_delta {
            let key = TrackKey {
                owner: owner.to_string(),
                name,
                is_process: false,
            };
            self.record_pct(key, delta_to_pct(delta, elapsed), observed_records);
        }
    }

    fn sample_child_process(
        &mut self,
        pid: i32,
        owner: &str,
        elapsed: f64,
        observed_records: &mut HashSet<TrackKey>,
        child_round_deltas: &mut HashMap<ChildThreadKey, u64>,
    ) {
        // 子进程只累计总 delta 生成进程级负载，同时保留线程 delta 给 history 展示。
        // 不把子进程线程放入 records，是为了避免生成 daemon 无法解析的子进程线程规则。
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
            let name = match fs::read_to_string(task.path().join("comm")) {
                Ok(name) => name.trim().to_string(),
                Err(_) => continue,
            };
            if name.is_empty() {
                continue;
            }
            let stat_path = format!("/proc/{pid}/task/{tid}/stat");
            let Some(ticks) = read_stat_ticks(&stat_path) else {
                continue;
            };
            let delta = self.tid_delta(pid, tid, ticks).unwrap_or(0);
            total_delta += delta;
            if delta > 0 {
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
        self.record_pct(key, delta_to_pct(total_delta, elapsed), observed_records);
    }

    fn tid_delta(&mut self, pid: i32, tid: i32, ticks: u64) -> Option<u64> {
        // 首次看到某个 TID 时没有前一帧数据，必须等下一轮才有有效 delta。
        let key = TidKey { pid, tid };
        let prev_ticks = self.prev_ticks.insert(key, ticks)?;
        if ticks < prev_ticks {
            return None;
        }
        Some(ticks - prev_ticks)
    }

    fn record_pct(
        &mut self,
        key: TrackKey,
        pct: f64,
        observed_records: &mut HashSet<TrackKey>,
    ) {
        observed_records.insert(key.clone());
        self.records
            .entry(key.clone())
            .or_insert_with(|| {
                let mut record = LoadRecord::new(&key);
                record.backfill_zero(self.rounds);
                record
            })
            .push(pct);
    }

    fn fill_missing_record_samples(&mut self, observed_records: &HashSet<TrackKey>) {
        for (key, record) in self.records.iter_mut() {
            if !observed_records.contains(key) {
                record.push(0.0);
            }
        }
    }

    fn record_child_thread_summaries(
        &mut self,
        child_round_deltas: HashMap<ChildThreadKey, u64>,
        elapsed: f64,
    ) {
        let known = self.child_threads.keys().cloned().collect::<Vec<_>>();
        for key in known {
            let pct = child_round_deltas
                .get(&key)
                .copied()
                .map(|delta| delta_to_pct(delta, elapsed))
                .unwrap_or(0.0);
            if let Some(summary) = self.child_threads.get_mut(&key) {
                summary.push(pct);
            }
        }
        for (key, delta) in child_round_deltas {
            if self.child_threads.contains_key(&key) {
                continue;
            }
            let mut summary = ChildThreadSummary::new(&key);
            summary.sample_count = self.rounds;
            summary.push(delta_to_pct(delta, elapsed));
            self.child_threads.insert(key, summary);
        }
    }
}

fn delta_to_pct(delta: u64, elapsed: f64) -> f64 {
    if elapsed <= 0.0 {
        0.0
    } else {
        ((delta as f64 / clock_ticks_per_second()) / elapsed * 100.0).clamp(0.0, 100.0)
    }
}

fn clock_ticks_per_second() -> f64 {
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
