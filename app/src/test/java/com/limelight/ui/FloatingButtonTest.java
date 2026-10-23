package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;

/**
 * The floating menu button remembers where it was dragged, in the app's real preferences, and is
 * put back there when the next stream screen is laid out, and after a turn or a resize. The real
 * button (floating_menu_button.xml) sits in a frame standing in for the stream screen's window,
 * so sizes are exact pixels. FloatingButtonDeviceTest drags it and recreates the screen on a
 * device, and GameFloatingButtonTest checks the stream screen's own button.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class FloatingButtonTest {
    private static final int WIDE = 2000;
    private static final int TALL = 1000;

    private final Context context = RuntimeEnvironment.getApplication();
    private final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    private int taps;

    private FrameLayout screen;
    private View button;
    private FloatingButton floating;

    // A new stream screen, with the button shown as Game shows it, or hidden by the setting
    private void startStream(boolean shown) {
        screen = new FrameLayout(context);
        LayoutInflater.from(context).inflate(R.layout.floating_menu_button, screen, true);
        button = screen.findViewById(R.id.floatingMenuButton);
        floating = new FloatingButton(button, screen, prefs, () -> taps++);
        if (shown) {
            show();
        }
    }

    private void show() {
        button.setVisibility(View.VISIBLE);
        floating.placeOnNextLayout();
    }

    private void layOut(int width, int height) {
        screen.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, width, height);
    }

    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        button.dispatchTouchEvent(event);
        event.recycle();
    }

    // A finger from the button's middle, moved by dx and dy, then lifted
    private void drag(float dx, float dy) {
        float x = button.getX() + button.getWidth() / 2f;
        float y = button.getY() + button.getHeight() / 2f;
        touch(MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 4; i++) {
            touch(MotionEvent.ACTION_MOVE, x + dx * i / 4, y + dy * i / 4);
        }
        touch(MotionEvent.ACTION_UP, x + dx, y + dy);
    }

    // The button goes from one alpha to another over this long, not at once. Robolectric runs an
    // animation to its end in the first frame, so the length is read from the button's animator.
    private void assertFades(float from, float to, long ms) {
        assertEquals("changed at once", from, button.getAlpha(), 0);
        assertEquals(ms, button.animate().getDuration());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
        assertEquals(to, button.getAlpha(), 0);
    }

    private void assertInLayoutSpot() {
        assertEquals("not where the layout puts it", 0, button.getTranslationX(), 0);
        assertEquals("not where the layout puts it", 0, button.getTranslationY(), 0);
    }

    private void assertAt(float x, float y) {
        assertEquals(x, button.getX(), 0.01f);
        assertEquals(y, button.getY(), 0.01f);
    }

    @Test
    public void withNothingSavedTheButtonStartsWhereTheLayoutPutsIt() {
        startStream(true);
        layOut(WIDE, TALL);
        assertInLayoutSpot();
        assertTrue(button.getX() > 0);
        assertTrue(button.getY() > 0);
    }

    @Test
    public void aDraggedSpotIsSavedAndTheNextStreamStartsThere() {
        startStream(true);
        layOut(WIDE, TALL);
        float startX = button.getX();
        float startY = button.getY();
        drag(600, 300);
        float x = button.getX();
        float y = button.getY();
        assertEquals(startX + 600, x, 0.01f);
        assertEquals(startY + 300, y, 0.01f);
        assertEquals("a drag isn't a tap", 0, taps);

        // Saved in the default preferences as shares of the room
        float roomX = WIDE - button.getWidth();
        float roomY = TALL - button.getHeight();
        assertEquals(x / roomX, prefs.getFloat("floating_button_landscape_x", -1), 1e-6f);
        assertEquals(y / roomY, prefs.getFloat("floating_button_landscape_y", -1), 1e-6f);
        assertFalse("a landscape drag saved a portrait spot", prefs.contains("floating_button_portrait_x"));

        startStream(true);
        layOut(WIDE, TALL);
        assertAt(x, y);
    }

    @Test
    public void landscapeAndPortraitEachKeepTheirOwnSpot() {
        startStream(true);
        layOut(WIDE, TALL);
        drag(600, 300);
        float landscapeX = button.getX();
        float landscapeY = button.getY();

        // Turned: nothing saved for portrait yet
        layOut(TALL, WIDE);
        assertInLayoutSpot();
        drag(200, 900);
        float portraitX = button.getX();
        float portraitY = button.getY();

        layOut(WIDE, TALL);
        assertAt(landscapeX, landscapeY);
        layOut(TALL, WIDE);
        assertAt(portraitX, portraitY);
    }

    @Test
    public void aSmallerWindowKeepsTheButtonOnScreen() {
        // Saved in the bottom right corner
        PreferenceConfiguration.saveFloatingButtonSpot(prefs, false, 1f, 1f);
        startStream(true);
        layOut(WIDE, TALL);
        assertAt(WIDE - button.getWidth(), TALL - button.getHeight());

        // Split screen, or a folded phone: still in the corner, all of it showing
        layOut(1200, 600);
        assertAt(1200 - button.getWidth(), 600 - button.getHeight());

        // A window smaller than the button: in the top left corner
        layOut(30, 20);
        assertAt(0, 0);
    }

    @Test
    public void otherLayoutsLeaveTheButtonAlone() {
        startStream(true);
        layOut(WIDE, TALL);
        // Where a drag that's still going has it
        button.setX(300);
        button.setY(200);
        // A stats update lays the screen out again at the same size
        screen.requestLayout();
        layOut(WIDE, TALL);
        assertAt(300, 200);
    }

    @Test
    public void shownAgainItGoesBackToItsSpot() {
        PreferenceConfiguration.saveFloatingButtonSpot(prefs, false, 0.5f, 0.5f);

        // Hidden when the stream starts, so it has never been laid out
        startStream(false);
        layOut(WIDE, TALL);
        assertEquals(0, button.getWidth());
        show();
        layOut(WIDE, TALL);
        float x = 0.5f * (WIDE - button.getWidth());
        float y = 0.5f * (TALL - button.getHeight());
        assertTrue(button.getWidth() > 0);
        assertAt(x, y);

        // Moved a little by a tap, then hidden and shown from the menu
        button.setX(x + 7);
        button.setVisibility(View.GONE);
        layOut(WIDE, TALL);
        show();
        layOut(WIDE, TALL);
        assertAt(x, y);
    }

    @Test
    public void badSavedValuesLeaveItWhereTheLayoutPutsIt() {
        float[] bad = {Float.NaN, -0.2f, 1.5f, Float.POSITIVE_INFINITY};
        for (float value : bad) {
            prefs.edit().clear().putFloat("floating_button_landscape_x", value)
                    .putFloat("floating_button_landscape_y", 0.5f).commit();
            startStream(true);
            layOut(WIDE, TALL);
            assertInLayoutSpot();
        }

        // Saved as another type
        prefs.edit().clear().putString("floating_button_landscape_x", "0.5")
                .putFloat("floating_button_landscape_y", 0.5f).commit();
        startStream(true);
        layOut(WIDE, TALL);
        assertInLayoutSpot();
        prefs.edit().clear().putFloat("floating_button_landscape_x", 0.5f)
                .putInt("floating_button_landscape_y", 1).commit();
        startStream(true);
        layOut(WIDE, TALL);
        assertInLayoutSpot();

        // Only one of the pair saved
        prefs.edit().clear().putFloat("floating_button_landscape_x", 0.5f).commit();
        startStream(true);
        layOut(WIDE, TALL);
        assertInLayoutSpot();
    }

    @Test
    public void aDragMovesTheButtonAndKeepsItOnTheScreen() {
        startStream(true);
        layOut(WIDE, TALL);
        float x = button.getX() + 20;
        float y = button.getY() + 20;
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, x + 600, y + 300);
        assertEquals(x - 20 + 600, button.getX(), 0.01f);
        assertEquals(y - 20 + 300, button.getY(), 0.01f);
        // Past the bottom right corner, and past the top left one
        touch(MotionEvent.ACTION_MOVE, x + 5000, y + 5000);
        assertEquals(WIDE - button.getWidth(), button.getX(), 0);
        assertEquals(TALL - button.getHeight(), button.getY(), 0);
        touch(MotionEvent.ACTION_MOVE, x - 5000, y - 5000);
        touch(MotionEvent.ACTION_UP, x - 5000, y - 5000);
        assertEquals(0, button.getX(), 0);
        assertEquals(0, button.getY(), 0);
        assertEquals("a drag isn't a tap", 0, taps);
    }

    @Test
    public void aTapOpensTheMenuAndTheDragThresholdIs10Px() {
        startStream(true);
        layOut(WIDE, TALL);
        float x = button.getX() + 20;
        float y = button.getY() + 20;

        // A finger that wobbles 10 px is still a tap
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, x + 10, y - 10);
        touch(MotionEvent.ACTION_UP, x + 10, y - 10);
        assertEquals(1, taps);
        assertFalse("a tap saved a spot", prefs.contains("floating_button_landscape_x"));

        // 11 px is a drag
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, x + 11, y);
        touch(MotionEvent.ACTION_UP, x + 11, y);
        assertEquals(1, taps);
        assertTrue(prefs.contains("floating_button_landscape_x"));

        // TalkBack's double-tap is a click
        button.performClick();
        assertEquals(2, taps);
    }

    @Test
    public void aCancelledDragKeepsItsSpot() {
        startStream(true);
        layOut(WIDE, TALL);
        float x = button.getX() + 20;
        float y = button.getY() + 20;

        // A touch that's cancelled before it moves is neither a tap nor a drag
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_CANCEL, x, y);
        assertEquals(0, taps);
        assertFalse("a cancelled tap saved a spot", prefs.contains("floating_button_landscape_x"));

        // The system can cancel a drag partway, when the notifications are pulled down over it
        // say. The button stays where the drag left it, and that's the spot saved.
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, x + 600, y + 300);
        touch(MotionEvent.ACTION_CANCEL, x + 600, y + 300);
        float draggedX = button.getX();
        float draggedY = button.getY();
        assertEquals(x - 20 + 600, draggedX, 0.01f);
        assertEquals(y - 20 + 300, draggedY, 0.01f);
        assertEquals("a drag isn't a tap", 0, taps);
        float[] spot = PreferenceConfiguration.readFloatingButtonSpot(prefs, false);
        assertEquals(draggedX / (WIDE - button.getWidth()), spot[0], 1e-6f);
        assertEquals(draggedY / (TALL - button.getHeight()), spot[1], 1e-6f);

        startStream(true);
        layOut(WIDE, TALL);
        assertAt(draggedX, draggedY);
    }

    @Test
    public void itLightsUpWhileTouchedAndFadesAfter() {
        startStream(true);
        layOut(WIDE, TALL);
        float x = button.getX() + 20;
        float y = button.getY() + 20;
        assertEquals("faded over the game", 0.55f, button.getAlpha(), 0);

        // Lights up over 120 ms, and fades back over 400 ms when the drag ends
        touch(MotionEvent.ACTION_DOWN, x, y);
        assertFades(0.55f, 1f, 120);
        touch(MotionEvent.ACTION_MOVE, x + 600, y + 300);
        touch(MotionEvent.ACTION_UP, x + 600, y + 300);
        assertFades(1f, 0.55f, 400);

        // The same for a tap, and for a cancelled touch
        x = button.getX() + 20;
        y = button.getY() + 20;
        touch(MotionEvent.ACTION_DOWN, x, y);
        assertFades(0.55f, 1f, 120);
        touch(MotionEvent.ACTION_UP, x, y);
        assertEquals(1, taps);
        assertFades(1f, 0.55f, 400);
        touch(MotionEvent.ACTION_DOWN, x, y);
        assertFades(0.55f, 1f, 120);
        touch(MotionEvent.ACTION_CANCEL, x, y);
        assertFades(1f, 0.55f, 400);
    }
}
