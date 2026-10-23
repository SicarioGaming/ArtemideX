package com.limelight.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * Cover Glow's one flourish: a slowly flowing rainbow (blue, purple, pink, orange), used only on
 * the Starwire wordmark and the ring around the selected game. Android's animation settings
 * still apply; with animations off the colours simply stay put.
 */
public final class Rainbow {
    // The last colour repeats the first, so the flow loops without a seam
    static final int[] COLORS = {0xFF0894FF, 0xFFC959DD, 0xFFFF2E54, 0xFFFF9004, 0xFF0894FF};
    private static final long TEXT_LOOP_MS = 6000;
    private static final long RING_LOOP_MS = 4000;

    private Rainbow() {}

    /**
     * Paints the text with the flowing rainbow, flowing if the user has animations on (Loops).
     * Cancel the returned animator when done.
     */
    public static ValueAnimator applyTo(TextView view) {
        Matrix matrix = new Matrix();
        LinearGradient[] shader = new LinearGradient[1];
        View.OnLayoutChangeListener sizer = (v, l, t, r, b, ol, ot, or, ob) -> {
            float span = Math.max(1, r - l) * 2f;
            shader[0] = new LinearGradient(0, 0, span, 0, COLORS, null, Shader.TileMode.REPEAT);
            view.getPaint().setShader(shader[0]);
            view.invalidate();
        };
        view.addOnLayoutChangeListener(sizer);
        if (view.getWidth() > 0) {
            sizer.onLayoutChange(view, view.getLeft(), view.getTop(), view.getRight(), view.getBottom(),
                    0, 0, 0, 0);
        }

        ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f);
        flow.setDuration(TEXT_LOOP_MS);
        flow.setRepeatCount(ValueAnimator.INFINITE);
        flow.setInterpolator(new LinearInterpolator());
        flow.addUpdateListener(animation -> {
            if (shader[0] != null) {
                matrix.setTranslate((Float) animation.getAnimatedValue() * view.getWidth() * 2f, 0);
                shader[0].setLocalMatrix(matrix);
                view.invalidate();
            }
        });
        Loops.run(view.getContext(), flow);
        return flow;
    }

    /** A rounded ring whose rainbow slowly turns; it runs only while it's showing. */
    public static final class Ring extends Drawable implements Animatable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private final float radius;
        private final float stroke;
        private ValueAnimator spin;
        private float angle;
        private SweepGradient sweep;

        public Ring(float radiusPx, float strokePx) {
            radius = radiusPx;
            stroke = strokePx;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            sweep = new SweepGradient(b.exactCenterX(), b.exactCenterY(), COLORS, null);
            paint.setShader(sweep);
            rect.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2);
        }

        @Override
        public void draw(Canvas canvas) {
            if (sweep != null) {
                matrix.setRotate(angle, getBounds().exactCenterX(), getBounds().exactCenterY());
                sweep.setLocalMatrix(matrix);
            }
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            if (visible) {
                start();
            }
            else {
                stop();
            }
            return changed;
        }

        @Override
        public void start() {
            if (spin != null || !loopsAllowed()) {
                return;
            }
            spin = ValueAnimator.ofFloat(0f, 360f);
            spin.setDuration(RING_LOOP_MS);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.addUpdateListener(animation -> {
                angle = (Float) animation.getAnimatedValue();
                invalidateSelf();
            });
            spin.start();
        }

        // The ring has no context of its own; the tile it's drawn on does
        private boolean loopsAllowed() {
            Callback host = getCallback();
            return !(host instanceof View) || Loops.allowed(((View) host).getContext());
        }

        @Override
        public void stop() {
            if (spin != null) {
                spin.cancel();
                spin = null;
            }
        }

        @Override
        public boolean isRunning() {
            return spin != null;
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
            return PixelFormat.TRANSLUCENT;
        }
    }
}
