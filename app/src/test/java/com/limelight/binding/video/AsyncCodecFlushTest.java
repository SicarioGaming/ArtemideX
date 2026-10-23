package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaCodec;
import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Codec recovery in async decode never hands out a buffer from before the flush. The emulator
 * matrix once got "index 0 is not owned by client" from getInputBuffer() right after a flush:
 * a callback that came between emptying the queue and flush() left index 0 in it.
 *
 * The callbacks are played by hand in the order that went wrong, on the main looper standing in
 * for the callback thread (Robolectric runs what's posted to it only on idle()), so each order
 * happens every time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class AsyncCodecFlushTest {
    private static final int NONE = -1;

    private final Handler callbackThread = new Handler(Looper.getMainLooper());
    private final AsyncCodecAdapter adapter = new AsyncCodecAdapter();

    private static void runCallbackThread() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static MediaCodec.BufferInfo frame(long ptsUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, 100, ptsUs, 0);
        return info;
    }

    @Test
    public void noInputBufferFromBeforeTheFlushIsHandedOutAfterIt() {
        MediaCodec.Callback codec = adapter.newCallback(callbackThread, null, null);

        // Decoding: the codec offers buffers 0 and 1, and the decoder takes 0 and queues it
        codec.onInputBufferAvailable(null, 0);
        codec.onInputBufferAvailable(null, 1);
        assertEquals(0, adapter.dequeueInputIndex(0));

        // Recovery, with every decoder thread waiting. The codec frees buffer 0 before flush()
        // takes it back, and sends one more that is still waiting on the callback thread when
        // flush() returns.
        adapter.quiesceAndRelease(null, null);
        codec.onInputBufferAvailable(null, 0);
        callbackThread.post(() -> codec.onInputBufferAvailable(null, 1));
        adapter.resumeAfterFlush();

        // start(): the codec owns every buffer again and offers them anew, after all of that
        callbackThread.post(() -> codec.onInputBufferAvailable(null, 2));
        callbackThread.post(() -> codec.onInputBufferAvailable(null, 0));
        runCallbackThread();

        assertEquals(2, adapter.dequeueInputIndex(0));
        assertEquals(0, adapter.dequeueInputIndex(0));
        assertEquals("a buffer from before the flush was left", NONE, adapter.dequeueInputIndex(0));
    }

    @Test
    public void noOutputBufferFromBeforeTheFlushIsHandedOutAfterIt() {
        MediaCodec.Callback codec = adapter.newCallback(callbackThread, null, null);

        adapter.quiesceAndRelease(null, null);
        // A decoded frame comes out between emptying the queues and flush()...
        codec.onOutputBufferAvailable(null, 3, frame(1000));
        // ...and one more is still waiting on the callback thread when flush() returns
        callbackThread.post(() -> codec.onOutputBufferAvailable(null, 4, frame(2000)));
        adapter.resumeAfterFlush();
        runCallbackThread();
        assertEquals("a frame from before the flush is waiting", 0, adapter.getOutputQueueSize());

        // After start(), new frames come through
        codec.onOutputBufferAvailable(null, 3, frame(3000));
        assertEquals(1, adapter.getOutputQueueSize());
    }

    @Test
    public void aRestartTakesOnlyTheNewCallbacksBuffers() {
        MediaCodec.Callback before = adapter.newCallback(callbackThread, null, null);

        // Restart recovery: quiesce, detach, stop, then a new callback for configure and start
        adapter.quiesceAndRelease(null, null);
        adapter.detach(null, null);
        MediaCodec.Callback after = adapter.newCallback(callbackThread, null, null);

        // The old callback still delivering something counts for nothing
        before.onInputBufferAvailable(null, 5);
        after.onInputBufferAvailable(null, 6);
        assertEquals(6, adapter.dequeueInputIndex(0));
        assertEquals(NONE, adapter.dequeueInputIndex(0));
    }

    @Test
    public void aFlushThatEndsInARestartDoesNotLetTheOldBuffersIn() {
        MediaCodec.Callback before = adapter.newCallback(callbackThread, null, null);

        // flush() worked but start() failed, so recovery moves on to a restart before the
        // callback thread got to letting buffers in again
        adapter.quiesceAndRelease(null, null);
        adapter.resumeAfterFlush();
        adapter.detach(null, null);
        runCallbackThread();

        before.onInputBufferAvailable(null, 1);
        assertEquals(NONE, adapter.dequeueInputIndex(0));
    }
}
