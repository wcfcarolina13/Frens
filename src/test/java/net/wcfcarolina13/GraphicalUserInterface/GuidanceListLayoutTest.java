package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidanceListLayoutTest {

    @Test
    void rowsStopAboveTheWarningLineAndConfirm() {
        int height = 240;
        int topY = 50;
        int step = 22;
        int visible = GuidanceListLayout.visibleRows(topY, height - 84, step);
        int lastRowBottom = topY + (visible - 1) * step + 20;
        assertTrue(lastRowBottom <= height - 80, "last row must end above the warning line");
        assertTrue(lastRowBottom < height - 60, "last row must end above Confirm");
    }

    @Test
    void atLeastOneRowOnATinyScreen() {
        assertEquals(1, GuidanceListLayout.visibleRows(50, 40, 22));
    }

    @Test
    void windowFollowsTheSelection() {
        assertEquals(0, GuidanceListLayout.clampOffset(0, 2, 10, 5));
        assertEquals(3, GuidanceListLayout.clampOffset(0, 7, 10, 5));
        assertEquals(2, GuidanceListLayout.clampOffset(4, 2, 10, 5));
        assertEquals(5, GuidanceListLayout.clampOffset(9, 9, 10, 5));
        assertEquals(0, GuidanceListLayout.clampOffset(3, 0, 3, 5));
    }
}
