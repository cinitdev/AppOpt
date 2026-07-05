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

#define VERSION            "1.7.5"
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

static void handle_shutdown_signal(int sig) {
    shutdown_requested = sig;
}

static char* strtrim(char* s) {
    char* end;
    /* isspace 仅对 unsigned char 与 EOF 有定义; char 可能有符号, 非 ASCII 字节
     * (如包名/层名/配置里的 UTF-8)转成负 int 即 UB, 故统一转 unsigned char。 */
    while (isspace((unsigned char)*s)) s++;
    if (*s == 0) return s;
    end = s + strlen(s) - 1;
    while (end > s && isspace((unsigned char)*end)) end--;
    *(end + 1) = 0;
    return s;
}

static bool read_file(int dir_fd, const char* filename, char* buf, size_t buf_size) {
    if (!filename || !buf || buf_size == 0) return false;
    int fd = openat(dir_fd, filename, O_RDONLY | O_CLOEXEC);
    if (fd == -1) return false;
    ssize_t n = read(fd, buf, buf_size - 1);
    close(fd);
    if (n <= 0) return false;
    buf[n] = '\0';
    return true;
}

static bool write_file(int dir_fd, const char* filename, const char* content, int flags) {
    if (!filename || !content) return false;
    int fd = openat(dir_fd, filename, flags | O_CLOEXEC, 0644);
    if (fd == -1) return false;
    size_t len = strlen(content);
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, content + off, len - off);
        if (n > 0) {
            off += (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        close(fd);
        return false;
    }
    close(fd);
    return true;
}

static int get_prop_first(char* out, size_t out_size, const char* const* keys, size_t key_count) {
    if (!out || out_size == 0) {
        return 0;
    }
    out[0] = '\0';
    for (size_t i = 0; i < key_count; i++) {
        char value[PROP_VALUE_MAX];
        if (__system_property_get(keys[i], value) > 0 && value[0] != '\0') {
            snprintf(out, out_size, "%s", value);
            return 1;
        }
    }
    return 0;
}

static int build_str(char *dest, size_t dest_size, ...) {
    if (!dest || dest_size == 0) return 0;
    va_list args;
    const char *segment;
    char *p = dest;
    size_t remaining = dest_size - 1;
    va_start(args, dest_size);
    while ((segment = va_arg(args, const char *)) != NULL) {
        size_t len = strlen(segment);
        if (len > remaining) {
            *p = '\0';
            va_end(args);
            return 0;
        }
        memcpy(p, segment, len);
        p += len;
        remaining -= len;
    }
    *p = '\0';
    va_end(args);
    return 1;
}

static bool stat_mtime_equal(const struct stat* st, const struct timespec* ts) {
    return st && ts && st->st_mtim.tv_sec == ts->tv_sec &&
           st->st_mtim.tv_nsec == ts->tv_nsec;
}

static bool stat_same_size_mtime(const struct stat* a, const struct stat* b) {
    return a && b && a->st_size == b->st_size &&
           a->st_mtim.tv_sec == b->st_mtim.tv_sec &&
           a->st_mtim.tv_nsec == b->st_mtim.tv_nsec;
}

static long long monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static bool file_mtime_recent(const struct stat* st) {
    if (!st) return false;
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0) return false;
    if (st->st_mtim.tv_sec > now.tv_sec) return true;
    time_t ds = now.tv_sec - st->st_mtim.tv_sec;
    if (ds <= 0) return true;
    return ds == 1 && st->st_mtim.tv_nsec > now.tv_nsec;
}

static void safe_history_filename(const char* pkg, char* out, size_t out_sz) {
    if (!out || out_sz == 0) return;
    out[0] = '\0';
    if (!pkg || !pkg[0]) {
        build_str(out, out_sz, "unknown", NULL);
        return;
    }

    size_t j = 0;
    for (size_t i = 0; pkg[i] && j + 1 < out_sz; i++) {
        unsigned char c = (unsigned char)pkg[i];
        if (isalnum(c) || c == '.' || c == '_' || c == '-' || c == ':') {
            out[j++] = (char)c;
        } else {
            out[j++] = '_';
        }
    }
    out[j] = '\0';

    if (j == 0 || strcmp(out, ".") == 0 || strcmp(out, "..") == 0) {
        build_str(out, out_sz, "unknown", NULL);
    }
}

static bool read_stable_command_file(const char* path, char* cmd_buf, size_t sz,
                                     bool (*is_valid)(const char*)) {
    if (!path || !cmd_buf || sz == 0 || !is_valid) return false;
    cmd_buf[0] = '\0';

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd == -1) return false;

    struct stat before, after;
    if (fstat(fd, &before) != 0) {
        close(fd);
        return false;
    }

    ssize_t n = read(fd, cmd_buf, sz - 1);
    if (fstat(fd, &after) != 0) {
        close(fd);
        return false;
    }
    close(fd);

    if (n <= 0) {
        if (!file_mtime_recent(&after)) unlink(path);
        return false;
    }

    cmd_buf[n] = '\0';
    char* trimmed = strtrim(cmd_buf);
    if (trimmed != cmd_buf) {
        memmove(cmd_buf, trimmed, strlen(trimmed) + 1);
    }

    bool stable = stat_same_size_mtime(&before, &after);
    bool complete = after.st_size >= 0 && (off_t)n >= after.st_size;
    if (!stable) {
        return false;
    }
    if (!complete) {
        if (!file_mtime_recent(&after)) unlink(path);
        return false;
    }

    if (!is_valid(cmd_buf)) {
        if (!file_mtime_recent(&after)) unlink(path);
        return false;
    }

    unlink(path);
    return true;
}

static void parse_cpu_ranges(const char* spec, cpu_set_t* set, const cpu_set_t* present) {
    if (!spec) return;
    char* copy = strdup(spec);
    if (!copy) return;
    char* s = copy;

    while (*s) {
        char* end;
        unsigned long a = strtoul(s, &end, 10);
        if (end == s) {
            s++;
            continue;
        }

        unsigned long b = a;
        if (*end == '-') {
            s = end + 1;
            b = strtoul(s, &end, 10);
            if (end == s) b = a;
        }

        if (a > b) { unsigned long t = a; a = b; b = t; }
        for (unsigned long i = a; i <= b && i < CPU_SETSIZE; i++) {
            if (present && !CPU_ISSET(i, present)) continue;
            CPU_SET(i, set);
        }

        s = (*end == ',') ? end + 1 : end;
    }
    free(copy);
}

static char* cpu_set_to_str(const cpu_set_t *set) {
    size_t buf_size = 8 * CPU_SETSIZE;
    char *buf = malloc(buf_size);
    if (!buf) return NULL;
    int start = -1, end = -1;
    char *p = buf;
    size_t remain = buf_size - 1;
    bool first = true;

    for (int i = 0; i < CPU_SETSIZE; i++) {
        if (CPU_ISSET(i, set)) {
            if (start == -1) {
                start = end = i;
            } else if (i == end + 1) {
                end = i;
            } else {
                int needed;
                if (start == end) {
                    needed = snprintf(p, remain + 1, "%s%d", first ? "" : ",", start);
                } else {
                    needed = snprintf(p, remain + 1, "%s%d-%d", first ? "" : ",", start, end);
                }
                if (needed < 0 || (size_t)needed > remain) {
                    free(buf);
                    return NULL;
                }
                p += needed;
                remain -= needed;
                start = end = i;
                first = false;
            }
        }
    }
    if (start != -1) {
        int needed;
        if (start == end) {
            needed = snprintf(p, remain + 1, "%s%d", first ? "" : ",", start);
        } else {
            needed = snprintf(p, remain + 1, "%s%d-%d", first ? "" : ",", start, end);
        }
        if (needed < 0 || (size_t)needed > remain) {
            free(buf);
            return NULL;
        }
        p += needed;
    }
    *p = '\0';
    return buf;
}

static bool create_cpuset_dir(const char *path, const char *cpus, const char *mems) {
    if (mkdir(path, 0755) != 0 && errno != EEXIST) return false;
    if (chmod(path, 0755) != 0) return false;
    if (chown(path, 0, 0) != 0) return false;

    char cpus_path[256];
    build_str(cpus_path, sizeof(cpus_path), path, "/cpus", NULL);
    if (!write_file(AT_FDCWD, cpus_path, cpus, O_WRONLY | O_CREAT | O_TRUNC)) return false;

    char mems_path[256];
    build_str(mems_path, sizeof(mems_path), path, "/mems", NULL);
    return write_file(AT_FDCWD, mems_path, mems, O_WRONLY | O_CREAT | O_TRUNC);
}

/*
 * 通过 sysfs 读取每个在线 CPU 的 cpuinfo_max_freq, 按最大频率把相同频率的
 * 连续核心聚成簇, 再按频率升序排列。最低频簇作为低性能档, 最高频簇作为
 * 最高性能/Prime 档, 其余与低性能档共同组成非最高性能池。
 * 这样可兼容 3 簇、4 簇、2 簇等 SoC, 不强行假设一定存在“小/中/大核”。
 */
static void classify_topology(CpuTopology* topo) {
    topo->num_clusters = 0;
    topo->little_str[0] = topo->middle_str[0] = topo->big_str[0] = topo->all_str[0] = '\0';
    topo->nonbig_str[0] = topo->middle_high_str[0] = topo->middle_low_str[0] = topo->base_str[0] = '\0';

    /* 收集每个在线 CPU 的最大频率 */
    unsigned long freq[CPU_SETSIZE];
    int cpu_list[CPU_SETSIZE];
    int n = 0;
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (!CPU_ISSET(cpu, &topo->present_cpus)) continue;
        char path[128], buf[32];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        unsigned long f = 0;
        if (read_file(AT_FDCWD, path, buf, sizeof(buf))) {
            strtrim(buf);
            f = strtoul(buf, NULL, 10);
        }
        cpu_list[n] = cpu;
        freq[n] = f;
        n++;
    }
    if (n == 0) return;

    /* 把连续且同频的 CPU 聚成一个簇 (按 CPU 编号天然升序遍历) */
    for (int i = 0; i < n; ) {
        if (topo->num_clusters >= MAX_CLUSTERS) break;
        CpuCluster* c = &topo->clusters[topo->num_clusters];
        CPU_ZERO(&c->cpus);
        c->max_freq = freq[i];
        c->first_cpu = cpu_list[i];
        c->last_cpu = cpu_list[i];
        CPU_SET(cpu_list[i], &c->cpus);
        int j = i + 1;
        while (j < n && freq[j] == freq[i] && cpu_list[j] == cpu_list[j - 1] + 1) {
            c->last_cpu = cpu_list[j];
            CPU_SET(cpu_list[j], &c->cpus);
            j++;
        }
        topo->num_clusters++;
        i = j;
    }

    /* 按 max_freq 升序对簇排序 (簇数极少, 冒泡足够) */
    for (int a = 0; a < topo->num_clusters - 1; a++) {
        for (int b = 0; b < topo->num_clusters - 1 - a; b++) {
            if (topo->clusters[b].max_freq > topo->clusters[b + 1].max_freq) {
                CpuCluster t = topo->clusters[b];
                topo->clusters[b] = topo->clusters[b + 1];
                topo->clusters[b + 1] = t;
            }
        }
    }

    /* 全部核心范围 */
    char* all = cpu_set_to_str(&topo->present_cpus);
    if (all) {
        build_str(topo->all_str, sizeof(topo->all_str), all, NULL);
        free(all);
    }

    int nc = topo->num_clusters;
    if (nc == 1) {
        /* 单一簇: 所有档位都只能用全部核心 */
        build_str(topo->little_str, sizeof(topo->little_str), topo->all_str, NULL);
        build_str(topo->middle_str, sizeof(topo->middle_str), topo->all_str, NULL);
        build_str(topo->big_str, sizeof(topo->big_str), topo->all_str, NULL);
        /* 无大小核之分, 兜底只能用全部核心 */
        build_str(topo->nonbig_str, sizeof(topo->nonbig_str), topo->all_str, NULL);
        build_str(topo->middle_high_str, sizeof(topo->middle_high_str), topo->all_str, NULL);
        build_str(topo->middle_low_str, sizeof(topo->middle_low_str), topo->all_str, NULL);
        build_str(topo->base_str, sizeof(topo->base_str), topo->all_str, NULL);
    } else {
        /* 最低频 = 低性能档, 最高频 = 最高性能/Prime 档 */
        char* lo = cpu_set_to_str(&topo->clusters[0].cpus);
        char* hi = cpu_set_to_str(&topo->clusters[nc - 1].cpus);
        if (lo) { build_str(topo->little_str, sizeof(topo->little_str), lo, NULL); free(lo); }
        if (hi) { build_str(topo->big_str, sizeof(topo->big_str), hi, NULL); free(hi); }

        /* 主性能范围 = 除首尾外所有簇的并集; 只有两簇时退化为低性能/非最高性能范围 */
        cpu_set_t mid;
        CPU_ZERO(&mid);
        for (int k = 1; k < nc - 1; k++) CPU_OR(&mid, &mid, &topo->clusters[k].cpus);
        if (CPU_COUNT(&mid) == 0) {
            build_str(topo->middle_str, sizeof(topo->middle_str), topo->little_str, NULL);
        } else {
            char* m = cpu_set_to_str(&mid);
            if (m) { build_str(topo->middle_str, sizeof(topo->middle_str), m, NULL); free(m); }
        }

        /* 主性能范围只按频率簇细分, 同频簇不能按 CPU 编号硬切。 */
        cpu_set_t mid_high, mid_low;
        CPU_ZERO(&mid_high);
        CPU_ZERO(&mid_low);
        if (nc >= 3) {
            CPU_OR(&mid_high, &mid_high, &topo->clusters[nc - 2].cpus);
            if (nc >= 4) {
                for (int k = 1; k < nc - 2; k++) {
                    CPU_OR(&mid_low, &mid_low, &topo->clusters[k].cpus);
                }
            } else {
                CPU_OR(&mid_low, &mid_low, &mid_high);
            }
        } else {
            CPU_OR(&mid_high, &mid_high, &topo->clusters[0].cpus);
            CPU_OR(&mid_low, &mid_low, &topo->clusters[0].cpus);
        }
        char* mh = cpu_set_to_str(&mid_high);
        char* ml = cpu_set_to_str(&mid_low);
        if (mh) { build_str(topo->middle_high_str, sizeof(topo->middle_high_str), mh, NULL); free(mh); }
        if (ml) { build_str(topo->middle_low_str, sizeof(topo->middle_low_str), ml, NULL); free(ml); }

        /* 兜底范围 = 全部核心 - 最高频/Prime 簇。
         * 这样进程整体与未点名的杂线程都不占用最高性能核心, 把最高性能核心留给
         * 被显式绑定的重载线程, 避免杂线程争抢/迁移污染最高性能核心。 */
        cpu_set_t nonbig;
        CPU_ZERO(&nonbig);
        for (int k = 0; k < nc - 1; k++) CPU_OR(&nonbig, &nonbig, &topo->clusters[k].cpus);
        if (CPU_COUNT(&nonbig) == 0) {
            build_str(topo->nonbig_str, sizeof(topo->nonbig_str), topo->all_str, NULL);
        } else {
            char* nb = cpu_set_to_str(&nonbig);
            if (nb) { build_str(topo->nonbig_str, sizeof(topo->nonbig_str), nb, NULL); free(nb); }
        }

        /* base 兜底(低性能+主性能低段): 用于最轻量线程,进一步排除主性能高段和最高性能核心。
         * 如果主性能范围未细分,则 base == nonbig。 */
        cpu_set_t base;
        CPU_ZERO(&base);
        if (nc >= 4) {
            for (int k = 0; k < nc - 2; k++) {
                CPU_OR(&base, &base, &topo->clusters[k].cpus);
            }
        } else {
            for (int k = 0; k < nc - 1; k++) {
                CPU_OR(&base, &base, &topo->clusters[k].cpus);
            }
        }
        if (CPU_COUNT(&base) == 0) CPU_OR(&base, &base, &topo->present_cpus);
        char* bs = cpu_set_to_str(&base);
        if (bs) { build_str(topo->base_str, sizeof(topo->base_str), bs, NULL); free(bs); }
    }

    printf("CPU 拓扑识别: %d 个性能簇:\n全部=[%s] 低性能=[%s] 主性能=[%s] 高性能=[%s] 最高性能=[%s] 非最高=[%s]\n",
           nc, topo->all_str, topo->little_str, topo->middle_str,
           topo->middle_high_str, topo->big_str, topo->nonbig_str);
}

static CpuTopology init_cpu_topo(void) {
    CpuTopology topo = { .cpuset_enabled = false, .base_cpuset_fd = -1 };
    CPU_ZERO(&topo.present_cpus);

    if (read_file(AT_FDCWD, "/sys/devices/system/cpu/present", topo.present_str, sizeof(topo.present_str))) {
        strtrim(topo.present_str);
    }
    parse_cpu_ranges(topo.present_str, &topo.present_cpus, NULL);

    classify_topology(&topo);

    if (access("/dev/cpuset", F_OK) != 0) return topo;

    if (create_cpuset_dir(BASE_CPUSET, topo.present_str, "0")) {
        topo.base_cpuset_fd = open(BASE_CPUSET, O_RDONLY | O_DIRECTORY);
        if (topo.base_cpuset_fd != -1) topo.cpuset_enabled = true;
    }

    char mems_path[256];
    build_str(mems_path, sizeof(mems_path), BASE_CPUSET, "/mems", NULL);
    if (!read_file(AT_FDCWD, mems_path, topo.mems_str, sizeof(topo.mems_str))) {
        build_str(topo.mems_str, sizeof(topo.mems_str), "0", NULL);
    } else {
        strtrim(topo.mems_str);
    }

    return topo;
}

