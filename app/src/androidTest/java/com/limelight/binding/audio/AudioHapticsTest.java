package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Audio haptics: bass in the audio becomes vibration, other sounds don't.
 */
@RunWith(AndroidJUnit4.class)
public class AudioHapticsTest {
    private static final int SAMPLE_RATE = 48000;

    private static short[] stereoTone(double hz, double amplitude, int frames) {
        short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            short s = (short) Math.round(Math.sin(2 * Math.PI * hz * i / SAMPLE_RATE) * amplitude * 32767);
            pcm[2 * i] = s;
            pcm[2 * i + 1] = s;
        }
        return pcm;
    }

    private static AudioHaptics haptics(int outputs, AudioHaptics.RumbleSink sink) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AudioHaptics haptics = new AudioHaptics(context, outputs, 100, sink);
        haptics.setup(SAMPLE_RATE, 2);
        return haptics;
    }

    @Test
    public void levelIsZeroBelowTheNoiseGateAndCappedAtOne() {
        assertEquals(0f, AudioHaptics.levelFor(0f, 1f), 0f);
        assertEquals(0f, AudioHaptics.levelFor(0.004f, 1f), 0f);
        assertEquals(1f, AudioHaptics.levelFor(0.9f, 1f), 0f);
        assertTrue(AudioHaptics.levelFor(0.1f, 2f) > AudioHaptics.levelFor(0.1f, 1f));
    }

    @Test
    public void loudBassVibratesStrongly() {
        AudioHaptics haptics = haptics(AudioHaptics.OUTPUT_CONTROLLER, null);
        haptics.process(stereoTone(50, 0.3, SAMPLE_RATE / 10));
        assertTrue("level " + haptics.level(), haptics.level() > 0.5f);
    }

    @Test
    public void trebleAndSilenceDontVibrate() {
        AudioHaptics haptics = haptics(AudioHaptics.OUTPUT_CONTROLLER, null);
        haptics.process(stereoTone(5000, 0.2, SAMPLE_RATE / 10));
        assertEquals(0f, haptics.level(), 0f);
        haptics.process(new short[SAMPLE_RATE / 5]);
        assertEquals(0f, haptics.level(), 0f);
    }

    @Test
    public void bassFadesAfterItStops() {
        AudioHaptics haptics = haptics(AudioHaptics.OUTPUT_CONTROLLER, null);
        haptics.process(stereoTone(50, 0.3, SAMPLE_RATE / 10));
        // Half a second of silence is well past the 80 ms release
        haptics.process(new short[SAMPLE_RATE]);
        assertEquals(0f, haptics.level(), 0f);
    }

    @Test
    public void controllerRumblesWithBassAndStopsWithTheStream() throws InterruptedException {
        List<Integer> lows = Collections.synchronizedList(new ArrayList<>());
        AudioHaptics haptics = haptics(AudioHaptics.OUTPUT_CONTROLLER, (low, high) -> lows.add(low & 0xFFFF));

        haptics.start();
        haptics.process(stereoTone(50, 0.3, SAMPLE_RATE / 10));
        Thread.sleep(200);
        haptics.stop();
        Thread.sleep(100);

        assertTrue("no rumble sent", !lows.isEmpty());
        int strongest = Collections.max(lows);
        assertTrue("strongest rumble " + strongest, strongest > 0x8000);
        assertEquals("rumble left on after stop", 0, (int) lows.get(lows.size() - 1));
    }
}
