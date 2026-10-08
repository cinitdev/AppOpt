SKIPUNZIP=0
QIXIA_IN_APP_UPDATE_MARKER="config/app/.qixia_in_app_update"
QIXIA_IN_APP_UPDATE_FLAG="/data/adb/qixia_in_app_update"
EMBEDDED_APP_CLEANUP_ALLOWED=1
# 更新时保留当前柒夏线程模块的配置和待处理数据。
QIXIA_UPGRADE_SOURCE=""
if [ "$(sed -n 's/^id=//p' /data/adb/modules/QixiaThreads/module.prop 2>/dev/null)" = "QixiaThreads" ] &&
	[ "$(readlink -f "$MODPATH")" != "$(readlink -f /data/adb/modules/QixiaThreads)" ]; then
	QIXIA_UPGRADE_SOURCE="/data/adb/modules/QixiaThreads"
fi
is_qixia_in_app_update() {
	[ "$QIXIA_IN_APP_UPDATE" = "1" ] && return 0
	[ -n "$MODPATH" ] && [ -f "$MODPATH/$QIXIA_IN_APP_UPDATE_MARKER" ] && return 0
	[ -f "$QIXIA_IN_APP_UPDATE_FLAG" ] && return 0
	return 1
}
print_manual_migration_notice() {
	ui_print "********************************************"
	ui_print "旧版迁移提醒（从 AppOpt 切换到柒夏线程）"
	ui_print "! 请先手动备份旧版 applist.conf，再处理旧版 APK 和模块。"
	ui_print "! 旧版 APK 请自行卸载，旧模块请自行停用或卸载。"
	ui_print "! 请勿同时启用旧版与新版模块，以免调度冲突。"
	ui_print "! 本安装器不自动迁移旧版 AppOpt 的规则和数据。"
	ui_print "! 新模块生效后，请将备份的 applist.conf 手动复制到："
	ui_print "/data/adb/modules/QixiaThreads/config/applist.conf"
	ui_print "! 复制后重新打开柒夏线程，按当前设置同步规则格式。"
	ui_print "********************************************"
}
check_magisk_version() {
	ui_print "- Magisk version: $MAGISK_VER_CODE"
	ui_print "- Module version: $(grep_prop version "${TMPDIR}/module.prop")"
	ui_print "- Module versionCode: $(grep_prop versionCode "${TMPDIR}/module.prop")"
	ui_print "********************************************"
	ui_print "- $(grep_prop description "${TMPDIR}/module.prop")"
	if [ "$MAGISK_VER_CODE" -lt 20400 ]; then
		ui_print "********************************************"
		ui_print "! 请安装 Magisk v20.4+ (20400+)"
		abort "********************************************"
	fi
}

check_required_files() {
	REQUIRED_FILE_LIST="/sys/devices/system/cpu/present /proc/loadavg"
	for REQUIRED_FILE in $REQUIRED_FILE_LIST; do
		if [ ! -e "$REQUIRED_FILE" ]; then
			ui_print "********************************************"
			ui_print "! $REQUIRED_FILE 文件不存在"
			ui_print "! 请检查设备环境"
			abort "********************************************"
		fi
	done
}
extract_bin() {
	ui_print "********************************************"
	mkdir -p $MODPATH/config/bin
	if [ "$ARCH" == "arm" ]; then
		BIN_ABI_DIR="armeabi-v7a"
	elif [ "$ARCH" == "arm64" ]; then
		BIN_ABI_DIR="arm64-v8a"
	elif [ "$ARCH" == "x86" ]; then
		BIN_ABI_DIR="x86"
	elif [ "$ARCH" == "x64" ]; then
		BIN_ABI_DIR="x86_64"
	else
		abort "! Unsupported platform: $ARCH"
	fi
	[ -f $MODPATH/config/bin/$BIN_ABI_DIR/QiXiaRs ] \
		|| abort "! 缺少 Rust 守护进程: $BIN_ABI_DIR/QiXiaRs"
	cp $MODPATH/config/bin/$BIN_ABI_DIR/QiXiaRs $MODPATH/config/bin/QiXiaRs \
		|| abort "! 安装 Rust 守护进程失败"
	[ -d "$MODPATH/config/ebpf/$BIN_ABI_DIR" ] || abort "! 缺少 eBPF ABI 目录: $BIN_ABI_DIR"
	cp "$MODPATH/config/ebpf/$BIN_ABI_DIR/queuebuffer_probe.bpf.o" "$MODPATH/config/ebpf/queuebuffer_probe.bpf.o" 2>/dev/null \
		|| abort "! 安装 queueBuffer RingBuf eBPF 对象失败"
	cp "$MODPATH/config/ebpf/$BIN_ABI_DIR/queuebuffer_probe_stats.bpf.o" "$MODPATH/config/ebpf/queuebuffer_probe_stats.bpf.o" 2>/dev/null \
		|| abort "! 安装 queueBuffer StatsMap eBPF 对象失败"
	cp "$MODPATH/config/ebpf/$BIN_ABI_DIR/queuebuffer_probe_perf.bpf.o" "$MODPATH/config/ebpf/queuebuffer_probe_perf.bpf.o" 2>/dev/null \
		|| abort "! 安装 queueBuffer PerfEvent eBPF 对象失败"
	ui_print "- Device platform: $ARCH"
	rm -rf $MODPATH/config/bin/armeabi-v7a $MODPATH/config/bin/arm64-v8a $MODPATH/config/bin/x86 $MODPATH/config/bin/x86_64
	rm -rf $MODPATH/config/ebpf/armeabi-v7a $MODPATH/config/ebpf/arm64-v8a $MODPATH/config/ebpf/x86 $MODPATH/config/ebpf/x86_64
	chmod a+x $MODPATH/config/bin/QiXiaRs
	if ! $MODPATH/config/bin/QiXiaRs -v; then
		abort "! Rust 守护验证失败，请检查模块 zip 文件或设备架构"
	fi
	ui_print "- Rust 守护验证通过: QiXiaRs"
}

