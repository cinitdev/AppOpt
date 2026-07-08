#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <limits.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysinfo.h>
#include <sys/time.h>
#include <sys/un.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <linux/android/binder.h>   /* 直连 binder 取 SF dump(免 fork dumpsys) */
#include <sys/system_properties.h>
#define APPOPT_HAVE_BINDER 1
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif
#ifndef MSG_DONTWAIT
#define MSG_DONTWAIT 0
#endif
/* 注: 不定义 BINDER_IPC_32BIT, binder_uintptr_t/size_t 在 32/64 位均为 __u64,
 * 结构体布局一致 -> 同一套代码可跨 ABI。现代 Android(12-16)内核均为 64 位
 * binder ABI(即便进程是 32 位)。极罕见的真 32 位内核上事务会失败, 自动回退
 * CLI dumpsys, 不会崩。 */

/* fps_monitor 模块: eBPF uprobe + timestats 回退 */
#include "ebpf_fps.h"
#include "fps_fallback.h"
#include "foreground_monitor.h"

#define VERSION            "1.7.6"
#define BASE_CPUSET        "/dev/cpuset/AppOpt"
#define MAX_PKG_LEN        128
#define MAX_THREAD_LEN     32
#define MAX_CLUSTERS       8
#define MODULE_DIR         "/data/adb/modules/AppOpt"
#define CONFIG_DIR         MODULE_DIR "/config"
#define LOG_DIR            MODULE_DIR "/logs"
#define EBPF_DIR           CONFIG_DIR "/ebpf"
#define CALIB_CMD_FILE     CONFIG_DIR "/calibrate.cmd"
#define CALIB_STATE_FILE   CONFIG_DIR "/calibrate.state"
#define CALIB_POLICY_FILE  CONFIG_DIR "/calib_policy.conf"
#define CALIB_POLICY_LOCK  CONFIG_DIR "/calib_policy.conf.lock"
#define CALIB_CONFIG_LOCK  CONFIG_DIR "/applist.conf.lock"
#define HISTORY_DIR        MODULE_DIR "/history"

/* ---- 真实帧率(FPS)监测 ----
 * App 写 FPS_CMD_FILE: "start <pkg> [socket token]" / "stop"  通知守护开/关监测
 * 策略: eBPF uprobe(优先) -> SurfaceFlinger --latency -> --timestats
 *   eBPF: 在目标进程 libgui::queueBuffer 挂 uprobe,逐帧精度
 *   SF fallback: binder 直连读取 SurfaceFlinger 帧时间戳,必要时切 timestats
 * 守护优先把每秒算出的 FPS 推到 App 创建的 Android 本地 socket;
 * socket 不可用时再覆盖写 app 私有目录(下方), 由 App 侧 FileObserver 兜底读取。 */
#define FPS_CMD_FILE       CONFIG_DIR "/fps.cmd"
#define FPS_OUT_DIR        "/data/data/top.suto.appopt/files"
#define FPS_OUT_FILE       FPS_OUT_DIR "/fps"
#define FPS_BPF_OBJ        EBPF_DIR "/queuebuffer_probe.bpf.o"  /* eBPF 字节码 */
#define FPS_WINDOW_MS      1000           /* 出一个 FPS 的周期 */
#define FPS_EBPF_STALE_MS  2500           /* 目标 PID 长时间无帧时不继续推旧 FPS */
#define FPS_EBPF_RESTART_COOLDOWN_MS 3000 /* PID 变化后重启 eBPF 的最小间隔 */
#define FPS_SOCKET_NAME_MAX 96
#define FPS_SOCKET_TOKEN_MAX 64
#define DAEMON_SOCKET_NAME "appopt_daemon_top.suto.appopt_v1"
#define DAEMON_SOCKET_PING_PREFIX "appopt.ping top.suto.appopt v1"
#define DAEMON_SOCKET_CALLBACK "appopt.callback top.suto.appopt v1"
#define CALIB_MIN_ROUNDS 60
#define CALIB_MAX_SAMPLES 2048
#define CALIB_MAX_TRACKED_TIDS 4096
#define CALIB_MAX_CHILD_THREAD_SUMMARIES 1024
#define CALIB_MAX_SERIES_POINTS 1200

