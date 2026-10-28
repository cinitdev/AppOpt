#!/system/bin/sh

# 先停用刷新率策略；守护的其他配置与历史不会被这里删除。
MODDIR=${0%/*}
touch "$MODDIR/remove"
sh "$MODDIR/config/tools/qixia_refresh_helper.sh" stop >/dev/null 2>&1 || exit 1
STATE="$MODDIR/config/state/refresh_rate.state"
if [ -r "$STATE" ] && grep -q '^baseline\.peak=' "$STATE"; then
    for APP_PROCESS in /system/bin/app_process /system/bin/app_process64 /system/bin/app_process32; do
        [ -x "$APP_PROCESS" ] || continue
        CLASSPATH="$MODDIR/config/tools/qixia_refresh_helper.jar" \
            "$APP_PROCESS" -Xnoimage-dex2oat /system/bin qixia.display.RefreshRestore "$STATE"
        break
    done
fi
