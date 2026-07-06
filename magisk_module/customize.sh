SKIPUNZIP=0
APPOPT_IN_APP_UPDATE_MARKER="config/app/.appopt_in_app_update"
APPOPT_IN_APP_UPDATE_FLAG="/data/adb/appopt_in_app_update"
is_appopt_in_app_update() {
	[ "$APPOPT_IN_APP_UPDATE" = "1" ] && return 0
	[ -n "$MODPATH" ] && [ -f "$MODPATH/$APPOPT_IN_APP_UPDATE_MARKER" ] && return 0
	[ -f "$APPOPT_IN_APP_UPDATE_FLAG" ] && return 0
	return 1
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
		cp $MODPATH/config/bin/armeabi-v7a/AppOpt $MODPATH/config/bin/AppOpt
	elif [ "$ARCH" == "arm64" ]; then
		cp $MODPATH/config/bin/arm64-v8a/AppOpt $MODPATH/config/bin/AppOpt
	elif [ "$ARCH" == "x86" ]; then
		cp $MODPATH/config/bin/x86/AppOpt $MODPATH/config/bin/AppOpt
	elif [ "$ARCH" == "x64" ]; then
		cp $MODPATH/config/bin/x86_64/AppOpt $MODPATH/config/bin/AppOpt
	else
		abort "! Unsupported platform: $ARCH"
	fi
	ui_print "- Device platform: $ARCH"
	rm -rf $MODPATH/config/bin/armeabi-v7a $MODPATH/config/bin/arm64-v8a $MODPATH/config/bin/x86 $MODPATH/config/bin/x86_64
	[ -f $MODPATH/config/bin/AppOpt ] && chmod a+x $MODPATH/config/bin/AppOpt
	if ! $MODPATH/config/bin/AppOpt -v; then
		abort "! 主程序验证失败，请检查模块zip文件是否损坏"
	fi
}

run_pkg_helper() {
	local OUT="$1"
	shift
	local HELPER="$MODPATH/config/app/tools/appopt_pkg_helper.sh"
	local HELPER_DIR="$MODPATH/config/app/tools"
	if [ ! -f "$HELPER" ]; then
		echo "ok=0" > "$OUT"
		echo "error=找不到内置安装器脚本" >> "$OUT"
		return 1
	fi
	APP_OPT_HELPER_DIR="$HELPER_DIR" \
	APP_OPT_PACKAGE="${APP_OPT_PACKAGE:-}" \
	APP_OPT_VERSION_CODE="${APP_OPT_VERSION_CODE:-}" \
	APP_OPT_VERSION_NAME="${APP_OPT_VERSION_NAME:-}" \
	sh "$HELPER" "$@" > "$OUT" 2>&1
	return $?
}

print_helper_error() {
	local TITLE="$1"
	local OUT="$2"
	ui_print "- $TITLE"
	[ -f "$OUT" ] || return
	sed -n '1,4p' "$OUT" | while IFS= read -r line; do
		[ -n "$line" ] && ui_print "  $line"
	done
}

