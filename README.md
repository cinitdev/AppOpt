# AppOpt

AppOpt 是一个面向 Root Android 设备的 CPU 线程绑核优化模块，并带有配套图形 App。
本项目基于原版 AppOpt v1.6.3（作者 suto）扩展，新增自动校准、真实 FPS 监测、
历史负载记录和游戏内悬浮控制能力。

模块兼容 Magisk / KernelSU / APatch，规则可热重载，通常不需要重启设备即可生效。
模块内置 Rust 守护进程 `AppOptRs` 和 C 版守护进程 `AppOpt`，当前默认优先启动
`AppOptRs`，连续异常退出时自动回退到 C 版。

## 主要功能

- 按包名、线程名配置 CPU affinity / cpuset 规则。
- `auto` 自动校准：采样游戏线程负载后生成绑核规则。
- FPS 监测：优先使用 eBPF uprobe 捕获 `libgui.so` queueBuffer 帧事件。
- eBPF 不可用时自动降级到 SurfaceFlinger fallback。
- 历史记录：按 `pkg + epoch` 去重保存线程负载、FPS 会话和折线数据，按记录生成时间倒序显示。
- 配套 Android App：悬浮窗、环境自检、规则管理、历史/日志查看和自动校准策略设置。
- ActivityTaskManager 前台助手：`appopt_foreground_helper.jar` 常驻监听前台任务，并生成
  `package_uid.map` 供 Rust 守护进程做 UID 预过滤。
- 守护进程看门狗：Native daemon 异常退出后自动拉起。

## 工作原理

`service.sh` 会优先启动 Rust 守护进程 `AppOptRs`。Rust 版读取 `applist.conf`
和 `package_uid.map`，先用 UID 缩小候选进程范围，再缓存已命中的 PID/TID，减少长期运行时
对 `/proc` 的全量遍历。C 版 `AppOpt` 保留为兼容兜底，Rust 版连续快速崩溃时看门狗会在本次开机内切回 C 版。

守护进程读取配置文件后，持续扫描目标进程和线程，并按规则设置 CPU affinity
与 cpuset。`auto` 校准会采样线程 CPU 使用率，将相似线程名归组，按 `avg/max/score`
和 `calib_policy.conf` 策略排序，只给 Top 重载线程生成规则，再追加包名兜底规则，
避免生成过多静态规则。App 设置页可像 Scene 一样勾选每一档要使用的 CPU 核心，
保存后写入 `7`、`5-6`、`0-6` 这类连续核心范围。默认通配组按组内最高单线程判断，
避免多个低负载线程累加误升档。CPU 档位按最大频率簇识别，不把同频核心按编号硬拆，
以兼容 2 簇、3 簇、4 簇等不同处理器布局。

FPS 监测优先通过 Rust/aya 加载 eBPF 程序，并 attach 到目标 PID 的 `libgui.so`
queueBuffer 候选符号。Android 侧不再启用全局 uprobe；启动时如果暂时找不到目标 PID，
会等待后续前台进程确认后再启动 eBPF，避免把其它应用的帧算进去。设备内核、ROM 策略、
符号或目标 ABI 不满足条件时，会自动降级到
SurfaceFlinger `--latency` 路径，再按运行时探测结果兜底到 `--timestats`。FPS 数据优先由
Native 守护进程推送到 App 创建的 Android 本地 socket，socket 不可用时再写入 App 私有目录
`fps` 文件作为兜底。

前台识别优先使用 root `app_process` 启动的 `appopt_foreground_helper.jar`，它通过
ActivityTaskManager/TaskStackListener 写入 `foreground_task.state`。App 侧在 helper
不可用时再回退到 UsageStats、cgroup 前台组和 dumpsys 组合判断。

历史 `.log` 进入数据库后会按 `epoch` 去重并删除源文件。数据库 ID 只作为内部主键，
历史列表只显示记录生成时间，并按生成时间排序，新的在上面。

## 目录结构

```text
app/                             Android App
native_daemon/                   Native 守护进程源码、前台检测和 FPS 监测
native_daemon/daemon_rs/         Rust 守护进程 AppOptRs
tools/appopt_foreground_helper/  ActivityTaskManager 前台助手源码
magisk_module/                   模块基础文件
build_module.sh                  Native / Magisk 模块构建脚本
FPS_EBPF_INTEGRATION.md          eBPF 集成说明
AppOpt工作原理.svg                工作原理图
AppOpt改版特色.md                 功能特色说明
```

## 环境准备

### 推荐版本

本项目当前按以下环境维护：

