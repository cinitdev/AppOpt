use super::*;
pub(super) use std::collections::{BTreeMap, BTreeSet, HashMap};
pub(super) use std::env;
#[cfg(unix)]
pub(super) use std::ffi::CString;
pub(super) use std::ffi::OsStr;
pub(super) use std::fs;
pub(super) use std::io;
pub(super) use std::path::{Path, PathBuf};
pub(super) use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) use std::sync::atomic::AtomicI32;
pub(super) use std::thread;
pub(super) use std::time::{Duration, Instant, UNIX_EPOCH};

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) use std::mem;
#[cfg(unix)]
pub(super) use std::os::unix::fs::MetadataExt;

// QixiaThreads Rust 守护进程的主逻辑。
//
// 设计目标是减少长期运行时对 /proc 的全量遍历：
// 1. App/前台助手写入 package_uid.map，守护进程用 appId 缩小包名候选并以 cmdline 最终确认。
// 2. 第一次启动、配置变化和周期校验时才全量扫描 /proc。
// 3. 日常只比较数字 PID 目录快照，并复查新增 PID；命中后缓存 PID 和线程结果。
// 4. 写亲和性前先读当前 Cpus_allowed_list，相同则跳过，避免重复抢系统调度配置。
// 5. 写入后再读回一次，用于发现移植系统/厂商服务把线程绑核抢写回去的情况。
pub(super) const VERSION: &str = "2.0.2";
pub(super) const DEFAULT_CONFIG: &str = "/data/adb/modules/QixiaThreads/config/applist.conf";
pub(super) const STATE_DIR: &str = "/data/adb/modules/QixiaThreads/config/state";
pub(super) const DEFAULT_UID_MAP: &str = "/data/adb/modules/QixiaThreads/config/state/package_uid.map";
pub(super) const FOREGROUND_TASK_STATE_FILE: &str = "/data/adb/modules/QixiaThreads/config/foreground_task.state";
pub(super) const PROCESS_CACHE_FILE: &str = "/data/adb/modules/QixiaThreads/config/state/pid_cache.tsv";
pub(super) const PROCESS_INDEX_MAGIC: &str = "QIXIA_PROCESS_INDEX_V1";
pub(super) const MANAGED_TID_STATE_FILE: &str = "/data/adb/modules/QixiaThreads/config/state/managed_tids.tsv";
pub(super) const MANAGED_TID_STATE_MAGIC: &str = "QIXIA_MANAGED_TIDS_V1";
pub(super) const BOOT_ID_FILE: &str = "/proc/sys/kernel/random/boot_id";
pub(super) const ANDROID_UID_USER_RANGE: u32 = 100_000;
pub(super) const DEFAULT_CPUSET_NAME: &str = "QiXiaRs";
pub(super) const DEFAULT_INTERVAL_SECS: u64 = 2;
pub(super) const PID_SNAPSHOT_ACTIVE_MS: u64 = 2_000;
pub(super) const PID_SNAPSHOT_IDLE_MS: u64 = 10_000;
pub(super) const PID_DISCOVERY_RETRY_MS: u64 = 6_000;
pub(super) const PID_CACHE_WRITE_MIN_MS: u64 = 10_000;
pub(super) const PID_GROWTH_HINT_MIN_MS: u64 = 10_000;
pub(super) const PID_SNAPSHOT_LOG_INTERVAL_MS: u64 = 30_000;
pub(super) const SCREEN_OFF_SCAN_INTERVAL_MS: u64 = 10_000;
pub(super) const ACTIVE_FULL_SCAN_INTERVAL_MS: u64 = 60_000;
pub(super) const SCREEN_OFF_FULL_SCAN_INTERVAL_MS: u64 = 5 * 60_000;
pub(super) const ACTIVE_PROCESS_DEEP_SCAN_MS: u64 = 10_000;
pub(super) const SCREEN_OFF_PROCESS_DEEP_SCAN_MS: u64 = 30_000;
pub(super) const BACKGROUND_SCAN_BUDGET_MS: u64 = 35;
pub(super) const BACKGROUND_AFFINITY_BUDGET_MS: u64 = 15;
pub(super) const FOREGROUND_AFFINITY_VERIFY_MS: u64 = 2_000;
pub(super) const ACTIVE_BACKGROUND_AFFINITY_VERIFY_MS: u64 = 10_000;
pub(super) const SCREEN_OFF_BACKGROUND_AFFINITY_VERIFY_MS: u64 = 30_000;
pub(super) const MAX_FOREGROUND_AFFINITY_CHECKS_PER_ROUND: usize = 256;
pub(super) const MAX_BACKGROUND_AFFINITY_CHECKS_PER_ROUND: usize = 128;
pub(super) const CPUSET_RETRY_INITIAL_MS: u64 = 10_000;
pub(super) const CPUSET_RETRY_MAX_MS: u64 = 5 * 60_000;
pub(super) const RUNTIME_CHANGE_LOG_INTERVAL_MS: u64 = 30_000;
pub(super) const RUNTIME_SUMMARY_LOG_INTERVAL_MS: u64 = 5 * 60_000;
pub(super) const RULE_HEALTH_FULL_SCAN_RETRY_MS: u64 = 5_000;
pub(super) const MAX_MANAGED_TIDS: usize = 32_768;
pub(super) const MAX_ERROR_DETAILS_PER_ROUND: usize = 3;
pub(super) const CPU_MASK_WORDS: usize = 16;
pub(super) const MAX_CONFIG_OWNER_BYTES: usize = 127;
pub(super) const MAX_CONFIG_THREAD_BYTES: usize = 31;
pub(super) static SHUTDOWN_REQUESTED: AtomicBool = AtomicBool::new(false);
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) static SHUTDOWN_EVENT_FD: AtomicI32 = AtomicI32::new(-1);

