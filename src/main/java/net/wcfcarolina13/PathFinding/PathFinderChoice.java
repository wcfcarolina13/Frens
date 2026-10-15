package net.wcfcarolina13.PathFinding;

/**
 * The two finders {@link PathFinder} can dispatch to. Debug/A-B only: this goes away when the
 * finders merge into one (1.1.240 slice D). {@link #label()} is the token the logs use.
 */
public enum PathFinderChoice {
    BIDIR("bidir"),
    BARITONE("baritone");

    private final String label;

    PathFinderChoice(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public PathFinderChoice other() {
        return this == BIDIR ? BARITONE : BIDIR;
    }

    /** Parses a command token: baritone, classic/bidir. Anything else (including "default") is null. */
    public static PathFinderChoice parse(String token) {
        if (token == null) {
            return null;
        }
        switch (token.toLowerCase(java.util.Locale.ROOT)) {
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
