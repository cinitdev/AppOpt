use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::env;
#[cfg(unix)]
use std::ffi::CString;
use std::ffi::OsStr;
use std::fs;
use std::io;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[cfg(any(target_os = "android", target_os = "linux"))]
use std::mem;
#[cfg(unix)]
use std::os::unix::fs::MetadataExt;

// AppOpt Rust 守护进程的主逻辑。
//
// 设计目标不是把 C 版简单翻译成 Rust，而是减少长期运行时对 /proc 的全量遍历：
// 1. App/前台 helper 写入 package_uid.map，daemon 用 appId 缩小包名候选并以 cmdline 最终确认。
// 2. 第一次启动、配置变化和周期校验时才全量扫描 /proc。
// 3. 日常只比较数字 PID 目录快照，并复查新增 PID；命中后缓存 PID 和线程结果。
// 4. 写 affinity 前先读当前 Cpus_allowed_list，相同则跳过，避免重复抢系统调度配置。
// 5. 写入后再读回一次，用于发现移植系统/厂商服务把线程绑核抢写回去的情况。
const VERSION: &str = "1.7.9";
const DEFAULT_CONFIG: &str = "/data/adb/modules/AppOpt/config/applist.conf";
const DEFAULT_UID_MAP: &str = "/data/adb/modules/AppOpt/config/package_uid.map";
const RULE_HEALTH_FILE: &str = "/data/adb/modules/AppOpt/config/rule_health.tsv";
const FOREGROUND_TASK_STATE_FILE: &str = "/data/adb/modules/AppOpt/config/foreground_task.state";
const PROCESS_CACHE_FILE: &str = "/data/adb/modules/AppOpt/config/pid_cache.tsv";
const PROCESS_INDEX_MAGIC: &str = "APPOPT_PROCESS_INDEX_V1";
const BOOT_ID_FILE: &str = "/proc/sys/kernel/random/boot_id";
const FOREGROUND_TASK_MAX_AGE_MS: u64 = 12_000;
const RULE_HEALTH_OBSERVE_SECS: u64 = 30;
const ANDROID_UID_USER_RANGE: u32 = 100_000;
const BASE_CPUSET: &str = "/dev/cpuset/AppOptRs";
const DEFAULT_INTERVAL_SECS: u64 = 2;
const FULL_RESCAN_MAX_MS: u64 = 60_000;
const PID_SNAPSHOT_ACTIVE_MS: u64 = 2_000;
const PID_SNAPSHOT_IDLE_MS: u64 = 10_000;
const PID_DISCOVERY_RETRY_MS: u64 = 6_000;
const PID_SNAPSHOT_LOG_INTERVAL_MS: u64 = 30_000;
const RULE_HEALTH_FULL_SCAN_RETRY_MS: u64 = 5_000;
const FOREGROUND_DISCOVERY_DELAY_MS: u64 = 2_000;
const FOREGROUND_DISCOVERY_COOLDOWN_MS: u64 = 10_000;
const BOOT_ID_READ_RETRY_MS: u64 = 60_000;
const ROUND_SUMMARY_EVERY: u64 = 30;
const MAX_ERROR_DETAILS_PER_ROUND: usize = 3;
const CPU_MASK_WORDS: usize = 16;
const MAX_CONFIG_OWNER_BYTES: usize = 127;
const MAX_CONFIG_THREAD_BYTES: usize = 31;
static CURRENT_BOOT_ID: OnceLock<String> = OnceLock::new();
static BOOT_ID_RETRY_AFTER_ELAPSED_MS: AtomicU64 = AtomicU64::new(0);
#[cfg(any(target_os = "android", target_os = "linux"))]
const DAEMON_SOCKET_NAME: &str = "appopt_daemon_top.suto.appopt_v1";
#[cfg(any(target_os = "android", target_os = "linux"))]
const DAEMON_SOCKET_PING_PREFIX: &str = "appopt.ping top.suto.appopt v1";
#[cfg(any(target_os = "android", target_os = "linux"))]
const DAEMON_SOCKET_CALLBACK: &str = "appopt.callback top.suto.appopt v1";
// 给 App 前台检测兜底使用的 cgroup 列表。这里不作为守护绑核主路径，只用于 --app-state。
// Android/ROM 命名会有差异，所以同时扫 cpuset/cpuctl 和 top-app/foreground_window。
const TOP_APP_GROUP_PATHS: [&str; 8] = [
    "/dev/cpuset/top-app/cgroup.procs",
    "/dev/cpuset/top-app/tasks",
    "/dev/cpuctl/top-app/cgroup.procs",
    "/dev/cpuctl/top-app/tasks",
    "/dev/cpuset/foreground_window/cgroup.procs",
    "/dev/cpuset/foreground_window/tasks",
    "/dev/cpuctl/foreground_window/cgroup.procs",
    "/dev/cpuctl/foreground_window/tasks",
];

