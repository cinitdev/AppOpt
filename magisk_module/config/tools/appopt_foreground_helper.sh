#!/system/bin/sh

DIR=${0%/*}
if command -v realpath >/dev/null 2>&1; then
    DIR=$(realpath "$DIR")
else
    DIR=$(cd "$DIR" 2>/dev/null && pwd -P)
fi

MODDIR=$(cd "$DIR/../.." 2>/dev/null && pwd -P)
JAR="$DIR/appopt_foreground_helper.jar"
CLASS="appopt.foreground.ForegroundHelper"
STATE="$MODDIR/config/foreground_task.state"
PID_FILE="$MODDIR/config/foreground_helper.pid"
LOG="$MODDIR/logs/ForegroundHelper.log"
NICE_NAME="appopt_foreground_helper"
RESTART_COOLDOWN_SECONDS=20

find_app_process() {
    for candidate in /system/bin/app_process /system/bin/app_process64 /system/bin/app_process32; do
        [ -x "$candidate" ] && {
            echo "$candidate"
            return 0
        }
    done
    return 1
}

is_helper_pid() {
    pid=$1
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -r "/proc/$pid/cmdline" ] || return 1
    executable=$(readlink "/proc/$pid/exe" 2>/dev/null)
    case "$executable" in
        */app_process|*/app_process32|*/app_process64) ;;
        *) return 1 ;;
    esac
    process_name=$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null | head -n 1)
    [ "$process_name" = "$NICE_NAME" ] && return 0

    # app_process 设置进程名之前可能短暂保留完整参数，兼容启动窗口。
    cmdline=$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
    case "$cmdline" in
        *"$CLASS"*) return 0 ;;
        *) return 1 ;;
    esac
}

helper_pids() {
    if command -v pidof >/dev/null 2>&1; then
        found=0
        for pid in $(pidof "$NICE_NAME" 2>/dev/null); do
            if is_helper_pid "$pid"; then
                echo "$pid"
                found=1
            fi
        done
        [ "$found" -eq 1 ] && return 0
    fi

    for proc_dir in /proc/[0-9]*; do
        pid=${proc_dir##*/}
        [ -r "$proc_dir/cmdline" ] || continue
        process_name=$(tr '\000' '\n' < "$proc_dir/cmdline" 2>/dev/null | head -n 1)
        if [ "$process_name" = "$NICE_NAME" ]; then
            is_helper_pid "$pid" && echo "$pid"
            continue
        fi
        cmdline=$(tr '\000' ' ' < "$proc_dir/cmdline" 2>/dev/null)
        case "$cmdline" in
            *"$CLASS"*) is_helper_pid "$pid" && echo "$pid" ;;
        esac
    done
}

stop_other_helpers() {
    keep_pid=$1
    for pid in $(helper_pids); do
        [ "$pid" = "$keep_pid" ] || kill "$pid" 2>/dev/null || true
    done
}

helper_running() {
    pid=""
    if [ -f "$PID_FILE" ]; then
        pid=$(cat "$PID_FILE" 2>/dev/null)
        case "$pid" in
            ''|*[!0-9]*) pid="" ;;
        esac
    fi
    if [ -n "$pid" ] && is_helper_pid "$pid"; then
        return 0
    fi

    # PID 文件可能在进程重启、模块更新或异常退出时失步，按命令行接管真实 helper。
    for pid in $(helper_pids); do
        printf '%s\n' "$pid" > "$PID_FILE"
        return 0
    done
    return 1
}

stop_all_helpers() {
    for pid in $(helper_pids); do
        kill "$pid" 2>/dev/null || true
    done
}

