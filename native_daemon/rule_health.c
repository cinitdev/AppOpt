/* 线程/子进程规则健康状态。
 *
 * C fallback 复用 ProcCache 已经收集的进程和线程，不额外遍历 /proc。
 * 负向观察窗口只读取 ActivityTaskManager helper 的 foreground_task.state。helper
 * 不可用时仍接受真实命中，但不累计 miss。状态文件格式与 Rust 守护完全一致。
 */
typedef enum {
    RULE_HEALTH_PENDING = 0,
    RULE_HEALTH_VALID,
    RULE_HEALTH_MISSED,
} RuleHealthStatusC;

typedef struct {
    char kind;
    char owner[MAX_PKG_LEN];
    char target[MAX_THREAD_LEN];
    RuleHealthStatusC status;
    unsigned int miss_count;
    unsigned long long first_observed_at;
    unsigned long long last_matched_at;
    unsigned long long last_checked_at;
    char last_checked_boot_id[129];
    unsigned long long last_checked_lifecycle_elapsed_ms;
    char rule_line[512];
    bool is_new;
    bool eligible;
} RuleHealthEntryC;

typedef struct {
    char pkg[MAX_PKG_LEN];
    unsigned long long started_elapsed_ms;
    unsigned long long full_scan_elapsed_ms;
    unsigned long long lifecycle_entered_elapsed_ms;
    unsigned long long lifecycle_entered_wall_ms;
    bool active;
    bool checked;
    bool full_scan_attempted;
} RuleHealthSessionC;

typedef struct {
    bool available;
    bool status_ok;
    bool listener_registered;
    char focused[MAX_PKG_LEN];
    char selection[32];
    char visible[4096];
    char exited[RULE_HEALTH_EXIT_BUFFER_SIZE];
    char lifecycle[RULE_HEALTH_LIFECYCLE_BUFFER_SIZE];
    unsigned long long updated_elapsed_ms;
} RuleHealthForegroundC;

typedef struct {
    char pkg[MAX_PKG_LEN];
    unsigned long long entered_elapsed_ms;
} RuleHealthDiscoveryLifecycleC;

static RuleHealthEntryC* rule_health_entries = NULL;
static size_t rule_health_count = 0;
static RuleHealthSessionC* rule_health_sessions = NULL;
static size_t rule_health_session_count = 0;
static bool rule_health_loaded = false;
static bool rule_health_config_ready = false;
static bool rule_health_config_fingerprint_valid = false;
static uint64_t rule_health_config_fingerprint_value = 0;
static bool rule_health_dirty = false;
static char rule_health_boot_id[129] = "";
static RuleHealthDiscoveryLifecycleC* rule_health_discovery_lifecycles = NULL;
static size_t rule_health_discovery_lifecycle_count = 0;
static unsigned long long rule_health_last_discovery_scan_elapsed_ms = 0;

static const char* rule_health_status_name(RuleHealthStatusC status) {
    if (status == RULE_HEALTH_VALID) return "valid";
    if (status == RULE_HEALTH_MISSED) return "missed";
    return "pending";
}

static RuleHealthStatusC rule_health_parse_status(const char* value) {
    if (value && strcmp(value, "valid") == 0) return RULE_HEALTH_VALID;
    if (value && strcmp(value, "missed") == 0) return RULE_HEALTH_MISSED;
    return RULE_HEALTH_PENDING;
}

static void rule_health_base_pkg(const char* owner, char* out, size_t out_size) {
    if (!out || out_size == 0) return;
    out[0] = '\0';
    if (!owner) return;
    const char* colon = strchr(owner, ':');
    size_t len = colon ? (size_t)(colon - owner) : strlen(owner);
    if (len >= out_size) len = out_size - 1;
    memcpy(out, owner, len);
    out[len] = '\0';
}

static void rule_health_trim_line_end(char* value) {
    if (!value) return;
    size_t len = strlen(value);
    while (len > 0 && (value[len - 1] == '\n' || value[len - 1] == '\r')) {
        value[--len] = '\0';
    }
}

static void rule_health_unescape(char* out, size_t out_size, const char* value) {
    if (!out || out_size == 0) return;
    size_t written = 0;
    for (size_t i = 0; value && value[i] && written + 1 < out_size; i++) {
        char ch = value[i];
        if (ch != '\\' || value[i + 1] == '\0') {
            out[written++] = ch;
            continue;
        }
        char escaped = value[++i];
        if (escaped == 't') out[written++] = '\t';
        else if (escaped == 'n') out[written++] = '\n';
        else if (escaped == '\\') out[written++] = '\\';
        else {
            out[written++] = '\\';
            if (written + 1 < out_size) out[written++] = escaped;
        }
    }
    out[written] = '\0';
}

static bool rule_health_write_escaped(FILE* fp, const char* value) {
    if (!fp) return false;
    for (const char* p = value ? value : ""; *p; p++) {
        const char* escaped = NULL;
        if (*p == '\\') escaped = "\\\\";
        else if (*p == '\t') escaped = "\\t";
        else if (*p == '\n') escaped = "\\n";
        if (escaped) {
            if (fputs(escaped, fp) == EOF) return false;
        } else if (fputc((unsigned char)*p, fp) == EOF) {
            return false;
        }
    }
    return true;
}

static ssize_t rule_health_find_in(
    const RuleHealthEntryC* entries,
    size_t count,
    char kind,
    const char* owner,
    const char* target
) {
    for (size_t i = 0; i < count; i++) {
        const RuleHealthEntryC* entry = &entries[i];
        if (entry->kind == kind && strcmp(entry->owner, owner) == 0 &&
            strcmp(entry->target, target ? target : "") == 0) {
            return (ssize_t)i;
        }
    }
    return -1;
}

