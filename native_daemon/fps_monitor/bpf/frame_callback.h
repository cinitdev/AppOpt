/* SPDX-License-Identifier: GPL-2.0 */
/* 完成回调与 ConsumerFrameEventHistory::addLatch 的参数读取。
 * latchTime 来自合成器，不能用回调抵达时间替代，否则批量通知会虚增 FPS。
 * target_tgids：1/2 为完成回调，3/4 为帧历史，5/6 为时间戳更新；每组 64/32 位。
 * pt_regs 的布局由运行内核决定，64 位内核上的 32 位应用另读 compat 参数。
 */
#if defined(__TARGET_ARCH_arm64)
struct qixia_pt_regs {
    unsigned long long regs[31], sp, pc, pstate;
};
static __always_inline unsigned long long qixia_read_surface(void *ctx, unsigned int abi) {
    unsigned long long value = ((struct qixia_pt_regs *)ctx)->regs[0];
    return (abi & 1) == 0 ? (unsigned int)value : value;
}
static __always_inline unsigned long long qixia_read_latch(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    if (abi == 1) return r->regs[1];
    if (abi == 2) return (unsigned long long)(unsigned int)r->regs[2] |
                        ((unsigned long long)(unsigned int)r->regs[3] << 32);
    if (abi == 3) return r->regs[2];
    if (abi == 5) return r->regs[7];
    /* AAPCS32：帧号占 r2/r3，latchTime 位于用户栈首；compat SP 为 r13。 */
    unsigned long long value = 0;
    unsigned long long sp = (unsigned int)r->regs[13];
    /* updateFrameTimestamps 的三指针及 24 字节 CompositorTiming 之后是 latchTime。 */
    bpf_probe_read_compat(&value, sizeof(value), (void *)(sp + (abi == 6 ? 48 : 0)));
    return value;
}
#elif defined(__TARGET_ARCH_arm)
struct qixia_pt_regs { unsigned int uregs[18]; };
static __always_inline unsigned long long qixia_read_surface(void *ctx, unsigned int abi) {
    return ((struct qixia_pt_regs *)ctx)->uregs[0];
}
static __always_inline unsigned long long qixia_read_latch(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    if (abi == 2) return (unsigned long long)r->uregs[2] |
                        ((unsigned long long)r->uregs[3] << 32);
    unsigned long long value = 0;
    bpf_probe_read_compat(&value, sizeof(value), (void *)((unsigned long long)r->uregs[13] + (abi == 6 ? 48 : 0)));
    return value;
}
#elif defined(QIXIA_BPF_X86_64)
struct qixia_pt_regs {
    unsigned long long r15, r14, r13, r12, bp, bx, r11, r10, r9, r8;
    unsigned long long ax, cx, dx, si, di, orig_ax, ip, cs, flags, sp, ss;
};
static __always_inline unsigned long long qixia_read_surface(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    if (abi & 1) return r->di;
    unsigned int value = 0;
    bpf_probe_read_compat(&value, sizeof(value), (void *)(r->sp + 4));
    return value;
}
static __always_inline unsigned long long qixia_read_latch(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    if (abi == 1) return r->si;
    if (abi == 3) return r->dx;
    unsigned long long value = 0;
    unsigned int offset = abi == 2 ? 8 : abi == 5 ? 32 : abi == 6 ? 60 : 16;
    bpf_probe_read_compat(&value, sizeof(value), (void *)(r->sp + offset));
    return value;
}
#elif defined(QIXIA_BPF_I386)
struct qixia_pt_regs {
    unsigned int bx, cx, dx, si, di, bp, ax, ds, es, fs, gs, orig_ax, ip, cs, flags, sp, ss;
};
static __always_inline unsigned long long qixia_read_surface(void *ctx, unsigned int abi) {
    unsigned int value = 0;
    unsigned long long sp = ((struct qixia_pt_regs *)ctx)->sp;
    bpf_probe_read_compat(&value, sizeof(value), (void *)(sp + 4));
    return value;
}
static __always_inline unsigned long long qixia_read_latch(void *ctx, unsigned int abi) {
    unsigned long long value = 0;
    unsigned long long sp = ((struct qixia_pt_regs *)ctx)->sp;
    bpf_probe_read_compat(&value, sizeof(value), (void *)(sp + (abi == 2 ? 8 : abi == 6 ? 60 : 16)));
    return value;
}
#else
#error "未实现当前内核架构的完成回调参数读取"
#endif

static __always_inline int qixia_valid_latch(unsigned long long timestamp, unsigned long long now) {
    /* 无效、未合成和未来时间一律不计帧；不读取屏幕 Hz，也不按固定 FPS 封顶。 */
    /* 超过帧统计保留期的值也无效，避免未传参时的旧指针被误当作时间。 */
    return timestamp > 0 && timestamp <= now && now - timestamp <= 10000000000ULL;
}
