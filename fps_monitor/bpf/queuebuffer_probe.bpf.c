/* queuebuffer_probe.bpf.c —— eBPF 帧率监测内核程序
 *
 * 简化版：不依赖 libbpf 头文件，只用内核 uapi
 * 通过 uprobe 钩取 libgui.so 的 queueBuffer 函数
 */
#include <linux/bpf.h>
#include <linux/types.h>

/* BPF helper 函数声明 */
static void *(*bpf_ringbuf_reserve)(void *ringbuf, __u64 size, __u64 flags) = (void *) 131;
static void (*bpf_ringbuf_submit)(void *data, __u64 flags) = (void *) 132;
static __u64 (*bpf_ktime_get_ns)(void) = (void *) 5;

/* Section 宏 */
#define SEC(name) __attribute__((section(name), used))

/* Map 定义宏 */
#define __uint(name, val) int (*name)[val]
#define __type(name, val) typeof(val) *name

/* 帧信号数据结构 */
struct frame_signal {
    __u64 ktime_ns;  /* 内核时间戳（纳秒） */
    __u64 buffer;    /* buffer 指针 */
};

/* Ring Buffer Map */
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 4096);  /* 4KB */
} RING_BUF SEC(".maps");

/* 获取寄存器中的第一个参数（ARM64: x0, x86_64: rdi） */
#if defined(__aarch64__)
#define PT_REGS_PARM1(x) (((struct pt_regs_arm64 *)(x))->regs[0])
struct pt_regs_arm64 {
    __u64 regs[31];
    __u64 sp;
    __u64 pc;
    __u64 pstate;
};
#elif defined(__x86_64__)
#define PT_REGS_PARM1(x) (((struct pt_regs_x86 *)(x))->di)
struct pt_regs_x86 {
    __u64 r15, r14, r13, r12, bp, bx;
    __u64 r11, r10, r9, r8, ax, cx, dx, si, di;
    __u64 orig_ax, ip, cs, flags, sp, ss;
};
#else
#define PT_REGS_PARM1(x) 0
#endif

/* uprobe 入口点 */
SEC("uprobe/queueBuffer")
int frame_analyzer_ebpf(void *ctx) {
    struct frame_signal *signal;

    /* 从 ring buffer 预留空间 */
    signal = bpf_ringbuf_reserve(&RING_BUF, sizeof(*signal), 0);
    if (!signal) {
        return 0;
    }

    /* 获取当前内核时间戳 */
    signal->ktime_ns = bpf_ktime_get_ns();

    /* 获取第一个参数（buffer 指针）*/
    signal->buffer = PT_REGS_PARM1(ctx);

    /* 提交数据到 ring buffer */
    bpf_ringbuf_submit(signal, 0);

    return 0;
}

char LICENSE[] SEC("license") = "GPL";
