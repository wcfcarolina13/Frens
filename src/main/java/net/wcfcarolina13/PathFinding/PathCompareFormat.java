package net.wcfcarolina13.PathFinding;

import net.wcfcarolina13.PathFinding.unified.PathPlanIds;

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

    /**
     * The 1.1.242 three-finder line: {@code unified=} after {@code baritone=}, then {@code plan=p<n>}
     * (the primary's plan ID, omitted when {@code planId <= 0}). Both additive to the k=v parse.
     */
    public static String line(String botName,
                              int fromX, int fromY, int fromZ,
                              int toX, int toY, int toZ,
                              PathFinderChoice primary,
                              long bidirMs, int bidirLen, String bidirStatus,
                              long baritoneMs, int baritoneLen, String baritoneStatus,
                              long unifiedMs, int unifiedLen, String unifiedStatus,
                              long planId) {
        return line(botName, fromX, fromY, fromZ, toX, toY, toZ, primary,
                bidirMs, bidirLen, bidirStatus, baritoneMs, baritoneLen, baritoneStatus)
                + " unified=" + finderValue(unifiedMs, unifiedLen, unifiedStatus)
                + (planId > 0 ? " plan=" + PathPlanIds.format(planId) : "");
    }

    /**
     * A unified plan's node count, comparable with the old finders' path lengths: moves + 1 (the start),
     * 1 for a FULL plan already at the goal, 0 for NO_PATH.
     */
    public static int unifiedLen(boolean noPath, int moves) {
        return noPath ? 0 : moves + 1;
    }

    /**
     * A unified plan's status: none for NO_PATH, else exec/noexec from {@code MoveSimulator} (not
     * {@link PathFinder#isPathExecutable}, which refuses every planned break or place).
     */
    public static String unifiedStatus(boolean noPath, boolean simulatedExecutable) {
        if (noPath) {
            return NONE;
        }
        return simulatedExecutable ? EXEC : NOEXEC;
    }

    /** Longest detail kept on an outcome line; walk details can run long. */
    static final int OUTCOME_DETAIL_MAX = 80;

    /**
     * The {@code [PathCompareOutcome]} line logged when a planned walk ends, so the analyzer can
     * join the primary walk's real outcome to the async {@code [PathCompare]} line for the same
     * bot and target. {@code reached} is the walk's own success flag (distance tolerance applies),
     * {@code null} gives {@code n/a}. {@code detail} is always the last field.
     */
    public static String outcomeLine(String botName, int x, int y, int z, Boolean reached, String detail) {
        return outcomeLine(botName, x, y, z, reached, detail, 0L);
    }

    /**
     * As above with the plan ID of the walk's last plan (1.1.242): {@code plan=p<n>} sits before
     * {@code detail=} so detail stays the last field. {@code planId <= 0} (a walk that never planned)
     * omits the field and the analyzer falls back to its time-window join.
     */
    public static String outcomeLine(String botName, int x, int y, int z, Boolean reached, String detail,
                                     long planId) {
        return "[PathCompareOutcome] bot=" + sanitize(botName)
                + " to=" + x + "," + y + "," + z
                + " reached=" + (reached == null ? "n/a" : reached ? "yes" : "no")
                + (planId > 0 ? " plan=" + PathPlanIds.format(planId) : "")
                + " detail=" + sanitizeDetail(detail);
    }

    /**
     * One token: whitespace runs become {@code _} and {@code =} becomes {@code :}, so the k=v parse
     * holds and the detail never trips the analyzer's phrase triggers ({@code walk blocked near}).
     */
    static String sanitizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "-";
        }
        String s = detail.strip().replaceAll("\\s+", "_").replace('=', ':');
        return s.length() > OUTCOME_DETAIL_MAX ? s.substring(0, OUTCOME_DETAIL_MAX) : s;
    }

    /** Bot names never carry whitespace in practice; guard anyway so the k=v parse holds. */
    static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.replaceAll("\\s", "_");
    }
}
