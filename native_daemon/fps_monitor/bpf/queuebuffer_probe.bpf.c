/* SPDX-License-Identifier: GPL-2.0 */
/* BPF 程序: uprobe 探测 BLAST 帧合成完成回调。
 * 编译: clang -target bpf -g -O2 -c queuebuffer_probe.bpf.c -o queuebuffer_probe.bpf.o
 *
 * 本文件不 #include <linux/bpf.h> 或 <bpf/bpf_helpers.h>, 改用内建函数声明
 * 和 __attribute__ section 宏, 避免对 asm/types.h 等内核头文件的依赖,
 * 从而可在 NDK clang 环境下直接编译。 */

/* --- 属性/布局宏(替代 bpf_helpers.h) --- */
#define SEC(NAME) __attribute__((section(NAME), used))
#define __always_inline inline __attribute__((always_inline))

/* BPF_MAP_TYPE 常量 */
#define BPF_MAP_TYPE_RINGBUF 27
#define BPF_MAP_TYPE_HASH 1
#define BPF_MAP_TYPE_ARRAY 2
#define BPF_ANY 0

/* map 定义宏: __uint 是"数组长度"技巧, 内核 BPF loader 通过 sizeof 读属性 */
#define __uint(name, val) int(*name)[val]

/* --- BPF helper 函数声明(clang -target bpf 内建识别) ---
 * 这些是用函数指针声明 BPF helper 的"标准技巧", 编号对应
 * include/uapi/linux/bpf.h 中的 __BPF_FUNC_MAPPER 枚举。
 * clang -target bpf 会根据这些编号生成正确的 BPF 调用指令。 */

/* 获取单调递增内核时间戳(纳秒) */
static unsigned long long (*bpf_ktime_get_ns)(void) = (void *)5;

/* 获取当前进程的 TGID(高32位)和 TID(低32位) */
static long (*bpf_get_current_pid_tgid)(void) = (void *)14;
static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)1;
static long (*bpf_map_update_elem)(void *map, const void *key, const void *value, unsigned long long flags) = (void *)2;

/* RingBuf 操作 */
static void *(*bpf_ringbuf_reserve)(void *ringbuf, unsigned long long size, long long flags) = (void *)131;
static void (*bpf_ringbuf_submit)(void *data, long long flags) = (void *)132;
static void (*bpf_ringbuf_discard)(void *data, long long flags) = (void *)133;

/* 输出到 trace_pipe(调试用) */
static long (*bpf_trace_printk)(const char *fmt, unsigned long long fmt_size, ...) = (void *)6;
/* helper 4 同时兼容旧 Android 内核；只用于 32 位 x86 compat 参数读取。 */
static long (*bpf_probe_read_compat)(void *dst, unsigned long long size, const void *unsafe_ptr) = (void *)4;

#include "frame_callback.h"

/* --- 发送给用户态的帧事件 --- */
struct frame_event {
    unsigned long long timestamp_ns;   /* 完成回调携带的合成时间戳 */
    unsigned int pid;                  /* 进程 TGID */
    unsigned int tid;                  /* 线程 ID */
    unsigned long long surface_ptr;    /* BLAST 队列指针 */
};

struct frame_stats_key {
    unsigned int pid;
    unsigned int tid;
    unsigned long long surface_ptr;
};

struct frame_stats_value {
    unsigned long long last_ts;
    unsigned long long total_frames;
};

/* 仅允许用户态登记过的目标进程触发帧统计。
 * 用户态为目标进程的线程挂载 uprobe，这里再按 TGID 过滤，避免进程切换期间接收旧目标事件。 */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 128);
} target_tgids SEC(".maps");

/* --- RingBuf map --- */
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 4096);         /* 4KB, 约可缓冲 170 个事件 */
} events SEC(".maps");

/* RingBuf 满时保留累计丢帧数，用户态限频报告，避免静默低估 FPS。 */
struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 1);
} ringbuf_drops SEC(".maps");

