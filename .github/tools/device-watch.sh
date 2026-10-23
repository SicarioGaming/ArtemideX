#!/usr/bin/env bash
# Ends an emulator action step early when the emulator quits before it has booted.
#
#   device-watch.sh --out DIR      (the action's pre-emulator-launch-script, in .github/workflows/devices.yml)
#
# ReactiveCircus/android-emulator-runner starts the emulator in the background and then asks adb
# whether it has booted until emulator-boot-timeout runs out, even when the emulator quit in its
# first second (as it did on every leg of the first private run, with "Not enough space to create
# userdata partition"): 15 minutes of a paid runner waiting for nothing. This starts a watcher in
# the background and returns. The watcher stops once the emulator has booted. If for GRACE seconds
# (60) there's neither an emulator process nor an emulator in adb devices, it writes
# DIR/emulator-quit.txt and stops the action, so the step fails a minute in instead. The
# emulator's own message is in the step's log, above the adb lines.
#
# Settings: PORT (5554, the action's emulator-port), GRACE (60), INTERVAL (5 seconds between
# looks), LIMIT (1800 seconds, after which the watcher gives up). The watcher's own log goes to
# RUNNER_TEMP/device-watch.log.

set -uo pipefail

out=""
while [ $# -gt 0 ]; do
    case "$1" in
        --out) [ $# -ge 2 ] || exit 4; out="$2"; shift 2 ;;
        --watch) watching=1; shift ;;
        *) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 4 ;;
    esac
done
[ -n "$out" ] || { echo "device-watch.sh: --out DIR" >&2; exit 4; }
mkdir -p "$out"
rm -f "$out/emulator-quit.txt"

if [ -z "${watching:-}" ]; then
    # The action waits for its script's output to close, so the watcher gets none of it
    log="${RUNNER_TEMP:-/tmp}/device-watch.log"
    nohup bash "$0" --out "$out" --watch > "$log" 2>&1 < /dev/null &
    echo "Watching the emulator (it gets ${GRACE:-60} s to show up; log in $log)"
    exit 0
fi

PORT="${PORT:-5554}" GRACE="${GRACE:-60}" INTERVAL="${INTERVAL:-5}" LIMIT="${LIMIT:-1800}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
adb="$sdk/platform-tools/adb"
[ -x "$adb" ] || adb=adb
start=$(date +%s)
seen=$start
while :; do
    now=$(date +%s)
    if [ "$("$adb" -s "emulator-$PORT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
        echo "booted after $((now - start)) s"
        exit 0
    fi
    # The launcher, then the qemu it starts; or adb listing it, even offline
    if pgrep -f 'qemu-system-|/emulator/emulator ' > /dev/null || "$adb" devices 2>/dev/null | grep -q "^emulator-$PORT[[:space:]]"; then
        seen=$now
    elif [ $((now - seen)) -ge "$GRACE" ]; then
        echo "The emulator quit before it booted: no emulator process and nothing on port $PORT for $GRACE s. Its own message is in the step's log (look for FATAL, ERROR or PANIC)." \
            > "$out/emulator-quit.txt"
        cat "$out/emulator-quit.txt"
        pkill -TERM -f '_actions/ReactiveCircus/android-emulator-runner/' || echo "found no emulator action to stop"
        exit 1
    fi
    if [ $((now - start)) -ge "$LIMIT" ]; then
        echo "gave up after $LIMIT s"
        exit 0
    fi
    sleep "$INTERVAL"
done
