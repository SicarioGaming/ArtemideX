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
  - [ ] Pan/zoom mode
  - [ ] Mouse mode picker and touch sensitivity
  - [ ] On-screen full keyboard and special-keys bar
  - [ ] Clipboard fetch/upload and server commands (need host support; check with Vibepollo)
  - [ ] Custom shortcuts imported from a file (Moonlight X's custom commands already cover adding your own)
- [ ] Virtual Display (start in virtual or primary display)
- [ ] Prevent Packet Loss
- [ ] V-Sync, Fast V-Sync, Sync: LFR and Async: LFR, without disturbing Moonlight X's
      Direct Frame Pacing
- [ ] Lite mode performance overlay
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
