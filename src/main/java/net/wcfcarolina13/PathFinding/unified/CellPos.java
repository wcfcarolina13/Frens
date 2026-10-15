package net.wcfcarolina13.PathFinding.unified;

/**
 * Packed cell coordinates in vanilla's {@code BlockPos.asLong} layout (verified with javap on the
 * 1.21.11 Yarn jar: x in the top 26 bits at shift 38, z in 26 bits at shift 12, y in the low 12 bits),
 * so {@code PlanAdapter} can call {@code BlockPos.fromLong} on a {@link PlannedMove} cell directly.
 */
public final class CellPos {
    private static final int BITS_XZ = 26;
    private static final int BITS_Y = 12;
    private static final long MASK_XZ = (1L << BITS_XZ) - 1L;
    private static final long MASK_Y = (1L << BITS_Y) - 1L;
    private static final int SHIFT_Z = BITS_Y;
    private static final int SHIFT_X = BITS_Y + BITS_XZ;

    private CellPos() {
    }

    public static long pack(int x, int y, int z) {
        return ((x & MASK_XZ) << SHIFT_X) | (y & MASK_Y) | ((z & MASK_XZ) << SHIFT_Z);
    }

    public static int x(long packed) {
        return (int) (packed >> SHIFT_X);
    }

    public static int y(long packed) {
        return (int) (packed << (64 - BITS_Y) >> (64 - BITS_Y));
    }

    public static int z(long packed) {
        return (int) (packed << (64 - SHIFT_X) >> (64 - BITS_XZ));
    }

    public static String format(long packed) {
        return x(packed) + "," + y(packed) + "," + z(packed);
    }
}
