package net.shasankp000.GameAI.skills.support;

import net.minecraft.block.BlockState;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared falling-block (sand/gravel/etc.) safety helpers.
 *
 * <p>Mining can trigger FallingBlockEntities that temporarily "refill" the work area.
 * This helper mirrors the descent safety behaviour: wait for falling blocks to settle,
 * then re-clear any refilled falling blocks in the provided work volume.</p>
 */
public final class FallingBlockStabilizer {

    public static final long DEFAULT_MAX_WAIT_MS = 5_000L;
    public static final long DEFAULT_POLL_MS = 200L;
    public static final long DEFAULT_MESSAGE_COOLDOWN_MS = 1_500L;

    private static final Map<UUID, Long> LAST_MESSAGE_MS = new ConcurrentHashMap<>();

    private FallingBlockStabilizer() {
    }

    public static boolean stabilizeAndReclear(ServerPlayerEntity player,
                                              @Nullable ServerCommandSource source,
                                              BlockPos focus,
                                              Iterable<BlockPos> workVolume,
                                              BiPredicate<ServerWorld, BlockPos> isPassable,
                                              Predicate<BlockState> ignoreState,
                                              Function<BlockPos, Boolean> mine,
                                              boolean sendChat) {
        return stabilizeAndReclear(
                player,
                source,
                focus,
                workVolume,
                isPassable,
                ignoreState,
                mine,
                DEFAULT_MAX_WAIT_MS,
                DEFAULT_POLL_MS,
                sendChat
        );
    }

    public static boolean stabilizeAndReclear(ServerPlayerEntity player,
                                              @Nullable ServerCommandSource source,
                                              BlockPos focus,
                                              Iterable<BlockPos> workVolume,
                                              BiPredicate<ServerWorld, BlockPos> isPassable,
                                              Predicate<BlockState> ignoreState,
                                              Function<BlockPos, Boolean> mine,
                                              long maxWaitMs,
                                              long pollMs,
                                              boolean sendChat) {
        if (player == null || focus == null || workVolume == null) {
            return true;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return true;
        }

        if (!waitForNearbyFallingBlocksToSettle(player, world, focus, maxWaitMs, pollMs, source, sendChat)) {
            return false;
        }

        // Re-check the work volume for sand/gravel refills and clear them.
        for (BlockPos pos : workVolume) {
            if (pos == null) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (ignoreState != null && ignoreState.test(state)) {
                continue;
            }
            if (isPassable != null && isPassable.test(world, pos)) {
                continue;
            }
            if (isFallingBlock(state)) {
                if (mine == null || !Boolean.TRUE.equals(mine.apply(pos))) {
                    if (sendChat) {
                        sendMessage(player, source,
                                "Sand/gravel refilled the area and I couldn't clear it safely. I'll pause here.");
                    }
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean waitForNearbyFallingBlocksToSettle(ServerPlayerEntity player,
                                                             ServerWorld world,
                                                             BlockPos focus,
                                                             long maxWaitMs,
                                                             long pollMs,
                                                             @Nullable ServerCommandSource source,
                                                             boolean sendChat) {
        if (player == null || world == null || focus == null || maxWaitMs <= 0) {
            return true;
        }

        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] hasFalling = new boolean[]{false};
            runOnServerThread(player, () -> {
                Box box = new Box(
                        focus.getX(), focus.getY(), focus.getZ(),
                        focus.getX() + 1, focus.getY() + 1, focus.getZ() + 1
                ).expand(3.5D, 10.0D, 3.5D);
                hasFalling[0] = !world.getEntitiesByClass(
                        FallingBlockEntity.class,
                        box,
                        entity -> entity != null && entity.isAlive()
                ).isEmpty();
            });

            if (!hasFalling[0]) {
                return true;
            }

            runOnServerThread(player, () -> BotActions.stop(player));
            maybeSendWaitMessage(player, source, sendChat);
            try {
                Thread.sleep(Math.max(25L, pollMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        if (sendChat) {
            sendMessage(player, source,
                    "Falling blocks are still settling here; I'll pause so I don't get buried. Use /bot resume.");
        }
        return false;
    }

    private static boolean isFallingBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.getBlock() instanceof net.minecraft.block.FallingBlock;
    }

    private static void maybeSendWaitMessage(ServerPlayerEntity player,
                                            @Nullable ServerCommandSource source,
                                            boolean sendChat) {
        if (!sendChat || player == null) {
            return;
        }
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE_MS.get(id);
        if (last != null && (now - last) < DEFAULT_MESSAGE_COOLDOWN_MS) {
            return;
        }
        LAST_MESSAGE_MS.put(id, now);
        sendMessage(player, source, "Waiting for sand/gravel to settle...");
    }

    private static void sendMessage(ServerPlayerEntity player,
                                    @Nullable ServerCommandSource source,
                                    String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        ServerCommandSource sink = source != null
                ? source
                : player.getCommandSource();
        ChatUtils.sendChatMessages(sink.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), message);
    }

    private static void runOnServerThread(ServerPlayerEntity player, Runnable action) {
        if (player == null || action == null) {
            return;
        }
        ServerWorld world = player.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            action.run();
            return;
        }
        if (server.isOnThread()) {
            action.run();
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        future.join();
    }
}
