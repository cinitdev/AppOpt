#!/system/bin/sh
# 只接管本模块的普通运行文件更新；系统挂载和启动策略仍交给管理器在重启时处理。
ACTIVE=/data/adb/modules/QixiaThreads
STAGED=/data/adb/modules_update/QixiaThreads
TX=/data/adb/qixia_hot_update
LOCK=/data/adb/qixia_hot_update.lock
RESULT=$ACTIVE/config/state/module_update.state
BOOT=$(cat /proc/sys/kernel/random/boot_id)
umask 077

prop() { sed -n "s/^$1=//p" "$2" 2>/dev/null | head -n 1; }
starttime() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '$1 != "Z" {print $20}'; }
alive() {
	local pid=${1%%:*} rest=${1#*:} stamp boot
	stamp=${rest%%:*}; boot=${rest#*:}
	case "$pid" in ''|*[!0-9]*) return 1 ;; esac
	[ "$boot" = "$rest" ] || [ "$boot" = "$BOOT" ] || return 1
	[ -n "$stamp" ] && [ "$(starttime "$pid")" = "$stamp" ]
}
state_file() { if [ -f "$TX/status" ]; then printf '%s\n' "$TX/status"; else printf '%s\n' "$RESULT"; fi; }
phase() { prop phase "$(state_file)"; }
# 按实际挂载解析 v1/v2 控制组，跟随 init 的后台环境，不硬编码厂商路径或管理器 UID。
runtime_cgroup_targets() {
	awk '
	FILENAME == ARGV[1] { split($0, c, ":"); target[c[1]]=c[3]; next }
	FILENAME == ARGV[2] { split($0, c, ":"); current[c[1]]=c[3]; split(c[2], names, ","); controller[c[1]]=names[1]; next }
	{
		for (s=7; s<=NF && $s!="-"; s++) {}
		if ($(s+1)!="cgroup" && $(s+1)!="cgroup2") next
		for (id in current) {
			if (!(id in target) || current[id]==target[id]) continue
			if (controller[id]=="") { if ($(s+1)!="cgroup2") continue }
			else if ($(s+1)!="cgroup" || index("," $(s+3) ",", "," controller[id] ",")==0) continue
			root=$4; path=target[id]
			if (root!="/" && path!=root && index(path, root "/")!=1) continue
			suffix=(root=="/" ? path : substr(path, length(root)+1))
			if (suffix=="/") suffix=""
			destination[id]=$5 suffix "/cgroup.procs"
		}
	}
	END {
		for (id in current) {
			if (!(id in target)) exit 1
			if (current[id]==target[id]) continue
			if (!(id in destination)) exit 1
			print destination[id]
		}
	}' "$1" "$2" "$3"
}
detach_runtime() {
	local destinations destination
	command -v setsid >/dev/null 2>&1 || return 1
	[ -r /proc/1/cgroup ] && [ -r "/proc/$$/cgroup" ] || return 1
	destinations=$(runtime_cgroup_targets /proc/1/cgroup "/proc/$$/cgroup" "/proc/$$/mountinfo") || return 1
	if [ -n "$destinations" ]; then
		while IFS= read -r destination; do
			# 只移动当前独立 Shell；不解冻管理器，也不更改其他应用的进程组。
			destination=$(printf '%b' "$destination")
			printf '%s\n' "$$" > "$destination" || return 1
		done <<EOF
$destinations
EOF
	fi
	cmp -s /proc/1/cgroup "/proc/$$/cgroup"
}
service_environment_healthy() {
	local owner pid stamp
	owner=$(readlink "$ACTIVE/config/state/service_watchdog.lock")
	pid=${owner%%:*}; stamp=${owner##*:}
	alive "$pid:$stamp" && cmp -s /proc/1/cgroup "/proc/$pid/cgroup"
}
show_result() {
	[ "${SHOW_RESULT:-0}" = 1 ] || return 0
	local message root helper
	case "$1" in
		applied) message='热更新成功，无需重启手机' ;;
		deferred|rollback_failed) message='更新需要重启，请打开柒夏线程查看结果' ;;
		failed) message='热更新尚未完成，请打开柒夏线程查看结果' ;;
		*) return 0 ;;
	esac
	printf '\n============================================\n%s\n%s\n============================================\n' "$message" "$2"
	# 成功时使用新版助手；切换前失败或已回退时，仍可使用暂存模块中的助手。
	for root in "$STAGED" "$ACTIVE"; do
		helper="$root/config/tools/qixia_pkg_helper.sh"
		[ -s "$helper" ] && [ -s "$root/config/tools/qixia_pkg_helper.jar" ] || continue
		if timeout 12 sh "$helper" toast "$message"; then return 0
		else printf 'Toast 助手执行失败，退出码=%s\n' "$?"; fi
		break
	done
	printf '%s\n' '系统 Toast 未能显示，请在柒夏线程内查看更新结果。'
	return 0
}
publish() {
	printf 'phase=%s\nboot=%s\nversion=%s\nbuild=%s\nrequest=%s\nreason=%s\n' "$1" "$BOOT" "$VERSION" "$BUILD" "$REQUEST" "$2" > "$TX/status.tmp" &&
		mv -f "$TX/status.tmp" "$TX/status" || return 1
	persist_result || return 1
	printf '%s\n' "$2"
	show_result "$1" "$2"
}
persist_result() {
	# 事务目录可以清理，App 恢复完成页只需保留这一份小状态。
	# 首次安装尚无生效目录时，仅使用事务状态，不能创建一个空的生效模块。
	[ -d "$ACTIVE/config" ] || return 0
	[ -f "$TX/status" ] || return 0
	mkdir -p "${RESULT%/*}" && cp "$TX/status" "$RESULT.tmp" &&
		mv -f "$RESULT.tmp" "$RESULT"
}
cleanup_completed_update() {
	# 仅事务持有者可以清理，失败、中断和待安装 APK 均保留重试资料。
	[ -n "$OWNER" ] && [ "$(readlink "$LOCK")" = "$OWNER" ] || return 1
	case "$(phase)" in applied|rebooted) ;; *) return 0 ;; esac
	[ ! -e "$STAGED" ] && [ ! -e "$ACTIVE/update" ] && [ ! -d "$ACTIVE/config/app" ] || return 0
	local state=$(state_file) file
	[ "$(prop version "$state")" = "$(prop versionCode "$ACTIVE/module.prop")" ] || return 0
	[ "$(prop build "$state")" = "$(sha256sum "$ACTIVE/config/bin/QiXiaRs" | cut -d ' ' -f 1)" ] || return 0
	persist_result || return 1
	# 日志归入已有日志目录；脚本与备份不再作为长期运行依赖。
	mkdir -p "$ACTIVE/logs" || return 1
	for file in "$TX/update.log" "$TX/app-update.log"; do
		[ ! -f "$file" ] || tail -n 200 "$file" >> "$ACTIVE/logs/HotUpdate.log" || return 1
	done
	rm -f "$ACTIVE/config/tools/qixia_pkg_helper.jar" "$ACTIVE/config/tools/qixia_pkg_helper.sh" \
		"$ACTIVE/config/tools/qixia_hot_update.sh" && rm -rf "$TX"
}
unlock() { [ "$(readlink "$LOCK")" != "$OWNER" ] || rm -f "$LOCK"; }
take_lock() {
	OWNER="$$:$(starttime $$):$BOOT"
	if ! ln -s "$OWNER" "$LOCK" 2>/dev/null; then
		local previous=$(readlink "$LOCK")
		alive "$previous" && return 1
		# 恢复锁只保护失效锁的回收，避免两个启动者同时删除对方的新锁。
		mkdir "$TX/recover.lock" 2>/dev/null || return 1
		if [ "$(readlink "$LOCK")" = "$previous" ]; then rm -f "$LOCK"; fi
		rmdir "$TX/recover.lock"
		ln -s "$OWNER" "$LOCK" 2>/dev/null || return 1
	fi
	trap 'unlock' EXIT
	trap 'exit 1' HUP INT TERM
}

