package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.BotCommandStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-tick automation: when enabled for a bot, it will automatically run "return to home" at sunset.
 */
public final class BotAutoReturnSunsetService {

    private static final Logger LOGGER = LoggerFactory.getLogger("auto-return-sunset");

    // Vanilla day cycle is 24000 ticks. Sunset/dusk starts around 12000.
    private static final long DAY_TICKS = 24000L;
    private static final long SUNSET_START_TICK = 12000L;

    private static final Map<UUID, Long> LAST_TRIGGERED_DAY = new ConcurrentHashMap<>();

    private record PendingSleep(Vec3d target, long triggeredServerTick, long day, long nextAttemptServerTick) {}

    private static final Map<UUID, PendingSleep> PENDING_SLEEP = new ConcurrentHashMap<>();

    private static final AtomicInteger AUTO_THREAD_ID = new AtomicInteger(0);
    private static final ExecutorService AUTO_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "auto-sunset-" + AUTO_THREAD_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private BotAutoReturnSunsetService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long serverTick = server.getTicks();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (!BotHomeService.isAutoReturnAtSunset(bot)) {
                // If toggle is disabled, clear any pending auto-sleep state.
                PENDING_SLEEP.remove(bot.getUuid());
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                PENDING_SLEEP.remove(bot.getUuid());
                continue;
            }
            if (world.getRegistryKey() != World.OVERWORLD) {
                // Policy: no cross-dimension home logic.
                PENDING_SLEEP.remove(bot.getUuid());
                continue;
            }
            if (bot.isSleeping()) {
                PENDING_SLEEP.remove(bot.getUuid());
                continue;
            }

            long timeOfDay = world.getTimeOfDay();
            long day = Math.floorDiv(timeOfDay, DAY_TICKS);
            long tod = Math.floorMod(timeOfDay, DAY_TICKS);

