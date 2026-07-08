#!/usr/bin/env bash
# 从本地源码构建 AppOpt Magisk 模块。
# eBPF 用户态加载和 attach 由 appopt_ebpf_bridge (Rust/aya) 提供。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$ROOT/native_daemon"
SRC="$NATIVE_DIR/AppOpt.c"
FOREGROUND_MONITOR_SRC="$NATIVE_DIR/foreground_monitor.c"
FPS_MON="$NATIVE_DIR/fps_monitor"
RUST_BRIDGE="$FPS_MON/appopt_ebpf_bridge"
RUST_DAEMON="$NATIVE_DIR/daemon_rs"
RUST_DAEMON_MAIN="$RUST_DAEMON/src/main.rs"
RUST_DAEMON_VERSION_SRC="$RUST_DAEMON/src/daemon_core/preamble.rs"
FOREGROUND_HELPER="$ROOT/tools/appopt_foreground_helper"
AYA_SUBMODULE="$FPS_MON/aya"
AYA_SUBMODULE_REL="native_daemon/fps_monitor/aya"
BASE_DIR="$ROOT/magisk_module"
MODULE_PROP="$BASE_DIR/module.prop"
WORK="$ROOT/build/module"
APP_NAME="AppOpt 线程优化"
APP_GRADLE="$ROOT/app/build.gradle.kts"
DAEMON_BRIDGE="$ROOT/app/src/main/java/top/suto/appopt/DaemonBridge.kt"
UPDATE_JSON="$ROOT/modules_update/AppOpt.json"
UPDATE_BRANCH="modules-update"

usage() {
    cat <<EOF
用法: ./build_module.sh [release|debug|no|publish] [--publish] [--dry-run]

  release  编译 release APK 并打包进模块（默认）
  debug    编译 debug APK 并打包进模块
  no       只编译模块，不打包 App
  publish  编译 release 模块，发布 GitHub Release，并更新 modules-update 分支的 AppOpt.json

选项:
  --publish  构建完成后发布 release 产物并更新 modules-update 分支
  --dry-run  完整预演发布流程，不创建 Release、不提交、不推送
EOF
}

APP_VARIANT="${1:-release}"
PUBLISH_GITHUB_RELEASE=0
PUBLISH_DRY_RUN=0
if [ "$#" -gt 0 ]; then
    shift
fi

case "$APP_VARIANT" in
    release) APP_VARIANT="release" ;;
    debug) APP_VARIANT="debug" ;;
    no|module) APP_VARIANT="" ;;
    publish|release-publish|--publish) APP_VARIANT="release"; PUBLISH_GITHUB_RELEASE=1 ;;
    --dry-run) APP_VARIANT="release"; PUBLISH_GITHUB_RELEASE=1; PUBLISH_DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "! Unknown command: $APP_VARIANT"; usage; exit 1 ;;
esac

for ARG in "$@"; do
    case "$ARG" in
        --publish|publish) PUBLISH_GITHUB_RELEASE=1 ;;
        --dry-run) PUBLISH_GITHUB_RELEASE=1; PUBLISH_DRY_RUN=1 ;;
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
[ -f "$MODULE_PROP" ] || { echo "! 找不到模块属性文件: $MODULE_PROP"; exit 1; }
[ -d "$NATIVE_DIR" ] || { echo "! 找不到 native 源码目录: $NATIVE_DIR"; exit 1; }
[ -f "$SRC" ] || { echo "! 找不到主源码: $SRC"; exit 1; }
[ -f "$FOREGROUND_MONITOR_SRC" ] || { echo "! 找不到前台检测源码: $FOREGROUND_MONITOR_SRC"; exit 1; }
[ -d "$FPS_MON" ] || { echo "! 找不到 fps_monitor 目录: $FPS_MON"; exit 1; }
[ -d "$RUST_BRIDGE" ] || { echo "! 找不到 Rust bridge: $RUST_BRIDGE"; exit 1; }
[ -f "$RUST_DAEMON/Cargo.toml" ] || { echo "! 找不到 Rust daemon: $RUST_DAEMON"; exit 1; }
[ -f "$RUST_DAEMON_MAIN" ] || { echo "! 找不到 Rust daemon 入口: $RUST_DAEMON_MAIN"; exit 1; }
[ -f "$RUST_DAEMON_VERSION_SRC" ] || { echo "! 找不到 Rust daemon 版本文件: $RUST_DAEMON_VERSION_SRC"; exit 1; }
[ -f "$FPS_MON/ebpf_fps.c" ] || { echo "! 找不到 Rust C 适配层: $FPS_MON/ebpf_fps.c"; exit 1; }