runtime_files() {
	local root=$1 file
	for file in service.sh module.prop uninstall.sh customize.sh; do
		[ ! -f "$root/$file" ] || printf '%s\n' "$file"
	done
	for file in "$root"/config/bin/QiXiaRs "$root"/config/ebpf/*.bpf.o \
		"$root"/config/tools/*.sh "$root"/config/tools/*.jar \
		"$root"/config/app/*.apk "$root"/config/app/app.prop; do
		[ ! -f "$file" ] || printf '%s\n' "${file#"$root/"}"
	done
}
runtime_manifest() {
	local root=$1 file
	runtime_files "$root" | LC_ALL=C sort | while IFS= read -r file; do
		[ ! -L "$root/$file" ] || return 1
		printf '%s %s\n' "$(sha256sum "$root/$file" | cut -d ' ' -f 1)" "$file"
	done
}
boot_manifest() {
	# 将目录结构、链接目标、文件内容一起比较，不能忽略 vendor 重排或空覆盖文件。
	(cd "$1" || exit 1
	 for file in system vendor product system_ext odm oem system.prop sepolicy.rule \
		post-fs-data.sh post-mount.sh boot-completed.sh skip_mount; do
		[ -e "$file" ] || [ -L "$file" ] || continue
		find "$file" | LC_ALL=C sort | while IFS= read -r node; do
			if [ -L "$node" ]; then printf 'L %s %s\n' "$node" "$(readlink "$node")"
			elif [ -f "$node" ]; then printf 'F %s %s\n' "$node" "$(sha256sum "$node" | cut -d ' ' -f 1)"
			elif [ -d "$node" ]; then printf 'D %s\n' "$node"
			else exit 1; fi
		done || exit 1
	 done)
}
eligible() {
	[ "$(prop id "$ACTIVE/module.prop")" = QixiaThreads ] || { REASON='首次安装需要重启以完成模块初始化'; return 1; }
	[ -s "$ACTIVE/service.sh" ] && [ -x "$ACTIVE/config/bin/QiXiaRs" ] || { REASON='当前模块尚未初始化，需要重启'; return 1; }
	[ "$(prop id "$STAGED/module.prop")" = QixiaThreads ] || { REASON='未找到独立的完整暂存模块，保留管理器的重启流程'; return 1; }
	for file in disable remove; do
		[ ! -e "$ACTIVE/$file" ] && [ ! -e "$STAGED/$file" ] || { REASON='模块已停用或待移除，不自动启动'; return 1; }
	done
	for file in service.sh config/bin/QiXiaRs config/ebpf/queuebuffer_probe.bpf.o \
		config/ebpf/queuebuffer_probe_perf.bpf.o config/ebpf/queuebuffer_probe_stats.bpf.o \
		config/tools/qixia_foreground_helper.sh config/tools/qixia_foreground_helper.jar; do
		[ -s "$STAGED/$file" ] && [ ! -L "$STAGED/$file" ] || { REASON="暂存文件缺失或无效：$file"; return 1; }
	done
	boot_manifest "$ACTIVE" > "$TX/boot.old" && boot_manifest "$STAGED" > "$TX/boot.new" &&
		cmp -s "$TX/boot.old" "$TX/boot.new" || { REASON='系统挂载、属性或 SELinux 策略有变化，需要重启'; return 1; }
	case "$(cat "$ACTIVE/config/calibrate.state" 2>/dev/null)" in
		sampling*) REASON='正在手动校准，为保留本次采集，本次更新需要重启生效'; return 1 ;;
	esac
	return 0
}
stop_service() {
	local owner pid stamp waited=0
	owner=$(readlink "$ACTIVE/config/state/service_watchdog.lock")
	pid=${owner%%:*}; stamp=${owner##*:}
	if alive "$pid:$stamp"; then
		tr '\000' '\n' < "/proc/$pid/cmdline" | grep -Fxq "$ACTIVE/service.sh" || return 1
		kill -TERM "$pid" || return 1
		while alive "$pid:$stamp" && [ "$waited" -lt 35 ]; do sleep 1; waited=$((waited + 1)); done
		alive "$pid:$stamp" && return 1
	fi
	# service 的正常退出负责落盘和释放接管；还活着时不替换正在使用的程序文件。
	# 旧助手在找不到进程时会逐个启动 shell 扫描 /proc；升级时直接复用新版停止逻辑。
	local helper_root=$ACTIVE remaining
	if [ -f "$STAGED/config/tools/qixia_foreground_helper.sh" ] && [ -x "$STAGED/config/bin/QiXiaRs" ]; then
		helper_root=$STAGED
	fi
	remaining=$("$helper_root/config/bin/QiXiaRs" --find-pid QiXiaRs 2>/dev/null) || return 1
	for pid in $remaining; do
		[ "$(readlink "/proc/$pid/exe" 2>/dev/null)" != "$ACTIVE/config/bin/QiXiaRs" ] || return 1
	done
	sh "$helper_root/config/tools/qixia_foreground_helper.sh" stop >/dev/null 2>&1 || true
}
start_service() {
	detach_runtime || return 1
	setsid sh "$ACTIVE/service.sh" --hot-update </dev/null >> "$ACTIVE/logs/HotUpdate.log" 2>&1 &
}
copy_file() {
	local source=$1 destination=$2
	mkdir -p "${destination%/*}" && cp -p "$source" "$destination.hot-new" &&
		cmp -s "$source" "$destination.hot-new" && mv -f "$destination.hot-new" "$destination"
}
backup_runtime() {
	(runtime_files "$ACTIVE"; runtime_files "$STAGED") | LC_ALL=C sort -u > "$TX/files"
	while IFS= read -r file; do
		if [ -f "$ACTIVE/$file" ]; then
			[ ! -L "$ACTIVE/$file" ] && copy_file "$ACTIVE/$file" "$TX/backup/$file" || return 1
		fi
	done < "$TX/files"
}
merge_policy_defaults() {
	if [ -f "$ACTIVE/config/calib_policy.conf" ] && [ -f "$STAGED/config/calib_policy.conf" ]; then
		# 新版本只补缺失键；刷入前后的用户选择始终以生效目录为准。
		awk '
		{ key=$0; sub(/#.*/, "", key); if (index(key,"=") == 0) key="";
		  else { sub(/=.*/, "", key); gsub(/^[[:space:]]+|[[:space:]]+$/, "", key) } }
		FILENAME == ARGV[1] { print; if (key != "") seen[key]=1; next }
		key != "" && !seen[key]++ { print }
		' "$ACTIVE/config/calib_policy.conf" "$STAGED/config/calib_policy.conf" > "$ACTIVE/config/calib_policy.hot-new" &&
			chmod 0644 "$ACTIVE/config/calib_policy.hot-new" &&
			mv -f "$ACTIVE/config/calib_policy.hot-new" "$ACTIVE/config/calib_policy.conf" || return 1
	fi
}
restore_runtime() {
	[ -f "$TX/files" ] || return 1
	while IFS= read -r file; do
		if [ -f "$TX/backup/$file" ]; then
			copy_file "$TX/backup/$file" "$ACTIVE/$file" || return 1
		else rm -f "$ACTIVE/$file" || return 1; fi
	done < "$TX/files"
}
healthy() {
	local tries=0 stable=0
	while [ "$tries" -lt 20 ]; do
		if service_environment_healthy && timeout 5 "$ACTIVE/config/bin/QiXiaRs" --check-daemon &&
			sh "$ACTIVE/config/tools/qixia_foreground_helper.sh" status | grep -q '^running '; then
			stable=$((stable + 1)); [ "$stable" -lt 3 ] || return 0
		else stable=0; fi
		tries=$((tries + 1)); sleep 1
	done
	return 1
}
old_healthy() {
	local tries=0 pid expected=$(stat -c '%d:%i' "$ACTIVE/config/bin/QiXiaRs")
	# 回退的旧版可能仍通过 shell 枚举进程，给其完整的有界启动时间。
	while [ "$tries" -lt 120 ]; do
		for pid in $("$ACTIVE/config/bin/QiXiaRs" --find-pid QiXiaRs 2>/dev/null); do
			[ "$(stat -Lc '%d:%i' /proc/$pid/exe 2>/dev/null)" = "$expected" ] || continue
			tr '\000' '\n' < /proc/$pid/cmdline | grep -Fxq "$ACTIVE/config/applist.conf" || continue
			sh "$ACTIVE/config/tools/qixia_foreground_helper.sh" status | grep -q '^running ' && return 0
		done
		tries=$((tries + 1)); sleep 1
	done
	return 1
}
recover() {
	local state=$(state_file) previous_phase=$(phase)
	case "$previous_phase" in switching|checking|committing|rollback_failed|deferred|applied|rebooted) ;;
		*) return 0 ;; esac
	if [ "$(prop boot "$state")" != "$BOOT" ]; then
		# 跨启动不能恢复旧备份；确认管理器已提升目标文件后，再允许收尾清理。
		if [ ! -e "$STAGED" ] && [ ! -e "$ACTIVE/update" ] &&
			[ "$(prop version "$state")" = "$(prop versionCode "$ACTIVE/module.prop")" ] &&
			[ "$(prop build "$state")" = "$(sha256sum "$ACTIVE/config/bin/QiXiaRs" | cut -d ' ' -f 1)" ]; then
			publish rebooted '系统已重启，由模块管理器应用完整更新' || return 1
		fi
		return 0
	fi
	case "$previous_phase" in switching|checking|committing|rollback_failed) ;; *) return 0 ;; esac
	if [ -d "$TX/consumed" ] && [ ! -e "$STAGED" ]; then mv "$TX/consumed" "$STAGED" || return 1; fi
	stop_service && restore_runtime || { publish rollback_failed '旧文件恢复失败，保留备份，请重启应用完整模块'; return 1; }
	start_service
	old_healthy || { publish rollback_failed '旧运行文件已恢复，但服务尚未恢复，请重启完成更新'; return 1; }
	publish deferred '热更新被中断，已恢复旧运行文件；更新仍可重启生效'
}
apply_update() {
	local waiters=$1 token waited=0 file
	# 必须在等待安装器退出前脱离它的控制组，否则管理器退后台后整个事务都会被冻结。
	detach_runtime || return 1
	# customize 被 source 执行，必须等安装 shell 和所属管理器命令均结束。
	for token in $waiters; do
		while alive "$token"; do
			[ "$waited" -lt 180 ] || return 1
			sleep 1; waited=$((waited + 1))
		done
	done
	mkdir -p "$TX" && chmod 0700 "$TX" || return 1
	take_lock || return 0
	[ -d "$STAGED" ] || { recover; return $?; }
	VERSION=$(prop versionCode "$STAGED/module.prop")
	BUILD=$(sha256sum "$STAGED/config/bin/QiXiaRs" 2>/dev/null | cut -d ' ' -f 1)
	recover || return 1
	eligible || { publish deferred "$REASON"; return 0; }
	# 备份只含程序文件，不包括用户规则、状态、日志和历史。
	rm -rf "$TX/backup"
	runtime_manifest "$STAGED" > "$TX/new.manifest" || return 1
	backup_runtime || { publish deferred '备份失败，保留原服务和待更新模块'; return 1; }
	publish switching '正在结束旧服务并切换程序文件' || return 1
	if ! stop_service; then
		start_service
		publish deferred '旧服务未能安全退出，本次保留重启更新'
		return 0
	fi
	local copied=1
	while IFS= read -r file; do
		[ "$file" != module.prop ] || continue
		if [ -f "$STAGED/$file" ]; then
			copy_file "$STAGED/$file" "$ACTIVE/$file" || { copied=0; break; }
		else rm -f "$ACTIVE/$file" || { copied=0; break; }; fi
	done < "$TX/files"
	# 记录同一次开机的 App 更新交接，热重启服务不能提前结束刷入界面。
	merge_policy_defaults || copied=0
	if [ -d "$ACTIVE/config/app" ]; then printf '%s\n' "$BOOT" > "$ACTIVE/config/app/.defer_boot"; fi
	if [ "$copied" = 1 ]; then
		publish checking '新版文件已就绪，正在验证 Rust 守护和前台助手'
		start_service
		if healthy && copy_file "$STAGED/module.prop" "$ACTIVE/module.prop"; then
			runtime_manifest "$ACTIVE" > "$TX/active.manifest"
			if cmp -s "$TX/new.manifest" "$TX/active.manifest"; then
				publish committing '新版服务已通过检查，正在完成更新'
				# 原子移走本模块暂存目录，避免下次重启用旧配置覆盖当前配置。
				if mv "$STAGED" "$TX/consumed" && rm -f "$ACTIVE/update"; then
					publish applied '模块热更新已生效，无需重启手机' || return 1
					rm -rf "$TX/backup" "$TX/consumed"
					cleanup_completed_update || printf '%s\n' '热更新已生效，临时文件清理未完成，下次启动重试'
					return 0
				fi
			fi
		fi
	fi
	# 保留暂存安装供重启使用，任何一步失败都恢复原程序。
	[ ! -d "$TX/consumed" ] || mv "$TX/consumed" "$STAGED"
	recover
}

