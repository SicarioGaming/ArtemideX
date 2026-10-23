#!/usr/bin/env bash
# Runs the instrumented tests on an emulator, for the emulator job in .github/workflows/build.yml.
#
#   .github/tools/emulator-tests.sh SERIAL APP_APK TEST_APK OUT_DIR
#   .github/tools/emulator-tests.sh --parse INSTRUMENT_OUTPUT
#                                   just read the output of an earlier am instrument -r run
#
# It refuses a real device: the tests add and remove a test PC, and a real device may hold the
# pairing with someone's PC. It installs with adb install -r and never uninstalls anything.
# Exit codes: 0 all passed, 1 a test failed, 2 the test process crashed or didn't finish,
# 3 install failed, 4 not an emulator or bad arguments.

set -euo pipefail

TEST_RUNNER="com.starwire.debug.test/androidx.test.runner.AndroidJUnitRunner"

# Reads am instrument -r output. am exits 0 whether or not tests fail, so this is the verdict.
parse() {
    awk '
        { sub(/\r$/, "") }
        /^INSTRUMENTATION_STATUS: class=/ { cls = substr($0, index($0, "=") + 1) }
        /^INSTRUMENTATION_STATUS: test=/ { test = substr($0, index($0, "=") + 1) }
        /^INSTRUMENTATION_STATUS_CODE: / {
            code = $2
            if (code == 0) passed++
            else if (code == -2 || code == -1) { failed++; failures = failures "  " cls "#" test "\n" }
            else if (code == -3 || code == -4) skipped++
        }
        /^INSTRUMENTATION_RESULT: shortMsg=/ { crash = substr($0, index($0, "=") + 1) }
        /^INSTRUMENTATION_CODE: -1/ { finished = 1 }
        END {
            printf "%d passed, %d failed, %d skipped\n", passed, failed, skipped
            if (failed) printf "Failed:\n%s", failures
            if (crash != "") { printf "The test process stopped: %s\n", crash; exit 2 }
            if (!finished || passed + failed == 0) { print "The run did not finish"; exit 2 }
            exit failed ? 1 : 0
        }' "$1"
}

if [ "${1:-}" = "--parse" ] && [ $# -eq 2 ]; then
    parse "$2"
    exit $?
fi
if [ $# -ne 4 ]; then
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    exit 4
fi

serial=$1
app_apk=$2
test_apk=$3
out=$4

if [ -n "${ADB:-}" ]; then
    :
elif command -v adb >/dev/null 2>&1; then
    ADB=adb
else
    ADB="$ANDROID_HOME/platform-tools/adb"
fi
adb_() {
    "$ADB" -s "$serial" "$@"
}
prop() {
    adb_ shell getprop "$1" | tr -d '\r'
}

if [ "$(prop ro.kernel.qemu)" != 1 ] && [ "$(prop ro.boot.qemu)" != 1 ]; then
    echo "$serial is not an emulator; this script only runs on emulators" >&2
    exit 4
fi

mkdir -p "$out"
{
    echo "model: $(prop ro.product.model)"
    echo "android: $(prop ro.build.version.release) (API $(prop ro.build.version.sdk))"
    echo "abi: $(prop ro.product.cpu.abi)"
    adb_ shell wm size | tr -d '\r'
    adb_ shell wm density | tr -d '\r'
} | tee "$out/device.txt"

for apk in "$app_apk" "$test_apk"; do
    if ! adb_ install -r -t "$apk"; then
        echo "Installing $apk failed" >&2
        exit 3
    fi
done

adb_ logcat -c || true
set +e
adb_ shell am instrument -w -r "$TEST_RUNNER" | tr -d '\r' > "$out/instrument.txt"
set -e
adb_ logcat -d > "$out/logcat.txt" 2>&1 || true

set +e
parse "$out/instrument.txt" | tee "$out/summary.txt"
status=${PIPESTATUS[0]}
set -e
exit "$status"
