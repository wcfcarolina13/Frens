package net.wcfcarolina13.GameAI.skills.support;

/**
 * Pure geometry for the woodcut relocation ring sampler in {@link TreeDetector}.
 */
public final class TreeSamplingPolicy {

    private TreeSamplingPolicy() {
    }

    /** Columns sampled on one ring: two per block of radius, capped at 48, never below the caller's floor. */
    public static int samplesForRing(int radius, int minSamplesPerRing) {
        return Math.max(minSamplesPerRing, Math.min(48, radius * 2));
    }

    /**
     * Highest y scanned in a sample column. {@code heightmapSample} is the chunk's raw
     * MOTION_BLOCKING_NO_LEAVES sample, which is {@code world.getTopY(...) - 1}; the scan
     * starts two above the world top and never more than {@code verticalRange} above the origin.
     */
    public static int scanMaxY(int heightmapSample, int originY, int verticalRange) {
        int worldTopY = heightmapSample + 1;
        return Math.min(worldTopY + 2, originY + Math.max(1, verticalRange));
    }
}
