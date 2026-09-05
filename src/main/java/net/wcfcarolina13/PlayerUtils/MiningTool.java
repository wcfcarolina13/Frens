package net.wcfcarolina13.PlayerUtils;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.MappedVillageService;
import net.wcfcarolina13.GameAI.services.ProtectedStructureBlockHelper;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
public class MiningTool {

    private static final long MINING_TICK_MS = 50;
    private static final long FAILSAFE_TIMEOUT_SECONDS = 12;
    private static final double SURVIVAL_REACH_SQ = 4.5 * 4.5;
    public static final Logger LOGGER = LoggerFactory.getLogger("mining-tool");

    private static final AtomicInteger MINING_THREAD_ID = new AtomicInteger(0);
    /**
     * Shared executor to avoid spawning a brand-new thread/executor per mined block (which can cause huge lag spikes
     * and thread churn in heavily modded servers/worlds).
     */
    private static volatile ScheduledExecutorService MINING_EXECUTOR = createMiningExecutor();

    private static ScheduledExecutorService createMiningExecutor() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mining-tool-" + MINING_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Interrupt all in-flight mining operations. Called during server shutdown. */
    public static void shutdownExecutors() {
        MINING_EXECUTOR.shutdownNow();
    }

    /** Re-create the mining executor if it was shut down. Called from {@code SERVER_STARTED}. */
    public static void restartExecutors() {
        if (MINING_EXECUTOR.isShutdown()) {
            MINING_EXECUTOR = createMiningExecutor();
        }
    }

    public static CompletableFuture<String> mineBlock(ServerPlayerEntity bot, BlockPos targetBlockPos) {
        return mineBlock(bot, targetBlockPos, false, true);
    }

    /**
     * Mines a block physically over time.
     *
     * @param preserveSelectedHotbarItem when true, keeps the currently selected item instead of
     *                                   auto-switching to the best tool.
     */
    public static CompletableFuture<String> mineBlock(ServerPlayerEntity bot,
                                                      BlockPos targetBlockPos,
                                                      boolean preserveSelectedHotbarItem) {
        return mineBlock(bot, targetBlockPos, preserveSelectedHotbarItem, true);
    }

    public static CompletableFuture<String> mineBlock(ServerPlayerEntity bot,
                                                      BlockPos targetBlockPos,
                                                      boolean preserveSelectedHotbarItem,
                                                      boolean enforceVillageProtection) {
        CompletableFuture<String> miningResult = new CompletableFuture<>();
        if (bot == null || targetBlockPos == null) {
            miningResult.complete("⚠️ Cannot mine: invalid target.");
            return miningResult;
        }
        MinecraftServer server = bot.getEntityWorld().getServer();
        if (server == null) {
            miningResult.complete("⚠️ Cannot mine: server unavailable.");
            return miningResult;
        }

        if (enforceVillageProtection
                && bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw
                && MappedVillageService.isInsideMappedVillage(sw, targetBlockPos)) {
            miningResult.complete("⚠️ Cannot mine: protected mapped village block.");
            return miningResult;
        }

        double distSq = bot.squaredDistanceTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY() + 0.5, targetBlockPos.getZ() + 0.5);
        if (distSq > SURVIVAL_REACH_SQ) {
            miningResult.complete("⚠️ Cannot mine: out of reach.");
            return miningResult;
        }

