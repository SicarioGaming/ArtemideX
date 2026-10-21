package com.limelight.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.R;

/**
 * The settings screen's "Predicted impact" panel: where the frame pacing and V-Sync choices sit
 * between fastest response and smoothest motion, in plain words. These are rough comparisons
 * between the choices, not measurements.
 */
public final class SettingsFeel {
    public static final int LEVEL_LOWER = 0;
    public static final int LEVEL_MEDIUM = 1;
    public static final int LEVEL_HIGHER = 2;

    /** 0 is fastest response, 1 is smoothest motion. */
    public final float position;
    public final String text;
    public final int delay;
    public final int battery;

    private SettingsFeel(float position, String text, int delay, int battery) {
        this.position = position;
        this.text = text;
        this.delay = delay;
        this.battery = battery;
    }

    public static SettingsFeel describe(Context context, SharedPreferences prefs) {
        return describe(context,
                prefs.getString("frame_pacing", "balanced"),
                prefs.getString("vsync_mode", "off"),
                prefs.getBoolean("checkbox_async_decode", false)
                        && prefs.getBoolean("checkbox_async_lfr", false));
    }

    static SettingsFeel describe(Context context, String pacing, String vsync, boolean asyncLfr) {
        float position;
        int textRes;
        int delay;
        int battery = LEVEL_MEDIUM;
        switch (pacing) {
            case "latency":
                position = 0.15f;
                textRes = R.string.feel_latency;
                delay = LEVEL_LOWER;
                break;
            case "cap-fps":
                position = 0.5f;
                textRes = R.string.feel_cap_fps;
                delay = LEVEL_MEDIUM;
                break;
            case "smoothness":
                position = 0.92f;
                textRes = R.string.feel_smoothness;
                delay = LEVEL_HIGHER;
                break;
            case "host-timed":
                position = 0.65f;
                textRes = R.string.feel_host_timed;
                delay = LEVEL_MEDIUM;
                break;
            case "warp":
                position = 0.06f;
                textRes = R.string.feel_warp;
                delay = LEVEL_LOWER;
                battery = LEVEL_HIGHER;
                break;
            case "warp2":
                position = 0.02f;
                textRes = R.string.feel_warp2;
                delay = LEVEL_LOWER;
                battery = LEVEL_HIGHER;
                break;
            case "balanced":
            default:
                position = 0.45f;
                textRes = R.string.feel_balanced;
                delay = LEVEL_MEDIUM;
                break;
        }

        StringBuilder text = new StringBuilder(context.getString(textRes));
        // V-Sync and the async options apply to the direct renderer, not host frame timing
        boolean direct = !"host-timed".equals(pacing);
        if (direct && "vsync".equals(vsync)) {
            position += 0.15f;
            delay = Math.min(LEVEL_HIGHER, delay + 1);
            text.append(' ').append(context.getString(R.string.feel_vsync));
        }
        else if (direct && "fast".equals(vsync)) {
            position += 0.05f;
            text.append(' ').append(context.getString(R.string.feel_fast_vsync));
        }
        if (direct && asyncLfr) {
            position -= 0.05f;
            text.append(' ').append(context.getString(R.string.feel_async_lfr));
        }

        return new SettingsFeel(Math.max(0f, Math.min(1f, position)), text.toString(), delay, battery);
    }

    public static String levelName(Context context, int level) {
        switch (level) {
            case LEVEL_LOWER:
                return context.getString(R.string.feel_level_lower);
            case LEVEL_HIGHER:
                return context.getString(R.string.feel_level_higher);
            default:
                return context.getString(R.string.feel_level_medium);
        }
    }
}
