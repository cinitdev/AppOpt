# AppOpt FPS 监测 eBPF 集成说明

## 当前架构

AppOpt 的 FPS 监测现在由 C 接口层、Rust/aya bridge 和 SurfaceFlinger fallback 组成。

```text
native_daemon/AppOpt.c
  调用 native_daemon/fps_monitor/ebpf_fps.h 暴露的 C API

native_daemon/fps_monitor/
  ebpf_fps.h                    native_daemon/AppOpt.c 使用的 C API
  ebpf_fps.c                    C shim, 通过 FFI 调用 Rust/aya bridge
  bpf/queuebuffer_probe.bpf.c        RingBuf 内核侧 BPF 程序
  bpf/queuebuffer_probe_perf.bpf.c   PerfEvent 备用内核侧 BPF 程序
  appopt_ebpf_bridge/                Rust staticlib, 负责 BPF 加载、uprobe attach、事件通道读取
  aya/                          精简后的本地 vendored aya
  fps_fallback.h/.c             SurfaceFlinger fallback
```

旧的纯 C eBPF loader 已移除。`ebpf_fps.c` 不再直接读
`/sys/bus/event_source/devices/uprobe/type`, 也不再自己用
`perf_event_open` 绑定 uprobe。uprobe attach 由 Rust/aya 封装完成。

## eBPF 路径

1. `native_daemon/AppOpt.c` 收到 FPS 监测命令后查找目标包名进程。
2. 找到 PID 时调用 `ebpf_fps_start(bpf_obj, pid, pkg)`。
3. 未及时找到 PID 时会用 `pid=-1` 启动全局 uprobe, Rust bridge 收到帧事件后按 `/proc/<pid>/cmdline` 过滤目标包名。
4. Rust/aya 优先加载 `queuebuffer_probe.bpf.o` 并初始化 RingBuf 事件通道。
5. 如果 RingBuf 创建/映射失败（例如 Android 17 上的 `mmap failed`），立即释放本次 eBPF 上下文，改为加载 `queuebuffer_probe_perf.bpf.o` 并使用 PerfEvent 备用通道。
6. Rust/aya 按优先级 attach `libgui.so` 的帧提交符号。
7. BPF 程序把帧事件写入当前可用事件通道。
8. Rust bridge 计算 FPS, C 层通过 `ebpf_fps_get()` 读取。

## App 通信路径

FPS 数据源和 App 通信是两层逻辑:

```text
FPS 数据源:
  eBPF uprobe
    -> SurfaceFlinger --latency
    -> SurfaceFlinger --timestats

FPS 传给 App:
  Android 本地 socket
    -> app 私有目录 fps 文件兜底
```

App 启动悬浮胶囊时会先创建一次性本地 socket, 并把 socket 名和随机 token 写入
`fps.cmd`。C 守护进程收到 `start <pkg> <socket> <token>` 后优先反连该 socket,
握手成功后按行推送 FPS。socket 被 SELinux/ROM 行为拦住时, C 端才覆盖写
`/data/data/top.suto.appopt/files/fps`, App 侧用 FileObserver 兜底读取。

守护进程存活检测也走反向验证: App 创建一次性 socket, 通过 root helper 下发
`--ping-daemon <socket> <token>`, C 守护进程连接回 App 并回传 token/版本/PID。
这样 App 不再只靠进程名判断, 可以区分同名或二改版本。

## uprobe 符号策略

当前符号列表在 `native_daemon/fps_monitor/appopt_ebpf_bridge/src/lib.rs` 中维护。

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

本文件描述 FPS/eBPF 监测链路。CPU 亲和性 `auto` 规则生成在 `native_daemon/AppOpt.c` 中完成,
不在 `native_daemon/fps_monitor` 或 Rust/aya bridge 中完成。

当前 `auto` 规则不依赖线程名白名单/黑名单, 也不特殊识别 `UnityMain`、`MainThread`、
`RenderThread`、`worker`、`Audio` 等名字。算法只看采样负载:

```text
score = avg * 0.65 + max * 0.35
```

阈值由 `/data/adb/modules/AppOpt/config/calib_policy.conf` 控制, App 的「设置 > 自动校准策略」
会可视化读写这个文件。刷入新模块但未重启时, App 会读取
`/data/adb/modules_update/AppOpt/config/calib_policy.conf` 并锁定编辑, 避免待生效更新覆盖用户设置。

线程组按 `score` 排序后默认最多输出 Top 6。每一档都可以在 App 设置里直接勾选
CPU 核心, 保存后写入策略文件里的 `cores`:

