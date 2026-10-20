package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatWeaponPolicyTest {

    @Test
    void swordAndBowDefenderUsesMeleeInsideFiveBlocks() {
        assertFalse(CombatWeaponPolicy.shouldEngageRanged(true, true, 2.0));
        assertFalse(CombatWeaponPolicy.shouldEngageRanged(true, true, 4.99));
    }

    @Test
    void swordAndBowDefenderCanRepositionAtFiveBlocks() {
        assertTrue(CombatWeaponPolicy.shouldEngageRanged(true, true, 5.0));
        assertTrue(CombatWeaponPolicy.shouldEngageRanged(true, true, 8.0));
    }

    @Test
    void bowOnlyDefenderCanSidestepAndShootInsideFiveBlocks() {
        assertTrue(CombatWeaponPolicy.shouldEngageRanged(true, false, 2.0));
        assertFalse(CombatWeaponPolicy.shouldEngageRanged(false, false, 2.0));
        assertFalse(CombatWeaponPolicy.shouldEngageRanged(false, true, 8.0));
    }

    @Test
    void unloadedRangedWeaponWithoutAProjectileIsNotUsable() {
        assertFalse(CombatWeaponPolicy.isUsableRangedWeapon(true, false, false, false));
    }

    @Test
    void unloadedRangedWeaponWithAProjectileIsUsable() {
        assertTrue(CombatWeaponPolicy.isUsableRangedWeapon(true, false, true, false));
    }

    @Test
    void chargedRangedWeaponDoesNotRequireSpareAmmunition() {
        assertTrue(CombatWeaponPolicy.isUsableRangedWeapon(true, true, false, false));
    }

    @Test
    void closeRangeUsesFallbackInsteadOfSelectingAnUnusableRangedWeapon() {
        assertEquals(
                CombatWeaponPolicy.CloseRangeChoice.FALLBACK,
                CombatWeaponPolicy.chooseCloseRangeChoice(false, false));
    }

    @Test
    void closeRangeUsesRangedWeaponWhenNoMeleeWeaponIsAvailableButRangedIsUsable() {
        assertEquals(
                CombatWeaponPolicy.CloseRangeChoice.RANGED,
                CombatWeaponPolicy.chooseCloseRangeChoice(false, true));
    }

    @Test
    void closeRangePrefersMeleeWhenBothWeaponTypesAreUsable() {
        assertEquals(
                CombatWeaponPolicy.CloseRangeChoice.MELEE,
                CombatWeaponPolicy.chooseCloseRangeChoice(true, true));
    }
}