static AppConfig* load_config(const char* config_file, const CpuTopology* topo, struct timespec* last_mtime) {
    struct stat st;
    if (stat(config_file, &st)) return NULL;
    AppConfig* cfg = calloc(1, sizeof(AppConfig));
    if (!cfg) return NULL;
    cfg->ref_count = 1;
    cfg->topo = *topo;
    build_str(cfg->config_file, sizeof(cfg->config_file), config_file, NULL);

    if (last_mtime && last_mtime->tv_sec != -1 && stat_mtime_equal(&st, last_mtime)) {
        free(cfg);
        return NULL;
    }

    FILE* fp = fopen(config_file, "r");
    if (!fp) {
        free(cfg);
        return NULL;
    }

    AffinityRule* new_rules = NULL;
    char** new_pkgs = NULL;
    char** new_auto = NULL;
    size_t rules_cnt = 0, pkgs_cnt = 0, auto_cnt = 0;
    char* line = NULL;
    size_t line_cap = 0;

    while (getline(&line, &line_cap, fp) != -1) {
        char* p = strtrim(line);
        if (*p == '#' || !*p) continue;

        char* eq = strchr(p, '=');
        if (!eq) continue;
        *eq++ = 0;

        char* br = strchr(p, '{');
        char* thread = "";
        if (br) {
            *br++ = 0;
            char* eb = strchr(br, '}');
            if (!eb) continue;
            *eb = 0;
            thread = strtrim(br);
        }

        char* pkg = strtrim(p);
        char* cpus = strtrim(eq);
        if (strlen(pkg) >= MAX_PKG_LEN || strlen(thread) >= MAX_THREAD_LEN) continue;

        /* 包名=auto: 标记为待校准, 不生成绑核规则, 但仍需被进程扫描发现 */
        if (!thread[0] && strcmp(cpus, "auto") == 0) {
            bool aexists = false;
            for (size_t i = 0; i < auto_cnt; i++) {
                if (strcmp(new_auto[i], pkg) == 0) { aexists = true; break; }
            }
            if (!aexists) {
                char** tmp_auto = realloc(new_auto, (auto_cnt + 1) * sizeof(char*));
                if (!tmp_auto) goto error;
                new_auto = tmp_auto;
                new_auto[auto_cnt] = strdup(pkg);
                if (!new_auto[auto_cnt]) goto error;
                auto_cnt++;
            }
            bool pexists = false;
            for (size_t i = 0; i < pkgs_cnt; i++) {
                if (strcmp(new_pkgs[i], pkg) == 0) { pexists = true; break; }
            }
            if (!pexists) {
                char** tmp_pkgs = realloc(new_pkgs, (pkgs_cnt + 1) * sizeof(char*));
                if (!tmp_pkgs) goto error;
                new_pkgs = tmp_pkgs;
                new_pkgs[pkgs_cnt] = strdup(pkg);
                if (!new_pkgs[pkgs_cnt]) goto error;
                pkgs_cnt++;
            }
            continue;
        }

        cpu_set_t set;
        CPU_ZERO(&set);
        parse_cpu_ranges(cpus, &set, &cfg->topo.present_cpus);
        if (CPU_COUNT(&set) == 0) continue;

        char* dir_name = cpu_set_to_str(&set);
        if (!dir_name) continue;

        char path[256];
        build_str(path, sizeof(path), BASE_CPUSET, "/", dir_name, NULL);
        if (!create_cpuset_dir(path, dir_name, cfg->topo.mems_str)) {
            free(dir_name);
            continue;
        }

        AffinityRule rule = {0};
        build_str(rule.pkg, sizeof(rule.pkg), pkg, NULL);
        build_str(rule.thread, sizeof(rule.thread), thread, NULL);
        build_str(rule.cpuset_dir, sizeof(rule.cpuset_dir), dir_name, NULL);
        rule.cpus = set;
        free(dir_name);

        AffinityRule* tmp_rules = realloc(new_rules, (rules_cnt + 1) * sizeof(AffinityRule));
        if (!tmp_rules) goto error;
        new_rules = tmp_rules;
        memcpy(&new_rules[rules_cnt], &rule, sizeof(AffinityRule));
        rules_cnt++;

        bool exists = false;
        if (new_pkgs != NULL) {
            for (size_t i = 0; i < pkgs_cnt; i++) {
                if (strcmp(new_pkgs[i], pkg) == 0) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            char** tmp_pkgs = realloc(new_pkgs, (pkgs_cnt + 1) * sizeof(char*));
            if (!tmp_pkgs) goto error;
            new_pkgs = tmp_pkgs;
            new_pkgs[pkgs_cnt] = strdup(pkg);
            if (!new_pkgs[pkgs_cnt]) goto error;
            pkgs_cnt++;
        }
    }

    if (cfg->rules) free(cfg->rules);
    if (cfg->pkgs) {
        for (size_t i = 0; i < cfg->num_pkgs; i++) free(cfg->pkgs[i]);
        free(cfg->pkgs);
    }

    if (last_mtime) *last_mtime = st.st_mtim;
    cfg->rules = new_rules;
    cfg->num_rules = rules_cnt;
    cfg->pkgs = new_pkgs;
    cfg->num_pkgs = pkgs_cnt;
    cfg->auto_pkgs = new_auto;
    cfg->num_auto_pkgs = auto_cnt;
    cfg->mtime = st.st_mtim;

    fclose(fp);
    free(line);
    printf("配置文件解析完成，共加载 %zu 条规则, %zu 个待校准(auto)包\n", rules_cnt, auto_cnt);
    return cfg;

    error:
    free(line);
    if (new_rules) free(new_rules);
    if (new_pkgs) {
        for (size_t i = 0; i < pkgs_cnt; i++) free(new_pkgs[i]);
        free(new_pkgs);
    }
    if (new_auto) {
        for (size_t i = 0; i < auto_cnt; i++) free(new_auto[i]);
        free(new_auto);
    }
    fclose(fp);
    free(cfg);
    return NULL;
}

static void proc_collect(const AppConfig* cfg, ProcCache* cache, size_t* count) {
    DIR* proc_dir = opendir("/proc");
    if (!proc_dir) return;
    int proc_fd = dirfd(proc_dir);
    if (proc_fd < 0) {
        closedir(proc_dir);
        return;
    }
    *count = 0;

    if (cache->procs == NULL) {
        cache->procs_cap = 2048;
        cache->procs = calloc(cache->procs_cap, sizeof(ProcessInfo));
        if (!cache->procs) {
            closedir(proc_dir);
            return;
        }
    }

    struct dirent* ent;
    time_t current_time = time(NULL);
    int current_proc_total = 0;
    while ((ent = readdir(proc_dir))) {
        char *end;
        long pid = strtol(ent->d_name, &end, 10);
        if (*end != '\0')  continue;
        current_proc_total++;

        if (!cache->scan_all_proc) {
            bool is_tracked = false;
            for (size_t i = 0; i < cache->num_tracked_pids; i++) {
                if (cache->tracked_pids[i] == pid) {
                    is_tracked = true;
                    break;
                }
            }
            if (!is_tracked) {
                struct stat statbuf;
                if (fstatat(proc_fd, ent->d_name, &statbuf, AT_SYMLINK_NOFOLLOW) != 0) continue;
                if (current_time - statbuf.st_mtime > 60) continue;
            }
        }

        int pid_fd = openat(proc_fd, ent->d_name, O_RDONLY | O_DIRECTORY);
        if (pid_fd == -1) continue;

        char cmd[MAX_PKG_LEN] = {0};
        if (!read_file(pid_fd, "cmdline", cmd, sizeof(cmd))) {
            close(pid_fd);
            continue;
        }
        char* name = strrchr(cmd, '/');
        name = name ? name + 1 : cmd;

        const char* matched_pkg = NULL;
        size_t matched_len = 0;
        for (size_t j = 0; j < cfg->num_pkgs; j++) {
            size_t plen = strlen(cfg->pkgs[j]);
            if (strcmp(name, cfg->pkgs[j]) == 0) {
                matched_pkg = cfg->pkgs[j];
                break;
            }
            if (plen > matched_len && strncmp(name, cfg->pkgs[j], plen) == 0 && name[plen] == ':') {
                matched_pkg = cfg->pkgs[j];
                matched_len = plen;
            }
        }
        if (!matched_pkg) {
            close(pid_fd);
            continue;
        }

        bool has_exact_pkg_rules = false;
        for (size_t i = 0; i < cfg->num_rules; i++) {
            if (strcmp(cfg->rules[i].pkg, name) == 0) {
                has_exact_pkg_rules = true;
                break;
            }
        }

        if (*count >= cache->procs_cap) {
            size_t new_cap = cache->procs_cap * 2;
            ProcessInfo* new_procs = realloc(cache->procs, new_cap * sizeof(ProcessInfo));
            if (!new_procs) {
                close(pid_fd);
                continue;
            }
            memset(new_procs + cache->procs_cap, 0, (new_cap - cache->procs_cap) * sizeof(ProcessInfo));
            cache->procs = new_procs;
            cache->procs_cap = new_cap;
        }

        ProcessInfo* proc = &cache->procs[*count];

        proc->pid = pid;
        build_str(proc->pkg, sizeof(proc->pkg), name, NULL);
        CPU_ZERO(&proc->base_cpus);
        proc->base_cpuset[0] = '\0';
        proc->num_threads = 0;
        proc->num_thread_rules = 0;

        if (!proc->thread_rules || proc->thread_rules_cap < 8) {
            size_t new_cap = proc->thread_rules_cap ? proc->thread_rules_cap * 2 : 8;
            AffinityRule** tmp = realloc(proc->thread_rules, new_cap * sizeof(AffinityRule*));
            if (!tmp) {
                close(pid_fd);
                continue;
            }
            proc->thread_rules = tmp;
            proc->thread_rules_cap = new_cap;
        }

        for (size_t i = 0; i < cfg->num_rules; i++) {
            const AffinityRule* rule = &cfg->rules[i];
            bool use_rule = strcmp(rule->pkg, proc->pkg) == 0;
            /* 子进程没有独立规则时, 只继承主包进程级兜底, 不继承主进程线程规则。 */
            if (!use_rule && !has_exact_pkg_rules &&
                strcmp(rule->pkg, matched_pkg) == 0 && rule->thread[0] == '\0') {
                use_rule = true;
            }
            if (!use_rule) continue;

            if (rule->thread[0]) {
                if (proc->num_thread_rules >= proc->thread_rules_cap) {
                    size_t new_cap = proc->thread_rules_cap * 2;
                    AffinityRule** tmp = realloc(proc->thread_rules, new_cap * sizeof(AffinityRule*));
                    if (!tmp) break;
                    proc->thread_rules = tmp;
                    proc->thread_rules_cap = new_cap;
                }
                proc->thread_rules[proc->num_thread_rules++] = (AffinityRule*)rule;
            } else {
                CPU_OR(&proc->base_cpus, &proc->base_cpus, &rule->cpus);
                build_str(proc->base_cpuset, sizeof(proc->base_cpuset), rule->cpuset_dir, NULL);
            }
        }

        if (CPU_COUNT(&proc->base_cpus) == 0 && proc->num_thread_rules == 0) {
            close(pid_fd);
            continue;
        }

        int task_fd = openat(pid_fd, "task", O_RDONLY | O_DIRECTORY);
        close(pid_fd);
        if (task_fd == -1) {
            continue;
        }

        DIR* task_dir = fdopendir(task_fd);
        if (!task_dir) {
            close(task_fd);
            continue;
        }

        if (!proc->threads || proc->threads_cap < 512) {
            size_t new_cap = proc->threads_cap ? proc->threads_cap * 2 : 64;
            ThreadInfo* tmp = realloc(proc->threads, new_cap * sizeof(ThreadInfo));
            if (!tmp) {
                closedir(task_dir);
                continue;
            }
            proc->threads = tmp;
            proc->threads_cap = new_cap;
        }

        struct dirent* tent;
        while ((tent = readdir(task_dir))) {
            char *end2;
            long tid = strtol(tent->d_name, &end2, 10);
            if (*end2 != '\0')  continue;
            char tname[MAX_THREAD_LEN] = {0};

            int tid_fd = openat(task_fd, tent->d_name, O_RDONLY | O_DIRECTORY);
            if (tid_fd == -1) continue;

            if (!read_file(tid_fd, "comm", tname, sizeof(tname))) {
                close(tid_fd);
                continue;
            }
            close(tid_fd);

            strtrim(tname);

            if (proc->num_threads >= proc->threads_cap) {
                size_t new_cap = proc->threads_cap * 2;
                ThreadInfo* tmp = realloc(proc->threads, new_cap * sizeof(ThreadInfo));
                if (!tmp) continue;
                proc->threads = tmp;
                proc->threads_cap = new_cap;
            }

            ThreadInfo* ti = &proc->threads[proc->num_threads];
            ti->tid = tid;
            build_str(ti->name, sizeof(ti->name), tname, NULL);
            CPU_ZERO(&ti->cpus);
            const char* matched = NULL;

            for (size_t i = 0; i < proc->num_thread_rules; i++) {
                const AffinityRule* rule = proc->thread_rules[i];
                if (fnmatch(rule->thread, ti->name, FNM_NOESCAPE) == 0) {
                    CPU_OR(&ti->cpus, &ti->cpus, &rule->cpus);
                    matched = rule->cpuset_dir;
                }
            }

            if (matched) {
                build_str(ti->cpuset_dir, sizeof(ti->cpuset_dir), matched, NULL);
            } else {
                ti->cpus = proc->base_cpus;
                build_str(ti->cpuset_dir, sizeof(ti->cpuset_dir), proc->base_cpuset, NULL);
            }

            proc->num_threads++;
        }

        closedir(task_dir);
        (*count)++;
    }
    closedir(proc_dir);
    if (current_proc_total > cache->last_proc_total) {
        cache->scan_all_proc = true;
    } else {
        cache->scan_all_proc = false;
    }
    cache->last_proc_total = current_proc_total;
}

static void update_cache(ProcCache* cache, const AppConfig* cfg, int* affinity_counter) {
    bool need_reload = false;
    struct sysinfo info;
    if (sysinfo(&info) != 0) {
        need_reload = true;
    } else {
        int current_proc_count = info.procs;
        if (current_proc_count > cache->last_proc_count) {
            /* 任何进程数增长都重扫: 新拉起的目标 app 可能就在新增进程里。
             * proc_collect 在 scan_all_proc=false 时仅扫描已跟踪 PID 或 mtime<60s
             * 的新进程, 故"每次增长就 reload"开销可控, 且能及时发现新进程
             * (旧逻辑要求增量>11 才 reload, 单个新 app 上来常漏掉, 延迟发现)。*/
            need_reload = true;
        }
        cache->last_proc_count = current_proc_count;
    }
    if (cache->procs != NULL && !need_reload) {
        for (size_t i = 0; i < cache->num_procs; i++) {
            if (kill(cache->procs[i].pid, 0) != 0) {
                need_reload = true;
                break;
            }
        }
    }
    if (need_reload) {
        size_t new_count = 0;
        proc_collect(cfg, cache, &new_count);

        if (new_count > cache->tracked_pids_cap) {
            size_t new_cap = cache->tracked_pids_cap ? cache->tracked_pids_cap * 2 : new_count;
            pid_t* new_pids = realloc(cache->tracked_pids, new_cap * sizeof(pid_t));
            if (new_pids) {
                cache->tracked_pids = new_pids;
                cache->tracked_pids_cap = new_cap;
            }
        }

        if (cache->tracked_pids) {
            cache->num_tracked_pids = 0;
            for (size_t i = 0; i < new_count; i++) {
                if (cache->num_tracked_pids < cache->tracked_pids_cap) {
                    cache->tracked_pids[cache->num_tracked_pids++] = cache->procs[i].pid;
                }
            }
        }

        cache->num_procs = new_count;
        *affinity_counter = 0;
    }
}

static void apply_affinity(ProcCache* cache, const CpuTopology* topo) {
    for (size_t i = 0; i < cache->num_procs; i++) {
        const ProcessInfo* proc = &cache->procs[i];
        for (size_t j = 0; j < proc->num_threads; j++) {
            const ThreadInfo* ti = &proc->threads[j];
            if (topo->cpuset_enabled && topo->base_cpuset_fd != -1) {
                char tid_str[32];
                snprintf(tid_str, sizeof(tid_str), "%d\n", ti->tid);
                if (CPU_COUNT(&ti->cpus) == 0) {
                    cpu_set_t curr;
                    if (sched_getaffinity(ti->tid, sizeof(curr), &curr) == -1) continue;
                    if (CPU_EQUAL(&topo->present_cpus, &curr)) continue;
                    write_file(topo->base_cpuset_fd, "tasks", tid_str, O_WRONLY | O_APPEND);
                } else {
                    cpu_set_t curr;
                    if (sched_getaffinity(ti->tid, sizeof(curr), &curr) == -1) continue;
                    if (CPU_EQUAL(&ti->cpus, &curr)) continue;
                    if (ti->cpuset_dir[0]) {
                        int fd = openat(topo->base_cpuset_fd, ti->cpuset_dir, O_RDONLY | O_DIRECTORY);
                        if (fd != -1) {
                            write_file(fd, "tasks", tid_str, O_WRONLY | O_APPEND);
                            close(fd);
                        }
                    }
                }
            }
            if (CPU_COUNT(&ti->cpus) == 0) continue;
            if (sched_setaffinity(ti->tid, sizeof(ti->cpus), &ti->cpus) == -1 && errno == ESRCH) {
                cache->last_proc_count = 0;
            }
        }
    }
}

static void config_release(AppConfig* cfg) {
    if (!cfg) return;
    if (atomic_fetch_sub(&cfg->ref_count, 1) == 1) {
        if (cfg->rules) free(cfg->rules);
        if (cfg->pkgs) {
            for (size_t i = 0; i < cfg->num_pkgs; i++) free(cfg->pkgs[i]);
            free(cfg->pkgs);
        }
        if (cfg->auto_pkgs) {
            for (size_t i = 0; i < cfg->num_auto_pkgs; i++) free(cfg->auto_pkgs[i]);
            free(cfg->auto_pkgs);
        }
        free(cfg);
    }
}

static AppConfig* get_config() {
    /* 锁内完成 load+ref++: 与 config_swap 的换出互斥, cfg 在自增期间不会被释放。 */
    pthread_mutex_lock(&config_swap_lock);
    AppConfig* cfg = atomic_load_explicit(&current_config, memory_order_acquire);
    if (cfg) atomic_fetch_add_explicit(&cfg->ref_count, 1, memory_order_acq_rel);
    pthread_mutex_unlock(&config_swap_lock);
    return cfg;
}

/* 原子换出 current_config 为 new_cfg, 返回旧值(调用方负责 config_release 旧值)。
 * 锁内 exchange, 与 get_config 的 load+ref++ 互斥, 保证读方拿到的引用计数有效。 */
static AppConfig* config_swap(AppConfig* new_cfg) {
    pthread_mutex_lock(&config_swap_lock);
    AppConfig* old = atomic_exchange(&current_config, new_cfg);
    pthread_mutex_unlock(&config_swap_lock);
    return old;
}

static void* config_loader_thread(void* arg) {
    int interval = *(int*)arg;
    free(arg);
    pthread_setname_np(pthread_self(), "ConfigLoader");

    struct timespec last_mtime = { .tv_sec = -1, .tv_nsec = -1 };
    while (1) {
        if (inotify_supported) {
            fd_set rfds;
            struct timeval tv;
            FD_ZERO(&rfds);
            FD_SET(inotify_fd, &rfds);
            tv.tv_sec = interval;
            tv.tv_usec = 0;

            int ret = select(inotify_fd + 1, &rfds, NULL, NULL, &tv);
            if (ret < 0) {
                if (errno == EINTR) continue;
                inotify_supported = 0;
                close(inotify_fd);
                inotify_fd = -1;
                continue;
            } else if (ret == 0) {
                continue;
            }

            char buf[4096] __attribute__((aligned(8)));
            ssize_t len = read(inotify_fd, buf, sizeof(buf));
            if (len <= 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    inotify_supported = 0;
                    close(inotify_fd);
                    inotify_fd = -1;
                }
                continue;
            }

            bool reload_needed = false;
            for (char* p = buf; p < buf + len;) {
                struct inotify_event* event = (struct inotify_event*)p;
                if (event->mask & (IN_CLOSE_WRITE | IN_DELETE_SELF | IN_MOVE_SELF)) {
                    reload_needed = true;

                    if (event->mask & (IN_DELETE_SELF | IN_MOVE_SELF)) {
                        sleep(interval);
                        AppConfig* cfg = get_config();
                        if (cfg) {
                            inotify_rm_watch(inotify_fd, inotify_wd);
                            inotify_wd = inotify_add_watch(inotify_fd, cfg->config_file, IN_CLOSE_WRITE | IN_DELETE_SELF | IN_MOVE_SELF);
                            last_mtime.tv_sec = -1;
                            last_mtime.tv_nsec = -1;
                            config_release(cfg);
                        }
                        if (inotify_wd < 0) {
                            inotify_supported = 0;
                            close(inotify_fd);
                            inotify_fd = -1;
                            break;
                        }
                    }
                }
                p += sizeof(struct inotify_event) + event->len;
            }

            if (reload_needed) {
                AppConfig* cfg = get_config();
                if (cfg) {
                    AppConfig* new_config = load_config(cfg->config_file, &cfg->topo, &last_mtime);
                    if (new_config) {
                        AppConfig* old_config = config_swap(new_config);
                        atomic_store(&config_updated, 1);
                        if (old_config) config_release(old_config);
                    }
                    config_release(cfg);
                }
            }
        } else {
            AppConfig* cfg = get_config();
            if (cfg) {
                AppConfig* new_config = load_config(cfg->config_file, &cfg->topo, &last_mtime);
                if (new_config) {
                    AppConfig* old_config = config_swap(new_config);
                    atomic_store(&config_updated, 1);
                    if (old_config) config_release(old_config);
                }
                config_release(cfg);
            }
            sleep(interval);
        }
    }
    return NULL;
}

/* ===================== 自动校准 (=auto) 模块 ===================== *
 * 用户在配置中写 "包名=auto" 后, App 启动游戏并下发 start 命令, 守护进程
 * 周期采样主进程各线程与子进程整体 CPU 占用; App 下发 stop 后, 按占用
 * 排序并依据 CPU 拓扑生成绑核规则, 回写 applist.conf。                    */

/* 单个进程内线程名的聚合采样数据 */
typedef struct {
    char owner[MAX_PKG_LEN];     /* 线程所属真实进程名, 包含可能的 :子进程后缀 */
    char name[MAX_THREAD_LEN];   /* 主进程线程名; 子进程整体样本为空 */
    bool is_process;             /* true 表示子进程整体负载, false 表示主进程线程 */
    unsigned long long busy;     /* 累计 (utime+stime) jiffies 增量 */
    bool alive;                  /* 本轮采样是否仍存在 */
    unsigned long long round_delta;  /* 本轮(一次 sample_once)累计的增量, 用于算瞬时占比 */
    float* series;               /* 每轮瞬时占比(%)序列, 用于历史折线图 */
    size_t series_len;
    size_t series_cap;
    size_t series_start;         /* 达到上限后作为环形缓冲保留最近采样 */
    double sum_pct;              /* 全会话统计不受可视曲线压缩影响 */
    double max_pct;
    size_t sample_count;
} ThreadSample;

/* per-(pid,tid) 的 CPU 跟踪, 用于在多进程下正确计算每个线程的增量,
 * 避免不同进程的同名线程互相覆盖基准值。 */
typedef struct {
    pid_t pid;
    pid_t tid;
    unsigned long long last;     /* 上一次采样的绝对 (utime+stime) */
    bool has_last;
    bool alive;
    char name[MAX_THREAD_LEN];
} TidTrack;

/* 子进程内部线程只保留 AVG/MAX 摘要, 用于历史记录按需展开。
 * 不保存独立曲线, 也不参与规则生成。 */
typedef struct {
    char owner[MAX_PKG_LEN];
    char name[MAX_THREAD_LEN];
    unsigned long long busy;
    unsigned long long round_delta;
    double sum_pct;
    double max_pct;
    size_t sample_count;
} ChildThreadSummary;

typedef struct {
    char pkg[MAX_PKG_LEN];
    ThreadSample* threads;       /* 主进程按线程、子进程按进程名聚合 */
    size_t count;
    size_t cap;
    TidTrack* tids;              /* 按 (pid,tid) 跟踪增量 */
    size_t tcount;
    size_t tcap;
    ChildThreadSummary* child_threads;
    size_t child_thread_count;
    size_t child_thread_cap;
    size_t round_count;          /* 已完成的采样轮数, 即折线序列长度 */
    long long last_sample_ms;    /* 上一轮采样完成时间, 用于计算真实 CPU 占用 */
    long clock_ticks_per_second;
} CalibData;

/* 在 stat 文件中解析 utime(14) + stime(15)。stat 的 comm 字段可能含空格/括号,
 * 故从最后一个 ')' 之后开始按空格切分, 该位置之后第 12、13 个字段即 utime、stime。 */
static bool read_thread_cpu(int task_fd, const char* tid_name, unsigned long long* out) {
    int fd = openat(task_fd, tid_name, O_RDONLY | O_DIRECTORY);
    if (fd == -1) return false;
    char buf[512];
    bool ok = read_file(fd, "stat", buf, sizeof(buf));
    close(fd);
    if (!ok) return false;

    char* rp = strrchr(buf, ')');
    if (!rp) return false;
    rp++;                              /* 跳到 comm 之后 */
    /* 此处起第一个 token 是 state(字段3), utime 是字段14 => 之后第 11 个 token */
    int field = 2;
    unsigned long long utime = 0, stime = 0;
    char* tok = strtok(rp, " \t\n");
    while (tok) {
        field++;
        if (field == 14) utime = strtoull(tok, NULL, 10);
        else if (field == 15) { stime = strtoull(tok, NULL, 10); break; }
        tok = strtok(NULL, " \t\n");
    }
    *out = utime + stime;
    return true;
}

