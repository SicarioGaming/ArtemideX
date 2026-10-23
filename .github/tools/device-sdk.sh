#!/usr/bin/env bash
# Android SDK setup for the emulator device matrix (.github/workflows/devices.yml).
#
#   device-sdk.sh cmdline-tools     make cmdline-tools/latest version 23.0 or newer
#   device-sdk.sh install PKG...    install SDK packages (the emulator, system images)
#
# The ubuntu-24.04 runner comes with command-line tools 12.0, whose avdmanager doesn't know
# pixel_fold, small_tablet, pixel_9_pro_xl and other newer device profiles: it stops with "No
# device found matching --device". The emulator action uses whatever is in cmdline-tools/latest,
# so 23.0 has to be put there first. Uses ANDROID_HOME (or ANDROID_SDK_ROOT).

set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "set ANDROID_HOME" >&2; exit 4; }
WANT=23.0
sdkmanager() { "$SDK/cmdline-tools/latest/bin/sdkmanager" "$@"; }
licenses() { yes | sdkmanager --licenses > /dev/null 2>&1 || true; }

case "${1:-}" in
    cmdline-tools)
        have=$(sed -n 's/^Pkg.Revision=//p' "$SDK/cmdline-tools/latest/source.properties" 2>/dev/null | tr -d '\r' || true)
        if [ -n "$have" ] && [ "${have%%.*}" -ge "${WANT%%.*}" ]; then
            echo "command-line tools $have"
            exit 0
        fi
        licenses
        sdkmanager --install "cmdline-tools;$WANT" > /dev/null
        [ -x "$SDK/cmdline-tools/$WANT/bin/avdmanager" ] || { echo "cmdline-tools;$WANT didn't install" >&2; exit 4; }
        # sdkmanager warns afterwards that 23.0 sits in "latest"; that's expected
        rm -rf "$SDK/cmdline-tools/latest"
        mv "$SDK/cmdline-tools/$WANT" "$SDK/cmdline-tools/latest"
        echo "command-line tools ${have:-none} -> $WANT"
        ;;
    install)
        shift
        [ $# -gt 0 ] || { echo "install what?" >&2; exit 4; }
        licenses
        sdkmanager --install "$@" > /dev/null
        echo "installed: $*"
        ;;
    *)
        sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
        exit 4
        ;;
esac
