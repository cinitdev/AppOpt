use super::*;

// 校准采集活跃线程并保存待确认建议；生效配置由 App 在用户保存时写入。
pub(super) const CONFIG_DIR: &str = "/data/adb/modules/QixiaThreads/config";
pub(super) const CALIB_CMD_FILE: &str = "/data/adb/modules/QixiaThreads/config/calibrate.cmd";
pub(super) const CALIB_STATE_FILE: &str = "/data/adb/modules/QixiaThreads/config/calibrate.state";
pub(crate) const CALIB_POLICY_FILE: &str = "/data/adb/modules/QixiaThreads/config/calib_policy.conf";
pub(super) const CALIB_POLICY_LOCK: &str = "/data/adb/modules/QixiaThreads/config/calib_policy.conf.lock";
pub(super) const CALIB_TOPO_BEGIN: &str = "# QixiaThreads detected CPU topology begin";
pub(super) const CALIB_TOPO_END: &str = "# QixiaThreads detected CPU topology end";
pub(super) const SAMPLE_INTERVAL: Duration = Duration::from_millis(500);
pub(super) const CALIB_PROGRESS_LOG_ROUNDS: usize = 120;
pub(super) const CALIB_MAX_SESSION_DURATION: Duration = Duration::from_secs(6 * 60 * 60);
pub(super) const PROCESS_REFRESH_ROUNDS: usize = 10;
pub(super) const CALIB_MIN_DURATION: Duration = Duration::from_secs(30);
pub(super) const CALIB_MAIN_RESTART_GRACE: Duration = Duration::from_secs(8);
pub(super) const CALIB_MAX_SERIES_POINTS: usize = 1200;
pub(super) const CALIB_MAX_TRACKED_RECORDS: usize = 1024;
pub(super) const CALIB_MAX_CHILD_THREAD_SUMMARIES: usize = 512;
pub(super) const HISTORY_MAX_SESSIONS: usize = 10;

#[derive(Debug, Clone)]
pub(super) struct ProcInfo {
    pub(super) pid: i32,
    pub(super) owner: String,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
pub(super) struct TrackKey {
    // owner 是主包名或子进程名；name 只有线程记录才使用。
    pub(super) owner: String,
    pub(super) name: String,
    // true 表示“子进程整体负载”，false 表示“主进程线程负载”。
    pub(super) is_process: bool,
}

#[derive(Debug, Clone, Copy, Hash, PartialEq, Eq)]
pub(super) struct TidKey {
    pub(super) pid: i32,
    pub(super) tid: i32,
    // /proc/<pid>/task/<tid>/stat 的 starttime，用来区分被复用的 TID。
    pub(super) starttime: u64,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
pub(super) struct ChildThreadKey {
    // 子进程线程摘要使用 owner+线程名聚合，写入 history 让 App 展开查看。
    pub(super) owner: String,
    pub(super) name: String,
}

#[derive(Debug, Clone)]
pub(super) struct LoadRecord {
    pub(super) owner: String,
    pub(super) name: String,
    pub(super) is_process: bool,
    // sum_pct/max_pct/samples 记录的是“真实 CPU 使用率”，不是线程占应用总负载比例。
    pub(super) sum_pct: f64,
    pub(super) max_pct: f64,
    pub(super) sample_count: usize,
    pub(super) samples: VecDeque<f32>,
    pub(super) series_stride: usize,
    pub(super) series_pending_sum: f64,
    pub(super) series_pending_count: usize,
    pub(super) last_seen_round: usize,
    pub(super) activity: Activity,
}

impl LoadRecord {
    pub(super) fn new(key: &TrackKey, first_seen_round: usize) -> Self {
        Self {
            owner: key.owner.clone(),
            name: key.name.clone(),
            is_process: key.is_process,
            sum_pct: 0.0,
            max_pct: 0.0,
            sample_count: 0,
            samples: VecDeque::new(),
            series_stride: 1,
            series_pending_sum: 0.0,
            series_pending_count: 0,
            last_seen_round: first_seen_round,
            activity: Activity::default(),
        }
    }

    pub(super) fn push(&mut self, pct: f64) {
        // history 只保留有限点数，避免用户长时间校准导致单个 log 无限膨胀。
        // 同名线程和子进程记录可能聚合多个 TID，允许超过单核的 100%。
        let pct = pct.clamp(0.0, 999.0);
        self.activity.observe(pct);
        self.sum_pct += pct;
        self.sample_count += 1;
        self.max_pct = self.max_pct.max(pct);
        self.series_pending_sum += pct;
        self.series_pending_count += 1;
        if self.series_pending_count >= self.series_stride {
            self.samples
                .push_back((self.series_pending_sum / self.series_pending_count as f64) as f32);
            self.series_pending_sum = 0.0;
            self.series_pending_count = 0;
        }
        if self.samples.len() > CALIB_MAX_SERIES_POINTS
            || (self.samples.len() == CALIB_MAX_SERIES_POINTS
                && self.series_pending_count > 0)
        {
            self.compact_series();
        }
    }

    pub(super) fn backfill_zero(&mut self, rounds: usize) {
        if rounds == 0 || self.sample_count != 0 {
            return;
        }
        self.sample_count = rounds;
        self.series_stride = 1;
        while rounds.div_ceil(self.series_stride) > CALIB_MAX_SERIES_POINTS {
            self.series_stride = self.series_stride.saturating_mul(2);
        }
        let visible = rounds / self.series_stride;
        self.samples = std::iter::repeat_n(0.0, visible).collect();
        self.series_pending_sum = 0.0;
        self.series_pending_count = rounds % self.series_stride;
    }

