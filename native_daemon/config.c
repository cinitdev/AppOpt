typedef struct {
    char* owner;
    char* thread;
    char* cpus;
} ParsedConfigRule;

static void free_parsed_config_rules(ParsedConfigRule* rules, size_t count) {
    if (!rules) return;
    for (size_t i = 0; i < count; i++) {
        free(rules[i].owner);
        free(rules[i].thread);
        free(rules[i].cpus);
    }
    free(rules);
}

static int add_parsed_config_rule(
    ParsedConfigRule** rules,
    size_t* count,
    const char* owner,
    const char* thread,
    const char* cpus
) {
    ParsedConfigRule* resized = realloc(*rules, (*count + 1) * sizeof(**rules));
    if (!resized) return -1;
    *rules = resized;
    ParsedConfigRule* rule = &resized[*count];
    memset(rule, 0, sizeof(*rule));
    rule->owner = strdup(owner);
    rule->thread = strdup(thread ? thread : "");
    rule->cpus = strdup(cpus);
    if (!rule->owner || !rule->thread || !rule->cpus) {
        free(rule->owner);
        free(rule->thread);
        free(rule->cpus);
        memset(rule, 0, sizeof(*rule));
        return -1;
    }
    (*count)++;
    return 0;
}

static int add_unique_config_name(char*** names, size_t* count, const char* name) {
    for (size_t i = 0; i < *count; i++) {
        if (strcmp((*names)[i], name) == 0) return 0;
    }
    char** resized = realloc(*names, (*count + 1) * sizeof(**names));
    if (!resized) return -1;
    *names = resized;
    resized[*count] = strdup(name);
    if (!resized[*count]) return -1;
    (*count)++;
    return 0;
}

static void remove_config_name(char*** names, size_t* count, const char* name) {
    if (!names || !*names || !count || !name) return;
    for (size_t i = 0; i < *count; i++) {
        if (strcmp((*names)[i], name) != 0) continue;
        free((*names)[i]);
        if (i + 1 < *count) {
            memmove(&(*names)[i], &(*names)[i + 1],
                    (*count - i - 1) * sizeof(**names));
        }
        (*count)--;
        if (*count == 0) {
            free(*names);
            *names = NULL;
        }
        return;
    }
}

/* 覆盖核心数量优先；数量相同时优先更高核心，再优先覆盖更低核心。 */
static int compare_config_cpu_sets(const cpu_set_t* left, const cpu_set_t* right) {
    int left_count = CPU_COUNT(left);
    int right_count = CPU_COUNT(right);
    if (left_count != right_count) return left_count > right_count ? 1 : -1;

    int left_high = -1, right_high = -1;
    int left_low = CPU_SETSIZE, right_low = CPU_SETSIZE;
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (CPU_ISSET(cpu, left)) {
            if (left_low == CPU_SETSIZE) left_low = cpu;
            left_high = cpu;
        }
        if (CPU_ISSET(cpu, right)) {
            if (right_low == CPU_SETSIZE) right_low = cpu;
            right_high = cpu;
        }
    }
    if (left_high != right_high) return left_high > right_high ? 1 : -1;
    if (left_low != right_low) return left_low < right_low ? 1 : -1;
    return 0;
}

static bool parsed_config_rule_valid(const ParsedConfigRule* rule) {
    if (!rule || !rule->owner || !rule->thread || !rule->cpus ||
        !rule->owner[0] || strlen(rule->owner) >= MAX_PKG_LEN ||
        strlen(rule->thread) >= MAX_THREAD_LEN) {
        return false;
    }
    if (!rule->thread[0] && strcasecmp(rule->cpus, "auto") == 0) return true;
    cpu_set_t set;
    CPU_ZERO(&set);
    return parse_cpu_ranges_strict(rule->cpus, &set, NULL);
}

/* 返回 0 表示成功，1 表示规则无效，-1 表示内存不足。 */
static int append_config_rule(
    AppConfig* cfg,
    AffinityRule** rules,
    size_t* rules_count,
    char*** packages,
    size_t* package_count,
    char*** auto_packages,
    size_t* auto_count,
    const ParsedConfigRule* parsed
) {
    if (!parsed_config_rule_valid(parsed)) return 1;

    if (!parsed->thread[0] && strcasecmp(parsed->cpus, "auto") == 0) {
        for (size_t i = 0; i < *rules_count; i++) {
            if (strcmp((*rules)[i].pkg, parsed->owner) == 0 &&
                !(*rules)[i].thread[0]) {
                return 0;
            }
        }
        if (add_unique_config_name(auto_packages, auto_count, parsed->owner) < 0 ||
            add_unique_config_name(packages, package_count, parsed->owner) < 0) {
            return -1;
        }
        return 0;
    }

    cpu_set_t set;
    CPU_ZERO(&set);
    if (!parse_cpu_ranges_strict(parsed->cpus, &set, NULL)) return 1;
    if (!parsed->thread[0]) {
        remove_config_name(auto_packages, auto_count, parsed->owner);
    }

    ssize_t duplicate = -1;
    for (size_t i = 0; i < *rules_count; i++) {
        if (strcmp((*rules)[i].pkg, parsed->owner) == 0 &&
            strcmp((*rules)[i].thread, parsed->thread) == 0) {
            duplicate = (ssize_t)i;
            break;
        }
    }
    if (duplicate >= 0 &&
        compare_config_cpu_sets(&set, &(*rules)[duplicate].cpus) <= 0) {
        return add_unique_config_name(packages, package_count, parsed->owner) < 0 ? -1 : 0;
    }

    cpu_set_t effective_set;
    CPU_AND(&effective_set, &set, &cfg->topo.present_cpus);
    char* dir_name = CPU_COUNT(&effective_set) > 0 ? cpu_set_to_str(&effective_set) : NULL;
    if (CPU_COUNT(&effective_set) > 0 && !dir_name) return -1;

    AffinityRule rule = {0};
    build_str(rule.pkg, sizeof(rule.pkg), parsed->owner, NULL);
    build_str(rule.thread, sizeof(rule.thread), parsed->thread, NULL);
    if (cfg->topo.cpuset_enabled && dir_name) {
        char path[256];
        build_str(path, sizeof(path), BASE_CPUSET, "/", dir_name, NULL);
        if (create_cpuset_dir(path, dir_name, cfg->topo.mems_str)) {
            build_str(rule.cpuset_dir, sizeof(rule.cpuset_dir), dir_name, NULL);
        } else {
            printf("cpuset 创建失败，规则降级为仅 sched_setaffinity: %s{%s}=%s\n",
                   parsed->owner, parsed->thread, dir_name);
            cfg->topo.cpuset_enabled = false;
        }
    }
    rule.cpus = set;
    free(dir_name);

    if (duplicate >= 0) {
        memcpy(&(*rules)[duplicate], &rule, sizeof(rule));
    } else {
        AffinityRule* resized = realloc(*rules, (*rules_count + 1) * sizeof(**rules));
        if (!resized) return -1;
        *rules = resized;
        memcpy(&resized[*rules_count], &rule, sizeof(rule));
        (*rules_count)++;
    }
    return add_unique_config_name(packages, package_count, parsed->owner) < 0 ? -1 : 0;
}

