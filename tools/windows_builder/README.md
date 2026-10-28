# 柒夏线程 · 可视化编译工具

[下载 QixiaBuilder.exe](https://github.com/cinitdev/AppOpt/raw/refs/heads/master/tools/windows_builder/QixiaBuilder.exe) · [源码目录](https://github.com/cinitdev/AppOpt/tree/master/tools/windows_builder) · [SHA-256 校验](QixiaBuilder.sha256)

这是 `build_module.sh` 的 Windows 图形入口，支持版本修改、环境检查、模块与 APK 构建、日志查看和发布。它复用项目构建脚本，不内置 Android 或 Rust 编译环境。

## 全新电脑可以运行吗？

| 用途 | 所需环境 |
| --- | --- |
| 打开 EXE 界面 | 标准 Windows 10/11 的 .NET Framework 4.x 与 WPF；建议使用 64 位 Windows 10/11，运行库异常时安装／修复 .NET Framework 4.8 |
| 编译 Android App 和模块 | 下列开发工具、完整项目源码及子模块 |
| 重新编译 EXE 自身 | 64 位 Windows、Windows PowerShell、.NET Framework 自带 C# 编译器；不需要 Visual Studio IDE 或 .NET SDK |
| 发布到 GitHub／Gitee | 在可编译环境基础上，配置目标仓库权限；GitHub 需要已登录的 `gh`，Gitee 需要 Token |

打开界面不需要 Node、Python、Java、Rust 或 Git。**刚装好系统通常能打开界面，但不能直接编译项目。** EXE 不会自动下载或安装开发工具。精简版 Windows 或损坏的 .NET/WPF 运行库不在此保证范围内。

## 首次配置编译环境

1. 安装 [Git for Windows](https://git-scm.com/download/win)，工具使用其中的 Git Bash，不使用 WSL。
2. 安装 [Python 3](https://www.python.org/downloads/windows/)，加入 PATH，确认 `python --version` 可用。
3. 安装 JDK 17，配置 `JAVA_HOME` 与 PATH，或在工具中指定 JDK 目录；Android Studio 自带 JDK 若使用不同版本，需确认 Gradle 兼容性。
4. 用 Android Studio SDK Manager 安装 Android SDK Platform 36、Build-Tools、Command-line Tools 和 NDK。当前验证的 NDK 为 `28.1.13356709`。通过项目 `local.properties` 中的 `sdk.dir` 或环境变量 `ANDROID_HOME`／`ANDROID_SDK_ROOT` 指定 SDK。
5. 安装 [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/)，选择“使用 C++ 的桌面开发”，包含 MSVC 工具和 Windows SDK。Windows 的 Rust 构建脚本及过程宏会使用主机链接器。
6. 用 [rustup](https://rustup.rs/) 安装 Rust stable，并安装四个 Android target：

   ```powershell
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
   ```

7. 克隆包含子模块的源码：

   ```powershell
   git clone --recurse-submodules https://github.com/cinitdev/AppOpt.git
   ```

首次构建需要联网获取 Gradle、Cargo 依赖。项目自带 Gradle Wrapper，无需单独安装 Gradle；eBPF 编译使用 NDK 的 Clang，无需额外安装 WSL、CMake 或 bpf-linker。环境检查能发现常见缺项，但通过检查不等同于完整构建已通过。

## 使用

1. 打开 EXE，选择包含 `build_module.sh` 的项目根目录。
2. 点击“检查构建环境”，按日志补齐缺失工具。
3. 按需修改版本名／编号，选择正式版、调试版或仅模块，开始构建。
4. 完成后通过界面打开模块 ZIP、APK 或输出目录。完整日志在 `build/build-gui.log`，可手动导出。

源码目录必须保留 `build_module.sh`、`tools/windows_builder/check_environment.sh` 等文件；单独下载 EXE 无法编译项目。
本地构建不需要发布 Token，也不需要 `modules_update/QixiaThreads.json`。若本地有该清单，版本修改会同时更新它；发布与发布预演仍需先准备发布清单和目标平台凭据。

## 从源码生成 EXE

在项目根目录执行：

```powershell
# 只生成 build/QixiaBuilder.exe
powershell -ExecutionPolicy Bypass -File .\tools\windows_builder\build.ps1

# 运行工具自检后，更新本目录分发的 EXE 和 SHA-256
powershell -ExecutionPolicy Bypass -File .\tools\windows_builder\build.ps1 -Test -Package
```

自检包括版本同步、有／无发布清单、参数转义、日志隐藏凭据、构建锁、停止进程树和失败状态，不会发布版本或连接手机。自检需要项目源码和 Git for Windows。

若 `build/QixiaBuilder.exe` 正在运行，编译时可添加 `-OutputDirectory build/builder-package`，使用其他输出目录而不关闭现有窗口。

下载后可在 PowerShell 中计算摘要，与本目录 `QixiaBuilder.sha256` 比较：

```powershell
Get-FileHash .\QixiaBuilder.exe -Algorithm SHA256
```
