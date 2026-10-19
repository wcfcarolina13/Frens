package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.UUID;

/**
 * When a bot says "Terminating all current tasks due to threat detections" (finding 37).
 * <p>
 * The line is said once per threat episode, per bot, and only when a threat pause actually
 * stopped a task. An episode starts with the first announcement and ends on the first tick
 * the bot sees no hostile in range. Before 1.1.275 one cooldown timestamp was shared by every
 * bot and the line fired whether or not anything was paused, so it repeated every 4 s,
 * alternating bots, while a hostile stayed in range.
 */
public final class DangerAlertPolicy {

    private DangerAlertPolicy() {
    }

    /** Announce only when the pause stopped something and this episode has not been announced. */
    public static boolean shouldAnnounce(boolean pausedSomething, boolean alreadyAnnouncedThisEpisode) {
        return pausedSomething && !alreadyAnnouncedThisEpisode;
    }

    /** The episode ends on the first scan with no hostile in range. */
    public static boolean shouldResetEpisode(boolean hostilesInRange) {
        return !hostilesInRange;
    }

    /**
     * Decides for {@code botId} and, when the answer is yes, marks its episode announced.
     * {@code episodes} is the caller's per-bot flag map.
     */
    public static boolean tryAnnounce(Map<UUID, Boolean> episodes, UUID botId, boolean pausedSomething) {
        if (episodes == null || botId == null) {
            return false;
        }
        boolean announced = Boolean.TRUE.equals(episodes.get(botId));
        if (!shouldAnnounce(pausedSomething, announced)) {
            return false;
        }
        episodes.put(botId, Boolean.TRUE);
        return true;
    }

    /** Clears {@code botId}'s episode flag when its scan found no hostile in range. */
    public static void onHostileScan(Map<UUID, Boolean> episodes, UUID botId, boolean hostilesInRange) {
        if (episodes == null || botId == null) {
            return;
        }
        if (shouldResetEpisode(hostilesInRange)) {
            episodes.remove(botId);
        }
    }
}
