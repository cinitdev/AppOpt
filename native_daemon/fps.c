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

    (void)pkg;
    printf("[FPS] 未锁定包名 PID, 暂不启动全局 eBPF 探测\n");
    return NULL;
}

static char* fps_jank_normalize_pkg(char* line) {
    if (!line) return NULL;
    char* value = strtrim(line);
    char* comment = strchr(value, '#');
    if (comment) *comment = '\0';
    value = strtrim(value);
    char* process = strchr(value, ':');
    if (process) *process = '\0';
    value = strtrim(value);
    if (!value[0] || !strchr(value, '.')) return NULL;
    for (const unsigned char* cursor = (const unsigned char*)value; *cursor; cursor++) {
        if (!isalnum(*cursor) && *cursor != '_' && *cursor != '.') return NULL;
    }
    return value;
}

static unsigned long long fps_jank_config_snapshot(
    char* preview,
    size_t preview_size,
    size_t* package_count,
    const char* match_pkg,
    bool* match_found
) {
    if (preview && preview_size > 0) preview[0] = '\0';
    if (package_count) *package_count = 0;
    if (match_found) *match_found = false;
    struct stat info;
    if (stat(JANK_BOOST_FILE, &info) != 0) return 0;
    unsigned long long signature =
        (unsigned long long)info.st_mtime * 1000003ULL + (unsigned long long)info.st_size;
    FILE* fp = fopen(JANK_BOOST_FILE, "r");
    if (!fp) return signature;
    char* line = NULL;
    size_t cap = 0;
    size_t count = 0;
    size_t shown = 0;
    while (getline(&line, &cap, fp) != -1) {
        char* value = fps_jank_normalize_pkg(line);
        if (!value) continue;
        count++;
        if (match_found && match_pkg && strcmp(value, match_pkg) == 0) {
            *match_found = true;
        }
        if (preview && shown < 5) {
            size_t used = strlen(preview);
            if (used < preview_size) {
                snprintf(
                    preview + used,
                    preview_size - used,
                    "%s%s",
                    shown > 0 ? ", " : "",
                    value);
            }
            shown++;
        }
    }
    free(line);
    fclose(fp);
    if (package_count) *package_count = count;
    return signature ^ ((unsigned long long)count << 32);
}

static bool fps_jank_cgroup_foreground_pkg(char* out, size_t out_size) {
    if (!out || out_size == 0) return false;
    FILE* fp = fopen(JANK_BOOST_FILE, "r");
    if (!fp) return false;
    bool found = false;
    char* line = NULL;
    size_t cap = 0;
    while (!found && getline(&line, &cap, fp) != -1) {
        char* pkg = fps_jank_normalize_pkg(line);
        if (!pkg) continue;
        app_top_state_result state;
        if (app_top_state_check(pkg, &state) && state.target_top_app) {
            build_str(out, out_size, pkg, NULL);
            found = true;
        }
    }
    free(line);
    fclose(fp);
    return found;
}

static bool fps_jank_foreground_pkg(char* out, size_t out_size) {
    if (!out || out_size == 0) return false;
    out[0] = '\0';
    FILE* fp = fopen(FOREGROUND_TASK_STATE_FILE, "r");
    if (!fp) return false;
    char status[32] = {0};
    char focused[MAX_PKG_LEN] = {0};
    unsigned long long updated_wall_ms = 0;
    char* line = NULL;
    size_t cap = 0;
    while (getline(&line, &cap, fp) != -1) {
        char* equal = strchr(line, '=');
        if (!equal) continue;
        *equal = '\0';
        char* key = strtrim(line);
        char* value = strtrim(equal + 1);
        if (strcmp(key, "status") == 0) build_str(status, sizeof(status), value, NULL);
        else if (strcmp(key, "focused_package") == 0) build_str(focused, sizeof(focused), value, NULL);
        else if (strcmp(key, "updated_wall_ms") == 0) updated_wall_ms = strtoull(value, NULL, 10);
    }
    free(line);
    fclose(fp);
    struct timespec wall;
    unsigned long long now_wall_ms = 0;
    if (clock_gettime(CLOCK_REALTIME, &wall) == 0) {
        now_wall_ms = (unsigned long long)wall.tv_sec * 1000ULL +
            (unsigned long long)wall.tv_nsec / 1000000ULL;
    }
    char* normalized = fps_jank_normalize_pkg(focused);
    if (strcmp(status, "ok") != 0 || !normalized || updated_wall_ms == 0 ||
        now_wall_ms < updated_wall_ms || now_wall_ms - updated_wall_ms > 12000ULL) return false;
    build_str(out, out_size, normalized, NULL);
    return true;
}

