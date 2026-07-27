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

/* 按 (pid,tid,starttime) 跟踪 CPU，用于在多进程下正确计算每个线程的增量，
 * 避免不同进程的同名线程互相覆盖基准值，也避免 TID 复用沿用旧基线。 */
typedef struct {
    pid_t pid;
    pid_t tid;
    unsigned long long starttime; /* /proc stat 字段 22，防止 TID 复用沿用旧基线 */
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
    long long started_ms;        /* 本次校准开始时间, 用于异常长会话兜底收尾 */
    long clock_ticks_per_second;
} CalibData;

/* 在 stat 文件中解析 utime(14) + stime(15) 与 starttime(22)。stat 的 comm 字段
 * 可能含空格/括号，故从最后一个 ')' 之后开始按空格切分。 */
static bool read_thread_cpu(int thread_fd, unsigned long long* cpu_out,
                            unsigned long long* starttime_out) {
    if (thread_fd < 0 || !cpu_out || !starttime_out) return false;
    char buf[512];
    if (!read_file(thread_fd, "stat", buf, sizeof(buf))) return false;

    char* rp = strrchr(buf, ')');
    if (!rp) return false;
    rp++;                              /* 跳到 comm 之后 */
    /* 此处第一个 token 是 state(字段 3)。 */
    int field = 2;
    unsigned long long utime = 0;
    unsigned long long stime = 0;
    unsigned long long starttime = 0;
    bool have_utime = false;
    bool have_stime = false;
    bool have_starttime = false;
    char* save = NULL;
    char* tok = strtok_r(rp, " \t\n", &save);
    while (tok) {
        field++;
        if (field == 14) {
            utime = strtoull(tok, NULL, 10);
            have_utime = true;
        } else if (field == 15) {
            stime = strtoull(tok, NULL, 10);
            have_stime = true;
        } else if (field == 22) {
            starttime = strtoull(tok, NULL, 10);
            have_starttime = true;
            break;
        }
        tok = strtok_r(NULL, " \t\n", &save);
    }
    if (!have_utime || !have_stime || !have_starttime || starttime == 0) return false;
    *cpu_out = utime + stime;
    *starttime_out = starttime;
    return true;
}

#define CALIB_MAX_SESSION_MS (6LL * 60LL * 60LL * 1000LL)
#define CALIB_PROGRESS_LOG_ROUNDS 120

typedef struct {
    pid_t pid;
    unsigned long long starttime;
    char owner[MAX_PKG_LEN];
} CalibProcess;

/* 收集属于该应用的所有进程: 主进程 (cmdline==pkg) 与子进程 (cmdline 以 "pkg:" 开头)。
 * 例如 com.tencent.tmgp.sgame 与 com.tencent.tmgp.sgame:GiftProcess 都计入。
 * 结果保留 pid、starttime 与真实进程名, 返回收集到的进程数。 */
static size_t collect_pkg_processes(const char* pkg, CalibProcess* out, size_t max) {
    if (!pkg || !pkg[0] || !out || max == 0) return 0;
    size_t plen = strlen(pkg);
    if (plen >= MAX_PKG_LEN) return 0;
    DIR* d = opendir("/proc");
    if (!d) return 0;
    int dfd = dirfd(d);
    if (dfd < 0) {
        closedir(d);
        return 0;
    }
    struct dirent* e;
    size_t n = 0;
    bool have_main = false;
    while ((e = readdir(d))) {
        char* end;
        long pid = strtol(e->d_name, &end, 10);
        if (*end != '\0') continue;
        int pfd = openat(dfd, e->d_name, O_RDONLY | O_DIRECTORY);
        if (pfd == -1) continue;
        char cmd[MAX_PKG_LEN] = {0};
        bool ok = read_file(pfd, "cmdline", cmd, sizeof(cmd));
        if (!ok) {
            close(pfd);
            continue;
        }
        char* name = strrchr(cmd, '/');
        name = name ? name + 1 : cmd;
        /* 主进程: 完全相等; 子进程: "pkg:子进程名" */
        bool is_main = strcmp(name, pkg) == 0;
        bool is_child = strncmp(name, pkg, plen) == 0 && name[plen] == ':';
        if (!is_main && !is_child) {
            close(pfd);
            continue;
        }
        unsigned long long process_cpu = 0;
        unsigned long long process_starttime = 0;
        ok = read_thread_cpu(pfd, &process_cpu, &process_starttime);
        close(pfd);
        if (!ok) continue;
        size_t slot = SIZE_MAX;
        if (is_main) {
            if (n < max) {
                if (!have_main && n > 0) {
                    out[n] = out[0];
                    slot = 0;
                } else {
                    slot = n;
                }
                n++;
            } else if (!have_main) {
                slot = 0;
            }
            have_main = true;
        } else if (is_child && n < max) {
            slot = n++;
        }
        if (slot != SIZE_MAX) {
            out[slot].pid = (pid_t)pid;
            out[slot].starttime = process_starttime;
            build_str(out[slot].owner, sizeof(out[slot].owner), name, NULL);
        }
    }
    closedir(d);
    return n;
}

