package net.wcfcarolina13.GameAI.skills;

import java.util.Comparator;

/**
 * Rules for the mining loop's exploration step, which moves the bot to a new stand when no target is
 * reachable from where it is. Pure (offsets only) so it can be tested without Minecraft types.
 *
 * <p>An exploration step only helps if the bot relocates. The loop counts a move of more than 2 blocks
 * horizontally as a relocation, so a candidate must be far enough that "arrived within 3 blocks of it"
 * still implies a real move, and a navigation that ends near the start is not a success.
 */
public final class ExplorationStepPolicy {
    /** Squared horizontal distance a move must exceed to count as a relocation (more than 2 blocks). */
    public static final int MOVED_DISTANCE_SQ = 4;
    /** Nearest candidate considered: 4 blocks, because navigation accepts arriving within 3. */
    public static final int MIN_CANDIDATE_DISTANCE_SQ = 16;
    /** Navigation attempts per exploration step; each failed GoTo costs seconds of the 60 s job cap. */
    public static final int MAX_TRIES_PER_STEP = 5;

    private ExplorationStepPolicy() {
    }

    public static boolean moved(int dx, int dz) {
        return dx * dx + dz * dz > MOVED_DISTANCE_SQ;
    }

    public static boolean farEnough(int dx, int dz) {
        return dx * dx + dz * dz >= MIN_CANDIDATE_DISTANCE_SQ;
    }

    /** Cosine between the offset and the facing direction; -1 for a zero offset. */
    public static double alignment(int dx, int dz, int facingX, int facingZ) {
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0.0) {
            return -1.0;
        }
        return (dx * facingX + dz * facingZ) / length;
    }

    /** A candidate stand as an offset from the bot, with its learned success score. */
    public record Candidate(int dx, int dz, double score) {
    }

    /** Facing first, then farthest first (the point is to relocate), then learned score. */
    public static Comparator<Candidate> order(int facingX, int facingZ) {
        return Comparator.<Candidate>comparingDouble(c -> -alignment(c.dx(), c.dz(), facingX, facingZ))
                .thenComparingInt(c -> -(c.dx() * c.dx() + c.dz() * c.dz()))
                .thenComparingDouble(c -> -c.score());
    }
}
