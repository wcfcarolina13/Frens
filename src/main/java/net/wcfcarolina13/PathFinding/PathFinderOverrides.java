package net.wcfcarolina13.PathFinding;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot finder override for head-to-head data (1.1.240 slice D). Debug/A-B only, in memory
 * only (cleared at SERVER_STOPPING), not a user feature: it goes away when the finders merge.
 * An override wins over the global {@link PathFinder#ACTIVE} choice.
 */
public final class PathFinderOverrides {

    private static final Map<UUID, PathFinderChoice> OVERRIDES = new ConcurrentHashMap<>();

    private PathFinderOverrides() {
    }

    /** Precedence: a per-bot override, else the global choice, else UNIFIED. */
    public static PathFinderChoice resolve(PathFinderChoice override, PathFinderChoice active) {
        if (override != null) {
            return override;
        }
        return active != null ? active : PathFinderChoice.UNIFIED;
    }

    /** The finder this bot plans with right now; a null bot follows the global choice. */
    public static PathFinderChoice resolveFor(UUID botUuid, PathFinderChoice active) {
        return resolve(get(botUuid), active);
    }

    public static PathFinderChoice get(UUID botUuid) {
        return botUuid == null ? null : OVERRIDES.get(botUuid);
    }

    /** Sets the override, or clears it when {@code choice} is null. */
    public static void set(UUID botUuid, PathFinderChoice choice) {
        if (botUuid == null) {
            return;
        }
        if (choice == null) {
            OVERRIDES.remove(botUuid);
        } else {
            OVERRIDES.put(botUuid, choice);
        }
    }

    public static void clearAll() {
        OVERRIDES.clear();
    }

    public static int size() {
        return OVERRIDES.size();
    }
}
