package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How strongly audio haptics vibrate for a given amount of bass. Turning audio into vibration
 * needs the device and is checked there (AudioHapticsTest).
 */
public class AudioHapticsLevelTest {
    @Test
    public void levelIsZeroBelowTheNoiseGateAndCappedAtOne() {
        assertEquals(0f, AudioHaptics.levelFor(0f, 1f), 0f);
        assertEquals(0f, AudioHaptics.levelFor(0.004f, 1f), 0f);
        assertEquals(1f, AudioHaptics.levelFor(0.9f, 1f), 0f);
        assertEquals(1f, AudioHaptics.levelFor(1f, 1f), 0f);
        assertTrue(AudioHaptics.levelFor(0.1f, 2f) > AudioHaptics.levelFor(0.1f, 1f));
    }

    @Test
    public void levelRisesWithTheBassAndTheIntensity() {
        float previous = 0;
        for (int i = 1; i <= 100; i++) {
            float level = AudioHaptics.levelFor(i / 100f, 1f);
            assertTrue("at " + i, level >= previous);
            previous = level;
        }
        assertEquals(0f, AudioHaptics.levelFor(0.1f, 0f), 0f);
    }
}
