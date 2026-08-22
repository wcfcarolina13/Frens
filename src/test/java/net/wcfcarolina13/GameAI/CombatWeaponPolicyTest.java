package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatWeaponPolicyTest {

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
