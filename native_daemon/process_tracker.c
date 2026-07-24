/* 文件化进程索引。
 *
 * 守护进程刷新时只临时加载 TSV，长期快照保存在文件中。App 与脚本通过
 * --find-pid/--find-processes 查询；输出前仍校验 starttime，避免 PID 复用。 */

/* 磁盘缓存写入 pid_cache.tsv，查询返回前仍校验当前进程身份。 */
typedef struct {
    pid_t pid;
    unsigned long long starttime;
    unsigned long long first_seen_elapsed_ms;
    char comm[MAX_PKG_LEN];
    char cmdline[MAX_PKG_LEN];
} ProcessIndexEntry;

static int process_index_compare_pid(const void* left, const void* right) {
    pid_t lhs = *(const pid_t*)left;
    pid_t rhs = *(const pid_t*)right;
    return lhs < rhs ? -1 : lhs > rhs ? 1 : 0;
}

static int process_index_compare_entry_pid(const void* left, const void* right) {
    const ProcessIndexEntry* lhs = left;
    const ProcessIndexEntry* rhs = right;
    return process_index_compare_pid(&lhs->pid, &rhs->pid);
}

static bool process_index_boot_id(char* out, size_t out_size) {
    if (!read_file(AT_FDCWD, "/proc/sys/kernel/random/boot_id", out, out_size)) {
        return false;
    }
    char* value = strtrim(out);
    if (value != out) memmove(out, value, strlen(value) + 1);
    return out[0] != '\0';
}

