package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NaturalSurfacePolicyTest {

    @Test
    void plainGroundIsUnchanged() {
        assertEquals(119, NaturalSurfacePolicy.surfaceY(119, y -> false, NaturalSurfacePolicy.MAX_SKIP));
    }

    @Test
    void towerPostOverAnOpenGapIsNotTheSurface() {
        // Sandbox case: grass at 119, air at 120-121 where the fren stands, tower post at 122.
        Set<Integer> skipped = Set.of(122, 121, 120);
        assertEquals(119, NaturalSurfacePolicy.surfaceY(122, skipped::contains, NaturalSurfacePolicy.MAX_SKIP));
    }

    @Test
    void solidWallDownToTheGroundIsSkipped() {
        Set<Integer> skipped = Set.of(124, 123, 122, 121, 120);
        assertEquals(119, NaturalSurfacePolicy.surfaceY(124, skipped::contains, NaturalSurfacePolicy.MAX_SKIP));
    }

    @Test
    void runawayWalkFallsBackToTheHeightmap() {
        assertEquals(100, NaturalSurfacePolicy.surfaceY(100, y -> true, 5));
    }
}
