/* SPDX-License-Identifier: GPL-2.0 */
/* BLAST 完成回调：this、nsecs_t latchTime、presentFence、stats。
 * latchTime 来自合成器，不能用回调抵达时间替代，否则批量通知会虚增 FPS。
 * target_tgids 的值表示被探测进程 ABI：1 为 64 位，2 为 32 位。
 * pt_regs 的布局由运行内核决定，64 位内核上的 32 位应用另读 compat 参数。
 */
#if defined(__TARGET_ARCH_arm64)
struct qixia_pt_regs {
    unsigned long long regs[31], sp, pc, pstate;
};
static __always_inline unsigned long long qixia_read_surface(void *ctx, unsigned int abi) {
    unsigned long long value = ((struct qixia_pt_regs *)ctx)->regs[0];
    return abi == 2 ? (unsigned int)value : value;
}
static __always_inline unsigned long long qixia_read_latch(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    /* AAPCS32 的 64 位第二参数对齐到 r2/r3，不能误读 r1。 */
    return abi == 2 ? ((unsigned long long)(unsigned int)r->regs[2] |
                      ((unsigned long long)(unsigned int)r->regs[3] << 32)) : r->regs[1];
}
#elif defined(__TARGET_ARCH_arm)
struct qixia_pt_regs { unsigned int uregs[18]; };
static __always_inline unsigned long long qixia_read_surface(void *ctx, unsigned int abi) {
    return ((struct qixia_pt_regs *)ctx)->uregs[0];
}
static __always_inline unsigned long long qixia_read_latch(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    return (unsigned long long)r->uregs[2] | ((unsigned long long)r->uregs[3] << 32);
}
#elif defined(QIXIA_BPF_X86_64)
struct qixia_pt_regs {
    unsigned long long r15, r14, r13, r12, bp, bx, r11, r10, r9, r8;
    unsigned long long ax, cx, dx, si, di, orig_ax, ip, cs, flags, sp, ss;
};
static __always_inline unsigned long long qixia_read_surface(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    if (abi != 2) return r->di;
    unsigned int value = 0;
    bpf_probe_read_compat(&value, sizeof(value), (void *)(r->sp + 4));
    return value;
}
static __always_inline unsigned long long qixia_read_latch(void *ctx, unsigned int abi) {
    struct qixia_pt_regs *r = ctx;
    if (abi != 2) return r->si;
    unsigned long long value = 0;
    bpf_probe_read_compat(&value, sizeof(value), (void *)(r->sp + 8));
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
    bpf_probe_read_compat(&value, sizeof(value), (void *)(sp + 8));
    return value;
}
#else
#error "未实现当前内核架构的完成回调参数读取"
#endif

static __always_inline int qixia_valid_latch(unsigned long long timestamp, unsigned long long now) {
    /* 无效、未合成和未来时间一律不计帧；不读取屏幕 Hz，也不按固定 FPS 封顶。 */
    return timestamp > 0 && timestamp <= now;
}
