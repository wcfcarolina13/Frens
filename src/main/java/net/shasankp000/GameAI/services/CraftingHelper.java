package net.shasankp000.GameAI.services;

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
import net.shasankp000.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Minimal crafting helper focused on basic block crafts (starting with crafting tables).
 */
public final class CraftingHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("crafting-helper");
    private static final Identifier CRAFTING_TABLE_ID = Identifier.of("minecraft", "crafting_table");
    private static final double STATION_REACH_SQ = 4.5D * 4.5D; // mimic player interact range
    private static final double COMMANDER_LOOK_RANGE = 24.0D;

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
        return switch (item.toLowerCase()) {
            case "crafting_table" -> craftWithPlanks(bot, source, commander, CRAFTING_TABLE_ID, 4, Items.CRAFTING_TABLE, amount);
            case "sticks", "stick" -> craftSticks(bot, source, commander, amount);
            case "axe" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.AXE, materialPreference);
            case "shovel" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.SHOVEL, materialPreference);
            case "pickaxe" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.PICKAXE, materialPreference);
            case "hoe" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.HOE, materialPreference);
            case "sword" -> craftToolMaterialAware(bot, source, commander, amount, ToolKind.SWORD, materialPreference);
            case "shield" -> craftShield(bot, source, commander, amount);
            case "bucket" -> craftSimple(bot, source, commander, amount, Items.BUCKET, Items.IRON_INGOT, 3);
            case "shears" -> craftSimple(bot, source, commander, amount, Items.SHEARS, Items.IRON_INGOT, 2);
            case "furnace" -> craftCobbleBlock(bot, source, commander, amount, Items.FURNACE, 8);
            case "chest" -> craftWithPlanks(bot, source, commander, Identifier.of("minecraft", "chest"), 8, Items.CHEST, amount);
            default -> 0;
        };
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
        if (!hasRecipePermission(commander, source.getServer(), recipeId)) {
            ChatUtils.sendSystemMessage(source, "I don't know how to craft that yet.");
            return 0;
        }
        ensurePlanksFromLogs(bot, planksPerItem * amountRequested);
        int plankCount = countPlanks(bot);
        int maxByPlanks = plankCount / planksPerItem;
        int crafts = Math.min(amountRequested, maxByPlanks);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "I need " + planksPerItem + " planks per craft. Missing " + (planksPerItem - plankCount) + ".");
            return 0;
        }
        if (!consumePlanks(bot, crafts * planksPerItem)) {
            ChatUtils.sendSystemMessage(source, "I couldn't reserve planks for crafting.");
            return 0;
        }
        distributeOutput(bot, output, crafts);
        return crafts;
    }

    private static int craftSticks(ServerPlayerEntity bot, ServerCommandSource source, ServerPlayerEntity commander, int amount) {
        // 2 planks => 4 sticks
        int craftsNeeded = (int) Math.ceil(amount / 4.0);
        ensurePlanksFromLogs(bot, craftsNeeded * 2);
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
        if (!consumePlanks(bot, crafts * 2)) {
            ChatUtils.sendSystemMessage(source, "I couldn't reserve planks for sticks.");
            return 0;
        }
        distributeOutput(bot, Items.STICK, crafts * 4);
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
            int crafts = craftToolWithMaterial(bot, source, kind, amount, mat);
            if (crafts > 0) {
                return crafts;
            }
        }
        ChatUtils.sendSystemMessage(source, "Missing materials for " + kind.name().toLowerCase(Locale.ROOT) + ".");
        return 0;
    }

    private static int craftToolWithMaterial(ServerPlayerEntity bot,
                                             ServerCommandSource source,
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
            ensurePlanksFromLogs(bot, headCount * amount);
        }
        int heads = mat.headItem().equals(Items.OAK_PLANKS) ? countPlanks(bot) : countItem(bot, mat.headItem());

        int maxBySticks = sticks / stickCount;
        int maxByHead = heads / headCount;
        int crafts = Math.min(amount, Math.min(maxBySticks, maxByHead));
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Missing materials for " + mat.name() + " " + kind.name().toLowerCase(Locale.ROOT) + ".");
            return 0;
        }

        consumeItem(bot, Items.STICK, crafts * stickCount);
        if (mat.headItem().equals(Items.OAK_PLANKS)) {
            consumePlanks(bot, crafts * headCount);
        } else {
            consumeItem(bot, mat.headItem(), crafts * headCount);
        }

        net.minecraft.item.Item output = switch (kind) {
            case AXE -> resolveTiered(mat, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE);
            case SHOVEL -> resolveTiered(mat, Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL);
            case PICKAXE -> resolveTiered(mat, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE);
            case HOE -> resolveTiered(mat, Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE);
            case SWORD -> resolveTiered(mat, Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD);
        };

        LOGGER.info("Crafted {} {}(s) using {} (sticks {} each, heads {}x {})",
                crafts,
                kind.name().toLowerCase(Locale.ROOT),
                mat.name(),
                stickCount,
                headCount,
                mat.headItem().getName().getString());
        distributeOutput(bot, output, crafts);
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
        int planks = countPlanks(bot);
        int iron = countItem(bot, Items.IRON_INGOT);
        int crafts = Math.min(amount, Math.min(planks / 6, iron / 1));
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "Shield needs 6 planks and 1 iron ingot each.");
            return 0;
        }
        consumePlanks(bot, crafts * 6);
        consumeItem(bot, Items.IRON_INGOT, crafts);
        distributeOutput(bot, Items.SHIELD, crafts);
        return crafts;
    }

    public static boolean ensureCraftingStation(ServerPlayerEntity bot, ServerCommandSource source) {
        if (bot == null) return false;
        // Check nearby crafting table
        BlockPos botPos = bot.getBlockPos();
        ServerWorld world = source.getWorld();
        BlockPos nearest = null;
        int radius = 16;
        int ySpan = 4;

        // First: use commander look target if it's a crafting table.
        BlockPos commanderLook = null;
        if (source.getPlayer() != null) {
            var hit = source.getPlayer().raycast(COMMANDER_LOOK_RANGE, 0, false);
            if (hit instanceof net.minecraft.util.hit.BlockHitResult bhr) {
                commanderLook = bhr.getBlockPos();
            }
        }
        if (commanderLook != null && world.getBlockState(commanderLook).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
            nearest = commanderLook.toImmutable();
        }

        if (nearest == null) {
            for (BlockPos pos : BlockPos.iterate(botPos.add(-radius, -ySpan, -radius), botPos.add(radius, ySpan, radius))) {
                if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                    nearest = pos.toImmutable();
                    break;
                }
            }
        }
        if (nearest != null) {
            double distSq = botPos.getSquaredDistance(nearest);
            LOGGER.info("Found nearby crafting table at {}", nearest.toShortString());
            List<BlockPos> standables = findStandableOptions(world, nearest, 2);
            LOGGER.info("Standable options near crafting table ({}): {}", nearest.toShortString(), standables.size());
            if (standables.isEmpty()) {
                LOGGER.warn("No standable spot near crafting table at {}", nearest.toShortString());
                return false;
            }
            BlockPos approach = standables.get(0);
            LOGGER.info("Selected approach {} (dist={})", approach.toShortString(), Math.sqrt(approach.getSquaredDistance(nearest)));
            if (distSq > COMMANDER_LOOK_RANGE * COMMANDER_LOOK_RANGE && findItemInInventory(bot, Items.CRAFTING_TABLE) != -1) {
                LOGGER.info("Existing table is far ({}). Will place inventory table instead.", Math.sqrt(distSq));
            } else {
                if (bot.getBlockPos().getSquaredDistance(approach) <= STATION_REACH_SQ) {
                    return true;
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
                    return true;
                }
                MovementService.clearRecentWalkAttempt(bot.getUuid());
                boolean close = MovementService.nudgeTowardUntilClose(bot, approach, STATION_REACH_SQ, 1500L, 0.14, "craft-table-nudge");
                if (!close) {
                    LOGGER.warn("Failed to reach crafting table at {}", nearest.toShortString());
                }
                return close;
            }
        }

        // Try placing from inventory
        int slot = findItemInInventory(bot, Items.CRAFTING_TABLE);
        if (slot != -1) {
            BlockPos placeAt = botPos.offset(bot.getHorizontalFacing());
            BotActions.placeBlockAt(bot, placeAt, java.util.List.of(Items.CRAFTING_TABLE));
            LOGGER.info("Placed crafting table from inventory at {}", placeAt.toShortString());
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
            MovementService.clearRecentWalkAttempt(bot.getUuid());
            boolean close = MovementService.nudgeTowardUntilClose(bot, placeAt, STATION_REACH_SQ, 1500L, 0.14, "craft-place-nudge");
            return close;
        }

        // Try crafting a crafting table from materials
        LOGGER.info("No crafting table found; attempting to craft one.");
        boolean crafted = craftCraftingTable(source, bot, source.getPlayer(), 1);
        if (crafted && findItemInInventory(bot, Items.CRAFTING_TABLE) != -1) {
            BlockPos placeAt = botPos.offset(bot.getHorizontalFacing());
            BotActions.placeBlockAt(bot, placeAt, java.util.List.of(Items.CRAFTING_TABLE));
            LOGGER.info("Crafted and placed crafting table at {}", placeAt.toShortString());
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
            MovementService.clearRecentWalkAttempt(bot.getUuid());
            boolean close = MovementService.nudgeTowardUntilClose(bot, placeAt, STATION_REACH_SQ, 1500L, 0.14, "craft-crafted-table-nudge");
            return close;
        }

        LOGGER.info("No crafting table within {} blocks of {}", radius, botPos.toShortString());
        ChatUtils.sendSystemMessage(source, "I need a crafting table nearby to craft that.");
        return false;
    }

    private static int craftSimple(ServerPlayerEntity bot,
                                   ServerCommandSource source,
                                   ServerPlayerEntity commander,
                                   int amount,
                                   net.minecraft.item.Item output,
                                   net.minecraft.item.Item input,
                                   int per) {
        int have = countItem(bot, input);
        int crafts = Math.min(amount, have / per);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "I need " + per + " " + input.getName().getString() + " per craft.");
            return 0;
        }
        consumeItem(bot, input, crafts * per);
        distributeOutput(bot, output, crafts);
        return crafts;
    }

    private static int craftCobbleBlock(ServerPlayerEntity bot,
                                        ServerCommandSource source,
                                        ServerPlayerEntity commander,
                                        int amount,
                                        net.minecraft.item.Item output,
                                        int per) {
        int cobble = countCobble(bot);
        int crafts = Math.min(amount, cobble / per);
        if (crafts <= 0) {
            ChatUtils.sendSystemMessage(source, "I need " + per + " cobblestone (or similar) per craft.");
            return 0;
        }
        consumeCobble(bot, crafts * per);
        distributeOutput(bot, output, crafts);
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
        ItemStack stack = new ItemStack(output, count);
        boolean added = bot.getInventory().insertStack(stack);
        if (!added && !stack.isEmpty()) {
            bot.dropItem(stack, false, false);
        }
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
