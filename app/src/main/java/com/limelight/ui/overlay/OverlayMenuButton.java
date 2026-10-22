package com.limelight.ui.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Custom view representing a single button in the overlay menu.
 *
 * Cover Glow styles: a square tile (icon above its label) for the tools grid, and pills for the
 * bar at the bottom of the sheet, where the primary pill is white. The selected button (by
 * controller or keyboard) turns white with dark text.
 */
public class OverlayMenuButton extends LinearLayout {
    public static final int STYLE_ROW = 0;
    public static final int STYLE_TILE = 1;
    public static final int STYLE_PILL = 2;
    public static final int STYLE_PRIMARY = 3;

    private static final int ICON_SIZE_DP = 24;
    private static final int PADDING_DP = 12;
    private static final int TEXT_SIZE_SP = 12;
    private static final int CORNER_RADIUS_DP = 8;

    private static final int DARK = 0xFF0B0F14;
    private static final int FROSTED = 0x1AFFFFFF;
    private static final int FROSTED_PILL = 0x24FFFFFF;
    private static final int RING = 0xFFC4B5FD;

    private ImageView iconView;
    private TextView labelView;
    private GradientDrawable background;
    private boolean isSelected = false;
    private int strokeWidth;
    private int style = STYLE_ROW;

    public OverlayMenuButton(Context context) {
        super(context);
        init(context);
    }

    public OverlayMenuButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OverlayMenuButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        float density = context.getResources().getDisplayMetrics().density;
        int padding = (int) (PADDING_DP * density);
        int iconSize = (int) (ICON_SIZE_DP * density);

        // Cache stroke width to avoid recalculating on every appearance update
        strokeWidth = (int) (3 * density);

        setPadding(padding, padding, padding, padding);
        setMinimumHeight(iconSize + 2 * padding);
        setClickable(true);
        setFocusable(true);

        // Create background drawable
        background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(CORNER_RADIUS_DP * density);
        background.setColor(0xD9000000); // Semi-transparent black
        setBackground(background);

        // Create icon view
        iconView = new ImageView(context);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER_VERTICAL;
        iconView.setLayoutParams(iconParams);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(iconView);

        // Create label view
        labelView = new TextView(context);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.leftMargin = (int) (8 * density);
        labelView.setLayoutParams(labelParams);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
        labelView.setTextColor(Color.WHITE);
        labelView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        addView(labelView);
    }

    /** Switches to one of the Cover Glow styles (STYLE_TILE, STYLE_PILL, STYLE_PRIMARY). */
    public void setStyle(int style) {
        this.style = style;
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) labelView.getLayoutParams();
        labelView.setTypeface(Typeface.DEFAULT_BOLD);

        if (style == STYLE_TILE) {
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            int padding = (int) (8 * density);
            setPadding(padding, (int) (12 * density), padding, (int) (10 * density));
            setMinimumHeight((int) (82 * density));
            background.setCornerRadius(18 * density);
            ((LinearLayout.LayoutParams) iconView.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
            labelParams.leftMargin = 0;
            labelParams.topMargin = iconView.getVisibility() == GONE ? 0 : (int) (8 * density);
            labelParams.gravity = Gravity.CENTER_HORIZONTAL;
            labelView.setGravity(Gravity.CENTER);
            labelView.setMaxLines(2);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
        else if (style == STYLE_PILL || style == STYLE_PRIMARY) {
            setGravity(Gravity.CENTER);
            int padding = (int) (18 * density);
            setPadding(padding, 0, padding, 0);
            setMinimumHeight((int) (52 * density));
            background.setCornerRadius(999 * density);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style == STYLE_PRIMARY ? 16 : 15);
            labelView.setMaxLines(1);
        }
        labelView.setLayoutParams(labelParams);
        updateAppearance();
    }

    /**
     * Set the button icon from a drawable resource ID
     */
    public void setIcon(int iconResId) {
        Drawable icon = getContext().getResources().getDrawable(iconResId);
        iconView.setImageDrawable(icon);
    }

    /**
     * Set the button icon from a drawable
     */
    public void setIcon(Drawable icon) {
        iconView.setImageDrawable(icon);
    }

    /**
     * Set the button label text
     */
    public void setLabel(String text) {
        labelView.setText(text);
        labelView.setVisibility(text != null && !text.isEmpty() ? VISIBLE : GONE);
    }

    /**
     * Set whether this button is currently selected
     */
    public void setSelected(boolean selected) {
        // Skip redundant updates to avoid unnecessary redraws
        if (isSelected == selected) {
            return;
        }
        isSelected = selected;
        updateAppearance();
    }

    @Override
    public boolean isSelected() {
        return isSelected;
    }

    /**
     * Update the visual appearance based on selection state
     */
    private void updateAppearance() {
        if (style == STYLE_ROW) {
            if (isSelected) {
                // Highlighted state - brighter background
                background.setColor(0xC0FFFFFF); // Semi-transparent white
                background.setStroke(strokeWidth, 0xFFFFFFFF); // White border
                setContentColor(0xFF000000);
            } else {
                background.setColor(0xD9000000); // Semi-transparent black
                background.setStroke(0, 0); // No border
                setContentColor(0xFFFFFFFF);
            }
            return;
        }

        if (style == STYLE_PRIMARY) {
            // Always white; a teal ring shows when it is selected
            background.setColor(0xFFFFFFFF);
            background.setStroke(isSelected ? strokeWidth : 0, RING);
            setContentColor(DARK);
        }
        else if (isSelected) {
            background.setColor(0xFFFFFFFF);
            background.setStroke(0, 0);
            setContentColor(DARK);
        }
        else {
            background.setColor(style == STYLE_TILE ? FROSTED : FROSTED_PILL);
            background.setStroke(0, 0);
            setContentColor(0xFFFFFFFF);
        }
    }

    private void setContentColor(int color) {
        iconView.setImageTintList(ColorStateList.valueOf(color));
        labelView.setTextColor(color);
    }

    public void hideIcon() {
        iconView.setVisibility(GONE);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) labelView.getLayoutParams();
        params.leftMargin = 0;
        params.topMargin = 0;
        labelView.setLayoutParams(params);
    }

    public static OverlayMenuButton create(Context context, int iconResId, String label) {
        OverlayMenuButton button = new OverlayMenuButton(context);
        if (iconResId != 0) {
            button.setIcon(iconResId);
        } else {
            button.hideIcon();
        }
        button.setLabel(label);
        return button;
    }

    public static OverlayMenuButton create(Context context, int iconResId, String label, int style) {
        OverlayMenuButton button = create(context, iconResId, label);
        button.setStyle(style);
        return button;
    }
}
