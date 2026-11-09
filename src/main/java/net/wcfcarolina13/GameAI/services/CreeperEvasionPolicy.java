package net.wcfcarolina13.GameAI.services;

/**
 * Pure decision function for creeper self-protection: given the measured
 * situation (distance, whether the bot is armed for melee, whether the creeper
 * is charged, the creeper's fuse state, and the bot's health fraction) it
 * returns what the bot should do.
 *
 * <p>Deliberately Minecraft-free — it takes primitives and enums only, so it is
 * unit-testable without a server. All the entity reads live at the call site in
 * {@code BotEventHandler.engageHostiles}.</p>
 *
 * <p><b>Thresholds (unchanged from the inline implementation this replaced):</b></p>
 * <ul>
 *   <li>Engagement radius — 6 blocks normal, 12 charged. Beyond it, {@link Decision#STAY}
 *       (nothing creeper-specific to do; the generic combat path handles it).</li>
 *   <li>Block-and-shield radius — 4.5 blocks, normal creepers only.</li>
 *   <li>Low health — at or below 50% of max, never stand and trade.</li>
 * </ul>
 *
 * <p><b>New behaviour (the "armed bots don't back off" report):</b> previously an
 * armed bot inside 4.5 blocks of a lone normal creeper would STAY and run the
 * block-and-shield trick even while the creeper was actively swelling. A vanilla
 * creeper's explosion radius is 3 blocks, so that put the bot inside the blast
 * every time the trick failed to seat a block. Now, whenever the fuse is running
 * ({@link FuseState#SWELLING} or {@link FuseState#IGNITED}) and the bot is inside
 * {@link #ARMED_FUSE_BACK_AWAY_RADIUS}, an armed bot returns
 * {@link Decision#BACK_AWAY}.</p>
 *
 * <p>{@link #ARMED_FUSE_BACK_AWAY_RADIUS} is 4.5 blocks, not 3: the 3-block
 * vanilla blast radius plus 1.5 blocks of conservative margin for the creeper
 * closing distance during the ~0.5 s it takes the bot to react and start moving.
 * It is deliberately equal to the block-and-shield radius, so the new rule
 * strictly pre-empts the trick rather than carving out a partial band — no
 * situation that used to FLEE_SPRINT now backs away more slowly.</p>
 */
public final class CreeperEvasionPolicy {

    /** What the bot should do about the creeper this tick. */
    public enum Decision {
        /** No creeper-specific evasion — hold ground (may include block-and-shield). */
        STAY,
        /** Retreat under shield, walking, still facing the creeper. */
        BACK_AWAY,
        /** Break contact at sprint speed. */
        FLEE_SPRINT
    }

    /** Whether the creeper's fuse is running, and how it started. */
    public enum FuseState {
        /** {@code getFuseSpeed() <= 0} and not ignited — dormant. */
        NONE,
        /** {@code getFuseSpeed() > 0} — swelling from proximity. */
        SWELLING,
        /** {@code isIgnited()} — flint-and-steel / goal ignition; cannot be defused. */
        IGNITED
    }

    /** Beyond this the creeper branch does nothing special. */
    public static final double ENGAGEMENT_RADIUS_NORMAL = 6.0D;
    public static final double ENGAGEMENT_RADIUS_CHARGED = 12.0D;
    /** Block-and-shield only makes sense point-blank against a normal creeper. */
    public static final double BLOCK_AND_SHIELD_RADIUS = 4.5D;
    /** Vanilla normal-creeper explosion radius. */
    public static final double BLAST_RADIUS = 3.0D;
    /** Blast radius + reaction margin — see the class javadoc. */
    public static final double ARMED_FUSE_BACK_AWAY_RADIUS = 4.5D;
    /**
     * Reserved. The pre-existing inline implementation did NOT branch on health
     * inside the creeper path (the generic {@code lowHealth} flag only fed the
     * shield decision), so {@link #decide} deliberately does not either — this
     * extraction changes no existing threshold. The value is carried for the
     * diagnostic line and for a future tuning pass.
     */
    public static final double LOW_HEALTH_FRACTION = 0.5D;

