package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkstationReachPolicyTest {
    @Test
    void opensWhenSameWorldInRangeAndBlockNearby() {
        assertNull(WorkstationReachPolicy.refusal("Lia", true, 10 * 10, true, "an anvil"));
        assertNull(WorkstationReachPolicy.refusal("Lia", true, 64 * 64, true, "an anvil"));
    }

    @Test
    void explainsEachRefusalInOrder() {
        assertEquals("Lia is in another dimension.",
                WorkstationReachPolicy.refusal("Lia", false, 0, false, "an anvil"));
        assertEquals("Lia is too far away (100 blocks). It has to be within 64 blocks.",
                WorkstationReachPolicy.refusal("Lia", true, 100 * 100, false, "an anvil"));
        assertEquals("Stand within 4 blocks of an Enchanting Table.",
                WorkstationReachPolicy.refusal("Lia", true, 4, false, "an Enchanting Table"));
    }

    @Test
    void spellPermissionCoversCompanionSpellsOnly() {
        assertTrue(SpellPermissionPolicy.isSpellAction(BotActionPolicy.Action.COMPANION));
        assertFalse(SpellPermissionPolicy.isSpellAction(BotActionPolicy.Action.FORTIFY));
    }
}
