# ArtemideX

**ArtemideX** is a Moonlight game-streaming client for Android. It's built on
[Moonlight X](https://github.com/MoreOrLessSoftware/moonlight-android), brings in the options and
in-stream menu from [Artemide](https://github.com/derflacco/moonlight-android) (and the
[Artemis](https://github.com/ClassicOldSong/moonlight-android) features Artemide carries), adds a
few features from other forks, and has a new, more colourful look called **Cover Glow**.

It installs beside Moonlight, Moonlight X and Artemide (app id `com.artemidex`), so you can keep
using them side by side.

> [!WARNING]
> **This app was vibecoded with AI.** The ArtemideX-specific code (merging the forks, porting
> the features, the Cover Glow redesign and the tests) was written by **Claude**, Anthropic's AI,
> in [Claude Code](https://claude.com/claude-code), directed by me. I'm a beginner, not an
> experienced Android developer. [REA](https://github.com/morluto/rea) (by morluto) was used
> alongside it for reverse-engineering and analysis.
>
> The features ported from other forks are their authors' work, credited below. What the AI
> wrote has on-device tests, but it hasn't been reviewed line by line by an experienced
> developer. Treat it as experimental, and read the code before trusting it with anything
> important.

> [!IMPORTANT]
> **Status: early.** Every feature is covered by on-device tests on a Snapdragon 8 Elite tablet
> (Android 16), but most haven't yet been used in a real stream. Expect rough edges, and please
> report what you find.
> <!-- Update this note once you've tested with a paired PC. -->

> [!NOTE]
> Several features need a host based on **Apollo**, such as
> [Vibepollo](https://github.com/Nonary/Vibepollo) or
> [Apollo](https://github.com/ClassicOldSong/Apollo). They're marked **(Apollo / Vibepollo)**
> below. Everything else works with [Sunshine](https://github.com/LizardByte/Sunshine) too.

## Where each feature comes from

ArtemideX stands on a lot of other people's work. Every feature below says where it came from:

- **Ported from *project* (*author*)**: that project's code, brought into ArtemideX and adapted.
- **Idea from *project* (*author*)**: inspired by that project, but written new for ArtemideX.
- **New in ArtemideX**: written for ArtemideX.

Where a feature started in an older fork and was carried forward (for example Artemis → Artemide),
both are named.

---

## Cover Glow: the new look

*New in ArtemideX.*

- **Game screen:** a big panel for the selected game, with its cover, name, Play/Resume, and
  its stream settings shown as small tags (codec, resolution and FPS, frame pacing, V-Sync, HDR,
  audio haptics, custom settings). The covers sit in a row below. It starts on the game running
  on the PC, otherwise the one you played last.
- **Colours from the cover:** the background and glow take the selected game's cover colour and
  fade between games. White text stays readable on every colour.
- **PC list:** frosted cards on a dark teal background, and frosted pills for the quick-settings
  bar.
- **Settings:** a back button and title, plus a row of section buttons that jump to each
  section and follow you as you scroll. Section names are in plain language (Stream, Sound &
  vibration, Controllers, In-stream menu, Touch & mouse…) in English.
- **"What you'll feel" panel** (wide screens): shows where your frame pacing and V-Sync choices
  sit between *fastest response* and *smoothest motion*, with rough delay and battery
  comparisons. These are comparisons between the choices, not measurements.
- **In-stream menu:** a side sheet in the game's colour, with the game and stream on top, a grid
  of tools, and Close, Quit and Disconnect at the bottom. Fully usable with a controller.

## In-stream menu

The menu itself, its quick actions and custom commands come from Moonlight X
(*MoreOrLessSoftware*). ArtemideX adds:

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
| Monitor switcher (Monitor 1–6) **(Apollo / Vibepollo)** | Idea from Moonlit Artemis (Mohammad Forhad / 4had45) |
| Change bitrate mid-stream **(Vibepollo)** | Idea from Moonlight V+ (qiin2333) |

## Streaming and video

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

## Input and feel

| Feature | Source |
| --- | --- |
| Pinch to zoom anytime, outside Pan/Zoom mode | Idea from Moonlit Artemis (Mohammad Forhad / 4had45); uses Artemis's pan/zoom code (Yukino Song) |
| Automatic clipboard sync (send yours when the stream starts, copy the PC's when you leave) **(Apollo / Vibepollo)** | Ported from Artemis (Yukino Song), via Artemide |
| Audio haptics: the game's bass becomes vibration on the device and/or the controller | Idea from Moonlight V+ (qiin2333); written new for ArtemideX |
| Hide PC cursor (never / while touching / always) **(Vibepollo)** | New in ArtemideX |
| Long-press to right-click in gaming trackpad mode | New in ArtemideX |
| Per-game settings for the new options (codec, V-Sync, LFR, async decode, display, touch mode, cursor, audio haptics) | Extends Moonlight X's per-app settings (MoreOrLessSoftware) |

## Kept from Moonlight X

Quick Launch apps, per-app settings, brightness slider, bitrate and stats overrides, Force HDR,
the in-stream overlay menu with custom commands, the improved stats overlay, and Ultra Low
Latency. All by MoreOrLessSoftware. See the
[Moonlight X README](https://github.com/MoreOrLessSoftware/moonlight-android).

## Not included

- Artemide's customisable on-screen key buttons
- Artemide's other MediaTek queue/preload values and its NVIDIA Tegra and legacy Qualcomm decoder
  extras (there's no device here to try them on)

## Credits

ArtemideX would not exist without these projects and their authors:

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
| [Vibepollo](https://github.com/Nonary/Vibepollo) | Chase Payne (Nonary) | The host ArtemideX is developed against (fork of Apollo) |
| [Apollo](https://github.com/ClassicOldSong/Apollo) / [Sunshine](https://github.com/LizardByte/Sunshine) | ClassicOldSong / LizardByte | The hosts Vibepollo is built on |

### Tools used to make ArtemideX

| Tool | Author | Used for |
| --- | --- | --- |
| [Claude Code](https://claude.com/claude-code) with Claude | Anthropic | Writing the ArtemideX code, ports, redesign and tests (see the note at the top) |
| [REA](https://github.com/morluto/rea) | morluto | Reverse-engineering and analysis (MIT) |

If I've credited something wrongly or missed you, please open an issue and I'll fix it.

## Licence

GPL-3.0, like Moonlight and every fork it builds on. See [LICENSE.txt](LICENSE.txt). PyroWave
(MIT) and libadrenotools (BSD-2-Clause) keep their own licences.
