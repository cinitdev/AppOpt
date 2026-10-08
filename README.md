# 柒夏线程 · Qixia Threads

柒夏线程是一个面向 Root Android 设备的 CPU 线程调度模块，并带有配套图形 App。
项目来源与第三方说明见 [SOURCE_ATTRIBUTION.md](SOURCE_ATTRIBUTION.md)。提供线程规则、自动核心分配、
校准建议、FPS 与性能采集、历史报告分析和游戏内悬浮控制。

模块兼容 Magisk / KernelSU / APatch，规则可热重载，通常不需要重启设备即可生效。
模块只维护 Rust 守护进程 `QiXiaRs`；异常退出时由看门狗继续拉起 Rust 守护进程。

## 主要功能

- 按包名、线程名配置 CPU affinity / cpuset 规则。
- `auto` 校准：采样线程负载，生成核心建议与待确认结果，由用户确认后保存规则。
- 自动分配：平均负载达到 5% 的活跃线程参与分配，按核心能力与争用持续调整。
- FPS 监测：优先使用 eBPF uprobe 捕获 `libgui.so` queueBuffer 帧事件。
- eBPF 不可用时自动降级到 SurfaceFlinger fallback。
- 历史记录：区分校准记录与自动分配记录，保留活跃线程、FPS、功率、电流、电量、温度、CPU/GPU 使用率及频率等可用数据，支持游标联动、指标展开和批量删除。
- 自动分配记录默认关闭，开启后仅保存使用超过 3 分钟的会话；规则应用及未开启记录的自动分配应用只保留最近使用摘要，不生成详细历史。
- 报告分析：FPS 图标出疑似掉帧区间，点击关联附近的线程负载、CPU/GPU 指标和核心事件；报告右上角可选择同应用的另一条校准或自动分配记录，对比完整会话的 FPS、功率与电量。
- 核心接管诊断：在已开启自动分配的应用管理中打开，核对最近目标、实际允许范围、cpuset 和最近操作结果；关闭历史记录也能使用，不新增运行数据文件。
- 配套 Android App：蓝白圆角界面，首页、应用、历史、日志和设置；支持启动检查更新、Markdown 更新日志与模块刷入。
- 可与 Scene 核心分配共存：App 启动或返回前台时，把已安装的配置应用与自动分配应用包名去重同步到 Scene 黑名单。
- ActivityTaskManager 前台助手：`qixia_foreground_helper.jar` 常驻监听前台任务，并生成
  `package_uid.map` 供 Rust 守护进程做 UID 预过滤。
- 守护进程看门狗：Rust daemon 异常退出后自动拉起。

## 工作原理

应用管理中开启自动分配后，`config/auto_affinity.conf` 每行保存一个包名，
关闭自动分配则删除该行。同一应用只保留一条配置，已有线程规则和规则检测暂停，原规则保留。
Rust 持续采样线程负载，依据核心拓扑和联合容量预算接管 cpuset；不锁频、不强制停核，也不将温度作为调度条件。
校准建议继续使用平均负载 5% 门槛，自动分配历史仍由原有记录开关独立控制。

掉帧定位依据已保存的 FPS 和区间最大帧间隔，表示采样区间而非逐帧追踪；低帧采样占比也不是逐帧慢帧比例。
现有记录未保存完整逐帧分布，因此不计算帧耗时 P95/P99；缺失的指标不补零。
接管诊断在内存中保留最近四个应用、每个应用最多 512 条线程快照，打开或刷新时由 Rust 只读核对实际状态。
应用离开前台后的正常释放会单独说明；分析和诊断均不更改线程分配算法，也不添加目标 FPS 参数。

`service.sh` 会启动 Rust 守护进程 `QiXiaRs`。Rust 版读取 `applist.conf`
和 `package_uid.map`，先用 UID 缩小候选进程范围，再缓存已命中的 PID/TID，减少长期运行时
对 `/proc` 的全量遍历。守护进程异常退出后，看门狗会在短暂延迟后重新启动 `QiXiaRs`。

守护进程读取配置后持续扫描目标进程和线程，按规则设置 CPU affinity 与 cpuset。
校准建议结合实测负载、CPU 能力和核心争用生成，保留通配合并、主进程兜底和子进程整体规则；
不再使用旧版 Top 6 上限、固定负载档位或固定核心编号。每个应用只保留一份待确认结果，
新结果覆盖旧结果，确认前不会改写 `applist.conf`。

核心拓扑根据设备提供的 CPU capacity、频率策略等信息识别，设置页按实际性能组展示，
不预设必须有三组。手动规则支持连续及非连续范围，例如 `0-3`、`4,6`、`0-3,7`；
默认使用单行语法，也可切换函数语法。设置修改即时保存。

