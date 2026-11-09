# 仅供 service.sh 用 . 加载函数，不独立启动，不创建常驻 Shell。

# Joyose 的探测、策略写入及组件操作；由 service.sh 统一提供短命令入口。
joyose_policy_value() {
	sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\([^#[:space:]]*\).*$/\1/p" "$JOY_POLICY" 2>/dev/null | tail -n 1
}

joyose_component_matches() {
	printf '%s\n' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' |
		grep -Fqx -e "$JOY_COMPONENT" -e 'com.xiaomi.joyose/com.xiaomi.joyose.smartop.SmartOpService'
}

joyose_probe() {
	local info services
	JOY_AVAILABLE=0; JOY_DISABLED=unknown; JOY_STATE=unknown; JOY_REASON=unsupported_system
	[ -n "$(getprop ro.miui.ui.version.code)$(getprop ro.miui.ui.version.name)$(getprop ro.mi.os.version.code)$(getprop ro.mi.os.version.name)" ] || return 0
	JOY_REASON=state_unreadable
	# 共享系统 UID 的包信息可能超过单个命令参数上限，直接流式解析，不存入 Shell 参数。
	# 保留 dumpsys 退出状态，失败或超时不能当成组件缺失。
	info=$({ timeout 3 dumpsys package com.xiaomi.joyose 2>/dev/null; printf "\nQIXIA_JOYOSE_DUMP_EXIT=%s\n" "$?"; } | awk '
	{
		if ($0 ~ /^QIXIA_JOYOSE_DUMP_EXIT=/) { split($0, result, "="); dump_ok=(result[2]=="0"); next }
		line=$0; sub(/^[[:space:]]*/, "", line); sub(/[[:space:]]*$/, "", line)
		if (line == "Packages:") { packages=1; next }
		if ($0 ~ /^[^[:space:]]/ && line != "Packages:") { packages=0; pkg=0; user=0 }
		if (packages && line ~ /^Package \[/) {
			pkg=(index(line,"Package [com.xiaomi.joyose] (")==1); user=0
		}
		if (!pkg) next
		if (line ~ /^User [0-9]+:/) {
			user=($1 == "User" && $2 == "0:"); section=""
			if (user) {
				seen=1; state="default"
				for (i=3; i<=NF; i++) { split($i, pair, "="); values[pair[1]]=pair[2] }
			}
			next
		}
		if (!user) next
		if (line == "enabledComponents:") { section="enabled"; next }
		if (line == "disabledComponents:") { section="disabled"; next }
		if (line ~ /:$/) { section=""; next }
		if (line == "com.xiaomi.joyose.smartop.SmartOpService" || line == ".smartop.SmartOpService") {
			if (section != "") state=section
		}
	}
	END {
		if (!dump_ok) exit 1
		if (!seen || values["installed"] != "true") print "missing unknown"
		else if (values["hidden"] == "true" || values["suspended"] == "true" ||
			(values["enabled"] != "0" && values["enabled"] != "1")) print "unavailable unknown"
		else print "ready " state
	}') || return 1
	case "$info" in
		'ready default'|'ready enabled'|'ready disabled') JOY_STATE=${info#ready } ;;
		*) JOY_REASON=package_unavailable; return 0 ;;
	esac
	# 显式组件查询包含禁用组件及两种直接启动状态，服务没有 intent-filter 也能识别。
	services=$(timeout 3 cmd package query-services --user 0 --components --query-flags 786944 -n "$JOY_COMPONENT" 2>/dev/null) || return 1
	joyose_component_matches "$services" || { JOY_REASON=component_missing; return 0; }
	case "$JOY_STATE" in
		disabled) JOY_DISABLED=1 ;;
		enabled) JOY_DISABLED=0 ;;
		default)
			services=$(timeout 3 cmd package query-services --user 0 --components --query-flags 786432 -n "$JOY_COMPONENT" 2>/dev/null) || return 1
			if joyose_component_matches "$services"; then JOY_DISABLED=0
			elif printf '%s\n' "$services" | grep -qi '^No services found$'; then JOY_DISABLED=1
			else return 1; fi ;;
	esac
	JOY_AVAILABLE=1; JOY_REASON=ready
}

