# ArtemideX roadmap

ArtemideX is a Moonlight Android client for streaming from a Vibepollo host to a
nubia NP05J tablet (Snapdragon 8 Elite, Android 16). It keeps Moonlight X's video
pipeline (Direct Frame Pacing, PyroWave, Vulkan renderer) and adds Artemide's
options and in-stream menu, selected features from other forks, and a new UI.

Base: Moonlight X (`MoreOrLessSoftware/moonlight-android`, branch `mlsoft-x`).
Work happens on the `artemidex` branch. Every source below is GPL-3.0, so
ArtemideX is GPL-3.0 too.

Each step ends with a build installed on the tablet and tested before the next starts.

## Phase 1: Identity

- [x] Rename the app to "ArtemideX" (`com.artemidex`, debug builds `com.artemidex.debug`),
      so it installs beside Moonlight X and Artemide instead of replacing them.

## Phase 2: Artemide features

Source: Artemide Experimental Async 0.5.1 (`derflacco/moonlight-android`, tag
`Artemide_Experimental_Async_0.5.1`), the version installed on the tablet.

- In-stream menu. Artemide's actions are added to Moonlight X's overlay menu
  (icon buttons, controller navigation) rather than Artemide's plain list dialog.
  - [x] Floating menu button: tap to open, drag to move; on by default, with a
        setting under Overlay Menu and a show/hide button in the menu
  - [x] Shortcuts page with Artemide's 14 key combos (Esc, F11, Alt+F4, Alt+Enter,
        Ctrl+V, Win, Win+D, Win+G, Ctrl+Alt+Tab, Shift+Tab, Win+Shift+Left,
        Ctrl+Alt+Shift+F1, Ctrl+Alt+Shift+F12, Win+Alt+B)
  - [x] Task Manager, Rotate screen, On-screen controller toggle
  - [x] Disconnect, Quit session, Stats, Soft keyboard, Mouse emulation (already in Moonlight X)
  - [x] Pan/zoom mode (pinch to zoom, drag to pan, double-tap to reset)
  - [x] Touch mode picker with Artemide's six modes (multi-touch, direct touch, natural and
        gaming trackpad, disabled, swapped clicks); starts from the trackpad setting each stream
  - [x] Touch sensitivity: a trackpad sensitivity setting, toggled from the menu
  - [x] Clipboard send/get and PC (server) commands. Vibepollo supports both
        (`/actions/clipboard`, packet 0x3000); each needs the client's permission in Vibepollo.
        Server commands needed a change to moonlight-common-c, committed on its local
        `artemidex` branch (not pushed anywhere yet)
  - [x] On-screen full keyboard (Full Keyboard in the menu): Artemide's 82-key layout, key
        popups, vibration and sticky Ctrl/Alt/Shift/Win, with opacity and vibration settings.
        Keys go straight to the host as keyboard input
  - [x] Automatic clipboard sync (Host Settings): sends this device's clipboard when the stream
        starts or you return to it, copies the PC's when you leave (also before Disconnect and
        Quit Session, waiting up to 2 seconds); optional messages and "hide clipboard content"
  - Not ported: Artemide's customisable on-screen key buttons (KeyBoardController, ~3,000 lines)
  - [ ] Custom shortcuts imported from a file (Moonlight X's custom commands already cover adding your own)
- [x] Virtual Display: a "Display on the PC" setting (host default, virtual, primary) and
      "Start in Virtual/Primary Display" when long-pressing a game. Host default sends nothing,
      because Vibepollo treats `virtualDisplay=0` as turning its virtual display off
- [x] Prevent Packet Loss: a tiny ignored control packet every 20 ms keeps Wi-Fi awake
      (new `LiSendKeepAlivePayload` in common-c)
- [x] V-Sync and Fast V-Sync: one "V-Sync" setting (Off, V-Sync, Fast V-Sync), off by
      default and used only by the direct renderer. Fast V-Sync is Artemide's nano pacer
      (NanoPacer.java) in "Prefer lowest latency" pacing. Artemide's own V-Sync only drives
      its GL upscaler, which ArtemideX doesn't have, so V-Sync here holds each frame for the
      next display refresh instead
- [x] Sync: LFR: off by default; in Balanced pacing keeps only the newest decoded frame
      (the other pacing modes already drain to the newest frame)
- [x] Async decode and Async: LFR, both off by default (Async LFR needs Async decode).
      Artemide's AsyncCodecAdapter, used only by the direct renderer without host frame
      timing. Codec recovery follows Artemide: release queued buffers, start() after flush,
      detach callbacks before stop()/reset() and attach them again before configure().
      Tested against the device's real H.264 decoder, including all three recovery paths
- Untouched by all of the above: the Vulkan renderer, host frame timing pacing, PyroWave
- [x] Lite mode performance overlay: one centered line with bandwidth, network/decode delay,
      loss and FPS (Advanced settings, under "Show performance stats")
- [ ] Check Moonlight X's existing Ultra Low Latency and Force Full Range Video
      against Artemide's behaviour

## Phase 3: Features from other forks

| Feature | Source fork |
| --- | --- |
| More ways to open the menu (long-press). Three-finger tap and holding Select, Start, Guide or LB+RB already work in Moonlight X | Moonlight X Turnip (`NoCrypt/moonlight-x-turnip`) |
| Per-game settings (resolution, FPS, bitrate, frame pacing) | Moonlight X Turnip |
| Custom PyroWave Vulkan driver (Turnip via libadrenotools) | Moonlight X Turnip |
| Better performance stats (1% lows, received vs displayed FPS warning) | Moonlight V+ (`qiin2333/moonlight-vplus`), Moonlight X Turnip |
| Change bitrate mid-stream from the menu | Moonlight V+ |
| Audio haptics (bass becomes vibration) | Moonlight V+ |
| Pinch-to-zoom anytime, accurate taps while zoomed | Moonlit Artemis (`4had45/moonlit-artemis`) |
| Monitor switcher | Moonlit Artemis |

Done so far (2026-10-21):

- [x] Monitor switcher: Monitors page in the menu, Monitor 1-6 send Ctrl+Alt+Shift+F1-F6
- [x] Bitrate mid-stream: Bitrate in the menu, 5-500 Mbps, via Vibepollo `/bitrate`
- [x] Better stats: 1% low FPS (average of the slowest 1% of frame gaps) in both overlays
- [x] Pinch to zoom anytime (Input settings, on by default; not in Multi-touch mode)
- [x] Per-game settings: App Settings (long-press a game) can also set the video codec, V-Sync,
      Sync: LFR, Async decode, Async: LFR, Display on the PC, touch mode, Hide PC cursor and
      Audio haptics for that game or Quick Launch item; each defaults to the global setting
- [x] Audio haptics (Audio settings, off by default): bass below ~120 Hz drives this device's
      vibration and/or the first controller's heavy motor, with a strength setting. The tablet's
      motor is on/off only, so strength becomes pulse length there. Game rumble and audio
      haptics share the controller (the stronger wins)
- [x] Custom PyroWave driver (Advanced settings, off by default): ported from Moonlight X Turnip
      (`0703b360`). Pick a Turnip .so/.zip/.adpkg; only PyroWave uses it. libadrenotools and its
      hooks are rebuilt here from the pinned upstream source rather than using the fork's
      prebuilts. Added: files that aren't 64-bit ARM libraries are refused, and a driver that
      can't run PyroWave falls back to the system driver. Not yet tried with a real Turnip driver

Also added on request: Warp Drive / Warp 2 frame pacing (from Artemide), Hide PC cursor
(never / while touching / always), and Long-press to right-click in the gaming trackpad mode.

### Phase 3 research notes (2026-10-21)

- Per-game settings already exist in Moonlight X (long-press a game, App Settings: resolution,
  FPS, bitrate, frame pacing, HDR, stats; plus Quick Launch). Remaining: add ArtemideX's new
  options (V-Sync, LFR, async decode, touch mode) to it.
- Bitrate mid-stream: Vibepollo `GET /bitrate?bitrate=<kbps>` (HTTPS, any streaming client),
  clamps to the host maximum and returns the applied value in `root.bitrate`.
- Monitor switcher: Vibepollo switches display on Ctrl+Alt+Shift+F1..F13 (input.cpp,
  `switch_display`), so a menu page sending those keys is enough.
- Better stats: Moonlight X already shows received vs rendered FPS variance; add 1% lows.
- Pinch-to-zoom anytime: detect a two-finger pinch (finger span changes) outside pan/zoom mode
  and hand that gesture to PanZoomHandler; not in multi-touch mode.
- Custom PyroWave driver: NoCrypt commit `0703b360` (remote `turnip`), 22 files, ~800 lines plus
  libadrenotools arm64 prebuilts and native Vulkan loader changes. Port it with
  `git cherry-pick`-style care, since our base has newer Vulkan renderer code.
- Audio haptics: new; low-pass the decoded audio, drive the device vibrator or controller rumble.

## Phase 4: Redesign

- [ ] Mockups of the new look to choose from before building
- [ ] More colour, clearer settings layout, smoother animations, and more
      shortcuts in the in-stream menu

## Open questions

- Artemide has no option named "Tight Vsync"; its V-Sync options are V-Sync,
  Fast V-Sync, Sync: LFR and Async: LFR, and all four are planned above.
- Clipboard sync, server commands and Virtual Display were built for Apollo;
  check each one against Vibepollo while testing.
