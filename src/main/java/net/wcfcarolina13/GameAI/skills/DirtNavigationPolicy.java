package net.wcfcarolina13.GameAI.skills;

import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight reinforcement tracker for dirt navigation outcomes.
 */
public final class DirtNavigationPolicy {

    private static final class Stats {
        int successes;
        int failures;

        // synchronized: worker threads record outcomes concurrently; a bare ++ could lose counts.
        synchronized double score() {
            // Laplace smoothing: start at 0.5
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

    private static final Map<Key, Stats> NAV_STATS = new ConcurrentHashMap<>();

    private DirtNavigationPolicy() {
    }

    public static double score(BlockPos origin, BlockPos target) {
        return score(new Key(origin, target));
    }

    /** Score for a raw offset (dx, dy, dz); 0.5 when nothing was recorded. */
    static double scoreOffset(int dx, int dy, int dz) {
        return score(new Key(dx, dy, dz));
    }

    private static double score(Key key) {
        Stats stats = NAV_STATS.get(key);
        return stats == null ? 0.5 : stats.score();
    }

    public static void record(BlockPos origin, BlockPos target, boolean success) {
        record(new Key(origin, target), success);
    }

    /** Records an outcome for a raw offset (dx, dy, dz). */
    static void recordOffset(int dx, int dy, int dz, boolean success) {
        record(new Key(dx, dy, dz), success);
    }

    private static void record(Key key, boolean success) {
        NAV_STATS.computeIfAbsent(key, k -> new Stats()).record(success);
    }

    /** Test seam: forget every recorded outcome. */
    static void clearForTests() {
        NAV_STATS.clear();
    }

    private record Key(int dx, int dy, int dz) {
        Key(BlockPos origin, BlockPos target) {
            this(target.getX() - origin.getX(),
                    target.getY() - origin.getY(),
                    target.getZ() - origin.getZ());
        }
    }
}