static ssize_t rule_health_find(char kind, const char* owner, const char* target) {
    return rule_health_find_in(
        rule_health_entries, rule_health_count, kind, owner, target);
}

static bool rule_health_load(void) {
    if (rule_health_loaded) return true;
    FILE* fp = fopen(RULE_HEALTH_FILE, "r");
    if (!fp) {
        if (errno == ENOENT) {
            rule_health_loaded = true;
            return true;
        }
        return false;
    }

    RuleHealthEntryC* loaded_entries = NULL;
    size_t loaded_count = 0;
    bool ok = true;
    char* line = NULL;
    size_t cap = 0;
    while (getline(&line, &cap, fp) != -1) {
        char* fields[11] = {0};
        char* cursor = line;
        size_t field_count = 0;
        while (field_count < 11) {
            fields[field_count++] = cursor;
            char* tab = strchr(cursor, '\t');
            if (!tab) break;
            *tab = '\0';
            cursor = tab + 1;
        }
        if (field_count != 9 && field_count != 11) continue;
        size_t rule_line_index = field_count == 11 ? 10 : 8;
        rule_health_trim_line_end(fields[rule_line_index]);
        if (!fields[0][0] || !fields[1][0]) continue;

        RuleHealthEntryC* resized = realloc(
            loaded_entries, (loaded_count + 1) * sizeof(*resized));
        if (!resized) {
            errno = ENOMEM;
            ok = false;
            break;
        }
        loaded_entries = resized;
        RuleHealthEntryC* entry = &loaded_entries[loaded_count++];
        memset(entry, 0, sizeof(*entry));
        entry->kind = fields[0][0];
        rule_health_unescape(entry->owner, sizeof(entry->owner), fields[1]);
        rule_health_unescape(entry->target, sizeof(entry->target), fields[2]);
        entry->status = rule_health_parse_status(fields[3]);
        entry->miss_count = (unsigned int)strtoul(fields[4], NULL, 10);
        entry->first_observed_at = strtoull(fields[5], NULL, 10);
        entry->last_matched_at = strtoull(fields[6], NULL, 10);
        entry->last_checked_at = strtoull(fields[7], NULL, 10);
        if (field_count == 11) {
            rule_health_unescape(
                entry->last_checked_boot_id,
                sizeof(entry->last_checked_boot_id),
                fields[8]);
            entry->last_checked_lifecycle_elapsed_ms = strtoull(fields[9], NULL, 10);
        }
        rule_health_unescape(
            entry->rule_line, sizeof(entry->rule_line), fields[rule_line_index]);
    }
    if (ok && (ferror(fp) || !feof(fp))) {
        if (errno == 0) errno = EIO;
        ok = false;
    }
    int saved_errno = errno;
    free(line);
    if (fclose(fp) != 0) {
        saved_errno = errno;
        ok = false;
    }
    if (!ok) {
        free(loaded_entries);
        errno = saved_errno ? saved_errno : EIO;
        return false;
    }

    free(rule_health_entries);
    rule_health_entries = loaded_entries;
    rule_health_count = loaded_count;
    rule_health_loaded = true;
    return true;
}

static bool rule_health_write(void) {
    char temp[PATH_MAX];
    snprintf(temp, sizeof(temp), "%s.tmp.%ld", RULE_HEALTH_FILE, (long)getpid());
    FILE* fp = fopen(temp, "w");
    if (!fp) return false;

    bool ok = true;
    for (size_t i = 0; i < rule_health_count && ok; i++) {
        const RuleHealthEntryC* entry = &rule_health_entries[i];
        ok = fprintf(fp, "%c\t", entry->kind) >= 0 &&
             rule_health_write_escaped(fp, entry->owner) && fputc('\t', fp) != EOF &&
             rule_health_write_escaped(fp, entry->target) &&
             fprintf(fp, "\t%s\t%u\t%llu\t%llu\t%llu\t",
                     rule_health_status_name(entry->status), entry->miss_count,
                     entry->first_observed_at, entry->last_matched_at,
                     entry->last_checked_at) >= 0 &&
             rule_health_write_escaped(fp, entry->last_checked_boot_id) &&
             fprintf(fp, "\t%llu\t", entry->last_checked_lifecycle_elapsed_ms) >= 0 &&
             rule_health_write_escaped(fp, entry->rule_line) && fputc('\n', fp) != EOF;
    }
    bool write_failed = !ok || ferror(fp) || fflush(fp) != 0 || fsync(fileno(fp)) != 0;
    if (fclose(fp) != 0) write_failed = true;
    if (write_failed) {
        unlink(temp);
        return false;
    }
    if (rename(temp, RULE_HEALTH_FILE) != 0) {
        unlink(temp);
        return false;
    }
    return true;
}

static void rule_health_finish_update(bool changed) {
    if (changed) rule_health_dirty = true;
    if (!rule_health_dirty) return;
    if (rule_health_write()) {
        rule_health_dirty = false;
    } else {
        printf("[规则健康] 写入状态文件失败，将在下一轮重试: %s\n", strerror(errno));
    }
}

