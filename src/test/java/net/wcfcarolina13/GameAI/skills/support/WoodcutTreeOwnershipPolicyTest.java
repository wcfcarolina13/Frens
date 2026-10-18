package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the woodcut log ownership filter. No net.minecraft types. */
class WoodcutTreeOwnershipPolicyTest {

    private static final int R = WoodcutTreeOwnershipPolicy.DEFAULT_RADIUS;

    private static boolean owns(int lx, int ly, int lz, List<int[]> others) {
        return WoodcutTreeOwnershipPolicy.ownsLog(lx, ly, lz, 0, 0, R, others);
    }

    @Test
    void trunkLogsAreOwned() {
        assertTrue(owns(0, 64, 0, List.of()));
        assertTrue(owns(0, 70, 0, List.<int[]>of(new int[]{4, 0, 64, 70})));
    }

    @Test
    void branchThreeSidewaysIsOwned() {
        assertTrue(owns(3, 68, 0, List.of()));
        assertTrue(owns(-3, 68, 2, List.<int[]>of(new int[]{8, 8, 64, 70})));
    }

    @Test
    void logSevenAwayIsNotOwned() {
        assertFalse(owns(7, 66, 0, List.of()));
        assertFalse(owns(0, 66, -6, List.of()));
        assertTrue(owns(5, 66, 5, List.of()));
    }

    @Test
    void anotherBaseColumnIsNotOwned() {
        List<int[]> others = List.<int[]>of(new int[]{3, 0, 64, 69});
        assertFalse(owns(3, 64, 0, others));
        assertFalse(owns(3, 70, 0, others));
    }

    @Test
    void logCloserToAnotherBaseIsNotOwned() {
        List<int[]> others = List.<int[]>of(new int[]{4, 0, 64, 70});
        // (3,0): 9 to self, 1 to other.
        assertFalse(owns(3, 68, 0, others));
        // (1,0): 1 to self, 9 to other.
        assertTrue(owns(1, 68, 0, others));
    }

    @Test
    void adjacentBaseIsTheSame2x2Tree() {
        List<int[]> twoByTwo = List.<int[]>of(
                new int[]{1, 0, 64, 75},
                new int[]{0, 1, 64, 75},
                new int[]{1, 1, 64, 75});
        assertTrue(owns(1, 66, 0, twoByTwo));
        assertTrue(owns(1, 66, 1, twoByTwo));
        assertTrue(owns(3, 70, 3, twoByTwo));
    }

    @Test
    void equidistantLogStaysOwned() {
        List<int[]> others = List.<int[]>of(new int[]{4, 0, 64, 70});
        assertTrue(owns(2, 68, 0, others));
        // Within the +1 margin: self 5, other 4, and 4 + 1 is not below 5.
        assertTrue(owns(2, 68, 1, List.<int[]>of(new int[]{4, 1, 64, 70})));
    }
}
