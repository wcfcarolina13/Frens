package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoatFootingPolicyTest {

    // A fren centred on block (10, 64, 20): box 10.2..10.8 on x, 20.2..20.8 on z.
    private static boolean under(int x, int y, int z) {
        return MoatFootingPolicy.isUnderFeet(10.2, 10.8, 20.2, 20.8, 64, x, y, z);
    }

    @Test
    void cellsBelowTheFeetAreFooting() {
        assertTrue(under(10, 63, 20));
        assertTrue(under(10, 61, 20));
    }

    @Test
    void cellsAtOrAboveTheFeetAreNot() {
        assertFalse(under(10, 64, 20));
        assertFalse(under(10, 65, 20));
    }

    @Test
    void neighbouringColumnsAreNotFootingWhenCentred() {
        assertFalse(under(11, 63, 20));
        assertFalse(under(9, 63, 20));
        assertFalse(under(10, 63, 21));
    }

    @Test
    void standingOnAnEdgeCountsBothColumns() {
        // Box 10.8..11.4 straddles columns 10 and 11.
        assertTrue(MoatFootingPolicy.isUnderFeet(10.8, 11.4, 20.2, 20.8, 64, 10, 63, 20));
        assertTrue(MoatFootingPolicy.isUnderFeet(10.8, 11.4, 20.2, 20.8, 64, 11, 63, 20));
        assertFalse(MoatFootingPolicy.isUnderFeet(10.8, 11.4, 20.2, 20.8, 64, 12, 63, 20));
    }
}
