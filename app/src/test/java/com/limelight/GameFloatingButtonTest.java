package com.limelight;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * The floating menu button as the stream screen sets it up: Game's own button, moving within
 * Game's window and saving to the default preferences. With no game in its Intent, Game.onCreate
 * sets up its overlays, this button among them, and then finishes before anything connects, so
 * no stream starts. FloatingButtonTest covers the button's own rules.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class GameFloatingButtonTest {
    private static final int WIDE = 2000;
    private static final int TALL = 1000;

    private final Context context = RuntimeEnvironment.getApplication();
    private final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

    private Game game;
    private View window;
    private View button;

    private void openStreamScreen() {
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().get();
        window = game.getWindow().getDecorView();
        button = game.findViewById(R.id.floatingMenuButton);
        assertEquals("the button is on by default", View.VISIBLE, button.getVisibility());
    }

    private void layOut(int width, int height) {
        window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, width, height);
    }

    // What the menu's floating button switch does
    private void setShown(boolean shown) {
        ReflectionHelpers.callInstanceMethod(game, "setFloatingButtonShown",
                ReflectionHelpers.ClassParameter.from(boolean.class, shown));
    }

    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        button.dispatchTouchEvent(event);
        event.recycle();
    }

    private float roomX() {
        return WIDE - button.getWidth();
    }

    private float roomY() {
        return TALL - button.getHeight();
    }

    private void assertAt(float x, float y) {
        assertEquals(x, button.getX(), 0.01f);
        assertEquals(y, button.getY(), 0.01f);
    }

    @Test
    public void theStreamScreenStartsTheButtonInItsSavedSpot() {
        PreferenceConfiguration.saveFloatingButtonSpot(prefs, false, 0.25f, 0.75f);
        openStreamScreen();
        layOut(WIDE, TALL);
        assertAt(0.25f * roomX(), 0.75f * roomY());
    }

    @Test
    public void aDragOnTheStreamScreenIsWhereTheNextOneStarts() {
        openStreamScreen();
        layOut(WIDE, TALL);
        float x = button.getX() + 20;
        float y = button.getY() + 20;
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, x + 600, y + 300);
        touch(MotionEvent.ACTION_UP, x + 600, y + 300);
        float draggedX = button.getX();
        float draggedY = button.getY();

        // Saved as shares of the room in Game's window
        float[] spot = PreferenceConfiguration.readFloatingButtonSpot(prefs, false);
        assertEquals(draggedX / roomX(), spot[0], 1e-6f);
        assertEquals(draggedY / roomY(), spot[1], 1e-6f);

        openStreamScreen();
        layOut(WIDE, TALL);
        assertAt(draggedX, draggedY);
    }

    @Test
    public void hiddenAndShownFromTheMenuItGoesBackToItsSpot() {
        PreferenceConfiguration.saveFloatingButtonSpot(prefs, false, 0.5f, 0.5f);
        openStreamScreen();
        layOut(WIDE, TALL);
        float x = 0.5f * roomX();
        float y = 0.5f * roomY();
        assertAt(x, y);

        // Moved a little by a tap, then hidden and shown again
        button.setX(x + 7);
        setShown(false);
        assertEquals(View.GONE, button.getVisibility());
        layOut(WIDE, TALL);
        setShown(true);
        assertEquals(View.VISIBLE, button.getVisibility());
        layOut(WIDE, TALL);
        assertAt(x, y);
    }
}
