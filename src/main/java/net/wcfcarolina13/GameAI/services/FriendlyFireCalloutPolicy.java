package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions for the combat callouts that depend on who hit whom. No Minecraft types, so the
 * rules are unit-testable.
 */
public final class FriendlyFireCalloutPolicy {

    /** Which post-combat line pool {@code BotCombatCalloutService.checkCombatEnd} draws from. */
    public enum PostCombatBucket { EXPLOSION_CREEPER, EXPLOSION, MULTI, SINGLE, GENERAL }

    /** Hostiles in one scan at or above this count is a multi-mob fight. */
    public static final int MULTI_HOSTILE_COUNT = 3;

    private FriendlyFireCalloutPolicy() {
    }

    /**
     * A bot apologises ("I didn't mean to do that!") after its hit on a player lands.
     *
     * @param victimIsHuman  the damaged entity is a real player, not a bot (bot victims already
     *                       react through their own friendly-fire-received line)
     * @param attackerIsBot  the damage source's attacker (the shooter for arrows) is a registered bot
     * @param sameEntity     attacker and victim are the same entity
     * @param blocked        the hit was blocked by a shield
     * @param damageTaken    the damage that actually landed after armour and effects. Zero screens
     *                       out snowball fights and other harmless hits
     */
    public static boolean shouldApologize(boolean victimIsHuman, boolean attackerIsBot, boolean sameEntity,
                                          boolean blocked, float damageTaken) {
        return victimIsHuman && attackerIsBot && !sameEntity && !blocked && damageTaken > 0.0f;
    }

    /** Whether one hostile scan counts as a multi-mob fight for the mid-combat callout. */
    public static boolean isMultiHostileFight(int activeHostiles) {
        return activeHostiles >= MULTI_HOSTILE_COUNT;
    }

    /**
     * Picks the post-combat line pool from what the bot saw during the fight. Explosions win,
     * then a fight that peaked at three or more hostiles, then a lone weak mob, else general.
     *
     * @param hasMetadata false when no combat metadata was recorded for the fight
     */
    public static PostCombatBucket postCombatBucket(boolean hasMetadata, boolean explosionSeen,
                                                    boolean creeperExplosionSeen, int maxDangerousMobCount,
                                                    boolean sawAnyHostile, boolean sawNonWeakHostile) {
        if (!hasMetadata) {
            return PostCombatBucket.GENERAL;
        }
        if (explosionSeen) {
            return creeperExplosionSeen ? PostCombatBucket.EXPLOSION_CREEPER : PostCombatBucket.EXPLOSION;
        }
        if (isMultiHostileFight(maxDangerousMobCount)) {
            return PostCombatBucket.MULTI;
        }
        if (sawAnyHostile && maxDangerousMobCount <= 1 && !sawNonWeakHostile) {
            return PostCombatBucket.SINGLE;
        }
        return PostCombatBucket.GENERAL;
    }
}
