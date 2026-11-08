package net.wcfcarolina13.GameAI.skills;

/**
 * Whether a block touching lava is a cobblestone generator's cell. Mining leaves a block that
 * touches lava in place so the lava stays behind it, but a generator's cell always touches lava,
 * and that lava meeting the water beside it is what makes the next block. Only cobblestone with
 * both lava and water beside it counts, so natural stone over a lava pocket is still left alone.
 */
public final class LavaGeneratorPolicy {

    /** A generator refills in about 1.5 s of lava flow; this is the longest the job waits for it. */
    public static final long REGROW_WAIT_MS = 4_000L;

    private LavaGeneratorPolicy() {
    }

    public static boolean isGeneratorCell(boolean cobblestone, boolean touchesLava, boolean touchesWater) {
        return cobblestone && touchesLava && touchesWater;
    }
}
