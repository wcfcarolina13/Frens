package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectSchedulePolicyTest {

    @Test
    void startedTravelKeepsTheWithdraw() {
        assertTrue(CollectSchedulePolicy.keepAction(true, false));
        assertTrue(CollectSchedulePolicy.keepAction(true, true));
    }

    @Test
    void cooldownRetryKeepsTheWithdraw() {
        assertTrue(CollectSchedulePolicy.keepAction(false, true));
    }

    @Test
    void refusedTravelWithNoRetryDropsTheWithdraw() {
        // Combat, food, artifact tier, mount: nothing will carry the withdraw to its chest.
        assertFalse(CollectSchedulePolicy.keepAction(false, false));
    }

    @Test
    void withdrawTypesAreRecognised() {
        assertTrue(CollectSchedulePolicy.isWithdraw("withdraw:stay"));
        assertTrue(CollectSchedulePolicy.isWithdraw("withdraw:home"));
        assertFalse(CollectSchedulePolicy.isWithdraw("retrieve:sender"));
        assertFalse(CollectSchedulePolicy.isWithdraw("skill_resume:bot skill woodcut Smokey"));
        assertFalse(CollectSchedulePolicy.isWithdraw(null));
    }

    @Test
    void replacingAWithdrawAtAnotherChestIsReported() {
        assertTrue(CollectSchedulePolicy.replacedDifferentTarget("withdraw:stay",
                new int[] {1, 64, 1}, new int[] {1, 64, 2}));
        assertTrue(CollectSchedulePolicy.replacedDifferentTarget("withdraw:player",
                new int[] {-5, 70, 9}, new int[] {5, 70, 9}));
    }

    @Test
    void sameChestIsNotAReplacement() {
        assertFalse(CollectSchedulePolicy.replacedDifferentTarget("withdraw:stay",
                new int[] {1, 64, 1}, new int[] {1, 64, 1}));
        // A new return mode for the same chest is still the same collect.
        assertFalse(CollectSchedulePolicy.replacedDifferentTarget("withdraw:home",
                new int[] {1, 64, 1}, new int[] {1, 64, 1}));
    }

    @Test
    void nonWithdrawOrMissingPreviousIsNotAReplacement() {
        assertFalse(CollectSchedulePolicy.replacedDifferentTarget(null, null, new int[] {1, 64, 1}));
        assertFalse(CollectSchedulePolicy.replacedDifferentTarget("retrieve:sender",
                new int[] {0, 64, 0}, new int[] {1, 64, 1}));
        assertFalse(CollectSchedulePolicy.replacedDifferentTarget("skill_resume:x", null, new int[] {1, 64, 1}));
        assertFalse(CollectSchedulePolicy.replacedDifferentTarget("withdraw:stay", null, new int[] {1, 64, 1}));
        assertFalse(CollectSchedulePolicy.replacedDifferentTarget("withdraw:stay", new int[] {1, 64, 1}, null));
    }
}
