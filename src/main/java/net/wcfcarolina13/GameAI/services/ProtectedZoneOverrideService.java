package net.wcfcarolina13.GameAI.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot, time-bounded grant that lets a bot break/place blocks inside
 * protected zones it would otherwise be refused from. Granted by the user
 * pressing Resume after a zone-rejected skill ({@link SkillResumeService}).
 *
 * <p>Scope is intentionally one-shot and short-lived: 60 s TTL or until the
 * skill that requested it completes. The override is global per-bot for the
 * window — i.e. covers every zone the skill might touch — because the skill's
 * full reach isn't known up front (a stripmine can wander, an excavate can
 * straddle multiple zones). Auto-clear on /bot stop / death / abort prevents
 * forgotten overrides.
 */
public final class ProtectedZoneOverrideService {

    private static final Logger LOGGER = LoggerFactory.getLogger("zone-override");

    private static final Map<UUID, Long> ACTIVE_UNTIL_MS = new ConcurrentHashMap<>();

    private ProtectedZoneOverrideService() {}

    public static void grantOverride(UUID botUuid, long durationMs) {
        if (botUuid == null || durationMs <= 0L) {
            return;
        }
        long until = System.currentTimeMillis() + durationMs;
        ACTIVE_UNTIL_MS.put(botUuid, until);
        LOGGER.info("zone-override: granted to {} for {}ms", botUuid, durationMs);
    }

    public static boolean hasActiveOverride(UUID botUuid) {
        if (botUuid == null) {
            return false;
        }
        Long until = ACTIVE_UNTIL_MS.get(botUuid);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            ACTIVE_UNTIL_MS.remove(botUuid);
            return false;
        }
        return true;
    }

    public static void clearOverride(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        if (ACTIVE_UNTIL_MS.remove(botUuid) != null) {
            LOGGER.info("zone-override: cleared for {}", botUuid);
        }
    }

    public static long getRemainingMs(UUID botUuid) {
        if (botUuid == null) {
            return 0L;
        }
        Long until = ACTIVE_UNTIL_MS.get(botUuid);
        if (until == null) {
            return 0L;
        }
        return Math.max(0L, until - System.currentTimeMillis());
    }
}