typedef struct {
    pid_t pid;
    char owner[MAX_PKG_LEN];
} CalibProcess;

/* 收集属于该应用的所有进程: 主进程 (cmdline==pkg) 与子进程 (cmdline 以 "pkg:" 开头)。
 * 例如 com.tencent.tmgp.sgame 与 com.tencent.tmgp.sgame:GiftProcess 都计入。
 * 结果保留 pid 与真实进程名, 返回收集到的进程数。 */
static size_t collect_pkg_processes(const char* pkg, CalibProcess* out, size_t max) {
    DIR* d = opendir("/proc");
    if (!d) return 0;
    int dfd = dirfd(d);
    if (dfd < 0) {
        closedir(d);
        return 0;
    }
    struct dirent* e;
    size_t n = 0;
    size_t plen = strlen(pkg);
    while ((e = readdir(d)) && n < max) {
        char* end;
        long pid = strtol(e->d_name, &end, 10);
        if (*end != '\0') continue;
        int pfd = openat(dfd, e->d_name, O_RDONLY | O_DIRECTORY);
        if (pfd == -1) continue;
        char cmd[MAX_PKG_LEN] = {0};
        bool ok = read_file(pfd, "cmdline", cmd, sizeof(cmd));
        close(pfd);
        if (!ok) continue;
        char* name = strrchr(cmd, '/');
        name = name ? name + 1 : cmd;
        /* 主进程: 完全相等; 子进程: "pkg:子进程名" */
        if (strcmp(name, pkg) == 0 ||
            (strncmp(name, pkg, plen) == 0 && name[plen] == ':')) {
            out[n].pid = (pid_t)pid;
            build_str(out[n].owner, sizeof(out[n].owner), name, NULL);
            n++;
        }
    }
    closedir(d);
    return n;
}

/* 在 data 中按样本类型、进程名和线程名查找, 不存在则追加, 返回索引。 */
static long calib_find_or_add(CalibData* data, const char* owner, const char* name,
                              bool is_process) {
    for (size_t i = 0; i < data->count; i++) {
        if (data->threads[i].is_process == is_process &&
            strcmp(data->threads[i].owner, owner) == 0 &&
            strcmp(data->threads[i].name, name) == 0) return (long)i;
    }
    if (data->count >= CALIB_MAX_SAMPLES) return -1;
    if (data->count >= data->cap) {
        size_t nc = data->cap ? data->cap * 2 : 64;
        ThreadSample* t = realloc(data->threads, nc * sizeof(ThreadSample));
        if (!t) return -1;
        data->threads = t;
        data->cap = nc;
    }
    ThreadSample* s = &data->threads[data->count];
    memset(s, 0, sizeof(*s));
    build_str(s->owner, sizeof(s->owner), owner, NULL);
    build_str(s->name, sizeof(s->name), name, NULL);
    s->is_process = is_process;
    if (data->round_count > 0) {
        size_t backfill = data->round_count < CALIB_MAX_SERIES_POINTS ?
            data->round_count : CALIB_MAX_SERIES_POINTS;
        s->series = calloc(backfill, sizeof(float));
        if (s->series) {
            s->series_len = backfill;
            s->series_cap = backfill;
        }
        s->sample_count = data->round_count;
    }
    return (long)data->count++;
}

/* 在 tid 跟踪表中按 (pid,tid) 查找, 不存在则追加, 返回指针; 失败返回 NULL */
static TidTrack* calib_track_tid(CalibData* data, pid_t pid, pid_t tid,
                                 const char* name) {
    for (size_t i = 0; i < data->tcount; i++) {
        if (data->tids[i].pid == pid && data->tids[i].tid == tid)
            return &data->tids[i];
    }
    if (data->tcount >= CALIB_MAX_TRACKED_TIDS) return NULL;
    if (data->tcount >= data->tcap) {
        size_t nc = data->tcap ? data->tcap * 2 : 128;
        TidTrack* t = realloc(data->tids, nc * sizeof(TidTrack));
        if (!t) return NULL;
        data->tids = t;
        data->tcap = nc;
    }
    TidTrack* tk = &data->tids[data->tcount++];
    memset(tk, 0, sizeof(*tk));
    tk->pid = pid;
    tk->tid = tid;
    build_str(tk->name, sizeof(tk->name), name, NULL);
    return tk;
}

static ChildThreadSummary* calib_track_child_thread(CalibData* data,
                                                    const char* owner,
                                                    const char* name) {
    for (size_t i = 0; i < data->child_thread_count; i++) {
        ChildThreadSummary* s = &data->child_threads[i];
        if (strcmp(s->owner, owner) == 0 && strcmp(s->name, name) == 0) return s;
    }
    if (data->child_thread_count >= CALIB_MAX_CHILD_THREAD_SUMMARIES) return NULL;
    if (data->child_thread_count >= data->child_thread_cap) {
        size_t nc = data->child_thread_cap ? data->child_thread_cap * 2 : 32;
        ChildThreadSummary* p = realloc(data->child_threads,
                                        nc * sizeof(ChildThreadSummary));
        if (!p) return NULL;
        data->child_threads = p;
        data->child_thread_cap = nc;
    }
    ChildThreadSummary* s = &data->child_threads[data->child_thread_count++];
    memset(s, 0, sizeof(*s));
    build_str(s->owner, sizeof(s->owner), owner, NULL);
    build_str(s->name, sizeof(s->name), name, NULL);
    s->sample_count = data->round_count;
    return s;
}

/* 对单个进程 task/ 做一遍扫描。主进程按线程累计, 子进程把所有线程增量
 * 汇总为一个进程样本, 避免大量短时低负载线程稀释子进程实际负载。 */
static bool calib_sample_proc(CalibData* data, pid_t pid, const char* owner) {
    char taskpath[64];
    snprintf(taskpath, sizeof(taskpath), "/proc/%d/task", pid);
    DIR* td = opendir(taskpath);
    if (!td) return false;
    int task_fd = dirfd(td);
    if (task_fd < 0) {
        closedir(td);
        return false;
    }
    const bool is_main_process = strcmp(owner, data->pkg) == 0;
    ThreadSample* process_sample = NULL;
    if (!is_main_process) {
        long idx = calib_find_or_add(data, owner, "", true);
        if (idx < 0) {
            closedir(td);
            return false;
        }
        process_sample = &data->threads[idx];
        process_sample->alive = true;
    }

    struct dirent* te;
    while ((te = readdir(td))) {
        char* end;
        long tid = strtol(te->d_name, &end, 10);
        if (*end != '\0') continue;

        int tfd = openat(task_fd, te->d_name, O_RDONLY | O_DIRECTORY);
        if (tfd == -1) continue;
        char tname[MAX_THREAD_LEN] = {0};
        bool ok = read_file(tfd, "comm", tname, sizeof(tname));
        close(tfd);
        if (!ok) continue;
        strtrim(tname);

        unsigned long long cpu;
        if (!read_thread_cpu(task_fd, te->d_name, &cpu)) continue;

        /* 用 (pid,tid) 跟踪正确的增量基准 */
        TidTrack* tk = calib_track_tid(data, pid, (pid_t)tid, tname);
        if (!tk) continue;
        tk->alive = true;
        unsigned long long delta = 0;
        if (tk->has_last && cpu >= tk->last) delta = cpu - tk->last;
        tk->last = cpu;
        tk->has_last = true;

        if (is_main_process) {
            long idx = calib_find_or_add(data, owner, tname, false);
            if (idx < 0) continue;
            ThreadSample* s = &data->threads[idx];
            s->alive = true;
            s->busy += delta;
            s->round_delta += delta;
        } else {
            process_sample->busy += delta;
            process_sample->round_delta += delta;
            if (delta > 0) {
                ChildThreadSummary* summary = calib_track_child_thread(data, owner, tname);
                if (summary) {
                    summary->busy += delta;
                    summary->round_delta += delta;
                }
            }
        }
    }
    closedir(td);
    return true;
}

/* 给线程的瞬时占比序列追加一个采样点 */
static void calib_series_push(ThreadSample* s, float pct) {
    if (s->series_len >= CALIB_MAX_SERIES_POINTS) {
        s->series[s->series_start] = pct;
        s->series_start = (s->series_start + 1) % s->series_cap;
        return;
    }
    if (s->series_len >= s->series_cap) {
        size_t nc = s->series_cap ? s->series_cap * 2 : 32;
        if (nc > CALIB_MAX_SERIES_POINTS) nc = CALIB_MAX_SERIES_POINTS;
        float* p = realloc(s->series, nc * sizeof(float));
        if (!p) return;
        s->series = p;
        s->series_cap = nc;
    }
    s->series[s->series_len++] = pct;
}

static double calib_cpu_percent(unsigned long long delta,
                                long long elapsed_ms,
                                long ticks_per_second) {
    if (delta == 0 || elapsed_ms <= 0 || ticks_per_second <= 0) return 0.0;
    double pct = (double)delta * 100000.0 /
                 ((double)ticks_per_second * (double)elapsed_ms);
    if (pct < 0.0) return 0.0;
    return pct > 100.0 ? 100.0 : pct;
}

/* TID 基准只需保留本轮仍存活的线程，避免长时间校准时线性增长。 */
static void calib_prune_dead_tids(CalibData* data) {
    size_t kept = 0;
    for (size_t i = 0; i < data->tcount; i++) {
        if (!data->tids[i].alive) continue;
        if (kept != i) data->tids[kept] = data->tids[i];
        kept++;
    }
    data->tcount = kept;
}

/* 对目标应用做一次采样: 主进程保留线程维度, :子进程仅保留整体负载。
 * 返回 false 表示应用所有进程都已消失 (游戏退出)。 */
static bool calib_sample_once(CalibData* data) {
    CalibProcess procs[64];
    size_t np = collect_pkg_processes(data->pkg, procs, 64);
    if (np == 0) return false;

    for (size_t i = 0; i < data->count; i++) {
        data->threads[i].alive = false;
        data->threads[i].round_delta = 0;
    }
    for (size_t i = 0; i < data->child_thread_count; i++) {
        data->child_threads[i].round_delta = 0;
    }
    for (size_t i = 0; i < data->tcount; i++) data->tids[i].alive = false;

    bool any = false;
    bool had_baseline = data->last_sample_ms > 0;
    for (size_t i = 0; i < np; i++) {
        if (calib_sample_proc(data, procs[i].pid, procs[i].owner)) any = true;
    }
    if (!any) return false;

    long long now_ms = monotonic_ms();
    if (data->clock_ticks_per_second <= 0) {
        data->clock_ticks_per_second = sysconf(_SC_CLK_TCK);
        if (data->clock_ticks_per_second <= 0) data->clock_ticks_per_second = 100;
    }
    if (!had_baseline) {
        data->last_sample_ms = now_ms;
        calib_prune_dead_tids(data);
        return true;
    }

    long long elapsed_ms = now_ms - data->last_sample_ms;
    data->last_sample_ms = now_ms;
    if (elapsed_ms <= 0) elapsed_ms = 500;

    /* jiffies / 实际墙钟间隔才是真实单核 CPU 使用率。空闲轮次也写入 0，
     * 防止只统计活跃轮次导致 AVG 被系统性抬高。 */
    for (size_t i = 0; i < data->count; i++) {
        double pct = calib_cpu_percent(data->threads[i].round_delta,
                                       elapsed_ms,
                                       data->clock_ticks_per_second);
        data->threads[i].sum_pct += pct;
        if (pct > data->threads[i].max_pct) data->threads[i].max_pct = pct;
        data->threads[i].sample_count++;
        calib_series_push(&data->threads[i], (float)pct);
    }
    for (size_t i = 0; i < data->child_thread_count; i++) {
        ChildThreadSummary* s = &data->child_threads[i];
        double pct = calib_cpu_percent(s->round_delta,
                                       elapsed_ms,
                                       data->clock_ticks_per_second);
        s->sum_pct += pct;
        if (pct > s->max_pct) s->max_pct = pct;
        s->sample_count++;
    }
    data->round_count++;
    calib_prune_dead_tids(data);
    return true;
}

static bool calib_rule_name_syntax_ok(const char* name) {
    if (!name || !name[0] || strcmp(name, "*") == 0) return false;
    for (size_t i = 0; name[i]; i++) {
        unsigned char c = (unsigned char)name[i];
        if (c == '{' || c == '}' || c == '=' || c == '/' || c == '\\' ||
            c == '*' || c == '\n' || c == '\r') {
            return false;
        }
    }
    return true;
}

static bool calib_wildcard_name_syntax_ok(const char* name) {
    if (!name || !name[0] || strcmp(name, "*") == 0) return false;
    bool has_wildcard = false;
    for (size_t i = 0; name[i]; i++) {
        unsigned char c = (unsigned char)name[i];
        if (c == '{' || c == '}' || c == '=' || c == '/' || c == '\\' ||
            c == '\n' || c == '\r') {
            return false;
        }
        if (c == '*') has_wildcard = true;
    }
    return has_wildcard;
}

static bool calib_wildcard_candidate(const char* name, char* out, size_t out_sz) {
    if (!name || !out || out_sz == 0 || !calib_rule_name_syntax_ok(name)) return false;
    out[0] = '\0';

    size_t digit_pos = 0;
    while (name[digit_pos] && !isdigit((unsigned char)name[digit_pos])) digit_pos++;
    if (!name[digit_pos]) return false;

    size_t prefix_len = digit_pos;
    while (prefix_len > 0 && isspace((unsigned char)name[prefix_len - 1])) prefix_len--;

    int alpha = 0;
    for (size_t i = 0; i < prefix_len; i++) {
        if (isalpha((unsigned char)name[i])) alpha++;
    }
    if (prefix_len < 4 || alpha < 2) return false;

    size_t first_digit_end = digit_pos;
    while (isdigit((unsigned char)name[first_digit_end])) first_digit_end++;
    bool has_stable_suffix = false;
    for (size_t i = first_digit_end; name[i]; i++) {
        if (isalpha((unsigned char)name[i])) {
            has_stable_suffix = true;
            break;
        }
    }

    if (has_stable_suffix) {
        static const char numeric_glob[] = "[0-9]*";
        const size_t numeric_glob_len = sizeof(numeric_glob) - 1;
        size_t src = 0;
        size_t dst = 0;
        bool overflow = false;
        while (name[src]) {
            if (isdigit((unsigned char)name[src])) {
                if (dst + numeric_glob_len + 1 > out_sz) {
                    overflow = true;
                    break;
                }
                memcpy(out + dst, numeric_glob, numeric_glob_len);
                dst += numeric_glob_len;
                while (isdigit((unsigned char)name[src])) src++;
            } else {
                if (dst + 2 > out_sz) {
                    overflow = true;
                    break;
                }
                out[dst++] = name[src++];
            }
        }
        if (!overflow) {
            out[dst] = '\0';
            if (calib_wildcard_name_syntax_ok(out)) return true;
        }
    }

    if (prefix_len + 2 > out_sz) return false;

    memcpy(out, name, prefix_len);
    out[prefix_len] = '*';
    out[prefix_len + 1] = '\0';
    return calib_wildcard_name_syntax_ok(out);
}

static int calib_wildcard_candidate_count(const CalibData* data, const char* owner,
                                          const char* candidate) {
    if (!data || !owner || !candidate || !candidate[0]) return 0;
    int count = 0;
    for (size_t i = 0; i < data->count; i++) {
        if (strcmp(data->threads[i].owner, owner) != 0) continue;
        char other[MAX_THREAD_LEN];
        if (calib_wildcard_candidate(data->threads[i].name, other, sizeof(other)) &&
            strcmp(other, candidate) == 0) {
            count++;
        }
    }
    return count;
}

static void calib_rule_base_for_thread(const CalibData* data, size_t idx, char* out, size_t out_sz) {
    if (!data || idx >= data->count || !out || out_sz == 0) return;
    out[0] = '\0';

    char candidate[MAX_THREAD_LEN];
    if (calib_wildcard_candidate(data->threads[idx].name, candidate, sizeof(candidate)) &&
        calib_wildcard_candidate_count(data, data->threads[idx].owner, candidate) >= 2) {
        build_str(out, out_sz, candidate, NULL);
        return;
    }

    if (calib_rule_name_syntax_ok(data->threads[idx].name)) {
        build_str(out, out_sz, data->threads[idx].name, NULL);
    }
}

/* 排序比较: busy 降序 */
static int calib_cmp_busy(const void* a, const void* b) {
    const ThreadSample* x = a;
    const ThreadSample* y = b;
    if (x->busy < y->busy) return 1;
    if (x->busy > y->busy) return -1;
    return 0;
}

static void calib_thread_pct_stats(const ThreadSample* s, double* avg, double* max) {
    if (!s || s->sample_count == 0) {
        *avg = 0.0;
        *max = 0.0;
        return;
    }
    *avg = s->sum_pct / (double)s->sample_count;
    *max = s->max_pct;
}

static double calib_load_score(double avg_pct, double max_pct) {
    return avg_pct * 0.65 + max_pct * 0.35;
}

typedef enum {
    CALIB_WILDCARD_MAX_MEMBER = 0,
    CALIB_WILDCARD_SUM = 1
} CalibWildcardMode;

typedef enum {
    CALIB_CORE_BIG = 0,
    CALIB_CORE_MIDDLE_HIGH = 1,
    CALIB_CORE_MIDDLE = 2,
    CALIB_CORE_NONBIG = 3
} CalibCoreTier;

typedef struct {
    double best_avg;
    double best_max;
    CalibCoreTier best_tier;
    char best_cores[64];
    double high_avg;
    double high_max;
    CalibCoreTier high_tier;
    char high_cores[64];
    double mid_avg;
    double mid_max;
    CalibCoreTier mid_tier;
    char mid_cores[64];
    int max_thread_rules;
    CalibWildcardMode wildcard_mode;
    CalibCoreTier fallback_tier;
    char fallback_cores[64];
} CalibPolicy;

static CalibPolicy calib_default_policy(void) {
    CalibPolicy p;
    p.best_avg = 18.0;
    p.best_max = 30.0;
    p.best_tier = CALIB_CORE_BIG;
    p.best_cores[0] = '\0';
    p.high_avg = 13.0;
    p.high_max = 22.0;
    p.high_tier = CALIB_CORE_MIDDLE_HIGH;
    p.high_cores[0] = '\0';
    p.mid_avg = 8.0;
    p.mid_max = 18.0;
    p.mid_tier = CALIB_CORE_MIDDLE;
    p.mid_cores[0] = '\0';
    p.max_thread_rules = 6;
    p.wildcard_mode = CALIB_WILDCARD_MAX_MEMBER;
    p.fallback_tier = CALIB_CORE_NONBIG;
    p.fallback_cores[0] = '\0';
    return p;
}

static double calib_clamp_pct(double v, double fallback) {
    if (v < 0.0 || v > 100.0) return fallback;
    return v;
}

static double calib_rule_number(const char* rule, const char* name, double fallback) {
    if (!rule || !name) return fallback;
    char key[32];
    snprintf(key, sizeof(key), "%s:", name);
    const char* p = strstr(rule, key);
    if (!p) return fallback;
    p += strlen(key);
    char* end = NULL;
    double v = strtod(p, &end);
    if (end == p) return fallback;
    return calib_clamp_pct(v, fallback);
}

static bool calib_rule_text(const char* rule, const char* name, char* out, size_t out_sz) {
    if (!rule || !name || !out || out_sz == 0) return false;
    char key[32];
    snprintf(key, sizeof(key), "%s:", name);
    const char* p = strstr(rule, key);
    if (!p) return false;
    p += strlen(key);
    const char* end = NULL;
    if (strcmp(name, "cores") == 0) {
        const char* markers[] = { ",avg:", ",max:", ",cores:" };
        for (size_t i = 0; i < sizeof(markers) / sizeof(markers[0]); i++) {
            const char* m = strstr(p, markers[i]);
            if (m && (!end || m < end)) end = m;
        }
    } else {
        end = strchr(p, ',');
    }
    size_t len = end ? (size_t)(end - p) : strlen(p);
    while (len > 0 && isspace((unsigned char)p[len - 1])) len--;
    while (len > 0 && isspace((unsigned char)*p)) { p++; len--; }
    if (len == 0 || len >= out_sz) return false;
    memcpy(out, p, len);
    out[len] = '\0';
    return true;
}

static bool calib_core_range_normalize(const char* s, char* out, size_t out_sz) {
    if (!s || !*s || !out || out_sz == 0) return false;
    if (strcmp(s, "auto") == 0) return false;

    char buf[64];
    build_str(buf, sizeof(buf), s, NULL);
    unsigned char seen[CPU_SETSIZE] = {0};
    int min_cpu = INT_MAX;
    int max_cpu = -1;
    int count = 0;

    char* part = strtok(buf, ",");
    while (part) {
        if (!*part) return false;
        char* dash = strchr(part, '-');
        long start;
        long end;
        char* ep = NULL;
        if (dash) {
            if (strchr(dash + 1, '-')) return false;
            *dash = '\0';
            start = strtol(part, &ep, 10);
            if (*ep != '\0') return false;
            end = strtol(dash + 1, &ep, 10);
            if (*ep != '\0') return false;
        } else {
            start = strtol(part, &ep, 10);
            if (*ep != '\0') return false;
            end = start;
        }
        if (start < 0 || end < start || end >= CPU_SETSIZE) return false;
        for (long cpu = start; cpu <= end; cpu++) {
            if (!seen[cpu]) {
                seen[cpu] = 1;
                count++;
                if ((int)cpu < min_cpu) min_cpu = (int)cpu;
                if ((int)cpu > max_cpu) max_cpu = (int)cpu;
            }
        }
        part = strtok(NULL, ",");
    }

    if (count <= 0 || min_cpu == INT_MAX || max_cpu < min_cpu) return false;
    if (count != max_cpu - min_cpu + 1) return false;
    if (min_cpu == max_cpu) {
        snprintf(out, out_sz, "%d", min_cpu);
    } else {
        snprintf(out, out_sz, "%d-%d", min_cpu, max_cpu);
    }
    return true;
}

