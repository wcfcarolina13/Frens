package net.wcfcarolina13.PathFinding.unified;

/**
 * The cells a search may land on. x,z: start..goal plus a margin; y: the depth floor
 * {@code min(start.y, goal.y) - 2} (hard limit, night safety) up to {@code max + 16}.
 *
 * <p>Node keys are packed relative to the box (x 25 bits, z 25 bits, y 9 bits), so the box is at
 * most 2^25 wide and 512 tall; the constructor enforces it.</p>
 */
public record SearchBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static final int MARGIN = 24;
    public static final int FAR_MARGIN = 48;
    /** Goals farther than this (horizontal Euclidean) get {@link #FAR_MARGIN}. */
    public static final int FAR_GOAL = 60;
    public static final int DEPTH_FLOOR = 2;
    public static final int HEADROOM = 16;
    public static final int MAX_WIDTH = 1 << 25;
    public static final int MAX_HEIGHT = 512;

    public SearchBox {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("empty search box");
        }
        if ((long) maxX - minX >= MAX_WIDTH || (long) maxZ - minZ >= MAX_WIDTH) {
            throw new IllegalArgumentException("search box wider than 2^25");
        }
        if ((long) maxY - minY >= MAX_HEIGHT) {
            throw new IllegalArgumentException("search box taller than 512");
        }
    }

    /** The design's default box around start and goal, clamped to the terrain's y range. */
    public static SearchBox around(int sx, int sy, int sz, int gx, int gy, int gz, int terrainMinY, int terrainMaxY) {
        double dx = gx - sx;
        double dz = gz - sz;
        int margin = Math.sqrt(dx * dx + dz * dz) > FAR_GOAL ? FAR_MARGIN : MARGIN;
        int minY = Math.max(terrainMinY, Math.min(sy, gy) - DEPTH_FLOOR);
        int maxY = Math.min(terrainMaxY, Math.max(sy, gy) + HEADROOM);
        if (maxY < minY) {
            maxY = minY;
        }
        return new SearchBox(Math.min(sx, gx) - margin, minY, Math.min(sz, gz) - margin,
                Math.max(sx, gx) + margin, maxY, Math.max(sz, gz) + margin);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Node key relative to the box: x 25 bits, z 25 bits, y 9 bits, swimRun 4 bits. */
    public long key(int x, int y, int z, int swimRun) {
        long rx = x - minX;
        long rz = z - minZ;
        long ry = y - minY;
        return (rx << 38) | (rz << 13) | (ry << 4) | (swimRun & 0xF);
    }
}
