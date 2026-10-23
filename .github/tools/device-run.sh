#!/usr/bin/env bash
# Runs one leg of the emulator device matrix (.github/devices/matrix.json) on a device that's
# already booted: the job each leg of .github/workflows/devices.yml runs, and the way to run a leg
# by hand. Every pass is a device-test.sh run, so an emulator is tested the way a phone is:
#
#   1. the normal run: install, the instrumented suite, the smoke tests, the crash check
#   2. for a foldable, each posture, changed with the app open, then the layout tests
#      (variant_tests in matrix.json) and the smoke tests again in that posture
#   3. each variant (text size, display size, dark theme, a right-to-left language, the other
#      orientation), changed with the app open, then the layout and smoke tests again; for the
#      input variants (a virtual gamepad, the D-pad, the TV launcher), the input and a crash check
#   4. the smoke tests once more, then the leg's random input (device-test.sh --monkey)
#
#   .github/tools/device-run.sh --leg ID -s SERIAL [options]
#   .github/tools/device-run.sh --leg-json FILE [options]      (CI: the leg's matrix entry)
#
#   -s SERIAL       the device (default: ANDROID_SERIAL, which the emulator action sets)
#   --out DIR       where the results go (default build/device-tests/<leg id>)
#   --apk-dir DIR   where the built APKs are (default app/build/outputs/apk)
#   --fresh         empty DIR first
#
# Postures and variants change system settings, so they only happen on an emulator (a serial
# like emulator-5554 that says it's one), and each setting is put back after its pass. With any
# other serial this only does the normal run, through device-test.sh, which refuses listed
# devices before sending them anything; this script sends a real device nothing itself.
#
# Leaves in DIR: passes.tsv (a line per pass: name, result, passed, failed, skipped, note),
# failed-tests.txt, facts.env, exit-code, and a folder per pass with device-test.sh's results
# and screenshots. Exit codes are device-test.sh's, and the worst pass counts: 0 all good, 1 a
# test failed, 2 a crash, an ANR or a run that didn't finish, 3 an install failed, 4 a device
# problem or bad arguments.

set -uo pipefail
# Git Bash: hand /data/local/tmp/... to adb untouched. Local paths go through host_path instead.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PKG="${STARWIRE_PKG:-com.starwire.debug}"
ACTIVITY=com.limelight.PcView
DT="$HERE/device-test.sh"
BASE_LIMIT="${BASE_TIMEOUT:-50m}"
PASS_LIMIT="${PASS_TIMEOUT:-20m}"

usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-4}"
}

leg="" leg_json="" serial="${ANDROID_SERIAL:-}" out="" apk_dir="$ROOT/app/build/outputs/apk" fresh=0
while [ $# -gt 0 ]; do
    case "$1" in
        --leg) [ $# -ge 2 ] || usage; leg="$2"; shift 2 ;;
        --leg-json) [ $# -ge 2 ] || usage; leg_json="$2"; shift 2 ;;
        -s) [ $# -ge 2 ] || usage; serial="$2"; shift 2 ;;
        --out) [ $# -ge 2 ] || usage; out="$2"; shift 2 ;;
        --apk-dir) [ $# -ge 2 ] || usage; apk_dir="$2"; shift 2 ;;
        --fresh) fresh=1; shift ;;
        -h|--help) usage 0 ;;
        *) echo "unknown option: $1" >&2; usage ;;
    esac
done
{ [ -n "$leg$leg_json" ] && [ -n "$serial" ]; } || usage

# A Python 3 that works (on Windows, "python3" can be the Store's placeholder)
PY=""
for p in python3 python py; do
    if "$p" -c 'import sys; sys.exit(sys.version_info < (3, 8))' > /dev/null 2>&1; then PY="$p"; break; fi
done
[ -n "$PY" ] || { echo "device-run.sh needs Python 3.8 or later" >&2; exit 4; }
# adb and Python on Windows want C:/... rather than Git Bash's /c/...
host_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}
MATRIX_PY=$(host_path "$HERE/device-matrix.py")
if [ -n "$leg_json" ]; then which=(--leg-json "$(host_path "$leg_json")"); else which=(--leg "$leg"); fi
leg_env=$("$PY" "$MATRIX_PY" leg-env "${which[@]}") || exit 4
eval "$leg_env"

