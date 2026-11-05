package net.wcfcarolina13.GameAI.services;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers that the player told a bot to stop following (or to stay) while a task held it, so the
 * task's end does not hand follow back. No Minecraft types.
 *
 * <p>A task takes the bot out of FOLLOW when it starts ({@code SkillManager.suspendFollowMode}) and
 * restores follow when it ends ({@code resumeFollowMode}). During the task the bot is not in FOLLOW,
 * so "/bot follow stop" found nothing to stop and the resume then restarted follow against the
 * player's explicit instruction. The command now records a veto; the resume consumes it and stays
 * idle. A new suspend clears any stale veto, so one only ever applies to the task it was given in.
 */
public final class FollowResumeVeto {

    private static final Set<UUID> VETOED = ConcurrentHashMap.newKeySet();

    private FollowResumeVeto() {
    }

    /** The player said stop following / stay while a task was running. */
    public static void veto(UUID botId) {
        if (botId != null) {
            VETOED.add(botId);
        }
    }

    /** A task is taking the bot out of follow now: forget any veto left from an earlier task. */
    public static void clearOnSuspend(UUID botId) {
        if (botId != null) {
            VETOED.remove(botId);
        }
    }

    /** True (and cleared) when the resume for this bot must not restore follow. */
    public static boolean consume(UUID botId) {
        return botId != null && VETOED.remove(botId);
    }

    /** Whether a stop-type command should record a veto: only while a task holds the bot. */
    public static boolean shouldVeto(boolean taskActive) {
        return taskActive;
    }

    static void clearAllForTests() {
        VETOED.clear();
    }
}