static uint64_t rule_health_hash_bytes(uint64_t hash, const void* data, size_t size) {
    const unsigned char* bytes = data;
    for (size_t i = 0; i < size; i++) {
        hash ^= bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

static uint64_t rule_health_config_fingerprint(const AppConfig* cfg) {
    uint64_t hash = 14695981039346656037ULL;
    hash = rule_health_hash_bytes(hash, &cfg->num_rules, sizeof(cfg->num_rules));
    for (size_t i = 0; i < cfg->num_rules; i++) {
        const AffinityRule* rule = &cfg->rules[i];
        hash = rule_health_hash_bytes(hash, rule->pkg, strlen(rule->pkg) + 1);
        hash = rule_health_hash_bytes(hash, rule->thread, strlen(rule->thread) + 1);
        hash = rule_health_hash_bytes(hash, &rule->cpus, sizeof(rule->cpus));
    }
    return hash;
}

static bool rule_health_sync_config(const AppConfig* cfg, bool* changed_out) {
    if (changed_out) *changed_out = false;
    uint64_t fingerprint = rule_health_config_fingerprint(cfg);
    if (rule_health_config_fingerprint_valid &&
        rule_health_config_fingerprint_value == fingerprint) {
        rule_health_config_ready = true;
        return true;
    }
    if (!rule_health_load()) {
        rule_health_config_ready = false;
        printf("[规则健康] 读取状态文件失败，本轮暂停同步: %s\n", strerror(errno));
        return false;
    }
    RuleHealthEntryC* next = NULL;
    if (cfg->num_rules > 0) {
        next = calloc(cfg->num_rules, sizeof(*next));
        if (!next) {
            printf("[规则健康] 同步配置失败: 内存不足，保留原状态\n");
            rule_health_config_ready = false;
            return false;
        }
    }

    size_t next_count = 0;
    bool changed = false;
    for (size_t i = 0; i < cfg->num_rules; i++) {
        const AffinityRule* rule = &cfg->rules[i];
        char kind = 0;
        const char* target = "";
        if (rule->thread[0]) {
            kind = 'T';
            target = rule->thread;
        } else if (strchr(rule->pkg, ':')) {
            kind = 'P';
        } else {
            continue;
        }

        RuleHealthEntryC entry = {0};
        entry.kind = kind;
        build_str(entry.owner, sizeof(entry.owner), rule->pkg, NULL);
        build_str(entry.target, sizeof(entry.target), target, NULL);
        ssize_t old_index = rule_health_find(kind, entry.owner, entry.target);
        if (old_index >= 0) {
            entry = rule_health_entries[old_index];
        } else {
            entry.status = RULE_HEALTH_PENDING;
            entry.is_new = true;
            changed = true;
        }

        char* cpus = cpu_set_to_str(&rule->cpus);
        if (cpus) {
            char new_line[512];
            if (kind == 'T') {
                snprintf(new_line, sizeof(new_line), "%s{%s}=%s", rule->pkg, rule->thread, cpus);
            } else {
                snprintf(new_line, sizeof(new_line), "%s=%s", rule->pkg, cpus);
            }
            if (strcmp(entry.rule_line, new_line) != 0) {
                build_str(entry.rule_line, sizeof(entry.rule_line), new_line, NULL);
                changed = true;
            }
            free(cpus);
        }

        // 同一目标即使在配置里重复出现，也只有一个健康身份；和 Rust HashMap 行为一致。
        ssize_t duplicate = rule_health_find_in(
            next, next_count, entry.kind, entry.owner, entry.target);
        if (duplicate >= 0) {
            next[duplicate] = entry;
        } else {
            next[next_count++] = entry;
        }
    }

    if (next_count != rule_health_count) changed = true;
    free(rule_health_entries);
    rule_health_entries = next;
    rule_health_count = next_count;
    rule_health_config_fingerprint_value = fingerprint;
    rule_health_config_fingerprint_valid = true;
    rule_health_config_ready = true;
    if (changed) rule_health_dirty = true;
    if (changed_out) *changed_out = changed;
    return true;
}

static bool rule_health_rule_disabled(const AffinityRule* rule) {
    if (!rule) return false;
    char kind = 0;
    const char* target = "";
    if (rule->thread[0]) {
        kind = 'T';
        target = rule->thread;
    } else if (strchr(rule->pkg, ':')) {
        kind = 'P';
    } else {
        return false;
    }

    if (!rule_health_config_ready || !rule_health_load()) return false;
    ssize_t index = rule_health_find(kind, rule->pkg, target);
    return index >= 0 &&
        rule_health_entries[index].status == RULE_HEALTH_MISSED;
}

static RuleHealthSessionC* rule_health_session(const char* pkg, bool create) {
    for (size_t i = 0; i < rule_health_session_count; i++) {
        if (strcmp(rule_health_sessions[i].pkg, pkg) == 0) return &rule_health_sessions[i];
    }
    if (!create) return NULL;
    RuleHealthSessionC* resized = realloc(
        rule_health_sessions, (rule_health_session_count + 1) * sizeof(*resized));
    if (!resized) return NULL;
    rule_health_sessions = resized;
    RuleHealthSessionC* session = &rule_health_sessions[rule_health_session_count++];
    memset(session, 0, sizeof(*session));
    build_str(session->pkg, sizeof(session->pkg), pkg, NULL);
    return session;
}

static bool rule_health_has_pending(const char* pkg) {
    for (size_t i = 0; i < rule_health_count; i++) {
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(rule_health_entries[i].owner, base, sizeof(base));
        if (strcmp(base, pkg) == 0 && rule_health_entries[i].status == RULE_HEALTH_PENDING) return true;
    }
    return false;
}

static bool rule_health_has_new(const char* pkg) {
    for (size_t i = 0; i < rule_health_count; i++) {
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(rule_health_entries[i].owner, base, sizeof(base));
        if (strcmp(base, pkg) == 0 && rule_health_entries[i].is_new &&
            rule_health_entries[i].status == RULE_HEALTH_PENDING) return true;
    }
    return false;
}

static void rule_health_prune_sessions(void) {
    size_t kept = 0;
    for (size_t i = 0; i < rule_health_session_count; i++) {
        if (!rule_health_has_pending(rule_health_sessions[i].pkg)) continue;
        if (kept != i) rule_health_sessions[kept] = rule_health_sessions[i];
        kept++;
    }
    rule_health_session_count = kept;
    if (kept == 0) {
        free(rule_health_sessions);
        rule_health_sessions = NULL;
        return;
    }
    RuleHealthSessionC* resized = realloc(rule_health_sessions, kept * sizeof(*resized));
    if (resized) rule_health_sessions = resized;
}

static unsigned long long rule_health_boottime_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0 || ts.tv_sec < 0 || ts.tv_nsec < 0) return 0;
    return (unsigned long long)ts.tv_sec * 1000ULL +
           (unsigned long long)ts.tv_nsec / 1000000ULL;
}