static bool parse_legacy_config_rule(char* line, ParsedConfigRule* parsed) {
    char* eq = strchr(line, '=');
    if (!eq) return false;
    *eq++ = '\0';

    char* thread = "";
    char* open = strchr(line, '{');
    if (open) {
        *open++ = '\0';
        char* close = strchr(open, '}');
        if (!close) return false;
        *close = '\0';
        if (strchr(open, '{') || strtrim(close + 1)[0]) return false;
        thread = strtrim(open);
        if (!thread[0]) return false;
    } else if (strchr(line, '}')) {
        return false;
    }

    parsed->owner = strtrim(line);
    parsed->thread = thread;
    parsed->cpus = strtrim(eq);
    return parsed->owner[0] && parsed->cpus[0];
}

static bool config_block_header(
    char* line,
    char** owner,
    char** fallback_cpus
) {
    char* open = strchr(line, '{');
    if (!open || open == line || strtrim(open + 1)[0]) return false;
    *open = '\0';
    char* prefix = strtrim(line);
    if (!prefix[0] || strchr(prefix, '{') || strchr(prefix, '}')) return false;

    char* eq = strchr(prefix, '=');
    if (eq) {
        *eq++ = '\0';
        *fallback_cpus = strtrim(eq);
    } else {
        *fallback_cpus = NULL;
    }
    *owner = strtrim(prefix);
    return true;
}

/* 返回 1 表示结束行，0 表示普通内容，-1 表示形似结束行但格式错误。 */
static int config_block_close(char* line, char** fallback_cpus) {
    if (line[0] != '}') return 0;
    char* tail = strtrim(line + 1);
    if (!tail[0]) {
        *fallback_cpus = NULL;
        return 1;
    }
    if (*tail != '=') return -1;
    tail = strtrim(tail + 1);
    if (!tail[0] || strchr(tail, '=')) return -1;
    *fallback_cpus = tail;
    return 1;
}

static int add_config_block_body_rule(
    ParsedConfigRule** rules,
    size_t* count,
    const char* block_owner,
    char* line
) {
    char* eq = strchr(line, '=');
    if (!eq) return 1;
    *eq++ = '\0';
    char* name = strtrim(line);
    char* cpus = strtrim(eq);
    if (!name[0] || !cpus[0] || strchr(name, '=') || strchr(name, '{') || strchr(name, '}')) {
        return 1;
    }

    char child_owner[MAX_PKG_LEN * 2];
    const char* owner = block_owner;
    const char* thread = name;
    size_t owner_len = strlen(block_owner);
    if (name[0] == ':' && name[1]) {
        if (snprintf(child_owner, sizeof(child_owner), "%s%s", block_owner, name) >=
            (int)sizeof(child_owner)) return 1;
        owner = child_owner;
        thread = "";
    } else if (strncmp(name, block_owner, owner_len) == 0 && name[owner_len] == ':' && name[owner_len + 1]) {
        owner = name;
        thread = "";
    }
    return add_parsed_config_rule(rules, count, owner, thread, cpus) < 0 ? -1 : 0;
}

typedef enum {
    CONFIG_BLOCK_STANDARD = 0,
    CONFIG_BLOCK_TAGGED,
    CONFIG_BLOCK_NATURAL,
    CONFIG_BLOCK_NESTED,
    CONFIG_BLOCK_FUNCTION,
    CONFIG_BLOCK_YAML
} ConfigBlockKind;

/* 返回 1 表示有效自定义头，0 表示不是自定义头，-1 表示自定义头格式错误。 */
static int config_custom_block_header(
    char* line,
    char** owner,
    char** fallback_cpus,
    ConfigBlockKind* kind
) {
    size_t len = strlen(line);
    if (len > 1 && line[len - 1] == ':' && !strchr(line, '=') && !strchr(line, ' ')) {
        line[len - 1] = '\0';
        *owner = strtrim(line);
        *fallback_cpus = NULL;
        *kind = CONFIG_BLOCK_YAML;
        return (*owner)[0] != '\0' && strcmp(*owner, "threads") != 0 &&
            strcmp(*owner, "processes") != 0 ? 1 : -1;
    }
    if (len < 2 || line[len - 1] != '{') return 0;
    line[len - 1] = '\0';
    char* prefix = strtrim(line);
    if (!prefix[0]) return 0;

    if (strcmp(prefix, "app") == 0 || strncmp(prefix, "app ", 4) == 0) {
        char* save = NULL;
        char* value = strcmp(prefix, "app") == 0 ? prefix + 3 : strtrim(prefix + 4);
        char* pkg = strtok_r(value, " \t", &save);
        char* keyword = strtok_r(NULL, " \t", &save);
        char* cpus = strtok_r(NULL, " \t", &save);
        char* extra = strtok_r(NULL, " \t", &save);
        *owner = pkg ? pkg : "__invalid__";
        *fallback_cpus = NULL;
        *kind = CONFIG_BLOCK_NATURAL;
        if (!pkg || extra || (keyword && (!cpus || strcmp(keyword, "fallback") != 0))) return -1;
        *fallback_cpus = cpus;
        return 1;
    }

    if (strncmp(prefix, "app(", 4) == 0) {
        char* close = strrchr(prefix + 4, ')');
        *owner = "__invalid__";
        *fallback_cpus = NULL;
        *kind = CONFIG_BLOCK_FUNCTION;
        if (!close || strtrim(close + 1)[0]) return -1;
        *close = '\0';
        char* args = strtrim(prefix + 4);
        char* comma = strchr(args, ',');
        if (comma) {
            if (strchr(comma + 1, ',')) return -1;
            *comma++ = '\0';
            *fallback_cpus = strtrim(comma);
        }
        *owner = strtrim(args);
        return (*owner)[0] && (!*fallback_cpus || (*fallback_cpus)[0]) ? 1 : -1;
    }

    if (prefix[strlen(prefix) - 1] == '=') {
        prefix[strlen(prefix) - 1] = '\0';
        *owner = strtrim(prefix);
        *fallback_cpus = NULL;
        *kind = CONFIG_BLOCK_TAGGED;
        return (*owner)[0] != '\0' && !strchr(*owner, '=') ? 1 : -1;
    }
    return 0;
}

static int add_typed_config_rule(
    ParsedConfigRule** rules,
    size_t* count,
    const char* owner,
    const char* name,
    const char* cpus,
    bool process
) {
    if (!name || !name[0] || !cpus || !cpus[0] ||
        strchr(name, '{') || strchr(name, '}') || strchr(name, '=')) return 1;
    if (!process) return add_parsed_config_rule(rules, count, owner, name, cpus) < 0 ? -1 : 0;

    char child_owner[MAX_PKG_LEN * 2];
    size_t owner_len = strlen(owner);
    if (strncmp(name, owner, owner_len) == 0 && name[owner_len] == ':' && name[owner_len + 1]) {
        return add_parsed_config_rule(rules, count, name, "", cpus) < 0 ? -1 : 0;
    }
    const char* suffix = name[0] == ':' ? name : NULL;
    int written = suffix ?
        snprintf(child_owner, sizeof(child_owner), "%s%s", owner, suffix) :
        snprintf(child_owner, sizeof(child_owner), "%s:%s", owner, name);
    if (written < 0 || written >= (int)sizeof(child_owner)) return 1;
    return add_parsed_config_rule(rules, count, child_owner, "", cpus) < 0 ? -1 : 0;
}

