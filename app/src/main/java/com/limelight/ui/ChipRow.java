package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The game screen's row of stream-setting chips. The chips that don't fit whole are left out:
 * a plain LinearLayout squeezed the last one until its text wrapped, which made the header taller
 * and moved the covers under it.
 *
 * Chips are laid out from the start in a single row, centred vertically.
 */
public class ChipRow extends LinearLayout {
    // How many chips, from the first, fit whole in the last measure
    private int shown;

    public ChipRow(Context context) {
        this(context, null);
    }

    public ChipRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
    }

    /** How many chips are showing; the rest didn't fit. */
    public int getShownCount() {
        return shown;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int room = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int unlimited = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int used = 0;
        int tallest = 0;
        shown = 0;
        boolean full = false;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            measureChildWithMargins(child, unlimited, 0, heightMeasureSpec, 0);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            // The last chip's end margin may run past the edge; its own width may not
            int end = used + params.getMarginStart() + child.getMeasuredWidth();
            if (full || child.getVisibility() == GONE || end > room) {
                full |= child.getVisibility() != GONE;
                continue;
            }
            used = end + params.getMarginEnd();
            tallest = Math.max(tallest, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
            shown = i + 1;
        }
        int width = Math.min(used, room) + getPaddingLeft() + getPaddingRight();
        int height = Math.max(tallest + getPaddingTop() + getPaddingBottom(), getSuggestedMinimumHeight());
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int x = rtl ? r - l - getPaddingRight() : getPaddingLeft();
        int top = getPaddingTop();
        int inner = b - t - getPaddingTop() - getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (i >= shown || child.getVisibility() == GONE) {
                // Left out: no room on screen, so nothing of it draws
                child.layout(0, 0, 0, 0);
                continue;
            }
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int y = top + params.topMargin + (inner - params.topMargin - params.bottomMargin - height) / 2;
            if (rtl) {
                x -= params.getMarginStart();
                child.layout(x - width, y, x, y + height);
                x -= width + params.getMarginEnd();
            }
            else {
                x += params.getMarginStart();
                child.layout(x, y, x + width, y + height);
                x += width + params.getMarginEnd();
            }
        }
    }
}
