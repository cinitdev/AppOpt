#!/bin/bash
# 在 WSL 中编译 eBPF 程序到 Android
# 需要 clang 和 Android NDK

set -e

echo "=== 编译 eBPF 帧率监测程序 ==="
echo ""

# 切换到脚本目录
cd "$(dirname "$0")"

# 检查 clang
if ! command -v clang &> /dev/null; then
    echo "错误：未找到 clang"
    echo "请安装: sudo apt install clang llvm"
    exit 1
fi

echo "✓ 使用 clang 版本:"
clang --version | head -1
echo ""

# 查找 Linux 版 NDK
WSL_NDK_DIR="$HOME/android-ndk"

if [ ! -d "$WSL_NDK_DIR" ]; then
    echo "错误：未找到 Android NDK"
    echo "请先运行 setup_and_build_wsl.sh 安装 NDK"
    exit 1
fi

echo "✓ 使用 NDK: $WSL_NDK_DIR"
echo ""

# eBPF 源文件
EBPF_SRC="fps_monitor/frame_analyzer.bpf.c"
EBPF_OBJ="magisk_module/system/ebpf/frame_analyzer.o"

if [ ! -f "$EBPF_SRC" ]; then
    echo "错误：未找到 $EBPF_SRC"
    exit 1
fi

# 创建输出目录
mkdir -p "$(dirname "$EBPF_OBJ")"

echo "正在编译 eBPF 程序..."
echo "输入: $EBPF_SRC"
echo "输出: $EBPF_OBJ"
echo ""

# 编译 eBPF 程序
# -target bpf: 目标为 BPF
# -O2: 优化级别
# -g: 调试信息
# -c: 只编译不链接
clang -target bpf \
    -O2 \
    -g \
    -c "$EBPF_SRC" \
    -o "$EBPF_OBJ" \
    -I"$WSL_NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include" \
    -D__TARGET_ARCH_arm64

if [ ! -f "$EBPF_OBJ" ]; then
    echo ""
    echo "错误：编译失败"
    exit 1
fi

echo ""
echo "✓ 编译成功！"
echo ""
ls -lh "$EBPF_OBJ"
echo ""

# 验证 BPF 对象
echo "验证 BPF 对象..."
file "$EBPF_OBJ"
echo ""

echo "=== 完成 ==="
echo ""
echo "下一步："
echo "1. 更新 AppOpt 的 Makefile 添加 libbpf 依赖"
echo "2. 修改 ebpf_fps.c 使用 ebpf_fps_libbpf.c"
echo "3. 重新编译 AppOpt 模块"