run_pkg_helper() {
	local OUT="$1"
	shift
	local HELPER="$MODPATH/config/tools/qixia_pkg_helper.sh"
	local HELPER_DIR="$MODPATH/config/tools"
	if [ ! -f "$HELPER" ]; then
		echo "ok=0" > "$OUT"
		echo "error=找不到内置安装器脚本" >> "$OUT"
		return 1
	fi
	QIXIA_HELPER_DIR="$HELPER_DIR" \
	QIXIA_PACKAGE="${QIXIA_PACKAGE:-}" \
	QIXIA_VERSION_CODE="${QIXIA_VERSION_CODE:-}" \
	QIXIA_VERSION_NAME="${QIXIA_VERSION_NAME:-}" \
	sh "$HELPER" "$@" > "$OUT" 2>&1
	return $?
}

print_helper_error() {
	local TITLE="$1"
	local OUT="$2"
	ui_print "- $TITLE"
	[ -f "$OUT" ] || return
	# 厂商框架的警告可能先于安装助手的真实错误输出。
	# 优先展示结构化结果，避免主题警告掩盖 error= 中的失败原因。
	if grep -q '^error=' "$OUT"; then
		grep -E '^(error|where|command|exit|output)=' "$OUT"
	else
		tail -n 8 "$OUT"
	fi | while IFS= read -r line; do
		[ -n "$line" ] && ui_print "  $line"
	done
}

