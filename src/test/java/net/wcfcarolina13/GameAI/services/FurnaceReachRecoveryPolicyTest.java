package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static net.wcfcarolina13.GameAI.services.FurnaceReachRecoveryPolicy.Decision.GIVE_UP;
import static net.wcfcarolina13.GameAI.services.FurnaceReachRecoveryPolicy.Decision.WALK_BACK;

class FurnaceReachRecoveryPolicyTest {

    @Test
    void firstLossInSameWorldWalksBack() {
        assertEquals(WALK_BACK, FurnaceReachRecoveryPolicy.onReachLost(true, 0, false));
    }

    @Test
    void secondLossStillWalksBack() {
        assertEquals(WALK_BACK, FurnaceReachRecoveryPolicy.onReachLost(true, 1, false));
    }

    @Test
    void givesUpOnceBudgetIsSpent() {
        assertEquals(GIVE_UP, FurnaceReachRecoveryPolicy.onReachLost(true,
                FurnaceReachRecoveryPolicy.MAX_WALK_BACKS, false));
        assertEquals(GIVE_UP, FurnaceReachRecoveryPolicy.onReachLost(true, 99, false));
    }

    @Test
    void budgetIsTwo() {
        assertEquals(2, FurnaceReachRecoveryPolicy.MAX_WALK_BACKS);
    }

    @Test
    void otherWorldGivesUp() {
        assertEquals(GIVE_UP, FurnaceReachRecoveryPolicy.onReachLost(false, 0, false));
    }

    @Test
    void stopRequestedGivesUp() {
        assertEquals(GIVE_UP, FurnaceReachRecoveryPolicy.onReachLost(true, 0, true));
    }

    @Test
    void negativeCountIsTreatedAsInvalid() {
        assertEquals(GIVE_UP, FurnaceReachRecoveryPolicy.onReachLost(true, -1, false));
    }
}
