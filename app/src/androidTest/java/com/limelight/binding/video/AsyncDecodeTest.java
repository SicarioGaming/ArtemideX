package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Async decode (from Artemide) against this device's real H.264 decoder: the adapter delivers
 * every frame, Async LFR keeps only the newest, and the codec recovery sequences Starwire uses
 * (flush then start, detach/stop/attach/configure) resume decoding.
 */
@RunWith(AndroidJUnit4.class)
public class AsyncDecodeTest {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FRAME_COUNT = 60;
    private static final long TIMEOUT_MS = 5000;
    // How long LFR's test waits for the decoder to finish: a limit, not a pause
    private static final long DECODE_WAIT_MS = 30_000;

    private static class EncodedFrame {
        final byte[] data;
        final long ptsUs;
        final int flags;

        EncodedFrame(byte[] data, long ptsUs, int flags) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    // Codec config (SPS/PPS) first, then FRAME_COUNT key frames
    private static List<EncodedFrame> stream;

    // Counts frames the adapter drops itself (its output queue holds at most 8 waiting frames)
    private final java.util.concurrent.atomic.AtomicInteger releasedByAdapter = new java.util.concurrent.atomic.AtomicInteger();
    private final AsyncCodecAdapter.OutputRelease release = (codec, index) -> {
        releasedByAdapter.incrementAndGet();
        codec.releaseOutputBuffer(index, false);
    };

    @BeforeClass
    public static void encodeTestStream() throws Exception {
        stream = new ArrayList<>();

        MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0); // every frame a key frame

        MediaCodec encoder = MediaCodec.createEncoderByType(MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int submitted = 0;
        boolean inputDone = false;
        long deadline = System.currentTimeMillis() + 10_000;

        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                int in = encoder.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    if (submitted == FRAME_COUNT) {
                        encoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        Image image = encoder.getInputImage(in);
                        fillFrame(image, submitted);
                        encoder.queueInputBuffer(in, 0, WIDTH * HEIGHT * 3 / 2, submitted * 33_333L, 0);
                        submitted++;
                    }
                }
            }

            int out = encoder.dequeueOutputBuffer(info, 10_000);
            if (out >= 0) {
                if (info.size > 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(out);
                    byte[] data = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(data);
                    stream.add(new EncodedFrame(data, info.presentationTimeUs, info.flags));
                }
                encoder.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }

        encoder.stop();
        encoder.release();

        assertTrue("Encoder produced no codec config", (stream.get(0).flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0);
    }

