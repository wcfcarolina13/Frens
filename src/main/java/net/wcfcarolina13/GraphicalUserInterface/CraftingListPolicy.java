package net.wcfcarolina13.GraphicalUserInterface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure filter/sort rules for the Crafting window list. No Minecraft types: the screen
 * resolves each item's creative-tab category and hands in plain strings.
 */
public final class CraftingListPolicy {

    private CraftingListPolicy() {
    }

    public static final String OTHER = "Other";

    public enum SortMode {
        NAME_ASC("A-Z"),
        NAME_DESC("Z-A"),
        CATEGORY("Category"),
        BASE_TYPE("Base type"),
        LEARNED("Learned");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public SortMode next() {
            SortMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        boolean grouped() {
            return this == CATEGORY || this == BASE_TYPE;
        }
    }

    /**
     * One crafted item. {@code learnedOrder} is its position in the server's history list;
     * {@code categoryOrder} is its creative tab's position (Integer.MAX_VALUE when it has none).
     */
    public record Entry(String id, String label, String category, int categoryOrder, int learnedOrder) {
        public String baseType() {
            return baseTypeOf(id);
        }
    }

    /** A list row: either a group header ({@code entry == null}) or an item. */
    public record Row(String header, Entry entry) {
        public boolean isHeader() {
            return entry == null;
        }
    }

    // Material families, keyed by the id-path tokens that name them. The first token of the
    // path that names a family wins, so "stone_bricks" is Stone and "polished_andesite" is Stone.
    private static final Map<String, String> FAMILY_BY_TOKEN = new LinkedHashMap<>();
    private static final List<String> FAMILY_ORDER = new ArrayList<>();

    private static void family(String family, String... tokens) {
        FAMILY_ORDER.add(family);
        for (String token : tokens) {
            FAMILY_BY_TOKEN.put(token, family);
        }
    }

    static {
        family("Wood", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo",
                "crimson", "warped", "wooden", "planks", "log", "wood", "stick", "sticks", "crafting",
                "chest", "barrel", "ladder", "bowl", "bookshelf", "composter", "lectern");
        family("Stone", "stone", "cobblestone", "cobbled", "deepslate", "andesite", "diorite", "granite",
                "tuff", "blackstone", "basalt", "calcite", "furnace", "stonecutter", "smoker");
        family("Sandstone", "sand", "sandstone");
        family("Brick", "brick", "bricks", "nether");
        family("Clay", "clay", "terracotta", "mud");
        family("Glass", "glass");
        family("Wool", "wool", "carpet", "bed", "banner", "string");
        family("Leather", "leather");
        family("Coal", "coal", "charcoal", "torch", "campfire");
        family("Copper", "copper", "lightning");
        family("Iron", "iron", "chain", "chainmail", "bucket", "shears", "anvil", "hopper", "rail", "minecart");
        family("Gold", "gold", "golden", "clock");
        family("Redstone", "redstone", "repeater", "comparator", "piston", "observer", "dispenser",
                "dropper", "lever", "daylight");
        family("Lapis", "lapis");
        family("Emerald", "emerald");
        family("Diamond", "diamond");
        family("Netherite", "netherite");
        family("Quartz", "quartz");
        family("Prismarine", "prismarine");
        family("End", "purpur", "end");
        family("Amethyst", "amethyst", "spyglass");
        family("Food", "bread", "cake", "cookie", "pie", "stew", "soup");
    }

    /** Material family an item is made from, read from its registry id; {@link #OTHER} when unknown. */
    public static String baseTypeOf(String id) {
        if (id == null || id.isBlank()) {
            return OTHER;
        }
        String path = id.toLowerCase(Locale.ROOT);
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        for (String token : path.split("[_/]")) {
            String family = FAMILY_BY_TOKEN.get(token);
            if (family != null) {
                return family;
            }
        }
        return OTHER;
    }

    static int baseTypeOrder(String family) {
        int idx = FAMILY_ORDER.indexOf(family);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }

    /** Case-insensitive substring match on name, id, category or base type. Blank query matches all. */
    public static boolean matches(Entry entry, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        return contains(entry.label(), q)
                || contains(entry.id(), q)
                || contains(entry.category(), q)
                || contains(entry.baseType(), q);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Filters, sorts, and (for Category / Base type) inserts a header row before each group. */
    public static List<Row> arrange(List<Entry> entries, String query, SortMode mode) {
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (e != null && matches(e, query)) {
                kept.add(e);
            }
        }
        kept.sort(comparator(mode));

        List<Row> rows = new ArrayList<>(kept.size() + 8);
        String currentGroup = null;
        for (Entry e : kept) {
            if (mode.grouped()) {
                String group = groupOf(e, mode);
                if (!group.equals(currentGroup)) {
                    rows.add(new Row(group, null));
                    currentGroup = group;
                }
            }
            rows.add(new Row(null, e));
        }
        return rows;
    }

    private static String groupOf(Entry e, SortMode mode) {
        if (mode == SortMode.CATEGORY) {
            String c = e.category();
            return c == null || c.isBlank() ? OTHER : c;
        }
        return e.baseType();
    }

    private static Comparator<Entry> comparator(SortMode mode) {
        Comparator<Entry> byName = Comparator
                .comparing((Entry e) -> e.label() == null ? "" : e.label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.id() == null ? "" : e.id());
        return switch (mode) {
            case NAME_ASC -> byName;
            case NAME_DESC -> byName.reversed();
            case CATEGORY -> Comparator.comparingInt(Entry::categoryOrder)
                    .thenComparing(e -> groupOf(e, SortMode.CATEGORY))
                    .thenComparing(byName);
            case BASE_TYPE -> Comparator.comparingInt((Entry e) -> baseTypeOrder(e.baseType()))
                    .thenComparing(byName);
            case LEARNED -> Comparator.comparingInt(Entry::learnedOrder);
        };
    }
}
