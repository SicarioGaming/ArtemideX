package com.limelight.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The floating menu button: a tap opens the menu, a drag moves the button within the screen. The
 * real button (floating_menu_button.xml) sits in a frame standing in for the stream screen's
 * window, so sizes are exact pixels.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class FloatingButtonTest {
    private static final int WIDE = 2000;
    private static final int TALL = 1000;

    private final Context context = RuntimeEnvironment.getApplication();
    private int taps;

    private FrameLayout screen;
    private View button;

    @Before
    public void startStream() {
        screen = new FrameLayout(context);
        LayoutInflater.from(context).inflate(R.layout.floating_menu_button, screen, true);
        button = screen.findViewById(R.id.floatingMenuButton);
        new FloatingButton(button, screen, () -> taps++);
        button.setVisibility(View.VISIBLE);
        screen.measure(View.MeasureSpec.makeMeasureSpec(WIDE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, WIDE, TALL);
    }

    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        button.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test
    public void aDragMovesTheButtonAndKeepsItOnTheScreen() {
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
        float x = button.getX() + 20;
        float y = button.getY() + 20;

        // A finger that wobbles 10 px is still a tap
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, x + 10, y - 10);
        touch(MotionEvent.ACTION_UP, x + 10, y - 10);
        assertEquals(1, taps);

        // 11 px is a drag
        touch(MotionEvent.ACTION_DOWN, x, y);
        touch(MotionEvent.ACTION_MOVE, x + 11, y);
        touch(MotionEvent.ACTION_UP, x + 11, y);
        assertEquals(1, taps);

        // TalkBack's double-tap is a click
        button.performClick();
        assertEquals(2, taps);
    }
}
