package com.limelight.binding.video;

import static org.junit.Assert.assertNotNull;

import android.media.MediaCodec;
import android.media.MediaFormat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Turning on low-latency decoding after the decoder starts (ported from Artemide) works on this
 * device's real H.264 decoder and leaves it usable.
 */
@RunWith(AndroidJUnit4.class)
public class LowLatencyAfterStartTest {
    @Test
    public void aStartedDecoderAcceptsLowLatencyAndKeepsWorking() throws Exception {
        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
            // No surface: decoded frames would go to buffers, which is enough here
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodecHelper.applyLowLatencyAfterStart(codec);

            // Still running: it hands out an input buffer
            int index = codec.dequeueInputBuffer(500_000);
            assertNotNull("decoder stopped giving input buffers", index >= 0 ? codec.getInputBuffer(index) : null);
        }
        finally {
            codec.release();
        }
    }

    @Test
    public void noDecoderIsFine() {
        MediaCodecHelper.applyLowLatencyAfterStart(null);
    }
}
