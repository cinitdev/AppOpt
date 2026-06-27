#!/system/bin/sh
# AppOpt 内置安装器启动脚本。
#
# 用法：
#   appopt_pkg_helper.sh app-info top.suto.appopt
#   appopt_pkg_helper.sh install /data/adb/modules/AppOpt/config/app/AppOpt.apk

DIR="${APP_OPT_HELPER_DIR:-${0%/*}}"
if command -v realpath >/dev/null 2>&1; then
    DIR="$(realpath "$DIR")"
else
    DIR="$(cd "$DIR" 2>/dev/null && pwd -P)"
fi
JAR="$DIR/appopt_pkg_helper.jar"
CLASS="appopt.pkghelper.PackageHelper"

if [ ! -f "$JAR" ]; then
    echo "ok=0"
    echo "error=找不到内置安装器 jar: $JAR"
    exit 1
fi

read_app_prop() {
    local key="$1"
    local file="$DIR/../app.prop"
    [ -f "$file" ] || return
    sed -n "s/^${key}=//p" "$file" | head -n 1
}

if [ "$1" = "install" ]; then
    [ -n "$APP_OPT_PACKAGE" ] || APP_OPT_PACKAGE="$(read_app_prop package)"
    [ -n "$APP_OPT_VERSION_CODE" ] || APP_OPT_VERSION_CODE="$(read_app_prop versionCode)"
    [ -n "$APP_OPT_VERSION_NAME" ] || APP_OPT_VERSION_NAME="$(read_app_prop versionName)"
    export APP_OPT_PACKAGE APP_OPT_VERSION_CODE APP_OPT_VERSION_NAME
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
    --nice-name=appopt_pkg_helper \
    "$CLASS" "$@"
