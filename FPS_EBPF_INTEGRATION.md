# AppOpt FPS 监测 eBPF 集成说明

## 当前架构

AppOpt 的 FPS 监测现在由 C 接口层、Rust/aya bridge 和 SurfaceFlinger fallback 组成。

```text
AppOpt.c
  调用 fps_monitor/ebpf_fps.h 暴露的 C API

fps_monitor/
  ebpf_fps.h                    AppOpt.c 使用的 C API
  ebpf_fps.c                    C shim, 通过 FFI 调用 Rust/aya bridge
  bpf/queuebuffer_probe.bpf.c   内核侧 BPF 程序
  appopt_ebpf_bridge/           Rust staticlib, 负责 BPF 加载、uprobe attach、ringbuf 读取
  aya/                          精简后的本地 vendored aya
  fps_fallback.h/.c             SurfaceFlinger fallback
```

旧的纯 C eBPF loader 已移除。`ebpf_fps.c` 不再直接读
`/sys/bus/event_source/devices/uprobe/type`, 也不再自己用
`perf_event_open` 绑定 uprobe。uprobe attach 由 Rust/aya 封装完成。

## eBPF 路径

1. `AppOpt.c` 收到 FPS 监测命令后查找目标包名进程。
2. 找到 PID 时调用 `ebpf_fps_start(bpf_obj, pid, pkg)`。
3. 未及时找到 PID 时会用 `pid=-1` 启动全局 uprobe, Rust bridge 收到帧事件后按 `/proc/<pid>/cmdline` 过滤目标包名。
4. Rust/aya 加载 `queuebuffer_probe.bpf.o`。
5. Rust/aya 按优先级 attach `libgui.so` 的帧提交符号。
6. BPF 程序把帧事件写入 ringbuf。
7. Rust bridge 计算 FPS, C 层通过 `ebpf_fps_get()` 读取。

## uprobe 符号策略

当前符号列表在 `fps_monitor/appopt_ebpf_bridge/src/lib.rs` 中维护。

优先级最高的两个符号对齐 `frame-analyzer-ebpf` 的做法:

```text
_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi
_ZN7android7Surface11queueBufferEP19ANativeWindowBufferiPNS_24SurfaceQueueBufferOutputE
```

如果这两个都 attach 失败, 再尝试额外候选作为 ROM/版本兼容兜底:

```text
_ZN7android7Surface16hook_queueBufferEP13ANativeWindowP19ANativeWindowBufferi
_ZN7android7Surface27hook_queueBuffer_DEPRECATEDEP13ANativeWindowP19ANativeWindowBuffer
_ZN7android7Surface19queueBufferInternalEP13ANativeWindowP19ANativeWindowBufferi
```

实际运行时只需要成功 attach 一个符号。日志中的 `ebpf_fps_symbol()` 会返回当前锁定的符号。

## fallback 路径

如果 eBPF 初始化失败, AppOpt 会降级到 SurfaceFlinger fallback:

```text
eBPF uprobe
  -> SurfaceFlinger --latency
  -> SurfaceFlinger --timestats
```

注意: `--timestats` 会修改 SurfaceFlinger 的统计状态, 可能影响 Scene 等工具的统计结果。
排查 Scene 数据异常时, 优先确认日志里是否出现 `切换到 timestats`。

## 与 auto 规则生成的关系

本文件描述 FPS/eBPF 监测链路。CPU 亲和性 `auto` 规则生成在 `AppOpt.c` 中完成,
不在 `fps_monitor` 或 Rust/aya bridge 中完成。

当前 `auto` 规则不依赖线程名白名单/黑名单, 也不特殊识别 `UnityMain`、`MainThread`、
`RenderThread`、`worker`、`Audio` 等名字。算法只看采样负载:

```text
score = avg * 0.65 + max * 0.35
```

线程组按 `score` 排序后最多输出 Top 6:

- Top1 且 `avg >= 25%`、`max >= 35%`: 大核。
- `avg >= 12%` 或 `max >= 22%`: 高中核+大核。
- `avg >= 6%` 或 `max >= 12%`: 中核+大核。
- 包名兜底规则: 小核+中核。

这样做的目的不是猜线程职责, 而是让不同游戏、不同引擎的线程命名差异只影响规则名字,
不影响分级判断。

## 日志示例

eBPF 可用:

```text
[FPS] 开始监测 com.tencent.tmgp.sgame, eBPF 能力: 可用
[FPS] 目标进程 PID: 12345, 尝试 eBPF uprobe...
[FPS] eBPF 已激活, 锁定符号: _ZN7android7Surface11queueBufferEP19ANativeWindowBufferi
[FPS] eBPF 当前帧事件 PID: 12345
[FPS] eBPF 首次捕获到帧率: 60.0 fps
```

未及时找到 PID, 使用全局探测:

```text
[FPS] 等待约 3 秒仍未找到 com.xxx 的进程, 尝试全局 eBPF 帧事件探测
[FPS] 未锁定包名 PID, 尝试 eBPF 全局 uprobe 探测屏幕帧事件...
[FPS] eBPF 当前帧事件 PID: 12345
```

降级到 fallback:

```text
[FPS] eBPF 初始化失败: ..., 降级到 SF fallback
[Fallback] 启动 SF dump 监测: com.xxx
```

## 权限和限制

- 需要 root 权限。
- 需要内核支持 BPF syscall、uprobes 和 ringbuf。
- 目标设备禁用 BPF 或限制 uprobe 时会自动 fallback。
- `frame-analyzer-ebpf` 只支持 64 位设备和应用; AppOpt 当前构建包含 4 个 ABI,
  但具体设备上 32 位应用的 eBPF attach 仍取决于 libgui 路径、符号和内核能力。
- Android 12-16 不靠版本号判断, 以实际 attach 成功与否为准。

## 构建

`build_module.sh` 会构建:

```text
queuebuffer_probe.bpf.o
appopt_ebpf_bridge staticlib
AppOpt 主二进制
Magisk 模块 zip
```

当前脚本会分别构建:

```text
arm64-v8a
armeabi-v7a
x86_64
x86
```

## 相关文件

- `fps_monitor/README.md`
- `fps_monitor/ebpf_fps.c`
- `fps_monitor/appopt_ebpf_bridge/src/lib.rs`
- `fps_monitor/bpf/queuebuffer_probe.bpf.c`