static const char* rule_health_current_boot_id(void) {
    if (rule_health_boot_id[0]) return rule_health_boot_id;
    char raw[160];
    if (!read_file(AT_FDCWD, "/proc/sys/kernel/random/boot_id", raw, sizeof(raw))) return "";
    char* value = strtrim(raw);
    size_t len = strlen(value);
    if (len == 0 || len >= sizeof(rule_health_boot_id)) return "";
    build_str(rule_health_boot_id, sizeof(rule_health_boot_id), value, NULL);
    return rule_health_boot_id;
}

static RuleHealthForegroundC rule_health_read_foreground(unsigned long long now_elapsed_ms) {
    RuleHealthForegroundC state = {0};
    FILE* fp = fopen(FOREGROUND_TASK_STATE_FILE, "r");
    if (!fp) return state;

    char status[32] = {0};
    char boot_id[129] = {0};
    unsigned long version = 0;
    unsigned long long updated_elapsed_ms = 0;
    char* line = NULL;
    size_t cap = 0;
    while (getline(&line, &cap, fp) != -1) {
        char* equal = strchr(line, '=');
        if (!equal) continue;
        *equal = '\0';
        char* key = strtrim(line);
        char* value = strtrim(equal + 1);
        if (strcmp(key, "version") == 0) {
            version = strtoul(value, NULL, 10);
        } else if (strcmp(key, "boot_id") == 0) {
            build_str(boot_id, sizeof(boot_id), value, NULL);
        } else if (strcmp(key, "status") == 0) {
            build_str(status, sizeof(status), value, NULL);
        } else if (strcmp(key, "mode") == 0) {
            state.listener_registered = strcmp(value, "listener") == 0;
        } else if (strcmp(key, "listener_registered") == 0) {
            state.listener_registered = strcmp(value, "1") == 0;
        } else if (strcmp(key, "selection") == 0) {
            build_str(state.selection, sizeof(state.selection), value, NULL);
        } else if (strcmp(key, "focused_package") == 0) {
            build_str(state.focused, sizeof(state.focused), value, NULL);
        } else if (strcmp(key, "visible_packages") == 0) {
            build_str(state.visible, sizeof(state.visible), value, NULL);
        } else if (strcmp(key, "exited_packages") == 0) {
            build_str(state.exited, sizeof(state.exited), value, NULL);
        } else if (strcmp(key, "lifecycle_packages") == 0) {
            build_str(state.lifecycle, sizeof(state.lifecycle), value, NULL);
        } else if (strcmp(key, "updated_elapsed_ms") == 0) {
            updated_elapsed_ms = strtoull(value, NULL, 10);
        }
    }
    free(line);
    fclose(fp);

    const char* current_boot_id = rule_health_current_boot_id();
    bool fresh = updated_elapsed_ms > 0 && now_elapsed_ms >= updated_elapsed_ms &&
        now_elapsed_ms - updated_elapsed_ms <= FOREGROUND_TASK_MAX_AGE_MS;
    bool valid_selection = strcmp(state.selection, "focused") == 0 ||
        strcmp(state.selection, "default-visible") == 0 ||
        strcmp(state.selection, "visible") == 0 ||
        strcmp(state.selection, "first") == 0 ||
        strcmp(state.selection, "none") == 0;
    state.status_ok = strcmp(status, "ok") == 0;
    state.available = version >= 2 && current_boot_id[0] &&
        strcmp(boot_id, current_boot_id) == 0 && state.listener_registered && fresh &&
        valid_selection && (state.status_ok || strcmp(status, "empty") == 0);
    state.updated_elapsed_ms = updated_elapsed_ms;
    return state;
}

static bool rule_health_visible_contains(const char* visible, const char* pkg) {
    if (!visible || !pkg || !pkg[0]) return false;
    size_t pkg_len = strlen(pkg);
    const char* cursor = visible;
    while (*cursor) {
        while (*cursor == ',' || isspace((unsigned char)*cursor)) cursor++;
        const char* end = cursor;
        while (*end && *end != ',') end++;
        const char* trimmed_end = end;
        while (trimmed_end > cursor && isspace((unsigned char)trimmed_end[-1])) trimmed_end--;
        if ((size_t)(trimmed_end - cursor) == pkg_len && strncmp(cursor, pkg, pkg_len) == 0) {
            return true;
        }
        cursor = end;
    }
    return false;
}

static bool rule_health_scope_selection(const RuleHealthForegroundC* state) {
    return state && state->available && state->status_ok &&
        (strcmp(state->selection, "focused") == 0 ||
         strcmp(state->selection, "default-visible") == 0 ||
         strcmp(state->selection, "visible") == 0);
}

static bool rule_health_start_selection(const RuleHealthForegroundC* state) {
    return rule_health_scope_selection(state) &&
        (strcmp(state->selection, "focused") == 0 ||
         strcmp(state->selection, "default-visible") == 0);
}

