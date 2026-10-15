package net.wcfcarolina13.GameAI.skills.support;

/**
 * Pure hunger decisions for a woodcut run with no food.
 *
 * <p>Ruling (1.1.102, commit 877cd756): a long skill does not hunt mid-task. It pauses
 * (flagManualResume plus "feed me, then /bot resume") when the bot is starving and cannot eat.
 * Auto-hunt stays opt-in and only runs once the task slot is free.
 *
 * <p>1.1.238 sandbox: the pause was checked only at the top of the woodcut loop, so one long
 * approach let it fire 5 minutes after food reached 5. The player had no chat warning before that:
 * hunger warnings are overhead holograms (2026-03-21), invisible from 46 blocks away. So the run
 * now warns once in chat when food gets low with nothing to eat, and checks the pause at the
 * approach loop heads too.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutHungerPolicy {

    /** Mirrors HealingService HUNGER_WARNING ("I'm hungry"). */
    public static final int DEFAULT_WARN_FOOD_LEVEL = 10;
    /** Mirrors HealingService HUNGER_CRITICAL ("I'm starving"). */
    public static final int DEFAULT_PAUSE_FOOD_LEVEL = 5;

    public static final String NO_FOOD_WARNING_LINE = "I'm getting hungry and I have no food.";
    public static final String STARVING_PAUSE_LINE =
            "I'm starving and out of food, so I stopped cutting. Feed me, then /bot resume.";

    public enum Action {
        /** Nothing to do. */
        NONE,
        /** Food is low and there is nothing to eat: say so once this run. */
        WARN_ONCE,
        /** Starving: try to eat, and pause the run if that fails. */
        PAUSE
    }

    private WoodcutHungerPolicy() {
    }

    /** {@link #decide(int, int, boolean, int, int)} with the default thresholds. */
    public static Action decide(int foodLevel, int safeFoodCount, boolean warnedThisRun) {
        return decide(foodLevel, safeFoodCount, warnedThisRun, DEFAULT_WARN_FOOD_LEVEL, DEFAULT_PAUSE_FOOD_LEVEL);
    }

    /**
     * PAUSE at or below {@code pauseLevel}, whatever the food count: the caller confirms with an
     * eat attempt (HealingService.shouldPauseForStarvation), so a bot with food eats instead.
     * WARN_ONCE at or below {@code warnLevel} with no safe food, if not yet warned this run.
     * Otherwise NONE.
     */
    public static Action decide(int foodLevel,
                                int safeFoodCount,
                                boolean warnedThisRun,
                                int warnLevel,
                                int pauseLevel) {
        if (foodLevel <= pauseLevel) {
            return Action.PAUSE;
        }
        if (foodLevel <= warnLevel && safeFoodCount <= 0 && !warnedThisRun) {
            return Action.WARN_ONCE;
        }
        return Action.NONE;
    }
}
