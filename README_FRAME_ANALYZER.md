# frame-analyzer-ebpf 对接指南

本文档说明如何将 AppOpt 的 eBPF 帧率监测从自研实现改为对接 [frame-analyzer-ebpf](https://github.com/shadow3aaa/frame-analyzer-ebpf)。

---

## 🎯 快速开始（推荐）

### 一键完成所有操作

```cmd
integrate_frame_analyzer.bat
```

这个脚本会自动：
1. 编译 frame-analyzer-wrapper
2. 替换 ebpf_fps.c 实现
3. 重新编译 AppOpt 模块

---

## 📋 分步操作

如果一键脚本失败，可以分步执行：

### 步骤 1：编译 frame-analyzer

```cmd
build_frame_analyzer.bat
```

**功能**：
- 自动查找 Android NDK（支持 NDK 25-28+）
- 交叉编译 Rust 项目到 Android
- 输出到 `magisk_module/bin/frame-analyzer`

**要求**：
- ✅ 已安装 Rust (`rustup.rs`)
- ✅ 已添加 Android target: `rustup target add aarch64-linux-android`
- ✅ 已安装 Android NDK

### 步骤 2：替换实现

手动操作：
```cmd
cd fps_monitor
ren ebpf_fps.c ebpf_fps_old.c.bak
ren ebpf_fps_new.c ebpf_fps.c
cd ..
```

### 步骤 3：编译 AppOpt

```bash
bash build_module.sh
```

---

## 🧪 测试

### 快速测试脚本

```cmd
test_frame_analyzer.bat
```

**功能**：
- 检查 ADB 连接和 root 权限
- 查找目标应用进程
- 运行 frame-analyzer 并显示帧率
- 验证 eBPF 功能是否正常

**支持的应用**：
- 王者荣耀
- 和平精英
- 原神
- 崩坏：星穹铁道
- 自定义包名

### 手动测试

```bash
# 1. 推送模块到手机
adb push build/AppOpt-增强版.zip /sdcard/

# 2. 安装模块
adb shell su -c "magisk --install-module /sdcard/AppOpt-增强版.zip"

# 3. 重启
adb reboot

# 4. 启动游戏，获取 PID
adb shell pidof com.tencent.tmgp.sgame

# 5. 测试 frame-analyzer
adb shell su -c "/data/adb/modules/AppOpt/bin/frame-analyzer <PID>"

# 6. 查看日志
adb logcat | grep FPS
```

---

## 📁 文件说明

### 核心文件

| 文件 | 说明 |
|------|------|
| `frame-analyzer-wrapper/` | Rust 项目目录 |
| `frame-analyzer-wrapper/Cargo.toml` | 依赖配置 |
| `frame-analyzer-wrapper/src/main.rs` | 主程序（对接 frame-analyzer-ebpf） |
| `fps_monitor/ebpf_fps_new.c` | 新的 C 实现（通过 fork/exec 调用 frame-analyzer） |
| `fps_monitor/ebpf_fps_old.c.bak` | 原实现备份 |

### 脚本文件

| 脚本 | 用途 |
|------|------|
| `integrate_frame_analyzer.bat` | 🌟 **一键完成所有操作** |
| `build_frame_analyzer.bat` | 编译 frame-analyzer-wrapper |
| `test_frame_analyzer.bat` | 测试 eBPF 功能是否正常 |
| `build_frame_analyzer.sh` | Linux/WSL 版本编译脚本 |

### 文档文件

| 文档 | 说明 |
|------|------|
| `FRAME_ANALYZER_INTEGRATION.md` | 详细技术文档 |
| `README_FRAME_ANALYZER.md` | 本文档（快速指南） |

---

## 🔧 环境要求

### 必需

- ✅ **Rust 工具链**
  ```cmd
  # 访问 https://rustup.rs 安装
  rustup target add aarch64-linux-android
  ```

- ✅ **Android NDK**
  - 支持版本：NDK 25 - 28+
  - 自动查找路径：
    - `%LOCALAPPDATA%\Android\Sdk\ndk\*`
    - `%USERPROFILE%\AppData\Local\Android\Sdk\ndk\*`
  - 手动设置：
    ```cmd
    set ANDROID_NDK_HOME=C:\path\to\ndk\28.x.xxxxx
    ```

### 可选

- ✅ **Git Bash / WSL** - 用于运行 `build_module.sh`
- ✅ **ADB** - 用于测试

---

## ❓ 常见问题

### Q1: 编译失败 "linker not found"

**原因**：NDK 路径未设置或不正确

**解决**：
```cmd
# 方法 1: 让脚本自动查找
build_frame_analyzer.bat

# 方法 2: 手动设置
set ANDROID_NDK_HOME=C:\Users\YourName\AppData\Local\Android\Sdk\ndk\28.0.12674087
```

### Q2: "frame-analyzer 未找到"

**原因**：模块未正确安装

**解决**：
```bash
# 检查文件是否存在
adb shell ls -l /data/adb/modules/AppOpt/bin/frame-analyzer

# 检查权限
adb shell su -c "chmod +x /data/adb/modules/AppOpt/bin/frame-analyzer"
```

### Q3: frame-analyzer 无输出

**原因**：应用未在前台运行或内核不支持 eBPF

**解决**：
1. 确保应用在前台运行
2. 检查内核是否支持 eBPF（Android 12+ 通常支持）
3. 查看日志：`adb logcat | grep -i ebpf`

### Q4: 编译后模块体积增大

**原因**：frame-analyzer 约 2-3 MB

**优化**：
- 已经使用 `llvm-strip` 优化
- 可以考虑用 UPX 压缩（需要测试兼容性）

### Q5: 需要更新 frame-analyzer

**操作**：
1. 更新 `frame-analyzer-wrapper/Cargo.toml` 中的版本
2. 重新运行 `build_frame_analyzer.bat`
3. 重新编译模块

---

## 📊 性能对比

| 指标 | 自研实现 | frame-analyzer |
|------|----------|----------------|
| 兼容性 | ⚠️ 部分设备失败 | ✅ 广泛验证 |
| 维护成本 | ❌ 需自行跟进 | ✅ 持续维护 |
| 内存占用 | ✅ 约 1 MB | ⚠️ 约 5-10 MB |
| CPU 占用 | ✅ 极低 | ✅ 极低 |
| 模块体积 | ✅ 约 600 KB | ⚠️ +2-3 MB |
| 符号识别 | ⚠️ 手动维护 | ✅ 自动识别 |

---

## 📝 许可证

- **frame-analyzer-ebpf**：GPL-3.0
- **AppOpt**：对接后整体受 GPL-3.0 约束

---

## 🔗 参考资源

- [frame-analyzer-ebpf](https://github.com/shadow3aaa/frame-analyzer-ebpf)
- [fas-rs](https://github.com/shadow3aaa/fas-rs)
- [Rust 交叉编译](https://rust-lang.github.io/rustup/cross-compilation.html)
- [Android NDK 下载](https://developer.android.com/ndk/downloads)

---

## 📮 反馈

如有问题，请：
1. 查看详细文档：`FRAME_ANALYZER_INTEGRATION.md`
2. 运行测试脚本：`test_frame_analyzer.bat`
3. 查看日志：`adb logcat | grep FPS`
4. 在酷安反馈：@一只小柒夏
