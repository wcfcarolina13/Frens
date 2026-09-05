package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldSlotPolicyTest {

    @Test
    void slotAlreadyInHotbarIsReturnedUnchangedEvenWhenLocked() {
        assertEquals(0, ScaffoldSlotPolicy.resolveHotbarTarget(0, -1, true));
        assertEquals(8, ScaffoldSlotPolicy.resolveHotbarTarget(8, 3, true));
        assertEquals(5, ScaffoldSlotPolicy.resolveHotbarTarget(5, -1, false));
    }

    @Test
    void lockedHotbarRejectsSlotsOutsideTheHotbar() {
        assertEquals(-1, ScaffoldSlotPolicy.resolveHotbarTarget(9, 4, true));
        assertEquals(-1, ScaffoldSlotPolicy.resolveHotbarTarget(35, -1, true));
    }

    @Test
    void unlockedHotbarSwapsIntoFirstEmptySlot() {
        assertEquals(4, ScaffoldSlotPolicy.resolveHotbarTarget(20, 4, false));
        assertEquals(0, ScaffoldSlotPolicy.resolveHotbarTarget(20, 0, false));
    }

    @Test
    void unlockedFullHotbarFallsBackToSlotZero() {
        assertEquals(0, ScaffoldSlotPolicy.resolveHotbarTarget(20, -1, false));
    }

    @Test
    void unlockedFullHotbarWithPreferredSlotOverloadPrefersThatSlot() {
        assertEquals(3, ScaffoldSlotPolicy.resolveHotbarTarget(20, -1, false, 3));
    }

    @Test
    void preferredSlotOverloadClampsOutOfRangeValues() {
        assertEquals(8, ScaffoldSlotPolicy.resolveHotbarTarget(20, -1, false, 12));
        assertEquals(0, ScaffoldSlotPolicy.resolveHotbarTarget(20, -1, false, -4));
    }

    @Test
    void preferredSlotOverloadIgnoresPreferenceWhenEmptySlotExists() {
        assertEquals(4, ScaffoldSlotPolicy.resolveHotbarTarget(20, 4, false, 7));
    }

    @Test
    void cooldownAppliesWhenRecoveryFailed() {
        assertTrue(ScaffoldSlotPolicy.shouldApplyEscapeCooldown(false, false));
        assertTrue(ScaffoldSlotPolicy.shouldApplyEscapeCooldown(false, true));
    }

    @Test
    void cooldownAppliesWhenRecoveryClaimedSuccessButBotIsStillBelowSurface() {
        assertTrue(ScaffoldSlotPolicy.shouldApplyEscapeCooldown(true, false));
    }

    @Test
    void noCooldownOnGenuineSuccess() {
        assertFalse(ScaffoldSlotPolicy.shouldApplyEscapeCooldown(true, true));
    }
}