out="${out:-$ROOT/build/device-tests/$LEG_ID}"
[ "$fresh" = 1 ] && rm -rf "$out"
mkdir -p "$out"
: > "$out/booted"          # the workflow retries with a cold boot only when this is missing
: > "$out/passes.tsv"
: > "$out/failed-tests.txt"
rm -f "$out/exit-code" "$out/infra-failure"
{ "$PY" "$MATRIX_PY" leg-env "${which[@]}" --plain; echo "BOOT=${BOOT_MODE:-by hand}"; } > "$out/facts.env"

# The same adb as device-test.sh: two adb versions on one PC restart each other's server
if [ -z "${ADB:-}" ]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ]; then
        sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tr -d '\r' | sed 's/\\:/:/g; s/\\\\/\//g' | tail -n 1)
    fi
    for candidate in "$sdk/platform-tools/adb" "$sdk/platform-tools/adb.exe"; do
        if [ -n "$sdk" ] && [ -x "$candidate" ]; then ADB="$candidate"; break; fi
    done
    ADB="${ADB:-adb}"
fi
export ADB
adb_() { "$ADB" -s "$serial" "$@"; }
sh_() { "$ADB" -s "$serial" shell "$@" | tr -d '\r'; }

worst=0
rank() {  # how bad an exit code is, as device-test.sh ranks several devices
    case "$1" in 2) echo 4 ;; 1) echo 3 ;; 3) echo 2 ;; 4) echo 1 ;; *) echo 0 ;; esac
}
code_of() { case "$1" in PASS|SKIP) echo 0 ;; FAIL) echo 1 ;; CRASH) echo 2 ;; INSTALL) echo 3 ;; *) echo 4 ;; esac; }
record() {  # name result passed failed skipped note
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "${3:-0}" "${4:-0}" "${5:-0}" "${6:-}" >> "$out/passes.tsv"
    local code
    code=$(code_of "$2")
    if [ "$(rank "$code")" -gt "$(rank "$worst")" ]; then worst=$code; fi
    echo "== $1: $2${6:+ ($6)}"
}
restore_all=()
finish() {
    local cmd
    for cmd in ${restore_all[@]+"${restore_all[@]}"}; do sh_ "$cmd" > /dev/null 2>&1; done
    echo "$worst" > "$out/exit-code"
    [ "$worst" = 4 ] && : > "$out/infra-failure"
    echo "Leg $LEG_ID: $(awk -F'\t' '{printf "%s%s %s", (NR > 1 ? ", " : ""), $1, $2}' "$out/passes.tsv")"
    exit "$worst"
}

# One device-test.sh run into DIR/NAME. Sets pass_status, pass_counts ("passed failed skipped")
# and pass_note.
pass_status="" pass_counts="0 0 0" pass_note=""
run_pass() {  # name time-limit device-test.sh options...
    local name="$1" limit="$2" rc res
    shift 2
    mkdir -p "$out/$name"
    timeout --kill-after=30s "$limit" bash "$DT" -s "$serial" --apk-dir "$apk_dir" --out "$out/$name" "$@" \
        > "$out/$name/run.txt" 2>&1
    rc=$?
    res="$out/$name/${serial//[^A-Za-z0-9._-]/_}"
    pass_counts=$(for f in suite-results.txt smoke-results.txt; do [ -f "$res/$f" ] && tail -n 1 "$res/$f"; done |
        awk '/ passed, .* failed, .* skipped/ {p += $1; f += $3; s += $5} END {printf "%d %d %d", p, f, s}')
    for f in suite-results.txt smoke-results.txt; do
        [ -f "$res/$f" ] && grep -E '^(FAIL |RUN PROBLEM)' "$res/$f" | sed "s/^/$name: /" >> "$out/failed-tests.txt"
    done
    pass_note=""
    case "$rc" in
        0) pass_status=PASS ;;
        1) pass_status=FAIL ;;
        2) pass_status=CRASH ;;
        3) pass_status=INSTALL ;;
        124|137) pass_status=CRASH; pass_note="didn't finish in $limit" ;;
        *) pass_status=DEVICE; pass_note=$(head -n 1 "$res/notes.txt" 2>/dev/null) ;;
    esac
    if [ -s "$res/crashes.txt" ]; then pass_note="${pass_note:+$pass_note; }see crashes.txt"; fi
}

