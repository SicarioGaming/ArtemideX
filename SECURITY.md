# Security

## Reporting a security problem

Please don't put the details in a public issue or discussion. Report it privately instead:

1. Go to the repo's [Security tab](https://github.com/SicarioGaming/Starwire/security) and choose
   **Report a vulnerability** (or use [this link](https://github.com/SicarioGaming/Starwire/security/advisories/new)).
2. Say which part of Starwire it's in (the Android app, the Windows microphone receiver or its
   setup), what you found and how to reproduce it. Only you and I can see the report.

I'll reply there. If you can't use GitHub's form, open an issue that only says you've found a
security problem, without the details, and I'll get in touch.

Fixes go into new releases, so please check the problem is still there in the
[latest release](https://github.com/SicarioGaming/Starwire/releases/latest).

## Known issue: the 0.2.0 microphone isn't encrypted

This is about **Settings → Sound & vibration → Send my microphone** with **Send my microphone
to** set to **A VBAN receiver on the PC (any host)**, which is the default, and the receiver that
`setup-microphone.cmd` installs on the PC.

In 0.2.0 the audio goes from your Android device to the PC as plain VBAN over UDP (port 6980). It
isn't encrypted or authenticated. That means:

- Someone on the same network can listen to what your microphone hears while you stream.
- Someone on the same network can send their own audio to the receiver, and it plays into your
  PC's virtual mic, so Discord or the game hears it as you.

Starwire only sends to private addresses (local network or VPN), and the receiver and its
firewall rule only accept private addresses and the right stream name (`Stream1` by default).
None of that is a security boundary. Everyone on your network has a private address, a UDP
packet's sender address is easy to fake, and the stream name is public. The firewall rule also
applies on every kind of network Windows knows about, public Wi-Fi included.

Until the fix ships:

- Use the microphone only on a network you trust, like your own home network.
- Away from home, send it over [Tailscale](https://tailscale.com/) or a VPN like it. That encrypts
  the audio between your device and the PC.
- If the PC is on a shared network (a dorm, office, hotel or LAN party), quit the receiver, even if
  you use Tailscale, because it still accepts audio from the local network: right-click its icon by
  the clock, then **Quit**. It starts again the next time you sign in to Windows. To stop that, run
  the setup again with `-NoStartup` (then quit it), or remove it with `-Uninstall`. The
  [microphone guide](windows/README.md) has the steps.

I'm working on a fix.

## The custom Vulkan driver option

**Settings → Advanced → Custom Vulkan driver** is off by default. It's experimental, and it
hasn't been tried with a real Turnip driver yet.

When you turn it on and pick a driver file (`.so`, `.zip` or `.adpkg`) with Android's file
picker, Starwire copies that file into its own private storage. On 64-bit ARM devices with
Android 10 or newer, PyroWave streams then load it in place of the device's own Vulkan driver.
Other codecs keep using the system driver. Starwire never downloads a driver itself.

To load it, Starwire uses libadrenotools, whose hook libraries change how Android loads the
Vulkan driver inside Starwire's own process. Those libraries are in every 64-bit ARM APK, but
they're only loaded when the option is on and a driver has been picked.

A driver is native code. It runs inside Starwire with all of Starwire's permissions, including
the network, the microphone and the keys for your paired PCs. So only use a driver from a source
you trust. Starwire refuses files that aren't 64-bit ARM libraries, and goes back to the system
driver when a driver can't load or can't run PyroWave. A driver that loads and then crashes will
end the stream.

## Checking that a download is really Starwire

Every Starwire APK is signed with the same certificate. Its SHA-256 fingerprint is:

```
B6:04:1D:43:9F:28:4F:17:16:64:6F:C0:F9:80:F1:9A:76:17:8F:A7:04:D5:6F:50:34:D4:06:2A:D3:C1:3F:97
```

For [AppVerifier](https://github.com/soupslurpr/AppVerifier), the package name is `com.starwire`.
With the Android SDK's build tools, `apksigner verify --print-certs Starwire-0.2.0.apk` should show
`certificate SHA-256 digest: b6041d439f284f1716646fc0f980f19a76178fa704d56f5034d4062ad3c13f97`.
The [README](README.md#more-details) has more on this, including the Windows zip.

Android won't install an update over Starwire if it was signed with a different certificate, so
if it refuses, don't force it by uninstalling first. Check where the file came from.