FPS 监测优先通过 Rust/aya 加载 eBPF 程序，并 attach 到目标 PID 的 `libgui.so`
queueBuffer 候选符号。Android 侧不再启用全局 uprobe；启动时如果暂时找不到目标 PID，
会等待后续前台进程确认后再启动 eBPF，避免把其它应用的帧算进去。设备内核、ROM 策略、
符号或目标 ABI 不满足条件时，会自动降级到
SurfaceFlinger `--latency` 路径，再按运行时探测结果兜底到 `--timestats`。FPS 数据优先由
Rust 守护进程推送到 App 创建的 Android 本地 socket，socket 不可用时再写入 App 私有目录
`fps` 文件作为兜底。

eBPF 采集的是应用提交帧率，不等同于屏幕实际呈现帧率。FPS 按有效帧间隔计算，
没有写死 120／144／165 Hz 屏幕上限；运行中刷新率变化也无需额外配置。
短间隔重复事件不会推进上一有效帧的时间戳，避免缩短统计时长导致 FPS 虚高。
当前最小有效间隔为 1 毫秒，对应最高 1000 FPS 的技术边界；不通过屏幕限幅隐藏偏差。

前台识别优先使用 root `app_process` 启动的 `qixia_foreground_helper.jar`，它通过
ActivityTaskManager/TaskStackListener 写入 `foreground_task.state`。App 侧在 helper
不可用时再回退到 UsageStats、cgroup 前台组和 dumpsys 组合判断。

校准草稿和历史暂存数据位于 App 私有目录，按需创建；历史成功导入数据库后清理对应源文件及空目录。
历史保留全部活跃线程，不受分配建议的 5% 门槛影响。自动分配操作逐次记录，系统实际运行核心变化
按采样观察记录，界面区分两种来源。长会话数据分段并压缩，列表按生成时间倒序展示。

## 目录结构

```text
app/                             Android App
native_daemon/daemon_rs/          Rust 守护进程 QiXiaRs
native_daemon/fps_monitor/        FPS/eBPF 监测与 Aya 子模块
native_daemon/history_probe/      Rust 性能指标采集
native_daemon/kernel_info/        CPU 与内核信息识别
tools/qixia_foreground_helper/    ActivityTaskManager 前台助手源码
tools/qixia_pkg_helper/           APK 安装助手源码
tools/windows_builder/           Windows 可视化编译工具源码
magisk_module/                   模块基础文件
build_module.sh                  Rust / Magisk 模块构建脚本
FPS_EBPF_INTEGRATION.md          eBPF 集成说明
SOURCE_ATTRIBUTION.md             项目来源与第三方说明
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
- Python 3，用于构建脚本中的版本同步和发布辅助。

不需要 WSL。`build_module.sh` 会使用本机 Android SDK / NDK / Rust 工具链完成编译。

### 拉取子模块

项目使用 `native_daemon/fps_monitor/aya` 子模块保存精简版 Aya。首次克隆建议带上子模块：

```bash
git clone --recurse-submodules https://github.com/cinitdev/AppOpt.git
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

### Windows 可视化构建

双击 `build/QixiaBuilder.exe` 打开「柒夏线程 · 构建中心」。首次生成或修改工具后，在项目根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\windows_builder\build.ps1
# 同时验证参数、日志、项目锁和停止进程树
powershell -ExecutionPolicy Bypass -File .\tools\windows_builder\build.ps1 -Test
```

工具使用 Windows 自带的 .NET Framework，不需要 Node 或额外安装 .NET SDK；编译项目仍需下文所述的 Git Bash、Python、JDK、Android SDK / NDK 和 Rust。EXE 可以移动到其他位置，打开后选择包含 `build_module.sh` 的完整源码目录。

- 支持正式版、调试版、仅模块，以及 Gitee / GitHub 发布和发布预演，直接调用原有脚本。
- 顶部可编辑版本名和版本编号，点击「保存版本」或开始构建时，统一写入 App、模块、Rust 守护和更新清单，并同步下载链接中的版本标签。重新载入可读取外部编辑；构建期间不能修改版本。
- 默认更新 Aya 子模块；可取消更新，也可临时指定 JDK / NDK。SDK 优先读取项目 `local.properties`。
- 实时显示构建日志、当前步骤和耗时；停止或退出会终止本次构建的进程树。同一项目不能同时从两个构建窗口启动，使用工具时也请避免另行运行构建脚本。
- 日志固定写入 `build/build-gui.log`，下次任务覆盖；可在界面手动导出。界面只保留最近输出，完整内容以导出日志为准。
- Gitee Token 可临时输入或使用 `GITEE_TOKEN`，不保存到配置文件；GitHub 使用已经登录的 `gh`。需要交互登录的凭据请先在终端配置。发布前会确认目标平台；预演仍会完整编译并检查远端，但不会创建 Release 或推送分支。
- 成功后可定位模块 ZIP / APK。发布失败时，已生成的 ZIP 会单独提示，不会把发布标记为成功。

### 命令行构建

在项目根目录执行，默认会编译 release APK 并打包进模块：

```bash
./build_module.sh
```

Windows 下建议用 Git Bash 执行：

```bash
cd /c/Users/你的用户名/AndroidStudioProjects/QixiaThreads
./build_module.sh
```

脚本参数：

```text
./build_module.sh [release|debug|no|publish] [--publish] [--github|--gitee] [--dry-run]
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

