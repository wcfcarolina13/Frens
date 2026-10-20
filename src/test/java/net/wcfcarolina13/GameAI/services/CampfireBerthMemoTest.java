package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampfireBerthMemoTest {

    /** Exposed campfires grouped by the section that holds them, like a palette walk would find them. */
    private static final class FakeWorld implements CampfireBerthMemo.SectionScanner {
        final List<int[]> campfires = new ArrayList<>();
        final Map<Long, List<int[]>> bySection = new HashMap<>();
        final Map<Long, Integer> scanCounts = new HashMap<>();

        void add(int x, int y, int z) {
            int[] c = {x, y, z};
            campfires.add(c);
            bySection.computeIfAbsent(CampfireBerthMemo.pack(x >> 4, y >> 4, z >> 4), k -> new ArrayList<>()).add(c);
        }

        @Override
        public void scan(int sx, int sy, int sz, CampfireBerthMemo.CampfireSink sink) {
            long key = CampfireBerthMemo.pack(sx, sy, sz);
            scanCounts.merge(key, 1, Integer::sum);
            for (int[] c : bySection.getOrDefault(key, List.of())) {
                sink.accept(c[0], c[1], c[2]);
            }
        }

        /** The original per-cell box scan: x±r, y±1, z±r. */
        boolean bruteForce(int x, int y, int z, int radius) {
            int r = Math.max(1, radius);
            for (int[] c : campfires) {
                if (Math.abs(c[0] - x) <= r && Math.abs(c[1] - y) <= 1 && Math.abs(c[2] - z) <= r) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void matchesBoxScanOnRandomWorldsIncludingSectionEdges() {
        Random rnd = new Random(276L);
        for (int trial = 0; trial < 40; trial++) {
            FakeWorld world = new FakeWorld();
            int count = 1 + rnd.nextInt(12);
            for (int i = 0; i < count; i++) {
                // Around section boundaries (x/z = 0/16/32, y = 64), negative coordinates included.
                world.add(rnd.nextInt(56) - 20, 60 + rnd.nextInt(10), rnd.nextInt(56) - 20);
            }
            int radius = 1 + rnd.nextInt(3);
            CampfireBerthMemo memo = new CampfireBerthMemo(radius, world);
            for (int y = 57; y <= 72; y++) {
                for (int x = -26; x <= 42; x++) {
                    for (int z = -26; z <= 42; z++) {
                        assertEquals(world.bruteForce(x, y, z, radius), memo.isNear(x, y, z),
                                "trial " + trial + " r=" + radius + " at " + x + "," + y + "," + z);
                    }
                }
            }
            // Each section is scanned at most once per memo.
            world.scanCounts.values().forEach(n -> assertEquals(1, n));
        }
    }

    @Test
    void berthIsTwoAcrossAndOneUpOrDownAtRadiusTwo() {
        FakeWorld world = new FakeWorld();
        world.add(15, 64, 15); // corner of a section: the berth reaches three neighbor sections
        CampfireBerthMemo memo = new CampfireBerthMemo(2, world);
        assertTrue(memo.isNear(17, 65, 17));
        assertTrue(memo.isNear(13, 63, 13));
        assertFalse(memo.isNear(18, 64, 15));
        assertFalse(memo.isNear(15, 66, 15));
        assertFalse(memo.isNear(15, 62, 15));
    }

    @Test
    void radiusBelowOneIsClampedLikeTheBoxScan() {
        FakeWorld world = new FakeWorld();
        world.add(0, 64, 0);
        CampfireBerthMemo memo = new CampfireBerthMemo(0, world);
        assertTrue(memo.isNear(1, 64, 1));
        assertFalse(memo.isNear(2, 64, 0));
    }

    @Test
    void noCampfiresMeansNothingIsNear() {
        CampfireBerthMemo memo = new CampfireBerthMemo(2, new FakeWorld());
        assertFalse(memo.isNear(0, 64, 0));
        assertFalse(memo.isNear(-1, -64, -1));
    }
}