install_or_update_app() {
	local APP_META="$MODPATH/config/app/app.prop"
	[ -f "$APP_META" ] || return

	if is_appopt_in_app_update; then
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
	[ -n "$APP_NAME" ] || APP_NAME="AppOpt"
	APP_DISPLAY="$APP_NAME $APP_VERSION_NAME ($APP_VERSION_CODE)"

	[ -n "$APP_PKG" ] || APP_PKG="top.suto.appopt"
	if [ ! -f "$APP_APK" ]; then
		ui_print "- 未找到随附应用，跳过安装"
		return
	fi
	if [ -z "$APP_VERSION_CODE" ]; then
		ui_print "- 随附应用缺少版本信息，跳过安装"
		return
	fi

	chmod 0644 "$APP_APK" 2>/dev/null || true

	APP_INFO="${TMPDIR:-/dev/tmp}/appopt_app_info.prop"
	INSTALL_INFO="${TMPDIR:-/dev/tmp}/appopt_install_info.prop"

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

	if APP_OPT_PACKAGE="$APP_PKG" APP_OPT_VERSION_CODE="$APP_VERSION_CODE" APP_OPT_VERSION_NAME="$APP_VERSION_NAME" run_pkg_helper "$INSTALL_INFO" install "$APP_APK" && [ "$(grep_prop ok "$INSTALL_INFO")" = "1" ]; then
		ui_print "- 应用安装完成"
	else
		print_helper_error "内置安装器执行失败" "$INSTALL_INFO"
		ui_print "! App 安装未完成，请手动安装模块内的 $APP_NAME.apk"
	fi
}
cleanup_embedded_app() {
	local APP_DIR="$MODPATH/config/app"
	[ -d "$APP_DIR" ] || return
	if is_appopt_in_app_update && [ -f "$APP_DIR/app.prop" ]; then
		ui_print "- 已保留临时 App 安装文件，重启后自动更新"
		return
	fi
	rm -rf "$APP_DIR"
	ui_print "- 已清理临时 App 安装文件"
}
remove_sys_perf_config() {
	for SYSPERFCONFIG in $(ls /system/vendor/bin/msm_irqbalance); do
		[[ ! -d $MODPATH${SYSPERFCONFIG%/*} ]] && mkdir -p $MODPATH${SYSPERFCONFIG%/*}
		ui_print "- Remove :$SYSPERFCONFIG"
		touch $MODPATH$SYSPERFCONFIG
	done
	if [ -n "$(pm path com.xiaomi.joyose)" ] && [ -n "$(getprop ro.miui.ui.version.code)" ]; then
		pm disable --user 0 com.xiaomi.joyose/.smartop.SmartOpService
		echo 'pm enable com.xiaomi.joyose/.smartop.SmartOpService' >> $MODPATH/uninstall.sh
	fi
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
	ui_print "/data/adb/modules/AppOpt/config/applist.conf"
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
	local ACTIVE_CONFIG="/data/adb/modules/AppOpt/config/applist.conf"
	local LEGACY_CONFIG="/data/adb/modules/AppOpt/applist.conf"
	if [ -f "$ACTIVE_CONFIG" ]; then
		cp -f "$ACTIVE_CONFIG" "$CONFIG_FILE"
		ui_print "- 线程规则配置：已保留"
		return
	elif [ -f "$LEGACY_CONFIG" ]; then
		cp -f "$LEGACY_CONFIG" "$CONFIG_FILE"
		ui_print "- 线程规则配置：已从旧路径迁移"
		return
	fi

# format_cpu_ranges函数用法：
# $(format_cpu_ranges "$e_core")           表示最低频性能簇
# $(format_cpu_ranges "$p_core")           表示中间性能簇
# $(format_cpu_ranges "$hp_core")          表示最高频/Prime 性能簇
# 也可以组合一起用：
# $(format_cpu_ranges "$e_core $p_core")  为非最高性能簇
# $(format_cpu_ranges "$p_core $hp_core") 为主性能簇与最高性能簇
common_rules="
# 将 '微信' 渲染线程与主线程绑定到中大核
com.tencent.mm=$(format_cpu_ranges "$e_core $p_core")
com.tencent.mm{RenderThread}=$(format_cpu_ranges "$hp_core")
com.tencent.mm{com.tencent.mm}=$(format_cpu_ranges "$p_core $hp_core")

# 将 '微信' 消息推送进程绑定到小核
com.tencent.mm:push=$(format_cpu_ranges "$e_core")

# 将 'QQ' 主线程与渲染线程绑定到中大核
com.tencent.mobileqq{encent.mobileqq}=$(format_cpu_ranges "$p_core $hp_core")
com.tencent.mobileqq{RenderThread}=$(format_cpu_ranges "$hp_core")

# 将 'QQ' 消息推送进程绑定到小核
com.tencent.mobileqq:MSF=$(format_cpu_ranges "$e_core")

# 将 '淘宝' 主线程绑定到大核
com.taobao.taobao{m.taobao.taobao}=$(format_cpu_ranges "$hp_core")
com.taobao.taobao{RenderThread}=$(format_cpu_ranges "$p_core $hp_core")

# 将 '酷安' 渲染线程绑定到大核
com.coolapk.market{RenderThread}=$(format_cpu_ranges "$hp_core")
com.coolapk.market{.coolapk.market}=$(format_cpu_ranges "$p_core $hp_core")

# 将 '抖音' 关键线程绑定到中大核
com.ss.android.ugc.aweme{main}=$(format_cpu_ranges "$hp_core")
com.ss.android.ugc.aweme{RenderThread}=$(format_cpu_ranges "$hp_core")
com.ss.android.ugc.aweme{droid.ugc.aweme}=$(format_cpu_ranges "$p_core $hp_core")

# 将 '支付宝' 渲染线程、主线程与扫一扫线程绑定到中大核
com.eg.android.AlipayGphone{RenderThread}=$(format_cpu_ranges "$hp_core")
com.eg.android.AlipayGphone{id.AlipayGphone}=$(format_cpu_ranges "$p_core $hp_core")
com.eg.android.AlipayGphone{ScanRecognize}=$(format_cpu_ranges "$hp_core")

# 将 '高德地图' 渲染线程与主线程绑定到中大核
com.autonavi.minimap{RenderThread}=$(format_cpu_ranges "$hp_core")
com.autonavi.minimap{utonavi.minimap}=$(format_cpu_ranges "$p_core $hp_core")

# 将 'Android图形显示组件'渲染引擎线程绑定到大核
surfaceflinger{RenderEngine}=$(format_cpu_ranges "$hp_core")

# 允许 'Android图形显示组件' 使用所有CPU核心$all_core
surfaceflinger=$all_core

# 将 '系统界面' 渲染引擎线程与主线程绑定到中大核
com.android.systemui{RenderThread}=$(format_cpu_ranges "$hp_core")
com.android.systemui{ndroid.systemui}=$(format_cpu_ranges "$p_core $hp_core")
"
game_rules="
# 将 '和平精英' 主线程绑定到中大核
com.tencent.tmgp.pubgmhd{Thread-[0-9]?}=$(format_cpu_ranges "$hp_core")
com.tencent.tmgp.pubgmhd{Thread-?}=$(format_cpu_ranges "$hp_core")
com.tencent.tmgp.pubgmhd{RenderThread*}=$(format_cpu_ranges "$p_core $hp_core")
com.tencent.tmgp.pubgmhd{RHIThread}=$(format_cpu_ranges "$e_core $p_core")
com.tencent.tmgp.pubgmhd=$(format_cpu_ranges "$e_core $p_core")

# 将 '蛋仔派对' 主线程绑定到大核
com.netease.party{MainThread}=$(format_cpu_ranges "$hp_core")
com.netease.party{Compute*}=$(format_cpu_ranges "$p_core $hp_core")
com.netease.party=$(format_cpu_ranges "$e_core $p_core")

# 将 '原神' Unity引擎线程绑定到中大核
com.miHoYo.Yuanshen{UnityMain}=$(format_cpu_ranges "$hp_core")
com.miHoYo.Yuanshen{UnityGfx*}=$(format_cpu_ranges "$p_core $hp_core")
com.miHoYo.Yuanshen=$(format_cpu_ranges "$e_core $p_core")

# '三角洲行动' 主线程绑定到大核
com.tencent.tmgp.dfm{GameThread}=$(format_cpu_ranges "$hp_core")
com.tencent.tmgp.dfm{Thread*}=$(format_cpu_ranges "$p_core $hp_core")
com.tencent.tmgp.dfm{TaskGraphNP*}=$(format_cpu_ranges "$p_core $hp_core")
com.tencent.tmgp.dfm{AudioTrack}=$(format_cpu_ranges "$e_core")
com.tencent.tmgp.dfm=$(format_cpu_ranges "$e_core $p_core")

# '金铲铲' Unity线程绑定到大核
com.tencent.jkchess{UnityMain}=$(format_cpu_ranges "$hp_core")
com.tencent.jkchess{UnityGfx*}=$(format_cpu_ranges "$p_core $hp_core")
com.tencent.jkchess=$(format_cpu_ranges "$e_core $p_core")

# '第五人格' 主线程绑定到大核
com.netease.dwrg{Thread-*}=$(format_cpu_ranges "$p_core $hp_core")
com.netease.dwrg{NativeThread}=$(format_cpu_ranges "$p_core $hp_core")
com.netease.dwrg=$(format_cpu_ranges "$e_core $p_core")
"
	echo "$common_rules" >> "$CONFIG_FILE"
	echo "$game_rules" >> "$CONFIG_FILE"
	ui_print "- 已生成默认线程规则配置"
}
prepare_calib_policy() {
	mkdir -p $MODPATH/config
	local ACTIVE_POLICY="/data/adb/modules/AppOpt/config/calib_policy.conf"
	local LEGACY_POLICY="/data/adb/modules/AppOpt/calib_policy.conf"
	local PENDING_POLICY="$MODPATH/config/calib_policy.conf"
	if [ -f "$ACTIVE_POLICY" ]; then
		cp -f "$ACTIVE_POLICY" "$PENDING_POLICY"
		ui_print "- 自动校准策略：已保留"
	elif [ -f "$LEGACY_POLICY" ]; then
		cp -f "$LEGACY_POLICY" "$PENDING_POLICY"
		ui_print "- 自动校准策略：已从旧路径迁移"
	else
		local BEST_CORES HIGH_CORES MID_CORES FALLBACK_CORES
		BEST_CORES="$(format_cpu_ranges "$hp_core")"
		[ -n "$BEST_CORES" ] || BEST_CORES="$(format_cpu_ranges "$all_core")"
		MID_CORES="$(format_cpu_ranges "$p_core")"
		[ -n "$MID_CORES" ] || MID_CORES="$(format_cpu_ranges "$e_core")"
		[ -n "$MID_CORES" ] || MID_CORES="$(format_cpu_ranges "$all_core")"
		HIGH_CORES="$(format_cpu_ranges "$p_high_core")"
		[ -n "$HIGH_CORES" ] || HIGH_CORES="$MID_CORES"
		FALLBACK_CORES="$(format_cpu_ranges "$e_core $p_core")"
		[ -n "$FALLBACK_CORES" ] || FALLBACK_CORES="$(format_cpu_ranges "$all_core")"
		cat > "$PENDING_POLICY" <<EOF
# AppOpt 自动校准策略
# App 内可视化编辑；手动改动时请保持 key=value 格式。
# 分配核心为连续 CPU 编号范围, 例如 7、5-6、0-6。
version=1
best_thread=avg:18,max:30,cores:$BEST_CORES
group_high=avg:13,max:22,cores:$HIGH_CORES
group_mid=avg:8,max:18,cores:$MID_CORES
wildcard_group=max_member
max_thread_rules=6
fallback=cores:$FALLBACK_CORES
EOF
		ui_print "- 已生成默认自动校准策略配置"
	fi
}
check_magisk_version
check_required_files
extract_bin
remove_sys_perf_config
module_instructions
add_default_rules
prepare_calib_policy
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/*.sh $MODPATH/config/bin/AppOpt" 0 2000 0755 0755 u:object_r:magisk_file:s0
[ -d "$MODPATH/config/app/tools" ] && chmod 0755 "$MODPATH/config/app/tools" "$MODPATH/config/app/tools"/*.sh 2>/dev/null
[ -d "$MODPATH/config/tools" ] && chmod 0755 "$MODPATH/config/tools" "$MODPATH/config/tools"/*.sh 2>/dev/null
install_or_update_app
cleanup_embedded_app