        // Line-of-sight check: bot must be able to see the target block (no mining through walls).
        // Skip for directly adjacent blocks — a block touching the bot's full height column
        // (feet, head, above-head) cannot have a wall between them.  The up(2) case covers
        // tunnel ceiling blocks one step ahead (stripmine 1×3 cross-section).
        BlockPos botBlock = BlockPos.ofFloored(bot.getX(), bot.getY(), bot.getZ());
        boolean adjacent = botBlock.getManhattanDistance(targetBlockPos) <= 1
                        || botBlock.up().getManhattanDistance(targetBlockPos) <= 1
                        || botBlock.up(2).getManhattanDistance(targetBlockPos) <= 1;
        if (!adjacent && bot.getEntityWorld() instanceof ServerWorld losWorld) {
            Vec3d eyePos = bot.getEyePos();
            Vec3d blockCenter = Vec3d.ofCenter(targetBlockPos);
            BlockHitResult hit = losWorld.raycast(new RaycastContext(
                    eyePos, blockCenter,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, bot));
            if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(targetBlockPos)) {
                miningResult.complete("⚠️ Cannot mine: no line of sight (blocked by " + hit.getBlockPos().toShortString() + ").");
                return miningResult;
            }
        }

        if (bot.getEntityWorld() instanceof ServerWorld serverWorld) {
            var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, serverWorld, targetBlockPos);
            if (!auth.allowed()) {
                miningResult.complete("⚠️ Cannot mine: protected claim.");
                return miningResult;
            }
        }

        AtomicBoolean canceled = new AtomicBoolean(false);

        AtomicInteger requiredTicksHolder = new AtomicInteger(1);
        AtomicInteger ticksElapsed = new AtomicInteger(0);
        AtomicInteger postThresholdAttempts = new AtomicInteger(0);

        // Initialize targeting and tool selection directly (avoid server-thread join deadlock)
        try {
            BlockState initialState = bot.getEntityWorld().getBlockState(targetBlockPos);
            if (ProtectedStructureBlockHelper.isNeverBreakAt(bot.getEntityWorld(), targetBlockPos)
                    || isNeverMineBlock(initialState)) {
                miningResult.complete("⚠️ Refusing to mine a protected block.");
                return miningResult;
            }
            LookController.faceBlock(bot, targetBlockPos);
            BlockState blockState = initialState;
            if (!preserveSelectedHotbarItem) {
                ItemStack bestTool = ToolSelector.selectBestToolForBlock(bot, blockState);
                LOGGER.debug("Preparing to mine {} with tool={} (creative={})",
                        targetBlockPos,
                        bestTool.isEmpty() ? "empty-hand" : bestTool.getItem().toString(),
                        bot.getAbilities().creativeMode);
                if (!switchToTool(bot, bestTool)) {
                    switchToHarmlessFallback(bot);
                }
            } else {
                ItemStack held = bot.getMainHandStack();
                if (isWeaponOnlyItem(held)) {
                    LOGGER.info("Mining {} refusing to preserve held weapon {}; switching to harmless fallback",
                            targetBlockPos,
                            held.getItem());
                    switchToHarmlessFallback(bot);
                    held = bot.getMainHandStack();
                }
                LOGGER.debug("Preparing to mine {} with preserved held item={} (creative={})",
                        targetBlockPos,
                        held == null || held.isEmpty() ? "empty-hand" : held.getItem().toString(),
                        bot.getAbilities().creativeMode);
            }

            // Harvest-level protection: refuse to mine valuable ores with inadequate tools.
            // Applies to ALL callers (mining skills, break-free, surface recovery, hobbies).
            if (net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector.isHarvestProtectedOre(blockState)) {
                ItemStack heldTool = bot.getMainHandStack();
                if (!net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector.canToolHarvest(heldTool, blockState)) {
                    String oreName = net.minecraft.registry.Registries.BLOCK.getId(blockState.getBlock()).getPath()
                            .replace('_', ' ');
                    String toolName = heldTool.isEmpty() ? "bare hands"
                            : net.minecraft.registry.Registries.ITEM.getId(heldTool.getItem()).getPath()
                                    .replace('_', ' ');
                    LOGGER.warn("Refusing to mine {} at {} with {} — inadequate tool",
                            oreName, targetBlockPos.toShortString(), toolName);
                    miningResult.complete("⚠️ Cannot mine " + oreName
                            + ": " + toolName + " cannot harvest it. Need a better tool.");
                    return miningResult;
                }
            }

            bot.swingHand(Hand.MAIN_HAND);

            float delta = blockState.calcBlockBreakingDelta(bot, bot.getEntityWorld(), targetBlockPos);
            if (delta <= 0.0f) {
                miningResult.complete("⚠️ Failed to initialize mining: block breaking delta <= 0");
                return miningResult;
            }
            int requiredTicks = Math.max(1, (int) Math.ceil(1.0f / delta));
            requiredTicksHolder.set(requiredTicks);
            String toolName = bot.getMainHandStack().isEmpty() ? "bare-hands" : bot.getMainHandStack().getItem().toString();
            LOGGER.info("Mining {} with {} (delta={}, ticks={}, ~{}ms, creative={})",
                    targetBlockPos, toolName,
                    String.format("%.4f", delta), requiredTicks, requiredTicks * MINING_TICK_MS,
                    bot.getAbilities().creativeMode);
        } catch (Throwable t) {
            LOGGER.error("Failed to prepare mining task at {}", targetBlockPos, t);
            miningResult.complete("⚠️ Failed to initialize mining: " + t.getMessage());
            return miningResult;
        }

        ScheduledFuture<?> task = MINING_EXECUTOR.scheduleAtFixedRate(() -> {
            if (canceled.get()) {
                return;
            }
            server.execute(() -> {
                if (miningResult.isDone()) {
                    return;
                }
                
                // Check if skill was aborted
                if (SkillManager.shouldAbortSkill(bot)) {
                    if (bot.getEntityWorld() instanceof ServerWorld sw)
                        sw.setBlockBreakingInfo(bot.getId(), targetBlockPos, -1);
                    LOGGER.info("Mining aborted for {} at {}", bot.getName().getString(), targetBlockPos);
                    miningResult.complete("⚠️ Mining aborted.");
                    return;
                }

                double tickDistSq = bot.squaredDistanceTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY() + 0.5, targetBlockPos.getZ() + 0.5);
                if (tickDistSq > SURVIVAL_REACH_SQ) {
                    if (bot.getEntityWorld() instanceof ServerWorld sw)
                        sw.setBlockBreakingInfo(bot.getId(), targetBlockPos, -1);
                    miningResult.complete("⚠️ Cannot mine: out of reach.");
                    return;
                }

                if (bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                    var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, serverWorld, targetBlockPos);
                    if (!auth.allowed()) {
                        miningResult.complete("⚠️ Cannot mine: protected claim.");
                        return;
                    }
                }
                
                try {
                    BlockState currentState = bot.getEntityWorld().getBlockState(targetBlockPos);
                    if (ProtectedStructureBlockHelper.isNeverBreakAt(bot.getEntityWorld(), targetBlockPos)
                            || isNeverMineBlock(currentState)) {
                        miningResult.complete("⚠️ Refusing to mine a protected block.");
                        return;
                    }
                    if (currentState.isAir()) {
                        // Clear break animation
                        if (bot.getEntityWorld() instanceof ServerWorld sw) {
                            sw.setBlockBreakingInfo(bot.getId(), targetBlockPos, -1);
                        }
                        LOGGER.info("Mining complete at {}", targetBlockPos);
                        miningResult.complete("Mining complete!");
                        return;
                    }

                    // Periodically re-poll for a better tool in case one was added to inventory mid-task
                    if (!preserveSelectedHotbarItem && ticksElapsed.get() > 0 && ticksElapsed.get() % 20 == 0) {
                        ItemStack current = bot.getMainHandStack();
                        ItemStack repolled = ToolSelector.selectBestToolForBlock(bot, currentState);
                        if (!ItemStack.areItemsAndComponentsEqual(current, repolled)) {
                            LOGGER.info("Mining re-poll: swapping from {} to {} for {}",
                                    current.isEmpty() ? "empty-hand" : current.getItem().toString(),
                                    repolled.isEmpty() ? "empty-hand" : repolled.getItem().toString(),
                                    targetBlockPos);
                            boolean swapped = switchToTool(bot, repolled);
                            if (!swapped) {
                                switchToHarmlessFallback(bot);
                            }
                            // Recalculate required ticks from the new tool
                            float newDelta = currentState.calcBlockBreakingDelta(bot, bot.getEntityWorld(), targetBlockPos);
                            if (newDelta > 0.0f) {
                                int newRequired = Math.max(1, (int) Math.ceil(1.0f / newDelta));
                                requiredTicksHolder.set(newRequired);
                                ticksElapsed.set(0);
                                postThresholdAttempts.set(0);
                                LOGGER.info("Mining re-poll: recalculated required ticks to {} (delta {}) for {}",
                                        newRequired, String.format("%.4f", newDelta), targetBlockPos);
                            }
                        }
                    }

                    // Re-face the target block every mining tick to maintain facing
                    LookController.faceBlock(bot, targetBlockPos);

                    bot.swingHand(Hand.MAIN_HAND);
                    int tick = ticksElapsed.incrementAndGet();
                    int required = requiredTicksHolder.get();

                    // Send progressive break animation (0-9 stages)
                    if (required > 1 && bot.getEntityWorld() instanceof ServerWorld sw) {
                        int stage = Math.min(9, (int) ((float) tick / required * 10));
                        sw.setBlockBreakingInfo(bot.getId(), targetBlockPos, stage);
                    }

                    if (tick >= required) {
                        // Clear break animation before breaking
                        if (bot.getEntityWorld() instanceof ServerWorld sw) {
                            sw.setBlockBreakingInfo(bot.getId(), targetBlockPos, -1);
                        }
                        boolean broke = bot.interactionManager.tryBreakBlock(targetBlockPos);
                        if (!broke) {
                            int attempts = postThresholdAttempts.incrementAndGet();
                            LOGGER.debug("Mining attempt {} for {} did not complete yet", attempts, targetBlockPos);
                            if (attempts > 5) {
                                miningResult.complete("⚠️ Mining halted before completion.");
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("Error during mining tick at {}", targetBlockPos, t);
                    miningResult.complete("⚠️ Mining failed: " + t.getMessage());
                }
            });
        }, 0, MINING_TICK_MS, TimeUnit.MILLISECONDS);

        // Timeout: actual mining time + 5 second buffer (was fixed 12s, killed slow blocks)
        long timeoutMs = Math.max(FAILSAFE_TIMEOUT_SECONDS * 1000,
                requiredTicksHolder.get() * MINING_TICK_MS + 5000);
        ScheduledFuture<?> timeoutTask = MINING_EXECUTOR.schedule(() -> {
            if (!miningResult.isDone()) {
                LOGGER.warn("Mining timeout reached for {} (after {}ms)", targetBlockPos, timeoutMs);
                miningResult.complete("⚠️ Mining attempt timed out.");
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        miningResult.whenComplete((result, error) -> {
            task.cancel(true);
            timeoutTask.cancel(true);
            canceled.set(true);
            // Clear any lingering break animation
            server.execute(() -> {
                if (bot.getEntityWorld() instanceof ServerWorld sw) {
                    sw.setBlockBreakingInfo(bot.getId(), targetBlockPos, -1);
                }
            });
        });

        return miningResult;
    }

    private static boolean isNeverMineBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        // Never destroy player storage or beds; prefer nudging away / alternate routing.
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL) || state.isOf(Blocks.ENDER_CHEST)) {
            return true;
        }
        if (state.isIn(BlockTags.BEDS) || state.isIn(BlockTags.SHULKER_BOXES)) {
            return true;
        }
        if (ProtectedStructureBlockHelper.isProtectedGlassLike(state)) {
            return true;
        }
        return false;
    }

    private static boolean switchToTool(ServerPlayerEntity bot, ItemStack tool) {
        return switchToTool(bot, tool, true);
    }

    /**
     * Selects {@code tool} into the main hand.
     *
     * <p>Bundle-aware: if the tool exists only inside a bundle, {@code allowBundleReach} lets us pull
     * one copy into a direct slot ({@link net.wcfcarolina13.GameAI.services.BundleService#reachFirst})
     * and rescan exactly once. Mining runs on {@code MINING_EXECUTOR} worker threads as well as on the
     * server thread (the re-poll path); {@code reachFirst} handles the hop either way.
     */
    private static boolean switchToTool(ServerPlayerEntity bot, ItemStack tool, boolean allowBundleReach) {
        if (tool == null || tool.isEmpty()) {
            return false;
        }

        // ── SCAN (read-only; safe on MINING_EXECUTOR worker threads) ──────
        PlayerInventory inventory = bot.getInventory();
        int foundSlot = -1;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (ItemStack.areItemsAndComponentsEqual(inventory.getStack(i), tool)) {
                foundSlot = i;
                break;
            }
        }

        // ── MUTATION (one atomic server-thread unit, re-validated) ────────
        if (foundSlot != -1) {
            final int slot = foundSlot;
            final ItemStack expected = tool;
            Boolean ok = callOnServer(bot, () -> {
                PlayerInventory inv = bot.getInventory();
                if (!ToolSelector.slotStillMatches(expected, inv.getStack(slot))) {
                    LOGGER.debug("Tool switch: slot {} no longer holds {} — aborting swap for {}",
                            slot, expected.getItem(), bot.getName().getString());
                    return Boolean.FALSE;
                }
                if (slot < 9) {
                    BotActions.selectHotbarSlot(bot, slot);
                    return Boolean.TRUE;
                }
                int target = findEmptyHotbarSlot(inv);
                if (target == -1) {
                    target = inv.getSelectedSlot();
                }
                swapStacks(inv, slot, target);
                BotActions.selectHotbarSlot(bot, target);
                return Boolean.TRUE;
            }, Boolean.FALSE);
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
        }

        if (allowBundleReach) {
            ItemStack wanted = tool;
            java.util.function.Predicate<ItemStack> match =
                    stack -> ItemStack.areItemsAndComponentsEqual(stack, wanted);
            // Only worth a bundle hop when nothing matching sits in a direct slot but a bundle holds one.
            // Extraction and the follow-up equip must be the same server-thread unit.
            Boolean reached = callOnServer(bot, () -> {
                if (InventoryIterator.countDirect(bot, match) != 0
                        || InventoryIterator.count(bot, match) == 0
                        || !net.wcfcarolina13.GameAI.services.BundleService.reachFirst(bot, match)) {
                    return Boolean.FALSE;
                }
                LOGGER.info("Tool switch: reached {} out of a bundle for {}",
                        wanted.getItem(), bot.getName().getString());
                return switchToTool(bot, wanted, false);
            }, Boolean.FALSE);
            return Boolean.TRUE.equals(reached);
        }
        return false;
    }

    /**
     * Runs {@code task} on the server thread (fast path when already there) and blocks for the result.
     * Mirrors {@code ToolProvisionService.callOnServer}; inventory mutations are server-thread only.
     */
    private static <T> T callOnServer(ServerPlayerEntity bot, java.util.function.Supplier<T> task, T fallback) {
        var server = bot == null ? null : bot.getEntityWorld().getServer();
        if (server == null || task == null) {
            return fallback;
        }
        if (server.isOnThread()) {
            try {
                return task.get();
            } catch (Throwable t) {
                LOGGER.debug("Tool switch: on-thread step failed: {}", t.toString());
                return fallback;
            }
        }
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.complete(fallback);
            }
        });
        try {
            T result = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            return result == null ? fallback : result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            LOGGER.debug("Tool switch: server-thread hop failed: {}", e.toString());
            return fallback;
        }
    }

    private static void switchToHarmlessFallback(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        // Scans and mutates inventory in one pass — must be a single server-thread unit.
        callOnServer(bot, () -> {
            switchToHarmlessFallbackOnThread(bot);
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    private static void switchToHarmlessFallbackOnThread(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
        for (int i = 9; i < PlayerInventory.MAIN_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                int selectedSlot = inventory.getSelectedSlot();
                swapStacks(inventory, i, selectedSlot);
                BotActions.selectHotbarSlot(bot, selectedSlot);
                return;
            }
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isHarmlessMiningFallback(stack)) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
        for (int i = 9; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (isHarmlessMiningFallback(stack)) {
                int selectedSlot = inventory.getSelectedSlot();
                swapStacks(inventory, i, selectedSlot);
                BotActions.selectHotbarSlot(bot, selectedSlot);
                return;
            }
        }
    }

    private static boolean isWeaponOnlyItem(ItemStack stack) {
        return BotActions.isCombatClassItem(stack);
    }

    private static boolean isHarmlessMiningFallback(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && !isWeaponOnlyItem(stack)
                && stack.get(DataComponentTypes.FOOD) == null
                && stack.getMiningSpeedMultiplier(Blocks.STONE.getDefaultState()) <= 1.0f;
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static void swapStacks(PlayerInventory inventory, int from, int to) {
        if (inventory == null || from == to) {
            return;
        }
        ItemStack fromStack = inventory.getStack(from);
        ItemStack toStack = inventory.getStack(to);
        inventory.setStack(from, toStack);
        inventory.setStack(to, fromStack);
        inventory.markDirty();
    }

}
