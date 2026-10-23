package net.wcfcarolina13.GameAI.services;

/**
 * Which steps of the bot respawn reset run, by why the bot was respawned. No Minecraft types.
 *
 * <p>{@code PlayerManager.respawnPlayer} fires {@code AFTER_RESPAWN} both after a death and when a
 * living bot leaves the End through the exit portal ({@code alive == true}). Since 1.1.291 both make
 * a new {@code createFakePlayer} entity, so the entity-bound parts of the reset must run either way:
 * re-register the bot, restart the auto-face loop (it captured the old entity), abort the running
 * task (its skill holds the old entity), clear the ride sync state, the dead flags and the respawn
 * tracker. The death-only parts would undo a trip the bot is still on: dropping its follow or base
 * target, setting it idle, teleporting it to its respawn point, the "regrouped" chat line and the
 * soul's respawn note.
 *
 * <p>The leash clear runs on both: a dimension change drops the bot's leads as death does, and a
 * stale saved lead would put an animal back on the bot's lead at the next rejoin.
 */
public final class RespawnResetPolicy {

    /** The death-path abort spoken by {@code TaskService.onBotRespawn}. Other policies' tests match it. */
    public static final String DEATH_TASK_ABORT = "§cCurrent task aborted due to respawn.";
    /** The death-path abort latched again by {@code BotEventHandler.onBotRespawn}. */
    public static final String DEATH_LATE_ABORT = "§cTask aborted due to bot respawn.";
    /**
     * The abort for a bot that changed dimension alive. Not "stopped": the soul observer buckets any
     * "stop" text as a manual stop.
     */
    public static final String DIMENSION_TASK_ABORT = "§eTask ended: changed dimension.";

    public enum Cause {
        /** The bot died and is respawning. */
        DEATH,
        /** The bot left the End alive; vanilla respawns it with {@code alive == true}. */
        DIMENSION_EXIT
    }

    /**
     * @param clearTargets       drop the follow and base targets
     * @param resetMode          set the bot idle and re-enable assisting allies
     * @param relocate           resolve the respawn destination and move the bot there (also records
     *                           the last safe position and the remembered spawn)
     * @param regroupChat        say "has regrouped and is ready to re-engage."
     * @param clearLeash         forget the saved leash target and held-lead record
     * @param noteSoulRespawn    record the respawn in the soul observer
     * @param sleepBeforeRestart the auto-face restart's 100 ms pause
     * @param taskAbortMessage   the abort message for the running task
     * @param lateAbortMessage   the second abort latched after relocation, or null for none
     */
    public record Plan(boolean clearTargets, boolean resetMode, boolean relocate, boolean regroupChat,
                       boolean clearLeash, boolean noteSoulRespawn, boolean sleepBeforeRestart,
                       String taskAbortMessage, String lateAbortMessage) {
    }

    private static final Plan DEATH_PLAN = new Plan(true, true, true, true, true, true, true,
            DEATH_TASK_ABORT, DEATH_LATE_ABORT);
    private static final Plan DIMENSION_EXIT_PLAN = new Plan(false, false, false, false, true, false, false,
            DIMENSION_TASK_ABORT, null);

    private RespawnResetPolicy() {
    }

    /** The cause for vanilla's {@code alive} flag: true only for a living bot leaving the End. */
    public static Cause causeOf(boolean alive) {
        return alive ? Cause.DIMENSION_EXIT : Cause.DEATH;
    }

    public static Plan plan(Cause cause) {
        return cause == Cause.DIMENSION_EXIT ? DIMENSION_EXIT_PLAN : DEATH_PLAN;
    }

    public static Plan plan(boolean alive) {
        return plan(causeOf(alive));
    }

    /**
     * Whether a skill hands back the follow it suspended at start. An abort drops it, except the
     * dimension-exit abort: a bot leaving the End alive keeps its trip, follow included.
     */
    public static boolean resumeFollowAfterSkill(boolean abortRequested, String abortReason) {
        return !abortRequested || DIMENSION_TASK_ABORT.equals(abortReason);
    }
}