static int process_index_hex_value(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static bool process_index_hex_decode(const char* value, char* out, size_t out_size) {
    if (strcmp(value, "-") == 0) {
        if (out_size == 0) return false;
        out[0] = '\0';
        return true;
    }
    size_t length = strlen(value);
    if ((length & 1U) != 0 || length / 2 >= out_size) return false;
    size_t output = 0;
    for (size_t i = 0; i < length; i += 2) {
        int high = process_index_hex_value(value[i]);
        int low = process_index_hex_value(value[i + 1]);
        if (high < 0 || low < 0) return false;
        out[output++] = (char)((high << 4) | low);
    }
    out[output] = '\0';
    return true;
}

static bool process_index_write_hex(FILE* file, const char* value) {
    static const char digits[] = "0123456789abcdef";
    if (!value[0]) return fputc('-', file) != EOF;
    for (const unsigned char* cursor = (const unsigned char*)value; *cursor; cursor++) {
        if (fputc(digits[*cursor >> 4], file) == EOF ||
            fputc(digits[*cursor & 0x0f], file) == EOF) {
            return false;
        }
    }
    return true;
}

static void process_index_entries_release(ProcessIndexEntry* entries) {
    free(entries);
}

static bool process_index_load(ProcessIndexEntry** entries, size_t* count) {
    *entries = NULL;
    *count = 0;
    FILE* file = fopen(PROCESS_CACHE_FILE, "r");
    if (!file) return false;

    char line[1024];
    if (!fgets(line, sizeof(line), file)) {
        fclose(file);
        return false;
    }
    char* save = NULL;
    char* magic = strtok_r(line, "\t\r\n", &save);
    char* stored_boot_id = strtok_r(NULL, "\t\r\n", &save);
    char current_boot_id[129] = {0};
    if (!magic || strcmp(magic, PROCESS_INDEX_MAGIC) != 0 || !stored_boot_id ||
        !process_index_boot_id(current_boot_id, sizeof(current_boot_id)) ||
        strcmp(stored_boot_id, current_boot_id) != 0) {
        fclose(file);
        return false;
    }

    size_t capacity = 0;
    while (fgets(line, sizeof(line), file)) {
        save = NULL;
        char* pid_text = strtok_r(line, "\t\r\n", &save);
        char* starttime_text = strtok_r(NULL, "\t\r\n", &save);
        char* first_seen_text = strtok_r(NULL, "\t\r\n", &save);
        char* comm_hex = strtok_r(NULL, "\t\r\n", &save);
        char* cmdline_hex = strtok_r(NULL, "\t\r\n", &save);
        if (!pid_text || !starttime_text || !first_seen_text ||
            !comm_hex || !cmdline_hex) {
            continue;
        }
        char* end = NULL;
        errno = 0;
        long pid_value = strtol(pid_text, &end, 10);
        if (errno == ERANGE || !end || *end != '\0' ||
            pid_value <= 0 || pid_value > INT_MAX) {
            continue;
        }
        errno = 0;
        unsigned long long starttime = strtoull(starttime_text, &end, 10);
        if (errno == ERANGE || !end || *end != '\0') continue;
        errno = 0;
        unsigned long long first_seen = strtoull(first_seen_text, &end, 10);
        if (errno == ERANGE || !end || *end != '\0') continue;

        if (*count >= capacity) {
            size_t new_capacity = capacity ? capacity * 2 : 256;
            ProcessIndexEntry* resized = realloc(
                *entries, new_capacity * sizeof(*resized));
            if (!resized) {
                process_index_entries_release(*entries);
                *entries = NULL;
                *count = 0;
                fclose(file);
                return false;
            }
            *entries = resized;
            capacity = new_capacity;
        }
        ProcessIndexEntry* entry = &(*entries)[*count];
        memset(entry, 0, sizeof(*entry));
        entry->pid = (pid_t)pid_value;
        entry->starttime = starttime;
        entry->first_seen_elapsed_ms = first_seen;
        if (!process_index_hex_decode(comm_hex, entry->comm, sizeof(entry->comm)) ||
            !process_index_hex_decode(cmdline_hex, entry->cmdline, sizeof(entry->cmdline))) {
            continue;
        }
        (*count)++;
    }
    if (*count > 1) {
        qsort(*entries, *count, sizeof(**entries), process_index_compare_entry_pid);
    }
    fclose(file);
    return true;
}

static bool process_index_write(
    const ProcessIndexEntry* entries,
    size_t count,
    unsigned long long now_elapsed
) {
    char boot_id[129] = {0};
    if (!process_index_boot_id(boot_id, sizeof(boot_id))) return false;
    char temporary[PATH_MAX];
    snprintf(temporary, sizeof(temporary), "%s.%d.tmp", PROCESS_CACHE_FILE, getpid());
    FILE* file = fopen(temporary, "w");
    if (!file) return false;
    bool ok = fprintf(
        file, "%s\t%s\t%llu\n", PROCESS_INDEX_MAGIC, boot_id, now_elapsed) >= 0;
    for (size_t i = 0; ok && i < count; i++) {
        const ProcessIndexEntry* entry = &entries[i];
        ok = fprintf(
            file, "%d\t%llu\t%llu\t",
            entry->pid, entry->starttime, entry->first_seen_elapsed_ms) >= 0 &&
            process_index_write_hex(file, entry->comm) &&
            fputc('\t', file) != EOF &&
            process_index_write_hex(file, entry->cmdline) &&
            fputc('\n', file) != EOF;
    }
    if (fclose(file) != 0) ok = false;
    if (ok) {
        chmod(temporary, 0644);
        if (rename(temporary, PROCESS_CACHE_FILE) != 0) ok = false;
    }
    if (!ok) unlink(temporary);
    return ok;
}

static bool process_index_enumerate_pids(pid_t** pids, size_t* count) {
    *pids = NULL;
    *count = 0;
    size_t capacity = 0;
    DIR* proc_dir = opendir("/proc");
    if (!proc_dir) return false;
    bool complete = true;
    while (true) {
        errno = 0;
        struct dirent* ent = readdir(proc_dir);
        if (!ent) {
            if (errno != 0) complete = false;
            break;
        }
        char* end = NULL;
        errno = 0;
        long value = strtol(ent->d_name, &end, 10);
        if (errno == ERANGE || !end || *end != '\0' ||
            value <= 0 || value > INT_MAX) {
            continue;
        }
        if (*count >= capacity) {
            size_t new_capacity = capacity ? capacity * 2 : 256;
            pid_t* resized = realloc(*pids, new_capacity * sizeof(*resized));
            if (!resized) {
                complete = false;
                break;
            }
            *pids = resized;
            capacity = new_capacity;
        }
        (*pids)[(*count)++] = (pid_t)value;
    }
    closedir(proc_dir);
    if (!complete) {
        free(*pids);
        *pids = NULL;
        *count = 0;
        return false;
    }
    qsort(*pids, *count, sizeof(**pids), process_index_compare_pid);
    return true;
}

static ssize_t process_index_entry_index(
    const ProcessIndexEntry* entries,
    size_t count,
    pid_t pid
) {
    size_t left = 0;
    size_t right = count;
    while (left < right) {
        size_t middle = left + (right - left) / 2;
        if (entries[middle].pid == pid) return (ssize_t)middle;
        if (entries[middle].pid < pid) left = middle + 1;
        else right = middle;
    }
    return -1;
}

static bool process_index_read_entry(
    pid_t pid,
    unsigned long long now_elapsed,
    const ProcessIndexEntry* old,
    ProcessIndexEntry* entry
) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    int pid_fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (pid_fd == -1) return false;
    unsigned long long starttime = 0;
    if (read_proc_stat_at(pid_fd, NULL, 0, &starttime) != PROC_FILE_OK) {
        close(pid_fd);
        return false;
    }
    memset(entry, 0, sizeof(*entry));
    entry->pid = pid;
    entry->starttime = starttime;
    entry->first_seen_elapsed_ms = old && old->starttime == starttime ?
        old->first_seen_elapsed_ms : now_elapsed;
    (void)read_proc_file_at(pid_fd, "comm", entry->comm, sizeof(entry->comm));
    (void)read_proc_file_at(pid_fd, "cmdline", entry->cmdline, sizeof(entry->cmdline));
    char* newline = strpbrk(entry->comm, "\r\n");
    if (newline) *newline = '\0';
    close(pid_fd);
    return true;
}

