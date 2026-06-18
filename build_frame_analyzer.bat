@echo off
REM 编译 frame-analyzer-wrapper 到 Android (Windows 版本 - 自动查找 NDK)

echo === 编译 frame-analyzer-wrapper for Android ===
echo.

cd /d "%~dp0frame-analyzer-wrapper"

REM 检查 Rust 工具链
where cargo >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未安装 Rust 工具链
    echo 请访问 https://rustup.rs 安装
    pause
    exit /b 1
)

REM 检查 Android target
rustup target list | findstr /C:"aarch64-linux-android (installed)" >nul
if %errorlevel% neq 0 (
    echo 安装 Android target...
    rustup target add aarch64-linux-android
)

REM 自动查找 NDK
if "%ANDROID_NDK_HOME%"=="" (
    echo 未设置 ANDROID_NDK_HOME，尝试自动查找...
    echo.

    REM 常见 NDK 位置
    set "SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
    if exist "%SDK_ROOT%\ndk" (
        REM 查找最新版本的 NDK
        for /f "delims=" %%i in ('dir /b /ad /o-n "%SDK_ROOT%\ndk" 2^>nul') do (
            set "ANDROID_NDK_HOME=%SDK_ROOT%\ndk\%%i"
            goto :ndk_found
        )
    )

    REM 检查 Android Studio 默认位置
    set "SDK_ROOT=%USERPROFILE%\AppData\Local\Android\Sdk"
    if exist "%SDK_ROOT%\ndk" (
        for /f "delims=" %%i in ('dir /b /ad /o-n "%SDK_ROOT%\ndk" 2^>nul') do (
            set "ANDROID_NDK_HOME=%SDK_ROOT%\ndk\%%i"
            goto :ndk_found
        )
    )

    echo 错误: 未找到 Android NDK
    echo.
    echo 请：
    echo 1. 安装 Android Studio 并下载 NDK
    echo 2. 或手动设置环境变量：
    echo    set ANDROID_NDK_HOME=C:\path\to\ndk\28.x.xxxxx
    echo.
    pause
    exit /b 1
)

:ndk_found
echo 使用 NDK: %ANDROID_NDK_HOME%
echo.

REM 检查 NDK 工具链是否存在
set "TOOLCHAIN=%ANDROID_NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64"
if not exist "%TOOLCHAIN%\bin\aarch64-linux-android30-clang.cmd" (
    echo 错误: NDK 工具链不完整
    echo 路径: %TOOLCHAIN%
    echo.
    echo 请重新安装 NDK 或检查路径
    pause
    exit /b 1
)

REM 配置 Cargo 交叉编译
if not exist ".cargo" mkdir .cargo

(
echo [target.aarch64-linux-android]
echo linker = "%TOOLCHAIN%\bin\aarch64-linux-android30-clang.cmd"
echo ar = "%TOOLCHAIN%\bin\llvm-ar.exe"
) > .cargo\config.toml

echo 开始编译 frame-analyzer-wrapper...
echo.
cargo build --target aarch64-linux-android --release

if %errorlevel% neq 0 (
    echo.
    echo ❌ 编译失败！
    echo.
    echo 常见问题：
    echo 1. 网络问题导致依赖下载失败 - 尝试配置 Cargo 镜像
    echo 2. NDK 版本不兼容 - 尝试使用 NDK r25c 或更新版本
    echo 3. Rust 工具链版本过旧 - 运行 rustup update
    echo.
    pause
    exit /b 1
)

REM 复制到 magisk_module/bin
if not exist "..\magisk_module\bin" mkdir "..\magisk_module\bin"

REM 找到编译产物（可能有或没有 .exe 后缀）
set "OUTPUT_FILE=target\aarch64-linux-android\release\frame-analyzer-wrapper"
if exist "%OUTPUT_FILE%" (
    copy /y "%OUTPUT_FILE%" "..\magisk_module\bin\frame-analyzer" >nul
) else if exist "%OUTPUT_FILE%.exe" (
    copy /y "%OUTPUT_FILE%.exe" "..\magisk_module\bin\frame-analyzer" >nul
) else (
    echo 错误: 找不到编译产物
    pause
    exit /b 1
)

REM Strip 减小体积
"%TOOLCHAIN%\bin\llvm-strip.exe" "..\magisk_module\bin\frame-analyzer"

echo.
echo ✅ 编译完成！
echo.
for %%F in (..\magisk_module\bin\frame-analyzer) do echo 文件大小: %%~zF 字节 (约 %%~zF/1024/1024 MB)
echo.
echo 📋 下一步：
echo.
echo 1. 替换 ebpf_fps.c
echo    cd fps_monitor
echo    ren ebpf_fps.c ebpf_fps_old.c.bak
echo    ren ebpf_fps_new.c ebpf_fps.c
echo.
echo 2. 重新编译 AppOpt 模块
echo    bash build_module.sh
echo.
echo 3. 测试
echo    查看文档: FRAME_ANALYZER_INTEGRATION.md
echo.
pause
