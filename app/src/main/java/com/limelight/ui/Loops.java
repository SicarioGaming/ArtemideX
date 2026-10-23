package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * Starwire's endless animations (the game screen's glow, its floating cover, the rainbow and the
 * twinkling stars) run only while their screen shows and the user has animations on.
 *
 * Before Android 8, an endless animator doesn't stop when animations are off: its length scales
 * to nothing and it repeats every frame, so the screen redraws for ever and the main thread
 * never rests. A paused one there still wakes the main thread every frame, so it's stopped
 * instead.
 */
public final class Loops {
    private Loops() {}

    /** Whether endless animations may run: the user has animations on. */
    public static boolean allowed(Context context) {
        // Android 8 and later also turn animators off for battery saver on some phones
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }
        // Developer options' animator duration scale; Accessibility's Remove animations sets it too
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    /** Starts the animation or carries it on, unless the user has animations off. */
    public static void run(Context context, ValueAnimator loop) {
        if (!allowed(context)) {
            stop(loop);
        }
        else if (loop.isPaused()) {
            loop.resume();
        }
        else if (!loop.isStarted()) {
            loop.start();
        }
    }

    /** Holds the animation while its screen is out of sight; run() carries it on. */
    public static void hold(ValueAnimator loop) {
        hold(loop, Build.VERSION.SDK_INT);
    }

    // For tests: hold() as on another Android version
    static void hold(ValueAnimator loop, int sdk) {
        if (sdk >= Build.VERSION_CODES.O) {
            loop.pause();
        }
        else {
            stop(loop);
        }
    }

    // Back to its start, so run() starts it from there again without a jump
    private static void stop(ValueAnimator loop) {
        if (loop.isStarted()) {
            loop.cancel();
            loop.setCurrentPlayTime(0);
        }
    }
}