# ------------------------------------------------------------------- a phone, tablet or handheld
normal_run_only() {
    echo "EMULATOR=false" >> "$out/facts.env"
    run_pass base "$BASE_LIMIT"
    local info
    info=$(cat "$out/base/${serial//[^A-Za-z0-9._-]/_}/device.txt" 2>/dev/null)
    {
        echo "SDK=$(sed -n 's/.*Android API \([0-9.]*\),.*/\1/p' <<< "$info")"
        echo "SIZE=$(sed -n 's/.*, \([0-9]*x[0-9]*\) at .*/\1/p' <<< "$info")"
        echo "DENSITY=$(sed -n 's/.* at \([0-9]*\) dpi.*/\1/p' <<< "$info")"
    } >> "$out/facts.env"
    read -r p f s <<< "$pass_counts"
    record base "$pass_status" "$p" "$f" "$s" "$pass_note"
    for x in $LEG_POSTURES $LEG_VARIANTS; do record "$x" SKIP 0 0 0 "not an emulator"; done
    [ "$LEG_MONKEY" -gt 0 ] && record monkey SKIP 0 0 0 "not an emulator"
    finish
}
case "$serial" in
    emulator-*) ;;
    *) normal_run_only ;;
esac

# --------------------------------------------------------------------------------- an emulator
if [ "$("$ADB" -s "$serial" get-state 2>/dev/null | tr -d '\r')" != device ]; then
    record setup DEVICE 0 0 0 "$serial is not connected"
    finish
fi
case "$(sh_ getprop ro.boot.qemu)$(sh_ getprop ro.kernel.qemu)" in
    *1*) ;;
    *) normal_run_only ;;
esac
SDK=$(sh_ getprop ro.build.version.sdk)
SIZE=$(sh_ wm size | awk -F': ' '/Physical size/ {print $2}' | tail -n 1)
DENSITY=$(sh_ wm density | awk -F': ' '/Physical density/ {print $2}' | tail -n 1)
{
    echo "EMULATOR=true"
    echo "SDK=$SDK"
    echo "MODEL=$(sh_ getprop ro.product.model)"
    echo "ABI=$(sh_ getprop ro.product.cpu.abi)"
    echo "FINGERPRINT=$(sh_ getprop ro.build.fingerprint)"
    echo "SIZE=$SIZE"
    echo "DENSITY=$DENSITY"
} >> "$out/facts.env"
echo "Leg $LEG_ID ($LEG_NAME): API $SDK, $SIZE at $DENSITY dpi"

# The smoke tests need the screen on and unlocked: keep it on while plugged in, and unlock it
# before each run (an emulator's lock screen has no PIN)
stay=$(sh_ settings get global stay_on_while_plugged_in)
[[ "$stay" =~ ^[0-9]+$ ]] && restore_all+=("settings put global stay_on_while_plugged_in $stay")
sh_ svc power stayon true > /dev/null 2>&1
wake() {
    local state
    sh_ input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
    state=$(sh_ dumpsys window)
    if grep -qE 'isKeyguardShowing=true|mShowingLockscreen=true|mDreamingLockscreen=true' <<< "$state"; then
        sh_ wm dismiss-keyguard > /dev/null 2>&1
        sh_ input keyevent 82 > /dev/null 2>&1
    fi
}
open_app() {
    sh_ am force-stop "$PKG" > /dev/null
    sh_ am start -W -n "$PKG/$ACTIVITY" > /dev/null
    sleep 2
}
in_front() {
    local state
    state=$(sh_ dumpsys activity activities)
    grep -qE "(mResumedActivity|topResumedActivity|ResumedActivity:).* ${PKG//./\\.}/" <<< "$state"
}
clear_logs() {
    adb_ logcat -c > /dev/null 2>&1
    adb_ logcat -b crash -c > /dev/null 2>&1
    true
}
crashed_since_clear() {  # dir: true when the app crashed or stopped responding since clear_logs
    local pkg_re="${PKG//./\\.}"
    adb_ logcat -d -b crash 2>/dev/null | tr -d '\r' > "$1/change-logcat-crash.txt"
    adb_ logcat -d -b main,system,events 2>/dev/null | tr -d '\r' > "$1/change-logcat.txt"
    grep -hE "^Process: $pkg_re\$|Process: $pkg_re,|>>> $pkg_re <<<|ANR in $pkg_re( |\$)|am_anr.*,$pkg_re," \
        "$1/change-logcat-crash.txt" "$1/change-logcat.txt" > "$1/change-crashes.txt"
    [ -s "$1/change-crashes.txt" ]
}
screenshot() { adb_ exec-out screencap -p > "$1" 2>/dev/null || rm -f "$1"; }
# After a change with the app open: did it survive, and is it still in front? Sets change_status
# and change_note.
check_change() {  # dir what-changed
    sleep 3
    screenshot "$1/after-change.png"
    change_status=PASS change_note="$2 with the app open"
    if crashed_since_clear "$1"; then
        change_status=CRASH change_note="the app crashed when $2 (change-crashes.txt)"
    elif ! in_front; then
        change_status=FAIL change_note="the app wasn't in front after $2"
    fi
}
# Records a posture or variant: the change, then the layout and smoke tests in that state
tests_after_change() {  # name dir
    local p f s st
    wake
    run_pass "$1" "$PASS_LIMIT" --no-install --only "$VARIANT_TESTS"
    st=$pass_status
    if [ "$(rank "$(code_of "$change_status")")" -gt "$(rank "$(code_of "$st")")" ]; then st=$change_status; fi
    read -r p f s <<< "$pass_counts"
    record "$1" "$st" "$p" "$f" "$s" "$change_note${pass_note:+; $pass_note}"
}

