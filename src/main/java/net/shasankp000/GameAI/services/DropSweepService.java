package net.shasankp000.GameAI.services;

import net.minecraft.entity.ItemEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.GameAI.DropSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Stage-2 refactor: coordination/state for opportunistic item drop sweeping.
 *
 * <p>This service owns cooldowns, retry tracking, and in-flight sweep state.
 * It intentionally does not know about bot "modes" beyond a caller-provided flag
 * indicating whether the sweep was command-driven (GUARD/PATROL) vs background.</p>
 */
public final class DropSweepService {

    private static final Logger LOGGER = LoggerFactory.getLogger("drop-sweep-service");

    private static final long DROP_SWEEP_COOLDOWN_MS = 4000L;
    private static volatile long lastDropSweepMs = 0L;
    private static final AtomicBoolean dropSweepInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean dropSweepCancelRequested = new AtomicBoolean(false);
    private static volatile UUID dropSweepOwner = null;
    private static volatile String dropSweepCancelReason = null;
    private static final long DROP_RETRY_COOLDOWN_MS = 15_000L;
    private static final ConcurrentHashMap<BlockPos, Long> dropRetryTimestamps = new ConcurrentHashMap<>();
    private static final double RL_MANUAL_NUDGE_DISTANCE_SQ = 4.0D;

    private DropSweepService() {}

    public static boolean isInProgress() {
        return dropSweepInProgress.get();
    }

    public static boolean isInProgressFor(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        if (!dropSweepInProgress.get()) {
            return false;
        }
        UUID owner = dropSweepOwner;
        return owner == null || owner.equals(bot.getUuid());
    }

    public static boolean isCancelRequestedFor(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        if (!dropSweepCancelRequested.get()) {
            return false;
        }
        UUID owner = dropSweepOwner;
        return owner == null || owner.equals(bot.getUuid());
    }

    /**
     * Best-effort cancellation: the sweep loop polls {@link #shouldAbort(ServerPlayerEntity)}.
     * This does not forcibly interrupt movement planning, but it prevents new targets from being pursued.
     */
    public static void requestCancel(ServerPlayerEntity bot, String reason) {
        if (bot == null) {
            return;
        }
        if (!dropSweepInProgress.get()) {
            return;
        }
        UUID owner = dropSweepOwner;
        if (owner != null && !owner.equals(bot.getUuid())) {
            return;
        }
        dropSweepCancelReason = reason;
        dropSweepCancelRequested.set(true);
    }

    public static boolean shouldAbort(ServerPlayerEntity bot) {
        return isCancelRequestedFor(bot);
    }

    public static void reset() {
        dropRetryTimestamps.clear();
        lastDropSweepMs = 0L;
        dropSweepInProgress.set(false);
        dropSweepCancelRequested.set(false);
        dropSweepOwner = null;
        dropSweepCancelReason = null;
    }

    public static void collectNearbyDrops(ServerPlayerEntity bot,
                                          double radius,
                                          boolean trainingMode,
                                          boolean commandDrivenSweep,
                                          BooleanSupplier isExternalOverrideActive,
                                          Consumer<Boolean> setExternalOverrideActive) {
        if (bot == null) {
            return;
        }
        if (dropSweepInProgress.get()) {
            return;
        }
        MinecraftServer srv = bot.getCommandSource().getServer();
        if (srv == null) {
            return;
        }
        World rawWorld = bot.getEntityWorld();
        if (!(rawWorld instanceof ServerWorld world)) {
            return;
        }

        double verticalRange = Math.max(6.0D, radius);
        Box searchBox = bot.getBoundingBox().expand(radius, verticalRange, radius);
        List<ItemEntity> drops = world.getEntitiesByClass(
                ItemEntity.class,
                searchBox,
                drop -> drop.isAlive() && !drop.isRemoved() && drop.squaredDistanceTo(bot) > 1.0D
        );

        long now = System.currentTimeMillis();
        Iterator<ItemEntity> iterator = drops.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next().getBlockPos().toImmutable();
            Long lastAttempt = dropRetryTimestamps.get(pos);
            if (lastAttempt != null) {
                if (now - lastAttempt < DROP_RETRY_COOLDOWN_MS) {
                    iterator.remove();
                    continue;
                }
                dropRetryTimestamps.remove(pos);
            }
        }
        if (drops.isEmpty()) {
            return;
        }

