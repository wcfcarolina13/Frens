package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.BedItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.BotChestRegistryService.ItemSnapshot;
import net.wcfcarolina13.GameAI.services.supply.SupplyServerHop;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals;
import net.wcfcarolina13.PlayerUtils.CombatInventoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class ToolProvisionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("tool-provision");
    private static final int CONTAINER_RADIUS = 12;
    private static final int CONTAINER_YSPAN = 6;
    /** How long a worker waits for an off-thread reachable-chest pull to start on the server thread. */
    private static final long PULL_HOP_TIMEOUT_MS = 2_500L;
    /** How long a chest tool search waits for its registry snapshot refresh to start on the server thread. */
    private static final long SNAPSHOT_HOP_TIMEOUT_MS = 3_000L;

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
        return ensurePickaxe(bot, source, commander, false);
    }

    public static boolean ensurePickaxe(ServerPlayerEntity bot,
                                        ServerCommandSource source,
                                        ServerPlayerEntity commander,
                                        boolean allowWoodenFallback) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "pickaxe")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        boolean canCraft = canCraftPickaxe(historyOwner);
        if (canCraft && hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "stone") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "iron") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "diamond") > 0;
        }
        if ((canCraft || allowWoodenFallback) && hasPlanksOrLogs(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "pickaxe", 1, "wood") > 0;
        }
        return false;
    }

    public static boolean ensureShovel(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander) {
        return ensureShovel(bot, source, commander, false);
    }

    public static boolean ensureShovel(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       boolean allowWoodenFallback) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "shovel")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        boolean canCraft = canCraftShovel(historyOwner);
        if (canCraft && hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "stone") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "iron") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "diamond") > 0;
        }
        if ((canCraft || allowWoodenFallback) && hasPlanksOrLogs(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "shovel", 1, "wood") > 0;
        }
        return false;
    }

    public static boolean ensureAxe(ServerPlayerEntity bot,
                                    ServerCommandSource source,
                                    ServerPlayerEntity commander) {
        return ensureAxe(bot, source, commander, false);
    }

    public static boolean ensureAxe(ServerPlayerEntity bot,
                                    ServerCommandSource source,
                                    ServerPlayerEntity commander,
                                    boolean allowWoodenFallback) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "axe")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        boolean canCraft = canCraftAxe(historyOwner);
        if (canCraft && hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "axe", 1, "stone") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "axe", 1, "iron") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "axe", 1, "diamond") > 0;
        }
        if ((canCraft || allowWoodenFallback) && hasPlanksOrLogs(bot, world)) {
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
        return ensureSword(bot, source, commander, false);
    }

    public static boolean ensureSword(ServerPlayerEntity bot,
                                      ServerCommandSource source,
                                      ServerPlayerEntity commander,
                                      boolean allowWoodenFallback) {
        if (bot == null || source == null) {
            return false;
        }
        if (hasTool(bot, "sword")) {
            return true;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        boolean canCraft = canCraftSword(historyOwner);
        if (canCraft && hasStoneMaterials(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "sword", 1, "stone") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "sword", 1, "iron") > 0;
        }
        if (canCraft && hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, historyOwner, "sword", 1, "diamond") > 0;
        }
        if ((canCraft || allowWoodenFallback) && hasPlanksOrLogs(bot, world)) {
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
        pullFromReachableChests(bot, world, stack -> stack.isOf(Items.SADDLE), null, minCount - have, "saddle");
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
        pullFromReachableChests(bot, world, stack -> stack.isOf(Items.LEAD), null, minCount - have, "lead");
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
            pullFromReachableChests(bot, world, stack -> stack.isIn(ItemTags.FENCES), null, minCount, "fence");
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
        int needed = Math.max(1, minCount - have);
        if (!hasBedFiberPotential(bot, world, needed) || !hasPlanksOrLogs(bot, world)) {
            return false;
        }
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

    public static boolean hasUsableAxe(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (axeScore(bot.getInventory().getStack(i)) > 0) {
                return true;
            }
        }
        return axeScore(bot.getMainHandStack()) > 0;
    }

    public static boolean hasServiceableMeleeWeapon(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (weaponScore(bot.getInventory().getStack(i)) > 0) {
                return true;
            }
        }
        return weaponScore(bot.getMainHandStack()) > 0;
    }

    /**
     * The idle wooden fallback's pull: a weapon, an axe, armor for empty slots, then sticks, planks
     * and logs for crafting, from the chests the bot can reach where it stands, each through the
     * supply facade ({@link #pullFromReachableChests}). Server tick; never walks, never waits.
     *
     * <p>One prompt per bot: the first item the owner is asked about ends the pull
     * ({@link SupplyPullPolicy.Pull#halted()}), and so does any refusal that answers for every
     * other item too (a cooldown, the owner refusing or ignoring a prompt, the owner being away).
     * The caller reads the result: {@code waiting} means don't craft or cut a tree over the
     * owner's pending answer, {@code missed} and {@code ownerAway} feed its ask backoff.
     */
    public static SupplyPullPolicy.Pull pullNearbyAccessibleIdleFallbackSupplies(ServerPlayerEntity bot,
                                                                                ServerWorld world,
                                                                                boolean needWeapon,
                                                                                boolean needAxe) {
        if (bot == null || world == null) {
            return SupplyPullPolicy.Pull.NOTHING;
        }
        SupplyPullPolicy.Pull pull = SupplyPullPolicy.Pull.NOTHING;

        // Lowest score first, as the old max(reversed(score)) pick did.
        if (needWeapon && !hasServiceableMeleeWeapon(bot)) {
            pull = pull.plus(pullFromReachableChests(bot, world, stack -> weaponScore(stack) > 0,
                    Comparator.comparingInt(ToolProvisionService::weaponScore), 1, "idle-weapon"));
        }
        if (!pull.halted() && needAxe && !hasUsableAxe(bot)) {
            pull = pull.plus(pullFromReachableChests(bot, world, stack -> axeScore(stack) > 0,
                    Comparator.comparingInt(ToolProvisionService::axeScore), 1, "idle-axe"));
        }
        if (!pull.halted()) {
            pull = pull.plus(withdrawMissingArmor(bot, world));
        }

        boolean stillNeedWeapon = needWeapon && !hasServiceableMeleeWeapon(bot);
        boolean stillNeedAxe = needAxe && !hasUsableAxe(bot);
        if (!pull.halted() && (stillNeedWeapon || stillNeedAxe)) {
            int desiredSticks = requiredFallbackStickCount(stillNeedWeapon, stillNeedAxe);
            int desiredPlanks = requiredFallbackPlankCount(stillNeedWeapon, stillNeedAxe);
            int haveSticks = countInventoryItem(bot, Items.STICK);
            int havePlanks = countTagged(bot, ItemTags.PLANKS);

            if (haveSticks < desiredSticks) {
                pull = pull.plus(pullFromReachableChests(bot, world, stack -> stack.isOf(Items.STICK), null,
                        desiredSticks - haveSticks, "idle-sticks"));
            }
            if (!pull.halted() && havePlanks < desiredPlanks) {
                pull = pull.plus(pullFromReachableChests(bot, world, stack -> stack.isIn(ItemTags.PLANKS), null,
                        desiredPlanks - havePlanks, "idle-planks"));
                havePlanks = countTagged(bot, ItemTags.PLANKS);
            }
            if (!pull.halted() && havePlanks < desiredPlanks) {
                int logsNeeded = (int) Math.ceil((desiredPlanks - havePlanks) / 4.0D);
                pull = pull.plus(pullFromReachableChests(bot, world, stack -> stack.isIn(ItemTags.LOGS), null,
                        logsNeeded, "idle-logs"));
            }
        }

        if (pull.movedAny()) {
            bot.getInventory().markDirty();
            CombatInventoryManager.ensureCombatLoadout(bot);
        }
        return pull;
    }

    public static boolean canCraftIdleWoodenFallback(ServerPlayerEntity bot,
                                                     boolean needWeapon,
                                                     boolean needAxe) {
        if (bot == null) {
            return false;
        }
        int requiredPlanks = requiredFallbackPlankCount(needWeapon, needAxe);
        int requiredSticks = requiredFallbackStickCount(needWeapon, needAxe);
        if (requiredPlanks <= 0 && requiredSticks <= 0) {
            return false;
        }

        int sticksAvailable = countInventoryItem(bot, Items.STICK);
        int planksAvailable = countTagged(bot, ItemTags.PLANKS);
        int logCount = countTagged(bot, ItemTags.LOGS);
        int plankEquivalent = planksAvailable + (logCount * 4);

        int extraSticksNeeded = Math.max(0, requiredSticks - sticksAvailable);
        int planksNeededForSticks = extraSticksNeeded <= 0 ? 0 : ((extraSticksNeeded + 3) / 4) * 2;
        int totalPlanksNeeded = requiredPlanks + planksNeededForSticks;
        return plankEquivalent >= totalPlanksNeeded;
    }

    public static int computeAccessibleIdleFallbackSignature(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return 0;
        }
        int hash = 1;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            hash = mixIdleFallbackSignature(hash, bot.getInventory().getStack(i), null, i);
        }
        List<ContainerSlot> slots = scanAccessibleContainers(bot, world, bot.getBlockPos());
        for (ContainerSlot slot : slots) {
            hash = mixIdleFallbackSignature(hash, slot.stack, slot.pos, slot.slot);
        }
        return hash;
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
        return true; // Universally known recipe — no crafting history required
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
            if (matchesToolKeyword(translation, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the bot has the given tool type but ONLY at wooden tier.
     * Used to decide if a stone upgrade is worthwhile.
     */
    public static boolean hasOnlyWoodenTool(ServerPlayerEntity bot, String toolKeyword) {
        if (bot == null || toolKeyword == null) return false;
        String key = toolKeyword.toLowerCase(Locale.ROOT);
        boolean hasAny = false;
        boolean allWooden = true;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String translation = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (!matchesToolKeyword(translation, key)) continue;
            hasAny = true;
            if (!translation.contains("wooden_")) {
                allWooden = false;
                break;
            }
        }
        return hasAny && allWooden;
    }

    private static boolean matchesToolKeyword(String translation, String keyword) {
        if (translation == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return switch (keyword) {
            case "axe" -> isAxeKey(translation);
            case "sword" -> translation.contains("sword");
            case "pickaxe" -> translation.contains("pickaxe");
            case "shovel" -> translation.contains("shovel");
            case "hoe" -> translation.contains("hoe");
            default -> translation.contains(keyword);
        };
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

    public static boolean hasStoneMaterialsAvailable(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null) return false;
        if (world == null && bot.getEntityWorld() instanceof ServerWorld sw) {
            world = sw;
        }
        return world != null && hasStoneMaterials(bot, world);
    }

    private static boolean hasStoneMaterials(ServerPlayerEntity bot, ServerWorld world) {
        return hasMaterial(bot, world, Items.COBBLESTONE)
                || hasMaterial(bot, world, Items.COBBLED_DEEPSLATE)
                || hasMaterial(bot, world, Items.BLACKSTONE);
    }

    private static boolean hasCobbleMaterials(ServerPlayerEntity bot, ServerWorld world) {
        return hasStoneMaterials(bot, world);
    }

    private static boolean hasBedFiberPotential(ServerPlayerEntity bot, ServerWorld world, int bedCount) {
        return countBedFiberPotential(bot, world) >= Math.max(3, bedCount * 3);
    }

    private static int countBedFiberPotential(ServerPlayerEntity bot, ServerWorld world) {
        int wool = countTagged(bot, ItemTags.WOOL)
                + grantableChestCount(bot, world, stack -> stack.isIn(ItemTags.WOOL), false);
        int string = countInventoryItem(bot, Items.STRING)
                + grantableChestCount(bot, world, stack -> stack.isOf(Items.STRING), false);
        return wool + (string / 4);
    }

    /** Quick bot-inventory-only check for planks or logs (no world scan). */
    public static boolean hasPlanksOrLogsInInventory(ServerPlayerEntity bot) {
        return bot != null && (hasTaggedItem(bot, ItemTags.PLANKS) || hasTaggedItem(bot, ItemTags.LOGS));
    }

    /** Check inventory AND nearby chests for planks or logs. */
    public static boolean hasPlanksOrLogsAvailable(ServerPlayerEntity bot) {
        if (bot == null) return false;
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) return hasPlanksOrLogsInInventory(bot);
        return hasPlanksOrLogs(bot, world);
    }

    private static boolean hasPlanksOrLogs(ServerPlayerEntity bot, ServerWorld world) {
        if (hasTaggedItem(bot, ItemTags.PLANKS) || hasTaggedItem(bot, ItemTags.LOGS)) {
            return true;
        }
        return grantableChestCount(bot, world,
                stack -> stack.isIn(ItemTags.PLANKS) || stack.isIn(ItemTags.LOGS), false) > 0;
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
        return grantableChestCount(bot, world, stack -> stack.isOf(item), false) > 0;
    }

    /**
     * How many of the stacks {@code wanted} accepts the supply policy could let this bot take from
     * the chests around it ({@code reachableOnly}: only those it can reach from where it stands):
     * each chest half's count of each exact item through
     * {@link SupplyWithdrawals#grantableEstimate}, so an item the policy never grants counts 0 and
     * an allowlisted one counts what lies above its reserve. Per half, so a double chest can only
     * be undercounted. Counts nothing the owner has not been asked about yet; the facade still
     * decides at the take.
     *
     * <p>Server thread only. Off it (a skill worker or the durability executor asking before a
     * craft) chest stock counts as 0 rather than hopping, and the world is not read; callers
     * count the bot's own inventory themselves.
     */
    private static int grantableChestCount(ServerPlayerEntity bot, ServerWorld world, Predicate<ItemStack> wanted,
                                           boolean reachableOnly) {
        if (bot == null || world == null || wanted == null) {
            return 0;
        }
        MinecraftServer server = world.getServer();
        if (server == null || !server.isOnThread()) {
            return 0;
        }
        List<ContainerSlot> slots = reachableOnly
                ? scanAccessibleContainers(bot, world, bot.getBlockPos())
                : scanContainers(world, bot.getBlockPos());
        int total = 0;
        for (ChestItemGroup group : groupByChestAndItem(slots, wanted)) {
            total += SupplyWithdrawals.grantableEstimate(world, group.sample, group.count);
        }
        return total;
    }

    /**
     * The one way this class takes items out of a chest: from the chests the bot can reach where it
     * stands, each distinct stack {@code wanted} accepts asked for through
     * {@link SupplyWithdrawals#withdraw} with {@link SupplyWithdrawals.WaitMode#NONE}, in
     * {@code order} (scan order when {@code null}). Never walks, never waits. Stops once
     * {@code desired} items moved, or on an answer that stops the pass ({@link SupplyPullPolicy#next}):
     * a prompt now open, the owner away, or a refusal that answers for everything else too. An
     * item the policy never grants is refused without a prompt and skipped everywhere; a chest
     * that refuses is skipped, both halves of a double chest; a chest with a transient refusal is
     * skipped for this pull only.
     *
     * <p>Server thread. Called off it (a skill such as {@code LeashToFenceSkill} reaching
     * {@link #ensureLead}), the whole pull runs in one bounded, abandon-safe
     * {@link SupplyServerHop} hop and reports nothing if the hop did not run.
     */
    private static SupplyPullPolicy.Pull pullFromReachableChests(ServerPlayerEntity bot,
                                                                 ServerWorld world,
                                                                 Predicate<ItemStack> wanted,
                                                                 Comparator<ItemStack> order,
                                                                 int desired,
                                                                 String purpose) {
        if (bot == null || world == null || wanted == null || desired <= 0) {
            return SupplyPullPolicy.Pull.NOTHING;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return SupplyPullPolicy.Pull.NOTHING;
        }
        if (!server.isOnThread()) {
            // The hop runs the pull on the server thread only (never inline on this worker once the
            // server stopped) and not at all if this worker gave up on it before it started.
            return SupplyServerHop.call(server,
                    () -> pullFromReachableChests(bot, world, wanted, order, desired, purpose),
                    PULL_HOP_TIMEOUT_MS, SupplyPullPolicy.Pull.NOTHING);
        }
        List<ChestItemGroup> groups = groupByChestAndItem(scanAccessibleContainers(bot, world, bot.getBlockPos()), wanted);
        if (order != null) {
            groups.sort((a, b) -> order.compare(a.sample, b.sample));
        }
        SupplyPullPolicy.Pull pull = SupplyPullPolicy.Pull.NOTHING;
        Set<BlockPos> skippedChests = new HashSet<>();
        List<ItemStack> skippedItems = new ArrayList<>();
        for (ChestItemGroup group : groups) {
            int left = desired - pull.moved();
            if (left <= 0) {
                break;
            }
            if (skippedChests.contains(group.pos) || containsSameItem(skippedItems, group.sample)) {
                continue;
            }
            SupplyWithdrawals.Result result = SupplyWithdrawals.withdraw(bot, group.pos, group.sample, left, left,
                    purpose, SupplyWithdrawals.WaitMode.NONE, null);
            pull = SupplyPullPolicy.fold(pull, result.kind(), result.moved(), result.scope());
            switch (SupplyPullPolicy.next(result.kind(), result.scope())) {
                case SKIP_ITEM -> skippedItems.add(group.sample);
                case SKIP_CHEST -> {
                    skippedChests.add(group.pos);
                    BlockPos otherHalf = ChestStoreService.otherChestHalf(world, group.pos);
                    if (otherHalf != null) {
                        skippedChests.add(otherHalf);
                    }
                }
                case RETRY_LATER -> skippedChests.add(group.pos);
                case STOP, OWNER_AWAY -> {
                    return pull;
                }
                case NEXT -> {
                }
            }
        }
        return pull;
    }

    private static SupplyPullPolicy.Pull withdrawMissingArmor(ServerPlayerEntity bot, ServerWorld world) {
        SupplyPullPolicy.Pull pull = SupplyPullPolicy.Pull.NOTHING;
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            if (pull.halted()) {
                break;
            }
            if (!bot.getEquippedStack(slot).isEmpty()) {
                continue;
            }
            // Lowest score first, as the old max(reversed(score)) pick did.
            pull = pull.plus(pullFromReachableChests(bot, world,
                    stack -> armorScore(stack, slot) > 0,
                    Comparator.comparingInt((ItemStack stack) -> armorScore(stack, slot)), 1, "idle-armor"));
        }
        return pull;
    }

    /** One chest half's stock of one exact item (id and components), in first-seen slot order. */
    private static final class ChestItemGroup {
        final BlockPos pos;
        final ItemStack sample;
        int count;

        ChestItemGroup(BlockPos pos, ItemStack sample, int count) {
            this.pos = pos;
            this.sample = sample;
            this.count = count;
        }
    }

    /** Groups the slots {@code wanted} accepts by chest half and exact item; each sample is a copy. */
    private static List<ChestItemGroup> groupByChestAndItem(List<ContainerSlot> slots, Predicate<ItemStack> wanted) {
        List<ChestItemGroup> groups = new ArrayList<>();
        for (ContainerSlot slot : slots) {
            if (slot.stack == null || slot.stack.isEmpty() || !wanted.test(slot.stack)) {
                continue;
            }
            ChestItemGroup match = null;
            for (ChestItemGroup group : groups) {
                if (group.pos.equals(slot.pos) && ItemStack.areItemsAndComponentsEqual(group.sample, slot.stack)) {
                    match = group;
                    break;
                }
            }
            if (match == null) {
                groups.add(new ChestItemGroup(slot.pos, slot.stack.copy(), slot.stack.getCount()));
            } else {
                match.count += slot.stack.getCount();
            }
        }
        return groups;
    }

    private static boolean containsSameItem(List<ItemStack> stacks, ItemStack stack) {
        for (ItemStack other : stacks) {
            if (ItemStack.areItemsAndComponentsEqual(other, stack)) {
                return true;
            }
        }
        return false;
    }

    private static List<ContainerSlot> scanAccessibleContainers(ServerPlayerEntity bot, ServerWorld world, BlockPos origin) {
        List<ContainerSlot> all = scanContainers(world, origin);
        if (bot == null) {
            return all;
        }
        return all.stream()
                .filter(slot -> BlockInteractionService.canInteract(bot, slot.pos))
                .toList();
    }

    private static int requiredFallbackStickCount(boolean needWeapon, boolean needAxe) {
        int sticks = 0;
        if (needWeapon) {
            sticks += 1;
        }
        if (needAxe) {
            sticks += 2;
        }
        return sticks;
    }

    private static int requiredFallbackPlankCount(boolean needWeapon, boolean needAxe) {
        int planks = 0;
        if (needWeapon) {
            planks += 2;
        }
        if (needAxe) {
            planks += 3;
        }
        planks += requiredFallbackStickCount(needWeapon, needAxe) > 0 ? 2 : 0;
        return planks;
    }

    private static int mixIdleFallbackSignature(int hash, ItemStack stack, BlockPos pos, int slot) {
        if (!isIdleFallbackRelevant(stack)) {
            return hash;
        }
        Identifier id = Identifier.of("minecraft", "air");
        try {
            id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
        } catch (Exception ignored) {
        }
        hash = 31 * hash + (id != null ? id.hashCode() : 0);
        hash = 31 * hash + stack.getCount();
        hash = 31 * hash + slot;
        if (pos != null) {
            hash = 31 * hash + pos.hashCode();
        }
        return hash;
    }

    private static boolean isIdleFallbackRelevant(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return weaponScore(stack) > 0
                || axeScore(stack) > 0
                || stack.isOf(Items.STICK)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isIn(ItemTags.LOGS)
                || armorScore(stack, EquipmentSlot.HEAD) > 0
                || armorScore(stack, EquipmentSlot.CHEST) > 0
                || armorScore(stack, EquipmentSlot.LEGS) > 0
                || armorScore(stack, EquipmentSlot.FEET) > 0;
    }

    private static int weaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        boolean weapon = key.contains("sword")
                || isAxeKey(key)
                || key.contains("trident")
                || key.contains("mace")
                || key.contains("dagger");
        if (!weapon) {
            return 0;
        }
        if (key.contains("netherite")) return 50;
        if (key.contains("diamond")) return 40;
        if (key.contains("iron")) return 30;
        if (key.contains("stone") || key.contains("cobble")) return 20;
        if (key.contains("wood") || key.contains("wooden")) return 10;
        if (key.contains("gold")) return 8;
        return 5;
    }

    private static int axeScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (!isAxeKey(key)) {
            return 0;
        }
        if (key.contains("netherite")) return 50;
        if (key.contains("diamond")) return 40;
        if (key.contains("iron")) return 30;
        if (key.contains("stone") || key.contains("cobble")) return 20;
        if (key.contains("wood") || key.contains("wooden")) return 10;
        if (key.contains("gold")) return 8;
        return 5;
    }

    private static boolean isAxeKey(String key) {
        return key != null && key.contains("_axe") && !key.contains("pickaxe");
    }

    private static int armorScore(ItemStack stack, EquipmentSlot expectedSlot) {
        if (stack == null || stack.isEmpty() || expectedSlot == null) {
            return 0;
        }
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null || equippable.slot() != expectedSlot) {
            return 0;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.contains("netherite")) return 60;
        if (key.contains("diamond")) return 50;
        if (key.contains("iron")) return 40;
        if (key.contains("chainmail")) return 30;
        if (key.contains("gold")) return 20;
        if (key.contains("leather")) return 10;
        return 5;
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

    /**
     * Every non-empty slot of every chest (a {@link ChestBlock}: chest, trapped chest, copper
     * chests) within {@link #CONTAINER_RADIUS} blocks and {@link #CONTAINER_YSPAN} up or down, one
     * entry per half. Chests only: the supply policy never takes from a barrel, shulker box, hopper
     * or furnace, so nothing else is offered or counted. Reads the world: server thread.
     */
    private static List<ContainerSlot> scanContainers(ServerWorld world, BlockPos origin) {
        List<ContainerSlot> out = new ArrayList<>();
        int r = CONTAINER_RADIUS;
        int y = CONTAINER_YSPAN;
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -y, -r), origin.add(r, y, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!(world.getBlockState(pos).getBlock() instanceof ChestBlock)) {
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
                out.add(new ContainerSlot(pos.toImmutable(), i, stack));
            }
        }
        return out;
    }

    /** One non-empty chest slot, read-only: no chest handle is kept, so nothing can move items through it. */
    private record ContainerSlot(BlockPos pos, int slot, ItemStack stack) {}

    // ── Leather armor crafting ──────────────────────────────────────────

    public static boolean hasAnyEmptyArmorSlot(ServerPlayerEntity bot) {
        if (bot == null) return false;
        return bot.getEquippedStack(EquipmentSlot.HEAD).isEmpty()
                || bot.getEquippedStack(EquipmentSlot.CHEST).isEmpty()
                || bot.getEquippedStack(EquipmentSlot.LEGS).isEmpty()
                || bot.getEquippedStack(EquipmentSlot.FEET).isEmpty();
    }

    public static int countLeatherAvailable(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null) return 0;
        // Reachable chests only, as before; leather is not on the supply allowlist, so today the
        // chests add nothing unless that list grows.
        return countInventoryItem(bot, Items.LEATHER)
                + grantableChestCount(bot, world, stack -> stack.isOf(Items.LEATHER), true);
    }

    public static boolean ensureLeatherArmorForSlot(ServerPlayerEntity bot,
                                                     ServerCommandSource source,
                                                     ServerPlayerEntity commander,
                                                     EquipmentSlot slot) {
        if (bot == null || source == null) return false;
        if (!bot.getEquippedStack(slot).isEmpty()) return false;
        String craftName = switch (slot) {
            case HEAD -> "leather_helmet";
            case CHEST -> "leather_chestplate";
            case LEGS -> "leather_leggings";
            case FEET -> "leather_boots";
            default -> null;
        };
        if (craftName == null) return false;
        int needed = switch (slot) {
            case FEET -> 4;
            case HEAD -> 5;
            case LEGS -> 7;
            case CHEST -> 8;
            default -> 0;
        };
        if (needed == 0) return false;
        // Pull leather from chests if inventory doesn't have enough
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            int have = countInventoryItem(bot, Items.LEATHER);
            if (have < needed) {
                pullFromReachableChests(bot, world, stack -> stack.isOf(Items.LEATHER), null, needed - have,
                        "leather-armor");
            }
        }
        if (countInventoryItem(bot, Items.LEATHER) < needed) return false;
        boolean crafted = CraftingHelper.craftGeneric(source, bot, commander, craftName, 1, null) > 0;
        if (crafted) {
            LOGGER.info("Crafted {} for {}", craftName, bot.getName().getString());
        }
        return crafted;
    }

    // ── Chest tool retrieval: axe helpers ────────────���─────────────────

    private static final Set<String> ALLOWED_AXE_IDS = Set.of(
            "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:copper_axe");

    private static final Map<String, Integer> AXE_TIER_RANK = Map.of(
            "minecraft:copper_axe", 3,
            "minecraft:stone_axe", 2,
            "minecraft:wooden_axe", 1);

    public static Predicate<ItemSnapshot> allowedAxeSnapshotFilter() {
        return snap -> snap != null && ALLOWED_AXE_IDS.contains(snap.itemId);
    }

    public static Predicate<ItemStack> allowedWoodcutAxePredicate() {
        return stack -> {
            if (stack == null || stack.isEmpty()) return false;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (!ALLOWED_AXE_IDS.contains(id)) return false;
            if (stack.hasEnchantments()) return false;
            if (stack.isDamageable()) {
                int remaining = stack.getMaxDamage() - stack.getDamage();
                if (remaining < 8) return false;
            }
            return true;
        };
    }

    public static Comparator<ItemSnapshot> axeTierComparator() {
        return Comparator.comparingInt(
                snap -> -AXE_TIER_RANK.getOrDefault(snap.itemId, 0));
    }

    // ── Chest tool retrieval: general API ─────────��────────────────────

    /**
     * {@link #retrieveToolFromChests(ServerPlayerEntity, ServerWorld, ServerCommandSource, Predicate,
     * Predicate, Comparator, int, SupplyWithdrawals.WaitMode)} without waiting for the owner:
     * Woodcut's mid-task refill and {@code DurabilityFallbackService} (a shared executor that must
     * never block on an owner). An open prompt makes this return {@code false}; a later call, once
     * the owner answers, walks and takes.
     */
    public static boolean retrieveToolFromChests(ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  ServerCommandSource source,
                                                  Predicate<ItemSnapshot> snapshotFilter,
                                                  Predicate<ItemStack> stackPredicate,
                                                  Comparator<ItemSnapshot> snapshotComparator,
                                                  int maxRange) {
        return retrieveToolFromChests(bot, world, source, snapshotFilter, stackPredicate, snapshotComparator,
                maxRange, SupplyWithdrawals.WaitMode.NONE);
    }

    /**
     * Walk to a registered chest and retrieve one tool matching the given criteria, through the
     * supply facade ({@link ChestStoreService#withdrawMatchingWalkOnly}): the owner is asked before
     * the bot walks, and nothing is taken without their permission or a standing one.
     * Runs on a worker thread; the registry snapshot refresh hops through {@link SupplyServerHop}.
     *
     * <p>Chests are tried best tool first, then nearest, until one gives a tool or an answer stops
     * the search ({@link SupplyPullPolicy#next}); a chest that refuses is skipped with its other
     * half. After a search that took nothing, this bot's {@link SupplyWithdrawals.WaitMode#NONE}
     * searches pause ({@link SupplyPullPolicy#retrievalPauseMs}: 5 s while a prompt is open, a
     * flat 60 s while the owner is away, 60 s doubling to 10 min per miss), because Woodcut calls
     * this before every log while it has no axe and would otherwise re-prompt the owner as fast as
     * it mines. An {@link SupplyWithdrawals.WaitMode#UNTIL_ANSWERED} search (Woodcut's start: the
     * owner is there to answer) is never paused. A search looking again at a prompt still open
     * leaves the registry file alone (nothing moved, so its snapshots are unchanged) and logs at
     * DEBUG; the facade logged the ask once.
     *
     * @param mode {@link SupplyWithdrawals.WaitMode#UNTIL_ANSWERED} to wait at the chest for the
     *             owner's answer (Woodcut's start only)
     * @return true if a tool was withdrawn into the bot's inventory
     */
    public static boolean retrieveToolFromChests(ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  ServerCommandSource source,
                                                  Predicate<ItemSnapshot> snapshotFilter,
                                                  Predicate<ItemStack> stackPredicate,
                                                  Comparator<ItemSnapshot> snapshotComparator,
                                                  int maxRange,
                                                  SupplyWithdrawals.WaitMode mode) {
        if (bot == null || world == null || source == null) return false;
        MinecraftServer server = world.getServer();
        if (server == null) return false;

        UUID botUuid = bot.getUuid();
        long startedAtMs = System.currentTimeMillis();
        RetrievalPause paused = TOOL_RETRIEVAL_PAUSES.get(botUuid);
        boolean waitsForAnswer = mode == SupplyWithdrawals.WaitMode.UNTIL_ANSWERED;
        if (!waitsForAnswer && paused != null && startedAtMs < paused.untilMs()) {
            LOGGER.debug("Chest tool retrieval: {} paused for {} ms after its last supply answer",
                    bot.getName().getString(), paused.untilMs() - startedAtMs);
            return false;
        }
        boolean relook = !waitsForAnswer && paused != null && paused.awaitingAnswer();

        if (!relook) {
            // Refresh snapshots on the server thread (block entities must be read there).
            Boolean refreshed = SupplyServerHop.call(server, () -> {
                BotChestRegistryService.refreshAllSnapshots(bot, world);
                return Boolean.TRUE;
            }, SNAPSHOT_HOP_TIMEOUT_MS, Boolean.FALSE);
            if (!Boolean.TRUE.equals(refreshed)) {
                LOGGER.debug("Chest tool retrieval: snapshot refresh failed/timed out for {}",
                        bot.getName().getString());
                return false;
            }
        }

        // Get all registered chests for this bot/owner
        List<BotChestRegistryService.ChestRecord> allChests =
                BotChestRegistryService.listChestsForOwner(bot, world);
        if (allChests.isEmpty()) {
            recordToolSearch(botUuid, paused, SupplyPullPolicy.Pull.NOTHING);
            return false;
        }

        double maxDistSq = (double) maxRange * maxRange;
        BlockPos botPos = bot.getBlockPos();

        // Build candidate list: chests within range whose snapshots contain matching items
        record ChestCandidate(BlockPos pos, ItemSnapshot bestMatch, double distSq) {}
        List<ChestCandidate> candidates = new ArrayList<>();

        for (var record : allChests) {
            if (record.destroyed) continue;
            BlockPos pos = record.toBlockPos();
            if (pos == null) continue;
            double distSq = botPos.getSquaredDistance(pos);
            if (distSq > maxDistSq) continue;
            if (record.contentsSnapshot == null) continue;

            // Find the best matching snapshot item in this chest
            ItemSnapshot bestMatch = null;
            for (ItemSnapshot snap : record.contentsSnapshot) {
                if (snap != null && snapshotFilter.test(snap)) {
                    if (bestMatch == null || snapshotComparator.compare(snap, bestMatch) < 0) {
                        bestMatch = snap;
                    }
                }
            }
            if (bestMatch != null) {
                candidates.add(new ChestCandidate(pos.toImmutable(), bestMatch, distSq));
            }
        }

        if (candidates.isEmpty()) {
            LOGGER.debug("Chest tool retrieval: no matching chests within {} blocks for {}",
                    maxRange, bot.getName().getString());
            recordToolSearch(botUuid, paused, SupplyPullPolicy.Pull.NOTHING);
            return false;
        }

        // Sort: best tool tier first, then nearest
        candidates.sort(Comparator
                .<ChestCandidate, ItemSnapshot>comparing(c -> c.bestMatch, snapshotComparator)
                .thenComparingDouble(c -> c.distSq));

        if (relook) {
            LOGGER.debug("Chest tool retrieval: looking again at {} candidate chest(s) for {} within {} blocks",
                    candidates.size(), bot.getName().getString(), maxRange);
        } else {
            LOGGER.info("Chest tool retrieval: {} candidate chest(s) for {} within {} blocks",
                    candidates.size(), bot.getName().getString(), maxRange);
        }

        // Try each candidate
        SupplyPullPolicy.Pull search = SupplyPullPolicy.Pull.NOTHING;
        Set<BlockPos> skippedHalves = new HashSet<>();
        SupplyWithdrawals.Result last = null;
        for (ChestCandidate candidate : candidates) {
            // A transient refusal (ABORTED among them) no longer ends the search, so a stop
            // request ends it here, before the next chest is asked or walked to.
            if (TaskService.isAbortRequested(botUuid)) {
                break;
            }
            if (skippedHalves.contains(candidate.pos)) {
                continue;
            }
            SupplyWithdrawals.Result result = ChestStoreService.withdrawMatchingWalkOnly(
                    source, bot, candidate.pos, 1, stackPredicate, "chest-tool", mode);
            last = result;
            search = SupplyPullPolicy.fold(search, result.kind(), result.moved(), result.scope());
            if (search.movedAny()) {
                TOOL_RETRIEVAL_PAUSES.remove(botUuid);
                LOGGER.info("Chest tool retrieval: withdrew tool from chest at {} for {}",
                        candidate.pos.toShortString(), bot.getName().getString());
                return true;
            }
            SupplyPullPolicy.Next next = SupplyPullPolicy.next(result.kind(), result.scope());
            if (next.stopsPass()) {
                break;
            }
            if (next == SupplyPullPolicy.Next.SKIP_CHEST || next == SupplyPullPolicy.Next.SKIP_ITEM) {
                // The ask read the chest's merged view, so its other half would answer the same.
                BlockPos otherHalf = ChestStoreService.otherChestHalf(world, candidate.pos);
                if (otherHalf != null) {
                    skippedHalves.add(otherHalf);
                }
            }
            LOGGER.debug("Chest tool retrieval: chest at {} gave nothing for {} ({} {} {})",
                    candidate.pos.toShortString(), bot.getName().getString(), result.kind(), result.reason(),
                    result.scope());
        }

        long pauseMs = recordToolSearch(botUuid, paused, search);
        if (pauseMs > 0L && !(relook && search.waiting())) {
            LOGGER.info("Chest tool retrieval: nothing taken for {} from {} candidate(s) (last: {} {} {}); next search in {} s",
                    bot.getName().getString(), candidates.size(),
                    last == null ? "-" : last.kind(), last == null ? "-" : last.reason(),
                    last == null ? "-" : last.scope(), pauseMs / 1000L);
        } else {
            LOGGER.debug("Chest tool retrieval: nothing taken for {} from {} candidate(s) (last: {} {} {}); next search in {} s",
                    bot.getName().getString(), candidates.size(),
                    last == null ? "-" : last.kind(), last == null ? "-" : last.reason(),
                    last == null ? "-" : last.scope(), pauseMs / 1000L);
        }
        return false;
    }

    /**
     * Records how a chest tool search that took nothing ended and returns its pause
     * ({@link SupplyPullPolicy#retrievalPauseMs}); the miss count moves per
     * {@link SupplyPullPolicy#nextMissCount}. A search that sets no pause (it asked nothing, or
     * met only transient trouble) leaves a pause still running as it was, for the unpaused
     * {@link SupplyWithdrawals.WaitMode#UNTIL_ANSWERED} search, and clears the open-prompt mark,
     * so the next search refreshes the registry snapshots again.
     */
    private static long recordToolSearch(UUID botUuid, RetrievalPause prior, SupplyPullPolicy.Pull search) {
        int priorMisses = prior == null ? 0 : prior.misses();
        long pauseMs = SupplyPullPolicy.retrievalPauseMs(search, priorMisses);
        int misses = SupplyPullPolicy.nextMissCount(priorMisses, search);
        long nowMs = System.currentTimeMillis();
        long untilMs = pauseMs > 0L ? nowMs + pauseMs : (prior == null ? 0L : prior.untilMs());
        if (untilMs <= nowMs && misses <= 0) {
            TOOL_RETRIEVAL_PAUSES.remove(botUuid);
        } else {
            TOOL_RETRIEVAL_PAUSES.put(botUuid, new RetrievalPause(untilMs, misses, search.waiting()));
        }
        return pauseMs;
    }

    /**
     * Forgets every bot's chest tool search pause. Called at SERVER_STOPPED through
     * {@link BotIdleHobbiesService#resetSession()} (Frens' SERVER_STOPPED handler), so a pause
     * never carries into the next world.
     */
    public static void resetSession() {
        TOOL_RETRIEVAL_PAUSES.clear();
    }

    /**
     * One bot's chest tool search pause.
     *
     * @param untilMs        when its next {@link SupplyWithdrawals.WaitMode#NONE} search may ask again
     * @param misses         consecutive searches that asked and got nothing; a withdrawal resets it
     * @param awaitingAnswer the last search ended on an open prompt: the next one only looks again
     */
    private record RetrievalPause(long untilMs, int misses, boolean awaitingAnswer) {
    }

    /** Per bot: its chest tool search pause (see {@link #retrieveToolFromChests}). Any thread. */
    private static final Map<UUID, RetrievalPause> TOOL_RETRIEVAL_PAUSES = new ConcurrentHashMap<>();
}
