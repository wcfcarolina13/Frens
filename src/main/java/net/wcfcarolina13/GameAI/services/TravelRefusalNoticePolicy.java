package net.wcfcarolina13.GameAI.services;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps automatic retry chatter bounded while preserving direct command feedback. */
final class TravelRefusalNoticePolicy {
    static final long REPEAT_TICKS = 3L * 60L * 20L;
    private record Notice(String reason, long tick) {}
    private final Map<UUID, Notice> notices = new HashMap<>();

    synchronized boolean shouldNotify(UUID bot, String reason, boolean automatic, long nowTick) {
        if (!automatic) return true;
        Notice previous = notices.get(bot);
        if (previous != null && previous.reason().equals(reason)
                && nowTick >= previous.tick() && nowTick - previous.tick() < REPEAT_TICKS) {
            return false;
        }
        notices.put(bot, new Notice(reason, nowTick));
        return true;
    }

    synchronized void clear() {
        notices.clear();
    }
}
