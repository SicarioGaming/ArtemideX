# Contributing to Starwire

Thanks for taking a look. Starwire is a one-person project, so bug reports and ideas help the
most.

## Bugs and ideas

- **Bugs:** open an issue with the
  [Bug report form](https://github.com/SicarioGaming/Starwire/issues/new/choose). It asks for
  your Starwire version, your device and Android version, and the host on your PC. Steps to make
  it happen again help a lot.
- **Ideas:** post them in [Discussions](https://github.com/SicarioGaming/Starwire/discussions),
  or use the **Feature idea** form if you already know what you'd like. Say if another Moonlight
  app already has it.
- **Security problems:** please follow [SECURITY.md](SECURITY.md) instead of opening a normal
  issue with the details.

## Building

The short version, with the NDK version from `app/build.gradle` installed:

```bash
git clone --recursive https://github.com/SicarioGaming/Starwire.git
cd Starwire
./gradlew assembleNonRootDebug
```

The debug APK lands in `app/build/outputs/apk/nonRoot/debug/` and installs as "Starwire (Debug)"
(`com.starwire.debug`), next to the release app. The README's **Building from source** part (under
[More details](README.md#more-details)) covers the requirements, signed release builds and the
prebuilt libraries.

## Tests

What exists today: 111 on-device tests (JUnit 4, instrumented) in 22 files under
`app/src/androidTest`. They run on an Android device and don't need a PC or a host. They're
written for and run on my RedMagic Astra tablet; I haven't tried them on an emulator.

To run all of them on a connected device:

```bash
./gradlew connectedNonRootDebugAndroidTest
```

Or by hand with `am instrument`, which is also how to run one class:

```bash
./gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
adb install -r app/build/outputs/apk/androidTest/nonRoot/debug/app-nonRoot-debug-androidTest.apk
adb shell am instrument -w com.starwire.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.limelight.binding.video.CustomVulkanDriverTest com.starwire.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The layout tests (`ScreenWidthTest`, `SettingsWidthTest`) check whatever screen size the device
has. To try a narrow screen on a tablet, run `adb shell wm size <width>x<height>` first and
`adb shell wm size reset` afterwards.

What doesn't exist yet, to be honest about it:

- There are no JVM unit tests (`app/src/test`), so every Java test needs a device. The only tests
  that run on a computer are a few C++ simulations of the frame pacer under
  `app/src/main/jni/vulkan/tests`, inherited from Moonlight X.
- CI (`.github/workflows/build.yml`) builds the app and the test APK on every push to `main` and
  every pull request, but it doesn't run any tests.
- The Windows microphone receiver has no automated tests. By hand,
  `StarwireMicReceiver.exe --console` runs it in a window that prints what it receives, and
  `--check` checks VB-Cable.

JVM unit tests and tests in CI are being added.

## Pull requests

If you'd like to change something, open an issue or a discussion first so we can agree on it.
Keep each pull request to one change, and say what you tested it on.

## Licence and credits

Starwire is GPL-3.0 ([LICENSE.txt](LICENSE.txt)), like Moonlight and every fork it builds on, and
anything you contribute is under the same licence. Starwire keeps its upstream credits: code
ported from another Moonlight version stays credited to its author in the README's
[Where every feature comes from](README.md#where-every-feature-comes-from) tables and the
[Credits](README.md#credits) table, and existing copyright and licence notices stay as they are.
If you port something, add its source and author there too.
