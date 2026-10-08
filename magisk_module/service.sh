#!/system/bin/sh
# service.sh —— 后期启动服务阶段执行（系统基本启动完成后）
# 在原版基础上改进:
#   1) 用看门狗拉起 QixiaThreads 守护进程, 异常退出自动重启 (单实例)
#   2) 把守护进程标准输出和标准错误写入 QixiaThreads.log，便于在 App 内查看
# 其余启动兼容和厂商性能调度开关保留；core_ctl 锁核改为显式可选。

MODDIR=${0%/*}
WATCHDOG_STATE_DIR="$MODDIR/config/state"
WATCHDOG_LOCK="$WATCHDOG_STATE_DIR/service_watchdog.lock"
WATCHDOG_OWNER=""
WATCHDOG_RECOVERY_LOCK=""
WATCHDOG_RECOVERY_HELD=0
WATCHDOG_DAEMON_PID=""
WATCHDOG_DAEMON_STARTTIME=""
WATCHDOG_STOPPING=0
FOREGROUND_HELPER="$MODDIR/config/tools/qixia_foreground_helper.sh"
LOG="$MODDIR/logs/QixiaThreads.log"

service_watchdog_starttime() {
	local pid="$1"
	case "$pid" in
		''|*[!0-9]*) return 1 ;;
	esac
	[ -r "/proc/$pid/stat" ] || return 1
	sed 's/^.*) //' "/proc/$pid/stat" 2>/dev/null | awk '{print $20}'
}

service_watchdog_owner_alive() {
	local owner="$1" current_boot="$2" owner_pid owner_rest owner_boot owner_start current_start
	[ -n "$owner" ] || return 1
	owner_pid=${owner%%:*}
	owner_rest=${owner#*:}
	[ "$owner_rest" != "$owner" ] || return 1
	owner_boot=${owner_rest%:*}
	owner_start=${owner_rest##*:}
	[ -n "$owner_start" ] || return 1
	current_start="$(service_watchdog_starttime "$owner_pid")"
	[ "$owner_start" = "$current_start" ] || return 1
	[ -z "$current_boot" ] || [ -z "$owner_boot" ] || [ "$owner_boot" = "$current_boot" ]
}

release_watchdog_recovery_lock() {
	[ "$WATCHDOG_RECOVERY_HELD" = 1 ] || return
	WATCHDOG_RECOVERY_HELD=0
	[ "$(readlink "$WATCHDOG_RECOVERY_LOCK" 2>/dev/null)" = "$WATCHDOG_OWNER" ] || return
	rm -f "$WATCHDOG_RECOVERY_LOCK" 2>/dev/null || true
}

publish_watchdog_lock_from_recovery() {
	if ln -s "$WATCHDOG_OWNER" "$WATCHDOG_LOCK" 2>/dev/null; then
		release_watchdog_recovery_lock
		return 0
	fi
	release_watchdog_recovery_lock
	return 1
}

acquire_watchdog_lock() {
	local owner owner_pid owner_boot owner_start owner_rest current_boot current_start recovery_owner
	mkdir -p "$WATCHDOG_STATE_DIR"
	current_boot="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
	[ -n "$current_boot" ] || exit 1
	current_start="$(service_watchdog_starttime "$$")"
	[ -n "$current_start" ] || exit 1
	WATCHDOG_OWNER="$$:$current_boot:$current_start"
	WATCHDOG_RECOVERY_LOCK="$WATCHDOG_STATE_DIR/service_watchdog.recovery.$current_boot"

	while true; do
		if { [ ! -d "$WATCHDOG_LOCK" ] || [ -L "$WATCHDOG_LOCK" ]; } &&
			ln -s "$WATCHDOG_OWNER" "$WATCHDOG_LOCK" 2>/dev/null; then
			break
		fi
		# 只有取得恢复互斥锁的进程才能检查和删除失效主锁。其他并发启动者只等待，
		# 避免多个恢复者在“读取旧 owner”和“删除锁”之间误删刚发布的新锁。
		if [ -d "$WATCHDOG_RECOVERY_LOCK" ] && [ ! -L "$WATCHDOG_RECOVERY_LOCK" ]; then
			owner_pid="$(sed -n 's/^pid=//p' "$WATCHDOG_RECOVERY_LOCK/owner" 2>/dev/null | head -n 1)"
			owner_boot="$(sed -n 's/^boot_id=//p' "$WATCHDOG_RECOVERY_LOCK/owner" 2>/dev/null | head -n 1)"
			owner_start="$(sed -n 's/^starttime=//p' "$WATCHDOG_RECOVERY_LOCK/owner" 2>/dev/null | head -n 1)"
			current_start="$(service_watchdog_starttime "$owner_pid")"
			if [ -n "$owner_start" ] && [ "$owner_start" = "$current_start" ] &&
				{ [ -z "$current_boot" ] || [ -z "$owner_boot" ] || [ "$owner_boot" = "$current_boot" ]; }; then
				sleep 0.05
				continue
			fi
			rm -f "$WATCHDOG_RECOVERY_LOCK/owner" 2>/dev/null || true
			rmdir "$WATCHDOG_RECOVERY_LOCK" 2>/dev/null || true
			[ -e "$WATCHDOG_RECOVERY_LOCK" ] && { sleep 0.05; continue; }
		fi
		if [ -L "$WATCHDOG_RECOVERY_LOCK" ]; then
			recovery_owner="$(readlink "$WATCHDOG_RECOVERY_LOCK" 2>/dev/null)"
			if service_watchdog_owner_alive "$recovery_owner" "$current_boot"; then
				sleep 0.05
				continue
			fi
			rm -f "$WATCHDOG_RECOVERY_LOCK" 2>/dev/null || true
		elif [ -e "$WATCHDOG_RECOVERY_LOCK" ]; then
			rm -f "$WATCHDOG_RECOVERY_LOCK" 2>/dev/null || true
		fi
		if ! ln -s "$WATCHDOG_OWNER" "$WATCHDOG_RECOVERY_LOCK" 2>/dev/null; then
			sleep 0.05
			continue
		fi
		WATCHDOG_RECOVERY_HELD=1
		if [ ! -e "$WATCHDOG_LOCK" ] && [ ! -L "$WATCHDOG_LOCK" ]; then
			if publish_watchdog_lock_from_recovery; then
				return 0
			fi
			continue
		fi

		# 兼容升级前遗留的目录锁。有效 owner 仍在运行时直接退出。
		if [ -d "$WATCHDOG_LOCK" ]; then
			owner_pid="$(sed -n 's/^pid=//p' "$WATCHDOG_LOCK/owner" 2>/dev/null | head -n 1)"
			owner_boot="$(sed -n 's/^boot_id=//p' "$WATCHDOG_LOCK/owner" 2>/dev/null | head -n 1)"
			owner_start="$(sed -n 's/^starttime=//p' "$WATCHDOG_LOCK/owner" 2>/dev/null | head -n 1)"
			current_start="$(service_watchdog_starttime "$owner_pid")"
			if [ -n "$owner_start" ] && [ "$owner_start" = "$current_start" ] &&
				{ [ -z "$current_boot" ] || [ -z "$owner_boot" ] || [ "$owner_boot" = "$current_boot" ]; }; then
				release_watchdog_recovery_lock
				exit 0
			fi
			rm -f "$WATCHDOG_LOCK/owner" 2>/dev/null || true
			rmdir "$WATCHDOG_LOCK" 2>/dev/null || true
			if publish_watchdog_lock_from_recovery; then
				return 0
			fi
			continue
		fi
		if [ ! -L "$WATCHDOG_LOCK" ]; then
			rm -f "$WATCHDOG_LOCK" 2>/dev/null || true
			if publish_watchdog_lock_from_recovery; then
				return 0
			fi
			continue
		fi

		owner="$(readlink "$WATCHDOG_LOCK" 2>/dev/null)"
		if [ -z "$owner" ]; then
			rm -f "$WATCHDOG_LOCK" 2>/dev/null || true
			if publish_watchdog_lock_from_recovery; then
				return 0
			fi
			continue
		fi
		owner_pid=${owner%%:*}
		owner_rest=${owner#*:}
		owner_boot=${owner_rest%:*}
		owner_start=${owner_rest##*:}
		current_start="$(service_watchdog_starttime "$owner_pid")"
		if [ -n "$owner_start" ] && [ "$owner_start" = "$current_start" ] &&
			{ [ -z "$current_boot" ] || [ -z "$owner_boot" ] || [ "$owner_boot" = "$current_boot" ]; }; then
			release_watchdog_recovery_lock
			exit 0
		fi
		rm -f "$WATCHDOG_LOCK" 2>/dev/null || true
		if publish_watchdog_lock_from_recovery; then
			return 0
		fi
	done
}

release_watchdog_lock() {
	release_watchdog_recovery_lock
	[ -n "$WATCHDOG_OWNER" ] || return
	[ "$(readlink "$WATCHDOG_LOCK" 2>/dev/null)" = "$WATCHDOG_OWNER" ] || return
	rm -f "$WATCHDOG_LOCK" 2>/dev/null || true
}

stop_watchdog_daemon() {
	local pid="$WATCHDOG_DAEMON_PID" expected="$WATCHDOG_DAEMON_STARTTIME" waited=0 state current
	[ -n "$pid" ] && [ -n "$expected" ] || return
	current="$(service_watchdog_starttime "$pid")"
	[ "$current" = "$expected" ] || return
	kill -TERM "$pid" 2>/dev/null || true
	while [ "$waited" -lt 150 ] && [ -r "/proc/$pid/stat" ]; do
		state="$(sed 's/^.*) //' "/proc/$pid/stat" 2>/dev/null | awk '{print $1}')"
		current="$(service_watchdog_starttime "$pid")"
		[ "$state" = "Z" ] && break
		[ "$current" = "$expected" ] || break
		sleep 0.1
		waited=$((waited + 1))
	done
	state="$(sed 's/^.*) //' "/proc/$pid/stat" 2>/dev/null | awk '{print $1}')"
	current="$(service_watchdog_starttime "$pid")"
	if [ "$state" != "Z" ] && [ "$current" = "$expected" ]; then
		echo "- Rust daemon graceful shutdown timed out; forcing stop pid=$pid" >> "$LOG" 2>/dev/null || true
		kill -KILL "$pid" 2>/dev/null || true
	fi
	wait "$pid" 2>/dev/null || true
	WATCHDOG_DAEMON_PID=""
	WATCHDOG_DAEMON_STARTTIME=""
}

stop_orphan_daemons() {
	local pid exe state start waited current
	[ -x "$BIN" ] || return
	for pid in $("$BIN" --find-pid "$DAEMON_PROC_NAME" 2>/dev/null); do
		[ "$pid" = "$WATCHDOG_DAEMON_PID" ] && continue
		exe="$(readlink "/proc/$pid/exe" 2>/dev/null)"
		[ "$exe" = "$BIN" ] || continue
		start="$(service_watchdog_starttime "$pid")"
		[ -n "$start" ] || continue
		kill -TERM "$pid" 2>/dev/null || true
		waited=0
		while [ "$waited" -lt 150 ] && [ -r "/proc/$pid/stat" ]; do
			state="$(sed 's/^.*) //' "/proc/$pid/stat" 2>/dev/null | awk '{print $1}')"
			current="$(service_watchdog_starttime "$pid")"
			if [ "$state" = "Z" ] || [ "$current" != "$start" ]; then
				break
			fi
			sleep 0.1
			waited=$((waited + 1))
		done
		state="$(sed 's/^.*) //' "/proc/$pid/stat" 2>/dev/null | awk '{print $1}')"
		current="$(service_watchdog_starttime "$pid")"
		exe="$(readlink "/proc/$pid/exe" 2>/dev/null)"
		if [ "$state" != "Z" ] && [ "$current" = "$start" ] && [ "$exe" = "$BIN" ]; then
			kill -KILL "$pid" 2>/dev/null || true
		fi
	done
}

cleanup_watchdog() {
	[ "$WATCHDOG_STOPPING" = 0 ] || return
	WATCHDOG_STOPPING=1
	if [ -f "$FOREGROUND_HELPER" ]; then
		sh "$FOREGROUND_HELPER" stop >/dev/null 2>&1 || true
	fi
	stop_watchdog_daemon
	stop_orphan_daemons
	release_watchdog_lock
}

acquire_watchdog_lock
trap 'cleanup_watchdog' EXIT
trap 'exit 0' HUP INT TERM

wait_sys_boot_completed() {
	local i=9
	until [ "$(getprop sys.boot_completed)" == "1" ] || [ $i -le 0 ]; do
		i=$((i-1))
		sleep 9
	done
}
wait_sys_boot_completed

cd "$MODDIR"
BIN="$MODDIR/config/bin/QiXiaRs"
DAEMON_PROC_NAME="QiXiaRs"
RS_RESTART_FLAG="$MODDIR/config/.qixia_restart_rs_daemon"
CONF="$MODDIR/config/applist.conf"
CALIB_POLICY="$MODDIR/config/calib_policy.conf"
LOG="$MODDIR/logs/QixiaThreads.log"
FOREGROUND_HELPER="$MODDIR/config/tools/qixia_foreground_helper.sh"
FOREGROUND_HELPER_LOG="$MODDIR/logs/ForegroundHelper.log"
QIXIA_IN_APP_UPDATE_FLAG="/data/adb/qixia_in_app_update"

mkdir -p "$MODDIR/config" "$MODDIR/config/bin" "$MODDIR/config/ebpf" \
	"$MODDIR/config/state" "$MODDIR/logs"
rm -f "$RS_RESTART_FLAG" 2>/dev/null || true

# 二进制不存在直接退出
[ -f "$BIN" ] || exit 0
chmod 0755 "$BIN"

# 同一次系统启动只清空一次。看门狗在本次开机内重启时保留前一次崩溃现场。
LOG_BOOT_MARKER="$WATCHDOG_STATE_DIR/service_log.boot_id"
CURRENT_BOOT_ID="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
LOG_BOOT_ID="$(cat "$LOG_BOOT_MARKER" 2>/dev/null)"
if [ -n "$CURRENT_BOOT_ID" ] && [ "$LOG_BOOT_ID" != "$CURRENT_BOOT_ID" ]; then
	: > "$LOG"
	[ -f "$FOREGROUND_HELPER_LOG" ] && : > "$FOREGROUND_HELPER_LOG"
	printf '%s\n' "$CURRENT_BOOT_ID" > "$LOG_BOOT_MARKER"
else
	echo "- service watchdog restarted in current boot; preserving existing logs" >> "$LOG"
fi

read_app_prop() {
	local key="$1"
	local file="$2"
	[ -f "$file" ] || return
	sed -n "s/^${key}=//p" "$file" | head -n 1
}

read_cpuset_name() {
	local name
	name="$(sed -n 's/^[[:space:]]*cpuset_name[[:space:]]*=[[:space:]]*\([^#[:space:]]*\).*$/\1/p' "$CALIB_POLICY" 2>/dev/null | tail -n 1)"
	[ -n "$name" ] || name="QiXiaRs"
	if [ "${#name}" -gt 48 ]; then
		name="QiXiaRs"
	fi
	case "$name" in
		.*|*[!A-Za-z0-9_.-]*) name="QiXiaRs" ;;
	esac
	printf '%s' "$name"
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
	if rm -rf "$MODDIR/config/app" && rm -f \
		"$MODDIR/config/tools/qixia_pkg_helper.jar" \
		"$MODDIR/config/tools/qixia_pkg_helper.sh"; then
		return 0
	fi
	echo "- 临时 App 安装文件或安装助手清理失败，下次启动重试" >> "$LOG"
	return 1
}

# App 内刷入模块时，随包 APK 留到重启后安装，避免终止当前刷入界面。
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
	APP_VARIANT="$(read_app_prop variant "$APP_META")"
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
	if run_app_helper "$APP_INFO" app-info "$APP_PKG" && [ "$(read_app_prop ok "$APP_INFO")" = "1" ]; then
		if [ "$(read_app_prop installed "$APP_INFO")" = "1" ]; then
			INSTALLED_VERSION_CODE="$(read_app_prop versionCode "$APP_INFO")"
			INSTALLED_VERSION_NAME="$(read_app_prop versionName "$APP_INFO")"
			echo "- 当前已安装 App：${INSTALLED_VERSION_NAME:-未知} ($INSTALLED_VERSION_CODE)" >> "$LOG"
			if [ "$APP_VARIANT" != "debug" ] &&
				[ "$INSTALLED_VERSION_CODE" = "$APP_VERSION_CODE" ] &&
				{ [ -z "$INSTALLED_VERSION_NAME" ] || [ "$INSTALLED_VERSION_NAME" = "$APP_VERSION_NAME" ]; }; then
				echo "- App 已是随附版本，清理延后安装文件" >> "$LOG"
				cleanup_deferred_app_files
				return
			fi
			if [ "$INSTALLED_VERSION_CODE" -gt "$APP_VERSION_CODE" ] 2>/dev/null; then
				echo "- 已安装 App 版本高于随附版本，清理延后安装文件" >> "$LOG"
				cleanup_deferred_app_files
				return
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

install_deferred_embedded_app
rm -f "$MODDIR/logs/QixiaThreads_app_info.prop" "$MODDIR/logs/QixiaThreads_app_install.prop" 2>/dev/null || true
rm -f "$QIXIA_IN_APP_UPDATE_FLAG" 2>/dev/null || true

start_foreground_helper() {
	[ -f "$FOREGROUND_HELPER" ] || return 1
	sh "$FOREGROUND_HELPER" start
}

# 只检查由本模块路径启动的守护进程，避免误判其他同名进程。
is_our_daemon_running() {
    INDEX_PIDS=""
    if [ -x "$BIN" ]; then
        INDEX_PIDS="$("$BIN" --find-pid "$DAEMON_PROC_NAME" 2>/dev/null)" || INDEX_PIDS=""
    fi
    if [ -z "$INDEX_PIDS" ]; then
        # 进程索引尚未建立时使用有界 /proc 快照，避免 pidof/pgrep 在异常 ROM 上卡死。
        for PROC_DIR in /proc/[0-9]*; do
            PID=${PROC_DIR##*/}
            [ -r "$PROC_DIR/exe" ] || continue
            EXE="$(readlink "$PROC_DIR/exe" 2>/dev/null)"
            [ "$EXE" = "$BIN" ] && INDEX_PIDS="$INDEX_PIDS $PID"
        done
    fi
    for PID in $INDEX_PIDS; do
        [ -n "$PID" ] || continue
        EXE="$(readlink "/proc/$PID/exe" 2>/dev/null)"
        if [ "$EXE" = "$BIN" ]; then
            # 接管升级/并发启动前留下的同路径守护进程，不能只把它当作
            # “已运行”而失去看门狗监控。
            if [ -z "$WATCHDOG_DAEMON_PID" ]; then
                WATCHDOG_DAEMON_PID="$PID"
                WATCHDOG_DAEMON_STARTTIME="$(service_watchdog_starttime "$PID")"
            fi
            return 0
        fi
    done
    return 1
}

