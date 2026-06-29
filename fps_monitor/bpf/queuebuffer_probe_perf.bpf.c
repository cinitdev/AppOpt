/* SPDX-License-Identifier: GPL-2.0 */
/* BPF 程序: uprobe 探测 android::Surface 帧提交函数。
 * PerfEvent 备用通道, 用于 RingBuf 用户态 mmap 不可用的 Android 内核。
 */

#define SEC(NAME) __attribute__((section(NAME), used))

/* BPF_MAP_TYPE 常量 */
#define BPF_MAP_TYPE_PERF_EVENT_ARRAY 4
#define BPF_F_CURRENT_CPU 0xffffffffULL

/* map 定义宏: __uint 是"数组长度"技巧, 内核 BPF loader 通过 sizeof 读属性 */
#define __uint(name, val) int(*name)[val]

/* 获取单调递增内核时间戳(纳秒) */
static unsigned long long (*bpf_ktime_get_ns)(void) = (void *)5;

/* 获取当前进程的 TGID(高32位)和 TID(低32位) */
static long (*bpf_get_current_pid_tgid)(void) = (void *)14;

/* PerfEvent 输出 */
static long (*bpf_perf_event_output)(void *ctx, void *map, unsigned long long flags, void *data, unsigned long long size) = (void *)25;

/* --- 发送给用户态的帧事件 --- */
struct frame_event {
    unsigned long long timestamp_ns;   /* bpf_ktime_get_ns() 时间戳 */
    unsigned int pid;                  /* 进程 TGID */
    unsigned int tid;                  /* 线程 ID */
    unsigned long long surface_ptr;    /* arm64 x0 参数(Surface/ANativeWindow 指针) */
};

/* --- PerfEvent map --- */
struct {
    __uint(type, BPF_MAP_TYPE_PERF_EVENT_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 0);
} events SEC(".maps");

/* --- uprobe 程序: 挂载 libgui 帧提交函数的入口 --- */
SEC("uprobe/libgui_queuebuffer")
int on_queue_buffer(void *ctx) {
    struct frame_event event = {};

    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    event.timestamp_ns = bpf_ktime_get_ns();
    event.pid = (unsigned int)(pid_tgid >> 32);
    event.tid = (unsigned int)pid_tgid;
    event.surface_ptr = 0;

    bpf_perf_event_output(ctx, &events, BPF_F_CURRENT_CPU, &event, sizeof(event));
    return 0;
}

char _license[] SEC("license") = "GPL";
