package net.wcfcarolina13.PathFinding;

import java.util.ArrayList;
import java.util.List;

/**
 * The finders {@link PathFinder} can dispatch to. {@link #label()} is the token the logs use.
 * UNIFIED is the 1.1.242 merged finder; BIDIR and BARITONE stay callable for PathCompare during the
 * field window and go in slice D.
 */
public enum PathFinderChoice {
    BIDIR("bidir"),
    BARITONE("baritone"),
    UNIFIED("unified");

    private final String label;

    PathFinderChoice(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Every finder except this one, in declaration order: what the PathCompare shadow runs. */
    public List<PathFinderChoice> others() {
        List<PathFinderChoice> out = new ArrayList<>(2);
        for (PathFinderChoice c : values()) {
            if (c != this) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Field-window mapping of the persisted boolean {@code baritonePathfinderEnabled} (it stays a
     * boolean until slice D): true (the 1.1.240 migrated default) is UNIFIED, false is BIDIR. BARITONE
     * as a primary exists only in memory, through {@code /bot config pathfinder baritone}.
     */
    public static PathFinderChoice fromLegacyFlag(boolean baritonePathfinderEnabled) {
        return baritonePathfinderEnabled ? UNIFIED : BIDIR;
    }

    /** Parses a command token: unified, baritone, classic/bidir. Anything else (including "default") is null. */
    public static PathFinderChoice parse(String token) {
        if (token == null) {
            return null;
        }
        switch (token.toLowerCase(java.util.Locale.ROOT)) {
            case "unified":
                return UNIFIED;
            case "baritone":
                return BARITONE;
            case "classic":
            case "bidir":
                return BIDIR;
            default:
                return null;
        }
    }
}
