package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodic safety net for idle bots stuck underground.
 *
 * <p>When idle hobbies and auto-hunt are both disabled (their defaults), no service
 * evaluates surface recovery for an idle underground bot.  This service fills that
 * gap by calling {@link BotFleeService#ensureAtSurface} on a worker thread every
 * 10 seconds for each qualifying bot.  The full linger decision tree inside
 * {@code shouldSuppressSurfaceRecovery()} still controls whether the bot actually
 * surfaces.</p>
 */
public final class BotUndergroundSurvivalService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-underground-survival");

    /** How often to evaluate each bot (200 ticks = 10 seconds). */
    private static final long CHECK_INTERVAL_TICKS = 200L;

    /** Per-bot throttle: next tick at which a check is allowed. */
    private static final Map<UUID, Long> NEXT_CHECK_TICK = new ConcurrentHashMap<>();

    /** Owner-facing line, sent once per stuck episode when the first backoff starts. */
    static final String STUCK_NOTIFY_LINE = "I'm stuck in a hole with nothing to build with.";

    /** Per-bot stuck-escape episodes (see {@link StuckEscapeBackoffPolicy}); absent = no episode. */
    private static final Map<UUID, StuckEscapeBackoffPolicy.State> STUCK_STATE = new ConcurrentHashMap<>();

    /**
     * Bots holding still after {@code /bot stop}: the abort latch is set and nothing else is running.
     * Only used to log the hold once; removed when the latch clears.
     */
    private static final Set<UUID> HOLDING_AFTER_STOP = ConcurrentHashMap.newKeySet();

    /**
     * Set when {@link #recordRecoveryAttempt} runs on this thread, so the survival-level feed in
     * {@link #onServerTick} does not record the same attempt twice (one attempt = one record).
     */
    private static final ThreadLocal<Boolean> ATTEMPT_RECORDED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final AtomicInteger THREAD_ID = new AtomicInteger(0);
    private static volatile ExecutorService EXECUTOR = createExecutor();

    private static ExecutorService createExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "underground-survival-" + THREAD_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private BotUndergroundSurvivalService() {}

    public static void shutdownExecutors() {
        EXECUTOR.shutdownNow();
        // Both are keyed to this server's tick count; a same-UUID bot in the next save must not
        // inherit a cooldown or throttle from it.
        STUCK_STATE.clear();
        NEXT_CHECK_TICK.clear();
        HOLDING_AFTER_STOP.clear();
    }

    /** Re-create the underground survival executor if it was shut down. Called from {@code SERVER_STARTED}. */
    public static void restartExecutors() {
        if (EXECUTOR.isShutdown()) {
            EXECUTOR = createExecutor();
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) return;
        long nowTick = server.getTicks();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            UUID botId = bot.getUuid();

            // /bot stop is an owner command: its 60-tick grace is checked every tick so it is never
            // missed between throttled checks.
            if (STUCK_STATE.containsKey(botId) && BotEventHandler.isInStopCommandGrace(botId)) {
                clearStuckEpisode(bot, "stop command");
            }

            // Throttle: one check per bot per interval
            if (nowTick < NEXT_CHECK_TICK.getOrDefault(botId, 0L)) continue;
            NEXT_CHECK_TICK.put(botId, nowTick + CHECK_INTERVAL_TICKS);

            boolean abortLatched = TaskService.isAbortRequested(botId);
            String latchReason = abortLatched ? TaskService.getCancelReason(botId).orElse("") : null;
            SurvivalLatchPolicy.Decision latchDecision = SurvivalLatchPolicy.decide(
                    abortLatched, latchReason, TaskService.hasActiveTask(botId));
            if (latchDecision != SurvivalLatchPolicy.Decision.HOLD) {
                HOLDING_AFTER_STOP.remove(botId);
            }

            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;
            if (world.getRegistryKey() != World.OVERWORLD) continue;

            // Only idle bots — don't interrupt tasks or active movement
            if (TaskService.hasActiveTask(botId)) {
                // A command-origin task means the owner told the bot to do something.
                if (STUCK_STATE.containsKey(botId) && TaskService.getActiveTaskInfo(botId)
                        .map(info -> info.origin() == TaskService.Origin.COMMAND).orElse(false)) {
                    clearStuckEpisode(bot, "owner command");
                }
                continue;
            }
            // A stopped bot stays put: the abort latch from /bot stop would abort every staging walk
            // instantly, so do not relaunch recovery until the next command clears it. Only the
            // manual stop holds; a death or threat latch is cleared just before dispatch below.
            if (latchDecision == SurvivalLatchPolicy.Decision.HOLD) {
                if (HOLDING_AFTER_STOP.add(botId)) {
                    LOGGER.info("Underground survival: {} holding still after a stop command until the next command",
                            bot.getName().getString());
                }
                continue;
            }
            BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
            if (mode != BotEventHandler.Mode.IDLE) {
                if (isOwnerSetMode(mode) && STUCK_STATE.containsKey(botId)) {
                    clearStuckEpisode(bot, "owner command (" + mode + ")");
                }
                continue;
            }

            // Already at surface — nothing to do
            if (BotFleeService.isAtSurface(bot, world)) {
                STUCK_STATE.remove(botId);
                continue;
            }

            // Another service already handling recovery — don't dogpile
            if (BotFleeService.isSurfaceRecoveryActive(botId)) continue;
            if (BotFleeService.isBreakingFree(botId)) continue;
            if (BotFleeService.isInShelter(botId)) continue;
            // Creeper backoff owns the bot's movement and its own abort latch.
            if (BotCreeperDefenseService.isBackingOff(botId)) continue;

            // Stuck with nothing to build with: back off instead of relaunching every 10 s.
            StuckEscapeBackoffPolicy.State stuck = STUCK_STATE.get(botId);
            if (stuck != null) {
                StuckEscapeBackoffPolicy.State observed = StuckEscapeBackoffPolicy.observe(stuck,
                        bot.isOnGround(), bot.getBlockY(), BotFleeService.countScaffoldBlocks(bot),
                        world.isDay(), false);
                if (!observed.active()) {
                    clearStuckEpisode(bot, "position, scaffold or daybreak changed");
                } else {
                    if (observed != stuck) {
                        STUCK_STATE.put(botId, observed);
                    }
                    if (StuckEscapeBackoffPolicy.inCooldown(observed, nowTick)) continue;
                }
            }

            // A death or threat abort left its latch with no task to clear it: every staging walk
            // would abort instantly, so clear it here, where escape really dispatches.
            if (latchDecision == SurvivalLatchPolicy.Decision.CLEAR_AND_PROCEED) {
                TaskService.clearAbortLatch(botId);
                LOGGER.info("Underground survival: cleared a stale abort latch for {} (reason={}) so escape can run",
                        bot.getName().getString(), latchReason);
            }

            // Dispatch to worker thread (ensureAtSurface is blocking)
            EXECUTOR.submit(() -> {
                try {
                    if (TaskService.isServerStopping()) return;
                    if (bot.isRemoved() || !bot.isAlive()) return;
                    ATTEMPT_RECORDED.set(Boolean.FALSE);
                    long startTick = server.getTicks();
                    boolean reached = BotFleeService.ensureAtSurface(bot, world);
                    recordUnfedFailure(bot, world, server, reached, startTick);
                } catch (Throwable t) {
                    LOGGER.warn("Underground survival check failed for {}: {}",
                            bot.getName().getString(), t.getMessage());
                } finally {
                    ATTEMPT_RECORDED.remove();
                }
            });
        }
    }

    /**
     * Worker thread only. Feeds the backoff from an {@code ensureAtSurface} attempt that really ran
     * (it stamped a surface-recovery failure during this call, so linger suppression and duplicate
     * requests are not counted) and ended below the surface, when {@code escapeToSurface} did not
     * already record it. Any failure path counts: 1.1.239 sandbox, a stale stop latch aborted the
     * staging walk and {@code escapeToSurface} returned before recording, so 29 attempts in 5
     * minutes never engaged the backoff.
     */
    private static void recordUnfedFailure(ServerPlayerEntity bot, ServerWorld world, MinecraftServer server,
                                           boolean reached, long startTick) {
        if (reached || ATTEMPT_RECORDED.get() || bot.isRemoved() || !bot.isAlive()) {
            return;
        }
        long endTick = server.getTicks();
        if (!BotFleeService.hadRecentSurfaceRecoveryFailure(bot.getUuid(), endTick, Math.max(1L, endTick - startTick))) {
            return;
        }
        if (BotFleeService.isAtSurface(bot, world)) {
            return;
        }
        recordRecoveryAttempt(bot, false, BotFleeService.countScaffoldBlocks(bot), world.isDay(), endTick);
    }

    /** Modes only an owner command sets; leaving IDLE for one of these ends a stuck episode. */
    private static boolean isOwnerSetMode(BotEventHandler.Mode mode) {
        return mode == BotEventHandler.Mode.FOLLOW
                || mode == BotEventHandler.Mode.GUARD
                || mode == BotEventHandler.Mode.PATROL
                || mode == BotEventHandler.Mode.STAY;
    }

    /** Worker thread only: bounded poll until the bot stands on something. */
    private static void awaitLanding(ServerPlayerEntity bot, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (!bot.isOnGround() && !bot.isRemoved() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void clearStuckEpisode(ServerPlayerEntity bot, String why) {
        if (STUCK_STATE.remove(bot.getUuid()) != null) {
            LOGGER.info("Stuck-escape backoff cleared for {}: {}", bot.getName().getString(), why);
        }
    }

    /**
     * Feeds one finished surface-recovery attempt into the stuck-escape backoff. Called from
     * {@code BotFleeService.escapeToSurface} and {@link #recordUnfedFailure} on a worker thread; the owner line goes through
     * {@link ChatUtils#sendSystemMessage}, which hops to the server thread itself.
     */
    static void recordRecoveryAttempt(ServerPlayerEntity bot, boolean succeeded, int scaffoldCount,
                                      boolean isDay, long nowTick) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUuid();
        ATTEMPT_RECORDED.set(Boolean.TRUE);
        if (!succeeded) {
            awaitLanding(bot, 800L); // an apex Y would read as a ground change and reset the episode
        }
        boolean ownerReachable = isOwnerReachable(bot);
        StuckEscapeBackoffPolicy.Result[] holder = new StuckEscapeBackoffPolicy.Result[1];
        STUCK_STATE.compute(botId, (id, prior) -> {
            StuckEscapeBackoffPolicy.Result r = StuckEscapeBackoffPolicy.recordAttempt(prior, succeeded,
                    bot.isOnGround(), bot.getBlockY(), scaffoldCount, isDay, nowTick, ownerReachable);
            holder[0] = r;
            return r.state().active() ? r.state() : null;
        });
        StuckEscapeBackoffPolicy.Result result = holder[0];
        if (result == null || result.outcome() == StuckEscapeBackoffPolicy.Outcome.NONE) {
            return;
        }
        LOGGER.info("Stuck-escape backoff: {} failed surface recovery {} times at Y={} with nothing to build with,"
                        + " pausing retries for {} s",
                bot.getName().getString(), result.state().failures(), result.state().groundY(),
                result.cooldownTicks() / 20L);
        if (result.outcome() == StuckEscapeBackoffPolicy.Outcome.NOTIFY_ONCE) {
            ChatUtils.sendSystemMessage(bot.getCommandSource(), STUCK_NOTIFY_LINE);
        } else if (!result.state().notified()) {
            LOGGER.info("Stuck-escape backoff: owner of {} offline or dead, stuck notice kept pending",
                    bot.getName().getString());
        }
    }

    /** The owner is online and alive, so the stuck line would not be dropped. */
    private static boolean isOwnerReachable(ServerPlayerEntity bot) {
        MinecraftServer server = bot.getCommandSource().getServer();
        ServerPlayerEntity owner = server != null ? CompanionCommunicationPolicy.resolveController(server, bot) : null;
        return StuckEscapeBackoffPolicy.ownerReachable(owner != null, owner != null && owner.isRemoved(),
                owner != null && owner.isAlive());
    }
}