static bool calib_core_range_usable(const CpuTopology* topo, const char* s) {
    char normalized[64];
    if (!s || !*s) return false;
    if (!calib_core_range_normalize(s, normalized, sizeof(normalized))) return false;

    cpu_set_t set;
    CPU_ZERO(&set);
    parse_cpu_ranges(normalized, &set, NULL);
    if (CPU_COUNT(&set) == 0) return false;

    if (topo && CPU_COUNT(&topo->present_cpus) > 0) {
        for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
            if (CPU_ISSET(cpu, &set) && !CPU_ISSET(cpu, &topo->present_cpus)) {
                return false;
            }
        }
    }
    return true;
}

static void calib_set_core_range(char* dst, size_t dst_sz, const char* src) {
    if (!dst || dst_sz == 0 || !src) return;
    char tmp[64];
    size_t j = 0;
    for (const char* p = src; *p && j + 1 < sizeof(tmp); p++) {
        if (!isspace((unsigned char)*p)) tmp[j++] = (char)tolower((unsigned char)*p);
    }
    tmp[j] = '\0';
    char normalized[64];
    if (!calib_core_range_normalize(tmp, normalized, sizeof(normalized))) return;
    build_str(dst, dst_sz, normalized, NULL);
}

static const char* calib_auto_core_range(const CpuTopology* topo, CalibCoreTier tier) {
    if (!topo) return "";
    switch (tier) {
        case CALIB_CORE_BIG:
            return topo->big_str[0] ? topo->big_str :
                   (topo->middle_high_str[0] ? topo->middle_high_str : topo->all_str);
        case CALIB_CORE_MIDDLE_HIGH:
            return topo->middle_high_str[0] ? topo->middle_high_str :
                   (topo->middle_str[0] ? topo->middle_str : topo->all_str);
        case CALIB_CORE_MIDDLE:
            return topo->middle_str[0] ? topo->middle_str : topo->all_str;
        case CALIB_CORE_NONBIG:
            return topo->nonbig_str[0] ? topo->nonbig_str :
                   (topo->all_str[0] ? topo->all_str : topo->base_str);
        default:
            return topo->all_str;
    }
}

static const char* calib_policy_core_range(const CpuTopology* topo,
                                           CalibCoreTier tier,
                                           const char* override_cores) {
    if (calib_core_range_usable(topo, override_cores)) {
        return override_cores;
    }
    return calib_auto_core_range(topo, tier);
}

static const char* calib_core_tier_label(CalibCoreTier tier) {
    switch (tier) {
        case CALIB_CORE_BIG: return "最高性能核心";
        case CALIB_CORE_MIDDLE_HIGH: return "高性能核心";
        case CALIB_CORE_MIDDLE: return "主性能核心";
        case CALIB_CORE_NONBIG: return "非最高性能核心";
        default: return "全部核心";
    }
}

static const char* calib_core_source_label(const CpuTopology* topo, const char* override_cores) {
    if (override_cores && override_cores[0]) {
        return calib_core_range_usable(topo, override_cores) ? "用户指定" : "用户指定无效, 已回退";
    }
    return "自动识别";
}

static void calib_log_policy_rule(const CpuTopology* topo,
                                  const char* key,
                                  const char* meaning,
                                  double avg,
                                  double max,
                                  CalibCoreTier tier,
                                  const char* cores,
                                  bool require_both) {
    const char* resolved = calib_policy_core_range(topo, tier, cores);
    printf("[校准策略] %s: %s; 条件=AVG>=%.1f%% %s MAX>=%.1f%%; 档位=%s; 核心=%s (%s)\n",
           key, meaning, avg, require_both ? "且" : "或", max, calib_core_tier_label(tier),
           resolved && resolved[0] ? resolved : "-", calib_core_source_label(topo, cores));
}

static void calib_log_policy(const CalibPolicy* p, const CpuTopology* topo,
                             const char* path, const struct stat* st) {
    static bool logged_missing = false;
    static bool logged_once = false;
    static struct timespec last_mtime = { .tv_sec = -1, .tv_nsec = -1 };
    static off_t last_size = -1;

    if (!p || !topo || !path || !st) {
        if (!logged_missing) {
            printf("[校准策略] 未读取到 %s, 使用内置默认策略\n",
                   path ? path : CALIB_POLICY_FILE);
            logged_missing = true;
        }
        return;
    }

    if (logged_once && stat_mtime_equal(st, &last_mtime) && last_size == st->st_size) return;
    logged_once = true;
    logged_missing = false;
    last_mtime = st->st_mtim;
    last_size = st->st_size;

    printf("[校准策略] 已读取配置文件: %s (size=%lld, mtime=%ld.%09ld)\n",
           path, (long long)st->st_size,
           (long)st->st_mtim.tv_sec, (long)st->st_mtim.tv_nsec);
    printf("[校准策略] CPU 拓扑: %d 个性能簇, 低性能=%s, 主性能=%s, 高性能=%s, 最高性能=%s, 非最高=%s, 全部=%s\n",
           topo->num_clusters,
           topo->little_str[0] ? topo->little_str : "-",
           topo->middle_str[0] ? topo->middle_str : "-",
           topo->middle_high_str[0] ? topo->middle_high_str : "-",
           topo->big_str[0] ? topo->big_str : "-",
           topo->nonbig_str[0] ? topo->nonbig_str : "-",
           topo->all_str[0] ? topo->all_str : "-");
    calib_log_policy_rule(topo, "best_thread",
                          "只挑主进程中负载最高且平均与峰值都达到阈值的 1 个线程生成第一条单独线程规则",
                          p->best_avg, p->best_max, p->best_tier, p->best_cores, true);
    calib_log_policy_rule(topo, "group_high",
                          "主进程较重线程或相似线程组平均与峰值都达到阈值后生成第二档线程规则",
                          p->high_avg, p->high_max, p->high_tier, p->high_cores, true);
    calib_log_policy_rule(topo, "group_mid",
                          "主进程中等负载线程或相似线程组平均与峰值都达到阈值后生成第三档线程规则",
                          p->mid_avg, p->mid_max, p->mid_tier, p->mid_cores, true);
    printf("[校准策略] child_process: 汇总子进程全部线程负载, 使用 group_high/group_mid 阈值生成进程级规则, 不参与 best_thread\n");
    printf("[校准策略] wildcard_group: %s; %s\n",
           p->wildcard_mode == CALIB_WILDCARD_SUM ? "sum" : "max_member",
           p->wildcard_mode == CALIB_WILDCARD_SUM ?
           "同名通配线程组会累加平均负载, 更激进" :
           "通配线程组只按组内最高单线程平均负载判断, 避免低负载线程累加误升档");
    printf("[校准策略] max_thread_rules: %d; 最多生成 %d 条线程级规则, 其余线程走包名兜底\n",
           p->max_thread_rules, p->max_thread_rules);
    printf("[校准策略] fallback: 没有单独线程规则的线程使用包名兜底; 档位=%s; 核心=%s (%s)\n",
           calib_core_tier_label(p->fallback_tier),
           calib_policy_core_range(topo, p->fallback_tier, p->fallback_cores),
           calib_core_source_label(topo, p->fallback_cores));
}

#define CALIB_TOPO_BEGIN "# AppOpt detected CPU topology begin"
#define CALIB_TOPO_END   "# AppOpt detected CPU topology end"

static bool calib_policy_lock_acquire(void) {
    const int timeout_ms = 5000;
    const int step_us = 50000;
    int waited_us = 0;
    while (!shutdown_requested && waited_us <= timeout_ms * 1000) {
        if (mkdir(CALIB_POLICY_LOCK, 0777) == 0) return true;
        if (errno != EEXIST) {
            printf("[校准策略] 获取配置锁失败: %s err=%s\n", CALIB_POLICY_LOCK, strerror(errno));
            return false;
        }

        struct stat st;
        time_t now = time(NULL);
        if (stat(CALIB_POLICY_LOCK, &st) == 0 && now > st.st_mtime + 30) {
            if (rmdir(CALIB_POLICY_LOCK) == 0) {
                printf("[校准策略] 已清理过期配置锁: %s\n", CALIB_POLICY_LOCK);
                continue;
            }
        }
        usleep(step_us);
        waited_us += step_us;
    }
    printf("[校准策略] 等待配置锁超时: %s\n", CALIB_POLICY_LOCK);
    return false;
}

static void calib_policy_lock_release(void) {
    if (rmdir(CALIB_POLICY_LOCK) != 0 && errno != ENOENT) {
        printf("[校准策略] 释放配置锁失败: %s err=%s\n", CALIB_POLICY_LOCK, strerror(errno));
    }
}

static void calib_sync_policy_topology(const CpuTopology* topo) {
    if (!topo) return;
    if (!calib_policy_lock_acquire()) return;

    char content[32768];
    FILE* f = fopen(CALIB_POLICY_FILE, "r");
    if (!f) {
        calib_policy_lock_release();
        return;
    }
    size_t used = fread(content, 1, sizeof(content) - 1, f);
    fclose(f);
    content[used] = '\0';

    char cleaned[32768];
    size_t clen = 0;
    bool in_block = false;
    const char* p = content;
    while (*p && clen + 2 < sizeof(cleaned)) {
        const char* nl = strchr(p, '\n');
        size_t len = nl ? (size_t)(nl - p) : strlen(p);
        bool skip = false;
        if (strncmp(p, CALIB_TOPO_BEGIN, strlen(CALIB_TOPO_BEGIN)) == 0) {
            in_block = true;
            skip = true;
        } else if (strncmp(p, CALIB_TOPO_END, strlen(CALIB_TOPO_END)) == 0) {
            in_block = false;
            skip = true;
        } else if (in_block || strncmp(p, "detected_", 9) == 0 ||
                   strncmp(p, "# CPU 拓扑识别:", strlen("# CPU 拓扑识别:")) == 0) {
            skip = true;
        }
        if (!skip && clen + len + 1 < sizeof(cleaned)) {
            memcpy(cleaned + clen, p, len);
            clen += len;
            cleaned[clen++] = '\n';
        }
        if (!nl) break;
        p = nl + 1;
    }
    while (clen > 0 && (cleaned[clen - 1] == '\n' || cleaned[clen - 1] == '\r')) clen--;
    cleaned[clen++] = '\n';
    cleaned[clen] = '\0';

    char block[1024];
    snprintf(block, sizeof(block),
             "\n%s\n"
             "# CPU 拓扑识别: %d 个性能簇, 全部=[%s] 低性能=[%s] 主性能=[%s] 高性能=[%s] 最高性能=[%s] 非最高=[%s]\n"
             "detected_clusters=%d\n"
             "detected_low=%s\n"
             "detected_main=%s\n"
             "detected_high=%s\n"
             "detected_non_top=%s\n"
             "detected_top=%s\n"
             "detected_all=%s\n"
             "%s\n",
             CALIB_TOPO_BEGIN,
             topo->num_clusters, topo->all_str, topo->little_str, topo->middle_str,
             topo->middle_high_str, topo->big_str, topo->nonbig_str,
             topo->num_clusters,
             topo->little_str, topo->middle_str, topo->middle_high_str,
             topo->nonbig_str, topo->big_str, topo->all_str,
             CALIB_TOPO_END);

    char next[32768];
    snprintf(next, sizeof(next), "%s%s", cleaned, block);
    if (strcmp(content, next) == 0) {
        calib_policy_lock_release();
        return;
    }

    char tmp_path[PATH_MAX];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", CALIB_POLICY_FILE);
    FILE* out = fopen(tmp_path, "w");
    if (!out) {
        calib_policy_lock_release();
        return;
    }
    fputs(next, out);
    if (fclose(out) != 0 || rename(tmp_path, CALIB_POLICY_FILE) != 0) {
        printf("[校准策略] 写入拓扑信息失败: %s err=%s\n", CALIB_POLICY_FILE, strerror(errno));
        unlink(tmp_path);
    }
    calib_policy_lock_release();
}

static CalibPolicy calib_load_policy(const CpuTopology* topo) {
    CalibPolicy p = calib_default_policy();
    calib_sync_policy_topology(topo);
    struct stat policy_st;
    bool have_policy_stat = (stat(CALIB_POLICY_FILE, &policy_st) == 0);
    FILE* f = fopen(CALIB_POLICY_FILE, "r");
    if (!f) {
        calib_log_policy(&p, topo, CALIB_POLICY_FILE, NULL);
        return p;
    }

    char line[256];
    while (fgets(line, sizeof(line), f)) {
        char* comment = strchr(line, '#');
        if (comment) *comment = '\0';
        char* t = strtrim(line);
        if (!*t) continue;
        char* eq = strchr(t, '=');
        if (!eq) continue;
        *eq = '\0';
        char* key = strtrim(t);
        char* val = strtrim(eq + 1);
        if (strcmp(key, "best_thread") == 0) {
            p.best_avg = calib_rule_number(val, "avg", p.best_avg);
            p.best_max = calib_rule_number(val, "max", p.best_max);
            char text[64];
            if (calib_rule_text(val, "cores", text, sizeof(text))) {
                calib_set_core_range(p.best_cores, sizeof(p.best_cores), text);
            }
        } else if (strcmp(key, "group_high") == 0) {
            p.high_avg = calib_rule_number(val, "avg", p.high_avg);
            p.high_max = calib_rule_number(val, "max", p.high_max);
            char text[64];
            if (calib_rule_text(val, "cores", text, sizeof(text))) {
                calib_set_core_range(p.high_cores, sizeof(p.high_cores), text);
            }
        } else if (strcmp(key, "group_mid") == 0) {
            p.mid_avg = calib_rule_number(val, "avg", p.mid_avg);
            p.mid_max = calib_rule_number(val, "max", p.mid_max);
            char text[64];
            if (calib_rule_text(val, "cores", text, sizeof(text))) {
                calib_set_core_range(p.mid_cores, sizeof(p.mid_cores), text);
            }
        } else if (strcmp(key, "max_thread_rules") == 0) {
            int n = atoi(val);
            if (n >= 1 && n <= 12) p.max_thread_rules = n;
        } else if (strcmp(key, "wildcard_group") == 0) {
            if (strcmp(val, "sum") == 0) {
                p.wildcard_mode = CALIB_WILDCARD_SUM;
            } else {
                p.wildcard_mode = CALIB_WILDCARD_MAX_MEMBER;
            }
        } else if (strcmp(key, "fallback") == 0) {
            char text[64];
            if (calib_rule_text(val, "cores", text, sizeof(text))) {
                calib_set_core_range(p.fallback_cores, sizeof(p.fallback_cores), text);
            } else if (!strchr(val, ':')) {
                calib_set_core_range(p.fallback_cores, sizeof(p.fallback_cores), val);
            }
        }
    }
    fclose(f);
    calib_log_policy(&p, topo, CALIB_POLICY_FILE, have_policy_stat ? &policy_st : NULL);
    return p;
}

/* 聚合后的通配组 */
typedef struct {
    char owner[MAX_PKG_LEN];     /* 规则所属真实进程名 */
    char base[MAX_THREAD_LEN];   /* 通配基名(可能含尾部 '*') */
    unsigned long long busy;     /* 组内线程 busy 之和 */
    double avg_pct;              /* 组内线程平均占比, 通配组按策略取最高成员或累加 */
    double max_pct;              /* 组内最高瞬时占比 */
    double score;                /* 综合评分, 用于 Top N 排序 */
    bool is_wild;                /* base 是否含通配符 */
} CalibGroup;

static int calib_group_tier(const CalibGroup* g, const CalibPolicy* p) {
    if (!g || !p) return 0;
    if (g->avg_pct >= p->high_avg && g->max_pct >= p->high_max) {
        return 2;  /* 高频中核 */
    }
    if (g->avg_pct >= p->mid_avg && g->max_pct >= p->mid_max) {
        return 1;  /* 中核 */
    }
    return 0;
}

static bool calib_append_rule(char** out, size_t* remain, int* lines,
                              const char* pkg, const char* thread,
                              const char* tier) {
    if (!out || !*out || !remain || !lines || !pkg || !tier || !tier[0]) return false;

    char line[MAX_PKG_LEN + MAX_THREAD_LEN + 128];
    int need;
    if (thread && thread[0]) {
        need = snprintf(line, sizeof(line), "%s{%s}=%s\n", pkg, thread, tier);
    } else {
        need = snprintf(line, sizeof(line), "%s=%s\n", pkg, tier);
    }
    if (need < 0 || (size_t)need >= sizeof(line) || (size_t)need > *remain) return false;

    memcpy(*out, line, (size_t)need);
    *out += need;
    *remain -= (size_t)need;
    (*lines)++;
    return true;
}

/* 根据采样结果生成规则文本, 追加写入 out_buf (调用方保证足够大)。
 * 返回生成的规则行数。 */
static int calib_generate_rules(CalibData* data, const CpuTopology* topo,
                                char* out_buf, size_t out_sz) {
    if (data->count == 0 || out_sz == 0) return 0;
    CalibPolicy policy = calib_load_policy(topo);

    /* 1) 按 busy 降序排序 */
    qsort(data->threads, data->count, sizeof(ThreadSample), calib_cmp_busy);

    /* 2) 主进程线程聚合为精确/通配组; 子进程整体样本稍后单独分级。 */
    CalibGroup* groups = calloc(data->count, sizeof(CalibGroup));
    if (!groups) return 0;
    size_t ng = 0;
    unsigned long long total = 0;
    for (size_t i = 0; i < data->count; i++) {
        total += data->threads[i].busy;
        if (data->threads[i].is_process ||
            strcmp(data->threads[i].owner, data->pkg) != 0) continue;
        char base[MAX_THREAD_LEN];
        calib_rule_base_for_thread(data, i, base, sizeof(base));
        if (!base[0]) continue;
        long gi = -1;
        for (size_t g = 0; g < ng; g++) {
            if (strcmp(groups[g].owner, data->threads[i].owner) == 0 &&
                strcmp(groups[g].base, base) == 0) { gi = (long)g; break; }
        }
        if (gi < 0) {
            gi = (long)ng++;
            build_str(groups[gi].owner, sizeof(groups[gi].owner),
                      data->threads[i].owner, NULL);
            build_str(groups[gi].base, sizeof(groups[gi].base), base, NULL);
            groups[gi].is_wild = (strchr(base, '*') != NULL);
            groups[gi].busy = 0;
            groups[gi].avg_pct = 0.0;
            groups[gi].max_pct = 0.0;
            groups[gi].score = 0.0;
        }
        groups[gi].busy += data->threads[i].busy;
        double avg_pct, max_pct;
        calib_thread_pct_stats(&data->threads[i], &avg_pct, &max_pct);
        if (groups[gi].is_wild && policy.wildcard_mode == CALIB_WILDCARD_MAX_MEMBER) {
            if (avg_pct > groups[gi].avg_pct) groups[gi].avg_pct = avg_pct;
        } else {
            groups[gi].avg_pct += avg_pct;
        }
        if (max_pct > groups[gi].max_pct) groups[gi].max_pct = max_pct;
    }
    if (total == 0) { free(groups); return 0; }

    for (size_t g = 0; g < ng; g++) {
        if (groups[g].avg_pct > 100.0) groups[g].avg_pct = 100.0;
        groups[g].score = calib_load_score(groups[g].avg_pct, groups[g].max_pct);
    }

    /* 组按 avg/max 综合评分降序, 峰值线程不会被累计 busy 掩盖。 */
    for (size_t a = 0; a + 1 < ng; a++)
        for (size_t b = 0; b + 1 < ng - a; b++)
            if (groups[b].score < groups[b + 1].score) {
                CalibGroup t = groups[b]; groups[b] = groups[b + 1]; groups[b + 1] = t;
            }

    /* 3) 按负载分级, 同时参考 avg 与 max:
     *    - 单个线程综合负载第一, 且 avg/max 都达到策略重载阈值: 独占最高性能簇, 并固定输出在第一行。
     *    - avg 与 max 同时达到 group_high 阈值: 高频中核。
     *    - avg 与 max 同时达到 group_mid 阈值: 中核。
     *    - 子进程按整体负载进入中档/高档, 不参与最佳线程竞争。
     *    - 其余由进程级兜底覆盖。
     *    不按线程名白名单/黑名单猜职责, 避免不同游戏命名差异导致误判。
     *    同档位先输出精确线程, 再输出通配组; 只保留 Top N, 避免规则过多。 */
    char big_thread[MAX_THREAD_LEN] = {0};
    char big_rule[MAX_THREAD_LEN] = {0};
    char big_owner[MAX_PKG_LEN] = {0};
    size_t best_idx = SIZE_MAX;
    double best_score = -1.0;
    double best_avg = 0.0;
    double best_max = 0.0;
    for (size_t i = 0; i < data->count; i++) {
        if (data->threads[i].is_process ||
            strcmp(data->threads[i].owner, data->pkg) != 0) continue;
        if (!calib_rule_name_syntax_ok(data->threads[i].name)) continue;
        double avg_pct, max_pct;
        calib_thread_pct_stats(&data->threads[i], &avg_pct, &max_pct);
        double score = calib_load_score(avg_pct, max_pct);
        if (score > best_score) {
            best_score = score;
            best_avg = avg_pct;
            best_max = max_pct;
            best_idx = i;
            build_str(big_owner, sizeof(big_owner), data->threads[i].owner, NULL);
            build_str(big_thread, sizeof(big_thread), data->threads[i].name, NULL);
        }
    }
    if (best_idx != SIZE_MAX) {
        calib_rule_base_for_thread(data, best_idx, big_rule, sizeof(big_rule));
    }

    char* p = out_buf;
    size_t remain = out_sz - 1;
    int lines = 0;
    int thread_lines = 0;
    const int max_thread_lines = policy.max_thread_rules;
    const bool big_rule_is_wild = (strchr(big_rule, '*') != NULL);
    const bool has_big_thread = big_thread[0] && big_rule[0] && !big_rule_is_wild &&
        (best_avg >= policy.best_avg && best_max >= policy.best_max);
    const char* big_tier = calib_policy_core_range(topo, policy.best_tier, policy.best_cores);
    const char* base_tier = calib_policy_core_range(topo, policy.fallback_tier, policy.fallback_cores);
    size_t fallback_reserve = 0;
    if (base_tier && base_tier[0]) {
        fallback_reserve = strlen(data->pkg) + 1 + strlen(base_tier) + 1;
        if (fallback_reserve > remain) fallback_reserve = remain;
    }
    size_t explicit_remain = remain - fallback_reserve;

    if (has_big_thread && big_tier && big_tier[0]) {
        if (!calib_append_rule(&p, &explicit_remain, &lines, big_owner, big_rule, big_tier))
            goto append_fallback;
        thread_lines++;
    }

    for (int tier_pass = 2; tier_pass >= 1 && thread_lines < max_thread_lines; tier_pass--) {
        for (int wild_pass = 0; wild_pass <= 1 && thread_lines < max_thread_lines; wild_pass++) {
            for (size_t g = 0; g < ng && thread_lines < max_thread_lines; g++) {
                if ((groups[g].is_wild ? 1 : 0) != wild_pass) continue;
                if (calib_group_tier(&groups[g], &policy) != tier_pass) continue;
                if (has_big_thread && strcmp(groups[g].owner, big_owner) == 0) {
                    if (strcmp(groups[g].base, big_rule) == 0) continue;
                    if (strchr(big_rule, '*') != NULL && !groups[g].is_wild) {
                        if (fnmatch(big_rule, groups[g].base, FNM_NOESCAPE) == 0) continue;
                    } else if (groups[g].is_wild) {
                        if (fnmatch(groups[g].base, big_thread, FNM_NOESCAPE) == 0) continue;
                    } else if (strcmp(groups[g].base, big_thread) == 0) {
                        continue;
                    }
                }

                const char* tier = (tier_pass == 2) ?
                    calib_policy_core_range(topo, policy.high_tier, policy.high_cores) :
                    calib_policy_core_range(topo, policy.mid_tier, policy.mid_cores);
                if (!tier || !tier[0]) continue;
                if (!calib_append_rule(&p, &explicit_remain, &lines,
                                       groups[g].owner, groups[g].base, tier))
                    goto append_fallback;
                thread_lines++;
            }
        }
    }

    /* 子进程只生成进程级规则。未达到阈值的子进程不写独立规则,
     * 运行时继续继承主进程兜底核心。 */
    for (int tier_pass = 2; tier_pass >= 1; tier_pass--) {
        for (size_t i = 0; i < data->count; i++) {
            ThreadSample* s = &data->threads[i];
            if (!s->is_process || strcmp(s->owner, data->pkg) == 0) continue;
            double avg_pct, max_pct;
            calib_thread_pct_stats(s, &avg_pct, &max_pct);
            CalibGroup process_group = {
                .avg_pct = avg_pct,
                .max_pct = max_pct
            };
            if (calib_group_tier(&process_group, &policy) != tier_pass) continue;
            const char* tier = (tier_pass == 2) ?
                calib_policy_core_range(topo, policy.high_tier, policy.high_cores) :
                calib_policy_core_range(topo, policy.mid_tier, policy.mid_cores);
            if (!tier || !tier[0]) continue;
            if (!calib_append_rule(&p, &explicit_remain, &lines, s->owner, NULL, tier))
                goto append_fallback;
        }
    }

    /* 4) 进程级兜底规则: 默认使用小核+中核(e_core+p_core), 对齐常见 0-6。
     *    用户也可在策略中改为中核/中高性能核心/全部核心。 */
append_fallback:
    remain = explicit_remain + fallback_reserve;
    if (base_tier && base_tier[0]) {
        calib_append_rule(&p, &remain, &lines, data->pkg, NULL, base_tier);
    }
    *p = '\0';
    free(groups);
    return lines;
}

