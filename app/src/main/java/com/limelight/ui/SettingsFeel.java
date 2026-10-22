package com.limelight.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The settings screen's "Predicted impact" panel: where the frame pacing, V-Sync and Async LFR
 * choices put you between fastest response and smoothest motion, and which way each of them
 * pushes. These are rough comparisons between the choices, not measurements.
 */
public final class SettingsFeel {
    public static final int LEVEL_LOWER = 0;
    public static final int LEVEL_MEDIUM = 1;
    public static final int LEVEL_HIGHER = 2;

    public static final int EFFECT_FASTER = -1;
    public static final int EFFECT_NONE = 0;
    public static final int EFFECT_SMOOTHER = 1;
    public static final int EFFECT_NOT_USED = 2;
    public static final int EFFECT_BALANCED = 3;

    /** One setting that moves the marker: its name, its current value, and which way it pushes. */
    public static final class Factor {
        public final String name;
        public final String value;
        public final int effect;

        Factor(String name, String value, int effect) {
            this.name = name;
            this.value = value;
            this.effect = effect;
        }
    }

    /** 0 is fastest response, 1 is smoothest motion. */
    public final float position;
    public final String text;
    public final int delay;
    public final int battery;
    public final List<Factor> factors;

    private SettingsFeel(float position, String text, int delay, int battery, List<Factor> factors) {
        this.position = position;
        this.text = text;
        this.delay = delay;
        this.battery = battery;
        this.factors = Collections.unmodifiableList(factors);
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
                pacing = "balanced";
                position = 0.45f;
                textRes = R.string.feel_balanced;
                delay = LEVEL_MEDIUM;
                break;
        }

        List<Factor> factors = new ArrayList<>();
        int pacingEffect = position < 0.35f ? EFFECT_FASTER : position > 0.6f ? EFFECT_SMOOTHER : EFFECT_BALANCED;
        factors.add(new Factor(context.getString(R.string.feel_factor_pacing),
                entryName(context, R.array.video_frame_pacing_names, R.array.video_frame_pacing_values, pacing),
                pacingEffect));

        // V-Sync and Async LFR apply to the direct renderer, not host frame timing
        boolean direct = !"host-timed".equals(pacing);
        String vsyncName = entryName(context, R.array.vsync_mode_names, R.array.vsync_mode_values, vsync);
        int vsyncEffect;
        if (!direct) {
            vsyncEffect = EFFECT_NOT_USED;
        }
        else if ("vsync".equals(vsync)) {
            position += 0.15f;
            delay = Math.min(LEVEL_HIGHER, delay + 1);
            vsyncEffect = EFFECT_SMOOTHER;
        }
        else if ("fast".equals(vsync)) {
            position += 0.05f;
            vsyncEffect = EFFECT_SMOOTHER;
        }
        else {
            vsyncEffect = EFFECT_NONE;
        }
        factors.add(new Factor(context.getString(R.string.feel_factor_vsync), vsyncName, vsyncEffect));

        int lfrEffect;
        if (!direct) {
            lfrEffect = EFFECT_NOT_USED;
        }
        else if (asyncLfr) {
            position -= 0.05f;
            lfrEffect = EFFECT_FASTER;
        }
        else {
            lfrEffect = EFFECT_NONE;
        }
        factors.add(new Factor(context.getString(R.string.feel_factor_async_lfr),
                context.getString(asyncLfr ? R.string.feel_on : R.string.feel_off), lfrEffect));

        return new SettingsFeel(Math.max(0f, Math.min(1f, position)), context.getString(textRes),
                delay, battery, factors);
    }

    // The name shown in settings for a list setting's value
    private static String entryName(Context context, int namesRes, int valuesRes, String value) {
        String[] names = context.getResources().getStringArray(namesRes);
        String[] values = context.getResources().getStringArray(valuesRes);
        for (int i = 0; i < values.length && i < names.length; i++) {
            if (values[i].equals(value)) {
                return names[i];
            }
        }
        return value;
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

    public static String effectName(Context context, int effect) {
        switch (effect) {
            case EFFECT_FASTER:
                return context.getString(R.string.feel_effect_faster);
            case EFFECT_SMOOTHER:
                return context.getString(R.string.feel_effect_smoother);
            case EFFECT_NOT_USED:
                return context.getString(R.string.feel_effect_not_used);
            case EFFECT_BALANCED:
                return context.getString(R.string.feel_effect_balanced);
            default:
                return context.getString(R.string.feel_effect_none);
        }
    }

    public static int effectColor(int effect) {
        switch (effect) {
            case EFFECT_FASTER:
                return 0xFFFDBA74;
            case EFFECT_SMOOTHER:
                return 0xFFA5B4FC;
            case EFFECT_BALANCED:
                return 0xFFE9D5FF;
            default:
                return 0xFF94A3B8;
        }
    }
}