#[cfg(any(target_os = "android", target_os = "linux"))]
extern "C" fn qixia_shutdown_handler(_signal: libc::c_int) {
    // 原子存储和非阻塞 eventfd 写入可安全用于异步信号处理。
    // 文件操作、线程等待及亲和性恢复仍在普通线程上下文中执行。
    request_shutdown();
}

pub(super) fn install_shutdown_handlers() -> io::Result<()> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    unsafe {
        let event_fd = libc::eventfd(0, libc::EFD_CLOEXEC | libc::EFD_NONBLOCK);
        if event_fd < 0 {
            return Err(io::Error::last_os_error());
        }
        SHUTDOWN_EVENT_FD.store(event_fd, Ordering::Release);
        let mut action: libc::sigaction = mem::zeroed();
        action.sa_sigaction = qixia_shutdown_handler as *const () as libc::sighandler_t;
        action.sa_flags = 0;
        libc::sigemptyset(&mut action.sa_mask);
        for signal in [libc::SIGTERM, libc::SIGINT, libc::SIGHUP] {
            if libc::sigaction(signal, &action, std::ptr::null_mut()) != 0 {
                let error = io::Error::last_os_error();
                SHUTDOWN_EVENT_FD.store(-1, Ordering::Release);
                libc::close(event_fd);
                return Err(error);
            }
        }
    }
    Ok(())
}

pub(crate) fn shutdown_requested() -> bool {
    SHUTDOWN_REQUESTED.load(Ordering::Relaxed)
}

