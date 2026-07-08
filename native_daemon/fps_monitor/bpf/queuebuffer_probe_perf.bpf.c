/* SPDX-License-Identifier: GPL-2.0 */
/* BPF 程序: uprobe 探测 android::Surface 帧提交函数。
 * PerfEvent 备用通道, 用于 RingBuf 用户态 mmap 不可用的 Android 内核。
 */

#define SEC(NAME) __attribute__((section(NAME), used))
#define __always_inline inline __attribute__((always_inline))

/* BPF_MAP_TYPE 常量 */
#define BPF_MAP_TYPE_PERF_EVENT_ARRAY 4
#define BPF_MAP_TYPE_HASH 1
#define BPF_F_CURRENT_CPU 0xffffffffULL
#define BPF_ANY 0

/* map 定义宏: __uint 是"数组长度"技巧, 内核 BPF loader 通过 sizeof 读属性 */
#define __uint(name, val) int(*name)[val]

/* 获取单调递增内核时间戳(纳秒) */
static unsigned long long (*bpf_ktime_get_ns)(void) = (void *)5;

/* 获取当前进程的 TGID(高32位)和 TID(低32位) */
static long (*bpf_get_current_pid_tgid)(void) = (void *)14;
static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)1;
static long (*bpf_map_update_elem)(void *map, const void *key, const void *value, unsigned long long flags) = (void *)2;

/* PerfEvent 输出 */
static long (*bpf_perf_event_output)(void *ctx, void *map, unsigned long long flags, void *data, unsigned long long size) = (void *)25;
static long (*bpf_probe_read_user)(void *dst, unsigned long long size, const void *unsafe_ptr) = (void *)112;

/* --- uprobe 参数读取 ---
 * queueBuffer 的第一个参数是 Surface/ANativeWindow 指针：
 * - arm64: x0
 * - arm: r0
 * - x86_64: rdi
 * - x86: 用户栈 esp + 4，esp 指向返回地址
 */
#if defined(__TARGET_ARCH_arm64)
struct appopt_pt_regs {
    unsigned long long regs[31];
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

static __always_inline unsigned long long appopt_read_parm1(void *ctx) {
    return ((struct appopt_pt_regs *)ctx)->regs[0];
}
#elif defined(__TARGET_ARCH_arm)
struct appopt_pt_regs {
    unsigned int uregs[18];
};

static __always_inline unsigned long long appopt_read_parm1(void *ctx) {
    return (unsigned long long)((struct appopt_pt_regs *)ctx)->uregs[0];
}
#elif defined(APPOPT_BPF_X86_64)
struct appopt_pt_regs {
    unsigned long long r15;
    unsigned long long r14;
    unsigned long long r13;
    unsigned long long r12;
    unsigned long long bp;
    unsigned long long bx;
    unsigned long long r11;
    unsigned long long r10;
    unsigned long long r9;
    unsigned long long r8;
    unsigned long long ax;
    unsigned long long cx;
    unsigned long long dx;
    unsigned long long si;
    unsigned long long di;
    unsigned long long orig_ax;
    unsigned long long ip;
    unsigned long long cs;
    unsigned long long flags;
    unsigned long long sp;
    unsigned long long ss;
};

static __always_inline unsigned long long appopt_read_parm1(void *ctx) {
    return ((struct appopt_pt_regs *)ctx)->di;
}
#elif defined(APPOPT_BPF_I386)
struct appopt_pt_regs {
    unsigned int bx;
    unsigned int cx;
    unsigned int dx;
    unsigned int si;
    unsigned int di;
    unsigned int bp;
    unsigned int ax;
    unsigned int ds;
    unsigned int es;
    unsigned int fs;
    unsigned int gs;
    unsigned int orig_ax;
    unsigned int ip;
    unsigned int cs;
    unsigned int flags;
    unsigned int sp;
    unsigned int ss;
};

static __always_inline unsigned long long appopt_read_parm1(void *ctx) {
    unsigned int value = 0;
    unsigned int sp = ((struct appopt_pt_regs *)ctx)->sp;
    if (sp == 0) {
        return 0;
    }
    if (bpf_probe_read_user(&value, sizeof(value), (const void *)(unsigned long long)(sp + 4)) != 0) {
        return 0;
    }
    return (unsigned long long)value;
}
#else
static __always_inline unsigned long long appopt_read_parm1(void *ctx) {
    (void)ctx;
    return 0;
}
#endif

/* --- 发送给用户态的帧事件 --- */
struct frame_event {
    unsigned long long timestamp_ns;   /* bpf_ktime_get_ns() 时间戳 */
    unsigned int pid;                  /* 进程 TGID */
    unsigned int tid;                  /* 线程 ID */
    unsigned long long surface_ptr;    /* arm64 x0 参数(Surface/ANativeWindow 指针) */
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

/* --- PerfEvent map --- */
struct {
    __uint(type, BPF_MAP_TYPE_PERF_EVENT_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 0);
} events SEC(".maps");

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
    key.tid = event->tid;
    key.surface_ptr = event->surface_ptr;

    struct frame_stats_value *value = bpf_map_lookup_elem(&frame_stats, &key);
    if (value) {
        if (event->timestamp_ns <= value->last_ts) {
            return 0;
        }
        /* 多个 queueBuffer 符号可能命中同一次提交, 1ms 内同 stream 视为重复事件。 */
        if (event->timestamp_ns - value->last_ts < 1000000ULL) {
            return 0;
        }
        value->last_ts = event->timestamp_ns;
        value->total_frames += 1;
        return 1;
    }

    struct frame_stats_value initial = {};
    initial.last_ts = event->timestamp_ns;
    initial.total_frames = 1;
    bpf_map_update_elem(&frame_stats, &key, &initial, BPF_ANY);
    return 1;
}

/* --- uprobe 程序: 挂载 libgui 帧提交函数的入口 --- */
SEC("uprobe/libgui_queuebuffer")
int on_queue_buffer(void *ctx) {
    struct frame_event event = {};

    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    event.timestamp_ns = bpf_ktime_get_ns();
    event.pid = (unsigned int)(pid_tgid >> 32);
    event.tid = (unsigned int)pid_tgid;
    event.surface_ptr = appopt_read_parm1(ctx);

    if (!record_frame_stats(&event)) {
        return 0;
    }

    bpf_perf_event_output(ctx, &events, BPF_F_CURRENT_CPU, &event, sizeof(event));
    return 0;
}

char _license[] SEC("license") = "GPL";
