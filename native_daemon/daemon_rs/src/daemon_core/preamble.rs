use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::env;
#[cfg(unix)]
use std::ffi::CString;
use std::ffi::OsStr;
use std::fs;
use std::io;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant, UNIX_EPOCH};

#[cfg(any(target_os = "android", target_os = "linux"))]
use std::mem;
#[cfg(unix)]
use std::os::unix::fs::MetadataExt;

// AppOpt Rust 守护进程的主逻辑。
//
// 设计目标不是把 C 版简单翻译成 Rust，而是减少长期运行时对 /proc 的全量遍历：
// 1. App/前台 helper 通过 PackageManager 写入 package_uid.map，daemon 先用 UID 过滤候选进程。
// 2. 第一次启动、配置变化、缓存失效时才全量扫描 /proc。
// 3. 找到目标进程后缓存 PID，后续优先只扫描这些 PID 的 task 目录。
// 4. 写 affinity 前先读当前 Cpus_allowed_list，相同则跳过，避免重复抢系统调度配置。
// 5. 写入后再读回一次，用于发现移植系统/厂商服务把线程绑核抢写回去的情况。
const VERSION: &str = "1.7.6";
const DEFAULT_CONFIG: &str = "/data/adb/modules/AppOpt/config/applist.conf";
const DEFAULT_UID_MAP: &str = "/data/adb/modules/AppOpt/config/package_uid.map";
const BASE_CPUSET: &str = "/dev/cpuset/AppOptRs";
const DEFAULT_INTERVAL_SECS: u64 = 2;
const FULL_RESCAN_ROUNDS: u32 = 30;
const ROUND_SUMMARY_EVERY: u64 = 30;
const MAX_ERROR_DETAILS_PER_ROUND: usize = 3;
const CPU_MASK_WORDS: usize = 16;
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
    uid: u32,
    // /proc/<pid>/cmdline 的第一段，用于区分主进程和子进程。
    cmdline: String,
    // 命中的进程级规则，主要用于日志和 --scan-once 输出。
    process_rules: Vec<String>,
    // 最终需要执行 sched_setaffinity 的线程动作。
    actions: Vec<ThreadAction>,
    scanned_threads: usize,
}

#[derive(Debug)]
struct ThreadAction {
    tid: i32,
    name: String,
    rule: String,
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
    // 只在配置变化、PID 缓存失效或周期到达时全量扫 /proc；平时优先复用已知 PID。
    known_pids: BTreeSet<i32>,
    // 用 len + mtime 判断配置文件是否变化，避免每轮都重新解释为“新配置”。
    last_config_key: Option<FileKey>,
    last_uid_map_key: Option<FileKey>,
    // 即使缓存一直有效，也周期性全量扫一次，处理 App 新拉起子进程但缓存未覆盖的情况。
    rounds_since_full_scan: u32,
    // 用系统进程总数做轻量触发器：进程数增长时主动全量扫，避免等周期扫描才发现新启动的 App。
    last_proc_total: Option<u64>,
    round_index: u64,
    logged_round_once: bool,
    last_logged_known_pids: usize,
    last_logged_processes: usize,
}

#[derive(Debug, Default)]
struct ScanPlan {
    // App/前台 helper 写入 package_uid.map 后，daemon 先用 UID 缩小候选进程范围。
    // 例如 com.tencent.mobileqq=10607，则 /proc 里 UID 不是 10607 的进程不再读 cmdline。
    by_uid: BTreeMap<u32, BTreeSet<String>>,
    // 没有 UID 映射的包仍保留 cmdline 兜底，避免旧配置、App 未同步 UID 或权限异常时完全失效。
    // 这个兜底会比 UID 路径多读一些 cmdline，但只针对没有映射的包。
    fallback_pkgs: BTreeSet<String>,
}

impl ScanPlan {
    fn is_empty(&self) -> bool {
        self.by_uid.is_empty() && self.fallback_pkgs.is_empty()
    }

    fn package_count(&self) -> usize {
        let mut pkgs = BTreeSet::new();
        for names in self.by_uid.values() {
            pkgs.extend(names.iter().cloned());
        }
        pkgs.extend(self.fallback_pkgs.iter().cloned());
        pkgs.len()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct FileKey {
    len: u64,
    modified_ms: u128,
}

#[derive(Debug, Default)]
struct AppTopState {
    ok: bool,
    target_top_app: bool,
    target_pid: i32,
    scanned: usize,
    packages: Vec<String>,
}
