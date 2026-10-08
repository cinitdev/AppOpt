#!/usr/bin/env bash
# 只读检查，不更新子模块、不安装依赖、不连接发布平台。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
failed=0
check_command() {
    local tool="$1"
    if command -v "$tool" >/dev/null 2>&1; then
        printf '[正常] %s: %s\n' "$tool" "$(command -v "$tool")"
    else
        printf '[缺少] %s\n' "$tool"
        failed=1
    fi
}
echo '- 检查命令行工具'
for tool in git python java javac jar cargo rustup; do check_command "$tool"; done
git --version 2>/dev/null || failed=1
python --version 2>/dev/null || failed=1
java -version 2>&1 || failed=1
javac -version 2>&1 || failed=1
cargo --version 2>/dev/null || failed=1
echo '- 检查 Android SDK / NDK'
sdk="$(python - <<'PY'
import os
sdk = ''
try:
    with open('local.properties', encoding='utf-8') as f:
        for line in f:
            if line.strip().startswith('sdk.dir='):
                sdk = line.strip()[8:]
                break
except OSError:
    pass
print(sdk.replace('\\:', ':').replace('\\\\', '\\').replace('\\', '/') or os.getenv('ANDROID_HOME') or os.getenv('ANDROID_SDK_ROOT') or '')
PY
)"
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
    echo '[缺少] Android SDK：请设置 local.properties 中的 sdk.dir 或 ANDROID_HOME。'
    failed=1
else
    echo "[正常] SDK: $sdk"
    platform="$(ls -d "$sdk"/platforms/*/ 2>/dev/null | sort -V | tail -n1)"
    build_tools="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -n1)"
    [ -f "${platform}android.jar" ] || { echo '[缺少] Android SDK platform / android.jar'; failed=1; }
    [ -f "${build_tools}lib/d8.jar" ] || { echo '[缺少] Android build-tools / d8.jar'; failed=1; }
fi
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    ndk="$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -n1)"
fi
if [ -d "$ndk/toolchains/llvm" ]; then
    echo "[正常] NDK: $ndk"
else
    echo '[缺少] Android NDK / LLVM 工具链'; failed=1
fi
echo '- 检查 Rust 目标'
targets="$(rustup target list --installed 2>/dev/null)"
for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
    if printf '%s\n' "$targets" | grep -qx "$target"; then echo "[正常] $target";
    else echo "[缺少] $target：请运行 rustup target add $target"; failed=1; fi
done
if [ -f native_daemon/fps_monitor/aya/aya/Cargo.toml ]; then
    echo '[正常] Aya 子模块已存在'
else
    echo '[提示] Aya 子模块尚未初始化，完整构建时将按脚本初始化。'
fi
echo '[提示] 发布凭据、远端版本和权限由发布步骤检查；本次未访问远端。'
exit "$failed"
