package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Geometry from vanilla PaintingEntity.calculateBoundingBox (1.21.11): the box is the variant's
 * width x height, 1/16 deep, flush against the wall face. Here a 1x1 painting hangs in air cell
 * (0,64,1) facing +Z on wall block (0,64,0); moved one block behind (-Z) the slab is
 * x 0..1, y 64..65, z 0..0.0625, inside the wall block's back.
 */
class PaintingSupportPolicyTest {
    private static boolean behindSupports(int x, int y, int z) {
        return PaintingSupportPolicy.supports(0, 64, 0, 1, 65, 0.0625, x, y, z);
    }

    @Test
    void wallBlockSupports() {
        assertTrue(behindSupports(0, 64, 0));
    }

    @Test
    void sideNeighboursDoNotSupport() {
        assertFalse(behindSupports(1, 64, 0));
        assertFalse(behindSupports(-1, 64, 0));
        assertFalse(behindSupports(0, 65, 0));
        assertFalse(behindSupports(0, 63, 0));
    }

    @Test
    void blockBehindWallDoesNotSupport() {
        assertFalse(behindSupports(0, 64, -1));
    }

    @Test
    void diagonalNeighbourDoesNotSupport() {
        assertFalse(behindSupports(1, 65, 0));
    }

    @Test
    void wideBoxCoversEveryWallBlockItSpans() {
        // 2x2 painting: x 0..2, y 64..66
        for (int x = 0; x <= 1; x++) {
            for (int y = 64; y <= 65; y++) {
                assertTrue(PaintingSupportPolicy.supports(0, 64, 0, 2, 66, 0.0625, x, y, 0));
            }
        }
        assertFalse(PaintingSupportPolicy.supports(0, 64, 0, 2, 66, 0.0625, 2, 64, 0));
    }

    @Test
    void toleratesFloatingPointErrorOnEdges() {
        assertFalse(PaintingSupportPolicy.supports(-1e-9, 64, 0, 1 + 1e-9, 65, 0.0625, 1, 64, 0));
        assertTrue(PaintingSupportPolicy.supports(1e-9, 64 + 1e-9, 1e-9, 1 - 1e-9, 65 - 1e-9, 0.0625, 0, 64, 0));
    }

    @Test
    void negativeFacingSlabStillHitsWall() {
        // Painting facing -Z on wall block (0,64,0): slab moved behind lies at z 0.9375..1.
        assertTrue(PaintingSupportPolicy.supports(0, 64, 0.9375, 1, 65, 1, 0, 64, 0));
        assertFalse(PaintingSupportPolicy.supports(0, 64, 0.9375, 1, 65, 1, 0, 64, 1));
    }
}
