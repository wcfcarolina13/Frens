package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a hostile's projectile hits a bot that is running a task, pause the task so the normal
 * IDLE branch (combat or flee) can deal with the shooter. Without this a bogged or skeleton
 * shooting from past the 10-block AutoFace pause radius never interrupts woodcutting: the bot
 * keeps chopping while it is shot. Pure: entity types are registry id strings, ticks are longs.
 *
 * <p>Any hostile projectile hit counts, not only bows and crossbows: a blaze fireball or a
 * drowned's trident pauses the task too. The shooter type ({@link #isRangedShooter}) is only a
 * log field. A hobby (ambient task) is paused without a resume record: it simply ends and the
 * hobby scheduler picks another later. Tasks whose interruption is worse than the arrows are
 * never paused: the death-retrieval sweep (a pause fails it for good) and surface recovery.
 */
public final class RangedThreatPausePolicy {

    /**
     * How long a hostile hit counts as "recent" for the wider 16-block combat scan and the
     * shelter-compromised check. A bogged fires every 50 (hard) to 70 ticks and a skeleton every
     * 20 to 60, so the old 40-tick window closed between a bogged's shots.
     */
    public static final int RECENT_DAMAGE_WINDOW_TICKS = 100;

    /** Minimum gap between two ranged-threat pauses for one bot, so a volley pauses once. */
    public static final long PAUSE_DEBOUNCE_TICKS = 100L;

    /** Beyond the wider combat scan the bot could not engage the shooter, so it keeps working. */
    public static final double MAX_SHOOTER_DISTANCE = 16.0D;

    /** Chat line sent with the pause. No em dash, no semicolon. */
    public static final String PAUSE_MESSAGE = "§cPausing current task: I'm being shot.";

    private static final Set<String> RANGED_SHOOTERS = Set.of(
            "minecraft:skeleton",
            "minecraft:stray",
            "minecraft:bogged",
            "minecraft:parched",
            "minecraft:pillager");

    private static final Map<UUID, Long> LAST_PAUSE_TICK = new ConcurrentHashMap<>();

    private RangedThreatPausePolicy() {
    }

    /**
     * Active ticket names a ranged hit never pauses. Skill tickets carry the "skill:" prefix
     * (DeathRetrievalService runs drop_sweep via SkillManager.runSkill with no resume record);
     * surface recovery is a system task named without it.
     */
    private static final Set<String> NEVER_PAUSE_TASKS = Set.of(
            "skill:drop_sweep",
            "surface_recovery");

    /** Bow and crossbow mobs, for the log line only. Wither skeletons and skeleton horses do not shoot. */
    public static boolean isRangedShooter(String typeId) {
        return typeId != null && RANGED_SHOOTERS.contains(typeId);
    }

    /** True for an active ticket name a ranged hit must not pause. */
    public static boolean isNeverPauseTask(String activeTaskName) {
        return activeTaskName != null
                && NEVER_PAUSE_TASKS.contains(activeTaskName.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * @param activeTaskName the bot's active ticket name ({@code TaskService.getActiveTaskName}),
     *                       null when the bot has no task
     */
    public static boolean shouldPause(String activeTaskName,
                                      boolean attackerHostile,
                                      boolean projectileDamage,
                                      double distance,
                                      boolean alreadyPausedRecently) {
        return activeTaskName != null
                && !isNeverPauseTask(activeTaskName)
                && attackerHostile
                && projectileDamage
                && !Double.isNaN(distance)
                && distance <= MAX_SHOOTER_DISTANCE
                && !alreadyPausedRecently;
    }

    public static boolean pausedRecently(long nowTick, Long lastPauseTick) {
        return lastPauseTick != null
                && nowTick >= lastPauseTick
                && nowTick - lastPauseTick < PAUSE_DEBOUNCE_TICKS;
    }

    /** True when this bot paused for a ranged threat within {@link #PAUSE_DEBOUNCE_TICKS}. */
    public static boolean pausedRecently(UUID bot, long nowTick) {
        return bot != null && pausedRecently(nowTick, LAST_PAUSE_TICK.get(bot));
    }

    public static void notePause(UUID bot, long nowTick) {
        if (bot != null) {
            LAST_PAUSE_TICK.put(bot, nowTick);
        }
    }

    static void resetForTests() {
        LAST_PAUSE_TICK.clear();
    }
}