static bool rule_health_foreground_contains(const RuleHealthForegroundC* state, const char* pkg) {
    if (!rule_health_scope_selection(state)) return false;
    return strcmp(state->focused, pkg) == 0 || rule_health_visible_contains(state->visible, pkg);
}

static bool rule_health_config_contains_base_pkg(const AppConfig* cfg, const char* pkg) {
    if (!cfg || !pkg || !pkg[0]) return false;
    for (size_t i = 0; i < cfg->num_pkgs; i++) {
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(cfg->pkgs[i], base, sizeof(base));
        if (strcmp(base, pkg) == 0) return true;
    }
    return false;
}

static bool rule_health_parse_u64_span(
    const char* start,
    const char* end,
    unsigned long long* value
) {
    if (!start || !end || !value || start >= end) return false;
    char number[32];
    size_t len = (size_t)(end - start);
    if (len >= sizeof(number)) return false;
    memcpy(number, start, len);
    number[len] = '\0';
    char* parse_end = NULL;
    errno = 0;
    unsigned long long parsed = strtoull(number, &parse_end, 10);
    if (errno == ERANGE || !parse_end || *parse_end != '\0' || parsed == 0) return false;
    *value = parsed;
    return true;
}

static bool rule_health_lifecycle(
    const RuleHealthForegroundC* state,
    const char* pkg,
    unsigned long long* entered_elapsed,
    unsigned long long* entered_wall
) {
    if (!state || !state->available || !pkg || !pkg[0] ||
        !entered_elapsed || !entered_wall) return false;
    size_t pkg_len = strlen(pkg);
    const char* cursor = state->lifecycle;
    while (*cursor) {
        while (*cursor == ',' || isspace((unsigned char)*cursor)) cursor++;
        const char* end = cursor;
        while (*end && *end != ',') end++;
        const char* first_at = memchr(cursor, '@', (size_t)(end - cursor));
        const char* second_at = first_at ?
            memchr(first_at + 1, '@', (size_t)(end - first_at - 1)) : NULL;
        if (first_at && second_at && (size_t)(first_at - cursor) == pkg_len &&
            strncmp(cursor, pkg, pkg_len) == 0 &&
            rule_health_parse_u64_span(first_at + 1, second_at, entered_elapsed) &&
            rule_health_parse_u64_span(second_at + 1, end, entered_wall)) {
            return true;
        }
        cursor = end;
    }
    return false;
}

/* 配置应用每次进入新的可靠前台生命周期时只补一次全量发现扫描。
 * 这能覆盖系统进程总数净值未增长的进程替换场景，又不会按守护轮次反复遍历 /proc。 */
static bool rule_health_foreground_discovery_scan_due(
    const AppConfig* cfg,
    const ProcCache* cache
) {
    for (size_t i = 0; i < rule_health_discovery_lifecycle_count;) {
        if (rule_health_config_contains_base_pkg(
                cfg, rule_health_discovery_lifecycles[i].pkg)) {
            i++;
            continue;
        }
        rule_health_discovery_lifecycles[i] =
            rule_health_discovery_lifecycles[--rule_health_discovery_lifecycle_count];
    }
    unsigned long long now_elapsed = rule_health_boottime_ms();
    if (now_elapsed == 0) return false;
    RuleHealthForegroundC foreground = rule_health_read_foreground(now_elapsed);
    if (!rule_health_start_selection(&foreground) || !foreground.focused[0] ||
        !rule_health_config_contains_base_pkg(cfg, foreground.focused)) {
        return false;
    }

    unsigned long long entered_elapsed = 0;
    unsigned long long entered_wall = 0;
    if (!rule_health_lifecycle(
            &foreground, foreground.focused, &entered_elapsed, &entered_wall)) {
        return false;
    }
    (void)entered_wall;

    unsigned long long discovery_deadline =
        entered_elapsed > ULLONG_MAX - FOREGROUND_DISCOVERY_DELAY_MS ?
            ULLONG_MAX : entered_elapsed + FOREGROUND_DISCOVERY_DELAY_MS;
    if (now_elapsed < discovery_deadline) return false;

    RuleHealthDiscoveryLifecycleC* lifecycle_item = NULL;
    for (size_t i = 0; i < rule_health_discovery_lifecycle_count; i++) {
        RuleHealthDiscoveryLifecycleC* item = &rule_health_discovery_lifecycles[i];
        if (strcmp(item->pkg, foreground.focused) != 0) continue;
        if (item->entered_elapsed_ms == entered_elapsed) return false;
        lifecycle_item = item;
        break;
    }

    bool existing_scan_covers_lifecycle = cache &&
        cache->last_full_scan_elapsed_ms >= discovery_deadline;
    if (!existing_scan_covers_lifecycle &&
        rule_health_last_discovery_scan_elapsed_ms > 0 &&
        now_elapsed - rule_health_last_discovery_scan_elapsed_ms <
            FOREGROUND_DISCOVERY_COOLDOWN_MS) {
        return false;
    }

    if (!lifecycle_item) {
        RuleHealthDiscoveryLifecycleC* resized = realloc(
            rule_health_discovery_lifecycles,
            (rule_health_discovery_lifecycle_count + 1) * sizeof(*resized));
        if (!resized) return false;
        rule_health_discovery_lifecycles = resized;
        lifecycle_item =
            &rule_health_discovery_lifecycles[rule_health_discovery_lifecycle_count++];
        memset(lifecycle_item, 0, sizeof(*lifecycle_item));
        build_str(
            lifecycle_item->pkg,
            sizeof(lifecycle_item->pkg),
            foreground.focused,
            NULL);
    }
    lifecycle_item->entered_elapsed_ms = entered_elapsed;
    if (existing_scan_covers_lifecycle) return false;
    rule_health_last_discovery_scan_elapsed_ms = now_elapsed;
    return true;
}

