package net.wcfcarolina13.GameAI.skills.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure tests for the relocation hop decision. No net.minecraft types. */
class RelocationWalkPolicyTest {

    @Test
    void progressOfAtLeastThreeBlocksEarnsAnotherHop() {
        // Field bug D: 96 blocks out, the walk got ~28 blocks closer before its deadline.
        assertTrue(RelocationWalkPolicy.continueToward(96.0, 68.0, 0));
        assertTrue(RelocationWalkPolicy.continueToward(10.0, 7.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(10.0, 7.1, 0));
    }

    @Test
    void noProgressOrMovingAwayStops() {
        assertFalse(RelocationWalkPolicy.continueToward(40.0, 40.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(40.0, 45.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(Double.NaN, 10.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(40.0, Double.NaN, 0));
    }

    @Test
    void hopCapEndsTheLoop() {
        assertTrue(RelocationWalkPolicy.continueToward(96.0, 68.0, RelocationWalkPolicy.MAX_EXTRA_HOPS - 1));
        assertFalse(RelocationWalkPolicy.continueToward(96.0, 68.0, RelocationWalkPolicy.MAX_EXTRA_HOPS));
        assertFalse(RelocationWalkPolicy.continueToward(96.0, 68.0, -1));
    }
}
