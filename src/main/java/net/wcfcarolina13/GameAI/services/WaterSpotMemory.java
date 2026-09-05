package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure (Minecraft-free) helper for remembering water locations a bot has actually
 * used, so it can go back to them instead of re-scanning blindly.
 *
 * <p>Score convention: <b>higher is better</b>. Callers that compute a
 * "lower is better" quality (as {@code FishingSkill} does) must negate before
 * storing. {@link #rank} subtracts the score, so a higher score ranks earlier.
 *
 * <p>All methods are static, null-safe, and return new immutable lists; the
 * caller owns persistence.
 */
public final class WaterSpotMemory {

    /** Default number of spots retained per bot, per world. */
    public static final int DEFAULT_CAP = 16;
    /** Two spots within this many blocks are considered the same spot. */
    public static final double DEDUPE_RADIUS = 4.0D;

    public static final String KIND_FISHING = "fishing";
    public static final String KIND_IRRIGATION = "irrigation";

    private WaterSpotMemory() {
    }

    /**
     * A remembered water block.
     *
     * @param kind {@link #KIND_FISHING} or {@link #KIND_IRRIGATION}
     * @param score higher = better
     */
    public record WaterSpot(int x, int y, int z, double score, long lastUsedTick, String kind) {
    }

    private static List<WaterSpot> copy(List<WaterSpot> spots) {
        List<WaterSpot> out = new ArrayList<>();
        if (spots != null) {
            for (WaterSpot s : spots) {
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static double distSq(WaterSpot a, int x, int y, int z) {
        double dx = a.x() - (double) x;
        double dy = a.y() - (double) y;
        double dz = a.z() - (double) z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Adds {@code spot}, merging with any existing spot of the same kind within
     * {@link #DEDUPE_RADIUS} (keeping the better score and the newer tick), then
     * trims to {@code cap} by dropping the worst entries (lowest score, oldest tick
     * as the tie-break).
     */
    public static List<WaterSpot> add(List<WaterSpot> spots, WaterSpot spot, int cap) {
        List<WaterSpot> out = copy(spots);
        if (spot == null) {
            return List.copyOf(out);
        }
        int effectiveCap = cap > 0 ? cap : DEFAULT_CAP;
        double dedupeSq = DEDUPE_RADIUS * DEDUPE_RADIUS;

        WaterSpot merged = spot;
        for (int i = out.size() - 1; i >= 0; i--) {
            WaterSpot existing = out.get(i);
            if (!java.util.Objects.equals(existing.kind(), spot.kind())) {
                continue;
            }
            if (distSq(existing, spot.x(), spot.y(), spot.z()) > dedupeSq) {
                continue;
            }
            // Keep the position of whichever entry scored better, but always the
            // newer tick and the better score.
            WaterSpot anchor = existing.score() > merged.score() ? existing : merged;
            merged = new WaterSpot(
                    anchor.x(), anchor.y(), anchor.z(),
                    Math.max(existing.score(), merged.score()),
                    Math.max(existing.lastUsedTick(), merged.lastUsedTick()),
                    anchor.kind());
            out.remove(i);
        }
        out.add(merged);

        if (out.size() > effectiveCap) {
            out.sort(Comparator.comparingDouble(WaterSpot::score).reversed()
                    .thenComparing(Comparator.comparingLong(WaterSpot::lastUsedTick).reversed()));
            out = new ArrayList<>(out.subList(0, effectiveCap));
        }
        return List.copyOf(out);
    }

    /** Drops spots older than {@code maxAgeTicks}. Non-positive ages keep everything. */
    public static List<WaterSpot> prune(List<WaterSpot> spots, long nowTick, long maxAgeTicks) {
        List<WaterSpot> out = copy(spots);
        if (maxAgeTicks <= 0L) {
            return List.copyOf(out);
        }
        List<WaterSpot> kept = new ArrayList<>();
        for (WaterSpot s : out) {
            long age = nowTick - s.lastUsedTick();
            if (age <= maxAgeTicks) {
                kept.add(s);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Orders spots best-first by {@code sqrt(distSq) - score * 2.0 + ageTicks / 24000.0}
     * (lower is better: near, high-scoring and recent wins).
     */
    public static List<WaterSpot> rank(List<WaterSpot> spots, int ox, int oy, int oz, long nowTick) {
        List<WaterSpot> out = copy(spots);
        out.sort(Comparator.comparingDouble(s -> rankCost(s, ox, oy, oz, nowTick)));
        return List.copyOf(out);
    }

    static double rankCost(WaterSpot s, int ox, int oy, int oz, long nowTick) {
        double dist = Math.sqrt(distSq(s, ox, oy, oz));
        double ageTicks = Math.max(0L, nowTick - s.lastUsedTick());
        return dist * 1.0D - s.score() * 2.0D + ageTicks / 24000.0D;
    }

    /** Removes every spot at exactly the given block position. */
    public static List<WaterSpot> remove(List<WaterSpot> spots, int x, int y, int z) {
        List<WaterSpot> out = copy(spots);
        out.removeIf(s -> s.x() == x && s.y() == y && s.z() == z);
        return List.copyOf(out);
    }
}
