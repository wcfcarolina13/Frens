package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoatFootingPolicyTest {

    // A fren standing in block (10, 64, 20), box 10.8..11.4 on x so it overlaps column 11 too.
    private static boolean under(boolean centreSupported, int x, int y, int z) {
        return MoatFootingPolicy.isUnderFeet(10, 20, 64, centreSupported, 10.8, 11.4, 20.2, 20.8, x, y, z);
    }

    @Test
    void ownColumnBelowTheFeetIsAlwaysFooting() {
        assertTrue(under(true, 10, 63, 20));
        assertTrue(under(true, 10, 61, 20));
        assertTrue(under(false, 10, 63, 20));
    }

    @Test
    void cellsAtOrAboveTheFeetAreNot() {
        assertFalse(under(false, 10, 64, 20));
        assertFalse(under(false, 11, 65, 20));
    }

    @Test
    void overlappedNeighbourIsDiggableWhileTheOwnColumnHoldsTheFren() {
        assertFalse(under(true, 11, 63, 20));
    }

    @Test
    void overlappedNeighbourIsFootingWhenTheOwnColumnIsOpen() {
        assertTrue(under(false, 11, 63, 20));
        assertFalse(under(false, 12, 63, 20));
        assertFalse(under(false, 11, 63, 21));
    }
}
