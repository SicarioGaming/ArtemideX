package com.limelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.HostCursorLedger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks when "Hide PC cursor" sends Vibepollo's toggle, and that a cursor left hidden by a
 * killed app or a dropped connection is put right at the next connection, without a stream.
 */
@RunWith(AndroidJUnit4.class)
public class HostCursorControllerTest {
    private static final int NEVER = PreferenceConfiguration.HIDE_CURSOR_NEVER;
    private static final int TOUCHING = PreferenceConfiguration.HIDE_CURSOR_WHILE_TOUCHING;
    private static final int ALWAYS = PreferenceConfiguration.HIDE_CURSOR_ALWAYS;

    private File dir;
    private HostCursorLedger ledger;
    private FakeToggle toggle;
    private ManualScheduler scheduler;

    // Counts toggles, and notes whether the record said "hidden" at the moment each one went out
    private class FakeToggle implements HostCursorController.Toggle {
        boolean linkUp = true;
        final List<Boolean> recordedAtSend = new ArrayList<>();

        int sends() {
            return recordedAtSend.size();
        }

        @Override
        public boolean isLinkUp() {
            return linkUp;
        }

        @Override
        public void send() {
            assertTrue("toggle sent without a link", linkUp);
            recordedAtSend.add(ledger.read() != null);
        }
    }

    private static class ManualScheduler implements HostCursorController.Scheduler {
        final List<Runnable> pending = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void postDelayed(Runnable task, long delayMs) {
            pending.add(task);
            delays.add(delayMs);
        }

        @Override
        public void cancel(Runnable task) {
            int i;
            while ((i = pending.indexOf(task)) >= 0) {
                pending.remove(i);
                delays.remove(i);
            }
        }

