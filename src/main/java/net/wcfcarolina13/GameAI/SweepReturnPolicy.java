package net.wcfcarolina13.GameAI;

/**
 * Whether a drop sweep walks back to where it started after making room (a bundle craft can take
 * the bot to a crafting table). Drops are searched around the bot, so past half the sweep radius
 * the drops near the start fall out of range.
 */
public final class SweepReturnPolicy {
    private SweepReturnPolicy() {
    }

    public static boolean shouldReturnToOrigin(double distanceSqFromOrigin, double sweepRadius) {
        if (sweepRadius <= 0 || distanceSqFromOrigin <= 0) {
            return false;
        }
        double half = sweepRadius / 2.0D;
        return distanceSqFromOrigin > half * half;
    }
}
