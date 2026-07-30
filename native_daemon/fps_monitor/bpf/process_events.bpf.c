/* SPDX-License-Identifier: GPL-2.0 */
/* AppOpt 进程/线程发现提示器；最终身份与规则命中必须由用户态 /proc 校验。 */

#define SEC(NAME) __attribute__((section(NAME), used))
#define __always_inline inline __attribute__((always_inline))
#define BPF_MAP_TYPE_HASH 1
#define BPF_MAP_TYPE_ARRAY 2
#define BPF_MAP_TYPE_PERCPU_ARRAY 6
#define BPF_MAP_TYPE_RINGBUF 27
#define __uint(name, val) int(*name)[val]

static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)1;
static long (*bpf_map_delete_elem)(void *map, const void *key) = (void *)3;
static long (*bpf_probe_read)(void *dst, unsigned int size, const void *unsafe_ptr) = (void *)4;
static unsigned long long (*bpf_get_current_pid_tgid)(void) = (void *)14;
static void *(*bpf_ringbuf_reserve)(void *ringbuf, unsigned long long size, long long flags) = (void *)131;
static void (*bpf_ringbuf_submit)(void *data, long long flags) = (void *)132;

enum process_event_kind {
    APPOPT_EVENT_EXEC = 1,
    APPOPT_EVENT_FORK = 2,
    APPOPT_EVENT_RENAME = 3,
    APPOPT_EVENT_EXIT = 4,
};

struct process_event {
    unsigned int kind;
    unsigned int tgid;
    unsigned int pid;
    unsigned int parent_tgid;
};

struct process_event_stats {
    unsigned long long submitted;
    unsigned long long dropped;
};

struct trace_offsets {
    unsigned int child_pid;
};

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 16384);
} events SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned char));
    __uint(max_entries, 64);
} target_tgids SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned char));
    __uint(max_entries, 256);
} tracked_tids SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(struct trace_offsets));
    __uint(max_entries, 1);
} trace_offsets SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(struct process_event_stats));
    __uint(max_entries, 1);
} event_stats SEC(".maps");

static __always_inline int emit_event(unsigned int kind, unsigned int tgid, unsigned int pid, unsigned int parent_tgid) {
    unsigned int zero = 0;
    struct process_event_stats *stats = bpf_map_lookup_elem(&event_stats, &zero);
    struct process_event *event = bpf_ringbuf_reserve(&events, sizeof(*event), 0);
    if (!event) {
        if (stats) {
            stats->dropped++;
        }
        return 0;
    }
    event->kind = kind;
    event->tgid = tgid;
    event->pid = pid;
    event->parent_tgid = parent_tgid;
    bpf_ringbuf_submit(event, 0);
    if (stats) {
        stats->submitted++;
    }
    return 0;
}

static __always_inline int is_managed(unsigned int tgid, unsigned int pid) {
    return bpf_map_lookup_elem(&target_tgids, &tgid) ||
           bpf_map_lookup_elem(&tracked_tids, &pid);
}

SEC("tracepoint/sched/sched_process_exec")
int appopt_sched_process_exec(void *ctx) {
    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    unsigned int tgid = (unsigned int)(pid_tgid >> 32);
    if (tgid == 0) {
        return 0;
    }
    return emit_event(APPOPT_EVENT_EXEC, tgid, (unsigned int)pid_tgid, 0);
}

SEC("tracepoint/sched/sched_process_fork")
int appopt_sched_process_fork(void *ctx) {
    unsigned int zero = 0;
    struct trace_offsets *offsets = bpf_map_lookup_elem(&trace_offsets, &zero);
    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    unsigned int parent_tgid = (unsigned int)(pid_tgid >> 32);
    unsigned int parent_tid = (unsigned int)pid_tgid;
    int child_pid = 0;
    if (!offsets || offsets->child_pid > 512 || parent_tgid == 0) {
        return 0;
    }
    if (!is_managed(parent_tgid, parent_tid)) {
        return 0;
    }
    if (bpf_probe_read(&child_pid, sizeof(child_pid),
                       (const char *)ctx + offsets->child_pid) != 0 || child_pid <= 0) {
        return 0;
    }
    return emit_event(APPOPT_EVENT_FORK, parent_tgid, (unsigned int)child_pid, parent_tgid);
}

SEC("tracepoint/task/task_rename")
int appopt_task_rename(void *ctx) {
    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    unsigned int tgid = (unsigned int)(pid_tgid >> 32);
    unsigned int pid = (unsigned int)pid_tgid;
    if (tgid == 0 || !is_managed(tgid, pid)) {
        return 0;
    }
    return emit_event(APPOPT_EVENT_RENAME, tgid, pid, 0);
}

SEC("tracepoint/sched/sched_process_exit")
int appopt_sched_process_exit(void *ctx) {
    unsigned long long pid_tgid = bpf_get_current_pid_tgid();
    unsigned int tgid = (unsigned int)(pid_tgid >> 32);
    unsigned int pid = (unsigned int)pid_tgid;
    if (tgid == 0 || !is_managed(tgid, pid)) {
        return 0;
    }
    emit_event(APPOPT_EVENT_EXIT, tgid, pid, 0);
    bpf_map_delete_elem(&tracked_tids, &pid);
    if (pid == tgid) {
        bpf_map_delete_elem(&target_tgids, &tgid);
    }
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