# 一个看门狗统一管理两个长期运行的子进程，避免留下两个 service.sh shell，
# 同时仍然可以独立重启前台助手和 Rust 守护进程。
daemon_restart_delay() {
	case "$1" in
		1) echo 5 ;;
		2) echo 10 ;;
		3) echo 30 ;;
		*) echo 60 ;;
	esac
}

watch_services() {
    local failure_logged=0 helper_tick=0 daemon_start_ts="" daemon_failures=0 daemon_next_start=0
    local daemon_alive daemon_state current_start exit_code end_ts runtime requested delay now_ts
    while true; do
        if [ "$helper_tick" -eq 0 ]; then
            if start_foreground_helper >/dev/null 2>&1; then
                failure_logged=0
            elif [ "$failure_logged" -eq 0 ]; then
                echo "- 前台助手启动失败：App 使用 UsageStats/cgroup/焦点检测降级，规则健康负向观察暂停" >> "$LOG"
                failure_logged=1
            fi
            helper_tick=1
        else
            helper_tick=0
        fi

        daemon_alive=0
        if [ -n "$WATCHDOG_DAEMON_PID" ]; then
            if [ -r "/proc/$WATCHDOG_DAEMON_PID/stat" ]; then
                daemon_state="$(sed 's/^.*) //' "/proc/$WATCHDOG_DAEMON_PID/stat" 2>/dev/null | awk '{print $1}')"
                current_start="$(service_watchdog_starttime "$WATCHDOG_DAEMON_PID")"
                if [ "$daemon_state" != "Z" ] && [ -n "$WATCHDOG_DAEMON_STARTTIME" ] &&
                    [ "$current_start" = "$WATCHDOG_DAEMON_STARTTIME" ]; then
                    daemon_alive=1
                fi
            fi
            if [ "$daemon_alive" -eq 0 ]; then
                exit_code=0
                wait "$WATCHDOG_DAEMON_PID" 2>/dev/null || exit_code=$?
                end_ts="$(date +%s 2>/dev/null || echo 0)"
                runtime=$((end_ts - daemon_start_ts))
                [ "$runtime" -lt 0 ] && runtime=0
                requested=0
                if [ -f "$RS_RESTART_FLAG" ]; then
                    rm -f "$RS_RESTART_FLAG"
                    requested=1
                    echo "- Rust daemon configuration restart requested" >> "$LOG"
                fi
                if [ "$requested" -eq 1 ]; then
                    daemon_failures=0
                    delay=0
                else
                    [ "$runtime" -ge 60 ] && daemon_failures=0
                    daemon_failures=$((daemon_failures + 1))
                    delay="$(daemon_restart_delay "$daemon_failures")"
                fi
                daemon_next_start=$((end_ts + delay))
                echo "- Rust daemon exited: code=$exit_code runtime=${runtime}s restart_delay=${delay}s" >> "$LOG"
                WATCHDOG_DAEMON_PID=""
                WATCHDOG_DAEMON_STARTTIME=""
                daemon_start_ts=""
            fi
        fi

        now_ts="$(date +%s 2>/dev/null || echo 0)"
        if [ "$daemon_alive" -eq 0 ] && [ "$now_ts" -ge "$daemon_next_start" ]; then
            if is_our_daemon_running; then
                [ -n "$WATCHDOG_DAEMON_PID" ] && [ -z "$daemon_start_ts" ] && daemon_start_ts="$now_ts"
            else
                CPUSET_NAME="$(read_cpuset_name)"
                echo "- 启动 Rust 守护进程: $BIN cpuset=/dev/cpuset/$CPUSET_NAME" >>"$LOG"
                daemon_start_ts="$(date +%s 2>/dev/null || echo 0)"
                "$BIN" -c "$CONF" -s 2 -b "$CPUSET_NAME" >>"$LOG" 2>&1 &
                WATCHDOG_DAEMON_PID=$!
                WATCHDOG_DAEMON_STARTTIME="$(service_watchdog_starttime "$WATCHDOG_DAEMON_PID")"
            fi
        fi
        sleep 5
    done
}

