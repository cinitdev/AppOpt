#!/system/bin/sh
# QixiaThreads APK/包管理助手启动脚本。
#
# 用法：
#   qixia_pkg_helper.sh app-info top.qixia.threads
#   qixia_pkg_helper.sh component-state com.xiaomi.joyose/.smartop.SmartOpService 0
#   qixia_pkg_helper.sh install /data/adb/modules/QixiaThreads/config/app/QixiaThreads.apk

DIR="${QIXIA_HELPER_DIR:-${0%/*}}"
if command -v realpath >/dev/null 2>&1; then
    DIR="$(realpath "$DIR")"
else
    DIR="$(cd "$DIR" 2>/dev/null && pwd -P)"
fi
JAR="$DIR/qixia_pkg_helper.jar"
CLASS="qixia.pkghelper.PackageHelper"

if [ ! -f "$JAR" ]; then
    echo "ok=0"
    echo "error=找不到内置安装器 jar: $JAR"
    exit 1
fi

APP_PROCESS=""
for CANDIDATE in /system/bin/app_process /system/bin/app_process64 /system/bin/app_process32; do
    if [ -x "$CANDIDATE" ]; then
        APP_PROCESS="$CANDIDATE"
        break
    fi
done

if [ -z "$APP_PROCESS" ]; then
    echo "ok=0"
    echo "error=找不到 app_process"
    exit 1
fi

exec "$APP_PROCESS" \
    -Djava.class.path="$JAR" \
    -Xnoimage-dex2oat \
    /system/bin \
    --nice-name=qixia_pkg_helper \
    "$CLASS" "$@"
