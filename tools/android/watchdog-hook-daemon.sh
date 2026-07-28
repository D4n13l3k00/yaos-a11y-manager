#!/system/bin/sh

BASE=/data/local/yaos-a11y/runtime
TARGET=com.yandex.tv.services.platform
LOG="$BASE/hook.log"
PID_FILE="$BASE/daemon.pid"
INJECTED_PID_FILE="$BASE/injected.pid"
ACTIVE_PID_FILE="$BASE/hook-active.pid"

umask 022
echo $$ > "$PID_FILE"
touch "$LOG"
chmod 644 "$PID_FILE" "$LOG"
echo "$(date '+%Y-%m-%d %H:%M:%S') monitor started" >> "$LOG"

while true; do
    target_pid="$(pidof "$TARGET" 2>/dev/null | awk '{print $1}')"
    injected_pid=""
    if [ -r "$INJECTED_PID_FILE" ]; then
        injected_pid="$(cat "$INJECTED_PID_FILE" 2>/dev/null)"
    fi

    if [ -n "$target_pid" ] && [ "$target_pid" != "$injected_pid" ]; then
        echo "$(date '+%Y-%m-%d %H:%M:%S') injecting pid=$target_pid" >> "$LOG"
        : > "$ACTIVE_PID_FILE"
        chmod 666 "$ACTIVE_PID_FILE"
        if "$BASE/frida-inject" \
            -p "$target_pid" \
            -s "$BASE/watchdog-hook.js" \
            -e >> "$LOG" 2>&1; then
            active_pid="$(cat "$ACTIVE_PID_FILE" 2>/dev/null || true)"
            if [ "$active_pid" = "$target_pid" ]; then
                echo "$target_pid" > "$INJECTED_PID_FILE"
                chmod 644 "$INJECTED_PID_FILE" "$ACTIVE_PID_FILE"
                echo "$(date '+%Y-%m-%d %H:%M:%S') injected pid=$target_pid" >> "$LOG"
            else
                echo "$(date '+%Y-%m-%d %H:%M:%S') agent did not confirm pid=$target_pid" >> "$LOG"
                sleep 2
            fi
        else
            echo "$(date '+%Y-%m-%d %H:%M:%S') injection failed pid=$target_pid" >> "$LOG"
            sleep 2
        fi
    fi

    if [ -n "$injected_pid" ] && ! kill -0 "$injected_pid" 2>/dev/null; then
        rm -f "$INJECTED_PID_FILE"
    fi

    sleep 1
done
