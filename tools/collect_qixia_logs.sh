#!/system/bin/sh
# QixiaThreads 手机端排障脚本。
# 使用 Root 终端执行：sh collect_qixia_logs.sh
# 脚本会先保存现有状态，启动 QixiaThreads 复现问题，按回车后再保存崩溃日志并打包。

if [ "$(id -u 2>/dev/null)" != "0" ]; then
    echo "请在 Root 终端中执行，当前不是 uid=0。"
    echo "例如：su -c sh /sdcard/Download/collect_qixia_logs.sh"
    exit 1
fi

BASE_DIR="/sdcard/Download"
STAMP="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo unknown)"
OUT="$BASE_DIR/QixiaThreads-log-$STAMP"
MODDIR="/data/adb/modules/QixiaThreads"

if [ ! -d "$MODDIR" ]; then
    echo "找不到已启用的 QixiaThreads 模块目录：$MODDIR"
    exit 1
fi

mkdir -p "$OUT" || {
    echo "无法创建输出目录：$OUT"
    exit 1
}

copy_if_exists() {
    source_path="$1"
    target_path="$2"
    if [ -e "$source_path" ]; then
        cp -L "$source_path" "$target_path" 2>/dev/null || true
    fi
}

snapshot_module_files() {
    suffix="$1"
    copy_if_exists "$MODDIR/logs/QixiaThreads.log" "$OUT/QixiaThreads${suffix}.log"
    copy_if_exists "$MODDIR/logs/Runtime.log" "$OUT/Runtime${suffix}.log"
    copy_if_exists "$MODDIR/logs/Runtime.log.1" "$OUT/Runtime${suffix}.log.1"
    copy_if_exists "$MODDIR/logs/Runtime.log.2" "$OUT/Runtime${suffix}.log.2"
    copy_if_exists "$MODDIR/logs/ForegroundEvents.log" "$OUT/ForegroundEvents${suffix}.log"
    copy_if_exists "$MODDIR/logs/ForegroundEvents.log.1" "$OUT/ForegroundEvents${suffix}.log.1"
    copy_if_exists "$MODDIR/logs/ForegroundEvents.log.2" "$OUT/ForegroundEvents${suffix}.log.2"
    copy_if_exists "$MODDIR/logs/ForegroundHelper.log" "$OUT/ForegroundHelper${suffix}.log"
    copy_if_exists "$MODDIR/config/foreground_task.state" "$OUT/foreground_task${suffix}.state"
    copy_if_exists "$MODDIR/config/state/package_uid.map" "$OUT/package_uid${suffix}.map"
    copy_if_exists "$MODDIR/config/state/pid_cache.tsv" "$OUT/pid_cache${suffix}.tsv"
    copy_if_exists "$MODDIR/config/state/rule_health.tsv" "$OUT/rule_health${suffix}.tsv"
}

echo "QixiaThreads 日志目录：$OUT"
echo "正在保存复现前状态..."

snapshot_module_files "-before"
copy_if_exists "$MODDIR/module.prop" "$OUT/module.prop"
copy_if_exists "$MODDIR/config/applist.conf" "$OUT/applist.conf"
copy_if_exists "$MODDIR/config/calib_policy.conf" "$OUT/calib_policy.conf"
copy_if_exists "$MODDIR/config/jank_boost.conf" "$OUT/jank_boost.conf"

{
    echo "=== id ==="
    id
    echo "=== uname ==="
    uname -a
    echo "=== qixia processes ==="
    ps -A 2>/dev/null | grep -i -E 'QixiaThreads|qixia' || true
    echo "=== module status ==="
    getprop ro.build.version.release
    getprop ro.build.version.sdk
    getprop ro.product.manufacturer
    getprop ro.product.model
    getenforce 2>/dev/null || true
} > "$OUT/device.txt" 2>&1

dumpsys package top.qixia.threads > "$OUT/package.txt" 2>&1 || true
dumpsys activity top > "$OUT/activity-top.txt" 2>&1 || true
logcat -d -b all -v threadtime -t 5000 > "$OUT/logcat-before.txt" 2>&1 || true

