package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioRecord;

import org.junit.Test;

/**
 * What the microphone capture loop does when the recorder fails (another app took the mic, or
 * the device went away). Before a4580fa6 it retried straight away, forever.
 */
public class MicrophoneReadErrorsTest {
    // The loop's handling of these read results in turn: how many reads it made before it gave
    // up (or -1 if it carried on through all of them), and how long it waited in between
    private static final class Run {
        int readsBeforeStopping = -1;
        long waitedMs;
    }

    private static Run run(int... reads) {
        Run run = new Run();
        int errors = 0;
        for (int i = 0; i < reads.length; i++) {
            errors = MicrophoneCaptureManager.readErrorsAfter(reads[i], errors);
            if (errors < 0) {
                run.readsBeforeStopping = i + 1;
                return run;
            }
            if (reads[i] < 0) {
                run.waitedMs += MicrophoneCaptureManager.READ_ERROR_WAIT_MS;
            }
        }
        return run;
    }

    private static int[] repeat(int value, int count) {
        int[] values = new int[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    @Test
    public void aRecorderThatKeepsFailingIsGivenUpAfterAboutASecond() {
        for (int error : new int[] {AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE,
                AudioRecord.ERROR_INVALID_OPERATION, AudioRecord.ERROR_DEAD_OBJECT}) {
            Run run = run(repeat(error, 1000));
            assertEquals("error " + error, MicrophoneCaptureManager.READ_ERROR_LIMIT, run.readsBeforeStopping);
            assertTrue("waited " + run.waitedMs + " ms", run.waitedMs >= 500 && run.waitedMs <= 2000);
        }
    }

    @Test
    public void itWaitsBetweenTries() {
        assertTrue(MicrophoneCaptureManager.READ_ERROR_WAIT_MS >= 10);
        assertEquals(MicrophoneCaptureManager.READ_ERROR_WAIT_MS, run(AudioRecord.ERROR_DEAD_OBJECT, 960).waitedMs);
    }

    @Test
    public void aGoodReadStartsTheCountAgain() {
        int limit = MicrophoneCaptureManager.READ_ERROR_LIMIT;
        // One short of the limit, a good read, then one short again: a glitch now and then never
        // adds up to stopping
        int[] reads = new int[3 * limit];
        java.util.Arrays.fill(reads, AudioRecord.ERROR_INVALID_OPERATION);
        reads[limit - 1] = 960;
        reads[2 * limit - 1] = 960;
        reads[3 * limit - 1] = 960;
        assertEquals(-1, run(reads).readsBeforeStopping);

        assertEquals(0, MicrophoneCaptureManager.readErrorsAfter(960, limit - 1));
    }

    @Test
    public void readingNothingIsNotAnError() {
        // READ_BLOCKING can return 0 samples; the loop just reads again
        assertEquals(0, MicrophoneCaptureManager.readErrorsAfter(0, 0));
        assertEquals(0, MicrophoneCaptureManager.readErrorsAfter(0, 12));
        assertEquals(-1, run(repeat(0, 1000)).readsBeforeStopping);
    }

    @Test
    public void theFirstErrorCountsAsOne() {
        // The loop logs the failure once, on the first error in a row
        assertEquals(1, MicrophoneCaptureManager.readErrorsAfter(AudioRecord.ERROR, 0));
        assertEquals(2, MicrophoneCaptureManager.readErrorsAfter(AudioRecord.ERROR, 1));
    }
}
