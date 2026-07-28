#!/system/bin/sh
set -eu

BASE=/data/local/yaos-a11y/runtime
mkdir -p "$BASE"
touch "$BASE/disabled"

if [ -r "$BASE/daemon.pid" ]; then
    daemon_pid="$(cat "$BASE/daemon.pid" 2>/dev/null || true)"
    if [ -n "$daemon_pid" ]; then
        kill "$daemon_pid" 2>/dev/null || true
    fi
fi

rm -f "$BASE/daemon.pid"
echo "DISABLE_OK"
