#!/usr/bin/env bash
# 从本地源码构建 AppOpt Magisk 模块。
# eBPF 用户态加载和 attach 由 appopt_ebpf_bridge (Rust/aya) 提供。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/AppOpt.c"
FPS_MON="$ROOT/fps_monitor"
RUST_BRIDGE="$FPS_MON/appopt_ebpf_bridge"
AYA_SUBMODULE="$FPS_MON/aya"
BASE_DIR="$ROOT/magisk_module"
WORK="$ROOT/build/module"
APP_NAME="AppOpt 线程优化"

usage() {
    cat <<EOF
用法: ./build_module.sh [release|debug|no|publish] [--publish]

  release  编译 release APK 并打包进模块（默认）
  debug    编译 debug APK 并打包进模块
  no       只编译模块，不打包 App
  publish  编译 release 模块，并发布 AppOpt.zip + changelog.md 到 GitHub Release

选项:
  --publish  构建完成后发布 release 产物
EOF
}

APP_VARIANT="${1:-release}"
PUBLISH_GITHUB_RELEASE=0
if [ "$#" -gt 0 ]; then
    shift
fi

case "$APP_VARIANT" in
    release) APP_VARIANT="release" ;;
    debug) APP_VARIANT="debug" ;;
    no|module) APP_VARIANT="" ;;
    publish|release-publish) APP_VARIANT="release"; PUBLISH_GITHUB_RELEASE=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "! Unknown command: $APP_VARIANT"; usage; exit 1 ;;
esac

for ARG in "$@"; do
    case "$ARG" in
        --publish|publish) PUBLISH_GITHUB_RELEASE=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "! Unknown option: $ARG"; usage; exit 1 ;;
    esac
done

if [ "$PUBLISH_GITHUB_RELEASE" = "1" ] && [ "$APP_VARIANT" != "release" ]; then
    echo "! GitHub Release 发布只支持 release 模块构建"
    exit 1
fi

ZIP="$ROOT/build/AppOpt.zip"

[ -d "$BASE_DIR" ] || { echo "! 找不到模块基底目录: $BASE_DIR"; exit 1; }
[ -f "$SRC" ] || { echo "! 找不到主源码: $SRC"; exit 1; }
[ -d "$FPS_MON" ] || { echo "! 找不到 fps_monitor 目录: $FPS_MON"; exit 1; }
[ -d "$RUST_BRIDGE" ] || { echo "! 找不到 Rust bridge: $RUST_BRIDGE"; exit 1; }
[ -f "$FPS_MON/ebpf_fps.c" ] || { echo "! 找不到 Rust C 适配层: $FPS_MON/ebpf_fps.c"; exit 1; }

ensure_aya_submodule() {
    [ -f "$ROOT/.gitmodules" ] || return 0
    grep -q "path = fps_monitor/aya" "$ROOT/.gitmodules" || return 0

    command -v git >/dev/null 2>&1 || {
        echo "! 找不到 git，无法初始化子模块: fps_monitor/aya"
        exit 1
    }

    echo "- 检查子模块: fps_monitor/aya"
    if [ "${APPOPT_SKIP_SUBMODULE_UPDATE:-0}" = "1" ]; then
        echo "- 跳过子模块指针重置，使用当前 fps_monitor/aya 工作区"
    else
        (
            cd "$ROOT"
            git submodule update --init --recursive fps_monitor/aya
        )
    fi

    (
        cd "$AYA_SUBMODULE"
        git sparse-checkout init --no-cone >/dev/null
        MSYS_NO_PATHCONV=1 git sparse-checkout set \
            "/*" \
            "!/.github/" \
            "!/.github/**" \
            "!/scripts/" \
            "!/scripts/**" >/dev/null
    )

    [ -f "$AYA_SUBMODULE/aya/Cargo.toml" ] || {
        echo "! 子模块不完整，缺少: $AYA_SUBMODULE/aya/Cargo.toml"
        exit 1
    }
    [ -f "$AYA_SUBMODULE/aya-obj/Cargo.toml" ] || {
        echo "! 子模块不完整，缺少: $AYA_SUBMODULE/aya-obj/Cargo.toml"
        exit 1
    }
}

ensure_aya_submodule

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

