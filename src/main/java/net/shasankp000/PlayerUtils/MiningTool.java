package net.shasankp000.PlayerUtils;

import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MiningTool {

    private static final long MINING_TICK_MS = 50;
    private static final long FAILSAFE_TIMEOUT_SECONDS = 12;
    public static final Logger LOGGER = LoggerFactory.getLogger("mining-tool");

    public static CompletableFuture<String> mineBlock(ServerPlayerEntity bot, BlockPos targetBlockPos) {
        CompletableFuture<String> miningResult = new CompletableFuture<>();
        MinecraftServer server = bot.getEntityWorld().getServer();

        if (server == null) {
            miningResult.complete("⚠️ Cannot mine: server unavailable.");
            return miningResult;
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "mining-tool");
            thread.setDaemon(true);
            return thread;
        };

        ScheduledExecutorService miningExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        AtomicBoolean shutdown = new AtomicBoolean(false);

        Runnable cleanup = () -> {
            if (shutdown.compareAndSet(false, true)) {
                miningExecutor.shutdownNow();
            }
        };

        AtomicInteger requiredTicksHolder = new AtomicInteger(1);
        AtomicInteger ticksElapsed = new AtomicInteger(0);
        AtomicInteger postThresholdAttempts = new AtomicInteger(0);

        // Initialize targeting and tool selection on the server thread.
        CompletableFuture<Void> init = new CompletableFuture<>();
        server.execute(() -> {
            try {
                LookController.faceBlock(bot, targetBlockPos);
                BlockState blockState = bot.getEntityWorld().getBlockState(targetBlockPos);
                ItemStack bestTool = ToolSelector.selectBestToolForBlock(bot, blockState);
                LOGGER.debug("Preparing to mine {} with tool={} (creative={})",
                        targetBlockPos,
                        bestTool.isEmpty() ? "empty-hand" : bestTool.getItem().toString(),
                        bot.getAbilities().creativeMode);
                switchToTool(bot, bestTool);
                bot.swingHand(Hand.MAIN_HAND);

                float delta = blockState.calcBlockBreakingDelta(bot, bot.getEntityWorld(), targetBlockPos);
                if (delta <= 0.0f) {
                    init.completeExceptionally(new IllegalStateException("block breaking delta <= 0"));
                    return;
                }
                int requiredTicks = Math.max(1, (int) Math.ceil(1.0f / delta));
                requiredTicksHolder.set(requiredTicks);
                LOGGER.debug("Prepared mining of {} (delta={}, approx ticks={})", targetBlockPos, delta, requiredTicks);
                init.complete(null);
            } catch (Throwable t) {
                LOGGER.error("Failed to prepare mining task at {}", targetBlockPos, t);
                init.completeExceptionally(t);
            }
        });

        try {
            init.join();
        } catch (Exception e) {
            miningResult.complete("⚠️ Failed to initialize mining: " + e.getMessage());
            cleanup.run();
            return miningResult;
        }

        ScheduledFuture<?> task = miningExecutor.scheduleAtFixedRate(() -> {
            server.execute(() -> {
                if (miningResult.isDone()) {
                    cleanup.run();
                    return;
                }
                
                // Check if skill was aborted
                if (SkillManager.shouldAbortSkill(bot)) {
                    LOGGER.info("Mining aborted for {} at {}", bot.getName().getString(), targetBlockPos);
                    miningResult.complete("⚠️ Mining aborted.");
                    cleanup.run();
                    return;
                }
                
                try {
                    BlockState currentState = bot.getEntityWorld().getBlockState(targetBlockPos);
                    if (currentState.isAir()) {
                        LOGGER.info("Mining complete at {}", targetBlockPos);
                        miningResult.complete("Mining complete!");
                        cleanup.run();
                        return;
                    }

                    bot.swingHand(Hand.MAIN_HAND);
                    int tick = ticksElapsed.incrementAndGet();
                    if (tick >= requiredTicksHolder.get()) {
                        boolean broke = bot.interactionManager.tryBreakBlock(targetBlockPos);
                        if (!broke) {
                            int attempts = postThresholdAttempts.incrementAndGet();
                            LOGGER.debug("Mining attempt {} for {} did not complete yet", attempts, targetBlockPos);
                            if (attempts > 5) {
                                miningResult.complete("⚠️ Mining halted before completion.");
                                cleanup.run();
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("Error during mining tick at {}", targetBlockPos, t);
                    miningResult.complete("⚠️ Mining failed: " + t.getMessage());
                    cleanup.run();
                }
            });
        }, 0, MINING_TICK_MS, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> timeoutTask = miningExecutor.schedule(() -> {
            if (!miningResult.isDone()) {
                LOGGER.warn("Mining timeout reached for {}", targetBlockPos);
                miningResult.complete("⚠️ Mining attempt timed out.");
            }
        }, FAILSAFE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        miningResult.whenComplete((result, error) -> {
            task.cancel(true);
            timeoutTask.cancel(true);
            cleanup.run();
        });

        return miningResult;
    }

    private static void switchToTool(ServerPlayerEntity bot, ItemStack tool) {
        if (tool == null || tool.isEmpty()) {
            return;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (ItemStack.areItemsEqual(stack, tool)) {
                bot.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

}