installer_waiters() {
	local parent="$1" depth=0 waiters="" stamp command
	case "$parent" in ''|*[!0-9]*) return 1 ;; esac
	while [ "$parent" -gt 1 ] && [ "$depth" -lt 9 ]; do
		stamp=$(starttime "$parent")
		[ -n "$stamp" ] || break
		command=$(tr '\000' ' ' < "/proc/$parent/cmdline" 2>/dev/null)
		if [ "$depth" -eq 0 ]; then
			waiters="$parent:$stamp"
		else
			case "$command" in
				*'module install'*|*'--install-module'*|*'update-binary'*|*'install_module'*)
					waiters="$waiters $parent:$stamp" ;;
			esac
		fi
		parent=$(sed 's/^.*) //' "/proc/$parent/stat" 2>/dev/null | awk '{print $2}')
		case "$parent" in ''|*[!0-9]*) break ;; esac
		depth=$((depth + 1))
	done
	[ -n "$waiters" ] || return 1
	printf '%s\n' "$waiters"
}

queue_update() {
	# 独立副本保证替换 service/tools 时工作进程不会读到半个新脚本。
	mkdir -p "$TX" && chmod 0700 "$TX" || return 1
	# 排队命令本身就是独立 Shell，先同步完成隔离，失败时保留原服务和重启更新。
	detach_runtime || { printf '%s\n' '无法建立独立后台环境，保留重启更新' >&2; return 1; }
	if alive "$(readlink "$LOCK")"; then
		printf 'request=%s\n' "$(prop request "$(state_file)")"
		return 0
	fi
	cp "$0" "$TX/worker.$$.new" && mv -f "$TX/worker.$$.new" "$TX/worker.sh" || return 1
	REQUEST="$BOOT-$$-$(starttime $$)"
	setsid sh "$TX/worker.sh" --apply "$1" "$REQUEST" "${2:-0}" </dev/null >> "$TX/update.log" 2>&1 &
	printf 'request=%s\n' "$REQUEST"
}

