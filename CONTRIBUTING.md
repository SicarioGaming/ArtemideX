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

## Commits, branches and the changelog

Commit messages follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/),
branch names follow [Conventional Branch](https://conventionalbranch.org), and
[CHANGELOG.md](CHANGELOG.md) follows [Keep a Changelog 1.0.0](https://keepachangelog.com/en/1.0.0/).
Commits before 23 Oct 2026 predate these rules. The history is public, so they stay as they are.

**Commit messages** start with a type, an optional scope in brackets, a colon and a space:

```text
fix(mic): stop the receiver dropping audio after a reconnect
feat(dualsense): add adaptive trigger effects
docs: add the 0.2.1 notes to CHANGELOG.md
```

- The types used here: `feat` (a new feature), `fix` (a bug fix), `docs`, `test`, `ci`, `build`,
  `refactor`, `perf`, `chore` and `revert`.
- The scope is optional and in lower case, like `fix(mic): ...` or `feat(dualsense): ...`.
- After the colon, write the subject in the imperative and in lower case: "fix: stop the crash",
  not "fix: Stopped the crash".
- A change that breaks something people rely on gets a `!` before the colon
  (`feat(mic)!: ...`) or a `BREAKING CHANGE: <what breaks>` footer.
- If there's a body, leave a blank line after the subject.

**Branch names** are `main`, or a type and a slash followed by lower-case words with hyphens:
`feature/`, `bugfix/`, `hotfix/`, `release/` or `chore/`. For example `feature/dualsense-triggers`,
`bugfix/mic-echo` or `release/v0.2.1` (dots are fine in version numbers). The other names the spec
allows, like `feat/` and `fix/`, pass the check too.

**The changelog:** every user-facing change adds a line under `[Unreleased]` in CHANGELOG.md, in
the same pull request, under Added, Changed, Deprecated, Removed, Fixed or Security. A release
moves the `[Unreleased]` lines under a new version heading with its date, like
`## [0.2.1] - YYYY-MM-DD`, and updates the compare links at the bottom.

**The check:** `.github/workflows/commits.yml` runs `.github/tools/check-commits.sh` on the
commits and branch name of every pull request, and on every push to `main`. It skips merge
commits, commits cherry-picked from upstream with `git cherry-pick -x`, and commits from before the
rules started. To run it yourself before pushing:

```bash
bash .github/tools/check-commits.sh                      # what your next push adds
bash .github/tools/check-commits.sh origin/main..HEAD    # what a pull request into main adds
bash .github/tools/check-commits.sh --branch "$(git branch --show-current)"
```

To have each message checked as you commit, add it as a commit-msg hook:

```bash
printf '#!/bin/sh\nexec bash .github/tools/check-commits.sh --msg-file "$1"\n' > .git/hooks/commit-msg
chmod +x .git/hooks/commit-msg
```

The hook lets git's own "Merge ...", "Revert ..." and `fixup!` messages through, so those commands
keep working. Before opening a pull request, squash the fixups and reword a revert as
`revert: <what it undoes>`.

To fix a message that's already committed, use `git commit --amend` for the latest commit, or
`git rebase -i origin/main` and "reword" for older ones, then push with
`git push --force-with-lease`.

## Licence and credits

Starwire is GPL-3.0 ([LICENSE.txt](LICENSE.txt)), like Moonlight and every fork it builds on, and
anything you contribute is under the same licence. Starwire keeps its upstream credits: code
ported from another Moonlight version stays credited to its author in the README's
[Where every feature comes from](README.md#where-every-feature-comes-from) tables and the
[Credits](README.md#credits) table, and existing copyright and licence notices stay as they are.
If you port something, add its source and author there too.
