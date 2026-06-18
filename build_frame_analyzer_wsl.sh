#!/bin/bash
# WSL 环境下编译 frame-analyzer 到 Android aarch64
# 使用方法：在 WSL Debian 中运行此脚本

set -e

echo "=== 开始在 WSL 中编译 frame-analyzer ==="
echo ""

# 检查是否在 WSL 环境中
if ! grep -qiE "(microsoft|wsl)" /proc/version 2>/dev/null; then
    echo "错误：此脚本需要在 WSL 环境中运行"
    exit 1
fi

# 获取当前脚本所在目录（WSL 路径）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"

# 查找或下载 Android NDK（Linux 版本）
WSL_NDK_DIR="$HOME/android-ndk"
ANDROID_NDK_HOME=""

# 检查是否已有 Linux 版 NDK
if [ -d "$WSL_NDK_DIR" ]; then
    ANDROID_NDK_HOME="$WSL_NDK_DIR"
    echo "使用现有 NDK: $ANDROID_NDK_HOME"
else
    echo "未找到 Linux 版 NDK，正在下载..."
    echo ""

    # 下载 NDK r21e (LTS)
    NDK_VERSION="r21e"
    NDK_URL="https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux-x86_64.zip"
    NDK_ZIP="/tmp/android-ndk.zip"

    echo "下载 NDK ${NDK_VERSION}..."
    if ! wget -O "$NDK_ZIP" "$NDK_URL"; then
        echo "错误：下载 NDK 失败"
        echo "请手动下载并解压到: $HOME/android-ndk"
        exit 1
    fi

    echo "解压 NDK..."
    mkdir -p "$HOME"
    unzip -q "$NDK_ZIP" -d "$HOME"
    mv "$HOME/android-ndk-${NDK_VERSION}" "$WSL_NDK_DIR"
    rm "$NDK_ZIP"

    ANDROID_NDK_HOME="$WSL_NDK_DIR"
    echo "NDK 安装完成: $ANDROID_NDK_HOME"
fi

echo ""

# 检查 Rust 工具链
if ! command -v rustup &> /dev/null; then
    echo "错误：未找到 Rust 工具链"
    echo "请在 WSL 中安装 Rust: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    exit 1
fi

# 检查 aarch64-linux-android target
if ! rustup target list --installed | grep -q "aarch64-linux-android"; then
    echo "正在添加 aarch64-linux-android target..."
    rustup target add aarch64-linux-android
fi

# 配置环境变量（使用 Linux 版本的工具链）
export CC_aarch64_linux_android="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
export AR_aarch64_linux_android="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"

# 检查编译器是否存在
if [ ! -f "$CC_aarch64_linux_android" ]; then
    echo "错误：未找到 NDK 编译器"
    echo "路径: $CC_aarch64_linux_android"
    echo ""
    echo "可用的 prebuilt 目录："
    ls -la "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/" 2>/dev/null || true
    exit 1
fi

echo "编译器配置完成"
echo "CC: $CC_aarch64_linux_android"
echo "AR: $AR_aarch64_linux_android"
echo ""

# 切换到 simple-analyzer 目录
cd "$PROJECT_ROOT/frame-analyzer-ebpf/examples/simple-analyzer"

# 清理之前的构建
echo "清理之前的构建..."
cargo clean 2>/dev/null || true
echo ""

# 开始编译
echo "开始编译（这可能需要几分钟）..."
if rustup toolchain list | grep -q "1.80.0"; then
    cargo +1.80.0 build --release --target aarch64-linux-android
else
    cargo build --release --target aarch64-linux-android
fi

# 检查编译结果
BINARY_PATH="$PROJECT_ROOT/frame-analyzer-ebpf/target/aarch64-linux-android/release/simple-analyzer"
if [ ! -f "$BINARY_PATH" ]; then
    echo ""
    echo "错误：编译失败，未找到输出文件"
    exit 1
fi

echo ""
echo "编译成功！"
echo ""

# 优化二进制（strip）
echo "优化二进制大小..."
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [ -f "$STRIP" ]; then
    "$STRIP" "$BINARY_PATH"
    echo "已优化"
else
    echo "警告：未找到 strip 工具，跳过优化"
fi

# 复制到 magisk_module
OUTPUT_DIR="$PROJECT_ROOT/magisk_module/bin"
mkdir -p "$OUTPUT_DIR"
cp "$BINARY_PATH" "$OUTPUT_DIR/frame-analyzer"
chmod +x "$OUTPUT_DIR/frame-analyzer"

echo ""
echo "✓ 已复制到: $OUTPUT_DIR/frame-analyzer"
echo ""

# 显示文件信息
ls -lh "$OUTPUT_DIR/frame-analyzer"
echo ""
echo "=== 编译完成 ==="
