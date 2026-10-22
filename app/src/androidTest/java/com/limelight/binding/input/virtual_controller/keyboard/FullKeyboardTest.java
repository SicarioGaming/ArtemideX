package com.limelight.binding.input.virtual_controller.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen full keyboard (from Artemide): every key reaches the host, taps send down and
 * up, and sticky modifiers lock with a long press.
 */
@RunWith(AndroidJUnit4.class)
public class FullKeyboardTest {
    private Context context;
    private FrameLayout parent;
    private KeyBoardLayoutController keyboard;
    private final List<KeyEvent> sent = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(context);
        prefs.stickyModifierKeys = true;
        prefs.keyboardVibrate = false;
        prefs.keyboardOpacity = 90;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            parent = new FrameLayout(context);
            keyboard = new KeyBoardLayoutController(parent, context, prefs, sent::add);
            keyboard.refreshLayout();
            keyboard.show();
        });
    }

    private List<View> allKeys() {
        List<View> keys = new ArrayList<>();
        ViewGroup keyboardView = (ViewGroup) parent.getChildAt(0);
        for (int r = 0; r < keyboardView.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) keyboardView.getChildAt(r);
            for (int k = 0; k < row.getChildCount(); k++) {
                keys.add(row.getChildAt(k));
            }
        }
        return keys;
    }

    private View keyFor(int keyCode) {
        for (View key : allKeys()) {
            if (String.valueOf(keyCode).equals(key.getTag())) {
                return key;
            }
        }
        throw new AssertionError("No key for keycode " + keyCode);
    }

    private void touch(View key, int action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent e = MotionEvent.obtain(now, now, action, 1, 1, 0);
            key.dispatchTouchEvent(e);
            e.recycle();
        });
    }

    @Test
    public void everyKeyMapsToAHostKey() {
        KeyboardTranslator translator = new KeyboardTranslator();
        int keyCount = 0;
        for (View key : allKeys()) {
            String tag = (String) key.getTag();
            if ("hide".equals(tag)) {
                continue;
            }
            int keyCode = Integer.parseInt(tag);
            assertNotEquals("Key " + KeyEvent.keyCodeToString(keyCode) + " would be dropped",
                    0, translator.translate(keyCode, -1));
            keyCount++;
        }
        assertTrue("Only " + keyCount + " keys", keyCount >= 70);
    }

    @Test
    public void tapSendsDownThenUp() {
        View a = keyFor(KeyEvent.KEYCODE_A);
        touch(a, MotionEvent.ACTION_DOWN);
        touch(a, MotionEvent.ACTION_UP);

        assertEquals(2, sent.size());
        assertEquals(KeyEvent.ACTION_DOWN, sent.get(0).getAction());
        assertEquals(KeyEvent.KEYCODE_A, sent.get(0).getKeyCode());
        assertEquals(KeyEvent.ACTION_UP, sent.get(1).getAction());
        assertEquals(KeyEvent.KEYCODE_A, sent.get(1).getKeyCode());
    }

    @Test
    public void longPressLocksShiftUntilTappedAgain() {
        View shift = keyFor(KeyEvent.KEYCODE_SHIFT_LEFT);
        View a = keyFor(KeyEvent.KEYCODE_A);

        // Long-press Shift: it goes down and stays down after the finger lifts
        touch(shift, MotionEvent.ACTION_DOWN);
        SystemClock.sleep(600);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        touch(shift, MotionEvent.ACTION_UP);
        assertTrue(keyboard.isModifierKeyPressed(KeyEvent.KEYCODE_SHIFT_LEFT));
        assertEquals(1, sent.size());
        assertEquals(KeyEvent.ACTION_DOWN, sent.get(0).getAction());

        // Typing while it's locked
        touch(a, MotionEvent.ACTION_DOWN);
        touch(a, MotionEvent.ACTION_UP);
        assertTrue(keyboard.isModifierKeyPressed(KeyEvent.KEYCODE_SHIFT_LEFT));

        // Tapping Shift again unlocks it, and its key-up then goes out
        touch(shift, MotionEvent.ACTION_DOWN);
        assertFalse(keyboard.isModifierKeyPressed(KeyEvent.KEYCODE_SHIFT_LEFT));
        touch(shift, MotionEvent.ACTION_UP);
        KeyEvent last = sent.get(sent.size() - 1);
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, last.getKeyCode());
        assertEquals(KeyEvent.ACTION_UP, last.getAction());
    }

    @Test
    public void hideKeyHidesTheKeyboard() {
        View hide = null;
        for (View key : allKeys()) {
            if ("hide".equals(key.getTag())) {
                hide = key;
            }
        }
        assertTrue("No hide key", hide != null);
        touch(hide, MotionEvent.ACTION_DOWN);
        touch(hide, MotionEvent.ACTION_UP);
        assertFalse(keyboard.isKeyboardVisible());
        assertTrue(sent.isEmpty());
    }
}
