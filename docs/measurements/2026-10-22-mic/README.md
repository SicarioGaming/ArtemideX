# Microphone test, 22 October 2026

The data behind the microphone numbers in Starwire 0.2.0: the README's **How it works and what I
measured**, the chart in `docs/images/mic-measured.png` and the 0.2.0 post on r/MoonlightStreaming.
It's here so anyone can check them.

## Reproduce it

You need Python 3 (standard library only). From this folder:

```
python3 -I analyse.py
```

On Windows use `py -3 -I analyse.py`. It reads the CSV files next to it, takes about a second and
prints the same output every time. The last part puts each published number next to what the data
gives and says `same`, `close` or `DIFF`.

## What I tested

- **Phone to PC, 90 seconds.** My RedMagic Astra tablet on Wi-Fi, streaming from my PC on Ethernet,
  with **Send my microphone** on. On the PC, `MicTest proxy` (the C# file here) listened on UDP
  6980 where Starwire sends, noted when each packet arrived and passed it on to
  `StarwireMicReceiver` on the same PC. At the same time `MicTest level` recorded how loud CABLE
  Output was every 10 ms. The build was the 0.2.0 work in progress that evening.
- **PC only.** `MicTest latency` sent fake VBAN packets to the receiver on the same PC, with a short
  1 kHz click every 94 packets (about every 0.5 s), recorded CABLE Output and timed each click from
  being sent to being heard. Two of those runs sent the packets 2000 ppm too fast, to make the
  phone's clock drift show up in a minute instead of an hour: one with the receiver as it was before
  that evening's fix, one with the fix (skip a 10 ms block when the buffer stays above 60 ms for
  2 seconds).

## The files

