package com.limelight.ui;

import static org.junit.Assert.assertEquals;

import android.view.View;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The game screen's chip row keeps the chips that fit whole and leaves out the rest, so the header
 * never grows a line. Plain views stand in for the chips, so sizes are exact pixels.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class ChipRowTest {
    // Three chips of 60, 100 and 40 px, each with 8 px after it
    private static ChipRow row() {
        ChipRow row = new ChipRow(RuntimeEnvironment.getApplication());
        for (int width : new int[] {60, 100, 40}) {
            View chip = new View(row.getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, 30);
            params.setMarginEnd(8);
            row.addView(chip, params);
        }
        return row;
    }

    private static void layOut(ChipRow row, int width, int height) {
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        row.layout(0, 0, width, height);
    }

    @Test
    public void everyChipShowsWhenThereIsRoom() {
        ChipRow row = row();
        // 60 + 8 + 100 + 8 + 40: the last chip's margin may run past the edge
        layOut(row, 216, 40);
        assertEquals(3, row.getShownCount());
        assertEquals(176, row.getChildAt(2).getLeft());
        assertEquals(216, row.getChildAt(2).getRight());
    }

    @Test
    public void chipsThatDontFitAreLeftOutNotSqueezed() {
        ChipRow row = row();
        layOut(row, 200, 40);
        assertEquals(2, row.getShownCount());
        assertEquals(100, row.getChildAt(1).getWidth());
        // The third isn't laid out anywhere on the row, so nothing of it draws
        assertEquals(0, row.getChildAt(2).getWidth());

        // A chip that doesn't fit hides the ones after it too, even small ones that would
        layOut(row, 120, 40);
        assertEquals(1, row.getShownCount());
        assertEquals(0, row.getChildAt(2).getWidth());
    }

    @Test
    public void chipsAreCentredInATallerRow() {
        ChipRow row = row();
        layOut(row, 300, 44);
        assertEquals(7, row.getChildAt(0).getTop());
        assertEquals(37, row.getChildAt(0).getBottom());
    }

    @Test
    public void anEmptyRowKeepsItsMinimumHeight() {
        ChipRow row = new ChipRow(RuntimeEnvironment.getApplication());
        row.setMinimumHeight(34);
        row.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(34, row.getMeasuredHeight());
        assertEquals(0, row.getShownCount());
    }
}