static void fps_jank_log_update(AppOptJankCtx* ctx, pid_t pid, double fps,
                                ebpf_fps_ctx* ebpf_ctx) {
    if (!ctx) return;
    AppOptFrameMetrics metrics = {0};
    const AppOptFrameMetrics* metrics_ptr = NULL;
    if (ebpf_ctx && ebpf_fps_metrics(ebpf_ctx, &metrics)) metrics_ptr = &metrics;
    int event = appopt_jank_update(ctx, (int)pid, fps, metrics_ptr);
    if (event > 0) {
        const char* message = appopt_jank_last_event(ctx);
        printf("[boost] %s\n", (message && message[0]) ? message : "状态已更新");
    }
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
    bool manual_monitoring = false;
    bool adaptive_monitoring = false;
    char pkg[MAX_PKG_LEN] = "";
    char cmd[384];
    char auto_pkg[MAX_PKG_LEN] = "";
    long long last_auto_check_ms = 0;
    unsigned long long last_jank_config_signature = ~0ULL;
    size_t last_selected_count = 0;
    AppOptJankCtx* jank_ctx = NULL;
    pid_t monitor_pid = (pid_t)-1;
    bool publish_fps = false;

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

    for (; !shutdown_requested;) {
        /* 轮询命令 */
        bool internal_command = false;
        bool has_command = fps_read_cmd(cmd, sizeof(cmd));
        long long command_now_ms = monotonic_ms();
        long long auto_check_interval_ms = last_selected_count > 0 ? 1000 : 10000;
        if (command_now_ms - last_auto_check_ms >= auto_check_interval_ms) {
            last_auto_check_ms = command_now_ms;
            char next_auto_pkg[MAX_PKG_LEN] = "";
            bool has_foreground = last_selected_count > 0 && fps_jank_foreground_pkg(
                next_auto_pkg, sizeof(next_auto_pkg));
            char selected_preview[512] = "";
            size_t selected_count = 0;
            bool selected_foreground = false;
            unsigned long long selected_signature = fps_jank_config_snapshot(
                selected_preview, sizeof(selected_preview), &selected_count,
                has_foreground ? next_auto_pkg : NULL, &selected_foreground);
            if (selected_count > 0 && !selected_foreground &&
                fps_jank_cgroup_foreground_pkg(next_auto_pkg, sizeof(next_auto_pkg))) {
                has_foreground = true;
                selected_foreground = true;
            }
            last_selected_count = selected_count;
            if (selected_signature != last_jank_config_signature) {
                if (selected_count == 0) {
                    printf("[boost] 卡顿时临时提速未配置应用\n");
                } else {
                    printf(
                        "[boost] 已配置临时提速: %s%s；等待所选应用进入前台\n",
                        selected_preview,
                        selected_count > 5 ? " ..." : "");
                }
                last_jank_config_signature = selected_signature;
            }
            bool has_auto = has_foreground && selected_foreground;
            bool should_adapt = has_auto && monitoring && strcmp(next_auto_pkg, pkg) == 0;
            if (should_adapt != adaptive_monitoring) {
                if (jank_ctx) {
                    appopt_jank_stop(jank_ctx);
                    jank_ctx = NULL;
                }
                adaptive_monitoring = should_adapt;
                if (should_adapt) {
                    jank_ctx = appopt_jank_create(pkg);
                    printf("[boost] 已为 %s 开启卡顿时临时提速\n", pkg);
                } else if (monitoring) {
                    printf("[boost] 已为 %s 停止卡顿时临时提速并恢复参数\n", pkg);
                }
            }
            if (!manual_monitoring && !has_command) {
                if (has_auto && (!monitoring || strcmp(next_auto_pkg, pkg) != 0)) {
                    snprintf(cmd, sizeof(cmd), "start %s", next_auto_pkg);
                    has_command = true;
                    internal_command = true;
                } else if (!has_auto && monitoring) {
                    build_str(cmd, sizeof(cmd), "stop", NULL);
                    has_command = true;
                    internal_command = true;
                }
            }
            build_str(auto_pkg, sizeof(auto_pkg), has_auto ? next_auto_pkg : "", NULL);
        }
        if (has_command) {
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
                    manual_monitoring = !internal_command;
                    publish_fps = manual_monitoring;
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
                    monitor_pid = (pid_t)-1;
                    if (jank_ctx) {
                        appopt_jank_stop(jank_ctx);
                        jank_ctx = NULL;
                    }
                    adaptive_monitoring = auto_pkg[0] && strcmp(auto_pkg, pkg) == 0;
                    if (adaptive_monitoring) {
                        jank_ctx = appopt_jank_create(pkg);
                        printf("[boost] 已为 %s 开启卡顿时临时提速\n", pkg);
                    }

                    /* 1. eBPF 预检查。真正加载/attach 是否成功由 ebpf_fps_start 决定。 */
                    ebpf_cap_t cap = ebpf_fps_probe_capability(FPS_BPF_OBJ);
                    printf("[FPS] 开始监测 %s, eBPF: %s\n", pkg, ebpf_cap_str(cap));

                    if (cap == EBPF_CAP_OK) {
                        /* 2. 找目标进程 PID。游戏刚启动时 /proc/cmdline 可能短暂不可见, 等一小段时间。 */
                        const char* pid_source = "未找到";
                        pid_t start_pid = fps_wait_pkg_pid(pkg, 30, 100 * 1000, &pid_source);
                        monitor_pid = start_pid;
                        if (start_pid < 0) {
                            printf("[FPS] 等待约 3 秒仍未找到 %s 的进程, 跳过全局 eBPF 探测\n", pkg);
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
                        if (monitor_pid <= 0) {
                            const char* fallback_pid_source = NULL;
                            monitor_pid = fps_find_preferred_pkg_pid(pkg, true, &fallback_pid_source);
                        }
                        fallback_ctx = fps_fallback_start(pkg);
                        if (!fallback_ctx) {
                            printf("[FPS] 警告: fallback 启动失败\n");
                        }
                    }
                }
            } else if (is_stop_command(cmd)) {
                if (!internal_command) manual_monitoring = false;
                if (!internal_command && auto_pkg[0] && monitoring && strcmp(auto_pkg, pkg) == 0) {
                    if (publish_fps) fps_write_out_windowed(0, &last_fps_output_ms, true);
                    publish_fps = false;
                    fps_socket_reset();
                    continue;
                }
                if (monitoring) {
                    bool was_publishing = publish_fps;
                    monitoring = false;
                    manual_monitoring = false;
                    adaptive_monitoring = false;
                    if (jank_ctx) { appopt_jank_stop(jank_ctx); jank_ctx = NULL; }
                    if (ebpf_ctx) { ebpf_fps_stop(ebpf_ctx); ebpf_ctx = NULL; }
                    if (fallback_ctx) { fps_fallback_stop(fallback_ctx); fallback_ctx = NULL; }
                    ebpf_seen_frames = false;
                    ebpf_stale_zero_sent = false;
                    printf("[FPS] 停止监测 %s\n", pkg);
                    if (was_publishing) fps_write_out_windowed(0, &last_fps_output_ms, true);
                    publish_fps = false;
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
            fps_jank_log_update(
                jank_ctx,
                active_pid,
                (fps_is_stale || fps_has_no_valid_value) ? 0.0 : fps,
                ebpf_ctx
            );
            if (fps_is_stale) {
                if (!ebpf_stale_zero_sent) {
                    printf("[FPS] eBPF 目标暂无新帧 %.1f 秒, 输出 0 FPS\n",
                           (double)(now_ms - ebpf_last_frame_ms) / 1000.0);
                    if (publish_fps) fps_write_out_windowed(0, &last_fps_output_ms, true);
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
                    monitor_pid = next_pid;
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
                    if (publish_fps) fps_write_out_windowed(fps, &last_fps_output_ms, false);
            }
            usleep(100 * 1000);  /* 100ms 轮询 */
            continue;
        }

        /* === Fallback 模式: --latency 或 timestats === */
        if (fallback_ctx) {
            double fps = fps_fallback_poll(fallback_ctx);
            fps_jank_log_update(jank_ctx, monitor_pid, fps, NULL);
            if (fallback_first_fps && fps > 0) {
                printf("[FPS] Fallback 首次捕获到帧率: %.1f fps\n", fps);
                fallback_first_fps = false;
            }
            if (publish_fps) fps_write_out_windowed(fps, &last_fps_output_ms, false);
            usleep((useconds_t)FPS_WINDOW_MS * 1000);  /* 按 FPS_WINDOW_MS 更新悬浮胶囊 */
            continue;
        }

        /* 无可用数据源 */
        usleep(500 * 1000);
            if (publish_fps) fps_write_out_windowed(0, &last_fps_output_ms, false);
    }
    if (jank_ctx) appopt_jank_stop(jank_ctx);
    if (ebpf_ctx) ebpf_fps_stop(ebpf_ctx);
    if (fallback_ctx) fps_fallback_stop(fallback_ctx);
    fps_socket_reset();
    return NULL;
}