static int config_custom_block_body_rule(
    ParsedConfigRule** rules,
    size_t* count,
    const char* owner,
    char* line,
    ConfigBlockKind* kind,
    int* section,
    char** fallback,
    size_t indent
) {
    if (*kind == CONFIG_BLOCK_TAGGED &&
        (strcmp(line, "threads {") == 0 || strcmp(line, "processes {") == 0)) {
        if (*count > 0) return 1;
        *kind = CONFIG_BLOCK_NESTED;
    }
    if (*kind == CONFIG_BLOCK_NESTED) {
        if (strcmp(line, "threads {") == 0) {
            if (*section) return 1;
            *section = 1;
            return 0;
        }
        if (strcmp(line, "processes {") == 0) {
            if (*section) return 1;
            *section = 2;
            return 0;
        }
        if (line[0] == '}' && *section) {
            *section = 0;
            return strcmp(line, "}") == 0 ? 0 : 1;
        }
    }
    if (*kind == CONFIG_BLOCK_YAML) {
        if (strcmp(line, "threads:") == 0) {
            if (indent != 4) return 1;
            *section = 1;
            return 0;
        }
        if (strcmp(line, "processes:") == 0) {
            if (indent != 4) return 1;
            *section = 2;
            return 0;
        }
    }

    if (*kind == CONFIG_BLOCK_FUNCTION) {
        bool process = false;
        char* args = NULL;
        if (strncmp(line, "thread(", 7) == 0) args = line + 7;
        else if (strncmp(line, "process(", 8) == 0) {
            args = line + 8;
            process = true;
        } else return 1;
        char* close = strrchr(args, ')');
        if (!close || strtrim(close + 1)[0]) return 1;
        *close = '\0';
        char* comma = strrchr(args, ',');
        if (!comma) return 1;
        *comma++ = '\0';
        return add_typed_config_rule(rules, count, owner, strtrim(args), strtrim(comma), process);
    }

    char* separator = *kind == CONFIG_BLOCK_YAML ? strrchr(line, ':') : strchr(line, '=');
    if (!separator) return 1;
    *separator++ = '\0';
    char* name = strtrim(line);
    char* cpus = strtrim(separator);
    if (!name[0] || !cpus[0]) return 1;
    bool outer_fallback =
        (*kind == CONFIG_BLOCK_TAGGED && strcmp(name, "fallback") == 0) ||
        (*kind == CONFIG_BLOCK_NESTED && *section == 0 && strcmp(name, "fallback") == 0) ||
        (*kind == CONFIG_BLOCK_YAML && indent == 4 && strcmp(name, "fallback") == 0);
    if (outer_fallback) {
        if (*fallback) return 1;
        *fallback = strdup(cpus);
        return *fallback ? 0 : -1;
    }

    if (*kind == CONFIG_BLOCK_TAGGED) {
        if (strncmp(name, "thread:", 7) == 0) {
            return add_typed_config_rule(rules, count, owner, name + 7, cpus, false);
        }
        if (strncmp(name, "process:", 8) == 0) {
            return add_typed_config_rule(rules, count, owner, name + 8, cpus, true);
        }
        return 1;
    }
    if (*kind == CONFIG_BLOCK_NATURAL) {
        if (strncmp(name, "thread ", 7) == 0) {
            return add_typed_config_rule(rules, count, owner, strtrim(name + 7), cpus, false);
        }
        if (strncmp(name, "process ", 8) == 0) {
            return add_typed_config_rule(rules, count, owner, strtrim(name + 8), cpus, true);
        }
        return 1;
    }
    if (*kind == CONFIG_BLOCK_NESTED || *kind == CONFIG_BLOCK_YAML) {
        if (*kind == CONFIG_BLOCK_YAML && indent != 8) return 1;
        if (*section == 1) return add_typed_config_rule(rules, count, owner, name, cpus, false);
        if (*section == 2) return add_typed_config_rule(rules, count, owner, name, cpus, true);
        return 1;
    }
    return 1;
}

