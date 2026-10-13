package net.wcfcarolina13.PathFinding;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.wcfcarolina13.CommandUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.PlayerUtils.blockDetectionUnit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;


public class ChartPathToBlock {

    /** Failure text returned when the Carpet {@code /player} command that drives the walk is unavailable. */
    public static final String CARPET_MISSING_MESSAGE =
            "Chart path needs the Carpet mod, which is not installed. The bot did not move.";

    /** Upper bound on walk steps (50 ms each, about 10 seconds) so the loop can never spin forever. */
    private static final int MAX_STEPS = 200;

    /** How long one step may wait for the server thread before the walk gives up. */
    private static final long STEP_TIMEOUT_MS = 2_000L;

    /** Returned when a caller runs the walk on the server thread, where sleeping would freeze the game. */
    public static final String SERVER_THREAD_MESSAGE =
            "Chart path must run off the server thread. The bot did not move.";

    /**
     * Walks the bot forward toward {@code targetBlockPos} until it bumps into an opaque block.
     *
     * <p>Must be called from a worker thread: it sleeps between steps so the server can tick, and
     * Carpet applies the forward input during those ticks. Every world/entity touch and every
     * command dispatch runs on the server thread via {@link #callOnServer}.
     */
    public static String chart(ServerPlayerEntity bot, BlockPos targetBlockPos, String blockType) {
        // The walk is driven by Carpet's /player command. Without Carpet the bot never moves, and the
        // loop below only exits when the block ahead is already opaque, so bail out up front.
        if (!CommandUtils.isCarpetLoaded()) {
            return CARPET_MISSING_MESSAGE;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return "Chart path could not find the server. The bot did not move.";
        }
        if (server.isOnThread()) {
            return SERVER_THREAD_MESSAGE;
        }
        ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);
        String botName = bot.getName().getString();
        String timedOut = "Chart path stopped because a step did not finish on the server.";

        // Start micro nav
        for (int step = 0; step < MAX_STEPS; step++) {
            // One server-thread hop per step: face, push forward, look ahead, and on contact stop + identify.
            String result = callOnServer(server, () -> {
                LookController.faceBlock(bot, targetBlockPos);
                CommandUtils.run(botSource, "player " + botName + " move forward");

                // If the bot collides with a block, stop
                Vec3d nextPos = bot.getEntityPos().add(bot.getRotationVec(1.0f).multiply(0.1));
                Vec3i nextPosInt = new Vec3i(
                        MathHelper.floor(nextPos.x),
                        MathHelper.floor(nextPos.y),
                        MathHelper.floor(nextPos.z)
                );
                if (!bot.getEntityWorld().getBlockState(new BlockPos(nextPosInt)).isOpaque()) {
                    return null; // keep walking
                }
                CommandUtils.run(botSource, "player " + botName + " stop");
                BlockPos hitPos = blockDetectionUnit.detectBlocks(bot, blockType);
                if (targetBlockPos.equals(hitPos)) {
                    return "Bot is now in front of the target block! Bot is at " + bot.getBlockPos().getX() + " " + bot.getBlockPos().getY() + " " + bot.getBlockPos().getZ();
                }
                return "Hit obstacle that is not target block! Need to adjust.";
            }, timedOut);
            if (result != null) {
                if (result == timedOut) {
                    stopOnServer(server, botSource, botName);
                }
                return result;
            }

            // Let the server tick so Carpet can apply the forward input.
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopOnServer(server, botSource, botName);
                return "Chart path was interrupted before reaching the target block.";
            }
        }
        stopOnServer(server, botSource, botName);
        return "Chart path gave up after " + MAX_STEPS + " steps without reaching the target block.";
    }

    private static void stopOnServer(MinecraftServer server, ServerCommandSource botSource, String botName) {
        server.execute(() -> CommandUtils.run(botSource, "player " + botName + " stop"));
    }

    /** Runs {@code task} on the server thread and waits for it (same shape as ChestStoreService.callOnServer). */
    private static <T> T callOnServer(MinecraftServer server, Supplier<T> task, T fallback) {
        if (server.isOnThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

}
