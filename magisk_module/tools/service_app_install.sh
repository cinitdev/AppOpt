# 仅供 service.sh 用 . 加载函数，不独立启动，不创建常驻 Shell。

read_app_prop() {
	local key="$1"
	local file="$2"
	[ -f "$file" ] || return
	sed -n "s/^${key}=//p" "$file" | head -n 1
}

run_app_helper() {
	local out="$1"
	shift
	local helper_pid helper_state waited=0 result
	QIXIA_HELPER_DIR="$APP_HELPER_DIR" \
	QIXIA_PACKAGE="$APP_PKG" \
	QIXIA_VERSION_CODE="$APP_VERSION_CODE" \
	QIXIA_VERSION_NAME="$APP_VERSION_NAME" \
	sh "$APP_HELPER" "$@" > "$out" 2>&1 &
	helper_pid=$!
	while [ -r "/proc/$helper_pid/stat" ]; do
		helper_state="$(sed 's/^.*) //' "/proc/$helper_pid/stat" 2>/dev/null | awk '{print $1}')"
		[ "$helper_state" = "Z" ] && break
		if [ "$waited" -ge 60 ]; then
			kill -TERM "$helper_pid" 2>/dev/null || true
			sleep 1
			kill -KILL "$helper_pid" 2>/dev/null || true
			wait "$helper_pid" 2>/dev/null || true
			printf 'ok=0\nerror=内置安装器执行超过 60 秒，已终止\n' >> "$out"
			return 124
		fi
		sleep 1
		waited=$((waited + 1))
	done
	result=0
	wait "$helper_pid" || result=$?
	return "$result"
}

cleanup_deferred_app_files() {
	if rm -rf "$MODDIR/config/app"; then
		# 热更新事务还需用助手显示结果，由事务持有者在提示结束后统一清理。
		if [ -d /data/adb/qixia_hot_update ] && [ -f "$MODDIR/config/tools/qixia_hot_update.sh" ]; then
			return 0
		fi
		rm -f "$MODDIR/config/tools/qixia_pkg_helper.jar" "$MODDIR/config/tools/qixia_pkg_helper.sh" && return 0
	fi
	echo "- 临时 App 安装文件或安装助手清理失败，下次启动重试" >> "$LOG"
	return 1
}

# 热更新的 APK 由用户明确确认、退出 App 后安装；普通开机仍可完成失败重试。
install_deferred_embedded_app() {
	local APP_DIR="$MODDIR/config/app"
	local APP_META="$APP_DIR/app.prop"
	if [ ! -d "$APP_DIR" ]; then
		cleanup_deferred_app_files
		return
	fi
	[ -f "$APP_META" ] || return

	APP_PKG="$(read_app_prop package "$APP_META")"
	APP_NAME="$(read_app_prop name "$APP_META")"
	APP_APK="$APP_DIR/$(read_app_prop apk "$APP_META")"
	APP_VERSION_CODE="$(read_app_prop versionCode "$APP_META")"
	APP_VERSION_NAME="$(read_app_prop versionName "$APP_META")"
	APP_HELPER_DIR="$MODDIR/config/tools"
	APP_HELPER="$APP_HELPER_DIR/qixia_pkg_helper.sh"
	[ -n "$APP_PKG" ] || APP_PKG="top.qixia.threads"
	[ -n "$APP_NAME" ] || APP_NAME="柒夏线程"

	if [ ! -f "$APP_APK" ] || [ -z "$APP_VERSION_CODE" ] || [ ! -f "$APP_HELPER" ]; then
		echo "- 延后 App 更新文件不完整，保留 config/app 等待手动处理" >> "$LOG"
		return
	fi

	chmod 0644 "$APP_APK" 2>/dev/null || true
	chmod 0755 "$APP_HELPER_DIR" "$APP_HELPER_DIR"/*.sh 2>/dev/null || true

	local APP_INFO="$MODDIR/logs/QixiaThreads_app_info.prop"
	local INSTALL_INFO="$MODDIR/logs/QixiaThreads_app_install.prop"
	local INSTALLED_VERSION_CODE INSTALLED_VERSION_NAME

	echo "- 检测到延后 App 更新：$APP_NAME $APP_VERSION_NAME ($APP_VERSION_CODE)" >> "$LOG"
	if run_app_helper "$APP_INFO" app-info "$APP_PKG" "$APP_APK" && [ "$(read_app_prop ok "$APP_INFO")" = "1" ]; then
		if [ "$(read_app_prop installed "$APP_INFO")" = "1" ]; then
			INSTALLED_VERSION_CODE="$(read_app_prop versionCode "$APP_INFO")"
			INSTALLED_VERSION_NAME="$(read_app_prop versionName "$APP_INFO")"
			echo "- 当前已安装 App：${INSTALLED_VERSION_NAME:-未知} ($INSTALLED_VERSION_CODE)" >> "$LOG"
			if [ "$(read_app_prop apk_same "$APP_INFO")" = 1 ]; then
				echo "- 已安装 APK 与随附文件完全相同，清理延后安装文件" >> "$LOG"
				cleanup_deferred_app_files
				return
			fi
			if [ "$INSTALLED_VERSION_CODE" -gt "$APP_VERSION_CODE" ] 2>/dev/null; then
				echo "- 已安装 App 版本高于随附版本，清理延后安装文件" >> "$LOG"
				cleanup_deferred_app_files
				return
			fi
			if [ "$INSTALLED_VERSION_CODE" = "$APP_VERSION_CODE" ]; then
				echo "- 版本号相同，但 APK 内容不同或尚未确认一致，继续覆盖安装" >> "$LOG"
			fi
		fi
	else
		echo "- 读取已安装 App 版本失败，仍尝试安装随附版本" >> "$LOG"
	fi

	if run_app_helper "$INSTALL_INFO" install "$APP_APK" && [ "$(read_app_prop ok "$INSTALL_INFO")" = "1" ]; then
		echo "- 延后 App 更新完成，清理临时安装文件" >> "$LOG"
		cleanup_deferred_app_files
	else
		echo "- 延后 App 更新失败，保留 config/app 以便下次开机重试" >> "$LOG"
		[ -f "$INSTALL_INFO" ] && sed -n '1,6p' "$INSTALL_INFO" >> "$LOG"
	fi
}
