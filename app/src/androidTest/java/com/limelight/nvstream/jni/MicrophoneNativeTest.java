package com.limelight.nvstream.jni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The microphone encoder ported from logabell's changes: it starts, takes audio, stops and starts
 * again without hanging, refuses formats it can't encode, and stays off without a stream.
 */
@RunWith(AndroidJUnit4.class)
public class MicrophoneNativeTest {
    private static final int RATE = 48000;
    private static final int FRAME = 960;

    @After
    public void tearDown() {
        MoonBridge.stopMicrophoneStreaming();
        MoonBridge.cleanupMicrophoneEncoder();
    }

    @Test
    public void offWithoutAStream() {
        // No host has agreed to take the microphone, so nothing should be sent
        assertFalse(MoonBridge.isMicrophoneStreamActive());
        assertFalse(MoonBridge.isMicrophoneEncryptionEnabled());
    }

    @Test
    public void encoderStartsTakesAudioAndStops() {
        assertEquals(0, MoonBridge.setupMicrophoneEncoder(RATE, 1, 24000));
        MoonBridge.startMicrophoneStreaming();

        // Three 20 ms frames of a quiet tone; the encoder thread sends them (and the send fails
        // quietly, since there's no connection)
        short[] pcm = new short[FRAME * 3];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(i * 2 * Math.PI * 440 / RATE) * 2000);
        }
        assertEquals(pcm.length, MoonBridge.queueMicrophonePcm(pcm, pcm.length));

        MoonBridge.stopMicrophoneStreaming();
        MoonBridge.cleanupMicrophoneEncoder();
    }

    @Test
    public void encoderCanStartAgainAfterCleanup() {
        // A second stream in the same session must get a working encoder, not a stuck thread
        for (int round = 0; round < 3; round++) {
            assertEquals(0, MoonBridge.setupMicrophoneEncoder(RATE, 1, 24000));
            MoonBridge.startMicrophoneStreaming();
            assertEquals(FRAME, MoonBridge.queueMicrophonePcm(new short[FRAME], FRAME));
            MoonBridge.stopMicrophoneStreaming();
            MoonBridge.cleanupMicrophoneEncoder();
        }
    }

    @Test
    public void refusesFormatsItCantEncode() {
        // The encoder is built for 48 kHz mono only
        assertEquals(-1, MoonBridge.setupMicrophoneEncoder(44100, 1, 24000));
        assertEquals(-1, MoonBridge.setupMicrophoneEncoder(RATE, 2, 24000));
    }

    @Test
    public void audioWithoutAnEncoderIsRefused() {
        MoonBridge.cleanupMicrophoneEncoder();
        assertTrue(MoonBridge.queueMicrophonePcm(new short[FRAME], FRAME) < 0);
    }

    @Test
    public void emptyAudioIsIgnored() {
        assertEquals(0, MoonBridge.queueMicrophonePcm(null, FRAME));
        assertEquals(0, MoonBridge.queueMicrophonePcm(new short[FRAME], 0));
    }
}
