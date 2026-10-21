#!/bin/sh
# Rebuilds the prebuilt lib/arm64-v8a/*.so in this folder, which app/build.gradle packages, from
# a libadrenotools checkout (https://github.com/bylaws/libadrenotools). libadrenotools loads a
# custom Vulkan driver (Turnip) in place of the system one; see vk_api.cpp. Run after updating it.
#
# Pinned to upstream commit:
#   8fae8ce254dfc1344527e05301e43f37dea2df80 (Update linkernsbypass)
# which carries lib/linkernsbypass at:
#   aa3975893d83ef1bc84c321ec60c65fbf1287887
# A build made from any other commit is written to SOURCE so it stays recorded.
#
#   ADRENOTOOLS_DIR  libadrenotools checkout, with lib/linkernsbypass checked out (default: an
#                    adrenotools folder next to the moonlight-android folder)
#   ANDROID_NDK      NDK to build with (default: the version app/build.gradle uses, in ANDROID_HOME
#                    or the default SDK location)
#   NINJA            ninja executable, if it isn't on the PATH
#
# Only arm64-v8a is built: libadrenotools supports no other Android ABI.
set -e

cd "$(dirname "$0")"
HERE=$(pwd)
REPO=$(cd ../../../../.. && pwd)

ADRENOTOOLS_DIR=${ADRENOTOOLS_DIR:-$REPO/../../adrenotools}
ADRENOTOOLS_DIR=$(cd "$ADRENOTOOLS_DIR" && pwd)

NDK_VERSION=$(sed -n 's/.*ndkVersion "\(.*\)".*/\1/p' "$REPO/app/build.gradle")
if [ -z "$ANDROID_NDK" ]; then
    SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}
    ANDROID_NDK=$SDK/ndk/$NDK_VERSION
fi
if [ ! -d "$ANDROID_NDK" ]; then
    echo "NDK not found at $ANDROID_NDK; set ANDROID_NDK" >&2
    exit 1
fi

if [ ! -d "$ADRENOTOOLS_DIR/lib/linkernsbypass" ]; then
    echo "linkernsbypass is missing; run: git -C $ADRENOTOOLS_DIR submodule update --init --recursive" >&2
    exit 1
fi

NINJA=${NINJA:-ninja}
STRIP=$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* | head -n 1)

BUILD=$ADRENOTOOLS_DIR/build-android-arm64
cmake -S "$ADRENOTOOLS_DIR" -B "$BUILD" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON
"$NINJA" -C "$BUILD"

LIBS=$HERE/lib/arm64-v8a
mkdir -p "$LIBS"
"$STRIP" --strip-unneeded -o "$LIBS/libadrenotools.so" "$BUILD/libadrenotools.so"
for hook in hook_impl main_hook file_redirect_hook gsl_alloc_hook; do
    "$STRIP" --strip-unneeded -o "$LIBS/lib$hook.so" "$BUILD/src/hook/lib$hook.so"
done

# Keep only the API header the renderer compiles against, in case it changes
cp "$ADRENOTOOLS_DIR/include/adrenotools/driver.h" "$ADRENOTOOLS_DIR/include/adrenotools/priv.h" include/adrenotools/

# Which libadrenotools and linkernsbypass these came from
{
    (cd "$ADRENOTOOLS_DIR" && git log -1 --format='libadrenotools %H %s')
    (cd "$ADRENOTOOLS_DIR/lib/linkernsbypass" && git log -1 --format='linkernsbypass %H %s')
} > SOURCE

echo "Updated $LIBS and $HERE from $ADRENOTOOLS_DIR"
cat SOURCE
