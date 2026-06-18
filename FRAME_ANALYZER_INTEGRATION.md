# frame-analyzer-ebpf 对接文档

## 概述

将 AppOpt 的 eBPF 帧率监测从自研实现改为对接 [frame-analyzer-ebpf](https://github.com/shadow3aaa/frame-analyzer-ebpf) 项目。

### 为什么对接？

1. **兼容性更好**：frame-analyzer-ebpf 在大量设备上验证过（fas-rs 使用）
2. **维护成本低**：shadow3aaa 持续维护，跟随 Android 版本更新
3. **避免自研风险**：uprobe/type 在某些设备上不可用，自研实现可能失败

---

## 架构设计

### 修改前
```
AppOpt.c
  ↓
ebpf_fps.c (自研)
  ↓
直接调用 perf_event_open + bpf()
```

### 修改后
```
AppOpt.c
  ↓
ebpf_fps_new.c (wrapper)
  ↓ fork/exec + pipe
frame-analyzer (Rust 二进制)
  ↓
frame-analyzer-ebpf (库)
  ↓
eBPF uprobe
```

---

## 文件清单

### 新增文件

1. **frame-analyzer-wrapper/** - Rust 包装程序
   - `Cargo.toml` - 依赖配置
   - `src/main.rs` - 主程序（接收 PID，输出 FPS）

2. **fps_monitor/ebpf_fps_new.c** - 新的 C 实现
   - 替换原 `ebpf_fps.c`
   - 通过 fork/exec 启动 frame-analyzer
   - 通过 pipe 读取帧率数据

3. **build_frame_analyzer.sh** - 编译脚本
   - 交叉编译 Rust 到 Android
   - 输出到 `magisk_module/bin/frame-analyzer`

---

## 编译步骤

### 前置要求

1. **Rust 工具链**
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   rustup target add aarch64-linux-android
   ```

2. **Android NDK**
   - 下载：https://developer.android.com/ndk/downloads
   - 设置环境变量：
     ```bash
     export ANDROID_NDK_HOME=/path/to/android-ndk-r25c
     ```

### 编译 frame-analyzer

```bash
cd /path/to/AppOpt
bash build_frame_analyzer.sh
```

输出：`magisk_module/bin/frame-analyzer` (约 2-3 MB)

### 替换 ebpf_fps.c

```bash
cd fps_monitor
mv ebpf_fps.c ebpf_fps_old.c.bak
mv ebpf_fps_new.c ebpf_fps.c
```

### 重新编译 AppOpt

```bash
bash build_module.sh
```

---

## 接口说明

### frame-analyzer 命令行接口

```bash
# 启动监测
frame-analyzer <pid>

# 输出格式（stdout）
60.5
59.8
61.2
...
```

- 每行一个浮点数，表示当前 FPS
- 基于滑动窗口（最近 10 帧）平均
- stderr 用于错误日志（已重定向到 /dev/null）

### C API 接口（保持不变）

```c
// 探测能力
ebpf_cap_t ebpf_fps_probe_capability(void);

// 启动监测（bpf_obj_path 参数被忽略）
ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid);

// 轮询数据
int ebpf_fps_poll(ebpf_fps_ctx *ctx);

// 获取 FPS
double ebpf_fps_get(ebpf_fps_ctx *ctx);

// 停止监测
void ebpf_fps_stop(ebpf_fps_ctx *ctx);
```

---

## 测试方法

### 1. 检查 frame-analyzer 是否存在

```bash
adb shell ls -lh /data/adb/modules/AppOpt/bin/frame-analyzer
```

### 2. 手动测试 frame-analyzer

```bash
# 启动游戏（例如王者荣耀）
adb shell am start com.tencent.tmgp.sgame/.SGameActivity

# 获取 PID
adb shell pidof com.tencent.tmgp.sgame

# 运行 frame-analyzer
adb shell su -c "/data/adb/modules/AppOpt/bin/frame-analyzer <pid>"

# 应该看到输出：
# 60.2
# 59.8
# ...
```

### 3. 检查 AppOpt 日志

```bash
adb logcat | grep FPS
```

期望看到：
```
[FPS] eBPF 已激活, 锁定符号: frame-analyzer-ebpf
[FPS] eBPF 首次捕获到帧率: 60.5 fps
```

---

## 故障排查

### 问题 1：frame-analyzer 未找到

**症状**：
```
[FPS] eBPF 能力: frame-analyzer 未找到
```

**解决**：
```bash
# 检查文件是否存在
adb shell ls -l /data/adb/modules/AppOpt/bin/frame-analyzer

# 检查权限
adb shell su -c "chmod +x /data/adb/modules/AppOpt/bin/frame-analyzer"
```

### 问题 2：frame-analyzer 无法运行

**症状**：
```
[FPS] 目标进程 PID: 12345, 尝试 eBPF uprobe...
[FPS] 未找到进程, 降级到 SF fallback
```

**解决**：
```bash
# 手动运行检查错误
adb shell su -c "/data/adb/modules/AppOpt/bin/frame-analyzer 12345"

# 常见原因：
# 1. SELinux 拒绝 → 添加 sepolicy 规则
# 2. 缺少依赖库 → 检查 ldd（但 Android 静态链接 Rust 一般不需要）
# 3. 内核不支持 eBPF → 降级到 SF fallback（预期行为）
```

### 问题 3：编译失败

**症状**：
```bash
error: linker `aarch64-linux-android30-clang` not found
```

**解决**：
```bash
# 检查 NDK 路径
echo $ANDROID_NDK_HOME

# 检查交叉编译工具链
ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/"
```

---

## 性能影响

### 内存占用
- frame-analyzer 进程：约 5-10 MB
- AppOpt 主进程：无额外开销（只是 fork+pipe）

### CPU 占用
- eBPF uprobe：几乎无开销（内核态）
- 数据传输：每帧约 100 字节（可忽略）

### 启动延迟
- fork/exec：约 50-100ms（一次性）
- 符号定位：frame-analyzer 内部处理（< 1s）

---

## 后续优化

1. **减小二进制体积**
   - 当前约 2-3 MB
   - 可以用 UPX 压缩到 1 MB 以下

2. **错误重试机制**
   - 当前 frame-analyzer 失败后立即降级
   - 可以添加重试（如游戏刚启动时）

3. **热更新支持**
   - frame-analyzer 更新无需重新编译 AppOpt
   - 只需替换二进制文件

---

## 许可证

- **frame-analyzer-ebpf**：GPL-3.0
- **AppOpt**：保持原有许可证
- **注意**：对接后 AppOpt 模块整体受 GPL-3.0 约束

---

## 参考资源

- frame-analyzer-ebpf: https://github.com/shadow3aaa/frame-analyzer-ebpf
- fas-rs: https://github.com/shadow3aaa/fas-rs
- Android NDK: https://developer.android.com/ndk
- Rust 交叉编译: https://rust-lang.github.io/rustup/cross-compilation.html