latest_dir() {
    ls -d "$1"/*/ 2>/dev/null | sort -V | tail -n1 | sed 's:/*$::'
}

run_gradle_task() {
    local task="$1"
    if [ -x "$ROOT/gradlew" ]; then
        (cd "$ROOT" && ./gradlew --no-daemon "-Dkotlin.incremental=false" "$task")
    elif [ -f "$ROOT/gradlew.bat" ]; then
        (cd "$ROOT" && ./gradlew.bat --no-daemon "-Dkotlin.incremental=false" "$task")
    else
        echo "! Gradle Wrapper not found"
        exit 1
    fi
}

build_pkg_helper() {
    local android_platform android_jar build_tools d8_jar helper_src helper_build helper_classes helper_dex tools_dir
    android_platform="$(latest_dir "$SDK_DIR/platforms")"
    android_jar="$android_platform/android.jar"
    build_tools="$(latest_dir "$SDK_DIR/build-tools")"
    d8_jar="$build_tools/lib/d8.jar"
    helper_src="$ROOT/tools/appopt_pkg_helper/src"
    helper_build="$ROOT/build/pkg-helper"
    helper_classes="$helper_build/classes"
    helper_dex="$helper_build/dex"
    tools_dir="$WORK/config/app/tools"

    [ -f "$android_jar" ] || { echo "! android.jar not found: $android_jar"; exit 1; }
    [ -f "$d8_jar" ] || { echo "! d8.jar not found: $d8_jar"; exit 1; }
    command -v javac >/dev/null 2>&1 || { echo "! javac not found"; exit 1; }
    command -v jar >/dev/null 2>&1 || { echo "! jar not found"; exit 1; }

    rm -rf "$helper_build"
    mkdir -p "$helper_classes" "$helper_dex" "$tools_dir"

    echo "- Build package helper dex jar"
    javac -encoding UTF-8 --release 11 \
        -classpath "$android_jar" \
        -d "$helper_classes" \
        $(find "$helper_src" -name '*.java' | sort)

    java -cp "$d8_jar" com.android.tools.r8.D8 \
        --release --min-api 31 \
        --output "$helper_dex" \
        $(find "$helper_classes" -name '*.class' | sort)

    (cd "$helper_dex" && jar cf "$tools_dir/appopt_pkg_helper.jar" classes.dex)
    [ -s "$tools_dir/appopt_pkg_helper.jar" ] || { echo "! package helper jar build failed"; exit 1; }
}

read_app_version_code() {
    grep -E 'versionCode[[:space:]]*=' "$ROOT/app/build.gradle.kts" | head -n1 | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/'
}

read_app_version_name() {
    grep -E 'versionName[[:space:]]*=' "$ROOT/app/build.gradle.kts" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/'
}

read_app_package() {
    grep -E 'applicationId[[:space:]]*=' "$ROOT/app/build.gradle.kts" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/'
}

read_module_version_name() {
    sed -n 's/^version=//p' "$BASE_DIR/module.prop" | head -n1 | tr -d '\r'
}

read_release_tag() {
    local version
    version="$(read_module_version_name)"
    [ -n "$version" ] || version="$(read_app_version_name)"
    [ -n "$version" ] || { echo "! Cannot read release version"; exit 1; }
    case "$version" in
        v*) printf '%s\n' "$version" ;;
        *) printf 'v%s\n' "$version" ;;
    esac
}

find_github_cli() {
    if command -v gh >/dev/null 2>&1; then
        command -v gh
        return 0
    fi
    local candidate
    if command -v cygpath >/dev/null 2>&1; then
        candidate="$(cygpath -u 'C:\Program Files\GitHub CLI\gh.exe' 2>/dev/null || true)"
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    for candidate in \
        "/c/Program Files/GitHub CLI/gh.exe" \
        "/mnt/c/Program Files/GitHub CLI/gh.exe"
    do
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

publish_github_release() {
    local gh_bin tag title changelog
    gh_bin="$(find_github_cli)" || {
        echo "! 找不到 GitHub CLI: gh"
        echo "! 请确认已安装 GitHub CLI 并加入 PATH"
        exit 1
    }

    tag="$(read_release_tag)"
    title="AppOpt $tag"
    changelog="$ROOT/modules_update/changelog.md"

    [ -s "$ZIP" ] || { echo "! 找不到模块 zip: $ZIP"; exit 1; }
    [ -s "$changelog" ] || { echo "! 找不到更新日志: $changelog"; exit 1; }

    "$gh_bin" auth status >/dev/null 2>&1 || {
        echo "! GitHub CLI 尚未登录，请先执行: gh auth login"
        exit 1
    }

    if "$gh_bin" release view "$tag" >/dev/null 2>&1; then
        echo "- GitHub Release 已存在: $tag"
        echo "- 更新 Release 说明并覆盖上传资产"
        "$gh_bin" release edit "$tag" --title "$title" --notes-file "$changelog"
        "$gh_bin" release upload "$tag" "$ZIP" "$changelog" --clobber
    else
        echo "- 创建 GitHub Release: $tag"
        "$gh_bin" release create "$tag" "$ZIP" "$changelog" \
            --title "$title" \
            --notes-file "$changelog"
    fi

    echo "- 发布完成: $tag"
}

build_and_embed_app() {
    [ -n "$APP_VARIANT" ] || return 0

    local task apk app_dir app_package version_code version_name
    case "$APP_VARIANT" in
        debug) task="assembleDebug"; apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk" ;;
        release) task="assembleRelease"; apk="$ROOT/app/build/outputs/apk/release/app-release.apk" ;;
    esac

    echo "- Build Android App: $task"
    run_gradle_task "$task"
    [ -f "$apk" ] || { echo "! APK not found: $apk"; exit 1; }

    app_dir="$WORK/config/app"
    mkdir -p "$app_dir"
    cp -f "$apk" "$app_dir/AppOpt.apk"

    app_package="$(read_app_package)"
    version_code="$(read_app_version_code)"
    version_name="$(read_app_version_name)"
    [ -n "$app_package" ] || { echo "! Cannot read App applicationId"; exit 1; }
    [ -n "$version_code" ] || { echo "! Cannot read App versionCode"; exit 1; }
    [ -n "$version_name" ] || { echo "! Cannot read App versionName"; exit 1; }

    cat > "$app_dir/app.prop" <<EOF
package=$app_package
name=$APP_NAME
variant=$APP_VARIANT
versionCode=$version_code
versionName=$version_name
apk=AppOpt.apk
EOF
    echo "- Embedded App: $APP_VARIANT $version_name ($version_code)"
}

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
build_pkg_helper
build_and_embed_app

BPF_SRC="$FPS_MON/bpf/queuebuffer_probe.bpf.c"
BPF_PERF_SRC="$FPS_MON/bpf/queuebuffer_probe_perf.bpf.c"
mkdir -p "$WORK/config/ebpf"
BPF_OBJ="$WORK/config/ebpf/queuebuffer_probe.bpf.o"
BPF_PERF_OBJ="$WORK/config/ebpf/queuebuffer_probe_perf.bpf.o"
[ -f "$BPF_SRC" ] || { echo "! 找不到 BPF 源码: $BPF_SRC"; exit 1; }
[ -f "$BPF_PERF_SRC" ] || { echo "! 找不到 PerfEvent BPF 源码: $BPF_PERF_SRC"; exit 1; }

CLANG="$BIN/clang"
[ ! -f "$CLANG" ] && CLANG="$BIN/clang.exe"
[ ! -f "$CLANG" ] && CLANG="$BIN/clang.cmd"
[ -f "$CLANG" ] || { echo "! 找不到 clang"; exit 1; }

LLVM_STRIP="$BIN/llvm-strip"
[ ! -f "$LLVM_STRIP" ] && LLVM_STRIP="$BIN/llvm-strip.exe"
[ ! -f "$LLVM_STRIP" ] && LLVM_STRIP="$BIN/llvm-strip.cmd"

SYSROOT="$TC/$HOST/sysroot"

build_bpf_obj() {
    local src="$1" obj="$2" label="$3"
    echo "- 构建 BPF 对象: $label"
    (
        cd "$(dirname "$src")"
        "$CLANG" -target bpf -g -O2 -c "$(basename "$src")" -o "$obj" \
            -fdebug-compilation-dir=. \
            -ffile-prefix-map="$ROOT=." \
            -I"$SYSROOT/usr/include" \
            -I"$SYSROOT/usr/include/aarch64-linux-android" \
            -D__TARGET_ARCH_arm64 \
            -Wno-unused-value
    )
    [ -s "$obj" ] || { echo "! BPF 对象构建失败: $label"; exit 1; }
}

build_bpf_obj "$BPF_SRC" "$BPF_OBJ" "queuebuffer_probe.bpf.c"
build_bpf_obj "$BPF_PERF_SRC" "$BPF_PERF_OBJ" "queuebuffer_probe_perf.bpf.c"

build_abi() {
    local triple="$1" abidir="$2" rust_target="$3"
    local cc="$BIN/${triple}${API}-clang${EXT}"
    local dst="$WORK/config/bin/$abidir/AppOpt"

    [ -f "$cc" ] || { echo "! 找不到 $abidir 编译器: $cc"; exit 1; }
    mkdir -p "$WORK/config/bin/$abidir"

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
    #sed -i -E "s/^version=.*/version=${VER}-增强版/" "$WORK/module.prop"
    echo "- module.prop 版本: ${VER}"
fi

rm -f "$ZIP"
python "$ROOT/scripts/ziptool.py" pack "$WORK" "$ZIP"
echo "- 完成: $ZIP"

if [ "$PUBLISH_GITHUB_RELEASE" = "1" ]; then
    publish_github_release
fi
