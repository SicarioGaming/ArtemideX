package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Cover Glow: a faint grid of dots behind the screens, for a quiet HUD feel. It's drawn from one
 * small repeating tile, so it costs next to nothing.
 */
public class DotGridView extends View {
    private static final int SPACING_DP = 22;
    private static final float DOT_DP = 1.3f;
    private static final int DOT_COLOR = 0x1FFFFFFF;

    private final Paint paint = new Paint();

    public DotGridView(Context context) {
        this(context, null);
    }

    public DotGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        float d = getResources().getDisplayMetrics().density;
        int spacing = Math.max(4, Math.round(SPACING_DP * d));
        Bitmap tile = Bitmap.createBitmap(spacing, spacing, Bitmap.Config.ARGB_8888);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(DOT_COLOR);
        new Canvas(tile).drawCircle(spacing / 2f, spacing / 2f, DOT_DP * d, dot);
        paint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
