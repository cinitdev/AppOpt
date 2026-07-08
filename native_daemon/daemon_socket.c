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

