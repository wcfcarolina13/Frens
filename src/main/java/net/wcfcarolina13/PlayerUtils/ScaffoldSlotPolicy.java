package net.wcfcarolina13.PlayerUtils;

/**
 * Pure decision helpers for scaffold/hotbar and surface-escape policy.
 *
 * <p>Deliberately free of {@code net.minecraft.*} types so the logic can be
 * unit-tested without a game runtime.</p>
 */
public final class ScaffoldSlotPolicy {

    private ScaffoldSlotPolicy() {}

    /**
     * Resolve which hotbar slot a found inventory slot should be used from.
     *
     * @param sourceSlot            slot the item was found in (any inventory index)
     * @param firstEmptyHotbarSlot  first empty hotbar slot, or -1 if the hotbar is full
     * @param hotbarLocked          whether the player has locked the hotbar against swaps
     * @return the source slot when it is already in the hotbar; -1 when the hotbar is
     *         locked and the item lives outside it (caller must treat as unavailable);
     *         otherwise the swap target slot.
     */
    public static int resolveHotbarTarget(int sourceSlot, int firstEmptyHotbarSlot, boolean hotbarLocked) {
        if (sourceSlot < 9) {
            return sourceSlot;
        }
        if (hotbarLocked) {
            return -1;
        }
        return firstEmptyHotbarSlot >= 0 ? firstEmptyHotbarSlot : 0;
    }

    /**
     * Whether an escape cooldown should be applied after a surface-recovery attempt.
     *
     * @param recoveredReported what the recovery call returned
     * @param atSurfaceNow      whether the bot is actually at the surface afterwards
     */
    public static boolean shouldApplyEscapeCooldown(boolean recoveredReported, boolean atSurfaceNow) {
        return !recoveredReported || !atSurfaceNow;
    }
}