/* 把主进程线程和子进程整体负载写入 history/<pkg>.log。
 * 格式: 每段首行 "# <epoch> <采样轮数>", 随后每行:
 *   "<AVG%> <MAX%> <名称>|<p1,p2,...,pN>[|v2:子线程名,AVG,MAX;...]"
 * 其中 p* 为每轮采样的瞬时占比(%)。每个包名最多保留最近 HISTORY_MAX_SESSIONS 段会话。 */
#define HISTORY_MAX_SESSIONS 7

static void calib_write_history_name(FILE* out, const char* name) {
    if (!out || !name) return;
    for (size_t i = 0; name[i]; i++) {
        unsigned char c = (unsigned char)name[i];
        if (c == '|' || c == ',' || c == ';' || c == '\n' || c == '\r' || c < 0x20) {
            fputc('_', out);
        } else {
            fputc(c, out);
        }
    }
}

static void calib_write_child_thread_summary(FILE* out, const CalibData* data,
                                             const char* owner) {
    size_t available = 0;
    for (size_t i = 0; i < data->child_thread_count; i++) {
        const ChildThreadSummary* s = &data->child_threads[i];
        if (strcmp(s->owner, owner) != 0 || s->sample_count == 0) continue;
        double avg = s->sum_pct / (double)s->sample_count;
        if (s->max_pct >= 0.05 || avg >= 0.05) available++;
    }
    if (available == 0) return;

    bool* written = calloc(data->child_thread_count, sizeof(bool));
    if (!written) return;
    fputs("|v2:", out);
    for (size_t slot = 0; slot < available; slot++) {
        size_t best = SIZE_MAX;
        double best_avg = -1.0;
        double best_max = -1.0;
        for (size_t i = 0; i < data->child_thread_count; i++) {
            const ChildThreadSummary* s = &data->child_threads[i];
            if (written[i] || strcmp(s->owner, owner) != 0 || s->sample_count == 0) continue;
            double avg = s->sum_pct / (double)s->sample_count;
            if (s->max_pct < 0.05 && avg < 0.05) continue;
            if (avg > best_avg || (avg == best_avg && s->max_pct > best_max)) {
                best = i;
                best_avg = avg;
                best_max = s->max_pct;
            }
        }
        if (best == SIZE_MAX) break;
        written[best] = true;
        if (slot > 0) fputc(';', out);
        calib_write_history_name(out, data->child_threads[best].name);
        fprintf(out, ",%.2f,%.2f", best_avg, best_max);
    }
    free(written);
}

static void calib_write_history(const char* pkg, CalibData* data) {
    if (data->count == 0) return;
    mkdir(HISTORY_DIR, 0755);

    unsigned long long total = 0;
    for (size_t i = 0; i < data->count; i++) total += data->threads[i].busy;
    if (total == 0) return;

    char path[512];
    char safe[MAX_PKG_LEN];
    safe_history_filename(pkg, safe, sizeof(safe));
    build_str(path, sizeof(path), HISTORY_DIR, "/", safe, ".log", NULL);

    /* 1) 把本次会话格式化到内存缓冲: 段头 + 每条负载记录(AVG MAX 名称|折线) */
    char* cur = NULL;
    size_t cur_len = 0;
    FILE* mem = open_memstream(&cur, &cur_len);
    if (mem) {
        fprintf(mem, "# %ld %zu\n", (long)time(NULL), data->round_count);
        for (size_t i = 0; i < data->count; i++) {
            ThreadSample* s = &data->threads[i];
            if (s->series_len == 0) continue;
            double avg = 0.0;
            double mx = 0.0;
            calib_thread_pct_stats(s, &avg, &mx);
            if (mx < 0.05 && avg < 0.05) continue;   /* 整段几乎零负载的线程略过 */
            if (s->is_process) {
                fprintf(mem, "%.2f %.2f %s|", avg, mx, s->owner);
            } else if (strcmp(s->owner, pkg) == 0) {
                fprintf(mem, "%.2f %.2f %s|", avg, mx, s->name);
            } else {
                fprintf(mem, "%.2f %.2f %s{%s}|", avg, mx, s->owner, s->name);
            }
            for (size_t k = 0; k < s->series_len; k++) {
                size_t index = (s->series_start + k) % s->series_cap;
                fprintf(mem, k ? ",%.2f" : "%.2f", s->series[index]);
            }
            if (s->is_process) {
                calib_write_child_thread_summary(mem, data, s->owner);
            }
            fputc('\n', mem);
        }
        fclose(mem);
    }
    if (!cur) return;

    /* 2) 读入已有内容, 找到各段(以 '#' 开头的行)的起始偏移 */
    char* old = NULL;
    long old_len = 0;
    FILE* rf = fopen(path, "r");
    if (rf) {
        fseek(rf, 0, SEEK_END);
        old_len = ftell(rf);
        fseek(rf, 0, SEEK_SET);
        if (old_len > 0) {
            old = malloc((size_t)old_len + 1);
            if (old) {
                size_t rd = fread(old, 1, (size_t)old_len, rf);
                old[rd] = '\0';
            }
        }
        fclose(rf);
    }

    /* 3) 统计旧段数, 计算需保留的旧段起点 (保留最近 MAX-1 段, 给本次留一段) */
    const char* keep_from = old;   /* 默认全部保留 */
    if (old) {
        /* 收集每个 '#' 段的行首指针 */
        size_t seg_count = 0;
        const char* offsets[256];
        int at_line_start = 1;
        for (char* p = old; *p; p++) {
            if (at_line_start && *p == '#') {
                if (seg_count < 256) offsets[seg_count] = p;
                seg_count++;
            }
            at_line_start = (*p == '\n');
        }
        size_t want_old = HISTORY_MAX_SESSIONS - 1;   /* 本次占 1 段 */
        if (seg_count > want_old) {
            size_t drop = seg_count - want_old;
            if (drop < seg_count && drop < 256) keep_from = offsets[drop];
        }
    }

    /* 4) 原子写入文件: 先写临时文件, 成功后 rename 覆盖原文件。
     *    避免 fopen(w) 直接清空导致写入失败时数据全丢。
     *    务必保证旧段以换行结尾, 否则新段头 "# ..." 会粘到旧段末行。 */
    char tmp_path[520];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", path);
    FILE* wf = fopen(tmp_path, "w");
    if (wf) {
        bool write_ok = true;
        if (keep_from && *keep_from) {
            if (fputs(keep_from, wf) == EOF) write_ok = false;
            size_t kl = strlen(keep_from);
            if (write_ok && kl > 0 && keep_from[kl - 1] != '\n') {
                if (fputc('\n', wf) == EOF) write_ok = false;
            }
        }
        if (write_ok && fputs(cur, wf) == EOF) write_ok = false;
        if (fclose(wf) != 0) write_ok = false;

        if (write_ok) {
            rename(tmp_path, path);  // 原子覆盖
        } else {
            unlink(tmp_path);        // 写入失败, 删除临时文件, 保留原文件
        }
    }

    free(old);
    free(cur);
}

static bool calib_write_line_normalized(FILE* out, const char* text) {
    if (!out || !text) return false;
    size_t len = strlen(text);
    if (len > 0 && fwrite(text, 1, len, out) != len) return false;
    if (len == 0 || text[len - 1] != '\n') {
        if (fputc('\n', out) == EOF) return false;
    }
    return true;
}

static bool calib_write_rules_block(FILE* out, const char* rules_text) {
    if (!out || !rules_text) return false;
    size_t len = strlen(rules_text);
    while (len > 0 && (rules_text[len - 1] == '\n' || rules_text[len - 1] == '\r')) len--;
    if (len == 0) return false;
    if (fwrite(rules_text, 1, len, out) != len) return false;
    return fputc('\n', out) != EOF;
}

static bool calib_line_is_blank(const char* line) {
    if (!line) return true;
    for (const char* p = line; *p; p++) {
        if (*p == '\n' || *p == '\r') continue;
        if (!isspace((unsigned char)*p)) return false;
    }
    return true;
}

typedef struct {
    char** lines;
    size_t count;
    size_t cap;
    char group[MAX_PKG_LEN];
    bool has_owner;
} CalibConfigBlock;

static void calib_config_block_clear(CalibConfigBlock* block) {
    if (!block) return;
    for (size_t i = 0; i < block->count; i++) free(block->lines[i]);
    free(block->lines);
    memset(block, 0, sizeof(*block));
}

static bool calib_config_block_add(CalibConfigBlock* block, const char* raw_line) {
    if (!block || !raw_line) return false;
    char* copy = strdup(raw_line);
    if (!copy) return false;
    char* trimmed = strtrim(copy);
    char* line = strdup(trimmed);
    free(copy);
    if (!line) return false;
    if (line[0] == '\0') { free(line); return true; }

    if (block->count == block->cap) {
        size_t next = block->cap ? block->cap * 2 : 8;
        char** grown = realloc(block->lines, next * sizeof(char*));
        if (!grown) { free(line); return false; }
        block->lines = grown;
        block->cap = next;
    }
    block->lines[block->count++] = line;
    return true;
}

static void calib_config_group_name(const char* owner, char* out, size_t out_sz) {
    if (!out || out_sz == 0) return;
    out[0] = '\0';
    if (!owner || !owner[0]) return;

    char tmp[MAX_PKG_LEN];
    build_str(tmp, sizeof(tmp), owner, NULL);
    char* colon = strchr(tmp, ':');
    if (colon) {
        *colon = '\0';
        if (strchr(tmp, '.')) {
            build_str(out, out_sz, tmp, NULL);
            return;
        }
    }
    build_str(out, out_sz, owner, NULL);
}

static bool calib_config_line_group(const char* raw_line, char* out, size_t out_sz) {
    if (!raw_line || !out || out_sz == 0) return false;
    out[0] = '\0';

    char* copy = strdup(raw_line);
    if (!copy) return false;
    char* t = strtrim(copy);
    if (*t == '\0' || *t == '#') { free(copy); return false; }

    char* eq = strchr(t, '=');
    if (!eq) { free(copy); return false; }
    *eq = '\0';
    char* owner = strtrim(t);
    char* brace = strchr(owner, '{');
    if (brace) {
        *brace = '\0';
        owner = strtrim(owner);
    }
    if (*owner == '\0') { free(copy); return false; }

    calib_config_group_name(owner, out, out_sz);
    free(copy);
    return out[0] != '\0';
}

static bool calib_write_config_block(FILE* out, const CalibConfigBlock* block, bool* wrote_any) {
    if (!out || !block || block->count == 0) return true;
    if (wrote_any && *wrote_any) {
        if (fputc('\n', out) == EOF) return false;
    }
    for (size_t i = 0; i < block->count; i++) {
        if (!calib_write_line_normalized(out, block->lines[i])) return false;
    }
    if (wrote_any) *wrote_any = true;
    return true;
}

static bool calib_write_rules_block_separated(FILE* out, const char* rules_text, bool* wrote_any) {
    if (!out || !rules_text) return false;
    if (wrote_any && *wrote_any) {
        if (fputc('\n', out) == EOF) return false;
    }
    if (!calib_write_rules_block(out, rules_text)) return false;
    if (wrote_any) *wrote_any = true;
    return true;
}

static bool calib_flush_config_block(FILE* out, CalibConfigBlock* block,
                                     const char* target_group, const char* rules_text,
                                     bool* wrote_any, bool* inserted) {
    if (!block || block->count == 0) return true;
    bool is_target = block->has_owner && target_group && strcmp(block->group, target_group) == 0;
    bool ok = true;
    if (is_target) {
        if (inserted && !*inserted) {
            ok = calib_write_rules_block_separated(out, rules_text, wrote_any);
            *inserted = ok;
        }
    } else {
        ok = calib_write_config_block(out, block, wrote_any);
    }
    calib_config_block_clear(block);
    return ok;
}

/* 把生成的规则写回配置文件: 同一应用/子进程视为一块, 自动校准完成后整体替换该应用旧块。
 * 这样可以清理重复 auto/旧规则, 并把应用块之间规整为一空行。成功返回 true。 */
static bool calib_write_back(const char* config_file, const char* pkg,
                             const char* rules_text) {
    FILE* in = fopen(config_file, "r");
    if (!in) return false;
    char tmp_path[4096 + 8];
    build_str(tmp_path, sizeof(tmp_path), config_file, ".tmp", NULL);
    FILE* out = fopen(tmp_path, "w");
    if (!out) { fclose(in); return false; }

    char target_group[MAX_PKG_LEN];
    calib_config_group_name(pkg, target_group, sizeof(target_group));

    char* line = NULL;
    size_t line_cap = 0;
    CalibConfigBlock block = {0};
    bool inserted = false;
    bool wrote_any = false;
    bool write_ok = true;
    while (write_ok && getline(&line, &line_cap, in) != -1) {
        if (calib_line_is_blank(line)) continue;

        char line_group[MAX_PKG_LEN];
        bool has_owner = calib_config_line_group(line, line_group, sizeof(line_group));
        if (has_owner) {
            if (block.count > 0) {
                if (!block.has_owner || strcmp(block.group, line_group) != 0) {
                    write_ok = calib_flush_config_block(out, &block, target_group, rules_text,
                                                        &wrote_any, &inserted);
                    if (!write_ok) break;
                }
            }
            if (block.count == 0) {
                block.has_owner = true;
                build_str(block.group, sizeof(block.group), line_group, NULL);
            }
        } else if (block.count > 0 && block.has_owner) {
            write_ok = calib_flush_config_block(out, &block, target_group, rules_text,
                                                &wrote_any, &inserted);
            if (!write_ok) break;
        }

        write_ok = calib_config_block_add(&block, line);
    }
    if (write_ok) {
        write_ok = calib_flush_config_block(out, &block, target_group, rules_text,
                                            &wrote_any, &inserted);
    } else {
        calib_config_block_clear(&block);
    }
    if (write_ok && !inserted) {
        write_ok = calib_write_rules_block_separated(out, rules_text, &wrote_any);
    }
    free(line);
    fclose(in);
    if (fclose(out) != 0) write_ok = false;
    if (!write_ok) {
        unlink(tmp_path);
        return false;
    }
    if (rename(tmp_path, config_file) != 0) {
        unlink(tmp_path);
        return false;
    }
    return true;
}

/* 写状态文件供 App 读取 */
static void calib_set_state(const char* state) {
    write_file(AT_FDCWD, CALIB_STATE_FILE, state, O_WRONLY | O_CREAT | O_TRUNC);
}

static bool is_start_command(const char* cmd) {
    return cmd && strncmp(cmd, "start ", 6) == 0 && cmd[6] != '\0';
}

static bool is_stop_command(const char* cmd) {
    return cmd && (strcmp(cmd, "stop") == 0 || strncmp(cmd, "stop ", 5) == 0);
}

static bool calib_cmd_valid(const char* cmd) {
    return is_start_command(cmd) || is_stop_command(cmd);
}

/* 读取并清空命令文件; 返回是否读到内容。cmd_buf 收到形如 "start <pkg>" 的命令 */
static bool calib_read_cmd(char* cmd_buf, size_t sz) {
    return read_stable_command_file(CALIB_CMD_FILE, cmd_buf, sz, calib_cmd_valid);
}

static void calib_free(CalibData* d) {
    if (d->threads) {
        for (size_t i = 0; i < d->count; i++) free(d->threads[i].series);
    }
    free(d->threads);
    free(d->tids);
    free(d->child_threads);
    d->threads = NULL;
    d->tids = NULL;
    d->child_threads = NULL;
    d->count = d->cap = 0;
    d->tcount = d->tcap = 0;
    d->child_thread_count = d->child_thread_cap = 0;
    d->round_count = 0;
    d->last_sample_ms = 0;
    d->clock_ticks_per_second = 0;
}

/*
 * 校准线程主循环。协议(均为纯文本文件):
 *   App  -> 守护:  写 CALIB_CMD_FILE, 内容 "start <pkg>" / "stop <pkg>"
 *   守护 -> App:   写 CALIB_STATE_FILE, 内容 "idle" / "sampling <pkg>" / "done <pkg>"
 * 帧率由 App 侧自行采集与显示, 守护进程仅负责线程负载采样与规则生成。
 */