    // A moving gradient so each frame differs
    private static void fillFrame(Image image, int frame) {
        Image.Plane[] planes = image.getPlanes();
        for (int p = 0; p < planes.length; p++) {
            int w = p == 0 ? WIDTH : WIDTH / 2;
            int h = p == 0 ? HEIGHT : HEIGHT / 2;
            ByteBuffer buf = planes[p].getBuffer();
            int rowStride = planes[p].getRowStride();
            int pixelStride = planes[p].getPixelStride();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buf.put(y * rowStride + x * pixelStride, (byte) (p == 0 ? (x + y + frame * 4) : 128));
                }
            }
        }
    }

    private MediaCodec createDecoder(AsyncCodecAdapter adapter) throws Exception {
        MediaCodec decoder = MediaCodec.createDecoderByType(MIME);
        attachAndConfigure(decoder, adapter);
        return decoder;
    }

    // The order MediaCodecDecoderRenderer.configureAndStartDecoder() uses
    private void attachAndConfigure(MediaCodec decoder, AsyncCodecAdapter adapter) {
        adapter.attachIfNeeded(decoder, Process.THREAD_PRIORITY_DISPLAY, null, release);
        decoder.configure(MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT), null, null, 0);
        decoder.start();
    }

    /**
     * Feeds the codec config and then frames [from, to) through the adapter, releasing outputs as
     * they come. Returns how many decoded frames the adapter handed out.
     */
    private int decode(MediaCodec decoder, AsyncCodecAdapter adapter, int from, int to) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        List<EncodedFrame> toSend = new ArrayList<>();
        toSend.add(stream.get(0)); // SPS/PPS, needed after each flush or restart
        for (int i = from; i < to; i++) {
            toSend.add(stream.get(1 + i));
        }

        int sent = 0;
        int outputs = 0;
        long lastProgress = System.currentTimeMillis();

        while (System.currentTimeMillis() - lastProgress < 1500) {
            adapter.drainNoRenderReleaseQueue(decoder, release);

            if (sent < toSend.size()) {
                int in = adapter.dequeueInputIndex(2000);
                if (in >= 0) {
                    EncodedFrame f = toSend.get(sent++);
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    buf.clear();
                    buf.put(f.data);
                    decoder.queueInputBuffer(in, 0, f.data.length, f.ptsUs, f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    lastProgress = System.currentTimeMillis();
                }
            }

            int out = adapter.dequeueOutputIndex(decoder, info, 2000, release);
            if (out >= 0) {
                decoder.releaseOutputBuffer(out, false);
                outputs++;
                lastProgress = System.currentTimeMillis();
            }

            if (sent == toSend.size() && outputs + releasedByAdapter.get() >= to - from) {
                break;
            }
        }
        adapter.drainNoRenderReleaseQueue(decoder, release);
        return outputs;
    }

    // Decodes frames [from, to) and checks every one came out of the decoder (handed out or
    // dropped by the adapter's bounded queue), with most handed out
    private void assertDecodes(MediaCodec decoder, AsyncCodecAdapter adapter, int from, int to) {
        releasedByAdapter.set(0);
        int outputs = decode(decoder, adapter, from, to);
        assertEquals(to - from, outputs + releasedByAdapter.get());
        assertTrue("Only " + outputs + " of " + (to - from) + " handed out", outputs >= (to - from) * 8 / 10);
    }

    @Test
    public void deliversEveryFrame() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertTrue(adapter.isActive());
            releasedByAdapter.set(0);
            int outputs = decode(decoder, adapter, 0, FRAME_COUNT);

            // Every decoded frame was either handed out or released, none leaked; and when the
            // reader keeps up, nearly all are handed out
            assertEquals(FRAME_COUNT, outputs + releasedByAdapter.get());
            assertTrue("Only " + outputs + " of " + FRAME_COUNT + " handed out", outputs >= FRAME_COUNT * 9 / 10);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
        assertTrue("Callback thread still running after detach", !adapter.isActive());
    }

    @Test
    public void asyncLfrKeepsOnlyTheNewestWaitingFrame() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        adapter.setPreferLowerDelays(true);
        MediaCodec decoder = createDecoder(adapter);
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Feed everything without taking outputs, so decoded frames pile up
            int sent = 0;
            int frames = 0;
            List<EncodedFrame> all = stream;
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (sent < all.size() && System.currentTimeMillis() < deadline) {
                adapter.drainNoRenderReleaseQueue(decoder, release);
                int in = adapter.dequeueInputIndex(5000);
                if (in >= 0) {
                    EncodedFrame f = all.get(sent++);
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    buf.clear();
                    buf.put(f.data);
                    decoder.queueInputBuffer(in, 0, f.data.length, f.ptsUs, f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    if ((f.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        frames++;
                    }
                }
            }
            assertEquals("frames fed to the decoder", all.size(), sent);
            // Let decoding finish: every frame comes out, and all but the waiting one are dropped
            // by LFR. Release the dropped ones as they come, so the decoder never runs out of
            // output buffers (the renderer thread does this each loop). An emulator's software
            // decoder took longer than half a second for 60 frames, so wait for the frames
            // themselves, as long as it takes up to a generous limit.
            long decodeDeadline = android.os.SystemClock.uptimeMillis() + DECODE_WAIT_MS;
            while (releasedByAdapter.get() + adapter.getOutputQueueSize() < frames
                    && android.os.SystemClock.uptimeMillis() < decodeDeadline) {
                adapter.drainNoRenderReleaseQueue(decoder, release);
                Thread.sleep(20);
            }
            adapter.drainNoRenderReleaseQueue(decoder, release);
            assertEquals("frames out of the decoder (dropped plus waiting) after " + DECODE_WAIT_MS + " ms",
                    frames, releasedByAdapter.get() + adapter.getOutputQueueSize());

            // With LFR, at most one frame is waiting, and it's a late one
            assertTrue("Output queue held " + adapter.getOutputQueueSize(), adapter.getOutputQueueSize() <= 1);
            int out = adapter.dequeueOutputIndex(decoder, info, 100_000, release);
            assertTrue("No frame waiting", out >= 0);
            assertTrue("Waiting frame is not one of the newest: pts " + info.presentationTimeUs,
                    info.presentationTimeUs >= (FRAME_COUNT - 10) * 33_333L);
            decoder.releaseOutputBuffer(out, false);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }

    @Test
    public void resumesAfterFlushRecovery() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertDecodes(decoder, adapter, 0, 20);

            // MediaCodecDecoderRenderer's flush recovery in async mode
            adapter.quiesceAndRelease(decoder, release);
            decoder.flush();
            decoder.start();

            assertDecodes(decoder, adapter, 20, 40);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }

    @Test
    public void resumesAfterRestartRecovery() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertDecodes(decoder, adapter, 0, 20);

            // MediaCodecDecoderRenderer's restart recovery in async mode
            adapter.quiesceAndRelease(decoder, release);
            adapter.detach(decoder, release);
            decoder.stop();
            attachAndConfigure(decoder, adapter);

            assertDecodes(decoder, adapter, 20, 40);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }

    @Test
    public void resumesAfterResetRecovery() throws Exception {
        AsyncCodecAdapter adapter = new AsyncCodecAdapter();
        MediaCodec decoder = createDecoder(adapter);
        try {
            assertDecodes(decoder, adapter, 0, 20);

            // MediaCodecDecoderRenderer's reset recovery in async mode
            adapter.quiesceAndRelease(decoder, release);
            adapter.detach(decoder, release);
            decoder.reset();
            attachAndConfigure(decoder, adapter);

            assertDecodes(decoder, adapter, 20, 40);
        } finally {
            adapter.detach(decoder, release);
            decoder.release();
        }
    }
}
