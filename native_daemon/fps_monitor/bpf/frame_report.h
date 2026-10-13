/* SPDX-License-Identifier: GPL-2.0 */
#pragma once

/* 独立保存每秒最大帧间隔，不改变 FPS 计数映射，也不增加用户态逐帧唤醒。 */
struct frame_report_value {
    unsigned long long interval_ts;
    unsigned long long max_interval_ns;
    unsigned long long window_ts;
};
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(struct frame_stats_key));
    __uint(value_size, sizeof(struct frame_report_value));
    __uint(max_entries, QIXIA_FRAME_STATS_MAX_ENTRIES);
} frame_report SEC(".maps");

/* 保留刚完成的窗口，避免用户态每 250 毫秒轮询时漏掉边界峰值。 */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(struct frame_stats_key));
    __uint(value_size, sizeof(struct frame_report_value));
    __uint(max_entries, QIXIA_FRAME_STATS_MAX_ENTRIES);
} frame_report_prev SEC(".maps");

static __always_inline void record_frame_report(struct frame_stats_key *key, unsigned long long timestamp, unsigned long long interval) {
    if (interval < 1000000ULL || interval > 5000000000ULL) return;
    struct frame_report_value *report = bpf_map_lookup_elem(&frame_report, key);
    if (report) {
        struct frame_report_value next = *report;
        if (timestamp - next.window_ts >= 1000000000ULL) {
            bpf_map_update_elem(&frame_report_prev, key, &next, BPF_ANY);
            next.window_ts = timestamp;
            next.max_interval_ns = 0;
        }
        if (interval >= next.max_interval_ns) {
            next.max_interval_ns = interval;
            next.interval_ts = timestamp;
            /* 一次替换完整快照，避免并发查询读到不同轮次的时间与间隔。 */
            bpf_map_update_elem(&frame_report, key, &next, BPF_ANY);
        }
    } else {
        struct frame_report_value initial = {timestamp, interval, timestamp};
        bpf_map_update_elem(&frame_report, key, &initial, BPF_ANY);
    }
}
