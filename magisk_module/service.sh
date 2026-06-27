#!/system/bin/sh
# service.sh —— late_start service 阶段执行 (系统基本启动完成后)
# 在原版基础上改进:
#   1) 用看门狗拉起 AppOpt 守护进程, 异常退出自动重启 (单实例)
#   2) 把守护进程 stdout/stderr 写入 AppOpt.log, 便于在 App 内查看
# 其余 (等开机、core_ctl 锁定在线核数、厂商性能调度开关) 保留原版行为。

wait_sys_boot_completed() {
	local i=9
	until [ "$(getprop sys.boot_completed)" == "1" ] || [ $i -le 0 ]; do
		i=$((i-1))
		sleep 9
	done
}
wait_sys_boot_completed

MODDIR=${0%/*}
cd "$MODDIR"
BIN="$MODDIR/config/bin/AppOpt"
CONF="$MODDIR/config/applist.conf"
LOG="$MODDIR/logs/AppOpt.log"
APPOPT_IN_APP_UPDATE_FLAG="/data/adb/appopt_in_app_update"

mkdir -p "$MODDIR/config" "$MODDIR/config/bin" "$MODDIR/config/ebpf" "$MODDIR/logs"

# 二进制不存在直接退出
[ -f "$BIN" ] || exit 0
chmod 0755 "$BIN"

# 本次开机先清空日志一次 (只保留本次开机以来的输出)
: > "$LOG"

read_app_prop() {
	local key="$1"
	local file="$2"
	[ -f "$file" ] || return
	sed -n "s/^${key}=//p" "$file" | head -n 1
}

run_app_helper() {
	local out="$1"
	shift
	APP_OPT_HELPER_DIR="$APP_HELPER_DIR" \
	APP_OPT_PACKAGE="$APP_PKG" \
	APP_OPT_VERSION_CODE="$APP_VERSION_CODE" \
	APP_OPT_VERSION_NAME="$APP_VERSION_NAME" \
	sh "$APP_HELPER" "$@" > "$out" 2>&1
	return $?
}

install_deferred_app_update() {
	local APP_DIR="$MODDIR/config/app"
	local APP_META="$APP_DIR/app.prop"
	[ -f "$APP_META" ] || return

	APP_PKG="$(read_app_prop package "$APP_META")"
	APP_NAME="$(read_app_prop name "$APP_META")"
	APP_APK="$APP_DIR/$(read_app_prop apk "$APP_META")"
	APP_VERSION_CODE="$(read_app_prop versionCode "$APP_META")"
	APP_VERSION_NAME="$(read_app_prop versionName "$APP_META")"
	APP_VARIANT="$(read_app_prop variant "$APP_META")"
	APP_HELPER_DIR="$APP_DIR/tools"
	APP_HELPER="$APP_HELPER_DIR/appopt_pkg_helper.sh"
	[ -n "$APP_PKG" ] || APP_PKG="top.suto.appopt"
	[ -n "$APP_NAME" ] || APP_NAME="AppOpt"

	if [ ! -f "$APP_APK" ] || [ -z "$APP_VERSION_CODE" ] || [ ! -f "$APP_HELPER" ]; then
		echo "- 延后 App 更新文件不完整，保留 config/app 等待手动处理" >> "$LOG"
		return
	fi

	chmod 0644 "$APP_APK" 2>/dev/null || true
	chmod 0755 "$APP_HELPER_DIR" "$APP_HELPER_DIR"/*.sh 2>/dev/null || true

	local APP_INFO="$MODDIR/logs/AppOpt_app_info.prop"
	local INSTALL_INFO="$MODDIR/logs/AppOpt_app_install.prop"
	local INSTALLED_VERSION_CODE INSTALLED_VERSION_NAME

	echo "- 检测到延后 App 更新：$APP_NAME $APP_VERSION_NAME ($APP_VERSION_CODE)" >> "$LOG"
	if run_app_helper "$APP_INFO" app-info "$APP_PKG" && [ "$(read_app_prop ok "$APP_INFO")" = "1" ]; then
		if [ "$(read_app_prop installed "$APP_INFO")" = "1" ]; then
			INSTALLED_VERSION_CODE="$(read_app_prop versionCode "$APP_INFO")"
			INSTALLED_VERSION_NAME="$(read_app_prop versionName "$APP_INFO")"
			echo "- 当前已安装 App：${INSTALLED_VERSION_NAME:-未知} ($INSTALLED_VERSION_CODE)" >> "$LOG"
			if [ "$APP_VARIANT" != "debug" ] &&
				[ "$INSTALLED_VERSION_CODE" = "$APP_VERSION_CODE" ] &&
				{ [ -z "$INSTALLED_VERSION_NAME" ] || [ "$INSTALLED_VERSION_NAME" = "$APP_VERSION_NAME" ]; }; then
				echo "- App 已是随附版本，清理延后安装文件" >> "$LOG"
				rm -rf "$APP_DIR"
				return
			fi
			if [ "$INSTALLED_VERSION_CODE" -gt "$APP_VERSION_CODE" ] 2>/dev/null; then
				echo "- 已安装 App 版本高于随附版本，清理延后安装文件" >> "$LOG"
				rm -rf "$APP_DIR"
				return
			fi
		fi
	else
		echo "- 读取已安装 App 版本失败，仍尝试安装随附版本" >> "$LOG"
	fi

	if run_app_helper "$INSTALL_INFO" install "$APP_APK" && [ "$(read_app_prop ok "$INSTALL_INFO")" = "1" ]; then
		echo "- 延后 App 更新完成，清理临时安装文件" >> "$LOG"
		rm -rf "$APP_DIR"
	else
		echo "- 延后 App 更新失败，保留 config/app 以便下次开机重试" >> "$LOG"
		[ -f "$INSTALL_INFO" ] && sed -n '1,6p' "$INSTALL_INFO" >> "$LOG"
	fi
}

install_deferred_app_update
rm -f "$MODDIR/logs/AppOpt_app_info.prop" "$MODDIR/logs/AppOpt_app_install.prop" 2>/dev/null || true
rm -f "$APPOPT_IN_APP_UPDATE_FLAG" 2>/dev/null || true

# 判断是否已经有"本模块自己的" AppOpt 守护进程在运行。
# 不能只用 pgrep -x AppOpt: 项目开源后, 其他二改版本也可能使用同名进程。
# 这里用 /proc/<pid>/exe 反查可执行文件路径, 只有路径等于本模块 BIN 才算命中。
is_our_daemon_running() {
	for PID in $(pidof AppOpt 2>/dev/null) $(pgrep -x AppOpt 2>/dev/null); do
		[ -n "$PID" ] || continue
		EXE="$(readlink "/proc/$PID/exe" 2>/dev/null)"
		[ "$EXE" = "$BIN" ] && return 0
	done
	return 1
}

# 看门狗: 守护进程退出后自动重启, 只按本模块 BIN 做单实例判断。
(
	while true; do
		if ! is_our_daemon_running; then
			# 追加写: 崩溃重启不丢上一轮日志, 便于排查异常退出原因
			"$BIN" -c "$CONF" -s 2 >>"$LOG" 2>&1
		fi
		sleep 5
	done
) &

# --- 以下为原版行为: 把可在线核数锁定到最大, 避免核心被离线 ---
for MAX_CPUS in /sys/devices/system/cpu/cpu*/core_ctl/max_cpus; do
	if [ -e "$MAX_CPUS" ] && [ "$(cat $MAX_CPUS)" != "$(cat ${MAX_CPUS%/*}/min_cpus)" ]; then
		chmod a+w "${MAX_CPUS%/*}/min_cpus"
		echo "$(cat $MAX_CPUS)" > "${MAX_CPUS%/*}/min_cpus"
		chmod a-w "${MAX_CPUS%/*}/min_cpus"
	fi
done

# 如需暂停绿厂oiface请将下面这行的#号注释删掉，恢复则将0改成1
# [ -n "$(getprop persist.sys.oiface.enable)" ] && setprop persist.sys.oiface.enable 0

# 如需禁用米系机型joyose请将下面这行pm命令前的#号删掉
# pm disable-user com.xiaomi.joyose; pm clear com.xiaomi.joyose