static bool process_index_read_starttime(pid_t pid, unsigned long long* starttime) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    int pid_fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (pid_fd == -1) return false;
    bool ok = read_proc_stat_at(pid_fd, NULL, 0, starttime) == PROC_FILE_OK;
    close(pid_fd);
    return ok;
}

static bool process_index_build_candidates(
    const ProcessIndexEntry* entries,
    size_t count,
    unsigned long long now_elapsed,
    pid_t** candidates,
    size_t* candidate_count
) {
    *candidates = NULL;
    *candidate_count = 0;
    if (count == 0) return true;
    pid_t* result = malloc(count * sizeof(*result));
    if (!result) return false;
    for (size_t i = 0; i < count; i++) {
        const ProcessIndexEntry* entry = &entries[i];
        if (now_elapsed >= entry->first_seen_elapsed_ms &&
            now_elapsed - entry->first_seen_elapsed_ms <= PID_DISCOVERY_RETRY_MS) {
            result[(*candidate_count)++] = entry->pid;
        }
    }
    if (*candidate_count == 0) {
        free(result);
        result = NULL;
    }
    *candidates = result;
    return true;
}

static bool process_index_prepare(
    unsigned long long now_elapsed,
    bool refresh_due,
    bool rebuild_all,
    ProcessIndexView* view
) {
    memset(view, 0, sizeof(*view));
    ProcessIndexEntry* old_entries = NULL;
    size_t old_count = 0;
    bool loaded = process_index_load(&old_entries, &old_count);
    if (!refresh_due && loaded) {
        view->loaded = true;
        if (old_count > 0) {
            view->current_pids = malloc(old_count * sizeof(*view->current_pids));
            if (!view->current_pids) {
                process_index_entries_release(old_entries);
                return false;
            }
            for (size_t i = 0; i < old_count; i++) {
                view->current_pids[i] = old_entries[i].pid;
            }
            view->num_current_pids = old_count;
        }
        bool ok = process_index_build_candidates(
            old_entries, old_count, now_elapsed,
            &view->candidate_pids, &view->num_candidate_pids);
        process_index_entries_release(old_entries);
        return ok;
    }

    pid_t* current_pids = NULL;
    size_t current_count = 0;
    if (!process_index_enumerate_pids(&current_pids, &current_count)) {
        process_index_entries_release(old_entries);
        return false;
    }
    ProcessIndexEntry* entries = current_count ?
        calloc(current_count, sizeof(*entries)) : NULL;
    if (current_count > 0 && !entries) {
        free(current_pids);
        process_index_entries_release(old_entries);
        return false;
    }
    size_t entry_count = 0;
    for (size_t i = 0; i < current_count; i++) {
        pid_t pid = current_pids[i];
        ssize_t old_index = loaded ?
            process_index_entry_index(old_entries, old_count, pid) : -1;
        const ProcessIndexEntry* old = old_index >= 0 ? &old_entries[old_index] : NULL;
        bool refresh_entry = rebuild_all || !old ||
            (now_elapsed >= old->first_seen_elapsed_ms &&
             now_elapsed - old->first_seen_elapsed_ms <= PID_DISCOVERY_RETRY_MS);
        if (refresh_entry) {
            if (!process_index_read_entry(
                    pid, now_elapsed, old, &entries[entry_count])) {
                continue;
            }
        } else {
            unsigned long long current_starttime = 0;
            if (!process_index_read_starttime(pid, &current_starttime)) continue;
            if (current_starttime == old->starttime) {
                entries[entry_count] = *old;
            } else if (!process_index_read_entry(
                           pid, now_elapsed, old, &entries[entry_count])) {
                continue;
            }
        }
        entry_count++;
    }
    view->added = 0;
    view->exited = 0;
    for (size_t i = 0; i < current_count; i++) {
        if (!loaded || process_index_entry_index(old_entries, old_count, current_pids[i]) < 0) {
            view->added++;
        }
    }
    if (loaded) {
        for (size_t i = 0; i < old_count; i++) {
            if (!bsearch(
                    &old_entries[i].pid, current_pids, current_count,
                    sizeof(*current_pids), process_index_compare_pid)) {
                view->exited++;
            }
        }
    }
    bool changed = !loaded || view->added > 0 || view->exited > 0;
    if (!changed) {
        for (size_t i = 0; i < entry_count; i++) {
            ssize_t old_index = process_index_entry_index(
                old_entries, old_count, entries[i].pid);
            if (old_index < 0 ||
                memcmp(&entries[i], &old_entries[old_index], sizeof(entries[i])) != 0) {
                changed = true;
                break;
            }
        }
    }
    if (changed && !process_index_write(entries, entry_count, now_elapsed)) {
        free(entries);
        free(current_pids);
        process_index_entries_release(old_entries);
        return false;
    }
    view->current_pids = current_pids;
    view->num_current_pids = current_count;
    view->refreshed = true;
    view->loaded = true;
    bool candidates_ok = process_index_build_candidates(
        entries, entry_count, now_elapsed,
        &view->candidate_pids, &view->num_candidate_pids);
    free(entries);
    process_index_entries_release(old_entries);
    return candidates_ok;
}

