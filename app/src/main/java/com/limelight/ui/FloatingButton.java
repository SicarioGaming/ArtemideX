package com.limelight.ui;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * The stream screen's floating menu button (from Artemide). A tap opens the overlay menu, or
 * closes it when it's open; a drag moves the button, kept within the screen.
 *
 * Starwire remembers where it was dragged. When a drag ends, the spot is saved as a share (0 to 1)
 * of the room the button can move in: the screen's width less the button's, and the same for the
 * height. Landscape and portrait screens each keep their own spot. When the screen is laid out at
 * a new size (the first layout, a turn, a fold or unfold, a resized window), and after the button
 * is shown again, it goes back to the spot saved for that orientation, inside the screen. With
 * nothing usable saved, it stays where floating_menu_button.xml puts it. It never moves from under
 * a finger: a layout that comes during a touch waits until the finger lifts.
 */
public final class FloatingButton {
    private static final int DRAG_THRESHOLD = 10;
    // Matches android:alpha in floating_menu_button.xml
    private static final float IDLE_ALPHA = 0.55f;

    private final View button;
    private final View screen;
    private final SharedPreferences prefs;
    private final Runnable onTap;

    // A finger is on the button, from ACTION_DOWN to ACTION_UP or ACTION_CANCEL
    private boolean touched;
    private boolean moving;
    private float dX, dY;
    private float startX, startY;
    // The screen size the button was last put in its spot for; 0 when it's due to be put back
    private int placedWidth, placedHeight;

    /**
     * @param screen what the button moves within; the stream screen passes its window's decor view
     * @param onTap  opens the menu, or closes it when it's open
     */
    public FloatingButton(View button, View screen, SharedPreferences prefs, Runnable onTap) {
        this.button = button;
        this.screen = screen;
        this.prefs = prefs;
        this.onTap = onTap;

        listenForTouches();
        // TalkBack's double-tap arrives as a click, not as touches
        button.setOnClickListener(view -> onTap.run());
        screen.addOnLayoutChangeListener((view, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) ->
                onScreenLaidOut(right - left, bottom - top));
    }

    /** For when the button is shown again: the next layout puts it back in its spot. */
    public void placeOnNextLayout() {
        placedWidth = 0;
        placedHeight = 0;
        button.requestLayout();
    }

    // The screen is laid out again for many reasons, a stats update among them. Only a new size,
    // or the button being shown again, puts the button back in its spot.
    private void onScreenLaidOut(int width, int height) {
        if (width == placedWidth && height == placedHeight) {
            return;
        }
        if (touched) {
            // Left under the finger; touchEnded() asks for this layout again
            return;
        }
        if (width <= 0 || height <= 0 || button.getWidth() == 0) {
            // Not laid out yet: the button has been hidden since the stream started
            return;
        }
        placedWidth = width;
        placedHeight = height;

        float[] spot = PreferenceConfiguration.readFloatingButtonSpot(prefs, isPortrait(width, height));
        if (!isFraction(spot[0]) || !isFraction(spot[1])) {
            // Nothing usable saved for this orientation: where the layout puts it
            button.setTranslationX(0);
            button.setTranslationY(0);
            return;
        }
        button.setX(toPixels(spot[0], room(width, button.getWidth())));
        button.setY(toPixels(spot[1], room(height, button.getHeight())));
    }

    private void saveSpot() {
        int width = screen.getWidth();
        int height = screen.getHeight();
        PreferenceConfiguration.saveFloatingButtonSpot(prefs, isPortrait(width, height),
                toFraction(button.getX(), room(width, button.getWidth())),
                toFraction(button.getY(), room(height, button.getHeight())));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void listenForTouches() {
        // The button sits above the menu, so it stays tappable while the menu is open
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    dX = view.getX() - event.getRawX();
                    dY = view.getY() - event.getRawY();
                    touched = true;
                    moving = false;
                    // The button sits faded over the game and lights up while touched
                    view.animate().alpha(1f).setDuration(120).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawX() - startX) > DRAG_THRESHOLD ||
                            Math.abs(event.getRawY() - startY) > DRAG_THRESHOLD) {
                        moving = true;
                    }

                    view.setX(clamp(event.getRawX() + dX, room(screen.getWidth(), view.getWidth())));
                    view.setY(clamp(event.getRawY() + dY, room(screen.getHeight(), view.getHeight())));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moving) {
                        saveSpot();
                    }
                    else {
                        onTap.run();
                    }
                    touchEnded();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    // The button stays where the drag left it, so that's the spot to remember
                    if (moving) {
                        saveSpot();
                    }
                    touchEnded();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void touchEnded() {
        touched = false;
        moving = false;
        button.animate().alpha(IDLE_ALPHA).setDuration(400).start();
        if (screen.getWidth() != placedWidth || screen.getHeight() != placedHeight) {
            // A layout came while the finger was down, so the button is put in its spot now
            button.requestLayout();
        }
    }

    /** A square screen counts as landscape. */
    static boolean isPortrait(int width, int height) {
        return height > width;
    }

    /** The room the button can move in along one side: the screen less the button, never below 0. */
    static float room(int screenSize, int buttonSize) {
        return Math.max(0, screenSize - buttonSize);
    }

    /** A position kept from 0 to the room. NaN comes out as 0. */
    static float clamp(float position, float room) {
        return position > 0 ? Math.min(position, Math.max(0, room)) : 0;
    }

    /** A position as a share of the room, from 0 to 1; 0 when there's no room. */
    static float toFraction(float position, float room) {
        return room > 0 ? clamp(position, room) / room : 0;
    }

    /** A saved share back to a position, inside the room. */
    static float toPixels(float fraction, float room) {
        return clamp(fraction * room, room);
    }

    /** Whether a saved value can be used: a share from 0 to 1. NaN and infinities can't. */
    static boolean isFraction(float value) {
        return value >= 0 && value <= 1;
    }
}
