# Your microphone on the PC, with any host

Starwire can send your phone or tablet's microphone to the PC, so you can talk in Discord or
in-game voice chat while you stream. This works with **Vibepollo, Sunshine and Apollo**, with no
change to your streaming host. Starwire sends the microphone as VBAN, VB-Audio's network audio
format, to a small receiver on the PC, which plays it into a virtual microphone.

You need two things on the PC, and the setup script below installs both:

- **VB-Cable**, a free virtual microphone from [VB-Audio](https://vb-audio.com/Cable/)
  (donationware). Windows needs a signed driver to add a microphone, and VB-Cable is one.
- **Starwire's microphone receiver**, in this folder. It's open source (GPL-3.0) and built on
  your PC from `StarwireMicReceiver.cs`.

## Set up the PC (once)

1. Download **Starwire-Microphone-for-Windows.zip** from the
   [latest release](https://github.com/SicarioGaming/Starwire/releases/latest) and unzip it.
2. Right-click `setup-microphone.ps1` and choose **Run with PowerShell**. Allow it to make
   changes when Windows asks. The script:
   - downloads VB-Cable from VB-Audio's site, checks it's signed by VB-Audio, and opens its setup:
     click **Install Driver**, then close it;
   - builds and installs the receiver in `Program Files\Starwire`;
   - lets the receiver through Windows Firewall, for your home network and private VPNs (like
     Tailscale) only;
   - starts the receiver now and whenever you sign in (minimized in the taskbar);
   - plays a test tone through VB-Cable to check the virtual microphone works.
3. If VB-Cable was new, **restart Windows** to finish installing it.
4. In Discord or the game, choose **CABLE Output (VB-Audio Virtual Cable)** as the microphone.

## Set up Starwire

**Settings → Sound & vibration**:

1. Turn on **Send my microphone**.
2. Set **Send my microphone to** to **A VBAN receiver on the PC (any host)**.

The microphone starts with each stream. The stream name (`Stream1`) and port (`6980`) only need
changing if you changed them on the PC too.

## Good to know

- **Home network only.** VBAN isn't encrypted, so Starwire only sends your voice to a PC on your
  home network or a private VPN like Tailscale (which encrypts it). The receiver only accepts
  those addresses too.
- **About 0.8 Mbps** of upload while you stream, uncompressed. Echo cancelling and noise
  reduction run on the phone first, so teammates don't hear the game through your speakers.
- **Check it any time:** run `"C:\Program Files\Starwire\StarwireMicReceiver.exe" --check` to
  test VB-Cable, or `--test-tone` while the receiver runs to hear a beep in Discord's mic test.
- **If Windows' sound goes quiet after installing VB-Cable**, Windows has made "CABLE Input"
  your default speakers. The setup script warns about this and opens Sound settings; pick your
  real speakers there.
- **Remove it:** run `setup-microphone.ps1 -Uninstall`. VB-Cable stays until you remove it
  with its own setup ("Remove Driver").
- **Other receivers work too.** VB-Audio's
  [VBAN-Receptor or Voicemeeter](https://vb-audio.com/Voicemeeter/vban.htm) can receive the
  stream; point their output at CABLE Input.
- **Using logabell's Apollo build?** Then you don't need any of this: choose **The PC's
  streaming app** instead, and the microphone goes over the stream itself.
