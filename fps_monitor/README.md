# fps_monitor —— AppOpt eBPF 游戏提交帧率采集模块

## 概述

独立的 FPS 监测子系统,支持 eBPF uprobe(优先)+ timestats 回退。

**设计目标**:
- 在 libgui.so 的帧提交函数(`hook_queueBuffer`/`queueBuffer`)上挂 eBPF uprobe
- 每次游戏提交一帧由内核记录时间戳,用户态计算瞬时 FPS
- 不注入游戏、不依赖 tracefs、不写死 offset、支持 Android 12-16

**架构**:
```
ebpf_fps.h/.c       —— 核心 eBPF 实现(自包含,不依赖 libbpf)
fps_fallback.h/.c   —— timestats 回退(eBPF 不可用时兜底)
bpf/queuebuffer_probe.bpf.c  —— BPF 内核侧程序
```

## 文件说明

### ebpf_fps.{h,c}
完整的 eBPF uprobe FPS 采集实现,约 600 行。包含:
- bpf() 系统调用封装
- BPF 对象加载(ELF 解析 + map 创建 + 重定位 + prog load)
- libgui 符号解析(从目标进程 maps + ELF .dynsym/.symtab)
- uprobe attach (perf_event_open)
- RingBuf 读取与 FPS 计算

**候选符号表**(按优先级):
1. `_ZN7android7Surface16hook_queueBufferE...` (最优,ANativeWindow 入口)
2. `_ZN7android7Surface27hook_queueBuffer_DEPRECATEDE...` (旧版)
3. `_ZN7android7Surface11queueBufferE...PNSE` (A14+ 新 ABI)
4. `_ZN7android7Surface11queueBufferE...i` (A12-13 旧 ABI)
5. `_ZN7android7Surface19queueBufferInternalE...` (内部实现)

运行时逐个尝试,第一个能产生事件的即锁定。

### fps_fallback.{h,c}
SurfaceFlinger timestats 回退实现。eBPF 失败时用 `dumpsys SurfaceFlinger --timestats` 获取 Layer FPS,精度低于 eBPF 但 A12-16 都能用。

### bpf/queuebuffer_probe.bpf.c
内核侧 BPF 程序,每次帧提交触发时记录:
- `bpf_ktime_get_ns()` 时间戳
- `bpf_get_current_pid_tgid()` PID/TID
- 通过 RingBuf 传给用户态

**无系统头文件依赖**:用 clang `-target bpf` 内建函数替代 `#include <linux/bpf.h>`,可在 NDK 环境直接编译。

## 编译

### BPF 程序
```bash
cd fps_monitor
./test_bpf_compile.sh   # 验证 NDK clang 可编译 BPF
```

或手动:
```bash
$NDK/toolchains/llvm/prebuilt/*/bin/clang \
  -target bpf -O2 -c bpf/queuebuffer_probe.bpf.c \
  -o bpf/queuebuffer_probe.bpf.o
```

### 用户态(整合到 AppOpt)
见父目录 `build_module.sh`,将编译 `ebpf_fps.c` + `fps_fallback.c` 并链接进 AppOpt 主二进制。

## 使用

```c
#include "fps_monitor/ebpf_fps.h"

// 1. 探测能力
ebpf_cap_t cap = ebpf_fps_probe_capability();
printf("eBPF: %s\n", ebpf_cap_str(cap));

// 2. 启动监测
ebpf_fps_ctx *ctx = ebpf_fps_start("/path/to/queuebuffer_probe.bpf.o", target_pid);
if (!ctx) {
    // 降级到 timestats
    fps_fallback_enable();
}

// 3. 主循环
while (monitoring) {
    if (ctx) {
        ebpf_fps_poll(ctx);          // 消费 RingBuf 事件
        double fps = ebpf_fps_get(ctx);
        printf("FPS: %.1f\n", fps);
    } else {
        long frames = fps_fallback_get_frames(pkg);
        // 差分算 FPS...
    }
    usleep(100000);
}

// 4. 停止
ebpf_fps_stop(ctx);
fps_fallback_disable();
```

## 权限要求

- root 权限(或 CAP_BPF + CAP_PERFMON)
- 内核支持:
  - `CONFIG_BPF_SYSCALL`
  - `CONFIG_BPF_EVENTS`
  - `CONFIG_UPROBE_EVENTS`
  - `BPF_MAP_TYPE_RINGBUF`(内核 5.8+)
- SELinux 允许 perf_event_open / bpf syscall

大部分 Android 12+ GKI 内核满足条件。若不支持,自动回退到 timestats。

## 设计文档

完整设计见: `../AppOpt_FPS_eBPF_tracefs_libgui_symbol_design_v2.md`

## 已知限制

- 仅支持 64 位游戏(32 位需单独适配 `/system/lib/libgui.so`)
- 不采集 x0 参数(Surface 指针),因此无多 Surface 分组
- BPF 程序加 verifier log 输出到 stderr(可能刷屏,调试后可关闭 log_level)
