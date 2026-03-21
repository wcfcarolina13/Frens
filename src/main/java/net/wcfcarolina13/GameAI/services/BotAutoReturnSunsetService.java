package net.wcfcarolina13.GameAI.services;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotCommandStateService;
import net.wcfcarolina13.GameAI.services.TravelMountHandler;
import net.wcfcarolina13.network.NavigationRequestPayload;
import net.wcfcarolina13.PathFinding.BaritoneStylePathFinder;
import net.wcfcarolina13.PathFinding.PathFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final long SUNRISE_END_TICK = 1000L;
    private static final double PRACTICAL_HOME_RANGE_SQ = 96.0D * 96.0D;
    private static final long HOME_FAILOVER_GRACE_TICKS = 120L;
    private static final long NO_PROGRESS_FAILOVER_TICKS = 320L;
    private static final long ROUTE_REPLAN_INTERVAL_TICKS = 40L;
    private static final double SEGMENT_REFRESH_REACH_SQ = 6.0D * 6.0D;
    private static final double MAX_SEGMENT_PROGRESS = 24.0D;
    private static final double MIN_SEGMENT_PROGRESS = 8.0D;
    private static final double COMMANDER_FALLBACK_RANGE_SQ = 48.0D * 48.0D;
    private static final double ALLY_FALLBACK_RANGE_SQ = 48.0D * 48.0D;
    private static final double CHEST_FALLBACK_RANGE_SQ = 32.0D * 32.0D;
    private static final double STABLE_ANCHOR_RADIUS = 12.0D;
    private static final long RECENT_SLEEP_WINDOW_MS = 3L * 24L * 60L * 60L * 1000L;
    private static final long LOCAL_REASSESS_COOLDOWN_TICKS = 120L;
    private static final long RECENT_SURFACE_FAILURE_TICKS = 240L;
    private static final int CLIFF_TRAP_VERTICAL_GAP = 10;
    private static final Map<UUID, Long> LAST_TRIGGERED_DAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_RESUMED_DAY = new ConcurrentHashMap<>();

    private record PendingSleep(Vec3d target, long triggeredServerTick, long day, long nextAttemptServerTick) {}

    private static final Map<UUID, PendingSleep> PENDING_SLEEP = new ConcurrentHashMap<>();
    private static final Map<UUID, SunsetSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private enum AnchorKind {
        HOME,
        SPAWN,
        BASE,
        BED,
        COMMANDER,
        ALLY_BOT,
        VILLAGE_HOUSE,
        CHEST,
        TACTICAL_SHELTER
    }

    private record SunsetAnchor(BlockPos pos, AnchorKind kind, String detail) {}

    private static final class SunsetSession {
        private BlockPos primaryHome;
        private AnchorKind primaryKind;
        private BlockPos activeAnchor;
        private AnchorKind activeKind;
        private boolean selfSufficient;
        private boolean fallbackEngaged;
        private long day;
        private long startedServerTick;
        private long lastProgressServerTick;
        private double lastProgressDistSq;
        private long nextRouteServerTick;
        private int routeFailures;
        private boolean tacticalShelterTriggered;
        private long localReassessUntilTick;
    }

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

    public static void beginAcceptedSunsetReturn(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null || bot.isRemoved() || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        long timeOfDay = world.getTimeOfDay();
        long day = Math.floorDiv(timeOfDay, DAY_TICKS);
        beginSunsetSession(server, bot, world, day, server.getTicks());
    }

    public static boolean isNightTravelSessionActive(UUID botId) {
        if (botId == null) {
            return false;
        }
        SunsetSession session = ACTIVE_SESSIONS.get(botId);
        return session != null && session.activeKind != AnchorKind.TACTICAL_SHELTER;
    }

    public static boolean isTacticalShelterSession(UUID botId) {
        if (botId == null) {
            return false;
        }
        SunsetSession session = ACTIVE_SESSIONS.get(botId);
        return session != null && session.activeKind == AnchorKind.TACTICAL_SHELTER;
    }

    public static void clearSession(UUID botId) {
        if (botId == null) {
            return;
        }
        ACTIVE_SESSIONS.remove(botId);
        PENDING_SLEEP.remove(botId);
    }

    public static void deferLocalReassess(UUID botId, long untilServerTick) {
        if (botId == null) {
            return;
        }
        SunsetSession session = ACTIVE_SESSIONS.get(botId);
        if (session == null) {
            return;
        }
        session.localReassessUntilTick = Math.max(session.localReassessUntilTick, untilServerTick);
    }

    /**
     * Called by the return-home arrival path after follow/base state is cleared.
     * Returns true when the default "Arrived at base." line should be suppressed.
     */
    public static boolean handleArrival(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null) {
            return false;
        }
        SunsetSession session = ACTIVE_SESSIONS.remove(bot.getUuid());
        if (session == null) {
            return false;
        }
        if (session.activeKind == AnchorKind.CHEST) {
            PENDING_SLEEP.remove(bot.getUuid());
            if (server != null) {
                BotFleeService.tryProactiveShelter(bot, server);
            }
            CompanionOverheadDialogueService.showOverheadLine(bot,
                    "Making shelter by the chest.",
                    3_000,
                    32.0,
                    "sunset-arrival",
                    "chest");
            return true;
        }
        if (session.activeKind == AnchorKind.COMMANDER || session.activeKind == AnchorKind.ALLY_BOT) {
            PENDING_SLEEP.remove(bot.getUuid());
            CompanionOverheadDialogueService.showOverheadLine(bot,
                    "Regrouped for the night.",
                    2_800,
                    32.0,
                    "sunset-arrival",
                    "regroup");
            return true;
        }
        if (session.activeKind == AnchorKind.VILLAGE_HOUSE) {
            PENDING_SLEEP.remove(bot.getUuid());
            if (server != null) {
                BotFleeService.tryProactiveShelter(bot, server);
            }
            CompanionOverheadDialogueService.showOverheadLine(bot,
                    "Sheltering in the village.",
                    3_000,
                    32.0,
                    "sunset-arrival",
                    "village");
            return true;
        }
        if (session.activeKind == AnchorKind.TACTICAL_SHELTER) {
            PENDING_SLEEP.remove(bot.getUuid());
            if (server != null) {
                BotFleeService.tryProactiveShelter(bot, server);
            }
            CompanionOverheadDialogueService.showOverheadLine(bot,
                    "Settling into shelter.",
                    3_000,
                    32.0,
                    "sunset-arrival",
                    "shelter");
            return true;
        }
        return false;
    }

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
                ACTIVE_SESSIONS.remove(bot.getUuid());
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                PENDING_SLEEP.remove(bot.getUuid());
                ACTIVE_SESSIONS.remove(bot.getUuid());
                continue;
            }
            if (world.getRegistryKey() != World.OVERWORLD) {
                // Policy: no cross-dimension home logic.
                PENDING_SLEEP.remove(bot.getUuid());
                ACTIVE_SESSIONS.remove(bot.getUuid());
                continue;
            }
            if (bot.isSleeping()) {
                PENDING_SLEEP.remove(bot.getUuid());
                ACTIVE_SESSIONS.remove(bot.getUuid());
                continue;
            }

            long timeOfDay = world.getTimeOfDay();
            long day = Math.floorDiv(timeOfDay, DAY_TICKS);
            long tod = Math.floorMod(timeOfDay, DAY_TICKS);

            tickSunsetSession(server, bot, world, serverTick, day, tod);

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

            // Sunrise: resume paused hunt sessions
            if (tod < SUNRISE_END_TICK && HuntSessionService.hasSession(bot.getUuid())) {
                long lastResumed = LAST_RESUMED_DAY.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
                if (lastResumed < day) {
                    LAST_RESUMED_DAY.put(bot.getUuid(), day);
                    var taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
                    if (taskInfo.isEmpty()) {
                        LOGGER.info("Sunrise hunt resume for {} (day={})", bot.getName().getString(), day);
                        SkillResumeService.tryAutoResume(bot);
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

            // If skip-permission is enabled, go directly to return without notification
            if (BotHomeService.isAutoReturnSkipPermission(bot)) {
                LOGGER.info("Auto-return skip-permission: {} returning home without asking", bot.getName().getString());
                if (bot.hasVehicle()) {
                    TravelMountHandler.ensureMountPersistence(bot.getVehicle());
                }
                beginSunsetSession(server, bot, world, day, serverTick);
                continue;
            }

            // If the owner is online, send a non-obstructive HUD notification instead of
            // directly triggering the return.  The player can Accept (which triggers
            // setReturnToBase via SpellNavigationNetworkManager) or Dismiss.
            boolean notifiedOwner = false;
            try {
                ServerPlayerEntity owner = CompanionCommunicationPolicy.resolveController(server, bot);
                if (owner != null && !owner.isRemoved()) {
                    String alias = bot.getName().getString();
                    Optional<BlockPos> homeOpt = BotHomeService.resolveHomeTarget(bot);
                    if (homeOpt.isPresent()) {
                        BlockPos home = homeOpt.get();
                        double dist = Math.sqrt(bot.squaredDistanceTo(Vec3d.ofCenter(home)));
                        boolean crossDim = false; // Auto-return is same-dimension (Overworld gate above)
                        int seconds = NavigationArtifactService.calculateDelayTicks(dist, crossDim) / 20;
                        ServerPlayNetworking.send(owner, new NavigationRequestPayload(alias, "home", seconds));
                        notifiedOwner = true;
                        LOGGER.info("Sent sunset auto-return notification to {} for bot {}",
                                owner.getName().getString(), alias);
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("Failed to send sunset notification for {}: {}", bot.getName().getString(), t.getMessage());
            }

            if (notifiedOwner) {
                // Owner was notified — wait for accept/dismiss. Accept creates the actual sunset session.
            } else {
                // Fallback: owner offline or no home resolved — proceed with direct return
                // (existing behavior).

                // Harden mount against despawn during the return trip.
                if (bot.hasVehicle()) {
                    TravelMountHandler.ensureMountPersistence(bot.getVehicle());
                }
                beginSunsetSession(server, bot, world, day, serverTick);
            }
        }
    }

    private static void beginSunsetSession(MinecraftServer server,
                                           ServerPlayerEntity bot,
                                           ServerWorld world,
                                           long day,
                                           long serverTick) {
        if (server == null || bot == null || world == null) {
            return;
        }
        SunsetAnchor anchor = resolvePrimaryHomeAnchor(bot, world);
        if (anchor == null || anchor.pos() == null) {
            return;
        }

        SunsetSession session = new SunsetSession();
        session.primaryHome = anchor.pos().toImmutable();
        session.primaryKind = anchor.kind();
        session.activeAnchor = anchor.pos().toImmutable();
        session.activeKind = anchor.kind();
        session.selfSufficient = BotHomeService.isAutoReturnSelfSufficientFallback(bot);
        session.fallbackEngaged = false;
        session.day = day;
        session.startedServerTick = serverTick;
        session.lastProgressServerTick = serverTick;
        session.lastProgressDistSq = bot.getBlockPos().getSquaredDistance(anchor.pos());
        session.nextRouteServerTick = 0L;
        session.routeFailures = 0;
        session.tacticalShelterTriggered = false;
        session.localReassessUntilTick = 0L;
        ACTIVE_SESSIONS.put(bot.getUuid(), session);
        retargetSession(bot, world, session, anchor, serverTick, true, "sunset-start");
    }

    private static void tickSunsetSession(MinecraftServer server,
                                          ServerPlayerEntity bot,
                                          ServerWorld world,
                                          long serverTick,
                                          long day,
                                          long tod) {
        if (server == null || bot == null || world == null) {
            return;
        }
        SunsetSession session = ACTIVE_SESSIONS.get(bot.getUuid());
        if (session == null) {
            return;
        }
        if (tod < SUNRISE_END_TICK || day > session.day) {
            clearSession(bot.getUuid());
            return;
        }

        if (BotFleeService.tryResumeInterruptedSurvival(bot, server)) {
            return;
        }
        if (BotFleeService.hasPendingInterruptedSurvival(bot.getUuid())) {
            return;
        }

        if (HealingService.isStarving(bot)) {
            if (HealingService.autoEat(bot) || BotMutualAidService.tryImmediateChestFoodRecovery(bot, world)) {
                return;
            }
            if (BotMutualAidService.trySeekFoodFromNearbyBot(bot, world)) {
                BotEventHandler.stopFollowing(bot, false);
                session.localReassessUntilTick = Math.max(session.localReassessUntilTick, serverTick + LOCAL_REASSESS_COOLDOWN_TICKS);
                return;
            }
            if (BotEmergencyRescueService.tryEmergencyRescue(bot, world, "sunset-starving")) {
                return;
            }
            BotEventHandler.stopFollowing(bot, false);
            session.localReassessUntilTick = Math.max(session.localReassessUntilTick, serverTick + LOCAL_REASSESS_COOLDOWN_TICKS);
            BotAutoHuntService.requestDecisionNow(bot);
            return;
        }

        if (session.activeKind == AnchorKind.TACTICAL_SHELTER) {
            PENDING_SLEEP.remove(bot.getUuid());
            if (BotFleeService.isInShelter(bot.getUuid())) {
                return;
            }
            if (TaskService.hasActiveTask(bot.getUuid())) {
                return;
            }
            if (!session.tacticalShelterTriggered || serverTick >= session.nextRouteServerTick) {
                BotEventHandler.stopFollowing(bot, false);
                session.tacticalShelterTriggered = BotFleeService.tryProactiveShelter(bot, server);
                session.nextRouteServerTick = serverTick + 80L;
            }
            return;
        }

        if (session.activeKind == AnchorKind.VILLAGE_HOUSE
                && bot.getBlockPos().getSquaredDistance(session.activeAnchor) <= 9.0D
                && SurvivalRecruitmentService.isVillageSignalNearby(world, bot.getBlockPos())) {
            BotEventHandler.stopFollowing(bot, false);
            return;
        }

        if (TaskService.hasActiveTask(bot.getUuid()) || BotFleeService.isInShelter(bot.getUuid())) {
            return;
        }

        if (serverTick < session.localReassessUntilTick) {
            BotEventHandler.stopFollowing(bot, false);
            return;
        }

        SunsetAnchor localOverride = resolveLocalSurvivalOverride(bot, world, session, serverTick);
        if (localOverride != null
                && localOverride.pos() != null
                && (!localOverride.pos().equals(session.activeAnchor) || localOverride.kind() != session.activeKind)) {
            retargetSession(bot, world, session, localOverride, serverTick, true, "sunset-local-survival");
            session.localReassessUntilTick = serverTick + LOCAL_REASSESS_COOLDOWN_TICKS;
            return;
        }

        if (!isReturningHome(bot)) {
            startOrResumeReturn(bot, session.activeAnchor, "sunset-resume");
        }

        double anchorDistSq = bot.getBlockPos().getSquaredDistance(session.activeAnchor);
        if (anchorDistSq + 4.0D < session.lastProgressDistSq) {
            session.lastProgressDistSq = anchorDistSq;
            session.lastProgressServerTick = serverTick;
            session.routeFailures = 0;
        }

        if (!session.fallbackEngaged
                && session.selfSufficient
                && shouldFailOverFromHome(bot, session, serverTick)) {
            SunsetAnchor fallback = resolveSelfSufficientFallback(bot, world, session.primaryHome);
            if (fallback != null && fallback.pos() != null && !fallback.pos().equals(session.activeAnchor)) {
                retargetSession(bot, world, session, fallback, serverTick, false, "sunset-fallback");
                return;
            }
        }

        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        BlockPos currentGoal = state != null ? state.followFixedGoal : null;
        boolean needSegmentRefresh = currentGoal == null
                || bot.getBlockPos().getSquaredDistance(currentGoal) <= SEGMENT_REFRESH_REACH_SQ
                || serverTick >= session.nextRouteServerTick;
        if (needSegmentRefresh) {
            boolean routed = refreshTravelSegment(bot, world, session);
            session.nextRouteServerTick = serverTick + ROUTE_REPLAN_INTERVAL_TICKS;
            if (!routed) {
                session.routeFailures++;
                if (!session.fallbackEngaged && session.selfSufficient && session.routeFailures >= 2) {
                    SunsetAnchor fallback = resolveSelfSufficientFallback(bot, world, session.primaryHome);
                    if (fallback != null && fallback.pos() != null && !fallback.pos().equals(session.activeAnchor)) {
                        retargetSession(bot, world, session, fallback, serverTick, false, "sunset-no-route");
                    }
                }
            } else {
                session.routeFailures = 0;
            }
        }
    }

    private static boolean shouldFailOverFromHome(ServerPlayerEntity bot, SunsetSession session, long serverTick) {
        if (bot == null || session == null) {
            return false;
        }
        if (serverTick - session.startedServerTick < HOME_FAILOVER_GRACE_TICKS) {
            return false;
        }
        if (session.activeKind != AnchorKind.HOME && session.activeKind != AnchorKind.SPAWN) {
            return false;
        }
        double homeDistSq = bot.getBlockPos().getSquaredDistance(session.primaryHome);
        if (homeDistSq > PRACTICAL_HOME_RANGE_SQ) {
            return true;
        }
        return (serverTick - session.lastProgressServerTick) >= NO_PROGRESS_FAILOVER_TICKS;
    }

    private static void retargetSession(ServerPlayerEntity bot,
                                        ServerWorld world,
                                        SunsetSession session,
                                        SunsetAnchor anchor,
                                        long serverTick,
                                        boolean announce,
                                        String reason) {
        if (bot == null || world == null || session == null || anchor == null || anchor.pos() == null) {
            return;
        }
        session.activeAnchor = anchor.pos().toImmutable();
        session.activeKind = anchor.kind();
        session.fallbackEngaged = session.fallbackEngaged || session.activeKind != session.primaryKind;
        session.lastProgressServerTick = serverTick;
        session.lastProgressDistSq = bot.getBlockPos().getSquaredDistance(session.activeAnchor);
        session.nextRouteServerTick = 0L;
        session.routeFailures = 0;
        session.tacticalShelterTriggered = false;
        session.localReassessUntilTick = 0L;

        if (supportsSleep(anchor.kind())) {
            PENDING_SLEEP.put(bot.getUuid(), new PendingSleep(Vec3d.ofCenter(anchor.pos()), serverTick, session.day, serverTick + 200L));
        } else {
            PENDING_SLEEP.remove(bot.getUuid());
        }

        if (anchor.kind() == AnchorKind.TACTICAL_SHELTER) {
            BotEventHandler.stopFollowing(bot, false);
            return;
        }

        primeReturnState(bot, anchor.pos());
        if (!refreshTravelSegment(bot, world, session)) {
            FollowPlannerService.requestPlanToGoal(LOGGER, bot, anchor.pos().toImmutable(), true, reason == null ? "sunset-route" : reason);
        }

        if (!announce) {
            return;
        }
        if (anchor.kind() == AnchorKind.HOME || anchor.kind() == AnchorKind.SPAWN) {
            return;
        }
        CompanionOverheadDialogueService.showOverheadLine(bot,
                "Switching to a local night shelter plan.",
                3_000,
                36.0,
                "sunset-fallback",
                anchor.kind().name().toLowerCase());
    }

    private static void startOrResumeReturn(ServerPlayerEntity bot, BlockPos anchor, String reason) {
        if (bot == null || anchor == null) {
            return;
        }
        primeReturnState(bot, anchor);
        FollowPlannerService.requestPlanToGoal(LOGGER, bot, anchor.toImmutable(), true, reason == null ? "sunset-route" : reason);
    }

    private static void primeReturnState(ServerPlayerEntity bot, BlockPos anchor) {
        if (bot == null || anchor == null) {
            return;
        }
        if (!isReturningHome(bot)) {
            BotEventHandler.setReturnToBaseSegmented(bot, Vec3d.ofCenter(anchor));
        }
        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        if (state == null) {
            return;
        }
        state.baseTarget = Vec3d.ofCenter(anchor);
        state.followFixedGoal = anchor.toImmutable();
        state.comeBestGoalDistSq = Double.NaN;
        state.comeTicksSinceBest = 0;
        state.comeRerouteAttempts = 0;
        state.comeNextRerouteTick = 0L;
        state.comeNextSkillTick = 0L;
        state.comeRecoverySkillInFlight = false;
        state.comeRecoverySkillStartTick = 0L;
        FollowStateService.clearPlanning(bot.getUuid());
        FollowDebugService.clear(bot.getUuid());
    }

    private static boolean refreshTravelSegment(ServerPlayerEntity bot, ServerWorld world, SunsetSession session) {
        if (bot == null || world == null || session == null || session.activeAnchor == null) {
            return false;
        }
        BlockPos botPos = bot.getBlockPos();
        BlockPos anchor = session.activeAnchor;
        int manhattan = Math.abs(anchor.getX() - botPos.getX()) + Math.abs(anchor.getZ() - botPos.getZ());
        BlockPos nextGoal = anchor;
        if (manhattan > 60) {
            List<PathFinder.PathNode> path = BaritoneStylePathFinder.calculatePath(botPos, anchor, world, 200L);
            nextGoal = chooseSegmentGoal(botPos, anchor, path);
            if (nextGoal == null) {
                return false;
            }
        }
        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        if (state == null) {
            return false;
        }
        if (nextGoal.equals(state.followFixedGoal)
                && state.baseTarget != null
                && anchor.equals(BlockPos.ofFloored(state.baseTarget))) {
            return true;
        }
        state.baseTarget = Vec3d.ofCenter(anchor);
        state.followFixedGoal = nextGoal.toImmutable();
        state.comeBestGoalDistSq = Double.NaN;
        state.comeTicksSinceBest = 0;
        state.comeRerouteAttempts = 0;
        state.comeNextRerouteTick = 0L;
        FollowStateService.clearPlanning(bot.getUuid());
        FollowDebugService.clear(bot.getUuid());
        FollowPlannerService.requestPlanToGoal(LOGGER, bot, nextGoal.toImmutable(), true, "sunset-segment");
        return true;
    }

    private static BlockPos chooseSegmentGoal(BlockPos start, BlockPos anchor, List<PathFinder.PathNode> path) {
        if (start == null || anchor == null || path == null || path.isEmpty()) {
            return null;
        }
        BlockPos best = null;
        double accumulated = 0.0D;
        BlockPos prev = start;
        for (PathFinder.PathNode node : path) {
            if (node == null || node.pos == null) {
                continue;
            }
            BlockPos current = node.pos.toImmutable();
            if (current.equals(start)) {
                continue;
            }
            accumulated += Math.sqrt(prev.getSquaredDistance(current));
            best = current;
            prev = current;
            if (accumulated >= MAX_SEGMENT_PROGRESS) {
                break;
            }
        }
        if (best == null || best.equals(start)) {
            return null;
        }
        double progress = Math.sqrt(start.getSquaredDistance(best));
        if (progress < MIN_SEGMENT_PROGRESS && !best.equals(anchor)) {
            return null;
        }
        return best;
    }

    private static SunsetAnchor resolvePrimaryHomeAnchor(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }
        BlockPos home = resolveSunsetHomeTarget(bot, world);
        if (home != null) {
            return new SunsetAnchor(home.toImmutable(), AnchorKind.HOME, "home");
        }
        BlockPos spawn = resolveSpawnPoint(world);
        return new SunsetAnchor(spawn.toImmutable(), AnchorKind.SPAWN, "spawn");
    }

    private static SunsetAnchor resolveLocalSurvivalOverride(ServerPlayerEntity bot,
                                                             ServerWorld world,
                                                             SunsetSession session,
                                                             long serverTick) {
        if (bot == null || world == null || session == null) {
            return null;
        }

        BlockPos validatedShelter = BotFleeService.getValidatedShelterStandPos(bot);
        if (validatedShelter != null) {
            if (SurvivalRecruitmentService.isVillageSignalNearby(world, validatedShelter)) {
                return new SunsetAnchor(validatedShelter.toImmutable(), AnchorKind.VILLAGE_HOUSE, "validated_village");
            }
            return new SunsetAnchor(validatedShelter.toImmutable(), AnchorKind.TACTICAL_SHELTER, "validated_local");
        }

        boolean recentSurfaceFailure = BotFleeService.hadRecentSurfaceRecoveryFailure(
                bot.getUuid(),
                serverTick,
                RECENT_SURFACE_FAILURE_TICKS);
        boolean noProgress = (serverTick - session.lastProgressServerTick) >= NO_PROGRESS_FAILOVER_TICKS;
        boolean depthTrapped = isCliffTrappedAgainstAnchor(bot, session.activeAnchor);
        boolean localOverride = recentSurfaceFailure
                || (noProgress && depthTrapped)
                || (depthTrapped && session.routeFailures >= 2 && !hasUsablePickaxe(bot));
        if (!localOverride) {
            return null;
        }

        BlockPos fallbackCenter = chooseLocalShelterSearchCenter(bot);
        BlockPos villageHouse = BotFleeService.findNearbyVillageHouseAnchor(
                bot,
                world,
                fallbackCenter,
                BotWaterEscapeService.isInWater(bot) ? 28 : 18);
        if (villageHouse != null) {
            return new SunsetAnchor(villageHouse.toImmutable(), AnchorKind.VILLAGE_HOUSE, "village_house");
        }

        if (session.selfSufficient) {
            SunsetAnchor fallback = resolveSelfSufficientFallback(bot, world, session.primaryHome);
            if (fallback != null && fallback.pos() != null) {
                return fallback;
            }
        }

        if (!recentSurfaceFailure) {
            return null;
        }
        return new SunsetAnchor(bot.getBlockPos().toImmutable(), AnchorKind.TACTICAL_SHELTER, "local_reassess");
    }

    private static SunsetAnchor resolveSelfSufficientFallback(ServerPlayerEntity bot, ServerWorld world, BlockPos primaryHome) {
        if (bot == null || world == null) {
            return null;
        }

        Optional<BlockPos> base = BotHomeService.findNearestBase(bot);
        if (base.isPresent() && !base.get().equals(primaryHome)) {
            return new SunsetAnchor(base.get().toImmutable(), AnchorKind.BASE, "base");
        }

        Optional<BlockPos> bed = BotHomeService.getLastSleep(bot);
        if (bed.isPresent() && !bed.get().equals(primaryHome)) {
            return new SunsetAnchor(bed.get().toImmutable(), AnchorKind.BED, "bed");
        }

        MinecraftServer server = world.getServer();
        if (server != null) {
            ServerPlayerEntity commander = CompanionCommunicationPolicy.resolveController(server, bot);
            if (commander != null
                    && !commander.isRemoved()
                    && commander.getEntityWorld() == world
                    && bot.squaredDistanceTo(Vec3d.ofCenter(commander.getBlockPos())) <= COMMANDER_FALLBACK_RANGE_SQ) {
                BlockPos safe = SafePositionService.findForwardSafeSpot(world, commander);
                if (safe == null) {
                    safe = SafePositionService.findSafeNear(world, commander.getBlockPos(), 4);
                }
                if (safe == null) {
                    safe = commander.getBlockPos();
                }
                return new SunsetAnchor(safe.toImmutable(), AnchorKind.COMMANDER, "commander");
            }

            UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
            if (ownerUuid != null) {
                ServerPlayerEntity ally = BotEventHandler.getRegisteredBots(server).stream()
                        .filter(other -> other != null
                                && other != bot
                                && !other.isRemoved()
                                && other.getEntityWorld() == world
                                && ownerUuid.equals(BotTerritoryAuthorizationService.resolveBotOwnerUuid(other))
                                && bot.squaredDistanceTo(Vec3d.ofCenter(other.getBlockPos())) <= ALLY_FALLBACK_RANGE_SQ)
                        .filter(other -> isStableAllyAnchor(bot, other, world))
                        .min(Comparator.comparingDouble(other -> bot.squaredDistanceTo(Vec3d.ofCenter(other.getBlockPos()))))
                        .orElse(null);
                if (ally != null) {
                    BlockPos safe = SafePositionService.findSafeNear(world, ally.getBlockPos(), 4);
                    if (safe == null) {
                        safe = ally.getBlockPos();
                    }
                    return new SunsetAnchor(safe.toImmutable(), AnchorKind.ALLY_BOT, "ally");
                }
            }
        }

        BlockPos villageSearchCenter = chooseLocalShelterSearchCenter(bot);
        BlockPos villageHouse = BotFleeService.findNearbyVillageHouseAnchor(
                bot,
                world,
                villageSearchCenter,
                BotWaterEscapeService.isInWater(bot) ? 28 : 18);
        if (villageHouse != null) {
            return new SunsetAnchor(villageHouse.toImmutable(), AnchorKind.VILLAGE_HOUSE, "village_house");
        }

        BlockPos chestAnchor = BotChestRegistryService.listChestsForOwner(bot, world).stream()
                .map(BotChestRegistryService.ChestRecord::toBlockPos)
                .filter(pos -> pos != null
                        && world.isChunkLoaded(pos)
                        && bot.getBlockPos().getSquaredDistance(pos) <= CHEST_FALLBACK_RANGE_SQ
                        && isStorageBlock(world.getBlockState(pos)))
                .min(Comparator.comparingDouble(bot.getBlockPos()::getSquaredDistance))
                .orElse(null);
        if (chestAnchor != null) {
            BlockPos safe = SafePositionService.findSafeNear(world, chestAnchor, 3);
            if (safe == null) {
                safe = chestAnchor;
            }
            return new SunsetAnchor(safe.toImmutable(), AnchorKind.CHEST, "chest");
        }

        return new SunsetAnchor(bot.getBlockPos().toImmutable(), AnchorKind.TACTICAL_SHELTER, "tactical");
    }

    private static boolean isCliffTrappedAgainstAnchor(ServerPlayerEntity bot, BlockPos anchor) {
        if (bot == null || anchor == null) {
            return false;
        }
        BlockPos botPos = bot.getBlockPos();
        int dy = anchor.getY() - botPos.getY();
        if (dy < CLIFF_TRAP_VERTICAL_GAP) {
            return false;
        }
        double horizontalSq = Math.pow(anchor.getX() - botPos.getX(), 2) + Math.pow(anchor.getZ() - botPos.getZ(), 2);
        return horizontalSq >= 9.0D;
    }

    private static boolean hasUsablePickaxe(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase();
            if (lower.contains("pickaxe")) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos chooseLocalShelterSearchCenter(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        if (!BotWaterEscapeService.isInWater(bot)) {
            return bot.getBlockPos();
        }
        BlockPos shore = BotWaterEscapeService.findNearestShoreStand(bot, 14);
        return shore != null ? shore : bot.getBlockPos();
    }

    private static boolean isStableAllyAnchor(ServerPlayerEntity bot, ServerPlayerEntity candidate, ServerWorld world) {
        if (candidate == null || bot == null || world == null) {
            return false;
        }
        if (BotFleeService.isInShelter(candidate.getUuid())) {
            return true;
        }
        if (BotHomeService.isNearAnyBase(candidate, STABLE_ANCHOR_RADIUS)
                || BotHomeService.isNearRecentSleep(candidate, STABLE_ANCHOR_RADIUS, RECENT_SLEEP_WINDOW_MS)) {
            return true;
        }
        double stableChestRadiusSq = STABLE_ANCHOR_RADIUS * STABLE_ANCHOR_RADIUS;
        for (BotChestRegistryService.ChestRecord record : BotChestRegistryService.listChestsForOwner(bot, world)) {
            if (record == null || record.destroyed) {
                continue;
            }
            BlockPos pos = record.toBlockPos();
            if (pos != null
                    && candidate.getBlockPos().getSquaredDistance(pos) <= stableChestRadiusSq
                    && world.isChunkLoaded(pos)
                    && isStorageBlock(world.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStorageBlock(BlockState state) {
        return state != null && (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL));
    }

    private static BlockPos resolveSpawnPoint(ServerWorld world) {
        if (world == null) {
            return BlockPos.ORIGIN;
        }
        try {
            var spawn = world.getSpawnPoint();
            if (spawn != null && spawn.getPos() != null) {
                return spawn.getPos();
            }
        } catch (Throwable ignored) {
        }
        return BlockPos.ORIGIN;
    }

    private static boolean supportsSleep(AnchorKind kind) {
        return kind == AnchorKind.HOME || kind == AnchorKind.BASE || kind == AnchorKind.BED;
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
