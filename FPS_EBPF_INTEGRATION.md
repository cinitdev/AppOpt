# 柒夏线程 FPS 采集说明

FPS 采集由 Rust 守护 `QiXiaRs`、Rust/aya bridge、eBPF 探针和 SurfaceFlinger 降级路径组成。
CPU 核心分配与校准建议在守护进程的其他模块中实现，不在 FPS bridge 内生成规则。

## 数据源与降级顺序

```text
目标应用进程的 libgui BLAST 帧合成完成回调
  RingBuf → StatsMap → PerfEvent
    → SurfaceFlinger --latency
    → SurfaceFlinger --timestats
```

- Rust 先确认目标包名对应的 PID，再为其线程挂载 uprobe；内核和用户态同时检查目标进程身份。
- RingBuf 适用于支持它的内核；旧内核或加载失败时尝试只轮询计数映射的 StatsMap，随后尝试 PerfEvent。
- RingBuf 按逐帧事件计算；StatsMap 按 `frame_stats` 的时间戳和计数差计算。PerfEvent 在计数映射可用时也优先读取映射，减少逐帧唤醒。
- eBPF 加载或挂载不可用时，按运行时探测结果切换到 SurfaceFlinger；已挂载但暂时无帧时保留探测或重试，不仅凭停帧触发降级。
- SurfaceFlinger `--timestats` 会改变全局统计状态，与其他监测工具同时使用时需要留意该后端。

## FPS 的含义与计时

eBPF 探针挂在 `libgui.so` 的 `BLASTBufferQueue::transactionCallback` 完成回调，读取回调携带的 `latchTime`，统计已合成帧的时间序列。
不再把 `Surface::queueBuffer` 的调用次数当作显示 FPS，也不把批量回调的到达时间当成帧时间。同一队列、同一合成时间的跨线程通知只计一帧；无效、未合成、未来和过期候选时间不计入。
这测量的是完成回调中的合成节奏，不是面板扫描时间或直接读取 present fence 的信号时间。Rust 不调用 SurfaceFlinger 读取刷新率并截断 eBPF 输出；不含 BLAST 或回调不可用的系统走原有 SurfaceFlinger 实际帧时间降级。
32 位参数按对应的调用约定读取，但还需要内核支持该进程的指令探测：K70 的 ARM64 5.15 内核拒绝 AArch32 uprobe，32 位应用需要走降级路径，不能保证使用 eBPF。BPF 对象使用新的程序入口，旧提交探针不能被误当成完成探针加载。

逐帧路径按 PID 和 Surface 区分来源，缺少 Surface 指针时按 TID 区分；从稳定帧源中选择一个，
不把多个应用进程或多个 Surface 的 FPS 简单相加。事件先按时间戳排序，再进入约一秒的滑动窗口。

- 帧率由有效间隔数量与累计间隔计算，不读取固定的 120／144／165 Hz 上限，也不要求用户填写最高刷新率。
- 裁剪窗口时保留跨越起点的完整间隔，只有其余样本已覆盖一秒才移除。停帧后恢复的短间隔不能挤掉停顿时间，避免瞬时 FPS 虚高。
- 同一队列的重复合成时间在内核与用户态去重；小于 1 毫秒的短间隔事件不推进有效时间，避免异常通知缩短下一帧间隔。
- 当前 1 毫秒最小间隔对应最高 1000 FPS 的测量边界。它是去重与有效性检查的边界，不是无限帧率支持。
- 高刷新率回归覆盖 144、165、240、360、480 Hz 等帧序列，以及运行中的刷新率切换。
- 启动阶段需积累至少 8 个有效间隔、覆盖约一秒；创建帧流后的空等时间和短暂事件突发不能代替预热。长卡顿保留，超过暂停边界后重新预热。
- 轮询按当前单调时钟计算窗口，包含最后一帧之后的空闲时间。视频持续出帧优先于偶发界面刷新，保留切换等待以免瞬时抖动。
- 切换探测函数时先关闭目标事件入口，丢弃旧候选排队事件；已有有效事件但尚未完成预热时不抢先轮换。清理过期 TID 映射不删除同一 Surface 的活跃帧窗口。

历史报告的最大帧间隔与 FPS 滑动窗口分别统计。StatsMap / PerfEvent 探针保留当前及上一已完成窗口，
避免轮询跨窗口时丢失峰值或重复上报。报告没有完整逐帧分布，不据此计算帧耗时 P95/P99；
掉帧定位表示采样区间，低帧采样占比不等于逐帧慢帧比例。

## App 通信与存储

App 创建本地 socket，并在开始请求中传入 socket 名称和一次性 token。Rust 验证后推送 FPS；
socket 不可用时，使用 App 私有目录下的 `fps` 文件兜底。
守护存活检查通过反向 socket 返回 token、版本和 PID，不仅依赖进程名。

校准和自动分配历史在 App 私有目录中按需生成，导入数据库后清理对应暂存文件。
校准记录、自动分配记录与首页最近使用摘要的保存条件不同，见 [README](README.md)。

## 代码位置

| 路径 | 职责 |
| --- | --- |
| `native_daemon/daemon_rs/src/fps_core/` | 监测生命周期、命令、socket 与 SurfaceFlinger 降级 |
| `native_daemon/fps_monitor/qixia_ebpf_bridge/src/fps_stream.rs` | 窗口计时、有效样本、独立报告峰值 |
| `native_daemon/fps_monitor/qixia_ebpf_bridge/src/streams.rs` | 帧源分流、选择与淘汰 |
| `native_daemon/fps_monitor/qixia_ebpf_bridge/src/stats.rs` | 计数映射轮询与报告窗口读取 |
| `native_daemon/fps_monitor/qixia_ebpf_bridge/src/constants.rs` | 候选符号与采集参数 |
| `native_daemon/fps_monitor/bpf/` | RingBuf、PerfEvent、StatsMap 探针 |
| `native_daemon/fps_monitor/aya/` | Aya Git 子模块及其许可 |

## 构建与验证

在项目根目录执行 `bash build_module.sh release`，构建四个 Android ABI、各后端 BPF 对象及内嵌 APK。
本地构建时可设置 `QIXIA_SKIP_SUBMODULE_UPDATE=1`，使用当前已检出的 Aya 提交。

FPS bridge 依赖 Linux / Android 的 Aya，完整测试应交叉编译后在 Android 上执行。
`fps_stream.rs` 只依赖标准库，也可使用 `rustc --test` 在本机运行计时回归。

测试覆盖完成回调批量到达、跨线程去重、64/32 位参数、无效合成时间、高刷新率、刷新率切换、无效批次、窗口大小、
长卡顿与暂停、停帧后突发和恢复、有效时间预热、报告峰值，以及 BPF 事件结构布局。模拟帧序列验证的是算法，不代表每种高刷硬件都已实测。
