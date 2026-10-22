package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * An ImageView that says when a cover has finished loading (the box art loader sets a
 * placeholder first and the real bitmap later), so the screen can take the cover's colour.
 */
public class CoverImageView extends ImageView {
    public interface OnCoverLoadedListener {
        void onCoverLoaded(Bitmap cover);
    }

    private OnCoverLoadedListener listener;

    public CoverImageView(Context context) {
        super(context);
    }

    public CoverImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CoverImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnCoverLoadedListener(OnCoverLoadedListener listener) {
        this.listener = listener;
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        // While loading, the loader shows a placeholder BitmapDrawable subclass; the real cover
        // arrives through setImageBitmap(), which makes a plain BitmapDrawable
        if (listener != null && drawable != null && drawable.getClass() == BitmapDrawable.class) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) {
                listener.onCoverLoaded(bitmap);
            }
        }
    }
}
