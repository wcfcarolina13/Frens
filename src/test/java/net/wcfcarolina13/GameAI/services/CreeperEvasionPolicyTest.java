package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CreeperEvasionPolicy.Decision;
import net.wcfcarolina13.GameAI.services.CreeperEvasionPolicy.FuseState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreeperEvasionPolicyTest {

    private static Decision decide(double dist, boolean armed, boolean charged, FuseState fuse) {
        return CreeperEvasionPolicy.decide(dist, armed, charged, fuse, 1.0D);
    }

    // ---- out of engagement range ----

    @Test
    void normalCreeperBeyondSixBlocksIsIgnored() {
        assertEquals(Decision.STAY, decide(6.1D, true, false, FuseState.NONE));
        assertEquals(Decision.STAY, decide(6.1D, false, false, FuseState.SWELLING));
    }

    @Test
    void chargedCreeperUsesTheWiderTwelveBlockEngagementRadius() {
        assertEquals(Decision.FLEE_SPRINT, decide(11.9D, true, true, FuseState.NONE));
        assertEquals(Decision.STAY, decide(12.1D, true, true, FuseState.NONE));
    }

    // ---- unarmed: unchanged, always sprint away ----

    @Test
    void unarmedBotAlwaysSprintsAwayInsideEngagementRange() {
        assertEquals(Decision.FLEE_SPRINT, decide(1.0D, false, false, FuseState.NONE));
        assertEquals(Decision.FLEE_SPRINT, decide(4.0D, false, false, FuseState.SWELLING));
        assertEquals(Decision.FLEE_SPRINT, decide(5.9D, false, false, FuseState.IGNITED));
    }

    // ---- armed, dormant creeper: today's block-and-shield behaviour preserved ----

    @Test
    void armedBotHoldsGroundAgainstADormantNormalCreeperPointBlank() {
        assertEquals(Decision.STAY, decide(4.5D, true, false, FuseState.NONE));
        assertEquals(Decision.STAY, decide(1.0D, true, false, FuseState.NONE));
    }

    @Test
    void armedBotFleesADormantNormalCreeperOutsideBlockAndShieldRange() {
        assertEquals(Decision.FLEE_SPRINT, decide(4.6D, true, false, FuseState.NONE));
    }

    @Test
    void armedBotNeverHoldsGroundAgainstAChargedCreeper() {
        assertEquals(Decision.FLEE_SPRINT, decide(1.0D, true, true, FuseState.NONE));
        assertEquals(Decision.FLEE_SPRINT, decide(4.0D, true, true, FuseState.NONE));
    }

    // ---- the new rule: armed + fusing + inside blast radius ----

    @Test
    void armedBotBacksAwayFromASwellingCreeperInsideBlastRadius() {
        assertEquals(Decision.BACK_AWAY, decide(3.0D, true, false, FuseState.SWELLING));
        assertEquals(Decision.BACK_AWAY, decide(4.5D, true, false, FuseState.SWELLING));
    }

    @Test
    void armedBotBacksAwayFromAnIgnitedCreeperInsideBlastRadius() {
        assertEquals(Decision.BACK_AWAY, decide(2.0D, true, false, FuseState.IGNITED));
    }

    @Test
    void theNewRuleAlsoAppliesToChargedCreepersAtPointBlank() {
        assertEquals(Decision.BACK_AWAY, decide(2.0D, true, true, FuseState.IGNITED));
    }

    @Test
    void aFusingCreeperOutsideTheBackAwayRadiusStillMakesAnArmedBotSprint() {
        assertEquals(Decision.FLEE_SPRINT, decide(4.6D, true, false, FuseState.SWELLING));
        assertEquals(Decision.FLEE_SPRINT, decide(5.5D, true, false, FuseState.IGNITED));
    }

    // ---- invariants ----

    @Test
    void theBackAwayRadiusCoversTheVanillaBlastRadius() {
        assertEquals(true,
                CreeperEvasionPolicy.ARMED_FUSE_BACK_AWAY_RADIUS >= CreeperEvasionPolicy.BLAST_RADIUS);
    }

    @Test
    void healthIsRecordedButDoesNotChangeTodaysThresholds() {
        assertEquals(CreeperEvasionPolicy.decide(2.0D, true, false, FuseState.NONE, 1.0D),
                CreeperEvasionPolicy.decide(2.0D, true, false, FuseState.NONE, 0.1D));
        assertEquals(CreeperEvasionPolicy.decide(5.0D, true, false, FuseState.NONE, 1.0D),
                CreeperEvasionPolicy.decide(5.0D, true, false, FuseState.NONE, 0.1D));
    }

    @Test
    void aNullFuseStateIsTreatedAsDormant() {
        assertEquals(Decision.STAY, decide(2.0D, true, false, null));
    }
}
