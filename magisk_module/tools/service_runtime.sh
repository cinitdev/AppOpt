# 仅供 service.sh 用 . 加载函数，不独立启动，不创建常驻 Shell。

wait_sys_boot_completed() {
	local i=9
	until [ "$(getprop sys.boot_completed)" == "1" ] || [ $i -le 0 ]; do
		i=$((i-1))
		sleep 9
	done
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

apply_runtime_policy() {
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
}
