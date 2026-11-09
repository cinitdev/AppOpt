#!/system/bin/sh
# 后期启动服务：单实例看门狗管理 Rust、前台与刷新率服务，并处理延后安装。

MODDIR=${0%/*}
# QIXIA_JOYOSE_CONTROL_VERSION=1
# QIXIA_SERVICE_TOOLS_VERSION=1
# 点加载共享函数，所有常驻管理逻辑继续在当前 service.sh 进程执行。
load_service_tool() {
	local tool="$MODDIR/tools/$1"
	[ -s "$tool" ] && [ -r "$tool" ] || { echo "- 服务脚本缺失或不可读：$tool" >&2; return 1; }
	. "$tool"
}

case "$1" in
	--joyose-status|--joyose-init|--joyose-apply|--joyose-restore|--joyose-set)
		load_service_tool service_joyose.sh || exit 1
		joyose_run "$@"; exit $? ;;
esac
# 只在本次启动消费事务标记，不把标记留在命令行或传给子进程。
SERVICE_HOT_UPDATE=${QIXIA_SERVICE_HOT_UPDATE:-0}
unset QIXIA_SERVICE_HOT_UPDATE
# 更新中断后先恢复完整运行文件；正常热启动由事务工作进程负责检查。
if [ "$SERVICE_HOT_UPDATE" != 1 ] && [ "$1" != --install-app ] &&
	[ -f /data/adb/qixia_hot_update/worker.sh ]; then
	sh /data/adb/qixia_hot_update/worker.sh --recover || exit 1
fi
# 事务恢复完成后才读取运行文件，避免先加载更新中断留下的混合版本。
for SERVICE_TOOL in service_joyose.sh service_watchdog.sh service_app_install.sh service_runtime.sh; do
	load_service_tool "$SERVICE_TOOL" || exit 1
done
unset SERVICE_TOOL
WATCHDOG_STATE_DIR="$MODDIR/config/state"
WATCHDOG_LOCK="$WATCHDOG_STATE_DIR/service_watchdog.lock"
WATCHDOG_OWNER=""
WATCHDOG_RECOVERY_LOCK=""
WATCHDOG_RECOVERY_HELD=0
WATCHDOG_DAEMON_PID=""
WATCHDOG_DAEMON_STARTTIME=""
WATCHDOG_STOPPING=0
FOREGROUND_HELPER="$MODDIR/config/tools/qixia_foreground_helper.sh"
REFRESH_HELPER="$MODDIR/config/tools/qixia_refresh_helper.sh"
LOG="$MODDIR/logs/QixiaThreads.log"

if [ "$1" != --install-app ]; then
	acquire_watchdog_lock
	trap 'cleanup_watchdog' EXIT
	trap 'exit 0' HUP INT TERM
fi

[ "$1" = --install-app ] || wait_sys_boot_completed

cd "$MODDIR"
BIN="$MODDIR/config/bin/QiXiaRs"
DAEMON_PROC_NAME="QiXiaRs"
RS_RESTART_FLAG="$MODDIR/config/.qixia_restart_rs_daemon"
CONF="$MODDIR/config/applist.conf"
CALIB_POLICY="$MODDIR/config/calib_policy.conf"
FOREGROUND_HELPER_LOG="$MODDIR/logs/ForegroundHelper.log"
QIXIA_IN_APP_UPDATE_FLAG="/data/adb/qixia_in_app_update"

mkdir -p "$MODDIR/config" "$MODDIR/config/bin" "$MODDIR/config/ebpf" \
	"$MODDIR/config/state" "$MODDIR/logs"
[ "$1" = --install-app ] || rm -f "$RS_RESTART_FLAG" 2>/dev/null || true

# 二进制不存在直接退出
[ -f "$BIN" ] || exit 0
chmod 0755 "$BIN"

# 同一次系统启动只清空一次。看门狗在本次开机内重启时保留前一次崩溃现场。
LOG_BOOT_MARKER="$WATCHDOG_STATE_DIR/service_log.boot_id"
CURRENT_BOOT_ID="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
LOG_BOOT_ID="$(cat "$LOG_BOOT_MARKER" 2>/dev/null)"
if [ "$1" = --install-app ]; then
	:
elif [ -n "$CURRENT_BOOT_ID" ] && [ "$LOG_BOOT_ID" != "$CURRENT_BOOT_ID" ]; then
	: > "$LOG"
	[ -f "$FOREGROUND_HELPER_LOG" ] && : > "$FOREGROUND_HELPER_LOG"
	printf '%s\n' "$CURRENT_BOOT_ID" > "$LOG_BOOT_MARKER"
else
	echo "- service watchdog restarted in current boot; preserving existing logs" >> "$LOG"
fi

if [ "$1" = --install-app ]; then
	# 显式安装与开机重试共用 APK 内容比较，兼容同版本号的不同构建。
	install_deferred_embedded_app
	[ ! -d "$MODDIR/config/app" ]
	exit $?
fi
if [ "$SERVICE_HOT_UPDATE" != 1 ] &&
	[ "$(cat "$MODDIR/config/app/.defer_boot" 2>/dev/null)" != "$CURRENT_BOOT_ID" ]; then
	install_deferred_embedded_app
	# 开机补装 APK 成功后收尾；热更新工作进程会自行完成清理。
	if [ -f "$MODDIR/config/tools/qixia_hot_update.sh" ]; then
		sh "$MODDIR/config/tools/qixia_hot_update.sh" --cleanup >> "$LOG" 2>&1 || true
	fi
fi
rm -f "$MODDIR/logs/QixiaThreads_app_info.prop" "$MODDIR/logs/QixiaThreads_app_install.prop" 2>/dev/null || true
rm -f "$QIXIA_IN_APP_UPDATE_FLAG" 2>/dev/null || true

apply_runtime_policy

# 启动或热更新后按用户选择应用一次；不轮询、不启用或清空整个 Joyose 应用。
joyose_run --joyose-apply >> "$LOG" 2>&1 || echo '- Joyose SmartOpService 策略未应用，请在设置中检查' >> "$LOG"

# 保持单个 service.sh 作为看门狗，不再派生第二个永久 shell。
watch_services
