package net.wcfcarolina13.GameAI.services;

import java.util.Map;

/**
 * Pure lookup for the metal armour a {@code /bot craft} name asks for (backlog 1217): iron, golden
 * and diamond helmets, chestplates, leggings and boots, with the material each takes and how many.
 * Counts follow the 1.21.11 recipe data ({@code data/minecraft/recipe/<material>_<piece>.json}) and
 * the minecraft.wiki Helmet, Chestplate, Leggings and Boots crafting grids: helmet 5, chestplate 8,
 * leggings 7, boots 4, all shaped across three columns, so a crafting table is needed. Netherite
 * armour is not crafted (the wiki's Armor page: "Netherite armor is obtained by upgrading diamond
 * armor in a smithing table", with "a netherite ingot and a netherite upgrade smithing template"),
 * so a netherite name is reported for the caller to refuse. Leather keeps its own path and is not
 * listed here. No Minecraft imports.
 */
public final class ArmorCraftPolicy {

    /** One armour craft: the output and material item ids, and the material per piece. */
    public record Piece(String itemId, String materialId, int perPiece) {
    }

    private static final Map<String, Integer> PIECE_COUNTS = Map.of(
            "helmet", 5,
            "chestplate", 8,
            "leggings", 7,
            "boots", 4);

    /** Name prefix (as typed) to the item-id prefix and the material item id. */
    private static final Map<String, String[]> MATERIALS = Map.of(
            "iron", new String[]{"iron", "minecraft:iron_ingot"},
            "gold", new String[]{"golden", "minecraft:gold_ingot"},
            "golden", new String[]{"golden", "minecraft:gold_ingot"},
            "diamond", new String[]{"diamond", "minecraft:diamond"});

    private ArmorCraftPolicy() {
    }

    /**
     * The metal armour piece a normalised craft name ({@link CraftRequestNamePolicy#normalize})
     * names, or {@code null} when it names none (leather, netherite and every other craft).
     */
    public static Piece piece(String normalizedName) {
        String[] parts = split(normalizedName);
        if (parts == null) {
            return null;
        }
        String[] material = MATERIALS.get(parts[0]);
        Integer per = PIECE_COUNTS.get(parts[1]);
        if (material == null || per == null) {
            return null;
        }
        return new Piece("minecraft:" + material[0] + "_" + parts[1], material[1], per);
    }

    /** True for a netherite armour name: made at a smithing table, never crafted. */
    public static boolean isNetheriteArmor(String normalizedName) {
        String[] parts = split(normalizedName);
        return parts != null && "netherite".equals(parts[0]) && PIECE_COUNTS.containsKey(parts[1]);
    }

    private static String[] split(String name) {
        if (name == null) {
            return null;
        }
        int cut = name.lastIndexOf('_');
        if (cut <= 0 || cut == name.length() - 1) {
            return null;
        }
        return new String[]{name.substring(0, cut), name.substring(cut + 1)};
    }
}
