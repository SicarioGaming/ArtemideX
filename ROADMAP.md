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
  - [ ] On-screen full keyboard and special-keys bar
  - [ ] Automatic clipboard sync when the stream starts or loses focus
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

## Phase 4: Redesign

- [ ] Mockups of the new look to choose from before building
- [ ] More colour, clearer settings layout, smoother animations, and more
      shortcuts in the in-stream menu

## Open questions

- Artemide has no option named "Tight Vsync"; its V-Sync options are V-Sync,
  Fast V-Sync, Sync: LFR and Async: LFR, and all four are planned above.
- Clipboard sync, server commands and Virtual Display were built for Apollo;
  check each one against Vibepollo while testing.
