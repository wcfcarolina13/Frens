package net.wcfcarolina13.GameAI.services;

/**
 * Whether a block supports a painting, given the painting's bounding box moved one block behind
 * it (into the wall). Pure so it can be tested without Minecraft types.
 *
 * <p>The block's cell is shrunk by {@link #INSET} before the overlap test. The old check grew the
 * cell by 0.01 instead, so every block sharing a face or edge with the wall slab also counted as
 * support, and mining refused the painted block's neighbours and the block behind it. The inset
 * also absorbs floating-point error on the painting's box edges; the slab is 1/16 deep, far more
 * than the inset, so the real support blocks still overlap.
 */
public final class PaintingSupportPolicy {
    static final double INSET = 0.01;

    private PaintingSupportPolicy() {
    }

    public static boolean supports(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   int blockX, int blockY, int blockZ) {
        return overlaps(minX, maxX, blockX) && overlaps(minY, maxY, blockY) && overlaps(minZ, maxZ, blockZ);
    }

    private static boolean overlaps(double min, double max, int block) {
        return min < block + 1 - INSET && max > block + INSET;
    }
}
