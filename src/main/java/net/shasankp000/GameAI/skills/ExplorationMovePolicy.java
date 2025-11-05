package net.shasankp000.GameAI.skills;

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

        double score() {
            // Laplace smoothing keeps the prior at 0.5 until we have real data.
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

    private static final Map<Key, Stats> MOVE_STATS = new ConcurrentHashMap<>();

    private ExplorationMovePolicy() {
    }

    public static double score(BlockPos origin, BlockPos destination) {
        Stats stats = MOVE_STATS.get(new Key(origin, destination));
        return stats == null ? 0.5 : stats.score();
    }

    public static void record(BlockPos origin, BlockPos destination, boolean success) {
        MOVE_STATS.computeIfAbsent(new Key(origin, destination), key -> new Stats())
                .record(success);
    }

    private record Key(int dx, int dy, int dz) {
        Key(BlockPos origin, BlockPos destination) {
            this(destination.getX() - origin.getX(),
                    destination.getY() - origin.getY(),
                    destination.getZ() - origin.getZ());
        }
    }
}
