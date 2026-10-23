package com.limelight.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.util.LruCache;

import com.limelight.R;

import java.util.Locale;

/**
 * Cover Glow: designed icon tiles for apps that have no real cover art, like Desktop or Steam Big
 * Picture (the host usually sends a plain default image for those). Real games keep the box art
 * from the PC unless "Icons for every app" is chosen.
 *
 * Each kind of app has its own colour and icon; the tile is a gradient in that colour with a
 * faint HUD grid, a soft light sweep and a glow behind the icon. Desktop and games without art get
 * the Starwire tile instead: the logo over a small starfield, with a glow in the app's colour.
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
        Kind kind = kindFor(appName);
        if (kind == Kind.DESKTOP || kind == Kind.GAME) {
            return new StarwireTile(accentFor(appName), appName == null ? 0 : appName.hashCode());
        }
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

    // Missing-cover tiles already drawn, by app name (about 0.5 MB each)
    private static final LruCache<String, Bitmap> MISSING_COVERS = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** The Starwire tile as a bitmap, for covers the PC doesn't have (shown with the app's name).
     *  Each app gets its own colour and its own take on the tile, so a row of them isn't all alike. */
    public static Bitmap missingCoverBitmap(String appName) {
        String key = appName == null ? "" : appName;
        Bitmap bitmap = MISSING_COVERS.get(key);
        if (bitmap == null) {
            // Box art proportions, like the covers it stands in for
            bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888);
            Drawable tile = new StarwireTile(accentFor(appName), key.hashCode());
            tile.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            tile.draw(new Canvas(bitmap));
            MISSING_COVERS.put(key, bitmap);
        }
        return bitmap;
    }

    // The launcher icon's colours: the ring flows blue, violet, red, orange
    private static final int[] RAINBOW = {0xFF0894FF, 0xFFC959DD, 0xFFFF2E54, 0xFFFF9004, 0xFF0894FF};

    /** Starwire's logo (the void, its rainbow ring, a tilted orbit with a moon, a star on the rim)
     *  over deep space with a few stars. The app's name picks its own take on it (where the ring's
     *  colours start, the orbit's tilt, where the moon is, where the glow sits and a second colour
     *  in it, the space's tint, the logo's place and size), so tiles side by side differ a little. */
    static final class StarwireTile extends Drawable {
        private final int accent;
        private final long seed;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint orbit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float[] stars = new float[0];
        private Shader space, nebula, haze, starGlow;

        // This app's take, fixed by its name
        private final float ringTurn, tilt, moonAngle, shiftX, shiftY, size;
        private final int glowCorner, starCount, secondColour, spaceTop;
        // Where the logo lands at the current bounds
        private float cx, cy, s;

        StarwireTile(int accent, long seed) {
            this.accent = accent;
            this.seed = seed;
            java.util.Random look = new java.util.Random(seed * 31 + 17);
            ringTurn = look.nextFloat() * 360f;
            tilt = -12f - look.nextFloat() * 24f;
            moonAngle = look.nextFloat() * 360f;
            shiftX = (look.nextFloat() - 0.5f) * 0.1f;
            shiftY = (look.nextFloat() - 0.5f) * 0.06f;
            size = 0.92f + look.nextFloat() * 0.12f;
            glowCorner = look.nextInt(4);
            starCount = 20 + look.nextInt(14);
            int second = GAME_ACCENTS[look.nextInt(GAME_ACCENTS.length)];
            secondColour = second == accent ? GAME_ACCENTS[(look.nextInt(GAME_ACCENTS.length - 1) + 1) % GAME_ACCENTS.length] : second;
            spaceTop = blend(0xFF24124F, accent, 0.1f + look.nextFloat() * 0.16f);
        }

        int accent() {
            return accent;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float w = b.width(), h = b.height();
            space = new LinearGradient(b.left, b.top, b.right, b.bottom, spaceTop, 0xFF05040F, Shader.TileMode.CLAMP);
            // The app's glow in one corner, a faint second colour in the opposite one
            float gx = (glowCorner & 1) == 0 ? 0.8f : 0.2f, gy = (glowCorner & 2) == 0 ? 0.12f : 0.88f;
            nebula = new RadialGradient(b.left + w * gx, b.top + h * gy, Math.max(1f, w * 0.85f),
                    (accent & 0x00FFFFFF) | 0x66000000, accent & 0x00FFFFFF, Shader.TileMode.CLAMP);
            haze = new RadialGradient(b.left + w * (1f - gx), b.top + h * (1f - gy), Math.max(1f, w * 0.7f),
                    (secondColour & 0x00FFFFFF) | 0x38000000, secondColour & 0x00FFFFFF, Shader.TileMode.CLAMP);

            cx = b.exactCenterX() + w * shiftX;
            cy = b.top + h * (0.42f + shiftY);
            s = Math.min(w, h) * 0.5f * size;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(s * 0.1f);
            SweepGradient sweep = new SweepGradient(cx, cy, RAINBOW, null);
            Matrix turn = new Matrix();
            turn.setRotate(ringTurn, cx, cy);
            sweep.setLocalMatrix(turn);
            ring.setShader(sweep);
            orbit.setStyle(Paint.Style.STROKE);
            orbit.setStrokeWidth(Math.max(1f, s * 0.022f));
            orbit.setColor(0xB3C4B5FD);
            starGlow = new RadialGradient(cx + s * 0.27f, cy - s * 0.27f, Math.max(1f, s * 0.2f),
                    0xCCFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);

            // The same stars every time for the same app: x, y, radius, alpha
            java.util.Random random = new java.util.Random(seed);
            stars = new float[starCount * 4];
            for (int i = 0; i < starCount; i++) {
                stars[i * 4] = b.left + random.nextFloat() * w;
                stars[i * 4 + 1] = b.top + random.nextFloat() * h;
                stars[i * 4 + 2] = Math.max(0.6f, w * (0.003f + random.nextFloat() * 0.006f));
                stars[i * 4 + 3] = 0.3f + random.nextFloat() * 0.6f;
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(space);
            canvas.drawRect(b, paint);
            paint.setShader(nebula);
            canvas.drawRect(b, paint);
            paint.setShader(haze);
            canvas.drawRect(b, paint);
            paint.setShader(null);
            for (int i = 0; i < stars.length; i += 4) {
                paint.setColor(Color.argb(Math.round(stars[i + 3] * 255), 255, 255, 255));
                canvas.drawCircle(stars[i], stars[i + 1], stars[i + 2], paint);
            }

            float rx = s * 0.6f, ry = s * 0.18f;
            oval.set(-rx, -ry, rx, ry);
            double angle = Math.toRadians(moonAngle);
            float moonX = (float) (rx * Math.cos(angle)), moonY = (float) (ry * Math.sin(angle));
            // The lower half of the orbit is the front, which crosses over the void
            boolean moonInFront = moonY >= 0;

            // The orbit passes behind the void, then its front half crosses over it
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(tilt);
            canvas.drawOval(oval, orbit);
            if (!moonInFront) {
                paint.setColor(0xFFF3EEFF);
                canvas.drawCircle(moonX, moonY, s * 0.055f, paint);
            }
            canvas.restore();

            paint.setColor(0xFF05040F);
            canvas.drawCircle(cx, cy, s * 0.34f, paint);
            canvas.drawCircle(cx, cy, s * 0.39f, ring);

            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(tilt);
            canvas.drawArc(oval, 0, 180, false, orbit);
            if (moonInFront) {
                paint.setColor(0xFFF3EEFF);
                canvas.drawCircle(moonX, moonY, s * 0.055f, paint);
            }
            canvas.restore();

            // The star on the rim, with its glow
            paint.setShader(starGlow);
            canvas.drawCircle(cx + s * 0.27f, cy - s * 0.27f, s * 0.2f, paint);
            paint.setShader(null);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx + s * 0.27f, cy - s * 0.27f, s * 0.07f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
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
