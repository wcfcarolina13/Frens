package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningTickPolicyTest {

    @Test
    void goneBlockCompletesEvenWhenTheClaimRefuses() {
        // The log broke on the previous tick; air is not a tree, so the claim now says no.
        assertEquals(MiningTickPolicy.Verdict.COMPLETE, MiningTickPolicy.verdict(true, false));
    }

    @Test
    void goneBlockCompletesWhenTheClaimAllows() {
        assertEquals(MiningTickPolicy.Verdict.COMPLETE, MiningTickPolicy.verdict(true, true));
    }

    @Test
    void presentBlockInARefusedClaimIsRefused() {
        assertEquals(MiningTickPolicy.Verdict.REFUSE_CLAIM, MiningTickPolicy.verdict(false, false));
    }

    @Test
    void presentBlockInAnAllowedClaimContinues() {
        assertEquals(MiningTickPolicy.Verdict.CONTINUE, MiningTickPolicy.verdict(false, true));
    }
}