    /**
     * The task-abort reason for a creeper backoff. {@code TaskService} says the abort reason
     * aloud as the bot's chat line, so it is plain speech, not a log key. It avoids every
     * substring {@code SoulEventObserver.sanitizeReasonCategory} buckets on ("stop", "halt",
     * "threat", "danger" and so on) so it stays in the {@code other} bucket the old
     * {@code creeper-fuse} key fell into. The {@code /bot skill} echo is suppressed for it.
     */
    public static final String ABORT_REASON = "A creeper! I dropped what I was doing to get clear.";

    private CreeperEvasionPolicy() {}

    /**
     * @param distance       horizontal-ish distance from bot to creeper, in blocks
     * @param armed          bot has a melee weapon AND emergency tactics are permitted
     * @param charged        creeper is lightning-charged (roughly 2x blast)
     * @param fuse           creeper fuse state
     * @param healthFraction bot health / max health, 0..1 — recorded, not branched on
     *                       (see {@link #LOW_HEALTH_FRACTION})
     */
    public static Decision decide(double distance,
                                  boolean armed,
                                  boolean charged,
                                  FuseState fuse,
                                  double healthFraction) {
        FuseState fuseState = fuse == null ? FuseState.NONE : fuse;
        double engagement = charged ? ENGAGEMENT_RADIUS_CHARGED : ENGAGEMENT_RADIUS_NORMAL;
        if (distance > engagement) {
            return Decision.STAY;
        }
        if (!armed) {
            return Decision.FLEE_SPRINT;
        }
        // NEW: an armed bot inside the blast radius of a running fuse retreats
        // instead of standing to place a block.
        if (fuseState != FuseState.NONE && distance <= ARMED_FUSE_BACK_AWAY_RADIUS) {
            return Decision.BACK_AWAY;
        }
        if (!charged && distance <= BLOCK_AND_SHIELD_RADIUS) {
            return Decision.STAY;
        }
        return Decision.FLEE_SPRINT;
    }

    /** Melee reach for the knockback swing (BotActions.attackTarget's 3-block reach). */
    public static final double KNOCKBACK_REACH = 3.0D;

    /**
     * Bradley's ruling (2026-11-08, backlog 986, "hit it to knock it back"): an armed bot with no
     * shield and no block to place cannot do the block-and-shield trick STAY stands for, and a
     * cornered one cannot back away. Before, it stood or backed into the wall and took ~10 damage
     * (headless run, walled 8x9 pen). Now, inside melee reach, it swings at the creeper first to
     * knock it back out of its fuse range, then backs off. Open ground is unchanged: FLEE_SPRINT and
     * a free BACK_AWAY swing nothing.
     */
    public static boolean swingFirst(Decision decision, boolean armed, boolean hasShield, boolean hasBlock,
                                     double distance, boolean cornered) {
        if (decision == null || !armed || hasShield || distance > KNOCKBACK_REACH) {
            return false;
        }
        if (decision == Decision.STAY) {
            return !hasBlock;
        }
        return cornered;
    }

    /** STAY's block-and-shield trick needs a shield or a block; without both the bot backs away instead. */
    public static boolean trickUnavailable(Decision decision, boolean hasShield, boolean hasBlock) {
        return decision == Decision.STAY && !hasShield && !hasBlock;
    }

    /** Ticks without gaining distance that count as cornered for the knockback swing (half a second). */
    public static final int BACKOFF_SWING_STUCK_TICKS = 10;

    /**
     * The creeper-defense backoff's swing (BotCreeperDefenseService): a bot backing away with a melee
     * weapon and no shield that has not gained distance for {@link #BACKOFF_SWING_STUCK_TICKS} with
     * the creeper inside {@link #KNOCKBACK_REACH} swings at it. A free flee keeps gaining distance, so
     * open ground is unchanged.
     */
    public static boolean backoffSwing(boolean hasMelee, boolean hasShield, double distance, int ticksWithoutProgress) {
        return hasMelee && !hasShield && distance <= KNOCKBACK_REACH
                && ticksWithoutProgress >= BACKOFF_SWING_STUCK_TICKS;
    }

    /** A knockback swing waits for a fully charged attack (a weak hit barely moves the creeper). */
    public static boolean swingReady(float attackCooldownProgress) {
        return attackCooldownProgress >= 0.9F;
    }

    /** The swing lands only in 3D reach (attackTarget's 3-block reach), not just horizontal reach. */
    public static boolean inSwingReach(double distance3d) {
        return distance3d <= KNOCKBACK_REACH;
    }
}