static bool rule_health_exit_elapsed(
    const RuleHealthForegroundC* state,
    const char* pkg,
    unsigned long long* elapsed
) {
    if (!state || !state->available || !pkg || !pkg[0] || !elapsed) return false;
    size_t pkg_len = strlen(pkg);
    const char* cursor = state->exited;
    while (*cursor) {
        while (*cursor == ',' || isspace((unsigned char)*cursor)) cursor++;
        const char* end = cursor;
        while (*end && *end != ',') end++;
        const char* separator = memchr(cursor, '@', (size_t)(end - cursor));
        if (separator && (size_t)(separator - cursor) == pkg_len &&
            strncmp(cursor, pkg, pkg_len) == 0) {
            char number[32];
            size_t len = (size_t)(end - separator - 1);
            if (len == 0 || len >= sizeof(number)) return false;
            memcpy(number, separator + 1, len);
            number[len] = '\0';
            char* parse_end = NULL;
            unsigned long long value = strtoull(number, &parse_end, 10);
            if (parse_end && *parse_end == '\0') {
                *elapsed = value;
                return true;
            }
            return false;
        }
        cursor = end;
    }
    return false;
}

static bool rule_health_observation_complete(
    unsigned long long started_elapsed,
    unsigned long long ended_elapsed
) {
    return ended_elapsed >= started_elapsed &&
        ended_elapsed - started_elapsed >= RULE_HEALTH_OBSERVE_SECS * 1000ULL;
}

static unsigned long long rule_health_observation_deadline(unsigned long long started_elapsed) {
    return started_elapsed + RULE_HEALTH_OBSERVE_SECS * 1000ULL;
}

static void rule_health_clear_eligible(const char* pkg) {
    for (size_t i = 0; i < rule_health_count; i++) {
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(rule_health_entries[i].owner, base, sizeof(base));
        if (strcmp(base, pkg) == 0) rule_health_entries[i].eligible = false;
    }
}

static bool rule_health_prepare_eligible(
    const char* pkg,
    bool new_lifecycle,
    unsigned long long entered_elapsed_ms,
    unsigned long long entered_wall_ms
) {
    bool any = false;
    for (size_t i = 0; i < rule_health_count; i++) {
        RuleHealthEntryC* entry = &rule_health_entries[i];
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(entry->owner, base, sizeof(base));
        if (strcmp(base, pkg) != 0 || entry->status != RULE_HEALTH_PENDING) continue;
        if (new_lifecycle) {
            const char* current_boot_id = rule_health_current_boot_id();
            bool has_lifecycle_identity = entry->last_checked_boot_id[0] &&
                entry->last_checked_lifecycle_elapsed_ms > 0;
            bool checked_in_lifecycle = has_lifecycle_identity ?
                current_boot_id[0] &&
                    strcmp(entry->last_checked_boot_id, current_boot_id) == 0 &&
                    entry->last_checked_lifecycle_elapsed_ms == entered_elapsed_ms :
                entry->last_checked_at > 0 &&
                    (entry->last_checked_at > ULLONG_MAX / 1000ULL ||
                     entry->last_checked_at * 1000ULL >= entered_wall_ms);
            /* 旧版 9 列状态只能用墙上时间判断。首次读取时把“本次已经检查过”
             * 迁移成 boot_id + 单调时钟身份，之后系统时间回拨也不会长期跳过新生命周期。 */
            if (!has_lifecycle_identity && checked_in_lifecycle && current_boot_id[0]) {
                build_str(
                    entry->last_checked_boot_id,
                    sizeof(entry->last_checked_boot_id),
                    current_boot_id,
                    NULL);
                entry->last_checked_lifecycle_elapsed_ms = entered_elapsed_ms;
                rule_health_dirty = true;
            }
            entry->eligible = !checked_in_lifecycle;
        } else if (entry->is_new) {
            entry->eligible = true;
        }
        if (entry->eligible) any = true;
    }
    return any;
}

static bool rule_health_start_observation(
    RuleHealthSessionC* session,
    unsigned long long now_wall,
    unsigned long long now_elapsed,
    bool new_lifecycle,
    unsigned long long entered_elapsed_ms,
    unsigned long long entered_wall_ms
) {
    if (!session || !rule_health_has_pending(session->pkg)) {
        if (session) {
            session->started_elapsed_ms = 0;
            session->full_scan_elapsed_ms = 0;
            session->checked = true;
            session->full_scan_attempted = false;
        }
        return false;
    }

    if (new_lifecycle) {
        session->lifecycle_entered_elapsed_ms = entered_elapsed_ms;
        session->lifecycle_entered_wall_ms = entered_wall_ms;
    }
    if (session->lifecycle_entered_elapsed_ms == 0 ||
        session->lifecycle_entered_wall_ms == 0 ||
        !rule_health_prepare_eligible(
            session->pkg, new_lifecycle,
            session->lifecycle_entered_elapsed_ms,
            session->lifecycle_entered_wall_ms)) {
        session->started_elapsed_ms = 0;
        session->full_scan_elapsed_ms = 0;
        session->checked = true;
        session->full_scan_attempted = false;
        return false;
    }

    session->started_elapsed_ms = now_elapsed;
    session->full_scan_elapsed_ms = 0;
    session->checked = false;
    session->full_scan_attempted = false;
    bool changed = false;
    for (size_t i = 0; i < rule_health_count; i++) {
        RuleHealthEntryC* entry = &rule_health_entries[i];
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(entry->owner, base, sizeof(base));
        if (strcmp(base, session->pkg) == 0 && entry->status == RULE_HEALTH_PENDING &&
            entry->eligible && entry->first_observed_at == 0) {
            entry->first_observed_at = now_wall;
            changed = true;
        }
    }
    printf("[规则健康] 开始观察: 应用=%s 窗口=%d秒\n",
           session->pkg, RULE_HEALTH_OBSERVE_SECS);
    return changed;
}

