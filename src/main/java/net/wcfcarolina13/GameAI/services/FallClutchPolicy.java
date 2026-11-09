package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions behind the water-bucket fall clutch ({@link BotFallSafetyService}). No Minecraft
 * types, so JUnit can test it.
 *
 * <p>The clutch used to arm only once the fall so far was already lethal (about 23 blocks at full
 * health), which left two or three ticks before impact in a 30-block drop. It now judges the whole
 * fall: the distance fallen plus the height still to go to the predicted landing.</p>
 */
public final class FallClutchPolicy {

    /** Fall damage is {@code fallDistance - 3} (minecraft.wiki, Fall damage), before armour and effects. */
    public static final double SAFE_FALL_DISTANCE = 3.0D;
    /** Falls that would cost less than this are left alone, whatever the bot's health. */
    public static final double MIN_DAMAGE_WORTH_A_BUCKET = 4.0D;

    private FallClutchPolicy() {
    }

    /** The damage the bot is heading for: what it has fallen plus what is still below it. */
    public static double projectedDamage(double fallDistance, double heightToLanding) {
        double total = Math.max(0.0D, fallDistance) + Math.max(0.0D, heightToLanding);
        return Math.max(0.0D, total - SAFE_FALL_DISTANCE);
    }

    /**
     * True when the landing would kill the bot or leave it at half a heart: the same margin the
     * clutch always used ({@code health - 0.5}), applied to the whole fall instead of the fall so far.
     */
    public static boolean projectedLethal(double fallDistance, double heightToLanding, double effectiveHealth) {
        double damage = projectedDamage(fallDistance, heightToLanding);
        return damage >= Math.max(MIN_DAMAGE_WORTH_A_BUCKET, effectiveHealth - 0.5D);
    }

    /**
     * True when the landing block's top face is within the bot's block reach, as a player's would
     * have to be: the bucket is used with the vanilla raycast from the eyes straight down, so the
     * eye-to-face distance (feet height above the face plus eye height) must not exceed the reach.
     * A height at or below zero means the bot lands this tick and nothing can be placed.
     */
    public static boolean inPlaceWindow(double feetAboveFace, double eyeHeight, double blockReach) {
        return feetAboveFace > 0.05D && feetAboveFace + eyeHeight <= blockReach;
    }
}
