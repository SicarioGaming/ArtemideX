<div align="center">

# ArtemideX

**Stream your PC games to your Android phone or tablet, with every option in one colourful app.**

[![Latest release](https://img.shields.io/github/v/release/SicarioGaming/ArtemideX?include_prereleases&label=release&style=flat-square)](https://github.com/SicarioGaming/ArtemideX/releases)
[![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-34A853?style=flat-square&logo=android&logoColor=white)](https://github.com/SicarioGaming/ArtemideX/releases)
[![Licence: GPL-3.0](https://img.shields.io/badge/licence-GPL--3.0-EF9421?style=flat-square)](LICENSE.txt)

### [⬇ Download ArtemideX](https://github.com/SicarioGaming/ArtemideX/releases)

<img src="docs/cover_glow_home.png" width="820" alt="ArtemideX's game screen: the selected game shown large with its stream settings and a Play button, and the PC's other games in a row below, all tinted by the game's colour">

<sub>The game screen takes its colour from whichever game you pick (shown here with sample covers).</sub>

</div>

---

## Why ArtemideX?

Moonlight lets you play the games on your PC from anywhere in your home, on your phone or
tablet. Over the years the community has made some brilliant versions of it, each great at
something different:

- [**Moonlight X**](https://github.com/MoreOrLessSoftware/moonlight-android) is built for smooth,
  low-latency video, with a Vulkan renderer and the PyroWave codec.
- [**Artemide**](https://github.com/derflacco/moonlight-android), building on
  [**Artemis**](https://github.com/ClassicOldSong/moonlight-android), is packed with tools for
  actually using a PC from a touchscreen: a handy in-stream menu, trackpad modes, a full keyboard,
  clipboard sharing and more.

Picking one meant giving up the other. ArtemideX brings them together in a single app, adds the
best ideas from a few more community versions, and wraps it all in a fresh, friendly design.
It installs beside any Moonlight you already have, so trying it costs nothing.

## What you get

<table>
<tr>
<td width="50%" valign="top">

### 🎨 A look that follows your game
The screens take their colour from the game you've picked. Your last game is ready to go with
one tap, and its stream settings are shown right under its name.

### 🎮 Everything during a stream, one tap away
Open the menu with the floating button, a three-finger tap or by holding Select on a
controller. Keyboard, mouse modes, zoom, key shortcuts, monitor switching, bitrate,
clipboard: it's all in one grid, and works with a controller too.

### ⚡ Fast and smooth
Moonlight X's Vulkan renderer and PyroWave, plus Artemide's low-latency options (Warp Drive,
Fast V-Sync, async decoding) for when every millisecond counts.

</td>
<td width="50%" valign="top">

<img src="docs/readme_menu.png" alt="The in-stream menu: the game's name, a grid of tools such as Keyboard, Mouse, Shortcuts, Monitors and Bitrate, and buttons to close, quit or disconnect">

</td>
</tr>
<tr>
<td width="50%" valign="top">

<img src="docs/cover_glow_settings.png" alt="The settings screen: section buttons along the top, settings in plain words, and a panel explaining how the chosen options feel">

</td>
<td width="50%" valign="top">

### 🧭 Settings that explain themselves
Sections with plain names (Stream, Sound & vibration, Controllers…), and a **"What you'll
feel"** panel that shows whether your choices lean towards fastest response or smoothest
motion.

### 🖐 Made for touchscreens
Pinch to zoom anytime, trackpad modes, long-press to right-click, an on-screen keyboard with
every key, and the option to hide the PC's mouse pointer while you play.

### 📳 Feel the bass
Audio haptics turns a game's bass into vibration on your device or controller.

</td>
</tr>
</table>

## Get started

You'll need a Windows PC to stream from, and your Android device on the same Wi-Fi network.

### 1. Set up your PC
Install a streaming host on the PC. We recommend
[**Vibepollo**](https://github.com/Nonary/Vibepollo), which ArtemideX is developed with and which
unlocks every feature. [Apollo](https://github.com/ClassicOldSong/Apollo) works too, and
[Sunshine](https://github.com/LizardByte/Sunshine) works for everything except a few extras
(clipboard sharing, PC commands, Virtual Display and changing the bitrate mid-stream).

### 2. Install ArtemideX
On your phone or tablet, open the
[**Releases page**](https://github.com/SicarioGaming/ArtemideX/releases), download the latest
`ArtemideX-….apk` and open it. Android will ask you to allow installing apps from your browser
or file manager; allow it, then tap **Install**.

### 3. Pair with your PC
Open ArtemideX and your PC appears in the list (if it doesn't, tap **+** and type the PC's IP
address). Tap it and ArtemideX shows a **PIN**. On the PC, open your host's web page (usually
`https://localhost:47990`), go to the **PIN** page and type it in. You only do this once.

### 4. Play
Tap your PC, then tap a game to bring it up, and tap **Play**. That's it.

### Good to know
- **Open the menu while streaming:** tap the floating button, tap with three fingers, or hold
  **Select** on a controller. **Close (B)** takes you back to the game.
- **Choppy picture?** In **Settings → Stream**, lower the bitrate or resolution. The
  "What you'll feel" panel shows what each frame pacing choice does.
- **Different settings per game:** tap a game, then **This game's settings**.
- **PC not showing up?** Make sure both are on the same network and the host is running, and
  check that your PC's firewall allows it.

## Status

0.1.0 is the first release. Every feature runs through tests on a real device (a Snapdragon 8
Elite tablet on Android 16) before it's added. Found something that doesn't work? Please
[open an issue](https://github.com/SicarioGaming/ArtemideX/issues).

## How it was made

ArtemideX was built by SicarioGaming together with AI: [Claude Code](https://claude.com/claude-code)
(Anthropic) wrote the code, with [REA](https://github.com/morluto/rea) (by morluto) for
reverse-engineering and analysis. The ideas, direction and design choices are SicarioGaming's. The
features that came from other Moonlight versions are their authors' work, and every one of them
is credited below.

---

## Where every feature comes from

Each feature below says where it came from:

- **Ported from *project* (*author*)**: that project's code, brought into ArtemideX and adapted.
- **Idea from *project* (*author*)**: inspired by that project, but written new for ArtemideX.
- **New in ArtemideX**: written for ArtemideX.

Where a feature started in an older version and was carried forward (for example Artemis →
Artemide), both are named. Features marked **(Apollo / Vibepollo)** need an Apollo-based host.

<details>
<summary><b>Cover Glow, the new design</b></summary>

*New in ArtemideX.*

- **Game screen:** the selected game shown large with its cover, name, Play/Resume and its
  stream settings as small tags. It starts on the game running on the PC, otherwise the one you
  played last.
- **Colours from the cover:** the background and glow take the game's cover colour and fade
  between games. White text stays readable on every colour.
- **PC list:** frosted cards and a frosted quick-settings bar.
- **Settings:** section buttons that jump to each section and follow you as you scroll, plain
  section names (in English), and the "What you'll feel" panel on wide screens. It compares the
  choices rather than measuring them.
- **In-stream menu:** a side sheet in the game's colour with a grid of tools, usable with a
  controller.

</details>

<details>
<summary><b>In-stream menu</b></summary>

The menu itself, its quick actions and custom commands come from Moonlight X
(MoreOrLessSoftware). ArtemideX adds:

| Feature | Source |
| --- | --- |
| Floating menu button (tap to open, drag to move) | Ported from Artemis (Yukino Song / ClassicOldSong), via Artemide |
| Shortcuts page: Esc, F11, Alt+F4, Alt+Enter, Ctrl+V, Win, Win+D, Win+G, Ctrl+Alt+Tab, Shift+Tab, Win+Shift+Left, Ctrl+Alt+Shift+F1/F12, Win+Alt+B | Ported from Axixi2233's Moonlight fork (axi), via Artemis and Artemide |
| Custom shortcuts imported from a file (same JSON format) | Format from Axixi2233's fork (axi) and Artemis (Yukino Song); importer written new for ArtemideX |
| Task Manager (Ctrl+Shift+Esc) | Ported from Artemis (Yukino Song), via Artemide |
| Rotate screen, Pan/Zoom mode (pinch to zoom, drag to pan, double-tap to reset) | Ported from Artemis (Yukino Song), via Artemide |
| Touch mode picker (multi-touch, direct touch, natural and gaming trackpad, disabled, swapped clicks) | Ported from Artemis (Yukino Song), via Artemide |
| Trackpad sensitivity, toggled from the menu | Ported from Artemis (Yukino Song) and Axixi2233's fork, via Artemide |
| Send / get clipboard **(Apollo / Vibepollo)** | Ported from Artemis (Yukino Song), via Artemide |
| PC commands (run the host's server commands) **(Apollo / Vibepollo)** | Ported from Artemis (Yukino Song), via Artemide |
| On-screen full keyboard (82 keys, sticky Ctrl/Alt/Shift/Win, vibration) | Ported from Axixi2233's fork (axi) and Artemis (Yukino Song), via Artemide |
| On-screen controller toggle | Ported from Artemis (Yukino Song), via Artemide |
| Monitor switcher (Monitor 1–6, using the host's Ctrl+Alt+Shift+F1… shortcuts) | Idea from Moonlit Artemis (Mohammad Forhad / 4had45) |
| Change bitrate mid-stream **(Vibepollo)** | Idea from Moonlight V+ (qiin2333) |

</details>

<details>
<summary><b>Streaming and video</b></summary>

| Feature | Source |
| --- | --- |
| Vulkan renderer with host frame timing, PyroWave codec | From Moonlight X (MoreOrLessSoftware) |
| Custom Vulkan driver (Turnip) for PyroWave | Ported from Moonlight X Turnip (ncpt / NoCrypt), uses libadrenotools (Billy Laws / bylaws). ArtemideX rebuilds libadrenotools from source, refuses files that aren't 64-bit ARM libraries, and falls back to the system driver if the custom one can't run PyroWave |
| Warp Drive / Warp 2 frame pacing (2x / 4x FPS from the PC, newest frame shown) | Ported from Artemis (Yukino Song), via Artemide |
| Fast V-Sync (Artemide's Nano Pacer) | Ported from Artemide (derflacco) |
| V-Sync (one setting: Off, V-Sync, Fast V-Sync) | V-Sync for the direct renderer is new in ArtemideX; Fast V-Sync as above |
| Sync: LFR | Ported from Artemide (derflacco) |
| Async decode and Async: LFR | Ported from Artemide (derflacco) |
| Ultra Low Latency: MediaTek's own ultra-low-latency mode follows the setting, and low latency is set again after the decoder starts | Ported from Artemide (derflacco). The base option and its Snapdragon/Exynos/Amlogic flags are from Moonlight X (MoreOrLessSoftware) and Artemis |
| Virtual Display: "Display on the PC" setting, "Start in Virtual/Primary Display" **(Apollo / Vibepollo)** | Ported from Artemis (Yukino Song), via Artemide |
| Prevent Packet Loss (keeps Wi-Fi awake during streams) | Option from Artemis (Yukino Song), via Artemide; rewritten for ArtemideX as a small keep-alive packet |
| Lite performance overlay (one compact line) | Ported from Axixi2233's fork (axi), via Artemis and Artemide |
| 1% low FPS in the stats | Idea from Moonlight V+ (qiin2333) and Moonlight X Turnip (NoCrypt) |

</details>

<details>
<summary><b>Input and feel</b></summary>

| Feature | Source |
| --- | --- |
| Pinch to zoom anytime, outside Pan/Zoom mode | Idea from Moonlit Artemis (Mohammad Forhad / 4had45); uses Artemis's pan/zoom code (Yukino Song) |
| Automatic clipboard sync (send yours when the stream starts, copy the PC's when you leave) **(Apollo / Vibepollo)** | Ported from Artemis (Yukino Song), via Artemide |
| Audio haptics: the game's bass becomes vibration on the device and/or the controller | Idea from Moonlight V+ (qiin2333); written new for ArtemideX |
| Hide PC cursor (never / while touching / always) **(Vibepollo)** | New in ArtemideX |
| Long-press to right-click in gaming trackpad mode | New in ArtemideX |
| Per-game settings for the new options (codec, V-Sync, LFR, async decode, display, touch mode, cursor, audio haptics) | Extends Moonlight X's per-app settings (MoreOrLessSoftware) |

</details>

<details>
<summary><b>Kept from Moonlight X, and not included</b></summary>

**Kept from Moonlight X** (all by MoreOrLessSoftware): Quick Launch apps, per-app settings,
brightness slider, bitrate and stats overrides, Force HDR, the in-stream overlay menu with custom
commands, the improved stats overlay, and Ultra Low Latency. See the
[Moonlight X README](https://github.com/MoreOrLessSoftware/moonlight-android).

**Not included:** Artemide's customisable on-screen key buttons, and its other MediaTek
queue/preload values and NVIDIA Tegra and legacy Qualcomm decoder extras.

</details>

## Credits

ArtemideX wouldn't exist without these projects and the people behind them:

| Project | Author | Used for |
| --- | --- | --- |
| [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) | Cameron Gutman and contributors | The original client everything here is built on |
| [Moonlight X](https://github.com/MoreOrLessSoftware/moonlight-android) | MoreOrLessSoftware | The base of ArtemideX: Vulkan renderer, PyroWave, overlay menu, per-app settings, Quick Launch |
| [Artemis](https://github.com/ClassicOldSong/moonlight-android) | Yukino Song (ClassicOldSong) | Pan/zoom, touch modes, clipboard, server commands, Virtual Display, Warp pacing, floating button, and more (via Artemide) |
| [Artemide](https://github.com/derflacco/moonlight-android) | derflacco | Nano Pacer / Fast V-Sync, Sync and Async LFR, async decode, MediaTek low-latency work, and the version of all the Artemis features above that ArtemideX ported |
| [Moonlight (Axixi2233 fork)](https://github.com/Axixi2233/moonlight-android) | Axixi2233 | Full keyboard, shortcuts list, Lite overlay, shortcut file format (via Artemis) |
| [Moonlight X Turnip](https://github.com/NoCrypt/moonlight-x-turnip) | ncpt (NoCrypt) | Custom Turnip Vulkan driver for PyroWave |
| [Moonlight V+](https://github.com/qiin2333/moonlight-vplus) | qiin2333 | Ideas: bitrate mid-stream, audio haptics, 1% lows |
| [Moonlit Artemis](https://github.com/4had45/moonlit-artemis) | Mohammad Forhad (4had45) | Ideas: monitor switcher, pinch to zoom anytime |
| [PyroWave](https://github.com/Themaister/pyrowave) | Hans-Kristian Arntzen (Themaister) | The PyroWave codec (MIT) |
| [libadrenotools](https://github.com/bylaws/libadrenotools) | Billy Laws (bylaws) | Loading a custom Adreno Vulkan driver (BSD-2-Clause) |
| [Vibepollo](https://github.com/Nonary/Vibepollo) | Chase Payne (Nonary) | The host ArtemideX is developed with (fork of Apollo) |
| [Apollo](https://github.com/ClassicOldSong/Apollo) / [Sunshine](https://github.com/LizardByte/Sunshine) | ClassicOldSong / LizardByte | The hosts Vibepollo is built on |
| [Claude Code](https://claude.com/claude-code) | Anthropic | Writing the ArtemideX code, ports, redesign and tests |
| [REA](https://github.com/morluto/rea) | morluto | Reverse-engineering and analysis (MIT) |

Credited something wrongly, or missed you? Please open an issue and it'll be fixed.

## More details

<details>
<summary><b>Device support</b></summary>

Android 5.0 or newer, on any CPU type. Some features need a recent device (async decoding, the
Vulkan renderer), and PyroWave and the custom Turnip driver need a 64-bit ARM device
(`arm64-v8a`), since their libraries are only built for that. Updates install over the top with
your settings kept, as long as they come from these releases. Tools such as
[Obtainium](https://github.com/ImranR98/Obtainium) can keep it updated straight from the
Releases page.

</details>

<details>
<summary><b>Building from source</b></summary>

1. Install Android Studio, the Android NDK (the version in `app/build.gradle`) and a JDK 17 or
   newer.
2. Clone with the submodules:
   ```bash
   git clone --recursive https://github.com/SicarioGaming/ArtemideX.git
   ```
   ArtemideX uses its own fork of
   [moonlight-common-c](https://github.com/SicarioGaming/moonlight-common-c) (branch `artemidex`),
   which adds the calls for PC commands and Prevent Packet Loss.
3. Create `local.properties` in the project folder with `ndk.dir=` set to your NDK folder.
4. Build a debug APK with `./gradlew assembleNonRootDebug`. It installs as "ArtemideX (Debug)"
   (`com.artemidex.debug`).
5. On-device tests: `./gradlew connectedNonRootDebugAndroidTest`.

**Signed release builds** use a key that's never committed. Put its details in
`keystore.properties` in the project folder (it's in `.gitignore`):

```properties
storeFile=artemidex-release.jks
storePassword=...
keyAlias=artemidex
keyPassword=...
```

or set `ARTEMIDEX_KEYSTORE`, `ARTEMIDEX_KEYSTORE_PASSWORD`, `ARTEMIDEX_KEY_ALIAS` and
`ARTEMIDEX_KEY_PASSWORD`. Then run `./gradlew assembleNonRootRelease`. Without a key, release
builds come out unsigned.

The libadrenotools and PyroWave libraries are included prebuilt, with the scripts that build
them from their upstream source next to them.

</details>

## Licence

GPL-3.0, like Moonlight and every version it builds on. See [LICENSE.txt](LICENSE.txt). PyroWave
(MIT) and libadrenotools (BSD-2-Clause) keep their own licences.
