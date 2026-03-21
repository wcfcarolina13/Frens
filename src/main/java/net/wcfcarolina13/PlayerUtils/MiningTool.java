package net.wcfcarolina13.PlayerUtils;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
    private static final ScheduledExecutorService MINING_EXECUTOR = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mining-tool-" + MINING_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public static CompletableFuture<String> mineBlock(ServerPlayerEntity bot, BlockPos targetBlockPos) {
        return mineBlock(bot, targetBlockPos, false);
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

        double distSq = bot.squaredDistanceTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY() + 0.5, targetBlockPos.getZ() + 0.5);
        if (distSq > SURVIVAL_REACH_SQ) {
            miningResult.complete("⚠️ Cannot mine: out of reach.");
            return miningResult;
        }

        // Line-of-sight check: bot must be able to see the target block (no mining through walls).
        // Skip for directly adjacent blocks — a block touching the bot cannot have a wall between them.
        BlockPos botBlock = BlockPos.ofFloored(bot.getX(), bot.getY(), bot.getZ());
        boolean adjacent = botBlock.getManhattanDistance(targetBlockPos) <= 1
                        || botBlock.up().getManhattanDistance(targetBlockPos) <= 1;
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
            if (isNeverMineBlock(initialState)) {
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
                    if (isNeverMineBlock(currentState)) {
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
        return false;
    }

    private static boolean switchToTool(ServerPlayerEntity bot, ItemStack tool) {
        if (tool == null || tool.isEmpty()) {
            return false;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (ItemStack.areItemsEqual(stack, tool)) {
                bot.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    private static void switchToHarmlessFallback(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                bot.getInventory().setSelectedSlot(i);
                return;
            }
            if (!isWeaponOnlyItem(stack) && stack.getMiningSpeedMultiplier(Blocks.STONE.getDefaultState()) <= 1.0f) {
                bot.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    private static boolean isWeaponOnlyItem(ItemStack stack) {
        return BotActions.isCombatClassItem(stack);
    }

}
