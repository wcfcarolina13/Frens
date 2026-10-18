package net.wcfcarolina13.GameAI.services.construction;

import net.wcfcarolina13.GameAI.skills.support.WoodcutBaseAccessPolicy;

import java.util.Locale;
import java.util.Set;

/**
 * Pure rules for a bot's own woodcut scaffold in the Spawn base (1.1.268 ruling). No Minecraft
 * types: callers pass registry ids ({@code minecraft:oak_planks}) and booleans.
 *
 * <p>Ownership is what lets a bot break a block inside the Spawn base, so it must never be
 * seedable: a position is registered only on a real, flagged placement success of a scaffold
 * material, and a break re-checks that the live block is still a scaffold material.
 */
public final class ScaffoldOwnershipPolicy {

    /**
     * ScaffoldService's success reason when a retried placement found the cell already holding a
     * scaffold block. That block may not be the bot's, so it is never registered as owned.
     */
    public static final String PLACED_DETECTED_AFTER_RETRY = "placed-detected-after-retry";

    /**
     * Union of {@code ScaffoldService.SCAFFOLD_BLOCKS} and {@code WoodcutSkill.PILLAR_BLOCKS}, as
     * block/item ids (each of these items places the block of the same id). Kept literal so this
     * class loads under JUnit; update it with either list.
     */
    private static final Set<String> SCAFFOLD_MATERIAL_IDS = Set.of(
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:moss_block",
            "minecraft:pale_moss_block",
            "minecraft:scaffolding",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:stone",
            "minecraft:netherrack",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            "minecraft:cherry_planks",
            "minecraft:mangrove_planks",
            "minecraft:crimson_planks",
            "minecraft:warped_planks"
    );

    private ScaffoldOwnershipPolicy() {
    }

    /** True when {@code itemOrBlockId} names a scaffold material. A bare path means {@code minecraft:}. */
    public static boolean isScaffoldMaterial(String itemOrBlockId) {
        if (itemOrBlockId == null || itemOrBlockId.isBlank()) {
            return false;
        }
        String id = itemOrBlockId.trim().toLowerCase(Locale.ROOT);
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        return SCAFFOLD_MATERIAL_IDS.contains(id);
    }

    /**
     * A flagged placement may register ownership only when it really succeeded, never from the
     * {@link #PLACED_DETECTED_AFTER_RETRY} adoption path.
     */
    public static boolean mayRegister(String placeReason, boolean succeeded) {
        return succeeded && !PLACED_DETECTED_AFTER_RETRY.equals(placeReason);
    }

    /** Breaking needs both: this bot owns the position, and the live block is still scaffold material. */
    public static boolean mayBreakOwned(boolean ownedByThisBot, boolean stillScaffoldMaterial) {
        return ownedByThisBot && stillScaffoldMaterial;
    }

    /**
     * The mutation kind the miner asks the claim gate for: a log or leaves is a tree block; else an
     * owned position still holding scaffold material is an own-scaffold break; else other.
     */
    public static WoodcutBaseAccessPolicy.MutationKind breakKind(boolean treeBlock, boolean ownedByThisBot,
                                                                 boolean stillScaffoldMaterial) {
        if (treeBlock) {
            return WoodcutBaseAccessPolicy.MutationKind.TREE_BLOCK;
        }
        if (mayBreakOwned(ownedByThisBot, stillScaffoldMaterial)) {
            return WoodcutBaseAccessPolicy.MutationKind.OWN_SCAFFOLD_BREAK;
        }
        return WoodcutBaseAccessPolicy.MutationKind.OTHER;
    }

    /**
     * An owned position leaves the registry when the block is gone, or when it is no longer a
     * scaffold material (someone else changed it: forget it, never break it).
     */
    public static boolean shouldForget(boolean ownedByThisBot, boolean blockGone, boolean stillScaffoldMaterial) {
        return ownedByThisBot && (blockGone || !stillScaffoldMaterial);
    }
}