ensure_aya_submodule() {
    [ -f "$ROOT/.gitmodules" ] || return 0
    grep -q "path = $AYA_SUBMODULE_REL" "$ROOT/.gitmodules" || return 0

    command -v git >/dev/null 2>&1 || {
        echo "! 找不到 git，无法初始化子模块: $AYA_SUBMODULE_REL"
        exit 1
    }

    echo "- 检查子模块: $AYA_SUBMODULE_REL"
    if [ "${APPOPT_SKIP_SUBMODULE_UPDATE:-0}" = "1" ]; then
        echo "- 跳过子模块指针重置，使用当前 $AYA_SUBMODULE_REL 工作区"
    else
        (
            cd "$ROOT"
            git submodule update --init --recursive "$AYA_SUBMODULE_REL"
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

build_foreground_helper() {
    local android_platform android_jar build_tools d8_jar helper_src stub_src helper_build
    local helper_classes helper_dex stub_jar tools_dir
    android_platform="$(latest_dir "$SDK_DIR/platforms")"
    android_jar="$android_platform/android.jar"
    build_tools="$(latest_dir "$SDK_DIR/build-tools")"
    d8_jar="$build_tools/lib/d8.jar"
    helper_src="$FOREGROUND_HELPER/src"
    stub_src="$FOREGROUND_HELPER/stubs"
    helper_build="$ROOT/build/foreground-helper"
    helper_classes="$helper_build/classes"
    helper_dex="$helper_build/dex"
    stub_jar="$helper_build/framework-stubs.jar"
    tools_dir="$WORK/config/tools"

    [ -f "$android_jar" ] || { echo "! android.jar not found: $android_jar"; exit 1; }
    [ -f "$d8_jar" ] || { echo "! d8.jar not found: $d8_jar"; exit 1; }
    [ -d "$helper_src" ] || { echo "! foreground helper source not found: $helper_src"; exit 1; }
    [ -d "$stub_src" ] || { echo "! foreground helper stubs not found: $stub_src"; exit 1; }

    rm -rf "$helper_build"
    mkdir -p "$helper_classes" "$helper_dex" "$tools_dir"

    echo "- Build ActivityTaskManager foreground helper dex jar"
    javac -encoding UTF-8 --release 11 \
        -classpath "$android_jar" \
        -d "$helper_classes" \
        $(find "$stub_src" "$helper_src" -name '*.java' | sort)

    (cd "$helper_classes" && jar cf "$stub_jar" android/app/TaskStackListener.class)
    java -cp "$d8_jar" com.android.tools.r8.D8 \
        --release --min-api 31 \
        --lib "$android_jar" \
        --lib "$stub_jar" \
        --output "$helper_dex" \
        $(find "$helper_classes/appopt" -name '*.class' | sort)

    (cd "$helper_dex" && jar cf "$tools_dir/appopt_foreground_helper.jar" classes.dex)
    [ -s "$tools_dir/appopt_foreground_helper.jar" ] || {
        echo "! foreground helper jar build failed"
        exit 1
    }
}

read_app_version_code() {
    grep -E 'versionCode[[:space:]]*=' "$APP_GRADLE" | head -n1 | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/'
}

read_app_version_name() {
    grep -E 'versionName[[:space:]]*=' "$APP_GRADLE" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/'
}

read_app_package() {
    grep -E 'applicationId[[:space:]]*=' "$APP_GRADLE" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/'
}

sync_source_versions() {
    local version_code version_name source_version_name current_code current_name current_c_version current_rs_version
    local current_module_version current_module_code
    local synced_code synced_name synced_c_version synced_rs_version synced_module_version synced_module_code
    version_code="$(read_app_version_code)"
    version_name="$(read_app_version_name)"
    [ -n "$version_code" ] || { echo "! 无法读取 App versionCode"; exit 1; }
    [ -n "$version_name" ] || { echo "! 无法读取 App versionName"; exit 1; }

    source_version_name="${version_name#v}"
    source_version_name="${source_version_name#V}"
    current_code="$(sed -n -E 's/.*REQUIRED_MODULE_VERSION_CODE[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$DAEMON_BRIDGE" | head -n1)"
    current_name="$(sed -n -E 's/.*REQUIRED_MODULE_VERSION_NAME[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$DAEMON_BRIDGE" | head -n1)"
    current_c_version="$(sed -n -E 's/^#define[[:space:]]+VERSION[[:space:]]+"([^"]*)".*/\1/p' "$SRC" | head -n1)"
    current_rs_version="$(sed -n -E 's/^const[[:space:]]+VERSION:[[:space:]]*&str[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$RUST_DAEMON_VERSION_SRC" | head -n1)"
    current_module_version="$(sed -n 's/^version=//p' "$MODULE_PROP" | head -n1 | tr -d '\r')"
    current_module_code="$(sed -n 's/^versionCode=//p' "$MODULE_PROP" | head -n1 | tr -d '\r')"

    if [ "$current_code" != "$version_code" ]; then
        echo "- 同步 REQUIRED_MODULE_VERSION_CODE: ${current_code:-缺失} -> $version_code"
        sed -E -i "s/(REQUIRED_MODULE_VERSION_CODE[[:space:]]*=[[:space:]]*)[0-9]+/\1$version_code/" "$DAEMON_BRIDGE"
    fi
    if [ "$current_name" != "$source_version_name" ]; then
        echo "- 同步 REQUIRED_MODULE_VERSION_NAME: ${current_name:-缺失} -> $source_version_name"
        sed -E -i "s/(REQUIRED_MODULE_VERSION_NAME[[:space:]]*=[[:space:]]*)\"[^\"]*\"/\1\"$source_version_name\"/" "$DAEMON_BRIDGE"
    fi
    if [ "$current_c_version" != "$source_version_name" ]; then
        echo "- 同步 AppOpt.c VERSION: ${current_c_version:-缺失} -> $source_version_name"
        sed -E -i "s/(^#define[[:space:]]+VERSION[[:space:]]+)\"[^\"]*\"/\1\"$source_version_name\"/" "$SRC"
    fi
    if [ "$current_rs_version" != "$source_version_name" ]; then
        echo "- 同步 AppOptRs VERSION: ${current_rs_version:-缺失} -> $source_version_name"
        sed -E -i "s/(^const[[:space:]]+VERSION:[[:space:]]*\&str[[:space:]]*=[[:space:]]*)\"[^\"]*\"/\1\"$source_version_name\"/" "$RUST_DAEMON_VERSION_SRC"
    fi
    if [ "$current_module_version" != "$version_name" ]; then
        echo "- 同步 module.prop version: ${current_module_version:-缺失} -> $version_name"
        sed -E -i "s/^version=.*/version=$version_name/" "$MODULE_PROP"
    fi
    if [ "$current_module_code" != "$version_code" ]; then
        echo "- 同步 module.prop versionCode: ${current_module_code:-缺失} -> $version_code"
        sed -E -i "s/^versionCode=.*/versionCode=$version_code/" "$MODULE_PROP"
    fi

    synced_code="$(sed -n -E 's/.*REQUIRED_MODULE_VERSION_CODE[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$DAEMON_BRIDGE" | head -n1)"
    synced_name="$(sed -n -E 's/.*REQUIRED_MODULE_VERSION_NAME[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$DAEMON_BRIDGE" | head -n1)"
    synced_c_version="$(sed -n -E 's/^#define[[:space:]]+VERSION[[:space:]]+"([^"]*)".*/\1/p' "$SRC" | head -n1)"
    synced_rs_version="$(sed -n -E 's/^const[[:space:]]+VERSION:[[:space:]]*&str[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$RUST_DAEMON_VERSION_SRC" | head -n1)"
    synced_module_version="$(sed -n 's/^version=//p' "$MODULE_PROP" | head -n1 | tr -d '\r')"
    synced_module_code="$(sed -n 's/^versionCode=//p' "$MODULE_PROP" | head -n1 | tr -d '\r')"
    [ "$synced_code" = "$version_code" ] || {
        echo "! REQUIRED_MODULE_VERSION_CODE 同步失败"
        exit 1
    }
    [ "$synced_name" = "$source_version_name" ] || {
        echo "! REQUIRED_MODULE_VERSION_NAME 同步失败"
        exit 1
    }
    [ "$synced_c_version" = "$source_version_name" ] || {
        echo "! AppOpt.c VERSION 同步失败"
        exit 1
    }
    [ "$synced_rs_version" = "$source_version_name" ] || {
        echo "! AppOptRs VERSION 同步失败"
        exit 1
    }
    [ "$synced_module_version" = "$version_name" ] || {
        echo "! module.prop version 同步失败"
        exit 1
    }
    [ "$synced_module_code" = "$version_code" ] || {
        echo "! module.prop versionCode 同步失败"
        exit 1
    }
    echo "- App、模块与守护进程版本已对齐: $version_name ($version_code)"
}

