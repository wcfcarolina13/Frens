package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.MiningThreatPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningThreatPolicyTest {

    @Test
    void noHostilesMeansNone() {
        assertEquals(Decision.NONE, MiningThreatPolicy.decide(0, 0));
        assertEquals(Decision.NONE, MiningThreatPolicy.decide(-1, 0));
    }

    @Test
    void engageableHostileMeansEngage() {
        assertEquals(Decision.ENGAGE, MiningThreatPolicy.decide(1, 1));
        assertEquals(Decision.ENGAGE, MiningThreatPolicy.decide(3, 1));
    }

    @Test
    void hostilesTheBotWontFightAreIgnored() {
        // finding 44: a name-tagged, caged or non-aggro mob must not stall mining
        assertEquals(Decision.IGNORE, MiningThreatPolicy.decide(1, 0));
        assertEquals(Decision.IGNORE, MiningThreatPolicy.decide(4, 0));
    }

    @Test
    void engageBecomesIgnoreOnceTheWaitCapIsSpent() {
        long cap = MiningThreatPolicy.ENGAGE_WAIT_CAP_MS;
        assertEquals(Decision.ENGAGE, MiningThreatPolicy.decide(1, 1, 0, 0));
        assertEquals(Decision.ENGAGE, MiningThreatPolicy.decide(1, 1, 0, cap - 1));
        assertEquals(Decision.IGNORE, MiningThreatPolicy.decide(1, 1, 0, cap));
        assertEquals(Decision.IGNORE, MiningThreatPolicy.decide(1, 1, 0, cap * 3));
    }

    @Test
    void capNeverAppliesWhileAnEngageableHostileTargetsTheBot() {
        // a creeper or angry iron golem after the bot: mining on would walk back into it
        long cap = MiningThreatPolicy.ENGAGE_WAIT_CAP_MS;
        assertEquals(Decision.ENGAGE, MiningThreatPolicy.decide(1, 1, 1, cap));
        assertEquals(Decision.ENGAGE, MiningThreatPolicy.decide(3, 2, 1, cap * 10));
        assertEquals(Decision.ENGAGE, MiningThreatPolicy.decide(1, 1, 1, Long.MAX_VALUE));
        // once nothing targets the bot the cap applies again
        assertEquals(Decision.IGNORE, MiningThreatPolicy.decide(3, 2, 0, cap));
    }

    @Test
    void targetingCountDoesNotTurnIgnoreOrNoneIntoEngage() {
        long cap = MiningThreatPolicy.ENGAGE_WAIT_CAP_MS;
        assertEquals(Decision.NONE, MiningThreatPolicy.decide(0, 0, 1, cap));
        assertEquals(Decision.IGNORE, MiningThreatPolicy.decide(2, 0, 1, cap));
    }

    @Test
    void capDoesNotTurnOtherDecisionsIntoEngage() {
        long cap = MiningThreatPolicy.ENGAGE_WAIT_CAP_MS;
        assertEquals(Decision.NONE, MiningThreatPolicy.decide(0, 0, 0, cap));
        assertEquals(Decision.IGNORE, MiningThreatPolicy.decide(2, 0, 0, 0));
    }
}
