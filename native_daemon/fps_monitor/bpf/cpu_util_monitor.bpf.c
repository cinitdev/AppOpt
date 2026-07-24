/* SPDX-License-Identifier: GPL-2.0 */
/* 轻量 sched_switch 利用率采样器，仅记录每个 CPU 的忙碌时间。 */

#define SEC(NAME) __attribute__((section(NAME), used))
#define __always_inline inline __attribute__((always_inline))
#define BPF_MAP_TYPE_PERCPU_ARRAY 6
#define BPF_ANY 0
#define __uint(name, val) int(*name)[val]

static unsigned long long (*bpf_ktime_get_ns)(void) = (void *)5;
static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)1;

/* sched_switch tracepoint 的稳定前缀，prev_pid 位于固定偏移 24。 */
struct sched_switch_args {
    unsigned short common_type;
    unsigned char common_flags;
    unsigned char common_preempt_count;
    int common_pid;
    char prev_comm[16];
    int prev_pid;
};

struct cpu_util_sample {
    unsigned long long last_ts;
    unsigned long long busy_ns;
    unsigned long long total_ns;
};

struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(struct cpu_util_sample));
    __uint(max_entries, 1);
} cpu_util SEC(".maps");

/* sched_switch 的 current task 是切换出去的任务，正好对应上一段运行时间。 */
SEC("tracepoint/sched/sched_switch")
int appopt_sched_switch(struct sched_switch_args *ctx) {
    unsigned int key = 0;
    struct cpu_util_sample *sample = bpf_map_lookup_elem(&cpu_util, &key);
    if (!sample) {
        return 0;
    }

    unsigned long long now = bpf_ktime_get_ns();
    if (sample->last_ts != 0 && now >= sample->last_ts) {
        unsigned long long delta = now - sample->last_ts;
        /* 防止 suspend/resume 或时钟异常把一次采样拉成超长区间。 */
        if (delta <= 2000000000ULL) {
            sample->total_ns += delta;
            if (ctx->prev_pid != 0) {
                sample->busy_ns += delta;
            }
        }
    }
    sample->last_ts = now;
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
