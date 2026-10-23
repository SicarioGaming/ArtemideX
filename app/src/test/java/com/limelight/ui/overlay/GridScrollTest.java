package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Where the in-stream menu's grid scrolls when the controller moves the selection to another
 * row. The grid fades out at an edge with more tools beyond, so the selected row stops clear of
 * that fade. Rows here are 100 px with 10 px between them, the grid shows 250 px of its 540 and
 * fades over 30 px.
 */
public class GridScrollTest {
    private static final int HEIGHT = 250;
    private static final int CONTENT = 540;
    private static final int FADE = 30;

    // Row n runs from 110 * n to 110 * n + 100
    private static int show(int row, int scrollY) {
        return OverlayMenuView.scrollToShow(110 * row, 110 * row + 100, scrollY, HEIGHT, CONTENT, FADE);
    }

    @Test
    public void aRowThatShowsClearOfTheFadeStaysPut() {
        assertEquals(0, show(0, 0));
        assertEquals(0, show(1, 0));
    }

    @Test
    public void movingDownStopsWithTheRowAboveTheFade() {
        // Row 2 ends at 320, plus the fade below it: 350, so 100 px down
        assertEquals(100, show(2, 0));
        // The last row needs no fade below, and the grid scrolls only as far as it goes
        assertEquals(CONTENT - HEIGHT, show(4, 100));
    }

    @Test
    public void movingUpStopsWithTheRowBelowTheFade() {
        // Row 1 starts at 110, less the fade above it: 80
        assertEquals(80, show(1, CONTENT - HEIGHT));
        // The first row needs no fade above
        assertEquals(0, show(0, 80));
    }

    @Test
    public void aGridThatFitsDoesNotScroll() {
        assertEquals(0, OverlayMenuView.scrollToShow(110, 210, 0, 600, CONTENT, FADE));
    }
}