static void process_index_view_release(ProcessIndexView* view) {
    if (!view) return;
    free(view->current_pids);
    free(view->candidate_pids);
    memset(view, 0, sizeof(*view));
}

static bool process_index_mark_candidate(pid_t pid, unsigned long long now_elapsed) {
    ProcessIndexEntry* entries = NULL;
    size_t count = 0;
    if (!process_index_load(&entries, &count)) return false;
    ssize_t index = process_index_entry_index(entries, count, pid);
    if (index >= 0) {
        entries[index].first_seen_elapsed_ms = now_elapsed;
    } else {
        ProcessIndexEntry entry;
        if (!process_index_read_entry(pid, now_elapsed, NULL, &entry)) {
            process_index_entries_release(entries);
            return false;
        }
        ProcessIndexEntry* resized = realloc(entries, (count + 1) * sizeof(*resized));
        if (!resized) {
            process_index_entries_release(entries);
            return false;
        }
        entries = resized;
        entries[count++] = entry;
    }
    bool ok = process_index_write(entries, count, now_elapsed);
    process_index_entries_release(entries);
    return ok;
}

static bool process_index_name_matches(const ProcessIndexEntry* entry, const char* name) {
    if (strcmp(entry->comm, name) == 0 || strcmp(entry->cmdline, name) == 0) return true;
    const char* base = strrchr(entry->cmdline, '/');
    return base && strcmp(base + 1, name) == 0;
}

