package net.shasankp000.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.GameAI.services.CraftingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal smelting helper: inserts raw items into the first furnace the bot is looking at.
 * Fuel and output handling are up to the commander for now.
 */
public final class SmeltingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("smelting-service");
    private static final double STATION_REACH_SQ = 4.5D * 4.5D;
    private static final double COMMANDER_LOOK_RANGE = 24.0D;

    private SmeltingService() {}

public static boolean startBatchCook(ServerPlayerEntity bot, ServerCommandSource source, String itemFilter, boolean useFuel) {
        if (bot == null || source == null) {
            return false;
        }
        var commander = source.getPlayer();
        StationTarget station = resolveFurnaceTarget(bot, commander, source.getWorld());
        if (station == null) {
            ChatUtils.sendSystemMessage(source, "I need a furnace (or similar) placed nearby.");
            return false;
        }
        BlockPos pos = station.pos();
        BlockPos approachPos = station.approach();
        ServerWorld world = source.getWorld();
        var state = world.getBlockState(pos);
        if (!(state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER))) {
            ChatUtils.sendSystemMessage(source, "Look at a furnace, blast furnace, or smoker.");
            return false;
        }
        LOGGER.info("Smelting target={} approach={} commander={} bot={}", pos.toShortString(), approachPos.toShortString(), commander != null ? commander.getName().getString() : "bot", bot.getName().getString());
        var be = world.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.block.entity.AbstractFurnaceBlockEntity)) {
            ChatUtils.sendSystemMessage(source, "That furnace is unavailable.");
            return false;
        }

        Map<Item, List<ItemStack>> cookables = findCookables(bot, world, state, itemFilter);
        if (cookables.isEmpty()) {
            List<String> available = listCookables(bot, world, state);
            if (available.isEmpty()) {
                ChatUtils.sendSystemMessage(source, "I have nothing cookable.");
            } else if (itemFilter == null || itemFilter.isBlank()) {
                ChatUtils.sendSystemMessage(source, "I can cook: " + String.join(", ", available) + ". Specify one (e.g., /bot cook chicken).");
            } else {
                ChatUtils.sendSystemMessage(source, "No '" + itemFilter + "' to cook. I have: " + String.join(", ", available));
            }
            return false;
        }

        // Run movement asynchronously so we don't block the server tick thread.
        var server = source.getServer();
        var worldKey = world.getRegistryKey();
        UUID botId = bot.getUuid();
        BlockPos furnacePos = pos.toImmutable();
        BlockPos approach = approachPos.toImmutable();
        boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);

        ChatUtils.sendSystemMessage(source, "Heading to the furnace...");

        CompletableFuture.runAsync(() -> {
            try {
                MovementService.MovementPlan plan = new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT,
                        approach,
                        approach,
                        null,
                        null,
                        bot.getHorizontalFacing());
                MovementService.MovementResult moveRes = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
                if (!moveRes.success()) {
                    MovementService.clearRecentWalkAttempt(botId);
                    MovementService.nudgeTowardUntilClose(bot, approach, STATION_REACH_SQ, 2600L, 0.15, "smelt-nudge");
                }
            } catch (Exception e) {
                LOGGER.warn("Smelt movement failed: {}", e.getMessage());
            }

            server.execute(() -> {
                ServerWorld liveWorld = server.getWorld(worldKey);
                ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                if (liveWorld == null || liveBot == null || liveBot.isRemoved()) {
                    return;
                }
                var be2 = liveWorld.getBlockEntity(furnacePos);
                if (!(be2 instanceof net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace)) {
                    ChatUtils.sendSystemMessage(source, "That furnace is unavailable.");
                    return;
                }
                if (!ensureStationInteractable(liveBot, furnacePos, STATION_REACH_SQ)) {
                    ChatUtils.sendSystemMessage(source, "Couldn't get close enough to use the furnace.");
                    return;
                }

                int inserted = 0;
                for (Map.Entry<Item, List<ItemStack>> entry : cookables.entrySet()) {
                    String name = entry.getKey().getName().getString();
                    for (ItemStack stack : entry.getValue()) {
                        if (insertIntoInput(furnace, stack.copy())) {
                            consumeFromInventory(liveBot, stack);
                            inserted++;
                            ChatUtils.sendSystemMessage(source, "Added raw stack: " + name);
                        }
                    }
                }

                if (inserted == 0) {
                    ChatUtils.sendSystemMessage(source, "No space to add raws.");
                    return;
                }
                if (useFuel) {
                    maybeLoadFuel(liveBot, source, furnace, true);
                } else if (hasFuel(liveBot)) {
                    ChatUtils.sendSystemMessage(source, "Fuel available (leaves/planks/logs). Re-run with '/bot cook [item] fuel true' to use it.");
                }
                ChatUtils.sendSystemMessage(source, "Added " + inserted + " raw stacks to the furnace.");
            });
        });
        return true;
    }

    private static boolean ensureStationInteractable(ServerPlayerEntity bot, BlockPos stationPos, double reachSq) {
        if (bot == null || stationPos == null) {
            return false;
        }
        if (BlockInteractionService.canInteract(bot, stationPos, reachSq)) {
            return true;
        }
        BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, stationPos, reachSq);
        if (blockingDoor != null) {
            MovementService.tryOpenDoorAt(bot, blockingDoor);
            return BlockInteractionService.canInteract(bot, stationPos, reachSq);
        }
        return false;
    }

    private record StationTarget(BlockPos pos, BlockPos approach) {}

    private static StationTarget resolveFurnaceTarget(ServerPlayerEntity bot, ServerPlayerEntity commander, ServerWorld world) {
        if (bot == null || world == null) return null;
        BlockPos botPos = bot.getBlockPos();

        // 1) Commander look (extended range)
        if (commander != null) {
            var hit = commander.raycast(COMMANDER_LOOK_RANGE, 1.0F, false);
            if (hit instanceof net.minecraft.util.hit.BlockHitResult bhr) {
                BlockPos p = bhr.getBlockPos();
                if (isFurnaceLike(world, p)) {
                    BlockPos approach = chooseApproach(world, p);
                    if (approach != null) {
                        return new StationTarget(p, approach);
                    }
                }
            }
        }

        // 2) Search nearby placed stations
        BlockPos nearest = findNearestFurnace(world, botPos, 24, 4);
        if (nearest != null) {
            double distSq = botPos.getSquaredDistance(nearest);
            if (distSq <= COMMANDER_LOOK_RANGE * COMMANDER_LOOK_RANGE) {
                BlockPos approach = chooseApproach(world, nearest);
                if (approach != null) {
                    return new StationTarget(nearest, approach);
                }
            } else if (findInventoryFurnace(bot) != null) {
                LOGGER.info("Existing furnace far ({}). Will place inventory furnace instead.", Math.sqrt(distSq));
            }
        }

        // 3) Place from inventory if available
        ItemStack invFurnace = findInventoryFurnace(bot);
        if (invFurnace != null) {
            BlockPos placeAt = botPos.offset(bot.getHorizontalFacing());
            BotActions.placeBlockAt(bot, placeAt, java.util.List.of(invFurnace.getItem()));
            LOGGER.info("Placed furnace-like {} at {}", invFurnace.getItem().getName().getString(), placeAt.toShortString());
            BlockPos approach = chooseApproach(world, placeAt);
            if (approach != null) {
                return new StationTarget(placeAt, approach);
            }
        }

        // 4) Craft furnace if possible, then place
        if (craftFurnace(bot, commander, world)) {
            ItemStack crafted = findInventoryFurnace(bot);
            if (crafted != null) {
                BlockPos placeAt = botPos.offset(bot.getHorizontalFacing());
                BotActions.placeBlockAt(bot, placeAt, java.util.List.of(crafted.getItem()));
                LOGGER.info("Crafted and placed furnace at {}", placeAt.toShortString());
                BlockPos approach = chooseApproach(world, placeAt);
                if (approach != null) {
                    return new StationTarget(placeAt, approach);
                }
            }
        }
        return null;
    }

    private static boolean isFurnaceLike(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER);
    }

    private static BlockPos findNearestFurnace(ServerWorld world, BlockPos origin, int radius, int ySpan) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -ySpan, -radius), origin.add(radius, ySpan, radius))) {
            if (isFurnaceLike(world, pos)) {
                double dist = origin.getSquaredDistance(pos);
                if (dist < best) {
                    best = dist;
                    nearest = pos.toImmutable();
                }
            }
        }
        return nearest;
    }

    private static BlockPos chooseApproach(ServerWorld world, BlockPos station) {
        if (world == null || station == null) return null;
        List<BlockPos> options = findStandableOptions(world, station, 2);
        if (options.isEmpty()) {
            return station.offset(net.minecraft.util.math.Direction.NORTH).up(); // fallback best effort
        }
        return options.get(0);
    }

    private static ItemStack findInventoryFurnace(ServerPlayerEntity bot) {
        if (bot == null) return null;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (s.isOf(Items.FURNACE) || s.isOf(Items.BLAST_FURNACE) || s.isOf(Items.SMOKER)) {
                return s;
            }
        }
        return null;
    }

    private static boolean craftFurnace(ServerPlayerEntity bot, ServerPlayerEntity commander, ServerWorld world) {
        if (bot == null) return false;
        int cobble = countCobble(bot);
        if (cobble < 8) {
            return false;
        }
        // Ensure crafting table nearby/placed
        CraftingHelper.ensureCraftingStation(bot, bot.getCommandSource());
        // Consume and add furnace
        if (!consumeCobble(bot, 8)) {
            return false;
        }
        bot.getInventory().insertStack(new ItemStack(Items.FURNACE, 1));
        LOGGER.info("Crafted furnace from inventory cobble");
        return true;
    }

    private static int countCobble(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE) || stack.isOf(Items.BLACKSTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean consumeCobble(ServerPlayerEntity bot, int count) {
        int remaining = count;
        for (int i = 0; i < bot.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE) || stack.isOf(Items.BLACKSTONE)) {
                int take = Math.min(stack.getCount(), remaining);
                stack.decrement(take);
                remaining -= take;
                if (stack.isEmpty()) {
                    bot.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }
        }
        return remaining == 0;
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

    private static boolean insertIntoInput(net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace, ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemStack input = furnace.getStack(0);
        if (input.isEmpty()) {
            furnace.setStack(0, stack);
            furnace.markDirty();
            return true;
        }
        if (ItemStack.areItemsEqual(input, stack) && ItemStack.areEqual(input, stack) && input.getCount() < input.getMaxCount()) {
            int transfer = Math.min(stack.getCount(), input.getMaxCount() - input.getCount());
            input.increment(transfer);
            stack.decrement(transfer);
            furnace.markDirty();
            return transfer > 0;
        }
        return false;
    }

    private static void consumeFromInventory(ServerPlayerEntity bot, ItemStack template) {
        int remaining = template.getCount();
        for (int i = 0; i < bot.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!ItemStack.areItemsEqual(stack, template)) {
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

    private static Map<Item, List<ItemStack>> findCookables(ServerPlayerEntity bot,
                                                            ServerWorld world,
                                                            net.minecraft.block.BlockState furnaceState,
                                                            String itemFilter) {
        Map<Item, List<ItemStack>> found = new LinkedHashMap<>();
        var recipeType = recipeTypeFor(furnaceState);
        if (recipeType == null) {
            return found;
        }
        String filterNorm = normalizeName(itemFilter);
        String filterSlug = filterNorm.replace(" ", "");
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String stackNameNorm = normalizeName(stack.getName().getString());
            String stackNameSlug = stackNameNorm.replace(" ", "");
            if (!filterNorm.isEmpty()
                    && !stackNameNorm.contains(filterNorm)
                    && !stackNameSlug.contains(filterSlug)) {
                continue;
            }
            if (isCookable(stack, world, recipeType)) {
                found.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(stack.copy());
            }
        }
        return found;
    }

    private static List<String> listCookables(ServerPlayerEntity bot,
                                              ServerWorld world,
                                              net.minecraft.block.BlockState furnaceState) {
        List<String> names = new ArrayList<>();
        var recipeType = recipeTypeFor(furnaceState);
        if (recipeType == null) {
            return names;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (isCookable(stack, world, recipeType)) {
                String n = stack.getName().getString();
                if (names.stream().noneMatch(x -> normalizeName(x).equals(normalizeName(n)))) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        return lower.replaceAll("\\s+", " ");
    }

    private static void maybeLoadFuel(ServerPlayerEntity bot,
                                      ServerCommandSource source,
                                      net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace,
                                      boolean consented) {
        Inventory inv = furnace;
        if (!inv.getStack(1).isEmpty()) {
            return; // fuel already present
        }
        FuelStack fuel = findCheapestFuel(bot);
        if (fuel == null) {
            return;
        }
        if (!consented) {
            return;
        }
        inv.setStack(1, fuel.stack());
        consumeFromInventory(bot, fuel.stack());
        furnace.markDirty();
        ChatUtils.sendSystemMessage(source, "Using fuel: " + fuel.name());
    }

    private record FuelStack(ItemStack stack, String name) {}

    private static boolean hasFuel(ServerPlayerEntity bot) {
        return findCheapestFuel(bot) != null;
    }

    private static FuelStack findCheapestFuel(ServerPlayerEntity bot) {
        ItemStack best = ItemStack.EMPTY;
        String name = "";
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.isIn(net.minecraft.registry.tag.ItemTags.LEAVES)) {
                best = stack.copy();
                best.setCount(Math.min(best.getCount(), best.getMaxCount()));
                name = "leaves";
                break;
            }
        }
        if (best.isEmpty()) {
            for (int i = 0; i < bot.getInventory().size(); i++) {
                ItemStack stack = bot.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
                    best = stack.copy();
                    best.setCount(Math.min(best.getCount(), best.getMaxCount()));
                    name = "planks";
                    break;
                }
            }
        }
        if (best.isEmpty()) {
            for (int i = 0; i < bot.getInventory().size(); i++) {
                ItemStack stack = bot.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                    best = stack.copy();
                    best.setCount(Math.min(best.getCount(), best.getMaxCount()));
                    name = "logs";
                    break;
                }
            }
        }
        if (best.isEmpty()) {
            return null;
        }
        return new FuelStack(best, name);
    }

    private static boolean isCookable(ItemStack stack,
                                      ServerWorld world,
                                      net.minecraft.recipe.RecipeType<? extends AbstractCookingRecipe> recipeType) {
        var input = new SingleStackRecipeInput(stack);
        for (RecipeEntry<?> entry : world.getRecipeManager().values()) {
            if (!entry.value().getType().equals(recipeType)) {
                continue;
            }
            if (entry.value() instanceof AbstractCookingRecipe cooking && cooking.matches(input, world)) {
                return true;
            }
        }
        return false;
    }

    private static net.minecraft.recipe.RecipeType<? extends AbstractCookingRecipe> recipeTypeFor(net.minecraft.block.BlockState state) {
        if (state.isOf(Blocks.BLAST_FURNACE)) {
            return net.minecraft.recipe.RecipeType.BLASTING;
        }
        if (state.isOf(Blocks.SMOKER)) {
            return net.minecraft.recipe.RecipeType.SMOKING;
        }
        if (state.isOf(Blocks.FURNACE)) {
            return net.minecraft.recipe.RecipeType.SMELTING;
        }
        return null;
    }
}
