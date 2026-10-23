#!/usr/bin/env bash
# Checks that the scripts in .github/tools still work, so a moved file or a renamed app ID can't
# break one without anyone noticing (moving the tools here in 0a95c91a broke pacer-trace.sh that
# way). CI runs it on every push. It needs no device and no network.
#
#   .github/tools/check-tools.sh                      run the checks
#   .github/tools/check-tools.sh --make-pacer-sample  rewrite fixtures/pacer-trace-sample.csv
#
# Replaying the sample trace needs a C++17 compiler (CXX, or g++ or clang++ on PATH). Without one
# that check is skipped with a note, except when CI is set, where it fails.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SAMPLE="$HERE/fixtures/pacer-trace-sample.csv"
failures=0

fail() {
    echo "FAIL: $*" >&2
    failures=$((failures + 1))
}

# Each check starts with begin, and ends with ok, which says so if it found nothing wrong
section=0
begin() {
    section=$failures
}
ok() {
    if [ $failures -eq $section ]; then echo "ok: $*"; fi
}

# A made-up trace in the format pacer_trace.h describes: 4 s of 60 fps on a 60 Hz screen, each
# frame arriving 6 to 10 ms before the vsync it's shown at. Fixed numbers, nothing random.
make_pacer_sample() {
    awk 'BEGIN {
        period = 16666667; start = 10000000000; host = 5000000000; frames = 240
        print "# moonlight pacer trace v1"
        print "# Made up, not recorded: 4 s of a 60 fps stream on a 60 Hz screen, each frame arriving"
        print "# 6 to 10 ms before its vsync. check-tools.sh replays it to show that pacer-trace.sh and"
        print "# pacer_replay still work, and makes it again with: check-tools.sh --make-pacer-sample"
        print "C,mode=4,jitterBuffer=1,presentAhead=0,streamFps=60,width=1920,height=1080,periodNs=" period \
              ",refreshHz=60.000,tenBit=0,dither=0,device=sample"
        print "R," period
        for (i = 0; i < frames; i++) {
            vsync = start + i * period
            pts = host + i * period
            early = 6000000 + ((i * 7) % 5) * 1000000
            printf "F,%.0f,%.0f,%.0f,1,0\n", vsync - early, pts, vsync
            printf "V,%.0f,1,0,%.0f,%d,1,0,0,%.0f,%.0f,0.0,100000,0,0\n", vsync, pts, i >= 30, early, period
        }
    }'
}

if [ "${1:-}" = "--make-pacer-sample" ]; then
    mkdir -p "$(dirname "$SAMPLE")"
    make_pacer_sample > "$SAMPLE"
    echo "Wrote $SAMPLE"
    exit 0
fi

cd "$ROOT"

# 1. Every shell script in the repository parses
begin
scripts=$(git ls-files '*.sh' 2>/dev/null || find . -name '*.sh' -not -path './.git/*')
count=0
for f in $scripts; do
    count=$((count + 1))
    bash -n "$f" || fail "$f doesn't parse"
done
ok "$count shell scripts parse"