static void* calib_thread(void* arg) {
    (void)arg;
    pthread_setname_np(pthread_self(), "AppOptCalib");
    calib_set_state("idle");

    bool sampling = false;
    CalibData data = {0};
    char cmd[256];

    for (;;) {
        if (calib_read_cmd(cmd, sizeof(cmd))) {
            if (is_start_command(cmd)) {
                const char* pkg = strtrim(cmd + 6);
                CalibProcess probe[8];
                size_t np = collect_pkg_processes(pkg, probe, 8);
                if (np > 0 && strlen(pkg) < MAX_PKG_LEN) {
                    calib_free(&data);
                    memset(&data, 0, sizeof(data));
                    build_str(data.pkg, sizeof(data.pkg), pkg, NULL);
                    sampling = true;
                    char st[MAX_PKG_LEN + 16];
                    snprintf(st, sizeof(st), "sampling %s", pkg);
                    calib_set_state(st);
                    printf("[校准] 开始采样 %s (%zu 个进程)\n", pkg, np);
                } else {
                    printf("[校准] 忽略 start: %s 未找到运行中的进程 (应用未启动?) 或包名过长\n", pkg);
                }
            } else if (is_stop_command(cmd)) {
                if (sampling) {
                    sampling = false;
                    printf("[校准] 收到停止命令: %s 共采样 %zu 轮, 记录 %zu 个负载项(跟踪 %zu 个TID, 子进程活跃线程摘要 %zu 个), 开始生成规则\n",
                           data.pkg, data.round_count, data.count, data.tcount,
                           data.child_thread_count);

                    /* 最低采样要求: 轮数不足时不生成规则, 否则数据不足以反映真实负载 */
                    if (data.round_count < CALIB_MIN_ROUNDS) {
                        printf("[校准] 警告: %s 采样时长不足 (仅 %zu 轮, 建议 >=%d 轮), 未生成规则\n",
                               data.pkg, data.round_count, CALIB_MIN_ROUNDS);
                        /* 采样不足时不保存历史记录, 避免垃圾数据干扰分析 */
                        char st[MAX_PKG_LEN + 64];
                        snprintf(st, sizeof(st), "done %s;reason=short", data.pkg);
                        calib_set_state(st);
                        calib_free(&data);
                        continue;
                    }

                    char rules[4096];
                    int n = calib_generate_rules(&data, &g_topo, rules, sizeof(rules));
                    calib_write_history(data.pkg, &data);
                    char st[MAX_PKG_LEN + 64];
                    if (n > 0) {
                        AppConfig* cfg = get_config();
                        const char* cf = cfg ? cfg->config_file : g_config_file;
                        if (calib_write_back(cf, data.pkg, rules)) {
                            printf("[校准] 已为 %s 生成 %d 条规则:\n%s", data.pkg, n, rules);
                            atomic_store(&config_updated, 1);
                            snprintf(st, sizeof(st), "done %s", data.pkg);
                        } else {
                            printf("[校准] 警告: %s 规则写回配置失败 (路径 %s)\n", data.pkg, cf);
                            snprintf(st, sizeof(st), "done %s;reason=write_fail", data.pkg);
                        }
                        if (cfg) config_release(cfg);
                    } else {
                        printf("[校准] 警告: %s 未能生成规则 (负载样本不足?)\n", data.pkg);
                        snprintf(st, sizeof(st), "done %s;reason=no_load", data.pkg);
                    }
                    calib_set_state(st);
                    calib_free(&data);
                }
            }
        }

        if (sampling) {
            size_t prev_rounds = data.round_count;
            if (!calib_sample_once(&data)) {
                /* 进程消失, 用已采集数据直接出规则 */
                sampling = false;
                printf("[校准] %s 进程已退出, 用现有 %zu 轮/%zu 个负载项(跟踪 %zu 个TID, 子进程活跃线程摘要 %zu 个)数据直接生成规则\n",
                       data.pkg, data.round_count, data.count, data.tcount,
                       data.child_thread_count);
                if (data.round_count < CALIB_MIN_ROUNDS) {
                    printf("[校准] 警告: %s 进程退出时采样不足 (仅 %zu 轮, 建议 >=%d 轮), 未生成规则\n",
                           data.pkg, data.round_count, CALIB_MIN_ROUNDS);
                    char st[MAX_PKG_LEN + 64];
                    snprintf(st, sizeof(st), "done %s;reason=short", data.pkg);
                    calib_set_state(st);
                    calib_free(&data);
                    continue;
                }
                char rules[4096];
                int n = calib_generate_rules(&data, &g_topo, rules, sizeof(rules));
                calib_write_history(data.pkg, &data);
                char st[MAX_PKG_LEN + 64];
                if (n > 0) {
                    AppConfig* cfg = get_config();
                    const char* cf = cfg ? cfg->config_file : g_config_file;
                    if (calib_write_back(cf, data.pkg, rules)) {
                        printf("[校准] 已为 %s 生成 %d 条规则:\n%s", data.pkg, n, rules);
                        atomic_store(&config_updated, 1);
                        snprintf(st, sizeof(st), "done %s", data.pkg);
                    } else {
                        printf("[校准] 警告: %s 规则写回配置失败 (路径 %s)\n", data.pkg, cf);
                        snprintf(st, sizeof(st), "done %s;reason=write_fail", data.pkg);
                    }
                    if (cfg) config_release(cfg);
                } else {
                    printf("[校准] 警告: %s 未能生成规则 (负载样本不足?)\n", data.pkg);
                    snprintf(st, sizeof(st), "done %s;reason=no_load", data.pkg);
                }
                calib_set_state(st);
                calib_free(&data);
            } else if (data.round_count != prev_rounds && data.round_count % 20 == 0) {
                /* 每 20 轮(约 10s)报一次进度, 避免每 0.5s 刷屏 */
                printf("[校准] %s 采样中... 已 %zu 轮, 当前记录 %zu 个负载项(跟踪 %zu 个TID, 子进程活跃线程摘要 %zu 个)\n",
                       data.pkg, data.round_count, data.count, data.tcount,
                       data.child_thread_count);
            }
        }
        usleep(500 * 1000);   /* 0.5s 采样周期 */
    }
    return NULL;
}

/* =====================================================================
 * 真实帧率(FPS)监测模块
 *
 * 优先使用 eBPF uprobe 监听 libgui queueBuffer 事件, 按目标 PID/包名过滤后
 * 计算最近窗口 FPS。eBPF 不可用时, 降级为 SurfaceFlinger binder 直连 dump:
 *   1) --latency: 差分读取目标 layer 的上屏时间戳, 不清空全局缓冲。
 *   2) --timestats: Android 16 等 --latency 不再吐帧时间戳时的兜底。
 *
 * 输出优先走 App 创建的本地 socket; socket 不可用才写 FPS_OUT_FILE。
 * 对 App 的输出频率由 FPS_WINDOW_MS 控制, 避免悬浮胶囊数值跳动过快。
 * ===================================================================== */

#define SF_MAX_CANDS  16
#define SF_LAYER_MAX  256

/* =====================================================================
 * 直连 binder 抓 SurfaceFlinger dump (对齐 Scene 思路, 替代 fork dumpsys)
 *
 * 原理: dumpsys 本身就是通过 binder 向服务发 DUMP_TRANSACTION
 *   (code = B_PACK_CHARS('_','D','M','P')), 传入一个 fd 和参数列表
 *   (如 "--latency" "<layer>"), 服务把 dump 文本写进该 fd。这里直接
 *   走 /dev/binder ioctl 复刻该过程:
 *     1) 向 servicemanager(handle 0) 发 checkService("SurfaceFlinger")
 *        取得 SF 的 binder handle(缓存复用, 仅首次有一次 binder 往返)。
 *     2) 建管道, 把写端 fd 经 binder 传给 SF, 发 DUMP_TRANSACTION 附 dump
 *        参数; 后台读线程从读端收 dump 文本(防 SF 写满管道阻塞)。
 *   这样每个采样窗口不再 fork /system/bin/dumpsys, 开销大幅降低。
 *
 * 通杀 Android 12-16: 仅依赖 /dev/binder uapi(linux/android/binder.h),
 *   该 ABI 长期稳定; writeInterfaceToken 的 strictmode/worksource/'SYST'
 *   头在 Android 8+ 一致。不定义 BINDER_IPC_32BIT, 故 32/64 位 ABI 共用同一
 *   套结构体布局(binder_uintptr_t 均为 __u64), 全 ABI 启用。任一步失败
 *   (取不到 handle / ioctl 被 SELinux 拒 / 真 32 位内核 ABI 不匹配)即永久
 *   回退 CLI dumpsys。
 * ===================================================================== */
#ifdef APPOPT_HAVE_BINDER
#define SF_DUMP_CODE   ((uint32_t)B_PACK_CHARS('_','D','M','P'))  /* DUMP_TRANSACTION */
#define SVC_CHECK_CODE 2u                          /* IServiceManager::checkService */

static int     g_bnd_fd    = -2;     /* -2 未尝试; -1 已判定不可用; >=0 已打开 */
static void*   g_bnd_map   = NULL;
static size_t  g_bnd_mapsz = 0;
static uint32_t g_sf_handle = 0;
static bool    g_sf_have   = false;

/* 极简 Parcel 写入器(写进调用方提供的栈缓冲, 记录 binder 对象偏移) */
typedef struct { uint8_t* b; size_t pos, cap; binder_size_t off[2]; size_t noff; bool bad; } bparcel;
static void bp_init(bparcel* p, uint8_t* buf, size_t cap){ p->b=buf; p->pos=0; p->cap=cap; p->noff=0; p->bad=false; }
static void bp_raw(bparcel* p, const void* s, size_t n){ if(p->pos+n>p->cap){p->bad=true;return;} memcpy(p->b+p->pos,s,n); p->pos+=n; }
static void bp_pad(bparcel* p){ while(p->pos & 3u){ if(p->pos>=p->cap){p->bad=true;return;} p->b[p->pos++]=0; } }
static void bp_i32(bparcel* p, int32_t v){ bp_raw(p,&v,4); }
/* UTF-16 字符串(Parcel::writeString16): int32 字符数, 然后 char16 + 结尾 0, 4 字节对齐 */
static void bp_str16(bparcel* p, const char* s){
    size_t n = s ? strlen(s) : 0;
    bp_i32(p, (int32_t)n);
    for (size_t i=0;i<n;i++){ uint16_t c=(uint8_t)s[i]; bp_raw(p,&c,2); }
    uint16_t z=0; bp_raw(p,&z,2);
    bp_pad(p);
}
/* writeInterfaceToken: int32 strict_policy | (kHeader<<16) , int32 work_source(-1),
 * int32 'SYST'(header marker), 再 writeString16(interface)。匹配 Android 10+。 */
static void bp_iface(bparcel* p, const char* iface){
    bp_i32(p, (int32_t)0x9c | (int32_t)(0x53 << 16)); /* STRICT_MODE_PENALTY 占位 | header 'S' 高位 */
    bp_i32(p, -1);                                    /* work source uid = unset */
    bp_i32(p, (int32_t)B_PACK_CHARS('S','Y','S','T'));/* kHeader: header token marker */
    bp_str16(p, iface);
}
/* 写一个 binder fd 对象(flat_binder_object, type=BINDER_TYPE_FD) 到 parcel,
 * 同时登记 object 偏移。复刻 Parcel::writeFileDescriptor: 直接写对象,
 * flags=0x7f|ACCEPTS_FDS, cookie=0(不转移所有权, 我们自己关)。 */
static void bp_fd(bparcel* p, int fd){
    if (p->noff >= 2) { p->bad=true; return; }
    bp_pad(p);
    struct flat_binder_object obj; memset(&obj,0,sizeof(obj));
    obj.hdr.type = BINDER_TYPE_FD;
    obj.flags = 0;                   /* 与 libbinder writeFileDescriptor 一致: fd 对象 flags=0 */
    obj.cookie = 0;                  /* takeOwnership=false(驱动只 dup, 我们自己关) */
    obj.binder = 0;
    obj.handle = (uint32_t)fd;
    binder_size_t at = p->pos;
    bp_raw(p, &obj, sizeof(obj));
    p->off[p->noff++] = at;
}

/* 打开并 mmap /dev/binder, 完成 BINDER_VERSION 协商。成功返回 fd, 失败 -1。 */
static int bnd_open(void){
    int fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
    if (fd < 0) { printf("[FPS][binder] open /dev/binder 失败: %s\n", strerror(errno)); return -1; }
    struct binder_version ver; memset(&ver,0,sizeof(ver));
    if (ioctl(fd, BINDER_VERSION, &ver) < 0) {
        printf("[FPS][binder] BINDER_VERSION ioctl 失败: %s\n", strerror(errno)); close(fd); return -1;
    }
    if (ver.protocol_version != BINDER_CURRENT_PROTOCOL_VERSION) {
        printf("[FPS][binder] 协议版本 %d != %d\n", ver.protocol_version, BINDER_CURRENT_PROTOCOL_VERSION);
        close(fd); return -1;
    }
    size_t msz = 128 * 1024;        /* 只收 dump 应答头, 128KB 足够; dump 正文走管道 */
    void* m = mmap(NULL, msz, PROT_READ, MAP_PRIVATE, fd, 0);
    if (m == MAP_FAILED) { printf("[FPS][binder] mmap 失败: %s\n", strerror(errno)); close(fd); return -1; }
    uint32_t mx = 0;                /* 限制 binder 线程数为 0(只用本线程同步收发) */
    ioctl(fd, BINDER_SET_MAX_THREADS, &mx);
    g_bnd_map = m; g_bnd_mapsz = msz;
    return fd;
}

/* 执行一次同步 binder 事务(handle, code, 入参 parcel), 收集 reply 数据。
 * reply 数据写入 rbuf(返回长度), 同时通过 *txn_err 返回事务状态(0=成功)。
 * 仅解析我们需要的命令: BR_REPLY(取 reply parcel) / BR_TRANSACTION_COMPLETE /
 * BR_DEAD_REPLY / BR_FAILED_REPLY。其余命令按其负载长度跳过。
 * 返回 reply 字节数(可能 0), 失败返回 -1。 */
