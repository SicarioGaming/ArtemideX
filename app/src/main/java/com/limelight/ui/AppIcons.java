package com.limelight.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;

import com.limelight.R;

import java.util.Locale;

/**
 * Cover Glow: designed icon tiles for apps that have no real cover art, like Desktop or Steam Big
 * Picture (the host usually sends a plain default image for those). Real games keep the box art
 * from the PC unless "Icons for every app" is chosen.
 *
 * Each kind of app has its own colour and icon; the tile is a gradient in that colour with a
 * faint HUD grid, a soft light sweep and a glow behind the icon.
 */
public final class AppIcons {
    public static final String PREF_COVER_STYLE = "cover_style";
    public static final String STYLE_MISSING = "missing";
    public static final String STYLE_ALWAYS = "always";
    public static final String STYLE_NEVER = "never";

    public enum Kind {
        DESKTOP(R.drawable.ic_overlay_desktop, 0xFF3B82F6),
        LAUNCHER(R.drawable.ic_overlay_gamepad, 0xFF06B6D4),
        LIBRARY(R.drawable.ic_overlay_folder, 0xFF8B5CF6),
        RETRO(R.drawable.cg_app_retro, 0xFFF97316),
        MEDIA(R.drawable.ic_overlay_play, 0xFFEC4899),
        TERMINAL(R.drawable.cg_app_terminal, 0xFF22C55E),
        GAME(R.drawable.ic_overlay_gamepad, 0);

        final int icon;
        final int accent;

        Kind(int icon, int accent) {
            this.icon = icon;
            this.accent = accent;
        }
    }

    // Colours for games shown as icons, picked by name so each keeps its colour
    private static final int[] GAME_ACCENTS = {
            0xFFE11D48, 0xFFF97316, 0xFFEAB308, 0xFF22C55E, 0xFF14B8A6, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899
    };

    private AppIcons() {}

    public static Kind kindFor(String appName) {
        String name = appName == null ? "" : appName.toLowerCase(Locale.ROOT);
        if (has(name, "desktop", "remote", "windows")) {
            return Kind.DESKTOP;
        }
        if (has(name, "steam", "big picture", "xbox", "game pass")) {
            return Kind.LAUNCHER;
        }
        if (has(name, "playnite", "launchbox", "gog", "epic", "ubisoft", "battle.net", "ea app", "library", "launcher")) {
            return Kind.LIBRARY;
        }
        if (has(name, "retroarch", "emulat", "dolphin", "pcsx", "rpcs3", "cemu", "yuzu", "ryujinx", "ppsspp",
                "duckstation", "mame", "es-de", "emulationstation")) {
            return Kind.RETRO;
        }
        if (has(name, "youtube", "netflix", "plex", "jellyfin", "kodi", "vlc", "spotify", "twitch", "browser",
                "chrome", "firefox", "media")) {
            return Kind.MEDIA;
        }
        if (has(name, "terminal", "powershell", "cmd")) {
            return Kind.TERMINAL;
        }
        return Kind.GAME;
    }

    private static boolean has(String name, String... words) {
        for (String word : words) {
            if (name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public static int accentFor(String appName) {
        Kind kind = kindFor(appName);
        if (kind != Kind.GAME) {
            return kind.accent;
        }
        int hash = appName == null ? 0 : appName.toLowerCase(Locale.ROOT).hashCode();
        return GAME_ACCENTS[Math.floorMod(hash, GAME_ACCENTS.length)];
    }

    /** Whether this app shows an icon tile instead of the PC's cover. */
    public static boolean useIcon(Context context, String appName) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String style = prefs.getString(PREF_COVER_STYLE, STYLE_MISSING);
        if (STYLE_ALWAYS.equals(style)) {
            return true;
        }
        if (STYLE_NEVER.equals(style)) {
            return false;
        }
        return hasNoCoverArt(appName);
    }

    // The apps hosts add themselves, which come without real cover art. Matched by their exact
    // names, so games with similar words in their titles keep their covers.
    private static final String[] BUILT_IN_APPS = {
            "desktop", "virtual desktop", "remote desktop", "steam", "steam big picture", "big picture",
            "playnite", "playnite fullscreen", "terminal", "powershell"
    };

    static boolean hasNoCoverArt(String appName) {
        String name = appName == null ? "" : appName.trim().toLowerCase(Locale.ROOT);
        for (String builtIn : BUILT_IN_APPS) {
            if (name.equals(builtIn)) {
                return true;
            }
        }
        return false;
    }

    public static Drawable tile(Context context, String appName) {
        Drawable icon = context.getDrawable(kindFor(appName).icon).mutate();
        icon.setTint(Color.WHITE);
        return new Tile(icon, accentFor(appName));
    }

    static final class Tile extends Drawable {
        private final Drawable icon;
        private final int accent;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint();
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

        Tile(Drawable icon, int accent) {
            this.icon = icon;
            this.accent = accent;
            grid.setColor(0x14FFFFFF);
            grid.setStrokeWidth(1f);
        }

        int accent() {
            return accent;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            int dark = blend(accent, Color.BLACK, 0.62f);
            fill.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    blend(accent, Color.WHITE, 0.08f), dark, Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(b.left, b.top, b.left + b.width() * 0.6f, b.top + b.height() * 0.6f,
                    0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            float cx = b.exactCenterX(), cy = b.top + b.height() * 0.42f;
            glow.setShader(new RadialGradient(cx, cy, Math.max(1f, b.width() * 0.5f),
                    0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            int size = Math.round(Math.min(b.width(), b.height()) * 0.42f);
            int left = Math.round(cx - size / 2f), top = Math.round(cy - size / 2f);
            icon.setBounds(left, top, left + size, top + size);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b, fill);

            // Faint HUD grid
            float step = Math.max(8f, b.width() / 8f);
            for (float x = b.left + step; x < b.right; x += step) {
                canvas.drawLine(x, b.top, x, b.bottom, grid);
            }
            for (float y = b.top + step; y < b.bottom; y += step) {
                canvas.drawLine(b.left, y, b.right, y, grid);
            }

            canvas.drawRect(b, sheen);
            canvas.drawRect(b, glow);
            icon.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            icon.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    static int blend(int from, int to, float amount) {
        float keep = 1f - amount;
        return Color.rgb(Math.round(Color.red(from) * keep + Color.red(to) * amount),
                Math.round(Color.green(from) * keep + Color.green(to) * amount),
                Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }
}