- Windows + Android Studio。
- JDK 17。
- Android Gradle Plugin 8.13.2。
- Kotlin 2.0.21。
- `compileSdk = 36`，`targetSdk = 36`，`minSdk = 31`。
- Android SDK Platform 36。
- Android SDK Build-Tools，建议安装 Android Studio SDK Manager 中的最新版。
- Android NDK 28.x，当前验证环境为 `28.1.13356709`。
- Rust stable toolchain，当前验证环境为 `rustc 1.96.0`。
- Git Bash，用来执行 `build_module.sh`。

不需要 WSL。`build_module.sh` 会使用本机 Android SDK / NDK / Rust 工具链完成编译。

### 拉取子模块

项目使用 `native_daemon/fps_monitor/aya` 子模块保存精简版 Aya。首次克隆建议带上子模块：

```bash
git clone --recurse-submodules <repo-url>
```

如果已经克隆过项目，在项目根目录执行：

```bash
git submodule update --init --recursive
```

### 安装 Android Studio / SDK / NDK

1. 安装 Android Studio。
2. 打开 `Settings -> Languages & Frameworks -> Android SDK`。
3. 在 `SDK Platforms` 中安装：
   - `Android 16.0 / API 36`，也就是 `android-36`。
4. 在 `SDK Tools` 中安装：
   - `Android SDK Build-Tools`
   - `Android SDK Platform-Tools`
   - `Android SDK Command-line Tools`
   - `NDK (Side by side)`
   - `CMake` 可装可不装，当前 `build_module.sh` 不依赖 CMake。
5. NDK 建议选择 `28.x`。脚本不是写死 NDK 版本，但会自动选择 `SDK/ndk/` 下版本号最高的一个。

确认 SDK / NDK 目录：

```text
C:\Users\你的用户名\AppData\Local\Android\Sdk
C:\Users\你的用户名\AppData\Local\Android\Sdk\ndk\28.1.13356709
```

Android Studio 通常会自动生成 `local.properties`：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

如果没有 `local.properties`，也可以使用环境变量：

```bash
export ANDROID_SDK_ROOT=/path/to/android/sdk
export ANDROID_HOME=/path/to/android/sdk
```

如果安装了多个 NDK，并且不想让脚本自动选择最高版本，可以显式指定：

```bash
export ANDROID_NDK_HOME=/path/to/android/sdk/ndk/28.1.13356709
```

Windows PowerShell 对应写法：

```powershell
$env:ANDROID_SDK_ROOT = "C:\Users\你的用户名\AppData\Local\Android\Sdk"
$env:ANDROID_NDK_HOME = "C:\Users\你的用户名\AppData\Local\Android\Sdk\ndk\28.1.13356709"
```

### 安装 Rust

Windows 推荐用 `rustup-init.exe` 安装：

1. 打开 <https://rustup.rs/>。
2. 下载并运行 `rustup-init.exe`。
3. 按默认选项安装 stable toolchain。
4. 安装完成后重新打开 PowerShell 或 Git Bash。

检查安装结果：

```bash
rustc --version
cargo --version
rustup --version
```

如果命令不存在，说明 Rust 没有加入 `PATH`，重新打开终端，或检查：

```text
C:\Users\你的用户名\.cargo\bin
```

### 安装 Rust Android targets

原生模块会构建 4 个 Android ABI，需要安装这些 Rust target：

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
rustup target add i686-linux-android
```

检查是否安装成功：

```bash
rustup target list --installed
```

输出里应该包含：

```text
aarch64-linux-android
armv7-linux-androideabi
x86_64-linux-android
i686-linux-android
```

### 安装 Git Bash

Windows 下建议安装 Git for Windows，并使用 Git Bash 执行原生模块脚本：

<https://git-scm.com/download/win>

安装后确认：

```bash
bash --version
git --version
```

### 编译前检查

在项目根目录检查 App 编译环境：

```powershell
.\gradlew.bat --version
```

在 Git Bash 中检查 Native 模块编译环境：

```bash
which cargo
cargo --version
rustup target list --installed
ls "$ANDROID_SDK_ROOT/ndk"
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

在项目根目录执行，默认会编译 release APK 并打包进模块：

```bash
./build_module.sh
```

Windows 下建议用 Git Bash 执行：

```bash
cd /c/Users/你的用户名/AndroidStudioProjects/AppOpt
./build_module.sh
```

脚本参数：

```text
./build_module.sh [release|debug|no|publish] [--publish] [--dry-run]
```

常用命令：

