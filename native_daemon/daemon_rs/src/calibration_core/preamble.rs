use std::collections::{HashMap, HashSet};
use std::fmt::Write as FmtWrite;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

// 校准线程负责读取 App 写入的 calibrate.cmd，采样目标应用的 CPU 负载并生成规则。
//
// 当前策略和 C 版保持核心行为一致，但修正了子进程线程规则的问题：
// - 主进程：记录每个线程的真实 CPU 使用率，用于生成 com.pkg{thread}=cpus。
// - 子进程：记录整个子进程的 CPU 使用率，用于生成 com.pkg:proc=cpus。
// - 子进程线程：只写入 history 明细给用户看，不生成 com.pkg:proc{thread}，
//   因为当前 C/AppOpt 规则语法不支持这种子进程线程规则，生成了也不会正确执行。
const CONFIG_DIR: &str = "/data/adb/modules/AppOpt/config";
const CALIB_CMD_FILE: &str = "/data/adb/modules/AppOpt/config/calibrate.cmd";
const CALIB_STATE_FILE: &str = "/data/adb/modules/AppOpt/config/calibrate.state";
const CALIB_POLICY_FILE: &str = "/data/adb/modules/AppOpt/config/calib_policy.conf";
const CALIB_POLICY_LOCK: &str = "/data/adb/modules/AppOpt/config/calib_policy.conf.lock";
const CALIB_CONFIG_LOCK: &str = "/data/adb/modules/AppOpt/config/applist.conf.lock";
const HISTORY_DIR: &str = "/data/adb/modules/AppOpt/history";
const CALIB_TOPO_BEGIN: &str = "# AppOpt detected CPU topology begin";
const CALIB_TOPO_END: &str = "# AppOpt detected CPU topology end";
const SAMPLE_INTERVAL: Duration = Duration::from_millis(500);
const PROCESS_REFRESH_ROUNDS: usize = 10;
const CALIB_MIN_ROUNDS: usize = 60;
const MAX_THREAD_RULES: usize = 6;
const CALIB_MAX_SERIES_POINTS: usize = 1200;
const HISTORY_MAX_SESSIONS: usize = 7;

#[derive(Debug, Clone)]
struct ProcInfo {
    pid: i32,
    owner: String,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
struct TrackKey {
    // owner 是主包名或子进程名；name 只有线程记录才使用。
    owner: String,
    name: String,
    // true 表示“子进程整体负载”，false 表示“主进程线程负载”。
    is_process: bool,
}

#[derive(Debug, Clone, Copy, Hash, PartialEq, Eq)]
struct TidKey {
    pid: i32,
    tid: i32,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
struct ChildThreadKey {
    // 子进程线程摘要使用 owner+线程名聚合，写入 history 让 App 展开查看。
    owner: String,
    name: String,
}

#[derive(Debug, Clone)]
struct LoadRecord {
    owner: String,
    name: String,
    is_process: bool,
    // sum_pct/max_pct/samples 记录的是“真实 CPU 使用率”，不是线程占应用总负载比例。
    sum_pct: f64,
    max_pct: f64,
    sample_count: usize,
    samples: Vec<f32>,
}

impl LoadRecord {
    fn new(key: &TrackKey) -> Self {
        Self {
            owner: key.owner.clone(),
            name: key.name.clone(),
            is_process: key.is_process,
            sum_pct: 0.0,
            max_pct: 0.0,
            sample_count: 0,
            samples: Vec::new(),
        }
    }

    fn push(&mut self, pct: f64) {
        // history 只保留有限点数，避免用户长时间校准导致单个 log 无限膨胀。
        let pct = pct.clamp(0.0, 100.0);
        self.sum_pct += pct;
        self.sample_count += 1;
        self.max_pct = self.max_pct.max(pct);
        if self.samples.len() >= CALIB_MAX_SERIES_POINTS {
            self.samples.remove(0);
        }
        self.samples.push(pct as f32);
    }

    fn backfill_zero(&mut self, rounds: usize) {
        if rounds == 0 || self.sample_count != 0 {
            return;
        }
        self.sample_count = rounds;
        let visible = rounds.min(CALIB_MAX_SERIES_POINTS);
        self.samples = vec![0.0; visible];
    }

    fn avg(&self) -> f64 {
        if self.sample_count == 0 {
            0.0
        } else {
            self.sum_pct / self.sample_count as f64
        }
    }
}

#[derive(Debug, Clone)]
struct ChildThreadSummary {
    owner: String,
    name: String,
    sum_pct: f64,
    max_pct: f64,
    sample_count: usize,
}

impl ChildThreadSummary {
    fn new(key: &ChildThreadKey) -> Self {
        Self {
            owner: key.owner.clone(),
            name: key.name.clone(),
            sum_pct: 0.0,
            max_pct: 0.0,
            sample_count: 0,
        }
    }

    fn push(&mut self, pct: f64) {
        let pct = pct.clamp(0.0, 999.0);
        self.sum_pct += pct;
        self.max_pct = self.max_pct.max(pct);
        self.sample_count += 1;
    }

    fn avg(&self) -> f64 {
        if self.sample_count == 0 {
            0.0
        } else {
            self.sum_pct / self.sample_count as f64
        }
    }
}

struct CalibSession {
    pkg: String,
    // pid -> owner。owner 可能是主包名，也可能是 com.pkg:push 这类子进程。
    processes: HashMap<i32, String>,
    // 每个 TID 上次读取到的 utime+stime，用相邻两次差值计算 CPU 使用率。
    prev_ticks: HashMap<TidKey, u64>,
    // records 只参与规则生成：主进程记录线程负载，子进程记录整体进程负载。
    records: HashMap<TrackKey, LoadRecord>,
    // 子进程线程明细只写入历史记录给用户查看，不生成子进程线程规则。
    child_threads: HashMap<ChildThreadKey, ChildThreadSummary>,
    rounds: usize,
    last_sample: Option<Instant>,
}
