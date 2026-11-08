package net.wcfcarolina13.GameAI.souls;

/**
 * Whether the owner's hurt counts as a fight for the shared-hardship peer rule (Bradley's ruling
 * 2026-11-08: "combat hurts only"). A fight needs an attacker that is neither the owner (their own
 * arrow, TNT, firework) nor one of the bots: hurts with no attacker (falls, lava, an ender pearl,
 * /kill, a dispenser's potion, an explosion nobody caused) are not a fight. Pure, no Minecraft types.
 */
public final class SharedHardshipPolicy {

    private SharedHardshipPolicy() {
    }

    public static boolean isFight(boolean hasAttacker, boolean attackerIsOwner, boolean attackerIsBot) {
        return hasAttacker && !attackerIsOwner && !attackerIsBot;
    }
}
