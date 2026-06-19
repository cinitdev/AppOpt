# AppOpt

AppOpt 是一个面向 Root Android 设备的 CPU 线程绑核优化模块，并带有配套图形 App。
本项目基于原版 AppOpt v1.6.3（作者 suto）扩展，新增自动校准、真实 FPS 监测、
历史负载记录和游戏内悬浮控制能力。

模块兼容 Magisk / KernelSU / APatch，规则可热重载，通常不需要重启设备即可生效。

## 主要功能

- 按包名、线程名配置 CPU affinity / cpuset 规则。
- `auto` 自动校准：采样游戏线程负载后生成绑核规则。
- FPS 监测：优先使用 eBPF uprobe 捕获 `libgui.so` queueBuffer 帧事件。
- eBPF 不可用时自动降级到 SurfaceFlinger fallback。
- 历史记录：保存线程负载、FPS 会话和折线数据。
- 配套 Android App：悬浮窗、环境自检、规则管理、历史记录查看。
- 守护进程看门狗：Native daemon 异常退出后自动拉起。

## 工作原理

Native 守护进程读取配置文件后，持续扫描目标进程和线程，并按规则设置 CPU affinity
与 cpuset。`auto` 校准会采样线程 CPU 使用率，将相似线程名归组，按 `avg/max/score`
排序，只给 Top 重载线程生成规则，再追加包名兜底规则，避免生成过多静态规则。

FPS 监测优先通过 Rust/aya 加载 eBPF 程序，并 attach 到 `libgui.so` 的 queueBuffer
候选符号。设备内核、ROM 策略、符号或目标 ABI 不满足条件时，会自动降级到
SurfaceFlinger 路径。

## 目录结构

```text
AppOpt.c                         Native 守护进程主源码
app/                             Android App
fps_monitor/                     FPS 监测、eBPF bridge 和 fallback
magisk_module/                   模块基础文件
build_module.sh                  Native / Magisk 模块构建脚本
FPS_EBPF_INTEGRATION.md          eBPF 集成说明
AppOpt工作原理.svg                工作原理图
AppOpt改版特色.md                 功能特色说明
```

## 环境准备

### 基础环境

- Windows + Android Studio，或其它能运行 Android Gradle / Bash 脚本的环境。
- JDK 17。
- Android SDK。
- Android SDK Build-Tools。
- Android NDK，建议安装 Android Studio SDK Manager 中较新的 NDK。
- Rust toolchain（`rustup` + `cargo`）。
- Git Bash 或同类 Bash 环境，用于执行 `build_module.sh`。

不需要 WSL。当前脚本会优先读取 `local.properties` 中的 `sdk.dir`，读取不到时再使用
`ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。

### Rust target

原生模块会构建 4 个 Android ABI，需要安装这些 Rust target：

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
rustup target add i686-linux-android
```

### Android SDK 路径

Android Studio 通常会自动生成 `local.properties`：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

也可以使用环境变量：

```bash
export ANDROID_SDK_ROOT=/path/to/android/sdk
export ANDROID_HOME=/path/to/android/sdk
```

如果安装了多个 NDK，`build_module.sh` 会自动选择 `SDK/ndk/` 下版本号最高的一个。
也可以显式指定：

```bash
export ANDROID_NDK_HOME=/path/to/android/sdk/ndk/xx.x.xxxxxxx
```

## 编译 Android App

Debug 包：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 包：

```bash
./gradlew assembleRelease
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleRelease
```

产物位置：

```text
app/build/outputs/apk/release/app-release.apk
```

## 编译 Native / Magisk 模块

在项目根目录执行：

```bash
./build_module.sh
```

Windows 下建议用 Git Bash 执行：

```bash
cd /c/Users/你的用户名/AndroidStudioProjects/AppOpt
./build_module.sh
```

脚本会完成：

- 编译 `fps_monitor/bpf/queuebuffer_probe.bpf.c` 为 BPF 对象。
- 编译 Rust/aya bridge 静态库。
- 编译 4 个 ABI 的 `AppOpt` native 二进制：
  - `arm64-v8a`
  - `armeabi-v7a`
  - `x86_64`
  - `x86`
- 复制 `magisk_module/` 基础文件。
- 打包 Magisk 模块 zip。

主要产物：

```text
build/module/                         模块工作目录
build/module/bin/<abi>/AppOpt          各 ABI native 二进制
build/module/queuebuffer_probe.bpf.o   eBPF 对象
build/AppOpt-增强版.zip                可刷入模块包
```

## 安装和使用

1. 编译或下载模块 zip。
2. 在 Magisk / KernelSU / APatch 中刷入模块。
3. 重启或按管理器要求重新加载模块。
4. 安装 Android App。
5. 在 App 中授予 Root、悬浮窗、使用情况访问等权限。
6. 进入游戏后可通过悬浮窗查看 FPS，并进行 `auto` 校准。

## 注意事项

- 需要 Root 权限。
- eBPF 依赖设备内核、ROM 策略、符号和目标应用 ABI；不可用时会自动 fallback。
- `auto` 规则不按固定线程名判断职责，而是按采样负载分级。
- `build/`、`app/build/`、`*.log` 和模块 zip 属于生成产物，不应提交到仓库。

## 文档

- [AppOpt改版特色.md](AppOpt改版特色.md)
- [FPS_EBPF_INTEGRATION.md](FPS_EBPF_INTEGRATION.md)
- [fps_monitor/README.md](fps_monitor/README.md)

## 致谢

原版 AppOpt v1.6.3 作者为 suto。本增强版在原有线程绑核守护进程基础上，
扩展了自动校准、FPS 监测、历史记录和图形 App 等能力。