# 编译 release 模块，默认只上传 QixiaThreads.zip 到 Gitee Release，
# 然后更新 Gitee modules-update 分支的 QixiaThreads.json 和完整 changelog.md
./build_module.sh publish

# 等价写法
./build_module.sh release --publish

# 完整预演发布，不创建 Release、不提交、不推送
./build_module.sh publish --dry-run

# 发布到 GitHub
./build_module.sh publish --github
```

默认发布到 Gitee，需要配置 `GITEE_TOKEN` 并具备该仓库的 Git 推送权限。
选择 `--github` 时会调用 GitHub CLI，首次使用前需要安装并登录：

```bash
gh auth login
```

发布脚本会先抓取所选平台最新的 `modules-update`，在独立临时 worktree 中更新
`modules_update/QixiaThreads.json` 和 `modules_update/changelog.md`。当前工作区和本地
`modules-update` 分支不会被切换或覆盖，远端其他文件保持不变。脚本不会强制推送；如果发布期间
远端分支再次产生新提交，会停止推送并要求重新执行。

日志在本地 `modules_update/changelog.md` 中按版本累积，新版本写在前面并保留旧版内容。
Release 只上传模块 ZIP，说明中提供完整日志链接；更新 JSON 的 `changelog` 指向
`modules-update` 分支的固定原始文件地址，App 会显示整份 Markdown。
Gitee 日志地址为：
<https://gitee.com/cinitdev/AppOpt/blob/modules-update/modules_update/changelog.md>。
只修改日志时，可单独将该文件推送到这个分支，无需重新构建 APK 或发布版本。

Release tag 会自动读取当前模块版本，优先使用 `magisk_module/module.prop` 中的
`version`，例如 `v1.7.3`；如果模块版本为空，则读取 App 的 `versionName`。
如果对应 Release 已存在，脚本会更新 Release 说明并覆盖上传资源。

脚本会完成：

- 编译 `native_daemon/fps_monitor/bpf/queuebuffer_probe.bpf.c` 为 RingBuf BPF 对象。
- 编译 `native_daemon/fps_monitor/bpf/queuebuffer_probe_stats.bpf.c` 为 StatsMap BPF 对象。
- 编译 `native_daemon/fps_monitor/bpf/queuebuffer_probe_perf.bpf.c` 为 PerfEvent 备用 BPF 对象。
- 通过 Cargo 编译 Rust/aya bridge 与 Rust 守护进程 `QiXiaRs`。
- 打包 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 四个 ABI 产物。
- 按参数编译 release/debug APK，并复制到模块的 `config/app/`。
- 编译 App 安装辅助工具，并打包到模块的 `config/tools/`。
- 编译 ActivityTaskManager 前台助手 `qixia_foreground_helper.jar`，并打包到模块的
  `config/tools/`。
- 复制 `magisk_module/` 基础文件。
- 打包 Magisk 模块 zip。

主要产物：

```text
build/module/                         模块工作目录
build/module/config/bin/<abi>/QiXiaRs 各 ABI Rust 守护进程
build/module/config/tools/qixia_foreground_helper.jar
build/module/config/ebpf/queuebuffer_probe.bpf.o   eBPF 对象
build/module/config/ebpf/queuebuffer_probe_perf.bpf.o   eBPF PerfEvent 备用对象
build/QixiaThreads.zip                       可刷入模块包
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

## 源码提交与注意事项

- 需要 Root 权限。
- eBPF 依赖设备内核、ROM 策略、符号和目标应用 ABI；不可用时会自动 fallback。
- `auto` 规则不按固定线程名判断职责，而是按采样负载分级。
- `build/`、`app/build/`、`*.log` 和模块 zip 属于生成产物，不应提交到仓库。
- Rust `target/`、本机 IDE 配置、`design/` 内的草稿与测试截图也不提交。
- `modules_update/` 在本地和更新分支单独维护，不随 GitHub `master` 源码提交。已有跟踪文件仍需在暂存时排除，不能仅依赖 `.gitignore`。
- 主分支保留源码、测试、构建工具及必要资源；APK、模块 ZIP 和编译工具 EXE 由构建生成。

## 文档

- [历史开发说明（更名前）](AppOpt改版特色.md)
- [FPS_EBPF_INTEGRATION.md](FPS_EBPF_INTEGRATION.md)
- [native_daemon/fps_monitor/README.md](native_daemon/fps_monitor/README.md)

## 致谢

柒夏线程由一只小柒夏维护。项目来源与第三方许可说明见 [SOURCE_ATTRIBUTION.md](SOURCE_ATTRIBUTION.md)。
