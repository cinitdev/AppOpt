/* ebpf_fps.h —— AppOpt 基于 eBPF uprobe 的游戏提交帧率(Submit FPS)采集模块
 *
 * 设计目标(详见 AppOpt_FPS_eBPF_tracefs_libgui_symbol_design_v2.md):
 *   - 在目标游戏进程的 libgui.so 帧提交函数(hook_queueBuffer/queueBuffer 等)
 *     上挂 eBPF uprobe, 每次提交一帧由内核记录时间戳并经 RingBuf 发给用户态;
 *   - 用户态据此算瞬时 FPS / 帧间隔。不注入游戏、不依赖 tracefs、不写死 offset。
 *
 * 与旧的 dumpsys --latency/--timestats 方案并行存在: 上层(AppOpt.c)优先尝试
 * eBPF, 失败时回退到旧方案。本模块仅负责 eBPF 这一档, 自包含、可独立编译/测试。
 */
#ifndef APPOPT_EBPF_FPS_H
#define APPOPT_EBPF_FPS_H

#include <stdbool.h>
#include <stdint.h>
#include <sys/types.h>

/* 不透明句柄: 持有 BPF 程序 fd、map fd、uprobe link、RingBuf 内存、统计状态等。 */
typedef struct ebpf_fps_ctx ebpf_fps_ctx;

/* eBPF 环境探测结果(供 UI/日志展示数据源能力) */
typedef enum {
    EBPF_CAP_OK = 0,            /* 一切就绪, eBPF 可用 */
    EBPF_CAP_NO_BPF_SYSCALL,    /* bpf() 系统调用被禁用/不存在 */
    EBPF_CAP_NO_RINGBUF,        /* 内核不支持 BPF_MAP_TYPE_RINGBUF */
    EBPF_CAP_NO_UPROBE,         /* 不支持 uprobe attach */
    EBPF_CAP_LOAD_FAILED,       /* BPF 程序加载失败(verifier 拒绝等) */
    EBPF_CAP_OBJ_NOT_FOUND,     /* 找不到 .bpf.o 文件 */
} ebpf_cap_t;

/* 探测当前内核/权限是否支持 eBPF 抓帧。无副作用(会创建并立即销毁一个测试 map)。
 * 返回 EBPF_CAP_OK 表示具备基本能力(仍需 attach 后验证事件)。 */
ebpf_cap_t ebpf_fps_probe_capability(void);

/* 把 ebpf_cap_t 转成可读字符串(中文), 供日志/UI 显示。 */
const char *ebpf_cap_str(ebpf_cap_t cap);

/* 启动对 target_pid 进程的帧率采集。
 *   bpf_obj_path: 编译好的 BPF 字节码文件路径(queuebuffer_probe.bpf.o)
 *   target_pid:   游戏进程 PID
 * 流程: 加载 BPF -> 解析该进程 libgui.so -> 按候选符号表逐个 attach uprobe ->
 *       每个符号 attach 后等待事件验证, 命中即锁定该符号开始采集。
 * 成功返回 ctx(非 NULL); 失败返回 NULL。 */
ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid);

/* 非阻塞地消费 RingBuf 中已积累的帧事件, 更新内部滑动窗口统计。
 * 应由调用方周期性调用(如每 100~200ms)。返回本次消费的事件数(>=0)。 */
int ebpf_fps_poll(ebpf_fps_ctx *ctx);

/* 取最近一次计算的瞬时 FPS(基于最近 1 秒窗口内的帧事件数 / 帧间隔)。
 * 无有效数据(刚启动/无事件)返回 0.0。 */
double ebpf_fps_get(ebpf_fps_ctx *ctx);

/* 取当前锁定的帧提交符号名(用于日志/UI), 未锁定返回 NULL。 */
const char *ebpf_fps_symbol(ebpf_fps_ctx *ctx);

/* 停止采集, detach uprobe, 释放所有资源。ctx 调用后失效。 */
void ebpf_fps_stop(ebpf_fps_ctx *ctx);

#endif /* APPOPT_EBPF_FPS_H */
