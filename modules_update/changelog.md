# AppOpt v1.7.3

## eBPF FPS 兼容性

- eBPF FPS 新增 PerfEvent 备用事件通道，RingBuf 创建或映射失败时会自动切换到 PerfEvent。
- 兼容 Android 17 上 RingBuf `mmap failed` 导致 FPS 无法采集的问题。
- 启动日志会输出当前实际使用后端，例如 `RingBuf` 或 `PerfEvent`，并单独提示 RingBuf 不可用原因。
- 优化目标应用长时间没有新帧时的日志语义，避免继续沿用旧 FPS。
- 新增 `queuebuffer_probe_perf.bpf.c`，构建时同时打包 RingBuf 和 PerfEvent 两套 eBPF 对象。

## Aya Lite 子模块

- 将 `fps_monitor/aya` 从本地 vendored 目录改为 `AppOpt-aya-lite` 子模块。
- `build_module.sh` 会自动初始化和更新 Aya Lite 子模块，减少首次构建漏拉依赖的问题。
- 子模块本地拉取时排除 `.github` 和 `scripts` 目录，保留编译需要的 Aya 代码和文档。
- 新增 GitHub Actions，用于定期检查 Aya Lite 更新、编译验证通过后自动提交子模块更新 PR。

## 构建和发布

- `build_module.sh` 新增 PerfEvent BPF 对象构建流程。
- App 包名改为从 `app/build.gradle.kts` 的 `applicationId` 自动读取，不再在脚本中硬编码。
- 更新 README 中的子模块拉取、BPF 构建和 release/publish 使用说明。
- 更新 Cargo.lock 到 Aya 0.14.0 / aya-obj 0.3.0。

## App 体验修复

- App 回到前台时重新检测 Root 授权状态，避免授权或撤销后首页状态过期。
- 调整运行环境卡片中未授权按钮的高度和内边距，减少未授权状态下的异常留白。