install_or_update_app() {
	local APP_META="$MODPATH/config/app/app.prop"
	[ -f "$APP_META" ] || return
	EMBEDDED_APP_CLEANUP_ALLOWED=0

	if is_qixia_in_app_update; then
		ui_print "- App 内刷入模块：跳过当前会话内安装 App"
		ui_print "- 已保留随附 App，重启后将自动更新 App"
		return
	fi

	local APP_PKG APP_NAME APP_APK APP_VERSION_CODE APP_VERSION_NAME APP_VARIANT APP_DISPLAY INSTALLED_VERSION_CODE INSTALLED_VERSION_NAME
	local FORCE_APP_INSTALL=0
	local APP_INFO INSTALL_INFO
	APP_PKG="$(grep_prop package "$APP_META")"
	APP_NAME="$(grep_prop name "$APP_META")"
	APP_APK="$MODPATH/config/app/$(grep_prop apk "$APP_META")"
	APP_VERSION_CODE="$(grep_prop versionCode "$APP_META")"
	APP_VERSION_NAME="$(grep_prop versionName "$APP_META")"
	APP_VARIANT="$(grep_prop variant "$APP_META")"
	[ -n "$APP_NAME" ] || APP_NAME="柒夏线程"
	APP_DISPLAY="$APP_NAME $APP_VERSION_NAME ($APP_VERSION_CODE)"

	[ -n "$APP_PKG" ] || APP_PKG="top.qixia.threads"
	if [ ! -f "$APP_APK" ]; then
		ui_print "- 未找到随附应用，跳过安装"
		return
	fi
	if [ -z "$APP_VERSION_CODE" ]; then
		ui_print "- 随附应用缺少版本信息，跳过安装"
		return
	fi

	chmod 0644 "$APP_APK" 2>/dev/null || true

	APP_INFO="${TMPDIR:-/dev/tmp}/qixia_app_info.prop"
	INSTALL_INFO="${TMPDIR:-/dev/tmp}/qixia_install_info.prop"

	if run_pkg_helper "$APP_INFO" app-info "$APP_PKG" && [ "$(grep_prop ok "$APP_INFO")" = "1" ]; then
		if [ "$(grep_prop installed "$APP_INFO")" = "1" ]; then
			INSTALLED_VERSION_CODE="$(grep_prop versionCode "$APP_INFO")"
			INSTALLED_VERSION_NAME="$(grep_prop versionName "$APP_INFO")"
			ui_print "- 已安装 App：$APP_NAME ${INSTALLED_VERSION_NAME:-未知} ($INSTALLED_VERSION_CODE)"
		else
			INSTALLED_VERSION_CODE=""
			INSTALLED_VERSION_NAME=""
			ui_print "- 未检测到已安装 App，准备安装随附版本"
		fi
	else
		print_helper_error "读取已安装 App 版本失败，跳过自动安装" "$APP_INFO"
		return
	fi

	if [ -n "$INSTALLED_VERSION_CODE" ] &&
		[ "$INSTALLED_VERSION_CODE" = "$APP_VERSION_CODE" ] &&
		{ [ -z "$INSTALLED_VERSION_NAME" ] || [ "$INSTALLED_VERSION_NAME" = "$APP_VERSION_NAME" ]; }; then
		if [ "$APP_VARIANT" = "debug" ]; then
			FORCE_APP_INSTALL=1
			ui_print "- Debug 应用包：版本相同，仍然执行覆盖安装"
		else
			ui_print "- 应用已是最新版本，跳过安装"
			EMBEDDED_APP_CLEANUP_ALLOWED=1
			return
		fi
	fi

	if [ -n "$INSTALLED_VERSION_CODE" ]; then
		if [ "$FORCE_APP_INSTALL" = "1" ]; then
			ui_print "- 覆盖安装 App：$APP_VERSION_NAME ($APP_VERSION_CODE)"
		elif [ "$INSTALLED_VERSION_CODE" -lt "$APP_VERSION_CODE" ] 2>/dev/null; then
			ui_print "- $APP_NAME 版本过低，准备更新"
		elif [ "$INSTALLED_VERSION_CODE" -gt "$APP_VERSION_CODE" ] 2>/dev/null; then
			ui_print "- $APP_NAME 已安装版本高于随附版本，跳过安装"
			EMBEDDED_APP_CLEANUP_ALLOWED=1
			return
		else
			ui_print "- $APP_NAME 已安装版本不同，准备更新"
		fi
		if [ "$FORCE_APP_INSTALL" != "1" ]; then
			ui_print "- 更新 App：${INSTALLED_VERSION_NAME:-未知} ($INSTALLED_VERSION_CODE) -> $APP_VERSION_NAME ($APP_VERSION_CODE)"
		fi
	else
		ui_print "- 安装 App：$APP_DISPLAY"
	fi

	if QIXIA_PACKAGE="$APP_PKG" QIXIA_VERSION_CODE="$APP_VERSION_CODE" QIXIA_VERSION_NAME="$APP_VERSION_NAME" run_pkg_helper "$INSTALL_INFO" install "$APP_APK" && [ "$(grep_prop ok "$INSTALL_INFO")" = "1" ]; then
		ui_print "- 应用安装完成"
		EMBEDDED_APP_CLEANUP_ALLOWED=1
	else
		print_helper_error "内置安装器执行失败" "$INSTALL_INFO"
		ui_print "! App 安装未完成，请手动安装模块内的 $APP_NAME.apk"
	fi
}
cleanup_embedded_app() {
	local APP_DIR="$MODPATH/config/app"
	if is_qixia_in_app_update && [ -f "$APP_DIR/app.prop" ]; then
		ui_print "- 已保留临时 App 安装文件，重启后自动更新"
		return
	fi
	if [ "$EMBEDDED_APP_CLEANUP_ALLOWED" != "1" ]; then
		ui_print "- 已保留临时 App 安装文件，可稍后重试或手动安装"
		return
	fi
	if rm -rf "$APP_DIR" && rm -f \
		"$MODPATH/config/tools/qixia_pkg_helper.jar" \
		"$MODPATH/config/tools/qixia_pkg_helper.sh"; then
		ui_print "- 已清理临时 App 安装文件及安装助手"
	else
		ui_print "! 临时 App 安装文件或安装助手清理失败，开机后重试"
	fi
}