# 默认允许 core_ctl 正常下线空闲核心，避免长期常驻功耗。确有低延迟需求时可在
# calib_policy.conf 显式设置 keep_all_cores_online=1 恢复旧行为。
KEEP_ALL_CORES_ONLINE="$(sed -n 's/^[[:space:]]*keep_all_cores_online[[:space:]]*=[[:space:]]*\([^#[:space:]]*\).*$/\1/p' "$CALIB_POLICY" 2>/dev/null | tail -n 1)"
if [ "$KEEP_ALL_CORES_ONLINE" = "1" ]; then
	for MAX_CPUS in /sys/devices/system/cpu/cpu*/core_ctl/max_cpus; do
		if [ -e "$MAX_CPUS" ] && [ "$(cat "$MAX_CPUS")" != "$(cat "${MAX_CPUS%/*}/min_cpus")" ]; then
			chmod a+w "${MAX_CPUS%/*}/min_cpus"
			cat "$MAX_CPUS" > "${MAX_CPUS%/*}/min_cpus"
			chmod a-w "${MAX_CPUS%/*}/min_cpus"
		fi
	done
	echo "- 已按策略保持全部核心在线" >> "$LOG"
else
	echo "- 低功耗策略：允许 core_ctl 下线空闲核心" >> "$LOG"
fi

# 如需暂停绿厂oiface请将下面这行的#号注释删掉，恢复则将0改成1
# [ -n "$(getprop persist.sys.oiface.enable)" ] && setprop persist.sys.oiface.enable 0

# 如需禁用米系机型joyose请将下面这行pm命令前的#号删掉
# pm disable-user com.xiaomi.joyose; pm clear com.xiaomi.joyose

# 保持单个 service.sh 作为看门狗，不再派生第二个永久 shell。
watch_services
