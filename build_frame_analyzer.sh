#!/bin/bash
# 编译 frame-analyzer-wrapper 到 Android

set -e

echo "=== 编译 frame-analyzer-wrapper for Android ==="

cd "$(dirname "$0")/frame-analyzer-wrapper"

# 检查 Rust 工具链
if ! command -v cargo &> /dev/null; then
    echo "错误: 未安装 Rust 工具链"
    echo "请访问 https://rustup.rs 安装"
    exit 1
fi

# 检查 Android target
if ! rustup target list | grep -q "aarch64-linux-android (installed)"; then
    echo "安装 Android target..."
    rustup target add aarch64-linux-android
fi

# 设置 NDK 路径（需要用户自行配置）
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "警告: 未设置 ANDROID_NDK_HOME 环境变量"
    echo "请设置 NDK 路径，例如："
    echo "export ANDROID_NDK_HOME=/path/to/android-ndk-r25c"
    exit 1
fi

# 配置交叉编译工具链
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang"
export AR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"

echo "使用 NDK: $ANDROID_NDK_HOME"

# 编译
echo "开始编译..."
cargo build --target aarch64-linux-android --release

# 复制到 magisk_module/bin
mkdir -p ../magisk_module/bin
cp target/aarch64-linux-android/release/frame-analyzer-wrapper \
   ../magisk_module/bin/frame-analyzer

# Strip 减小体积
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
    ../magisk_module/bin/frame-analyzer

echo ""
echo "✅ 编译完成！"
ls -lh ../magisk_module/bin/frame-analyzer
echo ""
echo "下一步："
echo "1. 替换 fps_monitor/ebpf_fps.c 为 ebpf_fps_new.c"
echo "2. 重新编译 AppOpt 模块"
echo "3. 测试 eBPF 帧率监测功能"
