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