case "$1" in
	--apply)
		REQUEST=$3; SHOW_RESULT=${4:-0}
		if ! apply_update "$2"; then
			# 等待安装器超时、状态写入失败等早期错误也需提示，不能沿用上次成功结果。
			case "$(prop request "$(state_file)"):$(phase)" in
				"$REQUEST:applied"|"$REQUEST:deferred"|"$REQUEST:rollback_failed") ;;
				*) show_result failed '本次热更新尚未确认完成，请打开柒夏线程检查；必要时重启生效。' ;;
			esac
			exit 1
		fi
		;;
	--queue) queue_update "$2" 1 ;;
	--queue-installer)
		WAITERS=$(installer_waiters "$2") || exit 1
		queue_update "$WAITERS" 1
		;;
	--recover)
		[ -f "$(state_file)" ] || exit 0
		# 普通服务不能在切换尚未完成时并发启动；工作进程使用 --hot-update 启动。
		alive "$(readlink "$LOCK")" && exit 75
		take_lock || exit 1
		VERSION=$(prop version "$(state_file)"); BUILD=$(prop build "$(state_file)"); REQUEST=$(prop request "$(state_file)")
		recover || exit 1
		cleanup_completed_update || true
		;;
	--status) cat "$(state_file)" 2>/dev/null ;;
	--cleanup)
		[ -d "$TX" ] || exit 0
		alive "$(readlink "$LOCK")" && exit 0
		take_lock || exit 1
		cleanup_completed_update
		;;
	--queue-app)
		[ "$(phase)" = applied ] && [ -f "$ACTIVE/config/app/app.prop" ] || exit 1
		detach_runtime || exit 1
		setsid sh "$0" --install-app </dev/null >> "$TX/app-update.log" 2>&1 &
		;;
	--install-app)
		sleep 3
		# 用户可能在成功提示刚出现时立即点完成，等模块事务释放锁后再安装 APK。
		WAITED=0
		while alive "$(readlink "$LOCK")" && [ "$WAITED" -lt 30 ]; do sleep 1; WAITED=$((WAITED + 1)); done
		take_lock || exit 1
		[ "$(phase)" = applied ] && [ ! -e "$STAGED" ] || exit 1
		sh "$ACTIVE/service.sh" --install-app || exit 1
		echo 'App 已更新，请从桌面重新打开。'
		cleanup_completed_update
		;;
	*) echo '用法：--queue | --queue-installer 安装器PID | --status | --queue-app'; exit 2 ;;
esac
