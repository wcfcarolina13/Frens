package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.WaterSpotMemory.WaterSpot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for {@link WaterSpotMemory} (score convention: higher = better). */
class WaterSpotMemoryTest {

    private static WaterSpot spot(int x, int z, double score, long tick) {
        return new WaterSpot(x, 64, z, score, tick, WaterSpotMemory.KIND_FISHING);
    }

    @Test
    void dedupeKeepsBetterScoreAndNewerTick() {
        List<WaterSpot> spots = WaterSpotMemory.add(List.of(), spot(0, 0, 2.0, 100L), 16);
        spots = WaterSpotMemory.add(spots, spot(2, 1, 5.0, 400L), 16);
        assertEquals(1, spots.size(), "spots within 4 blocks merge");
        assertEquals(5.0, spots.get(0).score(), 1e-9);
        assertEquals(400L, spots.get(0).lastUsedTick());
        assertEquals(2, spots.get(0).x(), "position of the better-scoring entry wins");
    }

    @Test
    void distinctSpotsBeyondDedupeRadiusAreKept() {
        List<WaterSpot> spots = WaterSpotMemory.add(List.of(), spot(0, 0, 1.0, 10L), 16);
        spots = WaterSpotMemory.add(spots, spot(30, 30, 1.0, 10L), 16);
        assertEquals(2, spots.size());
    }

    @Test
    void differentKindsDoNotMerge() {
        List<WaterSpot> spots = WaterSpotMemory.add(List.of(), spot(0, 0, 1.0, 10L), 16);
        spots = WaterSpotMemory.add(spots,
                new WaterSpot(1, 64, 0, 3.0, 20L, WaterSpotMemory.KIND_IRRIGATION), 16);
        assertEquals(2, spots.size());
        assertTrue(spots.stream().anyMatch(s -> WaterSpotMemory.KIND_IRRIGATION.equals(s.kind())),
                "kind is preserved");
    }

    @Test
    void capDropsWorstRankedEntries() {
        List<WaterSpot> spots = new ArrayList<>();
        List<WaterSpot> acc = List.of();
        for (int i = 0; i < 25; i++) {
            acc = WaterSpotMemory.add(acc, spot(i * 20, 0, i, 100L + i), 16);
        }
        spots.addAll(acc);
        assertEquals(16, spots.size());
        double worst = spots.stream().mapToDouble(WaterSpot::score).min().orElseThrow();
        assertEquals(9.0, worst, 1e-9, "the nine lowest-scoring spots were dropped");
    }

    @Test
    void pruneDropsSpotsOlderThanMaxAge() {
        List<WaterSpot> spots = List.of(spot(0, 0, 1.0, 100L), spot(50, 50, 1.0, 90_000L));
        List<WaterSpot> pruned = WaterSpotMemory.prune(spots, 100_000L, 24_000L);
        assertEquals(1, pruned.size());
        assertEquals(90_000L, pruned.get(0).lastUsedTick());
        assertEquals(2, WaterSpotMemory.prune(spots, 100_000L, 0L).size(), "non-positive age keeps all");
    }

    @Test
    void rankPrefersNearAndHighScoring() {
        WaterSpot near = spot(5, 0, 1.0, 1000L);
        WaterSpot farButGreat = spot(60, 0, 2.0, 1000L);
        List<WaterSpot> ranked = WaterSpotMemory.rank(List.of(farButGreat, near), 0, 64, 0, 1000L);
        assertEquals(near, ranked.get(0), "distance dominates a small score edge");

        WaterSpot slightlyFarther = spot(12, 0, 6.0, 1000L);
        List<WaterSpot> ranked2 = WaterSpotMemory.rank(List.of(near, slightlyFarther), 0, 64, 0, 1000L);
        assertEquals(slightlyFarther, ranked2.get(0), "a big score edge beats a small distance edge");
    }

    @Test
    void rankBreaksTiesByAge() {
        WaterSpot older = spot(10, 0, 1.0, 0L);
        WaterSpot newer = spot(0, 10, 1.0, 48_000L);
        List<WaterSpot> ranked = WaterSpotMemory.rank(List.of(older, newer), 0, 64, 0, 48_000L);
        assertEquals(newer, ranked.get(0));
    }

    @Test
    void removeDropsExactPositionOnly() {
        List<WaterSpot> spots = List.of(spot(0, 0, 1.0, 1L), spot(40, 0, 1.0, 1L));
        List<WaterSpot> after = WaterSpotMemory.remove(spots, 0, 64, 0);
        assertEquals(1, after.size());
        assertEquals(40, after.get(0).x());
        assertEquals(1, WaterSpotMemory.remove(after, 999, 999, 999).size(), "no-op when absent");
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertTrue(WaterSpotMemory.add(null, null, 16).isEmpty());
        assertEquals(1, WaterSpotMemory.add(null, spot(0, 0, 1.0, 1L), 16).size());
        assertTrue(WaterSpotMemory.prune(null, 100L, 10L).isEmpty());
        assertTrue(WaterSpotMemory.rank(null, 0, 0, 0, 0L).isEmpty());
        assertTrue(WaterSpotMemory.remove(null, 0, 0, 0).isEmpty());
        List<WaterSpot> withNullElement = new ArrayList<>();
        withNullElement.add(null);
        assertTrue(WaterSpotMemory.rank(withNullElement, 0, 0, 0, 0L).isEmpty());
    }
}
