package net.wcfcarolina13.GameAI;

final class CombatWeaponPolicy {

    enum CloseRangeChoice {
        MELEE,
        RANGED,
        FALLBACK
    }

    private CombatWeaponPolicy() {}

    static boolean isUsableRangedWeapon(
            boolean weaponPresent,
            boolean charged,
            boolean hasProjectile,
            boolean creativeMode) {
        return weaponPresent && (charged || hasProjectile || creativeMode);
    }

    static CloseRangeChoice chooseCloseRangeChoice(boolean hasMeleeWeapon, boolean hasUsableRangedWeapon) {
        if (hasMeleeWeapon) {
            return CloseRangeChoice.MELEE;
        }
        if (hasUsableRangedWeapon) {
            return CloseRangeChoice.RANGED;
        }
        return CloseRangeChoice.FALLBACK;
    }
}
