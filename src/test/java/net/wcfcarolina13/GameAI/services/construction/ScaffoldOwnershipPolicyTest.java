package net.wcfcarolina13.GameAI.services.construction;

import net.wcfcarolina13.GameAI.services.construction.ScaffoldOwnershipPolicy.OwnedVerdict;
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

    private static final long T0 = 1_760_000_000_000L;
    private static final long DAY = ScaffoldOwnershipPolicy.OWNERSHIP_TTL_MS;
    private static final String PLANKS = "minecraft:oak_planks";

    @Test
    void ttlIsTwentyFourHours() {
        assertEquals(86_400_000L, DAY);
    }

    @Test
    void expiryBoundaryIsExactlyTwentyFourHours() {
        assertFalse(ScaffoldOwnershipPolicy.isExpired(T0, T0));
        assertFalse(ScaffoldOwnershipPolicy.isExpired(T0, T0 + DAY - 1));
        assertTrue(ScaffoldOwnershipPolicy.isExpired(T0, T0 + DAY));
        assertTrue(ScaffoldOwnershipPolicy.isExpired(T0, T0 + DAY + 1));
    }

    @Test
    void missingOrFarFutureTimestampIsExpired() {
        assertTrue(ScaffoldOwnershipPolicy.isExpired(0L, T0));
        assertTrue(ScaffoldOwnershipPolicy.isExpired(-5L, T0));
        assertFalse(ScaffoldOwnershipPolicy.isExpired(T0 + 1_000L, T0));
        assertTrue(ScaffoldOwnershipPolicy.isExpired(T0 + DAY + 1, T0));
    }

    @Test
    void usableEntryNeedsBlockIdAndUnexpiredTime() {
        assertTrue(ScaffoldOwnershipPolicy.isUsableEntry(PLANKS, T0, T0 + 1));
        assertFalse(ScaffoldOwnershipPolicy.isUsableEntry(null, T0, T0 + 1));
        assertFalse(ScaffoldOwnershipPolicy.isUsableEntry("  ", T0, T0 + 1));
        assertFalse(ScaffoldOwnershipPolicy.isUsableEntry(PLANKS, T0, T0 + DAY));
    }

    @Test
    void verdictBreaksOnlyTheExactPlacedBlock() {
        assertEquals(OwnedVerdict.BREAK,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + 60_000L, PLANKS, false));
        // Another scaffold material in the same cell is still a different block: forget, never break.
        assertEquals(OwnedVerdict.FORGET_CHANGED,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + 1, "minecraft:dirt", false));
        assertEquals(OwnedVerdict.FORGET_CHANGED,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + 1, "minecraft:chest", false));
        assertEquals(OwnedVerdict.FORGET_CHANGED,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + 1, "minecraft:oak_log", false));
        assertEquals(OwnedVerdict.FORGET_CHANGED,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + 1, null, false));
    }

    @Test
    void verdictForgetsAirAndExpiredAndIgnoresUnowned() {
        assertEquals(OwnedVerdict.FORGET_GONE,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + 1, null, true));
        assertEquals(OwnedVerdict.FORGET_EXPIRED,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + DAY, PLANKS, false));
        // Expiry is decided before the live block: an expired entry never breaks, even unchanged.
        assertEquals(OwnedVerdict.FORGET_EXPIRED,
                ScaffoldOwnershipPolicy.ownedVerdict(PLANKS, T0, T0 + DAY + 1, null, true));
        assertEquals(OwnedVerdict.FORGET_EXPIRED,
                ScaffoldOwnershipPolicy.ownedVerdict("", T0, T0 + 1, "", false));
        assertEquals(OwnedVerdict.NOT_OWNED,
                ScaffoldOwnershipPolicy.ownedVerdict(null, T0, T0 + 1, PLANKS, false));
    }

    @Test
    void forgetForEveryForgetVerdictOnly() {
        assertTrue(ScaffoldOwnershipPolicy.shouldForget(OwnedVerdict.FORGET_GONE));
        assertTrue(ScaffoldOwnershipPolicy.shouldForget(OwnedVerdict.FORGET_CHANGED));
        assertTrue(ScaffoldOwnershipPolicy.shouldForget(OwnedVerdict.FORGET_EXPIRED));
        assertFalse(ScaffoldOwnershipPolicy.shouldForget(OwnedVerdict.BREAK));
        assertFalse(ScaffoldOwnershipPolicy.shouldForget(OwnedVerdict.NOT_OWNED));
        assertFalse(ScaffoldOwnershipPolicy.shouldForget(null));
    }

    @Test
    void breakKindTreeWinsThenOwnedScaffoldElseOther() {
        assertEquals(MutationKind.TREE_BLOCK, ScaffoldOwnershipPolicy.breakKind(true, OwnedVerdict.NOT_OWNED));
        assertEquals(MutationKind.TREE_BLOCK, ScaffoldOwnershipPolicy.breakKind(true, OwnedVerdict.BREAK));
        assertEquals(MutationKind.OWN_SCAFFOLD_BREAK, ScaffoldOwnershipPolicy.breakKind(false, OwnedVerdict.BREAK));
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, OwnedVerdict.FORGET_CHANGED));
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, OwnedVerdict.FORGET_EXPIRED));
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, OwnedVerdict.FORGET_GONE));
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, OwnedVerdict.NOT_OWNED));
        assertEquals(MutationKind.OTHER, ScaffoldOwnershipPolicy.breakKind(false, null));
    }
}