configure_joyose_smartop() {
	local MIUI_VERSION JOYOSE_INFO ORIGINAL_STATE ACTIVE_UNINSTALL UNINSTALL_FILE TEMP_FILE RESTORE_COMMAND
	local JOYOSE_COMPONENT="com.xiaomi.joyose/.smartop.SmartOpService"
	MIUI_VERSION="$(getprop ro.miui.ui.version.code)"
	[ -n "$MIUI_VERSION" ] || return

	# 柒夏线程模块更新时继承已记录的恢复责任。
	ACTIVE_UNINSTALL="$QIXIA_UPGRADE_SOURCE/uninstall.sh"
	ORIGINAL_STATE=""
	if [ -n "$QIXIA_UPGRADE_SOURCE" ]; then
		ORIGINAL_STATE="$(sed -n 's/^# QIXIA_JOYOSE_SMARTOP_ORIGINAL=\(default\|enabled\)$/\1/p' \
			"$ACTIVE_UNINSTALL" 2>/dev/null | head -n 1)"
	fi
	if [ -z "$ORIGINAL_STATE" ]; then
		JOYOSE_INFO="${TMPDIR:-/dev/tmp}/qixia_joyose_info.prop"
		if ! run_pkg_helper "$JOYOSE_INFO" component-state "$JOYOSE_COMPONENT" 0 ||
			[ "$(grep_prop ok "$JOYOSE_INFO")" != "1" ]; then
			ui_print "! 无法读取 Joyose SmartOpService 原始状态，本次不修改"
			return
		fi
		ORIGINAL_STATE="$(grep_prop state "$JOYOSE_INFO")"
		case "$ORIGINAL_STATE" in
			default|enabled) ;;
			disabled|disabled-user|disabled-until-used)
				ui_print "- Joyose SmartOpService 原本已禁用，保持用户状态"
				return
				;;
			*)
				ui_print "! Joyose SmartOpService 状态未知，本次不修改"
				return
				;;
		esac
	fi

	UNINSTALL_FILE="$MODPATH/uninstall.sh"
	if ! grep -q '^# QIXIA_JOYOSE_SMARTOP_ORIGINAL=' "$UNINSTALL_FILE" 2>/dev/null; then
		TEMP_FILE="$MODPATH/uninstall.sh.joyose.tmp.$$"
		if [ -f "$UNINSTALL_FILE" ]; then
			cp -pf "$UNINSTALL_FILE" "$TEMP_FILE" || {
				ui_print "! 无法复制模块卸载脚本，本次不修改 Joyose"
				return
			}
		else
			printf '#!/system/bin/sh\n' > "$TEMP_FILE" || {
				ui_print "! 无法创建模块卸载脚本，本次不修改 Joyose"
				return
			}
		fi
		if [ "$ORIGINAL_STATE" = default ]; then
			RESTORE_COMMAND='pm default-state --user 0 com.xiaomi.joyose/.smartop.SmartOpService >/dev/null 2>&1 || true'
		else
			RESTORE_COMMAND='pm enable --user 0 com.xiaomi.joyose/.smartop.SmartOpService >/dev/null 2>&1 || true'
		fi
		if ! printf '# QIXIA_JOYOSE_SMARTOP_ORIGINAL=%s\n%s\n' \
			"$ORIGINAL_STATE" "$RESTORE_COMMAND" >> "$TEMP_FILE" ||
			! mv -f "$TEMP_FILE" "$UNINSTALL_FILE"; then
			rm -f "$TEMP_FILE"
			ui_print "! 无法记录 Joyose 原始状态，本次不修改 SmartOpService"
			return
		fi
	fi
	if pm disable --user 0 "$JOYOSE_COMPONENT" >/dev/null 2>&1; then
		ui_print "- Joyose SmartOpService：已禁用，卸载时恢复为 $ORIGINAL_STATE"
	else
		ui_print "! Joyose SmartOpService 禁用失败，已保留原状态恢复记录"
	fi
}

