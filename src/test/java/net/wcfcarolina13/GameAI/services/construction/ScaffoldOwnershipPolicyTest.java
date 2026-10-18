package net.wcfcarolina13.GameAI.services.construction;

import net.wcfcarolina13.GameAI.skills.support.WoodcutBaseAccessPolicy.MutationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldOwnershipPolicyTest {

    @Test
    void scaffoldMaterialIsTheUnionOfBothLists() {
        // ScaffoldService.SCAFFOLD_BLOCKS only
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:stone"));
        // WoodcutSkill.PILLAR_BLOCKS only
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:moss_block"));
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:pale_moss_block"));
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:scaffolding"));
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:coarse_dirt"));
        // both
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:oak_planks"));
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:dirt"));
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:warped_planks"));
    }

    @Test
    void bareIdsAndCaseAreNormalized() {
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial("cobblestone"));
        assertTrue(ScaffoldOwnershipPolicy.isScaffoldMaterial(" Minecraft:Birch_Planks "));
    }

    @Test
    void nonScaffoldBlocksAreNotMaterial() {
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:oak_log"));
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:oak_leaves"));
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:grass_block"));
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:chest"));
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial("minecraft:air"));
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial("othermod:dirt"));
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial(""));
        assertFalse(ScaffoldOwnershipPolicy.isScaffoldMaterial(null));
    }

    @Test
    void registerOnlyOnRealSuccessNeverFromRetryAdoption() {
        assertTrue(ScaffoldOwnershipPolicy.mayRegister(null, true));
        assertTrue(ScaffoldOwnershipPolicy.mayRegister("", true));
        assertFalse(ScaffoldOwnershipPolicy.mayRegister(null, false));
        assertFalse(ScaffoldOwnershipPolicy.mayRegister("claim-denied protected-zone", false));
        assertFalse(ScaffoldOwnershipPolicy.mayRegister(ScaffoldOwnershipPolicy.PLACED_DETECTED_AFTER_RETRY, true));
        assertEquals("placed-detected-after-retry", ScaffoldOwnershipPolicy.PLACED_DETECTED_AFTER_RETRY);
    }

    @Test
    void breakOwnedNeedsOwnershipAndLiveMaterial() {
        assertTrue(ScaffoldOwnershipPolicy.mayBreakOwned(true, true));
        assertFalse(ScaffoldOwnershipPolicy.mayBreakOwned(true, false));
        assertFalse(ScaffoldOwnershipPolicy.mayBreakOwned(false, true));
        assertFalse(ScaffoldOwnershipPolicy.mayBreakOwned(false, false));
    }

    @Test
    void breakKindTreeWinsThenOwnedScaffoldElseOther() {
        assertEquals(MutationKind.TREE_BLOCK, ScaffoldOwnershipPolicy.breakKind(true, false, false));
        assertEquals(MutationKind.TREE_BLOCK, ScaffoldOwnershipPolicy.breakKind(true, true, true));
        assertEquals(MutationKind.OWN_SCAFFOLD_BREAK, ScaffoldOwnershipPolicy.breakKind(false, true, true));
        // Someone replaced the bot's scaffold with another block: not an own-scaffold break.
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, true, false));
        // Scaffold material the bot never placed (natural dirt, a player's planks).
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, false, true));
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, false, false));
    }

    @Test
    void forgetWhenGoneOrChangedNeverForUnowned() {
        assertTrue(ScaffoldOwnershipPolicy.shouldForget(true, true, false));
        assertTrue(ScaffoldOwnershipPolicy.shouldForget(true, false, false));
        assertFalse(ScaffoldOwnershipPolicy.shouldForget(true, false, true));
        assertFalse(ScaffoldOwnershipPolicy.shouldForget(false, true, false));
    }
}