joyose_write_policy() {
	local input=/dev/null
	if [ -e "$JOY_POLICY" ] || [ -L "$JOY_POLICY" ]; then
		[ -f "$JOY_POLICY" ] && [ ! -L "$JOY_POLICY" ] && [ -r "$JOY_POLICY" ] || return 1
		input=$JOY_POLICY
	fi
	awk -v disabled="$JOY_REQUESTED" -v original="$JOY_ORIGINAL" '
	BEGIN {
		values["joyose_smartop_disabled"]=disabled
		if (original != "") values["joyose_smartop_original_state"]=original
	}
	{
		clean=$0; sub(/#.*/, "", clean); split_at=index(clean,"="); key=substr(clean,1,split_at-1)
		gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
		if (split_at && key in values) {
			if (!written[key]++) print key "=" values[key]
		} else print
	}
	END { for (key in values) if (!written[key]) print key "=" values[key] }
	' "$input" > "$JOY_TMP" || return 1
	if [ -f "$JOY_POLICY" ] && cmp -s "$JOY_TMP" "$JOY_POLICY"; then rm -f "$JOY_TMP"; return 0; fi
	chmod 0644 "$JOY_TMP" && mv -f "$JOY_TMP" "$JOY_POLICY"
}

joyose_output() {
	printf 'version=1\nok=%s\navailable=%s\ndisabled=%s\nrequested=%s\nreason=%s\n' "$1" "$JOY_AVAILABLE" "$JOY_DISABLED" "$JOY_REQUESTED" "$JOY_REASON"
}

joyose_run() (
	JOY_POLICY="$MODDIR/config/calib_policy.conf"
	JOY_COMPONENT='com.xiaomi.joyose/.smartop.SmartOpService'
	JOY_LOCK="$JOY_POLICY.lock"; JOY_TMP="$JOY_POLICY.joyose.$$.tmp"
	JOY_AVAILABLE=0; JOY_DISABLED=unknown; JOY_REASON=state_unreadable
	JOY_REQUESTED=$(joyose_policy_value joyose_smartop_disabled)
	case "$JOY_REQUESTED" in 0|1) ;; *) JOY_REQUESTED=1 ;; esac
	if [ "$1" = --joyose-status ]; then
		if joyose_probe; then joyose_output 1; else joyose_output 0; exit 1; fi
		exit 0
	fi
	if [ "$1" = --joyose-set ]; then
		case "$2" in 0|1) ;; *) JOY_REASON=invalid_value; joyose_output 0; exit 1 ;; esac
		if [ -e /data/adb/modules_update/QixiaThreads/module.prop ] || [ -e "$MODDIR/update" ] || [ -e "$MODDIR/remove" ]; then
			JOY_REASON=pending_update; joyose_output 0; exit 1
		fi
	fi
	# 与 App 设置保存、Rust 拓扑补齐共用目录锁，防止覆盖其他策略键。
	mkdir -p "$MODDIR/config" || exit 1
	waited=0
	while ! mkdir "$JOY_LOCK" 2>/dev/null; do
		now=$(date +%s); mtime=$(stat -c %Y "$JOY_LOCK" 2>/dev/null || printf 0)
		if [ "$mtime" -gt 0 ] && [ "$now" -gt "$((mtime + 30))" ]; then
			rmdir "$JOY_LOCK" 2>/dev/null && continue
		fi
		waited=$((waited + 1))
		[ "$waited" -lt 5 ] || { JOY_REASON=policy_busy; joyose_output 0; exit 1; }
		sleep 1
	done
	trap 'rm -f "$JOY_TMP"; rmdir "$JOY_LOCK" 2>/dev/null || true' EXIT
	trap 'exit 1' HUP INT TERM
	JOY_REQUESTED=$(joyose_policy_value joyose_smartop_disabled)
	case "$JOY_REQUESTED" in 0|1) ;; *) JOY_REQUESTED=1 ;; esac
	JOY_ORIGINAL=$(joyose_policy_value joyose_smartop_original_state)
	case "$JOY_ORIGINAL" in default|enabled|disabled) ;; *) JOY_ORIGINAL='' ;; esac
	# 卸载先写 remove 再恢复；即使启动任务已经在等锁，也不能随后再次禁用组件。
	if [ "$1" != --joyose-restore ] && [ -e "$MODDIR/remove" ]; then
		JOY_REASON=pending_update; joyose_output 0; exit 1
	fi
	# 首次升级继承当前柒夏线程安装器已保存的恢复状态，避免把模块禁用误当成用户原始状态。
	if [ "$1" = --joyose-init ] && [ -z "$JOY_ORIGINAL" ] && [ -n "$2" ] &&
		[ "$(sed -n 's/^id=//p' "$2/module.prop" 2>/dev/null)" = QixiaThreads ]; then
		JOY_ORIGINAL=$(sed -n 's/^# QIXIA_JOYOSE_SMARTOP_ORIGINAL=\(default\|enabled\)$/\1/p' "$2/uninstall.sh" 2>/dev/null | head -n 1)
	fi
	# 包管理器暂不可用时也不能丢失升级前已经承担的恢复责任。
	if [ "$1" = --joyose-init ]; then
		joyose_write_policy || { JOY_REASON=policy_write_failed; joyose_output 0; exit 1; }
	fi
	joyose_probe || { joyose_output 0; exit 1; }
	if [ "$1" = --joyose-restore ]; then
		if [ "$JOY_AVAILABLE" != 1 ] || [ -z "$JOY_ORIGINAL" ]; then joyose_output 1; exit 0; fi
		target=$JOY_ORIGINAL
		case "$target" in default) action=default-state ;; enabled) action=enable ;; disabled) action=disable ;; esac
		if [ "$JOY_STATE" != "$target" ]; then
			timeout 5 pm "$action" --user 0 "$JOY_COMPONENT" >/dev/null 2>&1 || { JOY_REASON=restore_failed; joyose_output 0; exit 1; }
			joyose_probe || { joyose_output 0; exit 1; }
		fi
		[ "$JOY_AVAILABLE" = 1 ] && [ "$JOY_STATE" = "$target" ] || { JOY_REASON=restore_failed; joyose_output 0; exit 1; }
		joyose_output 1; exit 0
	fi
	if [ "$1" = --joyose-set ]; then
		[ "$JOY_AVAILABLE" = 1 ] || { joyose_output 0; exit 1; }
		JOY_REQUESTED=$2
	fi
	[ "$JOY_AVAILABLE" != 1 ] || [ -n "$JOY_ORIGINAL" ] || JOY_ORIGINAL=$JOY_STATE
	joyose_write_policy || { JOY_REASON=policy_write_failed; joyose_output 0; exit 1; }
	if [ "$JOY_AVAILABLE" != 1 ]; then joyose_output 1; exit 0; fi
	if [ "$JOY_DISABLED" != "$JOY_REQUESTED" ]; then
		if [ "$JOY_REQUESTED" = 1 ]; then action=disable; else action=enable; fi
		if ! timeout 5 pm "$action" --user 0 "$JOY_COMPONENT" >/dev/null 2>&1; then
			joyose_probe || true
			JOY_REASON=apply_failed; joyose_output 0; exit 1
		fi
		joyose_probe || { joyose_output 0; exit 1; }
	fi
	[ "$JOY_AVAILABLE" = 1 ] && [ "$JOY_DISABLED" = "$JOY_REQUESTED" ] || { JOY_REASON=apply_failed; joyose_output 0; exit 1; }
	joyose_output 1
)
