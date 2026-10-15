package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the hardened MCP fork's {@code blocks} dump (one {@code y=N: ["row", ...]} line per level,
 * rows north to south, space-separated cells west to east, {@code .} air, {@code ~} water,
 * {@code !} lava, {@code @} the centre, block ids elsewhere) into {@link VoxelGrid} glyphs
 * (design section 10.1).
 *
 * <p>Block ids map to {@code s} (sand, red sand, gravel: gravity), {@code d} (dirt family),
 * {@code l} (leaves), {@code w} (logs and wood), {@code #} (everything else, sandstone included).
 * With {@code protectSolids} the solid cells become {@code P} / {@code p} (the Spawn-base zone).</p>
 */
public final class BlocksDumpConverter {
    private static final Pattern LEVEL = Pattern.compile("y=(-?\\d+):\\s*\\[(.*)]");
    private static final Pattern ROW = Pattern.compile("\"([^\"]*)\"");

    private BlocksDumpConverter() {
    }

    /** y level to rows (north to south) of tokens (west to east). */
    public static Map<Integer, List<String[]>> parse(String dump) {
        Map<Integer, List<String[]>> levels = new LinkedHashMap<>();
        for (String line : dump.split("\\R")) {
            Matcher m = LEVEL.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            List<String[]> rows = new ArrayList<>();
            Matcher r = ROW.matcher(m.group(2));
            while (r.find()) {
                rows.add(r.group(1).trim().split("\\s+"));
            }
            levels.put(Integer.parseInt(m.group(1)), rows);
        }
        return levels;
    }

    public static char glyph(String token) {
        String t = token.startsWith("minecraft:") ? token.substring("minecraft:".length()) : token;
        if (t.startsWith("@") || t.equals(".")) {
            return '.';
        }
        if (t.equals("~")) {
            return '~';
        }
        if (t.equals("!")) {
            return '!';
        }
        if (t.equals("sand") || t.equals("red_sand") || t.equals("gravel")) {
            return 's';
        }
        if (t.equals("dirt") || t.equals("grass_block") || t.equals("coarse_dirt") || t.equals("podzol")
                || t.equals("rooted_dirt") || t.equals("dirt_path")) {
            return 'd';
        }
        if (t.endsWith("_leaves")) {
            return 'l';
        }
        if (t.endsWith("_log") || t.endsWith("_wood")) {
            return 'w';
        }
        return '#';
    }

    public static char protect(char glyph) {
        return switch (glyph) {
            case 's' -> 'p';
            case '#', 'd', 'w', 'l' -> 'P';
            default -> glyph;
        };
    }

    /**
     * Writes a dump into the grid with its centre column at (cx, cz). Rows are centred on cz and
     * columns on cx, so a 5-wide dump covers cx-2..cx+2.
     */
    public static void apply(VoxelGrid grid, String dump, int cx, int cz, boolean protectSolids) {
        for (Map.Entry<Integer, List<String[]>> level : parse(dump).entrySet()) {
            int y = level.getKey();
            List<String[]> rows = level.getValue();
            int z0 = cz - rows.size() / 2;
            for (int r = 0; r < rows.size(); r++) {
                String[] tokens = rows.get(r);
                int x0 = cx - tokens.length / 2;
                for (int c = 0; c < tokens.length; c++) {
                    char g = glyph(tokens[c]);
                    grid.set(x0 + c, y, z0 + r, protectSolids ? protect(g) : g);
                }
            }
        }
    }
}