/* frame_stats 满时保留累计失败次数；事件后端仍会发送逐帧事件，避免静默低估。 */
struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 1);
} frame_stats_drops SEC(".maps");

/* --- 内核侧帧计数 map ---
 * PerfEvent 是 per-CPU 事件通道，用户态读取时可能乱序/丢样本。
 * 这里在 BPF 内部按 pid + surface/tid 计数，用户态可以轮询计数差来计算 FPS。
 */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(struct frame_stats_key));
    __uint(value_size, sizeof(struct frame_stats_value));
    __uint(max_entries, 4096);
} frame_stats SEC(".maps");

static __always_inline int record_frame_stats(struct frame_event *event) {
    struct frame_stats_key key = {};
    key.pid = event->pid;
    key.tid = event->surface_ptr ? 0 : event->tid;
    key.surface_ptr = event->surface_ptr;

    struct frame_stats_value *value = bpf_map_lookup_elem(&frame_stats, &key);
    if (value) {
        if (event->timestamp_ns <= value->last_ts) {
            return 0;
        }
        /* 同一队列在同一合成时刻的多次通知只计一帧，跨 Binder 线程共享去重。 */
        if (event->timestamp_ns - value->last_ts < 1000000ULL) {
            return 0;
        }
        struct frame_stats_value next = {event->timestamp_ns, value->total_frames + 1};
        /* 一次替换完整快照，避免用户态读到新时间戳和旧计数。 */
        bpf_map_update_elem(&frame_stats, &key, &next, BPF_ANY);
        return 1;
    }

    struct frame_stats_value initial = {};
    initial.last_ts = event->timestamp_ns;
    initial.total_frames = 1;
    if (bpf_map_update_elem(&frame_stats, &key, &initial, BPF_ANY) != 0) {
        unsigned int drop_key = 0;
        unsigned int *drops = bpf_map_lookup_elem(&frame_stats_drops, &drop_key);
        if (drops) {
            __sync_fetch_and_add(drops, 1);
        }
        return -1;
    }
    return 1;
}

/* --- uprobe 程序: 挂载 libgui 帧合成完成回调入口 ---
 * attach 时指定:
 *   - binary:  /system/lib64/libgui.so (或 /system/lib/libgui.so)
 *   - symbol:  候选符号(按优先级尝试)
 *   - pid:     目标游戏进程(限制仅该进程触发)
 * 被探测函数每次被调用时, 此程序在内核态执行。
 * 记录回调携带的合成时间戳和队列, 通过 RingBuf 发到用户态。 */
SEC("uprobe/libgui_frame_completed")
int on_frame_completed(void *ctx) {
    struct frame_event local = {};

    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    local.pid = (unsigned int)(pid_tgid >> 32);
    unsigned int *abi = bpf_map_lookup_elem(&target_tgids, &local.pid);
    if (!abi) {
        return 0;
    }

    local.timestamp_ns = qixia_read_latch(ctx, *abi);
    if (!qixia_valid_latch(local.timestamp_ns, bpf_ktime_get_ns())) return 0;
    local.tid = (unsigned int)pid_tgid;

    local.surface_ptr = qixia_read_surface(ctx, *abi);
    if (!local.surface_ptr) return 0;

    int stats_result = record_frame_stats(&local);
    if (stats_result == 0) {
        return 0;
    }

    /* 在 RingBuf 预留空间(原子操作, 不阻塞) */
    struct frame_event *event = bpf_ringbuf_reserve(&events, sizeof(*event), 0);
    if (!event) {
        unsigned int key = 0;
        unsigned int *drops = bpf_map_lookup_elem(&ringbuf_drops, &key);
        if (drops) {
            __sync_fetch_and_add(drops, 1);
        }
        return 0;
    }
    *event = local;

    /* 提交到 RingBuf, 用户态可读 */
    bpf_ringbuf_submit(event, 0);
    return 0;
}

char _license[] SEC("license") = "GPL";