        if (trainingMode) {
            ItemEntity closest = drops.stream()
                    .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                    .orElse(null);
            if (closest == null) {
                return;
            }
            double distanceSq = bot.squaredDistanceTo(closest);
            if (!commandDrivenSweep && distanceSq > RL_MANUAL_NUDGE_DISTANCE_SQ) {
                dropRetryTimestamps.put(closest.getBlockPos().toImmutable(), now);
                return;
            }
            if (!dropSweepInProgress.compareAndSet(false, true)) {
                return;
            }

            dropSweepOwner = bot.getUuid();
            dropSweepCancelRequested.set(false);
            dropSweepCancelReason = null;

            BlockPos dropPos = closest.getBlockPos().toImmutable();
            long startedAt = System.currentTimeMillis();
            dropRetryTimestamps.put(dropPos, startedAt);

            int maxTargets = commandDrivenSweep ? 6 : 2;
            long durationMs = commandDrivenSweep ? 4500L : 3000L;

            CompletableFuture<Void> sweepTask = CompletableFuture.runAsync(() -> {
                        try {
                            ServerCommandSource source = bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS);
                            DropSweeper.sweep(source, radius, verticalRange, maxTargets, durationMs);
                        } catch (Exception sweepError) {
                            LOGGER.warn("Training drop sweep failed near {}: {}", dropPos, sweepError.getMessage());
                        }
                    })
                    .orTimeout(durationMs + 750L, TimeUnit.MILLISECONDS);

            lastDropSweepMs = startedAt;
            sweepTask.whenComplete((ignored, throwable) -> srv.execute(() -> {
                dropSweepInProgress.set(false);
                dropSweepCancelRequested.set(false);
                dropSweepOwner = null;
                dropSweepCancelReason = null;
            }));
            return;
        }

        if (now - lastDropSweepMs < DROP_SWEEP_COOLDOWN_MS) {
            return;
        }
        lastDropSweepMs = now;

        Set<BlockPos> attemptedPositions = new HashSet<>();
        for (ItemEntity drop : drops) {
            attemptedPositions.add(drop.getBlockPos().toImmutable());
        }
        final Set<BlockPos> trackedPositions = Set.copyOf(attemptedPositions);
        final ServerWorld trackedWorld = world;

        dropSweepInProgress.set(true);
        dropSweepOwner = bot.getUuid();
        dropSweepCancelRequested.set(false);
        dropSweepCancelReason = null;
        boolean needOverride = isExternalOverrideActive != null
                && setExternalOverrideActive != null
                && !isExternalOverrideActive.getAsBoolean();
        if (needOverride) {
            setExternalOverrideActive.accept(true);
        }
        final boolean activatedOverride = needOverride;

        CompletableFuture<Void> sweepFuture = CompletableFuture.runAsync(() -> {
            try {
                ServerCommandSource source = bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS);
                DropSweeper.sweep(source, radius, verticalRange, 4, 4000L);
            } catch (Exception sweepError) {
                LOGGER.warn("Drop sweep failed: {}", sweepError.getMessage(), sweepError);
            }
        }).orTimeout(4750, TimeUnit.MILLISECONDS);

        sweepFuture.whenComplete((ignored, throwable) -> srv.execute(() -> {
            long completionTime = System.currentTimeMillis();
            for (BlockPos pos : trackedPositions) {
                Box checkBox = Box.of(Vec3d.ofCenter(pos), 1.5D, 1.5D, 1.5D);
                boolean stillPresent = !trackedWorld.getEntitiesByClass(
                        ItemEntity.class,
                        checkBox,
                        entity -> entity.isAlive() && !entity.isRemoved()
                ).isEmpty();
                if (stillPresent) {
                    dropRetryTimestamps.put(pos, completionTime);
                } else {
                    dropRetryTimestamps.remove(pos);
                }
            }
            dropSweepInProgress.set(false);
            dropSweepCancelRequested.set(false);
            dropSweepOwner = null;
            dropSweepCancelReason = null;
            if (activatedOverride) {
                setExternalOverrideActive.accept(false);
            }
        }));
    }
}

