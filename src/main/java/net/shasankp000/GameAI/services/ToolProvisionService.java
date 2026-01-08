package net.shasankp000.GameAI.services;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.BedItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ToolProvisionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("tool-provision");
    private static final int CONTAINER_RADIUS = 12;
    private static final int CONTAINER_YSPAN = 6;

    private ToolProvisionService() {}

    public static boolean ensureTorches(ServerPlayerEntity bot,
                                        ServerCommandSource source,
                                        ServerPlayerEntity commander,
                                        int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.TORCH);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftTorch(historyOwner)) {
            LOGGER.debug("Torch craft blocked: recipe not in history for {}", historyOwner.getName().getString());
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasTorchIngredients(bot, world)) {
            LOGGER.debug("Torch craft blocked: missing coal/charcoal or stick materials.");
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "torch", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.TORCH) >= minCount;
    }

    public static boolean ensurePickaxe(ServerPlayerEntity bot,
                                        ServerCommandSource source,
                                        ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "pickaxe")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftPickaxe(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "stone") > 0;
        }
        if (hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "iron") > 0;
        }
        if (hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "diamond") > 0;
        }
        if (hasPlanksOrLogs(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "wood") > 0;
        }
        return false;
    }

    public static boolean ensureShovel(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "shovel")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftShovel(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "stone") > 0;
        }
        if (hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "iron") > 0;
        }
        if (hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "diamond") > 0;
        }
        if (hasPlanksOrLogs(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "wood") > 0;
        }
        return false;
    }

    public static boolean ensureAxe(ServerPlayerEntity bot,
                                    ServerCommandSource source,
                                    ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "axe")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftAxe(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "axe", 1, "stone") > 0;
        }
        if (hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "axe", 1, "iron") > 0;
        }
        if (hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "axe", 1, "diamond") > 0;
        }
        if (hasPlanksOrLogs(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "axe", 1, "wood") > 0;
        }
        return false;
    }

    public static boolean ensureHoe(ServerPlayerEntity bot,
                                    ServerCommandSource source,
                                    ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "hoe")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftHoe(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "hoe", 1, "stone") > 0;
        }
        if (hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "hoe", 1, "iron") > 0;
        }
        if (hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "hoe", 1, "diamond") > 0;
        }
        if (hasPlanksOrLogs(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "hoe", 1, "wood") > 0;
        }
        return false;
    }

    public static boolean ensureSword(ServerPlayerEntity bot,
                                      ServerCommandSource source,
                                      ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "sword")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftSword(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "sword", 1, "stone") > 0;
        }
        if (hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "sword", 1, "iron") > 0;
        }
        if (hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "sword", 1, "diamond") > 0;
        }
        if (hasPlanksOrLogs(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "sword", 1, "wood") > 0;
        }
        return false;
    }

    public static boolean ensureShears(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "shears")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftShears(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasMaterial(bot, world, Items.IRON_INGOT)) {
            return false;
        }
        return CraftingHelper.craftGeneric(source, bot, historyOwner, "shears", 1, null) > 0;
    }

    public static boolean ensureFishingRod(ServerPlayerEntity bot,
                                           ServerCommandSource source,
                                           ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "fishing_rod")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftFishingRod(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasMaterial(bot, world, Items.STRING) || !hasPlanksOrLogs(bot, world)) {
            return false;
        }
        return CraftingHelper.craftGeneric(source, bot, historyOwner, "fishing_rod", 1, null) > 0;
    }

    public static boolean ensureSaddle(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.SADDLE);
        if (have >= minCount) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        int pulled = withdrawFromNearbyContainers(bot, world, Items.SADDLE, minCount - have);
        have = countInventoryItem(bot, Items.SADDLE);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftSaddle(historyOwner)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "saddle", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.SADDLE) >= minCount;
    }

    public static boolean ensureCarrotOnStick(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "carrot_on_a_stick")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftCarrotOnStick(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasMaterial(bot, world, Items.CARROT)) {
            return false;
        }
        return CraftingHelper.craftGeneric(source, bot, historyOwner, "carrot_on_a_stick", 1, null) > 0;
    }

    public static boolean ensureWarpedFungusOnStick(ServerPlayerEntity bot,
                                                    ServerCommandSource source,
                                                    ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "warped_fungus_on_a_stick")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftWarpedFungusOnStick(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasMaterial(bot, world, Items.WARPED_FUNGUS)) {
            return false;
        }
        return CraftingHelper.craftGeneric(source, bot, historyOwner, "warped_fungus_on_a_stick", 1, null) > 0;
    }

    public static boolean ensureLead(ServerPlayerEntity bot,
                                     ServerCommandSource source,
                                     ServerPlayerEntity commander,
                                     int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.LEAD);
        if (have >= minCount) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        withdrawFromNearbyContainers(bot, world, Items.LEAD, minCount - have);
        have = countInventoryItem(bot, Items.LEAD);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftLead(historyOwner)) {
            return false;
        }
        if (!hasMaterial(bot, world, Items.STRING) || !hasMaterial(bot, world, Items.SLIME_BALL)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "lead", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.LEAD) >= minCount;
    }

    public static boolean ensureBundle(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countBundles(bot);
        if (have >= minCount) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftBundle(historyOwner)) {
            LOGGER.info("Bundle craft: recipe not in history for {}; attempting anyway.",
                    historyOwner != null ? historyOwner.getName().getString() : "unknown");
        }
        if (!ensureBundleCraftSpace(bot)) {
            LOGGER.info("Bundle craft blocked: no space available to craft.");
            return false;
        }
        if (!hasMaterial(bot, world, Items.LEATHER)) {
            if (hasMaterial(bot, world, Items.RABBIT_HIDE)) {
                LOGGER.info("Bundle craft: leather missing; attempting rabbit hide -> leather craft.");
                CraftingHelper.craftGeneric(source, bot, historyOwner, "leather", 1, null);
            } else {
                LOGGER.info("Bundle craft blocked: leather missing.");
                return false;
            }
        }
        if (!hasMaterial(bot, world, Items.LEATHER)) {
            LOGGER.info("Bundle craft blocked: leather missing after crafting attempt.");
            return false;
        }
        if (!hasMaterial(bot, world, Items.STRING)) {
            LOGGER.info("Bundle craft blocked: string missing.");
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "bundle", needed, null);
        return crafted > 0 && countBundles(bot) >= minCount;
    }

    private static boolean ensureBundleCraftSpace(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        if (bot.getInventory().getEmptySlot() != -1) {
            return true;
        }
        return hasRoomForItem(bot, Items.LEATHER) || hasRoomForItem(bot, Items.BUNDLE);
    }

    private static boolean hasRoomForItem(ServerPlayerEntity bot, Item item) {
        if (bot == null || item == null) {
            return false;
        }
        ItemStack probe = new ItemStack(item, 1);
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(stack, probe) && stack.getCount() < stack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    public static boolean ensureFence(ServerPlayerEntity bot,
                                      ServerCommandSource source,
                                      ServerPlayerEntity commander,
                                      int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        if (countTagged(bot, ItemTags.FENCES) >= minCount) {
            return true;
        }
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            withdrawFromNearbyContainersByTag(bot, world, ItemTags.FENCES, minCount);
            if (countTagged(bot, ItemTags.FENCES) >= minCount) {
                return true;
            }
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftFence(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "fence", minCount, null);
        return crafted > 0 && countTagged(bot, ItemTags.FENCES) >= minCount;
    }

    public static boolean ensureBucket(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.BUCKET);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftBucket(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasMaterial(bot, world, Items.IRON_INGOT)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "bucket", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.BUCKET) >= minCount;
    }

    public static boolean ensureShield(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "shield")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftShield(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasMaterial(bot, world, Items.IRON_INGOT) || !hasPlanksOrLogs(bot, world)) {
            return false;
        }
        return CraftingHelper.craftGeneric(source, bot, historyOwner, "shield", 1, null) > 0;
    }

    public static boolean ensureLadders(ServerPlayerEntity bot,
                                        ServerCommandSource source,
                                        ServerPlayerEntity commander,
                                        int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.LADDER);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftLadder(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "ladder", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.LADDER) >= minCount;
    }

    public static boolean ensureCraftingTable(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              ServerPlayerEntity commander,
                                              int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.CRAFTING_TABLE);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftCraftingTable(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "crafting_table", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.CRAFTING_TABLE) >= minCount;
    }

    public static boolean ensureChest(ServerPlayerEntity bot,
                                      ServerCommandSource source,
                                      ServerPlayerEntity commander,
                                      int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.CHEST);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftChest(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "chest", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.CHEST) >= minCount;
    }

    public static boolean ensureFurnace(ServerPlayerEntity bot,
                                        ServerCommandSource source,
                                        ServerPlayerEntity commander,
                                        int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.FURNACE);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftFurnace(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasCobbleMaterials(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "furnace", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.FURNACE) >= minCount;
    }

    public static boolean ensureBed(ServerPlayerEntity bot,
                                    ServerCommandSource source,
                                    ServerPlayerEntity commander,
                                    int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countBeds(bot);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftBed(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasWool(bot, world) || !hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "bed", needed, null);
        return crafted > 0 && countBeds(bot) >= minCount;
    }

    public static boolean ensureDoor(ServerPlayerEntity bot,
                                     ServerCommandSource source,
                                     ServerPlayerEntity commander,
                                     int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.OAK_DOOR)
                + countInventoryItem(bot, Items.SPRUCE_DOOR)
                + countInventoryItem(bot, Items.BIRCH_DOOR)
                + countInventoryItem(bot, Items.JUNGLE_DOOR)
                + countInventoryItem(bot, Items.ACACIA_DOOR)
                + countInventoryItem(bot, Items.DARK_OAK_DOOR)
                + countInventoryItem(bot, Items.MANGROVE_DOOR)
                + countInventoryItem(bot, Items.CHERRY_DOOR)
                + countInventoryItem(bot, Items.BAMBOO_DOOR)
                + countInventoryItem(bot, Items.CRIMSON_DOOR)
                + countInventoryItem(bot, Items.WARPED_DOOR);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftDoor(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "door", needed, null);
        return crafted > 0;
    }

    public static boolean ensureSticks(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countInventoryItem(bot, Items.STICK);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftSticks(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "stick", needed, null);
        return crafted > 0 && countInventoryItem(bot, Items.STICK) >= minCount;
    }

    public static boolean ensurePlanks(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       int minCount) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countTagged(bot, ItemTags.PLANKS);
        if (have >= minCount) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!canCraftPlanks(historyOwner)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasPlanksOrLogs(bot, world)) {
            return false;
        }
        int needed = Math.max(1, minCount - have);
        int crafted = CraftingHelper.craftGeneric(source, bot, historyOwner, "planks", needed, null);
        return crafted > 0 && countTagged(bot, ItemTags.PLANKS) >= minCount;
    }

    public static boolean ensureToolForKeyword(ServerPlayerEntity bot,
                                               ServerCommandSource source,
                                               ServerPlayerEntity commander,
                                               String toolKeyword) {
        if (toolKeyword == null) {
            return false;
        }
        String key = toolKeyword.toLowerCase(Locale.ROOT);
        if (key.contains("pickaxe")) {
            return ensurePickaxe(bot, source, commander);
        }
        if (key.contains("shovel")) {
            return ensureShovel(bot, source, commander);
        }
        if (key.contains("axe")) {
            return ensureAxe(bot, source, commander);
        }
        if (key.contains("hoe")) {
            return ensureHoe(bot, source, commander);
        }
        return false;
    }

    private static boolean canCraftTorch(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        return CraftingHistoryService.getHistory(commander).contains(Identifier.of("minecraft", "torch"));
    }

    private static boolean canCraftPickaxe(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        Set<Identifier> history = CraftingHistoryService.getHistory(commander);
        return history.contains(Identifier.of("minecraft", "wooden_pickaxe"))
                || history.contains(Identifier.of("minecraft", "stone_pickaxe"))
                || history.contains(Identifier.of("minecraft", "iron_pickaxe"))
                || history.contains(Identifier.of("minecraft", "diamond_pickaxe"));
    }

    private static boolean canCraftAxe(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        Set<Identifier> history = CraftingHistoryService.getHistory(commander);
        return history.contains(Identifier.of("minecraft", "wooden_axe"))
                || history.contains(Identifier.of("minecraft", "stone_axe"))
                || history.contains(Identifier.of("minecraft", "iron_axe"))
                || history.contains(Identifier.of("minecraft", "diamond_axe"));
    }

    private static boolean canCraftShovel(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        Set<Identifier> history = CraftingHistoryService.getHistory(commander);
        return history.contains(Identifier.of("minecraft", "wooden_shovel"))
                || history.contains(Identifier.of("minecraft", "stone_shovel"))
                || history.contains(Identifier.of("minecraft", "iron_shovel"))
                || history.contains(Identifier.of("minecraft", "diamond_shovel"));
    }

    private static boolean canCraftHoe(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        Set<Identifier> history = CraftingHistoryService.getHistory(commander);
        return history.contains(Identifier.of("minecraft", "wooden_hoe"))
                || history.contains(Identifier.of("minecraft", "stone_hoe"))
                || history.contains(Identifier.of("minecraft", "iron_hoe"))
                || history.contains(Identifier.of("minecraft", "diamond_hoe"));
    }

    private static boolean canCraftSword(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        Set<Identifier> history = CraftingHistoryService.getHistory(commander);
        return history.contains(Identifier.of("minecraft", "wooden_sword"))
                || history.contains(Identifier.of("minecraft", "stone_sword"))
                || history.contains(Identifier.of("minecraft", "iron_sword"))
                || history.contains(Identifier.of("minecraft", "diamond_sword"));
    }

    private static boolean canCraftShears(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "shears"));
    }

    private static boolean canCraftFishingRod(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "fishing_rod"));
    }

    private static boolean canCraftLadder(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "ladder"));
    }

    private static boolean canCraftCraftingTable(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "crafting_table"));
    }

    private static boolean canCraftChest(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "chest"));
    }

    private static boolean canCraftFurnace(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "furnace"));
    }

    private static boolean canCraftSaddle(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "saddle"));
    }

    private static boolean canCraftBucket(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "bucket"));
    }

    private static boolean canCraftShield(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "shield"));
    }

    private static boolean canCraftBed(ServerPlayerEntity commander) {
        return hasHistorySuffix(commander, "_bed");
    }

    private static boolean canCraftDoor(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        Set<Identifier> history = CraftingHistoryService.getHistory(commander);
        return history.contains(Identifier.of("minecraft", "oak_door"))
                || history.contains(Identifier.of("minecraft", "spruce_door"))
                || history.contains(Identifier.of("minecraft", "birch_door"))
                || history.contains(Identifier.of("minecraft", "jungle_door"))
                || history.contains(Identifier.of("minecraft", "acacia_door"))
                || history.contains(Identifier.of("minecraft", "dark_oak_door"))
                || history.contains(Identifier.of("minecraft", "mangrove_door"))
                || history.contains(Identifier.of("minecraft", "cherry_door"))
                || history.contains(Identifier.of("minecraft", "bamboo_door"))
                || history.contains(Identifier.of("minecraft", "crimson_door"))
                || history.contains(Identifier.of("minecraft", "warped_door"));
    }

    private static boolean canCraftLead(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "lead"));
    }

    private static boolean canCraftBundle(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "bundle"));
    }

    private static boolean canCraftFence(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "oak_fence"));
    }

    private static boolean canCraftSticks(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "stick"));
    }

    private static boolean canCraftPlanks(ServerPlayerEntity commander) {
        return hasHistorySuffix(commander, "_planks");
    }

    private static boolean canCraftCarrotOnStick(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "carrot_on_a_stick"));
    }

    private static boolean canCraftWarpedFungusOnStick(ServerPlayerEntity commander) {
        return canCraftExact(commander, Identifier.of("minecraft", "warped_fungus_on_a_stick"));
    }

    private static boolean hasTool(ServerPlayerEntity bot, String keyword) {
        if (bot == null || keyword == null) {
            return false;
        }
        String key = keyword.toLowerCase(Locale.ROOT);
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String translation = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (translation.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static int countInventoryItem(ServerPlayerEntity bot, Item item) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countBundles(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BundleItem) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countTagged(ServerPlayerEntity bot, net.minecraft.registry.tag.TagKey<Item> tag) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isIn(tag)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countBeds(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BedItem) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean hasTorchIngredients(ServerPlayerEntity bot, ServerWorld world) {
        boolean hasFuel = hasMaterial(bot, world, Items.COAL) || hasMaterial(bot, world, Items.CHARCOAL);
        if (!hasFuel) {
            return false;
        }
        if (countInventoryItem(bot, Items.STICK) > 0) {
            return true;
        }
        return hasPlanksOrLogs(bot, world);
    }

    private static boolean hasStoneMaterials(ServerPlayerEntity bot, ServerWorld world) {
        return hasMaterial(bot, world, Items.COBBLESTONE)
                || hasMaterial(bot, world, Items.COBBLED_DEEPSLATE)
                || hasMaterial(bot, world, Items.BLACKSTONE);
    }

    private static boolean hasCobbleMaterials(ServerPlayerEntity bot, ServerWorld world) {
        return hasStoneMaterials(bot, world);
    }

    private static boolean hasWool(ServerPlayerEntity bot, ServerWorld world) {
        if (hasTaggedItem(bot, ItemTags.WOOL)) {
            return true;
        }
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            if (slot.stack.isIn(ItemTags.WOOL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPlanksOrLogs(ServerPlayerEntity bot, ServerWorld world) {
        if (hasTaggedItem(bot, ItemTags.PLANKS) || hasTaggedItem(bot, ItemTags.LOGS)) {
            return true;
        }
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            if (slot.stack.isIn(ItemTags.PLANKS) || slot.stack.isIn(ItemTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTaggedItem(ServerPlayerEntity bot, net.minecraft.registry.tag.TagKey<Item> tag) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isIn(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMaterial(ServerPlayerEntity bot, ServerWorld world, Item item) {
        if (countInventoryItem(bot, item) > 0) {
            return true;
        }
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            if (slot.stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static int withdrawFromNearbyContainers(ServerPlayerEntity bot, ServerWorld world, Item item, int desired) {
        if (bot == null || world == null || item == null || desired <= 0) {
            return 0;
        }
        int moved = 0;
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            if (moved >= desired) {
                break;
            }
            ItemStack stack = slot.stack;
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            int take = Math.min(stack.getCount(), desired - moved);
            ItemStack extracted = stack.copy();
            extracted.setCount(take);
            boolean insertedAll = bot.getInventory().insertStack(extracted);
            int inserted = take - extracted.getCount();
            if (inserted <= 0) {
                continue;
            }
            stack.decrement(inserted);
            slot.inv.setStack(slot.slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            slot.inv.markDirty();
            moved += inserted;
        }
        return moved;
    }

    private static int withdrawFromNearbyContainersByTag(ServerPlayerEntity bot,
                                                         ServerWorld world,
                                                         net.minecraft.registry.tag.TagKey<Item> tag,
                                                         int desired) {
        if (bot == null || world == null || tag == null || desired <= 0) {
            return 0;
        }
        int moved = 0;
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            if (moved >= desired) {
                break;
            }
            ItemStack stack = slot.stack;
            if (stack.isEmpty() || !stack.isIn(tag)) {
                continue;
            }
            int take = Math.min(stack.getCount(), desired - moved);
            ItemStack extracted = stack.copy();
            extracted.setCount(take);
            boolean insertedAll = bot.getInventory().insertStack(extracted);
            int inserted = take - extracted.getCount();
            if (inserted <= 0) {
                continue;
            }
            stack.decrement(inserted);
            slot.inv.setStack(slot.slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            slot.inv.markDirty();
            moved += inserted;
        }
        return moved;
    }

    private static boolean canCraftExact(ServerPlayerEntity commander, Identifier id) {
        if (commander == null || id == null) {
            return false;
        }
        return CraftingHistoryService.getHistory(commander).contains(id);
    }

    private static boolean hasHistorySuffix(ServerPlayerEntity commander, String suffix) {
        if (commander == null || suffix == null || suffix.isBlank()) {
            return false;
        }
        for (Identifier id : CraftingHistoryService.getHistory(commander)) {
            if (id != null && id.getPath().endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static List<ContainerSlot> scanContainers(ServerWorld world, BlockPos origin) {
        List<ContainerSlot> out = new ArrayList<>();
        int r = CONTAINER_RADIUS;
        int y = CONTAINER_YSPAN;
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -y, -r), origin.add(r, y, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            var be = world.getBlockEntity(pos);
            if (!(be instanceof Inventory inv)) {
                continue;
            }
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                out.add(new ContainerSlot(inv, pos.toImmutable(), i, stack));
            }
        }
        return out;
    }

    private record ContainerSlot(Inventory inv, BlockPos pos, int slot, ItemStack stack) {}
}
