package net.wcfcarolina13.PathFinding.unified;

import static net.wcfcarolina13.PathFinding.unified.CellProps.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.wcfcarolina13.GameAI.services.ObstructionMinePolicy;

/**
 * Test terrain: a box of glyph cells in world coordinates (design section 2.1).
 *
 * <p>Glyphs (MCP {@code blocks} style): {@code .} air, {@code ~} water, {@code !} lava, {@code #} stone,
 * {@code d} dirt, {@code s} sand (gravity), {@code l} leaves, {@code w} log (added: trunks for
 * {@code trees_2x2}), {@code m} magma, {@code c} lit campfire (2-block berth, PF semantics: feet cells
 * within dx,dz +-2 and dy +-1 read HAZARD), {@code P} protected stone, {@code p} protected sand,
 * {@code _} bottom slab, {@code L} locked door, {@code n} planks or fence (a floor the executor never
 * breaks on a path: NEVER_BREAK, though its ticks are finite: 60 by hand). Any cell can also be marked protected (a zone box).
 * Cells outside the box read as unloaded (props 0).</p>
 *
 * <p>Break ticks come from {@link ObstructionMinePolicy#groundBreakTicks} with the {@link ToolTable}'s
 * tool, so the numbers match the game rule (stone by hand 150, with a wooden pickaxe 23, dirt and sand
 * 15, leaves 6).</p>
 */
public final class VoxelGrid implements MutableTerrainView {

    /** The single best tool a scenario's bot holds. */
    public enum ToolTable {
        HAND(1f, null), WOOD_PICK(2f, Tool.PICK), STONE_AXE(4f, Tool.AXE);

        final float speed;
        final Tool tool;

        ToolTable(float speed, Tool tool) {
            this.speed = speed;
            this.tool = tool;
        }

        /** Ground break ticks for a glyph, or {@code Integer.MAX_VALUE} when unbreakable. */
        public int ticks(char glyph) {
            Material m = Material.of(glyph);
            if (m == null) {
                return Integer.MAX_VALUE;
            }
            boolean matches = tool != null && tool == m.tool;
            float s = matches ? speed : 1f;
            boolean canHarvest = ObstructionMinePolicy.canHarvest(m.requiresTool, matches);
            return ObstructionMinePolicy.groundBreakTicks(s, m.hardness, canHarvest);
        }
    }

    enum Tool { PICK, AXE }

    private record Material(float hardness, boolean requiresTool, Tool tool) {
        static Material of(char g) {
            return switch (g) {
                case '#', '_' -> new Material(g == '#' ? 1.5f : 2.0f, true, Tool.PICK);
                case 'm' -> new Material(0.5f, true, Tool.PICK);
                case 'd', 's' -> new Material(0.5f, false, null);
                case 'l' -> new Material(0.2f, false, null);
                case 'w', 'n' -> new Material(2.0f, false, Tool.AXE);
                default -> null;
            };
        }
    }

    public final int originX;
    public final int originY;
    public final int originZ;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    private final char[] cells;
    private final boolean[] zone;
    private ToolTable tool;
    private final List<int[]> campfires = new ArrayList<>();