resolve_qixia_activity() {
    resolved="$(cmd package resolve-activity --brief \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        -p top.qixia.threads 2>/dev/null | tail -n 1)"
    case "$resolved" in
        top.qixia.threads/*) echo "$resolved" ;;
        *) echo "top.qixia.threads/.QixiaThreadsComposeActivity" ;;
    esac
}

start_qixia() {
    activity="$(resolve_qixia_activity)"
    : > "$OUT/app-start.txt"
    echo "resolved_activity=$activity" >> "$OUT/app-start.txt"
    am force-stop --user current top.qixia.threads >> "$OUT/app-start.txt" 2>&1 || true

    # 优先按当前 Android 用户启动，兼容多用户、工作资料和 Root 终端环境。
    start_result="$(am start --user current -W -n "$activity" 2>&1)"
    start_code=$?
    printf '%s\n' "$start_result" >> "$OUT/app-start.txt"
    if [ "$start_code" -eq 0 ]; then
        case "$start_result" in
            *Error*|*Exception*|*"No activity found"*) ;;
            *) return 0 ;;
        esac
    fi

    # 部分 ROM 的 am 不接受 current，降级到用户 0。
    start_result="$(am start --user 0 -W -n "$activity" 2>&1)"
    start_code=$?
    printf '%s\n' "$start_result" >> "$OUT/app-start.txt"
    [ "$start_code" -eq 0 ] || return 1
    case "$start_result" in
        *Error*|*Exception*|*"No activity found"*) return 1 ;;
        *) return 0 ;;
    esac
}

echo
echo "正在启动 QixiaThreads..."
if start_qixia; then
    echo "QixiaThreads 启动命令已发送，详见：$OUT/app-start.txt"
else
    echo "QixiaThreads 启动失败，详见：$OUT/app-start.txt"
fi

echo "请等待 QixiaThreads 卡在环境检测并闪退。"
echo "闪退后回到这里按回车，脚本会保存崩溃日志。"
if [ -r /dev/tty ]; then
    read -r ignored < /dev/tty
else
    echo "当前没有可交互终端，脚本将自动等待 30 秒。"
    sleep 30
fi

echo "正在保存复现后日志..."
snapshot_module_files "-after"
logcat -d -b all -v threadtime -t 30000 > "$OUT/logcat-all.txt" 2>&1 || true
logcat -d -b crash -v threadtime > "$OUT/logcat-crash.txt" 2>&1 || true
grep -i -E 'QixiaThreads|top.qixia.threads|AndroidRuntime|FATAL EXCEPTION|am_crash|ActivityTaskManager' \
    "$OUT/logcat-all.txt" > "$OUT/logcat-qixia.txt" 2>/dev/null || true
dumpsys dropbox --print data_app_crash > "$OUT/dropbox-app-crash.txt" 2>&1 || true
dmesg > "$OUT/dmesg.txt" 2>&1 || true
ps -A > "$OUT/processes-after.txt" 2>&1 || true

mkdir -p "$OUT/tombstones"
for tombstone in $(ls -t /data/tombstones/tombstone_* 2>/dev/null | head -n 3); do
    cp -L "$tombstone" "$OUT/tombstones/" 2>/dev/null || true
done

README="$OUT/README.txt"
{
    echo "QixiaThreads 手机排障日志"
    echo "生成目录：$OUT"
    echo "请优先查看：logcat-crash.txt、logcat-qixia.txt、QixiaThreads-after.log、ForegroundHelper-after.log"
    echo "applist.conf 可能包含用户自定义应用规则，发送前请按需打码。"
} > "$README"

ARCHIVE="$BASE_DIR/QixiaThreads-log-$STAMP.tar.gz"
if command -v tar >/dev/null 2>&1 && tar -czf "$ARCHIVE" -C "$BASE_DIR" "QixiaThreads-log-$STAMP" 2>/dev/null; then
    echo "已生成：$ARCHIVE"
elif command -v zip >/dev/null 2>&1 && (cd "$BASE_DIR" && zip -qr "QixiaThreads-log-$STAMP.zip" "QixiaThreads-log-$STAMP"); then
    ARCHIVE="$BASE_DIR/QixiaThreads-log-$STAMP.zip"
    echo "已生成：$ARCHIVE"
elif tar -cf "$BASE_DIR/QixiaThreads-log-$STAMP.tar" -C "$BASE_DIR" "QixiaThreads-log-$STAMP" 2>/dev/null; then
    ARCHIVE="$BASE_DIR/QixiaThreads-log-$STAMP.tar"
    echo "已生成：$ARCHIVE"
else
    echo "当前系统没有可用压缩命令，请直接发送目录：$OUT"
fi

echo "排障日志收集完成。不要重启手机后再抓取，否则 logcat 可能被清空。"
