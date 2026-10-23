package com.limelight.binding.audio;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Sends the microphone over the stream itself, Opus-encoded in native code. Only hosts that
 * negotiate it (logabell's Apollo build) take it.
 */
public final class HostMicrophoneSink implements MicrophoneSink {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 1;
    private static final int BITRATE = 24000;

    private boolean open;

    @Override
    public int open() {
        if (!MoonBridge.isMicrophoneStreamActive()) {
            return R.string.microphone_host_not_negotiated;
        }
        if (MoonBridge.setupMicrophoneEncoder(SAMPLE_RATE, CHANNEL_COUNT, BITRATE) != 0) {
            return R.string.microphone_encoder_setup_failed;
        }
        MoonBridge.startMicrophoneStreaming();
        open = true;
        return 0;
    }

    @Override
    public void write(short[] pcm, int samples) {
        if (MoonBridge.queueMicrophonePcm(pcm, samples) < 0) {
            LimeLog.warning("Failed to queue microphone PCM data for native encoding");
        }
    }

    @Override
    public void close() {
        if (open) {
            open = false;
            MoonBridge.stopMicrophoneStreaming();
            MoonBridge.cleanupMicrophoneEncoder();
        }
    }
}
