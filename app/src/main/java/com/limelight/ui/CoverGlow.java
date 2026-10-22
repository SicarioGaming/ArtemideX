package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;

/**
 * Colours for the Cover Glow look: the screen takes its colour from the selected game's cover.
 *
 * tintFromPixels() picks the cover's most vivid colour (saturated pixels weigh most, so a dark
 * cover with a red logo turns red rather than grey); deep() and panel() darken it for the
 * background and frosted panels, keeping white text readable on both.
 */
public final class CoverGlow {
    // Violet, used before a cover has loaded or when a cover has no colour
    public static final int DEFAULT_TINT = 0xFF7C3AED;

    private CoverGlow() {}

    public static int tintFromBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return DEFAULT_TINT;
        }
        try {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 32, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && small.getConfig() == Bitmap.Config.HARDWARE) {
                // Box art is decoded to GPU memory, which can't be read; copy the small version out
                Bitmap readable = small.copy(Bitmap.Config.ARGB_8888, false);
                if (small != bitmap) {
                    small.recycle();
                }
                small = readable;
            }
            int[] pixels = new int[small.getWidth() * small.getHeight()];
            small.getPixels(pixels, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
            if (small != bitmap) {
                small.recycle();
            }
            return tintFromPixels(pixels);
        }
        catch (RuntimeException e) {
            // The colour is decoration; never let a cover close the app
            return DEFAULT_TINT;
        }
    }

    public static int tintFromPixels(int[] pixels) {
        float[] hsv = new float[3];
        double x = 0, y = 0, weightSum = 0, satSum = 0;
        for (int pixel : pixels) {
            Color.colorToHSV(pixel, hsv);
            // Vivid, not-too-dark pixels decide the colour
            double weight = hsv[1] * hsv[1] * Math.min(1, hsv[2] * 1.5);
            if (weight <= 0) {
                continue;
            }
            // Hue is an angle, so average it as a direction
            double angle = Math.toRadians(hsv[0]);
            x += Math.cos(angle) * weight;
            y += Math.sin(angle) * weight;
            weightSum += weight;
            satSum += hsv[1] * weight;
        }
        if (weightSum < pixels.length * 0.02) {
            // Almost no colour in the cover (black and white art)
            return DEFAULT_TINT;
        }
        float hue = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
        float saturation = (float) Math.max(0.55, Math.min(0.9, satSum / weightSum));
        return Color.HSVToColor(new float[] {hue, saturation, 0.85f});
    }

    /** The background behind everything: the tint, mostly darkened. */
    public static int deep(int tint) {
        return Color.rgb(darken(Color.red(tint), 0.16f, 8),
                darken(Color.green(tint), 0.16f, 8),
                darken(Color.blue(tint), 0.16f, 8));
    }

    /** Frosted panels and sheets: a little lighter than deep(), nearly opaque so the stream
     *  behind doesn't show through the text. */
    public static int panel(int tint) {
        return Color.argb(0xF4, darken(Color.red(tint), 0.22f, 10),
                darken(Color.green(tint), 0.22f, 10),
                darken(Color.blue(tint), 0.22f, 10));
    }

    // Each game's colour is kept once its cover has been seen, so the stream's menu can use it
    private static final String PREF_FILENAME = "CoverGlow";

    public static void rememberTint(Context context, String pcUuid, int appId, int tint) {
        context.getSharedPreferences(PREF_FILENAME, Context.MODE_PRIVATE).edit()
                .putInt("tint_" + pcUuid + "_" + appId, tint)
                .apply();
    }

    public static int rememberedTint(Context context, String pcUuid, int appId) {
        return context.getSharedPreferences(PREF_FILENAME, Context.MODE_PRIVATE)
                .getInt("tint_" + pcUuid + "_" + appId, DEFAULT_TINT);
    }

    private static int darken(int channel, float keep, int floor) {
        return Math.round(channel * keep + floor);
    }
}