read_module_version_name() {
    sed -n 's/^version=//p' "$MODULE_PROP" | head -n1 | tr -d '\r'
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

validate_update_json() {
    local tag="$1" version_code
    version_code="$(read_app_version_code)"
    [ -s "$UPDATE_JSON" ] || { echo "! 找不到远程更新配置: $UPDATE_JSON"; exit 1; }

    python - "$UPDATE_JSON" "$tag" "$version_code" <<'PY'
import json
import sys

path, expected_tag, expected_code = sys.argv[1], sys.argv[2], int(sys.argv[3])
with open(path, encoding="utf-8") as f:
    data = json.load(f)

if data.get("version") != expected_tag:
    raise SystemExit(f"! AppOpt.json version={data.get('version')!r}, 预期 {expected_tag!r}")
if data.get("versionCode") != expected_code:
    raise SystemExit(f"! AppOpt.json versionCode={data.get('versionCode')!r}, 预期 {expected_code}")

release_path = f"/releases/download/{expected_tag}/"
for key in ("zipUrl", "changelog"):
    value = data.get(key)
    if not isinstance(value, str) or release_path not in value:
        raise SystemExit(f"! AppOpt.json {key} 未指向 {expected_tag} Release")
PY
}

publish_update_json() (
    set -e
    local tag="$1" dry_run="$2" repo_root worktree remote_ref base_commit latest_commit
    repo_root="$(git -C "$ROOT" rev-parse --show-toplevel 2>/dev/null)" || {
        echo "! 当前目录不是 Git 仓库: $ROOT"
        exit 1
    }
    remote_ref="refs/remotes/origin/$UPDATE_BRANCH"

    echo "- 同步远端更新分支: origin/$UPDATE_BRANCH"
    git -C "$repo_root" fetch --no-tags origin "$UPDATE_BRANCH"
    base_commit="$(git -C "$repo_root" rev-parse "$remote_ref^{commit}")" || {
        echo "! 找不到远端分支: origin/$UPDATE_BRANCH"
        exit 1
    }

    mkdir -p "$ROOT/build"
    worktree="$(mktemp -d "$ROOT/build/modules-update-publish.XXXXXX")"
    rmdir "$worktree"
    cleanup_update_worktree() {
        git -C "$repo_root" worktree remove --force "$worktree" >/dev/null 2>&1 || true
        git -C "$repo_root" worktree prune >/dev/null 2>&1 || true
    }
    trap cleanup_update_worktree EXIT INT TERM

    git -C "$repo_root" worktree add --detach "$worktree" "$base_commit" >/dev/null
    mkdir -p "$worktree/modules_update"
    cp -f "$UPDATE_JSON" "$worktree/modules_update/AppOpt.json"
    git -C "$worktree" add -- modules_update/AppOpt.json

    if git -C "$worktree" diff --cached --quiet; then
        echo "- origin/$UPDATE_BRANCH 的 AppOpt.json 已是 $tag"
        exit 0
    fi

    if [ "$dry_run" = "1" ]; then
        echo "- [预演] 将基于远端提交 $base_commit 更新 modules_update/AppOpt.json"
        git -C "$worktree" diff --cached --stat
        git -C "$worktree" diff --cached -- modules_update/AppOpt.json
        echo "- [预演] 未提交、未推送 modules-update 分支"
        exit 0
    fi

    git -C "$worktree" -c commit.gpgsign=false commit \
        -m "发布：更新 $tag 远程更新信息" >/dev/null

    # 推送前再次抓取。网页端若在发布过程中产生新提交，拒绝非快进推送。
    git -C "$repo_root" fetch --no-tags origin "$UPDATE_BRANCH"
    latest_commit="$(git -C "$repo_root" rev-parse "$remote_ref^{commit}")"
    if [ "$latest_commit" != "$base_commit" ]; then
        echo "! origin/$UPDATE_BRANCH 在发布期间发生变化，已停止推送，请重新执行发布"
        exit 1
    fi

    git -C "$worktree" push origin "HEAD:refs/heads/$UPDATE_BRANCH"
    echo "- 已更新 origin/$UPDATE_BRANCH: modules_update/AppOpt.json -> $tag"
)

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
    validate_update_json "$tag"

    "$gh_bin" auth status >/dev/null 2>&1 || {
        echo "! GitHub CLI 尚未登录，请先执行: gh auth login"
        exit 1
    }

    if [ "$PUBLISH_DRY_RUN" = "1" ]; then
        if "$gh_bin" release view "$tag" >/dev/null 2>&1; then
            echo "- [预演] 将更新 GitHub Release: $tag"
        else
            echo "- [预演] 将创建 GitHub Release: $tag"
        fi
        echo "- [预演] 将上传: $ZIP 和 $changelog"
        publish_update_json "$tag" 1
        echo "- 发布预演完成: $tag（未写入 GitHub）"
        return 0
    fi

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

    publish_update_json "$tag" 0
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

build_rust_daemon() {
    local rust_target="$1" cc="$2" abidir="$3"

    command -v cargo >/dev/null 2>&1 || { echo "! 找不到 cargo"; exit 1; }
    rustup target list --installed 2>/dev/null | grep -qx "$rust_target" || {
        echo "! 未安装 Rust target: $rust_target"
        exit 1
    }

    local ar="$BIN/llvm-ar${EXT}"
    [ -f "$cc" ] || { echo "! 找不到 NDK clang: $cc"; exit 1; }
    [ -f "$ar" ] || ar=""

    echo "- 构建 Rust daemon: $rust_target"
    local target_dir="$ROOT/build/rust-daemon-target"
    local target_env cargo_cc cargo_ar
    target_env="$(printf '%s' "$rust_target" | tr '[:lower:]-' '[:upper:]_')"
    cargo_cc="$(path_for_cargo "$cc")"
    [ -n "$ar" ] && cargo_ar="$(path_for_cargo "$ar")" || cargo_ar=""

    env \
        "CARGO_TARGET_${target_env}_LINKER=$cargo_cc" \
        "CARGO_TARGET_${target_env}_AR=$cargo_ar" \
        "RUSTC_BOOTSTRAP=1" \
        "RUSTFLAGS=${RUSTFLAGS:-} $RUST_NO_LOCATION_FLAGS" \
        cargo build --manifest-path "$RUST_DAEMON/Cargo.toml" \
            --release --target "$rust_target" --target-dir "$target_dir"

    local bin="$target_dir/$rust_target/release/appopt_daemon_rs"
    local dst="$WORK/config/bin/$abidir/AppOptRs"
    [ -f "$bin" ] || { echo "! 找不到 Rust daemon 产物: $bin"; exit 1; }
    cp "$bin" "$dst"
    [ -f "$LLVM_STRIP" ] && "$LLVM_STRIP" --strip-all "$dst" || true
}

sync_source_versions

echo "- 准备模块工作目录: $WORK"
rm -rf "$WORK"
mkdir -p "$WORK"
cp -r "$BASE_DIR/." "$WORK/"
build_pkg_helper
build_foreground_helper
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
    local src="$1" obj="$2" label="$3" target_arch="$4" include_arch="$5" abi_define="$6"
    echo "- 构建 BPF 对象: $label"
    (
        cd "$(dirname "$src")"
        "$CLANG" -target bpf -g -O2 -c "$(basename "$src")" -o "$obj" \
            -fdebug-compilation-dir=. \
            -ffile-prefix-map="$ROOT=." \
            -I"$SYSROOT/usr/include" \
            -I"$SYSROOT/usr/include/$include_arch" \
            -D"$target_arch" \
            -D"$abi_define" \
            -Wno-unused-value
    )
    [ -s "$obj" ] || { echo "! BPF 对象构建失败: $label"; exit 1; }
}