# ---------------------------------------------------------------------------- 1. the normal run
wake
if [ -n "$LEG_INSTALL_ABI" ]; then
    # Experimental: the app installed as another ABI, run through the image's translation
    mkdir -p "$out/base"
    result=$(adb_ install -r -t --abi "$LEG_INSTALL_ABI" "$(host_path "$apk_dir/nonRoot/debug/app-nonRoot-debug.apk")" 2>&1 | tr -d '\r')
    printf '%s\n' "$result" > "$out/base/install-abi.txt"
    if ! grep -q '^Success' <<< "$result"; then
        if grep -q NO_MATCHING_ABIS <<< "$result"; then
            record base SKIP 0 0 0 "this image can't run $LEG_INSTALL_ABI code"
        else
            record base INSTALL 0 0 0 "installing as $LEG_INSTALL_ABI failed (install-abi.txt)"
        fi
        finish
    fi
    result=$(adb_ install -r -t "$(host_path "$apk_dir/androidTest/nonRoot/debug/app-nonRoot-debug-androidTest.apk")" 2>&1 | tr -d '\r')
    printf '%s\n' "$result" >> "$out/base/install-abi.txt"
    grep -q '^Success' <<< "$result" || { record base INSTALL 0 0 0 "installing the tests failed"; finish; }
    abi=$(sh_ dumpsys package "$PKG" | awk -F= '/primaryCpuAbi/ {print $2; exit}')
    echo "PRIMARY_ABI=$abi" >> "$out/facts.env"
    run_pass base "$BASE_LIMIT" --no-install
    read -r p f s <<< "$pass_counts"
    record base "$pass_status" "$p" "$f" "$s" "installed as $LEG_INSTALL_ABI, primary ABI $abi${pass_note:+; $pass_note}"
    finish
fi
run_pass base "$BASE_LIMIT"
read -r p f s <<< "$pass_counts"
record base "$pass_status" "$p" "$f" "$s" "$pass_note"
case "$pass_status" in INSTALL|DEVICE) finish ;; esac

# ---------------------------------------------------------------------------- 2. fold postures
state_override=0
set_posture() {  # folded|half-open|unfolded: 0 when the emulator took it
    local reply want states id
    case "$1" in
        folded) reply=$(adb_ emu fold 2>&1 | tr -d '\r') ;;
        unfolded) reply=$(adb_ emu unfold 2>&1 | tr -d '\r') ;;
        *) reply="" ;;
    esac
    grep -q '^OK' <<< "$reply" && return 0
    # Otherwise the device_state service (Android 12L and later)
    case "$1" in folded) want=CLOSE ;; half-open) want=HALF_OPEN ;; *) want=OPEN ;; esac
    states=$(sh_ cmd device_state print-states 2>/dev/null)
    id=$(grep -oE "identifier=[0-9]+, name='[A-Za-z_]+'" <<< "$states" |
        awk -F"[=,']" -v w="$want" 'index($5, w) == 1 {print $2; exit}')
    [ -n "$id" ] || return 1
    sh_ cmd device_state state "$id" > /dev/null 2>&1 || return 1
    state_override=1
}
for posture in $LEG_POSTURES; do
    dir="$out/$posture"
    mkdir -p "$dir"
    wake
    open_app
    clear_logs
    if ! set_posture "$posture"; then
        record "$posture" SKIP 0 0 0 "this emulator has no $posture posture"
        continue
    fi
    check_change "$dir" "it was $posture"
    { sh_ wm size; sh_ cmd device_state print-state 2>/dev/null; } > "$dir/state.txt"
    tests_after_change "$posture" "$dir"
