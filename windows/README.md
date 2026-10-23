# Your microphone on the PC, with any host

Starwire can send your Android device's microphone to the PC, so you can talk in Discord or
in-game voice chat while you stream. This works with **Vibepollo, Sunshine and Apollo**, with no
change to your streaming host. Starwire sends the microphone as VBAN, VB-Audio's network audio
format, wrapped and encrypted, to a small receiver on the PC, which plays it into a virtual
microphone.

## Please read first

From 0.2.1 the audio is encrypted on the way, and the receiver only plays it from devices you've
paired with that PC. Pairing is how the PC knows the audio really comes from your device, and it
gives the two of them a key that nobody else on the network has. Being on the same network is no
longer enough to listen to your microphone or to send sound into the PC's virtual mic.

- **Updating from Starwire 0.2.0?** Update both sides and pair once:
  [Updating from Starwire 0.2.0](#updating-from-starwire-020) below has the steps. 0.2.0 sent
  the microphone unencrypted.
- **Don't show the pairing code while your screen is shared.** Anyone who sees it before you've
  typed it could pair instead of you.
- **Not encrypted**, one of the choices under **Send my microphone to** in Starwire, is for
  Voicemeeter and other VBAN apps. Anyone on the same network can listen to it or send sound into
  it, so use it only on a network you trust (see **Unencrypted VBAN** below).

[SECURITY.md](../SECURITY.md) says what the encryption protects against and what it doesn't.

## What you need

You need two things on the PC, and the setup below installs both:

- **VB-Cable**, a free virtual microphone from [VB-Audio](https://vb-audio.com/Cable/)
  (donationware). Windows needs a signed driver to add a microphone, and VB-Cable is one.
- **Starwire's microphone receiver**, in this folder. It's open source (GPL-3.0) and built on
  your PC from `StarwireMicReceiver.cs` and `SecureMic.cs`.

## Set up the PC (once)

1. Download **Starwire-Microphone-for-Windows.zip** from the
   [latest release](https://github.com/SicarioGaming/Starwire/releases/latest) and unzip it.
2. Double-click **setup-microphone.cmd**. If Windows says it protected your PC or asks whether
   to run a downloaded file, choose **More info → Run anyway** (or **Run**). Then allow it to
   make changes when Windows asks. The setup, in this order:
   - closes the receiver if it's already running, so it's safe to run the setup again;
   - stops if Windows 11's Smart App Control is on, and points you to another receiver
     (see **Smart App Control** below);
   - builds the receiver with the C# compiler that comes with Windows and installs it in
     `Program Files\Starwire`;
   - checks for VB-Cable. Only if it's missing, it downloads it from VB-Audio's site, checks it's
     signed by VB-Audio, and opens its setup: click **Install Driver**, then close it;
   - lets the receiver through Windows Firewall on UDP port 6980, for local-network and private
     VPN (like Tailscale) addresses only. The rule applies on every type of network;
   - makes it start whenever you sign in, and starts it now if VB-Cable is ready. It has no
     window, just an icon in the notification area by the clock (it may be under the ^ arrow);
   - if it started it, plays a test tone through VB-Cable to check the virtual microphone works;
   - warns you and opens Sound settings if VB-Cable took over your default speakers (see
     **CABLE Input** below);
   - shows the next steps and this PC's IP addresses.
3. If the setup says VB-Cable isn't showing up yet, **restart Windows** to finish installing it.
   The receiver starts by itself after the restart.
4. In Discord or the game, choose **CABLE Output (VB-Audio Virtual Cable)** as the microphone.

## Pair Starwire with the PC (once)

The receiver only plays a device's microphone once you've paired that device with the PC.

1. On the PC, right-click the Starwire microphone icon by the clock (it may be under the ^ arrow)
   and choose **Pair a device...**, then **Show the code**. The window shows a code of 16 letters
   and numbers, like `2491-650N-2RBH-G690`.
2. In Starwire on your Android device: **Settings → Sound & vibration → Send my microphone →
   Pair with the PC**. Pick the PC and type the code. Capitals, spaces and dashes don't matter.
3. The PC's window says **Paired with** your device, and the address it paired from. That's it:
   the device stays paired.

The code works once, for 5 minutes. After 10 wrong codes it stops working; choose **Pair a
device...** again for a new one. Pair each device you use (a phone and a tablet, say) the same
way. If two paired devices send at once, the first one keeps the microphone until it has been
quiet for 3 seconds.

Anyone who sees the code before you've typed it could pair instead of you, so don't show it
while your screen is shared. If the window says **Paired with** a device before you've typed the
code, or one that isn't yours, choose **Unpair it** there. And if a second device uses the same
code, the PC undoes the pairing and says the code was used twice; choose **Pair a device...**
again for a new code.

To unpair a device (if it's lost, say), right-click the icon → **Paired devices** → the device →
**Unpair**. It can't send its microphone to the PC again until you pair it again.

## Set up Starwire

**Settings → Sound & vibration**:

1. Turn on **Send my microphone**.
2. Check **Send my microphone to** says **Starwire's receiver on the PC (encrypted)**. It's the
   default.
3. Pair with the PC, as above, if you haven't yet.

The microphone starts with each stream. The port (`6980`) only needs changing if you changed it
on the PC too.

## Updating from Starwire 0.2.0

Starwire 0.2.0 sent the microphone unencrypted, and anyone on the same network could listen to it
or send sound into the PC's microphone. From 0.2.1 it's encrypted, and that needs both sides:

1. Update the Starwire app on your Android device.
2. Run the new setup from this folder on the PC (it replaces the old receiver).
3. Pair them once, as above.

Until both sides are updated the microphone doesn't work: the new receiver refuses 0.2.0's
unencrypted audio (its icon says so and tells you to update and pair), and a 0.2.0 receiver
doesn't understand the new app, which says the receiver isn't answering.

## Details and fixes

- **Encrypted, and only from paired devices.** Starwire still sends VBAN, VB-Audio's network audio
  format, but wrapped: each packet is encrypted and signed with a key made fresh for each stream
  from the key the device and the PC agreed on when they paired. The receiver plays nothing it
  can't check, refuses packets that are recorded and sent again, and never trusts an address on
  its own (anyone can fake one). As an extra layer it still only listens to local-network and
  private VPN addresses (like Tailscale), and so does its firewall rule. The paired devices'
  keys are kept in `%LOCALAPPDATA%\Starwire\mic-pairings.dat`, encrypted by Windows so only your
  Windows account can read them.
- **Unencrypted VBAN, if you really need it.** Starwire's receiver doesn't play plain VBAN (from
  Starwire 0.2.0, Voicemeeter or other VBAN apps) unless you run the setup with
  `-AllowUnencrypted`. That isn't safe: then anyone on the same network can send sound into the
  PC's microphone, and anyone who can see the network's traffic can listen to it. Only use it on
  a network you trust, and only if you need another VBAN app; it's never in the icon's menu, and
  the menu warns while it's on. Run the setup again without it to turn it off.
- **Turning it off.** Right-click the icon, then **Quit**: it stays off until you next sign in.
  To stop it starting at sign-in, run the setup again with `-NoStartup` (see **Options and
  removal** below). That run still starts the receiver once if VB-Cable is ready, so quit it
  afterwards. `-Uninstall` removes it completely.
- **Just under 1 Mbps** of upload while you stream, uncompressed: each encrypted packet is 584
  bytes, 187.5 a second. 0.2.0's plain VBAN measured about 0.85 Mbps.
- **If teammates hear the game,** wear headphones. Starwire turns on the device's echo
  cancelling and noise reduction where it has them, but some phones only cancel echo on calls.
- **CABLE Input must never be your default speakers.** VB-Cable sometimes takes over when it
  installs or after the restart. You'll notice because the PC's sound goes quiet, teammates hear
  your game, or you hear yourself in the stream. The setup warns about this and opens Sound
  settings: set the output you used before as the default (your speakers, the monitor's HDMI
  audio, or Steam Streaming Speakers).
- **The receiver's icon:** hover over it to see which paired device it's receiving, if any.
  Right-click it to **Pair a device...**, see the **Paired devices** (and unpair one), **Check
  the virtual microphone** (tests VB-Cable), **Play a test tone** (for Discord's mic test), open
  this guide (**Setup guide**), or **Quit**. The menu also shows how it plays into VB-Cable.
  Problems show up as Windows notifications, like audio from a device that isn't paired, or
  unencrypted audio from Starwire 0.2.0. Each kind shows at most once every 10 minutes, saying how
  many other addresses it came from in between; `--console` lists the addresses.
- **Troubleshooting:** `& "C:\Program Files\Starwire\StarwireMicReceiver.exe" --console` in
  Terminal (PowerShell) runs the receiver in a window that prints everything it does. Quit the
  icon first. Add `--pair` to have it print a pairing code too.
- **Less delay than 0.2.0:** the receiver plays into VB-Cable through WASAPI in 2.7 ms steps,
  instead of the 10 ms blocks of the older waveOut it used up to 0.2.0. In testing that took about
  7 ms off the delay on a steady connection and about 16 ms with real Wi-Fi timing, with far fewer
  short gaps in the sound. If WASAPI doesn't work on a PC, the receiver uses waveOut by itself,
  and its menu (or `--console`) says why. Add `--waveout` to use waveOut anyway, for example
  `--console --waveout`.
- **Port already in use?** Voicemeeter's VBAN and VBAN-Receptor also use port 6980. The
  receiver tells you when it starts; close the other one, or run the setup with `-Port 6990` and
  set the same port in Starwire.
- **Smart App Control** (Windows 11) blocks programs built on the PC, like the receiver. The setup
  notices and points you to VB-Audio's signed
  [VBAN-Receptor](https://vb-audio.com/Voicemeeter/vban.htm) instead: set its stream to
  `Stream1` and its output to CABLE Input. VBAN-Receptor isn't encrypted, so in Starwire choose
  **Not encrypted**, and only on a network you trust.
- **Options and removal:** right-click inside the unzipped folder, choose **Open in Terminal**,
  and run `.\setup-microphone.cmd -Uninstall` to remove the receiver, its firewall rule, its
  startup entry and the list of paired devices. `-NoStartup` keeps it from starting at sign-in;
  `-Port` changes the port the receiver listens on; `-AllowUnencrypted` (with `-StreamName`) is
  above. VB-Cable stays until you remove it with its own setup (**Remove Driver**), then restart.
- **Other receivers work too, unencrypted.** VB-Audio's
  [VBAN-Receptor or Voicemeeter](https://vb-audio.com/Voicemeeter/vban.htm) can receive plain
  VBAN from Starwire's **Not encrypted** option; point their output at CABLE Input. Anyone on the
  network can listen to that, or send sound into it.
- **Using logabell's Apollo build?** Then you don't need any of this: choose **The PC's
  streaming app** instead, and the microphone goes over the stream itself.