remove_sys_perf_config() {
	for SYSPERFCONFIG in $(ls /system/vendor/bin/msm_irqbalance); do
		[[ ! -d $MODPATH${SYSPERFCONFIG%/*} ]] && mkdir -p $MODPATH${SYSPERFCONFIG%/*}
		ui_print "- Remove :$SYSPERFCONFIG"
		touch $MODPATH$SYSPERFCONFIG
	done
}
format_cpu_ranges() {
	[ -z "${1// /}" ] && { cat /sys/devices/system/cpu/present; return; }
	awk -v input="$1" 'BEGIN {
		n = split(input, arr, /[[:space:],]+/)
		j = 0
		for (i = 1; i <= n; i++) {
			token = arr[i]
			if (token == "") continue
			if (token ~ /^[0-9]+-[0-9]+$/) {
				split(token, range, "-")
				start = range[1] + 0
				end = range[2] + 0
				if (start > end) {
					t = start
					start = end
					end = t
				}
				for (cpu = start; cpu <= end; cpu++) {
					if (!seen[cpu]++) nums[++j] = cpu
				}
			} else if (token ~ /^[0-9]+$/) {
				cpu = token + 0
				if (!seen[cpu]++) nums[++j] = cpu
			}
		}
		n = j
		if (!n) exit
		for (i = 1; i < n; i++) {
			min = i
			for (j = i + 1; j <= n; j++)
				if (nums[j] < nums[min]) min = j
			if (min != i) {
				t = nums[i]
				nums[i] = nums[min]
				nums[min] = t
			}
		}
		start = last = nums[1]
		for (i = 2; i <= n; i++) {
			if (nums[i] == last + 1) {
				last = nums[i]
				continue
			}
			printf "%s%s", sep, (start == last ? start : start "-" last)
			sep = ","
			start = last = nums[i]
		}
		printf "%s", sep
		printf (start == last ? start : start "-" last)
	}'
}
sorted_groups=$(
	for policy in /sys/devices/system/cpu/cpufreq/policy*; do
		[ -d "$policy" ] || continue
		cpus=$(cat "$policy/related_cpus" 2>/dev/null)
		freq=$(cat "$policy/cpuinfo_max_freq" 2>/dev/null)
		[ -z "$cpus" ] || [ -z "$freq" ] && continue
		echo "$freq:$cpus"
	done | sort -n -t: -k1,1 | awk -F: '
	$1 == prev { cores = cores " " $2; next }
	prev != "" { print prev ":" cores; cores = "" }
	{ prev = $1; cores = $2 }
	END { if (prev != "") print prev ":" cores }'
)
eval "$(echo "$sorted_groups" | awk -F: '
BEGIN { e_core=""; p_core=""; p_high_core=""; hp_core=""; total_groups=0 }
{ freq_arr[NR]=$1; cpus_arr[NR]=$2; total_groups=NR }
END {
	if (total_groups == 0) {
		print "e_core=\"\"; p_core=\"\"; p_high_core=\"\"; hp_core=\"\"; total_groups=0;"
		exit
	}
	e_core=cpus_arr[1]
	if (total_groups >= 2) hp_core=cpus_arr[total_groups]
	if (total_groups >= 3) {
		for (i = 2; i < total_groups; i++) p_core = p_core (p_core == "" ? "" : " ") cpus_arr[i]
		p_high_core=cpus_arr[total_groups - 1]
	}
	printf "e_core=\"%s\"; p_core=\"%s\"; p_high_core=\"%s\"; hp_core=\"%s\"; total_groups=%d;", e_core, p_core, p_high_core, hp_core, total_groups
}')"
all_core="$(cat /sys/devices/system/cpu/present)"
module_instructions() {
	ui_print "********************************************"
	ui_print "线程规则配置文件路径为："
	ui_print "/data/adb/modules/QixiaThreads/config/applist.conf"
	ui_print "------------------------------------------"
	ui_print "修改与添加规则无需重启，即时生效"
	ui_print "********************************************"
	cores=$(for cpus in /sys/devices/system/cpu/cpufreq/*/related_cpus; do
		[ -f "$cpus" ] && cat "$cpus" | wc -w
	done | paste -sd+)
	ui_print "当前$(getprop ro.soc.model)设备为$(nproc)核CPU，规格是：$cores"
	ui_print "可用CPU范围：$all_core"
	ui_print "------------------------------------------"
	[ -n "$(format_cpu_ranges "$e_core")" ] && ui_print "$(format_cpu_ranges "$e_core") 为低频性能簇，频率最低"
	[ $total_groups -ge 3 ] && [ -n "$(format_cpu_ranges "$p_core")" ] && ui_print "$(format_cpu_ranges "$p_core") 为主性能簇"
	[ $total_groups -ge 2 ] && [ -n "$(format_cpu_ranges "$hp_core")" ] && ui_print "$(format_cpu_ranges "$hp_core") 为最高性能簇"
	ui_print "------------------------------------------"
	ui_print "applist.conf 规则写法示例："
	ui_print "------------------------------------------"
	if [ -n "$all_core" ]; then
		ui_print "允许安卓 '图形显示组件 '调用所有核心："
		ui_print "surfaceflinger=$all_core"
		ui_print "------------------------------------------"
	fi
	if [ -n "$(format_cpu_ranges "$hp_core")" ]; then
		ui_print "单独将'图形显示组件'渲染引擎线程绑定到大核："
		ui_print "surfaceflinger{RenderEngine}=$(format_cpu_ranges "$hp_core")"
		ui_print "------------------------------------------"
	fi
	if [ -n "$(format_cpu_ranges "$e_core")" ]; then
		ui_print "将 '微信' 主进程绑定能效小核："
		ui_print "com.tencent.mm=$(format_cpu_ranges "$e_core")"
		ui_print "------------------------------------------"
	fi
	if [ -n "$(format_cpu_ranges "$e_core")" ]; then
		ui_print "将 '微信' 消息推送子进程绑定能效小核："
		ui_print "com.tencent.mm:push=$(format_cpu_ranges "$e_core")"
		ui_print "------------------------------------------"
	fi
	if [ -n "$(format_cpu_ranges "$hp_core")" ]; then
		ui_print "将 '系统界面' 渲染线程绑定到性能大核："
		ui_print "com.android.systemui{RenderThread}=$(format_cpu_ranges "$hp_core")"
		ui_print "------------------------------------------"
	fi
	ui_print "更多规则使用说明请参考："
	ui_print "http://AppOpt.suto.top"
	ui_print "********************************************"
}

add_default_rules() {
	mkdir -p $MODPATH/config
	local CONFIG_FILE="$MODPATH/config/applist.conf"
	local ACTIVE_CONFIG="$QIXIA_UPGRADE_SOURCE/config/applist.conf"
	if [ -n "$QIXIA_UPGRADE_SOURCE" ] && [ -f "$ACTIVE_CONFIG" ]; then
		cp -f "$ACTIVE_CONFIG" "$CONFIG_FILE" || abort "! 线程规则迁移失败，旧文件仍保留"
		ui_print "- 线程规则配置：已保留"
		return
	fi
	[ -f "$MODPATH/rules.sh" ] || abort "! 找不到默认规则文件 rules.sh"
	QIXIA_RULES_FILE="$CONFIG_FILE"
	. "$MODPATH/rules.sh"
	unset QIXIA_RULES_FILE
	ui_print "- 已生成默认线程规则配置"
}
normalize_cpuset_name() {
	local name="$1"
	[ -n "$name" ] && [ "${#name}" -le 48 ] || return 1
	case "$name" in
		.*|*[!A-Za-z0-9_.-]*) return 1 ;;
	esac
	printf '%s' "$name"
}

prepare_calib_policy() {
	mkdir -p $MODPATH/config
	local ACTIVE_POLICY="$QIXIA_UPGRADE_SOURCE/config/calib_policy.conf"
	local PENDING_POLICY="$MODPATH/config/calib_policy.conf"
	if [ -n "$QIXIA_UPGRADE_SOURCE" ] && [ -f "$ACTIVE_POLICY" ]; then
		cp -f "$ACTIVE_POLICY" "$PENDING_POLICY" || abort "! 校准设置迁移失败，旧文件仍保留"
		ui_print "- 自动校准策略：已保留"
	else
		cat > "$PENDING_POLICY" <<EOF
# QixiaThreads 规则格式与运行设置
# App 内可视化编辑；手动改动时请保持 key=value 格式。
# 校准仅生成待确认建议，旧负载档位与固定核心不参与生成。
version=2
rule_output_format=legacy
cpuset_name=QiXiaRs
keep_all_cores_online=0
EOF
		ui_print "- 已生成默认自动校准策略配置"
	fi
	# 升级时保留用户设置，仅补齐缺失的默认值。
	if ! grep -q '^[[:space:]]*keep_all_cores_online[[:space:]]*=' "$PENDING_POLICY"; then
		printf '\nkeep_all_cores_online=0\n' >> "$PENDING_POLICY" || abort "! 核心在线策略补齐失败"
	fi

	local CPUSET_NAME TMP_POLICY
	CPUSET_NAME="$(sed -n 's/^[[:space:]]*cpuset_name[[:space:]]*=[[:space:]]*\([^#[:space:]]*\).*$/\1/p' "$PENDING_POLICY" 2>/dev/null | tail -n 1)"
	CPUSET_NAME="$(normalize_cpuset_name "$CPUSET_NAME" 2>/dev/null)" || CPUSET_NAME=""
	if [ -z "$CPUSET_NAME" ]; then
		CPUSET_NAME="QiXiaRs"
		TMP_POLICY="$PENDING_POLICY.cpuset.tmp"
		if awk '
		{
			trimmed = $0
			sub(/^[[:space:]]*/, "", trimmed)
			if (trimmed ~ /^cpuset_name[[:space:]]*=/) next
			print
		}' "$PENDING_POLICY" > "$TMP_POLICY" &&
			printf 'cpuset_name=%s\n' "$CPUSET_NAME" >> "$TMP_POLICY" &&
			mv -f "$TMP_POLICY" "$PENDING_POLICY"; then
			ui_print "- Rust cpuset 运行组：已写入自动校准策略"
		else
			rm -f "$TMP_POLICY"
			ui_print "! Rust cpuset 运行组写入失败，将使用默认 QiXiaRs"
		fi
	fi
}

# 模块升级时保留自动核心分配的应用选择。
prepare_auto_affinity_config() {
	mkdir -p "$MODPATH/config"
	[ -n "$QIXIA_UPGRADE_SOURCE" ] || return 0
	if [ -f "$QIXIA_UPGRADE_SOURCE/config/auto_affinity.conf" ]; then
		cp -f "$QIXIA_UPGRADE_SOURCE/config/auto_affinity.conf" "$MODPATH/config/auto_affinity.conf" \
			|| abort "! 自动分配应用列表迁移失败，旧文件仍保留"
	fi
}

# 记录设置共用策略文件，升级时保留当前模块的记录开关。
prepare_auto_history() {
	local POLICY="$MODPATH/config/calib_policy.conf" FALLBACK=0 TEMP
	TEMP="$POLICY.history.tmp"
	# 安装时一次性转存当前模块的开关，运行期间不再读取独立配置文件。
	if [ -n "$QIXIA_UPGRADE_SOURCE" ] && [ -f "$QIXIA_UPGRADE_SOURCE/config/auto_history.conf" ]; then
		FALLBACK="$(awk '
		{ sub(/#.*/, ""); if ($0 ~ /^[[:space:]]*$/) next
		  split_at=index($0, "="); if (!split_at) { invalid=1; next }
		  key=substr($0, 1, split_at-1); value=substr($0, split_at+1)
		  gsub(/^[[:space:]]+|[[:space:]]+$/, "", key); gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
		  if (key == "enabled") { count++; enabled=value } }
		END { print (!invalid && count == 1 && enabled == "1") ? 1 : 0 }
		' "$QIXIA_UPGRADE_SOURCE/config/auto_history.conf")" || abort "! 自动分配记录设置读取失败"
	fi
	if awk -v fallback="$FALLBACK" '
	{
		clean=$0; sub(/#.*/, "", clean); split_at=index(clean, "=")
		key=substr(clean, 1, split_at-1); value=substr(clean, split_at+1)
		gsub(/^[[:space:]]+|[[:space:]]+$/, "", key); gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
		if (split_at && key == "auto_history_version") next
		if (split_at && key == "auto_history_enabled") { count++; enabled=value; next }
		print
	}
	END {
		print "auto_history_version=1"
		if (count == 0) enabled=fallback
		print "auto_history_enabled=" ((count <= 1 && enabled == "1") ? 1 : 0)
	}' "$POLICY" > "$TEMP" && mv -f "$TEMP" "$POLICY"; then
		rm -f "$MODPATH/config/auto_history.conf" "$MODPATH/config/auto_history.version"
	else
		rm -f "$TEMP"
		abort "! 自动分配记录设置合入策略文件失败"
	fi
}

# 重建临时进程索引，保留规则判定结果和线程恢复数据。
prepare_runtime_state_dir() {
	mkdir -p "$MODPATH/config/state"
	rm -f "$MODPATH/config/state/package_uid.map" "$MODPATH/config/state/package_uid.map."*.tmp \
		"$MODPATH/config/state/pid_cache.tsv" "$MODPATH/config/state/pid_cache.tsv."*.tmp
	[ -n "$QIXIA_UPGRADE_SOURCE" ] || return 0
	local FILE
	for FILE in rule_health.tsv managed_tids.tsv auto_affinity.restore app_storage.conf; do
		[ -f "$QIXIA_UPGRADE_SOURCE/config/state/$FILE" ] || continue
		cp -pf "$QIXIA_UPGRADE_SOURCE/config/state/$FILE" "$MODPATH/config/state/" \
			|| abort "! 模块运行状态保留失败：$FILE"
	done
}

# 更新目录布局时，不能丢弃当前模块尚未导入的用户数据。
# 仅在安装时转存；守护进程和应用不再读取模块目录中的待导入文件。
relocate_current_collection_files() {
	[ -n "$QIXIA_UPGRADE_SOURCE" ] || return 0
	local KIND SOURCE_DIR SOURCE NAME REGISTRATION FILES UID_VALUE USER_ID DESTINATION TEMP
	REGISTRATION="$QIXIA_UPGRADE_SOURCE/config/state/app_storage.conf"
	for KIND in history auto_history calibration_drafts; do
		case "$KIND" in
			history) SOURCE_DIR="$QIXIA_UPGRADE_SOURCE/history" ;;
			*) SOURCE_DIR="$QIXIA_UPGRADE_SOURCE/config/$KIND" ;;
		esac
		for SOURCE in "$SOURCE_DIR"/*.log "$SOURCE_DIR"/*.tmp "$SOURCE_DIR"/*.draft \
			"$SOURCE_DIR"/*.qixia-importing "$SOURCE_DIR"/*.invalid.* "$SOURCE_DIR/.draft.stamp"; do
			[ -f "$SOURCE" ] && [ ! -L "$SOURCE" ] || continue
			FILES=$(sed -n 's/^files=//p' "$REGISTRATION" 2>/dev/null)
			UID_VALUE=$(sed -n 's/^uid=//p' "$REGISTRATION" 2>/dev/null)
			case "$UID_VALUE" in ''|*[!0-9]*) abort "! 请先打开新版 App 初始化私有存储，再安装模块；原采集数据已保留" ;; esac
			USER_ID=$((UID_VALUE / 100000))
			case "$FILES" in
				"/data/user/$USER_ID/top.qixia.threads/files") ;;
				/data/data/top.qixia.threads/files) [ "$USER_ID" -eq 0 ] || abort "! App 存储用户不匹配" ;;
				/mnt/expand/*/user/"$USER_ID"/top.qixia.threads/files) ;;
				*) abort "! App 私有存储路径无效，采集数据仍保留在原位置" ;;
			esac
			[ -d "$FILES" ] && [ "$(stat -c %u "$FILES")" = "$UID_VALUE" ] \
				|| abort "! 请解锁并打开新版 App 后重试；原采集数据已保留"
			DESTINATION="$FILES/capture/$KIND"
			mkdir -p "$DESTINATION" || abort "! 无法准备 App 私有采集目录"
			NAME=${SOURCE##*/}
			if [ -e "$DESTINATION/$NAME" ]; then
				cmp -s "$SOURCE" "$DESTINATION/$NAME" || abort "! 采集文件同名冲突，双方数据均已保留：$NAME"
				continue
			fi
			TEMP="$DESTINATION/.install.$NAME"
			cp -p "$SOURCE" "$TEMP" && cmp -s "$SOURCE" "$TEMP" && mv "$TEMP" "$DESTINATION/$NAME" \
				|| abort "! 采集数据转存失败，原文件已保留：$NAME"
			chown "$UID_VALUE:$UID_VALUE" "$FILES/capture" "$DESTINATION" "$DESTINATION/$NAME" \
				|| abort "! 无法设置采集目录归属"
			chmod 0700 "$FILES/capture" "$DESTINATION"
			restorecon -R "$FILES/capture" || abort "! 无法设置采集目录访问标签"
		done
	done
}

