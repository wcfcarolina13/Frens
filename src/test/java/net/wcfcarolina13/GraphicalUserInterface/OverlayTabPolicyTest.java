package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayTabPolicyTest {

    /** 5 px per code point, close to Minecraft's average glyph. */
    private static final ToIntFunction<String> W5 = s -> s.codePointCount(0, s.length()) * 5;
    private static final int PAD = 3;

    private static List<OverlayTabPolicy.Tab> iconRow() {
        return List.of(
                OverlayTabPolicy.Tab.flexible("Actions", "Actions", "Do"),
                OverlayTabPolicy.Tab.flexible("Dialogue", "Dialogue", "Talk"),
                OverlayTabPolicy.Tab.fixed("✦", "Spells", 15),
                OverlayTabPolicy.Tab.flexible("Admin", "Admin"));
    }

    private static int sum(int[] widths) {
        int total = 0;
        for (int w : widths) {
            total += w;
        }
        return total;
    }

    @Test
    void wideRowKeepsEqualTabsAndFullLabels() {
        // 15 fixed + 3 * 60 = 195. "Dialogue" is 40 + 6 = 46, fits 60.
        OverlayTabPolicy.Plan plan = OverlayTabPolicy.plan(iconRow(), 195, PAD, W5);
        assertEquals(List.of("Actions", "Dialogue", "✦", "Admin"), plan.labels());
        assertArrayEquals(new int[] {60, 60, 15, 60}, plan.widths());
    }

    @Test
    void equalShareMatchesOldFloorDivision() {
        // 15 + 151 free: old code gave 151 / 3 = 50 each and left 1 px unused.
        OverlayTabPolicy.Plan plan = OverlayTabPolicy.plan(iconRow(), 166, PAD, W5);
        assertArrayEquals(new int[] {50, 50, 15, 50}, plan.widths());
    }

    @Test
    void mediumRowSizesTabsByLabel() {
        // Free 135: equal 45 < Dialogue 46. Natural 41 + 46 + 31 = 118, spare 17.
        OverlayTabPolicy.Plan plan = OverlayTabPolicy.plan(iconRow(), 150, PAD, W5);
        assertEquals(List.of("Actions", "Dialogue", "✦", "Admin"), plan.labels());
        assertArrayEquals(new int[] {41 + 6, 46 + 6, 15, 31 + 5}, plan.widths());
        assertEquals(150, sum(plan.widths()));
        assertFalse(plan.isShortened(0));
        assertTrue(plan.isShortened(2), "icon tab always offers its full name");
    }

    @Test
    void narrowRowFallsBackToShortLabels() {
        // Free 100 < 118 natural, short set: Do 16 + Talk 26 + Admin 31 = 73.
        OverlayTabPolicy.Plan plan = OverlayTabPolicy.plan(iconRow(), 115, PAD, W5);
        assertEquals(List.of("Do", "Talk", "✦", "Admin"), plan.labels());
        assertEquals(115, sum(plan.widths()));
        assertTrue(plan.isShortened(0));
        assertTrue(plan.isShortened(1));
        assertFalse(plan.isShortened(3));
    }

    @Test
    void tinyRowElidesAndNeverOverflows() {
        OverlayTabPolicy.Plan plan = OverlayTabPolicy.plan(iconRow(), 60, PAD, W5);
        assertTrue(sum(plan.widths()) <= 60);
        for (int i = 0; i < 4; i++) {
            if (i == 2) {
                continue;
            }
            assertTrue(W5.applyAsInt(plan.labels().get(i)) <= plan.widths()[i] - 2 * PAD || plan.labels().get(i).isEmpty(),
                    "label " + i + " fits its tab");
        }
    }

    @Test
    void fourTextTabsWhenSpellsActive() {
        List<OverlayTabPolicy.Tab> row = List.of(
                OverlayTabPolicy.Tab.flexible("Actions", "Actions", "Do"),
                OverlayTabPolicy.Tab.flexible("Dialogue", "Dialogue", "Talk"),
                OverlayTabPolicy.Tab.flexible("Spells", "Spells"),
                OverlayTabPolicy.Tab.flexible("Admin", "Admin"));
        OverlayTabPolicy.Plan wide = OverlayTabPolicy.plan(row, 200, PAD, W5);
        assertArrayEquals(new int[] {50, 50, 50, 50}, wide.widths());
        OverlayTabPolicy.Plan narrow = OverlayTabPolicy.plan(row, 140, PAD, W5);
        assertEquals(140, sum(narrow.widths()));
        assertEquals("Do", narrow.labels().get(0));
        assertEquals("Spells", narrow.labels().get(2));
    }

    @Test
    void fixedTabKeepsItsWidthInEveryPath() {
        for (int available : new int[] {300, 150, 115, 60}) {
            assertEquals(15, OverlayTabPolicy.plan(iconRow(), available, PAD, W5).widths()[2], "at " + available);
        }
    }
}