#[cfg_attr(not(any(target_os = "android", target_os = "linux")), allow(dead_code))]
pub(crate) fn request_shutdown() {
    SHUTDOWN_REQUESTED.store(true, Ordering::Relaxed);
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let fd = SHUTDOWN_EVENT_FD.load(Ordering::Acquire);
        if fd >= 0 {
            let value = 1u64;
            unsafe {
                libc::write(fd, (&value as *const u64).cast(), std::mem::size_of::<u64>());
            }
        }
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn shutdown_event_fd() -> Option<i32> {
    let fd = SHUTDOWN_EVENT_FD.load(Ordering::Acquire);
    (fd >= 0).then_some(fd)
}
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) const DAEMON_SOCKET_NAME: &str = "qixia_daemon_top.qixia.threads_v1";
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) const DAEMON_SOCKET_PING_PREFIX: &str = "qixia.ping top.qixia.threads v1";
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) const DAEMON_SOCKET_CALLBACK: &str = "qixia.callback top.qixia.threads v1";
// 给 App 前台检测兜底使用的 cgroup 列表。这里不作为守护绑核主路径，只用于 --app-state。
// Android/ROM 命名会有差异，所以同时扫 cpuset/cpuctl 和 top-app/foreground_window。
pub(super) const TOP_APP_GROUP_PATHS: [&str; 8] = [
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
pub(super) struct Rule {
    // owner 可以是基础包名 com.app，也可以是子进程 com.app:push。
    pub(super) owner: String,
    // thread 为 Some 时表示 com.app{RenderThread}=7 这种线程规则。
    pub(super) thread: Option<String>,
    pub(super) cpus: String,
    // auto 规则由 App/C 校准流程占位，守护进程不直接执行。
    pub(super) auto: bool,
}

impl Rule {
    pub(super) fn line(&self) -> String {
        match &self.thread {
            Some(thread) => format!("{}{{{}}}={}", self.owner, thread, self.cpus),
            None => format!("{}={}", self.owner, self.cpus),
        }
    }
}

#[derive(Debug)]
pub(super) struct ProcHit {
    pub(super) pid: i32,
    pub(super) pid_starttime: Option<u64>,
    pub(super) uid: u32,
    // /proc/<pid>/cmdline 的第一段，用于区分主进程和子进程。
    pub(super) cmdline: String,
    // 命中的进程级规则，主要用于日志和 --scan-once 输出。
    pub(super) process_rules: Vec<String>,
    // 最终需要执行 sched_setaffinity 的线程动作。
    pub(super) actions: Vec<ThreadAction>,
    // 包含仅用于健康复核、当前不执行的规则命中。missed 规则仍需靠真实命中恢复。
    pub(super) matched_rule_health_keys: Vec<String>,
    pub(super) scanned_threads: usize,
    // false 表示目标进程的 task/comm/starttime 扫描有缺口。正向命中仍可使用，
    // 但包含此命中的全量扫描不能作为规则健康的负向证据。
    pub(super) health_scan_complete: bool,
    pub(super) thread_fingerprint: Option<ThreadSetFingerprint>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(super) struct ThreadSetFingerprint {
    pub(super) count: usize,
    pub(super) xor_hash: u64,
    pub(super) sum_hash: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct ProcessScanStamp {
    pub(super) pid_starttime: u64,
    pub(super) thread_fingerprint: ThreadSetFingerprint,
    pub(super) last_deep_scan_elapsed_ms: u64,
    pub(super) next_deep_scan_elapsed_ms: u64,
}

#[derive(Debug, Default)]
pub(super) struct ProcScanResult {
    pub(super) hits: Vec<ProcHit>,
    // 根 /proc 枚举或无法归属到具体包的已知 PID 缺口。
    pub(super) complete: bool,
    // 已确认 owner 的 task/comm/starttime 缺口只阻塞对应基础包的健康负向证据。
    pub(super) health_incomplete_packages: BTreeSet<String>,
}

#[derive(Debug)]
pub(super) struct FullScanEvidence {
    pub(super) completed_at: u64,
    pub(super) global_complete: bool,
    pub(super) incomplete_packages: BTreeSet<String>,
    // None 表示覆盖全部配置包；Some 只允许对应包消费这次负向证据。
    pub(super) scanned_packages: Option<BTreeSet<String>>,
    // 定时全扫结束时实际出现过的 owner，用于避免子进程未启动时误判其子线程规则。
    pub(super) observed_owners: BTreeSet<String>,
}

#[derive(Debug)]
pub(super) struct ThreadAction {
    pub(super) tid: i32,
    pub(super) tid_starttime: Option<u64>,
    pub(super) name: String,
    pub(super) rule: String,
    // 健康检查直接消费结构化身份，避免从用于日志展示的合并规则字符串反解析。
    pub(super) rule_health_keys: Vec<String>,
    pub(super) cpus: String,
    pub(super) source: RuleSource,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ManagedTidEntry {
    pub(super) tgid: i32,
    pub(super) tgid_starttime: Option<u64>,
    pub(super) starttime: Option<u64>,
    pub(super) last_seen_round: u64,
    pub(super) cpuset_synced: bool,
    pub(super) cpuset_failure_count: u8,
    pub(super) cpuset_retry_after_elapsed_ms: u64,
    // Android 设备通常少于 64 核；超出 64 核时保持 None 并走完整验证路径。
    pub(super) desired_mask_low64: Option<u64>,
    // 上次写入后内核实际返回的有效掩码；cpuset 收窄时可能是 desired 的子集。
    pub(super) verified_mask_low64: Option<u64>,
    pub(super) last_affinity_check_elapsed_ms: u64,
    pub(super) next_affinity_check_elapsed_ms: u64,
    // 第一次接管前的状态只保存在本次守护进程生命周期内。规则删除或健康停用时
    // 先迁回原 cpuset，再恢复原亲和性，避免线程残留在 QixiaThreads 的子组中。
    pub(super) original_mask_low64: Option<u64>,
    pub(super) original_cpuset: Option<String>,
    // true 表示上述恢复基线已经成功写入 managed_tids.tsv。只有已持久化记录
    // 才允许修改线程；写盘失败时旧记录继续工作，新记录留到下轮重试。
    pub(super) restore_persisted: bool,
    // 规则已经消失但首次恢复被 ROM 暂时拒绝时，仅重试恢复，不再应用旧规则。
    pub(super) restore_pending: bool,
    // 基于单调时间的内存重试状态；不能仅因 Android 暂时拒绝恢复，
    // 就丢弃已持久化的原始恢复基线。
    pub(super) restore_failure_count: u8,
    pub(super) restore_retry_after_elapsed_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum RuleSource {
    Process,
    Thread,
}

#[derive(Debug)]
pub(super) struct Args {
    pub(super) config: PathBuf,
    pub(super) uid_map: PathBuf,
    pub(super) cpuset_name: String,
    pub(super) scan_once: bool,
    pub(super) apply_once: bool,
    pub(super) version: bool,
    pub(super) ping_daemon: Option<(String, String)>,
    pub(super) app_state_pkg: Option<String>,
    pub(super) find_pid_name: Option<String>,
    pub(super) find_process_names: Vec<String>,
    pub(super) target_pkg: Option<String>,
    pub(super) interval_secs: u64,
}


#[derive(Debug, Default)]
pub(super) struct ApplyStats {
    pub(super) applied: usize,
    pub(super) skipped: usize,
    pub(super) failed: usize,
    pub(super) restricted: usize,
    pub(super) invalid_rules: usize,
    pub(super) mismatched: usize,
    pub(super) cpuset_failed: usize,
}

#[derive(Debug, Default)]
pub(super) struct DaemonState {
    pub(super) auto_affinity_packages: BTreeSet<String>,
    // 只有已验证的前台目标才能加快共享 PID 发现；
    // 自动分配不会填充静态规则使用的 known_pids 集合。
    pub(super) auto_affinity_foreground: bool,
    // 已确认属于规则目标的 PID 缓存。
    // 只在配置变化、健康观察或周期到达时全量扫 /proc；平时优先复用已知 PID。
    pub(super) known_pids: BTreeSet<i32>,
    // 每个已知 PID 只保留轻量身份与最近一次 TID 集合指纹，不缓存线程名或完整规则动作。
    // 亮屏每 10 秒、息屏每 30 秒校验一次指纹，集合不变时跳过完整线程扫描。
    pub(super) process_scan_stamps: HashMap<i32, ProcessScanStamp>,
    // 只缓存已经通过 UID、cmdline、TGID 和 starttime 复查后产生动作的线程。
    // 它用于避免重复写入 cpuset，不作为规则命中或线程身份的最终依据。
    pub(super) managed_tids: HashMap<i32, ManagedTidEntry>,
    // 接管前状态会先原子写入 state/managed_tids.tsv，再修改 cpuset 和亲和性。
    // 守护进程异常退出后，新实例按 boot_id 和 starttime 续接同一份恢复基线。
    pub(super) managed_tid_journal_dirty: bool,
    pub(super) managed_tid_journal_loaded: bool,
    // 最多利用前三次完整扫描补收改名前的空组，没有旧登记后立即停止检查。
    pub(super) owned_cpuset_cleanup_attempts: u8,
    // 恢复日志损坏时无法知道哪些当前线程曾被旧守护进程接管。损坏时刻之前创建的
    // 未记录线程不再接管；它们退出后，新 starttime 身份可以正常进入管理。
    pub(super) managed_tid_quarantine_before_starttime: Option<u64>,
    pub(super) affinity_verify_cursor: usize,
    // 进程索引在守护进程生命周期内常驻内存；TSV 只负责跨进程查询和重启恢复。
    // 常规轮次只枚举数字 PID，索引变化在本轮结束前批量原子写盘一次。
    pub(super) process_index: ProcessIndex,
    pub(super) process_index_initialized: bool,
    pub(super) process_index_has_candidates: bool,
    pub(super) last_pid_snapshot_elapsed_ms: Option<u64>,
    pub(super) last_pid_snapshot_log_elapsed_ms: Option<u64>,
    pub(super) last_proc_growth_scan_elapsed_ms: Option<u64>,
    // 区分“尚未做过初始全扫”和“已经全扫但当前没有目标进程”。
    // known_pids 为空并不代表缓存未初始化，否则无目标进程时会每轮全扫 /proc。
    pub(super) proc_scan_initialized: bool,
    // 使用同一次文件读取所得的内容指纹判断配置是否变化。
    pub(super) last_config_key: Option<FileKey>,
    pub(super) last_uid_map_key: Option<FileKey>,
    // 即使快照和缓存一直有效，亮屏每 60 秒、息屏每 5 分钟补一次恢复全扫，
    // 校验极端竞态或 PID 快照读取缺口，同时避免恢复成每轮完整读取 /proc。
    pub(super) last_full_scan_elapsed_ms: Option<u64>,
    // 不完整全扫只保留正向结果，并在冷却后重试；不能把缺口当作完整缓存等待 60 秒。
    pub(super) last_full_scan_attempt_elapsed_ms: Option<u64>,
    // sysinfo 增长只要求尽快刷新数字 PID 快照，不再触发 cmdline 全量扫描。
    pub(super) proc_growth_scan_pending: bool,
    pub(super) last_proc_total: Option<u64>,
    // inotify 事件只能提前检查配置，不能重置固定的常规扫描节奏。
    pub(super) last_regular_scan_elapsed_ms: Option<u64>,
    pub(super) interactive: bool,
    // 助手状态短暂过期时沿用最后一次可信亮灭屏状态。首次尚无可信值时仍按亮屏处理，
    // 避免 App 刚启动而助手状态尚未落盘时把发现周期错误放慢到息屏档。
    pub(super) interactive_known: bool,
    pub(super) round_index: u64,
    pub(super) logged_round_once: bool,
    pub(super) last_runtime_summary_log_elapsed_ms: Option<u64>,
    pub(super) last_logged_known_pids: usize,
    pub(super) last_logged_processes: usize,
    pub(super) rule_health: rule_health::RuleHealth,
    // 自动模式等其他变化也可能需要重建运行时索引。
    pub(super) runtime_rule_index_dirty: bool,
}

#[derive(Debug, Default)]
pub(super) struct ScanPlan {
    // Android 多用户共享同一个 appId，完整 UID 为 userId * 100000 + appId。
    // 按 appId 索引可优先缩小候选包集合，也允许工作资料/OEM 分身命中同一包名规则。
    pub(super) by_app_id: BTreeMap<u32, BTreeSet<String>>,
    // 精确基础包名集合用于 appId 不同的厂商分身或隔离进程兜底。
    pub(super) all_pkgs: BTreeSet<String>,
    // 记录缺少 UID 映射的包，用于运行日志诊断；实际扫描仍通过 all_pkgs 精确兜底。
    pub(super) fallback_pkgs: BTreeSet<String>,
}

#[derive(Debug, Default)]
pub(super) struct RuntimeRuleIndex {
    // 只保存原始规则表下标，避免为健康状态过滤结果重复克隆完整规则字符串。
    pub(super) active_rule_indices: Vec<usize>,
    // owner -> 原始规则表下标；三条扫描路径共同复用这一份索引。
    pub(super) rules_by_owner: HashMap<String, Vec<usize>>,
    // 健康复核包含 missed 规则，但这些下标绝不会参与亲和性动作生成。
    pub(super) health_rules_by_owner: HashMap<String, Vec<usize>>,
    // 存在线程/子进程健康规则的基础包，避免每扫描一个进程都遍历全部 owner。
    pub(super) health_rule_packages: BTreeSet<String>,
    pub(super) plan: ScanPlan,
}

impl ScanPlan {
    pub(super) fn is_empty(&self) -> bool {
        self.all_pkgs.is_empty()
    }

    pub(super) fn package_count(&self) -> usize {
        self.all_pkgs.len()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct FileKey {
    pub(super) len: u64,
    pub(super) content_hash: u64,
}

#[derive(Debug, Default)]
pub(super) struct AppTopState {
    pub(super) ok: bool,
    pub(super) target_top_app: bool,
    pub(super) target_pid: i32,
    pub(super) target_pid_is_main: bool,
    pub(super) scanned: usize,
    pub(super) packages: Vec<String>,
}
