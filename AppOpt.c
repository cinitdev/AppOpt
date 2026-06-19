#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <pthread.h>
#include <sched.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>
#include <linux/android/binder.h>   /* 直连 binder 取 SF dump(免 fork dumpsys) */
#include <sys/system_properties.h>
#define APPOPT_HAVE_BINDER 1
/* 注: 不定义 BINDER_IPC_32BIT, binder_uintptr_t/size_t 在 32/64 位均为 __u64,
 * 结构体布局一致 -> 同一套代码可跨 ABI。现代 Android(12-16)内核均为 64 位
 * binder ABI(即便进程是 32 位)。极罕见的真 32 位内核上事务会失败, 自动回退
 * CLI dumpsys, 不会崩。 */

/* fps_monitor 模块: eBPF uprobe + timestats 回退 */
#include "ebpf_fps.h"
#include "fps_fallback.h"

#define VERSION            "1.7.0"
#define BASE_CPUSET        "/dev/cpuset/AppOpt"
#define MAX_PKG_LEN        128
#define MAX_THREAD_LEN     32
#define MAX_CLUSTERS       8
#define MODULE_DIR         "/data/adb/modules/AppOpt"
#define CALIB_CMD_FILE     MODULE_DIR "/calibrate.cmd"
#define CALIB_STATE_FILE   MODULE_DIR "/calibrate.state"
#define HISTORY_DIR        MODULE_DIR "/history"

/* ---- 真实帧率(FPS)监测 ----
 * App 写 FPS_CMD_FILE: "start <pkg>" / "stop"  通知守护开/关监测
 * 策略: eBPF uprobe(优先) -> timestats(回退)
 *   eBPF: 在目标进程 libgui::queueBuffer 挂 uprobe,逐帧精度
 *   timestats: dumpsys SurfaceFlinger --timestats, Layer 平均 FPS
 * 守护把每秒算出的 FPS 覆盖写到 app 私有目录(下方), 再 chcon 成 app 可读,
 * App 侧 FileObserver(CLOSE_WRITE) 收到即读取刷新悬浮球胶囊。 */
#define FPS_CMD_FILE       MODULE_DIR "/fps.cmd"
#define FPS_OUT_DIR        "/data/data/top.suto.appopt/files"
#define FPS_OUT_FILE       FPS_OUT_DIR "/fps"
#define FPS_BPF_OBJ        MODULE_DIR "/queuebuffer_probe.bpf.o"  /* eBPF 字节码 */
#define FPS_WINDOW_MS      1000           /* 出一个 FPS 的周期 */

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
    /* 自动识别的核心分级 */
    CpuCluster clusters[MAX_CLUSTERS];
    int num_clusters;       /* 按 max_freq 升序排列后的簇数量 */
    char little_str[64];    /* 小核范围字符串,如 "0-2" */
    char middle_str[64];    /* 中核范围字符串,如 "3-6" (完整中核,向后兼容) */
    char big_str[64];       /* 大核范围字符串,如 "7" */
    char all_str[64];       /* 全部核心范围,如 "0-7" */
    char nonbig_str[64];    /* 排除大核的范围(小核+中核),如 "0-6", 用作进程级兜底 */
    /* 动态细分的中核档位(仅当中核>=4个时有效,否则与 middle_str 相同) */
    char middle_high_str[64];  /* 中核高频段,如 "5-6" (给次重载线程) */
    char middle_low_str[64];   /* 中核低频段,如 "3-4" (给中载线程) */
    char base_str[64];         /* 兜底范围(小核+低中核),如 "0-4", 用于更严格的隔离 */
} CpuTopology;

typedef struct {
    atomic_int ref_count;
    AffinityRule* rules;
    size_t num_rules;
    time_t mtime;
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
    int fd = openat(dir_fd, filename, O_RDONLY | O_CLOEXEC);
    if (fd == -1) return false;
    ssize_t n = read(fd, buf, buf_size - 1);
    close(fd);
    if (n <= 0) return false;
    buf[n] = '\0';
    return true;
}

