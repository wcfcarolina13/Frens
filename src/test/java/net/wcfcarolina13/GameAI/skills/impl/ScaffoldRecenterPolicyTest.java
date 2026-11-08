package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldRecenterPolicyTest {

    @Test
    void smallDriftOntoAClearColumnIsAllowed() {
        assertTrue(ScaffoldRecenterPolicy.mayForcePosition(1.0, true, true));
        assertTrue(ScaffoldRecenterPolicy.mayForcePosition(4.0, true, true));
    }

    @Test
    void aBotThatWalkedAwayIsNotTeleportedBack() {
        assertFalse(ScaffoldRecenterPolicy.mayForcePosition(15.9 * 15.9, true, true));
    }

    @Test
    void aBlockedColumnTopIsNeverAStand() {
        assertFalse(ScaffoldRecenterPolicy.mayForcePosition(1.0, false, true));
        assertFalse(ScaffoldRecenterPolicy.mayForcePosition(1.0, true, false));
    }
}