| File | What it is |
| --- | --- |
| `arrivals.csv` | One row per packet from the tablet: `t_ms` (arrival on the PC's clock, ms), `frame` (VBAN frame counter), `length` (bytes, header included) and `peak` (loudest sample in it). 16,880 rows. |
| `cable-output-level.csv` | CABLE Output during the same 90 s: `t_ms`, `rms` and `peak` for each 10 ms window. Only levels were kept, no audio. |
| `pc-delay-20s.csv` | PC only, 20 s, no extra drift: `click`, `sent_ms`, `delay_ms`. |
| `pc-delay-release-10s.csv` | The same with the receiver from the release build, 10 s. |
| `pc-delay-drift-old-receiver.csv` | 60 s at 2000 ppm with the receiver before the drift fix. |
| `pc-delay-drift-new-receiver.csv` | 60 s at 2000 ppm with the drift fix. |
| `analyse.py` | Recomputes everything below from those files. |
| `MicTest.cs` | The tool that recorded them. It's a test helper, not part of Starwire. |

There are no network addresses in any of these: the logger only kept times, frame numbers, sizes
and levels. I left out `packets.bin`, which the proxy also writes (the 40 loudest packets, so raw
audio from the tablet's mic). The packet picture in `docs/images/mic-packet.png` is one of them.

I didn't save the exact command lines from that evening. These do the same thing (build with the
C# compiler that comes with Windows):

```
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /out:MicTest.exe MicTest.cs
StarwireMicReceiver.exe --port 6990
MicTest proxy 90 6980 6990 .
MicTest level 90 cable-output-level.csv
MicTest latency 20 6980 0 pc-delay-20s.csv
MicTest latency 60 6980 2000 pc-delay-drift-new-receiver.csv
```

The first `latency` run is with the receiver back on its normal port (6980) and nothing else
sending to it.

## What the numbers are, and how each one is worked out

**Packets.** 16,880 packets in 90.0 s, frames 24112 to 40991 with none missing, none out of order
and no duplicates. Each one is 540 bytes (28-byte VBAN header and 256 samples). With 28 bytes of
UDP and IPv4 under each, that's 0.852 Mbps.

**Arrival against the ideal schedule.** A packet holds 256 samples, so one should arrive every
5.333 ms. The script fits a straight line to arrival time against each packet's place in the audio
(`frame` x 256 / 48 kHz). The slope says the tablet's clock ran 63.2 ppm fast against the PC's. How
late each packet was against that line, with the earliest one as zero: **p50 10.9 ms, p99 21.0 ms,
max 38.2 ms**. Percentiles are nearest-rank.

Most of that spread is how Starwire sends: it reads 20 ms (960 samples) at a time and sends the
packets that fill as one burst. 97% of bursts are 3 or 4 packets. Timed against the end of each
20 ms read instead, packets were p50 2.0 ms, p99 6.4 ms and at most 21.6 ms late. That's Wi-Fi plus
scheduling on the tablet and the PC, so it's an upper bound for what the network added.

**The receiver's buffer.** The script replays those arrival times through the receiver's 0.2.0
rules: 10 ms blocks, playback starts once 4 are queued, a block that arrives with 12 queued is
dropped, and after an underrun it waits for 4 again. It's idealized: playback starts the moment the
fourth block arrives and then takes exactly one block every 10 ms on the PC's clock. Result: **no
underruns and nothing dropped** over 8,998 blocks. Counting queued blocks right after each one
arrives: 50 ms 3,258 times, 40 ms 4,499, 30 ms 1,229 and 20 ms 12 times. The audio actually left in
the queue was between 2.9 ms (just before a block arrived) and 47.5 ms (just after).

**CABLE Output.** 9,000 windows of 10 ms and none of them exactly silent, which is what a dropout
would look like. The quiet windows are just quiet (most likely the tablet's noise suppressor), not
gaps.

**PC side.** From a packet being sent to the receiver on the PC to the click showing up on CABLE
Output: median **107.4 ms** over 40 clicks, and **119.4 ms** over 20 clicks with the release build.
At 2000 ppm the fixed receiver stayed between **88.6 and 122.1 ms**, while the old one climbed to
**179.9 ms** in a minute.

**Drift.** At 63.2 ppm the delay grows 3.8 ms a minute, so the old receiver would have gone from
40 ms to its 120 ms cap in about 21 minutes. A phone whose clock is that much slow drains a 40 ms
buffer in about 10.6 minutes.

## Against what I published

| Published | From the data | |
| --- | --- | --- |
| 16,880 packets in 90 seconds, none lost or out of order | 16,880 in 90.0 s, 0 missing, 0 out of order | same |
| About 0.85 Mbps | 0.852 Mbps | same |
| Arrival p50 10.9 ms, p99 21 ms, max 38 ms | 10.866, 20.971, 38.246 ms | same |
| A 20 ms read goes out as a burst of 3 or 4 packets | 97.3% of bursts | same |
| The network added about 1 ms at p99 and 18 ms at worst | 6.4 ms at p99 and 21.6 ms at worst, timed against the reads | **different** |
| The buffer stayed at 30 to 50 ms | 30 to 50 ms when sampled every 50 ms, but 20 ms after 12 of 8,998 blocks, and as little as 2.9 ms of audio left at one point | **different** |
| No underruns, nothing dropped | 0 and 0 | same |
| CABLE Output with no dropouts | 0 of 9,000 windows silent | same |
| 107 ms median in one run, 119 ms in a shorter run of the release build | 107.4 and 119.4 ms | same |
| The tablet's clock ran 60 ppm fast | 63.2 ppm | close |
| About 3.6 ms more delay a minute | 3.8 ms (3.6 is for exactly 60 ppm) | close |
| The old receiver at its 120 ms cap after about 20 minutes | 21.1 minutes | same |
| A slow clock: a 40 ms gap roughly every 10 minutes | 10.6 minutes | same |
| At 2000 ppm: 89 to 122 ms instead of climbing to 180 | 88.6 to 122.1 ms, old receiver up to 179.9 ms | same |

The two that don't hold up:

- **"The network added about 1 ms at p99 and 18 ms at worst."** I got that by taking 20 ms off the
  p99 and the max, but percentiles don't subtract like that. Timed against the end of each 20 ms
  read, the spread left over is 6.4 ms at p99 and 21.6 ms at worst.
- **"The buffer stayed at 30 to 50 ms."** The chart sampled the replay every 50 ms, which missed
  the low points. Checked at every block, it touched 20 ms 12 times, and right before one block
  arrived there was only 2.9 ms of audio left, so it came close to an underrun once in 90 s. The
  replay code behind the original chart wasn't kept; this script's replay gives the same 30 to 50 ms
  range when sampled the same way, but not the same value at every point.

## Limits

One 90-second run, on one tablet, one Wi-Fi network and one PC. The PC-only runs are 10 to 60
seconds. The phone side (Android's capture delay and the 20 ms reads before a packet leaves) isn't
measured here at all.
