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

Most tests need no device. Only one needs a host: the stream smoke test, which runs only when
you ask for it.

- **JVM unit tests** in `app/src/test` run on the computer:

  ```bash
  ./gradlew testNonRootDebugUnitTest
  ```

  Lint runs the same way (`./gradlew lintNonRootDebug`). The warnings it already had are listed
  in `app/lint-baseline.xml`, so only a new one fails it.
- **Native tests:** a few C++ simulations of the frame pacer under
  `app/src/main/jni/vulkan/tests`, inherited from Moonlight X, and `partial_frame_test.sh` under
  `app/src/main/jni/moonlight-core/tests`, which checks moonlight-common-c's partial frames. The
  **Native host tests** step in `.github/workflows/build.yml` shows how to build and run them.
- **On-device tests** (JUnit 4, instrumented) in `app/src/androidTest`, written for and run on my
  RedMagic Astra tablet. The smoke tests among them use the app the way a person would, with UI
  Automator. The ones that need a PC skip, and say why, when the device has no paired PC or it
  isn't online.
- **The Windows microphone receiver's tests** in `windows/tests`: `run-tests.ps1` checks the
  encryption and pairing code against the same test vectors as the app, and `e2e-loopback.ps1`
  pairs a test sender with a receiver on the PC's loopback and streams to it. Both build with the
  C# compiler that comes with Windows and leave an installed receiver alone.

To run the on-device tests on a connected device, build both APKs, then run `device-test.sh` with
the serial that `adb devices -l` shows:

```bash
./gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest
.github/tools/device-test.sh -s <serial>
```

It installs with `adb install -r` and never uninstalls, so the debug app keeps its pairing with
your PC. After the instrumented suite it runs the smoke tests, saves their screenshots and looks
for crashes. `--allow-stream` adds a short Desktop stream to the paired PC; without it nothing is
streamed. `./gradlew connectedNonRootDebugAndroidTest` runs the suite too, but it may not keep the
pairing.

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

CI (`.github/workflows/build.yml`) runs the JVM tests, lint, the native tests and
`.github/tools/check-tools.sh` (which checks the scripts in `.github/tools` still work) on every
push, and builds the app and the test APK.

`.github/workflows/devices.yml` runs the on-device tests on emulators shaped like phones,
foldables, tablets, handhelds and Android TV, from Android 5 up: 5 of them on pull requests, 13
every Sunday and all 17 for a release tag. The list is `.github/devices/matrix.json`. Each one
runs `device-test.sh`. Then the foldables fold and unfold with the app open, and other legs
change the text size, display size, theme, language or orientation, with the layout and smoke
tests run again after each change. Some end with random input. Emulators have no hardware
decoder, Adreno GPU, real controller or microphone, so tests that need one skip there
(`DeviceCaps.assumeHardware`). To run one leg on an emulator you started yourself:
`.github/tools/device-run.sh --leg <id> -s emulator-5554`. The matrix hasn't run on GitHub yet.

What the tests don't cover yet, to be honest about it: the custom Turnip driver option with a
real Turnip driver, hosts other than Vibepollo, and the encrypted microphone from a real phone to
a real PC over Wi-Fi.

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
