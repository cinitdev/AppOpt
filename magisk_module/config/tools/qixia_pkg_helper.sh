#!/system/bin/sh
# QixiaThreads APK/包管理助手启动脚本。
#
# 用法：
#   qixia_pkg_helper.sh app-info top.qixia.threads [待比较APK]
#   qixia_pkg_helper.sh component-state com.xiaomi.joyose/.smartop.SmartOpService 0
#   qixia_pkg_helper.sh install /data/adb/modules/QixiaThreads/config/app/QixiaThreads.apk
#   qixia_pkg_helper.sh toast '热更新成功，无需重启手机'

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

# 安装器退出后会删除临时工作目录；ART 初始化仍会读取当前目录，必须先切到有效目录。
cd / || exit 1

exec "$APP_PROCESS" \
    -Djava.class.path="$JAR" \
    -Xnoimage-dex2oat \
    /system/bin \
    --nice-name=qixia_pkg_helper \
    "$CLASS" "$@"