# 系统服务死亡时 Java 助手会主动退出。短暂冷却可避免 system_server 尚未恢复时反复拉起。
helper_restart_cooling_down() {
    [ -f "$STATE" ] || return 1
    state_boot_id=$(sed -n 's/^boot_id=//p' "$STATE" 2>/dev/null | head -n 1)
    current_boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$state_boot_id" ] && [ "$state_boot_id" = "$current_boot_id" ] || return 1
    [ "$(sed -n 's/^status=//p' "$STATE" 2>/dev/null | head -n 1)" = "error" ] || return 1
    state_error=$(sed -n 's/^error=//p' "$STATE" 2>/dev/null | head -n 1)
    case "$state_error" in
        *DeadObjectException*|*DeadSystemException*|*DeadSystemRuntimeException*) ;;
        *) return 1 ;;
    esac
    updated_wall_ms=$(sed -n 's/^updated_wall_ms=//p' "$STATE" 2>/dev/null | head -n 1)
    case "$updated_wall_ms" in
        ''|*[!0-9]*) return 1 ;;
    esac
    now_seconds=$(date +%s 2>/dev/null) || return 1
    # Android 部分 sh 仍是 32 位算术，不能直接计算 13 位毫秒时间戳。
    updated_seconds=${updated_wall_ms%???}
    [ -n "$updated_seconds" ] || return 1
    age_seconds=$((now_seconds - updated_seconds))
    [ "$age_seconds" -ge 0 ] && [ "$age_seconds" -lt "$RESTART_COOLDOWN_SECONDS" ]
}

helper_dead_system_state() {
    [ -f "$STATE" ] && [ -f "$PID_FILE" ] || return 1
    state_pid=$(sed -n 's/^pid=//p' "$STATE" 2>/dev/null | head -n 1)
    running_pid=$(cat "$PID_FILE" 2>/dev/null)
    [ -n "$state_pid" ] && [ "$state_pid" = "$running_pid" ] || return 1
    state_boot_id=$(sed -n 's/^boot_id=//p' "$STATE" 2>/dev/null | head -n 1)
    current_boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$state_boot_id" ] && [ "$state_boot_id" = "$current_boot_id" ] || return 1
    [ "$(sed -n 's/^status=//p' "$STATE" 2>/dev/null | head -n 1)" = "error" ] || return 1
    state_error=$(sed -n 's/^error=//p' "$STATE" 2>/dev/null | head -n 1)
    case "$state_error" in
        *DeadObjectException*|*DeadSystemException*|*DeadSystemRuntimeException*) return 0 ;;
        *) return 1 ;;
    esac
}

start_helper() {
    if helper_running; then
        running_pid=$(cat "$PID_FILE" 2>/dev/null)
        if ! helper_dead_system_state; then
            # 模块更新或异常重启可能留下旧 helper，始终只保留 PID 文件指向的实例。
            stop_other_helpers "$running_pid"
            return 0
        fi
        stop_all_helpers
        rm -f "$PID_FILE"
    fi
    # PID 文件失效时仍可能残留旧 helper，先清理孤儿，避免多个进程争写同一状态文件。
    stop_all_helpers
    [ "${1:-}" = "force" ] || ! helper_restart_cooling_down || return 0
    mkdir -p "$MODDIR/config" "$MODDIR/logs"
    rm -f "$PID_FILE"
    [ -f "$JAR" ] || {
        echo "[前台助手] 找不到 jar: $JAR" >> "$LOG"
        return 1
    }
    app_process=$(find_app_process) || {
        echo "[前台助手] 找不到 app_process" >> "$LOG"
        return 1
    }

    "$app_process" \
        -Djava.class.path="$JAR" \
        -Xnoimage-dex2oat \
        /system/bin \
        --nice-name="$NICE_NAME" \
        "$CLASS" "$STATE" >> "$LOG" 2>&1 </dev/null &
    echo $! > "$PID_FILE"
    return 0
}

stop_helper() {
    stop_all_helpers
    rm -f "$PID_FILE"
}

case "${1:-start}" in
    start) start_helper ;;
    stop) stop_helper ;;
    restart) stop_helper; start_helper force ;;
    status)
        if helper_running; then
            echo "running pid=$(cat "$PID_FILE")"
        else
            echo "stopped"
            exit 1
        fi
        ;;
    *) echo "用法: $0 [start|stop|restart|status]"; exit 2 ;;
esac