```bash
# 编译 release APK 并打包进模块，默认行为
./build_module.sh
./build_module.sh release

# 编译 debug APK 并打包进模块
./build_module.sh debug

# 只编译模块，不打包 App
./build_module.sh no

# 编译 release 模块，发布 AppOpt.zip 和 changelog.md 到 GitHub Release，
# 然后基于远端最新提交更新 modules-update 分支的 AppOpt.json
./build_module.sh publish

# 等价写法
./build_module.sh release --publish

# 完整预演发布，不创建 Release、不提交、不推送
./build_module.sh publish --dry-run
```

发布功能会调用 GitHub CLI。首次使用前需要安装并登录：

```bash
gh auth login
```

发布脚本会先抓取 `origin/modules-update`，在独立临时 worktree 中只更新
`modules_update/AppOpt.json`。当前工作区和本地 `modules-update` 分支不会被切换或覆盖，
远端分支中的 `changelog.md` 及其他网页修改会保持不变。脚本不会强制推送；如果发布期间
远端分支再次产生新提交，会停止推送并要求重新执行。

Release tag 会自动读取当前模块版本，优先使用 `magisk_module/module.prop` 中的
`version`，例如 `v1.7.3`；如果模块版本为空，则读取 App 的 `versionName`。
如果对应 Release 已存在，脚本会更新 Release 说明并覆盖上传资源。

脚本会完成：

- 编译 `native_daemon/fps_monitor/bpf/queuebuffer_probe.bpf.c` 为 RingBuf BPF 对象。
- 编译 `native_daemon/fps_monitor/bpf/queuebuffer_probe_perf.bpf.c` 为 PerfEvent 备用 BPF 对象。
- 编译 Rust/aya bridge 静态库。
- 编译 Rust 守护进程 `AppOptRs`，并打包 4 个 ABI 产物。
- 按参数编译 release/debug APK，并复制到模块的 `config/app/`。
- 编译 App 安装辅助工具，并打包到模块的 `config/app/tools/`。
- 编译 ActivityTaskManager 前台助手 `appopt_foreground_helper.jar`，并打包到模块的
  `config/tools/`。
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
build/module/config/bin/<abi>/AppOpt   各 ABI native 二进制
build/module/config/bin/<abi>/AppOptRs 各 ABI Rust 守护进程
build/module/config/tools/appopt_foreground_helper.jar
build/module/config/ebpf/queuebuffer_probe.bpf.o   eBPF 对象
build/module/config/ebpf/queuebuffer_probe_perf.bpf.o   eBPF PerfEvent 备用对象
build/AppOpt.zip                       可刷入模块包
```

### 常见编译错误

`! 无法确定 Android SDK 目录`

说明脚本没有读到 `local.properties`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。先确认
`local.properties` 是否存在，或手动设置 SDK 环境变量。

`! 找不到 Android NDK`

说明 SDK 目录下没有 `ndk/`，或 `ANDROID_NDK_HOME` 指向了错误目录。用 Android Studio
SDK Manager 安装 `NDK (Side by side)`，建议安装 NDK 28.x。

`! 找不到 cargo`

说明 Rust 没装好，或当前终端没有读取到 `%USERPROFILE%\.cargo\bin`。

`! 未安装 Rust target: ...`

执行 `rustup target add ...` 安装 README 上面列出的 4 个 Android target。

`Aya 无法解析 BTF` 或 eBPF 加载失败

BPF 对象需要保留 BTF。当前脚本已经给 BPF 编译保留 `-g`，同时通过路径映射避免写入本机绝对路径。
如果手动改过编译参数，不要去掉 BPF 对象的 `-g`。

## 安装和使用

1. 编译或下载模块 zip。
2. 在 Magisk / KernelSU / APatch 中刷入模块。
3. 重启或按管理器要求重新加载模块。
4. 安装 Android App。
5. 在 App 中授予 Root、悬浮窗、使用情况访问等权限。
6. 进入游戏后可通过悬浮窗查看 FPS，并进行 `auto` 校准；再次点击悬浮窗或退出目标应用前台时，会停止采样并显示校准结果与生成规则。

## 注意事项

- 需要 Root 权限。
- eBPF 依赖设备内核、ROM 策略、符号和目标应用 ABI；不可用时会自动 fallback。
- `auto` 规则不按固定线程名判断职责，而是按采样负载分级。
- `build/`、`app/build/`、`*.log` 和模块 zip 属于生成产物，不应提交到仓库。

## 文档

- [AppOpt改版特色.md](AppOpt改版特色.md)
- [FPS_EBPF_INTEGRATION.md](FPS_EBPF_INTEGRATION.md)
- [native_daemon/fps_monitor/README.md](native_daemon/fps_monitor/README.md)

## 致谢

原版 AppOpt v1.6.3 作者为 suto。本增强版在原有线程绑核守护进程基础上，
扩展了自动校准、FPS 监测、历史记录和图形 App 等能力。
