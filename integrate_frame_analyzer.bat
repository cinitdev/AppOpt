@echo off
REM 一键完成 frame-analyzer 对接

echo ========================================
echo    AppOpt frame-analyzer 对接脚本
echo ========================================
echo.

cd /d "%~dp0"

REM ===== 第 1 步：编译 frame-analyzer =====
echo [1/3] 编译 frame-analyzer-wrapper...
echo.

call build_frame_analyzer.bat
if %errorlevel% neq 0 (
    echo.
    echo ❌ 编译失败，请检查错误信息
    pause
    exit /b 1
)

echo.
echo ========================================
echo.

REM ===== 第 2 步：替换 ebpf_fps.c =====
echo [2/3] 替换 ebpf_fps.c 实现...
echo.

cd fps_monitor

if not exist ebpf_fps_new.c (
    echo 错误: 找不到 ebpf_fps_new.c
    echo 请确保文件存在
    cd ..
    pause
    exit /b 1
)

REM 备份原文件
if exist ebpf_fps.c (
    if not exist ebpf_fps_old.c.bak (
        echo 备份原文件: ebpf_fps.c -^> ebpf_fps_old.c.bak
        ren ebpf_fps.c ebpf_fps_old.c.bak
    ) else (
        echo 删除旧的 ebpf_fps.c（已有备份）
        del ebpf_fps.c
    )
)

echo 使用新实现: ebpf_fps_new.c -^> ebpf_fps.c
copy /y ebpf_fps_new.c ebpf_fps.c >nul

cd ..

echo ✅ 替换完成
echo.
echo ========================================
echo.

REM ===== 第 3 步：重新编译 AppOpt =====
echo [3/3] 重新编译 AppOpt 模块...
echo.

bash build_module.sh
if %errorlevel% neq 0 (
    echo.
    echo ❌ 编译失败，请检查 build_module.sh 输出
    pause
    exit /b 1
)

echo.
echo ========================================
echo.
echo ✅ 全部完成！
echo.
echo 📦 输出文件:
echo    - frame-analyzer: magisk_module\bin\frame-analyzer
echo    - AppOpt 模块: build\AppOpt-增强版.zip
echo.
echo 📋 下一步：
echo.
echo 1. 推送到手机测试：
echo    adb push build\AppOpt-增强版.zip /sdcard/
echo    adb shell su -c "magisk --install-module /sdcard/AppOpt-增强版.zip"
echo    adb reboot
echo.
echo 2. 查看日志：
echo    adb logcat ^| grep FPS
echo.
echo 3. 手动测试 frame-analyzer：
echo    adb shell pidof com.tencent.tmgp.sgame
echo    adb shell su -c "/data/adb/modules/AppOpt/bin/frame-analyzer [PID]"
echo.
echo 详细文档: FRAME_ANALYZER_INTEGRATION.md
echo.
pause