#[derive(Debug, Clone)]
struct Rule {
    // owner 可以是基础包名 com.app，也可以是子进程 com.app:push。
    owner: String,
    // thread 为 Some 时表示 com.app{RenderThread}=7 这种线程规则。
    thread: Option<String>,
    cpus: String,
    // auto 规则由 App/C 校准流程占位，daemon 不直接执行。
    auto: bool,
}

impl Rule {
    fn line(&self) -> String {
        match &self.thread {
            Some(thread) => format!("{}{{{}}}={}", self.owner, thread, self.cpus),
            None => format!("{}={}", self.owner, self.cpus),
        }
    }
}

#[derive(Debug)]
struct ProcHit {
    pid: i32,
    pid_starttime: Option<u64>,
    uid: u32,
    // /proc/<pid>/cmdline 的第一段，用于区分主进程和子进程。
    cmdline: String,
    // 命中的进程级规则，主要用于日志和 --scan-once 输出。
    process_rules: Vec<String>,
    // 最终需要执行 sched_setaffinity 的线程动作。
    actions: Vec<ThreadAction>,
    scanned_threads: usize,
    // false 表示目标进程的 task/comm/starttime 扫描有缺口。正向命中仍可使用，
    // 但包含此命中的全量扫描不能作为规则健康的负向证据。
    health_scan_complete: bool,
}

#[derive(Debug, Default)]
struct ProcScanResult {
    hits: Vec<ProcHit>,
    // 根 /proc 枚举或无法归属到具体包的已知 PID 缺口。
    complete: bool,
    // 已确认 owner 的 task/comm/starttime 缺口只阻塞对应基础包的健康负向证据。
    health_incomplete_packages: BTreeSet<String>,
}

#[derive(Debug)]
struct FullScanEvidence {
    completed_at: u64,
    global_complete: bool,
    incomplete_packages: BTreeSet<String>,
}

