package net.wcfcarolina13.PathFinding;

import java.util.ArrayList;
import java.util.List;

/**
 * The finders {@link PathFinder} can dispatch to. {@link #label()} is the token the logs use.
 * BARITONE is the default; UNIFIED is the 1.1.242 merged finder, opt-in after the 1.1.242 A/B (it
 * reached 46% of walks against the old finders' 61.5%); BIDIR is the classic finder.
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
     * Mapping of the persisted boolean {@code baritonePathfinderEnabled}: true (the 1.1.240 migrated
     * default) is BARITONE, false is BIDIR. UNIFIED as a primary is opt-in and in memory only, through
     * {@code /bot config pathfinder unified [bot]} (1.1.242 A/B ruling).
     */
    public static PathFinderChoice fromLegacyFlag(boolean baritonePathfinderEnabled) {
        return baritonePathfinderEnabled ? BARITONE : BIDIR;
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
