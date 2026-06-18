# AppOpt FPS 监测 —— eBPF 集成完成

## 更新内容 (2026-06-14)

**重构了整个 FPS 监测子系统**,从旧的单一 `--latency` 方案升级到 **eBPF uprobe + 完整 SF dump 降级**。

### 新架构

```
src/AppOpt.c                   # 主守护(已集成 fps_monitor API)
fps_monitor/                   # FPS 监测子系统(独立模块)
  ├── ebpf_fps.{h,c}          # eBPF uprobe 实现(600行,自包含,不依赖 libbpf)
  ├── fps_fallback.{h,c}      # SF dump 完整降级(latency → timestats)
  └── bpf/queuebuffer_probe.bpf.c  # 内核侧 BPF 程序
```

### 工作原理(四档自动降级)

**档位 1(最优): eBPF uprobe**
- 在目标游戏进程的 `libgui.so` 帧提交函数(`hook_queueBuffer`/`queueBuffer`)上挂 uprobe
- 每次游戏提交一帧,内核记录时间戳 → RingBuf → 用户态计算瞬时 FPS
- 候选符号按优先级自动探测(支持 Android 12-16 不同 ABI)
- 逐帧精度,与 Scene/fas-rs 同一机制

**档位 2(次优): SF --latency binder 直连**
- eBPF 不可用时自动降级
- 使用 `dumpsys SurfaceFlinger --latency` 差分读,锁定主渲染层
- **binder 直连**(比 fork dumpsys 快 10 倍+),不清缓冲,与 Scene FAS 零冲突
- 瞬时 FPS(不被 128 帧历史平滑)

**档位 3(兜底): SF --timestats binder 直连**
- Android 16 等 `--latency` 失效时自动切换
- 使用 `dumpsys SurfaceFlinger --timestats` 获取 Layer 累计 totalFrames 差分
- **binder 直连**,精度低于 latency 但 A12-16 通用

**档位 4(保底): CLI dumpsys**
- binder 不可用时 `sf_dump_binder` 内置自动回退
- fork+exec `dumpsys` 命令,最后兜底方案

### 编译产物

- `build/module/bin/*/AppOpt` (4 个 ABI,已链接 fps_monitor)
- `build/module/queuebuffer_probe.bpf.o` (1.2KB BPF 字节码)
- `build/AppOpt-改版.zip` (完整模块包)

### 使用

刷模块,启动 AppOpt daemon,App 侧照常发 `start <pkg>` / `stop` 命令。守护日志会显示:

**eBPF 可用时**:
```
[FPS] 开始监测 com.tencent.tmgp.sgame, eBPF 能力: 可用
[FPS] 目标进程 PID: 12345, 尝试 eBPF uprobe...
[eBPF] 锁定符号: _ZN7android7Surface11queueBufferE... @ /system/lib64/libgui.so
[FPS] eBPF 已激活, 锁定符号: queueBuffer
```

**降级到 SF fallback 时**:
```
[FPS] eBPF 能力: bpf系统调用被禁用
[Fallback] 启动 SF dump 监测: xxx (--latency binder 直连差分读)
[Fallback] 锁定主层(binder 直连): SurfaceView[xxx]#12345 (~30.0 fps)
```

**进一步降级到 timestats 时**:
```
[Fallback] --latency 连续 4 窗口失效,切换到 timestats (binder 直连)
```

### 权限要求

eBPF 需要:
- root 权限
- 内核支持 `CONFIG_BPF_SYSCALL` + `CONFIG_BPF_EVENTS` + `CONFIG_UPROBE_EVENTS`
- 内核 5.8+ (RingBuf 支持)

大部分 Android 12+ GKI 满足。不满足时自动回退 SF dump。

### 与旧版差异

| 特性              | 旧版 (单一 --latency)     | 新版 (eBPF + 完整降级)       |
| ----------------- | ------------------------- | ---------------------------- |
| 最优数据源        | SF --latency              | eBPF uprobe                  |
| 精度              | Layer 平均(窗口)          | 逐帧                         |
| Android 16        | 需手动切 timestats        | 自动降级                     |
| binder 直连       | 支持                      | 支持(fallback 继承)          |
| 与 Scene 冲突     | 无(共享 SF 缓冲)          | 无(eBPF 独立 / fallback 共享)|
| 多 Surface 区分   | 无                        | 支持(当前未启用)             |

### 技术亮点

✅ **完全自包含**: eBPF 实现不依赖 libbpf 库(手写 ELF 解析 + bpf syscall 封装)  
✅ **binder 直连保留**: fallback 模块继承了 AppOpt 的 binder 直连优化  
✅ **Android 16 兼容**: 自动识别 `RequestedLayerState{...}` 格式并剥离外壳  
✅ **运行时探测**: 不硬编码版本号,根据实际数据源可用性动态降级  
✅ **零冲突设计**: --latency 不清缓冲,与 Scene FAS 等工具并行无干扰

### 已知限制

- 仅支持 64 位游戏(32 位需适配 `/system/lib/libgui.so`,未实现)
- 不采集 x0 参数(Surface 指针),暂无多 Surface 分组
- 部分厂商 ROM 可能禁用 BPF,自动降级 SF dump

### 下一步

在真机/模拟器上测试:
1. 刷模块 `build/AppOpt-改版.zip`
2. 启动游戏
3. App 发 `start <pkg>` 命令
4. 观察 logcat `[FPS]` / `[eBPF]` / `[Fallback]` 日志
5. 验证悬浮球 FPS 是否实时跳动

---

**设计文档详见**: `AppOpt_FPS_eBPF_tracefs_libgui_symbol_design_v2.md`  
**模块文档详见**: `fps_monitor/README.md`