# 2. The workflows and the tools only call tools that exist. Docs only get a note: they may name
# a script that's still being written.
begin
missing() {
    grep -oh '\.github/tools/[A-Za-z0-9_./-]*' "$@" 2>/dev/null | sed 's/[.]*$//' | sort -u |
        while read -r path; do [ -e "$path" ] || echo "$path"; done
}
for path in $(missing .github/workflows/*.yml $(git ls-files .github/tools)); do
    fail "the workflows or tools use $path, which doesn't exist"
done
for path in $(missing $(git ls-files '*.md')); do
    echo "note: a doc mentions $path, which doesn't exist (yet)"
done
ok "the workflows and tools only use tools that exist"

# 3. App IDs the tools default to are ones the build makes (pacer-trace.sh kept Moonlight X's)
begin
ids=$(grep -o 'applicationId "[^"]*"' app/build.gradle | cut -d'"' -f2 | tr '\n' ' ' | sed 's/ $//')
suffix=$(grep -o 'applicationIdSuffix "[^"]*"' app/build.gradle | cut -d'"' -f2 | head -n 1)
for tool in .github/tools/*.sh; do
    for id in $(grep -o ':-com\.[A-Za-z0-9_.]*}' "$tool" | sed 's/^:-//; s/}$//'); do
        known=no
        for app in $ids; do
            case "$id" in "$app" | "$app$suffix" | "$app$suffix.test") known=yes ;; esac
        done
        [ "$known" = yes ] || fail "$tool defaults to $id, which the build doesn't make ($ids, and $suffix)"
    done
done
ok "the tools' default app IDs are this app's"

# 4. pacer-trace.sh prints its help without a device
begin
set +e
help=$(bash .github/tools/pacer-trace.sh 2>&1)
status=$?
set -e
if [ $status -ne 2 ] || ! printf '%s\n' "$help" | grep -q 'analyze \[file'; then
    fail "pacer-trace.sh without a command should print its help and exit 2 (exit $status)"
else
    ok "pacer-trace.sh prints its help"
fi

# 5. The sample trace is the one make_pacer_sample makes
begin
if make_pacer_sample | diff -q --strip-trailing-cr - "$SAMPLE" >/dev/null; then
    ok "the sample trace is up to date"
else
    fail "$SAMPLE differs from what --make-pacer-sample makes"
fi

# 6. pacer-trace.sh builds the replay tool from this checkout and replays the sample
begin
cxx="${CXX:-}"
if [ -z "$cxx" ]; then
    for candidate in g++ clang++; do
        if command -v "$candidate" >/dev/null 2>&1; then cxx="$candidate"; break; fi
    done
fi
if [ -z "$cxx" ]; then
    if [ -n "${CI:-}" ]; then
        fail "no C++ compiler to build the pacer replay tool"
    else
        echo "skipped: replaying the sample trace (no C++ compiler; set CXX)"
    fi
else
    work="$(mktemp -d)"
    set +e
    replay=$(CXX="$cxx" PACER_TRACE_DIR="$work" ADB=/nonexistent/adb \
        bash .github/tools/pacer-trace.sh analyze "$SAMPLE" 2>&1)
    status=$?
    set -e
    rm -rf "$work"
    if [ $status -ne 0 ]; then
        printf '%s\n' "$replay" >&2
        fail "pacer-trace.sh analyze failed on the sample trace (exit $status)"
    elif ! printf '%s\n' "$replay" | grep -q 'Replayed through the current pacer' ||
         ! printf '%s\n' "$replay" | grep -q ' of 240 vsync decisions differ'; then
        printf '%s\n' "$replay" >&2
        fail "pacer-trace.sh analyze didn't replay all 240 vsyncs of the sample trace"
    else
        ok "pacer-trace.sh replays the sample trace"
    fi
fi

# 7. parse-instrument.sh gives the right verdict on am instrument output recorded on a tablet
begin
verdict() {  # fixture, expected exit code, a line its summary must have
    local out status
    set +e
    out=$(bash .github/tools/parse-instrument.sh "$HERE/fixtures/$1" 2>&1)
    status=$?
    set -e
    if [ $status -ne "$2" ] || ! grep -qxF "$3" <<< "$out"; then
        printf '%s\n' "$out" >&2
        fail "parse-instrument.sh on $1: exit $status (expected $2), or no line \"$3\""
    fi
}
verdict instrument-pass.txt 0 "1 passed, 0 failed, 0 skipped"
verdict instrument-mixed.txt 1 "SKIP com.limelight.devicecheck.MonkeyPreconditionTest#noSavedPc (only for device-test.sh --monkey)"
verdict instrument-mixed.txt 1 "1 passed, 1 failed, 1 skipped"
verdict instrument-crash.txt 2 "RUN PROBLEM: Process crashed."
verdict instrument-none.txt 2 "RUN PROBLEM: no tests ran"
ok "parse-instrument.sh reads passes, skips, failures, a crash and an empty run"

# 8. device-test.sh, against a stand-in for adb (fixtures/fake-adb.sh): it refuses listed
# devices without sending them anything, stops for a locked screen or an open stream, never
# uninstalls, and sends random input only where MonkeyPreconditionTest passed
begin
work="$(mktemp -d)"
printf '#!/usr/bin/env bash\nexec bash "%s" "$@"\n' "$HERE/fixtures/fake-adb.sh" > "$work/adb"
chmod +x "$work/adb"
mkdir -p "$work/apk/nonRoot/debug" "$work/apk/androidTest/nonRoot/debug"
touch "$work/apk/nonRoot/debug/app-nonRoot-debug.apk" \
      "$work/apk/androidTest/nonRoot/debug/app-nonRoot-debug-androidTest.apk"
: > "$work/all-calls"
fake_run() {  # expected exit code, NAME=value settings, then device-test.sh's arguments
    local expected=$1 status
    shift
    local settings=()
    while [[ "${1:-}" =~ ^[A-Z_][A-Z0-9_]*= ]]; do settings+=("$1"); shift; done
    : > "$work/calls"
    set +e
    env "${settings[@]}" FAKE_ADB_LOG="$work/calls" ADB="$work/adb" \
        bash .github/tools/device-test.sh --apk-dir "$work/apk" --out "$work/out" "$@" > "$work/output" 2>&1
    status=$?
    set -e
    cat "$work/calls" >> "$work/all-calls"
    if [ $status -ne "$expected" ]; then
        cat "$work/output" >&2
        fail "device-test.sh ${settings[*]} $*: exit $status, expected $expected"
    fi
}
called() {
    grep -qF -- "$1" "$work/calls"
}
denied_hash="sha256:$(printf '%s' FAKE-DENIED | sha256sum | cut -d' ' -f1)"
for deny in FAKE-DENIED Fake_Phone "$denied_hash"; do
    fake_run 4 STARWIRE_DENY_DEVICES="$deny" -s FAKE-DENIED
    if called "-s FAKE-DENIED"; then fail "device-test.sh sent a refused device (by $deny) a command"; fi
done
fake_run 4 STARWIRE_DENY_DEVICES=FAKESER -s adb-FAKESER-x1y2._adb-tls-connect._tcp
if called "-s adb-FAKESER"; then fail "device-test.sh sent a refused wireless device a command"; fi
if sed -n '/^DENY_DEVICES="/,/"$/p' .github/tools/device-test.sh | tr -d '"' | sed 's/^DENY_DEVICES=//' |
        grep -vqE '^sha256:[0-9a-f]{64}$'; then
    fail "device-test.sh's own list of refused devices has an entry that isn't sha256:<64 hex digits>"
fi
# Several devices in one run: the refused one is skipped, the other tested, the worst exit wins
fake_run 4 STARWIRE_DENY_DEVICES=FAKE-DENIED -s FAKE-1 -s FAKE-DENIED
if ! called "-s FAKE-1 install"; then fail "device-test.sh didn't test the allowed device of two"; fi
if called "-s FAKE-DENIED"; then fail "device-test.sh sent a refused device a command in a run of two"; fi
fake_run 4 FAKE_ADB_LOCKED=1 -s FAKE-1
if called " install "; then fail "device-test.sh installed on a locked device"; fi
fake_run 4 FAKE_ADB_STREAMING=1 -s FAKE-1
if called " install "; then fail "device-test.sh went on while a stream was open"; fi
fake_run 3 FAKE_ADB_INSTALL=fail -s FAKE-1
# A real device: random input needs --monkey-real-device as well, so nothing is checked or sent
fake_run 0 FAKE_ADB_MONKEY_CHECK=pass -s FAKE-1 --monkey 50
if called "MonkeyPreconditionTest" || called "shell monkey"; then
    fail "device-test.sh ran monkey on a real device without --monkey-real-device"
fi
# An emulator where the app knows a PC: the check fails, so no random input
fake_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_MONKEY_CHECK=fail -s FAKE-1 --monkey 50
if ! called "-e starwire.monkeyCheck true"; then fail "device-test.sh didn't run MonkeyPreconditionTest"; fi
if called "shell monkey"; then fail "device-test.sh ran monkey although MonkeyPreconditionTest failed"; fi
# An emulator that knows no PC: monkey runs with the fixed seed, in airplane mode, put back after
fake_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_MONKEY_CHECK=pass -s FAKE-1 --monkey 50
if ! called "shell monkey -p com.starwire.debug -s 20261023" || ! called "airplane-mode enable" ||
   ! called "airplane-mode disable"; then
    fail "device-test.sh didn't run monkey with the fixed seed in airplane mode on an empty emulator"
fi
# @HardwareOnly tests are left out on an emulator only, and --only narrows the suite to its classes
fake_run 0 -s FAKE-1
if called "notAnnotation"; then fail "device-test.sh left out @HardwareOnly tests on a real device"; fi
if ! called "-e notPackage com.limelight.smoke,com.limelight.devicecheck"; then
    fail "device-test.sh didn't run the whole instrumented suite"
fi
fake_run 0 FAKE_ADB_EMULATOR=1 -s FAKE-1 --only com.limelight.ScreenWidthTest,com.limelight.StringsTest
hw="-e notAnnotation com.limelight.testing.HardwareOnly"
if ! called "-e class com.limelight.ScreenWidthTest,com.limelight.StringsTest $hw" || called "notPackage" ||
   ! called "-e starwire.allowStream false $hw"; then
    fail "device-test.sh --only on an emulator didn't run just those classes, without @HardwareOnly tests"
fi
fake_run 4 -s FAKE-1 --only "com.limelight.X; rm -rf /"
if grep -q uninstall "$work/all-calls"; then fail "device-test.sh uninstalled something"; fi
rm -rf "$work"
ok "device-test.sh refuses listed devices, checks before it installs, guards monkey, and filters tests"

# 9. Every action the workflows use is pinned to a full commit hash with its release in a
# comment, and each action to the same commit in every workflow
begin
while IFS= read -r line; do
    use=$(sed -E 's/^[^:]*:[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*//' <<< "$line")
    case "$use" in ./*) continue ;; esac   # a workflow in this repo
    if ! [[ "$use" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}\ \#\ v[0-9]+(\.[0-9]+)*$ ]]; then
        fail "${line%%:*}: $use isn't pinned to a full commit hash with its version in a comment"
    fi
done < <(grep -HE '^[[:space:]]*(-[[:space:]]+)?uses:' .github/workflows/*.yml)
for action in $(grep -hoE 'uses:[[:space:]]*[^ ]+@[0-9a-f]{40}' .github/workflows/*.yml | sed -E 's/uses:[[:space:]]*//' |
        sort -u | cut -d@ -f1 | uniq -d); do
    fail "$action is pinned to different commits in different workflows"
done
ok "the workflows' actions are pinned to commits"

if [ $failures -ne 0 ]; then
    echo "$failures problem(s)" >&2
    exit 1
fi
echo "All tool checks passed"