    public VoxelGrid(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ, ToolTable tool) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = new char[sizeX * sizeY * sizeZ];
        this.zone = new boolean[cells.length];
        Arrays.fill(cells, '.');
        this.tool = tool;
    }

    public VoxelGrid copy() {
        VoxelGrid g = new VoxelGrid(originX, originY, originZ, sizeX, sizeY, sizeZ, tool);
        System.arraycopy(cells, 0, g.cells, 0, cells.length);
        System.arraycopy(zone, 0, g.zone, 0, zone.length);
        g.campfires.addAll(campfires);
        return g;
    }

    public ToolTable tool() {
        return tool;
    }

    public void setTool(ToolTable tool) {
        this.tool = tool;
    }

    private int index(int x, int y, int z) {
        int lx = x - originX;
        int ly = y - originY;
        int lz = z - originZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) {
            return -1;
        }
        return (ly * sizeZ + lz) * sizeX + lx;
    }

    public boolean inside(int x, int y, int z) {
        return index(x, y, z) >= 0;
    }

    /** Glyph at a cell, or 0 outside the grid. */
    public char get(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? 0 : cells[i];
    }

    public void set(int x, int y, int z, char glyph) {
        int i = index(x, y, z);
        if (i < 0) {
            throw new IllegalArgumentException("outside grid: " + x + "," + y + "," + z);
        }
        if (cells[i] == 'c') {
            campfires.removeIf(c -> c[0] == x && c[1] == y && c[2] == z);
        }
        cells[i] = glyph;
        if (glyph == 'c') {
            campfires.add(new int[]{x, y, z});
        }
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, char glyph) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                    set(x, y, z, glyph);
                }
            }
        }
    }

    public void protect(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                    int i = index(x, y, z);
                    if (i >= 0) {
                        zone[i] = true;
                    }
                }
            }
        }
    }

    public boolean isProtected(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0) {
            return false;
        }
        char c = cells[i];
        return zone[i] || c == 'P' || c == 'p';
    }

    static boolean solidGlyph(char c) {
        return switch (c) {
            case '#', 'P', 'd', 's', 'p', 'l', 'w', 'm', '_', 'n' -> true;
            default -> false;
        };
    }

    /** Feet cell inside a lit campfire's berth (dx,dz +-2, dy +-1). */
    public boolean inCampfireBerth(int x, int y, int z) {
        for (int[] c : campfires) {
            if (Math.abs(x - c[0]) <= 2 && Math.abs(z - c[2]) <= 2 && Math.abs(y - c[1]) <= 1) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int props(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0) {
            return 0;
        }
        char c = cells[i];
        boolean prot = zone[i] || c == 'P' || c == 'p';
        int p = LOADED | (prot ? PROTECTED : 0);
        switch (c) {
            case '.' -> {
                p |= PASSABLE_BODY;
                if (!prot && solidGlyph(get(x, y - 1, z))) {
                    p |= PLACEABLE;
                }
            }
            case '~' -> p |= solidGlyph(get(x, y - 1, z)) ? WATER_WADE : WATER_DEEP;
            case '!' -> p |= LAVA;
            case '#', 'P', 'd', 'l', 'w' -> p |= SOLID_FLOOR;
            case 's', 'p' -> p |= SOLID_FLOOR | GRAVITY;
            case 'm' -> p |= SOLID_FLOOR | HAZARD;
            case 'n' -> p |= SOLID_FLOOR | NEVER_BREAK;
            case '_' -> p |= SOLID_FLOOR | SLAB_TOP;
            case 'c' -> p |= HAZARD | NEVER_BREAK;
            case 'L' -> p |= LOCKED_DOOR;
            default -> throw new IllegalStateException("unknown glyph '" + c + "'");
        }
        if (!campfires.isEmpty() && c != 'c' && inCampfireBerth(x, y, z)) {
            p |= HAZARD;
        }
        return p;
    }

    @Override
    public int breakTicks(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0) {
            return (int) ActionCosts.COST_INF;
        }
        char c = cells[i];
        if (zone[i] || c == 'P' || c == 'p') {
            return (int) ActionCosts.COST_INF;
        }
        int t = tool.ticks(c);
        return t == Integer.MAX_VALUE ? (int) ActionCosts.COST_INF : t;
    }

    @Override
    public int minY() {
        return originY;
    }

    @Override
    public int maxY() {
        return originY + sizeY - 1;
    }

    @Override
    public void removeBlock(int x, int y, int z) {
        set(x, y, z, '.');
    }

    @Override
    public void placeBlock(int x, int y, int z) {
        set(x, y, z, 'd');
    }

    /** One y level as rows (north to south) of glyphs (west to east), for debugging. */
    public String layer(int y) {
        StringBuilder sb = new StringBuilder();
        for (int z = originZ; z < originZ + sizeZ; z++) {
            for (int x = originX; x < originX + sizeX; x++) {
                char c = get(x, y, z);
                sb.append(c == '.' && zone[index(x, y, z)] ? ',' : c);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