static bool process_index_current_name_matches(
    const ProcessIndexEntry* entry,
    const char* name
) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", entry->pid);
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd == -1) return false;
    unsigned long long starttime = 0;
    bool valid = read_starttime_at(fd, &starttime) && starttime == entry->starttime;
    ProcessIndexEntry current;
    memset(&current, 0, sizeof(current));
    if (valid) {
        (void)read_proc_file_at(fd, "comm", current.comm, sizeof(current.comm));
        (void)read_proc_file_at(fd, "cmdline", current.cmdline, sizeof(current.cmdline));
        char* newline = strpbrk(current.comm, "\r\n");
        if (newline) *newline = '\0';
        valid = process_index_name_matches(&current, name);
    }
    close(fd);
    return valid;
}

static int process_index_print_pids(const char* name) {
    ProcessIndexEntry* entries = NULL;
    size_t count = 0;
    if (!name || !name[0] || !process_index_load(&entries, &count)) return 1;
    bool found = false;
    for (size_t i = 0; i < count; i++) {
        if (process_index_name_matches(&entries[i], name) &&
            process_index_current_name_matches(&entries[i], name)) {
            printf("%d\n", entries[i].pid);
            found = true;
        }
    }
    process_index_entries_release(entries);
    return found ? 0 : 1;
}

static int process_index_print_names(int name_count, char* const names[]) {
    ProcessIndexEntry* entries = NULL;
    size_t count = 0;
    if (name_count <= 0 || !process_index_load(&entries, &count)) return 1;
    bool* found = calloc((size_t)name_count, sizeof(*found));
    if (!found) {
        process_index_entries_release(entries);
        return 1;
    }
    for (int name_index = 0; name_index < name_count; name_index++) {
        const char* name = names[name_index];
        for (size_t i = 0; i < count && !found[name_index]; i++) {
            found[name_index] = process_index_name_matches(&entries[i], name) &&
                process_index_current_name_matches(&entries[i], name);
        }
    }

    bool all_found = true;
    for (int name_index = 0; name_index < name_count; name_index++) {
        if (!found[name_index]) {
            all_found = false;
            break;
        }
    }
    if (!all_found) {
        pid_t* current_pids = NULL;
        size_t current_count = 0;
        if (!process_index_enumerate_pids(&current_pids, &current_count)) {
            free(found);
            process_index_entries_release(entries);
            return 1;
        }
        for (size_t pid_index = 0; pid_index < current_count && !all_found; pid_index++) {
            pid_t pid = current_pids[pid_index];
            if (process_index_entry_index(entries, count, pid) >= 0) continue;
            ProcessIndexEntry current;
            if (!process_index_read_entry(pid, 0, NULL, &current)) continue;
            all_found = true;
            for (int name_index = 0; name_index < name_count; name_index++) {
                if (!found[name_index] &&
                    process_index_name_matches(&current, names[name_index])) {
                    found[name_index] = true;
                }
                if (!found[name_index]) all_found = false;
            }
        }
        free(current_pids);
    }

    for (int name_index = 0; name_index < name_count; name_index++) {
        if (found[name_index]) printf("%s\n", names[name_index]);
    }
    free(found);
    process_index_entries_release(entries);
    /* 缓存读取成功时，未命中表示名称当前不存在，不再触发 App 全量遍历 /proc。 */
    return 0;
}
