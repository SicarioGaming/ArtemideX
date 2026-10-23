#!/usr/bin/env bash
# A stand-in for adb, so check-tools.sh can run device-test.sh and device-run.sh with no device.
# It writes every call to $FAKE_ADB_LOG and answers like one device would, keeping what changed
# (the app in front, a crash, a posture) in $FAKE_ADB_STATE (default: the log's folder).
# FAKE_ADB_* settings:
#   EMULATOR=1        say it's an emulator
#   SDK, SIZE, DENSITY   its API level (35), screen (1600x2560) and density (320)
#   OFFLINE=1         not connected
#   LOCKED=1          the lock screen is up; slow: it goes on the second wm dismiss-keyguard
#   STREAMING=1       a Starwire stream is in front
#   INSTALL=fail      installs fail the way a differently signed APK does
#   ABI=fail          installing with --abi fails the way an image without ARM translation does
#   MONKEY_CHECK=pass|fail   what MonkeyPreconditionTest reports (default fail)
#   FAIL=suite        the instrumented suite (or the classes --only names) has a failing test
#   CRASH_ON=fold|font|density   the app crashes when that changes
#   NOFOLD=1          a phone: no fold commands and no device states
#   TV=1              an Android TV image that lists the app in its TV launcher

echo "$*" >> "$FAKE_ADB_LOG"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
state="${FAKE_ADB_STATE:-$(dirname "$FAKE_ADB_LOG")}"
call="$*"
crash() { : > "$state/crash"; }

