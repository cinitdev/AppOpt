/* ebpf_fps.c - Rust/aya 帧率分析桥接层的 C API 适配文件。
 *
 * 这个文件保留 AppOpt.c 已经在使用的 ebpf_fps_* 接口。
 * 真正的 uprobe attach、BPF 加载和 ringbuf 读取逻辑在 Rust 中实现。
 */
#include "ebpf_fps.h"

#include <stdlib.h>

typedef struct AppOptEbpfCtx AppOptEbpfCtx;

extern AppOptEbpfCtx *appopt_ebpf_start(int pid, const char *bpf_obj_path);
extern AppOptEbpfCtx *appopt_ebpf_start_for_package(int pid, const char *bpf_obj_path, const char *target_pkg);
extern int appopt_ebpf_poll(AppOptEbpfCtx *ctx);
extern double appopt_ebpf_get(const AppOptEbpfCtx *ctx);
extern int appopt_ebpf_pid(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_symbol(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_last_error(const AppOptEbpfCtx *ctx);
extern const char *appopt_ebpf_last_start_error(void);
extern void appopt_ebpf_stop(AppOptEbpfCtx *ctx);

struct ebpf_fps_ctx {
    AppOptEbpfCtx *inner;
};

ebpf_cap_t ebpf_fps_probe_capability(void) {
    /*
     * Rust bridge 会在启动时执行真正的 BPF 和 uprobe 检查，因为 attach
     * 需要具体的目标 PID。这里返回 OK 是为了保持 AppOpt 现有的 fallback
     * 流程：如果 start 失败，上层会降级到 SurfaceFlinger。
     */
    return EBPF_CAP_OK;
}

const char *ebpf_cap_str(ebpf_cap_t cap) {
    switch (cap) {
        case EBPF_CAP_OK:             return "可用";
        case EBPF_CAP_NO_BPF_SYSCALL: return "bpf 系统调用不可用";
        case EBPF_CAP_NO_RINGBUF:     return "内核不支持 RingBuf";
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
