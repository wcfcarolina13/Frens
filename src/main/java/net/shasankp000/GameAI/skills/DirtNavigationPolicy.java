package net.shasankp000.GameAI.skills;

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

        double score() {
            // Laplace smoothing: start at 0.5
            return (successes + 1.0) / (successes + failures + 2.0);
        }

        void record(boolean success) {
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
        Stats stats = NAV_STATS.get(new Key(origin, target));
        return stats == null ? 0.5 : stats.score();
    }

    public static void record(BlockPos origin, BlockPos target, boolean success) {
        NAV_STATS.computeIfAbsent(new Key(origin, target), k -> new Stats())
                .record(success);
    }

    private record Key(int dx, int dy, int dz) {
        Key(BlockPos origin, BlockPos target) {
            this(target.getX() - origin.getX(),
                    target.getY() - origin.getY(),
                    target.getZ() - origin.getZ());
        }
    }
}
