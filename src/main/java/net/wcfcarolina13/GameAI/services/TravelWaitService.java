package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Keeps a bot useful while a fast-travel cooldown runs, then travels when it expires.
 *
 * <p>{@link NavigationArtifactService}'s cooldown gate enqueues the refused travel here instead of
 * simply leaving the bot idle. Every server tick the pending request is re-evaluated through the
 * pure-logic {@link TravelWaitPolicy}: hobbies are nudged while waiting, and the original travel is
 * retried the moment the cooldown expires.
 *
 * <p>{@link TravelWaitPolicy.Action#OFFLOAD_EXISTING} is currently treated as WAIT — see
 * {@code onServerTick} — because every {@code ChestStoreService.depositMatchingWalkOnly} caller
 * runs inside a skill/worker thread and there is no clean worker-launch path from a tick handler.
 */
public final class TravelWaitService {

    private static final Logger LOGGER = LoggerFactory.getLogger("travel-wait");

    /** How often the (world-scanning) nearby-chest probe is refreshed, in ticks. */
    private static final long CHEST_PROBE_INTERVAL_TICKS = 200L;
    /** How often a HOBBY decision may be nudged, in ticks. */
    private static final long HOBBY_NUDGE_INTERVAL_TICKS = 600L;
    /** Chest scan geometry (mirrors the offload scans used by skills). */
    private static final int CHEST_SCAN_RADIUS = 24;
    private static final int CHEST_SCAN_YSPAN = 8;
    private static final double CHEST_REMEMBERED_MAX_DIST_SQ = 140.0D * 140.0D;

    private static final Map<UUID, PendingTravel> PENDING = new ConcurrentHashMap<>();

    private TravelWaitService() {
    }

    private static final class PendingTravel {
        final String label;
        /**
         * Retry hook. Takes the server and a <em>freshly re-resolved</em> bot instance: a bot that
         * dies and respawns is a brand-new {@code createFakePlayer}, and the DISCONNECT cancel never
         * fires for bots (FakeClientConnection's disconnect is a no-op), so capturing the live entity
         * in the lambda would retry against a removed corpse.
         */
        final BiConsumer<MinecraftServer, ServerPlayerEntity> retryTravel;
        final long startedTick;
        int retries;
        long lastChestProbeTick = Long.MIN_VALUE;
        boolean chestNearby;
        long lastHobbyNudgeTick = Long.MIN_VALUE;
        TravelWaitPolicy.Action lastAction;

        PendingTravel(String label, BiConsumer<MinecraftServer, ServerPlayerEntity> retryTravel, long startedTick) {
            this.label = label;
            this.retryTravel = retryTravel;
            this.startedTick = startedTick;
        }
    }

    /**
     * Records a travel refused by the cooldown gate. One request per bot; a newer request replaces
     * any prior one. The owner is told once, up front.
     */
    public static boolean enqueue(ServerPlayerEntity bot, long remainingTicks, String label,
                                  BiConsumer<MinecraftServer, ServerPlayerEntity> retryTravel) {
        if (bot == null || retryTravel == null) {
            return false;
        }
        MinecraftServer server = bot.getEntityWorld() instanceof ServerWorld world ? world.getServer() : null;
        if (server == null) {
            // Without a server clock there is no meaningful startedTick or cooldown evaluation.
            LOGGER.debug("travel-wait enqueue skipped: no server for {}", bot.getName().getString());
            return false;
        }
        long now = server.getOverworld().getTime();
        String dest = label == null || label.isBlank() ? "their destination" : label;

        PENDING.put(bot.getUuid(), new PendingTravel(dest, retryTravel, now));
        LOGGER.info("travel-wait enqueued {} remaining={}t dest={}", bot.getName().getString(), remainingTicks, dest);
        return true;
    }

    /** Formats a tick count as "Xm Ys" (or "Ys" under a minute). */
    public static String formatWait(long remainingTicks) {
        int seconds = Math.max(1, (int) (remainingTicks / 20));
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    public static void cancel(UUID botUuid) {
        if (botUuid != null) {
            PENDING.remove(botUuid);
        }
    }

    public static void clear() {
        PENDING.clear();
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || PENDING.isEmpty() || TaskService.isServerStopping()) {
            return;
        }
        long now = server.getOverworld().getTime();

        for (Iterator<Map.Entry<UUID, PendingTravel>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PendingTravel> entry = it.next();
            UUID uuid = entry.getKey();
            PendingTravel pending = entry.getValue();

            // Always re-resolve by UUID: a respawned bot is a new entity instance, and nothing
            // cancels the request on death (bot disconnects are no-ops).
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(uuid);
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                it.remove();
                LOGGER.info("travel-wait dropping request for {} (bot gone or removed); dest={}",
                        uuid, pending.label);
                continue;
            }

            long remaining = NavigationArtifactService.getRemainingCooldownTicks(uuid, now);

            boolean taskActive = TaskService.hasActiveTask(uuid);
            boolean hobbyRunning = taskActive && TaskService.getActiveTaskInfo(uuid)
                    .map(info -> info.origin() == TaskService.Origin.AMBIENT)
                    .orElse(false);

            // Note: the probe only runs while no task is active, so `chestNearby` can be stale for
            // up to CHEST_PROBE_INTERVAL_TICKS (200t) after a hobby ends — it is a hint, not a fact.
            if (remaining > 0 && !taskActive
                    && now - pending.lastChestProbeTick >= CHEST_PROBE_INTERVAL_TICKS) {
                pending.lastChestProbeTick = now;
                pending.chestNearby = probeNearbyChest(bot);
            }

            TravelWaitPolicy.Inputs inputs = new TravelWaitPolicy.Inputs(
                    remaining,
                    BotHomeService.isIdleHobbiesEnabled(bot),
                    hobbyRunning,
                    taskActive,
                    pending.chestNearby,
                    fullness(bot));
            TravelWaitPolicy.Action action = TravelWaitPolicy.decide(inputs);

            if (action != pending.lastAction) {
                pending.lastAction = action;
                LOGGER.info("{}: {}", bot.getName().getString(), TravelWaitPolicy.describe(inputs, action));
                if (action == TravelWaitPolicy.Action.OFFLOAD_EXISTING) {
                    // Logged once per transition — this fires every tick otherwise.
                    LOGGER.info("travel-wait offload deferred (no worker launch path)");
                }
            }

            switch (action) {
                case TRAVEL_NOW -> {
                    it.remove();
                    if (!TravelWaitPolicy.canRetry(pending.retries)) {
                        LOGGER.info("travel-wait giving up on {} after {} retries", bot.getName().getString(),
                                pending.retries);
                        break;
                    }
                    pending.retries++;
                    LOGGER.info("travel-wait cooldown expired for {} (waited {}t); retrying travel to {}",
                            bot.getName().getString(), now - pending.startedTick, pending.label);
                    // The gate may re-enqueue if something re-armed the cooldown; the retry count
                    // carried below caps that loop.
                    int carried = pending.retries;
                    pending.retryTravel.accept(server, bot);
                    PendingTravel requeued = PENDING.get(uuid);
                    if (requeued != null && requeued.retries == 0) {
                        requeued.retries = carried;
                    }
                }
                case HOBBY -> {
                    if (!hobbyRunning && now - pending.lastHobbyNudgeTick >= HOBBY_NUDGE_INTERVAL_TICKS) {
                        pending.lastHobbyNudgeTick = now;
                        BotIdleHobbiesService.requestDecisionNow(bot);
                    }
                }
                case OFFLOAD_EXISTING, WAIT -> {
                    // nothing to do
                }
            }
        }
    }

    private static boolean probeNearbyChest(ServerPlayerEntity bot) {
        try {
            return !ChestStoreService.listDepositChestCandidates(
                    bot.getCommandSource(), bot, null,
                    CHEST_SCAN_RADIUS, CHEST_SCAN_YSPAN, CHEST_REMEMBERED_MAX_DIST_SQ).isEmpty();
        } catch (RuntimeException e) {
            LOGGER.debug("travel-wait chest probe failed: {}", e.toString());
            return false;
        }
    }

    private static float fullness(ServerPlayerEntity bot) {
        int occupied = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (!bot.getInventory().getStack(i).isEmpty()) {
                occupied++;
            }
        }
        return TravelWaitPolicy.fullness(occupied, PlayerInventory.MAIN_SIZE);
    }
}