static ssize_t bnd_transact(int fd, uint32_t handle, uint32_t code,
                            const bparcel* in, uint8_t* rbuf, size_t rcap,
                            int* txn_err){
    struct binder_transaction_data tr; memset(&tr,0,sizeof(tr));
    tr.target.handle = handle;
    tr.code = code;
    tr.flags = 0;                                  /* 同步事务(非 oneway) */
    tr.data_size = in->pos;
    tr.offsets_size = in->noff * sizeof(binder_size_t);
    tr.data.ptr.buffer = (binder_uintptr_t)(uintptr_t)in->b;
    tr.data.ptr.offsets = (binder_uintptr_t)(uintptr_t)in->off;

    /* 写缓冲: cmd(BC_TRANSACTION) + binder_transaction_data */
    uint8_t wb[sizeof(uint32_t)+sizeof(tr)];
    uint32_t bc = BC_TRANSACTION;
    memcpy(wb, &bc, sizeof(bc));
    memcpy(wb+sizeof(bc), &tr, sizeof(tr));

    uint8_t rb[4096];
    struct binder_write_read bwr;
    bool wrote = false, got_reply = false, completed = false;
    ssize_t rlen = 0; if (txn_err) *txn_err = 0;

    for (int spin = 0; spin < 64; spin++) {
        memset(&bwr,0,sizeof(bwr));
        if (!wrote) {
            bwr.write_buffer = (binder_uintptr_t)(uintptr_t)wb;
            bwr.write_size   = sizeof(wb);
        }
        bwr.read_buffer = (binder_uintptr_t)(uintptr_t)rb;
        bwr.read_size   = sizeof(rb);
        if (ioctl(fd, BINDER_WRITE_READ, &bwr) < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (bwr.write_consumed >= sizeof(wb)) wrote = true;

        /* 解析 read 返回的命令流 */
        size_t off = 0;
        while (off + sizeof(uint32_t) <= bwr.read_consumed) {
            uint32_t cmd; memcpy(&cmd, rb+off, sizeof(cmd)); off += sizeof(cmd);
            switch (cmd) {
            case BR_TRANSACTION_COMPLETE:
                completed = true; break;
            case BR_NOOP: case BR_SPAWN_LOOPER: break;
            case BR_INCREFS: case BR_ACQUIRE:
            case BR_RELEASE: case BR_DECREFS:
                off += 2*sizeof(binder_uintptr_t); break;   /* ptr+cookie, 忽略 */
            case BR_DEAD_REPLY: case BR_FAILED_REPLY:
                if (txn_err) *txn_err = -1; got_reply = true; break;
            case BR_ERROR:
                off += sizeof(uint32_t); if (txn_err) *txn_err = -1; got_reply = true; break;
            case BR_REPLY: {
                struct binder_transaction_data rt;
                if (off + sizeof(rt) > bwr.read_consumed) { return -1; }
                memcpy(&rt, rb+off, sizeof(rt)); off += sizeof(rt);
                if (rt.flags & TF_STATUS_CODE) {
                    int32_t st=0;
                    if (rt.data_size>=4 && rt.data.ptr.buffer)   /* 同数据路径: 判 buffer 非空再读 */
                        memcpy(&st,(void*)(uintptr_t)rt.data.ptr.buffer,4);
                    if (txn_err) *txn_err = st ? st : -1;
                } else if (rbuf && rcap) {
                    size_t cp = rt.data_size < rcap ? rt.data_size : rcap;
                    if (rt.data.ptr.buffer) memcpy(rbuf,(void*)(uintptr_t)rt.data.ptr.buffer,cp);
                    rlen = (ssize_t)cp;
                }
                /* 关键: reply 里若带 binder handle 对象(如 checkService 返回的服务句柄),
                 * 其引用计数是临时挂在本 reply 缓冲上的。下面 BC_FREE_BUFFER 会让驱动把
                 * 这些临时引用一并减掉, handle 随即失效。若我们还想继续用该 handle, 必须
                 * 在释放缓冲"之前"先 BC_ACQUIRE 一个强引用把它焊住。否则后续对该 handle
                 * 的事务会被驱动回 BR_FAILED_REPLY(此前 dump 全失败的真因)。 */
                if (rt.offsets_size > 0 && rt.data.ptr.offsets && rt.data.ptr.buffer) {
                    const binder_size_t* offs = (const binder_size_t*)(uintptr_t)rt.data.ptr.offsets;
                    size_t nobj = rt.offsets_size / sizeof(binder_size_t);
                    for (size_t k = 0; k < nobj; k++) {
                        struct flat_binder_object* fo =
                            (struct flat_binder_object*)((uint8_t*)(uintptr_t)rt.data.ptr.buffer + offs[k]);
                        if (fo->hdr.type == BINDER_TYPE_HANDLE) {
                            struct { uint32_t c; uint32_t h; } __attribute__((packed)) ac;
                            ac.c = BC_ACQUIRE; ac.h = fo->handle;
                            struct binder_write_read ar; memset(&ar,0,sizeof(ar));
                            ar.write_buffer=(binder_uintptr_t)(uintptr_t)&ac; ar.write_size=sizeof(ac);
                            ioctl(fd, BINDER_WRITE_READ, &ar);
                        }
                    }
                }
                /* 归还 reply 缓冲给 binder 驱动 */
                struct { uint32_t c; binder_uintptr_t p; } __attribute__((packed)) fb;
                fb.c = BC_FREE_BUFFER; fb.p = rt.data.ptr.buffer;
                struct binder_write_read fr; memset(&fr,0,sizeof(fr));
                fr.write_buffer=(binder_uintptr_t)(uintptr_t)&fb; fr.write_size=sizeof(fb);
                ioctl(fd, BINDER_WRITE_READ, &fr);
                got_reply = true; break;
            }
            default:
                /* 未知命令: 无法安全跳过其负载, 终止 */
                return got_reply ? rlen : -1;
            }
            if (got_reply) break;
        }
        if (got_reply) break;
        (void)completed;
    }
    return got_reply ? rlen : -1;
}

/* 向 servicemanager(handle 0) 发 checkService("SurfaceFlinger"), 解析 reply
 * 里的 flat_binder_object 取得 SF 的 handle。成功置 g_sf_handle 并返回 true。 */
static bool bnd_resolve_sf(int fd){
    uint8_t ibuf[256]; bparcel in; bp_init(&in, ibuf, sizeof(ibuf));
    bp_iface(&in, "android.os.IServiceManager");
    bp_str16(&in, "SurfaceFlinger");
    if (in.bad) return false;
    uint8_t rep[256]; int terr=0;
    ssize_t rl = bnd_transact(fd, 0, SVC_CHECK_CODE, &in, rep, sizeof(rep), &terr);
    if (rl < 0 || terr != 0) {
        printf("[FPS][binder] checkService(SF) 失败: rl=%zd txn_err=%d\n", rl, terr);
        return false;
    }
    /* reply: int32 (strict header) 可选 + flat_binder_object(handle 服务) 。
     * 扫描 reply 找第一个 BINDER_TYPE_HANDLE/WEAK_HANDLE 对象取 handle。 */
    for (size_t o = 0; o + sizeof(struct flat_binder_object) <= (size_t)rl; o += 4) {
        struct flat_binder_object obj; memcpy(&obj, rep+o, sizeof(obj));
        if (obj.hdr.type == BINDER_TYPE_HANDLE || obj.hdr.type == BINDER_TYPE_WEAK_HANDLE) {
            g_sf_handle = obj.handle;
            printf("[FPS][binder] 解析到 SF handle=%u\n", g_sf_handle);
            return true;
        }
    }
    printf("[FPS][binder] checkService reply(%zd 字节)里没找到 handle 对象\n", rl);
    return false;
}

/* 后台线程: 持续从管道读端把 dump 文本读进堆缓冲, 防止 SF 写满管道阻塞。 */
struct pipe_drain { int rfd; char* buf; size_t cap, used; };
static void* pipe_drain_fn(void* a){
    struct pipe_drain* d = (struct pipe_drain*)a;
    for(;;){
        if (d->used + 1 >= d->cap) {            /* 满: 读尽丢弃 */
            char t[4096]; if (read(d->rfd, t, sizeof(t)) <= 0) break; continue;
        }
        ssize_t r = read(d->rfd, d->buf + d->used, d->cap - 1 - d->used);
        if (r > 0) d->used += (size_t)r;
        else if (r == 0) break;
        else if (errno == EINTR) continue;
        else break;
    }
    if (d->buf) d->buf[d->used] = '\0';
    return NULL;
}

/* 经 binder 向 SF 发 DUMP_TRANSACTION, args 为 dump 参数(如 {"--latency","<layer>"}),
 * dump 文本收进 buf(NUL 结尾)。成功返回字节数, 失败返回 -1(调用方回退 CLI)。 */
/* 直连 binder 调 SurfaceFlinger dump (比 CLI dumpsys 快 10 倍+, 无 fork 开销)。
 * args: dump 参数数组(如 {"--latency", "LayerName"})
 * 返回写入 buf 的字节数; 失败返回 -1。
 * 非 static: 供 fps_fallback.c 等外部模块调用。 */
ssize_t sf_dump_binder(const char* const args[], int nargs,
                       char* buf, size_t bufsz){
    if (g_bnd_fd == -1) return -1;                /* 已判定不可用 */
    if (g_bnd_fd == -2) {                          /* 首次: 打开 + 解析 SF handle */
        int fd = bnd_open();
        if (fd < 0) { g_bnd_fd = -1; return -1; }
        g_bnd_fd = fd;
        g_sf_have = bnd_resolve_sf(fd);
        if (!g_sf_have) { /* 仍保留 fd, 下次重试解析 */ }
    }
    if (!g_sf_have) {
        g_sf_have = bnd_resolve_sf(g_bnd_fd);
        if (!g_sf_have) return -1;
    }

    int pfd[2];
    if (pipe(pfd) != 0) return -1;

    /* 组 dump parcel: writeFileDescriptor(写端) + int32 argc + argc * String16 。
     * BBinder::dump(fd, args) 的 onTransact 解码顺序: 先 readFileDescriptor,
     * 再 readInt32(argc), 再 argc 个 readString16。无 interface token。 */
    uint8_t ibuf[1024]; bparcel in; bp_init(&in, ibuf, sizeof(ibuf));
    bp_fd(&in, pfd[1]);            /* 写端 fd 对象(writeFileDescriptor) */
    bp_i32(&in, nargs);
    for (int i=0;i<nargs;i++) bp_str16(&in, args[i]);
    if (in.bad) { close(pfd[0]); close(pfd[1]); return -1; }

    /* 起读线程收 dump(SF 在事务期间写管道; 我们必须并发读) */
    struct pipe_drain d = { pfd[0], buf, bufsz, 0 };
    pthread_t th;
    if (pthread_create(&th, NULL, pipe_drain_fn, &d) != 0) {
        close(pfd[0]); close(pfd[1]); return -1;
    }

    uint8_t rep[512]; int terr=0;
    ssize_t rl = bnd_transact(g_bnd_fd, g_sf_handle, SF_DUMP_CODE, &in, rep, sizeof(rep), &terr);
    close(pfd[1]);                 /* 关写端: SF 那份已 dup, 此处关闭使读端能 EOF */
    pthread_join(th, NULL);
    close(pfd[0]);

    if (rl < 0 || terr != 0) {     /* 事务真失败(可能 SELinux 拒/handle 失效) */
        static int dlog = 0;
        if (!dlog) { dlog = 1;
            printf("[FPS][binder] dump 事务失败: rl=%zd txn_err=%d 收到=%zu字节 (arg0=%s)\n",
                   rl, terr, d.used, nargs > 0 ? args[0] : "(无参)");
        }
        g_sf_have = false;          /* 下次重新解析 handle */
        return -1;                  /* 让上层回退 CLI */
    }
    /* terr==0 且 rl>=0: 事务成功。d.used 可能为 0(如 --latency-clear 本就无文本输出),
     * 这仍是"binder 成功", 不能当失败回退 CLI。返回 >=0 由上层据此判定 binder 可用。 */
    return (ssize_t)d.used;
}
#endif /* APPOPT_HAVE_BINDER */

static bool socket_send_all(int fd, const char* data, size_t len, int flags) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = send(fd, data + off, len - off, flags | MSG_NOSIGNAL);
        if (n > 0) {
            off += (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

static int unix_connect_abstract(const char* name) {
    if (!name || name[0] == '\0') {
        errno = EINVAL;
        return -1;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    (void)fcntl(fd, F_SETFD, FD_CLOEXEC);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    size_t name_len = strnlen(name, sizeof(addr.sun_path) - 1);
    if (name_len == 0 || name_len >= sizeof(addr.sun_path)) {
        close(fd);
        errno = EINVAL;
        return -1;
    }
    memcpy(addr.sun_path + 1, name, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);

    if (connect(fd, (struct sockaddr*)&addr, addr_len) != 0) {
        int err = errno;
        close(fd);
        errno = err;
        return -1;
    }
    return fd;
}

static bool daemon_req_field(const char* req, const char* key, char* out, size_t out_sz) {
    if (!req || !key || !out || out_sz == 0) return false;
    out[0] = '\0';

    char pattern[32];
    int pn = snprintf(pattern, sizeof(pattern), "%s=", key);
    if (pn < 0 || (size_t)pn >= sizeof(pattern)) return false;

    const char* p = strstr(req, pattern);
    if (!p) return false;
    p += (size_t)pn;
    if (*p == '\0') return false;

    size_t n = 0;
    while (p[n] && !isspace((unsigned char)p[n])) {
        if (n + 1 >= out_sz) {
            out[0] = '\0';
            return false;
        }
        out[n] = p[n];
        n++;
    }
    out[n] = '\0';
    return n > 0;
}

static bool daemon_socket_send_callback(const char* name, const char* token) {
    if (!name || !token || name[0] == '\0' || token[0] == '\0') return false;

    int fd = unix_connect_abstract(name);
    if (fd < 0) {
        printf("[CTRL] daemon 反向验证回连失败: name=%s err=%s\n",
               name, strerror(errno));
        return false;
    }

    struct timeval tv = { .tv_sec = 2, .tv_usec = 0 };
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    char resp[256];
    int rn = snprintf(resp, sizeof(resp), "%s token=%s version=%s pid=%ld\n",
                      DAEMON_SOCKET_CALLBACK, token, VERSION, (long)getpid());
    if (rn < 0) {
        close(fd);
        return false;
    }
    if ((size_t)rn >= sizeof(resp)) rn = (int)sizeof(resp) - 1;

    bool ok = socket_send_all(fd, resp, (size_t)rn, 0);
    if (ok) {
        printf("[CTRL] daemon 反向验证回连成功: name=%s version=%s pid=%ld\n",
               name, VERSION, (long)getpid());
    } else {
        printf("[CTRL] daemon 反向验证回连发送失败: name=%s err=%s\n",
               name, strerror(errno));
    }
    close(fd);
    return ok;
}

static void daemon_socket_handle_client(int client_fd) {
    struct timeval tv = { .tv_sec = 2, .tv_usec = 0 };
    (void)setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    char req_buf[160];
    ssize_t n = recv(client_fd, req_buf, sizeof(req_buf) - 1, 0);
    if (n <= 0) {
        printf("[CTRL] daemon 验证 socket 收包失败: fd=%d err=%s\n",
               client_fd, n < 0 ? strerror(errno) : "EOF");
        return;
    }
    req_buf[n] = '\0';
    char* req = strtrim(req_buf);

    if (strncmp(req, DAEMON_SOCKET_PING_PREFIX, strlen(DAEMON_SOCKET_PING_PREFIX)) != 0) {
        printf("[CTRL] daemon 验证 socket 收到未知请求: fd=%d req=%s\n", client_fd, req);
        return;
    }
    char source[32] = "unknown";
    char callback_name[FPS_SOCKET_NAME_MAX] = "";
    char callback_token[FPS_SOCKET_TOKEN_MAX] = "";
    (void)daemon_req_field(req, "source", source, sizeof(source));
    bool has_callback =
        daemon_req_field(req, "callback", callback_name, sizeof(callback_name)) &&
        daemon_req_field(req, "token", callback_token, sizeof(callback_token));

    if (!has_callback) {
        printf("[CTRL] daemon 验证 socket 缺少反向验证参数: source=%s\n", source);
        return;
    }

    static unsigned long ping_count = 0;
    ping_count++;
    printf("[CTRL] daemon 验证 socket 收到反向验证请求: #%lu source=%s version=%s pid=%ld\n",
           ping_count, source, VERSION, (long)getpid());
    (void)daemon_socket_send_callback(callback_name, callback_token);
}

static int daemon_socket_ping_client(const char* callback_name, const char* callback_token) {
    if (!callback_name || !callback_token ||
        callback_name[0] == '\0' || callback_token[0] == '\0') {
        fprintf(stderr, "daemon ping 缺少反向验证 socket/token\n");
        return 2;
    }

    int fd = unix_connect_abstract(DAEMON_SOCKET_NAME);
    if (fd < 0) {
        fprintf(stderr, "daemon ping 连接失败: @%s err=%s\n",
                DAEMON_SOCKET_NAME, strerror(errno));
        return 3;
    }

    struct timeval tv = { .tv_sec = 2, .tv_usec = 0 };
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    char req[256];
    int rn = snprintf(req, sizeof(req), "%s source=reverse callback=%s token=%s\n",
                      DAEMON_SOCKET_PING_PREFIX, callback_name, callback_token);
    if (rn < 0 || (size_t)rn >= sizeof(req) ||
        !socket_send_all(fd, req, (size_t)rn, 0)) {
        fprintf(stderr, "daemon ping 发送失败: %s\n", strerror(errno));
        close(fd);
        return 4;
    }

    close(fd);
    return 0;
}

static void* daemon_socket_thread(void* arg) {
    (void)arg;
    pthread_setname_np(pthread_self(), "AppOptCtrl");

    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        printf("[CTRL] daemon 验证 socket 创建失败: %s\n", strerror(errno));
        return NULL;
    }
    (void)fcntl(server_fd, F_SETFD, FD_CLOEXEC);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    size_t name_len = strnlen(DAEMON_SOCKET_NAME, sizeof(addr.sun_path) - 1);
    memcpy(addr.sun_path + 1, DAEMON_SOCKET_NAME, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);

    if (bind(server_fd, (struct sockaddr*)&addr, addr_len) != 0) {
        printf("[CTRL] daemon 验证 socket 监听失败: @%s err=%s\n",
               DAEMON_SOCKET_NAME, strerror(errno));
        close(server_fd);
        return NULL;
    }

    if (listen(server_fd, 8) != 0) {
        printf("[CTRL] daemon 验证 socket listen 失败: @%s err=%s\n",
               DAEMON_SOCKET_NAME, strerror(errno));
        close(server_fd);
        return NULL;
    }

    printf("[CTRL] daemon 验证 socket 已监听: @%s\n", DAEMON_SOCKET_NAME);

    for (;;) {
        int client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            printf("[CTRL] daemon 验证 socket accept 失败: %s\n", strerror(errno));
            usleep(200 * 1000);
            continue;
        }
        (void)fcntl(client_fd, F_SETFD, FD_CLOEXEC);
        daemon_socket_handle_client(client_fd);
        close(client_fd);
    }
    return NULL;
}

typedef struct {
    int fd;
    char name[FPS_SOCKET_NAME_MAX];
    char token[FPS_SOCKET_TOKEN_MAX];
    bool send_logged;
    bool disabled;
} FpsSocket;

static FpsSocket g_fps_socket = { .fd = -1 };

static void fps_socket_close(void) {
    if (g_fps_socket.fd >= 0) close(g_fps_socket.fd);
    g_fps_socket.fd = -1;
}

static void fps_socket_reset(void) {
    fps_socket_close();
    g_fps_socket.name[0] = '\0';
    g_fps_socket.token[0] = '\0';
    g_fps_socket.send_logged = false;
    g_fps_socket.disabled = false;
}

static void fps_socket_configure(const char* name, const char* token) {
    fps_socket_reset();
    if (!name || !token || name[0] == '\0' || token[0] == '\0') return;
    if (strlen(name) >= sizeof(g_fps_socket.name) ||
        strlen(token) >= sizeof(g_fps_socket.token)) {
        printf("[FPS] socket 参数过长, 回退文件通道\n");
        return;
    }
    build_str(g_fps_socket.name, sizeof(g_fps_socket.name), name, NULL);
    build_str(g_fps_socket.token, sizeof(g_fps_socket.token), token, NULL);
    printf("[FPS] socket 数据通道已配置: name=%s name_len=%zu token_len=%zu\n",
           g_fps_socket.name, strlen(g_fps_socket.name), strlen(g_fps_socket.token));
}

static bool fps_socket_is_configured(void) {
    return g_fps_socket.name[0] != '\0' && g_fps_socket.token[0] != '\0';
}

static bool fps_socket_connect(void) {
    if (!fps_socket_is_configured() || g_fps_socket.disabled) return false;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        g_fps_socket.disabled = true;
        printf("[FPS] socket 数据通道创建失败: %s\n", strerror(errno));
        return false;
    }

    (void)fcntl(fd, F_SETFD, FD_CLOEXEC);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0'; /* Android LocalServerSocket(String) 使用 abstract namespace */
    size_t name_len = strnlen(g_fps_socket.name, sizeof(addr.sun_path) - 1);
    if (name_len == 0 || name_len >= sizeof(addr.sun_path)) {
        close(fd);
        g_fps_socket.disabled = true;
        printf("[FPS] socket 数据通道名称无效: name_len=%zu\n", name_len);
        return false;
    }
    memcpy(addr.sun_path + 1, g_fps_socket.name, name_len);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);

    printf("[FPS] socket 数据通道连接中: name=%s fd=%d\n", g_fps_socket.name, fd);
    if (connect(fd, (struct sockaddr*)&addr, addr_len) != 0) {
        int err = errno;
        close(fd);
        g_fps_socket.disabled = true;
        printf("[FPS] socket 数据通道连接失败: name=%s err=%s, 回退文件通道\n",
               g_fps_socket.name, strerror(err));
        return false;
    }

    char hello[FPS_SOCKET_TOKEN_MAX + 16];
    int hn = snprintf(hello, sizeof(hello), "hello %s\n", g_fps_socket.token);
    if (hn < 0 || (size_t)hn >= sizeof(hello)) {
        close(fd);
        g_fps_socket.disabled = true;
        printf("[FPS] socket 数据通道握手消息生成失败, 回退文件通道\n");
        return false;
    }
    ssize_t hw = send(fd, hello, (size_t)hn, MSG_NOSIGNAL);
    if (hw != hn) {
        int err = errno;
        close(fd);
        g_fps_socket.disabled = true;
        printf("[FPS] socket 数据通道握手失败: sent=%zd/%d err=%s, 回退文件通道\n",
               hw, hn, strerror(err));
        return false;
    }

    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) (void)fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    g_fps_socket.fd = fd;
    printf("[FPS] socket 数据通道已连接: name=%s fd=%d hello_len=%d\n",
           g_fps_socket.name, fd, hn);
    return true;
}

static bool fps_socket_send(double fps) {
    if (!fps_socket_is_configured() || g_fps_socket.disabled) return false;
    if (g_fps_socket.fd < 0 && !fps_socket_connect()) return false;

    char val[24];
    int n = snprintf(val, sizeof(val), "%.1f\n", fps);
    if (n < 0) return false;
    if ((size_t)n >= sizeof(val)) n = (int)sizeof(val) - 1;

    ssize_t sent = send(g_fps_socket.fd, val, (size_t)n, MSG_DONTWAIT | MSG_NOSIGNAL);
    if (sent == n) {
        if (!g_fps_socket.send_logged) {
            g_fps_socket.send_logged = true;
            printf("[FPS] socket 数据通道开始发送 FPS: fd=%d first=%.1f bytes=%d\n",
                   g_fps_socket.fd, fps, n);
        }
        return true;
    }
    if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        if (!g_fps_socket.send_logged) {
            printf("[FPS] socket 数据通道发送缓冲忙: fd=%d first=%.1f\n",
                   g_fps_socket.fd, fps);
        }
        return true; /* App 暂时读不过来时丢本次帧率, 不切回文件造成额外 IO。 */
    }

    int err = errno;
    int old_fd = g_fps_socket.fd;
    fps_socket_close();
    g_fps_socket.disabled = true;
    printf("[FPS] socket 数据通道发送失败: fd=%d sent=%zd/%d err=%s, 回退文件通道\n",
           old_fd, sent, n, sent < 0 ? strerror(err) : "partial write");
    return false;
}

/* 把 fps 覆盖写到 app 私有目录的 fps 文件, 并修标签+权限让 app 能读。
 * 跨 SELinux 域: root(此进程)写完后给文件打 app_data_file 标签, app 才读得到。*/