build_bpf_pair_for_abi() {
    local abidir="$1" target_arch="$2" include_arch="$3" abi_define="$4"
    local ebpf_dir="$WORK/config/ebpf/$abidir"
    mkdir -p "$ebpf_dir"
    build_bpf_obj "$BPF_SRC" "$ebpf_dir/queuebuffer_probe.bpf.o" "queuebuffer_probe.bpf.c ($abidir)" "$target_arch" "$include_arch" "$abi_define"
    build_bpf_obj "$BPF_PERF_SRC" "$ebpf_dir/queuebuffer_probe_perf.bpf.o" "queuebuffer_probe_perf.bpf.c ($abidir)" "$target_arch" "$include_arch" "$abi_define"
}

build_bpf_pair_for_abi arm64-v8a   __TARGET_ARCH_arm64 aarch64-linux-android  APPOPT_BPF_ARM64
build_bpf_pair_for_abi armeabi-v7a __TARGET_ARCH_arm   arm-linux-androideabi  APPOPT_BPF_ARM
build_bpf_pair_for_abi x86_64      __TARGET_ARCH_x86   x86_64-linux-android   APPOPT_BPF_X86_64
build_bpf_pair_for_abi x86         __TARGET_ARCH_x86   i686-linux-android      APPOPT_BPF_I386

