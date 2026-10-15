package net.wcfcarolina13.PathFinding.unified;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads {@code src/test/resources/pathfinding/<name>.grid}. Line format (world coordinates):
 * <pre>
 * # comment (only at the start of a directive line)
 * name open_field
 * origin X Y Z          the grid's lowest corner
 * size SX SY SZ
 * tool HAND|WOOD_PICK|STONE_AXE
 * start X Y Z
 * goal X Y Z
 * flags swim=1 break=1 place=0 scaffold=0 sprint=1 downward=0 maxfall=3
 * budget primary=500 failure=2000 nodecap=8000
 * fill X1 Y1 Z1 X2 Y2 Z2 G
 * protect X1 Y1 Z1 X2 Y2 Z2
 * set X Y Z G
 * dump resource/path.txt CX Y CZ [protect]   an MCP blocks dump centred on (CX, CZ); Y is informative
 * layer Y               followed by SZ rows of SX glyphs, north to south, west to east
 * </pre>
 * Directives apply in file order, so later lines override earlier ones (bottom y first).
 */
public final class GridLoader {

    /** A loaded scenario: its grid plus a context builder with the file's flags and budget. */
    public record GridScenario(String name, VoxelGrid grid, int startX, int startY, int startZ,
                               int goalX, int goalY, int goalZ, Map<String, Long> flags) {

        public long flag(String key, long fallback) {
            return flags.getOrDefault(key, fallback);
        }

        /** Builder over this grid with the file's flags (defaults: walkTo budgets, no break/place, swim on). */
        public SearchContext.Builder builder() {
            return builder(grid, goalX, goalY, goalZ);
        }

        public SearchContext.Builder builder(TerrainView terrain, int gx, int gy, int gz) {
            SearchContext.Builder b = SearchContext.builder(terrain, startX, startY, startZ, gx, gy, gz)
                    .allowSwim(flag("swim", 1) != 0)
                    .allowBreak(flag("break", 0) != 0)
                    .allowPlace(flag("place", 0) != 0)
                    .scaffoldCount((int) flag("scaffold", 0))
                    .canSprint(flag("sprint", 1) != 0)
                    .allowDownward(flag("downward", 0) != 0)
                    .maxFall((int) flag("maxfall", SearchContext.DEFAULT_MAX_FALL))
                    .primaryMs(flag("primary", SearchContext.DEFAULT_PRIMARY_MS))
                    .failureMs(flag("failure", SearchContext.DEFAULT_FAILURE_MS));
            if (flags.containsKey("nodecap")) {
                b.nodeCap((int) flag("nodecap", 0));
            }
            return b;
        }
    }

    private GridLoader() {
    }

    public static String resource(String path) {
        try (InputStream in = GridLoader.class.getResourceAsStream("/pathfinding/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource pathfinding/" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static GridScenario load(String name) {
        return parse(name, resource(name + ".grid"));
    }

    public static GridScenario parse(String fallbackName, String text) {
        String[] lines = text.split("\\R");
        String name = fallbackName;
        int[] origin = {0, 0, 0};
        int[] size = null;
        int[] start = null;
        int[] goal = null;
        VoxelGrid.ToolTable tool = VoxelGrid.ToolTable.HAND;
        VoxelGrid grid = null;
        Map<String, Long> flags = new HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "name" -> name = t[1];
                case "origin" -> origin = ints(t, 1, 3);
                case "size" -> size = ints(t, 1, 3);
                case "tool" -> {
                    tool = VoxelGrid.ToolTable.valueOf(t[1]);
                    if (grid != null) {
                        grid.setTool(tool);
                    }
                }
                case "start" -> start = ints(t, 1, 3);
                case "goal" -> goal = ints(t, 1, 3);
                case "flags", "budget" -> {
                    for (int k = 1; k < t.length; k++) {
                        String[] kv = t[k].split("=");
                        flags.put(kv[0], Long.parseLong(kv[1]));
                    }
                }
                case "fill" -> {
                    int[] c = ints(t, 1, 6);
                    grid(grid).fill(c[0], c[1], c[2], c[3], c[4], c[5], t[7].charAt(0));
                }
                case "protect" -> {
                    int[] c = ints(t, 1, 6);
                    grid(grid).protect(c[0], c[1], c[2], c[3], c[4], c[5]);
                }
                case "set" -> {
                    int[] c = ints(t, 1, 3);
                    grid(grid).set(c[0], c[1], c[2], t[4].charAt(0));
                }
                case "dump" -> {
                    int[] c = ints(t, 2, 3);
                    boolean prot = t.length > 5 && t[5].equals("protect");
                    BlocksDumpConverter.apply(grid(grid), resource(t[1]), c[0], c[2], prot);
                }
                case "layer" -> {
                    VoxelGrid g = grid(grid);
                    int y = Integer.parseInt(t[1]);
                    for (int r = 0; r < g.sizeZ; r++) {
                        String row = lines[++i];
                        if (row.length() < g.sizeX) {
                            throw new IllegalArgumentException(name + ": layer " + y + " row " + r + " too short");
                        }
                        for (int c = 0; c < g.sizeX; c++) {
                            g.set(g.originX + c, y, g.originZ + r, row.charAt(c));
                        }
                    }
                }
                default -> throw new IllegalArgumentException(name + ": unknown directive " + t[0]);
            }
            if (grid == null && size != null) {
                grid = new VoxelGrid(origin[0], origin[1], origin[2], size[0], size[1], size[2], tool);
            }
        }
        if (grid == null || start == null || goal == null) {
            throw new IllegalArgumentException(name + ": needs size, start and goal");
        }
        return new GridScenario(name, grid, start[0], start[1], start[2], goal[0], goal[1], goal[2], flags);
    }

    private static VoxelGrid grid(VoxelGrid g) {
        if (g == null) {
            throw new IllegalArgumentException("origin and size must come before cells");
        }
        return g;
    }

    private static int[] ints(String[] t, int from, int n) {
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            out[k] = Integer.parseInt(t[from + k]);
        }
        return out;
    }
}
