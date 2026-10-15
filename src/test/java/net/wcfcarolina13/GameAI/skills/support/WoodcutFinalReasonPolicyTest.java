package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the woodcut run's final reason choice (1.1.240). No net.minecraft types. */
class WoodcutFinalReasonPolicyTest {

    @Test
    void refusalSetsTheReasonWhenNothingElseHas() {
        assertTrue(WoodcutFinalReasonPolicy.selectionRefusalSetsReason("NO_TARGET"));
        assertTrue(WoodcutFinalReasonPolicy.selectionRefusalSetsReason(null));
    }

    @Test
    void pathFailureKeepsItsOwnLine() {
        assertFalse(WoodcutFinalReasonPolicy.selectionRefusalSetsReason("PATH_OR_REACH_FAILURE"));
    }

    @Test
    void otherRecordedFailuresAreNotOverwritten() {
        assertFalse(WoodcutFinalReasonPolicy.selectionRefusalSetsReason("INVENTORY_BLOCKED"));
        assertFalse(WoodcutFinalReasonPolicy.selectionRefusalSetsReason("PILLAR_NO_REACH"));
        assertFalse(WoodcutFinalReasonPolicy.selectionRefusalSetsReason("TRUNK_NEVER_STARTED"));
    }

    @Test
    void anEarlierProtectionRefusalStaysProtection() {
        // Already protection: leaving it unchanged keeps the same line.
        assertFalse(WoodcutFinalReasonPolicy.selectionRefusalSetsReason("PROTECTED_AT_MINING"));
    }
}
