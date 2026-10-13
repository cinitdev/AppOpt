/* SPDX-License-Identifier: GPL-2.0 */
/* BPF 程序: uprobe 探测 BLAST 帧合成完成回调。
 * PerfEvent 备用通道, 用于 RingBuf 用户态 mmap 不可用的 Android 内核。
 */

#define SEC(NAME) __attribute__((section(NAME), used))
#define __always_inline inline __attribute__((always_inline))

/* BPF_MAP_TYPE 常量 */
#define BPF_MAP_TYPE_HASH 1
#define BPF_MAP_TYPE_ARRAY 2
#define BPF_ANY 0
#if !defined(QIXIA_FRAME_STATS_MAX_ENTRIES)
#define QIXIA_FRAME_STATS_MAX_ENTRIES 4096
#endif
#if !defined(QIXIA_STATS_ONLY_BPF)
#define BPF_MAP_TYPE_PERF_EVENT_ARRAY 4
#define BPF_F_CURRENT_CPU 0xffffffffULL
#endif

/* map 定义宏: __uint 是"数组长度"技巧, 内核 BPF loader 通过 sizeof 读属性 */
#define __uint(name, val) int(*name)[val]

/* 获取单调递增内核时间戳(纳秒) */
static unsigned long long (*bpf_ktime_get_ns)(void) = (void *)5;

/* 获取当前进程的 TGID(高32位)和 TID(低32位) */
static long (*bpf_get_current_pid_tgid)(void) = (void *)14;
static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)1;
static long (*bpf_map_update_elem)(void *map, const void *key, const void *value, unsigned long long flags) = (void *)2;

/* PerfEvent 输出；StatsMap 对象不声明事件通道及其 helper。 */
#if !defined(QIXIA_STATS_ONLY_BPF)
static long (*bpf_perf_event_output)(void *ctx, void *map, unsigned long long flags, void *data, unsigned long long size) = (void *)25;
#endif
/* helper 4 兼容 Android 旧内核；只在 32 位 x86 参数读取分支使用。 */
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

#include "frame_report.h"

/* 目标线程 uprobe 的 TGID 白名单，由 Rust bridge 随进程变化增量更新。 */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 128);
} target_tgids SEC(".maps");

/* --- PerfEvent map --- */
#if !defined(QIXIA_STATS_ONLY_BPF)
struct {
    __uint(type, BPF_MAP_TYPE_PERF_EVENT_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 0);
} events SEC(".maps");
#endif

/* --- 内核侧帧计数 map ---
 * PerfEvent 是 per-CPU 事件通道，用户态读取时可能乱序/丢样本。
 * 这里在 BPF 内部按 pid + surface/tid 计数，用户态可以轮询计数差来计算 FPS。
 */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(struct frame_stats_key));
    __uint(value_size, sizeof(struct frame_stats_value));
    __uint(max_entries, QIXIA_FRAME_STATS_MAX_ENTRIES);
} frame_stats SEC(".maps");

/* frame_stats 满时保留累计失败次数；事件后端仍会发送逐帧事件。 */
struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 1);
} frame_stats_drops SEC(".maps");

/* 用户态确认 frame_stats 可读后写 1。默认值 0 保留逐帧 PerfEvent 兼容路径。 */
#if !defined(QIXIA_STATS_ONLY_BPF)
struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 1);
} perf_stats_only SEC(".maps");
#endif

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
        record_frame_report(&key, event->timestamp_ns, event->timestamp_ns - value->last_ts);
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

/* --- uprobe 程序: 挂载 libgui 帧合成完成回调入口 --- */
SEC("uprobe/libgui_frame_completed")
int on_frame_completed(void *ctx) {
    struct frame_event event = {};

    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    event.pid = (unsigned int)(pid_tgid >> 32);
    unsigned int *abi = bpf_map_lookup_elem(&target_tgids, &event.pid);
    if (!abi) {
        return 0;
    }

    event.timestamp_ns = qixia_read_latch(ctx, *abi);
    if (!qixia_valid_latch(event.timestamp_ns, bpf_ktime_get_ns())) return 0;
    event.tid = (unsigned int)pid_tgid;
    event.surface_ptr = qixia_read_surface(ctx, *abi);
    if (!event.surface_ptr) return 0;

    int stats_result = record_frame_stats(&event);
    if (stats_result == 0) {
        return 0;
    }

    /* StatsMap 后端只更新计数 map，不创建任何事件传输通道。 */
#if !defined(QIXIA_STATS_ONLY_BPF)
    unsigned int config_key = 0;
    unsigned int *stats_only = bpf_map_lookup_elem(&perf_stats_only, &config_key);
    if (stats_only && *stats_only) {
        return 0;
    }

    bpf_perf_event_output(ctx, &events, BPF_F_CURRENT_CPU, &event, sizeof(event));
#endif
    return 0;
}

char _license[] SEC("license") = "GPL";
