"""Recomputes every number from the 22 Oct 2026 microphone test (the Starwire 0.2.0 post and README).

    python3 -I analyse.py [folder]

Reads the CSV files in this folder (or the one given), prints the results and then each published
number next to what the data gives. Standard library only, no network, and the output is the same on
every run and every machine. See README.md for what each file is and how it was recorded.
"""
import csv
import hashlib
import math
import os
import sys

RATE = 48.0                 # samples per ms
PACKET = 256                # samples per VBAN packet
READ = 960                  # samples per AudioRecord read in Starwire (20 ms)
BLOCK = 480                 # samples per receiver block (10 ms)
START_BLOCKS, CAP_BLOCKS = 4, 12   # receiver: start at 40 ms queued, drop new audio at 120 ms
UDP_IPV4 = 8 + 20           # header bytes under each VBAN packet at the IP layer
CLICK_EVERY = 94            # MicTest latency: one click every 94 packets


def pct(values, p):
    """Nearest-rank percentile on the sorted values (same rule as the original analysis)."""
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100.0 * (len(s) - 1))))]


def median(values):
    s = sorted(values)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def fit(xs, ys):
    """Least-squares slope and intercept of ys against xs."""
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    return slope, my - slope * mx


def spread(xs, ys):
    """How late each arrival is against a schedule xs, after taking out the clock drift (ms, min = 0)."""
    slope, _ = fit(xs, ys)
    late = [y - slope * x for x, y in zip(xs, ys)]
    low = min(late)
    return [v - low for v in late], slope


def read_csv(folder, name):
    with open(os.path.join(folder, name), newline='') as f:
        return list(csv.DictReader(f))


def sha256(folder, name):
    with open(os.path.join(folder, name), 'rb') as f:
        return hashlib.sha256(f.read().replace(b'\r\n', b'\n')).hexdigest()


