package net.wcfcarolina13.PathFinding;

/**
 * Pure rules for the saved finder choice (1.1.261). The config holds a nullable string
 * ({@code pathfinder}): null means "the default", which is {@link PathFinderChoice#DEFAULT}
 * (UNIFIED). An explicit "unified", "baritone" or "classic" is the player's own pick.
 *
 * <p>The one-time 1.1.261 migration reads the legacy boolean {@code baritonePathfinderEnabled}:
 * false was an explicit classic pick (1.1.240 already moved the old false default to true), so it
 * is kept as "classic"; true was the Baritone default, so it moves to the new default (null).
 * No Minecraft imports.
 */
public final class PathFinderDefaultPolicy {

    public static final String UNIFIED = "unified";
    public static final String BARITONE = "baritone";
    public static final String CLASSIC = "classic";

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
            case BIDIR -> CLASSIC;
        };
    }

    /** What the 1.1.261 migration leaves in the config. */
    public record MigrationResult(String pathfinder, boolean needsSave) {
    }

    /**
     * One-time migration. Already migrated: nothing changes. Not migrated with an explicit string
     * present: the string is kept and only the marker gets stamped. Otherwise the legacy boolean
     * decides: false is "classic", true is the default (null).
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
        return new MigrationResult(legacyBaritone ? null : CLASSIC, true);
    }
}
