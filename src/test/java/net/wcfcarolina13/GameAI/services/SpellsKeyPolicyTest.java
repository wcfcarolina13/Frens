package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpellsKeyPolicyTest {

    @Test
    void lookedAtBotWinsInEveryMode() {
        assertEquals("Bob", SpellsKeyPolicy.chooseTarget("Bob", false, false, "Jake"));
        assertEquals("Bob", SpellsKeyPolicy.chooseTarget("Bob", true, true, "Jake"));
        assertEquals("Bob", SpellsKeyPolicy.chooseTarget("  Bob ", true, false, "Jake"));
    }

    @Test
    void questingOffNeverSendsTheDefaultJakeAlias() {
        // Finding 30: with Questing off "bot open Jake" was NO_SUCH_BOT.
        assertEquals("", SpellsKeyPolicy.chooseTarget(null, false, false, "Jake"));
        assertEquals("", SpellsKeyPolicy.chooseTarget("", false, true, "Jake"));
        assertEquals("", SpellsKeyPolicy.chooseTarget("   ", false, false, "Jake"));
    }

    @Test
    void questingOnUsesTheCompanionOnlyOnceRecruited() {
        assertEquals("Jake", SpellsKeyPolicy.chooseTarget(null, true, true, "Jake"));
        assertEquals("Ana", SpellsKeyPolicy.chooseTarget(null, true, true, " Ana "));
        assertEquals("", SpellsKeyPolicy.chooseTarget(null, true, false, "Jake"));
    }

    @Test
    void blankOrMissingCompanionAliasFallsBackToServerPick() {
        assertEquals("", SpellsKeyPolicy.chooseTarget(null, true, true, null));
        assertEquals("", SpellsKeyPolicy.chooseTarget(null, true, true, "  "));
    }
}
