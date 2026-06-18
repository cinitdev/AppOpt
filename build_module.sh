#!/usr/bin/env bash
# build_module.sh —— 以仓库内 magisk_module/ 目录为基底重打包模块:
# 把改版源码 (AppOpt.c) 编译进 bin/<abi>/AppOpt, 复用 magisk_module/ 里的
# service.sh / customize.sh / META-INF / module.prop / applist.conf。
#
# 用法: bash build_module.sh
#
# 依赖: Android NDK (从 local.properties 的 sdk.dir 推断)。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/AppOpt.c"
FPS_MON="$ROOT/fps_monitor"
BASE_DIR="$ROOT/magisk_module"
WORK="$ROOT/build/module"
ZIP="$ROOT/build/AppOpt-增强版.zip"

[ -d "$BASE_DIR" ] || { echo "! 找不到模块基底目录: $BASE_DIR"; exit 1; }
[ -f "$SRC" ]      || { echo "! 找不到源码: $SRC"; exit 1; }
[ -d "$FPS_MON" ]  || { echo "! 找不到 fps_monitor 模块: $FPS_MON"; exit 1; }

# --- 定位 NDK (自动从 local.properties 的 sdk.dir 推断, 无需手工配置环境) ---
# 用 Python 解析 Java .properties 转义 (\\ -> \, \: -> :) 并统一为正斜杠路径,
# 避免 Windows 路径里的反斜杠/盘符冒号被 sed 误处理。
resolve_sdk_dir() {
    python - "$ROOT/local.properties" <<'PY'
import sys
sdk = ""
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("sdk.dir="):
                sdk = s[len("sdk.dir="):]
                break
except OSError:
    pass
# 还原 Java properties 转义后统一为正斜杠
sdk = sdk.replace("\\:", ":").replace("\\\\", "\\").replace("\\", "/")
print(sdk)
PY
}

SDK_DIR="$(resolve_sdk_dir)"
# 回退: 环境变量
[ -n "$SDK_DIR" ] || SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK_DIR" ] || { echo "! 无法确定 SDK 目录: 请在 local.properties 写 sdk.dir 或设置 ANDROID_HOME"; exit 1; }

# 优先用环境变量指定的 NDK, 否则取 SDK 下 ndk/ 内版本号最高的
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    NDK=$(ls -d "$SDK_DIR/ndk"/*/ 2>/dev/null | sort -V | tail -n1)
    NDK="${NDK%/}"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "! 在 $SDK_DIR/ndk 下未找到 NDK, 也未设置 ANDROID_NDK_HOME"; exit 1; }
echo "- 使用 SDK: $SDK_DIR"
echo "- 使用 NDK: $NDK"

TC="$NDK/toolchains/llvm/prebuilt"
HOST=$(ls "$TC" 2>/dev/null | head -n1)
BIN="$TC/$HOST/bin"
API=24
EXT=""; case "$HOST" in windows*) EXT=".cmd" ;; esac

# --- 以仓库 magisk_module/ 目录作为基底 ---
echo "- 以模块目录为基底: $BASE_DIR"
rm -rf "$WORK"; mkdir -p "$WORK"
cp -r "$BASE_DIR/." "$WORK/"

# --- 编译 eBPF 程序 (fps_monitor/bpf/queuebuffer_probe.bpf.c) ---
BPF_SRC="$FPS_MON/bpf/queuebuffer_probe.bpf.c"
BPF_OBJ="$WORK/queuebuffer_probe.bpf.o"
if [ -f "$BPF_SRC" ]; then
    echo "- 编译 eBPF 程序: queuebuffer_probe.bpf.c"
    CLANG="$BIN/clang"
    [ ! -f "$CLANG" ] && CLANG="$BIN/clang.exe"
    [ ! -f "$CLANG" ] && CLANG="$BIN/clang.cmd"

    if [ -f "$CLANG" ]; then
        # 添加 NDK sysroot 头文件路径
        SYSROOT="$TC/$HOST/sysroot"
        "$CLANG" -target bpf -O2 -c "$BPF_SRC" -o "$BPF_OBJ" \
            -I"$SYSROOT/usr/include" \
            -I"$SYSROOT/usr/include/aarch64-linux-android" \
            -D__TARGET_ARCH_arm64 \
            -Wno-unused-value \
            2>&1 | head -10
        if [ -f "$BPF_OBJ" ] && [ -s "$BPF_OBJ" ]; then
            echo "  eBPF 编译成功: $(ls -lh "$BPF_OBJ" 2>/dev/null | awk '{print $5}')"
        else
            echo "  警告: eBPF 编译失败, AppOpt 将只能用 timestats 回退"
            rm -f "$BPF_OBJ"
        fi
    else
        echo "  警告: 找不到 clang, 跳过 eBPF 编译"
    fi
else
    echo "  跳过 eBPF 编译(未找到 $BPF_SRC)"
fi

# --- 编译改版源码 + fps_monitor 模块, 覆盖原版二进制 ---
build_abi() {
    local triple="$1" abidir="$2"
    local cc="$BIN/${triple}${API}-clang${EXT}"
    local dst="$WORK/bin/$abidir/AppOpt"
    if [ ! -f "$cc" ]; then echo "  跳过 $abidir (无工具链)"; return 0; fi
    [ -d "$WORK/bin/$abidir" ] || mkdir -p "$WORK/bin/$abidir"
    echo "- 编译 $abidir (AppOpt + fps_monitor)"
    # 链接 fps_monitor 模块: ebpf_fps.c + fps_fallback.c
    "$cc" -Wall -Wextra -O2 -pthread \
        -I"$FPS_MON" \
        "$SRC" \
        "$FPS_MON/ebpf_fps.c" \
        "$FPS_MON/fps_fallback.c" \
        -o "$dst"
}

build_abi aarch64-linux-android    arm64-v8a
build_abi armv7a-linux-androideabi armeabi-v7a
build_abi x86_64-linux-android     x86_64
build_abi i686-linux-android       x86

# --- 同步 module.prop 版本号为源码内 VERSION (可选, 便于区分改版) ---
VER=$(grep -E '#define[[:space:]]+VERSION' "$SRC" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
if [ -n "$VER" ] && [ -f "$WORK/module.prop" ]; then
    sed -i -E "s/^version=.*/version=${VER}-增强版/" "$WORK/module.prop"
    echo "- module.prop 版本标记为 ${VER}-增强版"
fi

# --- 重新打包 (保持原版目录结构, zip 内无顶层目录) ---
rm -f "$ZIP"
python "$ROOT/scripts/ziptool.py" pack "$WORK" "$ZIP"
echo "- 完成: $ZIP"
