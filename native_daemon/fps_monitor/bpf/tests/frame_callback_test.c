/* 四种内核布局的参数回归；使用模拟用户栈，不需要挂载探针或修改应用。 */
#include <assert.h>
#include <stdio.h>
#include <string.h>

#undef __always_inline
#define __always_inline inline __attribute__((always_inline))

static unsigned long long expected_address;
static unsigned long long user_latch = 12345678901234ULL;
static long bpf_probe_read_compat(void *dst, unsigned long long size, const void *address) {
    if ((unsigned long long)address != expected_address || size != sizeof(user_latch)) return -1;
    memcpy(dst, &user_latch, sizeof(user_latch));
    return 0;
}
#include "../frame_callback.h"

int main(void) {
    struct qixia_pt_regs regs = {0};
    const unsigned long long frame_number = 0x123456789abcdef0ULL;
    (void)frame_number;
#if defined(__TARGET_ARCH_arm64)
    regs.regs[0] = 0x1234567890ULL;
    regs.regs[1] = user_latch;
    assert(qixia_read_latch(&regs, 1) == user_latch);
    regs.regs[2] = (unsigned int)user_latch;
    regs.regs[3] = user_latch >> 32;
    assert(qixia_read_latch(&regs, 2) == user_latch);
    regs.regs[1] = frame_number;
    regs.regs[2] = user_latch;
    assert(qixia_read_surface(&regs, 3) == regs.regs[0]);
    assert(qixia_read_latch(&regs, 3) == user_latch);
    regs.regs[0] = 0xffffffff12345678ULL;
    regs.regs[2] = (unsigned int)frame_number;
    regs.regs[3] = frame_number >> 32;
    regs.regs[13] = 0x87654320;
    regs.sp = 0x11111111;
    expected_address = regs.regs[13];
    assert(qixia_read_surface(&regs, 4) == 0x12345678);
    assert(qixia_read_latch(&regs, 4) == user_latch);
    regs.regs[7] = user_latch;
    assert(qixia_read_latch(&regs, 5) == user_latch);
    expected_address += 48;
    assert(qixia_read_latch(&regs, 6) == user_latch);
#elif defined(__TARGET_ARCH_arm)
    regs.uregs[0] = 0x12345678;
    regs.uregs[2] = (unsigned int)user_latch;
    regs.uregs[3] = user_latch >> 32;
    assert(qixia_read_latch(&regs, 2) == user_latch);
    regs.uregs[2] = (unsigned int)frame_number;
    regs.uregs[3] = frame_number >> 32;
    regs.uregs[13] = 0x87654320;
    expected_address = regs.uregs[13];
    assert(qixia_read_surface(&regs, 4) == 0x12345678);
    assert(qixia_read_latch(&regs, 4) == user_latch);
    expected_address += 48;
    assert(qixia_read_latch(&regs, 6) == user_latch);
#elif defined(QIXIA_BPF_X86_64)
    regs.di = 0x1234567890ULL;
    regs.si = user_latch;
    assert(qixia_read_latch(&regs, 1) == user_latch);
    regs.si = frame_number;
    regs.dx = user_latch;
    assert(qixia_read_surface(&regs, 3) == regs.di);
    assert(qixia_read_latch(&regs, 3) == user_latch);
    regs.sp = 0x87654320;
    expected_address = regs.sp + 8;
    assert(qixia_read_latch(&regs, 2) == user_latch);
    expected_address = regs.sp + 16;
    assert(qixia_read_latch(&regs, 4) == user_latch);
    expected_address = regs.sp + 32;
    assert(qixia_read_latch(&regs, 5) == user_latch);
    expected_address = regs.sp + 60;
    assert(qixia_read_latch(&regs, 6) == user_latch);
#elif defined(QIXIA_BPF_I386)
    regs.sp = 0x87654320;
    expected_address = (unsigned long long)regs.sp + 8;
    assert(qixia_read_latch(&regs, 2) == user_latch);
    expected_address = (unsigned long long)regs.sp + 16;
    assert(qixia_read_latch(&regs, 4) == user_latch);
    expected_address = (unsigned long long)regs.sp + 60;
    assert(qixia_read_latch(&regs, 6) == user_latch);
#endif
    // 用户栈不可读时不能把帧号或旧寄存器当成时间戳。
    expected_address = 0;
    assert(qixia_read_latch(&regs, 4) == 0);
    assert(qixia_read_latch(&regs, 6) == 0);
    assert(qixia_valid_latch(user_latch, user_latch + 100));
    assert(!qixia_valid_latch(0, user_latch));
    assert(!qixia_valid_latch(~0ULL, user_latch));
    assert(!qixia_valid_latch(user_latch + 1, user_latch));
    assert(!qixia_valid_latch(user_latch, user_latch + 10000000001ULL));
    puts("参数布局、32 位用户栈、无效时间过滤通过");
    return 0;
}
