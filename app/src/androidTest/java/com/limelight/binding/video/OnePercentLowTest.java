package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The 1% low FPS shown in the performance overlay.
 */
@RunWith(AndroidJUnit4.class)
public class OnePercentLowTest {
    @Test
    public void steadyStreamHasLowEqualToItsRate() {
        long[] intervals = new long[200];
        java.util.Arrays.fill(intervals, 1_000_000_000L / 120);
        assertEquals(120f, MediaCodecDecoderRenderer.onePercentLowFps(intervals, intervals.length), 0.5f);
    }

    @Test
    public void slowFramesPullTheLowDown() {
        // 198 frames at 120 FPS and 2 that took 50 ms: the slowest 1% are those two
        long[] intervals = new long[200];
        java.util.Arrays.fill(intervals, 1_000_000_000L / 120);
        intervals[17] = 50_000_000L;
        intervals[150] = 50_000_000L;
        assertEquals(20f, MediaCodecDecoderRenderer.onePercentLowFps(intervals, intervals.length), 0.5f);
    }

    @Test
    public void tooFewFramesShowsNothing() {
        assertEquals(0f, MediaCodecDecoderRenderer.onePercentLowFps(new long[10], 10), 0f);
    }
}
