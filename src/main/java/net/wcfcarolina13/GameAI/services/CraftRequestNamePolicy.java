package net.wcfcarolina13.GameAI.services;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure parser for the item name given to a craft request. Material-qualified tool names such as
 * {@code stone_axe}, {@code "wood pickaxe"} or {@code minecraft:golden_hoe} become the generic tool
 * name the craft dispatcher knows ({@code axe}, {@code pickaxe}, ...) plus the material family the
 * tool-crafting code accepts ({@code wood}, {@code stone}, {@code iron}, {@code gold},
 * {@code diamond}; {@code netherite} is reported so the caller can refuse it). Every other name is
 * only normalised (trimmed, lower-cased, {@code minecraft:} stripped, spaces to underscores) and
 * carries no material. No Minecraft imports — unit tested directly.
 */
public final class CraftRequestNamePolicy {

    /** A parsed request: the name to dispatch on and the material the name asked for, or null. */
    public record Parsed(String genericName, String material) {
        public boolean hasMaterial() {
            return material != null;
        }
    }

    private static final Set<String> TOOL_KINDS = Set.of("axe", "pickaxe", "shovel", "hoe", "sword");

    private static final Map<String, String> MATERIAL_WORDS = Map.of(
            "wood", "wood",
            "wooden", "wood",
            "stone", "stone",
            "iron", "iron",
            "gold", "gold",
            "golden", "gold",
            "diamond", "diamond",
            "netherite", "netherite");

    private CraftRequestNamePolicy() {
    }

    public static Parsed parse(String raw) {
        String name = normalize(raw);
        int cut = name.lastIndexOf('_');
        if (cut <= 0 || cut == name.length() - 1) {
            return new Parsed(name, null);
        }
        String material = MATERIAL_WORDS.get(name.substring(0, cut));
        String kind = toolKind(name.substring(cut + 1));
        if (material == null || kind == null) {
            return new Parsed(name, null);
        }
        return new Parsed(kind, material);
    }

    /**
     * Whether {@code word} names a tool material ("stone", "wooden", "Golden", ...). The
     * {@code /bot craft <item> <material>} branch uses it to tell a material from a bot's name,
     * since the bare-string material argument also matches {@code /bot craft axe Jake}.
     */
    public static boolean isMaterialWord(String word) {
        return MATERIAL_WORDS.containsKey(normalize(word));
    }

    /**
     * Whether the trailing word of {@code /bot craft <item> [amount] <word>} names the bot rather
     * than a material. Only a live bot's name can be a bot. A material word stays a material only
     * when the item takes one, i.e. it is a bare tool kind with no material of its own: so
     * {@code stone_axe Iron} goes to the bot Iron, while {@code axe Iron} stays an iron axe (the
     * explicit bot slot {@code axe iron Iron} reaches the bot there). Letter case plays no part.
     */
    public static boolean trailingWordIsBot(String item, String word, boolean namesLiveBot) {
        return namesLiveBot && (!isMaterialWord(word) || !takesMaterialArg(item));
    }

    /** True when {@code item} is a bare tool kind ("axe", "pickaxes") with no material in its name. */
    static boolean takesMaterialArg(String item) {
        Parsed parsed = parse(item);
        return !parsed.hasMaterial() && toolKind(parsed.genericName()) != null;
    }

    /** Trim, lower-case, drop a {@code minecraft:} prefix, and join words with single underscores. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        name = name.replaceAll("[\\s-]+", "_").replaceAll("_+", "_");
        if (name.startsWith("_")) {
            name = name.substring(1);
        }
        if (name.endsWith("_")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static String toolKind(String word) {
        if (TOOL_KINDS.contains(word)) {
            return word;
        }
        if (word.endsWith("s") && TOOL_KINDS.contains(word.substring(0, word.length() - 1))) {
            return word.substring(0, word.length() - 1);
        }
        return null;
    }
}
