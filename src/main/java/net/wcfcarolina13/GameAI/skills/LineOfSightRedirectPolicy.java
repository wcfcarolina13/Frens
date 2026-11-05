package net.wcfcarolina13.GameAI.skills;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a harvest target cannot be mined because another block hides it ("no line of sight
 * (blocked by x, y, z)" from MiningTool), the hiding block is the one the bot can see. If that
 * block is itself a block the job wants (stone in front of stone), mine it instead: the job makes
 * progress and the original target is no longer hidden.
 *
 * <p>Before this, the hidden target went into the skill's excluded set and the next candidate was
 * tried. Inside a stone face most candidates within radius are hidden, so a {@code mining 80 stone}
 * run filled the excluded set and stopped early ("No reachable stone blocks found") with stone in
 * plain view. Pure so it can be tested without Minecraft types.
 */
public final class LineOfSightRedirectPolicy {
    private static final Pattern BLOCKED_BY =
            Pattern.compile("no line of sight \\(blocked by (-?\\d+), (-?\\d+), (-?\\d+)\\)");

    /** A target hidden this many times in one job is excluded; the first time it gets one more try. */
    public static final int HIDDEN_RETRY_LIMIT = 2;

    private LineOfSightRedirectPolicy() {
    }

    /** @param timesHidden how often this target was hidden in this job, counting this time */
    public static boolean excludeHidden(int timesHidden) {
        return timesHidden >= HIDDEN_RETRY_LIMIT;
    }

    /** The hiding block's coordinates from a MiningTool result, or null when the result is not a line-of-sight refusal. */
    public static int[] blockerFrom(String miningResult) {
        if (miningResult == null) {
            return null;
        }
        Matcher m = BLOCKED_BY.matcher(miningResult);
        if (!m.find()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Mine the hiding block instead only when it is a different block, one the job wants, has no
     * block entity (never break a chest or furnace to see past it), is not already excluded, and
     * is within reach.
     */
    public static boolean shouldRedirect(boolean differentBlock, boolean matchesTarget, boolean hasBlockEntity,
                                         boolean excluded, boolean withinReach) {
        return differentBlock && matchesTarget && !hasBlockEntity && !excluded && withinReach;
    }
}