static int commit_parsed_config_block(
    AppConfig* cfg,
    AffinityRule** rules,
    size_t* rules_count,
    char*** packages,
    size_t* package_count,
    char*** auto_packages,
    size_t* auto_count,
    ParsedConfigRule** block_rules,
    size_t* block_rule_count,
    const char* owner,
    const char* fallback,
    bool valid
) {
    if (valid && fallback &&
        add_parsed_config_rule(block_rules, block_rule_count, owner, "", fallback) < 0) return -1;
    if (valid) {
        for (size_t i = 0; i < *block_rule_count; i++) {
            if (!parsed_config_rule_valid(&(*block_rules)[i])) return 0;
        }
        for (size_t i = 0; i < *block_rule_count; i++) {
            int result = append_config_rule(cfg, rules, rules_count, packages, package_count,
                                            auto_packages, auto_count, &(*block_rules)[i]);
            if (result < 0) return -1;
        }
    }
    return 0;
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
    ParsedConfigRule* block_rules = NULL;
    size_t block_rule_count = 0;
    char* block_owner = NULL;
    char* block_fallback = NULL;
    bool block_valid = true;
    ConfigBlockKind block_kind = CONFIG_BLOCK_STANDARD;
    int block_section = 0;

    while (getline(&line, &line_cap, fp) != -1) {
        size_t indent = 0;
        while (line[indent] == ' ' || line[indent] == '\t') indent++;
        char* p = strtrim(line);
        char* comment = strstr(p, "//");
        if (comment) {
            *comment = '\0';
            p = strtrim(p);
        }

process_config_line:
        if (block_owner) {
            if (!p[0] || p[0] == '#') continue;
            bool finish = false;
            bool reprocess = false;
            char* tail_fallback = NULL;
            if (block_kind == CONFIG_BLOCK_YAML && indent == 0) {
                finish = true;
                reprocess = true;
            } else if (block_kind == CONFIG_BLOCK_STANDARD) {
                int close_result = config_block_close(p, &tail_fallback);
                if (close_result != 0) {
                    if (close_result < 0 || (block_fallback && tail_fallback)) block_valid = false;
                    finish = true;
                }
            } else if (block_kind != CONFIG_BLOCK_YAML && p[0] == '}' && block_section == 0) {
                if (strcmp(p, "}") != 0) block_valid = false;
                finish = true;
            }

            if (finish) {
                const char* fallback = block_fallback ? block_fallback : tail_fallback;
                int commit = commit_parsed_config_block(
                    cfg, &new_rules, &rules_cnt, &new_pkgs, &pkgs_cnt,
                    &new_auto, &auto_cnt, &block_rules, &block_rule_count,
                    block_owner, fallback, block_valid);
                if (commit < 0) goto error;
                free_parsed_config_rules(block_rules, block_rule_count);
                block_rules = NULL;
                block_rule_count = 0;
                free(block_owner);
                free(block_fallback);
                block_owner = NULL;
                block_fallback = NULL;
                block_valid = true;
                block_kind = CONFIG_BLOCK_STANDARD;
                block_section = 0;
                if (reprocess) goto process_config_line;
                continue;
            }

            int body_result = block_kind == CONFIG_BLOCK_STANDARD ?
                add_config_block_body_rule(&block_rules, &block_rule_count, block_owner, p) :
                config_custom_block_body_rule(&block_rules, &block_rule_count, block_owner, p,
                                              &block_kind, &block_section, &block_fallback, indent);
            if (body_result < 0) goto error;
            if (body_result > 0) block_valid = false;
            continue;
        }

        if (!p[0] || p[0] == '#') continue;
        char* header_owner = NULL;
        char* header_fallback = NULL;
        ConfigBlockKind header_kind = CONFIG_BLOCK_STANDARD;
        char* header_copy = strdup(p);
        if (!header_copy) goto error;
        int custom_header = config_custom_block_header(
            header_copy, &header_owner, &header_fallback, &header_kind);
        if (custom_header != 0) {
            block_owner = strdup(header_owner);
            block_fallback = header_fallback ? strdup(header_fallback) : NULL;
            free(header_copy);
            if (!block_owner || (header_fallback && !block_fallback)) goto error;
            block_valid = custom_header > 0 && block_owner[0] && strlen(block_owner) < MAX_PKG_LEN &&
                !strchr(block_owner, '=') &&
                (!block_fallback || (block_fallback[0] && !strchr(block_fallback, '=')));
            block_kind = header_kind;
            block_section = 0;
            continue;
        }
        free(header_copy);
        if (config_block_header(p, &header_owner, &header_fallback)) {
            block_owner = strdup(header_owner);
            block_fallback = header_fallback ? strdup(header_fallback) : NULL;
            if (!block_owner || (header_fallback && !block_fallback)) goto error;
            block_valid = block_owner[0] && strlen(block_owner) < MAX_PKG_LEN &&
                !strchr(block_owner, '=') &&
                (!block_fallback || (block_fallback[0] && !strchr(block_fallback, '=')));
            block_kind = CONFIG_BLOCK_STANDARD;
            block_section = 0;
            continue;
        }

        ParsedConfigRule parsed = {0};
        if (parse_legacy_config_rule(p, &parsed)) {
            int result = append_config_rule(
                cfg, &new_rules, &rules_cnt, &new_pkgs, &pkgs_cnt,
                &new_auto, &auto_cnt, &parsed);
            if (result < 0) goto error;
        }
    }

    if (block_owner && block_kind == CONFIG_BLOCK_YAML) {
        int commit = commit_parsed_config_block(
            cfg, &new_rules, &rules_cnt, &new_pkgs, &pkgs_cnt,
            &new_auto, &auto_cnt, &block_rules, &block_rule_count,
            block_owner, block_fallback, block_valid);
        if (commit < 0) goto error;
    }

    /* 未闭合区块中的内容全部丢弃，不把半块规则带入运行状态。 */
    free_parsed_config_rules(block_rules, block_rule_count);
    block_rules = NULL;
    block_rule_count = 0;
    free(block_owner);
    free(block_fallback);
    block_owner = NULL;
    block_fallback = NULL;

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
    free_parsed_config_rules(block_rules, block_rule_count);
    free(block_owner);
    free(block_fallback);
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

typedef enum {
    PROC_FILE_OK = 0,
    PROC_FILE_EMPTY,
    PROC_FILE_ERROR,
} ProcFileResult;

typedef enum {
    PROCESS_REFRESH_INCLUDED = 0,
    PROCESS_REFRESH_NOT_RELEVANT,
    PROCESS_REFRESH_READ_GAP,
    PROCESS_REFRESH_IDENTITY_LOST,
} ProcessRefreshResult;

static ProcFileResult read_proc_file_at(
    int dir_fd,
    const char* filename,
    char* buf,
    size_t buf_size
) {
    if (!filename || !buf || buf_size == 0) {
        errno = EINVAL;
        return PROC_FILE_ERROR;
    }
    int fd = openat(dir_fd, filename, O_RDONLY | O_CLOEXEC);
    if (fd == -1) return PROC_FILE_ERROR;

    ssize_t size;
    do {
        size = read(fd, buf, buf_size - 1);
    } while (size < 0 && errno == EINTR);
    int saved_errno = errno;
    close(fd);
    if (size < 0) {
        errno = saved_errno;
        return PROC_FILE_ERROR;
    }
    errno = 0;
    if (size == 0) {
        buf[0] = '\0';
        return PROC_FILE_EMPTY;
    }
    buf[size] = '\0';
    return PROC_FILE_OK;
}

static bool parse_proc_stat(
    const char* stat_line,
    char* comm,
    size_t comm_size,
    unsigned long long* starttime
) {
    if (!stat_line || !starttime) return false;
    const char* open = strchr(stat_line, '(');
    const char* close = strrchr(stat_line, ')');
    if (!open || !close || close <= open) return false;

    if (comm && comm_size > 0) {
        size_t length = (size_t)(close - open - 1);
        if (length >= comm_size) length = comm_size - 1;
        memcpy(comm, open + 1, length);
        comm[length] = '\0';
    }

    const char* cursor = close + 1;
    for (int field = 3; field <= 22; field++) {
        while (*cursor && isspace((unsigned char)*cursor)) cursor++;
        if (!*cursor) return false;
        const char* end = cursor;
        while (*end && !isspace((unsigned char)*end)) end++;
        if (field == 22) {
            char number[32];
            size_t len = (size_t)(end - cursor);
            if (len == 0 || len >= sizeof(number)) return false;
            memcpy(number, cursor, len);
            number[len] = '\0';
            char* parse_end = NULL;
            errno = 0;
            unsigned long long value = strtoull(number, &parse_end, 10);
            if (errno == ERANGE || !parse_end || *parse_end != '\0') return false;
            *starttime = value;
            return true;
        }
        cursor = end;
    }
    return false;
}

static ProcFileResult read_proc_stat_at(
    int dir_fd,
    char* comm,
    size_t comm_size,
    unsigned long long* starttime
) {
    char stat_line[1024];
    ProcFileResult result = read_proc_file_at(
        dir_fd, "stat", stat_line, sizeof(stat_line));
    if (result != PROC_FILE_OK) return result;
    if (!parse_proc_stat(stat_line, comm, comm_size, starttime)) {
        errno = EINVAL;
        return PROC_FILE_ERROR;
    }
    return PROC_FILE_OK;
}

static bool read_starttime_at(int dir_fd, unsigned long long* starttime) {
    return read_proc_stat_at(dir_fd, NULL, 0, starttime) == PROC_FILE_OK;
}

static void base_process_name(const char* name, char* out, size_t out_size) {
    if (!out || out_size == 0) return;
    out[0] = '\0';
    if (!name) return;
    const char* colon = strchr(name, ':');
    size_t len = colon ? (size_t)(colon - name) : strlen(name);
    if (len >= out_size) len = out_size - 1;
    memcpy(out, name, len);
    out[len] = '\0';
}

static void cpuset_dir_for_mask(
    const AppConfig* cfg,
    const cpu_set_t* cpus,
    char* out,
    size_t out_size
) {
    if (!out || out_size == 0) return;
    out[0] = '\0';
    if (!cfg || !cpus || !cfg->topo.cpuset_enabled || CPU_COUNT(cpus) == 0) return;

    cpu_set_t effective;
    CPU_AND(&effective, cpus, &cfg->topo.present_cpus);
    if (CPU_COUNT(&effective) == 0) return;
    char* dir_name = cpu_set_to_str(&effective);
    if (!dir_name) return;
    char path[256];
    build_str(path, sizeof(path), BASE_CPUSET, "/", dir_name, NULL);
    if (access(path, F_OK) == 0 ||
        create_cpuset_dir(path, dir_name, cfg->topo.mems_str)) {
        build_str(out, out_size, dir_name, NULL);
    }
    free(dir_name);
}

static bool ensure_process_capacity(ProcCache* cache, size_t needed) {
    if (needed <= cache->procs_cap) return true;
    size_t new_cap = cache->procs_cap ? cache->procs_cap : 64;
    while (new_cap < needed) {
        if (new_cap > SIZE_MAX / 2) {
            new_cap = needed;
            break;
        }
        new_cap *= 2;
    }
    ProcessInfo* resized = realloc(cache->procs, new_cap * sizeof(*resized));
    if (!resized) return false;
    memset(resized + cache->procs_cap, 0,
           (new_cap - cache->procs_cap) * sizeof(*resized));
    cache->procs = resized;
    cache->procs_cap = new_cap;
    return true;
}

static bool ensure_thread_capacity(ProcessInfo* proc, size_t needed) {
    if (needed <= proc->threads_cap) return true;
    size_t new_cap = proc->threads_cap ? proc->threads_cap : 64;
    while (new_cap < needed) {
        if (new_cap > SIZE_MAX / 2) {
            new_cap = needed;
            break;
        }
        new_cap *= 2;
    }
    ThreadInfo* resized = realloc(proc->threads, new_cap * sizeof(*resized));
    if (!resized) return false;
    proc->threads = resized;
    proc->threads_cap = new_cap;
    return true;
}

static bool ensure_thread_rule_capacity(ProcessInfo* proc, size_t needed) {
    if (needed <= proc->thread_rules_cap) return true;
    size_t new_cap = proc->thread_rules_cap ? proc->thread_rules_cap : 8;
    while (new_cap < needed) {
        if (new_cap > SIZE_MAX / 2) {
            new_cap = needed;
            break;
        }
        new_cap *= 2;
    }
    AffinityRule** resized = realloc(
        proc->thread_rules, new_cap * sizeof(*resized));
    if (!resized) return false;
    proc->thread_rules = resized;
    proc->thread_rules_cap = new_cap;
    return true;
}

static ssize_t tracked_process_index(const ProcCache* cache, pid_t pid) {
    for (size_t i = 0; i < cache->num_tracked_procs; i++) {
        if (cache->tracked_procs[i].pid == pid) return (ssize_t)i;
    }
    return -1;
}

static ssize_t tracked_process_identity_index(
    const ProcCache* cache,
    pid_t pid,
    unsigned long long starttime
) {
    for (size_t i = 0; i < cache->num_tracked_procs; i++) {
        if (cache->tracked_procs[i].pid == pid &&
            cache->tracked_procs[i].starttime == starttime) {
            return (ssize_t)i;
        }
    }
    return -1;
}

static bool ensure_tracked_process_capacity(ProcCache* cache, size_t needed) {
    if (needed <= cache->tracked_procs_cap) return true;
    size_t new_cap = cache->tracked_procs_cap ? cache->tracked_procs_cap : 8;
    while (new_cap < needed) {
        if (new_cap > SIZE_MAX / 2) {
            new_cap = needed;
            break;
        }
        new_cap *= 2;
    }
    TrackedProcess* resized = realloc(
        cache->tracked_procs, new_cap * sizeof(*resized));
    if (!resized) return false;
    cache->tracked_procs = resized;
    cache->tracked_procs_cap = new_cap;
    return true;
}

static bool merge_tracked_processes(
    ProcCache* cache,
    bool* identity_changed
) {
    if (identity_changed) *identity_changed = false;
    if (!ensure_tracked_process_capacity(
            cache, cache->num_tracked_procs + cache->num_procs)) {
        return false;
    }
    for (size_t i = 0; i < cache->num_procs; i++) {
        const ProcessInfo* proc = &cache->procs[i];
        ssize_t index = tracked_process_index(cache, proc->pid);
        if (index >= 0) {
            if (cache->tracked_procs[index].starttime != proc->starttime) {
                if (identity_changed) *identity_changed = true;
            }
            cache->tracked_procs[index].starttime = proc->starttime;
            continue;
        }
        TrackedProcess* tracked =
            &cache->tracked_procs[cache->num_tracked_procs++];
        tracked->pid = proc->pid;
        tracked->starttime = proc->starttime;
    }
    return true;
}

static bool replace_tracked_processes(ProcCache* cache) {
    if (!ensure_tracked_process_capacity(cache, cache->num_procs)) return false;
    cache->num_tracked_procs = cache->num_procs;
    for (size_t i = 0; i < cache->num_procs; i++) {
        cache->tracked_procs[i].pid = cache->procs[i].pid;
        cache->tracked_procs[i].starttime = cache->procs[i].starttime;
    }
    return true;
}

static bool process_rule_scope(
    const AppConfig* cfg,
    const char* name,
    char* base_pkg,
    size_t base_pkg_size,
    bool* is_child,
    bool* has_exact_process_rule
) {
    base_process_name(name, base_pkg, base_pkg_size);
    *is_child = strchr(name, ':') != NULL;
    *has_exact_process_rule = false;
    bool has_exact_owner_rules = false;
    bool has_base_process_rule = false;

    for (size_t i = 0; i < cfg->num_rules; i++) {
        const AffinityRule* rule = &cfg->rules[i];
        bool exact_owner = strcmp(rule->pkg, name) == 0;
        bool base_process = rule->thread[0] == '\0' &&
            strcmp(rule->pkg, base_pkg) == 0;
        if (!exact_owner && !base_process) continue;
        if (rule_health_rule_disabled(rule)) continue;
        if (exact_owner) {
            has_exact_owner_rules = true;
            if (rule->thread[0] == '\0') *has_exact_process_rule = true;
        }
        if (base_process) has_base_process_rule = true;
    }
    return has_exact_owner_rules || (*is_child && has_base_process_rule);
}

static bool configure_process_rules(
    const AppConfig* cfg,
    ProcessInfo* proc,
    const char* name,
    bool* complete
) {
    char base_pkg[MAX_PKG_LEN];
    bool is_child = false;
    bool has_exact_process_rule = false;
    if (!process_rule_scope(
            cfg, name, base_pkg, sizeof(base_pkg),
            &is_child, &has_exact_process_rule)) {
        return false;
    }

    build_str(proc->pkg, sizeof(proc->pkg), name, NULL);
    CPU_ZERO(&proc->base_cpus);
    proc->base_cpuset[0] = '\0';
    proc->num_thread_rules = 0;

    for (size_t i = 0; i < cfg->num_rules; i++) {
        const AffinityRule* rule = &cfg->rules[i];
        bool use_rule = strcmp(rule->pkg, name) == 0;
        if (!use_rule && is_child && !has_exact_process_rule &&
            rule->thread[0] == '\0' && strcmp(rule->pkg, base_pkg) == 0) {
            use_rule = true;
        }
        if (!use_rule) continue;
        if (rule_health_rule_disabled(rule)) continue;

        if (rule->thread[0]) {
            if (!ensure_thread_rule_capacity(
                    proc, proc->num_thread_rules + 1)) {
                *complete = false;
                continue;
            }
            proc->thread_rules[proc->num_thread_rules++] = (AffinityRule*)rule;
        } else {
            CPU_OR(&proc->base_cpus, &proc->base_cpus, &rule->cpus);
        }
    }

    cpuset_dir_for_mask(
        cfg, &proc->base_cpus, proc->base_cpuset, sizeof(proc->base_cpuset));
    return true;
}

static void assign_thread_rule(
    const AppConfig* cfg,
    const ProcessInfo* proc,
    ThreadInfo* thread
) {
    CPU_ZERO(&thread->cpus);
    thread->cpuset_dir[0] = '\0';
    bool matched = false;
    for (size_t i = 0; i < proc->num_thread_rules; i++) {
        const AffinityRule* rule = proc->thread_rules[i];
        if (fnmatch(rule->thread, thread->name, FNM_NOESCAPE) == 0) {
            CPU_OR(&thread->cpus, &thread->cpus, &rule->cpus);
            matched = true;
        }
    }
    if (matched) {
        cpuset_dir_for_mask(
            cfg, &thread->cpus, thread->cpuset_dir,
            sizeof(thread->cpuset_dir));
    } else {
        thread->cpus = proc->base_cpus;
        build_str(
            thread->cpuset_dir, sizeof(thread->cpuset_dir),
            proc->base_cpuset, NULL);
    }
}

static int compare_thread_info(const void* lhs, const void* rhs) {
    const ThreadInfo* left = lhs;
    const ThreadInfo* right = rhs;
    if (left->tid < right->tid) return -1;
    if (left->tid > right->tid) return 1;
    return 0;
}

static bool scan_process_tasks(
    const AppConfig* cfg,
    ProcessInfo* proc,
    int pid_fd
) {
    proc->num_threads = 0;
    int task_fd = openat(
        pid_fd, "task", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (task_fd == -1) return false;
    DIR* task_dir = fdopendir(task_fd);
    if (!task_dir) {
        close(task_fd);
        return false;
    }

    bool complete = true;
    while (true) {
        errno = 0;
        struct dirent* entry = readdir(task_dir);
        if (!entry) {
            if (errno != 0) complete = false;
            break;
        }
        char* end = NULL;
        errno = 0;
        long value = strtol(entry->d_name, &end, 10);
        if (errno == ERANGE || !end || *end != '\0' ||
            value <= 0 || value > INT_MAX) {
            continue;
        }

        int tid_fd = openat(
            task_fd, entry->d_name,
            O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (tid_fd == -1) {
            complete = false;
            continue;
        }
        char name[MAX_THREAD_LEN] = {0};
        unsigned long long starttime = 0;
        ProcFileResult identity = read_proc_stat_at(
            tid_fd, name, sizeof(name), &starttime);
        close(tid_fd);
        if (identity != PROC_FILE_OK) {
            complete = false;
            continue;
        }

        if (!ensure_thread_capacity(proc, proc->num_threads + 1)) {
            complete = false;
            continue;
        }
        ThreadInfo* thread = &proc->threads[proc->num_threads++];
        memset(thread, 0, sizeof(*thread));
        thread->tid = (pid_t)value;
        thread->starttime = starttime;
        build_str(thread->name, sizeof(thread->name), strtrim(name), NULL);
        assign_thread_rule(cfg, proc, thread);
    }
    closedir(task_dir);
    if (proc->num_threads > 1) {
        qsort(proc->threads, proc->num_threads,
              sizeof(*proc->threads), compare_thread_info);
    }
    return complete;
}

static bool populate_process(
    const AppConfig* cfg,
    ProcessInfo* proc,
    pid_t pid,
    unsigned long long starttime,
    const char* name,
    int pid_fd
) {
    bool complete = true;
    proc->pid = pid;
    proc->starttime = starttime;
    if (!configure_process_rules(cfg, proc, name, &complete)) return false;
    if (!scan_process_tasks(cfg, proc, pid_fd)) complete = false;
    return complete;
}

static bool identity_errno(int error) {
    return error == ENOENT || error == ESRCH;
}

static ProcessRefreshResult refresh_known_process(
    const AppConfig* cfg,
    ProcessInfo* proc,
    const TrackedProcess* tracked
) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", tracked->pid);
    int pid_fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (pid_fd == -1) {
        return identity_errno(errno) ?
            PROCESS_REFRESH_IDENTITY_LOST : PROCESS_REFRESH_READ_GAP;
    }

    unsigned long long starttime = 0;
    ProcFileResult stat_result = read_proc_stat_at(
        pid_fd, NULL, 0, &starttime);
    if (stat_result != PROC_FILE_OK) {
        int error = errno;
        close(pid_fd);
        return identity_errno(error) ?
            PROCESS_REFRESH_IDENTITY_LOST : PROCESS_REFRESH_READ_GAP;
    }
    if (starttime != tracked->starttime) {
        close(pid_fd);
        return PROCESS_REFRESH_IDENTITY_LOST;
    }

    char cmdline[MAX_PKG_LEN] = {0};
    ProcFileResult cmd_result = read_proc_file_at(
        pid_fd, "cmdline", cmdline, sizeof(cmdline));
    if (cmd_result != PROC_FILE_OK || cmdline[0] == '\0') {
        int error = errno;
        close(pid_fd);
        return cmd_result == PROC_FILE_ERROR && identity_errno(error) ?
            PROCESS_REFRESH_IDENTITY_LOST : PROCESS_REFRESH_READ_GAP;
    }
    char* name = strrchr(cmdline, '/');
    name = name ? name + 1 : cmdline;

    bool complete = true;
    proc->pid = tracked->pid;
    proc->starttime = starttime;
    /* thread_rules 指向 AppConfig，因此扫描 task 或应用快照前，
     * 必须使用本轮持有引用的配置重新构建。 */
    if (!configure_process_rules(cfg, proc, name, &complete)) {
        close(pid_fd);
        return PROCESS_REFRESH_NOT_RELEVANT;
    }
    /* 已缓存 PID 的刷新只补充最新正向命中；task 读取缺口
     * 不应影响最近一次完整扫描的完成时间戳。 */
    scan_process_tasks(cfg, proc, pid_fd);
    close(pid_fd);
    return PROCESS_REFRESH_INCLUDED;
}

static uint64_t process_cache_hash_bytes(
    uint64_t hash,
    const void* data,
    size_t size
) {
    const unsigned char* bytes = data;
    for (size_t i = 0; i < size; i++) {
        hash ^= bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

static uint64_t process_cache_fingerprint(const ProcCache* cache) {
    uint64_t hash = 14695981039346656037ULL;
    hash = process_cache_hash_bytes(
        hash, &cache->num_procs, sizeof(cache->num_procs));
    for (size_t i = 0; i < cache->num_procs; i++) {
        const ProcessInfo* proc = &cache->procs[i];
        hash = process_cache_hash_bytes(hash, &proc->pid, sizeof(proc->pid));
        hash = process_cache_hash_bytes(
            hash, &proc->starttime, sizeof(proc->starttime));
        hash = process_cache_hash_bytes(
            hash, proc->pkg, strlen(proc->pkg) + 1);
        hash = process_cache_hash_bytes(
            hash, &proc->base_cpus, sizeof(proc->base_cpus));
        hash = process_cache_hash_bytes(
            hash, &proc->num_threads, sizeof(proc->num_threads));
        for (size_t j = 0; j < proc->num_threads; j++) {
            const ThreadInfo* thread = &proc->threads[j];
            hash = process_cache_hash_bytes(
                hash, &thread->tid, sizeof(thread->tid));
            hash = process_cache_hash_bytes(
                hash, &thread->starttime, sizeof(thread->starttime));
            hash = process_cache_hash_bytes(
                hash, thread->name, strlen(thread->name) + 1);
            hash = process_cache_hash_bytes(
                hash, &thread->cpus, sizeof(thread->cpus));
        }
    }
    return hash;
}

typedef struct {
    bool global_complete;
    char (*incomplete_pkgs)[MAX_PKG_LEN];
    size_t num_incomplete_pkgs;
    size_t incomplete_pkgs_cap;
} ProcScanQuality;

static void proc_scan_quality_init(ProcScanQuality* quality) {
    memset(quality, 0, sizeof(*quality));
    quality->global_complete = true;
}

static void proc_scan_mark_package_gap(
    ProcScanQuality* quality,
    const char* process_name
) {
    char base_pkg[MAX_PKG_LEN];
    base_process_name(process_name, base_pkg, sizeof(base_pkg));
    if (!base_pkg[0]) {
        quality->global_complete = false;
        return;
    }
    for (size_t i = 0; i < quality->num_incomplete_pkgs; i++) {
        if (strcmp(quality->incomplete_pkgs[i], base_pkg) == 0) return;
    }
    if (quality->num_incomplete_pkgs >= quality->incomplete_pkgs_cap) {
        size_t new_cap = quality->incomplete_pkgs_cap ?
            quality->incomplete_pkgs_cap * 2 : 4;
        if (new_cap < quality->incomplete_pkgs_cap) {
            quality->global_complete = false;
            return;
        }
        char (*resized)[MAX_PKG_LEN] = realloc(
            quality->incomplete_pkgs, new_cap * sizeof(*resized));
        if (!resized) {
            quality->global_complete = false;
            return;
        }
        quality->incomplete_pkgs = resized;
        quality->incomplete_pkgs_cap = new_cap;
    }
    build_str(
        quality->incomplete_pkgs[quality->num_incomplete_pkgs++],
        MAX_PKG_LEN, base_pkg, NULL);
}

static void proc_scan_quality_release(ProcScanQuality* quality) {
    free(quality->incomplete_pkgs);
    memset(quality, 0, sizeof(*quality));
}

static void proc_cache_commit_full_scan_quality(
    ProcCache* cache,
    ProcScanQuality* quality
) {
    free(cache->last_full_incomplete_pkgs);
    cache->last_full_incomplete_pkgs = quality->incomplete_pkgs;
    cache->num_last_full_incomplete_pkgs = quality->num_incomplete_pkgs;
    quality->incomplete_pkgs = NULL;
    quality->num_incomplete_pkgs = 0;
    quality->incomplete_pkgs_cap = 0;
}

static bool proc_cache_full_scan_complete_for(
    const ProcCache* cache,
    const char* pkg
) {
    if (!cache || !pkg || !pkg[0]) return false;
    char base_pkg[MAX_PKG_LEN];
    base_process_name(pkg, base_pkg, sizeof(base_pkg));
    for (size_t i = 0; i < cache->num_last_full_incomplete_pkgs; i++) {
        if (strcmp(cache->last_full_incomplete_pkgs[i], base_pkg) == 0) {
            return false;
        }
    }
    return true;
}

static bool config_has_runtime_rules(const AppConfig* cfg) {
    for (size_t i = 0; i < cfg->num_rules; i++) {
        if (!rule_health_rule_disabled(&cfg->rules[i])) return true;
    }
    return false;
}

static void clear_empty_runtime_cache(
    ProcCache* cache,
    int* affinity_counter,
    unsigned long long now_elapsed
) {
    cache->num_procs = 0;
    cache->num_tracked_procs = 0;
    cache->initialized = true;
    cache->scan_all_proc = false;
    cache->proc_growth_pending = false;
    cache->last_full_scan_attempt_elapsed_ms = 0;
    cache->last_health_full_scan_attempt_elapsed_ms = 0;
    cache->last_refresh_elapsed_ms = now_elapsed;
    free(cache->last_full_incomplete_pkgs);
    cache->last_full_incomplete_pkgs = NULL;
    cache->num_last_full_incomplete_pkgs = 0;
    *affinity_counter = 0;
}

static bool proc_collect(
    const AppConfig* cfg,
    ProcCache* cache,
    size_t* count,
    bool full_scan,
    ProcScanQuality* quality
) {
    DIR* proc_dir = opendir("/proc");
    if (!proc_dir) return false;
    int proc_fd = dirfd(proc_dir);
    if (proc_fd < 0) {
        closedir(proc_dir);
        return false;
    }
    *count = 0;

    struct dirent* ent;
    time_t current_time = time(NULL);
    while (true) {
        errno = 0;
        ent = readdir(proc_dir);
        if (!ent) {
            if (errno != 0) quality->global_complete = false;
            break;
        }
        char* end = NULL;
        errno = 0;
        long value = strtol(ent->d_name, &end, 10);
        if (errno == ERANGE || !end || *end != '\0' ||
            value <= 0 || value > INT_MAX) {
            continue;
        }
        pid_t pid = (pid_t)value;
        bool is_tracked = tracked_process_index(cache, pid) >= 0;
        if (!full_scan) {
            if (!is_tracked) {
                struct stat statbuf;
                if (fstatat(proc_fd, ent->d_name, &statbuf, AT_SYMLINK_NOFOLLOW) != 0) continue;
                if (current_time - statbuf.st_mtime > 60) continue;
            }
        }

        int pid_fd = openat(proc_fd, ent->d_name,O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (pid_fd == -1) {
            if (is_tracked) quality->global_complete = false;
            continue;
        }

        char cmd[MAX_PKG_LEN] = {0};
        ProcFileResult cmd_result = read_proc_file_at(pid_fd, "cmdline", cmd, sizeof(cmd));
        if (cmd_result != PROC_FILE_OK || cmd[0] == '\0') {
            if (is_tracked) quality->global_complete = false;
            close(pid_fd);
            continue;
        }
        char* name = strrchr(cmd, '/');
        name = name ? name + 1 : cmd;

        char base_pkg[MAX_PKG_LEN];
        bool is_child = false;
        bool has_exact_process_rule = false;
        if (!process_rule_scope(
                cfg, name, base_pkg, sizeof(base_pkg),
                &is_child, &has_exact_process_rule)) {
            close(pid_fd);
            continue;
        }

        unsigned long long starttime = 0;
        if (read_proc_stat_at(pid_fd, NULL, 0, &starttime) != PROC_FILE_OK) {
            proc_scan_mark_package_gap(quality, name);
            close(pid_fd);
            continue;
        }
        if (!ensure_process_capacity(cache, *count + 1)) {
            quality->global_complete = false;
            close(pid_fd);
            continue;
        }

        ProcessInfo* proc = &cache->procs[*count];
        bool process_complete = populate_process(
            cfg, proc, pid, starttime, name, pid_fd);
        close(pid_fd);
        if (!process_complete) proc_scan_mark_package_gap(quality, name);
        (*count)++;
    }
    closedir(proc_dir);
    return quality->global_complete;
}

static void refresh_tracked_processes(
    ProcCache* cache,
    const AppConfig* cfg,
    int* affinity_counter
) {
    uint64_t old_fingerprint = process_cache_fingerprint(cache);
    if (!ensure_process_capacity(
            cache, cache->num_procs + cache->num_tracked_procs)) {
        cache->num_procs = 0;
        cache->scan_all_proc = true;
        *affinity_counter = 0;
        return;
    }

    size_t working_count = cache->num_procs;
    size_t tracked_count = 0;
    size_t previous_tracked_count = cache->num_tracked_procs;
    for (size_t i = 0; i < previous_tracked_count; i++) {
        TrackedProcess tracked = cache->tracked_procs[i];
        size_t slot = working_count;
        for (size_t j = 0; j < working_count; j++) {
            if (cache->procs[j].pid == tracked.pid &&
                cache->procs[j].starttime == tracked.starttime) {
                slot = j;
                break;
            }
        }
        if (slot == working_count) {
            for (size_t j = 0; j < working_count; j++) {
                if (cache->procs[j].pid == 0) {
                    slot = j;
                    break;
                }
            }
        }
        if (slot == working_count) working_count++;

        ProcessInfo* proc = &cache->procs[slot];
        ProcessRefreshResult result = refresh_known_process(cfg, proc, &tracked);
        if (result == PROCESS_REFRESH_IDENTITY_LOST) {
            proc->pid = 0;
            proc->num_threads = 0;
            cache->scan_all_proc = true;
            continue;
        }
        if (result == PROCESS_REFRESH_NOT_RELEVANT) {
            proc->pid = 0;
            proc->num_threads = 0;
            continue;
        }

        cache->tracked_procs[tracked_count++] = tracked;
        if (result == PROCESS_REFRESH_READ_GAP) {
            proc->pid = 0;
            proc->num_threads = 0;
        }
    }

    cache->num_tracked_procs = tracked_count;
    for (size_t i = 0; i < working_count; i++) {
        ProcessInfo* proc = &cache->procs[i];
        if (proc->pid == 0 ||
            tracked_process_identity_index(
                cache, proc->pid, proc->starttime) >= 0) {
            continue;
        }
        proc->pid = 0;
        proc->num_threads = 0;
    }

    size_t active_count = 0;
    for (size_t i = 0; i < working_count; i++) {
        if (cache->procs[i].pid == 0) continue;
        if (active_count != i) {
            ProcessInfo temporary = cache->procs[active_count];
            cache->procs[active_count] = cache->procs[i];
            cache->procs[i] = temporary;
        }
        active_count++;
    }
    cache->num_procs = active_count;
    cache->last_refresh_elapsed_ms = rule_health_boottime_ms();
    if (process_cache_fingerprint(cache) != old_fingerprint) {
        *affinity_counter = 0;
    }
}

static void update_cache(
    ProcCache* cache,
    const AppConfig* cfg,
    int* affinity_counter,
    bool force_full_scan
) {
    unsigned long long now_elapsed = rule_health_boottime_ms();
    if (!config_has_runtime_rules(cfg)) {
        clear_empty_runtime_cache(cache, affinity_counter, now_elapsed);
        return;
    }
    bool periodic_full_scan = cache->initialized && now_elapsed > 0 &&
        (cache->last_full_scan_elapsed_ms == 0 ||
         now_elapsed - cache->last_full_scan_elapsed_ms >= PROC_FULL_RESCAN_MS);
    bool full_retry_allowed = now_elapsed == 0 ||
        cache->last_full_scan_attempt_elapsed_ms == 0 ||
        now_elapsed - cache->last_full_scan_attempt_elapsed_ms >=
            RULE_HEALTH_FULL_SCAN_RETRY_MS;
    bool full_scan_requested = !cache->initialized || cache->scan_all_proc ||
        periodic_full_scan || force_full_scan;
    bool full_scan = full_scan_requested && full_retry_allowed;
    struct sysinfo info;
    if (sysinfo(&info) == 0) {
        int current_proc_count = info.procs;
        if (current_proc_count > cache->last_proc_count) {
            /* 合并处理进程/线程数量波动，避免线程创建导致 2 秒主循环
             * 每轮都枚举 /proc。 */
            cache->proc_growth_pending = true;
        }
        cache->last_proc_count = current_proc_count;
    }
    bool growth_scan_due = cache->proc_growth_pending &&
        (now_elapsed == 0 || cache->last_proc_growth_scan_elapsed_ms == 0 ||
         now_elapsed - cache->last_proc_growth_scan_elapsed_ms >=
            PROC_GROWTH_SCAN_COOLDOWN_MS);
    bool incremental_scan = growth_scan_due && !full_scan_requested;
    if (full_scan || incremental_scan) {
        if (full_scan && now_elapsed > 0) {
            cache->last_full_scan_attempt_elapsed_ms = now_elapsed;
            cache->last_health_full_scan_attempt_elapsed_ms = now_elapsed;
        }
        size_t new_count = 0;
        ProcScanQuality quality;
        proc_scan_quality_init(&quality);
        bool scan_complete = proc_collect(
            cfg, cache, &new_count, full_scan, &quality);
        cache->num_procs = new_count;
        bool full_cache_complete = scan_complete &&
            quality.num_incomplete_pkgs == 0;
        bool identity_changed = false;
        bool tracked_cache_complete = full_scan && full_cache_complete ?
            replace_tracked_processes(cache) :
            merge_tracked_processes(cache, &identity_changed);
        cache->initialized = true;
        if (full_scan) {
            cache->scan_all_proc = identity_changed ||
                !scan_complete || !tracked_cache_complete;
        } else {
            cache->scan_all_proc = cache->scan_all_proc ||
                identity_changed || !scan_complete || !tracked_cache_complete;
        }
        unsigned long long completed_at = rule_health_boottime_ms();
        cache->last_refresh_elapsed_ms = completed_at;
        if (incremental_scan) {
            cache->last_proc_growth_scan_elapsed_ms = completed_at;
            if (scan_complete) cache->proc_growth_pending = false;
        }
        if (full_scan && scan_complete) {
            proc_cache_commit_full_scan_quality(cache, &quality);
            cache->last_full_scan_elapsed_ms = completed_at;
            if (tracked_cache_complete) {
                cache->last_full_scan_attempt_elapsed_ms = 0;
                cache->last_proc_growth_scan_elapsed_ms = completed_at;
                cache->proc_growth_pending = false;
            }
        }
        proc_scan_quality_release(&quality);
        *affinity_counter = 0;
        return;
    }
    refresh_tracked_processes(cache, cfg, affinity_counter);
}

static void apply_affinity(ProcCache* cache, const CpuTopology* topo) {
    for (size_t i = 0; i < cache->num_procs; i++) {
        const ProcessInfo* proc = &cache->procs[i];
        char proc_path[64];
        snprintf(proc_path, sizeof(proc_path), "/proc/%d", proc->pid);
        int proc_fd = open(proc_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        unsigned long long process_starttime = 0;
        if (proc_fd == -1 || !read_starttime_at(proc_fd, &process_starttime) ||
            process_starttime != proc->starttime) {
            if (proc_fd != -1) close(proc_fd);
            cache->scan_all_proc = true;
            continue;
        }
        int task_fd = openat(proc_fd, "task", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        close(proc_fd);
        if (task_fd == -1) continue;
        for (size_t j = 0; j < proc->num_threads; j++) {
            const ThreadInfo* ti = &proc->threads[j];
            bool has_requested_cpus = CPU_COUNT(&ti->cpus) > 0;
            cpu_set_t effective_cpus;
            CPU_AND(&effective_cpus, &ti->cpus, &topo->present_cpus);
            if (!has_requested_cpus || CPU_COUNT(&effective_cpus) == 0) continue;
            char tid_name[32];
            snprintf(tid_name, sizeof(tid_name), "%d", ti->tid);
            int tid_fd = openat(task_fd, tid_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
            unsigned long long thread_starttime = 0;
            if (tid_fd == -1 || !read_starttime_at(tid_fd, &thread_starttime) ||
                thread_starttime != ti->starttime) {
                if (tid_fd != -1) close(tid_fd);
                continue;
            }
            close(tid_fd);
            if (topo->cpuset_enabled && topo->base_cpuset_fd != -1) {
                char tid_str[32];
                snprintf(tid_str, sizeof(tid_str), "%d\n", ti->tid);
                cpu_set_t current_cpus;
                if (sched_getaffinity(
                        ti->tid, sizeof(current_cpus), &current_cpus) == -1) {
                    continue;
                }
                if (CPU_EQUAL(&effective_cpus, &current_cpus)) continue;
                if (ti->cpuset_dir[0]) {
                    int fd = openat(
                        topo->base_cpuset_fd, ti->cpuset_dir,
                        O_RDONLY | O_DIRECTORY);
                    if (fd != -1) {
                        write_file(fd, "tasks", tid_str, O_WRONLY | O_APPEND);
                        close(fd);
                    }
                }
            }
            sched_setaffinity(
                ti->tid, sizeof(effective_cpus), &effective_cpus);
        }
        close(task_fd);
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