static bool write_file(int dir_fd, const char* filename, const char* content, int flags) {
    int fd = openat(dir_fd, filename, flags | O_CLOEXEC, 0644);
    if (fd == -1) return false;
    ssize_t n = write(fd, content, strlen(content));
    close(fd);
    return (n == (ssize_t)strlen(content));
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
    va_list args;
    const char *segment;
    char *p = dest;
    size_t remaining = dest_size - 1;
    va_start(args, dest_size);
    while ((segment = va_arg(args, const char *)) != NULL) {
        size_t len = strlen(segment);
        if (len > remaining) {
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
 * 连续核心聚成簇, 再按频率升序排列。最低频簇视为小核, 最高频簇视为大核,
 * 中间所有簇合并视为中核。结果以 "a-b" / "a,b" 形式写入 topo 的字符串字段,
 * 供 com.xxx=auto 校准完成后生成规则使用。
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
        /* 单一簇: 大中小都用全部核心 */
        build_str(topo->little_str, sizeof(topo->little_str), topo->all_str, NULL);
        build_str(topo->middle_str, sizeof(topo->middle_str), topo->all_str, NULL);
        build_str(topo->big_str, sizeof(topo->big_str), topo->all_str, NULL);
        /* 无大小核之分, 兜底只能用全部核心 */
        build_str(topo->nonbig_str, sizeof(topo->nonbig_str), topo->all_str, NULL);
        build_str(topo->middle_high_str, sizeof(topo->middle_high_str), topo->all_str, NULL);
        build_str(topo->middle_low_str, sizeof(topo->middle_low_str), topo->all_str, NULL);
        build_str(topo->base_str, sizeof(topo->base_str), topo->all_str, NULL);
    } else {
        /* 最低频 = 小核, 最高频 = 大核 */
        char* lo = cpu_set_to_str(&topo->clusters[0].cpus);
        char* hi = cpu_set_to_str(&topo->clusters[nc - 1].cpus);
        if (lo) { build_str(topo->little_str, sizeof(topo->little_str), lo, NULL); free(lo); }
        if (hi) { build_str(topo->big_str, sizeof(topo->big_str), hi, NULL); free(hi); }

        /* 中核 = 除首尾外所有簇的并集; 只有两簇时中核退化为小核范围 */
        cpu_set_t mid;
        CPU_ZERO(&mid);
        for (int k = 1; k < nc - 1; k++) CPU_OR(&mid, &mid, &topo->clusters[k].cpus);
        if (CPU_COUNT(&mid) == 0) {
            build_str(topo->middle_str, sizeof(topo->middle_str), topo->little_str, NULL);
        } else {
            char* m = cpu_set_to_str(&mid);
            if (m) { build_str(topo->middle_str, sizeof(topo->middle_str), m, NULL); free(m); }
        }

        /* 中核细分: 如果中核>=4个,按核心编号分为高低两档(高频一半 vs 低频一半)。
         * 用于更精细的负载分配——次重载线程用高中核,中载线程用低中核。 */
        int mid_count = CPU_COUNT(&mid);
        if (mid_count >= 4) {
            cpu_set_t mid_high, mid_low;
            CPU_ZERO(&mid_high);
            CPU_ZERO(&mid_low);
            int half = mid_count / 2;
            int cnt = 0;
            for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
                if (CPU_ISSET(cpu, &mid)) {
                    if (cnt < half) {
                        CPU_SET(cpu, &mid_low);
                    } else {
                        CPU_SET(cpu, &mid_high);
                    }
                    cnt++;
                }
            }
            char* mh = cpu_set_to_str(&mid_high);
            char* ml = cpu_set_to_str(&mid_low);
            if (mh) { build_str(topo->middle_high_str, sizeof(topo->middle_high_str), mh, NULL); free(mh); }
            if (ml) { build_str(topo->middle_low_str, sizeof(topo->middle_low_str), ml, NULL); free(ml); }
        } else {
            /* 中核<4个,不细分,高低档都用完整中核 */
            build_str(topo->middle_high_str, sizeof(topo->middle_high_str), topo->middle_str, NULL);
            build_str(topo->middle_low_str, sizeof(topo->middle_low_str), topo->middle_str, NULL);
        }

        /* 兜底范围 = 全部核心 - 最高频(大核)簇, 即小核+中核。
         * 这样进程整体与未点名的杂线程都不占用超大核, 把大核留给被显式绑定的重载线程,
         * 避免杂线程争抢/迁移污染大核, 提升关键重载线程的稳定性(对齐原模块 "=0-6" 的策略)。 */
        cpu_set_t nonbig;
        CPU_ZERO(&nonbig);
        for (int k = 0; k < nc - 1; k++) CPU_OR(&nonbig, &nonbig, &topo->clusters[k].cpus);
        if (CPU_COUNT(&nonbig) == 0) {
            build_str(topo->nonbig_str, sizeof(topo->nonbig_str), topo->all_str, NULL);
        } else {
            char* nb = cpu_set_to_str(&nonbig);
            if (nb) { build_str(topo->nonbig_str, sizeof(topo->nonbig_str), nb, NULL); free(nb); }
        }

        /* base 兜底(小核+低中核): 用于最轻量线程,进一步排除高中核和大核的干扰。
         * 如果中核未细分,则 base == nonbig。 */
        cpu_set_t base;
        CPU_ZERO(&base);
        CPU_OR(&base, &base, &topo->clusters[0].cpus);  /* 小核 */
        if (mid_count >= 4) {
            /* 加低中核 */
            for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
                if (CPU_ISSET(cpu, &mid)) {
                    int cnt = 0;
                    for (int c = 0; c < CPU_SETSIZE; c++) {
                        if (CPU_ISSET(c, &mid) && c < cpu) cnt++;
                    }
                    if (cnt < mid_count / 2) CPU_SET(cpu, &base);
                }
            }
        } else {
            /* 中核未细分,base = 小核+完整中核 = nonbig */
            CPU_OR(&base, &base, &mid);
        }
        char* bs = cpu_set_to_str(&base);
        if (bs) { build_str(topo->base_str, sizeof(topo->base_str), bs, NULL); free(bs); }
    }

    printf("CPU 拓扑识别: %d 个簇, 小核=[%s] 中核=[%s] 大核=[%s]\n",
           nc, topo->little_str, topo->middle_str, topo->big_str);
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