typedef struct {
    char pkg[MAX_PKG_LEN];
    char thread[MAX_THREAD_LEN];
    char cpuset_dir[256];
    cpu_set_t cpus;
} AffinityRule;

typedef struct {
    pid_t tid;
    char name[MAX_THREAD_LEN];
    char cpuset_dir[256];
    cpu_set_t cpus;
} ThreadInfo;

typedef struct {
    pid_t pid;
    char pkg[MAX_PKG_LEN];
    char base_cpuset[128];
    cpu_set_t base_cpus;
    ThreadInfo* threads;
    size_t num_threads;
    size_t threads_cap;
    AffinityRule** thread_rules;
    size_t num_thread_rules;
    size_t thread_rules_cap;
} ProcessInfo;

typedef struct {
    int first_cpu;          /* 簇内第一个逻辑 CPU 编号 */
    int last_cpu;           /* 簇内最后一个逻辑 CPU 编号(连续假设) */
    unsigned long max_freq; /* 该簇 cpuinfo_max_freq (kHz) */
    cpu_set_t cpus;         /* 该簇包含的 CPU 集合 */
} CpuCluster;

typedef struct {
    cpu_set_t present_cpus;
    char present_str[128];
    char mems_str[32];
    bool cpuset_enabled;
    int base_cpuset_fd;
    /* 自动识别的性能档位。字段名保留 little/middle/big 是为了少改调用点;
     * 对外日志与策略使用更中性的低性能/主性能/高性能/最高性能描述。 */
    CpuCluster clusters[MAX_CLUSTERS];
    int num_clusters;       /* 按 max_freq 升序排列后的簇数量 */
    char little_str[64];    /* 最低频性能簇,如 "0-2" */
    char middle_str[64];    /* 主性能范围: 排除最高频簇后的主要性能核心,如 "3-6" */
    char big_str[64];       /* 最高频性能簇/Prime 簇,如 "7" 或 "6-7" */
    char all_str[64];       /* 全部核心范围,如 "0-7" */
    char nonbig_str[64];    /* 非最高频簇范围,如 "0-6", 用作进程级兜底 */
    char middle_high_str[64];  /* 非最高频范围中的高性能部分,如 "5-6" */
    char middle_low_str[64];   /* 非最高频范围中的低性能部分,如 "3-4" */
    char base_str[64];         /* 更保守兜底范围,如 "0-4", 用于更严格的隔离 */
} CpuTopology;

typedef struct {
    atomic_int ref_count;
    AffinityRule* rules;
    size_t num_rules;
    struct timespec mtime;
    CpuTopology topo;
    char** pkgs;
    size_t num_pkgs;
    char** auto_pkgs;       /* 配置为 =auto、等待校准的包名 */
    size_t num_auto_pkgs;
    char config_file[4096];
} AppConfig;

typedef struct {
    ProcessInfo* procs;
    size_t num_procs;
    size_t procs_cap;
    int last_proc_count;
    bool scan_all_proc;
    pid_t* tracked_pids;
    size_t num_tracked_pids;
    size_t tracked_pids_cap;
    int last_proc_total;
} ProcCache;

static atomic_int config_updated = ATOMIC_VAR_INIT(0);
static int inotify_fd = -1;
static int inotify_wd = -1;
static int inotify_supported = 0;
static _Atomic(AppConfig*) current_config = NULL;
/* 串行化"读方获取引用(load+ref++)"与"写方换出 current_config", 消除 split-refcount
 * UAF: 否则 get_config 在 load 之后、ref++ 之前, loader 线程可能换走并 release 到 0
 * 释放该 cfg, ref++ 即写已 free 内存。加锁后读方自增要么排在换出前(计数已 >=2,
 * 后续 release 不归零), 要么排在换出后(读到新 cfg)。 */
static pthread_mutex_t config_swap_lock = PTHREAD_MUTEX_INITIALIZER;
static CpuTopology g_topo;                 /* 校准线程使用的全局拓扑快照 */
static char g_config_file[4096] = "";      /* 校准线程回写用的配置文件路径 */
static volatile sig_atomic_t shutdown_requested = 0;

#include "core.c"
#include "config.c"
#include "calibration.c"
#include "surfaceflinger.c"
#include "daemon_socket.c"
#include "fps.c"
#include "daemon_main.c"