static bool calib_has_main_process(const CalibProcess* procs, size_t count,
                                   const char* pkg) {
    if (!procs || !pkg) return false;
    for (size_t i = 0; i < count; i++) {
        if (strcmp(procs[i].owner, pkg) == 0) return true;
    }
    return false;
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

/* 在 tid 跟踪表中按 (pid,tid) 查找；starttime 变化时重置增量基线。 */
static TidTrack* calib_track_tid(CalibData* data, pid_t pid, pid_t tid,
                                 unsigned long long starttime, const char* name) {
    for (size_t i = 0; i < data->tcount; i++) {
        if (data->tids[i].pid == pid && data->tids[i].tid == tid) {
            if (data->tids[i].starttime != starttime) {
                data->tids[i].starttime = starttime;
                data->tids[i].last = 0;
                data->tids[i].has_last = false;
            }
            build_str(data->tids[i].name, sizeof(data->tids[i].name), name, NULL);
            return &data->tids[i];
        }
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
    tk->starttime = starttime;
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
static bool calib_sample_proc(CalibData* data, pid_t pid, const char* owner,
                              unsigned long long expected_starttime) {
    char procpath[64];
    snprintf(procpath, sizeof(procpath), "/proc/%d", pid);
    int process_fd = open(procpath, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (process_fd == -1) return false;

    char cmd[MAX_PKG_LEN] = {0};
    unsigned long long process_cpu = 0;
    unsigned long long process_starttime = 0;
    bool identity_ok = read_file(process_fd, "cmdline", cmd, sizeof(cmd)) &&
        read_thread_cpu(process_fd, &process_cpu, &process_starttime);
    char* current_owner = strrchr(cmd, '/');
    current_owner = current_owner ? current_owner + 1 : cmd;
    identity_ok = identity_ok && strcmp(current_owner, owner) == 0 &&
        process_starttime == expected_starttime;
    if (!identity_ok) {
        close(process_fd);
        return false;
    }

    int raw_task_fd = openat(process_fd, "task", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    close(process_fd);
    if (raw_task_fd == -1) return false;
    DIR* td = fdopendir(raw_task_fd);
    if (!td) {
        close(raw_task_fd);
        return false;
    }
    int task_fd = dirfd(td);
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
        unsigned long long cpu = 0;
        unsigned long long starttime = 0;
        if (ok) ok = read_thread_cpu(tfd, &cpu, &starttime);
        close(tfd);
        if (!ok) continue;
        char* trimmed_name = strtrim(tname);

        /* 用 (pid,tid,starttime) 跟踪正确的增量基准。 */
        TidTrack* tk = calib_track_tid(data, pid, (pid_t)tid, starttime, trimmed_name);
        if (!tk) continue;
        tk->alive = true;
        unsigned long long delta = 0;
        if (tk->has_last && cpu >= tk->last) delta = cpu - tk->last;
        tk->last = cpu;
        tk->has_last = true;

        if (is_main_process) {
            long idx = calib_find_or_add(data, owner, trimmed_name, false);
            if (idx < 0) continue;
            ThreadSample* s = &data->threads[idx];
            s->alive = true;
            s->busy += delta;
            s->round_delta += delta;
        } else {
            process_sample->busy += delta;
            process_sample->round_delta += delta;
            if (delta > 0) {
                ChildThreadSummary* summary = calib_track_child_thread(data, owner, trimmed_name);
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
 * 返回 false 表示主进程已消失或身份校验失败，常驻子进程不能单独维持校准。 */
static bool calib_sample_once(CalibData* data) {
    CalibProcess procs[64];
    size_t np = collect_pkg_processes(data->pkg, procs, 64);
    if (np == 0 || !calib_has_main_process(procs, np, data->pkg)) return false;

    for (size_t i = 0; i < data->count; i++) {
        data->threads[i].alive = false;
        data->threads[i].round_delta = 0;
    }
    for (size_t i = 0; i < data->child_thread_count; i++) {
        data->child_threads[i].round_delta = 0;
    }
    for (size_t i = 0; i < data->tcount; i++) data->tids[i].alive = false;

    bool any = false;
    bool main_sampled = false;
    bool had_baseline = data->last_sample_ms > 0;
    for (size_t i = 0; i < np; i++) {
        if (calib_sample_proc(data, procs[i].pid, procs[i].owner,
                              procs[i].starttime)) {
            any = true;
            if (strcmp(procs[i].owner, data->pkg) == 0) main_sampled = true;
        }
    }
    if (!any || !main_sampled) return false;

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
            c == '*' || c == '?' || c == '[' || c == ']' ||
            c == '\n' || c == '\r' || (c < ' ' && c != '\t')) {
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

#define CALIB_MAX_NUMBER_SEGMENTS ((MAX_THREAD_LEN + 1) / 2)

typedef struct {
    bool valid;
    size_t length;
    size_t count;
    size_t starts[CALIB_MAX_NUMBER_SEGMENTS];
    size_t ends[CALIB_MAX_NUMBER_SEGMENTS];
    size_t stable_anchors;
} CalibNumericShape;

typedef struct {
    bool valid;
    char own_base[MAX_THREAD_LEN];
    char canonical[MAX_THREAD_LEN];
    size_t observed_coverage;
    size_t specificity;
    size_t pattern_chars;
} CalibRuleBaseInfo;

static bool calib_ascii_alpha(unsigned char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}

static bool calib_direct_number_delimiter(unsigned char c) {
    return c == ' ' || c == '\t' || c == '-' || c == '_';
}

/* 数字只按 ASCII 字节分段，非 ASCII 字节原样保留，避免截断 UTF-8。 */
static bool calib_parse_numeric_shape(const char* name, CalibNumericShape* out) {
    if (!name || !out || !calib_rule_name_syntax_ok(name)) return false;
    memset(out, 0, sizeof(*out));
    out->length = strlen(name);

    for (size_t i = 0; i < out->length;) {
        unsigned char c = (unsigned char)name[i];
        if (c >= '0' && c <= '9') {
            if (out->count >= CALIB_MAX_NUMBER_SEGMENTS) return false;
            size_t end = i + 1;
            while (end < out->length) {
                unsigned char next = (unsigned char)name[end];
                if (next < '0' || next > '9') break;
                end++;
            }
            out->starts[out->count] = i;
            out->ends[out->count] = end;
            out->count++;
            i = end;
            continue;
        }

        if (calib_ascii_alpha(c)) {
            out->stable_anchors++;
        } else if (c >= 0x80 && (c & 0xc0) != 0x80) {
            /* 每个 UTF-8 首字节视为一个稳定字符，续字节不重复计数。 */
            out->stable_anchors++;
        }
        i++;
    }
    out->valid = true;
    return true;
}

static bool calib_numeric_shapes_equal(const char* left, const CalibNumericShape* ls,
                                       const char* right, const CalibNumericShape* rs) {
    if (!left || !right || !ls || !rs || !ls->valid || !rs->valid ||
        ls->count != rs->count) {
        return false;
    }

    size_t left_pos = 0;
    size_t right_pos = 0;
    for (size_t i = 0; i < ls->count; i++) {
        size_t left_len = ls->starts[i] - left_pos;
        size_t right_len = rs->starts[i] - right_pos;
        if (left_len != right_len || memcmp(left + left_pos, right + right_pos, left_len) != 0) {
            return false;
        }
        left_pos = ls->ends[i];
        right_pos = rs->ends[i];
    }

    size_t left_tail = ls->length - left_pos;
    size_t right_tail = rs->length - right_pos;
    return left_tail == right_tail &&
        memcmp(left + left_pos, right + right_pos, left_tail) == 0;
}

static bool calib_numeric_segment_differs(const char* left, const CalibNumericShape* ls,
                                          const char* right, const CalibNumericShape* rs,
                                          size_t segment) {
    size_t left_len = ls->ends[segment] - ls->starts[segment];
    size_t right_len = rs->ends[segment] - rs->starts[segment];
    return left_len != right_len ||
        memcmp(left + ls->starts[segment], right + rs->starts[segment], left_len) != 0;
}

static bool calib_numeric_segment_direct(const char* name, const CalibNumericShape* shape,
                                         size_t segment) {
    size_t start = shape->starts[segment];
    size_t end = shape->ends[segment];
    if (start == 0 || !calib_direct_number_delimiter((unsigned char)name[start - 1])) {
        return false;
    }
    return end == shape->length ||
        calib_direct_number_delimiter((unsigned char)name[end]);
}

static bool calib_append_pattern_bytes(char* out, size_t out_sz, size_t* used,
                                       const char* text, size_t length) {
    if (!out || !used || !text || *used + length + 1 > out_sz) return false;
    memcpy(out + *used, text, length);
    *used += length;
    out[*used] = '\0';
    return true;
}

static bool calib_build_own_rule_base(const CalibData* data,
                                      const CalibNumericShape* shapes,
                                      size_t idx, char* out, size_t out_sz) {
    if (!data || !shapes || idx >= data->count || !out || out_sz == 0 ||
        !shapes[idx].valid) {
        return false;
    }

    const ThreadSample* sample = &data->threads[idx];
    const CalibNumericShape* shape = &shapes[idx];
    bool direct[CALIB_MAX_NUMBER_SEGMENTS] = {0};
    bool varied[CALIB_MAX_NUMBER_SEGMENTS] = {0};
    size_t dynamic_count = 0;
    size_t dynamic_segment = SIZE_MAX;

    for (size_t s = 0; s < shape->count; s++) {
        direct[s] = calib_numeric_segment_direct(sample->name, shape, s);
    }

    for (size_t other = 0; other < data->count; other++) {
        if (other == idx || data->threads[other].is_process || !shapes[other].valid ||
            strcmp(data->threads[other].owner, sample->owner) != 0 ||
            !calib_numeric_shapes_equal(sample->name, shape,
                                        data->threads[other].name, &shapes[other])) {
            continue;
        }
        for (size_t s = 0; s < shape->count; s++) {
            if (calib_numeric_segment_differs(sample->name, shape,
                                              data->threads[other].name, &shapes[other], s)) {
                varied[s] = true;
            }
        }
    }

    if (shape->stable_anchors >= 2) {
        for (size_t s = 0; s < shape->count; s++) {
            if (direct[s] || varied[s]) {
                dynamic_count++;
                dynamic_segment = s;
            }
        }
    }

    if (dynamic_count == 0) {
        build_str(out, out_sz, sample->name, NULL);
        return out[0] != '\0';
    }

    bool simple_trailing_star = dynamic_count == 1 && direct[dynamic_segment] &&
        shape->ends[dynamic_segment] == shape->length;
    static const char numeric_glob[] = "[0-9]*";
    char candidate[MAX_THREAD_LEN] = {0};
    size_t used = 0;
    size_t source_pos = 0;

    for (size_t s = 0; s < shape->count; s++) {
        bool dynamic = direct[s] || varied[s];
        size_t literal_end = shape->starts[s];
        /* 只有裸 '*' 能吸收被省略的空白；数字字符类前必须保留原分隔符。 */
        if (dynamic && direct[s] && simple_trailing_star) {
            while (literal_end > source_pos &&
                   (sample->name[literal_end - 1] == ' ' ||
                    sample->name[literal_end - 1] == '\t')) {
                literal_end--;
            }
        }
        if (!calib_append_pattern_bytes(candidate, sizeof(candidate), &used,
                                        sample->name + source_pos,
                                        literal_end - source_pos)) {
            goto keep_exact;
        }
        if (dynamic) {
            const char* glob = simple_trailing_star ? "*" : numeric_glob;
            if (!calib_append_pattern_bytes(candidate, sizeof(candidate), &used,
                                            glob, strlen(glob))) {
                goto keep_exact;
            }
        } else if (!calib_append_pattern_bytes(candidate, sizeof(candidate), &used,
                                               sample->name + shape->starts[s],
                                               shape->ends[s] - shape->starts[s])) {
            goto keep_exact;
        }
        source_pos = shape->ends[s];
    }

    if (!calib_append_pattern_bytes(candidate, sizeof(candidate), &used,
                                    sample->name + source_pos,
                                    shape->length - source_pos) ||
        !calib_wildcard_name_syntax_ok(candidate)) {
        goto keep_exact;
    }
    build_str(out, out_sz, candidate, NULL);
    return true;

keep_exact:
    build_str(out, out_sz, sample->name, NULL);
    return out[0] != '\0';
}

static size_t calib_utf8_char_count(const char* text) {
    if (!text) return 0;
    size_t count = 0;
    for (size_t i = 0; text[i]; i++) {
        unsigned char c = (unsigned char)text[i];
        if ((c & 0xc0) != 0x80) count++;
    }
    return count;
}

/* specificity 表示模式必须匹配的原子数，越少表示覆盖越宽。 */
static size_t calib_pattern_specificity(const char* pattern) {
    if (!pattern) return SIZE_MAX;
    size_t specificity = 0;
    for (size_t i = 0; pattern[i];) {
        unsigned char c = (unsigned char)pattern[i];
        if (c == '*') {
            i++;
            continue;
        }
        if (c == '[') {
            const char* close = strchr(pattern + i + 1, ']');
            if (close) {
                specificity++;
                i = (size_t)(close - pattern) + 1;
                continue;
            }
        }
        specificity++;
        i++;
        while (pattern[i] && ((unsigned char)pattern[i] & 0xc0) == 0x80) i++;
    }
    return specificity;
}

static bool calib_wider_pattern(const CalibRuleBaseInfo* candidate,
                                const CalibRuleBaseInfo* current) {
    if (candidate->observed_coverage != current->observed_coverage) {
        return candidate->observed_coverage > current->observed_coverage;
    }
    if (candidate->specificity != current->specificity) {
        return candidate->specificity < current->specificity;
    }
    if (candidate->pattern_chars != current->pattern_chars) {
        return candidate->pattern_chars < current->pattern_chars;
    }
    return strcmp(candidate->own_base, current->own_base) < 0;
}

/* own base 只计算一次；canonical 再从已生成通配模式中选择观察覆盖最宽者。 */
static CalibRuleBaseInfo* calib_prepare_rule_bases(const CalibData* data) {
    if (!data || data->count == 0) return NULL;
    CalibNumericShape* shapes = calloc(data->count, sizeof(*shapes));
    CalibRuleBaseInfo* infos = calloc(data->count, sizeof(*infos));
    if (!shapes || !infos) {
        free(shapes);
        free(infos);
        return NULL;
    }

    for (size_t i = 0; i < data->count; i++) {
        if (data->threads[i].is_process ||
            !calib_parse_numeric_shape(data->threads[i].name, &shapes[i])) {
            continue;
        }
        infos[i].valid = calib_build_own_rule_base(data, shapes, i,
                                                  infos[i].own_base,
                                                  sizeof(infos[i].own_base));
    }

    for (size_t i = 0; i < data->count; i++) {
        if (!infos[i].valid || !strchr(infos[i].own_base, '*')) continue;
        for (size_t other = 0; other < data->count; other++) {
            if (!infos[other].valid ||
                strcmp(data->threads[i].owner, data->threads[other].owner) != 0) {
                continue;
            }
            if (fnmatch(infos[i].own_base, data->threads[other].name, FNM_NOESCAPE) == 0) {
                infos[i].observed_coverage++;
            }
        }
        infos[i].specificity = calib_pattern_specificity(infos[i].own_base);
        infos[i].pattern_chars = calib_utf8_char_count(infos[i].own_base);
    }

    for (size_t i = 0; i < data->count; i++) {
        if (!infos[i].valid) continue;
        const CalibRuleBaseInfo* widest = NULL;
        for (size_t candidate = 0; candidate < data->count; candidate++) {
            if (!infos[candidate].valid || !strchr(infos[candidate].own_base, '*') ||
                strcmp(data->threads[i].owner, data->threads[candidate].owner) != 0 ||
                fnmatch(infos[candidate].own_base, data->threads[i].name,
                        FNM_NOESCAPE) != 0) {
                continue;
            }
            if (!widest || calib_wider_pattern(&infos[candidate], widest)) {
                widest = &infos[candidate];
            }
        }
        build_str(infos[i].canonical, sizeof(infos[i].canonical),
                  widest ? widest->own_base : infos[i].own_base, NULL);
    }

    free(shapes);
    return infos;
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
    CALIB_RULE_OUTPUT_LEGACY = 0,
    CALIB_RULE_OUTPUT_AUTHOR_BLOCK = 1,
    CALIB_RULE_OUTPUT_COMPACT_EXTENDED_BLOCK = 2,
    CALIB_RULE_OUTPUT_TAGGED_BLOCK = 3,
    CALIB_RULE_OUTPUT_NATURAL_BLOCK = 4,
    CALIB_RULE_OUTPUT_NESTED_BLOCK = 5,
    CALIB_RULE_OUTPUT_FUNCTION_BLOCK = 6,
    CALIB_RULE_OUTPUT_YAML = 7
} CalibRuleOutputFormat;

static const char* calib_rule_output_format_wire(CalibRuleOutputFormat format) {
    switch (format) {
        case CALIB_RULE_OUTPUT_AUTHOR_BLOCK:
            return "author_block";
        case CALIB_RULE_OUTPUT_COMPACT_EXTENDED_BLOCK:
            return "compact_extended_block";
        case CALIB_RULE_OUTPUT_TAGGED_BLOCK:
            return "tagged_block";
        case CALIB_RULE_OUTPUT_NATURAL_BLOCK:
            return "natural_block";
        case CALIB_RULE_OUTPUT_NESTED_BLOCK:
            return "nested_block";
        case CALIB_RULE_OUTPUT_FUNCTION_BLOCK:
            return "function_block";
        case CALIB_RULE_OUTPUT_YAML:
            return "yaml";
        case CALIB_RULE_OUTPUT_LEGACY:
        default:
            return "legacy";
    }
}

static CalibRuleOutputFormat calib_rule_output_format_from_wire(const char* value) {
    if (!value) return CALIB_RULE_OUTPUT_LEGACY;
    if (strcmp(value, "author_block") == 0) return CALIB_RULE_OUTPUT_AUTHOR_BLOCK;
    if (strcmp(value, "compact_header_block") == 0 ||
        strcmp(value, "separate_fallback_block") == 0 ||
        strcmp(value, "compact_separate_fallback_block") == 0 ||
        strcmp(value, "extended_block") == 0) {
        return CALIB_RULE_OUTPUT_AUTHOR_BLOCK;
    }
    if (strcmp(value, "compact_extended_block") == 0) {
        return CALIB_RULE_OUTPUT_COMPACT_EXTENDED_BLOCK;
    }
    if (strcmp(value, "tagged_block") == 0) return CALIB_RULE_OUTPUT_TAGGED_BLOCK;
    if (strcmp(value, "natural_block") == 0) return CALIB_RULE_OUTPUT_NATURAL_BLOCK;
    if (strcmp(value, "nested_block") == 0) return CALIB_RULE_OUTPUT_NESTED_BLOCK;
    if (strcmp(value, "function_block") == 0) return CALIB_RULE_OUTPUT_FUNCTION_BLOCK;
    if (strcmp(value, "yaml") == 0) return CALIB_RULE_OUTPUT_YAML;
    return CALIB_RULE_OUTPUT_LEGACY;
}

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
    CalibRuleOutputFormat rule_output_format;
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
    p.rule_output_format = CALIB_RULE_OUTPUT_LEGACY;
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
    printf("[校准策略] rule_output_format: %s; 只改变规则写回格式, 不改变绑核效果\n",
           calib_rule_output_format_wire(p->rule_output_format));
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

static bool calib_config_lock_acquire(void) {
    const int timeout_ms = 5000;
    const int step_us = 50000;
    int waited_us = 0;
    while (!shutdown_requested && waited_us <= timeout_ms * 1000) {
        if (mkdir(CALIB_CONFIG_LOCK, 0777) == 0) return true;
        if (errno != EEXIST) {
            printf("[校准] 获取规则配置锁失败: %s err=%s\n",
                   CALIB_CONFIG_LOCK, strerror(errno));
            return false;
        }

        struct stat st;
        time_t now = time(NULL);
        if (stat(CALIB_CONFIG_LOCK, &st) == 0 && now > st.st_mtime + 30) {
            char owner_path[512];
            build_str(owner_path, sizeof(owner_path), CALIB_CONFIG_LOCK, "/owner", NULL);
            unlink(owner_path);
            if (rmdir(CALIB_CONFIG_LOCK) == 0) {
                printf("[校准] 已清理过期规则配置锁: %s\n", CALIB_CONFIG_LOCK);
                continue;
            }
        }
        usleep(step_us);
        waited_us += step_us;
    }
    printf("[校准] 等待规则配置锁超时: %s\n", CALIB_CONFIG_LOCK);
    return false;
}

static void calib_config_lock_release(void) {
    if (rmdir(CALIB_CONFIG_LOCK) != 0 && errno != ENOENT) {
        printf("[校准] 释放规则配置锁失败: %s err=%s\n",
               CALIB_CONFIG_LOCK, strerror(errno));
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
        } else if (strcmp(key, "rule_output_format") == 0) {
            p.rule_output_format = calib_rule_output_format_from_wire(val);
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
    int tier;                    /* 0=不输出, 1=中档, 2=高档 */
    bool overlaps_best;          /* 已由最佳通配规则覆盖, 避免运行时 CPU_OR */
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

static bool calib_group_precedes(const CalibGroup* left, const CalibGroup* right) {
    if (left->score != right->score) return left->score > right->score;
    if (left->avg_pct != right->avg_pct) return left->avg_pct > right->avg_pct;
    if (left->max_pct != right->max_pct) return left->max_pct > right->max_pct;
    return strcmp(left->base, right->base) < 0;
}

static bool calib_patterns_overlap_observed(const CalibData* data,
                                            const CalibRuleBaseInfo* infos,
                                            const char* owner,
                                            const char* left,
                                            const char* right) {
    if (!data || !infos || !owner || !left || !right) return false;
    for (size_t i = 0; i < data->count; i++) {
        if (!infos[i].valid || strcmp(data->threads[i].owner, owner) != 0) continue;
        if (fnmatch(left, data->threads[i].name, FNM_NOESCAPE) == 0 &&
            fnmatch(right, data->threads[i].name, FNM_NOESCAPE) == 0) {
            return true;
        }
    }
    return false;
}

/* 两个 canonical 模式观察上交叉且双方各有独占成员时，最佳规则会产生歧义。 */
static bool calib_best_pattern_ambiguous(const CalibData* data,
                                         const CalibRuleBaseInfo* infos,
                                         const CalibGroup* groups, size_t group_count,
                                         const char* owner, const char* best_pattern) {
    if (!data || !infos || !groups || !owner || !best_pattern) return true;
    for (size_t g = 0; g < group_count; g++) {
        if (strcmp(groups[g].owner, owner) != 0 ||
            strcmp(groups[g].base, best_pattern) == 0) {
            continue;
        }
        bool intersects = false;
        bool best_only = false;
        bool other_only = false;
        for (size_t i = 0; i < data->count; i++) {
            if (!infos[i].valid || strcmp(data->threads[i].owner, owner) != 0) continue;
            bool matches_best = fnmatch(best_pattern, data->threads[i].name,
                                        FNM_NOESCAPE) == 0;
            bool matches_other = fnmatch(groups[g].base, data->threads[i].name,
                                         FNM_NOESCAPE) == 0;
            intersects |= matches_best && matches_other;
            best_only |= matches_best && !matches_other;
            other_only |= matches_other && !matches_best;
        }
        if (intersects && best_only && other_only) return true;
    }
    return false;
}

static size_t calib_group_root(size_t* parent, size_t node) {
    size_t root = node;
    while (parent[root] != root) root = parent[root];
    while (parent[node] != node) {
        size_t next = parent[node];
        parent[node] = root;
        node = next;
    }
    return root;
}

static void calib_group_union(size_t* parent, size_t left, size_t right) {
    size_t left_root = calib_group_root(parent, left);
    size_t right_root = calib_group_root(parent, right);
    if (left_root != right_root) parent[right_root] = left_root;
}

/* 普通组只按本次观察到的原始名称判定重叠。同一连通分量统一提升到最高档，
 * 使所有可能同时命中的生成规则拥有相同 CPU 掩码。 */
static void calib_resolve_group_tiers(const CalibData* data,
                                      const CalibRuleBaseInfo* infos,
                                      CalibGroup* groups, size_t group_count,
                                      const char* best_owner, const char* best_pattern,
                                      bool best_active) {
    if (!data || !infos || !groups || group_count == 0) return;

    size_t* parent = malloc(group_count * sizeof(*parent));
    int* component_tier = calloc(group_count, sizeof(*component_tier));
    if (!parent || !component_tier) {
        /* 无法完成冲突消解时不冒险输出可能被 CPU_OR 扩大的普通规则。 */
        for (size_t g = 0; g < group_count; g++) groups[g].tier = 0;
        free(parent);
        free(component_tier);
    } else {
        for (size_t g = 0; g < group_count; g++) parent[g] = g;

        /* 每个观察名称连接所有能命中它的 canonical 组，保持 O(n²×名称长度)。 */
        for (size_t i = 0; i < data->count; i++) {
            if (!infos[i].valid) continue;
            size_t first = SIZE_MAX;
            for (size_t g = 0; g < group_count; g++) {
                if (strcmp(groups[g].owner, data->threads[i].owner) != 0 ||
                    fnmatch(groups[g].base, data->threads[i].name, FNM_NOESCAPE) != 0) {
                    continue;
                }
                if (first == SIZE_MAX) {
                    first = g;
                } else {
                    calib_group_union(parent, first, g);
                }
            }
        }

        for (size_t g = 0; g < group_count; g++) {
            size_t root = calib_group_root(parent, g);
            if (groups[g].tier > component_tier[root]) component_tier[root] = groups[g].tier;
        }
        for (size_t g = 0; g < group_count; g++) {
            groups[g].tier = component_tier[calib_group_root(parent, g)];
        }

        free(parent);
        free(component_tier);
    }

    /* 普通组已先完成统一升档；Best 生效后再屏蔽所有观察上重叠的组。 */
    if (best_active) {
        for (size_t g = 0; g < group_count; g++) {
            if (strcmp(groups[g].owner, best_owner) == 0 &&
                calib_patterns_overlap_observed(data, infos, best_owner,
                                                best_pattern, groups[g].base)) {
                groups[g].overlaps_best = true;
            }
        }
    }
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

/* 按本次采样规模预留规则空间，避免规则较多时静默丢失后续条目。 */
static bool calib_rules_buffer_capacity(const CalibData* data, size_t* capacity_out) {
    if (!data || !capacity_out) return false;

    const size_t max_rule_line = MAX_PKG_LEN + MAX_THREAD_LEN + 128;
    if (data->count > (SIZE_MAX - 4) / 2) return false;
    size_t max_rule_lines = data->count * 2 + 4;
    if (max_rule_lines > (SIZE_MAX - 1) / max_rule_line) return false;
    size_t legacy_bytes = max_rule_lines * max_rule_line;
    if (legacy_bytes > (SIZE_MAX - 1) / 3) return false;

    size_t capacity = legacy_bytes * 3 + 1;
    if (capacity < CALIB_RULES_MIN_BUFFER_SIZE) {
        capacity = CALIB_RULES_MIN_BUFFER_SIZE;
    }
    *capacity_out = capacity;
    return true;
}

typedef struct {
    char* owner;
    char* thread;
    char* cpus;
} CalibGeneratedRule;

static bool calib_generated_child_rule(const CalibGeneratedRule* rule,
                                       const char* pkg, size_t pkg_len) {
    if (!rule || !pkg || (rule->thread && rule->thread[0])) return false;
    size_t owner_len = strlen(rule->owner);
    return owner_len > pkg_len + 1 &&
        strncmp(rule->owner, pkg, pkg_len) == 0 && rule->owner[pkg_len] == ':';
}

static bool calib_format_append(char** out, size_t* remain, const char* format, ...) {
    if (!out || !*out || !remain || !format) return false;
    va_list args;
    va_start(args, format);
    int need = vsnprintf(*out, *remain + 1, format, args);
    va_end(args);
    if (need < 0 || (size_t)need > *remain) return false;
    *out += need;
    *remain -= (size_t)need;
    return true;
}

static bool calib_format_generated_rules(const char* pkg, const char* legacy,
                                         CalibRuleOutputFormat output_format,
                                         char* out, size_t out_sz) {
    if (!pkg || !legacy || !out || out_sz == 0) return false;
    if (output_format == CALIB_RULE_OUTPUT_LEGACY) {
        size_t length = strlen(legacy);
        if (length >= out_sz) return false;
        memcpy(out, legacy, length + 1);
        return true;
    }

    char* copy = strdup(legacy);
    if (!copy) return false;
    CalibGeneratedRule* rules = NULL;
    size_t count = 0;
    char* save = NULL;
    for (char* line = strtok_r(copy, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        char* eq = strchr(line, '=');
        if (!eq) continue;
        *eq++ = '\0';
        char* key = strtrim(line);
        char* cpus = strtrim(eq);
        if (!key[0] || !cpus[0]) continue;

        char* thread = NULL;
        char* open = strchr(key, '{');
        if (open) {
            *open++ = '\0';
            char* close = strrchr(open, '}');
            if (!close || strtrim(close + 1)[0]) continue;
            *close = '\0';
            thread = strtrim(open);
        }

        CalibGeneratedRule* resized = realloc(rules, (count + 1) * sizeof(*rules));
        if (!resized) {
            free(rules);
            free(copy);
            return false;
        }
        rules = resized;
        rules[count++] = (CalibGeneratedRule) {
            .owner = strtrim(key),
            .thread = thread,
            .cpus = cpus
        };
    }

    const char* fallback = NULL;
    size_t thread_count = 0;
    size_t child_count = 0;
    size_t pkg_len = strlen(pkg);
    for (size_t i = 0; i < count; i++) {
        if (strcmp(rules[i].owner, pkg) == 0) {
            if (rules[i].thread && rules[i].thread[0]) thread_count++;
            else fallback = rules[i].cpus;
        } else if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
            child_count++;
        }
    }

    char* cursor = out;
    size_t remain = out_sz - 1;
    out[0] = '\0';
    bool ok = true;
    /* 只有主进程兜底时保持单行，避免生成没有实际成员的空区块。 */
    if (thread_count == 0 && child_count == 0) {
        if (fallback) {
            ok = calib_format_append(&cursor, &remain, "%s=%s\n", pkg, fallback);
        }
    } else if (output_format == CALIB_RULE_OUTPUT_AUTHOR_BLOCK) {
        if (thread_count > 0) {
            ok = fallback ?
                calib_format_append(&cursor, &remain, "%s=%s {\n", pkg, fallback) :
                calib_format_append(&cursor, &remain, "%s {\n", pkg);
            for (size_t i = 0; ok && i < count; i++) {
                if (strcmp(rules[i].owner, pkg) == 0 && rules[i].thread && rules[i].thread[0]) {
                    ok = calib_format_append(&cursor, &remain, "    %s=%s\n",
                                             rules[i].thread, rules[i].cpus);
                }
            }
            if (ok) ok = calib_format_append(&cursor, &remain, "}\n");
        } else if (fallback) {
            ok = calib_format_append(&cursor, &remain, "%s=%s\n", pkg, fallback);
        }
        for (size_t i = 0; ok && i < count; i++) {
            if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
                ok = calib_format_append(&cursor, &remain, "%s=%s\n",
                                         rules[i].owner, rules[i].cpus);
            }
        }
    } else if (output_format == CALIB_RULE_OUTPUT_COMPACT_EXTENDED_BLOCK) {
        if (thread_count + child_count > 0) {
            ok = calib_format_append(&cursor, &remain, "%s{\n", pkg);
            for (size_t i = 0; ok && i < count; i++) {
                if (strcmp(rules[i].owner, pkg) == 0 && rules[i].thread && rules[i].thread[0]) {
                    ok = calib_format_append(&cursor, &remain, "    %s=%s\n",
                                             rules[i].thread, rules[i].cpus);
                }
            }
            for (size_t i = 0; ok && i < count; i++) {
                if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
                    ok = calib_format_append(&cursor, &remain, "    %s=%s\n",
                                             rules[i].owner + pkg_len, rules[i].cpus);
                }
            }
            if (ok) {
                ok = fallback ?
                    calib_format_append(&cursor, &remain, "}=%s\n", fallback) :
                    calib_format_append(&cursor, &remain, "}\n");
            }
        } else if (fallback) {
            ok = calib_format_append(&cursor, &remain, "%s=%s\n", pkg, fallback);
        }
    } else if (output_format == CALIB_RULE_OUTPUT_TAGGED_BLOCK) {
        ok = calib_format_append(&cursor, &remain, "%s={\n", pkg);
        for (size_t i = 0; ok && i < count; i++) {
            if (strcmp(rules[i].owner, pkg) == 0 && rules[i].thread && rules[i].thread[0]) {
                ok = calib_format_append(&cursor, &remain, "    thread:%s=%s\n",
                                         rules[i].thread, rules[i].cpus);
            }
        }
        for (size_t i = 0; ok && i < count; i++) {
            if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
                ok = calib_format_append(&cursor, &remain, "    process:%s=%s\n",
                                         rules[i].owner + pkg_len + 1, rules[i].cpus);
            }
        }
        if (ok && fallback) ok = calib_format_append(&cursor, &remain, "    fallback=%s\n", fallback);
        if (ok) ok = calib_format_append(&cursor, &remain, "}\n");
    } else if (output_format == CALIB_RULE_OUTPUT_NATURAL_BLOCK) {
        ok = fallback ?
            calib_format_append(&cursor, &remain, "app %s fallback %s {\n", pkg, fallback) :
            calib_format_append(&cursor, &remain, "app %s {\n", pkg);
        for (size_t i = 0; ok && i < count; i++) {
            if (strcmp(rules[i].owner, pkg) == 0 && rules[i].thread && rules[i].thread[0]) {
                ok = calib_format_append(&cursor, &remain, "    thread %s=%s\n",
                                         rules[i].thread, rules[i].cpus);
            }
        }
        for (size_t i = 0; ok && i < count; i++) {
            if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
                ok = calib_format_append(&cursor, &remain, "    process %s=%s\n",
                                         rules[i].owner + pkg_len + 1, rules[i].cpus);
            }
        }
        if (ok) ok = calib_format_append(&cursor, &remain, "}\n");
    } else if (output_format == CALIB_RULE_OUTPUT_NESTED_BLOCK) {
        ok = calib_format_append(&cursor, &remain, "%s={\n", pkg);
        if (ok && thread_count > 0) {
            ok = calib_format_append(&cursor, &remain, "    threads {\n");
            for (size_t i = 0; ok && i < count; i++) {
                if (strcmp(rules[i].owner, pkg) == 0 && rules[i].thread && rules[i].thread[0]) {
                    ok = calib_format_append(&cursor, &remain, "        %s=%s\n",
                                             rules[i].thread, rules[i].cpus);
                }
            }
            if (ok) ok = calib_format_append(&cursor, &remain, "    }\n");
        }
        if (ok && child_count > 0) {
            ok = calib_format_append(&cursor, &remain, "    processes {\n");
            for (size_t i = 0; ok && i < count; i++) {
                if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
                    ok = calib_format_append(&cursor, &remain, "        %s=%s\n",
                                             rules[i].owner + pkg_len + 1, rules[i].cpus);
                }
            }
            if (ok) ok = calib_format_append(&cursor, &remain, "    }\n");
        }
        if (ok && fallback) ok = calib_format_append(&cursor, &remain, "    fallback=%s\n", fallback);
        if (ok) ok = calib_format_append(&cursor, &remain, "}\n");
    } else if (output_format == CALIB_RULE_OUTPUT_FUNCTION_BLOCK) {
        ok = fallback ?
            calib_format_append(&cursor, &remain, "app(%s, %s) {\n", pkg, fallback) :
            calib_format_append(&cursor, &remain, "app(%s) {\n", pkg);
        for (size_t i = 0; ok && i < count; i++) {
            if (strcmp(rules[i].owner, pkg) == 0 && rules[i].thread && rules[i].thread[0]) {
                ok = calib_format_append(&cursor, &remain, "    thread(%s, %s)\n",
                                         rules[i].thread, rules[i].cpus);
            }
        }
        for (size_t i = 0; ok && i < count; i++) {
            if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
                ok = calib_format_append(&cursor, &remain, "    process(%s, %s)\n",
                                         rules[i].owner + pkg_len + 1, rules[i].cpus);
            }
        }
        if (ok) ok = calib_format_append(&cursor, &remain, "}\n");
    } else if (output_format == CALIB_RULE_OUTPUT_YAML) {
        ok = calib_format_append(&cursor, &remain, "%s:\n", pkg);
        if (ok && thread_count > 0) {
            ok = calib_format_append(&cursor, &remain, "    threads:\n");
            for (size_t i = 0; ok && i < count; i++) {
                if (strcmp(rules[i].owner, pkg) == 0 && rules[i].thread && rules[i].thread[0]) {
                    ok = calib_format_append(&cursor, &remain, "        %s: %s\n",
                                             rules[i].thread, rules[i].cpus);
                }
            }
        }
        if (ok && child_count > 0) {
            ok = calib_format_append(&cursor, &remain, "    processes:\n");
            for (size_t i = 0; ok && i < count; i++) {
                if (calib_generated_child_rule(&rules[i], pkg, pkg_len)) {
                    ok = calib_format_append(&cursor, &remain, "        %s: %s\n",
                                             rules[i].owner + pkg_len + 1, rules[i].cpus);
                }
            }
        }
        if (ok && fallback) ok = calib_format_append(&cursor, &remain, "    fallback: %s\n", fallback);
    }

    /* 理论上校准只生成当前包名、线程和子进程规则；仍保留未知规则以避免未来扩展丢行。 */
    for (size_t i = 0; ok && i < count; i++) {
        bool main_rule = strcmp(rules[i].owner, pkg) == 0;
        bool child_rule = calib_generated_child_rule(&rules[i], pkg, pkg_len);
        if (!main_rule && !child_rule) {
            ok = rules[i].thread && rules[i].thread[0] ?
                calib_format_append(&cursor, &remain, "%s{%s}=%s\n",
                                    rules[i].owner, rules[i].thread, rules[i].cpus) :
                calib_format_append(&cursor, &remain, "%s=%s\n",
                                    rules[i].owner, rules[i].cpus);
        }
    }
    if (ok) *cursor = '\0';
    free(rules);
    free(copy);
    return ok;
}

/* 根据采样结果生成规则文本, 追加写入 out_buf (调用方保证足够大)。
 * 返回生成的规则行数。 */
static int calib_generate_rules(CalibData* data, const CpuTopology* topo,
                                char* out_buf, size_t out_sz) {
    if (data->count == 0 || out_sz == 0) return 0;
    CalibPolicy policy = calib_load_policy(topo);

    /* 1) 按 busy 降序排序 */
    qsort(data->threads, data->count, sizeof(ThreadSample), calib_cmp_busy);

    CalibRuleBaseInfo* rule_bases = calib_prepare_rule_bases(data);
    if (!rule_bases) return 0;

    /* 2) 主进程线程聚合为精确/通配组; 子进程整体样本稍后单独分级。 */
    CalibGroup* groups = calloc(data->count, sizeof(CalibGroup));
    if (!groups) {
        free(rule_bases);
        return 0;
    }
    size_t ng = 0;
    unsigned long long total = 0;
    for (size_t i = 0; i < data->count; i++) {
        total += data->threads[i].busy;
        if (data->threads[i].is_process ||
            strcmp(data->threads[i].owner, data->pkg) != 0 ||
            !rule_bases[i].valid || !rule_bases[i].canonical[0]) continue;
        const char* base = rule_bases[i].canonical;
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
    if (total == 0) {
        free(groups);
        free(rule_bases);
        return 0;
    }

    for (size_t g = 0; g < ng; g++) {
        if (groups[g].avg_pct > 100.0) groups[g].avg_pct = 100.0;
        groups[g].score = calib_load_score(groups[g].avg_pct, groups[g].max_pct);
        groups[g].tier = calib_group_tier(&groups[g], &policy);
    }

    /* 组按 avg/max 综合评分降序, 峰值线程不会被累计 busy 掩盖。 */
    for (size_t a = 0; a + 1 < ng; a++)
        for (size_t b = 0; b + 1 < ng - a; b++)
            if (calib_group_precedes(&groups[b + 1], &groups[b])) {
                CalibGroup t = groups[b]; groups[b] = groups[b + 1]; groups[b + 1] = t;
            }

    /* 3) 按负载分级, 同时参考 avg 与 max:
     *    - 单个线程综合负载第一, 且 avg/max 都达到策略重载阈值: 使用最高性能簇, 并固定输出在第一行。
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
        if (!rule_bases[i].valid) continue;
        double avg_pct, max_pct;
        calib_thread_pct_stats(&data->threads[i], &avg_pct, &max_pct);
        double score = calib_load_score(avg_pct, max_pct);
        bool better = best_idx == SIZE_MAX || score > best_score ||
            (score == best_score && (avg_pct > best_avg ||
             (avg_pct == best_avg && (max_pct > best_max ||
              (max_pct == best_max && strcmp(data->threads[i].name, big_thread) < 0)))));
        if (better) {
            best_score = score;
            best_avg = avg_pct;
            best_max = max_pct;
            best_idx = i;
            build_str(big_owner, sizeof(big_owner), data->threads[i].owner, NULL);
            build_str(big_thread, sizeof(big_thread), data->threads[i].name, NULL);
        }
    }
    if (best_idx != SIZE_MAX) {
        /* 最高负载线程即使被同族动态通配 canonical 吸收，也应让整个无歧义组
         * 保留 best 档，避免 RenderThread 与 RenderThread 2 合并后降为 high 档。 */
        bool ambiguous = calib_best_pattern_ambiguous(
            data, rule_bases, groups, ng, big_owner, rule_bases[best_idx].canonical);
        if (!ambiguous) {
            build_str(big_rule, sizeof(big_rule), rule_bases[best_idx].canonical, NULL);
        }
    }

    char* p = out_buf;
    size_t remain = out_sz - 1;
    int lines = 0;
    int thread_lines = 0;
    const int max_thread_lines = policy.max_thread_rules;
    const bool has_big_thread = big_thread[0] && big_rule[0] &&
        (best_avg >= policy.best_avg && best_max >= policy.best_max);
    const char* big_tier = calib_policy_core_range(topo, policy.best_tier, policy.best_cores);
    const char* base_tier = calib_policy_core_range(topo, policy.fallback_tier, policy.fallback_cores);
    const bool best_active = has_big_thread && big_tier && big_tier[0];
    calib_resolve_group_tiers(data, rule_bases, groups, ng,
                              big_owner, big_rule, best_active);
    size_t fallback_reserve = 0;
    if (base_tier && base_tier[0]) {
        fallback_reserve = strlen(data->pkg) + 1 + strlen(base_tier) + 1;
        if (fallback_reserve > remain) fallback_reserve = remain;
    }
    size_t explicit_remain = remain - fallback_reserve;

    if (best_active) {
        if (!calib_append_rule(&p, &explicit_remain, &lines, big_owner, big_rule, big_tier))
            goto append_fallback;
        thread_lines++;
    }

    for (int tier_pass = 2; tier_pass >= 1 && thread_lines < max_thread_lines; tier_pass--) {
        for (int wild_pass = 0; wild_pass <= 1 && thread_lines < max_thread_lines; wild_pass++) {
            for (size_t g = 0; g < ng && thread_lines < max_thread_lines; g++) {
                if ((groups[g].is_wild ? 1 : 0) != wild_pass) continue;
                if (groups[g].overlaps_best || groups[g].tier != tier_pass) continue;

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
    if (policy.rule_output_format != CALIB_RULE_OUTPUT_LEGACY) {
        char* legacy = strdup(out_buf);
        if (legacy) {
            if (!calib_format_generated_rules(data->pkg, legacy, policy.rule_output_format,
                                              out_buf, out_sz)) {
                printf("[校准] 警告: %s 规则格式转换空间不足，已保留单行格式\n", data->pkg);
                build_str(out_buf, out_sz, legacy, NULL);
            }
            free(legacy);
        } else {
            printf("[校准] 警告: %s 规则格式转换内存不足，已保留单行格式\n", data->pkg);
        }
    }
    free(groups);
    free(rule_bases);
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

static bool calib_write_history(const char* pkg, CalibData* data) {
    if (data->count == 0) return false;
    if (mkdir(HISTORY_DIR, 0755) != 0 && errno != EEXIST) return false;

    unsigned long long total = 0;
    for (size_t i = 0; i < data->count; i++) total += data->threads[i].busy;
    if (total == 0) return false;

    char path[512];
    char safe[MAX_PKG_LEN];
    safe_history_filename(pkg, safe, sizeof(safe));
    build_str(path, sizeof(path), HISTORY_DIR, "/", safe, ".log", NULL);

    /* 1) 把本次会话格式化到内存缓冲: 段头 + 每条负载记录(AVG MAX 名称|折线) */
    char* cur = NULL;
    size_t cur_len = 0;
    size_t written_rows = 0;
    FILE* mem = open_memstream(&cur, &cur_len);
    if (!mem) return false;
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
        written_rows++;
    }
    if (fclose(mem) != 0 || !cur || written_rows == 0) {
        free(cur);
        return false;
    }

    /* 2) 读入已有内容, 找到各段(以 '#' 开头的行)的起始偏移 */
    char* old = NULL;
    size_t old_len = 0;
    FILE* rf = fopen(path, "rb");
    if (!rf && errno != ENOENT) {
        free(cur);
        return false;
    }
    if (rf) {
        bool read_ok = fseek(rf, 0, SEEK_END) == 0;
        long end = read_ok ? ftell(rf) : -1;
        if (end < 0 || (uintmax_t)end > (uintmax_t)(SIZE_MAX - 1)) {
            read_ok = false;
        } else {
            old_len = (size_t)end;
        }
        if (read_ok && fseek(rf, 0, SEEK_SET) != 0) read_ok = false;
        if (old_len > 0) {
            old = malloc(old_len + 1);
            if (!old || fread(old, 1, old_len, rf) != old_len || ferror(rf)) {
                read_ok = false;
            } else {
                old[old_len] = '\0';
            }
        }
        if (fclose(rf) != 0) read_ok = false;
        if (!read_ok) {
            free(old);
            free(cur);
            return false;
        }
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
    if (!wf) {
        free(old);
        free(cur);
        return false;
    }
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
    if (write_ok && rename(tmp_path, path) != 0) write_ok = false;
    if (!write_ok) unlink(tmp_path);

    free(old);
    free(cur);
    return write_ok;
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
    size_t len = strlen(raw_line);
    while (len > 0 && (raw_line[len - 1] == '\n' || raw_line[len - 1] == '\r')) len--;
    char* line = malloc(len + 1);
    if (!line) return false;
    memcpy(line, raw_line, len);
    line[len] = '\0';
    if (len == 0) { free(line); return true; }

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

static bool calib_config_top_level_comment(const char* raw_line) {
    if (!raw_line || !raw_line[0] ||
        raw_line[0] == ' ' || raw_line[0] == '\t') return false;
    return raw_line[0] == '#' ||
        (raw_line[0] == '/' && raw_line[1] == '/');
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

static bool calib_rule_syntax_block_group(const char* raw_line, char* out, size_t out_sz, ConfigBlockKind* out_kind) {
    if (!raw_line || !out || out_sz == 0) return false;
    out[0] = '\0';
    char* copy = strdup(raw_line);
    if (!copy) return false;
    char* line = strtrim(copy);
    char* comment = strstr(line, "//");
    if (comment) {
        *comment = '\0';
        line = strtrim(line);
    }
    char* owner = NULL;
    char* fallback = NULL;
    ConfigBlockKind kind = CONFIG_BLOCK_STANDARD;
    int custom_header = config_custom_block_header(line, &owner, &fallback, &kind);
    bool header = custom_header > 0;
    if (custom_header < 0) {
        free(copy);
        return false;
    }
    if (custom_header == 0) {
        free(copy);
        copy = strdup(raw_line);
        if (!copy) return false;
        line = strtrim(copy);
        comment = strstr(line, "//");
        if (comment) {
            *comment = '\0';
            line = strtrim(line);
        }
        header = config_block_header(line, &owner, &fallback);
        kind = CONFIG_BLOCK_STANDARD;
    }
    if (header && owner && owner[0]) calib_config_group_name(owner, out, out_sz);
    if (header && out_kind) *out_kind = kind;
    free(copy);
    return header && out[0] != '\0';
}

static int calib_rule_syntax_brace_delta(const char* raw_line) {
    if (!raw_line) return 0;
    const char* code = raw_line;
    while (*code == ' ' || *code == '\t') code++;
    if (*code == '#') return 0;
    int delta = 0;
    for (const char* p = code; *p; p++) {
        if (p[0] == '/' && p[1] == '/') break;
        if (*p == '{') delta++;
        else if (*p == '}') delta--;
    }
    return delta;
}

static bool calib_parsed_block_valid(const ParsedConfigRule* rules, size_t count,
                                     const char* owner, const char* fallback, bool valid) {
    if (!valid || !owner || !owner[0] || strlen(owner) >= MAX_PKG_LEN) return false;
    for (size_t i = 0; i < count; i++) {
        if (!parsed_config_rule_valid(&rules[i])) return false;
    }
    if (fallback) {
        ParsedConfigRule rule = {
            .owner = (char*)owner,
            .thread = "",
            .cpus = (char*)fallback
        };
        if (!parsed_config_rule_valid(&rule)) return false;
    }
    return true;
}

static bool calib_validate_rule_syntax_blocks(FILE* in) {
    if (!in || fseek(in, 0, SEEK_SET) != 0) return false;
    char* line = NULL;
    size_t line_cap = 0;
    ParsedConfigRule* rules = NULL;
    size_t rule_count = 0;
    char* owner = NULL;
    char* fallback = NULL;
    ConfigBlockKind kind = CONFIG_BLOCK_STANDARD;
    int section = 0;
    bool block_valid = true;
    bool valid = true;

    while (valid && getline(&line, &line_cap, in) != -1) {
        size_t indent = 0;
        while (line[indent] == ' ' || line[indent] == '\t') indent++;
        char* copy = strdup(line);
        if (!copy) {
            valid = false;
            break;
        }
        char* code = strtrim(copy);
        char* comment = strstr(code, "//");
        if (comment) {
            *comment = '\0';
            code = strtrim(code);
        }

validate_config_line:
        if (owner) {
            if (!code[0] || code[0] == '#') {
                free(copy);
                continue;
            }
            bool finish = false;
            bool reprocess = false;
            char* tail_fallback = NULL;
            if (kind == CONFIG_BLOCK_YAML && indent == 0) {
                finish = true;
                reprocess = true;
            } else if (kind == CONFIG_BLOCK_STANDARD) {
                int close_result = config_block_close(code, &tail_fallback);
                if (close_result != 0) {
                    block_valid &= close_result > 0 && !(fallback && tail_fallback);
                    finish = true;
                }
            } else if (kind != CONFIG_BLOCK_YAML && code[0] == '}' && section == 0) {
                if (strcmp(code, "}") != 0) block_valid = false;
                finish = true;
            }
            if (finish) {
                const char* effective_fallback = fallback ? fallback : tail_fallback;
                valid = calib_parsed_block_valid(rules, rule_count, owner,
                                                  effective_fallback, block_valid);
                free_parsed_config_rules(rules, rule_count);
                rules = NULL;
                rule_count = 0;
                free(owner);
                free(fallback);
                owner = NULL;
                fallback = NULL;
                kind = CONFIG_BLOCK_STANDARD;
                section = 0;
                block_valid = true;
                if (valid && reprocess) goto validate_config_line;
                free(copy);
                continue;
            }
            int result = kind == CONFIG_BLOCK_STANDARD ?
                add_config_block_body_rule(&rules, &rule_count, owner, code) :
                config_custom_block_body_rule(&rules, &rule_count, owner, code,
                                              &kind, &section, &fallback, indent);
            if (result < 0) valid = false;
            else if (result > 0) block_valid = false;
            free(copy);
            continue;
        }

        if (!code[0] || code[0] == '#') {
            free(copy);
            continue;
        }
        char* custom = strdup(code);
        if (!custom) {
            free(copy);
            valid = false;
            break;
        }
        char* header_owner = NULL;
        char* header_fallback = NULL;
        ConfigBlockKind header_kind = CONFIG_BLOCK_STANDARD;
        int custom_header = config_custom_block_header(
            custom, &header_owner, &header_fallback, &header_kind);
        if (custom_header != 0) {
            owner = strdup(header_owner);
            fallback = header_fallback ? strdup(header_fallback) : NULL;
            kind = header_kind;
            block_valid = custom_header > 0 && owner && (!header_fallback || fallback);
            free(custom);
            free(copy);
            continue;
        }
        free(custom);
        if (config_block_header(code, &header_owner, &header_fallback)) {
            owner = strdup(header_owner);
            fallback = header_fallback ? strdup(header_fallback) : NULL;
            block_valid = owner && (!header_fallback || fallback);
        }
        free(copy);
    }
    if (valid && owner && kind == CONFIG_BLOCK_YAML) {
        valid = calib_parsed_block_valid(rules, rule_count, owner, fallback, block_valid);
    } else if (owner) {
        valid = false;
    }
    if (ferror(in)) valid = false;
    free_parsed_config_rules(rules, rule_count);
    free(owner);
    free(fallback);
    free(line);
    clearerr(in);
    if (fseek(in, 0, SEEK_SET) != 0) valid = false;
    return valid;
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
        /* YAML 区块会把下一个顶层规则前的注释纳入范围。替换目标规则时保留这些注释，
         * 避免校准一次就删除用户写在应用块之间的说明。 */
        for (size_t i = 0; ok && i < block->count; i++) {
            if (calib_config_top_level_comment(block->lines[i])) {
                ok = calib_write_line_normalized(out, block->lines[i]);
            }
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
    if (!calib_config_lock_acquire()) return false;
    FILE* in = fopen(config_file, "r");
    if (!in) {
        calib_config_lock_release();
        return false;
    }
    if (!calib_validate_rule_syntax_blocks(in)) {
        printf("[校准] 规则配置中存在未闭合或格式错误的区块，已取消写回\n");
        fclose(in);
        calib_config_lock_release();
        return false;
    }
    char tmp_path[4096 + 8];
    build_str(tmp_path, sizeof(tmp_path), config_file, ".tmp", NULL);
    FILE* out = fopen(tmp_path, "w");
    if (!out) {
        fclose(in);
        calib_config_lock_release();
        return false;
    }

    char target_group[MAX_PKG_LEN];
    calib_config_group_name(pkg, target_group, sizeof(target_group));

    char* line = NULL;
    size_t line_cap = 0;
    CalibConfigBlock block = {0};
    bool inserted = false;
    bool wrote_any = false;
    bool write_ok = true;
    bool in_rule_syntax_block = false;
    ConfigBlockKind rule_syntax_kind = CONFIG_BLOCK_STANDARD;
    int rule_syntax_depth = 0;
    while (write_ok && getline(&line, &line_cap, in) != -1) {
        if (calib_line_is_blank(line)) continue;

process_write_line:
        if (in_rule_syntax_block && rule_syntax_kind == CONFIG_BLOCK_YAML) {
            const char* p = line;
            while (*p == ' ' || *p == '\t') p++;
            if (p == line && *p && *p != '#' && !(p[0] == '/' && p[1] == '/')) {
                in_rule_syntax_block = false;
                goto process_write_line;
            }
        }
        char line_group[MAX_PKG_LEN];
        ConfigBlockKind header_kind = CONFIG_BLOCK_STANDARD;
        bool block_header = !in_rule_syntax_block &&
            calib_rule_syntax_block_group(line, line_group, sizeof(line_group), &header_kind);
        if (in_rule_syntax_block) {
            write_ok = calib_config_block_add(&block, line);
            if (rule_syntax_kind != CONFIG_BLOCK_YAML) {
                rule_syntax_depth += calib_rule_syntax_brace_delta(line);
                if (rule_syntax_depth == 0) in_rule_syntax_block = false;
                else if (rule_syntax_depth < 0) write_ok = false;
            }
            continue;
        }
        bool has_owner = block_header ||
            calib_config_line_group(line, line_group, sizeof(line_group));
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
        if (block_header) {
            in_rule_syntax_block = true;
            rule_syntax_kind = header_kind;
            rule_syntax_depth = header_kind == CONFIG_BLOCK_YAML ? 0 :
                calib_rule_syntax_brace_delta(line);
            if (header_kind != CONFIG_BLOCK_YAML && rule_syntax_depth <= 0) write_ok = false;
        }
    }
    if (write_ok && in_rule_syntax_block && rule_syntax_kind != CONFIG_BLOCK_YAML) {
        write_ok = false;
        calib_config_block_clear(&block);
    } else if (write_ok) {
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
        calib_config_lock_release();
        return false;
    }
    if (rename(tmp_path, config_file) != 0) {
        unlink(tmp_path);
        calib_config_lock_release();
        return false;
    }
    calib_config_lock_release();
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
    d->started_ms = 0;
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
                CalibProcess probe[64];
                size_t np = collect_pkg_processes(pkg, probe, 64);
                if (np > 0 && calib_has_main_process(probe, np, pkg) &&
                    strlen(pkg) < MAX_PKG_LEN) {
                    calib_free(&data);
                    memset(&data, 0, sizeof(data));
                    build_str(data.pkg, sizeof(data.pkg), pkg, NULL);
                    data.started_ms = monotonic_ms();
                    sampling = true;
                    char st[MAX_PKG_LEN + 16];
                    snprintf(st, sizeof(st), "sampling %s", pkg);
                    calib_set_state(st);
                    printf("[校准] 开始采样 %s (%zu 个进程)\n", pkg, np);
                } else {
                    printf("[校准] 忽略 start: %s 未找到运行中的主进程 (应用未启动?) 或包名过长\n", pkg);
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

                    size_t rules_capacity = 0;
                    char* rules = NULL;
                    if (calib_rules_buffer_capacity(&data, &rules_capacity)) {
                        rules = calloc(1, rules_capacity);
                    }
                    int n = rules ?
                        calib_generate_rules(&data, &g_topo, rules, rules_capacity) : 0;
                    if (!calib_write_history(data.pkg, &data)) {
                        printf("[校准] 警告: %s 历史记录写入失败，规则生成继续执行\n", data.pkg);
                    }
                    char st[MAX_PKG_LEN + 64];
                    if (!rules) {
                        printf("[校准] 警告: %s 规则缓冲区分配失败\n", data.pkg);
                        snprintf(st, sizeof(st), "done %s;reason=memory", data.pkg);
                    } else if (n > 0) {
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
                    free(rules);
                    calib_free(&data);
                }
            }
        }

        if (sampling) {
            size_t prev_rounds = data.round_count;
            long long now_ms = monotonic_ms();
            bool timed_out = data.started_ms > 0 && now_ms >= data.started_ms &&
                now_ms - data.started_ms >= CALIB_MAX_SESSION_MS;
            if (timed_out || !calib_sample_once(&data)) {
                /* 主进程消失或异常长会话到达上限时，用已采集数据直接收尾。 */
                sampling = false;
                if (timed_out) {
                    printf("[校准] %s 已达到 6 小时会话上限, 用现有 %zu 轮/%zu 个负载项(跟踪 %zu 个TID, 子进程活跃线程摘要 %zu 个)数据直接生成规则\n",
                           data.pkg, data.round_count, data.count, data.tcount,
                           data.child_thread_count);
                } else {
                    printf("[校准] %s 主进程已退出, 用现有 %zu 轮/%zu 个负载项(跟踪 %zu 个TID, 子进程活跃线程摘要 %zu 个)数据直接生成规则\n",
                           data.pkg, data.round_count, data.count, data.tcount,
                           data.child_thread_count);
                }
                if (data.round_count < CALIB_MIN_ROUNDS) {
                    printf("[校准] 警告: %s 自动收尾时采样不足 (仅 %zu 轮, 建议 >=%d 轮), 未生成规则\n",
                           data.pkg, data.round_count, CALIB_MIN_ROUNDS);
                    char st[MAX_PKG_LEN + 64];
                    snprintf(st, sizeof(st), "done %s;reason=short", data.pkg);
                    calib_set_state(st);
                    calib_free(&data);
                    continue;
                }
                size_t rules_capacity = 0;
                char* rules = NULL;
                if (calib_rules_buffer_capacity(&data, &rules_capacity)) {
                    rules = calloc(1, rules_capacity);
                }
                int n = rules ?
                    calib_generate_rules(&data, &g_topo, rules, rules_capacity) : 0;
                if (!calib_write_history(data.pkg, &data)) {
                    printf("[校准] 警告: %s 历史记录写入失败，规则生成继续执行\n", data.pkg);
                }
                char st[MAX_PKG_LEN + 64];
                if (!rules) {
                    printf("[校准] 警告: %s 规则缓冲区分配失败\n", data.pkg);
                    snprintf(st, sizeof(st), "done %s;reason=memory", data.pkg);
                } else if (n > 0) {
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
                free(rules);
                calib_free(&data);
            } else if (data.round_count != prev_rounds &&
                       data.round_count % CALIB_PROGRESS_LOG_ROUNDS == 0) {
                /* 每 120 轮(约 60s)报一次进度，保留长会话心跳但避免持续刷屏。 */
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