static AppConfig* load_config(const char* config_file, const CpuTopology* topo, time_t* last_mtime) {
    struct stat st;
    if (stat(config_file, &st)) return NULL;
    AppConfig* cfg = calloc(1, sizeof(AppConfig));
    if (!cfg) return NULL;
    cfg->ref_count = 1;
    cfg->topo = *topo;
    build_str(cfg->config_file, sizeof(cfg->config_file), config_file, NULL);

    if (last_mtime && *last_mtime == st.st_mtime && *last_mtime != -1) {
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
    char line[256];

    while (fgets(line, sizeof(line), fp)) {
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

    if (last_mtime) *last_mtime = st.st_mtime;
    cfg->rules = new_rules;
    cfg->num_rules = rules_cnt;
    cfg->pkgs = new_pkgs;
    cfg->num_pkgs = pkgs_cnt;
    cfg->auto_pkgs = new_auto;
    cfg->num_auto_pkgs = auto_cnt;
    cfg->mtime = st.st_mtime;

    fclose(fp);
    printf("配置文件解析完成，共加载 %zu 条规则, %zu 个待校准(auto)包\n", rules_cnt, auto_cnt);
    return cfg;

    error:
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

        bool found = false;
        for (size_t j = 0; j < cfg->num_pkgs; j++) {
            size_t plen = strlen(cfg->pkgs[j]);
            if (strcmp(name, cfg->pkgs[j]) == 0 ||
                (strncmp(name, cfg->pkgs[j], plen) == 0 && name[plen] == ':')) {
                found = true;
                break;
            }
        }
        if (!found) {
            close(pid_fd);
            continue;
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
            if (strcmp(rule->pkg, proc->pkg) != 0) continue;

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

    time_t last_mtime = -1;
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
                            last_mtime = -1;
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
 * 周期采样目标进程各线程的 CPU 占用; App 下发 stop 后, 按占用排序并依据
 * CPU 拓扑生成大/中/小核绑定规则(含通配符聚合), 回写 applist.conf。       */

/* 单个线程名的聚合采样数据 (同名线程跨多进程累加) */
typedef struct {
    char name[MAX_THREAD_LEN];   /* 线程名 (comm) */
    unsigned long long busy;     /* 累计 (utime+stime) jiffies 增量 */
    bool alive;                  /* 本轮采样是否仍存在 */
    unsigned long long round_delta;  /* 本轮(一次 sample_once)累计的增量, 用于算瞬时占比 */
    float* series;               /* 每轮瞬时占比(%)序列, 用于历史折线图 */
    size_t series_len;
    size_t series_cap;
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

typedef struct {
    char pkg[MAX_PKG_LEN];
    ThreadSample* threads;       /* 按线程名聚合 */
    size_t count;
    size_t cap;
    TidTrack* tids;              /* 按 (pid,tid) 跟踪增量 */
    size_t tcount;
    size_t tcap;
    size_t round_count;          /* 已完成的采样轮数, 即折线序列长度 */
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

/* 收集属于该应用的所有进程 pid: 主进程 (cmdline==pkg) 与子进程 (cmdline 以 "pkg:" 开头)。
 * 例如 com.tencent.tmgp.sgame 与 com.tencent.tmgp.sgame:GiftProcess 都计入。
 * 结果写入 out_pids (容量 max), 返回收集到的进程数。 */
static size_t collect_pkg_pids(const char* pkg, pid_t* out_pids, size_t max) {
    DIR* d = opendir("/proc");
    if (!d) return 0;
    int dfd = dirfd(d);
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
            out_pids[n++] = (pid_t)pid;
        }
    }
    closedir(d);
    return n;
}

/* 在 data 中按线程名查找, 不存在则追加, 返回索引; 失败返回 -1 */
static long calib_find_or_add(CalibData* data, const char* name) {
    for (size_t i = 0; i < data->count; i++) {
        if (strcmp(data->threads[i].name, name) == 0) return (long)i;
    }
    if (data->count >= data->cap) {
        size_t nc = data->cap ? data->cap * 2 : 64;
        ThreadSample* t = realloc(data->threads, nc * sizeof(ThreadSample));
        if (!t) return -1;
        data->threads = t;
        data->cap = nc;
    }
    ThreadSample* s = &data->threads[data->count];
    memset(s, 0, sizeof(*s));
    build_str(s->name, sizeof(s->name), name, NULL);
    return (long)data->count++;
}

/* 在 tid 跟踪表中按 (pid,tid) 查找, 不存在则追加, 返回指针; 失败返回 NULL */
static TidTrack* calib_track_tid(CalibData* data, pid_t pid, pid_t tid,
                                 const char* name) {
    for (size_t i = 0; i < data->tcount; i++) {
        if (data->tids[i].pid == pid && data->tids[i].tid == tid)
            return &data->tids[i];
    }
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

/* 对单个进程 task/ 做一遍扫描, 把每线程增量累加进按名聚合表。
 * 返回 false 表示该进程已不存在。 */
static bool calib_sample_proc(CalibData* data, pid_t pid) {
    char taskpath[64];
    snprintf(taskpath, sizeof(taskpath), "/proc/%d/task", pid);
    DIR* td = opendir(taskpath);
    if (!td) return false;
    int task_fd = dirfd(td);

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

        /* 增量累加到按线程名聚合的统计 */
        long idx = calib_find_or_add(data, tname);
        if (idx < 0) continue;
        ThreadSample* s = &data->threads[idx];
        s->alive = true;
        s->busy += delta;
        s->round_delta += delta;   /* 本轮累计, sample_once 收尾时折算瞬时占比 */
    }
    closedir(td);
    return true;
}

/* 给线程的瞬时占比序列追加一个采样点 */
static void calib_series_push(ThreadSample* s, float pct) {
    if (s->series_len >= s->series_cap) {
        size_t nc = s->series_cap ? s->series_cap * 2 : 32;
        float* p = realloc(s->series, nc * sizeof(float));
        if (!p) return;
        s->series = p;
        s->series_cap = nc;
    }
    s->series[s->series_len++] = pct;
}

/* 对目标应用做一次采样: 收集主进程与所有 :子进程, 逐一扫描线程。
 * 返回 false 表示应用所有进程都已消失 (游戏退出)。 */
static bool calib_sample_once(CalibData* data) {
    pid_t pids[64];
    size_t np = collect_pkg_pids(data->pkg, pids, 64);
    if (np == 0) return false;

    for (size_t i = 0; i < data->count; i++) {
        data->threads[i].alive = false;
        data->threads[i].round_delta = 0;
    }
    for (size_t i = 0; i < data->tcount; i++) data->tids[i].alive = false;

    bool any = false;
    bool had_baseline = (data->round_count > 0) || (data->tcount > 0);
    for (size_t i = 0; i < np; i++) {
        if (calib_sample_proc(data, pids[i])) any = true;
    }
    if (!any) return false;

    /* 收尾: 把本轮各线程增量折算成瞬时占比, 追加到折线序列。
     * 第一轮没有基准(delta 全 0), 跳过记录以免折线全是 0。 */
    if (had_baseline) {
        unsigned long long round_total = 0;
        for (size_t i = 0; i < data->count; i++)
            round_total += data->threads[i].round_delta;
        if (round_total > 0) {
            for (size_t i = 0; i < data->count; i++) {
                float pct = (float)((double)data->threads[i].round_delta * 100.0
                                    / (double)round_total);
                calib_series_push(&data->threads[i], pct);
            }
            data->round_count++;
        }
    }
    return any;
}

/* 求线程名的"通配基名": 找到第一个数字, 截断(连同数字前的尾部空格)并加 '*'。
 * 例如 "Job.worker 0" -> "Job.worker*", "Thread-12" -> "Thread-*",
 * "GameLoop" 无数字 -> 原样返回(不加通配)。out 至少 MAX_THREAD_LEN 字节。 */
static void calib_wildcard_base(const char* name, char* out, size_t out_sz) {
    size_t i = 0;
    for (; name[i] && i < out_sz - 2; i++) {
        if (isdigit((unsigned char)name[i])) break;
        out[i] = name[i];
    }
    if (name[i] && isdigit((unsigned char)name[i])) {
        /* 去掉数字前的尾部空格, 让通配符紧贴前缀: "Job.worker *" -> "Job.worker*" */
        while (i > 0 && out[i - 1] == ' ') i--;
        out[i] = '*';
        out[i + 1] = '\0';
    } else {
        out[i] = '\0';
    }
}

static char* calib_merge_cpu_ranges(const CpuTopology* topo,
                                    const char* a, const char* b) {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (a && a[0]) parse_cpu_ranges(a, &set, &topo->present_cpus);
    if (b && b[0]) parse_cpu_ranges(b, &set, &topo->present_cpus);
    if (CPU_COUNT(&set) == 0) return NULL;
    return cpu_set_to_str(&set);
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
    double sum = 0.0;
    double mx = 0.0;
    if (!s || s->series_len == 0) {
        *avg = 0.0;
        *max = 0.0;
        return;
    }
    for (size_t i = 0; i < s->series_len; i++) {
        double v = s->series[i];
        sum += v;
        if (v > mx) mx = v;
    }
    *avg = sum / (double)s->series_len;
    *max = mx;
}

/* 聚合后的通配组 */
typedef struct {
    char base[MAX_THREAD_LEN];   /* 通配基名(可能含尾部 '*') */
    unsigned long long busy;     /* 组内线程 busy 之和 */
    double avg_pct;              /* 组内线程平均占比之和 */
    double max_pct;              /* 组内最高瞬时占比 */
    double score;                /* 综合评分, 用于 Top N 排序 */
    bool is_wild;                /* base 是否含通配符 */
} CalibGroup;

/* 根据采样结果生成规则文本, 追加写入 out_buf (调用方保证足够大)。
 * 返回生成的规则行数。 */
static int calib_generate_rules(CalibData* data, const CpuTopology* topo,
                                char* out_buf, size_t out_sz) {
    if (data->count == 0) return 0;

    /* 1) 按 busy 降序排序 */
    qsort(data->threads, data->count, sizeof(ThreadSample), calib_cmp_busy);

    /* 2) 聚合为通配组 */
    CalibGroup* groups = calloc(data->count, sizeof(CalibGroup));
    if (!groups) return 0;
    size_t ng = 0;
    unsigned long long total = 0;
    for (size_t i = 0; i < data->count; i++) {
        total += data->threads[i].busy;
        char base[MAX_THREAD_LEN];
        calib_wildcard_base(data->threads[i].name, base, sizeof(base));
        long gi = -1;
        for (size_t g = 0; g < ng; g++) {
            if (strcmp(groups[g].base, base) == 0) { gi = (long)g; break; }
        }
        if (gi < 0) {
            gi = (long)ng++;
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
        groups[gi].avg_pct += avg_pct;
        if (max_pct > groups[gi].max_pct) groups[gi].max_pct = max_pct;
    }
    if (total == 0) { free(groups); return 0; }

    for (size_t g = 0; g < ng; g++) {
        if (groups[g].avg_pct > 100.0) groups[g].avg_pct = 100.0;
        groups[g].score = groups[g].avg_pct * 0.65 + groups[g].max_pct * 0.35;
    }

    /* 组按 avg/max 综合评分降序, 峰值线程不会被累计 busy 掩盖。 */
    for (size_t a = 0; a + 1 < ng; a++)
        for (size_t b = 0; b + 1 < ng - a; b++)
            if (groups[b].score < groups[b + 1].score) {
                CalibGroup t = groups[b]; groups[b] = groups[b + 1]; groups[b + 1] = t;
            }

    char* high_perf = calib_merge_cpu_ranges(topo,
        topo->middle_high_str[0] ? topo->middle_high_str : topo->middle_str,
        topo->big_str);
    char* perf = calib_merge_cpu_ranges(topo, topo->middle_str, topo->big_str);

    /* 3) 按负载分级, 同时参考 avg 与 max:
     *    - Top1 且 avg>=25% 且 max>=35%: 大核。
     *    - avg>=12% 或 max>=22%: 高中核+大核。
     *    - avg>=6%  或 max>=12%: 中核+大核。
     *    - 其余由进程级兜底覆盖。
     *    不按线程名白名单/黑名单猜职责, 避免不同游戏命名差异导致误判。
     *    只输出 Top N 线程规则, 避免生成过多静态绑核规则导致调度僵硬。 */
    char* p = out_buf;
    size_t remain = out_sz - 1;
    int lines = 0;
    int thread_lines = 0;
    const int max_thread_lines = 6;
    char line[256];
    for (size_t g = 0; g < ng && thread_lines < max_thread_lines; g++) {
        const char* tier = NULL;
        double avg = groups[g].avg_pct;
        double max = groups[g].max_pct;
        if (g == 0 && avg >= 25.0 && max >= 35.0) {
            tier = topo->big_str[0] ? topo->big_str : (high_perf ? high_perf : topo->all_str);
        } else if (avg >= 12.0 || max >= 22.0) {
            tier = high_perf ? high_perf : topo->all_str;
        } else if (avg >= 6.0 || max >= 12.0) {
            tier = perf ? perf : topo->all_str;
        } else {
            continue;
        }
        if (!tier || !tier[0]) continue;
        int need = snprintf(line, sizeof(line), "%s{%s}=%s\n",
                            data->pkg, groups[g].base, tier);
        if (need < 0 || (size_t)need > remain) break;
        memcpy(p, line, need);
        p += need; remain -= need; lines++; thread_lines++;
    }

    /* 4) 进程级兜底规则: 使用小核+中核(e_core+p_core), 对齐作者预置规则里的 0-6。
     *    不再使用更窄的 base_str(如 0-4), 避免未知关键线程被压到低档核心。 */
    const char* base_tier = topo->nonbig_str[0] ? topo->nonbig_str :
                            (topo->all_str[0] ? topo->all_str : topo->base_str);
    if (base_tier[0]) {
        int need = snprintf(line, sizeof(line), "%s=%s\n", data->pkg, base_tier);
        if (need > 0 && (size_t)need <= remain) {
            memcpy(p, line, need); p += need; remain -= need; lines++;
        }
    }
    *p = '\0';
    free(high_perf);
    free(perf);
    free(groups);
    return lines;
}

/* 把每线程负载历史写入 history/<pkg>.log (Scene 风格折线数据)。
 * 格式: 每段首行 "# <epoch> <采样轮数>", 随后每行:
 *   "<AVG%> <MAX%> <线程名>|<p1,p2,...,pN>"
 * 其中 p* 为每轮采样的瞬时占比(%)。每个包名最多保留最近 HISTORY_MAX_SESSIONS 段会话。 */
#define HISTORY_MAX_SESSIONS 7

static void calib_write_history(const char* pkg, CalibData* data) {
    if (data->count == 0) return;
    mkdir(HISTORY_DIR, 0755);

    unsigned long long total = 0;
    for (size_t i = 0; i < data->count; i++) total += data->threads[i].busy;
    if (total == 0) return;

    char path[512];
    char safe[MAX_PKG_LEN];
    /* 包名作为文件名: 不含路径分隔符, 直接用 */
    build_str(safe, sizeof(safe), pkg, NULL);
    build_str(path, sizeof(path), HISTORY_DIR, "/", safe, ".log", NULL);

    /* 1) 把本次会话格式化到内存缓冲: 段头 + 每线程(AVG MAX 名称|折线) */
    char* cur = NULL;
    size_t cur_len = 0;
    FILE* mem = open_memstream(&cur, &cur_len);
    if (mem) {
        fprintf(mem, "# %ld %zu\n", (long)time(NULL), data->round_count);
        for (size_t i = 0; i < data->count; i++) {
            ThreadSample* s = &data->threads[i];
            if (s->series_len == 0) continue;
            /* AVG / MAX 基于该线程实际采样点 */
            double sum = 0.0; float mx = 0.0f;
            for (size_t k = 0; k < s->series_len; k++) {
                sum += s->series[k];
                if (s->series[k] > mx) mx = s->series[k];
            }
            double avg = sum / (double)s->series_len;
            if (mx < 0.05f && avg < 0.05) continue;   /* 整段几乎零负载的线程略过 */
            fprintf(mem, "%.2f %.2f %s|", avg, mx, s->name);
            for (size_t k = 0; k < s->series_len; k++) {
                fprintf(mem, k ? ",%.2f" : "%.2f", s->series[k]);
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

/* 把生成的规则写回配置文件: 逐行复制原文件, 遇到 "pkg=auto" 行替换为 rules_text,
 * 其余行原样保留。成功返回 true。 */
static bool calib_write_back(const char* config_file, const char* pkg,
                             const char* rules_text) {
    FILE* in = fopen(config_file, "r");
    if (!in) return false;
    char tmp_path[4096 + 8];
    build_str(tmp_path, sizeof(tmp_path), config_file, ".tmp", NULL);
    FILE* out = fopen(tmp_path, "w");
    if (!out) { fclose(in); return false; }

    char line[256];
    bool replaced = false;
    while (fgets(line, sizeof(line), in)) {
        char copy[256];
        build_str(copy, sizeof(copy), line, NULL);
        char* t = strtrim(copy);
        bool is_target = false;
        if (*t != '#' && *t) {
            char* eq = strchr(t, '=');
            if (eq) {
                char saved = *eq;
                *eq = '\0';
                char* lpkg = strtrim(t);
                char* lval = strtrim(eq + 1);
                if (!strchr(lpkg, '{') && strcmp(lpkg, pkg) == 0
                    && strcmp(lval, "auto") == 0) {
                    is_target = true;
                }
                *eq = saved;
            }
        }
        if (is_target) {
            fputs(rules_text, out);   /* 用生成规则替换该 auto 行 */
            replaced = true;
        } else {
            fputs(line, out);
        }
    }
    if (!replaced) {                  /* 原文件无对应 auto 行则追加 */
        fputs(rules_text, out);
    }
    fclose(in);
    fclose(out);
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

/* 读取并清空命令文件; 返回是否读到内容。cmd_buf 收到形如 "start <pkg>" 的命令 */
static bool calib_read_cmd(char* cmd_buf, size_t sz) {
    if (!read_file(AT_FDCWD, CALIB_CMD_FILE, cmd_buf, sz)) return false;
    unlink(CALIB_CMD_FILE);          /* 消费后删除, 避免重复触发 */
    strtrim(cmd_buf);
    return cmd_buf[0] != '\0';
}

static void calib_free(CalibData* d) {
    if (d->threads) {
        for (size_t i = 0; i < d->count; i++) free(d->threads[i].series);
    }
    free(d->threads);
    free(d->tids);
    d->threads = NULL;
    d->tids = NULL;
    d->count = d->cap = 0;
    d->tcount = d->tcap = 0;
    d->round_count = 0;
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
            if (strncmp(cmd, "start ", 6) == 0) {
                const char* pkg = strtrim(cmd + 6);
                pid_t probe[8];
                size_t np = collect_pkg_pids(pkg, probe, 8);
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
            } else if (strncmp(cmd, "stop", 4) == 0) {
                if (sampling) {
                    sampling = false;
                    printf("[校准] 收到停止命令: %s 共采样 %zu 轮, 捕获 %zu 个线程, 开始生成规则\n",
                           data.pkg, data.round_count, data.tcount);

                    /* 最低采样要求: 至少 60 轮 (约 30 秒), 否则数据不足以反映真实负载 */
                    if (data.round_count < 60) {
                        printf("[校准] 警告: %s 采样时长不足 (仅 %zu 轮, 建议 >=60 轮), 未生成规则\n",
                               data.pkg, data.round_count);
                        /* 采样不足时不保存历史记录, 避免垃圾数据干扰分析 */
                        char st[MAX_PKG_LEN + 64];
                        snprintf(st, sizeof(st), "done %s;reason=short", data.pkg);
                        calib_set_state(st);
                        calib_free(&data);
                        continue;
                    }

                    char rules[2048];
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
                        printf("[校准] 警告: %s 未能生成规则 (线程负载样本不足?)\n", data.pkg);
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
                printf("[校准] %s 进程已退出, 用现有 %zu 轮/%zu 线程数据直接生成规则\n",
                       data.pkg, data.round_count, data.tcount);
                char rules[2048];
                int n = calib_generate_rules(&data, &g_topo, rules, sizeof(rules));
                calib_write_history(data.pkg, &data);
                if (n > 0) {
                    AppConfig* cfg = get_config();
                    const char* cf = cfg ? cfg->config_file : g_config_file;
                    if (calib_write_back(cf, data.pkg, rules)) {
                        printf("[校准] 已为 %s 生成 %d 条规则:\n%s", data.pkg, n, rules);
                        atomic_store(&config_updated, 1);
                    } else {
                        printf("[校准] 警告: %s 规则写回配置失败 (路径 %s)\n", data.pkg, cf);
                    }
                    if (cfg) config_release(cfg);
                } else {
                    printf("[校准] 警告: %s 未能生成规则 (线程负载样本不足?)\n", data.pkg);
                }
                /* 进程退出也算完成一次校准并已生成规则, 与 stop 分支保持一致写 done,
                 * 否则 App 侧 waitDone() 会因状态停留在 idle 而误判超时。 */
                char st[MAX_PKG_LEN + 16];
                snprintf(st, sizeof(st), "done %s", data.pkg);
                calib_set_state(st);
                calib_free(&data);
            } else if (data.round_count != prev_rounds && data.round_count % 20 == 0) {
                /* 每 20 轮(约 10s)报一次进度, 避免每 0.5s 刷屏 */
                printf("[校准] %s 采样中... 已 %zu 轮, 当前 %zu 个线程\n",
                       data.pkg, data.round_count, data.tcount);
            }
        }
        usleep(500 * 1000);   /* 0.5s 采样周期 */
    }
    return NULL;
}

/* =====================================================================
 * 真实帧率(FPS)监测模块  ——  dumpsys SurfaceFlinger --latency 方案
 *
 * 背景: perfetto 的 android.surfaceflinger.frametimeline 在部分 ROM(MIUI 等)
 *   上抓不到游戏 SurfaceView 的渲染帧(合成路径被改), 实测只能拿到悬浮窗/Toast
 *   等覆盖层。改用历史更可靠的 SurfaceFlinger --latency:
 *
 *   1) dumpsys SurfaceFlinger --list  列出所有 layer 名(每行一个, 含 #NNN 后缀)。
 *      游戏主渲染层是含包名的 SurfaceView, 例如:
 *        SurfaceView[com.x/.../Activity](BLAST)#27973
 *      同包名还有 ActivityRecord/容器/Background 等层, 但它们 0 帧。
 *   2) 等一个窗口(FPS_WINDOW_MS)后, 对候选层执行
 *        dumpsys SurfaceFlinger --latency "<layer>"
 *      输出: 首行=刷新周期(ns); 之后每行 3 个 ns 时间戳, 第2列=实际上屏时间。
 *      跳过 0 与 INT64_MAX(未上屏)哨兵。差分读: 只统计上屏时间晚于上次基准的
 *      新帧, 并把基准推进到本次见到的最新帧。不调用 --latency-clear(那会清掉
 *      全局共享缓冲、干扰 Scene FAS 等同样读该缓冲的工具), 与 Scene 同法。
 *   3) 在所有含包名的候选层里取帧数最多的那个 = 主渲染面, 锁定其层名;
 *      之后每窗口只读该层。该层读到 0 帧多次(场景切换/层重建导致 #NNN 变化)
 *      则重新发现。FPS 覆盖写到 app 私有目录并 chcon 成 app 可读。
 *
 * 全程用 fork+execv(不经 shell), 层名含空格/括号也无引号/注入问题。
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

/* 把整型 fps 覆盖写到 app 私有目录的 fps 文件, 并修标签+权限让 app 能读。
 * 跨 SELinux 域: root(此进程)写完后给文件打 app_data_file 标签, app 才读得到。*/
static void fps_write_out(double fps) {
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
    if (fd < 0) return;
    ssize_t w = write(fd, val, (size_t)n);
    (void)w;
    close(fd);
    /* chmod/chcon: app 进程(非 root)需可读。私有目录文件默认带创建者(root)上下文,
     * app 域读不到, 必须改成 app_data_file。O_TRUNC 覆盖写复用同一 inode, SELinux
     * 标签随 inode 保留, 故仅在文件新建时设一次; 避免每窗口都 fork chcon 拖慢更新频率。*/
    if (fresh) {
        chmod(FPS_OUT_FILE, 0666);
        system("chcon u:object_r:app_data_file:s0 " FPS_OUT_FILE " 2>/dev/null");
    }
}

/* 读取并消费 fps.cmd; cmd_buf 收到 "start <pkg>" / "stop"。无命令返回 false。*/
static bool fps_read_cmd(char* cmd_buf, size_t sz) {
    if (!read_file(AT_FDCWD, FPS_CMD_FILE, cmd_buf, sz)) return false;
    unlink(FPS_CMD_FILE);
    strtrim(cmd_buf);
    return cmd_buf[0] != '\0';
}

/*
 * FPS 监测线程。协议(纯文本文件):
 *   App -> 守护: 写 FPS_CMD_FILE, 内容 "start <pkg>" / "stop"
 *   守护 -> App: 每窗口覆盖写 FPS_OUT_FILE(app 私有目录), 内容为浮点 FPS
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
    char cmd[256];

    /* eBPF 状态 */
    ebpf_fps_ctx *ebpf_ctx = NULL;
    pid_t target_pid = -1;
    bool ebpf_first_fps = true;
    bool ebpf_pid_reported = false;

    /* Fallback 状态(包含 --latency + timestats) */
    fps_fallback_ctx *fallback_ctx = NULL;
    bool fallback_first_fps = true;

    for (;;) {
        /* 轮询命令 */
        if (fps_read_cmd(cmd, sizeof(cmd))) {
            if (strncmp(cmd, "start ", 6) == 0) {
                const char* p = strtrim(cmd + 6);
                if (strlen(p) < MAX_PKG_LEN) {
                    /* 清理旧状态 */
                    if (ebpf_ctx) { ebpf_fps_stop(ebpf_ctx); ebpf_ctx = NULL; }
                    if (fallback_ctx) { fps_fallback_stop(fallback_ctx); fallback_ctx = NULL; }

                    build_str(pkg, sizeof(pkg), p, NULL);
                    monitoring = true;
                    ebpf_first_fps = true;
                    ebpf_pid_reported = false;
                    fallback_first_fps = true;

                    /* 1. 探测 eBPF 能力 */
                    ebpf_cap_t cap = ebpf_fps_probe_capability();
                    printf("[FPS] 开始监测 %s, eBPF 能力: %s\n", pkg, ebpf_cap_str(cap));

                    if (cap == EBPF_CAP_OK) {
                        /* 2. 找目标进程 PID。游戏刚启动时 /proc/cmdline 可能短暂不可见, 等一小段时间。 */
                        target_pid = -1;
                        for (int attempt = 0; attempt < 30 && target_pid < 0; attempt++) {
                            pid_t pids[64];
                            size_t np = collect_pkg_pids(pkg, pids, 64);
                            if (np > 0) {
                                target_pid = pids[0];
                                break;
                            }
                            usleep(100 * 1000);
                        }
                        if (target_pid < 0) {
                            printf("[FPS] 等待约 3 秒仍未找到 %s 的进程, 尝试全局 eBPF 帧事件探测\n", pkg);
                        }

                        /* 3. 尝试 eBPF attach */
                        if (target_pid > 0) {
                            printf("[FPS] 目标进程 PID: %d, 尝试 eBPF uprobe...\n", target_pid);
                            ebpf_ctx = ebpf_fps_start(FPS_BPF_OBJ, target_pid, pkg);
                        } else {
                            printf("[FPS] 未锁定包名 PID, 尝试 eBPF 全局 uprobe 探测屏幕帧事件...\n");
                            ebpf_ctx = ebpf_fps_start(FPS_BPF_OBJ, (pid_t)-1, pkg);
                        }

                        if (ebpf_ctx) {
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
            } else if (strncmp(cmd, "stop", 4) == 0) {
                if (monitoring) {
                    monitoring = false;
                    if (ebpf_ctx) { ebpf_fps_stop(ebpf_ctx); ebpf_ctx = NULL; }
                    if (fallback_ctx) { fps_fallback_stop(fallback_ctx); fallback_ctx = NULL; }
                    printf("[FPS] 停止监测 %s\n", pkg);
                    fps_write_out(0);
                }
            }
        }

        if (!monitoring) {
            usleep(300 * 1000);
            continue;
        }

        /* === eBPF 模式: 高频轮询 RingBuf === */
        if (ebpf_ctx) {
            ebpf_fps_poll(ebpf_ctx);
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
            fps_write_out(fps);
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
            fps_write_out(fps);
            usleep(1000 * 1000);  /* 1 秒窗口 */
            continue;
        }

        /* 无可用数据源 */
        usleep(500 * 1000);
        fps_write_out(0);
    }
    return NULL;
}

static void print_help(const char* prog_name) {
    printf("Usage: %s [OPTIONS]\n", prog_name);
    printf("Options:\n");
    printf("  -c <config_file>   指定配置文件 (默认: ./applist.conf)\n");
    printf("  -s <interval>      设置检查间隔(秒) (必须>=1, 默认: 2)\n");
    printf("  -v                 显示程序版本\n");
    printf("  -h                 显示帮助信息\n");
    printf("\n示例:\n");
    printf("  %s -c /data/applist.conf -s 3\n", prog_name);
}

int main(int argc, char **argv) {
    /* stdout/stderr 重定向到文件(service.sh 写 AppOpt.log)时默认是全缓冲,
     * 长驻守护进程填不满 4KB 缓冲区, 日志会一直卡在内存里不落盘 ->
     * 表现为日志文件长期为空。改成行缓冲: 每输出一行立即写入文件。
     * 必须在任何 printf(含下面 init_cpu_topo)之前设置。 */
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);

    CpuTopology topo = init_cpu_topo();
    g_topo = topo;                     /* 校准线程使用的拓扑快照 */
    char config_file[4096] = "./applist.conf";
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

    /* 启动真实帧率监测线程 (perfetto frametimeline, App 下发 start/stop) */
    pthread_t fps_tid;
    if (pthread_create(&fps_tid, NULL, fps_thread, NULL) == 0) {
        pthread_detach(fps_tid);
        printf("启用真实帧率监测线程 (perfetto)\n");
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
            printf("Android 版本: %s", android_ver);
            /* API Level */
            if (get_prop_first(api_level, sizeof(api_level), api_level_keys,sizeof(api_level_keys) / sizeof(api_level_keys[0]))) {
                printf(" (API %s)", api_level);
            }
            printf("\n");
        }

        /* 设备品牌和型号 */
        if (get_prop_first(brand, sizeof(brand), brand_keys,sizeof(brand_keys) / sizeof(brand_keys[0]))) {
            printf("设备品牌: %s", brand);
            if (get_prop_first(model, sizeof(model), market_model_keys,sizeof(market_model_keys) / sizeof(market_model_keys[0]))) {
                printf(" %s", model);
            } else if (get_prop_first(model, sizeof(model), cert_model_keys,sizeof(cert_model_keys) / sizeof(cert_model_keys[0]))) {
                printf(" %s", model);
            }
            printf("\n");
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

    for (;;) {
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
}
