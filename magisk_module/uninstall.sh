#!/system/bin/sh

# 先停用刷新率策略；守护的其他配置与历史不会被这里删除。
MODDIR=${0%/*}
touch "$MODDIR/remove"
# Joyose 先独立恢复，后续刷新率恢复失败不能跳过本项；共用 service.sh 的短命令入口。
JOYOSE_RESTORE_RESULT=0
sh "$MODDIR/service.sh" --joyose-restore >/dev/null 2>&1 || JOYOSE_RESTORE_RESULT=1
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
exit "$JOYOSE_RESTORE_RESULT"