static bool rule_health_mark_matches(const ProcCache* cache, unsigned long long now_wall) {
    bool changed = false;
    for (size_t i = 0; i < rule_health_count; i++) {
        RuleHealthEntryC* entry = &rule_health_entries[i];
        /* missed 是连续两次观察后的终态，只有 pending 仍参与健康确认。 */
        if (entry->status != RULE_HEALTH_PENDING) continue;
        bool matched = false;
        for (size_t p = 0; p < cache->num_procs && !matched; p++) {
            const ProcessInfo* proc = &cache->procs[p];
            if (strcmp(proc->pkg, entry->owner) != 0) continue;
            if (entry->kind == 'P') {
                matched = true;
                break;
            }
            for (size_t t = 0; t < proc->num_threads; t++) {
                if (fnmatch(entry->target, proc->threads[t].name, FNM_NOESCAPE) == 0) {
                    matched = true;
                    break;
                }
            }
        }
        if (matched && (entry->status != RULE_HEALTH_VALID || entry->miss_count != 0)) {
            printf("[规则健康] 已确认: %s\n", entry->rule_line);
            entry->status = RULE_HEALTH_VALID;
            entry->miss_count = 0;
            entry->last_matched_at = now_wall;
            entry->last_checked_at = now_wall;
            entry->eligible = false;
            changed = true;
        }
    }
    return changed;
}

static void rule_health_finish_observation(
    const char* pkg,
    unsigned long long now_wall,
    unsigned long long lifecycle_entered_elapsed_ms,
    size_t* first_miss,
    size_t* confirmed_miss
) {
    *first_miss = 0;
    *confirmed_miss = 0;
    for (size_t i = 0; i < rule_health_count; i++) {
        RuleHealthEntryC* entry = &rule_health_entries[i];
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(entry->owner, base, sizeof(base));
        if (strcmp(base, pkg) != 0 || entry->status != RULE_HEALTH_PENDING ||
            !entry->eligible) continue;
        if (entry->miss_count < UINT_MAX) entry->miss_count++;
        if (entry->miss_count >= 2) {
            entry->status = RULE_HEALTH_MISSED;
            (*confirmed_miss)++;
        } else {
            entry->status = RULE_HEALTH_PENDING;
            (*first_miss)++;
        }
        entry->last_checked_at = now_wall;
        const char* current_boot_id = rule_health_current_boot_id();
        if (current_boot_id[0]) {
            build_str(
                entry->last_checked_boot_id,
                sizeof(entry->last_checked_boot_id),
                current_boot_id,
                NULL);
            entry->last_checked_lifecycle_elapsed_ms = lifecycle_entered_elapsed_ms;
        }
        entry->eligible = false;
    }
}

static bool rule_health_full_scan_due(const ProcCache* cache) {
    if (!cache) return false;
    unsigned long long now_elapsed = rule_health_boottime_ms();
    if (now_elapsed == 0) return false;
    for (size_t i = 0; i < rule_health_session_count; i++) {
        const RuleHealthSessionC* session = &rule_health_sessions[i];
        if (!session->active || session->checked || session->full_scan_attempted ||
            session->started_elapsed_ms == 0) continue;
        unsigned long long deadline =
            rule_health_observation_deadline(session->started_elapsed_ms);
        if (now_elapsed < deadline || session->full_scan_elapsed_ms >= deadline) continue;
        if (cache->last_health_full_scan_attempt_elapsed_ms == 0 ||
            now_elapsed - cache->last_health_full_scan_attempt_elapsed_ms >=
                RULE_HEALTH_FULL_SCAN_RETRY_MS) {
            return true;
        }
    }
    return false;
}

