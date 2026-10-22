package com.limelight.ui;

import android.view.View;
import android.widget.AbsListView;
import android.widget.GridView;

/**
 * Lets the game screen's header (the selected game's cover, title and Play button) scroll away
 * with the covers, so a short screen such as a phone held sideways has room for the games.
 *
 * The header sits over the top of the grid, which is padded by the header's height so the first
 * row starts below it. As the grid scrolls, the header moves up with it. Once the Play button
 * has gone, a slim bar with the selected game's name and its own Play button fades in; tapping
 * the bar scrolls back to the top.
 */
public final class CollapsingHeader implements AbsListView.OnScrollListener, View.OnLayoutChangeListener {
    private final View header;
    private final View heroSection;
    private final View miniBar;
    private AbsListView grid;
    private boolean alignPending;

    public CollapsingHeader(View header, View heroSection, View miniBar) {
        this.header = header;
        this.heroSection = heroSection;
        this.miniBar = miniBar;
        header.addOnLayoutChangeListener(this);
        miniBar.setOnClickListener(v -> scrollToTop());
    }

    /** Call with each new grid (the fragment can be replaced). */
    public void attach(AbsListView grid) {
        this.grid = grid;
        grid.setOnScrollListener(this);
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (alignPending && v == this.grid) {
                alignPending = false;
                v.post(this::alignFirstRow);
            }
        });
        fitGridUnderHeader();
    }

    /** Scrolls the grid back to the top so the whole header shows. */
    public void scrollToTop() {
        int scrolled = scrolled();
        if (grid != null && scrolled > 0) {
            grid.smoothScrollBy(-scrolled, 350);
        }
    }

    /** True when part of the header has scrolled away. */
    public boolean isCollapsed() {
        return header.getTranslationY() < 0;
    }

    /** Brings the header back when the controller or keyboard moves into it. */
    public void expandOnFocus(View... views) {
        for (View view : views) {
            view.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && isCollapsed()) {
                    scrollToTop();
                }
            });
        }
    }

    // How far the grid's content has scrolled from its top, in pixels. Rows are all one height,
    // so the first visible row says how many are above it.
    int scrolled() {
        if (grid == null || grid.getChildCount() == 0) {
            return 0;
        }
        View first = grid.getChildAt(0);
        int row = grid.getFirstVisiblePosition();
        int rowHeight = first.getHeight();
        if (grid instanceof GridView) {
            GridView gridView = (GridView) grid;
            row /= Math.max(1, gridView.getNumColumns());
            rowHeight += gridView.getVerticalSpacing();
        }
        return row * rowHeight + grid.getPaddingTop() - first.getTop();
    }

    private void fitGridUnderHeader() {
        int height = header.getHeight();
        if (grid == null || height == 0) {
            return;
        }
        if (grid.getPaddingTop() != height) {
            // A grid at the top stays at the top when the header changes height (a longer
            // title, the first layout); one scrolled down keeps its place
            boolean atTop = scrolled() <= 0;
            grid.setPadding(grid.getPaddingLeft(), height, grid.getPaddingRight(), grid.getPaddingBottom());
            // Aligned once the grid has laid out with its new padding; before that it won't move
            alignPending = atTop;
        }
        update();
    }

    private void alignFirstRow() {
        if (grid == null || grid.getChildCount() == 0 || grid.getFirstVisiblePosition() != 0) {
            return;
        }
        int gap = grid.getChildAt(0).getTop() - grid.getPaddingTop();
        if (gap != 0) {
            grid.scrollListBy(gap);
        }
    }

    private void update() {
        int scrolled = Math.max(0, scrolled());
        header.setTranslationY(-Math.min(scrolled, header.getHeight()));

        // The bar fades in over the last stretch before the Play button goes under it
        float barHeight = miniBar.getHeight() > 0 ? miniBar.getHeight() : 1;
        float start = heroSection.getBottom() - barHeight * 1.75f;
        float progress = Math.max(0f, Math.min(1f, (scrolled - start) / barHeight));
        miniBar.setAlpha(progress);
        miniBar.setTranslationY((1f - progress) * -barHeight * 0.3f);
        miniBar.setVisibility(progress > 0f ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (view == grid) {
            update();
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                               int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (bottom - top != oldBottom - oldTop) {
            fitGridUnderHeader();
        }
    }
}
