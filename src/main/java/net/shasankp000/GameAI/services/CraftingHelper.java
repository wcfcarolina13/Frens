package net.shasankp000.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.ReturnBaseStuckService;
import net.shasankp000.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Minimal crafting helper focused on basic block crafts (starting with crafting tables).
 */
public final class CraftingHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("crafting-helper");
    private static final Identifier CRAFTING_TABLE_ID = Identifier.of("minecraft", "crafting_table");
    private static final double STATION_REACH_SQ = 4.5D * 4.5D; // mimic player interact range
    private static final double COMMANDER_LOOK_RANGE = 24.0D;
    private static final int CHEST_SEARCH_RADIUS = 10;
    private static final int CHEST_OFFLOAD_SEARCH_RADIUS = 18;
    private static final int CHEST_OFFLOAD_SEARCH_YSPAN = 6;
    private static final Set<Item> CHEAP_MOB_DROPS = Set.of(
            Items.ROTTEN_FLESH,
            Items.BONE,
            Items.STRING,
            Items.SPIDER_EYE,
            Items.FEATHER,
            Items.LEATHER
    );
    private static final Set<Item> CHEAP_BLOCKS = Set.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRAVEL,
            Items.SAND,
            Items.RED_SAND,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.BLACKSTONE
    );
    private static final int CRAFTING_TABLE_SEARCH_RADIUS = 40;
    private static final int CRAFTING_TABLE_SEARCH_YSPAN = 6;
    private static final double MAX_REMEMBERED_TABLE_DIST_SQ = 140.0D * 140.0D;
    private static final Map<UUID, WorldPos> LAST_KNOWN_CRAFTING_TABLE = new java.util.concurrent.ConcurrentHashMap<>();

    private record WorldPos(net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey, BlockPos pos) {}

    private CraftingHelper() {
    }

    public static boolean craftCraftingTable(ServerCommandSource source, ServerPlayerEntity bot, ServerPlayerEntity commander, int amountRequested) {
        if (bot == null || source == null) {
            return false;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }

        int crafts = craftWithPlanks(bot, source, commander, CRAFTING_TABLE_ID, 4, Items.CRAFTING_TABLE, amountRequested);
        if (crafts > 0) {
            ChatUtils.sendSystemMessage(source, "Crafted " + crafts + " crafting table(s). Punch a block and run /bot place crafting_table to set it down.");
            return true;
        }
        return false;
    }

    public static int craftGeneric(ServerCommandSource source,
                                   ServerPlayerEntity bot,
                                   ServerPlayerEntity commander,
                                   String item,
                                   int amount,
                                   String materialPreference) {
        String normalized = item == null ? "" : item.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        if (normalized.endsWith("_planks")) {
            Item target = Registries.ITEM.get(Identifier.of("minecraft", normalized));
            return craftPlanks(bot, source, commander, amount, target);
        }
        return switch (normalized) {
            case "crafting_table" -> craftWithPlanks(bot, source, commander, CRAFTING_TABLE_ID, 4, Items.CRAFTING_TABLE, amount);
            case "sticks", "stick" -> craftSticks(bot, source, commander, amount);
            case "axe" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.AXE, materialPreference);
            case "shovel" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.SHOVEL, materialPreference);
            case "pickaxe" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.PICKAXE, materialPreference);
            case "hoe" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.HOE, materialPreference);
            case "sword" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.SWORD, materialPreference);
            case "shield" -> craftShield(bot, source, commander, amount);
            case "fishing_rod", "rod" -> craftFishingRod(bot, source, commander, amount);
            case "bucket" -> craftSimple(bot, source, commander, amount, Items.BUCKET, Items.IRON_INGOT, 3);
            case "shears" -> craftSimple(bot, source, commander, amount, Items.SHEARS, Items.IRON_INGOT, 2);
            case "furnace" -> craftCobbleBlock(bot, source, commander, amount, Items.FURNACE, 8);
            case "chest" -> craftWithPlanks(bot, source, commander, Identifier.of("minecraft", "chest"), 8, Items.CHEST, amount);
            case "planks", "plank" -> craftPlanks(bot, source, commander, amount, null);
            case "ladder", "ladders" -> craftLadders(bot, source, commander, amount);
            case "bed", "beds" -> craftBed(bot, source, commander, amount);
            default -> 0;
        };
    }

    private static int craftPlanks(ServerPlayerEntity bot,
                                   ServerCommandSource source,
                                   ServerPlayerEntity commander,
                                   int amount,
                                   Item targetPlank) {
        if (bot == null || source == null) {
            return 0;
        }
        int needed = Math.max(1, amount);
        int planksPerLog = 4;
        int logsNeeded = (int) Math.ceil(needed / (double) planksPerLog);
        int crafted = 0;

        for (int i = 0; i < bot.getInventory().size() && logsNeeded > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                continue;
            }
            if (targetPlank != null && !isMatchingPlank(stack.getItem(), targetPlank)) {
                continue;
            }
            int use = Math.min(stack.getCount(), logsNeeded);
            Item plankItem = mapLogToPlank(stack.getItem());
            if (plankItem == null || plankItem == Items.AIR) {
                continue;
            }
            stack.decrement(use);
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
            int produced = use * planksPerLog;
            distributeOutput(bot, plankItem, produced);
            recordCraftHistory(commander, plankItem);
            crafted += produced;
            logsNeeded -= use;
        }

        if (crafted == 0) {
            if (targetPlank != null && targetPlank != Items.AIR) {
                ChatUtils.sendSystemMessage(source, "I need matching logs to craft " + targetPlank.getName().getString() + ".");
            } else {
                ChatUtils.sendSystemMessage(source, "I need logs to craft planks.");
            }
            return 0;
        }
        return crafted;
    }

    private static int craftLadders(ServerPlayerEntity bot, ServerCommandSource source, ServerPlayerEntity commander, int amountRequested) {
        if (bot == null || source == null) {
            return 0;
        }
        // Ladder: 7 sticks => 3 ladders (3x3 recipe)
        if (!ensureCraftingStation(bot, source)) {
            ChatUtils.sendSystemMessage(source, "I need a crafting table placed nearby to craft that.");
            return 0;
        }
        if (!hasRecipePermission(commander, source.getServer(), Identifier.of("minecraft", "ladder"))) {
            ChatUtils.sendSystemMessage(source, "I don't know how to craft that yet.");
            return 0;
        }

        int craftsNeeded = (int) Math.ceil(amountRequested / 3.0);
        int neededSticks = craftsNeeded * 7;
        if (!ensureSticks(bot, source, neededSticks)) {
            int haveSticks = countItem(bot, Items.STICK);
            int planks = countPlanks(bot);
            ChatUtils.sendSystemMessage(source, "Missing sticks for ladders; need " + neededSticks + ", have " + haveSticks + " (planks: " + planks + ").");
            return 0;
        }

        int sticks = countItem(bot, Items.STICK);
        int crafts = Math.min(craftsNeeded, sticks / 7);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Missing sticks for ladders.");
            return 0;
        }
        Map<Item, Integer> reserveItems = new HashMap<>();
        reserveItems.put(Items.STICK, crafts * 7);
        ensureInventorySpaceForOutput(bot, source, Items.LADDER, crafts * 3, 0, 0, reserveItems);
        consumeItem(bot, Items.STICK, crafts * 7);
        distributeOutput(bot, Items.LADDER, crafts * 3);
        recordCraftHistory(commander, Identifier.of("minecraft", "ladder"));
        return crafts * 3;
    }

    private static int craftBed(ServerPlayerEntity bot, ServerCommandSource source, ServerPlayerEntity commander, int amount) {
        if (bot == null || source == null) {
            return 0;
        }
        if (!ensureCraftingStation(bot, source)) {
            ChatUtils.sendSystemMessage(source, "I need a crafting table placed nearby to craft a bed.");
            return 0;
        }
        // Choose a color based on available wool stacks (3 required per bed).
        record BedRecipe(net.minecraft.item.Item wool, net.minecraft.item.Item bed, Identifier recipeId) {}
        List<BedRecipe> recipes = List.of(
                new BedRecipe(Items.WHITE_WOOL, Items.WHITE_BED, Identifier.of("minecraft", "white_bed")),
                new BedRecipe(Items.BLACK_WOOL, Items.BLACK_BED, Identifier.of("minecraft", "black_bed")),
                new BedRecipe(Items.BLUE_WOOL, Items.BLUE_BED, Identifier.of("minecraft", "blue_bed")),
                new BedRecipe(Items.BROWN_WOOL, Items.BROWN_BED, Identifier.of("minecraft", "brown_bed")),
                new BedRecipe(Items.CYAN_WOOL, Items.CYAN_BED, Identifier.of("minecraft", "cyan_bed")),
                new BedRecipe(Items.GRAY_WOOL, Items.GRAY_BED, Identifier.of("minecraft", "gray_bed")),
                new BedRecipe(Items.GREEN_WOOL, Items.GREEN_BED, Identifier.of("minecraft", "green_bed")),
                new BedRecipe(Items.LIGHT_BLUE_WOOL, Items.LIGHT_BLUE_BED, Identifier.of("minecraft", "light_blue_bed")),
                new BedRecipe(Items.LIGHT_GRAY_WOOL, Items.LIGHT_GRAY_BED, Identifier.of("minecraft", "light_gray_bed")),
                new BedRecipe(Items.LIME_WOOL, Items.LIME_BED, Identifier.of("minecraft", "lime_bed")),
                new BedRecipe(Items.MAGENTA_WOOL, Items.MAGENTA_BED, Identifier.of("minecraft", "magenta_bed")),
                new BedRecipe(Items.ORANGE_WOOL, Items.ORANGE_BED, Identifier.of("minecraft", "orange_bed")),
                new BedRecipe(Items.PINK_WOOL, Items.PINK_BED, Identifier.of("minecraft", "pink_bed")),
                new BedRecipe(Items.PURPLE_WOOL, Items.PURPLE_BED, Identifier.of("minecraft", "purple_bed")),
                new BedRecipe(Items.RED_WOOL, Items.RED_BED, Identifier.of("minecraft", "red_bed")),
                new BedRecipe(Items.YELLOW_WOOL, Items.YELLOW_BED, Identifier.of("minecraft", "yellow_bed"))
        );

        BedRecipe chosen = null;
        Map<Item, Integer> totals = countItemsInInventoryAndNearbyChests(
                bot,
                source,
                recipes.stream().map(BedRecipe::wool).toList());
        for (BedRecipe recipe : recipes) {
            int count = totals.getOrDefault(recipe.wool(), 0);
            if (count >= 3) {
                chosen = recipe;
                break;
            }
        }
        if (chosen == null) {
            ChatUtils.sendSystemMessage(source, "Beds need 3 matching wool and 3 planks per bed.");
            return 0;
        }
        if (!hasRecipePermission(commander, source.getServer(), chosen.recipeId())) {
            ChatUtils.sendSystemMessage(source, "I don't know how to craft that yet.");
            return 0;
        }

        if (!ensurePlanksAvailable(bot, source, 3 * amount)) {
            ChatUtils.sendSystemMessage(source, "Beds need 3 planks per bed.");
            return 0;
        }

        int desiredWool = 3 * amount;
        Item woolItem = chosen.wool();
        int woolInInv = countItem(bot, woolItem);
        if (woolInInv < desiredWool) {
            withdrawFromNearbyChests(bot, source, s -> s.isOf(woolItem), desiredWool - woolInInv);
        }

        int plankCount = countPlanks(bot);
        int woolCount = countItem(bot, woolItem);
        int maxByPlanks = plankCount / 3;
        int maxByWool = woolCount / 3;
        int crafts = Math.min(amount, Math.min(maxByPlanks, maxByWool));
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Beds need 3 wool + 3 planks per bed.");
            return 0;
        }
        Map<Item, Integer> reserveItems = new HashMap<>();
        reserveItems.put(chosen.wool(), crafts * 3);
        ensureInventorySpaceForOutput(bot, source, chosen.bed(), crafts, crafts * 3, 0, reserveItems);
        if (!consumePlanks(bot, crafts * 3)) {
            ChatUtils.sendSystemMessage(source, "I couldn't reserve planks for beds.");
            return 0;
        }
        consumeItem(bot, chosen.wool(), crafts * 3);
        distributeOutput(bot, chosen.bed(), crafts);
        recordCraftHistory(commander, chosen.recipeId());
        return crafts;
    }

    private static int countPlanks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean consumePlanks(ServerPlayerEntity bot, int needed) {
        int remaining = needed;
        for (int i = 0; i < bot.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            stack.decrement(take);
            remaining -= take;
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
        return remaining == 0;
    }

    private static boolean hasRecipePermission(ServerPlayerEntity commander, MinecraftServer server, Identifier recipeId) {
        if (commander == null) {
            return true;
        }
        var key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.RECIPE, recipeId);
        Optional<RecipeEntry<?>> recipe = server.getRecipeManager().get(key);
        if (recipe.isEmpty()) {
            return false;
        }
        if (commander.isCreative()) {
            return true;
        }
        return true; // Fallback: server recipes are present; per-player gating not exposed here
    }

    private static int craftWithPlanks(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       Identifier recipeId,
                                       int planksPerItem,
                                       net.minecraft.item.Item output,
                                       int amountRequested) {
        if (!output.equals(Items.CRAFTING_TABLE)) {
            if (!ensureCraftingStation(bot, source)) {
                ChatUtils.sendSystemMessage(source, "I need a crafting table placed nearby to craft that.");
                return 0;
            }
        }
        if (!hasRecipePermission(commander, source.getServer(), recipeId)) {
            ChatUtils.sendSystemMessage(source, "I don't know how to craft that yet.");
            return 0;
        }
        if (!ensurePlanksAvailable(bot, source, planksPerItem * amountRequested)) {
            ChatUtils.sendSystemMessage(source, "I need " + planksPerItem + " planks per craft.");
            return 0;
        }
        int plankCount = countPlanks(bot);
        int maxByPlanks = plankCount / planksPerItem;
        int crafts = Math.min(amountRequested, maxByPlanks);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "I need " + planksPerItem + " planks per craft. Missing " + (planksPerItem - plankCount) + ".");
            return 0;
        }
        ensureInventorySpaceForOutput(bot, source, output, crafts, crafts * planksPerItem, 0, Map.of());
        if (!consumePlanks(bot, crafts * planksPerItem)) {
            ChatUtils.sendSystemMessage(source, "I couldn't reserve planks for crafting.");
            return 0;
        }
        distributeOutput(bot, output, crafts);
        recordCraftHistory(commander, recipeId);
        return crafts;
    }

    private static int craftSticks(ServerPlayerEntity bot, ServerCommandSource source, ServerPlayerEntity commander, int amount) {
        // 2 planks => 4 sticks
        int craftsNeeded = (int) Math.ceil(amount / 4.0);
        if (!ensurePlanksAvailable(bot, source, craftsNeeded * 2)) {
            ChatUtils.sendSystemMessage(source, "Sticks need 2 planks per craft.");
            return 0;
        }
        if (!hasRecipePermission(commander, source.getServer(), Identifier.of("minecraft", "stick"))) {
            ChatUtils.sendSystemMessage(source, "I don't know how to craft that yet.");
            return 0;
        }
        int plankCount = countPlanks(bot);
        int maxByPlanks = plankCount / 2;
        int crafts = Math.min(craftsNeeded, maxByPlanks);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Sticks need 2 planks per craft.");
            return 0;
        }
        ensureInventorySpaceForOutput(bot, source, Items.STICK, crafts * 4, crafts * 2, 0, Map.of());
        if (!consumePlanks(bot, crafts * 2)) {
            ChatUtils.sendSystemMessage(source, "I couldn't reserve planks for sticks.");
            return 0;
        }
        distributeOutput(bot, Items.STICK, crafts * 4);
        recordCraftHistory(commander, Identifier.of("minecraft", "stick"));
        return crafts * 4;
    }

    private enum ToolKind {
        AXE, SHOVEL, PICKAXE, HOE, SWORD
    }

    private record ToolMaterial(net.minecraft.item.Item headItem, int headCount, String name) {}

    private static int craftToolMaterialAware(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              ServerPlayerEntity commander,
                                              int amount,
                                              ToolKind kind,
                                              String materialPreference) {
        List<ToolMaterial> materials = List.of(
                new ToolMaterial(Items.DIAMOND, 3, "diamond"),
                new ToolMaterial(Items.IRON_INGOT, 3, "iron"),
                new ToolMaterial(Items.COBBLESTONE, 3, "stone"),
                new ToolMaterial(Items.COBBLED_DEEPSLATE, 3, "stone"),
                new ToolMaterial(Items.BLACKSTONE, 3, "stone"),
                new ToolMaterial(Items.OAK_PLANKS, 3, "wood") // plank sentinel, counted via countPlanks
        );

        // Determine preferred material order
        if (materialPreference != null && !materialPreference.isBlank()) {
            materials = materials.stream()
                    .sorted((a, b) -> Boolean.compare(
                            b.name().equalsIgnoreCase(materialPreference),
                            a.name().equalsIgnoreCase(materialPreference)))
                    .toList();
        }

        if (materialPreference == null || materialPreference.isBlank()) {
            String avail = listAvailableMaterials(bot, materials);
            LOGGER.info("Crafting {} with default material order. Available: {}", kind.name().toLowerCase(Locale.ROOT), avail);
        }

        for (ToolMaterial mat : materials) {
            int crafts = craftToolWithMaterial(bot, source, commander, kind, amount, mat);
            if (crafts > 0) {
                return crafts;
            }
        }
        ChatUtils.sendSystemMessage(source, "Missing materials for " + kind.name().toLowerCase(Locale.ROOT) + ".");
        return 0;
    }

    private static int craftToolWithMaterial(ServerPlayerEntity bot,
                                             ServerCommandSource source,
                                             ServerPlayerEntity commander,
                                             ToolKind kind,
                                             int amount,
                                             ToolMaterial mat) {
        // Ensure crafting table nearby for 3x3 recipes
        if (!ensureCraftingStation(bot, source)) {
            ChatUtils.sendSystemMessage(source, "I need a crafting table placed nearby to craft that.");
            return 0;
        }

        int stickCount = switch (kind) {
            case SWORD -> 1;
            case SHOVEL -> 2;
            default -> 2;
        };
        int headCount = switch (kind) {
            case SHOVEL -> 1;
            case HOE -> 2;
            case SWORD -> 2;
            default -> mat.headCount();
        };

        int neededSticks = stickCount * amount;
        if (!ensureSticks(bot, source, neededSticks)) {
            int haveSticks = countItem(bot, Items.STICK);
            int planks = countPlanks(bot);
            ChatUtils.sendSystemMessage(source, "Missing sticks; need " + neededSticks + ", have " + haveSticks + " (planks: " + planks + "). Add planks/logs and retry.");
            return 0;
        }
        int sticks = countItem(bot, Items.STICK);

        if (mat.headItem().equals(Items.OAK_PLANKS)) {
            ensurePlanksAvailable(bot, source, headCount * amount);
        } else {
            ensureItemAvailable(bot, source, mat.headItem(), headCount * amount);
        }
        int heads = mat.headItem().equals(Items.OAK_PLANKS) ? countPlanks(bot) : countItem(bot, mat.headItem());

        int maxBySticks = sticks / stickCount;
        int maxByHead = heads / headCount;
        int crafts = Math.min(amount, Math.min(maxBySticks, maxByHead));
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Missing materials for " + mat.name() + " " + kind.name().toLowerCase(Locale.ROOT) + ".");
            return 0;
        }

        net.minecraft.item.Item output = switch (kind) {
            case AXE -> resolveTiered(mat, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE);
            case SHOVEL -> resolveTiered(mat, Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL);
            case PICKAXE -> resolveTiered(mat, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE);
            case HOE -> resolveTiered(mat, Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE);
            case SWORD -> resolveTiered(mat, Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD);
        };
        Map<Item, Integer> reserveItems = new HashMap<>();
        reserveItems.put(Items.STICK, crafts * stickCount);
        if (mat.headItem().equals(Items.OAK_PLANKS)) {
            ensureInventorySpaceForOutput(bot, source, output, crafts, crafts * headCount, 0, reserveItems);
        } else {
            reserveItems.put(mat.headItem(), crafts * headCount);
            ensureInventorySpaceForOutput(bot, source, output, crafts, 0, 0, reserveItems);
        }

        consumeItem(bot, Items.STICK, crafts * stickCount);
        if (mat.headItem().equals(Items.OAK_PLANKS)) {
            consumePlanks(bot, crafts * headCount);
        } else {
            consumeItem(bot, mat.headItem(), crafts * headCount);
        }

        LOGGER.info("Crafted {} {}(s) using {} (sticks {} each, heads {}x {})",
                crafts,
                kind.name().toLowerCase(Locale.ROOT),
                mat.name(),
                stickCount,
                headCount,
                mat.headItem().getName().getString());
        distributeOutput(bot, output, crafts);
        recordCraftHistory(commander, output);
        return crafts;
    }

    private static net.minecraft.item.Item resolveTiered(ToolMaterial mat,
                                                         net.minecraft.item.Item wood,
                                                         net.minecraft.item.Item stone,
                                                         net.minecraft.item.Item iron,
                                                         net.minecraft.item.Item diamond) {
        if (mat.headItem().equals(Items.DIAMOND)) return diamond;
        if (mat.headItem().equals(Items.IRON_INGOT)) return iron;
        if (mat.name().equals("stone")) return stone;
        return wood;
    }

    private static String listAvailableMaterials(ServerPlayerEntity bot, List<ToolMaterial> mats) {
        List<String> available = new ArrayList<>();
        for (ToolMaterial m : mats) {
            int count = m.headItem().equals(Items.OAK_PLANKS)
                    ? countPlanks(bot)
                    : countItem(bot, m.headItem());
            if (count >= m.headCount()) {
                available.add(m.name() + " (" + count + ")");
            }
        }
        return available.isEmpty() ? "none" : String.join(", ", available);
    }

    private static int craftShield(ServerPlayerEntity bot,
                                   ServerCommandSource source,
                                   ServerPlayerEntity commander,
                                   int amount) {
        if (!ensureCraftingStation(bot, source)) {
            ChatUtils.sendSystemMessage(source, "I need a crafting table placed nearby to craft a shield.");
            return 0;
        }
        ensurePlanksAvailable(bot, source, 6 * amount);
        ensureItemAvailable(bot, source, Items.IRON_INGOT, amount);
        int planks = countPlanks(bot);
        int iron = countItem(bot, Items.IRON_INGOT);
        int crafts = Math.min(amount, Math.min(planks / 6, iron / 1));
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Shield needs 6 planks and 1 iron ingot each.");
            return 0;
        }
        Map<Item, Integer> reserveItems = new HashMap<>();
        reserveItems.put(Items.IRON_INGOT, crafts);
        ensureInventorySpaceForOutput(bot, source, Items.SHIELD, crafts, crafts * 6, 0, reserveItems);
        consumePlanks(bot, crafts * 6);
        consumeItem(bot, Items.IRON_INGOT, crafts);
        distributeOutput(bot, Items.SHIELD, crafts);
        recordCraftHistory(commander, Items.SHIELD);
        return crafts;
    }

    public static boolean ensureCraftingStation(ServerPlayerEntity bot, ServerCommandSource source) {
        if (bot == null) return false;
        // Check nearby crafting table
        BlockPos botPos = bot.getBlockPos();
        ServerWorld world = source.getWorld();
        BlockPos nearest = null;
        int radius = CRAFTING_TABLE_SEARCH_RADIUS;
        int ySpan = CRAFTING_TABLE_SEARCH_YSPAN;

        // Prefer a remembered table location for this bot/world (fast, avoids large scans).
        WorldPos remembered = LAST_KNOWN_CRAFTING_TABLE.get(bot.getUuid());
        if (remembered != null && remembered.worldKey() != null && remembered.worldKey().equals(world.getRegistryKey())) {
            BlockPos pos = remembered.pos();
            if (pos != null && botPos.getSquaredDistance(pos) <= MAX_REMEMBERED_TABLE_DIST_SQ) {
                nearest = pos.toImmutable();
            }
        }

        // First: use commander look target if it's a crafting table.
        BlockPos commanderLook = null;
        if (source.getPlayer() != null && (source.getServer() == null || source.getServer().isOnThread())) {
            var hit = source.getPlayer().raycast(COMMANDER_LOOK_RANGE, 0, false);
            if (hit instanceof net.minecraft.util.hit.BlockHitResult bhr) {
                commanderLook = bhr.getBlockPos();
            }
        }
        if (commanderLook != null && world.getBlockState(commanderLook).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
            nearest = commanderLook.toImmutable();
        }

        if (nearest == null) {
            double best = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.iterate(botPos.add(-radius, -ySpan, -radius), botPos.add(radius, ySpan, radius))) {
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                    double distSq = botPos.getSquaredDistance(pos);
                    if (distSq < best) {
                        best = distSq;
                        nearest = pos.toImmutable();
                    }
                }
            }
        }
        if (nearest != null) {
            // If the chunk is loaded and the remembered table is gone, drop it.
            if (world.isChunkLoaded(nearest) && !world.getBlockState(nearest).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                LAST_KNOWN_CRAFTING_TABLE.remove(bot.getUuid());
                nearest = null;
            }
        }
        if (nearest != null) {
            double distSq = botPos.getSquaredDistance(nearest);
            LOGGER.info("Found nearby crafting table at {}", nearest.toShortString());
            LAST_KNOWN_CRAFTING_TABLE.put(bot.getUuid(), new WorldPos(world.getRegistryKey(), nearest.toImmutable()));
            if (distSq > 36.0D) { // >6 blocks away
                ChatUtils.sendSystemMessage(source,
                        "Give me a moment — I'll use the crafting table at "
                                + nearest.getX() + ", " + nearest.getY() + ", " + nearest.getZ() + ".");
            }

            // If this table is far enough that its chunk may be unloaded, approach conservatively first
            // (avoid scanning standable blocks which can chunk-load and hitch).
            if (!world.isChunkLoaded(nearest) && distSq > (CRAFTING_TABLE_SEARCH_RADIUS * (double) CRAFTING_TABLE_SEARCH_RADIUS)) {
                boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);
                List<BlockPos> approaches = List.of(
                        nearest.north(),
                        nearest.south(),
                        nearest.east(),
                        nearest.west()
                );
                for (BlockPos approach : approaches) {
                    MovementService.MovementPlan plan = new MovementService.MovementPlan(
                            MovementService.Mode.DIRECT,
                            approach,
                            approach,
                            null,
                            null,
                            bot.getHorizontalFacing());
                    MovementService.MovementResult res = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
                    if (res.success() || bot.getBlockPos().getSquaredDistance(nearest) <= STATION_REACH_SQ) {
                        if (ensureStationInteractable(bot, nearest, STATION_REACH_SQ)) {
                            return true;
                        }
                    }
                    ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(nearest));
                }
                // Fall through to the normal standable scan once the chunk is likely loaded.
            }
            List<BlockPos> standables = findStandableOptions(world, nearest, 2);
            LOGGER.info("Standable options near crafting table ({}): {}", nearest.toShortString(), standables.size());
            if (standables.isEmpty()) {
                LOGGER.warn("No standable spot near crafting table at {}", nearest.toShortString());
                return false;
            }
            BlockPos approach = standables.get(0);
            LOGGER.info("Selected approach {} (dist={})", approach.toShortString(), Math.sqrt(approach.getSquaredDistance(nearest)));
            if (bot.getBlockPos().getSquaredDistance(approach) <= STATION_REACH_SQ) {
                return ensureStationInteractable(bot, nearest, STATION_REACH_SQ);
            }
            boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    approach,
                    approach,
                    null,
                    null,
                    bot.getHorizontalFacing());
            MovementService.MovementResult res = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
            if (res.success() || bot.getBlockPos().getSquaredDistance(approach) <= STATION_REACH_SQ) {
                LOGGER.info("Reached crafting table approach {}", approach.toShortString());
                return ensureStationInteractable(bot, nearest, STATION_REACH_SQ);
            }
            ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(nearest));
            MovementService.clearRecentWalkAttempt(bot.getUuid());
            boolean close = MovementService.nudgeTowardUntilClose(bot, approach, STATION_REACH_SQ, 2200L, 0.14, "craft-table-nudge");
            if (!close) {
                LOGGER.warn("Failed to reach crafting table at {}", nearest.toShortString());
                ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(nearest));
            }
            return close && ensureStationInteractable(bot, nearest, STATION_REACH_SQ);
        }

        // Try placing from inventory
        int slot = findItemInInventory(bot, Items.CRAFTING_TABLE);
        if (slot != -1) {
            BlockPos placeAt = findNearbyStationPlacement(world, botPos);
            if (placeAt == null) {
                placeAt = botPos.offset(bot.getHorizontalFacing());
            }
            boolean placed = BotActions.placeBlockAt(bot, placeAt, java.util.List.of(Items.CRAFTING_TABLE));
            if (!placed || !world.getBlockState(placeAt).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                // Fallback: try a few nearby cells if the forward placement failed (blocked/fluids/odd footing).
                for (BlockPos alt : findNearbyStationPlacementOptions(world, botPos)) {
                    if (BotActions.placeBlockAt(bot, alt, java.util.List.of(Items.CRAFTING_TABLE))
                            && world.getBlockState(alt).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                        placeAt = alt.toImmutable();
                        placed = true;
                        break;
                    }
                }
            }
            if (!placed || !world.getBlockState(placeAt).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                LOGGER.warn("Failed to place crafting table from inventory near {}", botPos.toShortString());
                ChatUtils.sendSystemMessage(source, "I couldn't place a crafting table here.");
                return false;
            }
            LOGGER.info("Placed crafting table from inventory at {}", placeAt.toShortString());
            LAST_KNOWN_CRAFTING_TABLE.put(bot.getUuid(), new WorldPos(world.getRegistryKey(), placeAt.toImmutable()));
            boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    placeAt,
                    placeAt,
                    null,
                    null,
                    bot.getHorizontalFacing());
            MovementService.MovementResult res = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
            if (res.success() || bot.getBlockPos().getSquaredDistance(placeAt) <= STATION_REACH_SQ) {
                LOGGER.info("Reached newly placed crafting table at {}", placeAt.toShortString());
                return true;
            }
            LOGGER.warn("Failed to step to newly placed crafting table at {}: {}", placeAt.toShortString(), res.detail());
            ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(placeAt));
            MovementService.clearRecentWalkAttempt(bot.getUuid());
            boolean close = MovementService.nudgeTowardUntilClose(bot, placeAt, STATION_REACH_SQ, 1500L, 0.14, "craft-place-nudge");
            return close;
        }

        // Try crafting a crafting table from materials
        LOGGER.info("No crafting table found; attempting to craft one.");
        ensurePlanksAvailable(bot, source, 4);
        boolean crafted = craftCraftingTable(source, bot, source.getPlayer(), 1);
        if (crafted && findItemInInventory(bot, Items.CRAFTING_TABLE) != -1) {
            BlockPos placeAt = findNearbyStationPlacement(world, botPos);
            if (placeAt == null) {
                placeAt = botPos.offset(bot.getHorizontalFacing());
            }
            boolean placed = BotActions.placeBlockAt(bot, placeAt, java.util.List.of(Items.CRAFTING_TABLE));
            if (!placed || !world.getBlockState(placeAt).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                for (BlockPos alt : findNearbyStationPlacementOptions(world, botPos)) {
                    if (BotActions.placeBlockAt(bot, alt, java.util.List.of(Items.CRAFTING_TABLE))
                            && world.getBlockState(alt).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                        placeAt = alt.toImmutable();
                        placed = true;
                        break;
                    }
                }
            }
            if (!placed || !world.getBlockState(placeAt).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                LOGGER.warn("Crafted a crafting table but failed to place it near {}", botPos.toShortString());
                ChatUtils.sendSystemMessage(source, "I crafted a crafting table but couldn't place it here.");
                return false;
            }
            LOGGER.info("Crafted and placed crafting table at {}", placeAt.toShortString());
            LAST_KNOWN_CRAFTING_TABLE.put(bot.getUuid(), new WorldPos(world.getRegistryKey(), placeAt.toImmutable()));
            boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    placeAt,
                    placeAt,
                    null,
                    null,
                    bot.getHorizontalFacing());
            MovementService.MovementResult res = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
            if (res.success() || bot.getBlockPos().getSquaredDistance(placeAt) <= STATION_REACH_SQ) {
                return true;
            }
            ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(placeAt));
            MovementService.clearRecentWalkAttempt(bot.getUuid());
            boolean close = MovementService.nudgeTowardUntilClose(bot, placeAt, STATION_REACH_SQ, 1500L, 0.14, "craft-crafted-table-nudge");
            return close;
        }

        LOGGER.info("No crafting table within {} blocks of {}", radius, botPos.toShortString());
        ChatUtils.sendSystemMessage(source, "I need a crafting table nearby to craft that.");
        return false;
    }

    private static BlockPos findNearbyStationPlacement(ServerWorld world, BlockPos origin) {
        List<BlockPos> options = findNearbyStationPlacementOptions(world, origin);
        return options.isEmpty() ? null : options.get(0);
    }

    private static List<BlockPos> findNearbyStationPlacementOptions(ServerWorld world, BlockPos origin) {
        if (world == null || origin == null) {
            return List.of();
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            candidates.add(origin.offset(dir));
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                candidates.add(origin.add(dx, 0, dz));
            }
        }
        List<BlockPos> valid = new ArrayList<>();
        for (BlockPos pos : candidates) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockPos below = pos.down();
            if (!world.isChunkLoaded(below)) {
                continue;
            }
            if (!world.getFluidState(pos).isEmpty() || !world.getFluidState(below).isEmpty()) {
                continue;
            }
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                continue;
            }
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            if (!state.isAir() && !state.isReplaceable() && !state.isOf(net.minecraft.block.Blocks.SNOW)) {
                continue;
            }
            if (!hasAnyAdjacentStand(world, pos)) {
                continue;
            }
            valid.add(pos.toImmutable());
        }
        valid.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return valid;
    }

    private static boolean hasAnyAdjacentStand(ServerWorld world, BlockPos stationPos) {
        if (world == null || stationPos == null) {
            return false;
        }
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos stand = stationPos.offset(dir);
            BlockPos below = stand.down();
            if (!world.isChunkLoaded(stand) || !world.isChunkLoaded(below)) {
                continue;
            }
            if (!world.getFluidState(stand).isEmpty() || !world.getFluidState(below).isEmpty()) {
                continue;
            }
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(stand).getCollisionShape(world, stand).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(stand.up()).getCollisionShape(world, stand.up()).isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean ensureStationInteractable(ServerPlayerEntity bot, BlockPos stationPos, double reachSq) {
        if (bot == null || stationPos == null) {
            return false;
        }
        if (BlockInteractionService.canInteract(bot, stationPos, reachSq)) {
            return true;
        }
        // If something is blocking interaction (often a door), try to open it and re-check.
        BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, stationPos, reachSq);
        if (blockingDoor != null) {
            MovementService.tryOpenDoorAt(bot, blockingDoor);
            return BlockInteractionService.canInteract(bot, stationPos, reachSq);
        }
        return false;
    }

    private static int craftSimple(ServerPlayerEntity bot,
                                   ServerCommandSource source,
                                   ServerPlayerEntity commander,
                                   int amount,
                                   net.minecraft.item.Item output,
                                   net.minecraft.item.Item input,
                                   int per) {
        if (output.equals(Items.BUCKET)) {
            if (!ensureCraftingStation(bot, source)) {
                ChatUtils.sendSystemMessage(source, "I need a crafting table placed nearby to craft that.");
                return 0;
            }
        }
        if (input.equals(Items.IRON_INGOT)) {
            ensureItemAvailable(bot, source, Items.IRON_INGOT, per * amount);
        }
        int have = countItem(bot, input);
        int crafts = Math.min(amount, have / per);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "I need " + per + " " + input.getName().getString() + " per craft.");
            return 0;
        }
        Map<Item, Integer> reserveItems = new HashMap<>();
        reserveItems.put(input, crafts * per);
        ensureInventorySpaceForOutput(bot, source, output, crafts, 0, 0, reserveItems);
        consumeItem(bot, input, crafts * per);
        distributeOutput(bot, output, crafts);
        recordCraftHistory(commander, output);
        return crafts;
    }

    private static int craftCobbleBlock(ServerPlayerEntity bot,
                                        ServerCommandSource source,
                                        ServerPlayerEntity commander,
                                        int amount,
                                        net.minecraft.item.Item output,
                                        int per) {
        if (output.equals(Items.FURNACE)) {
            if (!ensureCraftingStation(bot, source)) {
                ChatUtils.sendSystemMessage(source, "I need a crafting table placed nearby to craft that.");
                return 0;
            }
        }
        ensureCobbleAvailable(bot, source, per * amount);
        int cobble = countCobble(bot);
        int crafts = Math.min(amount, cobble / per);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "I need " + per + " cobblestone (or similar) per craft.");
            return 0;
        }
        Map<Item, Integer> reserveItems = new HashMap<>();
        reserveItems.put(Items.COBBLESTONE, crafts * per);
        reserveItems.put(Items.COBBLED_DEEPSLATE, crafts * per);
        reserveItems.put(Items.BLACKSTONE, crafts * per);
        ensureInventorySpaceForOutput(bot, source, output, crafts, 0, 0, reserveItems);
        consumeCobble(bot, crafts * per);
        distributeOutput(bot, output, crafts);
        recordCraftHistory(commander, output);
        return crafts;
    }

    private static int craftFishingRod(ServerPlayerEntity bot,
                                       ServerCommandSource source,
                                       ServerPlayerEntity commander,
                                       int amount) {
        if (!ensureCraftingStation(bot, source)) {
            ChatUtils.sendSystemMessage(source, "Fishing rods require a nearby crafting table.");
            return 0;
        }
        ensureSticks(bot, source, amount * 3);
        ensureItemAvailable(bot, source, Items.STRING, amount * 2);
        int sticks = countItem(bot, Items.STICK);
        int strings = countItem(bot, Items.STRING);
        int crafts = Math.min(amount, Math.min(sticks / 3, strings / 2));
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Fishing rods need 3 sticks and 2 strings each.");
            return 0;
        }
        Map<Item, Integer> reserveItems = new HashMap<>();
        reserveItems.put(Items.STICK, crafts * 3);
        reserveItems.put(Items.STRING, crafts * 2);
        ensureInventorySpaceForOutput(bot, source, Items.FISHING_ROD, crafts, 0, 0, reserveItems);
        consumeItem(bot, Items.STICK, crafts * 3);
        consumeItem(bot, Items.STRING, crafts * 2);
        distributeOutput(bot, Items.FISHING_ROD, crafts);
        recordCraftHistory(commander, Items.FISHING_ROD);
        return crafts;
    }

    private static int countItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void consumeItem(ServerPlayerEntity bot, net.minecraft.item.Item item, int needed) {
        int remaining = needed;
        for (int i = 0; i < bot.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isOf(item)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            stack.decrement(take);
            remaining -= take;
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private static void distributeOutput(ServerPlayerEntity bot, net.minecraft.item.Item output, int count) {
        debugChest("Distribute output item=" + output.getName().getString() + " count=" + count
                + " emptySlot=" + bot.getInventory().getEmptySlot());
        ensureInventorySpaceForOutput(bot, bot.getCommandSource(), output, count, 0, 0, Map.of());
        ItemStack stack = new ItemStack(output, count);
        boolean added = bot.getInventory().insertStack(stack);
        debugChest("InsertStack result added=" + added + " remaining=" + stack.getCount());
        if (!added && !stack.isEmpty()) {
            debugChest("InsertStack failed; retrying after offload.");
            ensureInventorySpaceForOutput(bot, bot.getCommandSource(), output, stack.getCount(), 0, 0, Map.of(), true);
            boolean retryAdded = bot.getInventory().insertStack(stack);
            debugChest("InsertStack retry added=" + retryAdded + " remaining=" + stack.getCount());
            if (!retryAdded && !stack.isEmpty()) {
                bot.dropItem(stack, false, false);
            }
        }
    }

    private static ItemStack stashInChest(ServerPlayerEntity bot, ItemStack stack) {
        if (bot == null || stack == null || stack.isEmpty() || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return stack;
        }
        List<BlockPos> chests = findNearbyChests(world, bot.getBlockPos(), CHEST_SEARCH_RADIUS);
        debugChest("StashInChest chestsFound=" + chests.size() + " botPos=" + bot.getBlockPos().toShortString());
        if (chests.isEmpty()) {
            debugChest("StashInChest no chests within radius=" + CHEST_SEARCH_RADIUS);
        }
        for (BlockPos pos : chests) {
            debugChest("Stash chest candidate pos=" + pos.toShortString()
                    + " distSq=" + bot.getBlockPos().getSquaredDistance(pos));
        }
        ItemStack remaining = depositIntoChests(bot, stack.copy(), chests, true);
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }
        remaining = depositIntoChests(bot, remaining, chests, false);
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }
        net.minecraft.block.entity.ChestBlockEntity placed = placeChestNearBot(world, bot, STATION_REACH_SQ);
        if (placed == null) {
            return remaining;
        }
        ItemStack afterPlaced = insertIntoInventory(placed, remaining.copy());
        if (afterPlaced.isEmpty()) {
            placed.markDirty();
            return ItemStack.EMPTY;
        }
        return afterPlaced;
    }

    private static void ensureInventorySpaceForOutput(ServerPlayerEntity bot,
                                                      ServerCommandSource source,
                                                      Item output,
                                                      int outputCount,
                                                      int reservePlanks,
                                                      int reserveLogs,
                                                      Map<Item, Integer> reserveItems) {
        ensureInventorySpaceForOutput(bot, source, output, outputCount, reservePlanks, reserveLogs, reserveItems, false);
    }

    private static void ensureInventorySpaceForOutput(ServerPlayerEntity bot,
                                                      ServerCommandSource source,
                                                      Item output,
                                                      int outputCount,
                                                      int reservePlanks,
                                                      int reserveLogs,
                                                      Map<Item, Integer> reserveItems,
                                                      boolean forceOffload) {
        if (bot == null || source == null || output == null) {
            return;
        }
        if (outputCount <= 0) {
            return;
        }
        if (!forceOffload) {
            if (hasRoomForItem(bot, output)) {
                debugChest("Skip offload: room for output=" + output.getName().getString()
                        + " count=" + outputCount
                        + " emptySlot=" + bot.getInventory().getEmptySlot());
                return;
            }
            if (bot.getInventory().getEmptySlot() != -1) {
                return;
            }
        }
        debugChest("Need space for output=" + output.getName().getString()
                + " count=" + outputCount
                + " reserves(planks=" + reservePlanks
                + ", logs=" + reserveLogs
                + ", items=" + reserveItems.size() + ")"
                + " force=" + forceOffload
                + " botPos=" + bot.getBlockPos().toShortString());
        offloadCheapItemsToNearbyChest(bot, source, reservePlanks, reserveLogs, reserveItems);
        debugChest("Post-offload emptySlot=" + bot.getInventory().getEmptySlot());
    }

    static boolean offloadCheapItemsToNearbyChest(ServerPlayerEntity bot,
                                                  ServerCommandSource source,
                                                  int reservePlanks,
                                                  int reserveLogs,
                                                  Map<Item, Integer> reserveItems) {
        return offloadCheapItemsToNearbyChestInternal(bot, source, reservePlanks, reserveLogs, reserveItems);
    }

    private static boolean offloadCheapItemsToNearbyChestInternal(ServerPlayerEntity bot,
                                                                  ServerCommandSource source,
                                                                  int reservePlanks,
                                                                  int reserveLogs,
                                                                  Map<Item, Integer> reserveItems) {
        if (bot == null || source == null) {
            return false;
        }
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> chests = findNearbyChests(world, bot.getBlockPos(), CHEST_OFFLOAD_SEARCH_RADIUS, CHEST_OFFLOAD_SEARCH_YSPAN);
        if (chests.isEmpty()) {
            BlockPos placed = ChestStoreService.placeChestNearBot(source, bot, false);
            if (placed != null) {
                chests = List.of(placed.toImmutable());
            }
        }
        if (chests.isEmpty()) {
            debugChest("No nearby chests and no placeable chest; skipping offload.");
            return false;
        }
        debugChest("Offloading to " + chests.size() + " chest(s)"
                + " reserves(planks=" + reservePlanks
                + ", logs=" + reserveLogs
                + ", items=" + reserveItems.size() + ")");
        for (BlockPos pos : chests) {
            debugChest("Chest candidate pos=" + pos.toShortString()
                    + " distSq=" + bot.getBlockPos().getSquaredDistance(pos));
        }
        Set<Item> reservedItems = new HashSet<>(reserveItems.keySet());
        boolean reservePlanksNeeded = reservePlanks > 0;
        boolean reserveLogsNeeded = reserveLogs > 0;
        Predicate<ItemStack> cheapFilter = stack -> isCheapDumpItem(stack)
                && !isReservedForCraft(stack, reservedItems, reservePlanksNeeded, reserveLogsNeeded);
        for (BlockPos chestPos : chests) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, cheapFilter);
            debugChest("Offload attempt (cheap) chest=" + chestPos.toShortString() + " moved=" + moved);
            if (moved > 0) {
                return true;
            }
        }
        debugChest("Cheap offload not enough; attempting non-damageable offload.");
        Predicate<ItemStack> fallbackFilter = stack -> !isProtectedItem(stack)
                && !isReservedForCraft(stack, reservedItems, reservePlanksNeeded, reserveLogsNeeded);
        for (BlockPos chestPos : chests) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, fallbackFilter);
            debugChest("Offload attempt (fallback) chest=" + chestPos.toShortString() + " moved=" + moved);
            if (moved > 0) {
                return true;
            }
        }
        debugChest("Offload complete after fallback; empty slot=" + bot.getInventory().getEmptySlot());
        return bot.getInventory().getEmptySlot() != -1;
    }

    private static void offloadMatchingItems(ServerPlayerEntity bot,
                                             Map<Item, Integer> remainingReserves,
                                             int[] keepPlanks,
                                             int[] keepLogs,
                                             List<BlockPos> chests,
                                             Predicate<ItemStack> allowMove) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getEmptySlot() != -1) {
                return;
            }
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || !allowMove.test(stack)) {
                continue;
            }
            String itemName = stack.getItem().getName().getString();
            int keep = 0;
            Integer itemReserve = remainingReserves.get(stack.getItem());
            if (itemReserve != null && itemReserve > 0) {
                keep = Math.min(itemReserve, stack.getCount());
                remainingReserves.put(stack.getItem(), itemReserve - keep);
            } else if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS) && keepPlanks[0] > 0) {
                keep = Math.min(keepPlanks[0], stack.getCount());
                keepPlanks[0] -= keep;
            } else if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS) && keepLogs[0] > 0) {
                keep = Math.min(keepLogs[0], stack.getCount());
                keepLogs[0] -= keep;
            }
            int toMove = stack.getCount() - keep;
            if (toMove <= 0) {
                continue;
            }
            debugChest("Offload candidate item=" + itemName + " count=" + stack.getCount() + " keep=" + keep);
            ItemStack moving = stack.copy();
            moving.setCount(toMove);
            ItemStack remaining = depositIntoChests(bot, moving, chests, true);
            if (!remaining.isEmpty()) {
                remaining = depositIntoChests(bot, remaining, chests, false);
            }
            int moved = toMove - remaining.getCount();
            if (moved > 0) {
                debugChest("Offloaded " + moved + "x " + itemName);
                stack.decrement(moved);
                if (stack.isEmpty()) {
                    bot.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }
        }
    }

    private static boolean isCheapDumpItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
            return true;
        }
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
            return true;
        }
        Item item = stack.getItem();
        return CHEAP_BLOCKS.contains(item) || CHEAP_MOB_DROPS.contains(item);
    }

    private static boolean isReservedForCraft(ItemStack stack,
                                              Set<Item> reservedItems,
                                              boolean reservePlanksNeeded,
                                              boolean reserveLogsNeeded) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (reservedItems != null && reservedItems.contains(stack.getItem())) {
            return true;
        }
        if (reservePlanksNeeded && stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
            return true;
        }
        if (reserveLogsNeeded && stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
            return true;
        }
        return false;
    }
    private static boolean isProtectedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        return ChestStoreService.isOffloadProtected(stack);
    }

    private static boolean hasRoomForItem(ServerPlayerEntity bot, Item item) {
        if (bot == null || item == null) {
            return false;
        }
        if (bot.getInventory().getEmptySlot() != -1) {
            return true;
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


    private static ItemStack depositIntoChests(ServerPlayerEntity bot,
                                               ItemStack stack,
                                               List<BlockPos> chests,
                                               boolean requireSameItem) {
        if (bot == null || stack == null || stack.isEmpty() || chests == null || chests.isEmpty()) {
            return stack;
        }
        for (BlockPos pos : chests) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                debugChest("Chest skip pos=" + pos.toShortString() + " world=null");
                continue;
            }
            if (!world.isChunkLoaded(pos)) {
                debugChest("Chest skip pos=" + pos.toShortString() + " chunkNotLoaded");
                continue;
            }
            var be = world.getBlockEntity(pos);
            if (!(be instanceof net.minecraft.block.entity.ChestBlockEntity chest)) {
                debugChest("Chest skip pos=" + pos.toShortString()
                        + " be=" + (be == null ? "null" : be.getClass().getSimpleName()));
                continue;
            }
            boolean containsItem = chestContainsItem(chest, stack.getItem());
            boolean hasItemSpace = chestHasSpace(chest, stack.getItem());
            boolean hasAnySpace = chestHasAnySpace(chest);
            if (requireSameItem) {
                if (!containsItem || !hasItemSpace) {
                    debugChest("Chest skip pos=" + pos.toShortString()
                            + " requireSameItem=true containsItem=" + containsItem
                            + " hasItemSpace=" + hasItemSpace);
                    continue;
                }
            } else if (!hasAnySpace) {
                debugChest("Chest skip pos=" + pos.toShortString()
                        + " requireSameItem=false hasAnySpace=false");
                continue;
            }
            if (!ensureInteractable(bot, bot.getCommandSource(), pos, STATION_REACH_SQ)) {
                debugChest("Chest not interactable at " + pos.toShortString()
                        + " requireSameItem=" + requireSameItem
                        + " botPos=" + bot.getBlockPos().toShortString());
                continue;
            }
            int before = stack.getCount();
            ItemStack remaining = insertIntoInventory(chest, stack.copy());
            if (remaining.getCount() != before) {
                chest.markDirty();
                debugChest("Inserted into chest at " + pos.toShortString()
                        + " item=" + stack.getItem().getName().getString()
                        + " moved=" + (before - remaining.getCount()));
            } else {
                debugChest("Insert into chest at " + pos.toShortString()
                        + " did not move item=" + stack.getItem().getName().getString()
                        + " requireSameItem=" + requireSameItem);
            }
            stack = remaining;
        }
        return stack;
    }

    private static void debugChest(String message) {
        DebugToggleService.debug(LOGGER, "[ChestDebug] {}", message);
    }

    private static boolean ensureInteractable(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              BlockPos target,
                                              double reachSq) {
        if (bot == null || source == null || target == null) {
            return false;
        }
        if (BlockInteractionService.canInteract(bot, target, reachSq)) {
            return true;
        }
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> standOptions = findStandableOptions(world, target, 2);
        if (standOptions.isEmpty()) {
            standOptions = List.of(target);
        }
        boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);
        for (BlockPos stand : standOptions) {
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    stand,
                    stand,
                    null,
                    null,
                    bot.getHorizontalFacing());
            MovementService.MovementResult res = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
            if (res.success() || bot.getBlockPos().getSquaredDistance(target) <= reachSq) {
                if (BlockInteractionService.canInteract(bot, target, reachSq)) {
                    return true;
                }
            }
        }
        return BlockInteractionService.canInteract(bot, target, reachSq);
    }

    private static boolean chestContainsItem(Inventory chest, net.minecraft.item.Item item) {
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean chestHasSpace(Inventory chest, net.minecraft.item.Item item) {
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.isOf(item) && stack.getCount() < stack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private static boolean chestHasAnySpace(Inventory chest) {
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.getCount() < stack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private static net.minecraft.block.entity.ChestBlockEntity placeChestNearBot(ServerWorld world,
                                                                                 ServerPlayerEntity bot,
                                                                                 double reachSq) {
        if (world == null || bot == null) {
            return null;
        }
        if (findItemInInventory(bot, Items.CHEST) == -1) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : findNearbyStationPlacementOptions(world, origin)) {
            if (!BlockInteractionService.canInteract(bot, pos, reachSq)) {
                continue;
            }
            if (BotActions.placeBlockAt(bot, pos, java.util.List.of(Items.CHEST))
                    && world.getBlockState(pos).isOf(net.minecraft.block.Blocks.CHEST)) {
                var be = world.getBlockEntity(pos);
                if (be instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
                    ChatUtils.sendSystemMessage(bot.getCommandSource(), "Placed a chest to store items.");
                    return chest;
                }
            }
        }
        return null;
    }

    private static int countCobble(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE) || stack.isOf(Items.BLACKSTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void consumeCobble(ServerPlayerEntity bot, int needed) {
        int remaining = needed;
        for (int i = 0; i < bot.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!stack.isOf(Items.COBBLESTONE) && !stack.isOf(Items.COBBLED_DEEPSLATE) && !stack.isOf(Items.BLACKSTONE)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            stack.decrement(take);
            remaining -= take;
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private static int findItemInInventory(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        if (bot == null) return -1;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean ensureSticks(ServerPlayerEntity bot, ServerCommandSource source, int needed) {
        int have = countItem(bot, Items.STICK);
        if (have >= needed) {
            return true;
        }
        int missing = needed - have;
        int crafted = craftSticks(bot, source, source.getPlayer(), missing);
        int after = countItem(bot, Items.STICK);
        LOGGER.info("ensureSticks: needed={} before={} crafted={} after={} planks={}",
                needed, have, crafted, after, countPlanks(bot));
        return after >= needed;
    }

    private static boolean ensurePlanksAvailable(ServerPlayerEntity bot, ServerCommandSource source, int neededPlanks) {
        if (bot == null || source == null) {
            return false;
        }
        int have = countPlanks(bot);
        if (have >= neededPlanks) {
            return true;
        }
        int missing = neededPlanks - have;
        // Pull planks first, then logs (convert to planks).
        withdrawFromNearbyChests(bot, source, s -> s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), missing);
        have = countPlanks(bot);
        if (have >= neededPlanks) {
            return true;
        }
        missing = neededPlanks - have;
        int logsNeeded = (int) Math.ceil(missing / 4.0);
        withdrawFromNearbyChests(bot, source, s -> s.isIn(net.minecraft.registry.tag.ItemTags.LOGS), logsNeeded);
        ensurePlanksFromLogs(bot, neededPlanks);
        return countPlanks(bot) >= neededPlanks;
    }

    private static void ensureCobbleAvailable(ServerPlayerEntity bot, ServerCommandSource source, int neededCobble) {
        if (bot == null || source == null) {
            return;
        }
        int have = countCobble(bot);
        if (have >= neededCobble) {
            return;
        }
        int missing = neededCobble - have;
        withdrawFromNearbyChests(bot, source,
                s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE) || s.isOf(Items.BLACKSTONE),
                missing);
    }

    private static void ensureItemAvailable(ServerPlayerEntity bot, ServerCommandSource source, Item item, int needed) {
        if (bot == null || source == null || item == null) {
            return;
        }
        int have = countItem(bot, item);
        if (have >= needed) {
            return;
        }
        withdrawFromNearbyChests(bot, source, s -> s.isOf(item), needed - have);
    }

    private static Map<Item, Integer> countItemsInInventoryAndNearbyChests(ServerPlayerEntity bot,
                                                                          ServerCommandSource source,
                                                                          List<Item> items) {
        Map<Item, Integer> totals = new HashMap<>();
        if (bot == null || source == null || items == null || items.isEmpty()) {
            return totals;
        }
        for (Item item : items) {
            totals.put(item, countItem(bot, item));
        }
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return totals;
        }
        Set<Item> itemSet = new HashSet<>(items);
        for (BlockPos chestPos : findNearbyChests(world, bot.getBlockPos(), CHEST_SEARCH_RADIUS)) {
            var be = world.getBlockEntity(chestPos);
            if (!(be instanceof ChestBlockEntity chest)) {
                continue;
            }
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.getStack(i);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (!itemSet.contains(item)) continue;
                totals.put(item, totals.getOrDefault(item, 0) + stack.getCount());
            }
        }
        return totals;
    }

    private static int withdrawFromNearbyChests(ServerPlayerEntity bot,
                                               ServerCommandSource source,
                                               Predicate<ItemStack> match,
                                               int desired) {
        if (bot == null || source == null || desired <= 0 || match == null) {
            return 0;
        }
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return 0;
        }
        int moved = 0;
        for (BlockPos chestPos : findNearbyChests(world, bot.getBlockPos(), CHEST_SEARCH_RADIUS)) {
            if (moved >= desired) {
                break;
            }
            var be = world.getBlockEntity(chestPos);
            if (!(be instanceof ChestBlockEntity chest)) {
                continue;
            }
            int available = countMatchingStacks(chest, match, desired - moved);
            if (available <= 0) {
                continue;
            }
            if (!moveNearBlock(bot, source, chestPos, STATION_REACH_SQ)) {
                continue;
            }
            if (!BlockInteractionService.canInteract(bot, chestPos, STATION_REACH_SQ)) {
                continue;
            }
            moved += withdrawFromInventory(chest, bot.getInventory(), match, desired - moved);
        }
        return moved;
    }

    private static List<BlockPos> findNearbyChests(ServerWorld world, BlockPos origin, int radius) {
        List<BlockPos> chests = new ArrayList<>();
        if (world == null || origin == null) {
            return chests;
        }
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            var state = world.getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                chests.add(pos.toImmutable());
            }
        }
        chests.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return chests;
    }

    private static List<BlockPos> findNearbyChests(ServerWorld world, BlockPos origin, int radius, int ySpan) {
        List<BlockPos> chests = new ArrayList<>();
        if (world == null || origin == null) {
            return chests;
        }
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -ySpan, -radius), origin.add(radius, ySpan, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            var state = world.getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                chests.add(pos.toImmutable());
            }
        }
        chests.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return chests;
    }

    private static boolean moveNearBlock(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target, double reachSq) {
        if (bot == null || source == null || target == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(target) <= reachSq) {
            return true;
        }
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos stand = target;
        List<BlockPos> standOptions = findStandableOptions(world, target, 2);
        if (!standOptions.isEmpty()) {
            stand = standOptions.get(0);
        }
        boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult res = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
        return res.success() || bot.getBlockPos().getSquaredDistance(target) <= reachSq;
    }

    private static int withdrawFromInventory(Inventory from,
                                            Inventory to,
                                            Predicate<ItemStack> match,
                                            int amount) {
        int moved = 0;
        for (int i = 0; i < from.size() && moved < amount; i++) {
            ItemStack stack = from.getStack(i);
            if (stack.isEmpty() || !match.test(stack)) {
                continue;
            }
            int toMove = Math.min(stack.getCount(), amount - moved);
            ItemStack split = stack.split(toMove);
            if (split.isEmpty()) {
                continue;
            }
            ItemStack remainder = insertIntoInventory(to, split);
            if (!remainder.isEmpty()) {
                // Put back what didn't fit.
                stack.increment(remainder.getCount());
                from.setStack(i, stack);
                break;
            }
            moved += toMove;
        }
        return moved;
    }

    private static int countMatchingStacks(ChestBlockEntity chest,
                                           Predicate<ItemStack> match,
                                           int limit) {
        if (chest == null || match == null || limit <= 0) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < chest.size() && total < limit; i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isEmpty() || !match.test(stack)) {
                continue;
            }
            total += stack.getCount();
        }
        return total;
    }

    private static ItemStack insertIntoInventory(Inventory inv, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                inv.setStack(i, remaining);
                return ItemStack.EMPTY;
            }
            if (ItemStack.areItemsEqual(slot, remaining) && ItemStack.areEqual(slot, remaining) && slot.getCount() < slot.getMaxCount()) {
                int canAdd = Math.min(slot.getMaxCount() - slot.getCount(), remaining.getCount());
                slot.increment(canAdd);
                remaining.decrement(canAdd);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return remaining;
    }

    private static void ensurePlanksFromLogs(ServerPlayerEntity bot, int neededExtra) {
        int planks = countPlanks(bot);
        if (planks >= neededExtra) {
            return;
        }
        int missing = neededExtra - planks;
        int planksPerLog = 4;
        int logsNeeded = (int) Math.ceil(missing / (double) planksPerLog);

        for (int i = 0; i < bot.getInventory().size() && logsNeeded > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                continue;
            }
            int convert = Math.min(stack.getCount(), logsNeeded);
            Item plankItem = mapLogToPlank(stack.getItem());
            stack.decrement(convert);
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
            int produced = convert * planksPerLog;
            distributeOutput(bot, plankItem, produced);
            logsNeeded -= convert;
        }
    }

    private static List<BlockPos> findStandableOptions(ServerWorld world, BlockPos center, int radius) {
        List<BlockPos> options = new ArrayList<>();
        if (world == null || center == null) {
            return options;
        }
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -1, -radius), center.add(radius, 1, radius))) {
            BlockPos foot = pos.toImmutable();
            BlockPos head = foot.up();
            BlockPos below = foot.down();
            boolean solidBelow = !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
            boolean footClear = world.getBlockState(foot).getCollisionShape(world, foot).isEmpty();
            boolean headClear = world.getBlockState(head).getCollisionShape(world, head).isEmpty();
            if (solidBelow && footClear && headClear) {
                options.add(foot);
            }
        }
        options.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return options;
    }

    private static Item mapLogToPlank(Item logItem) {
        Identifier id = Registries.ITEM.getId(logItem);
        String path = id.getPath();
        String base = path;
        for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
            if (path.endsWith(suffix)) {
                base = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        Identifier plankId = Identifier.of(id.getNamespace(), base + "_planks");
        Item mapped = Registries.ITEM.get(plankId);
        if (mapped == null || mapped == Items.AIR) {
            return Items.OAK_PLANKS;
        }
        return mapped;
    }

    private static boolean isMatchingPlank(Item logItem, Item targetPlank) {
        if (logItem == null || targetPlank == null) {
            return false;
        }
        return mapLogToPlank(logItem).equals(targetPlank);
    }

    private static void recordCraftHistory(ServerPlayerEntity commander, Identifier recipeId) {
        if (commander == null || recipeId == null) {
            return;
        }
        CraftingHistoryService.recordCraft(commander, recipeId);
    }

    private static void recordCraftHistory(ServerPlayerEntity commander, net.minecraft.item.Item output) {
        if (commander == null || output == null) {
            return;
        }
        recordCraftHistory(commander, Registries.ITEM.getId(output));
    }

    /**
     * Lightweight, per-tick style walk toward a static station without teleport; keeps server load low.
     */
    private static boolean tickFollowStation(ServerPlayerEntity bot, BlockPos target, long timeoutMs, double impulse) {
        if (bot == null || target == null) {
            return false;
        }
        LOGGER.info("tickFollowStation start: bot={} target={} dist={}", bot.getName().getString(), target.toShortString(), Math.sqrt(bot.getBlockPos().getSquaredDistance(target)));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= STATION_REACH_SQ) {
                net.shasankp000.GameAI.BotActions.stop(bot);
                return true;
            }
            Vec3d center = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            net.shasankp000.GameAI.BotActions.stop(bot);
            net.shasankp000.Entity.LookController.faceBlock(bot, target);
            net.shasankp000.GameAI.BotActions.sprint(bot, distSq > 9.0);
            if (center.y - bot.getY() > 0.6D) {
                net.shasankp000.GameAI.BotActions.jump(bot);
            } else {
                net.shasankp000.GameAI.BotActions.autoJumpIfNeeded(bot);
            }
            net.shasankp000.GameAI.BotActions.applyMovementInput(bot, center, impulse);
            try {
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean reached = bot.getBlockPos().getSquaredDistance(target) <= STATION_REACH_SQ;
        if (!reached) {
            LOGGER.warn("tickFollowStation failed: bot at {} still {} blocks from {}", bot.getBlockPos().toShortString(), String.format("%.2f", Math.sqrt(bot.getBlockPos().getSquaredDistance(target))), target.toShortString());
        }
        return reached;
    }
}
