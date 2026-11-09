package net.wcfcarolina13.PathFinding;

/**
 * Pure rules for the saved finder choice (1.1.261). The config holds a nullable string
 * ({@code pathfinder}): null means "the default", which is {@link PathFinderChoice#DEFAULT}
 * (UNIFIED). An explicit "unified" or "baritone" is the player's own pick. A saved "classic" (the
 * bidirectional finder, removed in Slice D) loads as the default.
 *
 * <p>The one-time 1.1.261 migration reads the legacy boolean {@code baritonePathfinderEnabled}. It
 * used to keep a false (an explicit classic pick) as "classic"; with classic gone both values move to
 * the default (null).
 * No Minecraft imports.
 */
public final class PathFinderDefaultPolicy {

    public static final String UNIFIED = "unified";
    public static final String BARITONE = "baritone";

    private PathFinderDefaultPolicy() {
    }

    /** The finder a saved string selects: null, blank or unknown is the default (UNIFIED). */
    public static PathFinderChoice fromSaved(String saved) {
        if (saved == null || saved.isBlank()) {
            return PathFinderChoice.DEFAULT;
        }
        PathFinderChoice parsed = PathFinderChoice.parse(saved.trim());
        return parsed != null ? parsed : PathFinderChoice.DEFAULT;
    }

    /** The string an explicit pick is saved as. Null in, null out (the default). */
    public static String toSaved(PathFinderChoice choice) {
        if (choice == null) {
            return null;
        }
        return switch (choice) {
            case UNIFIED -> UNIFIED;
            case BARITONE -> BARITONE;
        };
    }

    /** A saved token for the classic finder, removed in Slice D: it is cleared to the default on load. */
    public static boolean isRemovedFinderToken(String saved) {
        if (saved == null) {
            return false;
        }
        String t = saved.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("classic") || t.equals("bidir");
    }

    /** What the 1.1.261 migration leaves in the config. */
    public record MigrationResult(String pathfinder, boolean needsSave) {
    }

    /**
     * One-time migration. Already migrated: nothing changes. Not migrated with an explicit string
     * present: the string is kept and only the marker gets stamped. Otherwise the default (null),
     * whatever the legacy boolean says (its false meant classic, which no longer exists).
     *
     * @param saved          the saved {@code pathfinder} string (nullable)
     * @param legacyBaritone the legacy {@code baritonePathfinderEnabled} value, after the 1.1.240 step
     * @param migrated       the {@code unifiedDefaultMigrated} marker
     */
    public static MigrationResult migrate(String saved, boolean legacyBaritone, boolean migrated) {
        if (migrated) {
            return new MigrationResult(saved, false);
        }
        if (saved != null && !saved.isBlank()) {
            return new MigrationResult(saved, true);
        }
        return new MigrationResult(null, true);
    }
}
