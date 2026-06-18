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
BIN="$MODDIR/AppOpt"
CONF="$MODDIR/applist.conf"
LOG="$MODDIR/AppOpt.log"

# 二进制不存在直接退出
[ -f "$BIN" ] || exit 0
chmod 0755 "$BIN"

# 本次开机先清空日志一次 (只保留本次开机以来的输出)
: > "$LOG"

# 看门狗: 守护进程退出后自动重启, 单实例运行
# 按精确进程名(comm)匹配, 而非 pgrep -f <完整路径>: 后者扫描完整命令行,
# 万一别的进程命令行恰好含该路径会误判"仍存活"而漏重启。comm 名固定为 AppOpt。
(
	while true; do
		if ! pgrep -x AppOpt >/dev/null 2>&1; then
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
