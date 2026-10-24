package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions for the "bots log off when they can't sleep" setting (1.1.298).
 *
 * <p>After a "zzz" attempt or a co-sleep attempt fails, {@link #decide} says whether the bot logs
 * off at once, stays online with a reason to tell the owner, or stays online silently (it slept,
 * the attempt was cancelled, or logging off would be wrong there). {@link #shouldWake} is the wake
 * rule for a logged-off bot. No Minecraft types, so both are unit tested directly.
 */
final class BotSleepLogoffPolicy {

    private BotSleepLogoffPolicy() {}

    enum Decision {
        /** Log off now and come back at dawn. */
        LOG_OFF,
        /** The admin setting is off: stay online and tell the owner once. */
        STAY_SETTING_OFF,
        /** The daylight cycle gamerule is off, so dawn never comes: stay online and say why. */
        STAY_NO_DAYLIGHT_CYCLE,
        /** Nothing to do or say: it slept, was cancelled, is gone, or a safeguard applies. */
        STAY_SILENT
    }

    /**
     * Decides what a bot does after a sleep attempt ends.
     *
     * @param settingOn        "Bots log off when they can't sleep" (default on)
     * @param success          the attempt reported success
     * @param aborted          the task was cancelled or the worker interrupted
     * @param botSleeping      the bot is in a bed right now
     * @param botGone          the bot entity is removed or dead
     * @param bedsWork         the bot's dimension allows beds (not Nether or End)
     * @param mounted          the bot is riding something
     * @param serverStopping   the server is shutting down
     * @param daytime          time of day in the bot's world is before nightfall
     * @param daylightCycleOn  the daylight cycle gamerule is on
     */
    static Decision decide(boolean settingOn,
                           boolean success,
                           boolean aborted,
                           boolean botSleeping,
                           boolean botGone,
                           boolean bedsWork,
                           boolean mounted,
                           boolean serverStopping,
                           boolean daytime,
                           boolean daylightCycleOn) {
        if (success || botSleeping) return Decision.STAY_SILENT;
        if (aborted || botGone || serverStopping) return Decision.STAY_SILENT;
        if (!bedsWork || mounted || daytime) return Decision.STAY_SILENT;
        if (!settingOn) return Decision.STAY_SETTING_OFF;
        if (!daylightCycleOn) return Decision.STAY_NO_DAYLIGHT_CYCLE;
        return Decision.LOG_OFF;
    }

    /**
     * Whether a logged-off bot comes back now.
     *
     * <p>Daytime always wakes it. A bot logged off by the immediate path ({@code dawnOnly}) waits
     * for dawn, unless the daylight cycle has since been turned off (dawn would never come, so it
     * falls back to the older rule). Older entries ({@code dawnOnly} false) keep the older rule:
     * back once a human is in the world and none of them is asleep.
     */
    static boolean shouldWake(boolean daytime,
                              boolean anyHumanInWorld,
                              boolean anyHumanSleeping,
                              boolean dawnOnly,
                              boolean daylightCycleOn) {
        if (daytime) return true;
        if (dawnOnly && daylightCycleOn) return false;
        return anyHumanInWorld && !anyHumanSleeping;
    }

    /**
     * Whether the wake monitor should spawn the bot, or just drop the logged-off entry because
     * the bot is already online again (respawned by hand in the meantime).
     */
    static boolean shouldRespawn(boolean alreadyOnline) {
        return !alreadyOnline;
    }

    /** Day/night from a raw time of day, matching the wake monitor (night starts at 12000). */
    static boolean isDaytime(long timeOfDay) {
        return Math.floorMod(timeOfDay, 24_000L) < 12_000L;
    }
}