done
if [ -n "$LEG_POSTURES" ]; then
    set_posture unfolded
    [ "$state_override" = 1 ] && sh_ cmd device_state state reset > /dev/null 2>&1
fi

# ---------------------------------------------------------------------------------- 3. variants
# What Settings > Display size calls largest (SettingsLib DisplayDensityUtils), or nothing when
# the screen is too small for a larger size
largest_density() {
    local w="${SIZE%x*}" h="${SIZE#*x}"
    awk -v w="$w" -v h="$h" -v d="$DENSITY" 'BEGIN {
        m = (w < h) ? w : h; maxd = int(160 * m / 320); s = maxd / d; if (s > 1.5) s = 1.5
        n = int((s - 1) / 0.09); if (n > 3) n = 3; if (n < 1) exit 1
        v = int(d * s); v -= v % 2; print v }'
}
gamepad_json() {
    # A virtual Xbox One S pad (045e:02ea, so Android's own key layout applies) with rumble:
    # D-pad down twice, then A, then B. Numbers rather than names, which older uinput reads too.
    cat <<'EOF'
{"id":1,"command":"register","name":"Starwire test pad","vid":1118,"pid":746,"bus":"usb",
 "configuration":[{"type":100,"data":[1,3,21]},
                  {"type":101,"data":[304,305,307,308,310,311,314,315,316,317,318]},
                  {"type":103,"data":[0,1,2,3,4,5,16,17]},
                  {"type":107,"data":[80]}],
 "ff_effects_max":1,
 "abs_info":[{"code":0,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128,"resolution":0}},
             {"code":1,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128,"resolution":0}},
             {"code":2,"info":{"value":0,"minimum":0,"maximum":1023,"fuzz":0,"flat":0,"resolution":0}},
             {"code":3,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128,"resolution":0}},
             {"code":4,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128,"resolution":0}},
             {"code":5,"info":{"value":0,"minimum":0,"maximum":1023,"fuzz":0,"flat":0,"resolution":0}},
             {"code":16,"info":{"value":0,"minimum":-1,"maximum":1,"fuzz":0,"flat":0,"resolution":0}},
             {"code":17,"info":{"value":0,"minimum":-1,"maximum":1,"fuzz":0,"flat":0,"resolution":0}}]}
{"id":1,"command":"delay","duration":2000}
{"id":1,"command":"inject","events":[3,17,1, 0,0,0, 3,17,0, 0,0,0]}
{"id":1,"command":"delay","duration":400}
{"id":1,"command":"inject","events":[3,17,1, 0,0,0, 3,17,0, 0,0,0]}
{"id":1,"command":"delay","duration":400}
{"id":1,"command":"inject","events":[1,304,1, 0,0,0, 1,304,0, 0,0,0]}
{"id":1,"command":"delay","duration":1500}
{"id":1,"command":"inject","events":[1,305,1, 0,0,0, 1,305,0, 0,0,0]}
{"id":1,"command":"delay","duration":1500}
EOF
}
for v in $LEG_VARIANTS; do
    dir="$out/$v" skip="" apply=() restore=() language=""
    mkdir -p "$dir"
    case "$v" in
        font-*)
            scale="${v#font-}" was=$(sh_ settings get system font_scale)
            if [ "${SDK:-0}" -lt 34 ] && awk -v s="$scale" 'BEGIN {exit !(s > 1.3)}'; then
                skip="text above 130% needs Android 14"
            else
                apply=("settings put system font_scale $scale")
                [[ "$was" =~ ^[0-9.]+$ ]] || was=1.0
                restore=("settings put system font_scale $was")
            fi ;;
        display-largest)
            if d=$(largest_density); then
                apply=("wm density $d") restore=("wm density reset")
                echo "density $DENSITY -> $d" > "$dir/state.txt"
            else
                skip="the screen is too small for a larger display size"
            fi ;;
        dark)
            if [ "${SDK:-0}" -ge 29 ]; then
                apply=("cmd uimode night yes") restore=("cmd uimode night no")
            else
                skip="the system dark theme needs Android 10"
            fi ;;
        rtl-*)
            if [ "${SDK:-0}" -ge 33 ]; then
                language="${v#rtl-}"
                apply=("cmd locale set-app-locales $PKG --locales $language")
                restore=("cmd locale set-app-locales $PKG")
            else
                skip="a language for one app needs Android 13"
            fi ;;
        landscape|portrait)
            natural=portrait
            [ "${SIZE%x*}" -gt "${SIZE#*x}" ] 2>/dev/null && natural=landscape
            if [ "$v" = "$natural" ]; then
                skip="the screen is $v already"
            else
                rot=$(sh_ settings get system user_rotation) acc=$(sh_ settings get system accelerometer_rotation)
                [[ "$rot" =~ ^[0-9]+$ ]] || rot=0
                [[ "$acc" =~ ^[0-9]+$ ]] || acc=1
                apply=("settings put system accelerometer_rotation 0" "settings put system user_rotation 1")
                restore=("settings put system user_rotation $rot" "settings put system accelerometer_rotation $acc")
            fi ;;
        gamepad)
            [ -n "$(sh_ 'ls /system/bin/uinput 2>/dev/null')" ] || skip="this Android has no uinput tool" ;;
        tv-launcher|dpad)
            features=$(sh_ pm list features)
            grep -q 'feature:android.software.leanback$' <<< "$features" || skip="not a TV image" ;;
    esac
    if [ -n "$skip" ]; then
        record "$v" SKIP 0 0 0 "$skip"
        continue
    fi
    wake
    open_app
    clear_logs
    for cmd in ${apply[@]+"${apply[@]}"}; do sh_ "$cmd" > /dev/null; done
    if [ -n "$language" ]; then
        # Android answers "Locales for <app> for user 0 are [he]"
        took=$(sh_ cmd locale get-app-locales "$PKG")
        echo "$took" > "$dir/state.txt"
        if ! grep -qE "\[([^]]*,)?$language([-,]|\])" <<< "$took"; then
            for cmd in ${restore[@]+"${restore[@]}"}; do sh_ "$cmd" > /dev/null; done
            record "$v" SKIP 0 0 0 "the app's language didn't change to $language (state.txt)"
            continue
        fi
    fi
    case "$v" in
        gamepad)
            gamepad_json > "$dir/gamepad.json"
            adb_ push "$(host_path "$dir/gamepad.json")" /data/local/tmp/starwire-gamepad.json > /dev/null 2>&1
            if timeout 60 "$ADB" -s "$serial" shell uinput /data/local/tmp/starwire-gamepad.json > "$dir/uinput.txt" 2>&1; then
                how="a virtual gamepad pressed down, down, A and B"
            else
                how="uinput didn't take the virtual gamepad (uinput.txt), so only the crash check counts"
            fi
            sh_ rm -f /data/local/tmp/starwire-gamepad.json > /dev/null
            check_change "$dir" "$how"
            record "$v" "$change_status" 0 0 0 "$change_note" ;;
        dpad)
            for key in 20 20 22 19 23; do sh_ input keyevent "$key" > /dev/null; sleep 0.5; done
            check_change "$dir" "the D-pad pressed down, down, right, up and center"
            record "$v" "$change_status" 0 0 0 "$change_note" ;;
        tv-launcher)
            sh_ cmd package query-activities --brief -a android.intent.action.MAIN \
                -c android.intent.category.LEANBACK_LAUNCHER > "$dir/leanback.txt" 2>&1
            check_change "$dir" "it opened"
            if [ "$change_status" = PASS ] && ! grep -q "$PKG/" "$dir/leanback.txt"; then
                change_status=FAIL change_note="the TV launcher doesn't list it (LEANBACK_LAUNCHER)"
            fi
            record "$v" "$change_status" 0 0 0 "$change_note" ;;
        *)
            check_change "$dir" "$v was switched on"
            tests_after_change "$v" "$dir" ;;
    esac
    for cmd in ${restore[@]+"${restore[@]}"}; do sh_ "$cmd" > /dev/null; done
done

# ------------------------------------------------------------------------------ 4. random input
if [ "$LEG_MONKEY" -gt 0 ]; then
    wake
    run_pass monkey "$PASS_LIMIT" --no-install --smoke-only --monkey "$LEG_MONKEY"
    read -r p f s <<< "$pass_counts"
    line=$(sed -n 's/^Monkey: *//p' "$out/monkey/${serial//[^A-Za-z0-9._-]/_}/summary.txt" 2>/dev/null)
    st=$pass_status
    case "$st:$line" in PASS:skipped*|PASS:not\ run*|PASS:) st=SKIP ;; esac
    record monkey "$st" "$p" "$f" "$s" "smoke tests, then ${line:-no monkey line in summary.txt}${pass_note:+; $pass_note}"
fi
sh_ am force-stop "$PKG" > /dev/null 2>&1
finish