case "$call" in
    "devices -l")
        echo "List of devices attached"
        echo "FAKE-1 device product:fake model:Fake_Tablet device:fake transport_id:1"
        echo "FAKE-DENIED device product:fake model:Fake_Phone device:fake transport_id:2"
        echo "emulator-5554 device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:3"
        ;;
    *" get-state")
        if [ "${FAKE_ADB_OFFLINE:-0}" = 1 ]; then echo "error: device offline" >&2; exit 1; fi
        echo device ;;
    *"getprop ro.product.model") echo "Fake Tablet" ;;
    *"getprop ro.build.version.sdk") echo "${FAKE_ADB_SDK:-35}" ;;
    *"getprop ro.product.cpu.abi") echo x86_64 ;;
    *"getprop ro.build.fingerprint") echo "fake/fake/fake:${FAKE_ADB_SDK:-35}/FAKE/1:userdebug/test-keys" ;;
    *"getprop ro.boot.qemu") echo "${FAKE_ADB_EMULATOR:-0}" ;;
    *"getprop ro.kernel.qemu") echo 0 ;;
    *"wm size") echo "Physical size: ${FAKE_ADB_SIZE:-1600x2560}" ;;
    *"wm density")
        echo "Physical density: ${FAKE_ADB_DENSITY:-320}"
        [ -f "$state/density" ] && echo "Override density: $(cat "$state/density")" ;;
    *"wm density reset") rm -f "$state/density" ;;
    *"wm density "[0-9]*) echo "${call##* }" > "$state/density"; [ "${FAKE_ADB_CRASH_ON:-}" = density ] && crash ;;
    *"dumpsys power") echo "  mWakefulness=Awake" ;;
    *"dumpsys window")
        if [ "${FAKE_ADB_LOCKED:-0}" = 1 ] ||
           { [ "${FAKE_ADB_LOCKED:-0}" = slow ] && [ "$(cat "$state/unlocks" 2>/dev/null || echo 0)" -lt 2 ]; }; then
            echo "    isKeyguardShowing=true"
        else
            echo "    isKeyguardShowing=false"
        fi
        ;;
    *"wm dismiss-keyguard") echo $(( $(cat "$state/unlocks" 2>/dev/null || echo 0) + 1 )) > "$state/unlocks" ;;
    *"locksettings get-disabled") echo false ;;
    *"dumpsys activity activities")
        if [ "${FAKE_ADB_STREAMING:-0}" = 1 ]; then
            echo "  topResumedActivity=ActivityRecord{1 u0 com.starwire.debug/com.limelight.Game t9}"
        elif [ -f "$state/front" ]; then
            echo "  topResumedActivity=ActivityRecord{1 u0 com.starwire.debug/com.limelight.PcView t9}"
        else
            echo "  topResumedActivity=ActivityRecord{1 u0 com.example.launcher/.Home t1}"
        fi
        ;;
    *"dumpsys package com.starwire.debug") echo "    primaryCpuAbi=arm64-v8a" ;;
    *" install "*" --abi "*)
        if [ "${FAKE_ADB_ABI:-}" = fail ]; then
            echo "Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]"
        else
            echo "Success"
        fi
        ;;
    *" install "*)
        if [ "${FAKE_ADB_INSTALL:-}" = fail ]; then
            echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]"
        else
            echo "Success"
        fi
        ;;
    *"date "*) echo "2026-01-01 00:00:00" ;;
    *"am instrument"*MonkeyPreconditionTest*) cat "$here/instrument-precondition-${FAKE_ADB_MONKEY_CHECK:-fail}.txt" ;;
    *"am instrument"*"-e notPackage "*|*"am instrument"*"-e class com.limelight.ScreenWidthTest"*)
        if [ "${FAKE_ADB_FAIL:-}" = suite ]; then cat "$here/instrument-mixed.txt"; else cat "$here/instrument-pass.txt"; fi
        ;;
    *"am instrument"*) cat "$here/instrument-pass.txt" ;;
    *"am start "*) : > "$state/front"; echo "Status: ok" ;;
    *"am force-stop "*) rm -f "$state/front" ;;
    *"run-as "*"shared_prefs/"*)
        echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>'
        echo '<map>'
        echo '    <string name="hide_cursor_mode">always</string>'
        echo '</map>'
        ;;
    *"run-as "*"host_cursor"*) echo end ;;
    *"settings get global airplane_mode_on") echo 0 ;;
    *"settings get global stay_on_while_plugged_in") echo 0 ;;
    *"settings get global hide_error_dialogs") echo null ;;
    *"settings get system font_scale") echo 1.0 ;;
    *"settings get system user_rotation") echo 0 ;;
    *"settings get system accelerometer_rotation") echo 1 ;;
    *"settings put system font_scale "*)
        [ "${FAKE_ADB_CRASH_ON:-}" = font ] && [ "${call##* }" != 1.0 ] && crash ;;
    *"shell monkey "*) echo "// Monkey finished" ;;
    *" logcat -c"|*" logcat -b crash -c") rm -f "$state/crash" ;;
    *" logcat -d -b crash"*)
        [ -f "$state/crash" ] && echo "01-01 00:00:01.000  1234  1234 E AndroidRuntime: Process: com.starwire.debug, PID: 1234" ;;
    # Android 6's logcat takes one buffer per -b, not a list
    *" logcat -d -b "*,*) echo "Unable to open log device '${call#*-b }'" ;;
    *" logcat -d -b main -b system -b events"*) echo "01-01 00:00:01.000  1000  1000 I ActivityManager: fake" ;;
    *" exec-out screencap -p") printf '\x89PNG\r\n\x1a\n' ;;
    *" emu fold"|*" emu unfold")
        if [ "${FAKE_ADB_NOFOLD:-0}" = 1 ]; then echo "KO: unknown command, try 'help'"; exit 0; fi
        echo "${call##* }" > "$state/posture"
        [ "${call##* }" = fold ] && [ "${FAKE_ADB_CRASH_ON:-}" = fold ] && crash
        echo OK ;;
    *"cmd device_state print-states")
        [ "${FAKE_ADB_NOFOLD:-0}" = 1 ] && exit 0
        echo "Supported states: [ DeviceState{identifier=0, name='CLOSED'}, DeviceState{identifier=1, name='HALF_OPENED'}, DeviceState{identifier=2, name='OPENED'} ]" ;;
    *"cmd device_state print-state") cat "$state/posture" 2>/dev/null || echo OPENED ;;
    *"cmd device_state state "*) echo "state ${call##* }" > "$state/posture" ;;
    *"cmd locale set-app-locales com.starwire.debug --locales "*) echo "${call##* }" > "$state/locales" ;;
    *"cmd locale set-app-locales com.starwire.debug") rm -f "$state/locales" ;;
    *"cmd locale get-app-locales com.starwire.debug")
        echo "Locales for com.starwire.debug for user 0 are [$(cat "$state/locales" 2>/dev/null)]" ;;
    *"ls /system/bin/uinput 2>/dev/null") echo /system/bin/uinput ;;
    *"pm list features")
        echo "feature:android.hardware.touchscreen"
        [ "${FAKE_ADB_TV:-0}" = 1 ] && echo "feature:android.software.leanback" ;;
    *"cmd package query-activities"*) [ "${FAKE_ADB_TV:-0}" = 1 ] && echo "com.starwire.debug/com.limelight.PcView" ;;
    *) ;;
esac
exit 0
