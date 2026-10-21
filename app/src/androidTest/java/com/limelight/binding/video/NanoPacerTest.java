package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast V-Sync's pacer (from Artemide), driven with real timing and a fake decoder.
 */
@RunWith(AndroidJUnit4.class)
public class NanoPacerTest {
    private static final int FPS = 60;
    private static final long FRAME_NS = 1_000_000_000L / FPS;

    // Stands in for MediaCodec: hands out queued outputs and records releases
    private static class FakeDecoder implements NanoPacer.OutputCallbacks {
        final ArrayDeque<long[]> ready = new ArrayDeque<>(); // {index, ptsUs}
        final List<Integer> releasedWithoutRender = new ArrayList<>();

        @Override
        public int nextOutputIndex(MediaCodec.BufferInfo info, int timeoutUs) {
            long[] next = ready.poll();
            if (next == null) {
                return MediaCodec.INFO_TRY_AGAIN_LATER;
            }
            info.set(0, 0, next[1], 0);
            return (int) next[0];
        }

        @Override
        public void releaseOutputBuffer(int index, boolean render) {
            if (!render) {
                releasedWithoutRender.add(index);
            }
        }

        @Override
        public void onDequeued(long presentationTimeUs, long dequeueNs) {
        }
    }

    // Run the pacer the way the renderer does, once per decoded frame at the display rate,
    // until it has locked onto 1:1 pacing. Returns the next frame's timestamp.
    private static long lockOn(NanoPacer pacer) {
        long ptsUs = 1_000_000;
        for (int i = 0; i < 30; i++) {
            pacer.updatePacingMode(true, FPS, (float) FPS);

            NanoPacer.LatestOutput frame = new NanoPacer.LatestOutput();
            frame.index = 0;
            frame.ptsUs = ptsUs;
            long before = System.nanoTime();
            pacer.waitAndDrainLatest(new MediaCodec.BufferInfo(), frame, new FakeDecoder());

            // Keep frames arriving at the display rate, including any time spent waiting
            long remainingMs = (FRAME_NS - (System.nanoTime() - before)) / 1_000_000L;
            if (remainingMs > 0) {
                SystemClock.sleep(remainingMs);
            }
            ptsUs += FRAME_NS / 1000;
        }
        pacer.updatePacingMode(true, FPS, (float) FPS);
        return ptsUs;
    }

    @Test
    public void doesNothingWhenOff() {
        NanoPacer pacer = new NanoPacer();
        pacer.updatePacingMode(false, FPS, (float) FPS);

        NanoPacer.LatestOutput latest = new NanoPacer.LatestOutput();
        latest.index = 3;
        FakeDecoder decoder = new FakeDecoder();
        decoder.ready.add(new long[]{4, 1000});

        assertEquals(0, pacer.waitAndDrainLatest(new MediaCodec.BufferInfo(), latest, decoder));
        assertEquals(3, latest.index);
        assertTrue(decoder.releasedWithoutRender.isEmpty());
    }

    @Test
    public void swapsInNewerFrameAndDropsTheStaleOne() {
        // With the screen off, Android may wake this test late, and a frame that's already late
        // is shown at once with nothing to swap. So try a few times: each attempt must be
        // consistent, and at least one must catch the pacer waiting.
        boolean swapped = false;
        for (int attempt = 0; attempt < 5 && !swapped; attempt++) {
            NanoPacer pacer = new NanoPacer();
            long ptsUs = lockOn(pacer);

            NanoPacer.LatestOutput latest = new NanoPacer.LatestOutput();
            latest.index = 1;
            latest.ptsUs = ptsUs;

            // A newer frame is already decoded while the pacer waits for frame 1's slot
            FakeDecoder decoder = new FakeDecoder();
            decoder.ready.add(new long[]{2, ptsUs + FRAME_NS / 1000});

            int drained = pacer.waitAndDrainLatest(new MediaCodec.BufferInfo(), latest, decoder);

            if (drained == 1) {
                // Swapped: the newer frame replaces the stale one, which is released unshown
                assertEquals(2, latest.index);
                assertEquals(List.of(1), decoder.releasedWithoutRender);
                swapped = true;
            }
            else {
                // Didn't wait: frame 1 is shown as is, and nothing was dropped or leaked
                assertEquals(0, drained);
                assertEquals(1, latest.index);
                assertTrue(decoder.releasedWithoutRender.isEmpty());
                assertEquals(1, decoder.ready.size());
            }
        }
        assertTrue("The pacer never waited for a frame's slot in 5 attempts", swapped);
    }

    @Test
    public void waitsNoLongerThanAboutTwoFrames() {
        NanoPacer pacer = new NanoPacer();
        lockOn(pacer);

        // A timestamp far from the others, as after a stream hiccup
        NanoPacer.LatestOutput latest = new NanoPacer.LatestOutput();
        latest.index = 1;
        latest.ptsUs = 50_000_000;

        long start = System.nanoTime();
        pacer.waitAndDrainLatest(new MediaCodec.BufferInfo(), latest, new FakeDecoder());
        long waitedNs = System.nanoTime() - start;

        // A 48 s timestamp jump must not make the pacer wait for it. The limit allows for the
        // timer slack Android gives a background process (this test runs with the screen off);
        // a pacer that followed the timestamps would wait 48 seconds.
        assertTrue("Waited " + waitedNs / 1_000_000 + " ms", waitedNs < 500_000_000L);
    }
}