def replay(times):
    """The receiver's rules from 0.2.0, fed with the logged arrival times.

    Every 480 samples make a 10 ms block. Playback starts once 4 blocks are queued and then takes one
    block every 10 ms; a block that arrives with 12 queued is dropped; if the queue runs dry, playback
    stops and starts again at 4 blocks. Returns the audio left in the queue just before and just after
    each block was added (ms), the queued block count after each packet, and the underrun and drop counts.
    """
    fill, starting, waiting, end = 0, True, 0, 0.0
    before, after, per_packet = [], [], []
    underruns = drops = 0
    for t in times:
        fill += PACKET
        while fill >= BLOCK:
            fill -= BLOCK
            if not starting and end <= t:
                underruns += 1
                starting, waiting = True, 0
            if starting:
                waiting += 1
                if waiting >= START_BLOCKS:
                    starting, end = False, t + START_BLOCKS * 10.0
                continue
            left = end - t
            if math.ceil(left / 10.0 - 1e-9) >= CAP_BLOCKS:
                drops += 1
                continue
            before.append(left)
            end += 10.0
            after.append(end - t)
        queued = waiting if starting else max(0, math.ceil((end - t) / 10.0 - 1e-9))
        per_packet.append((t, queued * 10))
    return before, after, per_packet, underruns, drops


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    found = {}

    print('Files (SHA-256 with LF line endings)')
    for name in ('arrivals.csv', 'cable-output-level.csv', 'pc-delay-20s.csv',
                 'pc-delay-release-10s.csv', 'pc-delay-drift-old-receiver.csv',
                 'pc-delay-drift-new-receiver.csv'):
        print('  %-34s %s' % (name, sha256(folder, name)))

    # --- 1. Packets from the tablet over Wi-Fi --------------------------------------------------------
    rows = read_csv(folder, 'arrivals.csv')
    t = [float(r['t_ms']) for r in rows]
    frames = [int(r['frame']) for r in rows]
    lengths = sorted(set(int(r['length']) for r in rows))
    n = len(rows)
    duration = t[-1] - t[0]
    expected = frames[-1] - frames[0] + 1
    missing = expected - len(set(frames))
    out_of_order = sum(1 for a, b in zip(frames, frames[1:]) if b < a)
    duplicates = n - len(set(frames))
    mbps = n * (lengths[0] + UDP_IPV4) * 8 / (duration / 1000.0) / 1e6
    found.update(packets=n, lost=missing, out_of_order=out_of_order, seconds=duration / 1000.0, mbps=mbps)
    print()
    print('1. Arrivals on the PC: arrivals.csv')
    print('  packets %d over %.3f s (%.2f per second), frames %d to %d' % (
        n, duration / 1000.0, n / (duration / 1000.0), frames[0], frames[-1]))
    print('  missing %d, out of order %d, duplicates %d' % (missing, out_of_order, duplicates))
    print('  packet size %s bytes, %.3f Mbps at the IP layer (+%d bytes UDP and IPv4 each)' % (
        '/'.join(map(str, lengths)), mbps, UDP_IPV4))

    # --- 2. Clock drift and arrival time against the ideal schedule -----------------------------------
    media = [(f - frames[0]) * PACKET / RATE for f in frames]          # when each packet's audio starts
    late, slope = spread(media, t)
    ppm = (1 - slope) * 1e6
    found.update(ppm=ppm, p50=pct(late, 50), p99=pct(late, 99), max=max(late))
    print()
    print('2. Arrival time against the ideal schedule (one packet every 5.333 ms, drift taken out)')
    print('  the tablet clock ran %.1f ppm fast against the PC (fit slope %.9f)' % (ppm, slope))
    print('  p50 %.3f ms, p99 %.3f ms, p99.9 %.3f ms, max %.3f ms' % (
        pct(late, 50), pct(late, 99), pct(late, 99.9), max(late)))

    bursts, size = {}, 1
    for a, b in zip(t, t[1:]):
        if b - a < 2.0:
            size += 1
        else:
            bursts[size] = bursts.get(size, 0) + 1
            size = 1
    bursts[size] = bursts.get(size, 0) + 1
    total = sum(bursts.values())
    print('  packets arrive in bursts (gaps under 2 ms): %s' % ', '.join(
        '%d x %d' % (bursts[k], k) for k in sorted(bursts, key=lambda k: (-bursts[k], k))))
    three_or_four = (bursts.get(3, 0) + bursts.get(4, 0)) / float(total) * 100
    print('  bursts of 3 or 4 packets: %.1f%%' % three_or_four)

    sent = [math.ceil((f + 1) * PACKET / float(READ)) * READ / RATE for f in frames]  # end of its 20 ms read
    late_read, _ = spread(sent, t)
    found.update(read_p99=pct(late_read, 99), read_max=max(late_read))
    print('  against the end of each 20 ms read instead (what the read bursts leave over):')
    print('  p50 %.3f ms, p99 %.3f ms, max %.3f ms' % (pct(late_read, 50), pct(late_read, 99), max(late_read)))

    # --- 3. The receiver's buffer, replayed from those arrivals ---------------------------------------
    before, after, per_packet, underruns, drops = replay([x - t[0] for x in t])
    blocks_after = {}
    for v in after:
        k = int(math.ceil(v / 10.0 - 1e-9)) * 10
        blocks_after[k] = blocks_after.get(k, 0) + 1
    ticks = []
    j = 0
    for k in range(int(duration // 50) + 1):          # every 50 ms, the count after the last packet
        while j + 1 < len(per_packet) and per_packet[j + 1][0] <= k * 50:
            j += 1
        ticks.append(per_packet[j][1] if per_packet[j][0] <= k * 50 else 0)
    found.update(underruns=underruns, drops=drops, tick_min=min(ticks[20:]), tick_max=max(ticks),
                 left_min=min(before), left_max=max(after))
    print()
    print('3. Receiver buffer, replayed with its 0.2.0 rules (10 ms blocks, start at 40, drop at 120)')
    print('  underruns %d, blocks dropped %d, blocks played %d' % (underruns, drops, len(after)))
    print('  blocks queued right after each block arrives: %s' % ', '.join(
        '%d ms x %d' % (k, blocks_after[k]) for k in sorted(blocks_after)))
    print('  sampled every 50 ms (as in the published chart), after the first second: %d to %d ms' % (
        min(ticks[20:]), max(ticks)))
    print('  audio actually left: %.1f ms at the lowest (just before a block), %.1f ms at the highest '
          '(just after); median %.1f before, %.1f after' % (min(before), max(after), median(before), median(after)))

    # --- 4. CABLE Output level during the run ---------------------------------------------------------
    level = read_csv(folder, 'cable-output-level.csv')
    silent = sum(1 for r in level if int(r['peak']) == 0)
    loud = sum(1 for r in level if float(r['rms']) > 32.8)
    found.update(level_windows=len(level), silent=silent)
    print()
    print('4. CABLE Output level: cable-output-level.csv')
    print('  %d windows of 10 ms, %d exactly silent (a dropout would be), %d above -60 dBFS, peak %d' % (
        len(level), silent, loud, max(int(r['peak']) for r in level)))

    # --- 5. PC-only delay: a click sent to the receiver on this PC until it's heard on CABLE Output ---
    print()
    print('5. PC only, from a packet arriving to it being heard on CABLE Output')
    for name, key in (('pc-delay-20s.csv', 'base'), ('pc-delay-release-10s.csv', 'release'),
                      ('pc-delay-drift-old-receiver.csv', 'old'), ('pc-delay-drift-new-receiver.csv', 'new')):
        r = read_csv(folder, name)
        clicks = [int(x['click']) for x in r]
        sent_ms = [float(x['sent_ms']) for x in r]
        delay = [float(x['delay_ms']) for x in r]
        every, _ = fit(clicks, sent_ms)
        drift = (CLICK_EVERY * PACKET / RATE / every - 1) * 1e6
        found[key] = (median(delay), min(delay), max(delay))
        print('  %-33s %3d clicks, sent about %d ppm fast, median %.1f, min %.1f, max %.1f ms, last 5: %s' % (
            name, len(delay), int(round(drift / 1000.0)) * 1000, median(delay), min(delay), max(delay),
            ' '.join('%.0f' % d for d in delay[-5:])))

    # --- 6. What the drift means over time ------------------------------------------------------------
    per_minute = found['ppm'] * 1e-6 * 60000
    print()
    print('6. Drift arithmetic at the measured %.1f ppm' % found['ppm'])
    print('  %.2f ms more delay a minute; %.1f minutes from 40 ms to the 120 ms cap; with a clock this much'
          ' slow, %.1f minutes to drain 40 ms' % (per_minute, 80 / per_minute, 40 / per_minute))
    found.update(per_minute=per_minute, to_cap=80 / per_minute, to_drain=40 / per_minute)

    # --- Published numbers against the data -----------------------------------------------------------
    f = found
    claims = [
        ('16,880 packets in 90 seconds', '%d in %.1f s' % (f['packets'], f['seconds']),
         f['packets'] == 16880 and round(f['seconds']) == 90),
        ('none lost, none out of order', '%d lost, %d out of order' % (f['lost'], f['out_of_order']),
         f['lost'] == 0 and f['out_of_order'] == 0),
        ('about 0.85 Mbps', '%.3f Mbps' % f['mbps'], round(f['mbps'], 2) == 0.85),
        ('arrival p50 10.9 ms', '%.3f ms' % f['p50'], round(f['p50'], 1) == 10.9),
        ('arrival p99 21 ms', '%.3f ms' % f['p99'], round(f['p99']) == 21),
        ('arrival max 38 ms (38.2 on the chart)', '%.3f ms' % f['max'], round(f['max'], 1) == 38.2),
        ('bursts of 3 or 4 packets per 20 ms read', '%.1f%% of bursts' % three_or_four, three_or_four > 95),
        ('network added about 1 ms at p99, 18 ms at worst',
         'p99 %.1f ms, max %.1f ms beyond the read schedule' % (f['read_p99'], f['read_max']),
         round(f['read_p99']) == 1 and round(f['read_max']) == 18),
        ('buffer 30 to 50 ms', '%d-%d ms every 50 ms; per block %d-%d ms; audio left %.1f-%.1f ms' % (
            f['tick_min'], f['tick_max'], min(blocks_after), max(blocks_after), f['left_min'], f['left_max']),
         min(blocks_after) >= 30 and max(blocks_after) <= 50),
        ('no underruns, nothing dropped', '%d underruns, %d dropped' % (f['underruns'], f['drops']),
         f['underruns'] == 0 and f['drops'] == 0),
        ('CABLE Output level with no dropouts', '%d of %d windows silent' % (f['silent'], f['level_windows']),
         f['silent'] == 0),
        ('PC side 107 ms median in one run', '%.1f ms' % f['base'][0], round(f['base'][0]) == 107),
        ('119 ms in a shorter run of the release build', '%.1f ms' % f['release'][0],
         round(f['release'][0]) == 119),
        ('tablet clock 60 ppm fast', '%.1f ppm' % f['ppm'], 'close' if round(f['ppm'], -1) == 60 else False),
        ('about 3.6 ms more delay a minute', '%.2f ms (3.6 is for exactly 60 ppm)' % f['per_minute'],
         'close' if abs(f['per_minute'] - 3.6) < 0.3 else False),
        ('120 ms cap after about 20 minutes', '%.1f minutes' % f['to_cap'], 15 <= f['to_cap'] <= 25),
        ('a 40 ms gap roughly every 10 minutes (slow clock)', '%.1f minutes' % f['to_drain'],
         8 <= f['to_drain'] <= 12),
        ('2000 ppm test: new receiver 89-122 ms', '%.1f-%.1f ms' % f['new'][1:],
         round(f['new'][1]) == 89 and round(f['new'][2]) == 122),
        ('2000 ppm test: old receiver climbing to 180', 'max %.1f ms' % f['old'][2], round(f['old'][2]) == 180),
    ]
    print()
    print('Published numbers against the data')
    for claim, value, ok in claims:
        print('  %-5s %-50s %s' % ('close' if ok == 'close' else 'same' if ok else 'DIFF', claim, value))


if __name__ == '__main__':
    main()
