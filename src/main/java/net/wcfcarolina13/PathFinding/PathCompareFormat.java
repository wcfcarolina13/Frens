package net.wcfcarolina13.PathFinding;

/**
 * The one {@code [PathCompare]} log line (1.1.240 slice D). The format is FIXED:
 * tools/logs/frens_log_report.py parses it with {@code (\w+)=(\S+)} pairs and
 * {@code ^(-?\d+)ms/(-?\d+)/(\w+)} finder values, so no value may contain a space.
 */
public final class PathCompareFormat {

    public static final String EXEC = "exec";
    public static final String NOEXEC = "noexec";
    public static final String NONE = "none";

    private PathCompareFormat() {
    }

    /** none when there is no path, else exec/noexec from the per-pair re-check. */
    public static String status(int pathLen, boolean allPairsExecutable) {
        if (pathLen <= 0) {
            return NONE;
        }
        return allPairsExecutable ? EXEC : NOEXEC;
    }

    /**
     * True when two consecutive nodes are one move apart: at most one block on each horizontal
     * axis. Height is left to {@link PathMovePolicy#isExecutable}.
     */
    public static boolean isAdjacentStep(int dx, int dz) {
        return Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
    }

    /** One finder's value: {@code <ms>ms/<len>/<status>}. */
    public static String finderValue(long ms, int len, String status) {
        return ms + "ms/" + Math.max(0, len) + "/" + status;
    }

    public static String line(String botName,
                              int fromX, int fromY, int fromZ,
                              int toX, int toY, int toZ,
                              PathFinderChoice primary,
                              long bidirMs, int bidirLen, String bidirStatus,
                              long baritoneMs, int baritoneLen, String baritoneStatus) {
        return "[PathCompare] bot=" + sanitize(botName)
                + " from=" + fromX + "," + fromY + "," + fromZ
                + " to=" + toX + "," + toY + "," + toZ
                + " primary=" + primary.label()
                + " bidir=" + finderValue(bidirMs, bidirLen, bidirStatus)
                + " baritone=" + finderValue(baritoneMs, baritoneLen, baritoneStatus);
    }

    /** Bot names never carry whitespace in practice; guard anyway so the k=v parse holds. */
    static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.replaceAll("\\s", "_");
    }
}
