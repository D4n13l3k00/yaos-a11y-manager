#!/system/bin/sh
set -eu

STAGE=/data/local/tmp/yaos-a11y-stage
BASE=/data/local/yaos-a11y/runtime
TARGET=com.yandex.tv.services.platform
FRIDA_SHA256=e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea

frida_source="$STAGE/frida-inject"
if [ ! -f "$frida_source" ]; then
    frida_source="$BASE/frida-inject"
fi
actual_hash="$(sha256sum "$frida_source" 2>/dev/null | awk '{print $1}')"
if [ "$actual_hash" != "$FRIDA_SHA256" ]; then
    echo "frida-inject checksum mismatch"
    exit 1
fi

mkdir -p "$BASE"
chmod 755 /data/local/yaos-a11y "$BASE"

if [ -r "$BASE/daemon.pid" ]; then
    old_daemon="$(cat "$BASE/daemon.pid" 2>/dev/null || true)"
    if [ -n "$old_daemon" ]; then
        kill "$old_daemon" 2>/dev/null || true
    fi
fi

if [ "$frida_source" != "$BASE/frida-inject" ]; then
    cp "$frida_source" "$BASE/frida-inject"
fi
cp "$STAGE/watchdog-hook.js" "$BASE/watchdog-hook.js"
cp "$STAGE/watchdog-hook-daemon.sh" "$BASE/watchdog-hook-daemon.sh"
cp "$STAGE/disable-hook.sh" "$BASE/disable-hook.sh"
chmod 755 "$BASE/frida-inject" "$BASE/watchdog-hook-daemon.sh" "$BASE/disable-hook.sh"
chmod 644 "$BASE/watchdog-hook.js"
rm -f "$BASE/disabled"

target_pid="$(pidof "$TARGET" 2>/dev/null | awk '{print $1}')"
active_pid="$(cat "$BASE/hook-active.pid" 2>/dev/null || true)"
if [ -z "$target_pid" ] || [ "$active_pid" != "$target_pid" ]; then
    rm -f "$BASE/injected.pid" "$BASE/hook-active.pid"
fi

for target_pid in $(pidof "$TARGET" 2>/dev/null || true); do
    kill -CONT "$target_pid" 2>/dev/null || true
done

nohup sh "$BASE/watchdog-hook-daemon.sh" >/dev/null 2>&1 &
echo "INSTALL_OK"
