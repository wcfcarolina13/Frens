package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeStuckDropPolicyTest {

    @Test
    void aSafeDropCostsNoHealth() {
        assertTrue(TreeStuckDropPolicy.isSafeDrop(0));
        assertTrue(TreeStuckDropPolicy.isSafeDrop(3));
        assertFalse(TreeStuckDropPolicy.isSafeDrop(4), "4 blocks cost a heart (sandbox 2026-11-09)");
        assertFalse(TreeStuckDropPolicy.isSafeDrop(-1));
    }

    @Test
    void sidewaysMomentumIsDroppedOnlyOverTheChosenColumn() {
        assertTrue(TreeStuckDropPolicy.holdOverTarget(5, 7, 5, 7, 0.2, 0.0));
        assertFalse(TreeStuckDropPolicy.holdOverTarget(4, 7, 5, 7, 0.2, 0.0), "not there yet");
        assertFalse(TreeStuckDropPolicy.holdOverTarget(5, 7, 5, 7, 0.0, 0.0), "already still");
    }
}
