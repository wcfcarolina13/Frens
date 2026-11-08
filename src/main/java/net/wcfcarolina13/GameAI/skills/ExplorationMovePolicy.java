package net.wcfcarolina13.GameAI.skills;

import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the historical success rate of exploratory reposition steps so we can bias movement toward
 * offsets that previously worked in similar terrain.
 */
public final class ExplorationMovePolicy {

    private static final class Stats {
        int successes;
        int failures;

        // synchronized: worker threads record outcomes concurrently; a bare ++ could lose counts.
        synchronized double score() {
            // Laplace smoothing keeps the prior at 0.5 until we have real data.
            return (successes + 1.0) / (successes + failures + 2.0);
        }

        synchronized void record(boolean success) {
            if (success) {
                successes++;
            } else {
                failures++;
            }
        }
    }

    private static final Map<Key, Stats> MOVE_STATS = new ConcurrentHashMap<>();

    private ExplorationMovePolicy() {
    }

    public static double score(BlockPos origin, BlockPos destination) {
        return score(new Key(origin, destination));
    }

    /** Score for a raw offset (dx, dy, dz); 0.5 when nothing was recorded. */
    static double scoreOffset(int dx, int dy, int dz) {
        return score(new Key(dx, dy, dz));
    }

    private static double score(Key key) {
        Stats stats = MOVE_STATS.get(key);
        return stats == null ? 0.5 : stats.score();
    }

    public static void record(BlockPos origin, BlockPos destination, boolean success) {
        record(new Key(origin, destination), success);
    }

    /** Records an outcome for a raw offset (dx, dy, dz). */
    static void recordOffset(int dx, int dy, int dz, boolean success) {
        record(new Key(dx, dy, dz), success);
    }

    private static void record(Key key, boolean success) {
        MOVE_STATS.computeIfAbsent(key, k -> new Stats()).record(success);
    }

    /** Test seam: forget every recorded outcome. */
    static void clearForTests() {
        MOVE_STATS.clear();
    }

    private record Key(int dx, int dy, int dz) {
        Key(BlockPos origin, BlockPos destination) {
            this(destination.getX() - origin.getX(),
                    destination.getY() - origin.getY(),
                    destination.getZ() - origin.getZ());
        }
    }
}