    pub(super) fn avg(&self) -> f64 {
        if self.sample_count == 0 {
            0.0
        } else {
            self.sum_pct / self.sample_count as f64
        }
    }

    pub(super) fn retention_score(&self) -> f64 {
        // 平均负载比一次性峰值更能代表可生成规则的稳定线程。
        self.avg() * 4.0 + self.max_pct
    }

    pub(super) fn compact_series(&mut self) {
        let old_stride = self.series_stride;
        let mut compacted = VecDeque::with_capacity(self.samples.len().div_ceil(2));
        while self.samples.len() >= 2 {
            let left = self.samples.pop_front().unwrap_or_default();
            let right = self.samples.pop_front().unwrap_or_default();
            compacted.push_back((left + right) * 0.5);
        }
        if let Some(last) = self.samples.pop_front() {
            self.series_pending_sum += last as f64 * old_stride as f64;
            self.series_pending_count += old_stride;
        }
        self.samples = compacted;
        self.series_stride = old_stride.saturating_mul(2).max(1);
    }

    pub(super) fn series_values(&self) -> VecDeque<f32> {
        let mut values = self.samples.clone();
        if self.series_pending_count > 0 {
            values.push_back((self.series_pending_sum / self.series_pending_count as f64) as f32);
        }
        values
    }
}

#[derive(Debug, Clone)]
pub(super) struct ChildThreadSummary {
    pub(super) owner: String,
    pub(super) name: String,
    pub(super) sum_pct: f64,
    pub(super) max_pct: f64,
    pub(super) activity: Activity,
}

impl ChildThreadSummary {
    pub(super) fn new(key: &ChildThreadKey) -> Self {
        Self {
            owner: key.owner.clone(),
            name: key.name.clone(),
            sum_pct: 0.0,
            max_pct: 0.0,
            activity: Activity::default(),
        }
    }

    pub(super) fn push(&mut self, pct: f64) {
        let pct = pct.clamp(0.0, 999.0);
        self.activity.observe(pct);
        self.sum_pct += pct;
        self.max_pct = self.max_pct.max(pct);
    }

    pub(super) fn avg(&self, total_samples: usize) -> f64 {
        if total_samples == 0 {
            0.0
        } else {
            self.sum_pct / total_samples as f64
        }
    }
}

pub(super) struct CalibSession {
    pub(super) pkg: String,
    pub(super) storage: Option<crate::private_storage::Storage>,
    pub(super) analyzer: crate::auto_affinity::calibration::Analyzer,
    // pid -> owner。owner 可能是主包名，也可能是 com.pkg:push 这类子进程。
    pub(super) processes: HashMap<i32, String>,
    // 每个 TID 上次读取到的 utime+stime，用相邻两次差值计算 CPU 使用率。
    pub(super) prev_ticks: HashMap<TidKey, u64>,
    // 主进程线程与子进程整体曲线用于历史，线程负载同时提供规则建议。
    pub(super) records: HashMap<TrackKey, LoadRecord>,
    // 子进程活跃线程摘要同时用于历史和规则建议。
    pub(super) child_threads: HashMap<ChildThreadKey, ChildThreadSummary>,
    // 这些容器每 500ms 清空复用容量，避免高线程应用持续触发分配器。
    pub(super) scratch_processes: HashMap<i32, String>,
    pub(super) scratch_observed_tids: HashSet<TidKey>,
    pub(super) scratch_child_round_deltas: HashMap<ChildThreadKey, u64>,
    pub(super) scratch_grouped_delta: HashMap<String, u64>,
    pub(super) rounds: usize,
    pub(super) started_at: Instant,
    pub(super) last_sample: Option<Instant>,
    pub(super) last_process_scan: Option<Instant>,
    pub(super) active_duration: Duration,
    pub(super) main_active_since: Option<Instant>,
    pub(super) main_missing_since: Option<Instant>,
}

#[cfg(test)]
mod load_record_series_tests {
    use super::*;

    #[test]
    pub(super) fn series_downsamples_the_full_timeline_without_changing_full_average() {
        let key = TrackKey {
            owner: "com.example".to_string(),
            name: "RenderThread".to_string(),
            is_process: false,
        };
        let mut record = LoadRecord::new(&key, 0);
        let total = CALIB_MAX_SERIES_POINTS + 37;
        let mut expected_sum = 0.0;
        for index in 0..total {
            let value = (index % 97) as f64;
            expected_sum += value;
            record.push(value);
        }

        let series = record.series_values();
        assert!(series.len() <= CALIB_MAX_SERIES_POINTS);
        assert_eq!(record.sample_count, total);
        assert!((record.avg() - expected_sum / total as f64).abs() < f64::EPSILON);
        assert_eq!(record.max_pct, 96.0);
        assert!(record.series_stride > 1);
        assert_eq!(series.front().copied(), Some(0.5));
        assert_eq!(series.back().copied(), Some(((total - 1) % 97) as f32));
    }

    #[test]
    pub(super) fn aggregate_records_preserve_multi_core_load() {
        let key = TrackKey {
            owner: "com.example:worker".to_string(),
            name: String::new(),
            is_process: true,
        };
        let mut record = LoadRecord::new(&key, 0);
        record.push(245.5);
        assert_eq!(record.avg(), 245.5);
        assert_eq!(record.max_pct, 245.5);
        assert_eq!(record.series_values().back().copied(), Some(245.5));
    }
}
