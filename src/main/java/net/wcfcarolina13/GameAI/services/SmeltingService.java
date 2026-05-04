package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.FuelRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.GameAI.services.CraftingHelper;
import net.wcfcarolina13.GameAI.services.ReturnBaseStuckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal smelting helper: inserts raw items into the first furnace the bot is looking at.
 * Fuel and output handling are up to the commander for now.
 */
public final class SmeltingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("smelting-service");
    private static final double STATION_REACH_SQ = 4.5D * 4.5D;
    private static final int CHEST_SEARCH_RADIUS = 10;
    private static final double COMMANDER_LOOK_RANGE = 24.0D;
    private static final long COOK_BATCH_TIMEOUT_MS = 3L * 60L * 1000L;
    private static final long COOK_TOTAL_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long COOK_POLL_INTERVAL_MS = 2000L;

    private SmeltingService() {}

    public static boolean startBatchCook(ServerPlayerEntity bot, ServerCommandSource source, String itemFilter, String fuelSpec) {
        return startBatchCookInternal(bot, source, itemFilter, fuelSpec, false);
    }

    public static boolean startBatchCookFoodOnly(ServerPlayerEntity bot, ServerCommandSource source, String fuelSpec) {
        return startBatchCookInternal(bot, source, "", fuelSpec, true);
    }

    /**
     * Synchronous multi-batch cooking: cycles through ALL distinct raw food types in inventory,
     * waits for each batch to finish, refuels as needed, and eats cooked food if hungry.
     *
     * <p>Must be called from a worker thread (not the server thread). Furnace mutations are
     * dispatched to the server thread via {@code server.execute()}.
     *
     * @return true if any food was cooked
     */
    public static boolean cookAllFoodSync(ServerPlayerEntity bot,
                                           ServerCommandSource source,
                                           ServerWorld world) {
        if (bot == null || source == null || world == null) {
            return false;
        }
        var server = source.getServer();
        if (server == null || TaskService.isServerStopping()) {
            return false;
        }

        // Resolve furnace (null commander = bot-only, no commander look-at)
        StationTarget station = resolveFurnaceTarget(bot, null, world);
        if (station == null) {
            LOGGER.info("cookAllFoodSync: no furnace available for {}", bot.getName().getString());
            return false;
        }
        BlockPos furnacePos = station.pos().toImmutable();
        BlockPos approachPos = station.approach().toImmutable();
        var worldKey = world.getRegistryKey();
        UUID botId = bot.getUuid();
        boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);

        // Move to furnace synchronously (we are on a worker thread)
        boolean moved = false;
        try {
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    approachPos, approachPos, null, null, bot.getHorizontalFacing());
            MovementService.MovementResult moveRes = MovementService.execute(
                    bot.getCommandSource(), bot, plan, allowTeleport, true);
            moved = moveRes.success() || bot.getBlockPos().getSquaredDistance(approachPos) <= STATION_REACH_SQ;
            if (!moved) {
                MovementService.clearRecentWalkAttempt(botId);
                moved = MovementService.nudgeTowardUntilClose(
                        bot, approachPos, STATION_REACH_SQ, 2600L, 0.15, "cook-sync-nudge");
            }
        } catch (Exception e) {
            LOGGER.warn("cookAllFoodSync: movement failed for {}: {}", bot.getName().getString(), e.getMessage());
        }
        if (!moved) {
            LOGGER.info("cookAllFoodSync: {} couldn't reach furnace at {}", bot.getName().getString(), furnacePos.toShortString());
            return false;
        }

        LOGGER.info("cookAllFoodSync: {} starting cook cycle at furnace {}", bot.getName().getString(), furnacePos.toShortString());
        long totalStartMs = System.currentTimeMillis();
        boolean anyCooked = false;

        // Gather distinct raw food item types that are cookable
        var furnaceState = world.getBlockState(furnacePos);
        Map<Item, List<ItemStack>> allCookables = findCookables(bot, world, furnaceState, "", true);
        if (allCookables.isEmpty()) {
            LOGGER.info("cookAllFoodSync: {} has no cookable food", bot.getName().getString());
            return false;
        }

        List<Item> itemTypes = new ArrayList<>(allCookables.keySet());
        for (Item rawItem : itemTypes) {
            if (Thread.currentThread().isInterrupted() || TaskService.isServerStopping()) {
                break;
            }
            if (System.currentTimeMillis() - totalStartMs > COOK_TOTAL_TIMEOUT_MS) {
                LOGGER.info("cookAllFoodSync: {} total timeout reached", bot.getName().getString());
                break;
            }

            // Re-check this item type still exists in inventory (may have been eaten)
            Map<Item, List<ItemStack>> currentCookables = findCookables(bot, world, furnaceState, "", true);
            List<ItemStack> stacks = currentCookables.get(rawItem);
            if (stacks == null || stacks.isEmpty()) {
                continue;
            }

            LOGGER.info("cookAllFoodSync: {} cooking {} ({}x stacks)",
                    bot.getName().getString(), rawItem.getName().getString(), stacks.size());

            // All furnace mutations happen on the server thread
            CompletableFuture<Boolean> loadResult = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerWorld liveWorld = server.getWorld(worldKey);
                    ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                    if (liveWorld == null || liveBot == null || liveBot.isRemoved()) {
                        loadResult.complete(false);
                        return;
                    }
                    var be = liveWorld.getBlockEntity(furnacePos);
                    if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
                        loadResult.complete(false);
                        return;
                    }
                    if (!ensureStationInteractable(liveBot, furnacePos, STATION_REACH_SQ)) {
                        loadResult.complete(false);
                        return;
                    }

                    // Evacuate output
                    ItemStack output = furnace.getStack(2);
                    if (!output.isEmpty()) {
                        ItemStack remaining = evacuateOutput(liveBot, source, output.copy(), STATION_REACH_SQ);
                        if (remaining != null && remaining.isEmpty()) {
                            furnace.setStack(2, ItemStack.EMPTY);
                        } else if (remaining != null && remaining.getCount() < output.getCount()) {
                            furnace.setStack(2, remaining);
                        }
                    }

                    // Evacuate input if different item type
                    ItemStack existingInput = furnace.getStack(0);
                    if (!existingInput.isEmpty() && !existingInput.isOf(rawItem)) {
                        ItemStack remaining = evacuateInput(liveBot, source, existingInput.copy(), STATION_REACH_SQ);
                        if (remaining != null && remaining.isEmpty()) {
                            furnace.setStack(0, ItemStack.EMPTY);
                        } else if (remaining != null && remaining.getCount() < existingInput.getCount()) {
                            furnace.setStack(0, remaining);
                        }
                    }

                    // Load all stacks of this raw food type
                    // Re-read cookables on server thread for thread safety
                    var liveFurnaceState = liveWorld.getBlockState(furnacePos);
                    Map<Item, List<ItemStack>> liveCookables = findCookables(liveBot, liveWorld, liveFurnaceState, "", true);
                    List<ItemStack> liveStacks = liveCookables.get(rawItem);
                    int inserted = 0;
                    if (liveStacks != null) {
                        for (ItemStack stack : liveStacks) {
                            if (insertIntoInput(furnace, stack.copy())) {
                                consumeFromInventory(liveBot, stack);
                                inserted++;
                            }
                        }
                    }
                    if (inserted == 0) {
                        loadResult.complete(false);
                        return;
                    }

                    // Ensure fuel
                    ensureFuel(liveBot, source, furnace, liveWorld, "auto");
                    loadResult.complete(true);
                } catch (Throwable t) {
                    LOGGER.warn("cookAllFoodSync: load failed: {}", t.getMessage());
                    loadResult.complete(false);
                }
            });

            if (TaskService.isServerStopping()) break;
            boolean loaded;
            try {
                loaded = loadResult.get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                LOGGER.warn("cookAllFoodSync: load future timed out");
                continue;
            } catch (Exception e) {
                LOGGER.warn("cookAllFoodSync: load future failed: {}", e.getMessage());
                continue;
            }
            if (!loaded) {
                continue;
            }

            // Poll until this batch is done
            long batchStartMs = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted() && !TaskService.isServerStopping()) {
                if (System.currentTimeMillis() - batchStartMs > COOK_BATCH_TIMEOUT_MS) {
                    LOGGER.info("cookAllFoodSync: {} batch timeout for {}", bot.getName().getString(), rawItem.getName().getString());
                    break;
                }
                if (System.currentTimeMillis() - totalStartMs > COOK_TOTAL_TIMEOUT_MS) {
                    break;
                }

                try {
                    Thread.sleep(COOK_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // Check furnace state + refuel + eat on server thread
                CompletableFuture<Boolean> pollResult = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        ServerWorld liveWorld = server.getWorld(worldKey);
                        ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                        if (liveWorld == null || liveBot == null || liveBot.isRemoved()) {
                            pollResult.complete(true); // done — bot gone
                            return;
                        }
                        var be = liveWorld.getBlockEntity(furnacePos);
                        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
                            pollResult.complete(true); // done — furnace gone
                            return;
                        }

                        // Eat from output if hungry
                        ItemStack output = furnace.getStack(2);
                        if (!output.isEmpty() && liveBot.getHungerManager().getFoodLevel() < 18) {
                            ItemStack taken = output.copy();
                            liveBot.getInventory().insertStack(taken);
                            int consumed = output.getCount() - taken.getCount();
                            if (consumed > 0) {
                                output.decrement(consumed);
                                if (output.isEmpty()) {
                                    furnace.setStack(2, ItemStack.EMPTY);
                                }
                                furnace.markDirty();
                            }
                            HealingService.autoEat(liveBot);
                        }

                        // Refuel if needed and input still has items
                        ItemStack input = furnace.getStack(0);
                        if (!input.isEmpty() && needsFuel(furnace)) {
                            ensureFuel(liveBot, source, furnace, liveWorld, "auto");
                        }

                        // Check if batch is done (input slot empty)
                        boolean inputEmpty = furnace.getStack(0).isEmpty();
                        pollResult.complete(inputEmpty);
                    } catch (Throwable t) {
                        LOGGER.warn("cookAllFoodSync: poll failed: {}", t.getMessage());
                        pollResult.complete(true);
                    }
                });

                if (TaskService.isServerStopping()) break;
                boolean batchDone;
                try {
                    batchDone = pollResult.get(3, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    LOGGER.warn("cookAllFoodSync: poll future timed out");
                    break;
                } catch (Exception e) {
                    LOGGER.warn("cookAllFoodSync: poll future failed: {}", e.getMessage());
                    break;
                }
                if (batchDone) {
                    anyCooked = true;
                    break;
                }
            }

            // Evacuate final output for this batch
            CompletableFuture<Void> evacuateFuture = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerWorld liveWorld = server.getWorld(worldKey);
                    ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                    if (liveWorld == null || liveBot == null || liveBot.isRemoved()) {
                        evacuateFuture.complete(null);
                        return;
                    }
                    var be = liveWorld.getBlockEntity(furnacePos);
                    if (be instanceof AbstractFurnaceBlockEntity furnace) {
                        ItemStack output = furnace.getStack(2);
                        if (!output.isEmpty()) {
                            ItemStack remaining = evacuateOutput(liveBot, source, output.copy(), STATION_REACH_SQ);
                            if (remaining != null && remaining.isEmpty()) {
                                furnace.setStack(2, ItemStack.EMPTY);
                            } else if (remaining != null && remaining.getCount() < output.getCount()) {
                                furnace.setStack(2, remaining);
                            }
                        }
                        // Eat cooked food if hungry
                        HealingService.autoEat(liveBot);
                    }
                    evacuateFuture.complete(null);
                } catch (Throwable t) {
                    LOGGER.warn("cookAllFoodSync: evacuate failed: {}", t.getMessage());
                    evacuateFuture.complete(null);
                }
            });
            if (!TaskService.isServerStopping()) {
                try {
                    evacuateFuture.get(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
        }

        if (anyCooked) {
            LOGGER.info("cookAllFoodSync: {} finished cooking cycle ({}ms)",
                    bot.getName().getString(), System.currentTimeMillis() - totalStartMs);
        }
        return anyCooked;
    }

    private static boolean startBatchCookInternal(ServerPlayerEntity bot,
                                                  ServerCommandSource source,
                                                  String itemFilter,
                                                  String fuelSpec,
                                                  boolean foodOnly) {
        if (bot == null || source == null) {
            return false;
        }
        if (bot.getInventory().getEmptySlot() == -1) {
            CraftingHelper.offloadCheapItemsToNearbyChest(bot, source, 0, 0, Map.of());
        }
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<String> availableCookables = listCookablesForAnyStation(bot, world, foodOnly);
        if (!hasMatchingCookables(bot, world, itemFilter, foodOnly)) {
            if (availableCookables.isEmpty()) {
                ChatUtils.sendSystemMessage(source, "I have nothing cookable.");
            } else if (itemFilter == null || itemFilter.isBlank()) {
                ChatUtils.sendSystemMessage(source, "I can cook: " + String.join(", ", availableCookables) + ". Specify one (e.g., /bot cook chicken).");
            } else {
                ChatUtils.sendSystemMessage(source, "No '" + itemFilter + "' to cook. I have: " + String.join(", ", availableCookables));
            }
            return false;
        }
        var commander = source.getPlayer();
        StationTarget station = resolveFurnaceTarget(bot, commander, world);
        if (station == null) {
            ChatUtils.sendSystemMessage(source, "I need a furnace (or similar) placed nearby.");
            return false;
        }
        BlockPos pos = station.pos();
        BlockPos approachPos = station.approach();
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
        BotFurnaceRegistryService.registerFurnace(bot, pos, world, "smelt");

        Map<Item, List<ItemStack>> cookables = findCookables(bot, world, state, itemFilter, foodOnly);
        if (cookables.isEmpty()) {
            List<String> available = listCookables(bot, world, state, foodOnly);
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

        java.util.concurrent.atomic.AtomicBoolean moveSucceeded = new java.util.concurrent.atomic.AtomicBoolean(false);
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
                moveSucceeded.set(moveRes.success() || bot.getBlockPos().getSquaredDistance(approach) <= STATION_REACH_SQ);
                if (!moveSucceeded.get()) {
                    MovementService.clearRecentWalkAttempt(botId);
                    boolean close = MovementService.nudgeTowardUntilClose(bot, approach, STATION_REACH_SQ, 2600L, 0.15, "smelt-nudge");
                    moveSucceeded.set(close || bot.getBlockPos().getSquaredDistance(approach) <= STATION_REACH_SQ);
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
                if (!moveSucceeded.get()) {
                    ReturnBaseStuckService.tickAndCheckStuck(liveBot, Vec3d.ofCenter(furnacePos));
                }
                if (!ensureStationInteractable(liveBot, furnacePos, STATION_REACH_SQ)) {
                    ChatUtils.sendSystemMessage(source, "Couldn't get close enough to use the furnace.");
                    return;
                }

                ItemStack output = furnace.getStack(2);
                if (!output.isEmpty()) {
                    ItemStack remaining = evacuateOutput(liveBot, source, output.copy(), STATION_REACH_SQ);
                    if (remaining != null && remaining.isEmpty()) {
                        furnace.setStack(2, ItemStack.EMPTY);
                        ChatUtils.sendSystemMessage(source, "Cleared furnace output.");
                    } else if (remaining != null && remaining.getCount() < output.getCount()) {
                        furnace.setStack(2, remaining);
                        ChatUtils.sendSystemMessage(source, "Cleared some furnace output.");
                    } else {
                        ChatUtils.sendSystemMessage(source, "Inventory full — clear furnace output first.");
                        return;
                    }
                }

                ItemStack input = furnace.getStack(0);
                if (!input.isEmpty()) {
                    boolean autoCook = itemFilter == null || itemFilter.isBlank();
                    boolean filterMatchesInput = autoCook || matchesFilter(input, itemFilter);
                    if (!filterMatchesInput) {
                        Item desired = pickDesiredCookable(cookables);
                        if (desired == null || !input.isOf(desired)) {
                            ItemStack remaining = evacuateInput(liveBot, source, input.copy(), STATION_REACH_SQ);
                            if (remaining != null && remaining.isEmpty()) {
                                furnace.setStack(0, ItemStack.EMPTY);
                                ChatUtils.sendSystemMessage(source, "Cleared existing furnace input.");
                            } else if (remaining != null && remaining.getCount() < input.getCount()) {
                                furnace.setStack(0, remaining);
                                ChatUtils.sendSystemMessage(source, "Cleared some furnace input.");
                            } else {
                                ChatUtils.sendSystemMessage(source, "Inventory full — clear furnace input first.");
                                return;
                            }
                        }
                    } else {
                        int fuelTopped = topUpFuelMatching(liveBot, furnace);
                        if (fuelTopped > 0) {
                            ChatUtils.sendSystemMessage(source, "Topped up fuel with " + furnace.getStack(1).getName().getString() + ".");
                        } else if (ensureFuel(liveBot, source, furnace, liveWorld, fuelSpec)) {
                            ChatUtils.sendSystemMessage(source, "Furnace already loaded; topped up fuel.");
                        } else if (needsFuel(furnace) && hasFuel(liveBot, liveWorld)) {
                            ChatUtils.sendSystemMessage(source, "Furnace already loaded; it needs fuel.");
                        }
                        int topped = topUpInputMatching(liveBot, furnace, liveWorld, state);
                        if (topped > 0) {
                            ChatUtils.sendSystemMessage(source, "Added " + topped + " more " + input.getName().getString() + ".");
                        }
                        return;
                    }
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
                if (ensureFuel(liveBot, source, furnace, liveWorld, fuelSpec)) {
                    ChatUtils.sendSystemMessage(source, "Loaded fuel.");
                } else if (needsFuel(furnace) && hasFuel(liveBot, liveWorld)) {
                    ChatUtils.sendSystemMessage(source, "Fuel available (leaves/planks/logs). Re-run with '/bot cook [item] fuel auto' to use it.");
                }
                ChatUtils.sendSystemMessage(source, "Added " + inserted + " raw stack" + (inserted == 1 ? "" : "s") + " to the furnace.");
            });
        });
        return true;
    }

    public static List<net.minecraft.util.Identifier> listCookableIds(ServerPlayerEntity bot,
                                                                      ServerWorld world,
                                                                      net.minecraft.block.BlockState furnaceState) {
        return listCookableIds(bot, world, furnaceState, false);
    }

    public static List<net.minecraft.util.Identifier> listCookableFoodIds(ServerPlayerEntity bot,
                                                                          ServerWorld world,
                                                                          net.minecraft.block.BlockState furnaceState) {
        return listCookableIds(bot, world, furnaceState, true);
    }

    private static List<net.minecraft.util.Identifier> listCookableIds(ServerPlayerEntity bot,
                                                                       ServerWorld world,
                                                                       net.minecraft.block.BlockState furnaceState,
                                                                       boolean foodOnly) {
        List<net.minecraft.util.Identifier> ids = new ArrayList<>();
        if (bot == null || world == null || furnaceState == null) {
            return ids;
        }
        var recipeType = recipeTypeFor(furnaceState);
        if (recipeType == null) {
            return ids;
        }
        Set<net.minecraft.util.Identifier> unique = new LinkedHashSet<>();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (isFuelItem(stack, world)) {
                continue;
            }
            if (matchesCookablePolicy(stack, world, recipeType, foodOnly)) {
                unique.add(Registries.ITEM.getId(stack.getItem()));
            }
        }
        ids.addAll(unique);
        return ids;
    }

    public static List<Identifier> listFuelIds(ServerPlayerEntity bot, ServerWorld world) {
        List<Identifier> out = new ArrayList<>();
        if (bot == null || world == null) {
            return out;
        }
        Set<Identifier> unique = new LinkedHashSet<>();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (isFuelItem(stack, world)) {
                unique.add(Registries.ITEM.getId(stack.getItem()));
            }
        }
        out.addAll(unique);
        return out;
    }

    public static boolean hasCookableInventoryItems(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        return !listCookableIds(bot, world, Blocks.FURNACE.getDefaultState()).isEmpty();
    }

    public static boolean hasCookableFoodInventoryItems(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        return !listCookableFoodIds(bot, world, Blocks.FURNACE.getDefaultState()).isEmpty();
    }

    public static boolean hasFuelForAutoCook(ServerPlayerEntity bot, ServerWorld world) {
        return bot != null && world != null && hasFuel(bot, world);
    }

    public static boolean hasReadyFoodOutput(ServerPlayerEntity bot, ServerWorld world) {
        return findReadyFoodFurnace(bot, world) != null;
    }

    public static boolean startCollectFinishedFood(ServerPlayerEntity bot, ServerCommandSource source) {
        if (bot == null || source == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos furnacePos = findReadyFoodFurnace(bot, world);
        if (furnacePos == null) {
            return false;
        }
        BlockPos approach = chooseApproach(world, furnacePos);
        if (approach == null) {
            return false;
        }

        var server = source.getServer();
        var worldKey = world.getRegistryKey();
        UUID botId = bot.getUuid();
        BlockPos targetPos = furnacePos.toImmutable();
        BlockPos standPos = approach.toImmutable();
        boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);

        CompletableFuture.runAsync(() -> {
            boolean moveSucceeded = false;
            try {
                MovementService.MovementPlan plan = new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT,
                        standPos,
                        standPos,
                        null,
                        null,
                        bot.getHorizontalFacing());
                MovementService.MovementResult moveRes = MovementService.execute(bot.getCommandSource(), bot, plan, allowTeleport, true);
                moveSucceeded = moveRes.success() || bot.getBlockPos().getSquaredDistance(standPos) <= STATION_REACH_SQ;
            } catch (Exception e) {
                LOGGER.debug("Auto-collect movement failed: {}", e.getMessage());
            }

            final boolean reached = moveSucceeded;
            server.execute(() -> {
                ServerWorld liveWorld = server.getWorld(worldKey);
                ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                if (liveWorld == null || liveBot == null || liveBot.isRemoved()) {
                    return;
                }
                var be = liveWorld.getBlockEntity(targetPos);
                if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
                    BotFurnaceRegistryService.removeSharedFurnace(liveBot, liveWorld, targetPos);
                    return;
                }
                BotFurnaceRegistryService.registerFurnace(liveBot, targetPos, liveWorld, "smelt");
                if (!ensureStationInteractable(liveBot, targetPos, STATION_REACH_SQ)) {
                    return;
                }
                ItemStack output = furnace.getStack(2);
                if (output.isEmpty() || output.getComponents().get(net.minecraft.component.DataComponentTypes.FOOD) == null) {
                    return;
                }
                ItemStack remaining = evacuateOutput(liveBot, source, output.copy(), STATION_REACH_SQ);
                if (remaining.isEmpty()) {
                    furnace.setStack(2, ItemStack.EMPTY);
                } else if (remaining.getCount() < output.getCount()) {
                    furnace.setStack(2, remaining);
                }
                furnace.markDirty();
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

        // 2) Search shared tactical furnaces first (same-owner memory)
        for (BlockPos shared : BotFurnaceRegistryService.listValidSharedFurnacePositions(bot, world, botPos, 64.0D * 64.0D)) {
            BlockPos approach = chooseApproach(world, shared);
            if (approach != null) {
                return new StationTarget(shared, approach);
            }
        }

        // 3) Search nearby placed stations
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

        // 4) Place from inventory if available
        ItemStack invFurnace = findInventoryFurnace(bot);
        if (invFurnace != null) {
            BlockPos placeAt = chooseTacticalPlacementTarget(bot, world);
            boolean placed = BotActions.placeBlockAt(bot, placeAt, java.util.List.of(invFurnace.getItem()));
            if (placed) {
                LOGGER.info("Placed furnace-like {} at {}", invFurnace.getItem().getName().getString(), placeAt.toShortString());
                try { Thread.sleep(150L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                // Re-scan to confirm block entity is ready (avoids race condition)
                BlockPos confirmed = findNearestFurnace(world, botPos, 4, 2);
                if (confirmed != null && isFurnaceLike(world, confirmed)) {
                    BotFurnaceRegistryService.registerFurnace(bot, confirmed, world, "tactical");
                    BlockPos approach3 = chooseApproach(world, confirmed);
                    if (approach3 != null) return new StationTarget(confirmed.toImmutable(), approach3);
                }
            } else {
                LOGGER.info("Failed to place furnace-like {} at {}", invFurnace.getItem().getName().getString(), placeAt.toShortString());
            }
        }

        // 5) Craft furnace if possible, then place
        if (craftFurnace(bot, commander, world)) {
            ItemStack crafted = findInventoryFurnace(bot);
            if (crafted != null) {
                BlockPos placeAt = chooseTacticalPlacementTarget(bot, world);
                boolean placed = BotActions.placeBlockAt(bot, placeAt, java.util.List.of(crafted.getItem()));
                if (placed) {
                    LOGGER.info("Crafted and placed furnace at {}", placeAt.toShortString());
                    try { Thread.sleep(150L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    // Re-scan to confirm block entity is ready (avoids race condition)
                    BlockPos confirmed = findNearestFurnace(world, botPos, 4, 2);
                    if (confirmed != null && isFurnaceLike(world, confirmed)) {
                        BotFurnaceRegistryService.registerFurnace(bot, confirmed, world, "tactical");
                        BlockPos approach4 = chooseApproach(world, confirmed);
                        if (approach4 != null) return new StationTarget(confirmed.toImmutable(), approach4);
                    }
                } else {
                    LOGGER.info("Failed to place crafted furnace at {}", placeAt.toShortString());
                }
            }
        }
        return null;
    }

    private static boolean isFurnaceLike(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER);
    }

    private static BlockPos findReadyFoodFurnace(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BotFurnaceRegistryService.listValidSharedFurnacePositions(bot, world, origin, 64.0D * 64.0D)) {
            var be = world.getBlockEntity(pos);
            if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
                continue;
            }
            ItemStack output = furnace.getStack(2);
            if (output.isEmpty()) {
                continue;
            }
            if (output.getComponents().get(net.minecraft.component.DataComponentTypes.FOOD) == null) {
                continue;
            }
            return pos.toImmutable();
        }

        BlockPos nearest = findNearestFurnace(world, origin, 16, 4);
        if (nearest != null) {
            var be = world.getBlockEntity(nearest);
            if (be instanceof AbstractFurnaceBlockEntity furnace) {
                ItemStack output = furnace.getStack(2);
                if (!output.isEmpty() && output.getComponents().get(net.minecraft.component.DataComponentTypes.FOOD) != null) {
                    BotFurnaceRegistryService.registerFurnace(bot, nearest, world, "smelt");
                    return nearest.toImmutable();
                }
            }
        }
        return null;
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

    private static BlockPos chooseTacticalPlacementTarget(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return BlockPos.ORIGIN;
        }
        BlockPos chestAnchor = findSharedChestAnchor(bot, world, 20.0D * 20.0D);
        if (chestAnchor != null) {
            BlockPos nearChest = findPlacementNearAnchor(bot, world, chestAnchor, 2, true);
            if (nearChest != null) {
                return nearChest;
            }
        }

        Optional<BlockPos> bed = BotHomeService.getLastSleep(bot);
        if (bed.isPresent() && bot.getBlockPos().getSquaredDistance(bed.get()) <= 24.0D * 24.0D) {
            BlockPos nearBed = findPlacementNearAnchor(bot, world, bed.get(), 4, false);
            if (nearBed != null) {
                return nearBed;
            }
        }

        Optional<BlockPos> base = BotHomeService.findNearestBase(bot);
        if (base.isPresent() && bot.getBlockPos().getSquaredDistance(base.get()) <= 28.0D * 28.0D) {
            BlockPos nearBase = findPlacementNearAnchor(bot, world, base.get(), 5, false);
            if (nearBase != null) {
                return nearBase;
            }
        }

        BlockPos local = findPlacementNearAnchor(bot, world, bot.getBlockPos(), 3, false);
        return local != null ? local : bot.getBlockPos().offset(bot.getHorizontalFacing());
    }

    private static BlockPos findSharedChestAnchor(ServerPlayerEntity bot, ServerWorld world, double maxDistanceSq) {
        if (bot == null || world == null) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (BotChestRegistryService.ChestRecord record : BotChestRegistryService.listChestsForOwner(bot, world)) {
            if (record == null || record.destroyed) {
                continue;
            }
            BlockPos pos = record.toBlockPos();
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!(world.getBlockState(pos).isOf(Blocks.CHEST)
                    || world.getBlockState(pos).isOf(Blocks.TRAPPED_CHEST)
                    || world.getBlockState(pos).isOf(Blocks.BARREL))) {
                continue;
            }
            double distSq = origin.getSquaredDistance(pos);
            if (maxDistanceSq > 0.0D && distSq > maxDistanceSq) {
                continue;
            }
            if (distSq < bestSq) {
                bestSq = distSq;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private static BlockPos findPlacementNearAnchor(ServerPlayerEntity bot,
                                                    ServerWorld world,
                                                    BlockPos anchor,
                                                    int radius,
                                                    boolean preferAdjacent) {
        if (bot == null || world == null || anchor == null) {
            return null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                candidates.add(anchor.add(dx, 0, dz).toImmutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> {
            double anchorPenalty = anchor.getSquaredDistance(pos);
            double botPenalty = bot.getBlockPos().getSquaredDistance(pos) * 0.10D;
            return anchorPenalty + botPenalty;
        }));
        if (preferAdjacent) {
            candidates.sort(Comparator.comparingDouble(anchor::getSquaredDistance));
        }
        for (BlockPos pos : candidates) {
            if (!world.isChunkLoaded(pos) || !world.isChunkLoaded(pos.down())) {
                continue;
            }
            if (!BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos).allowed()) {
                continue;
            }
            if (!world.getFluidState(pos).isEmpty() || !world.getFluidState(pos.down()).isEmpty()) {
                continue;
            }
            if (world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(pos).isAir() && !world.getBlockState(pos).isReplaceable() && !world.getBlockState(pos).isOf(Blocks.SNOW)) {
                continue;
            }
            if (chooseApproach(world, pos) == null) {
                continue;
            }
            return pos.toImmutable();
        }
        return null;
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
            var footState = world.getBlockState(foot);
            boolean footClear = footState.getCollisionShape(world, foot).isEmpty()
                    || WalkablePartialBlocks.isStandable(footState, world, foot);
            var headState = world.getBlockState(head);
            boolean headClear = headState.getCollisionShape(world, head).isEmpty()
                    || WalkablePartialBlocks.isPathable(headState, world, head);
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
                                                            String itemFilter,
                                                            boolean foodOnly) {
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
            if (isFuelItem(stack, world)) {
                continue;
            }
            String stackNameNorm = normalizeName(stack.getName().getString());
            String stackNameSlug = stackNameNorm.replace(" ", "");
            if (!filterNorm.isEmpty()
                    && !stackNameNorm.contains(filterNorm)
                    && !stackNameSlug.contains(filterSlug)) {
                continue;
            }
            if (matchesCookablePolicy(stack, world, recipeType, foodOnly)) {
                found.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(stack.copy());
            }
        }
        return found;
    }

    private static List<String> listCookables(ServerPlayerEntity bot,
                                              ServerWorld world,
                                              net.minecraft.block.BlockState furnaceState,
                                              boolean foodOnly) {
        List<String> names = new ArrayList<>();
        var recipeType = recipeTypeFor(furnaceState);
        if (recipeType == null) {
            return names;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (isFuelItem(stack, world)) {
                continue;
            }
            if (matchesCookablePolicy(stack, world, recipeType, foodOnly)) {
                String n = stack.getName().getString();
                if (names.stream().noneMatch(x -> normalizeName(x).equals(normalizeName(n)))) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    private static List<String> listCookablesForAnyStation(ServerPlayerEntity bot,
                                                           ServerWorld world,
                                                           boolean foodOnly) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(listCookables(bot, world, Blocks.FURNACE.getDefaultState(), foodOnly));
        names.addAll(listCookables(bot, world, Blocks.BLAST_FURNACE.getDefaultState(), foodOnly));
        names.addAll(listCookables(bot, world, Blocks.SMOKER.getDefaultState(), foodOnly));
        return new ArrayList<>(names);
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        return lower.replaceAll("\\s+", " ");
    }

    private static boolean isFuelItem(ItemStack stack, ServerWorld world) {
        if (stack == null || world == null || stack.isEmpty()) {
            return false;
        }
        return getFuelTime(world, stack) > 0;
    }

    private static boolean matchesFilter(ItemStack stack, String itemFilter) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (itemFilter == null || itemFilter.isBlank()) {
            return true;
        }
        String filterNorm = normalizeName(itemFilter);
        String filterSlug = filterNorm.replace(" ", "");
        String stackNameNorm = normalizeName(stack.getName().getString());
        String stackNameSlug = stackNameNorm.replace(" ", "");
        return stackNameNorm.contains(filterNorm) || stackNameSlug.contains(filterSlug);
    }

    private static boolean hasMatchingCookables(ServerPlayerEntity bot,
                                                ServerWorld world,
                                                String itemFilter,
                                                boolean foodOnly) {
        if (bot == null || world == null) {
            return false;
        }
        for (net.minecraft.block.BlockState furnaceState : List.of(
                Blocks.FURNACE.getDefaultState(),
                Blocks.BLAST_FURNACE.getDefaultState(),
                Blocks.SMOKER.getDefaultState()
        )) {
            if (!findCookables(bot, world, furnaceState, itemFilter, foodOnly).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void maybeLoadFuel(ServerPlayerEntity bot,
                                      ServerCommandSource source,
                                      net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace,
                                      boolean consented) {
        Inventory inv = furnace;
        if (!inv.getStack(1).isEmpty()) {
            return; // fuel already present
        }
        FuelStack fuel = findCheapestFuel(bot, bot.getEntityWorld() instanceof ServerWorld sw ? sw : null);
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

    private static boolean hasFuel(ServerPlayerEntity bot, ServerWorld world) {
        return findCheapestFuel(bot, world) != null;
    }

    private static FuelStack findCheapestFuel(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }
        ItemStack best = ItemStack.EMPTY;
        int bestTime = Integer.MAX_VALUE;
        String name = "";
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            int fuelCost = getFuelCost(world, stack);
            if (fuelCost == Integer.MAX_VALUE) {
                continue;
            }
            if (fuelCost < bestTime) {
                bestTime = fuelCost;
                best = stack.copy();
                best.setCount(Math.min(best.getCount(), best.getMaxCount()));
                name = stack.getName().getString();
            }
        }
        if (best.isEmpty()) {
            return null;
        }
        return new FuelStack(best, name);
    }

    private static int getFuelCost(ServerWorld world, ItemStack stack) {
        int fuelTime = getFuelTime(world, stack);
        if (fuelTime <= 0) {
            return Integer.MAX_VALUE;
        }
        int cost = fuelTime;
        if (stack.isOf(Items.LEAF_LITTER)) {
            return 0;
        }
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.LEAVES)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS)) {
            cost = Math.max(1, cost - 200);
        }
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS_THAT_BURN)) {
            cost += 50000;
        }
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHARCOAL)
                || stack.isOf(Items.COAL_BLOCK)) {
            cost += 100000;
        }
        return cost;
    }

    private static int topUpFuelMatching(ServerPlayerEntity bot,
                                         net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace) {
        if (bot == null || furnace == null) {
            return 0;
        }
        ItemStack fuel = furnace.getStack(1);
        if (fuel == null || fuel.isEmpty()) {
            return 0;
        }
        int space = fuel.getMaxCount() - fuel.getCount();
        if (space <= 0) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < bot.getInventory().size() && space > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!ItemStack.areItemsEqual(stack, fuel) || !ItemStack.areEqual(stack, fuel)) {
                continue;
            }
            int take = Math.min(stack.getCount(), space);
            stack.decrement(take);
            fuel.increment(take);
            space -= take;
            moved += take;
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
        if (moved > 0) {
            furnace.markDirty();
        }
        return moved;
    }

    private static Map<Item, List<ItemStack>> findCookablesForItem(ServerPlayerEntity bot,
                                                                   ServerWorld world,
                                                                   net.minecraft.block.BlockState furnaceState,
                                                                   Item item) {
        Map<Item, List<ItemStack>> found = new LinkedHashMap<>();
        if (bot == null || world == null || furnaceState == null || item == null) {
            return found;
        }
        var recipeType = recipeTypeFor(furnaceState);
        if (recipeType == null) {
            return found;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!stack.isOf(item)) {
                continue;
            }
            if (isFuelItem(stack, world)) {
                continue;
            }
            if (matchesCookablePolicy(stack, world, recipeType, false)) {
                found.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(stack.copy());
            }
        }
        return found;
    }
    private static int getFuelTime(ServerWorld world, ItemStack stack) {
        if (world == null || stack == null || stack.isEmpty()) {
            return 0;
        }
        FuelRegistry registry = world.getFuelRegistry();
        return registry != null ? registry.getFuelTicks(stack) : 0;
    }

    private static FuelStack findFuelByItem(ServerPlayerEntity bot, Item fuelItem) {
        if (bot == null || fuelItem == null) {
            return null;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isOf(fuelItem)) {
                continue;
            }
            ItemStack copy = stack.copy();
            copy.setCount(Math.min(copy.getCount(), copy.getMaxCount()));
            return new FuelStack(copy, fuelItem.getName().getString());
        }
        return null;
    }

    private static boolean ensureFuel(ServerPlayerEntity bot,
                                      ServerCommandSource source,
                                      net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace,
                                      ServerWorld world,
                                      String fuelSpec) {
        if (bot == null || furnace == null || world == null) {
            return false;
        }
        if (furnace.getStack(1) != null && !furnace.getStack(1).isEmpty()) {
            return false;
        }
        String spec = fuelSpec != null ? fuelSpec.trim().toLowerCase(Locale.ROOT) : "";
        if (spec.isEmpty() || spec.equals("false") || spec.equals("off") || spec.equals("none")) {
            return false;
        }
        FuelStack fuel;
        if (spec.equals("true") || spec.equals("on") || spec.equals("auto") || spec.equals("use")) {
            fuel = findCheapestFuel(bot, world);
        } else {
            Item item = resolveItem(spec);
            fuel = item != null ? findFuelByItem(bot, item) : null;
            if (fuel == null && source != null) {
                ChatUtils.sendSystemMessage(source, "I don't have that fuel item.");
            }
        }
        if (fuel == null) {
            return false;
        }
        Inventory inv = furnace;
        inv.setStack(1, fuel.stack());
        consumeFromInventory(bot, fuel.stack());
        furnace.markDirty();
        return true;
    }

    private static boolean needsFuel(net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace) {
        return furnace != null && furnace.getStack(1).isEmpty();
    }

    private static Item resolveItem(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Identifier id = raw.contains(":") ? Identifier.tryParse(raw) : Identifier.tryParse("minecraft:" + raw);
        if (id == null) {
            return null;
        }
        Item item = Registries.ITEM.get(id);
        return item != null && item != Items.AIR ? item : null;
    }

    private static Item pickDesiredCookable(Map<Item, List<ItemStack>> cookables) {
        if (cookables == null || cookables.isEmpty()) {
            return null;
        }
        return cookables.keySet().iterator().next();
    }

    private static int topUpInput(ServerPlayerEntity bot,
                                  net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace,
                                  Map<Item, List<ItemStack>> cookables) {
        if (bot == null || furnace == null || cookables == null || cookables.isEmpty()) {
            return 0;
        }
        ItemStack input = furnace.getStack(0);
        if (input.isEmpty()) {
            return 0;
        }
        List<ItemStack> stacks = cookables.get(input.getItem());
        if (stacks == null || stacks.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (ItemStack stack : stacks) {
            if (insertIntoInput(furnace, stack.copy())) {
                consumeFromInventory(bot, stack);
                inserted++;
            }
        }
        return inserted;
    }

    private static int topUpInputMatching(ServerPlayerEntity bot,
                                          net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace,
                                          ServerWorld world,
                                          net.minecraft.block.BlockState furnaceState) {
        if (bot == null || furnace == null || world == null || furnaceState == null) {
            return 0;
        }
        ItemStack input = furnace.getStack(0);
        if (input.isEmpty()) {
            return 0;
        }
        int space = input.getMaxCount() - input.getCount();
        if (space <= 0) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < bot.getInventory().size() && space > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!ItemStack.areItemsEqual(stack, input)) {
                continue;
            }
            if (isFuelItem(stack, world)) {
                continue;
            }
            int take = Math.min(stack.getCount(), space);
            stack.decrement(take);
            input.increment(take);
            space -= take;
            moved += take;
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
        if (moved > 0) {
            furnace.markDirty();
        }
        return moved;
    }

    private static ItemStack evacuateOutput(ServerPlayerEntity bot,
                                            ServerCommandSource source,
                                            ItemStack output,
                                            double reachSq) {
        if (bot == null || output == null || output.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = output.copy();
        bot.getInventory().insertStack(remaining);
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return depositToChest(bot, source, remaining, reachSq, true);
    }

    private static ItemStack evacuateInput(ServerPlayerEntity bot,
                                           ServerCommandSource source,
                                           ItemStack input,
                                           double reachSq) {
        if (bot == null || input == null || input.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = input.copy();
        bot.getInventory().insertStack(remaining);
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return depositToChest(bot, source, remaining, reachSq, false);
    }

    private static ItemStack depositToChest(ServerPlayerEntity bot,
                                            ServerCommandSource source,
                                            ItemStack stack,
                                            double reachSq,
                                            boolean output) {
        if (bot == null || stack == null || stack.isEmpty()) {
            return stack;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return stack;
        }
        List<BlockPos> chests = findNearbyChests(world, bot.getBlockPos(), CHEST_SEARCH_RADIUS);
        ItemStack remaining = depositIntoChests(bot, stack.copy(), chests, reachSq, true);
        if (remaining.isEmpty()) {
            String verb = output ? "furnace output" : "furnace input";
            ChatUtils.sendSystemMessage(source, "Stored " + verb + " in nearby chest.");
            return ItemStack.EMPTY;
        }
        remaining = depositIntoChests(bot, remaining, chests, reachSq, false);
        if (remaining.isEmpty()) {
            String verb = output ? "furnace output" : "furnace input";
            ChatUtils.sendSystemMessage(source, "Stored " + verb + " in nearby chest.");
            return ItemStack.EMPTY;
        }
        net.minecraft.block.entity.ChestBlockEntity placed = placeChestNearBot(world, bot, reachSq);
        if (placed == null) {
            return remaining;
        }
        ItemStack afterPlaced = insertIntoInventory(placed, remaining.copy());
        if (afterPlaced.isEmpty()) {
            placed.markDirty();
            String verb = output ? "furnace output" : "furnace input";
            ChatUtils.sendSystemMessage(source, "Stored " + verb + " in nearby chest.");
        }
        return afterPlaced;
    }

    private static ItemStack depositIntoChests(ServerPlayerEntity bot,
                                               ItemStack stack,
                                               List<BlockPos> chests,
                                               double reachSq,
                                               boolean requireSameItem) {
        if (bot == null || stack == null || stack.isEmpty() || chests == null || chests.isEmpty()) {
            return stack;
        }
        for (BlockPos pos : chests) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world) || !world.isChunkLoaded(pos)) {
                continue;
            }
            var be = world.getBlockEntity(pos);
            if (!(be instanceof net.minecraft.block.entity.ChestBlockEntity chest)) {
                continue;
            }
            if (requireSameItem) {
                if (!chestContainsItem(chest, stack.getItem()) || !chestHasSpace(chest, stack.getItem())) {
                    continue;
                }
            } else if (!chestHasAnySpace(chest)) {
                continue;
            }
            if (!ensureInteractable(bot, pos, reachSq)) {
                continue;
            }
            int before = stack.getCount();
            ItemStack remaining = insertIntoInventory(chest, stack.copy());
            if (remaining.getCount() != before) {
                chest.markDirty();
            }
            stack = remaining;
        }
        return stack;
    }

    private static net.minecraft.block.entity.ChestBlockEntity placeChestNearBot(ServerWorld world,
                                                                                 ServerPlayerEntity bot,
                                                                                 double reachSq) {
        if (world == null || bot == null) {
            return null;
        }
        if (findInventoryChest(bot) == null) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : findChestPlacementOptions(world, origin)) {
            if (!BlockInteractionService.canInteract(bot, pos, reachSq)) {
                continue;
            }
            if (BotActions.placeBlockAt(bot, pos, java.util.List.of(Items.CHEST))
                    && world.getBlockState(pos).isOf(Blocks.CHEST)) {
                var be = world.getBlockEntity(pos);
                if (be instanceof ChestBlockEntity chest) {
                    ChatUtils.sendSystemMessage(bot.getCommandSource(), "Placed a chest to store items.");
                    return chest;
                }
            }
        }
        return null;
    }

    private static boolean ensureInteractable(ServerPlayerEntity bot, BlockPos target, double reachSq) {
        if (bot == null || target == null) {
            return false;
        }
        if (BlockInteractionService.canInteract(bot, target, reachSq)) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
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

    private static boolean moveNearBlock(ServerPlayerEntity bot, BlockPos target, double reachSq) {
        if (bot == null || target == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(target) <= reachSq) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
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

    private static ItemStack findInventoryChest(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(Items.CHEST)) {
                return stack;
            }
        }
        return null;
    }

    private static List<BlockPos> findChestPlacementOptions(ServerWorld world, BlockPos origin) {
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
            if (!state.isAir() && !state.isReplaceable() && !state.isOf(Blocks.SNOW)) {
                continue;
            }
            valid.add(pos.toImmutable());
        }
        valid.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return valid;
    }

    private static boolean chestContainsItem(Inventory chest, Item item) {
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean chestHasSpace(Inventory chest, Item item) {
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

    private static boolean matchesCookablePolicy(ItemStack stack,
                                                 ServerWorld world,
                                                 net.minecraft.recipe.RecipeType<? extends AbstractCookingRecipe> recipeType,
                                                 boolean foodOnly) {
        RecipeEntry<? extends AbstractCookingRecipe> recipe = findMatchingCookingRecipe(stack, world, recipeType);
        if (recipe == null) {
            return false;
        }
        if (!foodOnly) {
            return true;
        }
        ItemStack output = recipe.value().craft(new SingleStackRecipeInput(stack), world.getRegistryManager());
        return output.getComponents().get(net.minecraft.component.DataComponentTypes.FOOD) != null;
    }

    @SuppressWarnings("unchecked")
    private static RecipeEntry<? extends AbstractCookingRecipe> findMatchingCookingRecipe(ItemStack stack,
                                                                                          ServerWorld world,
                                                                                          net.minecraft.recipe.RecipeType<? extends AbstractCookingRecipe> recipeType) {
        var input = new SingleStackRecipeInput(stack);
        for (RecipeEntry<?> entry : world.getRecipeManager().values()) {
            if (!entry.value().getType().equals(recipeType)) {
                continue;
            }
            if (entry.value() instanceof AbstractCookingRecipe cooking && cooking.matches(input, world)) {
                return (RecipeEntry<? extends AbstractCookingRecipe>) entry;
            }
        }
        return null;
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
