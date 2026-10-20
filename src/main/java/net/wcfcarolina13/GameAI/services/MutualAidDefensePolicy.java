package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions for a mutual-aid approach that moves one step per server tick (a defender closing
 * on a hostile or an ally, a hungry bot walking to a donor). It replaces the old blocking nudge,
 * which slept on the server thread for up to 2.4-3.2 s while the bot it was moving could not tick.
 * The numbers keep that nudge's budgets: its millisecond deadlines at 20 ticks per second, and its
 * stall limit of 7 sleeps of 90 ms (about 14 ticks).
 */
public final class MutualAidDefensePolicy {

    /** Old nudge budget for closing on a hostile: 2.4 s. */
    public static final long HOSTILE_DEADLINE_TICKS = 48L;
    /** Old nudge budget for closing on the ally when the hostile was gone: 1.6 s. */
    public static final long ALLY_DEADLINE_TICKS = 32L;
    /** Old nudge budget for a hungry bot walking to a donor: 3.2 s. */
    public static final long SEEK_FOOD_DEADLINE_TICKS = 64L;
    /** Ticks without getting closer before the approach gives up. */
    public static final int NO_PROGRESS_LIMIT_TICKS = 14;
    /** A step counts as progress only when it closes the best distance by more than this (squared blocks). */
    public static final double PROGRESS_EPSILON_SQ = 0.05D;
    /** Sprint when further than 3 blocks. */
    public static final double SPRINT_BEYOND_SQ = 9.0D;
    /** Beyond 4 blocks the push is at least {@link #FAR_MIN_IMPULSE}. */
    public static final double FAR_BEYOND_SQ = 16.0D;
    public static final double FAR_MIN_IMPULSE = 0.16D;

    public enum Decision {
        /** Within reach: stop and act. */
        ARRIVED,
        /** Take one more step this tick. */
        CONTINUE,
        /** Out of time or stalled: stop and back off. */
        ABORT
    }

    /**
     * The bot's own hostile scan radius (BotEventHandler's 10-block engage range), squared. A defender
     * this close to the hostile is already fought by its own combat loop.
     */
    public static final double OWN_COMBAT_SCAN_SQ = 10.0D * 10.0D;

    /** Why a defense approach ends without stopping the bot. */
    public enum Handoff {
        /** Keep approaching. */
        NONE,
        /** A task (a hobby that started mid-approach) now moves the bot. */
        TASK,
        /** The bot's own combat has a target, or will fight the hostile within its own scan. */
        OWN_COMBAT
    }

    /** The decision and the progress bookkeeping to carry into the next tick. */
    public record Step(Decision decision, double bestDistSq, int noProgressTicks) {
    }

    private MutualAidDefensePolicy() {
    }

    /**
     * One tick of the approach. Arrival wins over everything; otherwise the step updates the best
     * distance seen (a step that does not beat it by {@link #PROGRESS_EPSILON_SQ} counts as no
     * progress) and aborts when the deadline has passed or the bot has stalled for
     * {@link #NO_PROGRESS_LIMIT_TICKS}.
     */
    public static Step step(double distSq, double reachSq, double bestDistSq, int noProgressTicks,
                            long nowTick, long deadlineTick) {
        if (distSq <= reachSq) {
            return new Step(Decision.ARRIVED, Math.min(bestDistSq, distSq), 0);
        }
        double best = bestDistSq;
        int stalled = noProgressTicks;
        if (distSq + PROGRESS_EPSILON_SQ >= bestDistSq) {
            stalled++;
        } else {
            best = distSq;
            stalled = 0;
        }
        if (nowTick >= deadlineTick || stalled >= NO_PROGRESS_LIMIT_TICKS) {
            return new Step(Decision.ABORT, best, stalled);
        }
        return new Step(Decision.CONTINUE, best, stalled);
    }

    /**
     * Whether a defense approach hands the bot to another mover this tick. The approach pushes the
     * bot every tick, so a second mover (its own combat loop, or a task) would fight it for the
     * input; and stopping the bot on the way out would cancel that mover's work (a bow draw). Either
     * way the approach did its job: it is a success-handoff, ended without a stop.
     *
     * <p>A hostile within the own-combat scan is handed off only when own combat would fight it.
     * A mob targeting the ally, not the bot, passes own combat's pursuit gate only for a bot with
     * a ranged weapon; a melee bot keeps approaching and strikes at reach, which turns the mob on
     * the bot, and own combat takes over from there.
     *
     * @param hasCombatTarget       the bot's own combat loop has a live target
     * @param hostileDistSq         squared distance to the hostile walked toward, or
     *                              {@link Double#POSITIVE_INFINITY} when there is none (approaching the ally)
     * @param ownCombatWouldEngage  the bot's own combat would engage that hostile
     * @param hasActiveTask         a task is running for the bot
     */
    public static Handoff defenseHandoff(boolean hasCombatTarget, double hostileDistSq,
                                         boolean ownCombatWouldEngage, boolean hasActiveTask) {
        if (hasActiveTask) {
            return Handoff.TASK;
        }
        if (hasCombatTarget || (hostileDistSq <= OWN_COMBAT_SCAN_SQ && ownCombatWouldEngage)) {
            return Handoff.OWN_COMBAT;
        }
        return Handoff.NONE;
    }

    /** Armor points that make a bot without any weapon fit to defend. */
    public static final int ARMOR_ONLY_COMBAT_MIN = 6;
    /** Readiness weight of a usable ranged weapon: a stone-tier melee weapon. */
    public static final int RANGED_READINESS_SCORE = 20;

    /**
     * Whether a bot is fit to go defend an ally: fed, and armed with a melee or a usable ranged
     * weapon, or armored enough to fight bare-handed. A bow-only bot used to fail this, so it
     * never went (finding 53).
     */
    public static boolean isCombatCapable(boolean foodOk, boolean hasMelee, boolean hasRanged, int armor) {
        return foodOk && (hasMelee || hasRanged || armor >= ARMOR_ONLY_COMBAT_MIN);
    }

    /** A defender with any usable weapon is stronger than an ally with none. */
    public static boolean outArmsAlly(boolean defenderMelee, boolean defenderRanged,
                                      boolean allyMelee, boolean allyRanged) {
        return (defenderMelee || defenderRanged) && !allyMelee && !allyRanged;
    }

    /** The weapon part of a readiness score: the best melee score, or a usable ranged weapon. */
    public static int readinessWeaponScore(int bestMeleeScore, boolean hasRanged) {
        return Math.max(bestMeleeScore, hasRanged ? RANGED_READINESS_SCORE : 0);
    }

    /**
     * Whether an item translation key names a melee weapon. "axe" alone also matched every
     * pickaxe (and "waxed_*"), so a bot with only a pickaxe counted as armed; an axe now needs the
     * item name to end in "axe" (not "pickaxe").
     */
    public static boolean isMeleeWeaponKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (key.contains("sword") || key.contains("trident") || key.contains("mace") || key.contains("dagger")) {
            return true;
        }
        // The axe ends the item name: "axe" inside a name matched every waxed_* block.
        String path = key.substring(key.lastIndexOf('.') + 1);
        return path.endsWith("axe") && !path.endsWith("pickaxe");
    }

    public static boolean shouldSprint(double distSq) {
        return distSq > SPRINT_BEYOND_SQ;
    }

    /** The old nudge's push: its base impulse, raised to {@link #FAR_MIN_IMPULSE} when far away. */
    public static double impulse(double distSq, double baseImpulse) {
        return distSq > FAR_BEYOND_SQ ? Math.max(baseImpulse, FAR_MIN_IMPULSE) : baseImpulse;
    }
}