        void runAll() {
            List<Runnable> tasks = new ArrayList<>(pending);
            pending.clear();
            delays.clear();
            for (Runnable task : tasks) {
                task.run();
            }
        }
    }

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "host_cursor_controller_test_" + System.nanoTime());
        ledger = new HostCursorLedger(dir, "pc-a");
        toggle = new FakeToggle();
        scheduler = new ManualScheduler();
    }

    @After
    public void tearDown() {
        deleteAll(dir);
    }

    private static void deleteAll(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteAll(child);
            }
        }
        file.delete();
    }

    private HostCursorController controller(int mode) {
        return new HostCursorController(mode, ledger, toggle, scheduler, 7);
    }

    // What an earlier Starwire process left behind when it was killed with the cursor hidden
    private void leftHiddenEarlier() {
        new HostCursorLedger(dir, "pc-a").markHidden(7, "always");
    }

    @Test
    public void alwaysHidesAtStartAfterWritingTheRecord() {
        HostCursorController c = controller(ALWAYS);
        assertEquals(HostCursorController.RECONCILE_NOTHING, c.onStreamStarted(false));
        assertEquals(1, toggle.sends());
        assertTrue("record written before the toggle", toggle.recordedAtSend.get(0));
        assertTrue(c.isBelievedHidden());
        assertNotNull(ledger.read());
    }

    @Test
    public void neverAndTouchingSendNothingAtStart() {
        assertEquals(HostCursorController.RECONCILE_NOTHING, controller(NEVER).onStreamStarted(false));
        assertEquals(HostCursorController.RECONCILE_NOTHING, controller(TOUCHING).onStreamStarted(false));
        assertEquals(0, toggle.sends());
        assertNull(ledger.read());
    }

    @Test
    public void cleanStopShowsItAndClearsTheRecordAfterTheFlush() {
        HostCursorController c = controller(ALWAYS);
        c.onStreamStarted(false);

        assertTrue("caller should wait before stopping", c.onStopping());
        assertEquals(2, toggle.sends());
        assertFalse(c.isBelievedHidden());
        assertNotNull("kept until the toggle has had time to arrive", ledger.read());

        c.onShowFlushed();
        assertNull(ledger.read());

        // Stopping again does nothing
        assertFalse(c.onStopping());
        assertEquals(2, toggle.sends());
    }

    @Test
    public void stopWithTheCursorShowingNeedsNoFlush() {
        HostCursorController c = controller(NEVER);
        c.onStreamStarted(false);
        assertFalse(c.onStopping());
        assertEquals(0, toggle.sends());
    }

    @Test
    public void lostConnectionKeepsTheRecord() {
        HostCursorController c = controller(ALWAYS);
        c.onStreamStarted(false);

        c.onConnectionLost(-1);
        assertEquals(1, toggle.sends());
        assertFalse(c.isBelievedHidden());
        HostCursorLedger.Debt debt = ledger.read();
        assertNotNull(debt);
        assertEquals("terminated:-1", debt.reason);

        // The stop that follows sends nothing on the dead link
        assertFalse(c.onStopping());
        assertEquals(1, toggle.sends());
        assertNotNull(ledger.read());
    }

    @Test
    public void stopWithoutALinkKeepsTheRecord() {
        HostCursorController c = controller(ALWAYS);
        c.onStreamStarted(false);
        toggle.linkUp = false;

        assertFalse(c.onStopping());
        assertEquals(1, toggle.sends());
        assertEquals("stopped without a link", ledger.read().reason);
    }

    @Test
    public void leftHiddenIsShownForNeverAndTouching() {
        for (int mode : new int[] {NEVER, TOUCHING}) {
            leftHiddenEarlier();
            int before = toggle.sends();
            HostCursorController c = controller(mode);

            assertEquals(HostCursorController.RECONCILE_SHOWN, c.onStreamStarted(false));
            assertEquals(before + 1, toggle.sends());
            assertFalse(c.isBelievedHidden());
            assertTrue(c.reconciledThisStream());
            assertNull(ledger.read());

            // Nothing more to send at the end
            assertFalse(c.onStopping());
            assertEquals(before + 1, toggle.sends());
        }
    }

    @Test
    public void leftHiddenIsKeptForAlwaysWithoutSendingAnything() {
        leftHiddenEarlier();
        HostCursorController c = controller(ALWAYS);

        assertEquals(HostCursorController.RECONCILE_ADOPTED, c.onStreamStarted(true));
        assertEquals(0, toggle.sends());
        assertTrue(c.isBelievedHidden());
        assertFalse(c.reconciledThisStream());
        assertEquals("adopted", ledger.read().reason);

        // And it's shown at the end as usual
        assertTrue(c.onStopping());
        assertEquals(1, toggle.sends());
        c.onShowFlushed();
        assertNull(ledger.read());
    }

    @Test
    public void leftHiddenWithoutALinkStaysRecorded() {
        leftHiddenEarlier();
        toggle.linkUp = false;
        assertEquals(HostCursorController.RECONCILE_FAILED, controller(NEVER).onStreamStarted(false));
        assertEquals(0, toggle.sends());
        assertNotNull(ledger.read());
    }

    @Test
    public void touchHidesAndShowsAgainAfterAPause() {
        HostCursorController c = controller(TOUCHING);
        c.onStreamStarted(false);

        c.onFingerDown();
        assertEquals(1, toggle.sends());
        assertTrue(toggle.recordedAtSend.get(0));
        assertTrue(c.isBelievedHidden());

        c.onFingersUp();
        assertEquals("shown only after the pause", 1, toggle.sends());
        assertEquals(List.of(HostCursorController.SHOW_AFTER_TOUCH_MS), scheduler.delays);

        scheduler.runAll();
        assertEquals(2, toggle.sends());
        assertFalse(c.isBelievedHidden());
        assertNull(ledger.read());
    }

    @Test
    public void quickTapsDontSendTheToggleAgain() {
        HostCursorController c = controller(TOUCHING);
        c.onStreamStarted(false);

        c.onFingerDown();
        c.onFingersUp();
        c.onFingerDown();
        c.onFingersUp();
        c.onFingerDown();
        assertEquals(1, toggle.sends());
        assertTrue(scheduler.pending.isEmpty());

        c.onFingersUp();
        assertEquals(1, scheduler.pending.size());
        scheduler.runAll();
        assertEquals(2, toggle.sends());
    }

    @Test
    public void touchesDoNothingInTheOtherModes() {
        HostCursorController never = controller(NEVER);
        never.onStreamStarted(false);
        never.onFingerDown();
        never.onFingersUp();

        HostCursorController always = controller(ALWAYS);
        always.onStreamStarted(false);
        always.onFingerDown();
        always.onFingersUp();

        assertEquals(1, toggle.sends());
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test
    public void stopDuringThePauseShowsItAtOnce() {
        HostCursorController c = controller(TOUCHING);
        c.onStreamStarted(false);
        c.onFingerDown();
        c.onFingersUp();

        assertTrue(c.onStopping());
        assertTrue("the delayed show is cancelled", scheduler.pending.isEmpty());
        assertEquals(2, toggle.sends());

        // Touches after the stop are ignored
        c.onFingerDown();
        assertEquals(2, toggle.sends());
    }

    @Test
    public void killedWithAFingerDownIsPutRightNextTime() {
        HostCursorController first = controller(TOUCHING);
        first.onStreamStarted(false);
        first.onFingerDown();
        // The app is killed here: no stop, no callbacks

        HostCursorController next = new HostCursorController(NEVER,
                new HostCursorLedger(dir, "pc-a"), toggle, scheduler, 7);
        assertEquals(HostCursorController.RECONCILE_SHOWN, next.onStreamStarted(false));
        assertEquals(2, toggle.sends());
        assertNull(ledger.read());
    }

    @Test
    public void killedAfterTheFingerLiftedLeavesNothingToFix() {
        HostCursorController first = controller(TOUCHING);
        first.onStreamStarted(false);
        first.onFingerDown();
        first.onFingersUp();
        scheduler.runAll();

        assertEquals(HostCursorController.RECONCILE_NOTHING, controller(NEVER).onStreamStarted(false));
        assertEquals(2, toggle.sends());
    }

    @Test
    public void fixSendsOnceAndChangesNothingElse() {
        HostCursorController c = controller(ALWAYS);
        c.onStreamStarted(false);

        assertTrue(c.onManualFix());
        assertEquals(2, toggle.sends());
        assertTrue(c.isBelievedHidden());
        assertNotNull(ledger.read());

        HostCursorLedger other = new HostCursorLedger(dir, "pc-b");
        HostCursorController never = new HostCursorController(NEVER, other, toggle, scheduler, 7);
        never.onStreamStarted(false);
        assertTrue(never.onManualFix());
        assertEquals(3, toggle.sends());
        assertFalse(never.isBelievedHidden());
        assertNull(other.read());
    }

    @Test
    public void fixDoesNothingAfterTheStreamEnds() {
        HostCursorController c = controller(NEVER);
        c.onStreamStarted(false);
        c.onStopping();
        assertFalse(c.onManualFix());
        assertEquals(0, toggle.sends());
    }

    @Test
    public void eachPcIsSeparate() {
        leftHiddenEarlier();
        HostCursorController other = new HostCursorController(NEVER,
                new HostCursorLedger(dir, "pc-b"), toggle, scheduler, 7);

        assertEquals(HostCursorController.RECONCILE_NOTHING, other.onStreamStarted(false));
        assertEquals(0, toggle.sends());
        assertNotNull("pc-a still needs its cursor back", ledger.read());
    }

    @Test
    public void noLinkMeansNoToggleAndNoRecord() {
        toggle.linkUp = false;
        HostCursorController c = controller(ALWAYS);
        c.onStreamStarted(false);
        assertEquals(0, toggle.sends());
        assertFalse(c.isBelievedHidden());
        assertNull(ledger.read());
    }

    @Test
    public void nothingHappensIfTheStreamStartsAfterAStop() {
        HostCursorController c = controller(ALWAYS);
        c.onStopping();
        assertEquals(HostCursorController.RECONCILE_NOTHING, c.onStreamStarted(false));
        assertEquals(0, toggle.sends());
        assertNull(ledger.read());
    }

    @Test
    public void toggleKeysArePressedAndReleasedInOrder() {
        List<short[]> sent = new ArrayList<>();
        HostCursorController.pressToggleKeys((keyCode, keyDirection, modifiers) ->
                sent.add(new short[] {keyCode, keyDirection, modifiers}));

        byte ctrl = KeyboardPacket.MODIFIER_CTRL;
        byte alt = KeyboardPacket.MODIFIER_ALT;
        byte shift = KeyboardPacket.MODIFIER_SHIFT;
        byte down = KeyboardPacket.KEY_DOWN;
        byte up = KeyboardPacket.KEY_UP;
        short[][] expected = {
                {0xA2, down, 0},
                {0xA4, down, ctrl},
                {0xA0, down, (short) (ctrl | alt)},
                {0x4E, down, (short) (ctrl | alt | shift)},
                {0x4E, up, (short) (ctrl | alt | shift)},
                {0xA0, up, (short) (ctrl | alt)},
                {0xA4, up, ctrl},
                {0xA2, up, 0},
        };
        assertEquals(expected.length, sent.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals("key " + i, expected[i], sent.get(i));
        }
    }
}
