package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterLayoutPolicyTest {

    @Test
    void roomyScreenKeepsTheOriginal290PxPanel() {
        RosterLayoutPolicy.Fit fit = RosterLayoutPolicy.fit(328, 8, 1);
        assertFalse(fit.compact());
        assertEquals(6, fit.rows());
        assertEquals(290, fit.panelH());
    }

    @Test
    void height240GoesCompactAndFitsInsideTheMargins() {
        RosterLayoutPolicy.Fit fit = RosterLayoutPolicy.fit(240, 8, 1);
        assertTrue(fit.compact());
        assertEquals(5, fit.rows());
        assertTrue(fit.panelH() <= 240 - 16, "panel " + fit.panelH());
    }

    @Test
    void aWrappedSubtitleCostsALineOfHeight() {
        RosterLayoutPolicy.Fit one = RosterLayoutPolicy.fit(240, 8, 1);
        RosterLayoutPolicy.Fit two = RosterLayoutPolicy.fit(240, 8, 2);
        assertTrue(two.panelH() <= 240 - 16);
        assertEquals(one.rows() - 1, two.rows());
        // Roomy layout grows by the extra subtitle line.
        assertEquals(300, RosterLayoutPolicy.fit(400, 8, 2).panelH());
    }

    @Test
    void tinyScreenStillShowsOneRow() {
        assertEquals(1, RosterLayoutPolicy.fit(100, 8, 1).rows());
    }

    @Test
    void visibleRowsClampsBetweenOneAndMax() {
        assertEquals(1, RosterLayoutPolicy.visibleRows(50, 100, 18, 6));
        assertEquals(3, RosterLayoutPolicy.visibleRows(160, 100, 18, 6));
        assertEquals(6, RosterLayoutPolicy.visibleRows(1000, 100, 18, 6));
    }

    @Test
    void clampFirstRowKeepsTheLastPageFull() {
        assertEquals(0, RosterLayoutPolicy.clampFirstRow(-3, 10, 4));
        assertEquals(6, RosterLayoutPolicy.clampFirstRow(9, 10, 4));
        assertEquals(2, RosterLayoutPolicy.clampFirstRow(2, 10, 4));
        assertEquals(0, RosterLayoutPolicy.clampFirstRow(5, 3, 4));
    }

    @Test
    void spacingReproducesThe1_1_227OffsetsAndTheFixedHeights() {
        RosterLayoutPolicy.Spacing r = RosterLayoutPolicy.ROOMY;
        int rowsTop = r.titleTop() + r.subtitleGap() + RosterLayoutPolicy.SUBTITLE_LINE_H + r.rowsGap();
        assertEquals(52, rowsTop);
        int linkY = rowsTop + 6 * RosterLayoutPolicy.ROW_H + r.navGap() + r.actionsGap() + r.createGap() + r.linkGap();
        assertEquals(258, linkY);
        assertEquals(182, RosterLayoutPolicy.ROOMY_FIXED_H);
        assertEquals(290, RosterLayoutPolicy.ROOMY_FIXED_H + 6 * RosterLayoutPolicy.ROW_H);
        assertEquals(134, RosterLayoutPolicy.COMPACT_FIXED_H);
        assertEquals(RosterLayoutPolicy.COMPACT, RosterLayoutPolicy.spacing(true));
        assertEquals(RosterLayoutPolicy.ROOMY, RosterLayoutPolicy.spacing(false));
    }

    @Test
    void otherWorldsToggleRowStillFitsRoomyAtScaleFiveWindow() {
        RosterLayoutPolicy.Fit fit = RosterLayoutPolicy.fit(328, 8, 1, RosterLayoutPolicy.TOGGLE_ROW_H);
        assertFalse(fit.compact());
        assertEquals(6, fit.rows());
        assertEquals(290 + RosterLayoutPolicy.TOGGLE_ROW_H, fit.panelH());
    }

    @Test
    void otherWorldsToggleRowWithTwoLineSubtitleGoesCompactAndKeepsRows() {
        RosterLayoutPolicy.Fit fit = RosterLayoutPolicy.fit(328, 8, 2, RosterLayoutPolicy.TOGGLE_ROW_H);
        assertTrue(fit.compact());
        assertTrue(fit.rows() >= 1);
        assertTrue(fit.panelH() <= 328 - 16);
        assertEquals(RosterLayoutPolicy.fit(328, 8, 2), RosterLayoutPolicy.fit(328, 8, 2, 0));
    }
}
