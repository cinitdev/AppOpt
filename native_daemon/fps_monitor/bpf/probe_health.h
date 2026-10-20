/* 只记录回调确实发生、但参数无效的证据；静止画面没有事件时不能误换帧源。 */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(unsigned int));
    __uint(value_size, sizeof(unsigned int));
    __uint(max_entries, 128);
} invalid_latch SEC(".maps");

static __always_inline int qixia_check_latch(unsigned int pid, unsigned long long timestamp) {
    if (qixia_valid_latch(timestamp, bpf_ktime_get_ns())) return 1;
    if (!bpf_map_lookup_elem(&invalid_latch, &pid)) {
        unsigned int invalid = 1;
        bpf_map_update_elem(&invalid_latch, &pid, &invalid, 0);
    }
    return 0;
}
