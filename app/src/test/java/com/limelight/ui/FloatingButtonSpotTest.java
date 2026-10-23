package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The floating menu button's spot is saved as a share of the room it can move in, so it lands in
 * the same place on a screen of another size, and never off it. Plain numbers here:
 * FloatingButtonTest lays out the real button, and FloatingButtonDeviceTest drags it on a device.
 */
public class FloatingButtonSpotTest {
    // A 2400 x 1080 phone in landscape and a 120 px button
    private static final int WIDTH = 2400;
    private static final int HEIGHT = 1080;
    private static final int BUTTON = 120;

    @Test
    public void theRoomIsTheScreenLessTheButton() {
        assertEquals(2280, FloatingButton.room(WIDTH, BUTTON), 0);
        assertEquals(960, FloatingButton.room(HEIGHT, BUTTON), 0);
    }

    @Test
    public void aSpotGoesToAShareAndBack() {
        float room = FloatingButton.room(WIDTH, BUTTON);
        assertEquals(0f, FloatingButton.toFraction(0, room), 0);
        assertEquals(0.5f, FloatingButton.toFraction(1140, room), 0);
        assertEquals(1f, FloatingButton.toFraction(2280, room), 0);
        assertEquals(1140, FloatingButton.toPixels(0.5f, room), 0);
        assertEquals(2280, FloatingButton.toPixels(1f, room), 0);
        // A third isn't exact as a float, but it comes back to the same pixel
        assertEquals(760, FloatingButton.toPixels(FloatingButton.toFraction(760, room), room), 0.01f);
    }

    @Test
    public void positionsAreKeptInsideTheRoom() {
        assertEquals(0, FloatingButton.clamp(-30, 1000), 0);
        assertEquals(400, FloatingButton.clamp(400, 1000), 0);
        assertEquals(1000, FloatingButton.clamp(1200, 1000), 0);
        assertEquals(0, FloatingButton.clamp(Float.NaN, 1000), 0);
        // Past either edge, the share is the edge's
        assertEquals(0f, FloatingButton.toFraction(-5, 1000), 0);
        assertEquals(1f, FloatingButton.toFraction(1500, 1000), 0);
        assertEquals(1000, FloatingButton.toPixels(1.5f, 1000), 0);
    }

    @Test
    public void aSmallerScreenKeepsTheSpotInProportionAndOnScreen() {
        // Saved at the right edge, a quarter of the way down
        float x = FloatingButton.toFraction(2280, FloatingButton.room(WIDTH, BUTTON));
        float y = FloatingButton.toFraction(240, FloatingButton.room(HEIGHT, BUTTON));
        assertEquals(1f, x, 0);
        assertEquals(0.25f, y, 0);

        // Half the window in split screen: still at the right edge with the whole button showing,
        // and still a quarter of the way down
        assertEquals(1080, FloatingButton.toPixels(x, FloatingButton.room(1200, BUTTON)), 0);
        assertEquals(105, FloatingButton.toPixels(y, FloatingButton.room(540, BUTTON)), 0);
        // The old pixels would have been off the screen
        assertEquals(1080, FloatingButton.clamp(2280, FloatingButton.room(1200, BUTTON)), 0);
    }

    @Test
    public void aButtonBiggerThanTheScreenSitsInTheCorner() {
        float room = FloatingButton.room(100, BUTTON);
        assertEquals(0, room, 0);
        assertEquals(0, FloatingButton.toPixels(0.7f, room), 0);
        assertEquals(0, FloatingButton.clamp(50, room), 0);
        // With no room there's no share to work out, and no dividing by 0
        assertEquals(0f, FloatingButton.toFraction(50, room), 0);
        assertEquals(0f, FloatingButton.toFraction(50, FloatingButton.room(BUTTON, BUTTON)), 0);
    }

    @Test
    public void onlySharesFrom0To1AreUsed() {
        for (float good : new float[] {0f, 0.25f, 1f}) {
            assertTrue(String.valueOf(good), FloatingButton.isFraction(good));
        }
        for (float bad : new float[] {Float.NaN, -0.01f, 1.01f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, 2400f}) {
            assertFalse(String.valueOf(bad), FloatingButton.isFraction(bad));
        }
    }

    @Test
    public void tallScreensArePortraitAndSquareOnesLandscape() {
        assertTrue(FloatingButton.isPortrait(1080, 2400));
        assertFalse(FloatingButton.isPortrait(2400, 1080));
        assertFalse(FloatingButton.isPortrait(1800, 1800));
        // A Fold's inner screen held upright
        assertTrue(FloatingButton.isPortrait(1856, 2160));
    }
}
