/* ebpf_fps.c —— 基于 frame-analyzer-ebpf 的帧率采集实现
 *
 * 本实现通过对接 shadow3aaa 的 frame-analyzer-ebpf (Rust) 项目实现 eBPF 帧率监测。
 * frame-analyzer-ebpf 是经过大量设备验证的成熟方案，避免了自行实现 uprobe 的兼容性问题。
 *
 * 架构：
 *   - AppOpt.c (C) 通过 fork+exec 启动 frame-analyzer-wrapper (Rust 编译的二进制)
 *   - frame-analyzer-wrapper 输出格式：每行一个浮点数 FPS
 *   - 通过 pipe 读取帧率数据
 */
#define _GNU_SOURCE
#include "ebpf_fps.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>

#define FRAME_ANALYZER_BIN "/data/adb/modules/AppOpt/bin/frame-analyzer"

/* eBPF FPS 上下文 */
struct ebpf_fps_ctx {
    pid_t analyzer_pid;      /* frame-analyzer 子进程 PID */
    int   pipe_fd;           /* 读取帧率数据的管道 */
    double last_fps;         /* 最后一次读取的 FPS */
    char  symbol[128];       /* 符号名（frame-analyzer 自动识别） */
    bool  active;            /* 是否激活 */
};

/* ===================== 能力探测 ===================== */

ebpf_cap_t ebpf_fps_probe_capability(void) {
    /* 检查 frame-analyzer 二进制是否存在 */
    if (access(FRAME_ANALYZER_BIN, X_OK) != 0) {
        return EBPF_CAP_OBJ_NOT_FOUND;
    }

    /* frame-analyzer 内部会检查 eBPF 支持，这里假设可用 */
    return EBPF_CAP_OK;
}

const char *ebpf_cap_str(ebpf_cap_t cap) {
    switch (cap) {
        case EBPF_CAP_OK:              return "可用";
        case EBPF_CAP_NO_BPF_SYSCALL:  return "bpf() 系统调用被禁用";
        case EBPF_CAP_NO_RINGBUF:      return "内核不支持 RingBuf";
        case EBPF_CAP_NO_UPROBE:       return "不支持 uprobe";
        case EBPF_CAP_LOAD_FAILED:     return "BPF 程序加载失败";
        case EBPF_CAP_OBJ_NOT_FOUND:   return "frame-analyzer 未找到";
        default:                       return "未知错误";
    }
}

/* ===================== 启动 frame-analyzer ===================== */

ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid) {
    (void)bpf_obj_path;  /* 不需要，frame-analyzer 自带 BPF 程序 */

    /* 创建管道 */
    int pfd[2];
    if (pipe(pfd) != 0) {
        return NULL;
    }

    /* 设置非阻塞读 */
    int flags = fcntl(pfd[0], F_GETFL, 0);
    fcntl(pfd[0], F_SETFL, flags | O_NONBLOCK);

    /* fork 子进程 */
    pid_t pid = fork();
    if (pid < 0) {
        close(pfd[0]);
        close(pfd[1]);
        return NULL;
    }

    if (pid == 0) {
        /* 子进程：执行 frame-analyzer */
        close(pfd[0]);

        /* 重定向 stdout 到管道 */
        dup2(pfd[1], STDOUT_FILENO);

        /* 重定向 stderr 到 /dev/null，避免干扰 */
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }

        close(pfd[1]);

        /* 构造参数：frame-analyzer --pid <pid> */
        char pid_str[32];
        snprintf(pid_str, sizeof(pid_str), "%d", target_pid);

        execl(FRAME_ANALYZER_BIN, "frame-analyzer", "--pid", pid_str, NULL);

        /* execl 失败 */
        _exit(127);
    }

    /* 父进程 */
    close(pfd[1]);

    /* 创建上下文 */
    ebpf_fps_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        close(pfd[0]);
        kill(pid, SIGTERM);
        waitpid(pid, NULL, 0);
        return NULL;
    }

    ctx->analyzer_pid = pid;
    ctx->pipe_fd = pfd[0];
    ctx->last_fps = 0.0;
    ctx->active = true;
    snprintf(ctx->symbol, sizeof(ctx->symbol), "frame-analyzer-ebpf");

    return ctx;
}

/* ===================== 轮询帧率数据 ===================== */

int ebpf_fps_poll(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->active) {
        return 0;
    }

    /* 非阻塞读取管道数据 */
    char buf[256];
    ssize_t n = read(ctx->pipe_fd, buf, sizeof(buf) - 1);

    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            /* 没有数据，正常 */
            return 0;
        }
        /* 读取错误 */
        ctx->active = false;
        return -1;
    }

    if (n == 0) {
        /* 管道关闭（子进程退出） */
        ctx->active = false;
        return -1;
    }

    buf[n] = '\0';

    /* 解析浮点数（每行一个 FPS 值） */
    char *line = strtok(buf, "\n");
    int count = 0;

    while (line) {
        double fps = atof(line);
        if (fps > 0.0) {
            ctx->last_fps = fps;
            count++;
        }
        line = strtok(NULL, "\n");
    }

    return count;
}

/* ===================== 获取 FPS ===================== */

double ebpf_fps_get(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->active) {
        return 0.0;
    }
    return ctx->last_fps;
}

const char *ebpf_fps_symbol(ebpf_fps_ctx *ctx) {
    if (!ctx) {
        return NULL;
    }
    return ctx->symbol;
}

/* ===================== 停止 ===================== */

void ebpf_fps_stop(ebpf_fps_ctx *ctx) {
    if (!ctx) {
        return;
    }

    ctx->active = false;

    /* 关闭管道 */
    if (ctx->pipe_fd >= 0) {
        close(ctx->pipe_fd);
    }

    /* 终止子进程 */
    if (ctx->analyzer_pid > 0) {
        kill(ctx->analyzer_pid, SIGTERM);

        /* 等待子进程退出（带超时） */
        for (int i = 0; i < 10; i++) {
            int status;
            pid_t ret = waitpid(ctx->analyzer_pid, &status, WNOHANG);
            if (ret > 0) {
                break;
            }
            usleep(100000);  /* 100ms */
        }

        /* 强制杀死 */
        kill(ctx->analyzer_pid, SIGKILL);
        waitpid(ctx->analyzer_pid, NULL, 0);
    }

    free(ctx);
}
