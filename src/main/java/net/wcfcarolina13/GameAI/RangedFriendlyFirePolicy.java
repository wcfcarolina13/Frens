package net.wcfcarolina13.GameAI;

/**
 * Pure geometry for the ranged friendly-fire gate: whether a player stands in the line of fire from
 * a shooter's eye to its aim point. Bots used to be skipped outright, so a bow defender shot the
 * ally it was defending (finding 54). A bot now counts too, with tighter limits so an ally standing
 * beside the target does not freeze the shooter: it must be between the shooter and the target and
 * close to the line. Human players keep the wider, older cone.
 */
public final class RangedFriendlyFirePolicy {

    /** Shots shorter than this are never gated. */
    public static final double MIN_SHOT_DIST = 0.5D;
    /** Lateral reach of the cone for a human player: hitbox width plus bow divergence. */
    public static final double HUMAN_LATERAL = 1.5D;
    /** How far past the aim point a human still counts. */
    public static final double HUMAN_PAST_AIM = 1.0D;
    /** Lateral reach of the cone for a bot: about a hitbox and a half. */
    public static final double BOT_LATERAL = 0.9D;
    /** A bot counts only when it is at least this much short of the aim point. */
    public static final double BOT_SHORT_OF_AIM = 0.5D;

    private RangedFriendlyFirePolicy() {
    }

    /**
     * Whether a player at {@code other} (its mid-body) is in the line of fire from {@code from} to
     * {@code aim}.
     *
     * @param otherIsBot the player is a Frens bot (tighter cone, only between shooter and target)
     */
    public static boolean inLineOfFire(double fromX, double fromY, double fromZ,
                                       double aimX, double aimY, double aimZ,
                                       double otherX, double otherY, double otherZ,
                                       boolean otherIsBot) {
        double dx = aimX - fromX;
        double dy = aimY - fromY;
        double dz = aimZ - fromZ;
        double maxDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (maxDist < MIN_SHOT_DIST) {
            return false;
        }
        double ux = dx / maxDist;
        double uy = dy / maxDist;
        double uz = dz / maxDist;
        double ox = otherX - fromX;
        double oy = otherY - fromY;
        double oz = otherZ - fromZ;
        double along = ox * ux + oy * uy + oz * uz;
        double maxAlong = otherIsBot ? maxDist - BOT_SHORT_OF_AIM : maxDist + HUMAN_PAST_AIM;
        if (along <= 0.0D || along >= maxAlong) {
            return false;
        }
        double px = ox - ux * along;
        double py = oy - uy * along;
        double pz = oz - uz * along;
        double lateral = otherIsBot ? BOT_LATERAL : HUMAN_LATERAL;
        return px * px + py * py + pz * pz <= lateral * lateral;
    }

    /**
     * Whether a ranged bot may look for a new firing stance. Close to the target it normally just
     * fights from where it is, but when the shot is held for a friend in the line there is nothing
     * else to do there (a bow-only bot has no melee to fall back on), so it sidesteps.
     */
    public static boolean mayReposition(double distSqToTarget, double minDistSq, boolean friendlyInLine) {
        return friendlyInLine || distSqToTarget > minDistSq;
    }

    /** Prefer a clear sidestep or retreat over closing through the ally's line. */
    public static double repositionScore(boolean clearShot, double blockedVisibility, double cover,
                                         double moveCostSq, boolean friendlyInLine, double forwardProgress) {
        if (!friendlyInLine) {
            return blockedVisibility + cover - moveCostSq * 2.0D;
        }
        double visibility = clearShot ? 1_000_000.0D : Math.min(blockedVisibility, 999.0D);
        double spacing = clearShot && forwardProgress <= 0.5D ? 1_000_000.0D : 0.0D;
        return visibility + spacing + cover - moveCostSq * 2.0D;
    }
}