# 保留根目录默认对象，兼容手动解包测试；正式刷入时 customize.sh 会按设备 ABI 覆盖。
cp "$WORK/config/ebpf/arm64-v8a/queuebuffer_probe.bpf.o" "$BPF_OBJ"
cp "$WORK/config/ebpf/arm64-v8a/queuebuffer_probe_perf.bpf.o" "$BPF_PERF_OBJ"

build_abi() {
    local triple="$1" abidir="$2" rust_target="$3"
    local cc="$BIN/${triple}${API}-clang${EXT}"
    local dst="$WORK/config/bin/$abidir/AppOpt"

    [ -f "$cc" ] || { echo "! 找不到 $abidir 编译器: $cc"; exit 1; }
    mkdir -p "$WORK/config/bin/$abidir"

    build_rust_bridge "$rust_target" "$cc"

    echo "- 构建 $abidir (AppOpt + Rust/aya eBPF bridge)"
    "$cc" -Wall -Wextra -O2 -pthread \
        -I"$ROOT" \
        -I"$FPS_MON" \
        "$SRC" \
        "$FOREGROUND_MONITOR_SRC" \
        "$FPS_MON/ebpf_fps.c" \
        "$FPS_MON/fps_fallback.c" \
        "$RUST_BRIDGE_LIB" \
        -ldl -llog \
        -o "$dst"

    [ -f "$LLVM_STRIP" ] && "$LLVM_STRIP" --strip-all "$dst" || true
    build_rust_daemon "$rust_target" "$cc" "$abidir"
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
