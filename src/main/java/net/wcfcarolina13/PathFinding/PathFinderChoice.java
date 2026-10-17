package net.wcfcarolina13.PathFinding;

import java.util.ArrayList;
import java.util.List;

/**
 * The finders {@link PathFinder} can dispatch to. {@link #label()} is the token the logs use.
 * UNIFIED (the 1.1.242 merged finder) is the default since 1.1.261, after the swapped A/B of
 * 2026-10-17 (docs/testing/PATHFINDER_AB_2026-10-17.md); BARITONE is the opt-in fallback; BIDIR is
 * the classic finder, still selectable.
 */
public enum PathFinderChoice {
    BIDIR("bidir"),
    BARITONE("baritone"),
    UNIFIED("unified");

    /** The finder used when nothing is saved and no bot override applies (1.1.261). */
    public static final PathFinderChoice DEFAULT = UNIFIED;

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