#[derive(Debug)]
struct ThreadAction {
    tid: i32,
    tid_starttime: Option<u64>,
    name: String,
    rule: String,
    // 健康检查直接消费结构化身份，避免从用于日志展示的合并规则字符串反解析。
    rule_health_keys: Vec<String>,
    cpus: String,
    source: RuleSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuleSource {
    Process,
    Thread,
}

#[derive(Debug)]
struct Args {
    config: PathBuf,
    uid_map: PathBuf,
    scan_once: bool,
    apply_once: bool,
    version: bool,
    ping_daemon: Option<(String, String)>,
    app_state_pkg: Option<String>,
    find_pid_name: Option<String>,
    find_process_names: Vec<String>,
    target_pkg: Option<String>,
    interval_secs: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CpuMask {
    words: [u64; CPU_MASK_WORDS],
}

#[derive(Debug, Default)]
struct ApplyStats {
    applied: usize,
    skipped: usize,
    failed: usize,
    restricted: usize,
    invalid_rules: usize,
    mismatched: usize,
    cpuset_failed: usize,
}

#[derive(Debug, Default)]
struct DaemonState {
    // 已确认属于规则目标的 PID 缓存。
    // 只在配置变化、健康观察或周期到达时全量扫 /proc；平时优先复用已知 PID。
    known_pids: BTreeSet<i32>,
    // 进程发现状态保存在共享 TSV，内存只记录调度节奏，不长期保存全量 PID 快照。
    process_index_initialized: bool,
    process_index_has_candidates: bool,
    last_pid_snapshot_elapsed_ms: Option<u64>,
    last_pid_snapshot_log_elapsed_ms: Option<u64>,
    stable_pid_snapshot_rounds: u32,
    // 区分“尚未做过初始全扫”和“已经全扫但当前没有目标进程”。
    // known_pids 为空并不代表缓存未初始化，否则无目标进程时会每轮全扫 /proc。
    proc_scan_initialized: bool,
    // 使用同一次文件读取所得的内容指纹判断配置是否变化。
    last_config_key: Option<FileKey>,
    last_uid_map_key: Option<FileKey>,
    // 即使快照和缓存一直有效，也每 60 秒至多补一次全量扫，校验极端竞态或
    // PID 快照读取缺口，同时避免恢复成每轮完整读取 /proc。
    last_full_scan_elapsed_ms: Option<u64>,
    // 不完整全扫只保留正向结果，并在冷却后重试；不能把缺口当作完整缓存等待 60 秒。
    last_full_scan_attempt_elapsed_ms: Option<u64>,
    // 健康观察到期后的不完整全扫需要限频，避免按 2 秒主循环反复遍历 /proc。
    last_health_full_scan_attempt_elapsed_ms: Option<u64>,
    // sysinfo 增长只要求尽快刷新数字 PID 快照，不再触发 cmdline 全量扫描。
    proc_growth_scan_pending: bool,
    last_proc_total: Option<u64>,
    // 每个配置应用的可靠前台生命周期只触发一次进程发现全扫。
    foreground_scan_lifecycles: HashMap<String, u64>,
    last_foreground_discovery_scan_elapsed_ms: Option<u64>,
    round_index: u64,
    logged_round_once: bool,
    last_logged_known_pids: usize,
    last_logged_processes: usize,
    rule_health: HashMap<String, RuleHealthEntry>,
    rule_health_loaded: bool,
    rule_health_dirty: bool,
    health_active_packages: BTreeSet<String>,
    health_session_started: HashMap<String, u64>,
    health_session_checked: BTreeSet<String>,
    health_session_full_scan_at: HashMap<String, u64>,
    // 每个前台生命周期至多由健康检查主动触发一次全扫；不完整时等待正常扫描或下次启动。
    health_session_full_scan_attempted: BTreeSet<String>,
    health_session_lifecycle: HashMap<String, RuleHealthLifecycle>,
    // 每个观察窗口只结算开始该窗口时尚待确认的规则。应用仍在同一次前台生命周期内
    // 新增规则时，只给新增规则开启新窗口，避免旧规则在一次启动里累计两次 miss。
    health_session_eligible_keys: HashMap<String, BTreeSet<String>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuleHealthStatus {
    Pending,
    Valid,
    Missed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct RuleHealthLifecycle {
    entered_elapsed_ms: u64,
    entered_wall_ms: u64,
}

#[derive(Debug, Clone)]
struct RuleHealthEntry {
    kind: char,
    owner: String,
    target: String,
    status: RuleHealthStatus,
    miss_count: u32,
    first_observed_at: u64,
    last_matched_at: u64,
    last_checked_at: u64,
    last_checked_boot_id: String,
    last_checked_lifecycle_elapsed_ms: u64,
    rule_line: String,
}
#[derive(Debug, Default)]
struct ScanPlan {
    // Android 多用户共享同一个 appId，完整 UID 为 userId * 100000 + appId。
    // 按 appId 索引可优先缩小候选包集合，也允许工作资料/OEM 分身命中同一包名规则。
    by_app_id: BTreeMap<u32, BTreeSet<String>>,
    // 精确基础包名集合用于 appId 不同的厂商分身/isolated 进程兜底，并与 C 版行为对齐。
    all_pkgs: BTreeSet<String>,
    // 记录缺少 UID 映射的包，用于运行日志诊断；实际扫描仍通过 all_pkgs 精确兜底。
    fallback_pkgs: BTreeSet<String>,
}

impl ScanPlan {
    fn is_empty(&self) -> bool {
        self.all_pkgs.is_empty()
    }

    fn package_count(&self) -> usize {
        self.all_pkgs.len()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct FileKey {
    len: u64,
    content_hash: u64,
}

#[derive(Debug, Default)]
struct AppTopState {
    ok: bool,
    target_top_app: bool,
    target_pid: i32,
    target_pid_is_main: bool,
    scanned: usize,
    packages: Vec<String>,
}
