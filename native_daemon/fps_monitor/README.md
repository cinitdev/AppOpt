# FPS 监测模块

柒夏线程由 Rust 守护直接调用 Rust/aya bridge，负责目标应用的 BLAST 帧合成完成回调采集与 SurfaceFlinger 降级。

```text
bpf/queuebuffer_probe.bpf.c        RingBuf 探针
bpf/queuebuffer_probe_stats.bpf.c  StatsMap 探针
bpf/queuebuffer_probe_perf.bpf.c   PerfEvent 探针
qixia_ebpf_bridge/                 BPF 加载、帧源选择、计时和统计
aya/                              Aya Git 子模块
```

在项目根目录运行 `bash build_module.sh release`，生成四种 Android ABI 的守护程序及 BPF 对象。
构建缓存 `target/` 和生成的对象文件不提交到源码仓库。

FPS 按有效帧间隔计算，不按 120／144／165 Hz 等屏幕上限截断。短间隔重复事件不会推进计时基线；
当前最小有效间隔为 1 毫秒。eBPF 使用完成回调携带的合成时间，不使用缓冲区提交次数或回调到达时间；
它反映合成节奏，不等同于面板扫描时间。32 位应用还需要内核支持对应 ABI 的 uprobe。

完整说明见 [FPS 采集说明](../../FPS_EBPF_INTEGRATION.md)，安装、自动分配、校准与历史记录见
[项目 README](../../README.md)。
