/* ebpf_fps.c - Rust/aya 帧率分析桥接层的 C API 适配文件。
 *
 * 这个文件保留 AppOpt.c 已经在使用的 ebpf_fps_* 接口。
 * 真正的 uprobe attach、BPF 加载和事件通道读取逻辑在 Rust 中实现。
 */
#include "ebpf_fps.h"

#include <errno.h>
#include <linux/bpf.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef BPF_MAP_CREATE
#define BPF_MAP_CREATE 0
#endif

#ifndef BPF_MAP_TYPE_PERF_EVENT_ARRAY
#define BPF_MAP_TYPE_PERF_EVENT_ARRAY 4
#endif

#ifndef BPF_MAP_TYPE_RINGBUF
#define BPF_MAP_TYPE_RINGBUF 27
#endif

#ifndef ENOTSUP
#define ENOTSUP EOPNOTSUPP
#endif

#ifndef __NR_bpf
#if defined(__aarch64__)
#define __NR_bpf 280
#elif defined(__arm__)
#define __NR_bpf 386
#elif defined(__i386__)
#define __NR_bpf 357
#elif defined(__x86_64__)
#define __NR_bpf 321
#endif
#endif

typedef struct AppOptEbpfCtx AppOptEbpfCtx;

extern AppOptEbpfCtx *appopt_ebpf_start(int pid, const char *bpf_obj_path);
extern AppOptEbpfCtx *appopt_ebpf_start_for_package(int pid, const char *bpf_obj_path, const char *target_pkg);
extern int appopt_ebpf_poll(AppOptEbpfCtx *ctx);
extern double appopt_ebpf_get(const AppOptEbpfCtx *ctx);
extern int appopt_ebpf_pid(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_symbol(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_backend(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_startup_note(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_last_error(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_last_start_error(void);
extern void appopt_ebpf_stop(AppOptEbpfCtx *ctx);

struct ebpf_fps_ctx {
    AppOptEbpfCtx *inner;
};

static int probe_bpf_map(union bpf_attr *attr) {
    int fd = (int)syscall(__NR_bpf, BPF_MAP_CREATE, attr, sizeof(*attr));
    if (fd >= 0) {
        close(fd);
        return 0;
    }
    return errno;
}

ebpf_cap_t ebpf_fps_probe_capability(const char *bpf_obj_path) {
    if (!bpf_obj_path || access(bpf_obj_path, R_OK) != 0) {
        return EBPF_CAP_OBJ_NOT_FOUND;
    }

#ifndef __NR_bpf
    return EBPF_CAP_NO_BPF_SYSCALL;
#else
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_type = BPF_MAP_TYPE_RINGBUF;
    attr.max_entries = 4096;

    int ring_errno = probe_bpf_map(&attr);
    if (ring_errno == 0) {
        return EBPF_CAP_OK;
    }
    if (ring_errno == ENOSYS) {
        return EBPF_CAP_NO_BPF_SYSCALL;
    }

    memset(&attr, 0, sizeof(attr));
    attr.map_type = BPF_MAP_TYPE_PERF_EVENT_ARRAY;
    attr.key_size = sizeof(unsigned int);
    attr.value_size = sizeof(unsigned int);
    attr.max_entries = 1;

    int perf_errno = probe_bpf_map(&attr);
    if (perf_errno == 0) {
        return EBPF_CAP_OK;
    }

    if (perf_errno == ENOSYS) {
        return EBPF_CAP_NO_BPF_SYSCALL;
    }
    if ((ring_errno == EINVAL || ring_errno == EOPNOTSUPP || ring_errno == ENOTSUP)
            && (perf_errno == EINVAL || perf_errno == EOPNOTSUPP || perf_errno == ENOTSUP)) {
        return EBPF_CAP_NO_RINGBUF;
    }
    return EBPF_CAP_LOAD_FAILED;
#endif
}

const char *ebpf_cap_str(ebpf_cap_t cap) {
    switch (cap) {
        case EBPF_CAP_OK:             return "尝试启动";
        case EBPF_CAP_NO_BPF_SYSCALL: return "bpf 系统调用不可用";
        case EBPF_CAP_NO_RINGBUF:     return "eBPF 事件通道不可用";
        case EBPF_CAP_NO_UPROBE:      return "不支持 uprobe";
        case EBPF_CAP_LOAD_FAILED:    return "BPF 程序加载失败";
        case EBPF_CAP_OBJ_NOT_FOUND:  return "找不到 BPF 字节码文件";
        default:                      return "未知";
    }
}

ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid, const char *target_pkg) {
    (void)bpf_obj_path;

    AppOptEbpfCtx *inner = appopt_ebpf_start_for_package((int)target_pid, bpf_obj_path, target_pkg);
    if (!inner) {
        return NULL;
    }

    ebpf_fps_ctx *ctx = (ebpf_fps_ctx *)calloc(1, sizeof(*ctx));
    if (!ctx) {
        appopt_ebpf_stop(inner);
        return NULL;
    }

    ctx->inner = inner;
    return ctx;
}

int ebpf_fps_poll(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->inner) {
        return -1;
    }
    return appopt_ebpf_poll(ctx->inner);
}

double ebpf_fps_get(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->inner) {
        return 0.0;
    }
    return appopt_ebpf_get(ctx->inner);
}

pid_t ebpf_fps_pid(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->inner) {
        return (pid_t)-1;
    }
    return (pid_t)appopt_ebpf_pid(ctx->inner);
}

const char *ebpf_fps_symbol(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->inner) {
        return NULL;
    }
    return appopt_ebpf_symbol(ctx->inner);
}

const char *ebpf_fps_backend(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->inner) {
        return NULL;
    }
    return appopt_ebpf_backend(ctx->inner);
}

const char *ebpf_fps_startup_note(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->inner) {
        return NULL;
    }
    return appopt_ebpf_startup_note(ctx->inner);
}

const char *ebpf_fps_last_error(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->inner) {
        return appopt_ebpf_last_start_error();
    }
    return appopt_ebpf_last_error(ctx->inner);
}

void ebpf_fps_stop(ebpf_fps_ctx *ctx) {
    if (!ctx) {
        return;
    }
    if (ctx->inner) {
        appopt_ebpf_stop(ctx->inner);
    }
    free(ctx);
}
