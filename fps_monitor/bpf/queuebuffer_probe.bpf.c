/* SPDX-License-Identifier: GPL-2.0 */
/* BPF 程序: uprobe 探测 android::Surface 帧提交函数。
 * 编译: clang -target bpf -g -O2 -c queuebuffer_probe.bpf.c -o queuebuffer_probe.bpf.o
 *
 * 本文件不 #include <linux/bpf.h> 或 <bpf/bpf_helpers.h>, 改用内建函数声明
 * 和 __attribute__ section 宏, 避免对 asm/types.h 等内核头文件的依赖,
 * 从而可在 NDK clang 环境下直接编译。 */

/* --- 属性/布局宏(替代 bpf_helpers.h) --- */
#define SEC(NAME) __attribute__((section(NAME), used))

/* BPF_MAP_TYPE 常量 */
#define BPF_MAP_TYPE_RINGBUF 27

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

/* RingBuf 操作 */
static void *(*bpf_ringbuf_reserve)(void *ringbuf, unsigned long long size, long long flags) = (void *)131;
static void (*bpf_ringbuf_submit)(void *data, long long flags) = (void *)132;
static void (*bpf_ringbuf_discard)(void *data, long long flags) = (void *)133;

/* 输出到 trace_pipe(调试用) */
static long (*bpf_trace_printk)(const char *fmt, unsigned long long fmt_size, ...) = (void *)6;

/* --- 发送给用户态的帧事件 --- */
struct frame_event {
    unsigned long long timestamp_ns;   /* bpf_ktime_get_ns() 时间戳 */
    unsigned int pid;                  /* 进程 TGID */
    unsigned int tid;                  /* 线程 ID */
    unsigned long long surface_ptr;    /* arm64 x0 参数(Surface/ANativeWindow 指针) */
};

/* --- RingBuf map --- */
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 4096);         /* 4KB, 约可缓冲 170 个事件 */
} events SEC(".maps");

/* --- uprobe 程序: 挂载 libgui 帧提交函数的入口 ---
 * attach 时指定:
 *   - binary:  /system/lib64/libgui.so (或 /system/lib/libgui.so)
 *   - symbol:  候选符号(按优先级尝试)
 *   - pid:     目标游戏进程(限制仅该进程触发)
 * 被探测函数每次被调用时, 此程序在内核态执行。
 * 记录当前时间戳和调用上下文, 通过 RingBuf 发到用户态。 */
SEC("uprobe/libgui_queuebuffer")
int on_queue_buffer(void *ctx) {
    struct frame_event *event;

    /* 在 RingBuf 预留空间(原子操作, 不阻塞) */
    event = bpf_ringbuf_reserve(&events, sizeof(*event), 0);
    if (!event) {
        /* RingBuf 满: 丢弃本帧(极少发生, 且无损功能) */
        return 0;
    }

    /* 记录帧事件 */
    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    event->timestamp_ns = bpf_ktime_get_ns();
    event->pid = (unsigned int)(pid_tgid >> 32);
    event->tid = (unsigned int)pid_tgid;

    /* 注: surface_ptr 需要读取 arm64 PT_REGS_PARM1(ctx)。
     * clang -target bpf 支持 __builtin_preserve_access_index
     * 读取 ctx->regs[0]。编译时 aya 的 BPF_PROG 宏会自动展开,
     * 若直接用 clang 原生编译, 需要引入对应框架宏。
     * 简化方案: 当前不采集 x0(不影响 FPS 计算)。 */
    event->surface_ptr = 0;

    /* 提交到 RingBuf, 用户态可读 */
    bpf_ringbuf_submit(event, 0);
    return 0;
}

char _license[] SEC("license") = "GPL";