static void rule_health_update(const AppConfig* cfg, const ProcCache* cache) {
    bool changed = false;
    if (!rule_health_sync_config(cfg, &changed)) return;
    unsigned long long now_wall = (unsigned long long)time(NULL);
    unsigned long long now_elapsed = rule_health_boottime_ms();

    bool has_pending_before_match = false;
    for (size_t i = 0; i < rule_health_count; i++) {
        if (rule_health_entries[i].status == RULE_HEALTH_PENDING) {
            has_pending_before_match = true;
            break;
        }
    }
    if (!has_pending_before_match) {
        rule_health_prune_sessions();
        for (size_t i = 0; i < rule_health_count; i++) rule_health_entries[i].is_new = false;
        rule_health_finish_update(changed);
        return;
    }

    if (rule_health_mark_matches(cache, now_wall)) changed = true;
    rule_health_prune_sessions();
    bool has_pending = false;
    for (size_t i = 0; i < rule_health_count; i++) {
        if (rule_health_entries[i].status != RULE_HEALTH_PENDING) continue;
        has_pending = true;
        char base[MAX_PKG_LEN];
        rule_health_base_pkg(rule_health_entries[i].owner, base, sizeof(base));
        rule_health_session(base, true);
    }

    if (!has_pending) {
        for (size_t i = 0; i < rule_health_count; i++) rule_health_entries[i].is_new = false;
        rule_health_finish_update(changed);
        return;
    }

    RuleHealthForegroundC foreground = {0};
    if (now_elapsed > 0) {
        foreground = rule_health_read_foreground(now_elapsed);
    }

    for (size_t s = 0; s < rule_health_session_count; s++) {
        RuleHealthSessionC* session = &rule_health_sessions[s];
        if (!session->active || session->checked || session->started_elapsed_ms == 0 ||
            session->full_scan_elapsed_ms != 0) continue;
        unsigned long long deadline =
            rule_health_observation_deadline(session->started_elapsed_ms);
        if (cache->last_health_full_scan_attempt_elapsed_ms >= deadline) {
            session->full_scan_attempted = true;
        }
        if (cache->last_full_scan_elapsed_ms >= deadline &&
            proc_cache_full_scan_complete_for(cache, session->pkg)) {
            session->full_scan_elapsed_ms = cache->last_full_scan_elapsed_ms;
        }
    }

    for (size_t s = 0; s < rule_health_session_count; s++) {
        RuleHealthSessionC* session = &rule_health_sessions[s];
        bool configured = rule_health_has_pending(session->pkg);
        unsigned long long lifecycle_elapsed = 0;
        unsigned long long lifecycle_wall = 0;
        bool has_lifecycle = rule_health_lifecycle(
            &foreground, session->pkg, &lifecycle_elapsed, &lifecycle_wall);
        bool in_scope = configured && has_lifecycle &&
            rule_health_foreground_contains(&foreground, session->pkg);

        bool ended = false;
        if (session->active && !foreground.available) {
            if (session->started_elapsed_ms > 0 && !session->checked) {
                printf("[规则健康] 观察取消: 应用=%s 原因=前台状态暂不可用\n",
                       session->pkg);
            }
            /* 继续锁住本次生命周期。helper 暂时不可用只取消当前观察窗口，
             * 不能让同一次应用启动在 helper 恢复后再次累计未命中；只有可靠退出
             * （或可靠快照确认应用已不在范围内）才能解除。 */
            session->checked = true;
            session->full_scan_elapsed_ms = 0;
            session->full_scan_attempted = false;
            rule_health_clear_eligible(session->pkg);
        } else if (session->active) {
            unsigned long long exited_elapsed = 0;
            bool lifecycle_changed = !has_lifecycle ||
                lifecycle_elapsed != session->lifecycle_entered_elapsed_ms ||
                lifecycle_wall != session->lifecycle_entered_wall_ms;
            bool interrupted = session->lifecycle_entered_elapsed_ms > 0 &&
                rule_health_exit_elapsed(&foreground, session->pkg, &exited_elapsed) &&
                exited_elapsed > session->lifecycle_entered_elapsed_ms &&
                (session->started_elapsed_ms == 0 ||
                 exited_elapsed > session->started_elapsed_ms);

            if (lifecycle_changed || interrupted || !in_scope) {
                if (session->started_elapsed_ms > 0 && !session->checked) {
                    printf("[规则健康] 观察取消: 应用=%s 原因=前台生命周期已结束或发生变化\n",
                           session->pkg);
                }
                ended = true;
            }
        }

        if (ended) {
            session->active = false;
            session->started_elapsed_ms = 0;
            session->full_scan_elapsed_ms = 0;
            session->lifecycle_entered_elapsed_ms = 0;
            session->lifecycle_entered_wall_ms = 0;
            session->checked = false;
            session->full_scan_attempted = false;
            rule_health_clear_eligible(session->pkg);
        }

        bool should_enter = configured && !session->active && has_lifecycle &&
            rule_health_start_selection(&foreground) &&
            rule_health_has_pending(session->pkg) &&
            strcmp(foreground.focused, session->pkg) == 0;
        if (should_enter) {
            session->active = true;
            if (rule_health_start_observation(
                    session, now_wall, now_elapsed, true,
                    lifecycle_elapsed, lifecycle_wall)) changed = true;
        } else if (session->active && in_scope && rule_health_has_new(session->pkg)) {
            if (rule_health_start_observation(
                    session, now_wall, now_elapsed, false,
                    session->lifecycle_entered_elapsed_ms,
                    session->lifecycle_entered_wall_ms)) changed = true;
        }
    }

    for (size_t s = 0; s < rule_health_session_count; s++) {
        RuleHealthSessionC* session = &rule_health_sessions[s];
        unsigned long long lifecycle_elapsed = 0;
        unsigned long long lifecycle_wall = 0;
        bool same_lifecycle = rule_health_lifecycle(
            &foreground, session->pkg, &lifecycle_elapsed, &lifecycle_wall) &&
            lifecycle_elapsed == session->lifecycle_entered_elapsed_ms &&
            lifecycle_wall == session->lifecycle_entered_wall_ms;
        if (!session->active || session->checked || session->started_elapsed_ms == 0 ||
            !rule_health_observation_complete(session->started_elapsed_ms, now_elapsed) ||
            !same_lifecycle ||
            !rule_health_foreground_contains(&foreground, session->pkg)) {
            continue;
        }

        unsigned long long deadline =
            rule_health_observation_deadline(session->started_elapsed_ms);
        if (foreground.updated_elapsed_ms < deadline ||
            session->full_scan_elapsed_ms < deadline ||
            foreground.updated_elapsed_ms < session->full_scan_elapsed_ms) continue;

        size_t first_miss = 0;
        size_t confirmed_miss = 0;
        rule_health_finish_observation(
            session->pkg, now_wall, session->lifecycle_entered_elapsed_ms,
            &first_miss, &confirmed_miss);
        session->checked = true;
        session->full_scan_elapsed_ms = 0;
        session->full_scan_attempted = false;
        if (first_miss + confirmed_miss > 0) {
            changed = true;
            printf("[规则健康] 观察结束: 应用=%s 首次待复核=%zu 连续未命中=%zu，会话保持到应用离开\n",
                   session->pkg, first_miss, confirmed_miss);
        }
    }

    for (size_t i = 0; i < rule_health_count; i++) rule_health_entries[i].is_new = false;
    rule_health_finish_update(changed);
}