            // First, if we have a pending "sleep after arriving home" request, try to fulfill it.
            PendingSleep pending = PENDING_SLEEP.get(bot.getUuid());
            if (pending != null) {
                // Drop stale pending requests (e.g. unreachable path, mode changed, etc.).
                // Keep it long enough to cover "arrive at dusk -> wait -> sleep at night".
                if (pending.day() != day || (serverTick - pending.triggeredServerTick()) > 18_000L) { // ~15 minutes @20tps
                    PENDING_SLEEP.remove(bot.getUuid());
                } else {
                    // If commander changed the bot away from returning/staying/idle, treat that as an override.
                    BotEventHandler.Mode modeNow = BotEventHandler.getCurrentMode(bot);
                    if (!isReturningHome(bot)
                            && modeNow != BotEventHandler.Mode.STAY
                            && modeNow != BotEventHandler.Mode.IDLE) {
                        PENDING_SLEEP.remove(bot.getUuid());
                    } else {
                        // Don't spam attempts.
                        if (serverTick >= pending.nextAttemptServerTick()) {
                            var taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
                            if (taskInfo.isEmpty() || !"skill:sleep".equalsIgnoreCase(taskInfo.get().name())) {
                                double dx = pending.target().x - bot.getX();
                                double dz = pending.target().z - bot.getZ();
                                double horizDistSq = dx * dx + dz * dz;
                                if (horizDistSq <= 3.2D * 3.2D) {
                                    // Only attempt to sleep when it can actually succeed.
                                    if (canSleepNow(world)) {
                                        scheduleSleep(server, bot);
                                        // Back off retries in case sleep fails (no bed, blocked, etc.).
                                        PENDING_SLEEP.put(bot.getUuid(), new PendingSleep(
                                                pending.target(), pending.triggeredServerTick(), pending.day(), serverTick + 1_200L));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (tod < SUNSET_START_TICK) {
                continue;
            }

            long last = LAST_TRIGGERED_DAY.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
            if (last >= day) {
                continue;
            }

            // Gating: only trigger if the bot is eligible (open-ended skill OR idle-not-following OR guard/patrol if enabled)
            // and NOT already busy with a non-open-ended commander task.
            if (!isEligibleForSunsetAutomation(bot)) {
                continue;
            }

            LAST_TRIGGERED_DAY.put(bot.getUuid(), day);

            try {
                // Only interrupt open-ended tasks; other (commander) tasks are not eligible anyway.
                TaskService.getActiveTaskInfo(bot.getUuid()).ifPresent(info -> {
                    if (info.openEnded()) {
                        TaskService.forceAbort(bot.getUuid(), "§cSunset: heading home.");
                    }
                });
            } catch (Throwable ignored) {
                // Best-effort; auto-return should still attempt even if task system is unavailable.
            }

            LOGGER.info("Auto-return at sunset triggered for {} (day={} tod={})", bot.getName().getString(), day, tod);

            // Choose where to return.
            BlockPos homePos = resolveSunsetHomeTarget(bot, world);
            if (homePos != null) {
                BotEventHandler.setReturnToBase(bot, Vec3d.ofCenter(homePos));
            } else {
                BotEventHandler.setReturnToBase(bot, (ServerPlayerEntity) null);
            }

            // Track where "home" is so we can attempt sleep once the bot arrives.
            BotCommandStateService.State st = BotCommandStateService.stateFor(bot);
            Vec3d baseTarget = st != null ? st.baseTarget : null;
            if (baseTarget != null) {
                PENDING_SLEEP.put(bot.getUuid(), new PendingSleep(baseTarget, serverTick, day, serverTick + 200L));
            }
        }
    }

    private static boolean canSleepNow(ServerWorld world) {
        if (world == null) {
            return false;
        }
        return !world.isDay() || world.isThundering();
    }

    private static BlockPos resolveSunsetHomeTarget(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }

        // Override: if enabled, always prefer the last slept bed (if known) over nearest base.
        if (BotHomeService.isAutoReturnPreferLastBedAtSunset(bot)) {
            var last = BotHomeService.getLastSleep(bot);
            if (last.isPresent()) {
                return last.get().toImmutable();
            }
        }

        return BotHomeService.resolveHomeTarget(bot)
                .map(BlockPos::toImmutable)
                .orElse(null);
    }

    private static boolean isEligibleForSunsetAutomation(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        UUID botUuid = bot.getUuid();

        // Already sleeping / already going to sleep / already going home.
        if (bot.isSleeping()) {
            return false;
        }
        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
        if (isReturningHome(bot)) {
            return false;
        }

        var taskInfo = TaskService.getActiveTaskInfo(botUuid);
        if (taskInfo.isPresent()) {
            TaskService.ActiveTaskInfo info = taskInfo.get();
            if (info.name() != null && "skill:sleep".equalsIgnoreCase(info.name())) {
                return false;
            }
            // Only allow sunset automation to interrupt open-ended tasks.
            return info.openEnded();
        }

        // No active task: allow if idle (and not following) OR guard/patrol (optional).
        if (mode == BotEventHandler.Mode.IDLE) {
            return true;
        }

        if ((mode == BotEventHandler.Mode.GUARD || mode == BotEventHandler.Mode.PATROL)
                && BotHomeService.isAutoReturnGuardPatrolEligible(bot)) {
            return true;
        }

        return false;
    }

    private static boolean isReturningHome(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
        if (mode == BotEventHandler.Mode.RETURNING_BASE) {
            return true;
        }
        if (mode != BotEventHandler.Mode.FOLLOW) {
            return false;
        }
        BotCommandStateService.State st = BotCommandStateService.stateFor(bot);
        return st != null && st.baseTarget != null && st.followFixedGoal != null;
    }

    private static void scheduleSleep(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null || bot.isRemoved()) {
            return;
        }
        UUID botUuid = bot.getUuid();

        // Avoid double-queueing sleep.
        var active = TaskService.getActiveTaskInfo(botUuid);
        if (active.isPresent() && "skill:sleep".equalsIgnoreCase(active.get().name())) {
            return;
        }

        ServerCommandSource source = bot.getCommandSource().withSilent();
        var ticketOpt = TaskService.beginSkill("sleep", source, botUuid);
        if (ticketOpt.isEmpty()) {
            return;
        }

        TaskService.TaskTicket ticket = ticketOpt.get();
        ticket.setOrigin(TaskService.Origin.SYSTEM);
        ticket.setOpenEnded(false);

        AUTO_EXECUTOR.submit(() -> {
            boolean success = false;
            try {
                TaskService.attachExecutingThread(ticket, Thread.currentThread());
                success = SleepService.sleep(source, bot) && !TaskService.isAbortRequested(botUuid);
            } catch (Exception e) {
                LOGGER.warn("Auto-sleep at sunset failed for {}: {}", bot.getName().getString(), e.getMessage());
            } finally {
                TaskService.complete(ticket, success);
            }
        });
    }
}