- Top1 单线程且 AVG 和 MAX 同时达到 `best_thread` 阈值: 最高性能簇, 固定输出在第一行。
- AVG 和 MAX 同时达到 `group_high` 阈值: 高频中核档。
- AVG 和 MAX 同时达到 `group_mid` 阈值: 中核档。
- 包名兜底规则: 默认非最高性能簇, 对齐常见 `0-6` 兜底, 可在 App 设置里调整。
- `cores:7`、`cores:5-6`、`cores:0-6`: 直接使用用户指定的连续核心范围。
- `target:*` 已移除; 核心分配只看 `cores`。

默认策略:

```ini
best_thread=avg:18,max:30,cores:7
group_high=avg:13,max:22,cores:5-6
group_mid=avg:8,max:18,cores:4-6
wildcard_group=max_member
max_thread_rules=6
fallback=cores:0-6
```

`wildcard_group=max_member` 表示 `Thread-1/Thread-2`、`Job.worker 1/2` 这类相似线程
会先合成 `Thread-*`、`Job.worker*` 一组, AVG 只取组内最忙的单个线程, MAX 取组内最高峰值。
需要更激进时可在 App 设置中改为“平均负载相加”, 对应 `wildcard_group=sum`, 此时 AVG
会累加组内所有线程。两种模式都只改变这组 AVG 的计算方式; 一旦入选, 规则名仍输出为
`Job.worker*` 这类规则, 不会改成组内某个具体线程名。

`fallback=cores:0-6` 对应 App 设置里的“进程兜底核心”。
它只影响最后一行 `包名=...` 兜底规则, 不改变已经单独生成的线程规则。

守护进程运行后会在 `calib_policy.conf` 写入当前设备拓扑。这里不再假设一定是
“小/中/大”三段, 而是按 CPU 最大频率簇生成通用性能档位；同频簇不会再按 CPU 编号硬切,
避免把 8 Gen 5 这类同频性能核误拆成两个档位。

```ini
# CPU 拓扑识别: 3 个性能簇, 全部=[0-7] 低性能=[0-3] 主性能=[4-6] 高性能=[5-6] 最高性能=[7] 非最高=[0-6]
detected_low=0-3
detected_main=4-6
detected_high=5-6
detected_non_top=0-6
detected_top=7
detected_all=0-7
```

示例规则:

- 3 簇设备如 870: 低性能=`0-3`, 主性能/高性能=`4-6`, 最高性能=`7`。
- 4 簇设备如 8 Gen 2: 低性能=`0-2`, 主性能=`3-6`, 高性能=`5-6`, 最高性能=`7`。
- 2 簇设备如部分 8 Gen 5: 低性能/主性能/高性能=`0-5`, 最高性能=`6-7`。

这样做的目的不是猜线程职责, 而是让不同游戏、不同引擎的线程命名差异只影响规则名字,
不影响分级判断。

## 日志示例

eBPF 可用:

```text
[FPS] 开始监测 com.tencent.tmgp.sgame, eBPF: 尝试启动
[FPS] 目标进程 PID: 12345, 尝试 eBPF uprobe...
[FPS] eBPF 使用后端: RingBuf
[FPS] eBPF 已激活, 锁定符号: _ZN7android7Surface11queueBufferEP19ANativeWindowBufferi
[FPS] eBPF 当前帧事件 PID: 12345
[FPS] eBPF 首次捕获到帧率: 60.0 fps
```

RingBuf 不可用, 自动切换 PerfEvent:

```text
[FPS] eBPF RingBuf 不可用: `mmap` failed
[FPS] eBPF 使用后端: PerfEvent
[FPS] eBPF 已激活, 锁定符号: _ZN7android7Surface16hook_queueBufferEP13ANativeWindowP19ANativeWindowBufferi
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
- 需要内核支持 BPF syscall 和 uprobes；事件通道优先 RingBuf，RingBuf 不可用时尝试 PerfEvent。
- 目标设备禁用 BPF 或限制 uprobe 时会自动 fallback。
- `frame-analyzer-ebpf` 只支持 64 位设备和应用; AppOpt 当前构建包含 4 个 ABI,
  但具体设备上 32 位应用的 eBPF attach 仍取决于 libgui 路径、符号和内核能力。
- Android 12-16 不靠版本号判断, 以实际 attach 成功与否为准。

## 构建

`build_module.sh` 会构建:

```text
queuebuffer_probe.bpf.o
queuebuffer_probe_perf.bpf.o
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

- `native_daemon/fps_monitor/README.md`
- `native_daemon/fps_monitor/ebpf_fps.c`
- `native_daemon/fps_monitor/appopt_ebpf_bridge/src/lib.rs`
- `native_daemon/fps_monitor/bpf/queuebuffer_probe.bpf.c`
- `native_daemon/fps_monitor/bpf/queuebuffer_probe_perf.bpf.c`