static void fps_write_file(double fps) {
    static bool warn_logged = false;
    char val[24];
    int n = snprintf(val, sizeof(val), "%.1f", fps);
    /* snprintf 返回的是"本应写入"的长度, 截断时会 >= sizeof(val); 钳到实际缓冲
     * 长度, 避免 write 越界读栈(现实 FPS 不可能触发, 仅作防御)。 */
    if (n < 0) return;
    if ((size_t)n >= sizeof(val)) n = (int)sizeof(val) - 1;
    /* 文件不存在=首次/app 重装后重建, 需重新打标签(标签随 inode 走) */
    bool fresh = (access(FPS_OUT_FILE, F_OK) != 0);
    /* O_TRUNC 覆盖写; app 侧 FileObserver 监听 CLOSE_WRITE */
    int fd = open(FPS_OUT_FILE, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    if (fd < 0) {
        if (!warn_logged) {
            warn_logged = true;
            printf("[FPS] 文件兜底写入失败: path=%s err=%s\n", FPS_OUT_FILE, strerror(errno));
        }
        return;
    }
    ssize_t w = write(fd, val, (size_t)n);
    if (w != (ssize_t)n && !warn_logged) {
        warn_logged = true;
        printf("[FPS] 文件兜底写入不完整: path=%s written=%zd/%d err=%s\n",
               FPS_OUT_FILE, w, n, w < 0 ? strerror(errno) : "short write");
    } else if (w == (ssize_t)n) {
        warn_logged = false;
    }
    close(fd);
    /* chmod/chcon: app 进程(非 root)需可读。私有目录文件默认带创建者(root)上下文,
     * app 域读不到, 必须改成 app_data_file。O_TRUNC 覆盖写复用同一 inode, SELinux
     * 标签随 inode 保留, 故仅在文件新建时设一次; 避免每窗口都 fork chcon 拖慢更新频率。*/
    if (fresh) {
        chmod(FPS_OUT_FILE, 0666);
        system("chcon u:object_r:app_data_file:s0 " FPS_OUT_FILE " 2>/dev/null");
    }
}

static void fps_write_out(double fps) {
    if (fps_socket_send(fps)) return;
    fps_write_file(fps);
}

static void fps_write_out_windowed(double fps, long long* last_output_ms, bool force) {
    long long now = monotonic_ms();
    if (!force && last_output_ms && *last_output_ms > 0 && now > 0 &&
        now - *last_output_ms < FPS_WINDOW_MS) {
        return;
    }
    fps_write_out(fps);
    if (last_output_ms) {
        *last_output_ms = now > 0 ? now : *last_output_ms + FPS_WINDOW_MS;
    }
}

static bool fps_cmd_valid(const char* cmd) {
    return is_start_command(cmd) || is_stop_command(cmd);
}

/* 读取并消费 fps.cmd; cmd_buf 收到 "start <pkg> [socket token]" / "stop"。无命令返回 false。*/
static bool fps_read_cmd(char* cmd_buf, size_t sz) {
    return read_stable_command_file(FPS_CMD_FILE, cmd_buf, sz, fps_cmd_valid);
}

static bool fps_pid_is_main_process(pid_t pid, const char* pkg) {
    if (pid <= 0 || !pkg || !*pkg) return false;

    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d", pid);
    int pfd = open(proc_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (pfd < 0) return false;

    char cmd[MAX_PKG_LEN] = {0};
    bool ok = read_file(pfd, "cmdline", cmd, sizeof(cmd));
    close(pfd);
    if (!ok) return false;

    char* name = strrchr(cmd, '/');
    name = name ? name + 1 : cmd;
    return strcmp(name, pkg) == 0;
}

static pid_t fps_find_preferred_pkg_pid(const char* pkg, bool allow_child,
                                        const char** source) {
    if (source) *source = "未找到";

    app_top_state_result top_state;
    if (app_top_state_check(pkg, &top_state) &&
        top_state.target_top_app && top_state.target_pid > 0) {
        if (source) *source = "cgroup 前台组";
        return top_state.target_pid;
    }

    CalibProcess procs[64];
    size_t np = collect_pkg_processes(pkg, procs, 64);
    for (size_t i = 0; i < np; i++) {
        if (fps_pid_is_main_process(procs[i].pid, pkg)) {
            if (source) *source = "包名主进程";
            return procs[i].pid;
        }
    }

    if (allow_child && np > 0) {
        if (source) *source = "包名子进程回退";
        return procs[0].pid;
    }
    return (pid_t)-1;
}

static pid_t fps_wait_pkg_pid(const char* pkg, int attempts, useconds_t delay_us,
                              const char** source) {
    for (int attempt = 0; attempt < attempts; attempt++) {
        pid_t pid = fps_find_preferred_pkg_pid(pkg, false, source);
        if (pid > 0) return pid;
        if (delay_us > 0) usleep(delay_us);
    }
    return fps_find_preferred_pkg_pid(pkg, true, source);
}

static ebpf_fps_ctx* fps_start_ebpf_ctx(const char* pkg, pid_t target_pid,
                                        const char* pid_source) {
    if (target_pid > 0) {
        printf("[FPS] 目标进程 PID: %d (来源: %s), 尝试 eBPF uprobe...\n",
               target_pid, pid_source ? pid_source : "未知");
        return ebpf_fps_start(FPS_BPF_OBJ, target_pid, pkg);
    }

    printf("[FPS] 未锁定包名 PID, 尝试 eBPF 全局 uprobe 探测屏幕帧事件...\n");
    return ebpf_fps_start(FPS_BPF_OBJ, (pid_t)-1, pkg);
}

/*
 * FPS 监测线程。控制协议仍走纯文本文件:
 *   App -> 守护: 写 FPS_CMD_FILE, 内容 "start <pkg> [socket token]" / "stop"
 *   守护 -> App: 优先向 App 的 Android 本地 socket 按行推送浮点 FPS;
 *                socket 不可用时覆盖写 FPS_OUT_FILE(app 私有目录)兜底。
 *
 * 策略(自动回退):
 *   1) eBPF uprobe libgui::queueBuffer (最优, 逐帧精度)
 *   2) SF --latency binder 直连 (次优, SF 层帧率, 快速)
 *   3) SF --timestats binder 直连 (回退, Android 16 等 --latency 失效时)
 *
 * 仅在 start..stop 期间工作, 不监测时完全静默(零开销)。
 */
static void* fps_thread(void* arg) {
    (void)arg;
    pthread_setname_np(pthread_self(), "AppOptFps");

    bool monitoring = false;
    char pkg[MAX_PKG_LEN] = "";
    char cmd[384];

    /* eBPF 状态 */
    ebpf_fps_ctx *ebpf_ctx = NULL;
    bool ebpf_first_fps = true;
    bool ebpf_pid_reported = false;
    int ebpf_poll_failures = 0;
    bool ebpf_seen_frames = false;
    bool ebpf_stale_zero_sent = false;
    long long ebpf_last_frame_ms = 0;
    long long ebpf_last_pid_check_ms = 0;
    long long ebpf_last_restart_ms = 0;

    /* Fallback 状态(包含 --latency + timestats) */
    fps_fallback_ctx *fallback_ctx = NULL;
    bool fallback_first_fps = true;
    long long last_fps_output_ms = 0;

    for (;;) {
        /* 轮询命令 */
        if (fps_read_cmd(cmd, sizeof(cmd))) {
            if (is_start_command(cmd)) {
                const char* p = strtrim(cmd + 6);
                char start_pkg[MAX_PKG_LEN] = "";
                char socket_name[FPS_SOCKET_NAME_MAX] = "";
                char socket_token[FPS_SOCKET_TOKEN_MAX] = "";
                int parsed = sscanf(p, "%127s %95s %63s", start_pkg, socket_name, socket_token);
                if (parsed >= 1 && strlen(start_pkg) < MAX_PKG_LEN) {
                    /* 清理旧状态 */
                    if (ebpf_ctx) { ebpf_fps_stop(ebpf_ctx); ebpf_ctx = NULL; }
                    if (fallback_ctx) { fps_fallback_stop(fallback_ctx); fallback_ctx = NULL; }
                    fps_socket_reset();

                    build_str(pkg, sizeof(pkg), start_pkg, NULL);
                    if (parsed >= 3) fps_socket_configure(socket_name, socket_token);
                    monitoring = true;
                    ebpf_first_fps = true;
                    ebpf_pid_reported = false;
                    ebpf_poll_failures = 0;
                    ebpf_seen_frames = false;
                    ebpf_stale_zero_sent = false;
                    ebpf_last_frame_ms = monotonic_ms();
                    ebpf_last_pid_check_ms = 0;
                    ebpf_last_restart_ms = ebpf_last_frame_ms;
                    fallback_first_fps = true;
                    last_fps_output_ms = 0;

                    /* 1. eBPF 预检查。真正加载/attach 是否成功由 ebpf_fps_start 决定。 */
                    ebpf_cap_t cap = ebpf_fps_probe_capability(FPS_BPF_OBJ);
                    printf("[FPS] 开始监测 %s, eBPF: %s\n", pkg, ebpf_cap_str(cap));

                    if (cap == EBPF_CAP_OK) {
                        /* 2. 找目标进程 PID。游戏刚启动时 /proc/cmdline 可能短暂不可见, 等一小段时间。 */
                        const char* pid_source = "未找到";
                        pid_t start_pid = fps_wait_pkg_pid(pkg, 30, 100 * 1000, &pid_source);
                        if (start_pid < 0) {
                            printf("[FPS] 等待约 3 秒仍未找到 %s 的进程, 尝试全局 eBPF 帧事件探测\n", pkg);
                        }

                        /* 3. 尝试 eBPF attach */
                        ebpf_ctx = fps_start_ebpf_ctx(pkg, start_pid, pid_source);

                        if (ebpf_ctx) {
                            long long started_ms = monotonic_ms();
                            ebpf_last_frame_ms = started_ms;
                            ebpf_last_restart_ms = started_ms;
                            const char *backend = ebpf_fps_backend(ebpf_ctx);
                            const char *warn = ebpf_fps_startup_note(ebpf_ctx);
                            if (warn && warn[0]) {
                                printf("[FPS] eBPF %s\n", warn);
                            }
                            printf("[FPS] eBPF 使用后端: %s\n",
                                   (backend && backend[0]) ? backend : "未知");
                            printf("[FPS] eBPF 已激活, 锁定符号: %s\n", ebpf_fps_symbol(ebpf_ctx));
                        } else {
                            const char *err = ebpf_fps_last_error(NULL);
                            printf("[FPS] eBPF 初始化失败: %s, 降级到 SF fallback\n",
                                   (err && err[0]) ? err : "未知错误");
                        }
                    }

                    /* 4. eBPF 不可用 -> 启用 SF fallback (含 latency + timestats) */
                    if (!ebpf_ctx) {
                        fallback_ctx = fps_fallback_start(pkg);
                        if (!fallback_ctx) {
                            printf("[FPS] 警告: fallback 启动失败\n");
                        }
                    }
                }
            } else if (is_stop_command(cmd)) {
                if (monitoring) {
                    monitoring = false;
                    if (ebpf_ctx) { ebpf_fps_stop(ebpf_ctx); ebpf_ctx = NULL; }
                    if (fallback_ctx) { fps_fallback_stop(fallback_ctx); fallback_ctx = NULL; }
                    ebpf_seen_frames = false;
                    ebpf_stale_zero_sent = false;
                    printf("[FPS] 停止监测 %s\n", pkg);
                    fps_write_out_windowed(0, &last_fps_output_ms, true);
                    fps_socket_reset();
                    last_fps_output_ms = 0;
                }
            }
        }

        if (!monitoring) {
            usleep(300 * 1000);
            continue;
        }

        /* === eBPF 模式: 高频轮询事件通道 === */
        if (ebpf_ctx) {
            int poll_rc = ebpf_fps_poll(ebpf_ctx);
            if (poll_rc < 0) {
                ebpf_poll_failures++;
                if (ebpf_poll_failures >= 3) {
                    const char* err = ebpf_fps_last_error(ebpf_ctx);
                    printf("[FPS] eBPF 轮询连续失败 %d 次: %s, 切换到 SF fallback\n",
                           ebpf_poll_failures, (err && err[0]) ? err : "未知错误");
                    ebpf_fps_stop(ebpf_ctx);
                    ebpf_ctx = NULL;
                    fallback_first_fps = true;
                    fallback_ctx = fps_fallback_start(pkg);
                    if (!fallback_ctx) {
                        printf("[FPS] 警告: fallback 启动失败\n");
                    }
                    usleep(100 * 1000);
                    continue;
                }
            } else {
                ebpf_poll_failures = 0;
            }
            long long now_ms = monotonic_ms();
            if (poll_rc > 0) {
                ebpf_seen_frames = true;
                ebpf_stale_zero_sent = false;
                ebpf_last_frame_ms = now_ms;
            }
            double fps = ebpf_fps_get(ebpf_ctx);
            pid_t active_pid = ebpf_fps_pid(ebpf_ctx);
            if (!ebpf_pid_reported && active_pid > 0) {
                printf("[FPS] eBPF 当前帧事件 PID: %d\n", active_pid);
                ebpf_pid_reported = true;
            }
            if (ebpf_first_fps && fps > 0) {
                printf("[FPS] eBPF 首次捕获到帧率: %.1f fps\n", fps);
                ebpf_first_fps = false;
            }

            bool fps_is_stale = ebpf_seen_frames && now_ms > 0 &&
                now_ms - ebpf_last_frame_ms >= FPS_EBPF_STALE_MS;
            bool fps_has_no_valid_value = ebpf_first_fps && now_ms > 0 &&
                now_ms - ebpf_last_restart_ms >= FPS_EBPF_STALE_MS;
            if (fps_is_stale) {
                if (!ebpf_stale_zero_sent) {
                    printf("[FPS] eBPF 目标暂无新帧 %.1f 秒, 输出 0 FPS\n",
                           (double)(now_ms - ebpf_last_frame_ms) / 1000.0);
                    fps_write_out_windowed(0, &last_fps_output_ms, true);
                    ebpf_stale_zero_sent = true;
                }

            }

            if ((fps_is_stale || fps_has_no_valid_value) &&
                now_ms - ebpf_last_pid_check_ms >= 1000 &&
                now_ms - ebpf_last_restart_ms >= FPS_EBPF_RESTART_COOLDOWN_MS) {
                ebpf_last_pid_check_ms = now_ms;
                const char* next_pid_source = "未找到";
                pid_t next_pid = fps_find_preferred_pkg_pid(pkg, true, &next_pid_source);
                if (next_pid > 0 && next_pid != active_pid) {
                    printf("[FPS] 目标进程已切换: old=%d new=%d (来源: %s), 重启 eBPF 监测\n",
                           active_pid, next_pid, next_pid_source);
                    ebpf_fps_stop(ebpf_ctx);
                    ebpf_ctx = fps_start_ebpf_ctx(pkg, next_pid, next_pid_source);
                    ebpf_first_fps = true;
                    ebpf_pid_reported = false;
                    ebpf_poll_failures = 0;
                    ebpf_seen_frames = false;
                    ebpf_stale_zero_sent = false;
                    ebpf_last_frame_ms = now_ms;
                    ebpf_last_restart_ms = now_ms;
                    if (ebpf_ctx) {
                        const char *backend = ebpf_fps_backend(ebpf_ctx);
                        const char *warn = ebpf_fps_startup_note(ebpf_ctx);
                        if (warn && warn[0]) {
                            printf("[FPS] eBPF %s\n", warn);
                        }
                        printf("[FPS] eBPF 使用后端: %s\n",
                               (backend && backend[0]) ? backend : "未知");
                        printf("[FPS] eBPF 已重启, 锁定符号: %s\n", ebpf_fps_symbol(ebpf_ctx));
                    } else {
                        const char *err = ebpf_fps_last_error(NULL);
                        printf("[FPS] eBPF 重启失败: %s, 切换到 SF fallback\n",
                               (err && err[0]) ? err : "未知错误");
                        fallback_first_fps = true;
                        fallback_ctx = fps_fallback_start(pkg);
                        if (!fallback_ctx) {
                            printf("[FPS] 警告: fallback 启动失败\n");
                        }
                    }
                    usleep(100 * 1000);
                    continue;
                }
            }

            if (!fps_is_stale) {
                fps_write_out_windowed(fps, &last_fps_output_ms, false);
            }
            usleep(100 * 1000);  /* 100ms 轮询 */
            continue;
        }

        /* === Fallback 模式: --latency 或 timestats === */
        if (fallback_ctx) {
            double fps = fps_fallback_poll(fallback_ctx);
            if (fallback_first_fps && fps > 0) {
                printf("[FPS] Fallback 首次捕获到帧率: %.1f fps\n", fps);
                fallback_first_fps = false;
            }
            fps_write_out_windowed(fps, &last_fps_output_ms, false);
            usleep((useconds_t)FPS_WINDOW_MS * 1000);  /* 按 FPS_WINDOW_MS 更新悬浮胶囊 */
            continue;
        }

        /* 无可用数据源 */
        usleep(500 * 1000);
        fps_write_out_windowed(0, &last_fps_output_ms, false);
    }
    return NULL;
}

static void print_help(const char* prog_name) {
    printf("Usage: %s [OPTIONS]\n", prog_name);
    printf("Options:\n");
    printf("  -c <config_file>   指定配置文件 (默认: /data/adb/modules/AppOpt/config/applist.conf)\n");
    printf("  -s <interval>      设置检查间隔(秒) (必须>=1, 默认: 2)\n");
    printf("  -v                 显示程序版本\n");
    printf("  -P, --ping-daemon <socket> <token>  请求守护进程反向验证 App\n");
    printf("  --app-state <pkg>                   读取 top-app/foreground_window 并检查目标包是否前台\n");
    printf("  -h                 显示帮助信息\n");
    printf("\n示例:\n");
    printf("  %s -c /data/adb/modules/AppOpt/config/applist.conf -s 3\n", prog_name);
}

int main(int argc, char **argv) {
    /* stdout/stderr 重定向到文件(service.sh 写 AppOpt.log)时默认是全缓冲,
     * 长驻守护进程填不满 4KB 缓冲区, 日志会一直卡在内存里不落盘 ->
     * 表现为日志文件长期为空。改成行缓冲: 每输出一行立即写入文件。
     * 必须在任何 printf(含下面 init_cpu_topo)之前设置。 */
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);
    signal(SIGTERM, handle_shutdown_signal);
    signal(SIGINT, handle_shutdown_signal);
    signal(SIGHUP, handle_shutdown_signal);
    signal(SIGPIPE, SIG_IGN);

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--ping-daemon") == 0 || strcmp(argv[i], "-P") == 0) {
            if (i + 2 >= argc) {
                fprintf(stderr, "--ping-daemon 需要 <socket> <token>\n");
                return 2;
            }
            return daemon_socket_ping_client(argv[i + 1], argv[i + 2]);
        }
        if (strcmp(argv[i], "--app-state") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "--app-state 需要 <pkg>\n");
                return 2;
            }
            return app_state_print_cli(argv[i + 1], NULL);
        }
    }

    CpuTopology topo = init_cpu_topo();
    mkdir(CONFIG_DIR, 0755);
    mkdir(LOG_DIR, 0755);
    mkdir(EBPF_DIR, 0755);
    (void)calib_load_policy(&topo);
    g_topo = topo;                     /* 校准线程使用的拓扑快照 */
    char config_file[4096] = CONFIG_DIR "/applist.conf";
    int sleep_interval = 2;
    int opt;
    while ((opt = getopt(argc, argv, "c:s:hv")) != -1) {
        switch (opt) {
            case 'c':
                build_str(config_file, sizeof(config_file), optarg, NULL);
                printf("配置文件: %s\n", config_file);
                break;
            case 's':
            {
                char *endptr;
                long val = strtol(optarg, &endptr, 10);
                if (endptr == optarg || *endptr != '\0' || val < 1) {
                    fprintf(stderr, "无效的时间间隔: %s\n", optarg);
                    fprintf(stderr, "间隔必须是 >=1 的整数\n");
                    exit(EXIT_FAILURE);
                }
                sleep_interval = (int)val;
                printf("检查间隔: %d 秒\n", sleep_interval);
                break;
            }
            case 'v':
                printf("AppOpt 版本 %s\n", VERSION);
                exit(EXIT_SUCCESS);
            case 'h':
                print_help(argv[0]);
                exit(EXIT_SUCCESS);
            default:
                print_help(argv[0]);
                exit(EXIT_FAILURE);
        }
    }

    struct stat st;
    if (stat(config_file, &st) != 0) {
        const char* initial_content = "# 规则编写与使用说明请参考 http://AppOpt.suto.top\n\n";
        if (write_file(AT_FDCWD, config_file, initial_content, O_WRONLY | O_CREAT | O_TRUNC)) {
            printf("配置文件不存在，重建一个空的配置文件: %s\n", config_file);
        }
    }

    AppConfig* initial_config = load_config(config_file, &topo, NULL);
    if (!initial_config) {
        fprintf(stderr, "初始配置加载失败\n");
        exit(EXIT_FAILURE);
    }
    build_str(g_config_file, sizeof(g_config_file), config_file, NULL);
    atomic_store(&current_config, initial_config);
    atomic_store(&config_updated, 1);

    inotify_fd = inotify_init1(IN_CLOEXEC);
    if (inotify_fd >= 0) {
        int flags = fcntl(inotify_fd, F_GETFL);
        if (flags >= 0) fcntl(inotify_fd, F_SETFL, flags | O_NONBLOCK);
        inotify_wd = inotify_add_watch(inotify_fd, config_file, IN_CLOSE_WRITE | IN_DELETE_SELF | IN_MOVE_SELF);
        if (inotify_wd >= 0) {
            inotify_supported = 1;
            printf("启用inotify监控配置文件变更\n");
        } else {
            close(inotify_fd);
            inotify_fd = -1;
            printf("inotify初始化失败，使用轮询模式\n");
        }
    }

    pthread_t loader_thread;
    int* interval_ptr = malloc(sizeof(int));
    if (!interval_ptr) {
        config_release(initial_config);
        if (inotify_supported) close(inotify_fd);
        exit(EXIT_FAILURE);
    }
    *interval_ptr = sleep_interval;

    if (pthread_create(&loader_thread, NULL, config_loader_thread, interval_ptr) != 0) {
        perror("配置加载器线程创建失败");
        free(interval_ptr);
        config_release(initial_config);
        if (inotify_supported) close(inotify_fd);
        exit(EXIT_FAILURE);
    }
    pthread_detach(loader_thread);

    /* 启动自动校准线程 (处理 App 下发的 start/stop 命令) */
    pthread_t calib_tid;
    if (pthread_create(&calib_tid, NULL, calib_thread, NULL) == 0) {
        pthread_detach(calib_tid);
        printf("启用自动校准线程 (=auto)\n");
    } else {
        perror("校准线程创建失败");
    }

    /* 启动真实帧率监测线程 (eBPF 优先, SurfaceFlinger 兜底) */
    pthread_t fps_tid;
    if (pthread_create(&fps_tid, NULL, fps_thread, NULL) == 0) {
        pthread_detach(fps_tid);
        printf("启用真实帧率监测线程 (eBPF/SF fallback)\n");
    } else {
        perror("帧率监测线程创建失败");
    }

    ProcCache cache = {0};
    int affinity_counter = 0;
    printf("启动AppOpt服务 v%s (作者: suto & 一只小柒夏)\n", VERSION);

    /* 输出设备信息 */
    {
        char android_ver[PROP_VALUE_MAX];
        char api_level[PROP_VALUE_MAX];
        char brand[PROP_VALUE_MAX];
        char model[PROP_VALUE_MAX];
        const char* const android_ver_keys[] = {
                "ro.build.version.release",
                "ro.system.build.version.release"
        };
        const char* const api_level_keys[] = {
                "ro.build.version.sdk",
                "ro.system.build.version.sdk"
        };
        const char* const brand_keys[] = {
                "ro.product.brand",
                "ro.product.system.brand",
                "ro.product.vendor.brand",
                "ro.product.odm.brand",
                "ro.product.product.brand"
        };
        const char* const market_model_keys[] = {
                "ro.product.marketname",
                "ro.product.vendor.marketname",
                "ro.product.odm.marketname",
                "ro.product.system.marketname",
                "ro.product.product.marketname",
                "ro.vendor.product.marketname",
                "ro.config.marketing_name",
                "ro.vendor.oplus.market.name",
                "ro.oplus.market.name"
        };
        const char* const cert_model_keys[] = {
                "ro.product.model",
                "ro.product.vendor.model",
                "ro.product.odm.model",
                "ro.product.system.model",
                "ro.product.product.model"
        };

        /* Android 版本 */
        if (get_prop_first(android_ver, sizeof(android_ver), android_ver_keys,sizeof(android_ver_keys) / sizeof(android_ver_keys[0]))) {
            /* API Level */
            if (get_prop_first(api_level, sizeof(api_level), api_level_keys,sizeof(api_level_keys) / sizeof(api_level_keys[0]))) {
                printf("Android 版本: %s (API %s)\n", android_ver, api_level);
            } else {
                printf("Android 版本: %s\n", android_ver);
            }
        }

        /* 设备品牌和型号 */
        if (get_prop_first(brand, sizeof(brand), brand_keys,sizeof(brand_keys) / sizeof(brand_keys[0]))) {
            if (get_prop_first(model, sizeof(model), market_model_keys,sizeof(market_model_keys) / sizeof(market_model_keys[0]))) {
                printf("设备品牌: %s %s\n", brand, model);
            } else if (get_prop_first(model, sizeof(model), cert_model_keys,sizeof(cert_model_keys) / sizeof(cert_model_keys[0]))) {
                printf("设备品牌: %s %s\n", brand, model);
            } else {
                printf("设备品牌: %s\n", brand);
            }
        } else if (get_prop_first(model, sizeof(model), market_model_keys,sizeof(market_model_keys) / sizeof(market_model_keys[0]))) {
            printf("设备型号: %s\n", model);
        } else if (get_prop_first(model, sizeof(model), cert_model_keys,sizeof(cert_model_keys) / sizeof(cert_model_keys[0]))) {
            printf("设备型号: %s\n", model);
        }

        /* 内核版本 */
        struct utsname uts;
        if (uname(&uts) == 0) {
            printf("内核版本: %s %s\n", uts.sysname, uts.release);
        }
    }

    /* 启动守护进程身份验证 socket。App 用它确认这是 AppOpt 自己的守护进程, 不再只看进程名。 */
    pthread_t daemon_sock_tid;
    if (pthread_create(&daemon_sock_tid, NULL, daemon_socket_thread, NULL) == 0) {
        pthread_detach(daemon_sock_tid);
        printf("启用守护进程验证 socket\n");
    } else {
        perror("守护进程验证 socket 线程创建失败");
    }

    time_t last_policy_sync = 0;
    while (!shutdown_requested) {
        time_t now = time(NULL);
        if (now - last_policy_sync >= 5) {
            calib_sync_policy_topology(&topo);
            last_policy_sync = now;
        }

        if (atomic_exchange(&config_updated, 0)) {
            cache.scan_all_proc = true;
            cache.last_proc_count = 0;
        }

        AppConfig* cfg = get_config();
        if (cfg) {
            update_cache(&cache, cfg, &affinity_counter);
            affinity_counter--;
            if (affinity_counter < 1) {
                apply_affinity(&cache, &cfg->topo);
                affinity_counter = 5;
            }
            config_release(cfg);
        }
        sleep(sleep_interval);
    }
    printf("收到退出信号 %d, AppOpt 服务退出\n", shutdown_requested);
    return 0;
}
