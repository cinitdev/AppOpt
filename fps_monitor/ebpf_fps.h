/* ebpf_fps.h - AppOpt 游戏 FPS 采集的 C 接口
 *
 * 这个头文件是 AppOpt.c 和 fps_monitor/ebpf_fps.c 之间的稳定接口。
 * 当前实现不是旧的纯 C eBPF loader，而是:
 *
 *   AppOpt.c
 *     -> ebpf_fps_* C API
 *     -> fps_monitor/ebpf_fps.c
 *     -> appopt_ebpf_bridge Rust/aya staticlib
 *     -> queuebuffer_probe.bpf.o
 *
 * Rust/aya 负责加载 BPF 字节码、attach 目标游戏进程 libgui.so 的
 * queueBuffer/hook_queueBuffer 符号，并从 ringbuf 读取帧提交事件。
 * C 层只保留统一的启动、轮询、读取 FPS、停止接口，方便 AppOpt.c 使用。
 *
 * 如果 eBPF 初始化失败，上层会降级到 SurfaceFlinger fallback。
 */
#ifndef APPOPT_EBPF_FPS_H
#define APPOPT_EBPF_FPS_H

#include <stdbool.h>
#include <stdint.h>
#include <sys/types.h>

/* 不透明句柄: 内部实际持有 Rust/aya bridge 的上下文。 */
typedef struct ebpf_fps_ctx ebpf_fps_ctx;

/* eBPF 能力或初始化状态，用于日志/UI 展示。 */
typedef enum {
    EBPF_CAP_OK = 0,            /* eBPF 路径可尝试启动 */
    EBPF_CAP_NO_BPF_SYSCALL,    /* bpf() 系统调用不可用或被禁用 */
    EBPF_CAP_NO_RINGBUF,        /* 内核不支持 BPF_MAP_TYPE_RINGBUF */
    EBPF_CAP_NO_UPROBE,         /* 当前环境不支持 uprobe attach */
    EBPF_CAP_LOAD_FAILED,       /* BPF 程序加载失败，例如 verifier 拒绝 */
    EBPF_CAP_OBJ_NOT_FOUND,     /* 找不到 queuebuffer_probe.bpf.o */
} ebpf_cap_t;

/* 探测当前 eBPF 路径是否可以尝试启动。
 *
 * 现在真正的 BPF 加载和 uprobe attach 由 Rust/aya 在 ebpf_fps_start()
 * 内完成，因为 attach 需要具体目标 PID。这里保留接口用于兼容 AppOpt.c
 * 现有流程。
 */
ebpf_cap_t ebpf_fps_probe_capability(void);

/* 把 ebpf_cap_t 转成中文说明，供日志/UI 使用。 */
const char *ebpf_cap_str(ebpf_cap_t cap);

/* 启动目标进程的 eBPF FPS 采集。
 *
 * bpf_obj_path: 编译好的 BPF 字节码文件，例如 queuebuffer_probe.bpf.o
 * target_pid:   目标游戏进程 PID；传 -1 表示全局探测后按 target_pkg 锁定
 * target_pkg:   目标包名，用于全局探测模式下过滤帧事件 PID
 *
 * 成功返回 ctx；失败返回 NULL，上层应降级到 fallback。
 */
ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid, const char *target_pkg);

/* 非阻塞消费 ringbuf 中已积累的帧事件，并更新内部 FPS 统计。
 * 建议上层周期性调用，例如每 100ms 一次。
 * 返回本次消费的事件数；失败返回 -1。
 */
int ebpf_fps_poll(ebpf_fps_ctx *ctx);

/* 获取最近一次计算出的 FPS。
 * 刚启动、无事件或无有效数据时返回 0.0。
 */
double ebpf_fps_get(ebpf_fps_ctx *ctx);

/* 获取当前 eBPF 实际锁定的 PID。
 * 指定 PID 启动时返回该 PID；全局探测模式下，收到第一条帧事件后返回事件 PID。
 * 未锁定或 ctx 无效时返回 -1。
 */
pid_t ebpf_fps_pid(ebpf_fps_ctx *ctx);

/* 获取当前成功 attach 的 libgui.so 符号名，用于日志/UI。
 * 尚未锁定或 ctx 无效时返回 NULL。
 */
const char *ebpf_fps_symbol(ebpf_fps_ctx *ctx);

/* 获取最近一次 eBPF 启动或运行错误。
 * ctx 为 NULL 时返回最近一次启动失败原因。
 */
const char *ebpf_fps_last_error(ebpf_fps_ctx *ctx);

/* 停止采集并释放资源。调用后 ctx 失效。 */
void ebpf_fps_stop(ebpf_fps_ctx *ctx);

#endif /* APPOPT_EBPF_FPS_H */
