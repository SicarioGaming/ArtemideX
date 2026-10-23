package com.limelight;

import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.HostCursorLedger;

import java.util.logging.Logger;

/**
 * "Hide PC cursor": hides and shows the PC's cursor with Vibepollo's Ctrl+Alt+Shift+N toggle.
 * The toggle is one switch for the whole host and Vibepollo can't say which way it's set, so
 * Starwire keeps track itself. What it hid is written down per PC (HostCursorLedger) before
 * the toggle goes out, so a cursor left hidden by a crash, a force-stop or a dropped
 * connection is put right the next time Starwire connects to that PC.
 */
public class HostCursorController {
    /** Sends the toggle to the PC. */
    public interface Toggle {
        // False when there's no stream to send it on, or the host has no such toggle
        boolean isLinkUp();
        void send();
    }

    /** Runs the delayed show after the last finger lifts (a Handler in the app). */
    public interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void cancel(Runnable task);
    }

    /** Where the toggle's key presses go: the stream's keyboard input. */
    public interface KeySink {
        void sendKey(short keyCode, byte keyDirection, byte modifiers);
    }

    // Vibepollo (like Sunshine) toggles drawing the cursor into the video on Ctrl+Alt+Shift+N
    static final short[] TOGGLE_KEYS = {
            0xA2 /* left Ctrl */, 0xA4 /* left Alt */, 0xA0 /* left Shift */, 0x4E /* N */
    };
    private static final byte[] TOGGLE_KEY_MODIFIERS = {
            KeyboardPacket.MODIFIER_CTRL, KeyboardPacket.MODIFIER_ALT, KeyboardPacket.MODIFIER_SHIFT, 0
    };

    // What onStreamStarted() did about a cursor an earlier stream left hidden
    public static final int RECONCILE_NOTHING = 0;
    public static final int RECONCILE_SHOWN = 1;
    // This stream hides it anyway, so it's kept hidden without sending anything
    public static final int RECONCILE_ADOPTED = 2;
    public static final int RECONCILE_FAILED = 3;

    // Showing it again waits a moment after the last finger lifts, so quick taps don't make it
    // flicker or send the toggle twice in a row
    static final long SHOW_AFTER_TOUCH_MS = 300;

    private static final Logger LOG = Logger.getLogger("HostCursor");

    private final int mode;
    private final HostCursorLedger ledger;
    private final Toggle toggle;
    private final Scheduler scheduler;
    private final int appId;

    // Whether Starwire has toggled the cursor off and not back on
    private boolean believedHidden;
    // Whether this stream sent the toggle to show a cursor left hidden earlier
    private boolean reconciled;
    private boolean stopped;

    private final Runnable showAfterTouch = this::showAfterTouch;

    public HostCursorController(int mode, HostCursorLedger ledger, Toggle toggle, Scheduler scheduler, int appId) {
        this.mode = mode;
        this.ledger = ledger;
        this.toggle = toggle;
        this.scheduler = scheduler;
        this.appId = appId;
    }

    /**
     * Presses Ctrl+Alt+Shift+N and releases it straight away in reverse order, each key with
     * the modifiers held before it. All at once, so the modifiers are up again before any touch
     * or click that follows reaches the PC.
     */
    public static void pressToggleKeys(KeySink sink) {
        byte modifiers = 0;
        for (int i = 0; i < TOGGLE_KEYS.length; i++) {
            sink.sendKey(TOGGLE_KEYS[i], KeyboardPacket.KEY_DOWN, modifiers);
            modifiers |= TOGGLE_KEY_MODIFIERS[i];
        }
        for (int i = TOGGLE_KEYS.length - 1; i >= 0; i--) {
            modifiers &= (byte) ~TOGGLE_KEY_MODIFIERS[i];
            sink.sendKey(TOGGLE_KEYS[i], KeyboardPacket.KEY_UP, modifiers);
        }
    }

    public boolean isBelievedHidden() {
        return believedHidden;
    }

    /** Whether this stream turned the cursor back on after an earlier one left it hidden. */
    public boolean reconciledThisStream() {
        return reconciled;
    }

    /**
     * The stream is up. If an earlier stream to this PC left the cursor hidden, put that right
     * for this stream's setting; otherwise hide it now for "Always".
     */
    public int onStreamStarted(boolean resumedSession) {
        if (stopped) {
            return RECONCILE_NOTHING;
        }

        HostCursorLedger.Debt debt = ledger.read();
        if (debt == null) {
            if (mode == PreferenceConfiguration.HIDE_CURSOR_ALWAYS) {
                hide("always");
            }
            return RECONCILE_NOTHING;
        }

        // Nothing on the PC says which way the cursor is now. It would be showing again if
        // Vibepollo restarted or another client pressed the toggle, but usually neither has
        // happened, so take it as still hidden.
        LOG.info("Cursor left hidden " + debt + "; this stream " +
                (resumedSession ? "resumed the session" : "launched the app"));
        if (mode == PreferenceConfiguration.HIDE_CURSOR_ALWAYS) {
            believedHidden = true;
            ledger.markHidden(appId, "adopted");
            LOG.info("Reconcile action=adopt");
            return RECONCILE_ADOPTED;
        }
        if (!toggle.isLinkUp()) {
            LOG.warning("Reconcile action=show failed: no link");
            return RECONCILE_FAILED;
        }
        toggle.send();
        reconciled = true;
        ledger.markShown();
        LOG.info("Reconcile action=show");
        return RECONCILE_SHOWN;
    }

    // First finger down, for "While touching"
    public void onFingerDown() {
        if (mode != PreferenceConfiguration.HIDE_CURSOR_WHILE_TOUCHING || stopped) {
            return;
        }
        scheduler.cancel(showAfterTouch);
        hide("touch");
    }

    // Last finger up, or the touch was cancelled
    public void onFingersUp() {
        if (mode != PreferenceConfiguration.HIDE_CURSOR_WHILE_TOUCHING || stopped) {
            return;
        }
        scheduler.cancel(showAfterTouch);
        scheduler.postDelayed(showAfterTouch, SHOW_AFTER_TOUCH_MS);
    }

    /**
     * The stream is ending while the link still works: show the cursor if Starwire hid it.
     * Returns true when the toggle was just sent. Vibepollo throws away input it hasn't handled
     * yet when the session stops, so the caller should wait a moment before stopping the
     * connection, then call onShowFlushed().
     */
    public boolean onStopping() {
        if (stopped) {
            return false;
        }
        stopped = true;
        scheduler.cancel(showAfterTouch);

        if (!believedHidden) {
            return false;
        }
        if (!show()) {
            // Keep the record, so the next connection to this PC shows it
            believedHidden = false;
            ledger.setReason("stopped without a link");
            return false;
        }
        return true;
    }

    public void onShowFlushed() {
        ledger.markShown();
    }

    /**
     * The connection dropped or the PC ended it. The toggle can't get through any more, so
     * the record stays and the next connection to this PC shows the cursor.
     */
    public void onConnectionLost(int errorCode) {
        if (stopped) {
            return;
        }
        stopped = true;
        scheduler.cancel(showAfterTouch);

        if (believedHidden) {
            believedHidden = false;
            ledger.setReason("terminated:" + errorCode);
            LOG.info("Connection lost (" + errorCode + ") with the cursor hidden; it's shown at the next connection");
        }
    }

    /**
     * "Fix PC cursor": the PC's cursor is the wrong way round, so send the toggle once. What
     * Starwire believes stays the same, since the toggle brings the PC back in line with it.
     * Returns false when there was no link to send it on.
     */
    public boolean onManualFix() {
        if (stopped || !toggle.isLinkUp()) {
            return false;
        }
        toggle.send();
        LOG.info("Fix PC cursor sent; Starwire has it " + (believedHidden ? "hidden" : "shown"));
        return true;
    }

    private void hide(String reason) {
        if (believedHidden || !toggle.isLinkUp()) {
            return;
        }
        // Written first, so it's there even if the app dies right after the toggle goes out
        ledger.markHidden(appId, reason);
        toggle.send();
        believedHidden = true;
    }

    private void showAfterTouch() {
        if (show()) {
            ledger.markShown();
        }
    }

    // Sends the show toggle; the caller removes the record once it's sure it got there
    private boolean show() {
        if (!believedHidden || !toggle.isLinkUp()) {
            return false;
        }
        toggle.send();
        believedHidden = false;
        return true;
    }
}
