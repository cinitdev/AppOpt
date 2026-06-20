# fps_monitor

AppOpt 的 FPS 监测模块。当前 eBPF 路径由 C shim + Rust/aya bridge 组成。

## 目录结构

```text
ebpf_fps.h                    AppOpt.c 使用的 C API
ebpf_fps.c                    C 适配层, 通过 FFI 调用 Rust/aya bridge
fps_fallback.h/.c             SurfaceFlinger fallback
bpf/queuebuffer_probe.bpf.c   内核侧 BPF 程序, 由 NDK clang 编译
appopt_ebpf_bridge/           Rust staticlib, 负责加载 BPF、attach uprobe、读取 ringbuf
aya/                          精简后的本地 vendored aya, 只保留 aya/aya-obj 和许可证
```

旧的纯 C eBPF loader 已移除。四个 ABI 都链接 `ebpf_fps.c`
和对应 ABI 的 `libappopt_ebpf_bridge.a`。

## 构建

在项目根目录执行:

```bash
./build_module.sh
```

脚本会构建:

- `queuebuffer_probe.bpf.o`
- 每个 Android ABI 的 `appopt_ebpf_bridge`
- `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 的 AppOpt 二进制

## 运行流程

1. `AppOpt.c` 调用 `ebpf_fps_*` C API。
2. `ebpf_fps.c` 转发到 Rust FFI。
3. Rust/aya 加载 `queuebuffer_probe.bpf.o`。
4. Rust/aya attach 到 `libgui.so` 的 queueBuffer 候选符号。
5. BPF 程序把帧事件写入 `events` ringbuf。
6. Rust/aya 轮询 ringbuf, 计算 FPS 后返回给 C 层。

如果 eBPF 启动失败，AppOpt 会降级到 SurfaceFlinger fallback。FPS 传给 App 的通道不在
`fps_monitor` 内实现: C 守护进程优先推送到 App 创建的 Android 本地 socket，socket
不可用时再写 app 私有目录 `fps` 文件兜底。

## 与 auto 绑核的关系

`fps_monitor` 只负责 FPS 数据源和 eBPF/fallback 监测，不负责生成 CPU 亲和性规则。
`auto` 规则生成逻辑在 `AppOpt.c` 中。

当前 `auto` 不按 `UnityMain`、`MainThread`、`RenderThread` 等线程名做白名单或黑名单判断。
采样完成后会按线程组统计 `avg`、`max`, 并计算:

```text
score = avg * 0.65 + max * 0.35
```

规则只输出综合负载最高的 Top 6 线程组:

- Top1 单线程且 `avg >= 18%` 或 `max >= 30%`: 绑定到最高性能簇，并固定输出在第一行。
- `avg >= 13%` 或 `max >= 22%`: 绑定到高频中核档。
- `avg >= 8%` 或 `max >= 18%`: 绑定到中核档。
- 包名兜底规则固定为非最高性能簇, 避免未知线程默认抢大核。
