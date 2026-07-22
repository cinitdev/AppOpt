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
 * queueBuffer/hook_queueBuffer 符号，并从 RingBuf/PerfEvent 读取帧提交事件。
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
typedef struct AppOptJankCtx AppOptJankCtx;

typedef struct {
    double fps;
    uint64_t median_interval_ns;
    uint64_t p95_interval_ns;
    uint64_t max_interval_ns;
    uint32_t frame_count;
    uint32_t flags;
} AppOptFrameMetrics;

/* eBPF 预检查或初始化状态，用于日志/UI 展示。 */
typedef enum {
    EBPF_CAP_OK = 0,            /* eBPF 路径可尝试启动 */
    EBPF_CAP_NO_BPF_SYSCALL,    /* bpf() 系统调用不可用或被禁用 */
    EBPF_CAP_NO_RINGBUF,        /* RingBuf/PerfEvent 事件通道不可用 */
    EBPF_CAP_NO_UPROBE,         /* 当前环境不支持 uprobe attach */
    EBPF_CAP_LOAD_FAILED,       /* BPF 程序加载失败，例如 verifier 拒绝 */
    EBPF_CAP_OBJ_NOT_FOUND,     /* 找不到 queuebuffer_probe.bpf.o */
} ebpf_cap_t;

/* 探测当前 eBPF 路径是否可以尝试启动。
 *
 * 这里会先检查 BPF 对象是否存在、bpf() 系统调用是否可用、当前内核是否支持
 * RingBuf 或 PerfEvent 通道。真正的 uprobe attach 仍由 Rust/aya 在 ebpf_fps_start() 内完成，
 * 因为 attach 需要具体目标 PID。
 */
ebpf_cap_t ebpf_fps_probe_capability(const char *bpf_obj_path);

/* 把 ebpf_cap_t 转成中文说明，供日志/UI 使用。 */
const char *ebpf_cap_str(ebpf_cap_t cap);

/* 启动目标进程的 eBPF FPS 采集。
 *
 * bpf_obj_path: 编译好的 BPF 字节码文件，例如 queuebuffer_probe.bpf.o
 * target_pid:   目标游戏进程 PID；必须大于 0，Android 侧不再启用全局 uprobe
 * target_pkg:   目标包名，用于校验/日志，避免同包多进程切换时误记其它应用
 *
 * 成功返回 ctx；失败返回 NULL，上层应降级到 fallback。
 */
ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid, const char *target_pkg);

/* 非阻塞消费 eBPF 事件通道中已积累的帧事件，并更新内部 FPS 统计。
 * 建议上层周期性调用，例如每 100ms 一次。
 * 返回本次消费的事件数；失败返回 -1。
 */
int ebpf_fps_poll(ebpf_fps_ctx *ctx);

/* 获取最近一次计算出的 FPS。
 * 刚启动、无事件或无有效数据时返回 0.0。
 */
double ebpf_fps_get(ebpf_fps_ctx *ctx);

/* 读取当前活跃 Surface 的帧间隔统计；无有效逐帧数据时返回 false。 */
bool ebpf_fps_metrics(ebpf_fps_ctx *ctx, AppOptFrameMetrics *out);

/* 获取当前 eBPF 实际锁定的 PID。
 * 指定 PID 启动时返回该 PID。
 * 未锁定或 ctx 无效时返回 -1。
 */
pid_t ebpf_fps_pid(ebpf_fps_ctx *ctx);

/* 获取当前成功 attach 的 libgui.so 符号名，用于日志/UI。
 * 尚未锁定或 ctx 无效时返回 NULL。
 */
const char *ebpf_fps_symbol(ebpf_fps_ctx *ctx);

/* 获取当前事件通道后端: RingBuf 或 PerfEvent。 */
const char *ebpf_fps_backend(ebpf_fps_ctx *ctx);

/* 获取本次 eBPF 启动过程中的非致命提示，例如 RingBuf 降级到 PerfEvent。 */
const char *ebpf_fps_startup_note(ebpf_fps_ctx *ctx);

/* 获取最近一次 eBPF 启动或运行错误。
 * ctx 为 NULL 时返回最近一次启动失败原因。
 */
const char *ebpf_fps_last_error(ebpf_fps_ctx *ctx);

/* 停止采集并释放资源。调用后 ctx 失效。 */
void ebpf_fps_stop(ebpf_fps_ctx *ctx);

/* 卡顿增强执行器由 C/Rust 共用；不会修改线程亲和性或 applist.conf。 */
AppOptJankCtx *appopt_jank_create(const char *pkg);
int appopt_jank_recover(void);
int appopt_jank_update(AppOptJankCtx *ctx, int pid, double fps,
                       const AppOptFrameMetrics *metrics);
const char *appopt_jank_last_event(const AppOptJankCtx *ctx);
void appopt_jank_stop(AppOptJankCtx *ctx);

#endif /* APPOPT_EBPF_FPS_H */
