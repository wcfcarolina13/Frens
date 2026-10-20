package net.wcfcarolina13.GameAI.services;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Per-scan memo for "is this cell near an exposed campfire": the same answer as scanning the
 * box x±r, y±1, z±r around the cell for an exposed campfire
 * ({@link BotCampfireAvoidanceService#isNearExposedCampfire}), at a fraction of the reads.
 *
 * <p>Each 16³ chunk section a query's box overlaps is handed to the {@link SectionScanner}
 * once. The scanner reports every exposed campfire in that section (the world-backed one
 * asks the section palette {@code hasAny(campfire)} first, false almost everywhere), and each
 * reported campfire marks its berth, the box x±r, y±1, z±r around it. The berth test is the
 * box test turned around (|dx| ≤ r, |dy| ≤ 1, |dz| ≤ r is symmetric), and a campfire inside a
 * cell's box lies in a section that box overlaps, so the answer is exact. Model:
 * {@code PathFinder.CampfireMemo}.
 *
 * <p>Pure (no Minecraft types), so the geometry is unit-tested. Not thread-safe.
 */
public final class CampfireBerthMemo {

    public static final int RADIUS_Y = 1;

    /** Receives one exposed campfire's block coordinates. */
    @FunctionalInterface
    public interface CampfireSink {
        void accept(int x, int y, int z);
    }

    /** Reports every exposed campfire in section (sx, sy, sz); reports nothing for unloaded or out-of-world sections. */
    @FunctionalInterface
    public interface SectionScanner {
        void scan(int sectionX, int sectionY, int sectionZ, CampfireSink sink);
    }

    private final int radiusXZ;
    private final SectionScanner scanner;
    private final LongOpenHashSet scannedSections = new LongOpenHashSet();
    private final LongOpenHashSet dangerCells = new LongOpenHashSet();

    /** {@code radius} matches {@code isNearExposedCampfire}'s: clamped to at least 1. */
    public CampfireBerthMemo(int radius, SectionScanner scanner) {
        this.radiusXZ = Math.max(1, radius);
        this.scanner = scanner;
    }

    public boolean isNear(int x, int y, int z) {
        for (int sx = (x - radiusXZ) >> 4; sx <= (x + radiusXZ) >> 4; sx++) {
            for (int sz = (z - radiusXZ) >> 4; sz <= (z + radiusXZ) >> 4; sz++) {
                for (int sy = (y - RADIUS_Y) >> 4; sy <= (y + RADIUS_Y) >> 4; sy++) {
                    scanSection(sx, sy, sz);
                }
            }
        }
        return !dangerCells.isEmpty() && dangerCells.contains(pack(x, y, z));
    }

    private void scanSection(int sx, int sy, int sz) {
        if (!scannedSections.add(pack(sx, sy, sz))) {
            return;
        }
        scanner.scan(sx, sy, sz, this::markBerth);
    }

    private void markBerth(int cx, int cy, int cz) {
        for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
            for (int dy = -RADIUS_Y; dy <= RADIUS_Y; dy++) {
                for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                    dangerCells.add(pack(cx + dx, cy + dy, cz + dz));
                }
            }
        }
    }

    /** Same bit layout as {@code BlockPos.asLong} (26 x, 26 z, 12 y); section coords fit it too. */
    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
