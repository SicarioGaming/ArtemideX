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

- [ ] Rename the app to "ArtemideX" with its own application ID, so it installs
      beside Moonlight X and Artemide instead of replacing them.

## Phase 2: Artemide features

Source: Artemide Experimental Async 0.5.1 (`derflacco/moonlight-android`, tag
`Artemide_Experimental_Async_0.5.1`), the version installed on the tablet.

- [ ] In-stream menu button and all of its actions: soft keyboard, performance
      overlay toggle, mouse and stream modes, pan/zoom, rotate screen, on-screen
      controller and keyboard, touch sensitivity, task manager, key shortcuts
      (Esc, Win, Win+D, Alt+Enter, Ctrl+Alt+Tab, and more), server commands,
      clipboard fetch/upload, disconnect, quit session
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
| More ways to open the menu (long-press, LB+RB, three-finger tap) | Moonlight X Turnip (`NoCrypt/moonlight-x-turnip`) |
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
