# Your microphone on the PC, with any host

Starwire can send your Android device's microphone to the PC, so you can talk in Discord or
in-game voice chat while you stream. This works with **Vibepollo, Sunshine and Apollo**, with no
change to your streaming host. Starwire sends the microphone as VBAN, VB-Audio's network audio
format, to a small receiver on the PC, which plays it into a virtual microphone.

## Please read first: it isn't secure yet

In 0.2.0 the microphone audio isn't encrypted or authenticated. Someone on the same network as
the PC or your Android device could listen to it, or send their own audio that plays into the
virtual mic, so Discord or the game hears it as you. The receiver only checks that audio comes
from a private address with the right stream name, and that isn't a security boundary: everyone
on the network has a private address, the sender's address is easy to fake, and the stream name
is public. The firewall rule the setup adds is also on for every type of network, public Wi-Fi
included. Until the fix ships:

- Use it only on a network you trust, like your own home network, or over
  [Tailscale](https://tailscale.com/) or a VPN like it, which encrypts the audio on the way.
- On a shared network (a dorm, office, hotel or LAN party), quit the receiver, even if you use
  Tailscale: right-click its icon by the clock, then **Quit**.
  [Turning it off](#details-and-fixes) below says how to keep it off.

I'm working on a fix. [SECURITY.md](../SECURITY.md) has more.

## What you need

You need two things on the PC, and the setup below installs both:

- **VB-Cable**, a free virtual microphone from [VB-Audio](https://vb-audio.com/Cable/)
  (donationware). Windows needs a signed driver to add a microphone, and VB-Cable is one.
- **Starwire's microphone receiver**, in this folder. It's open source (GPL-3.0) and built on
  your PC from `StarwireMicReceiver.cs`.

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

## Set up Starwire

**Settings → Sound & vibration**:

1. Turn on **Send my microphone**.
2. Check **Send my microphone to** says **A VBAN receiver on the PC (any host)**. It's the
   default.

The microphone starts with each stream. The stream name (`Stream1`) and port (`6980`) only need
changing if you changed them on the PC too.

## Details and fixes

- **Turning it off.** Right-click the icon, then **Quit**: it stays off until you next sign in.
  To stop it starting at sign-in, run the setup again with `-NoStartup` (see **Options and
  removal** below). That run still starts the receiver once if VB-Cable is ready, so quit it
  afterwards. `-Uninstall` removes it completely. Do one of these on a laptop that joins public
  or shared Wi-Fi.
- **Local network only, but not secure.** Starwire only sends your voice to a PC at a
  local-network address or over a private VPN, and the receiver only accepts audio from those
  addresses. That's a filter, not protection: see
  [the security note](#please-read-first-it-isnt-secure-yet) at the top.
- **About 0.85 Mbps** of upload while you stream, uncompressed.
- **If teammates hear the game,** wear headphones. Starwire turns on the device's echo
  cancelling and noise reduction where it has them, but some phones only cancel echo on calls.
- **CABLE Input must never be your default speakers.** VB-Cable sometimes takes over when it
  installs or after the restart. You'll notice because the PC's sound goes quiet, teammates hear
  your game, or you hear yourself in the stream. The setup warns about this and opens Sound
  settings: set the output you used before as the default (your speakers, the monitor's HDMI
  audio, or Steam Streaming Speakers).
- **The receiver's icon:** hover over it to see whether it's receiving your Android device. Right-click it
  to **Check the virtual microphone** (tests VB-Cable), **Play a test tone** (for Discord's mic
  test), open this guide (**Setup guide**), or **Quit**. Problems show up as Windows notifications.
- **Troubleshooting:** `& "C:\Program Files\Starwire\StarwireMicReceiver.exe" --console` in
  Terminal (PowerShell) runs the receiver in a window that prints everything it does. Quit the
  icon first.
- **Port already in use?** Voicemeeter's VBAN and VBAN-Receptor also use port 6980. The
  receiver tells you when it starts; close the other one, or run the setup with `-Port 6990` and
  set the same port in Starwire.
- **Smart App Control** (Windows 11) blocks programs built on the PC, like the receiver. The setup
  notices and points you to VB-Audio's signed
  [VBAN-Receptor](https://vb-audio.com/Voicemeeter/vban.htm) instead: set its stream to
  `Stream1` and its output to CABLE Input.
- **Options and removal:** right-click inside the unzipped folder, choose **Open in Terminal**,
  and run `.\setup-microphone.cmd -Uninstall` to remove the receiver, its firewall rule and its
  startup entry. `-NoStartup` keeps it from starting at sign-in; `-StreamName` and `-Port`
  change what it listens for. VB-Cable stays until you remove it with its own setup
  (**Remove Driver**), then restart.
- **Other receivers work too.** VB-Audio's
  [VBAN-Receptor or Voicemeeter](https://vb-audio.com/Voicemeeter/vban.htm) can receive the
  stream; point their output at CABLE Input.
- **Using logabell's Apollo build?** Then you don't need any of this: choose **The PC's
  streaming app** instead, and the microphone goes over the stream itself.
