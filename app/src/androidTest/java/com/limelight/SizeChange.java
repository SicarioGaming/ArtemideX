package com.limelight;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.test.platform.app.InstrumentationRegistry;

/**
 * Hands a screen a new size the way Android does when a phone folds or unfolds, goes into split
 * screen or turns: the screen's resources take the new size, then onConfigurationChanged runs.
 * The device's own display size and rotation settings are left alone, and so is its screen.
 */
public final class SizeChange {
    private SizeChange() {
    }

    /** Gives the screen this size in dp. Returns the size it had, for {@link #restore}. */
    public static Configuration apply(Activity activity, int widthDp, int heightDp) {
        Configuration[] before = new Configuration[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            before[0] = new Configuration(activity.getResources().getConfiguration());
            Configuration resized = new Configuration(before[0]);
            resized.screenWidthDp = widthDp;
            resized.screenHeightDp = heightDp;
            resized.smallestScreenWidthDp = Math.min(widthDp, heightDp);
            resized.orientation = widthDp > heightDp ?
                    Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
            dispatch(activity, resized);
        });
        return before[0];
    }

    /** Puts back the size {@link #apply} returned. */
    public static void restore(Activity activity, Configuration config) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> dispatch(activity, config));
    }

    // What ActivityThread does for a change the screen handles itself (see android:configChanges)
    @SuppressWarnings("deprecation")
    private static void dispatch(Activity activity, Configuration config) {
        Resources resources = activity.getResources();
        resources.updateConfiguration(config, resources.getDisplayMetrics());
        activity.onConfigurationChanged(new Configuration(config));
    }
}
