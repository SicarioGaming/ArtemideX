package com.limelight.ui;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

/**
 * The stream screen's floating menu button (from Artemide). A tap opens the overlay menu, or
 * closes it when it's open; a drag moves the button, kept within the screen.
 */
public final class FloatingButton {
    private static final int DRAG_THRESHOLD = 10;
    // Matches android:alpha in floating_menu_button.xml
    private static final float IDLE_ALPHA = 0.55f;

    private final View button;
    private final View screen;
    private final Runnable onTap;

    private boolean moving;
    private float dX, dY;
    private float startX, startY;

    /**
     * @param screen what the button moves within; the stream screen passes its window's decor view
     * @param onTap  opens the menu, or closes it when it's open
     */
    public FloatingButton(View button, View screen, Runnable onTap) {
        this.button = button;
        this.screen = screen;
        this.onTap = onTap;

        listenForTouches();
        // TalkBack's double-tap arrives as a click, not as touches
        button.setOnClickListener(view -> onTap.run());
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
                    moving = false;
                    // The button sits faded over the game and lights up while touched
                    view.animate().alpha(1f).setDuration(120).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawX() - startX) > DRAG_THRESHOLD ||
                            Math.abs(event.getRawY() - startY) > DRAG_THRESHOLD) {
                        moving = true;
                    }

                    float maxX = screen.getWidth() - view.getWidth();
                    float maxY = screen.getHeight() - view.getHeight();
                    view.setX(Math.max(0, Math.min(event.getRawX() + dX, maxX)));
                    view.setY(Math.max(0, Math.min(event.getRawY() + dY, maxY)));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moving) {
                        onTap.run();
                    }
                    moving = false;
                    view.animate().alpha(IDLE_ALPHA).setDuration(400).start();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    moving = false;
                    view.animate().alpha(IDLE_ALPHA).setDuration(400).start();
                    return true;
                default:
                    return false;
            }
        });
    }
}
