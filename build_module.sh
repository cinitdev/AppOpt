#!/usr/bin/env bash
# 从本地源码构建 AppOpt Magisk 模块。
# eBPF 用户态加载和 attach 由 appopt_ebpf_bridge (Rust/aya) 提供。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/AppOpt.c"
FPS_MON="$ROOT/fps_monitor"
RUST_BRIDGE="$FPS_MON/appopt_ebpf_bridge"
BASE_DIR="$ROOT/magisk_module"
WORK="$ROOT/build/module"
ZIP="$ROOT/build/AppOpt-增强版.zip"

[ -d "$BASE_DIR" ] || { echo "! 找不到模块基底目录: $BASE_DIR"; exit 1; }
[ -f "$SRC" ] || { echo "! 找不到主源码: $SRC"; exit 1; }
[ -d "$FPS_MON" ] || { echo "! 找不到 fps_monitor 目录: $FPS_MON"; exit 1; }
[ -d "$RUST_BRIDGE" ] || { echo "! 找不到 Rust bridge: $RUST_BRIDGE"; exit 1; }
[ -f "$FPS_MON/ebpf_fps.c" ] || { echo "! 找不到 Rust C 适配层: $FPS_MON/ebpf_fps.c"; exit 1; }

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
sdk = sdk.replace("\\:", ":").replace("\\\\", "\\").replace("\\", "/")
print(sdk)
PY
}

SDK_DIR="$(resolve_sdk_dir)"
[ -n "$SDK_DIR" ] || SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK_DIR" ] || { echo "! 无法确定 Android SDK 目录"; exit 1; }

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    NDK=$(ls -d "$SDK_DIR/ndk"/*/ 2>/dev/null | sort -V | tail -n1)
    NDK="${NDK%/}"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "! 找不到 Android NDK"; exit 1; }

echo "- 使用 SDK: $SDK_DIR"
echo "- 使用 NDK: $NDK"

TC="$NDK/toolchains/llvm/prebuilt"
HOST=$(ls "$TC" 2>/dev/null | head -n1)
BIN="$TC/$HOST/bin"
API=24
EXT=""
case "$HOST" in windows*) EXT=".cmd" ;; esac

path_for_cargo() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s\n' "$1"
    fi
}

RUST_NO_LOCATION_FLAGS="-Zlocation-detail=none -C debuginfo=0"

build_rust_bridge() {
    local rust_target="$1" cc="$2"
    RUST_BRIDGE_LIB=""

    command -v cargo >/dev/null 2>&1 || { echo "! 找不到 cargo"; exit 1; }
    rustup target list --installed 2>/dev/null | grep -qx "$rust_target" || {
        echo "! 未安装 Rust target: $rust_target"
        exit 1
    }

    local ar="$BIN/llvm-ar${EXT}"
    [ -f "$cc" ] || { echo "! 找不到 NDK clang: $cc"; exit 1; }
    [ -f "$ar" ] || ar=""

    echo "- 构建 Rust/aya bridge: $rust_target"
    local target_dir="$ROOT/build/rust-target"
    local target_env cargo_cc cargo_ar
    target_env="$(printf '%s' "$rust_target" | tr '[:lower:]-' '[:upper:]_')"
    cargo_cc="$(path_for_cargo "$cc")"
    [ -n "$ar" ] && cargo_ar="$(path_for_cargo "$ar")" || cargo_ar=""

    env \
        "CARGO_TARGET_${target_env}_LINKER=$cargo_cc" \
        "CARGO_TARGET_${target_env}_AR=$cargo_ar" \
        "RUSTC_BOOTSTRAP=1" \
        "RUSTFLAGS=${RUSTFLAGS:-} $RUST_NO_LOCATION_FLAGS" \
        cargo build --manifest-path "$RUST_BRIDGE/Cargo.toml" \
            --release --target "$rust_target" --target-dir "$target_dir"

    local lib="$target_dir/$rust_target/release/libappopt_ebpf_bridge.a"
    [ -f "$lib" ] || { echo "! 找不到 Rust bridge 产物: $lib"; exit 1; }
    RUST_BRIDGE_LIB="$lib"
}

echo "- 准备模块工作目录: $WORK"
rm -rf "$WORK"
mkdir -p "$WORK"
cp -r "$BASE_DIR/." "$WORK/"

BPF_SRC="$FPS_MON/bpf/queuebuffer_probe.bpf.c"
BPF_OBJ="$WORK/queuebuffer_probe.bpf.o"
[ -f "$BPF_SRC" ] || { echo "! 找不到 BPF 源码: $BPF_SRC"; exit 1; }

CLANG="$BIN/clang"
[ ! -f "$CLANG" ] && CLANG="$BIN/clang.exe"
[ ! -f "$CLANG" ] && CLANG="$BIN/clang.cmd"
[ -f "$CLANG" ] || { echo "! 找不到 clang"; exit 1; }

LLVM_STRIP="$BIN/llvm-strip"
[ ! -f "$LLVM_STRIP" ] && LLVM_STRIP="$BIN/llvm-strip.exe"
[ ! -f "$LLVM_STRIP" ] && LLVM_STRIP="$BIN/llvm-strip.cmd"

echo "- 构建 BPF 对象: queuebuffer_probe.bpf.c"
SYSROOT="$TC/$HOST/sysroot"
(
    cd "$(dirname "$BPF_SRC")"
    "$CLANG" -target bpf -g -O2 -c "$(basename "$BPF_SRC")" -o "$BPF_OBJ" \
        -fdebug-compilation-dir=. \
        -ffile-prefix-map="$ROOT=." \
        -I"$SYSROOT/usr/include" \
        -I"$SYSROOT/usr/include/aarch64-linux-android" \
        -D__TARGET_ARCH_arm64 \
        -Wno-unused-value
)
[ -s "$BPF_OBJ" ] || { echo "! BPF 对象构建失败"; exit 1; }

build_abi() {
    local triple="$1" abidir="$2" rust_target="$3"
    local cc="$BIN/${triple}${API}-clang${EXT}"
    local dst="$WORK/bin/$abidir/AppOpt"

    [ -f "$cc" ] || { echo "! 找不到 $abidir 编译器: $cc"; exit 1; }
    mkdir -p "$WORK/bin/$abidir"

    build_rust_bridge "$rust_target" "$cc"

    echo "- 构建 $abidir (AppOpt + Rust/aya eBPF bridge)"
    "$cc" -Wall -Wextra -O2 -pthread \
        -I"$FPS_MON" \
        "$SRC" \
        "$FPS_MON/ebpf_fps.c" \
        "$FPS_MON/fps_fallback.c" \
        "$RUST_BRIDGE_LIB" \
        -ldl -llog \
        -o "$dst"

    [ -f "$LLVM_STRIP" ] && "$LLVM_STRIP" --strip-all "$dst" || true
}

build_abi aarch64-linux-android    arm64-v8a     aarch64-linux-android
build_abi armv7a-linux-androideabi armeabi-v7a   armv7-linux-androideabi
build_abi x86_64-linux-android     x86_64        x86_64-linux-android
build_abi i686-linux-android       x86           i686-linux-android

VER=$(grep -E '#define[[:space:]]+VERSION' "$SRC" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
if [ -n "$VER" ] && [ -f "$WORK/module.prop" ]; then
    sed -i -E "s/^version=.*/version=${VER}-增强版/" "$WORK/module.prop"
    echo "- module.prop 版本: ${VER}-增强版"
fi

rm -f "$ZIP"
python "$ROOT/scripts/ziptool.py" pack "$WORK" "$ZIP"
echo "- 完成: $ZIP"
