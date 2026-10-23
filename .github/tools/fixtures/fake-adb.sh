#!/usr/bin/env bash
# A stand-in for adb, so check-tools.sh can run device-test.sh with no device. It writes every
# call to $FAKE_ADB_LOG and answers like one device would. FAKE_ADB_* settings:
#   EMULATOR=1        say it's an emulator
#   LOCKED=1          the lock screen is up
#   STREAMING=1       a Starwire stream is in front
#   INSTALL=fail      installs fail the way a differently signed APK does
#   MONKEY_CHECK=pass|fail   what MonkeyPreconditionTest reports (default fail)

echo "$*" >> "$FAKE_ADB_LOG"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
call="$*"

case "$call" in
    "devices -l")
        echo "List of devices attached"
        echo "FAKE-1 device product:fake model:Fake_Tablet device:fake transport_id:1"
        echo "FAKE-DENIED device product:fake model:Fake_Phone device:fake transport_id:2"
        ;;
    *" get-state") echo device ;;
    *"getprop ro.product.model") echo "Fake Tablet" ;;
    *"getprop ro.build.version.sdk") echo 35 ;;
    *"getprop ro.product.cpu.abi") echo x86_64 ;;
    *"getprop ro.boot.qemu") echo "${FAKE_ADB_EMULATOR:-0}" ;;
    *"getprop ro.kernel.qemu") echo 0 ;;
    *"wm size") echo "Physical size: 1600x2560" ;;
    *"wm density") echo "Physical density: 320" ;;
    *"dumpsys power") echo "  mWakefulness=Awake" ;;
    *"dumpsys window")
        if [ "${FAKE_ADB_LOCKED:-0}" = 1 ]; then echo "    isKeyguardShowing=true"; else echo "    isKeyguardShowing=false"; fi
        ;;
    *"dumpsys activity activities")
        if [ "${FAKE_ADB_STREAMING:-0}" = 1 ]; then
            echo "  topResumedActivity=ActivityRecord{1 u0 com.starwire.debug/com.limelight.Game t9}"
        else
            echo "  topResumedActivity=ActivityRecord{1 u0 com.example.launcher/.Home t1}"
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
    *"am instrument"*) cat "$here/instrument-pass.txt" ;;
    *"run-as "*"shared_prefs/"*)
        echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>'
        echo '<map>'
        echo '    <string name="hide_cursor_mode">always</string>'
        echo '</map>'
        ;;
    *"run-as "*"host_cursor"*) echo end ;;
    *"settings get global airplane_mode_on") echo 0 ;;
    *"shell monkey "*) echo "// Monkey finished" ;;
    *) ;;
esac
exit 0
