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

find_app_process() {
    for candidate in /system/bin/app_process /system/bin/app_process64 /system/bin/app_process32; do
        [ -x "$candidate" ] && {
            echo "$candidate"
            return 0
        }
    done
    return 1
}

helper_running() {
    [ -f "$PID_FILE" ] || return 1
    pid=$(cat "$PID_FILE" 2>/dev/null)
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -d "/proc/$pid" ] || return 1
    cmdline=$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
    case "$cmdline" in
        *"$NICE_NAME"*|*"$CLASS"*) return 0 ;;
        *) return 1 ;;
    esac
}

start_helper() {
    helper_running && return 0
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
    if helper_running; then
        kill "$(cat "$PID_FILE")" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
}

case "${1:-start}" in
    start) start_helper ;;
    stop) stop_helper ;;
    restart) stop_helper; start_helper ;;
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