normalize_calib_rule_output_format() {
	local POLICY_FILE="$MODPATH/config/calib_policy.conf"
	local OLD_FORMAT TMP_FILE
	[ -f "$POLICY_FILE" ] || return
	OLD_FORMAT="$(sed -n 's/^[[:space:]]*rule_output_format[[:space:]]*=[[:space:]]*\([^#[:space:]]*\).*$/\1/p' "$POLICY_FILE" | tail -n 1)"
	case "$OLD_FORMAT" in
		compact_header_block|separate_fallback_block|compact_separate_fallback_block|extended_block)
			TMP_FILE="$POLICY_FILE.format.tmp"
			if awk -v old_format="$OLD_FORMAT" '
			BEGIN { migrated = 0 }
			{
				trimmed = $0
				sub(/^[[:space:]]*/, "", trimmed)
				if (trimmed ~ /^rule_output_format_migration[[:space:]]*=/) next
				if (trimmed ~ /^rule_output_format[[:space:]]*=/) {
					comment = ""
					hash = index($0, "#")
					if (hash > 0) comment = substr($0, hash)
					if (!migrated) printf "rule_output_format_migration=%s\n", old_format
					printf "rule_output_format=author_block"
					if (comment != "") printf " %s", comment
					printf "\n"
					migrated = 1
					next
				}
				print
			}' "$POLICY_FILE" > "$TMP_FILE" && mv -f "$TMP_FILE" "$POLICY_FILE"; then
				ui_print "- 校准规则格式：旧区块生成策略已迁移为原作者格式"
			else
				rm -f "$TMP_FILE"
				ui_print "! 校准规则格式迁移失败，守护进程将使用原作者格式兼容处理"
			fi
			;;
	esac
}
check_magisk_version
check_required_files
extract_bin
configure_joyose_smartop
remove_sys_perf_config
module_instructions
add_default_rules
rm -f "$MODPATH/rules.sh"
prepare_calib_policy
prepare_auto_affinity_config
prepare_auto_history
prepare_runtime_state_dir
relocate_current_collection_files
normalize_calib_rule_output_format
set_perm_recursive "$MODPATH" 0 0 0755 0644
for SCRIPT in "$MODPATH"/*.sh; do
	[ -f "$SCRIPT" ] && set_perm "$SCRIPT" 0 2000 0755 u:object_r:magisk_file:s0
done
[ -f "$MODPATH/config/bin/QiXiaRs" ] && set_perm "$MODPATH/config/bin/QiXiaRs" 0 2000 0755 u:object_r:magisk_file:s0
[ -d "$MODPATH/config/tools" ] && chmod 0755 "$MODPATH/config/tools" "$MODPATH/config/tools"/*.sh 2>/dev/null
install_or_update_app
cleanup_embedded_app
print_manual_migration_notice
