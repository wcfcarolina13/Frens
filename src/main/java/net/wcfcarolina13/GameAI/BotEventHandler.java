package net.wcfcarolina13.GameAI;

import net.wcfcarolina13.EntityUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.ServerTask;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.ItemStack;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.GameMode;
import net.minecraft.entity.EntityType;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.DangerZoneDetector.DangerZoneDetector;
import net.wcfcarolina13.Database.QTable;
import net.wcfcarolina13.Database.QTableStorage;
import net.wcfcarolina13.GameAI.services.BackgroundSweepPolicy;
import net.wcfcarolina13.GameAI.services.BotPersistenceService;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.ElytraFlightService;
import net.wcfcarolina13.GameAI.services.TreeStuckEscapeService;
import net.wcfcarolina13.GameAI.services.BotLifecycleService;
import net.wcfcarolina13.GameAI.services.BotRegistry;
import net.wcfcarolina13.GameAI.services.BotCommandStateService;
import net.wcfcarolina13.GameAI.services.DropSweepService;
import net.wcfcarolina13.GameAI.services.GuardPatrolService;
import net.wcfcarolina13.GameAI.services.HealingService;
import net.wcfcarolina13.GameAI.services.BotRescueService;
import net.wcfcarolina13.GameAI.services.BotThreatService;
import net.wcfcarolina13.GameAI.services.BotArrowRecoveryService;
import net.wcfcarolina13.GameAI.services.BotStuckService;
import net.wcfcarolina13.GameAI.services.BotRLActionService;
import net.wcfcarolina13.GameAI.services.BotRLPersistenceThrottleService;
import net.wcfcarolina13.GameAI.services.BotCombatCalloutService;
import net.wcfcarolina13.GameAI.services.BotFleeService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotAutoHuntService;
import net.wcfcarolina13.GameAI.services.BotAutoReturnSunsetService;
import net.wcfcarolina13.GameAI.services.BotMutualAidService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.Database.StateActionPair;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.Entity.LookController;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.FaceClosestEntity;
import net.wcfcarolina13.LauncherDetection.LauncherEnvironment;

import java.util.ArrayList;
import net.wcfcarolina13.PlayerUtils.*;
import net.wcfcarolina13.WorldUitls.GetTime;
import net.wcfcarolina13.Entity.EntityDetails;
import net.wcfcarolina13.PathFinding.GoTo;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.FollowPathService;
import net.wcfcarolina13.GameAI.services.FollowDebugService;
import net.wcfcarolina13.GameAI.services.FollowStateService;
import net.wcfcarolina13.GameAI.services.FollowPlannerService;
import net.wcfcarolina13.GameAI.services.FollowStateService.FollowDoorPlan;
import net.wcfcarolina13.GameAI.services.FollowStateService.FollowDoorRecovery;
import net.wcfcarolina13.GameAI.services.FollowStateService.VerticalClimbLock;
import net.wcfcarolina13.GameAI.services.FollowMovementService;
import net.wcfcarolina13.GameAI.services.SharedStateService;
import net.wcfcarolina13.GameAI.services.CompanionSafeZoneService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.services.follow.FollowVerticalAssistPolicyUtil;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.WorldUitls.isBlockItem;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static net.wcfcarolina13.GameAI.State.isStateConsistent;
import static net.wcfcarolina13.GameAI.services.FollowStateService.*;


public class BotEventHandler {
    public static final Logger LOGGER = LoggerFactory.getLogger("frens");
    private static MinecraftServer server = null;
    public static ServerPlayerEntity bot = null;
    private static final boolean DEBUG_RL = false;
    // Stage-2 refactor: primary bot selection moved to BotLifecycleService.
    public static final String qTableDir = LauncherEnvironment.getStorageDirectory("qtable_storage");
    private static final Object monitorLock = new Object();
    private static boolean isExecuting = false;
    private static final double DEFAULT_RISK_APPETITE = 0.5; // Default value upon respawn
    public static boolean botDied = false; // Flag to track if the bot died
    public static boolean hasRespawned = false; // flag to track if the bot has respawned before or not
    public static int botSpawnCount = 0;
    // Stage-2 refactor: last spawn state moved to BotLifecycleService.
    private static State currentState = null;
    private static final Random RANDOM = new Random();
    // Stage-2 refactor: drop-sweep state moved to DropSweepService.
    // Stage-2 refactor: burial/suffocation rescue moved to BotRescueService.
    // Stage-2 refactor: follow/come state maps moved to FollowStateService.
    private static final long FOLLOW_SEALED_STATE_TTL_MS = 1_000L;
    private static final double FOLLOW_PERSONAL_SPACE = 3.0D; // prefer at least ~3 block gap
    private static final double FOLLOW_BACKUP_DISTANCE = 1.05D; // trigger backup after linger
    private static final long FOLLOW_BACKUP_TRIGGER_MS = 3_000L;
    private static final double FOLLOW_SPRINT_DISTANCE_SQ = 4.0D; // >2 blocks -> sprint
    private static final double FOLLOW_TELEPORT_DISTANCE_SQ = 225.0D; // ~15 blocks
    private static final int FOLLOW_TELEPORT_STUCK_TICKS = 30; // ~1.5 seconds @20tps
    private static final int FOLLOW_TELEPORT_COOLDOWN_TICKS = 40; // 2 seconds @20tps
    private static final long FOLLOW_POST_DOOR_AVOID_MS = 6_000L;
    // Door-plan stuck abort: if bot's block hasn't changed in this many ticks, give up on the
    // door plan and let generic follow (direct pursuit / wolf-teleport) take over. Larger than
    // normal walking-through-door transit time so we don't false-trigger while the bot is
    // actively moving through the doorway (a 1-block transit takes ~5-8 ticks at follow speed).
    private static final int  FOLLOW_DOOR_STUCK_ABORT_TICKS = 24;
    private static final long FOLLOW_DOOR_ABORT_AVOID_MS = 12_000L;
    // Stuck-jump trigger: after ~0.4s of no block-position change while ticking a door plan,
    // force a jump each tick. Slabs/stairs/trapdoors at head level block horizontal motion
    // but are cleared by a simple hop. Same remedy as commander's own jump nudging the bot.
    private static final int  FOLLOW_DOOR_STUCK_JUMP_TICKS = FollowDoorRules.STUCK_JUMP_TICKS;
    private static final double COME_REACHABILITY_PROBE_RANGE_SQ = 32.0D * 32.0D;
    private static final long COME_REACHABILITY_PROBE_TIMEOUT_MS = 60L;
    private static final long COME_REACHABILITY_PROBE_COOLDOWN_TICKS = 80L;
    private static final long FOLLOW_BACKOFF_LOG_COOLDOWN_TICKS = 100L;
    private static final long FOLLOW_VERTICAL_ASSIST_COOLDOWN_MS = 350L;
    private static final long FOLLOW_VERTICAL_LOCK_TTL_MS = 8_000L;
    private static final int FOLLOW_VERTICAL_LOCK_NO_PROGRESS_TICKS = 30;
    private static final int FOLLOW_VERTICAL_LOCK_HARD_FAIL_TICKS = 80;
    private static final int FOLLOW_VERTICAL_LOCK_NO_DOOR_ABORT_TICKS = 12;
    private static final int FOLLOW_CLIMB_EXIT_STAGNANT_TICKS = 8;
    private static final long FOLLOW_VERTICAL_LOCK_REPLAN_COOLDOWN_MS = 1_500L;
    private static final long FOLLOW_VERTICAL_LOCK_FAIL_COOLDOWN_MS = 4_000L;
    private static final int FOLLOW_VERTICAL_DOOR_LOOP_BREAK_STREAK = 4;
    private static final long FOLLOW_VERTICAL_DOOR_LOOP_BREAK_COOLDOWN_MS = 2_500L;
    private static final long FOLLOW_COMMANDER_LADDER_HINT_TTL_MS = 8_000L;
    private static final long FOLLOW_COMMANDER_LADDER_OFF_GRACE_MS = 4_000L;
    private static final long COME_RECOVERY_STALE_TICKS = 200L; // 10s @20tps
    private static final Map<UUID, Long> FOLLOW_BACKOFF_LOG_TICK = new ConcurrentHashMap<>();
    private static final AtomicInteger COME_RECOVERY_THREAD_ID = new AtomicInteger(0);
    private static volatile ExecutorService COME_RECOVERY_EXECUTOR = createComeRecoveryExecutor();

    private static ExecutorService createComeRecoveryExecutor() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "come-recovery-" + COME_RECOVERY_THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** Interrupt all in-flight come-recovery tasks. Called during server shutdown. */
    public static void shutdownExecutors() {
        COME_RECOVERY_EXECUTOR.shutdownNow();
    }

    /** Re-create the come-recovery executor if it was shut down. Called from {@code SERVER_STARTED}. */
    public static void restartExecutors() {
        if (COME_RECOVERY_EXECUTOR.isShutdown()) {
            COME_RECOVERY_EXECUTOR = createComeRecoveryExecutor();
        }
    }

    private static BlockPos currentAvoidDoor(UUID botId) {
        return FollowStateService.currentAvoidDoor(botId);
    }

    private static void avoidDoorFor(UUID botId, BlockPos doorBase, long durationMs, String reason) {
        if (botId == null || doorBase == null) {
            return;
        }
        FollowStateService.avoidDoorFor(botId, doorBase, Math.max(500L, durationMs));
        ServerPlayerEntity bot = server != null ? server.getPlayerManager().getPlayer(botId) : null;
        if (bot != null) {
            maybeLogFollowDecision(bot, "avoid-door: doorBase=" + doorBase.toShortString()
                    + " durationMs=" + durationMs
                    + " reason=" + (reason == null ? "" : reason));
        }
    }

    private static boolean isNearRecentlyCrossedDoor(UUID botId, BlockPos doorBase, long windowMs, double radiusSq) {
        if (botId == null || doorBase == null) {
            return false;
        }
        long lastDoorMs = FollowStateService.FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(botId, -1L);
        BlockPos lastDoor = FollowStateService.FOLLOW_LAST_DOOR_BASE.get(botId);
        if (lastDoorMs < 0 || lastDoor == null) {
            return false;
        }
        if ((System.currentTimeMillis() - lastDoorMs) > windowMs) {
            return false;
        }
        return lastDoor.getSquaredDistance(doorBase) <= radiusSq;
    }

    public static void noteObstructDamage(ServerPlayerEntity bot) {
        BotRescueService.noteObstructDamage(bot);
    }

    // Stage-2 refactor: per-bot command state moved to BotCommandStateService.
    private static final Map<UUID, Long> LAST_RL_SAMPLE_TICK = new ConcurrentHashMap<>();

    /** Per-bot creeper-flee progress tracking — when distance isn't improving, force shield. */
    private record CreeperFleeMemory(UUID creeperUuid, double lastDistance, long lastTick, int stuckTicks) {}
    private static final Map<UUID, CreeperFleeMemory> CREEPER_FLEE_STATE = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> FOLLOW_LAST_VERTICAL_ASSIST_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_VERTICAL_DOOR_LOOP_LAST_BASE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FOLLOW_VERTICAL_DOOR_LOOP_STREAK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_VERTICAL_DOOR_LOOP_LAST_BREAK_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_VERTICAL_LOCK_LAST_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_VERTICAL_LOCK_FAIL_COOLDOWN_UNTIL_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, CommanderLadderHint> FOLLOW_COMMANDER_LADDER_HINT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_JOIN_ENCLOSURE_CHECK_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_ARMOR_AUDIT_TICK = new ConcurrentHashMap<>();
    private static final long ARMOR_AUDIT_COOLDOWN_TICKS = 60L; // 3 seconds at 20 tps
    /** Per-bot current combat target UUID for threat-scoring stickiness. */
    private static final Map<UUID, UUID> COMBAT_TARGET = new ConcurrentHashMap<>();
    /** Teleport detection: last known position per bot, updated each tick. */
    private static final Map<UUID, Vec3d> TELEPORT_DETECT_LAST_POS = new ConcurrentHashMap<>();
    /** Grace period after external teleport: suppress surface recovery until this tick. */
    private static final Map<UUID, Long> TELEPORT_GRACE_UNTIL_TICK = new ConcurrentHashMap<>();
    /** Grace period after /bot stop: suppress join-enclosure check so it doesn't launch break-free. */
    private static final Map<UUID, Long> STOP_COMMAND_GRACE_UNTIL_TICK = new ConcurrentHashMap<>();
    /** Minimum squared distance between ticks to consider as an external teleport. 16 blocks = 256. */
    private static final double EXTERNAL_TELEPORT_THRESHOLD_SQ = 256.0;
    // Stage-2 refactor: burial/suffocation rescue moved to BotRescueService.
    private static volatile boolean externalOverrideActive = false;
    // Stage-2 refactor: lifecycle respawn flag moved to BotLifecycleService.
    public enum CombatStyle {
        AGGRESSIVE,
        EVASIVE
    }
    private static CombatStyle combatStyle = CombatStyle.AGGRESSIVE;

    public enum Mode {
        IDLE,
        FOLLOW,
        GUARD,
        PATROL,
        STAY,
        RETURNING_BASE,
        TRAVELING
    }
    private static long lastRespawnHandledTick = -1;

    private static void debugRL(String message) {
        if (DEBUG_RL) {
            LOGGER.debug(message);
        }
    }

    public static boolean throttleTraining(ServerPlayerEntity bot, boolean urgent) {
        if (bot == null || bot.getCommandSource().getServer() == null) {
            return true;
        }
        int interval = urgent ? 2 : 20; // urgent ~0.1s, passive ~1s (assuming 20tps)
        long now = bot.getCommandSource().getServer().getTicks();
        long last = LAST_RL_SAMPLE_TICK.getOrDefault(bot.getUuid(), -1L);
        if (last >= 0 && (now - last) < interval) {
            return false;
        }
        LAST_RL_SAMPLE_TICK.put(bot.getUuid(), now);
        return true;
    }

    private static BotCommandStateService.State stateFor(ServerPlayerEntity bot) {
        return BotCommandStateService.stateFor(bot);
    }

    private static BotCommandStateService.State stateFor(UUID uuid) {
        return BotCommandStateService.stateFor(uuid);
    }

    private static BotCommandStateService.State primaryState() {
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (primaryUuid != null) {
            return stateFor(primaryUuid);
        }
        Iterator<UUID> iterator = BotRegistry.ids().iterator();
        if (iterator.hasNext()) {
            return stateFor(iterator.next());
        }
        return null;
    }

    private static void setMode(ServerPlayerEntity bot, Mode mode) {
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.mode = mode;
        }
        // Any mode change means an active command — break free from shelter if needed
        if (bot != null) {
            BotFleeService.clearShelterAndBreakFree(bot);
        }
        if (bot != null && mode != Mode.FOLLOW) {
            FOLLOW_COMMANDER_LADDER_HINT.remove(bot.getUuid());
        }
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (bot != null && primaryUuid != null && bot.getUuid().equals(primaryUuid)) {
            BotCommandStateService.State primary = primaryState();
            if (primary != null) {
                primary.mode = mode;
            }
        }
    }

    private static Mode getMode(ServerPlayerEntity bot) {
        BotCommandStateService.State state = stateFor(bot);
        return state != null ? state.mode : Mode.IDLE;
    }

    /** Public accessor for {@link #getMode}. */
    public static Mode getModePublic(ServerPlayerEntity bot) {
        return getMode(bot);
    }

    /**
     * Force the bot into {@link Mode#IDLE}.
     *
     * <p>Intended for user-facing "resume idle" and post-action auto-resume.
     * Does not abort tasks; callers should check {@link net.wcfcarolina13.GameAI.services.TaskService#hasActiveTask} first.
     */
    public static boolean setIdleMode(ServerPlayerEntity bot, boolean silent) {
        if (bot == null || bot.isRemoved()) {
            return false;
        }

        // Clear any directed mode targets.
        setFollowTarget(bot, null);
        setBaseTarget(bot, null);
        clearGuard(bot);

        Mode prior = getMode(bot);
        setMode(bot, Mode.IDLE);
        setAssistAllies(bot, true);

        if (!silent && prior != Mode.IDLE) {
            sendBotMessage(bot, "Back to idling.");
        }
        return prior != Mode.IDLE;
    }

    public static String setIdleMode(ServerPlayerEntity bot) {
        boolean ok = setIdleMode(bot, true);
        if (bot == null) {
            return "No bot selected.";
        }
        return ok ? (bot.getName().getString() + " is now IDLE.") : (bot.getName().getString() + " is already IDLE.");
    }

    private static void setFollowTarget(ServerPlayerEntity bot, UUID targetUuid) {
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followTargetUuid = targetUuid;
        }
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (bot != null && primaryUuid != null && bot.getUuid().equals(primaryUuid)) {
            BotCommandStateService.State primary = primaryState();
            if (primary != null) {
                primary.followTargetUuid = targetUuid;
            }
        }
    }

    private static UUID getFollowTargetFor(ServerPlayerEntity bot) {
        BotCommandStateService.State state = stateFor(bot);
        return state != null ? state.followTargetUuid : null;
    }

    private static void clearState(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        BotCommandStateService.clear(bot.getUuid());
    }

    private static void setGuardState(ServerPlayerEntity bot, Vec3d center, double radius) {
        if (bot == null) {
            return;
        }
        GuardPatrolService.setGuardState(bot.getUuid(), center, radius);
    }

    private static Vec3d getGuardCenter(ServerPlayerEntity bot) {
        return bot == null ? null : GuardPatrolService.getGuardCenter(bot.getUuid());
    }

    private static double getGuardRadius(ServerPlayerEntity bot) {
        return bot == null ? 6.0D : GuardPatrolService.getGuardRadius(bot.getUuid());
    }

    private static double getPatrolRadius(ServerPlayerEntity bot) {
        return bot == null ? 6.0D : GuardPatrolService.getPatrolRadius(bot.getUuid());
    }

    private static void setBaseTarget(ServerPlayerEntity bot, Vec3d base) {
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.baseTarget = base;
        }
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (bot != null && primaryUuid != null && bot.getUuid().equals(primaryUuid)) {
            BotCommandStateService.State primary = primaryState();
            if (primary != null) {
                primary.baseTarget = base;
            }
        }
    }

    private static void clearGuard(ServerPlayerEntity bot) {
        setGuardState(bot, null, getGuardRadius(bot));
    }

    private static void clearBase(ServerPlayerEntity bot) {
        setBaseTarget(bot, null);
    }

    public static Vec3d getBaseTarget(ServerPlayerEntity bot) {
        BotCommandStateService.State state = stateFor(bot);
        return state != null ? state.baseTarget : null;
    }

    /**
     * Check if the bot is currently in "return to base" mode.
     * Return-to-base uses Mode.FOLLOW internally (for door/waypoint planning)
     * but has state.baseTarget and state.followFixedGoal set.
     * 
     * @return true if the bot is actively returning to its base
     */
    public static boolean isReturningToBase(ServerPlayerEntity bot) {
        if (bot == null) return false;
        BotCommandStateService.State state = stateFor(bot);
        if (state == null) return false;
        // Return-to-base uses FOLLOW mode with baseTarget + followFixedGoal set
        return state.mode == Mode.FOLLOW 
                && state.baseTarget != null 
                && state.followFixedGoal != null;
    }

    /**
     * Check if the bot is actively following a player (not returning to base).
     * This excludes return-to-base mode which also uses Mode.FOLLOW internally.
     * 
     * @return true if the bot is following a player entity
     */
    public static boolean isFollowingPlayer(ServerPlayerEntity bot) {
        if (bot == null) return false;
        BotCommandStateService.State state = stateFor(bot);
        if (state == null) return false;
        // Following a player: FOLLOW mode with followTargetUuid set (not baseTarget)
        return state.mode == Mode.FOLLOW 
                && state.followTargetUuid != null 
                && state.baseTarget == null;
    }

    private static void setAssistAllies(ServerPlayerEntity bot, boolean enable) {
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.assistAllies = enable;
        }
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (bot != null && primaryUuid != null && bot.getUuid().equals(primaryUuid)) {
            BotCommandStateService.State primary = primaryState();
            if (primary != null) {
                primary.assistAllies = enable;
            }
        }
    }

    private static boolean isAssistAllies(ServerPlayerEntity bot) {
        BotCommandStateService.State state = stateFor(bot);
        return state != null && state.assistAllies;
    }

    private static void setShieldRaised(ServerPlayerEntity bot, boolean raised) {
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.shieldRaised = raised;
        }
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (bot != null && primaryUuid != null && bot.getUuid().equals(primaryUuid)) {
            BotCommandStateService.State primary = primaryState();
            if (primary != null) {
                primary.shieldRaised = raised;
            }
        }
    }

    private static boolean isShieldRaised(ServerPlayerEntity bot) {
        BotCommandStateService.State state = stateFor(bot);
        return state != null && state.shieldRaised;
    }

    private static long getShieldDecisionTick(ServerPlayerEntity bot) {
        BotCommandStateService.State state = stateFor(bot);
        return state != null ? state.shieldDecisionTick : 0L;
    }

    private static void setShieldDecisionTick(ServerPlayerEntity bot, long tick) {
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.shieldDecisionTick = tick;
        }
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (bot != null && primaryUuid != null && bot.getUuid().equals(primaryUuid)) {
            BotCommandStateService.State primary = primaryState();
            if (primary != null) {
                primary.shieldDecisionTick = tick;
            }
        }
    }

    public BotEventHandler(MinecraftServer server, ServerPlayerEntity bot) {
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (server != null && bot != null && (primaryUuid == null || primaryUuid.equals(bot.getUuid()))) {
            registerBot(bot);
        }
    }

    public static void setExternalOverrideActive(boolean active) {
        if (externalOverrideActive == active) {
            return;
        }
        externalOverrideActive = active;
        if (active) {
            LOGGER.info("External override activated; pausing training loop.");
        } else {
            LOGGER.info("External override cleared; training loop may resume.");
        }
    }

    public static boolean isExternalOverrideActive() {
        return externalOverrideActive;
    }

    public static void rememberSpawn(ServerWorld world, Vec3d pos, float yaw, float pitch) {
        BotLifecycleService.rememberSpawn(world, pos, yaw, pitch);
    }

    public static void ensureBotPresence(MinecraftServer srv) {
        // Survival recruitment mode: do not conjure bots into existence before the player recruits in a village.
        if (srv != null
                && net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.isEnabled(srv)
                && !net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.isWorldRecruited(srv)) {
            return;
        }

        // Survival recruitment mode: if the recruited companion is marked dead, do not auto-respawn them.
        // Resurrection should be performed via the ritual flow.
        if (srv != null && net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.isEnabled(srv)) {
            try {
                net.wcfcarolina13.FilingSystem.ManualConfig.SurvivalRecruitmentState st =
                        net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.getState(srv);
                if (st != null && st.isRecruited() && st.isCompanionDead()) {
                    return;
                }
            } catch (Throwable ignored) {
                // If state lookup fails, fall back to legacy behavior.
            }
        }
        String lastBotName = BotLifecycleService.getLastBotName();
        if (srv == null || lastBotName == null) {
            return;
        }
        ServerPlayerEntity existing = null;
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (primaryUuid != null) {
            existing = srv.getPlayerManager().getPlayer(primaryUuid);
        }
        if (existing == null) {
            Iterator<UUID> iterator = BotRegistry.ids().iterator();
            while (existing == null && iterator.hasNext()) {
                UUID candidateId = iterator.next();
                existing = srv.getPlayerManager().getPlayer(candidateId);
                if (existing != null) {
                    BotLifecycleService.setPrimaryBotUuid(candidateId);
                }
            }
        }
        if (existing == null) {
            existing = srv.getPlayerManager().getPlayer(lastBotName);
        }
        if (existing != null) {
            registerBot(existing);
            return;
        }
        if (BotLifecycleService.isPendingBotRespawn()) {
            return;
        }

        BotLifecycleService.SpawnSnapshot snapshot = BotLifecycleService.getLastSpawn();
        RegistryKey<World> worldKey = (snapshot != null && snapshot.worldKey() != null) ? snapshot.worldKey() : World.OVERWORLD;
        ServerWorld world = srv.getWorld(worldKey);
        if (world == null) {
            world = srv.getOverworld();
            worldKey = World.OVERWORLD;
        }
        Vec3d spawn = snapshot != null ? snapshot.position() : null;
        if (spawn == null) {
            double centerX = world.getWorldBorder().getCenterX();
            double centerZ = world.getWorldBorder().getCenterZ();
            int spawnX = (int) Math.round(centerX);
            int spawnZ = (int) Math.round(centerZ);
            int spawnY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, spawnX, spawnZ);
            spawn = new Vec3d(spawnX + 0.5, spawnY, spawnZ + 0.5);
        }
        final RegistryKey<World> targetWorld = worldKey;
        final Vec3d spawnPos = spawn;
        final float yaw = snapshot != null ? snapshot.yaw() : 0.0F;
        final float pitch = snapshot != null ? snapshot.pitch() : 0.0F;
        BotLifecycleService.setPendingBotRespawn(true);
        UUID targetUuid = BotLifecycleService.getPrimaryBotUuid();
        srv.execute(() -> {
            if (targetUuid != null) {
                TaskService.forceAbort(targetUuid, "§cRestoring bot after owner respawn.");
            } else {
                TaskService.forceAbort("§cRestoring bot after owner respawn.");
            }
            createFakePlayer.createFake(lastBotName, srv, spawnPos, yaw, pitch, targetWorld, GameMode.SURVIVAL, false);
        });
    }

    public static void registerBot(ServerPlayerEntity candidate) {
        if (candidate == null) {
            return;
        }
        BotRegistry.register(candidate.getUuid());
        BotLifecycleService.setPrimaryBotUuid(candidate.getUuid());
        BotEventHandler.bot = candidate;
        stateFor(candidate);
        MinecraftServer srv = candidate.getCommandSource().getServer();
        if (srv != null && (BotEventHandler.server == null || BotEventHandler.server == srv)) {
            BotEventHandler.server = srv;
        }
        BotLifecycleService.setLastBotName(candidate.getName().getString());
        if (candidate.getEntityWorld() instanceof ServerWorld serverWorld) {
            rememberSpawn(serverWorld, new Vec3d(candidate.getX(), candidate.getY(), candidate.getZ()), candidate.getYaw(), candidate.getPitch());
        }
        BotLifecycleService.setPendingBotRespawn(false);
        net.wcfcarolina13.GameAI.services.BotControlApplier.applyToBot(candidate);
        
        // Proactively check if bot spawned inside blocks and needs to mine out
        if (srv != null) {
            srv.execute(() -> {
                // Immediate check (tick+5): spawn-in-block suffocation
                srv.send(new net.minecraft.server.ServerTask(srv.getTicks() + 5, () -> {
                    if (!candidate.isRemoved()) {
                        checkForSpawnInBlocks(candidate);
                    }
                }));
                // Delayed check (tick+40, ~2 seconds): if bot hasn't moved and has no sky,
                // it's likely trapped (shelter from previous session, cave, etc.)
                final Vec3d spawnPos = new Vec3d(candidate.getX(), candidate.getY(), candidate.getZ());
                srv.send(new net.minecraft.server.ServerTask(srv.getTicks() + 40, () -> {
                    if (candidate.isRemoved()) return;
                    if (!(candidate.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw)) return;
                    if (net.wcfcarolina13.GameAI.services.TaskService.isServerStopping()) return;
                    Long lastJoinCheck = LAST_JOIN_ENCLOSURE_CHECK_TICK.get(candidate.getUuid());
                    if (lastJoinCheck != null && (srv.getTicks() - lastJoinCheck) < 200L) return;
                    LAST_JOIN_ENCLOSURE_CHECK_TICK.put(candidate.getUuid(), (long) srv.getTicks());
                    Mode liveMode = getCurrentMode(candidate);
                    if (liveMode != Mode.IDLE) return;
                    if (getFollowTargetUuid(candidate) != null || getBaseTarget(candidate) != null) return;
                    if (net.wcfcarolina13.GameAI.services.TaskService.hasActiveTask(candidate.getUuid())) return;
                    if (net.wcfcarolina13.GameAI.skills.SkillManager.shouldAbortSkill(candidate)) return;
                    String rideSuppression = net.wcfcarolina13.GameAI.services.RideSyncService
                            .getJoinEnclosureSuppressionReason(candidate, srv.getTicks());
                    if (rideSuppression != null) {
                        LOGGER.info("Bot {} join enclosure check suppressed: {}",
                                candidate.getName().getString(),
                                rideSuppression);
                        return;
                    }
                    // Suppress enclosure check during teleport grace period (e.g., arrived via fast-travel)
                    if (isInTeleportGracePeriod(candidate)) return;
                    // Suppress enclosure check after /bot stop — the player intentionally stopped the bot;
                    // launching break-free would undermine the stop command.
                    Long stopGrace = STOP_COMMAND_GRACE_UNTIL_TICK.get(candidate.getUuid());
                    if (stopGrace != null && srv.getTicks() < stopGrace) return;
                    // Suppress enclosure check when near a registered base — underground bases
                    // always look "enclosed" but the bot is at home, not trapped.
                    if (net.wcfcarolina13.GameAI.services.BotHomeService.isNearAnyBase(candidate, 32.0D)) {
                        LOGGER.info("Bot {} join enclosure check suppressed: near registered base",
                                candidate.getName().getString());
                        return;
                    }
                    // Use isAtSurface (heightmap + wide sky check) instead of raw isSkyVisible —
                    // previous mining sessions can create skylights that fool isSkyVisible.
                    if (BotFleeService.isAtSurface(candidate, sw)) return;
                    // Suppress if commander is nearby — the bot was likely left here
                    // intentionally (e.g., after a stripmine session). The linger
                    // decision tree will handle surface recovery with proper context.
                    ServerPlayerEntity cmdr = net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy
                            .resolveController(srv, candidate);
                    if (cmdr != null && !cmdr.isRemoved()
                            && cmdr.getEntityWorld() == candidate.getEntityWorld()
                            && cmdr.squaredDistanceTo(candidate) < 48.0 * 48.0) {
                        LOGGER.info("Bot {} join enclosure check suppressed: commander {} nearby ({} blocks)",
                                candidate.getName().getString(), cmdr.getName().getString(),
                                String.format("%.0f", Math.sqrt(cmdr.squaredDistanceTo(candidate))));
                        return;
                    }
                    double movedSq = candidate.squaredDistanceTo(spawnPos);
                    if (movedSq < 4.0) { // hasn't moved more than 2 blocks
                        // At night, stay sheltered — don't break free into danger
                        if (!sw.isDay() && !sw.isThundering()) {
                            LOGGER.info("Bot {} enclosed on join but nighttime — staying sheltered until dawn",
                                    candidate.getName().getString());
                            if (!BotFleeService.setShelterFromJoin(candidate)) {
                                LOGGER.info("Bot {} join enclosure failed validation — launching local recovery instead",
                                        candidate.getName().getString());
                                BotFleeService.forceBreakFree(candidate);
                            }
                        } else {
                            LOGGER.info("Bot {} trapped on join — hasn't moved ({} blocks) and no sky. Launching break-free.",
                                    candidate.getName().getString(), String.format("%.1f", Math.sqrt(movedSq)));
                            BotFleeService.forceBreakFree(candidate);
                        }
                    }
                }));
            });
        }
    }

    public static void unregisterBot(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        net.wcfcarolina13.GameAI.services.RideSyncService.clearBotState(uuid);
        BotRegistry.unregister(uuid);
        net.wcfcarolina13.GameAI.services.DurabilityFallbackService.clearCooldowns(uuid);
        BotPersistenceService.removeBot(bot);
        clearState(bot);
        LAST_RL_SAMPLE_TICK.remove(uuid);
        LAST_JOIN_ENCLOSURE_CHECK_TICK.remove(uuid);
        LAST_ARMOR_AUDIT_TICK.remove(uuid);
        UUID primaryUuid = BotLifecycleService.getPrimaryBotUuid();
        if (primaryUuid != null && primaryUuid.equals(uuid)) {
            BotLifecycleService.setPrimaryBotUuid(null);
            Iterator<UUID> iterator = BotRegistry.ids().iterator();
            if (iterator.hasNext()) {
                BotLifecycleService.setPrimaryBotUuid(iterator.next());
            }
        }
        if (BotEventHandler.bot != null && BotEventHandler.bot.getUuid().equals(uuid)) {
            BotEventHandler.bot = null;
        }
    }

    public static boolean isRegisteredBot(ServerPlayerEntity candidate) {
        return candidate != null && BotRegistry.isRegistered(candidate.getUuid());
    }

    /** Check if the bot is within the post-teleport grace period (suppresses surface recovery). */
    public static boolean isInTeleportGracePeriod(ServerPlayerEntity bot) {
        if (bot == null || server == null) return false;
        Long until = TELEPORT_GRACE_UNTIL_TICK.get(bot.getUuid());
        return until != null && server.getTicks() < until;
    }

    /** Record that /bot stop was just issued — suppresses the join-enclosure check for 60 ticks (3s). */
    public static void noteStopCommand(UUID botUuid) {
        if (botUuid == null || server == null) return;
        STOP_COMMAND_GRACE_UNTIL_TICK.put(botUuid, (long) server.getTicks() + 60L);
    }

    /** Returns true if the bot is within the stop-command grace window (60 ticks after /bot stop). */
    public static boolean isInStopCommandGrace(UUID botUuid) {
        if (botUuid == null || server == null) return false;
        Long grace = STOP_COMMAND_GRACE_UNTIL_TICK.get(botUuid);
        return grace != null && server.getTicks() < grace;
    }

    /** Called after fast-travel arrival so the teleport detector doesn't see the spawn-to-destination jump. */
    public static void notifyTravelArrival(UUID botUuid, Vec3d arrivalPos, long currentTick) {
        if (botUuid == null || arrivalPos == null) return;
        TELEPORT_DETECT_LAST_POS.put(botUuid, arrivalPos);
        TELEPORT_GRACE_UNTIL_TICK.put(botUuid, currentTick + 40L);
    }

    public static List<ServerPlayerEntity> getRegisteredBots(MinecraftServer fallback) {
        MinecraftServer srv = server != null ? server : fallback;
        if (srv == null) {
            return List.of();
        }
        List<ServerPlayerEntity> bots = new ArrayList<>();
        for (UUID uuid : BotRegistry.ids()) {
            ServerPlayerEntity player = srv.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                bots.add(player);
            }
        }
        return bots;
    }

    private static State initializeBotState(QTable qTable) {
        State initialState = null;

        if (qTable == null || qTable.getTable().isEmpty()) {
            debugRL("No initial state available. Q-table is empty.");
        } else {
            debugRL("Loaded Q-table: Total state-action pairs = " + qTable.getTable().size());

            // Get the most recent state from the Q-table
            StateActionPair recentPair = qTable.getTable().keySet().iterator().next();
            initialState = recentPair.getState();

            debugRL("Setting initial state to: " + initialState);
        }

        return initialState;
    }

    public void detectAndReact(RLAgent rlAgentHook, double distanceToHostileEntity, QTable qTable) throws IOException {
        if (!net.wcfcarolina13.Commands.modCommandRegistry.enableReinforcementLearning) {
            return;
        }
        if (bot != null) {
            engageImmediateThreats(bot);
        }
        if (externalOverrideActive) {
            LOGGER.debug("Skipping detectAndReact because external override is active.");
            return;
        }
        // Never let the RL/training loop fight player-issued commands (follow/skills/guard/etc).
        // This avoids “bot looks busy” stalls where training actions cancel commanded movement.
        if (bot != null) {
            Mode mode = getMode(bot);
            if (mode != null && mode != Mode.IDLE) {
                LOGGER.debug("Skipping detectAndReact because bot is in mode={}", mode);
                return;
            }
        }
        synchronized (monitorLock) {
            if (isExecuting) {
                debugRL("Executing detection code");
                return; // Skip if already executing
            } else {
                debugRL("No immediate threats detected");
                // Reset state when no threats are detected
                BotEventHandler.currentState = createInitialState(bot);
            }
            isExecuting = true;
        }

        try {
            double dangerDistance = DangerZoneDetector.detectDangerZone(bot, 10, 10, 10);
            debugRL("Distance from danger zone: " + dangerDistance + " blocks");

            List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 10); // Example bounding box size
            List<Entity> hostileEntities = nearbyEntities.stream()
                    .filter(EntityUtil::isHostile)
                    .toList();

            LOGGER.debug("detectAndReact triggered: hostiles={}, trainingMode={}, alreadyExecuting={}",
                    hostileEntities.size(), net.wcfcarolina13.Commands.modCommandRegistry.isTrainingMode, isExecuting);

            BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);

            List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

            boolean hasSculkNearby = nearbyBlocks.stream()
                    .anyMatch(block -> block.contains("Sculk Sensor") || block.contains("Sculk Shrieker"));

            BotStuckService.EnvironmentSnapshot environmentSnapshot = BotStuckService.analyzeEnvironment(bot);
            boolean threatDetected = shouldEnterCombat(!hostileEntities.isEmpty(), dangerDistance, hasSculkNearby);
            BotStuckService.updateStuckTracker(bot, environmentSnapshot);

            debugRL("Nearby blocks: " + nearbyBlocks);

            int timeofDay = GetTime.getTimeOfWorld(bot);
            String time = (timeofDay >= 12000 && timeofDay < 24000) ? "night" : "day";

            World world = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS).getWorld();
            RegistryKey<World> dimType = world.getRegistryKey();
            String dimension = dimType.getValue().toString();

            if (!hostileEntities.isEmpty()) {
                // Combat callout: threat detected
                Entity closestThreat = hostileEntities.stream()
                        .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                        .orElse(null);
                if (closestThreat != null) {
                    net.wcfcarolina13.GameAI.services.BotCombatCalloutService.onThreatDetected(bot, closestThreat);
                }
                
                List<EntityDetails> nearbyEntitiesList = nearbyEntities.stream()
                        .map(entity -> EntityDetails.from(bot, entity))
                        .toList();

                State currentState;

                if (hasRespawned && botDied) {
                    State lastKnownState = QTableStorage.loadLastKnownState(qTableDir + "/lastKnownState.bin");
                    currentState = createInitialState(bot);
                    BotEventHandler.botDied = false;

                    if (isStateConsistent(lastKnownState, currentState)) {
                        debugRL("Merged values from last known state.");
                        currentState.setRiskMap(lastKnownState.getRiskMap());
                        currentState.setPodMap(lastKnownState.getPodMap());
                    }
                } else {
                    currentState = initializeBotState(qTable);

                    debugRL("Created initial state");
                }

                if (botSpawnCount == 0) {
                    currentState = createInitialState(bot);
                }

                performLearningStep(rlAgentHook, qTable, currentState, nearbyEntitiesList, nearbyBlocks,
                        distanceToHostileEntity, time, dimension);

            } else if ((dangerDistance > 0.0 && dangerDistance <= 5.0) || hasSculkNearby) {
                debugRL("Danger zone detected within 5 blocks");

                debugRL("Triggered handler for danger zone case.");

                List<EntityDetails> nearbyEntitiesList = nearbyEntities.stream()
                        .map(entity -> EntityDetails.from(bot, entity))
                        .toList();

                State currentState;

                if (hasRespawned && botDied) {
                    State lastKnownState = QTableStorage.loadLastKnownState(qTableDir + "/lastKnownState.bin");
                    currentState = createInitialState(bot);
                    BotEventHandler.botDied = false;

                    if (isStateConsistent(lastKnownState, currentState)) {
                        debugRL("Merged values from last known state.");
                        currentState.setRiskMap(lastKnownState.getRiskMap());
                        currentState.setPodMap(lastKnownState.getPodMap());
                    }
                } else {
                    currentState = initializeBotState(qTable);
                }

                if (botSpawnCount == 0) {
                    currentState = createInitialState(bot);
                }

                performLearningStep(rlAgentHook, qTable, currentState, nearbyEntitiesList, nearbyBlocks,
                        distanceToHostileEntity, time, dimension);
            } else {
                debugRL("Passive environment detected. Running exploratory step.");

                collectNearbyDrops(bot, 6.0D);

                List<EntityDetails> nearbyEntitiesList = nearbyEntities.stream()
                        .map(entity -> EntityDetails.from(bot, entity))
                        .toList();

                State currentState = BotEventHandler.currentState != null ? BotEventHandler.currentState : createInitialState(bot);
                if (currentState.getRiskMap() == null) {
                    currentState.setRiskMap(new HashMap<>());
                }
                if (currentState.getPodMap() == null) {
                    currentState.setPodMap(new HashMap<>());
                }

                double safeDistance = Double.isFinite(distanceToHostileEntity) && distanceToHostileEntity > 0
                        ? distanceToHostileEntity
                        : 50.0;

                performLearningStep(rlAgentHook, qTable, currentState, nearbyEntitiesList, nearbyBlocks,
                        safeDistance, time, dimension);
            }


        } finally {
            synchronized (monitorLock) {
                isExecuting = false;
                AutoFaceEntity.isHandlerTriggered = false;
                AutoFaceEntity.setBotExecutingTask(false);
                AutoFaceEntity.isBotMoving = false;
                debugRL("Resetting handler trigger flag to: " + false);
            }
        }
    }

    public static void tickDrowningRescue(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity player : getRegisteredBots(server)) {
            if (player.isAlive() && player.isTouchingWater() && player.getAir() < player.getMaxAir()) {
                if (player.isSneaking() && !net.wcfcarolina13.GameAI.services.SneakLockService.isLocked(player.getUuid())) {
                    player.setSneaking(false);
                }
                player.setPitch(-80f);
                player.setYaw(player.getYaw());
                player.setSprinting(true);
                player.setSwimming(true);
                net.wcfcarolina13.GameAI.BotActions.jump(player);
                net.wcfcarolina13.GameAI.BotActions.moveForward(player);
            }
        }
    }

    public static void tickHunger(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity player : getRegisteredBots(server)) {
            // Pass nearby hostiles so autoEat knows whether it's safe.
            // Without this, autoEat sees null hostiles and always considers it safe,
            // causing the bot to eat food mid-combat (and "attack" with food).
            List<Entity> nearbyHostiles = AutoFaceEntity.detectNearbyEntities(player, 10.0D)
                    .stream()
                    .filter(EntityUtil::isHostile)
                    .toList();
            HealingService.autoEat(player, nearbyHostiles);
        }
    }

    public static void tickDurabilityArmorAudit(MinecraftServer server) {
        if (server == null) return;
        long nowTick = server.getTicks();
        for (ServerPlayerEntity player : getRegisteredBots(server)) {
            if (player.isRemoved() || !player.isAlive()) continue;

            // Skip during combat — spec forbids mid-combat armor mutation
            if (BotCombatCalloutService.isInCombat(player.getUuid())) continue;

            // Per-bot throttle: audit at most once every 3 seconds
            long last = LAST_ARMOR_AUDIT_TICK.getOrDefault(player.getUuid(), 0L);
            if (nowTick - last < ARMOR_AUDIT_COOLDOWN_TICKS) continue;
            LAST_ARMOR_AUDIT_TICK.put(player.getUuid(), nowTick);

            try {
                armorUtils.autoEquipArmor(player);
            } catch (Throwable t) {
                LOGGER.debug("tickDurabilityArmorAudit: autoEquipArmor failed for {}: {}",
                        player.getName().getString(), t.getMessage());
            }
        }
    }


    public static State getCurrentState() {

        return BotEventHandler.currentState;

    }

    public void detectAndReactPlayMode(RLAgent rlAgentHook, QTable qTable) {
        if (!net.wcfcarolina13.Commands.modCommandRegistry.enableReinforcementLearning) {
            return;
        }
        if (externalOverrideActive) {
            LOGGER.debug("Skipping detectAndReactPlayMode because external override is active.");
            return;
        }
        if (bot != null) {
            Mode mode = getMode(bot);
            if (mode != null && mode != Mode.IDLE) {
                LOGGER.debug("Skipping detectAndReactPlayMode because bot is in mode={}", mode);
                return;
            }
        }
        synchronized (monitorLock) {
            if (isExecuting) {
                debugRL("Already executing detection code, skipping...");
                return; // Skip if already executing
            }
            isExecuting = true;
        }

        try {
            ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);


            if (qTable == null) {
                ChatUtils.sendChatMessages(botSource, "I have no training data to work with! Please spawn me in training mode so that I can learn first!");
            }

            else {
                // Detect nearby hostile entities
                List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 10); // Example bounding box size
                List<Entity> hostileEntities = nearbyEntities.stream()
                        .filter(EntityUtil::isHostile)
                        .toList();

                if (!hostileEntities.isEmpty()) {
                    // Gather state information
                    State currentState = createInitialState(bot);

//                double riskAppetite = currentState.getRiskAppetite();
//
                    Map<StateActions.Action, Double> riskMap = currentState.getRiskMap();



                    // Choose action
                    StateActions.Action chosenAction = rlAgentHook.chooseActionPlayMode(currentState, qTable, riskMap, "detectAndReactPlayMode");


                    // Log chosen action for debugging
                    debugRL("Play Mode - Chosen action: " + chosenAction);

                    // Execute action
                    executeAction(chosenAction);
                }
                else if (DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) <= 5.0 && DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) > 0.0) {

                    // Gather state information
                    State currentState = createInitialState(bot);

                    Map<StateActions.Action, Double> riskMap = currentState.getRiskMap();


                    // Choose action
                    StateActions.Action chosenAction = rlAgentHook.chooseActionPlayMode(currentState, qTable, riskMap, "detectAndReactPlayMode");


                    // Log chosen action for debugging
                    debugRL("Play Mode - Chosen action: " + chosenAction);

                    // Execute action
                    executeAction(chosenAction);
                }


            }
        } finally {
            synchronized (monitorLock) {
                debugRL("Resetting handler trigger flag.");
                isExecuting = false;
                AutoFaceEntity.isHandlerTriggered = false; // Reset the trigger flag
                AutoFaceEntity.setBotExecutingTask(false);
                AutoFaceEntity.isBotMoving = false;
            }
        }
    }

    private static void executeAction(StateActions.Action chosenAction) {
        ActionHoldTracker.recordAction(chosenAction);
        switch (chosenAction) {
            case MOVE_FORWARD -> performAction("moveForward");
            case MOVE_BACKWARD -> performAction("moveBackward");
            case TURN_LEFT -> performAction("turnLeft");
            case TURN_RIGHT -> performAction("turnRight");
            case JUMP -> performAction("jump");
            case JUMP_FORWARD -> performAction("jumpForward");
            case SNEAK -> performAction("sneak");
            case SPRINT -> performAction("sprint");
            case STOP_SNEAKING -> performAction("unsneak");
            case STOP_SPRINTING -> performAction("unsprint");
            case STOP_MOVING -> performAction("stopMoving");
            case USE_ITEM -> performAction("useItem");
            case EQUIP_ARMOR -> armorUtils.autoEquipArmor(bot);
            case ATTACK -> {
                performAction("attack");
                // Combat callout: engagement - find nearest hostile at attack time
                if (bot != null) {
                    List<Entity> nearbyHostiles = AutoFaceEntity.detectNearbyEntities(bot, 10.0D).stream()
                            .filter(e -> e instanceof net.minecraft.entity.mob.HostileEntity)
                            .toList();
                    if (!nearbyHostiles.isEmpty()) {
                        Entity target = nearbyHostiles.stream()
                                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                                .orElse(null);
                        if (target != null) {
                            net.wcfcarolina13.GameAI.services.BotCombatCalloutService.onEngagement(bot, target);
                        }
                    }
                }
            }
            case BREAK_BLOCK_FORWARD -> performAction("breakBlock");
            case PLACE_SUPPORT_BLOCK -> performAction("placeSupportBlock");
            case ESCAPE_STAIRS -> performAction("escapeStairs");
            case HOTBAR_1 -> performAction("hotbar1");
            case HOTBAR_2 -> performAction("hotbar2");
            case HOTBAR_3 -> performAction("hotbar3");
            case HOTBAR_4 -> performAction("hotbar4");
            case HOTBAR_5 -> performAction("hotbar5");
            case HOTBAR_6 -> performAction("hotbar6");
            case HOTBAR_7 -> performAction("hotbar7");
            case HOTBAR_8 -> performAction("hotbar8");
            case HOTBAR_9 -> performAction("hotbar9");
            case STAY -> debugRL("Performing action: Stay and do nothing");
        }
    }

    private static ServerPlayerEntity findEscortPlayer(ServerPlayerEntity bot) {
        MinecraftServer srv = bot.getCommandSource().getServer();
        if (srv == null) {
            return null;
        }

        return srv.getPlayerManager().getPlayerList().stream()
                .filter(player -> !player.getUuid().equals(bot.getUuid()))
                .filter(player -> !player.isSpectator())
                .min(Comparator.comparingDouble(player -> player.squaredDistanceTo(bot)))
                .orElse(null);
    }

    private static boolean shouldEnterCombat(boolean hostilesNearby, double dangerDistance, boolean hasSculkNearby) {
        boolean dangerProximity = dangerDistance > 0.0 && dangerDistance <= 5.0;
        return hostilesNearby || dangerProximity || hasSculkNearby;
    }

    private static boolean assessImmediateThreat(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        double dangerDistance = DangerZoneDetector.detectDangerZone(bot, 10, 10, 10);
        List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 10);
        boolean hostilesNearby = nearbyEntities.stream().anyMatch(EntityUtil::isHostile);
        boolean hasSculkNearby = false;
        if (!hostilesNearby) {
            try {
                BlockDistanceLimitedSearch search = new BlockDistanceLimitedSearch(bot, 3, 5);
                hasSculkNearby = search.detectNearbyBlocks().stream()
                        .anyMatch(block -> block.contains("Sculk Sensor") || block.contains("Sculk Shrieker"));
            } catch (Exception e) {
                LOGGER.warn("Unable to evaluate sculk proximity while assessing threats: {}", e.getMessage());
            }
        }
        return shouldEnterCombat(hostilesNearby, dangerDistance, hasSculkNearby);
    }



    public static void onBotRespawn(ServerPlayerEntity bot) {
        registerBot(bot);
        net.wcfcarolina13.GameAI.services.RideSyncService.clearBotState(bot.getUuid());
        BotStuckService.resetBot(bot.getUuid());
        BotFleeService.reset(bot.getUuid());
        BotCombatCalloutService.resetCombatState(bot.getUuid());
        // Clear stale directed-mode targets from previous life so hobbies aren't
        // blocked by leftover follow-or-base-intent after respawn.
        setBaseTarget(bot, null);
        setFollowTarget(bot, null);

        MinecraftServer srv = bot.getCommandSource().getServer();
        ServerWorld botWorld = bot.getCommandSource().getWorld();
        ServerWorld destinationWorld = botWorld;
        Vec3d target = null;

        // ── Checkpoint-based fallback chain (mirrors vanilla bed-missing/obstructed logic) ──
        String alias = bot.getName().getString();
        String respawnLog = null;

        // 1. Vanilla spawn point (set by sleeping in bed) — VALIDATED
        net.minecraft.server.network.ServerPlayerEntity.Respawn respawnInfo = bot.getRespawn();
        BlockPos bedSpawn = null;
        RegistryKey<World> bedDimKey = null;
        if (respawnInfo != null && respawnInfo.respawnData() != null) {
            bedSpawn = respawnInfo.respawnData().getPos();
            bedDimKey = respawnInfo.respawnData().getDimension();
        }
        if (bedSpawn != null && srv != null && bedDimKey != null) {
            ServerWorld bedWorld = srv.getWorld(bedDimKey);
            if (bedWorld != null) {
                BlockPos safeBed = SafePositionService.validateBedSpawn(bedWorld, bedSpawn);
                if (safeBed != null) {
                    destinationWorld = bedWorld;
                    target = new Vec3d(safeBed.getX() + 0.5, safeBed.getY() + 0.1, safeBed.getZ() + 0.5);
                    respawnLog = "bed at " + bedSpawn.toShortString();
                } else {
                    LOGGER.info("[Frens] Respawn for {}: bed missing or obstructed at {}, falling through.",
                            alias, bedSpawn.toShortString());
                }
            }
        }

        // 2. Recruitment anchor (questing mode) — with safe-surface validation
        if (target == null && srv != null) {
            try {
                if (net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.isEnabled(srv)) {
                    net.wcfcarolina13.FilingSystem.ManualConfig.SurvivalRecruitmentState st =
                            net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.getState(srv);
                    if (st != null && st.isCompanionAnchorSet()) {
                        BlockPos anchorPos = BlockPos.fromLong(st.getCompanionAnchorPos());
                        String anchorDim = st.getCompanionAnchorDimension();
                        if (anchorDim != null) {
                            net.minecraft.util.Identifier dimId = net.minecraft.util.Identifier.tryParse(anchorDim);
                            if (dimId != null) {
                                RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, dimId);
                                ServerWorld anchorWorld = srv.getWorld(key);
                                if (anchorWorld != null) {
                                    BlockPos safeAnchor = SafePositionService.findSafeSurface(anchorWorld, anchorPos, 5, 10);
                                    if (safeAnchor != null) {
                                        destinationWorld = anchorWorld;
                                        target = new Vec3d(safeAnchor.getX() + 0.5, safeAnchor.getY() + 0.1, safeAnchor.getZ() + 0.5);
                                        respawnLog = "recruitment anchor near " + anchorPos.toShortString();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[Frens] Respawn for {}: recruitment anchor lookup failed", alias, t);
            }
        }

        // 3. BotSpawn config position (admin/training bots) — with safe-surface validation
        if (target == null && srv != null && net.wcfcarolina13.Frens.CONFIG != null) {
            String worldKey = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(srv);
            net.wcfcarolina13.FilingSystem.ManualConfig.BotSpawn spawn =
                    net.wcfcarolina13.Frens.CONFIG.getBotSpawn(alias, worldKey);
            if (spawn != null && spawn.dimension() != null) {
                // Per-world BotSpawn is already scoped to the current world, so no level-name check needed.
                net.minecraft.util.Identifier dimId = net.minecraft.util.Identifier.tryParse(spawn.dimension());
                    if (dimId != null) {
                        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, dimId);
                        ServerWorld spawnWorld = srv.getWorld(key);
                        if (spawnWorld != null) {
                            BlockPos spawnPos = BlockPos.ofFloored(spawn.x(), spawn.y(), spawn.z());
                            BlockPos safeSpawn = SafePositionService.findSafeSurface(spawnWorld, spawnPos, 5, 10);
                            if (safeSpawn != null) {
                                destinationWorld = spawnWorld;
                                target = new Vec3d(safeSpawn.getX() + 0.5, safeSpawn.getY() + 0.1, safeSpawn.getZ() + 0.5);
                                respawnLog = "BotSpawn config near " + spawnPos.toShortString();
                            }
                        }
                    }
            }
        }

        // 4. Failsafe tier — admin-configurable per-bot preference
        if (target == null && srv != null) {
            String failsafeMode = "world_spawn";
            if (net.wcfcarolina13.Frens.CONFIG != null) {
                String wk = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(srv);
                net.wcfcarolina13.FilingSystem.ManualConfig.BotControlSettings ctrl =
                        net.wcfcarolina13.Frens.CONFIG.getOrCreateBotControl(alias, wk);
                failsafeMode = ctrl.getFailsafeSpawnMode();
            }

            // 4a. Owner's bed
            if ("owner_bed".equals(failsafeMode)) {
                try {
                    ServerPlayerEntity owner = net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy
                            .resolveController(srv, bot);
                    if (owner != null) {
                        net.minecraft.server.network.ServerPlayerEntity.Respawn ownerRespawn = owner.getRespawn();
                        if (ownerRespawn != null && ownerRespawn.respawnData() != null) {
                            BlockPos ownerBedPos = ownerRespawn.respawnData().getPos();
                            RegistryKey<World> ownerBedDim = ownerRespawn.respawnData().getDimension();
                            if (ownerBedPos != null && ownerBedDim != null) {
                                ServerWorld ownerBedWorld = srv.getWorld(ownerBedDim);
                                if (ownerBedWorld != null) {
                                    BlockPos safeOwnerBed = SafePositionService.validateBedSpawn(ownerBedWorld, ownerBedPos);
                                    if (safeOwnerBed != null) {
                                        destinationWorld = ownerBedWorld;
                                        target = new Vec3d(safeOwnerBed.getX() + 0.5, safeOwnerBed.getY() + 0.1, safeOwnerBed.getZ() + 0.5);
                                        respawnLog = "owner bed near " + ownerBedPos.toShortString();
                                    }
                                }
                            }
                        }
                    }
                    if (target == null) {
                        LOGGER.info("[Frens] Respawn for {}: failsafe=owner_bed but owner offline or bed gone, falling through.", alias);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[Frens] Respawn for {}: owner_bed failsafe lookup failed", alias, t);
                }
            }

            // 4b. Saved base (bot's preferred home base)
            //     Bases are restricted to the Overworld by the UI, so always look there
            //     regardless of which dimension the bot died in.
            if (target == null && "saved_base".equals(failsafeMode)) {
                try {
                    java.util.Optional<String> baseLabel = BotHomeService.getPreferredHomeBaseLabel(bot);
                    ServerWorld overworld = srv.getOverworld();
                    if (baseLabel.isPresent() && overworld != null) {
                        java.util.Optional<BlockPos> basePos = BotHomeService.getBaseByLabel(srv, overworld, baseLabel.get());
                        if (basePos.isPresent()) {
                            BlockPos safeBase = SafePositionService.findSafeSurface(overworld, basePos.get(), 5, 10);
                            if (safeBase != null) {
                                destinationWorld = overworld;
                                target = new Vec3d(safeBase.getX() + 0.5, safeBase.getY() + 0.1, safeBase.getZ() + 0.5);
                                respawnLog = "saved base '" + baseLabel.get() + "' near " + basePos.get().toShortString();
                            }
                        }
                    }
                    if (target == null) {
                        LOGGER.info("[Frens] Respawn for {}: failsafe=saved_base but no preferred base set or obstructed, falling through.", alias);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[Frens] Respawn for {}: saved_base failsafe lookup failed", alias, t);
                }
            }
        }

        // 5. World spawn (guaranteed — always available)
        if (target == null && srv != null) {
            ServerWorld overworld = srv.getOverworld();
            if (overworld != null) {
                BlockPos worldSpawn = resolveSpawnPoint(overworld);
                BlockPos safeWorldSpawn = SafePositionService.findSafeSurface(overworld, worldSpawn, 5, 16);
                if (safeWorldSpawn != null) {
                    destinationWorld = overworld;
                    target = new Vec3d(safeWorldSpawn.getX() + 0.5, safeWorldSpawn.getY() + 0.1, safeWorldSpawn.getZ() + 0.5);
                    respawnLog = "world spawn near " + worldSpawn.toShortString();
                } else {
                    // Heightmap-only absolute fallback: find any solid column at world spawn XZ
                    int surfaceY = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING, worldSpawn.getX(), worldSpawn.getZ());
                    if (surfaceY > overworld.getBottomY()) {
                        destinationWorld = overworld;
                        target = new Vec3d(worldSpawn.getX() + 0.5, surfaceY + 0.1, worldSpawn.getZ() + 0.5);
                        respawnLog = "world spawn heightmap at Y=" + surfaceY;
                    } else {
                        // Complete void — place on bedrock level
                        destinationWorld = overworld;
                        target = new Vec3d(worldSpawn.getX() + 0.5, overworld.getBottomY() + 1.1, worldSpawn.getZ() + 0.5);
                        respawnLog = "void fallback at bedrock Y=" + (overworld.getBottomY() + 1);
                        LOGGER.warn("[Frens] Respawn for {}: no solid ground found anywhere near world spawn; placing at bedrock level.", alias);
                    }
                }
            }
        }

        // 6. Absolute emergency fallback (should never happen)
        if (target == null) {
            BlockPos anchor = bot.getBlockPos().up(2);
            target = new Vec3d(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            respawnLog = "emergency fallback at current pos";
            LOGGER.warn("[Frens] Respawn for {}: all checkpoints exhausted, using current position.", alias);
        }

        LOGGER.info("[Frens] Respawn for {}: resolved to {} ({})", alias,
                String.format("%.1f, %.1f, %.1f", target.x, target.y, target.z),
                respawnLog != null ? respawnLog : "unknown");

        if (destinationWorld != null && destinationWorld != botWorld) {
            bot.teleport(destinationWorld, target.x, target.y, target.z,
                    EnumSet.noneOf(PositionFlag.class),
                    bot.getYaw(), bot.getPitch(), true);
        } else {
            bot.refreshPositionAndAngles(target.x, target.y, target.z,
                    bot.getYaw(), bot.getPitch());
        }

        bot.setVelocity(Vec3d.ZERO);
        bot.setInvulnerable(false); // ensure no stale invulnerability from a previous respawn
        bot.timeUntilRegen = 0;     // clear vanilla damage-immunity cooldown
        bot.hurtTime = 0;           // clear vanilla hurt animation timer
        bot.deathTime = 0;          // clear death animation counter
        // Clear LivingEntity.dead and ServerPlayNetworkHandler.dead.  Without this,
        // ServerPlayerEntity.isInvulnerableTo() treats the bot as invulnerable for the
        // rest of the session via canInteractWithGame(), blocking all damage after the
        // first death.  Fake players reuse the same entity across "respawns" so both
        // death flags must be reset manually.
        ((net.wcfcarolina13.mixin.LivingEntityAccessor) bot).setDead(false);
        if (bot.networkHandler != null) {
            ((net.wcfcarolina13.mixin.ServerPlayNetworkHandlerAccessor) (Object) bot.networkHandler).setDead(false);
        }
        if (srv != null) {
            lastRespawnHandledTick = srv.getTicks();
        }

        BotStuckService.setLastSafePosition(bot.getUuid(), target);
        TaskService.forceAbort(bot.getUuid(), "§cTask aborted due to bot respawn.");
        setExternalOverrideActive(false);
        setMode(bot, Mode.IDLE);
        setAssistAllies(bot, true);
        if (destinationWorld != null) {
            rememberSpawn(destinationWorld, target, bot.getYaw(), bot.getPitch());
        }

        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                bot.getName().getString() + " has regrouped and is ready to re-engage.");

        rescueFromBurial(bot);
    }

    public static void ensureRespawnHandled(ServerPlayerEntity bot) {
        if (!isRegisteredBot(bot)) {
            return;
        }
        MinecraftServer srv = bot.getCommandSource().getServer();
        if (srv == null) {
            return;
        }

        long checkTick = srv.getTicks() + 5;
        srv.send(new ServerTask((int) checkTick, () -> {
            long ticksSince = lastRespawnHandledTick < 0 ? Long.MAX_VALUE : checkTick - lastRespawnHandledTick;
            if (ticksSince <= 5) {
                return; // recent respawn already handled
            }

            LOGGER.warn("AFTER_RESPAWN did not fire for bot {}; forcing respawn routine", bot.getName().getString());
            // Resolve the current entity — the captured 'bot' may be stale (old entity from death event)
            ServerPlayerEntity currentBot = srv.getPlayerManager().getPlayer(bot.getUuid());
            if (currentBot == null) currentBot = bot;
            if (currentBot.isDead()) {
                currentBot.setHealth(currentBot.getMaxHealth());
            }
            onBotRespawn(currentBot);
        }));
    }

    public static boolean updateBehavior(ServerPlayerEntity bot, MinecraftServer server, List<Entity> nearbyEntities, List<Entity> hostileEntities) {
        if (!isRegisteredBot(bot)) {
            return false;
        }

        // Safety net: bots should never be permanently invulnerable.
        // If the flag is stuck on (e.g. from a failed ServerTask), force-clear it.
        if (bot.isInvulnerable() && bot.isAlive()) {
            bot.setInvulnerable(false);
            if (server.getTicks() % 100 == 0) {
                LOGGER.warn("Cleared stale invulnerability on bot {}", bot.getName().getString());
            }
        }

        // Diagnostic: log damage-immunity state for 200 ticks after respawn
        long ticksSinceRespawn = lastRespawnHandledTick > 0 ? server.getTicks() - lastRespawnHandledTick : -1;
        if (ticksSinceRespawn >= 0 && ticksSinceRespawn <= 200 && ticksSinceRespawn % 20 == 0) {
            LOGGER.info("[respawn-diag] {} tick+{}: invulnerable={} timeUntilRegen={} hurtTime={} hp={} alive={} removed={}",
                    bot.getName().getString(), ticksSinceRespawn,
                    bot.isInvulnerable(), bot.timeUntilRegen, bot.hurtTime,
                    String.format("%.1f", bot.getHealth()), bot.isAlive(), bot.isRemoved());
        }

        // ── External teleport detection ─────────────────────────────────
        // If the bot's position jumped >16 blocks in a single tick (and it's not mid-travel),
        // it was likely teleported by a console command. Cancel tasks, clear goals, and
        // suppress surface recovery for 40 ticks (2 seconds) to avoid false underground detection.
        UUID botId = bot.getUuid();
        Vec3d currentPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d lastPos = TELEPORT_DETECT_LAST_POS.put(botId, currentPos);
        if (lastPos != null && currentPos.squaredDistanceTo(lastPos) > EXTERNAL_TELEPORT_THRESHOLD_SQ) {
            BotCommandStateService.State tpState = stateFor(bot);
            // Don't trigger during fast-travel arrival (mode == TRAVELING)
            if (tpState != null && tpState.mode != Mode.TRAVELING) {
                LOGGER.info("External teleport detected for {} (moved {} blocks in 1 tick) — clearing tasks and goals",
                        bot.getName().getString(), (int) Math.sqrt(currentPos.squaredDistanceTo(lastPos)));
                // Cancel any active skill/task (forceAbort sets the abort latch that skills check)
                TaskService.forceAbort(botId, "External teleport detected.");
                // Clear follow/movement goals
                if (tpState.followFixedGoal != null) {
                    tpState.followFixedGoal = null;
                }
                // Stop movement
                BotActions.stop(bot);
                // Suppress surface recovery for 2 seconds
                TELEPORT_GRACE_UNTIL_TICK.put(botId, (long) server.getTicks() + 40L);
            }
        }

        BotCommandStateService.State state = stateFor(bot);
        Mode mode = state != null ? state.mode : Mode.IDLE;
        List<Entity> augmentedHostiles = BotThreatService.augmentHostiles(
                bot,
                hostileEntities,
                isAssistAllies(bot),
                server,
                BotRegistry.ids());

        // Combat callout bookkeeping: treat "hostiles present" as ongoing combat activity.
        // Without this, long skeleton fights can go quiet for >5s and trigger a premature "standing down".
        try {
            net.wcfcarolina13.GameAI.services.BotCombatCalloutService.noteCombatOngoing(bot, !augmentedHostiles.isEmpty());
        } catch (Throwable ignored) {
        }

        // Arrow recovery bookkeeping:
        // - if hostiles present, remember the last "combat" tick
        // - always tick arrow tracking so we can detect "miss" events (cooldown-gated)
        if (!augmentedHostiles.isEmpty()) {
            BotArrowRecoveryService.noteHostilesSeen(bot, server.getTicks());
        }
        BotArrowRecoveryService.tickArrowTracking(bot, server, !augmentedHostiles.isEmpty());

        // ---- Creeper fuse backoff (all modes, pre-empts skills + follow) ----
        // Always-on defensive interrupt: if any ignited creeper is within range,
        // back the bot off to a safe distance before doing ANYTHING else this
        // tick. Owns its own ABORT_LATCH lifecycle so worker-thread skills stop
        // fighting our movement inputs. See BotCreeperDefenseService.
        if (net.wcfcarolina13.GameAI.services.BotCreeperDefenseService.tickBackoff(bot, server)) {
            return true;
        }

        // ---- Post-combat drop/arrow sweep (all modes) ----
        if (augmentedHostiles.isEmpty()) {
            BotCombatCalloutService.noteHostilesCleared(bot, server.getTicks());
        } else {
            // Hostiles present — cancel any active drop sweep but do NOT clear kill positions
            // or combat center. Those accumulate during the entire combat session and are only
            // cleared when the post-combat sweep finishes (or times out at 30s).
            BotCombatCalloutService.cancelPendingSweep(bot.getUuid());
            DropSweepService.requestCancel(bot, "hostiles-reappeared");
        }
        if (augmentedHostiles.isEmpty()
                && BotCombatCalloutService.isPostCombatSweepReady(bot)) {
            if (tickPostCombatSweep(bot, server, mode)) {
                return true;
            }
        }
        // Linger window: FOLLOW mode pauses near the combat site while sweep delay elapses
        // so drops stay within pickup range.  Pick up any items already at our feet.
        if (augmentedHostiles.isEmpty()
                && mode == Mode.FOLLOW
                && BotCombatCalloutService.isInPostCombatLingerWindow(bot)) {
            Entity nearDrop = findNearestDrop(bot, 6.0);
            if (nearDrop != null) {
                moveToward(bot, positionOf(nearDrop), 0.25D, false);
            }
            return true; // suppress follow movement during linger
        }

        // ---- Opportunistic idle drop-sweep (FOLLOW / STAY / IDLE) ----
        // After 15 s of idle (no hostiles, player/bot stationary), the bot walks to nearby
        // ground items.  Cancels the moment the player moves >1 block (FOLLOW) or hostiles appear.
        if (augmentedHostiles.isEmpty() && (mode == Mode.FOLLOW || mode == Mode.STAY || mode == Mode.IDLE)) {
            if (tickOpportunisticIdleSweep(bot, state, server, mode)) {
                return true;
            }
        } else {
            // Reset idle timer when hostiles are present or mode doesn't qualify.
            FollowStateService.clearIdleSweep(bot.getUuid());
        }

        // ---- Daylight break-free (IDLE only) ----
        // If the bot sealed itself in a shelter overnight, break free at dawn.
        // Must run BEFORE proactive shelter check to avoid re-sheltering immediately.
        if (mode == Mode.IDLE && BotFleeService.isInShelter(bot.getUuid())) {
            if (BotFleeService.checkDaylightBreakFree(bot, server)) {
                return true;
            }
        }

        // ---- Proactive shelter (IDLE only, after sweep completes) ----
        // At night in survival, idle bots seek shelter once they're done collecting items.
        if (mode == Mode.IDLE && augmentedHostiles.isEmpty()) {
            if (BotFleeService.tryProactiveShelter(bot, server)) {
                return true;
            }
        }

        // Run shelter validation for ALL modes — clears stale shelter at dawn, launches break-free.
        // Previously only ran in IDLE (default case), so FOLLOW bots kept stale shelter indefinitely.
        BotFleeService.validateAndTickShelter(bot, server);
        if (augmentedHostiles.isEmpty() && handleStarvationOverride(bot, server, mode)) {
            return true;
        }

        switch (mode) {
            case FOLLOW -> {
                // Arrow recovery during FOLLOW: only allow short, bounded detours when we're not far from
                // the commander (or fixed goal). This makes post-combat arrow pickup much more reliable.
                if (augmentedHostiles.isEmpty()) {
                    boolean allowRecovery = false;
                    if (state != null) {
                        if (state.followTargetUuid != null) {
                            ServerPlayerEntity commander = server != null
                                    ? server.getPlayerManager().getPlayer(state.followTargetUuid)
                                    : null;
                            if (commander != null && !commander.isRemoved()) {
                                double dx = commander.getX() - bot.getX();
                                double dz = commander.getZ() - bot.getZ();
                                allowRecovery = (dx * dx + dz * dz) <= (30.0D * 30.0D);
                            }
                        } else if (state.followFixedGoal != null) {
                            double gx = state.followFixedGoal.getX() + 0.5D;
                            double gz = state.followFixedGoal.getZ() + 0.5D;
                            double dx = gx - bot.getX();
                            double dz = gz - bot.getZ();
                            allowRecovery = (dx * dx + dz * dz) <= (30.0D * 30.0D);
                        }
                    }
                    if (allowRecovery && BotArrowRecoveryService.tryRecoverMissedArrows(bot, server, null, 26.0D)) {
                        return true;
                    }
                    // Sweep mob drops after combat (mirrors GUARD/PATROL behavior).
                    // Only block follow while a sweep is actively running.
                    if (allowRecovery) {
                        if (DropSweepService.isInProgressFor(bot) && !DropSweepService.isCancelRequestedFor(bot)) {
                            return true;
                        }
                        if (!DropSweepService.isInProgress()) {
                            Entity nearestItem = findNearestDrop(bot, 8.0D);
                            if (nearestItem != null) {
                                collectNearbyDrops(bot, 8.0D);
                                if (DropSweepService.isInProgressFor(bot)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                return handleFollow(bot, state, server, augmentedHostiles);
            }
            case GUARD -> {
                return handleGuard(bot, state, nearbyEntities, augmentedHostiles);
            }
            case PATROL -> {
                return handlePatrol(bot, state, nearbyEntities, augmentedHostiles);
            }
            case STAY -> {
                if (!augmentedHostiles.isEmpty() && engageHostiles(bot, server, augmentedHostiles)) {
                    return true;
                }
                // Use the combat anchor so slight post-fight drift doesn't make arrows "fall off" the radar.
                if (BotArrowRecoveryService.tryRecoverMissedArrows(bot, server, null, 18.0D)) {
                    return true;
                }
                BotActions.stop(bot);
                return true;
            }
            case RETURNING_BASE -> {
                // Note: This case is currently not used. Return-to-base uses FOLLOW mode with
                // state.baseTarget set. Stuck detection is handled in handleFollow.
                if (!augmentedHostiles.isEmpty() && engageHostiles(bot, server, augmentedHostiles)) {
                    return true;
                }
                return handleReturnToBase(bot, state);
            }
            default -> {
                // Tactical shelter: suppress idle behaviors but let combat run normally.
                // (validateAndTickShelter runs above for ALL modes; here we just check the flag)
                if (BotFleeService.isInShelter(bot.getUuid())) {
                    if (augmentedHostiles.isEmpty()) {
                        return true; // safe — stay sheltered
                    }
                    // hostiles present — fall through to normal combat
                }
                // Flee check: if outnumbered/critically wounded and IDLE, sprint to safety
                if (BotFleeService.tickFlee(bot, server, augmentedHostiles, mode)) {
                    return true;
                }
                if (!augmentedHostiles.isEmpty()) {
                    return engageHostiles(bot, server, augmentedHostiles);
                }
                // If we were recently damaged by a hostile (within 2s), do a wider scan.
                // Prevents premature combat exit when a skeleton shoots from beyond 10-block range.
                if (BotCombatCalloutService.wasRecentlyDamagedByHostile(bot, server.getTicks(), 40)) {
                    List<Entity> widerScan = AutoFaceEntity.detectNearbyEntities(bot, 16.0D)
                            .stream()
                            .filter(EntityUtil::isHostile)
                            .toList();
                    if (!widerScan.isEmpty()) {
                        return engageHostiles(bot, server, widerScan);
                    }
                    return true; // stay alert, don't start idle behaviors
                }
                // tryProactiveShelter is now called before the mode switch (pre-idle-sweep)
                if (BotArrowRecoveryService.tryRecoverMissedArrows(bot, server, null, 24.0D)) {
                    return true;
                }
                return false;
            }
        }
    }

    public static String setFollowMode(ServerPlayerEntity bot, ServerPlayerEntity target) {
        return setFollowMode(bot, target, true);
    }

    /**
     * @param announce Whether to send a bot-authored chat acknowledgement (use false for commands
     *                 that already emit a system summary to avoid redundant messages).
     */
    public static String setFollowMode(ServerPlayerEntity bot, ServerPlayerEntity target, boolean announce) {
        if (bot != null && TaskService.hasActiveTask(bot.getUuid())) {
            sendBotMessage(bot, "Busy with another task right now.");
            return "Bot is busy executing another task. Try again after it finishes.";
        }
        if (target == null) {
            return "Unable to follow — target not found.";
        }
        registerBot(bot);
        setFollowTarget(bot, target.getUuid());
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followNoTeleport = false;
            state.followStopRange = 0.0D;
            state.followStandoffRange = 0.0D;
            state.followFixedGoal = null;
            state.comeBestGoalDistSq = Double.NaN;
            state.comeTicksSinceBest = 0;
            state.comeRerouteAttempts = 0;
            state.comeNextRerouteTick = 0L;
            state.comeNextSkillTick = 0L;
            state.comeRecoverySkillInFlight = false;
            state.comeRecoverySkillStartTick = 0L;
            state.comeRecoverySkillAttempts = 0;
            state.comeAllowRecoverySkills = true;
        }
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);
        clearBase(bot);
        // Clear any lingering idle sweep — it consumes ticks and blocks follow movement.
        UUID id = bot.getUuid();
        FollowStateService.clearIdleSweep(id);
        DropSweepService.requestCancel(bot, "follow-start");
        // Kick off a follow plan immediately so "around the corner / door enclosures" work even when
        // the commander isn't standing in front of the door. This is async + bounded, so it won't block ticks.
        FollowStateService.clearPlanning(id);
        FollowDebugService.clear(id);
        requestFollowPathPlan(bot, target, true, "follow-start");
        if (announce) {
            sendBotMessage(bot, "Following " + target.getName().getString() + ".");
        }
        return "Now following " + target.getName().getString() + ".";
    }

    public static String setFollowModeWalk(ServerPlayerEntity bot, ServerPlayerEntity target, double stopRange) {
        if (bot == null || target == null) {
            return "Unable to follow — target not found.";
        }
        registerBot(bot);
        setFollowTarget(bot, target.getUuid());
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followNoTeleport = true;
            state.followStopRange = Math.max(1.5D, stopRange);
            state.followStandoffRange = 0.0D;
            state.followFixedGoal = null;
            state.comeBestGoalDistSq = Double.NaN;
            state.comeTicksSinceBest = 0;
            state.comeRerouteAttempts = 0;
            state.comeNextRerouteTick = 0L;
            state.comeNextSkillTick = 0L;
            state.comeRecoverySkillInFlight = false;
            state.comeRecoverySkillStartTick = 0L;
            state.comeRecoverySkillAttempts = 0;
            state.comeAllowRecoverySkills = true;
        }
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);
        clearBase(bot);
        UUID id = bot.getUuid();
        FollowStateService.clearIdleSweep(id);
        DropSweepService.requestCancel(bot, "follow-start-walk");
        FollowStateService.clearPlanning(id);
        FollowDebugService.clear(id);
        requestFollowPathPlan(bot, target, true, "follow-start-walk");
        sendBotMessage(bot, "Walking to you.");
        return "Walking to " + target.getName().getString() + ".";
    }

    public static String setComeModeWalk(ServerPlayerEntity bot,
                                        ServerPlayerEntity commander,
                                        BlockPos fixedGoal,
                                        double stopRange) {
        return setComeModeWalk(bot, commander, fixedGoal, stopRange, true);
    }

    /**
     * Come mode (fixed-goal follow-walk). If allowRecoverySkills is false, the bot will not launch
     * "come recovery" digging skills (collect_dirt ascent / stripmine) and will only walk/path.
     */
    public static String setComeModeWalk(ServerPlayerEntity bot,
                                        ServerPlayerEntity commander,
                                        BlockPos fixedGoal,
                                        double stopRange,
                                        boolean allowRecoverySkills) {
        if (bot == null || fixedGoal == null) {
            return "Unable to come — destination not found.";
        }
        registerBot(bot);
        setFollowTarget(bot, commander != null ? commander.getUuid() : null);
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followNoTeleport = true;
            state.followStopRange = Math.max(1.5D, stopRange);
            state.followStandoffRange = 0.0D;
            state.followFixedGoal = fixedGoal.toImmutable();
            state.comeBestGoalDistSq = Double.NaN;
            state.comeTicksSinceBest = 0;
            state.comeRerouteAttempts = 0;
            state.comeNextRerouteTick = 0L;
            state.comeNextSkillTick = 0L;
            state.comeRecoverySkillInFlight = false;
            state.comeRecoverySkillStartTick = 0L;
            state.comeRecoverySkillAttempts = 0;
            state.comeAllowRecoverySkills = allowRecoverySkills;
        }
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);
        clearBase(bot);

        UUID id = bot.getUuid();
        FollowStateService.clearPlanning(id);
        FollowDebugService.clear(id);

        requestFollowPathPlanToGoal(bot, fixedGoal, true, "come-start");
        sendBotMessage(bot, "Walking to your last location.");
        return "Walking to your last location.";
    }

    public static String stopFollowing(ServerPlayerEntity bot) {
        return stopFollowing(bot, true);
    }

    /**
     * @param announce Whether to send a bot-authored chat acknowledgement (use false for commands
     *                 that already emit a system summary to avoid redundant messages).
     */
    public static String stopFollowing(ServerPlayerEntity bot, boolean announce) {
        if (bot != null) {
            registerBot(bot);
        }
        BotCommandStateService.State existingState = stateFor(bot);
        boolean hadFixedGoal = existingState != null && existingState.followFixedGoal != null;
        boolean wasFollowing = getMode(bot) == Mode.FOLLOW && (getFollowTargetFor(bot) != null || hadFixedGoal);
        setFollowTarget(bot, null);
        if (bot != null) {
            UUID id = bot.getUuid();
            FollowStateService.clearAll(id);
            FollowDebugService.clear(id);
        }
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followNoTeleport = false;
            state.followStopRange = 0.0D;
            state.followStandoffRange = 0.0D;
            state.followFixedGoal = null;
            state.comeBestGoalDistSq = Double.NaN;
            state.comeTicksSinceBest = 0;
            state.comeRerouteAttempts = 0;
            state.comeNextRerouteTick = 0L;
            state.comeNextSkillTick = 0L;
            state.comeRecoverySkillInFlight = false;
            state.comeRecoverySkillStartTick = 0L;
            state.comeAllowRecoverySkills = true;
        }

        // IMPORTANT: return-to-base uses FOLLOW mode with baseTarget + followFixedGoal.
        // Clearing followFixedGoal alone is not enough in some edge cases (UI/handlers may still
        // treat baseTarget as a pending home intent). A stop/follow-stop should cancel BOTH.
        clearBase(bot);
        if (bot != null) {
            BotActions.stop(bot);
        }
        setMode(bot, Mode.IDLE);
        setAssistAllies(bot, true);
        if (announce && bot != null && wasFollowing) {
            sendBotMessage(bot, "Stopping follow command.");
        }
        return wasFollowing ? "Bot stopped following." : "Bot is not currently following anyone.";
    }

    /**
     * Follow a player, but keep a larger "standoff" distance while the bot has line-of-sight.
     * If line-of-sight breaks (around corners/doors), normal pursuit + planning should resume.
     */
    public static String setFollowModeDistance(ServerPlayerEntity bot, ServerPlayerEntity target, double standoffRange) {
        if (bot != null && TaskService.hasActiveTask(bot.getUuid())) {
            sendBotMessage(bot, "Busy with another task right now.");
            return "Bot is busy executing another task. Try again after it finishes.";
        }
        if (target == null) {
            return "Unable to follow — target not found.";
        }
        registerBot(bot);
        setFollowTarget(bot, target.getUuid());
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followNoTeleport = false;
            state.followStopRange = 0.0D;
            state.followStandoffRange = Math.max(0.0D, standoffRange);
            state.followFixedGoal = null;
            state.comeBestGoalDistSq = Double.NaN;
            state.comeTicksSinceBest = 0;
            state.comeRerouteAttempts = 0;
            state.comeNextRerouteTick = 0L;
            state.comeNextSkillTick = 0L;
            state.comeRecoverySkillInFlight = false;
            state.comeRecoverySkillStartTick = 0L;
            state.comeRecoverySkillAttempts = 0;
            state.comeAllowRecoverySkills = true;
        }
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);
        clearBase(bot);

        UUID id = bot.getUuid();
        FollowStateService.clearPlanning(id);
        FollowDebugService.clear(id);
        requestFollowPathPlan(bot, target, true, "follow-distance-start");
        sendBotMessage(bot, "Following " + target.getName().getString() + " at a distance.");
        return "Now following " + target.getName().getString() + " at a distance.";
    }

    /**
     * Adjust the current follow standoff distance (0 disables standoff and reverts to default follow spacing).
     */
    public static void setFollowStandoffRange(ServerPlayerEntity bot, double standoffRange) {
        if (bot == null) {
            return;
        }
        registerBot(bot);
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followStandoffRange = Math.max(0.0D, standoffRange);
        }
    }

    public static String setGuardMode(ServerPlayerEntity bot, double radius) {
        registerBot(bot);
        // If a drop sweep is currently driving movement, cancel it so GUARD can take over immediately.
        DropSweepService.requestCancel(bot, "mode-switch-guard");
        setGuardState(bot, positionOf(bot), Math.max(3.0D, radius));
        setMode(bot, Mode.GUARD);
        setFollowTarget(bot, null);
        clearBase(bot);
        GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
        GuardPatrolService.resetStuck(bot.getUuid());
        sendBotMessage(bot, String.format(Locale.ROOT, "Guarding this area (radius %.1f blocks).", getGuardRadius(bot)));
        return "Guarding the area.";
    }

    public static String setPatrolMode(ServerPlayerEntity bot, double radius) {
        registerBot(bot);
        // If a drop sweep is currently driving movement, cancel it so PATROL can take over immediately.
        DropSweepService.requestCancel(bot, "mode-switch-patrol");
        GuardPatrolService.setPatrolState(bot.getUuid(), positionOf(bot), Math.max(3.0D, radius));
        setMode(bot, Mode.PATROL);
        setFollowTarget(bot, null);
        clearBase(bot);
        GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
        GuardPatrolService.resetStuck(bot.getUuid());
        sendBotMessage(bot, String.format(Locale.ROOT, "Patrolling this area (radius %.1f blocks).", getPatrolRadius(bot)));
        return "Patrolling the area.";
    }

    public static String setStayMode(ServerPlayerEntity bot) {
        registerBot(bot);
        setMode(bot, Mode.STAY);
        setFollowTarget(bot, null);
        setGuardState(bot, positionOf(bot), getGuardRadius(bot));
        clearBase(bot);
        sendBotMessage(bot, "Staying put here.");
        return "Bot will hold position.";
    }

    /** Distance (in blocks) beyond which questing-mode bots need a compass/map for non-HOME bases. */
    private static final double BASE_NAV_TOOL_DISTANCE = 256.0D;

    public static String setReturnToBase(ServerPlayerEntity bot, Vec3d base) {
        return setReturnToBaseInternal(bot, base, true, true);
    }

    public static String setReturnToBaseSegmented(ServerPlayerEntity bot, Vec3d base) {
        return setReturnToBaseInternal(bot, base, true, false);
    }

    private static String setReturnToBaseInternal(ServerPlayerEntity bot,
                                                  Vec3d base,
                                                  boolean announce,
                                                  boolean requestInitialPlan) {
        registerBot(bot);
        if (base == null) {
            return "No base location available.";
        }

        // Questing-mode: distant non-HOME bases require compass or map
        MinecraftServer srv = bot.getCommandSource().getServer();
        boolean questingMode = srv != null && net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.isEnabled(srv);
        if (questingMode) {
            double distSq = bot.squaredDistanceTo(base.x, base.y, base.z);
            boolean isHome = isPreferredHomeBase(bot, base);
            if (distSq > BASE_NAV_TOOL_DISTANCE * BASE_NAV_TOOL_DISTANCE && !isHome && !botHasNavigationTool(bot)) {
                sendBotMessage(bot, "I need a compass or map to find my way to a base that far away.");
                return "Bot needs a compass or map for distant bases.";
            }
        }

        // Guard: if already returning to the same (or very close) base, don't reset state
        // This prevents repeated calls from resetting stuck detection counters
        if (isReturningToBase(bot)) {
            BotCommandStateService.State existingState = stateFor(bot);
            if (existingState != null && existingState.baseTarget != null) {
                double dx = base.x - existingState.baseTarget.x;
                double dz = base.z - existingState.baseTarget.z;
                double distSq2 = dx * dx + dz * dz;
                if (distSq2 < 9.0D) { // Within 3 blocks of same target
                    LOGGER.debug("setReturnToBase: already returning to nearby base, skipping reset");
                    return "Bot is already returning to base.";
                }
            }
        }

        // Try bot-to-bot artifact teleport when far from base — a same-owner bot
        // at the destination with tier-2 artifacts can summon the traveler instantly.
        BlockPos baseBP = BlockPos.ofFloored(base.x, base.y, base.z);
        if (bot.getBlockPos().getSquaredDistance(baseBP) > 96.0D * 96.0D) {
            MinecraftServer srvTp = bot.getCommandSource().getServer();
            if (srvTp != null
                    && net.wcfcarolina13.GameAI.services.NavigationArtifactService
                            .tryBotToBotArtifactTeleport(srvTp, bot, baseBP)) {
                return "A companion at base is summoning " + bot.getName().getString() + " home via artifact.";
            }
        }

        // Preserve baseTarget so sunset automation can track "home" for auto-sleep.
        setBaseTarget(bot, base);

        // Use the follow-walk system with a fixed goal. This gives us the more robust door/corner/waypoint
        // planning that FOLLOW/COME already have, avoiding the simple "push into wall" steering.
        setFollowTarget(bot, null);
        BlockPos goal = BlockPos.ofFloored(base.x, base.y, base.z).toImmutable();
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followNoTeleport = false;
            state.followStopRange = 0.0D;
            state.followStandoffRange = 0.0D;
            state.followFixedGoal = goal;
            state.comeBestGoalDistSq = Double.NaN;
            state.comeTicksSinceBest = 0;
            state.comeRerouteAttempts = 0;
            state.comeNextRerouteTick = 0L;
            state.comeNextSkillTick = 0L;
            state.comeRecoverySkillInFlight = false;
            state.comeRecoverySkillStartTick = 0L;
            // Enable recovery skills so bot can mine out of caves/crevices en route home.
            state.comeAllowRecoverySkills = true;
        }
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);

        UUID id = bot.getUuid();
        FollowStateService.clearPlanning(id);
        FollowDebugService.clear(id);
        // Note: ReturnBaseStuckService counter is NOT cleared here - it should keep counting
        if (requestInitialPlan) {
            requestFollowPathPlanToGoal(bot, goal, true, "return-base-start");
        }

        if (announce) {
            sendBotMessage(bot, "Returning to base.");
        }
        return "Bot is returning to base.";
    }

    public static String setReturnToBase(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null) {
            return "No bot available.";
        }
        ServerWorld world = bot.getCommandSource().getWorld();

        // Policy: home/base return is not cross-dimension.
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
            sendBotMessage(bot, "I can't return home from this dimension.");
            return "Home return is not available in this dimension.";
        }

        Vec3d base = null;
        java.util.Optional<BlockPos> home = BotHomeService.resolveHomeTarget(bot);
        if (home.isPresent()) {
            base = Vec3d.ofCenter(home.get());
        }

        // Fallback for older worlds / no saved home info.
        if (base == null) {
            ServerWorld spawnWorld = commander != null ? commander.getCommandSource().getWorld() : world;
            BlockPos spawn = resolveSpawnPoint(spawnWorld);
            base = Vec3d.ofCenter(spawn);
        }

        return setReturnToBase(bot, base);
    }

    public static String setReturnToBase(ServerPlayerEntity bot) {
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos spawn = resolveSpawnPoint(world);
        return setReturnToBase(bot, Vec3d.ofCenter(spawn));
    }

    /** Returns true if the given base position matches the bot's preferred HOME base (within 3 blocks). */
    private static boolean isPreferredHomeBase(ServerPlayerEntity bot, Vec3d target) {
        java.util.Optional<BlockPos> home = net.wcfcarolina13.GameAI.services.BotHomeService.resolvePreferredHomeBase(bot);
        if (home.isEmpty()) return false;
        BlockPos h = home.get();
        double dx = target.x - (h.getX() + 0.5);
        double dz = target.z - (h.getZ() + 0.5);
        return dx * dx + dz * dz < 9.0D;
    }

    /** Returns true if the bot has a compass, recovery compass, map, or filled map. */
    private static boolean botHasNavigationTool(ServerPlayerEntity bot) {
        if (bot == null) return false;
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            net.minecraft.item.ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isOf(net.minecraft.item.Items.COMPASS)
                    || stack.isOf(net.minecraft.item.Items.RECOVERY_COMPASS)
                    || stack.isOf(net.minecraft.item.Items.FILLED_MAP)
                    || stack.isOf(net.minecraft.item.Items.MAP)
                    || stack.isOf(net.minecraft.item.Items.ENDER_EYE)) {
                return true;
            }
        }
        return false;
    }

    public static String toggleAssistAllies(ServerPlayerEntity bot, boolean enable) {
        registerBot(bot);
        setAssistAllies(bot, enable);
        String message = enable ? "Engaging threats against allies." : "Standing down unless attacked.";
        sendBotMessage(bot, message);
        return message;
    }

    public static String setBotDefense(ServerPlayerEntity bot, boolean enable) {
        registerBot(bot);
        setAssistAllies(bot, enable);
        String message = enable
                ? "I'll defend nearby bots when they are attacked."
                : "I'll focus on my own fights.";
        sendBotMessage(bot, message);
        return message;
    }

    private static BlockPos resolveSpawnPoint(ServerWorld world) {
        if (world == null) {
            return BlockPos.ORIGIN;
        }
        try {
            net.minecraft.world.WorldProperties.SpawnPoint sp = world.getSpawnPoint();
            if (sp != null) {
                BlockPos pos = sp.getPos();
                if (pos != null) {
                    return pos;
                }
            }
        } catch (Throwable ignored) {
        }
        return BlockPos.ORIGIN;
    }

    public static Mode getCurrentMode() {
        BotCommandStateService.State state = primaryState();
        return state != null ? state.mode : Mode.IDLE;
    }

    public static Mode getCurrentMode(ServerPlayerEntity bot) {
        return getMode(bot);
    }

    public static boolean isPassiveMode() {
        Mode mode = getCurrentMode();
        return mode == Mode.IDLE || mode == Mode.STAY || mode == Mode.GUARD || mode == Mode.PATROL;
    }

    public static CombatStyle getCombatStyle() {
        return combatStyle;
    }

    public static boolean engageImmediateThreats(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        MinecraftServer srv = bot.getCommandSource().getServer();
        if (srv == null) {
            return false;
        }
        List<Entity> hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10.0D)
                .stream()
                .filter(EntityUtil::isHostile)
                .toList();
        if (hostiles.isEmpty()) {
            return false;
        }
        srv.execute(() -> engageHostiles(bot, srv, hostiles));
        return true;
    }

    public static Vec3d getGuardCenterVec() {
        UUID primary = BotLifecycleService.getPrimaryBotUuid();
        return primary != null ? GuardPatrolService.getGuardCenter(primary) : null;
    }

    public static double getGuardRadiusValue() {
        UUID primary = BotLifecycleService.getPrimaryBotUuid();
        return GuardPatrolService.getGuardRadius(primary);
    }

    public static ServerPlayerEntity getFollowTarget() {
        BotCommandStateService.State state = primaryState();
        UUID targetUuid = state != null ? state.followTargetUuid : null;
        if (targetUuid == null || server == null) {
            return null;
        }
        return server.getPlayerManager().getPlayer(targetUuid);
    }

    public static UUID getFollowTargetUuid(ServerPlayerEntity bot) {
        return getFollowTargetFor(bot);
    }

    public static UUID getFollowTargetUuid() {
        BotCommandStateService.State state = primaryState();
        return state != null ? state.followTargetUuid : null;
    }

    public static String setCombatStyle(ServerPlayerEntity bot, CombatStyle style) {
        combatStyle = style;
        String message = style == CombatStyle.AGGRESSIVE ?
                "Combat stance set to aggressive." :
                "Combat stance set to evasive.";
        sendBotMessage(bot, message);
        return message;
    }

    private static boolean handleFollow(ServerPlayerEntity bot, BotCommandStateService.State state, MinecraftServer server, List<Entity> hostileEntities) {
        UUID targetUuid = state != null ? state.followTargetUuid : null;
        BlockPos fixedGoal = state != null ? state.followFixedGoal : null;
        ServerPlayerEntity target = targetUuid != null && server != null
                ? server.getPlayerManager().getPlayer(targetUuid)
                : null;
        if (target == null && fixedGoal == null) {
            setMode(bot, Mode.IDLE);
            setFollowTarget(bot, null);
	            if (bot != null) {
	                UUID id = bot.getUuid();
	                FollowStateService.clearAll(id);
	                FollowDebugService.clear(id);
		            }
	            sendBotMessage(bot, "Follow target lost. Returning to idle.");
	            return false;
	        }

        List<Entity> augmentedHostiles = new ArrayList<>(hostileEntities);

        if (!augmentedHostiles.isEmpty() && engageHostiles(bot, server, augmentedHostiles)) {
            return true;
        }

        lowerShieldTracking(bot);

        if (ElytraFlightService.isInFlight(bot.getUuid())) {
            return true; // ElytraFlightService handles all movement while flying
        }

        // Tree-stuck escape: if the bot is stranded on a tree canopy, handle it before normal follow.
        if (TreeStuckEscapeService.tryEscape(bot, server)) {
            return true; // escape in progress, skip normal follow
        }

        Vec3d targetPos = fixedGoal != null ? Vec3d.ofCenter(fixedGoal) : positionOf(target);

        // Post-combat arrow recovery (FOLLOW mode):
        // Only when following an actual player (not fixed-goal return-to-base/come),
        // and keep it bounded near the commander so we don't take long detours.
        if (target != null && fixedGoal == null && server != null) {
            double distToCommanderSq = bot.squaredDistanceTo(targetPos);
            if (distToCommanderSq <= 16.0D * 16.0D) {
                if (BotArrowRecoveryService.tryRecoverMissedArrows(bot, server, targetPos, 10.0D)) {
                    return true;
                }
            }
        }

    double distanceSq = bot.squaredDistanceTo(targetPos);
    double horizDistSq = horizontalDistanceSq(bot, targetPos);
	        if (target != null) {
            handleFollowPersonalSpace(bot, target, horizDistSq, targetPos);
	        }
        boolean soulOfEnderActive = net.wcfcarolina13.GameAI.services.SoulOfEnderService.isActive(bot.getUuid());
        boolean forceWalk = state != null && state.followNoTeleport && !soulOfEnderActive;
        double stopRange = state != null ? state.followStopRange : 0.0D;
        boolean allowTeleportPref = (SkillPreferences.teleportDuringSkills(bot) || SkillPreferences.followTeleport(bot) || soulOfEnderActive) && !forceWalk;
        boolean canSee = target != null && bot.canSee(target);
        if (fixedGoal != null) {
            canSee = !isDirectRouteBlocked(bot, targetPos, fixedGoal);
        }
        double deltaY = fixedGoal != null
                ? (targetPos.y - bot.getY())
                : (target.getY() - bot.getY());
        double absDeltaY = Math.abs(deltaY);
        MinecraftServer srv = bot.getCommandSource().getServer();

        if (fixedGoal != null && state != null && srv != null) {
            long nowTick = srv.getTicks();
            if (state.comeRecoverySkillInFlight) {
                boolean hasTask = TaskService.hasActiveTask(bot.getUuid());
                long startedTick = state.comeRecoverySkillStartTick;
                long ageTicks = startedTick > 0L ? Math.max(0L, nowTick - startedTick) : 0L;
                if (!hasTask && startedTick > 0L && ageTicks >= COME_RECOVERY_STALE_TICKS) {
                    LOGGER.warn("[ComeRecovery] stale-inflight-cleared bot={} goal={} ageTicks={} hasTask={}",
                            bot.getName().getString(),
                            fixedGoal.toShortString(),
                            ageTicks,
                            hasTask);
                    state.comeRecoverySkillInFlight = false;
                    state.comeRecoverySkillStartTick = 0L;
                    state.comeNextSkillTick = Math.max(state.comeNextSkillTick, nowTick + 40L);
                }
            }

            double goalDistSq = bot.getBlockPos().getSquaredDistance(fixedGoal);
            if (!Double.isFinite(state.comeBestGoalDistSq)) {
                state.comeBestGoalDistSq = goalDistSq;
                state.comeTicksSinceBest = 0;
            } else {
                // Only count progress if we beat the previous best by a meaningful amount.
                if (goalDistSq <= state.comeBestGoalDistSq - 1.0D) {
                    state.comeBestGoalDistSq = goalDistSq;
                    state.comeTicksSinceBest = 0;
                    state.comeRerouteAttempts = 0;
                    state.comeNextRerouteTick = 0L;
                } else {
                    state.comeTicksSinceBest++;
                }
            }

            // If we're vertically separated and have no LoS to the goal, try mining recovery sooner.
            boolean verticalProblem = absDeltaY >= 6.0D && !canSee;
            int triggerTicks = verticalProblem ? 25 : 60;

            if (state.comeAllowRecoverySkills && state.comeTicksSinceBest >= triggerTicks) {
                if (state.comeRecoverySkillInFlight) {
                    if ((state.comeTicksSinceBest % 20) == 0) {
                        LOGGER.info("[ComeRecovery] launch-wait bot={} goal={} ticksSinceBest={} startedTick={} nowTick={} hasTask={}",
                                bot.getName().getString(),
                                fixedGoal.toShortString(),
                                state.comeTicksSinceBest,
                                state.comeRecoverySkillStartTick,
                                nowTick,
                                TaskService.hasActiveTask(bot.getUuid()));
                    }
                } else if (nowTick < state.comeNextSkillTick) {
                    if (state.comeTicksSinceBest % 20 == 0) {
                        LOGGER.info("[FollowAssert] recovery-cooldown bot={} goal={} ticksSinceBest={} nowTick={} nextSkillTick={}",
                                bot.getName().getString(),
                                fixedGoal.toShortString(),
                                state.comeTicksSinceBest,
                                nowTick,
                                state.comeNextSkillTick);
                    }
                } else if (triggerComeRecoverySkill(bot, target, fixedGoal, targetPos, deltaY, horizDistSq, srv, state)) {
                    return true;
                }
            } else if (!state.comeAllowRecoverySkills && state.comeTicksSinceBest == triggerTicks) {
                LOGGER.info("[FollowAssert] recovery-suppressed bot={} goal={} reason=safe-regroup ticksSinceBest={}",
                        bot.getName().getString(),
                        fixedGoal.toShortString(),
                        state.comeTicksSinceBest);
            }
        }
        if (target != null && bot.getEntityWorld() != target.getEntityWorld() && srv != null) {
            long now = srv.getOverworld().getTime();
            // Cooldown: only attempt dimension teleport once every 100 ticks (5s)
            // to avoid spamming during rapid portal transitions
            if (now - state.dimHandoffNotifiedTick < 100L) {
                return false;
            }
            state.dimHandoffNotifiedTick = now;

            ServerWorld targetWorld = srv.getWorld(target.getEntityWorld().getRegistryKey());
            if (targetWorld == null) {
                LOGGER.warn("Follow dimension handoff: unable to resolve target world {} for {}",
                        target.getEntityWorld().getRegistryKey().getValue(),
                        bot.getName().getString());
                return false;
            }

            // Abort active skills and dismount before dimension change
            TaskService.forceAbort(bot.getUuid(), "\u00a7cDimension transition (following commander).");
            if (bot.hasVehicle()) {
                bot.stopRiding();
            }
            if (ElytraFlightService.isInFlight(bot.getUuid())) {
                bot.stopGliding();
                // ElytraFlightService will clean up on next tick when it sees phase mismatch
            }

            LOGGER.info("Follow dimension handoff: teleporting {} from {} to {} (following {})",
                    bot.getName().getString(),
                    bot.getEntityWorld().getRegistryKey().getValue(),
                    targetWorld.getRegistryKey().getValue(),
                    target.getName().getString());

            // Bring the bot's mount along across the dimension boundary.
            // Vanilla cross-dim teleport on a vehicle handles passenger
            // transfer; we explicitly do the bot-then-mount pair here so
            // the mount arrives near the bot's destination rather than
            // staying behind in the source dimension. If the mount can't
            // be safely placed, abort the bot teleport too — better to stay
            // with the horse than strand the pair.
            if (!net.wcfcarolina13.GameAI.services.TravelMountHandler.coTeleportSavedMount(
                    bot, targetWorld, target.getBlockPos())) {
                LOGGER.info("Cross-dim follow teleport deferred: bot={} target={} reason=mount-no-safe-spot",
                        bot.getName().getString(), target.getName().getString());
                return false;
            }
            bot.teleport(targetWorld,
                    target.getX(), target.getY(), target.getZ(),
                    EnumSet.noneOf(PositionFlag.class),
                    target.getYaw(), target.getPitch(),
                    true);
            bot.setVelocity(Vec3d.ZERO);

            // Dimension-specific personality line via overhead dialogue
            {
                String dimPath = targetWorld.getRegistryKey().getValue().getPath();
                String line;
                if (dimPath.contains("end")) {
                    line = RANDOM.nextBoolean()
                            ? "I'll follow you to the end...is that corny?"
                            : "The End. Sure. Why not.";
                } else if (dimPath.contains("nether")) {
                    line = RANDOM.nextBoolean()
                            ? "Into the fire with you, then."
                            : "Nether it is. Stay close.";
                } else {
                    line = RANDOM.nextBoolean()
                            ? "Fresh air. Finally."
                            : "Back to the surface. Good.";
                }
                CompanionOverheadDialogueService.showOverheadLine(bot, line, 3_000, 48.0, "follow-dim", dimPath);
            }

            return true; // teleported successfully, follow continues next tick
        }
        LOGGER.debug("Follow tick: bot={} target={} dist={}/{} dy={} forceWalk={} allowTpPref={} canSee={} stopRange={}",
                bot.getName().getString(),
                target != null ? target.getName().getString() : "goal",
                Math.sqrt(distanceSq),
                fixedGoal != null ? fixedGoal : target.getBlockPos(),
                String.format(Locale.ROOT, "%.2f", deltaY),
                forceWalk,
                allowTeleportPref,
                canSee,
                stopRange);
        if (fixedGoal == null && target != null && srv != null && !ElytraFlightService.isInFlight(bot.getUuid())) {
            double botAboveTarget = bot.getY() - target.getY();
            // Only attempt elytra glide for cliff/mountain scenarios where the player is
            // horizontally distant.  If they're directly below (underground/hole), elytra
            // can't descend into a tunnel — the bot should wait at the opening instead.
            if (botAboveTarget >= 6.0D && horizDistSq > 10.0D * 10.0D) {
                if (ElytraFlightService.tryAutonomousFollowFlightNow(srv, bot, srv.getTicks())) {
                    return true;
                }
            }
        }
        // stopRange is used for "come"-style one-shot moves: do not stop if we're vertically far away
        // (e.g., standing under the commander on a floor below).
        if (fixedGoal != null && stopRange > 0.0D) {
            double stopRangeSq = stopRange * stopRange;
            if (horizDistSq <= stopRangeSq && absDeltaY <= 2.5D) {
                stopFollowing(bot);
                return true;
            }
        }
        // Come-mode early-exit: if the live player has moved back within reach (e.g. after a regroup
        // pillar-up) before the bot reaches the stale fixed goal, resume normal follow immediately.
        // This prevents the bot from walking all the way to where the player WAS.
        // Y tolerance is generous (10 blocks) because after recovery the bot may surface several
        // blocks below the player; canSee already guarantees reachability via normal follow.
        if (fixedGoal != null && target != null && state != null) {
            Vec3d liveTargetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
            double liveHorizSq = horizontalDistanceSq(bot, liveTargetPos);
            double liveDeltaY = Math.abs(target.getY() - bot.getY());
            boolean canSeeTarget = bot.canSee(target);
            // Reachability fallback: if can't see but within 64 blocks, probe pathfinding (150ms budget).
            // Prevents bot from walking back down its own tunnel after surfacing on a hill.
            // Cooldown: only probe every 2 seconds to avoid tick-by-tick pathfinding storms.
            boolean reachable = canSeeTarget;
            if (!reachable && liveHorizSq <= 64.0D * 64.0D
                    && server.getTicks() % 40 == 0
                    && bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld reachWorld) {
                reachable = net.wcfcarolina13.PathFinding.PathFinder.canReach(
                        bot.getBlockPos(), target.getBlockPos(), reachWorld, 150L);
            }
            if (liveHorizSq <= 8.0D * 8.0D && liveDeltaY <= 10.0D && reachable) {
                state.followFixedGoal = null;
                state.comeBestGoalDistSq = Double.NaN;
                state.comeTicksSinceBest = 0;
                state.comeRerouteAttempts = 0;
                state.comeNextRerouteTick = 0L;
                state.comeNextSkillTick = 0L;
                state.comeRecoverySkillInFlight = false;
                state.comeRecoverySkillAttempts = 0;
                FollowStateService.FOLLOW_AUTO_REGROUP_ATTEMPTS.remove(bot.getUuid());
                FollowStateService.FOLLOW_WAYPOINTS.remove(bot.getUuid());
                // Clear stale stuck state from come-mode so mine-escape doesn't fire immediately
                // when normal follow resumes (stagnant counter would carry over otherwise).
                net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.clear(bot.getUuid());
                BotActions.stop(bot);
                maybeLogFollowDecision(bot, "come-early-exit: live player in reach liveHorizDist="
                        + String.format(Locale.ROOT, "%.2f", Math.sqrt(liveHorizSq))
                        + " liveDeltaY=" + String.format(Locale.ROOT, "%.2f", liveDeltaY));
                return true;
            } else if (liveHorizSq <= 12.0D * 12.0D) {
                // Near-miss: log why early-exit didn't fire to help diagnose future issues
                boolean hasLos = bot.canSee(target);
                maybeLogFollowDecision(bot, "come-early-exit SKIP: horizDist="
                        + String.format(Locale.ROOT, "%.1f", Math.sqrt(liveHorizSq))
                        + " deltaY=" + String.format(Locale.ROOT, "%.1f", liveDeltaY)
                        + " canSee=" + hasLos
                        + (liveHorizSq > 8.0D * 8.0D ? " [horiz>8]" : "")
                        + (liveDeltaY > 10.0D ? " [deltaY>10]" : "")
                        + (!hasLos ? " [no-LOS]" : ""));
            }
        }

        // Regroup distance safeguard: if the player has moved >128 blocks from the snapshot goal
        // and recovery skills are disabled (pure pathfinding regroup), stop and wait.
        if (fixedGoal != null && target != null && state != null
                && !state.comeAllowRecoverySkills && state.baseTarget == null) {
            double playerToGoalDx = target.getX() - fixedGoal.getX();
            double playerToGoalDz = target.getZ() - fixedGoal.getZ();
            double playerToGoalDistSq = playerToGoalDx * playerToGoalDx + playerToGoalDz * playerToGoalDz;
            if (playerToGoalDistSq > 128.0D * 128.0D) {
                // Player moved far from where they were when regroup started — stop and wait
                setMode(bot, Mode.STAY);
                setGuardState(bot, positionOf(bot), 8.0D);
                String coords = fixedGoal.getX() + ", " + fixedGoal.getY() + ", " + fixedGoal.getZ();
                boolean sunsetReturn = net.wcfcarolina13.GameAI.services.BotHomeService.isAutoReturnAtSunset(bot);
                String botName2 = bot.getName().getString();
                if (sunsetReturn) {
                    sendBotMessage(bot, botName2 + " will wait for you to return to " + coords + ", or return home at sunset.");
                } else {
                    sendBotMessage(bot, botName2 + " will wait for you to return to " + coords + ".");
                }
                state.followFixedGoal = null;
                return true;
            }
        }

        // Special case: return-to-base uses FOLLOW with a fixed goal, but keeps baseTarget populated.
        // Use the older RETURNING_BASE arrival semantics (arrive -> STAY) while benefiting from
        // FOLLOW's door/corner/waypoint planning.
        if (fixedGoal != null && state != null && state.baseTarget != null) {
            double dxHome = state.baseTarget.x - bot.getX();
            double dzHome = state.baseTarget.z - bot.getZ();
            double homeHorizDistSq = dxHome * dxHome + dzHome * dzHome;
            if (homeHorizDistSq <= 3.0D * 3.0D) {
                UUID id = bot.getUuid();
                FollowStateService.clearAll(id);
                FollowDebugService.clear(id);
                setFollowTarget(bot, null);
                state.followNoTeleport = false;
                state.followStopRange = 0.0D;
                state.followStandoffRange = 0.0D;
                state.followFixedGoal = null;
                state.comeBestGoalDistSq = Double.NaN;
                state.comeTicksSinceBest = 0;
                state.comeRerouteAttempts = 0;
                state.comeNextRerouteTick = 0L;
                state.comeNextSkillTick = 0L;
                state.comeRecoverySkillInFlight = false;
                state.comeRecoverySkillStartTick = 0L;
                state.comeAllowRecoverySkills = true;
                BotActions.stop(bot);
                setMode(bot, Mode.IDLE);
                setBaseTarget(bot, null);
                net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.clear(bot.getUuid());
                MinecraftServer arrivalServer = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
                if (!net.wcfcarolina13.GameAI.services.BotAutoReturnSunsetService.handleArrival(bot, arrivalServer)) {
                    sendBotMessage(bot, "Arrived at base.");
                }
                return true;
            }
            
            // Bot is returning to base but hasn't arrived yet - check for stuck condition
            // This enables progressive escape attempts (backup, pillar, flare) when stuck
            LOGGER.debug("Return-base stuck check: calling tickAndCheckStuck for {} at {}", 
                    bot.getName().getString(), bot.getBlockPos().toShortString());
            if (net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.tickAndCheckStuck(bot, state.baseTarget)) {
                triggerStuckFlare(bot);
            }
        }

        UUID botId = bot.getUuid();
        if (fixedGoal == null && target != null) {
            updateCommanderLadderHint(bot, target);
        } else {
            FOLLOW_COMMANDER_LADDER_HINT.remove(botId);
        }
        BlockPos navGoalBlock = fixedGoal != null ? fixedGoal : target.getBlockPos();
        Vec3d navGoalPos = targetPos;
        boolean usingWaypoints = false;
        ArrayDeque<BlockPos> waypoints = FollowStateService.FOLLOW_WAYPOINTS.get(botId);
        int waypointCount = waypoints != null ? waypoints.size() : 0;
        if (waypoints != null) {
            while (!waypoints.isEmpty()) {
	                BlockPos peek = waypoints.peekFirst();
	                if (peek == null) {
	                    waypoints.pollFirst();
	                    continue;
	                }
	                // Never target door blocks as waypoints: standing "in" the door block can trigger rescue logic
	                // and cause oscillation at doorway thresholds. Instead, expand the door waypoint into
	                // approach+step tiles on either side of the doorway.
	                if (bot.getEntityWorld() instanceof ServerWorld world) {
	                    BlockState peekState = world.getBlockState(peek);
	                    BlockState peekUpState = world.getBlockState(peek.up());
                        boolean peekIsOpenable = peekState.getBlock() instanceof DoorBlock
                                || peekUpState.getBlock() instanceof DoorBlock
                                || peekState.getBlock() instanceof FenceGateBlock
                                || peekUpState.getBlock() instanceof FenceGateBlock;
                        if (peekIsOpenable) {
	                        FollowDoorPlan doorWpPlan = buildFollowDoorPlan(bot, world, peek);
	                        if (doorWpPlan != null) {
	                            waypoints.pollFirst();
	                            waypoints.addFirst(doorWpPlan.stepThroughPos().toImmutable());
	                            waypoints.addFirst(doorWpPlan.approachPos().toImmutable());
	                            continue;
	                        }
	                    }
	                }
	                if (bot.getBlockPos().getSquaredDistance(peek) <= FollowPathService.WAYPOINT_REACH_SQ) {
	                    waypoints.pollFirst();
	                    FollowStateService.FOLLOW_LAST_DISTANCE_SQ.remove(botId);
	                    FollowStateService.FOLLOW_STAGNANT_TICKS.remove(botId);
                    FollowStateService.FOLLOW_LAST_BLOCK_POS.remove(botId);
                    FollowStateService.FOLLOW_POS_STAGNANT_TICKS.remove(botId);
                    continue;
                }
                navGoalBlock = peek;
                navGoalPos = Vec3d.ofCenter(peek);
                usingWaypoints = true;
                break;
            }
            if (waypoints.isEmpty()) {
                FollowStateService.FOLLOW_WAYPOINTS.remove(botId);
                usingWaypoints = false;
                navGoalBlock = fixedGoal != null ? fixedGoal : target.getBlockPos();
                navGoalPos = targetPos;
                waypointCount = 0;
                // When waypoints are exhausted but goal is still far, request fresh waypoints
                // rather than falling through to followInputStep which can't navigate corners.
                if (fixedGoal != null && bot.getBlockPos().getSquaredDistance(fixedGoal) > 36.0D) {
                    requestFollowPathPlanToGoal(bot, fixedGoal, false, "waypoints-exhausted-far");
                }
            }
        }
	        // If the commander is far away, do not let stale local waypoints keep us orbiting a doorway.
	        // Once we're out of a tight enclosure, prioritise direct pursuit / long-range catch-up.
	        if (usingWaypoints && distanceSq >= 900.0D) { // ~30 blocks
	            BotStuckService.EnvironmentSnapshot env = BotStuckService.analyzeEnvironment(bot);
	            if (env != null && (!env.enclosed() || env.hasEscapeRoute())) {
		                FollowStateService.FOLLOW_WAYPOINTS.remove(botId);
		                FollowStateService.FOLLOW_DOOR_PLAN.remove(botId);
		                FollowStateService.FOLLOW_DOOR_LAST_BLOCK.remove(botId);
	                FollowStateService.FOLLOW_DOOR_STUCK_TICKS.remove(botId);
	                FollowStateService.FOLLOW_DOOR_RECOVERY.remove(botId);
	                usingWaypoints = false;
	                navGoalBlock = fixedGoal != null ? fixedGoal : target.getBlockPos();
	                navGoalPos = targetPos;
	                maybeLogFollowDecision(bot, "drop-waypoints: long-range target dist="
	                        + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq)));
	            }
	        }
        // In come mode on flat terrain with an unobstructed path to the goal, drop waypoints so
        // the bot moves in a smooth straight line rather than hopping block-by-block.
        if (usingWaypoints && fixedGoal != null && absDeltaY <= 2.0D) {
            boolean directToGoalClear = !isDirectRouteBlocked(bot, targetPos, fixedGoal);
            if (directToGoalClear) {
                FollowStateService.FOLLOW_WAYPOINTS.remove(botId);
                FollowStateService.FOLLOW_DOOR_PLAN.remove(botId);
                FollowStateService.FOLLOW_DOOR_LAST_BLOCK.remove(botId);
                FollowStateService.FOLLOW_DOOR_STUCK_TICKS.remove(botId);
                FollowStateService.FOLLOW_DOOR_RECOVERY.remove(botId);
                usingWaypoints = false;
                navGoalBlock = fixedGoal;
                navGoalPos = targetPos;
                maybeLogFollowDecision(bot, "drop-waypoints: come-mode flat terrain direct-clear dy="
                        + String.format(Locale.ROOT, "%.2f", absDeltaY));
            }
        }

        double progressDistSq = bot.getBlockPos().getSquaredDistance(navGoalBlock);
        boolean directBlocked = progressDistSq <= 36.0D && isDirectRouteBlocked(bot, navGoalPos, navGoalBlock);
        boolean botSealed = isSealedSpace(bot);
        boolean commanderSealed = target != null && isSealedSpace(target);
        maybeLogFollowStatus(bot, target, distanceSq, horizDistSq, canSee, directBlocked, usingWaypoints, navGoalBlock, waypointCount, botSealed, commanderSealed);

        if (handleFollowObstacles(bot, target, navGoalPos, navGoalBlock, progressDistSq, distanceSq, absDeltaY, canSee, directBlocked, allowTeleportPref, forceWalk, srv)) {
            return true;
        }
        // Personal space / standoff applies even when following waypoints; otherwise the bot can "pile onto" the commander
        // when the final waypoint is near the player.
        double desiredSpace = FOLLOW_PERSONAL_SPACE;
        if (target != null && state != null && state.followStandoffRange > 0.0D) {
            desiredSpace = Math.max(desiredSpace, state.followStandoffRange);
        }
        double personalSpaceSq = desiredSpace * desiredSpace;
        // Personal space only applies when following an actual entity. For fixed-goal follow,
        // stopping early based on Euclidean distance can strand the bot behind doors/walls.
        if (target != null && canSee && horizDistSq <= personalSpaceSq) {
            // When the player is significantly below the bot (went underground), stay put and
            // announce rather than silently stopping.  Do not auto-descend — it could be fatal.
            // Only trigger when the player is genuinely far below (6+ blocks) — 3 blocks triggers
            // on normal slopes and staircases.  Also require player to be BELOW the bot (deltaY < 0).
            if (fixedGoal == null && deltaY < -6.0D) {
                long nowTick = srv != null ? srv.getTicks() : 0L;
                // Track when the bot first started waiting at this drop-off.
                FollowStateService.FOLLOW_WAIT_ABOVE_START_TICK.putIfAbsent(botId, nowTick);

                long lastAnnounce = FollowStateService.FOLLOW_WAIT_ABOVE_ANNOUNCED_TICK.getOrDefault(botId, 0L);
                if (nowTick - lastAnnounce >= 600L) { // announce at most every 30s
                    FollowStateService.FOLLOW_WAIT_ABOVE_ANNOUNCED_TICK.put(botId, nowTick);
                    CompanionOverheadDialogueService.showOverheadLine(bot,
                            "Waiting by the opening.", 4_000, 48.0, "follow-wait-above", null);
                    net.wcfcarolina13.GameAI.services.EmotecraftBridge.playEmote(
                            bot, net.wcfcarolina13.GameAI.services.EmotecraftBridge.EmoteId.HERE);
                    // Check auto-regroup config to tailor the message.
                    boolean autoRegroupEnabled = false;
                    if (net.wcfcarolina13.Frens.CONFIG != null) {
                        String alias = bot.getName().getString();
                        String wk = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(srv);
                        autoRegroupEnabled = net.wcfcarolina13.Frens.CONFIG.getOrCreateBotControl(alias, wk).isAutoRegroupOnLost();
                    }
                    if (autoRegroupEnabled) {
                        sendBotMessage(bot, "I can see you down there but it's too dangerous to follow. I'll find a way down shortly.");
                    } else {
                        sendBotMessage(bot, "I can see you down there but it's too dangerous to follow. Use /bot regroup when you're ready.");
                    }
                }

                // Auto-regroup: if the toggle is enabled and the bot has been waiting ~2 minutes, descend.
                long waitStart = FollowStateService.FOLLOW_WAIT_ABOVE_START_TICK.getOrDefault(botId, nowTick);
                if (nowTick - waitStart >= 2400L && target != null && srv != null) {
                    boolean autoRegroup = false;
                    if (net.wcfcarolina13.Frens.CONFIG != null) {
                        String alias = bot.getName().getString();
                        String wk = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(srv);
                        autoRegroup = net.wcfcarolina13.Frens.CONFIG.getOrCreateBotControl(alias, wk).isAutoRegroupOnLost();
                    }
                    if (autoRegroup) {
                        FollowStateService.FOLLOW_WAIT_ABOVE_START_TICK.remove(botId);
                        FollowStateService.FOLLOW_WAIT_ABOVE_ANNOUNCED_TICK.remove(botId);
                        CompanionOverheadDialogueService.showOverheadLine(bot,
                                "Coming to find you.", 3_000, 48.0, "follow-auto-regroup", null);
                        BlockPos goal = target.getBlockPos().toImmutable();
                        net.minecraft.server.world.ServerWorld sw = target.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld s ? s : null;
                        if (sw != null) {
                            BlockPos safe = net.wcfcarolina13.GameAI.services.SafePositionService.findForwardSafeSpot(sw, target);
                            if (safe == null) {
                                safe = net.wcfcarolina13.GameAI.services.SafePositionService.findSafeNear(sw, goal, 8);
                            }
                            if (safe != null) {
                                goal = safe;
                            }
                        }
                        // No recovery skills — pure pathfinding, same as manual regroup
                        setComeModeWalk(bot, target, goal, 3.2D, false);
                        return true;
                    }
                }
            } else {
                // Player is no longer far below — clear the drop-off wait timer.
                FollowStateService.FOLLOW_WAIT_ABOVE_START_TICK.remove(botId);
            }
            FOLLOW_WAYPOINTS.remove(botId);
            FOLLOW_DOOR_PLAN.remove(botId);
            BotActions.stop(bot);
            LookController.faceBlock(bot, BlockPos.ofFloored(targetPos));
            return true;
        }
        if (usingWaypoints) {
            double sprintDistanceSq = Math.max(distanceSq, progressDistSq);
            boolean sprint = sprintDistanceSq > FOLLOW_SPRINT_DISTANCE_SQ;
            moveToward(bot, navGoalPos, 1.0D, sprint);
        } else {
            // If we're physically blocked (glass/fence/door), don't "settle" just because we're close in Euclidean distance.
            boolean allowCloseStop = canSee && !directBlocked;
            followInputStep(bot, targetPos, horizDistSq, allowCloseStop, desiredSpace);
        }
        return true;
    }

    /**
     * Post-combat cleanup: recover arrows, then sweep drops.  Returns true if
     * this method is actively driving bot movement (caller should skip mode handler).
     * For STAY mode the bot returns to its post after the sweep finishes.
     */
    private static boolean tickPostCombatSweep(ServerPlayerEntity bot,
                                                MinecraftServer server,
                                                Mode mode) {
        String botName = bot.getName().getString();

        // Anchor for bounded recovery (STAY/GUARD use guard center).
        Vec3d anchor = null;
        if (mode == Mode.STAY || mode == Mode.GUARD) {
            anchor = getGuardCenter(bot);
        }
        if (anchor == null) {
            anchor = positionOf(bot);
        }
        double arrowRadius = (mode == Mode.STAY) ? 18.0D : 24.0D;

        // Step 1: arrow recovery (has its own 2 s safety delay).
        if (BotArrowRecoveryService.tryRecoverMissedArrows(bot, server, anchor, arrowRadius)) {
            LOGGER.debug("[PostCombatSweep] {} arrow-recovery driving movement", botName);
            return true;
        }

        // Step 2: drop sweep – let an in-flight sweep keep driving.
        if (DropSweepService.isInProgressFor(bot) && !DropSweepService.isCancelRequestedFor(bot)) {
            return true;
        }

        // Step 3: search for drops near bot AND near the combat center (if bot drifted).
        double sweepRadius = 16.0D;
        if (!DropSweepService.isInProgress()) {
            Entity nearestItem = findNearestDrop(bot, sweepRadius);

            // If nothing near the bot, check around the combat center (where the fight happened).
            Vec3d combatCenter = BotCombatCalloutService.getLastCombatCenter(bot.getUuid());
            if (nearestItem == null && combatCenter != null
                    && positionOf(bot).distanceTo(combatCenter) > 4.0D
                    && bot.getEntityWorld() instanceof ServerWorld sw) {
                Box ccBox = new Box(
                        combatCenter.x - sweepRadius, combatCenter.y - 6.0D, combatCenter.z - sweepRadius,
                        combatCenter.x + sweepRadius, combatCenter.y + 6.0D, combatCenter.z + sweepRadius);
                Entity ccItem = sw.getEntitiesByClass(ItemEntity.class, ccBox,
                                drop -> drop.isAlive() && !drop.isRemoved())
                        .stream()
                        .min(java.util.Comparator.comparingDouble(e -> e.squaredDistanceTo(combatCenter.x, combatCenter.y, combatCenter.z)))
                        .orElse(null);
                if (ccItem != null) {
                    // Drops exist near combat center — walk back to collect them.
                    LOGGER.debug("[PostCombatSweep] {} drops found near combat center ({},{},{}) — walking back",
                            botName, (int) combatCenter.x, (int) combatCenter.y, (int) combatCenter.z);
                    BlockPos ccBlock = BlockPos.ofFloored(combatCenter.x, combatCenter.y, combatCenter.z);
                    double ccDistSq = bot.squaredDistanceTo(combatCenter);
                    FollowMovementService.followWaypointStep(bot, ccBlock, ccDistSq,
                            64.0D, () -> lowerShieldTracking(bot));
                    return true;
                }
            }

            if (nearestItem != null) {
                LOGGER.debug("[PostCombatSweep] {} starting drop sweep, nearest={}", botName,
                        nearestItem.getBlockPos().toShortString());
                collectNearbyDrops(bot, sweepRadius);
                if (DropSweepService.isInProgressFor(bot)) {
                    return true;
                }
            }
        }

        // Step 3b: check near recorded kill positions (ranged kills, mob death sites).
        // Uses a wider 12-block radius and waypoint-step movement for better terrain navigation.
        if (bot.getEntityWorld() instanceof ServerWorld killSw) {
            java.util.List<Vec3d> killPositions = BotCombatCalloutService.getRecentKillPositions(bot.getUuid());
            Vec3d botPos = positionOf(bot);
            killPositions.sort(java.util.Comparator.comparingDouble(kp -> botPos.squaredDistanceTo(kp)));
            for (Vec3d kp : killPositions) {
                double distToKp = botPos.distanceTo(kp);
                if (distToKp < 3.0D) continue; // already here, normal pickup handles it
                Box kpBox = new Box(kp.x - 12, kp.y - 6, kp.z - 12, kp.x + 12, kp.y + 6, kp.z + 12);
                boolean hasItems = !killSw.getEntitiesByClass(ItemEntity.class, kpBox,
                        d -> d.isAlive() && !d.isRemoved()).isEmpty();
                if (hasItems) {
                    LOGGER.debug("[PostCombatSweep] {} walking to kill site ({},{},{}) dist={}",
                            botName, (int) kp.x, (int) kp.y, (int) kp.z,
                            String.format(Locale.ROOT, "%.1f", distToKp));
                    // Use waypoint-step for better obstacle handling (tree trunks, hills)
                    BlockPos kpBlock = BlockPos.ofFloored(kp.x, kp.y, kp.z);
                    double distSq = bot.squaredDistanceTo(kp);
                    FollowMovementService.followWaypointStep(bot, kpBlock, distSq,
                            64.0D, () -> lowerShieldTracking(bot));
                    return true;
                }
            }
        }

        // Step 4: pick up ground arrows (mob-fired arrows the recovery service missed).
        if (bot.getEntityWorld() instanceof ServerWorld sw) {
            double arrowSweepRadius = 16.0D;
            Box arrowBox = bot.getBoundingBox().expand(arrowSweepRadius, 6.0D, arrowSweepRadius);
            PersistentProjectileEntity nearestArrow = sw.getEntitiesByClass(
                    PersistentProjectileEntity.class, arrowBox,
                    p -> !p.isRemoved()
                            && p.isAlive()
                            && p.pickupType == PersistentProjectileEntity.PickupPermission.ALLOWED
                            && p.getVelocity().lengthSquared() < 0.001D
                            && p.squaredDistanceTo(bot) > 1.0D
            ).stream()
                    .min(java.util.Comparator.comparingDouble(bot::squaredDistanceTo))
                    .orElse(null);
            if (nearestArrow != null) {
                Vec3d arrowPos = new Vec3d(nearestArrow.getX(), nearestArrow.getY(), nearestArrow.getZ());
                boolean sprint = bot.squaredDistanceTo(arrowPos) > 100.0D;
                moveToward(bot, arrowPos, 0.25D, sprint);
                LOGGER.debug("[PostCombatSweep] {} walking to ground arrow", botName);
                return true;
            }
        }

        // Step 5: STAY mode – return to post if we drifted during the sweep.
        if (mode == Mode.STAY) {
            Vec3d guardCenter = getGuardCenter(bot);
            if (guardCenter != null && positionOf(bot).distanceTo(guardCenter) > 1.5D) {
                moveToward(bot, guardCenter, 0.9D, false);
                return true;
            }
        }

        // Nothing left to recover – sweep complete.
        LOGGER.debug("[PostCombatSweep] {} sweep complete, nothing left to collect", botName);
        BotCombatCalloutService.clearPostCombatSweep(bot.getUuid());
        return false;
    }

    /** Ticks required before idle drop-sweep activates (15 seconds). */
    private static final long IDLE_SWEEP_DELAY_TICKS = 300L;
    /** Maximum distance the bot will walk for an idle sweep. */
    private static final double IDLE_SWEEP_RADIUS = 20.0D;
    /** No-progress timeout: abandon a sweep target the bot can't get closer to within 10 s. */
    private static final long IDLE_SWEEP_NO_PROGRESS_TIMEOUT_TICKS = 200L;
    /** Squared-distance delta that counts as "made progress" toward the target. */
    private static final double IDLE_SWEEP_PROGRESS_DELTA_SQ = 0.25D;
    /** Cooldown for an unreachable drop position (60 s). */
    private static final long IDLE_SWEEP_BLACKLIST_DURATION_TICKS = 1200L;

    /**
     * Opportunistic idle drop-sweep.  When the player and bot are standing still for 15 s,
     * the bot walks over to collect any ground items within range.  In FOLLOW mode the sweep
     * cancels the instant the player moves &gt;1 block from their snapshot block position.
     * Uses block-distance (integer positions) so looking around or sub-block jitter
     * doesn't count as movement.
     */
    private static boolean tickOpportunisticIdleSweep(ServerPlayerEntity bot,
                                                      BotCommandStateService.State state,
                                                      MinecraftServer server,
                                                      Mode mode) {
        if (bot == null || server == null) return false;
        UUID botId = bot.getUuid();
        long nowTick = server.getTicks();
        if (TaskService.hasActiveTask(botId)) {
            boolean hadIdleSweepState = Boolean.TRUE.equals(FollowStateService.IDLE_SWEEP_ACTIVE.get(botId))
                    || FollowStateService.IDLE_SWEEP_TARGET.containsKey(botId)
                    || FollowStateService.IDLE_SWEEP_START_TICK.containsKey(botId);
            boolean hadInFlightSweep = DropSweepService.isInProgressFor(bot);
            if (hadIdleSweepState || hadInFlightSweep) {
                LOGGER.info("[IdleSweep] {} suppressed — active task {}",
                        bot.getName().getString(),
                        TaskService.getActiveTaskName(botId).orElse("unknown"));
            }
            BackgroundSweepPolicy.clearPendingIdleSweepState(botId);
            DropSweepService.requestCancel(bot, "active-task");
            return false;
        }

        // Hard gate: while a real commander is mining in tight quarters, bail out of any
        // idle-sweep state (pending, in-progress, or committed) so the bot stays clear of them.
        // The 3.5s block-break window means the bot resumes normal behaviour shortly after
        // the commander stops digging.
        if (net.wcfcarolina13.GameAI.services.CommanderActivityService.isBotNearActiveMiner(bot)) {
            boolean hadIdleSweepState = Boolean.TRUE.equals(FollowStateService.IDLE_SWEEP_ACTIVE.get(botId))
                    || FollowStateService.IDLE_SWEEP_TARGET.containsKey(botId)
                    || FollowStateService.IDLE_SWEEP_START_TICK.containsKey(botId);
            boolean hadInFlightSweep = DropSweepService.isInProgressFor(bot);
            if (hadIdleSweepState || hadInFlightSweep) {
                LOGGER.debug("[IdleSweep] {} suppressed — commander mining nearby",
                        bot.getName().getString());
                FollowStateService.clearIdleSweep(botId);
                DropSweepService.requestCancel(bot, "commander-mining");
            }
            BackgroundSweepPolicy.clearPendingIdleSweepState(botId);
            return false;
        }

        // Resolve the player for FOLLOW mode movement check.
        ServerPlayerEntity commander = null;
        if (mode == Mode.FOLLOW && state != null && state.followTargetUuid != null) {
            commander = server.getPlayerManager().getPlayer(state.followTargetUuid);
        }

        BlockPos botBlock = bot.getBlockPos();

        // ===== ACTIVE SWEEP: bot is walking to / collecting drops =====
        Boolean active = FollowStateService.IDLE_SWEEP_ACTIVE.get(botId);
        if (active != null && active) {
            // Suppress idle head-rotation while we're driving a non-skill waypoint —
            // otherwise AutoFaceEntity points the bot's head at random nearby mobs
            // (the "Jake hopping while looking up at an enderman" autopsy from
            // 2026-05-06). Reset to false on every exit path that returns false.
            net.wcfcarolina13.Entity.AutoFaceEntity.setBotExecutingTask(true);

            BlockPos lastSafetyCheck = FollowStateService.IDLE_SWEEP_BOT_BLOCK.get(botId);
            if (bot.isOnGround() && (lastSafetyCheck == null || !lastSafetyCheck.equals(botBlock))
                    && bot.getEntityWorld() instanceof ServerWorld idleSweepWorld) {
                SafePositionService.SurfaceCandidateAssessment posCheck =
                        SafePositionService.analyzeSurfaceCandidate(idleSweepWorld, botBlock);
                if (!BackgroundSweepPolicy.isIdleSweepOriginSafe(posCheck)) {
                    BlockPos unsafeTarget = FollowStateService.IDLE_SWEEP_TARGET.get(botId);
                    LOGGER.info("[IdleSweep] {} cancelled — unsafe origin {} standable={} steepDrops={} blocked={}",
                            bot.getName().getString(), botBlock.toShortString(), posCheck.standable(),
                            posCheck.steepDropNeighbors(), posCheck.blockedCardinals());
                    if (unsafeTarget != null) {
                        blacklistIdleSweepTarget(botId, unsafeTarget,
                                nowTick + IDLE_SWEEP_BLACKLIST_DURATION_TICKS);
                    }
                    BackgroundSweepPolicy.clearPendingIdleSweepState(botId);
                    DropSweepService.requestCancel(bot, "unsafe-origin");
                    net.wcfcarolina13.Entity.AutoFaceEntity.setBotExecutingTask(false);
                    return false;
                }
                FollowStateService.IDLE_SWEEP_BOT_BLOCK.put(botId, botBlock.toImmutable());
            }

            // Cancel if player moved >1 block (FOLLOW mode).
            if (mode == Mode.FOLLOW && commander != null) {
                BlockPos snapPlayer = FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.get(botId);
                if (snapPlayer != null) {
                    int pdx = commander.getBlockX() - snapPlayer.getX();
                    int pdz = commander.getBlockZ() - snapPlayer.getZ();
                    if (pdx * pdx + pdz * pdz > 1) {
                        LOGGER.debug("[IdleSweep] {} cancelled — player moved", bot.getName().getString());
                        FollowStateService.clearIdleSweep(botId);
                        DropSweepService.requestCancel(bot, "player-moved");
                        net.wcfcarolina13.Entity.AutoFaceEntity.setBotExecutingTask(false);
                        return false;
                    }
                }
            }

            // Drive a running DropSweepService sweep.
            if (DropSweepService.isInProgressFor(bot) && !DropSweepService.isCancelRequestedFor(bot)) {
                return true;
            }

            // Walk toward committed target (or find a new one).
            BlockPos target = FollowStateService.IDLE_SWEEP_TARGET.get(botId);
            if (target != null) {
                double distSq = bot.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                if (distSq <= 2.25D) {
                    // Arrived — start formal pickup and look for the next item.
                    FollowStateService.IDLE_SWEEP_TARGET.remove(botId);
                    FollowStateService.IDLE_SWEEP_LAST_PROGRESS_TICK.remove(botId);
                    FollowStateService.IDLE_SWEEP_LAST_DISTANCE_SQ.remove(botId);
                    if (!DropSweepService.isInProgress()) {
                        collectNearbyDrops(bot, IDLE_SWEEP_RADIUS);
                        if (DropSweepService.isInProgressFor(bot)) {
                            return true;
                        }
                    }
                } else {
                    // Progress check: if the bot hasn't gotten measurably closer to the
                    // committed target within the timeout window, the path is unreachable
                    // (typically a fence/wall/head-clearance block we can't auto-step).
                    // Blacklist the position so subsequent sweeps skip it, then exit.
                    Long lastProgress = FollowStateService.IDLE_SWEEP_LAST_PROGRESS_TICK.get(botId);
                    Double lastDistSq = FollowStateService.IDLE_SWEEP_LAST_DISTANCE_SQ.get(botId);
                    if (lastProgress == null || lastDistSq == null) {
                        FollowStateService.IDLE_SWEEP_LAST_PROGRESS_TICK.put(botId, nowTick);
                        FollowStateService.IDLE_SWEEP_LAST_DISTANCE_SQ.put(botId, distSq);
                    } else if (distSq + IDLE_SWEEP_PROGRESS_DELTA_SQ < lastDistSq) {
                        FollowStateService.IDLE_SWEEP_LAST_PROGRESS_TICK.put(botId, nowTick);
                        FollowStateService.IDLE_SWEEP_LAST_DISTANCE_SQ.put(botId, distSq);
                    } else if (nowTick - lastProgress > IDLE_SWEEP_NO_PROGRESS_TIMEOUT_TICKS) {
                        LOGGER.info("[IdleSweep] {} abandoning unreachable drop at {} — no progress for {}s",
                                bot.getName().getString(), target.toShortString(),
                                (nowTick - lastProgress) / 20L);
                        blacklistIdleSweepTarget(botId, target,
                                nowTick + IDLE_SWEEP_BLACKLIST_DURATION_TICKS);
                        FollowStateService.clearIdleSweep(botId);
                        DropSweepService.requestCancel(bot, "no-progress");
                        net.wcfcarolina13.Entity.AutoFaceEntity.setBotExecutingTask(false);
                        return false;
                    }
                    // Still walking to the target.
                    FollowMovementService.followWaypointStep(bot, target, distSq,
                            64.0D, () -> lowerShieldTracking(bot));
                    return true;
                }
            }

            // No committed target — look for the next drop.
            Entity nearDrop = findNearestDrop(bot, IDLE_SWEEP_RADIUS);
            if (nearDrop != null) {
                BlockPos dropBlock = nearDrop.getBlockPos();
                double distSq = bot.squaredDistanceTo(nearDrop);
                if (distSq > 2.25D) {
                    FollowStateService.IDLE_SWEEP_TARGET.put(botId, dropBlock);
                    FollowStateService.IDLE_SWEEP_LAST_PROGRESS_TICK.put(botId, nowTick);
                    FollowStateService.IDLE_SWEEP_LAST_DISTANCE_SQ.put(botId, distSq);
                    FollowMovementService.followWaypointStep(bot, dropBlock, distSq,
                            64.0D, () -> lowerShieldTracking(bot));
                    return true;
                }
                if (!DropSweepService.isInProgress()) {
                    collectNearbyDrops(bot, IDLE_SWEEP_RADIUS);
                    if (DropSweepService.isInProgressFor(bot)) {
                        return true;
                    }
                }
                // Even if formal sweep can't start, stay active and keep trying.
                return true;
            }

            // Nothing left — sweep done.
            LOGGER.debug("[IdleSweep] {} complete — no more drops", bot.getName().getString());
            FollowStateService.clearIdleSweep(botId);
            net.wcfcarolina13.Entity.AutoFaceEntity.setBotExecutingTask(false);
            return false;
        }

        // ===== ACCUMULATING: count idle ticks =====
        // Use block positions: looking around or sub-block jitter doesn't reset the timer.
        BlockPos snapBot = FollowStateService.IDLE_SWEEP_BOT_BLOCK.get(botId);
        if (snapBot != null && !snapBot.equals(botBlock)) {
            // Bot changed block position — reset timer.
            FollowStateService.IDLE_SWEEP_START_TICK.put(botId, nowTick);
            FollowStateService.IDLE_SWEEP_BOT_BLOCK.put(botId, botBlock);
            if (commander != null) {
                FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.put(botId, commander.getBlockPos());
            }
            return false;
        }
        if (mode == Mode.FOLLOW && commander != null) {
            BlockPos snapPlayer = FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.get(botId);
            if (snapPlayer != null) {
                int pdx = commander.getBlockX() - snapPlayer.getX();
                int pdz = commander.getBlockZ() - snapPlayer.getZ();
                if (pdx * pdx + pdz * pdz > 1) {
                    // Player moved >1 block — reset timer.
                    FollowStateService.IDLE_SWEEP_START_TICK.put(botId, nowTick);
                    FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.put(botId, commander.getBlockPos());
                    FollowStateService.IDLE_SWEEP_BOT_BLOCK.put(botId, botBlock);
                    return false;
                }
            }
        }

        // Initialize idle timer if not set.
        Long idleStart = FollowStateService.IDLE_SWEEP_START_TICK.get(botId);
        if (idleStart == null) {
            FollowStateService.IDLE_SWEEP_START_TICK.put(botId, nowTick);
            FollowStateService.IDLE_SWEEP_BOT_BLOCK.put(botId, botBlock);
            if (commander != null) {
                FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.put(botId, commander.getBlockPos());
            }
            return false;
        }

        // Check if idle threshold reached.
        if (nowTick - idleStart < IDLE_SWEEP_DELAY_TICKS) {
            return false;
        }

        // Avoid starting opportunistic movement from cells that are enclosed,
        // not actually standable, or bordered by a dangerous drop.
        if (bot.getEntityWorld() instanceof ServerWorld idleSweepWorld) {
            SafePositionService.SurfaceCandidateAssessment posCheck =
                    SafePositionService.analyzeSurfaceCandidate(idleSweepWorld, botBlock);
            if (!BackgroundSweepPolicy.isIdleSweepOriginSafe(posCheck)) {
                LOGGER.debug("[IdleSweep] {} suppressed — unsafe origin {} standable={} openSky={} nearSurface={} steepDrops={} blocked={}",
                        bot.getName().getString(), botBlock.toShortString(), posCheck.standable(),
                        posCheck.openSky(), posCheck.nearSurface(), posCheck.steepDropNeighbors(),
                        posCheck.blockedCardinals());
                FollowStateService.IDLE_SWEEP_START_TICK.put(botId, nowTick);
                return false;
            }
        }

        // Threshold reached — check for nearby drops.
        Entity nearDrop = findNearestDrop(bot, IDLE_SWEEP_RADIUS);
        if (nearDrop == null) {
            return false;
        }

        // Activate idle sweep.
        LOGGER.info("[IdleSweep] {} activating — idle for {}s, nearest drop at {}",
                bot.getName().getString(),
                (nowTick - idleStart) / 20L,
                nearDrop.getBlockPos().toShortString());
        FollowStateService.IDLE_SWEEP_ACTIVE.put(botId, true);
        FollowStateService.IDLE_SWEEP_TARGET.put(botId, nearDrop.getBlockPos());
        if (commander != null) {
            FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.put(botId, commander.getBlockPos());
        }

        // Start moving toward the drop. Seed progress trackers + suppress idle
        // head-rotation so the bot keeps eyes on the waypoint.
        BlockPos dropBlock = nearDrop.getBlockPos();
        double distSq = bot.squaredDistanceTo(nearDrop);
        FollowStateService.IDLE_SWEEP_LAST_PROGRESS_TICK.put(botId, nowTick);
        FollowStateService.IDLE_SWEEP_LAST_DISTANCE_SQ.put(botId, distSq);
        net.wcfcarolina13.Entity.AutoFaceEntity.setBotExecutingTask(true);
        FollowMovementService.followWaypointStep(bot, dropBlock, distSq,
                64.0D, () -> lowerShieldTracking(bot));
        return true;
    }

    public static void collectNearbyDrops(ServerPlayerEntity bot, double radius) {
        Mode currentMode = getMode(bot);
        boolean trainingMode = net.wcfcarolina13.Commands.modCommandRegistry.isTrainingMode;
        boolean commandDrivenSweep = currentMode == Mode.GUARD || currentMode == Mode.PATROL;
        DropSweepService.collectNearbyDrops(
                bot,
                radius,
                trainingMode,
                commandDrivenSweep,
                BotEventHandler::isExternalOverrideActive,
                BotEventHandler::setExternalOverrideActive
        );
    }

    private static boolean handleGuard(ServerPlayerEntity bot, BotCommandStateService.State state, List<Entity> nearbyEntities, List<Entity> hostileEntities) {
        Vec3d center = getGuardCenter(bot);
        double radius = getGuardRadius(bot);
        if (center == null) {
            center = positionOf(bot);
            setGuardState(bot, center, radius);
        }
        MinecraftServer server = bot.getCommandSource().getServer();

        if (!hostileEntities.isEmpty() && engageHostiles(bot, server, hostileEntities)) {
            return true;
        }

        lowerShieldTracking(bot);

        // After combat, try to recover missed arrows (stay within the guard radius).
        if (server != null && BotArrowRecoveryService.tryRecoverMissedArrows(bot, server, center, radius)) {
            return true;
        }

        // If a sweep is in-flight for this bot, let it keep driving movement unless we've requested cancellation.
        if (DropSweepService.isInProgressFor(bot) && !DropSweepService.isCancelRequestedFor(bot)) {
            return true;
        }

        // If an escape is already running on a worker thread, yield.
        if (GuardPatrolService.isEscapeInProgress(bot.getUuid())) {
            return true;
        }

        // Stuck detection: track position and check for stagnation.
        GuardPatrolService.updateStuckTracker(bot.getUuid(), positionOf(bot));
        long currentTick = server != null ? server.getTicks() : 0L;
        if (GuardPatrolService.isStuck(bot.getUuid(), currentTick)) {
            if (bot.getEntityWorld() instanceof ServerWorld sw && !BotFleeService.isAtSurface(bot, sw)) {
                GuardPatrolService.setEscapeInProgress(bot.getUuid(), true);
                LOGGER.info("Guard escape: {} starting surface escape", bot.getName().getString());
                CompletableFuture.runAsync(() -> {
                    try {
                        boolean escaped = BotFleeService.ensureAtSurfaceForHobby(bot, sw);
                        if (!escaped) {
                            LOGGER.warn("Guard escape: {} failed — cooldown 30s", bot.getName().getString());
                            GuardPatrolService.startEscapeCooldown(bot.getUuid(), server.getTicks());
                        } else {
                            LOGGER.info("Guard escape: {} succeeded", bot.getName().getString());
                        }
                    } finally {
                        GuardPatrolService.setEscapeInProgress(bot.getUuid(), false);
                    }
                });
                return true;
            }
            // At surface but stuck — reset and let normal movement retry.
            GuardPatrolService.resetStuck(bot.getUuid());
        }

        Entity nearestItem = findNearestDrop(bot, radius);
        if (nearestItem != null) {
            collectNearbyDrops(bot, Math.max(radius, 4.0D));
            return true;
        }

        double distanceFromCenter = positionOf(bot).distanceTo(center);
        if (distanceFromCenter > 1.5D) {
            moveToward(bot, center, 0.9D, false);
            return true;
        }

        BotActions.stop(bot);
        return true;
    }

    private static boolean handlePatrol(ServerPlayerEntity bot, BotCommandStateService.State state, List<Entity> nearbyEntities, List<Entity> hostileEntities) {
        Vec3d center = getGuardCenter(bot);
        double radius = getPatrolRadius(bot);
        if (center == null) {
            center = positionOf(bot);
            GuardPatrolService.setPatrolState(bot.getUuid(), center, radius);
        }
        MinecraftServer server = bot.getCommandSource().getServer();

        if (!hostileEntities.isEmpty() && engageHostiles(bot, server, hostileEntities)) {
            return true;
        }

        lowerShieldTracking(bot);

        // After combat, try to recover missed arrows (stay within the patrol radius).
        if (server != null && BotArrowRecoveryService.tryRecoverMissedArrows(bot, server, center, radius)) {
            return true;
        }

        // If a sweep is in-flight for this bot, let it keep driving movement unless we've requested cancellation.
        if (DropSweepService.isInProgressFor(bot) && !DropSweepService.isCancelRequestedFor(bot)) {
            return true;
        }

        // If an escape is already running on a worker thread, yield.
        if (GuardPatrolService.isEscapeInProgress(bot.getUuid())) {
            return true;
        }

        // Stuck detection: track position and check for stagnation.
        GuardPatrolService.updateStuckTracker(bot.getUuid(), positionOf(bot));
        long currentTick = server != null ? server.getTicks() : 0L;
        if (GuardPatrolService.isStuck(bot.getUuid(), currentTick)) {
            if (bot.getEntityWorld() instanceof ServerWorld sw && !BotFleeService.isAtSurface(bot, sw)) {
                GuardPatrolService.setEscapeInProgress(bot.getUuid(), true);
                LOGGER.info("Patrol escape: {} starting surface escape", bot.getName().getString());
                CompletableFuture.runAsync(() -> {
                    try {
                        boolean escaped = BotFleeService.ensureAtSurfaceForHobby(bot, sw);
                        if (!escaped) {
                            LOGGER.warn("Patrol escape: {} failed — cooldown 30s", bot.getName().getString());
                            GuardPatrolService.startEscapeCooldown(bot.getUuid(), server.getTicks());
                        } else {
                            LOGGER.info("Patrol escape: {} succeeded", bot.getName().getString());
                        }
                    } finally {
                        GuardPatrolService.setEscapeInProgress(bot.getUuid(), false);
                    }
                });
                return true;
            }
            // At surface but stuck — reset tracker and pick a new patrol target.
            GuardPatrolService.resetStuck(bot.getUuid());
            GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
        }

        Entity nearestItem = findNearestDrop(bot, radius);
        if (nearestItem != null) {
            collectNearbyDrops(bot, Math.max(radius, 4.0D));
            return true;
        }

        double distanceFromCenter = positionOf(bot).distanceTo(center);
        if (distanceFromCenter > radius) {
            GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
            if (server != null) {
                GuardPatrolService.setNextPatrolPickTick(bot.getUuid(), server.getTicks() + 20L);
            }
            moveToward(bot, center, 2.0D, false);
            return true;
        }

        Vec3d patrolTarget = GuardPatrolService.getPatrolTarget(bot.getUuid());
        if (patrolTarget != null) {
            double dist = positionOf(bot).distanceTo(patrolTarget);
            if (dist > 1.35D) {
                moveToward(bot, patrolTarget, 1.1D, false);
                return true;
            }
            GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
            if (server != null) {
                GuardPatrolService.setNextPatrolPickTick(bot.getUuid(), server.getTicks() + 10L);
            }
        }

        if (patrolTarget == null && server != null) {
            long nowTick = server.getTicks();
            long nextPickTick = GuardPatrolService.getNextPatrolPickTick(bot.getUuid());
            if (nowTick >= nextPickTick) {
                Vec3d next = randomPointWithin(center, radius * 0.85D);
                GuardPatrolService.setPatrolTarget(bot.getUuid(), next);
                GuardPatrolService.setNextPatrolPickTick(bot.getUuid(), nowTick + 40L + RANDOM.nextInt(40));
                moveToward(bot, next, 1.1D, false);
                return true;
            }
        }

        BotActions.stop(bot);
        return true;
    }

    private static boolean handleReturnToBase(ServerPlayerEntity bot, BotCommandStateService.State state) {
        // Note: This method is currently unused. Return-to-base uses FOLLOW mode via setReturnToBase().
        // Keeping this for potential future use or if we want to switch back to dedicated mode.
        Vec3d base = state != null ? state.baseTarget : null;
        if (base == null) {
            setMode(bot, Mode.IDLE);
            setAssistAllies(bot, true);
            net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.clear(bot.getUuid());
            return false;
        }

        // Important: use horizontal distance, matching FollowMovementService.moveToward().
        // Otherwise the bot can be close enough to stop moving, but never "arrive" here,
        // leaving RETURNING_BASE stuck indefinitely.
        double dx = base.x - bot.getX();
        double dz = base.z - bot.getZ();
        double horizDistSq = dx * dx + dz * dz;
        if (horizDistSq <= 3.0D * 3.0D) {
            setMode(bot, Mode.STAY);
            sendBotMessage(bot, "Arrived at base. Holding position.");
            setBaseTarget(bot, null);
            net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.clear(bot.getUuid());

            // If idle hobbies are enabled, automatically resume idling after a short pause.
            MinecraftServer srv = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
            if (srv != null) {
                try {
                    net.wcfcarolina13.GameAI.services.BotIdleResumeService.scheduleResumeIfEnabled(
                            srv,
                            bot,
                            400L,
                            "return-to-base"
                    );
                } catch (Throwable ignored) {
                }
            }
            return true;
        }

        // Check if bot is stuck and should trigger a flare
        if (net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.tickAndCheckStuck(bot, base)) {
            triggerStuckFlare(bot);
        }

        // Door handling: like vanilla villagers, try to open/traverse doors when blocked
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            BlockPos baseBlock = BlockPos.ofFloored(base.x, base.y, base.z);
            
            // Check for doors directly in front of the bot
            BlockPos botPos = bot.getBlockPos();
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos candidate = botPos.offset(dir);
                BlockState candidateState = world.getBlockState(candidate);
                BlockState candidateUpState = world.getBlockState(candidate.up());
                boolean isDoor = candidateState.getBlock() instanceof DoorBlock
                        || candidateUpState.getBlock() instanceof DoorBlock
                        || candidateState.getBlock() instanceof FenceGateBlock
                        || candidateUpState.getBlock() instanceof FenceGateBlock;
                if (isDoor) {
                    // Try to open and step through
                    if (MovementService.tryOpenDoorAt(bot, candidate)) {
                        // Commit to stepping through
                        if (MovementService.tryTraverseOpenableToward(bot, candidate, baseBlock, "return-base-door")) {
                            return true;
                        }
                    }
                }
            }
            
            // If we seem stuck (not making progress), look for nearby doors as escape routes
            UUID botId = bot.getUuid();
            BlockPos curBlock = bot.getBlockPos();
            BlockPos prevBlock = FOLLOW_LAST_BLOCK_POS.get(botId);
            int posStagnant = FOLLOW_POS_STAGNANT_TICKS.getOrDefault(botId, 0);
            if (prevBlock != null && prevBlock.equals(curBlock)) {
                posStagnant++;
            } else {
                posStagnant = 0;
                FOLLOW_LAST_BLOCK_POS.put(botId, curBlock.toImmutable());
            }
            FOLLOW_POS_STAGNANT_TICKS.put(botId, posStagnant);
            
            // If stagnant for 10+ ticks, try door escape planning
            if (posStagnant >= 10) {
                MovementService.DoorSubgoalPlan doorPlan = MovementService.findDoorEscapePlan(bot, baseBlock, null);
                if (doorPlan != null) {
                    // Open the door
                    MovementService.tryOpenDoorAt(bot, doorPlan.doorBase());
                    // Try to traverse through
                    if (MovementService.tryTraverseOpenableToward(bot, doorPlan.doorBase(), baseBlock, "return-base-escape")) {
                        // Reset stagnation since we're moving through the door
                        FOLLOW_POS_STAGNANT_TICKS.put(botId, 0);
                        return true;
                    }
                }
            }
            
            // Also check for doors along the direct line to base
            BlockPos blockingDoor = BlockInteractionService.findDoorAlongLine(bot, base, 6.0D);
            if (blockingDoor != null) {
                MovementService.tryOpenDoorAt(bot, blockingDoor);
            }
        }

        moveToward(bot, base, 2.0D, false);
        return true;
    }

    private static boolean handleStarvationOverride(ServerPlayerEntity bot, MinecraftServer server, Mode mode) {
        if (bot == null || server == null || !HealingService.isStarving(bot)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (HealingService.autoEat(bot) || BotMutualAidService.tryImmediateChestFoodRecovery(bot, world)) {
            return true;
        }
        if (net.wcfcarolina13.GameAI.services.BotEmergencyRescueService.tryEmergencyRescue(bot, world, "starvation-override")) {
            return true;
        }
        if (mode == Mode.FOLLOW && isReturningToBase(bot)) {
            if (BotMutualAidService.trySeekFoodFromNearbyBot(bot, world)) {
                BotAutoReturnSunsetService.deferLocalReassess(bot.getUuid(), server.getTicks() + 120L);
                stopFollowing(bot, false);
                return true;
            }
            BotAutoReturnSunsetService.deferLocalReassess(bot.getUuid(), server.getTicks() + 120L);
            stopFollowing(bot, false);
            BotAutoHuntService.requestDecisionNow(bot);
            return true;
        }
        if (BotFleeService.isInShelter(bot.getUuid())) {
            if (BotMutualAidService.trySeekFoodFromNearbyBot(bot, world)) {
                BotFleeService.clearShelterAndBreakFree(bot);
                return true;
            }
            BotFleeService.clearShelterAndBreakFree(bot);
            BotAutoHuntService.requestDecisionNow(bot);
            return true;
        }
        return false;
    }

    /**
     * Triggers the flare skill when bot is stuck during return-to-base.
     * Runs asynchronously to avoid blocking the tick thread.
     */
    private static void triggerStuckFlare(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUuid();
        
        // Send message to commander that bot is stuck
        sendBotMessage(bot, "§eI'm stuck and too far from base. Sending a flare signal!");
        
        // Mark the flare as sent (for cooldown)
        net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.markFlareSent(botId);
        
        // Execute flare skill in a separate thread
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        
        Thread flareThread = new Thread(() -> {
            try {
                ServerCommandSource source = bot.getCommandSource().withSilent();
                SkillContext context = new SkillContext(source, null, null);
                SkillExecutionResult result = SkillManager.runSkill("flare", context);
                if (!result.success()) {
                    // Log but don't spam - flare failed silently
                    LOGGER.info("Flare skill failed for {}: {}", bot.getName().getString(), result.message());
                }
            } catch (Exception e) {
                LOGGER.warn("Error running flare skill for {}: {}", bot.getName().getString(), e.getMessage());
            }
        }, "flare-stuck-" + bot.getName().getString());
        flareThread.setDaemon(true);
        flareThread.start();
    }

    /**
     * Computes a weighted threat score for a hostile entity.
     * Higher score = more dangerous / higher priority target.
     */
    private static double scoreThreat(ServerPlayerEntity bot, Entity entity) {
        // --- Base type danger ---
        double typeDanger;
        EntityType<?> type = entity.getType();
        if (type == EntityType.CREEPER)                          typeDanger = 8;
        else if (type == EntityType.WITCH)                       typeDanger = 7;
        else if (type.isIn(EntityTypeTags.SKELETONS))            typeDanger = 7;
        else if (type == EntityType.PILLAGER)                    typeDanger = 6;
        else if (type == EntityType.PHANTOM)                     typeDanger = BotActions.isPhantomDiving(bot, entity) ? 5 : 1;
        else if (type == EntityType.ZOMBIE || type == EntityType.HUSK
                || type == EntityType.DROWNED || type == EntityType.ZOMBIE_VILLAGER) typeDanger = 4;
        else if (type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER) typeDanger = 4;
        else if (type == EntityType.ENDERMAN)                    typeDanger = 3;
        else if (type == EntityType.GHAST)                       typeDanger = 6;
        else                                                     typeDanger = 3;

        // --- Proximity bonus: closer = more dangerous ---
        double distance = Math.sqrt(entity.squaredDistanceTo(bot));
        double proximityBonus = Math.max(0.0, (10.0 - distance) / 2.0);

        // --- State multipliers ---
        double stateMult = 1.0;
        if (type == EntityType.CREEPER && entity instanceof net.minecraft.entity.mob.CreeperEntity creeper
                && creeper.isIgnited()) {
            stateMult = 2.0;
        }
        if (entity instanceof net.minecraft.entity.mob.ZombieEntity zombie && zombie.isBaby()) {
            stateMult = 1.5;
        }
        // Mob actively targeting this bot
        if (entity instanceof net.minecraft.entity.mob.MobEntity mob
                && mob.getTarget() != null && mob.getTarget().equals(bot)) {
            stateMult *= 1.3;
        }

        // --- Target stickiness: small bonus to avoid flip-flopping ---
        double stickinessBonus = 0.0;
        UUID currentTarget = COMBAT_TARGET.get(bot.getUuid());
        if (currentTarget != null && currentTarget.equals(entity.getUuid())) {
            stickinessBonus = 3.0;
        }

        double baseScore = typeDanger * (1.0 + proximityBonus) * stateMult + stickinessBonus;
        // Tamed-animal defense boost: if this bot has marked the candidate as a
        // defended attacker (within DEFEND_EXPIRE_TICKS), add the boost so the
        // candidate jumps to the top of the combat priority queue. Returns 0
        // when not defended (zero allocation, zero state in the common case).
        // See BotAnimalDefenseService for the boost map and expiry rules.
        return baseScore + net.wcfcarolina13.GameAI.services.BotAnimalDefenseService
                .defenseBoost(bot, entity);
    }

    private static boolean engageHostiles(ServerPlayerEntity bot, MinecraftServer server, List<Entity> hostileEntities) {
        // Tamed-animal defense: inject any defended attackers into the hostile
        // list (dedup by UUID, returns same reference if no defense targets).
        // Always safe to reassign — augmentHostilesWithDefenseTargets never
        // mutates the input list.
        hostileEntities = net.wcfcarolina13.GameAI.services.BotAnimalDefenseService
                .augmentHostilesWithDefenseTargets(bot, hostileEntities);
        // Iron golem special rules: accidental hits get ignored entirely, and
        // direct aggro forces a flee response (golems are tanky and the bot
        // will lose). See spec section "Iron Golem Special Rules".
        boolean golemAggroFlee = false;
        if (!hostileEntities.isEmpty()) {
            java.util.List<Entity> filtered = new java.util.ArrayList<>(hostileEntities.size());
            for (Entity e : hostileEntities) {
                if (e instanceof net.minecraft.entity.passive.IronGolemEntity golem) {
                    LivingEntity golemTarget = golem.getTarget();
                    if (golemTarget == bot) {
                        golemAggroFlee = true;
                        // Don't add to the engage list — flee branch handles it below.
                        continue;
                    }
                    // Accidental hit: drop the golem from the engage list.
                    continue;
                }
                if (!net.wcfcarolina13.GameAI.services.BotCombatPolicyService.shouldBotAttack(e, bot)) {
                    // Name-tagged mob — bot is forbidden from engaging.
                    continue;
                }
                // Dangerous-pursuit gate: don't initiate combat against a non-aggroed
                // mob unless the bot has a long-range weapon. Self-defense (mob is
                // already targeting the bot) always passes through.
                if (!net.wcfcarolina13.GameAI.services.DangerousPursuitGate.shouldEngageNonAggro(bot, e)) {
                    continue;
                }
                // Locked-gate enclosure respect: don't run through a locked
                // gate (or under a locked trapdoor) to fight a mob on the
                // far side. Same predicate the drop-sweep filter uses.
                if (net.wcfcarolina13.GameAI.services.DangerousPursuitGate
                        .crossesLockedGate(bot, e.getBlockPos(), bot.getEntityWorld())) {
                    continue;
                }
                filtered.add(e);
            }
            hostileEntities = filtered;
        }
        if (golemAggroFlee) {
            // Reuse the existing creeper flee pattern from earlier in this
            // method to retreat from the golem. We need a Vec3d retreat target.
            // Find the closest iron golem in the original (pre-filter) input
            // and flee from it.
            Entity closestGolem = null;
            double bestSq = Double.MAX_VALUE;
            // We dropped the iron golems from hostileEntities; rescan via a
            // small box query so we still know where the angry golem is.
            if (bot.getEntityWorld() instanceof ServerWorld golemWorld) {
                for (Entity g : golemWorld.getEntitiesByClass(
                        net.minecraft.entity.passive.IronGolemEntity.class,
                        Box.of(new Vec3d(bot.getX(), bot.getY(), bot.getZ()), 32, 16, 32),
                        golem -> golem != null && golem.getTarget() == bot)) {
                    double sq = g.squaredDistanceTo(bot);
                    if (sq < bestSq) {
                        bestSq = sq;
                        closestGolem = g;
                    }
                }
            }
            if (closestGolem != null) {
                double dx = bot.getX() - closestGolem.getX();
                double dz = bot.getZ() - closestGolem.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 0.01) { dx = 1; dz = 0; len = 1; }
                Vec3d fleeTarget = new Vec3d(
                        bot.getX() + (dx / len) * 12,
                        bot.getY(),
                        bot.getZ() + (dz / len) * 12);
                BotActions.sprint(bot, true);
                FollowMovementService.moveToward(bot, fleeTarget, 1.0, true, null);
                COMBAT_TARGET.remove(bot.getUuid());
                return true;
            }
        }
        if (hostileEntities.isEmpty()) {
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }
        boolean patrolSuppressPursuit = net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                .checkForPatrolAndSuppressPursuit(bot, server, hostileEntities);
        boolean botArmed = BotActions.hasMeleeWeapon(bot) || BotActions.hasRangedWeapon(bot);
        // Filter out non-actionable phantoms: circling too high to hit and not diving.
        // These aren't real threats and shouldn't influence target selection or trigger
        // the phantom handler (which would make the bot shield-face the sky).
        List<Entity> actionable = hostileEntities.stream()
                .filter(e -> e.getType() != EntityType.PHANTOM
                        || BotActions.isPhantomDiving(bot, e)
                        || (e.getY() - bot.getY()) <= 3.0)
                .filter(e -> isActionableDrownedThreat(bot, e, botArmed))
                .toList();
        if (actionable.isEmpty()) {
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }
        Entity closest = actionable.stream()
                .max(Comparator.comparingDouble(e -> scoreThreat(bot, e)))
                .orElse(null);
        if (closest == null) {
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }
        // Track current combat target for stickiness scoring.
        COMBAT_TARGET.put(bot.getUuid(), closest.getUuid());

        // Cancel any active food consumption — holding food during combat means
        // the bot "attacks" with food and the shield check thinks it's already blocking.
        if (bot.isUsingItem()) {
            ItemStack activeItem = bot.getActiveItem();
            if (activeItem != null && !activeItem.isEmpty()
                    && activeItem.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                bot.clearActiveItem();
            }
        }

        // Prepare combat loadout (armor, shield, weapon staging) regardless of mode.
        // Guard/Patrol skip the AutoFaceEntity loadout path, so this is their only chance.
        CombatInventoryManager.ensureCombatLoadout(bot);

        double distance = Math.sqrt(closest.squaredDistanceTo(bot));
        boolean targetVisible = closest instanceof LivingEntity living && bot.canSee(living);
        boolean hasRanged = targetVisible && BotActions.hasRangedWeapon(bot);
        double verticalDiff = bot.getY() - closest.getY();
        boolean projectileThreat = closest.getType().isIn(EntityTypeTags.SKELETONS) || closest.getName().getString().toLowerCase(Locale.ROOT).contains("pillager");
        boolean creeperThreat = closest.getType() == EntityType.CREEPER;
        boolean multipleThreats = actionable.size() > 1;
        boolean lowHealth = bot.getHealth() <= bot.getMaxHealth() * 0.5F;
        boolean shouldBlock = (projectileThreat || creeperThreat || multipleThreats || lowHealth) && distance <= 4.5D;

        if (shouldHoldShoreAgainstDrowned(bot, closest)) {
            if (holdShoreAgainstDrowned(bot, closest, targetVisible && hasRanged)) {
                return true;
            }
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }

        if (combatStyle == CombatStyle.EVASIVE && distance <= 6.0D && verticalDiff > 1.0D) {
            BotActions.moveBackward(bot);
            if (bot.isOnGround() && verticalDiff > 2.0D) {
                BotActions.jump(bot);
            }
            return true;
        }

        // Creeper handling: block-and-shield if armed and creeper is the only threat;
        // otherwise flee. Charged (lightning-powered) creepers get larger thresholds
        // because their blast radius is roughly 2x normal; the bot needs to bail
        // earlier and shield from further away. If the bot can't make distance
        // (cornered, blocked, hitting a wall), force the shield up regardless.
        if (creeperThreat) {
            boolean chargedCreeper = closest instanceof net.minecraft.entity.mob.CreeperEntity creeperEntity
                    && creeperEntity.isCharged();
            double creeperEngagementRadius = chargedCreeper ? 12.0D : 6.0D;
            double creeperShieldRadius = chargedCreeper ? 8.0D : 4.5D;
            double fleeMoveDist = chargedCreeper ? 24.0D : 12.0D;

            if (distance <= creeperEngagementRadius) {
                boolean onlyCreepers = actionable.stream()
                        .allMatch(e -> e.getType() == EntityType.CREEPER);
                boolean hasMelee = BotActions.hasMeleeWeapon(bot);
                // Block-and-shield trick is only sane against normal creepers — a single
                // block barrier doesn't survive a charged blast at point-blank range.
                if (!chargedCreeper && onlyCreepers && hasMelee && distance <= 4.5D
                        && SkillPreferences.emergencyTactics(bot)) {
                    double cdx = closest.getX() - bot.getX();
                    double cdz = closest.getZ() - bot.getZ();
                    double clen = Math.sqrt(cdx * cdx + cdz * cdz);
                    if (clen > 0.01) {
                        BlockPos blockPos = bot.getBlockPos().add(
                                (int) Math.round(cdx / clen),
                                0,
                                (int) Math.round(cdz / clen));
                        BotActions.placeBlockAt(bot, blockPos);
                    }
                    BotActions.raiseShieldFacing(bot, closest);
                    return true;
                }

                // Track flee progress per bot. If the bot doesn't gain ground over
                // ~1s, treat it as "can't make distance" and force the shield even
                // if we're outside the normal shield radius.
                long nowTick = server.getTicks();
                UUID botId = bot.getUuid();
                CreeperFleeMemory mem = CREEPER_FLEE_STATE.get(botId);
                boolean cantMakeDistance = false;
                if (mem != null && mem.creeperUuid().equals(closest.getUuid())
                        && nowTick - mem.lastTick() <= 30L) {
                    int stuck = mem.stuckTicks();
                    if (distance - mem.lastDistance() < 0.3D) {
                        stuck += (int) Math.max(1L, nowTick - mem.lastTick());
                    } else {
                        stuck = 0;
                    }
                    cantMakeDistance = stuck >= 20;
                    CREEPER_FLEE_STATE.put(botId, new CreeperFleeMemory(closest.getUuid(), distance, nowTick, stuck));
                } else {
                    CREEPER_FLEE_STATE.put(botId, new CreeperFleeMemory(closest.getUuid(), distance, nowTick, 0));
                }

                boolean hasShield = bot.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)
                        || bot.getMainHandStack().isOf(net.minecraft.item.Items.SHIELD);
                if (distance <= creeperShieldRadius || (cantMakeDistance && hasShield)) {
                    BotActions.raiseShieldFacing(bot, closest);
                }
                double dx = bot.getX() - closest.getX();
                double dz = bot.getZ() - closest.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 0.01) { dx = 1; dz = 0; len = 1; }
                Vec3d fleeTarget = new Vec3d(
                        bot.getX() + (dx / len) * fleeMoveDist,
                        bot.getY(),
                        bot.getZ() + (dz / len) * fleeMoveDist);
                BotActions.sprint(bot, true);
                FollowMovementService.moveToward(bot, fleeTarget, 1.0, true, null);
                return true;
            } else {
                // Out of engagement range — drop any stale flee memory so we don't
                // misattribute "no progress" once the creeper closes in again.
                CREEPER_FLEE_STATE.remove(bot.getUuid());
            }
        }

        // Ghast handling: never melee approach — use ranged or take cover.
        // GhastFireballDeflectService handles punching fireballs back independently.
        if (closest.getType() == EntityType.GHAST) {
            if (hasRanged && closest instanceof LivingEntity living) {
                if (BotActions.tryRepositionForRanged(bot, living, server.getTicks())) {
                    return true;
                }
                if (BotActions.performRangedAttack(bot, living, server.getTicks())) {
                    return true;
                }
            }
            // No ranged weapon or can't fire — shield up and hold position (defilade).
            BotActions.raiseShieldFacing(bot, closest);
            return true;
        }

        // Phantom handling: conserve arrows — only shoot during dive; seek cover if unarmed.
        if (closest.getType() == EntityType.PHANTOM) {
            // ALWAYS prefer any ground hostile over a phantom — zombies/skeletons are the real threat.
            // Phantoms deal low damage and can be ignored while ground mobs are present.
            Entity groundThreat = actionable.stream()
                    .filter(e -> e.getType() != EntityType.PHANTOM)
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                    .orElse(null);

            if (groundThreat != null) {
                // Re-target the ground threat and fall through to normal combat below.
                // Works for ALL hostile types — existing creeper/skeleton/generic handlers take over.
                closest = groundThreat;
                distance = Math.sqrt(closest.squaredDistanceTo(bot));
                targetVisible = closest instanceof LivingEntity lv && bot.canSee(lv);
                hasRanged = targetVisible && BotActions.hasRangedWeapon(bot);
                projectileThreat = closest.getType().isIn(EntityTypeTags.SKELETONS)
                        || closest.getName().getString().toLowerCase(Locale.ROOT).contains("pillager");
                creeperThreat = closest.getType() == EntityType.CREEPER;
                shouldBlock = (projectileThreat || creeperThreat || multipleThreats || lowHealth) && distance <= 4.5D;
                // Fall through to the existing combat logic below.
            } else {
                boolean diving = BotActions.isPhantomDiving(bot, closest);

                // At night with phantom-only threat and no dive in progress,
                // don't stand around with shield — let shelter logic take over.
                if (!diving && bot.getEntityWorld() instanceof ServerWorld phantomWorld && !phantomWorld.isDay()) {
                    return false;
                }

                // Shield up when phantom is diving close — brace for impact.
                if (diving && distance <= 5.0) {
                    BotActions.raiseShieldFacing(bot, closest);
                }

                // Melee if phantom dives within reach.
                if (diving && distance <= 3.0 && closest instanceof LivingEntity) {
                    lowerShieldTracking(bot);
                    CombatWeaponPolicy.CloseRangeChoice choice =
                            BotActions.prepareCloseRangeWeapon(bot, hasRanged);
                    if (choice == CombatWeaponPolicy.CloseRangeChoice.RANGED
                            && closest instanceof LivingEntity living
                            && BotActions.performRangedAttack(bot, living, server.getTicks())) {
                        return true;
                    }
                    BotActions.attackTarget(bot, closest);
                    return true;
                }

                // Ranged fire only during dive.
                if (diving && hasRanged && closest instanceof LivingEntity living) {
                    lowerShieldTracking(bot);
                    if (BotActions.tryRepositionForRanged(bot, living, server.getTicks())) {
                        return true;
                    }
                    if (BotActions.performRangedAttack(bot, living, server.getTicks())) {
                        return true;
                    }
                }

                // No ranged weapon — seek overhead cover.
                if (!hasRanged) {
                    BlockPos cover = BotActions.findNearestOverheadCover(bot, 12);
                    if (cover != null && !cover.equals(bot.getBlockPos())) {
                        BotActions.sprint(bot, true);
                        FollowMovementService.moveToward(bot, Vec3d.ofCenter(cover), 1.0, true, null);
                        return true;
                    }
                    BotActions.raiseShieldFacing(bot, closest);
                    return true;
                }

                // Has ranged but phantom NOT diving — wait patiently, shield up, save arrows.
                BotActions.raiseShieldFacing(bot, closest);
                return true;
            }
        }

        // Cobweb barrier: if there's a cobweb between the bot and the target, we can't
        // effectively engage — arrows are swallowed, and the pathfinder rejects cobweb
        // cells as deadly terrain so we can't approach either. Back off with shield up
        // instead of sitting there eating projectiles through the web. This matches the
        // "take cover if being shot at through cobwebs" behaviour: the bot preserves
        // distance while the commander can close the gap and break the web manually.
        if (closest instanceof LivingEntity lvCobweb
                && BotActions.isCobwebBetweenBotAndTarget(bot, lvCobweb)) {
            BotActions.raiseShieldFacing(bot, closest);
            if (distance <= 8.0D) {
                // Move directly away from the target without the follow planner (which
                // would try to path through the cobweb).
                double bdx = bot.getX() - closest.getX();
                double bdz = bot.getZ() - closest.getZ();
                double blen = Math.sqrt(bdx * bdx + bdz * bdz);
                if (blen > 0.01) {
                    Vec3d retreat = new Vec3d(
                            bot.getX() + (bdx / blen) * 4.0D,
                            bot.getY(),
                            bot.getZ() + (bdz / blen) * 4.0D);
                    BotActions.sprint(bot, true);
                    FollowMovementService.moveToward(bot, retreat, 0.5, true, null);
                }
            }
            return true;
        }

        if (hasRanged && distance >= 5.0D && closest instanceof LivingEntity living) {
            if (BotActions.tryRepositionForRanged(bot, living, server.getTicks())) {
                return true;
            }
            if (BotActions.performRangedAttack(bot, living, server.getTicks())) {
                return true;
            }
        } else {
            BotActions.resetRangedState(bot);
        }

        ItemStack activeMainHand = bot.getMainHandStack();
        double preferredEngageDistance = BotActions.getPreferredMeleeEngageDistance(activeMainHand);
        double preferredStopDistance = BotActions.getPreferredMeleeStopDistance(activeMainHand);

        if (distance > preferredEngageDistance) {
            if (patrolSuppressPursuit && net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                    .shouldSuppressPursuit(bot, closest)) {
                BotActions.raiseShieldFacing(bot, closest);
            } else {
                lowerShieldTracking(bot);
                moveToward(bot, positionOf(closest), preferredStopDistance, true);
            }
        } else if (shouldBlock) {
            // In melee range with a sword: stop sprinting when 2+ mobs are close so
            // sweep attacks can trigger (vanilla sweep requires: on ground, not sprinting,
            // cooldown >= 0.9, low horizontal speed). Non-sword weapons (axe, mace, trident,
            // spear) don't sweep — and the spear actively benefits from sprinting (charge attack).
            boolean holdingSword = BotActions.isSword(bot.getMainHandStack());
            long nearbyMeleeCount = actionable.stream()
                    .filter(e -> e.squaredDistanceTo(bot) <= 16.0) // within 4 blocks
                    .filter(e -> e.getType() != EntityType.GHAST && e.getType() != EntityType.PHANTOM)
                    .count();
            if (holdingSword && nearbyMeleeCount >= 2) {
                BotActions.sprint(bot, false);
            }
            // Face the most dangerous incoming threat while shielding.
            // Prefer ranged threats (skeletons, pillagers) since they deal damage at distance;
            // if none, face the closest mob.
            Entity shieldFaceThreat = actionable.stream()
                    .filter(e -> e.getType().isIn(EntityTypeTags.SKELETONS)
                            || e.getName().getString().toLowerCase(Locale.ROOT).contains("pillager")
                            || e.getType() == EntityType.WITCH)
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                    .orElse(closest);

            long now = bot.getCommandSource().getServer().getTicks();
            if (!isShieldRaised(bot)) {
                if (BotActions.raiseShieldFacing(bot, shieldFaceThreat)) {
                    setShieldRaised(bot, true);
                    setShieldDecisionTick(bot, now);
                    return true;
                }
                // No shield available — attack with whatever we have
                lowerShieldTracking(bot);
                CombatWeaponPolicy.CloseRangeChoice choice =
                        BotActions.prepareCloseRangeWeapon(bot, hasRanged);
                if (choice == CombatWeaponPolicy.CloseRangeChoice.RANGED
                        && closest instanceof LivingEntity living
                        && BotActions.performRangedAttack(bot, living, server.getTicks())) {
                    return true;
                }
                BotActions.attackTarget(bot, closest);
                return true;
            }

            if (now - getShieldDecisionTick(bot) >= 15) {
                lowerShieldTracking(bot);
                CombatWeaponPolicy.CloseRangeChoice choice =
                        BotActions.prepareCloseRangeWeapon(bot, hasRanged);
                if (choice == CombatWeaponPolicy.CloseRangeChoice.RANGED
                        && closest instanceof LivingEntity living
                        && BotActions.performRangedAttack(bot, living, server.getTicks())) {
                    setShieldDecisionTick(bot, now);
                    return true;
                }
                BotActions.attackTarget(bot, closest);
                setShieldDecisionTick(bot, now);
            }
            return true;
        } else {
            lowerShieldTracking(bot);
            // Stop sprinting when surrounded with a sword to enable sweep attacks.
            // Spears benefit from sprinting (charge attack), so never stop sprint for them.
            boolean holdingSword2 = BotActions.isSword(bot.getMainHandStack());
            long nearbyMeleeCount2 = actionable.stream()
                    .filter(e -> e.squaredDistanceTo(bot) <= 16.0)
                    .filter(e -> e.getType() != EntityType.GHAST && e.getType() != EntityType.PHANTOM)
                    .count();
            if (holdingSword2 && nearbyMeleeCount2 >= 2) {
                BotActions.sprint(bot, false);
            }
            CombatWeaponPolicy.CloseRangeChoice choice =
                    BotActions.prepareCloseRangeWeapon(bot, hasRanged);
            if (choice == CombatWeaponPolicy.CloseRangeChoice.RANGED
                    && closest instanceof LivingEntity living) {
                BotActions.clearForceMelee(bot);
                if (BotActions.tryRepositionForRanged(bot, living, server.getTicks())) {
                    return true;
                }
                BotActions.performRangedAttack(bot, living, server.getTicks());
            } else {
                if (BotActions.shouldReopenSpearSpacing(bot, closest)) {
                    BotActions.sprint(bot, false);
                    BotActions.moveBackward(bot);
                    return true;
                }
                if (BotActions.shouldPressSpearCharge(bot, closest)) {
                    if (patrolSuppressPursuit && net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                            .shouldSuppressPursuit(bot, closest)) {
                        BotActions.raiseShieldFacing(bot, closest);
                    } else {
                        BotActions.sprint(bot, true);
                        moveToward(bot, positionOf(closest), BotActions.getPreferredMeleeStopDistance(bot.getMainHandStack()), true);
                    }
                }
                // Hit-and-retreat kiting: after swinging, backpedal during cooldown reset
                // to dodge the mob's return hit, then step forward to re-engage.
                // Only kite against 1-2 mobs — backpedaling when surrounded exposes the back.
                float cooldown = bot.getAttackCooldownProgress(0.5f);
                if (nearbyMeleeCount2 <= 2
                        && choice == CombatWeaponPolicy.CloseRangeChoice.MELEE
                        && !BotActions.isSpear(bot.getMainHandStack())) {
                    if (cooldown < 0.3f && distance <= 2.5D) {
                        // Just swung — backpedal out of mob's reach
                        BotActions.moveBackward(bot);
                    } else if (cooldown >= 0.7f && distance > 2.0D) {
                        // Cooldown almost ready — step forward to re-engage
                        if (patrolSuppressPursuit && net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                                .shouldSuppressPursuit(bot, closest)) {
                            BotActions.raiseShieldFacing(bot, closest);
                        } else {
                            moveToward(bot, positionOf(closest), 1.5D, false);
                        }
                    }
                }
                BotActions.attackTarget(bot, closest);
            }
        }
        return true;
    }

    private static boolean isActionableDrownedThreat(ServerPlayerEntity bot, Entity hostile, boolean botArmed) {
        if (bot == null || hostile == null || hostile.getType() != EntityType.DROWNED) {
            return true;
        }
        if (!isEntityInWater(hostile)) {
            return true;
        }
        if (net.wcfcarolina13.GameAI.services.BotWaterEscapeService.isInWater(bot)) {
            return true;
        }
        if (!botArmed && !isDrownedLockedOntoBot(bot, hostile)) {
            return false;
        }
        return true;
    }

    private static boolean shouldHoldShoreAgainstDrowned(ServerPlayerEntity bot, Entity hostile) {
        return bot != null
                && hostile != null
                && hostile.getType() == EntityType.DROWNED
                && isEntityInWater(hostile)
                && !net.wcfcarolina13.GameAI.services.BotWaterEscapeService.isInWater(bot);
    }

    private static boolean holdShoreAgainstDrowned(ServerPlayerEntity bot, Entity hostile, boolean canUseRanged) {
        if (bot == null || hostile == null) {
            return false;
        }
        BlockPos shore = net.wcfcarolina13.GameAI.services.BotWaterEscapeService.findNearestShoreStand(bot, 8);
        if (shore != null && bot.getBlockPos().getSquaredDistance(shore) > 2.25D) {
            moveToward(bot, Vec3d.ofCenter(shore), 1.5D, true);
            return true;
        }
        if (canUseRanged && hostile instanceof LivingEntity living) {
            lowerShieldTracking(bot);
            if (BotActions.tryRepositionForRanged(bot, living, bot.getCommandSource().getServer().getTicks())) {
                return true;
            }
            if (BotActions.performRangedAttack(bot, living, bot.getCommandSource().getServer().getTicks())) {
                return true;
            }
        }
        if (isDrownedLockedOntoBot(bot, hostile) || hostile.squaredDistanceTo(bot) <= 25.0D) {
            BotActions.raiseShieldFacing(bot, hostile);
            return true;
        }
        return false;
    }

    private static boolean isDrownedLockedOntoBot(ServerPlayerEntity bot, Entity hostile) {
        if (bot == null || !(hostile instanceof net.minecraft.entity.mob.MobEntity mob)) {
            return false;
        }
        return mob.getTarget() == bot;
    }

    private static boolean isEntityInWater(Entity entity) {
        if (entity == null) {
            return false;
        }
        return entity.isTouchingWater()
                || entity.isSubmergedInWater()
                || entity.isSwimming()
                || entity.getEntityWorld().getFluidState(entity.getBlockPos()).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                || entity.getEntityWorld().getFluidState(entity.getBlockPos().up()).isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    private static void moveToward(ServerPlayerEntity bot, Vec3d target, double stopDistance, boolean sprint) {
        FollowMovementService.moveToward(bot, target, stopDistance, sprint, () -> lowerShieldTracking(bot));
    }

    private static void followInputStep(ServerPlayerEntity bot, Vec3d targetPos, double distanceSq, boolean allowCloseStop, double followPersonalSpace) {
        FollowMovementService.followInputStep(bot, targetPos, distanceSq, allowCloseStop, followPersonalSpace, FOLLOW_SPRINT_DISTANCE_SQ);
    }

    private static boolean handleFollowObstacles(ServerPlayerEntity bot,
                                                 ServerPlayerEntity target,
                                                 Vec3d navGoalPos,
                                                 BlockPos navGoalBlock,
                                                 double progressDistSq,
                                                 double targetDistSq,
                                                 double absDeltaY,
                                                 boolean canSee,
                                                 boolean directBlocked,
                                                 boolean allowTeleportPref,
                                                 boolean forceWalk,
                                                 MinecraftServer server) {
        if (bot == null) {
            return false;
        }
        BotCommandStateService.State st = stateFor(bot);
        boolean fixedGoalActive = st != null && st.followFixedGoal != null;
        boolean returningToBase = fixedGoalActive && st != null && st.baseTarget != null;
        if (!fixedGoalActive && target == null) {
            return false;
        }
        boolean botSealed = isSealedSpace(bot);
        boolean commanderSealed = target != null && isSealedSpace(target);
        boolean teleportPreferenceEnabled = SkillPreferences.teleportDuringSkills(bot);
        // Only treat "very close" as resolved if we're not physically blocked; otherwise we still need
        // door/waypoint planning to reach the commander through the enclosure.
        if (progressDistSq <= 2.25D && canSee && !directBlocked) {
            UUID id = bot.getUuid();
            FOLLOW_LAST_DISTANCE_SQ.remove(id);
            FOLLOW_STAGNANT_TICKS.remove(id);
            FOLLOW_LAST_BLOCK_POS.remove(id);
            FOLLOW_POS_STAGNANT_TICKS.remove(id);
            FollowStateService.clearVerticalClimbLock(id);
            FOLLOW_VERTICAL_LOCK_LAST_POS.remove(id);
            FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.remove(id);
            FOLLOW_COMMANDER_LADDER_HINT.remove(id);
            return false;
        }

        UUID id = bot.getUuid();
        CommanderLadderHint ladderHint = getCommanderLadderHint(id);
        Double last = FOLLOW_LAST_DISTANCE_SQ.get(id);
        int stagnant = FOLLOW_STAGNANT_TICKS.getOrDefault(id, 0);
        if (last != null && progressDistSq >= last - 0.01D) {
            stagnant++;
        } else {
            stagnant = 0;
        }
        FOLLOW_LAST_DISTANCE_SQ.put(id, progressDistSq);

        // Secondary stagnation signal: if we're not changing block position at all, we're effectively stuck
        // (common when pushing into fences/doors/corners where distance may still jitter slightly).
        BlockPos curBlock = bot.getBlockPos();
        BlockPos prevBlock = FOLLOW_LAST_BLOCK_POS.get(id);
        int posStagnant = FOLLOW_POS_STAGNANT_TICKS.getOrDefault(id, 0);
        if (prevBlock != null && prevBlock.equals(curBlock)) {
            posStagnant++;
        } else {
            posStagnant = 0;
            FOLLOW_LAST_BLOCK_POS.put(id, curBlock.toImmutable());
        }
        FOLLOW_POS_STAGNANT_TICKS.put(id, posStagnant);
        int blockedTicks = FOLLOW_DIRECT_BLOCKED_TICKS.getOrDefault(id, 0);
        if (directBlocked) {
            blockedTicks++;
            FOLLOW_DIRECT_BLOCKED_TICKS.put(id, blockedTicks);
        } else {
            FOLLOW_DIRECT_BLOCKED_TICKS.remove(id);
            blockedTicks = 0;
        }
        int effectiveStagnant = Math.max(Math.max(stagnant, posStagnant), blockedTicks);
        int leafPosThreshold = returningToBase ? 3 : 5;
        int leafStagnantThreshold = returningToBase ? 6 : 10;
        if (directBlocked && posStagnant >= leafPosThreshold && effectiveStagnant >= leafStagnantThreshold) {
            BlockPos directionGoal = navGoalBlock != null ? navGoalBlock : (target != null ? target.getBlockPos() : null);
            Direction towardAction = directionGoal != null
                    ? approximateToward(bot.getBlockPos(), directionGoal)
                    : bot.getHorizontalFacing();
            if (MovementService.hasLeafObstruction(bot, towardAction)
                    && MovementService.clearLeafObstruction(bot, towardAction)) {
                maybeLogFollowDecision(bot, "leaf-cleared: toward=" + towardAction + " stagnant=" + effectiveStagnant);
                return true;
            }
        }

        if (returningToBase && navGoalBlock != null && effectiveStagnant >= 8) {
            FollowMovementService.FollowWaterEscapeResult waterEscape =
                    FollowMovementService.tryFollowWaterLedgeEscapeDetailed(bot, navGoalBlock);
            if (waterEscape != null && (waterEscape.outcome() == FollowMovementService.FollowWaterEscapeOutcome.INPUT_APPLIED
                    || waterEscape.outcome() == FollowMovementService.FollowWaterEscapeOutcome.DISPLACED)) {
                maybeLogFollowDecision(bot, "water-ledge-escape: outcome=" + waterEscape.outcome()
                        + " detail=" + waterEscape.detail());
                return true;
            }
        }

        // Escape lava, magma blocks, or fire underfoot.
        BlockPos dangerGoal = navGoalBlock != null ? navGoalBlock : (target != null ? target.getBlockPos() : null);
        if (dangerGoal != null && FollowMovementService.tryDangerousGroundEscape(bot, dangerGoal)) {
            maybeLogFollowDecision(bot, "dangerous-ground-escape");
            return true;
        }

        if (!fixedGoalActive && target != null && bot.isClimbing() && !target.isClimbing()) {
            double climbGap = target.getY() - bot.getY();
            if (climbGap >= -1.5D && climbGap <= 3.5D) {
                if (shouldAllowClimbExit(bot, target, ladderHint, effectiveStagnant + 4, false)
                        && tryExitClimbableTowardTarget(bot, target, effectiveStagnant + 4, "follow-climb-dismount", false)) {
                    return true;
                }
            }
        }

        boolean hintVerticalCommit = target != null && shouldCommitToHintLadder(bot, target, ladderHint);
        boolean targetAboveBot = target != null
                && ((target.getY() - bot.getY()) >= 3.0D
                || (hintVerticalCommit && (target.getY() - bot.getY()) >= 1.75D));
        if (!fixedGoalActive && target != null) {
            if (maybeAcquireVerticalClimbLock(bot, target, targetDistSq, targetAboveBot, directBlocked, effectiveStagnant)) {
                return true;
            }
            VerticalClimbLock verticalLock = FollowStateService.getVerticalClimbLock(id);
            if (verticalLock != null && tickVerticalClimbLock(bot, target, verticalLock, targetDistSq, effectiveStagnant, directBlocked)) {
                return true;
            }
        }
        boolean targetBelowBot = target != null
                && ((bot.getY() - target.getY()) >= 3.0D
                || (hintVerticalCommit && (bot.getY() - target.getY()) >= 1.75D));
        boolean preferVerticalClimb = shouldPreferVerticalClimb(bot, target, targetAboveBot, targetDistSq);
        if (!fixedGoalActive && targetBelowBot && tryVerticalClimbDescentAssist(bot, target, targetDistSq, effectiveStagnant, directBlocked)) {
            return true;
        }
        if (!fixedGoalActive && targetAboveBot && tryVerticalClimbAssist(bot, target, targetDistSq, effectiveStagnant, directBlocked)) {
            return true;
        }

        Vec3d commanderGoal = fixedGoalActive ? navGoalPos : positionOf(target);
        BlockPos commanderGoalBlock = fixedGoalActive ? navGoalBlock : target.getBlockPos();
        boolean commanderRouteBlocked = isDirectRouteBlocked(bot, commanderGoal, commanderGoalBlock);
        if (target != null && canSee && commanderRouteBlocked && isOpenDoorBetween(bot, target)) {
            commanderRouteBlocked = false;
        }
        // For fixed-goal follow, the original canSee flag represents LoS to the FINAL goal, which can be
        // blocked by distant terrain even when the current navigation goal (waypoint) is locally clear.
        // Use the direct-route check to the current nav goal as the "route clear" signal instead.
        boolean clearRoute = fixedGoalActive ? !commanderRouteBlocked : (canSee && !commanderRouteBlocked);
        if (clearRoute) {
            if (!botSealed && !commanderSealed) {
                // Door-plan scratch state is raycast-scoped — dropping it is fine, it'll
                // rebuild next tick if the bot encounters another door. But FOLLOW_WAYPOINTS
                // is the pathfinder's considered plan, produced by cell-by-cell analysis
                // that is geometrically stronger than this 2-height raycast. A raycast can
                // miss thin door panels, stair corners, and box-boundary precision issues
                // that the pathfinder caught. Discarding the plan on a raycast-only signal
                // drops the bot into direct-pursuit (followInputStep), which has no obstacle
                // awareness and freezes on the very wedges the pathfinder routed around.
                // Keep the waypoints; let them be consumed naturally as the bot progresses.
                // The commander-moved replan guard in maybeRequestFollowPathPlan handles
                // stale plans when the commander relocates.
                FOLLOW_DOOR_PLAN.remove(id);
                FOLLOW_DOOR_LAST_BLOCK.remove(id);
                FOLLOW_DOOR_STUCK_TICKS.remove(id);
                FOLLOW_DOOR_RECOVERY.remove(id);
                maybeLogFollowDecision(bot, "commander-route clear (waypoints preserved): dist="
                        + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
                return false;
            } else {
                maybeLogFollowDecision(bot, "commander-route clear but sealed: botSealed="
                        + botSealed + " commanderSealed=" + commanderSealed);
            }
        }

        if (!fixedGoalActive) {
            boolean overrideApplied = applyLongRangeFollowOverride(bot, target, targetDistSq, navGoalBlock, canSee, directBlocked, botSealed, commanderSealed);
            if (overrideApplied) {
                directBlocked = false;
            }
        }

        // Reuse return-to-base stuck escape logic for normal follow (non-fixed goal), but only when
        // teleportation is explicitly disabled ("force walk") and the bot is NOT mounted.
        //
        // Mining / pillaring escapes are far too destructive for normal follow, and when mounted they
        // can cause dismount loops and block-breaking while the horse is just trying to climb terrain.
        //
        // When the bot has line-of-sight to the target and is within 10 blocks, suppress all mining
        // escapes — the problem is navigation (terrain traversal), not physical obstruction.
        if (!fixedGoalActive && target != null) {
            if (forceWalk && !bot.hasVehicle()) {
                boolean suppressMining = canSee && targetDistSq < 100.0D;
                net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.tickAndCheckStuck(bot, positionOf(target), suppressMining);
            } else {
                // Prevent stale stuck timers from accumulating when not using the follow stuck system.
                net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.clear(bot.getUuid());
            }
        }

        if (target != null && shouldPrioritizeCommanderOverDoors(bot, target, canSee, directBlocked, targetDistSq, botSealed, commanderSealed)) {
            FOLLOW_DOOR_PLAN.remove(id);
            FOLLOW_DOOR_LAST_BLOCK.remove(id);
            FOLLOW_DOOR_STUCK_TICKS.remove(id);
            FOLLOW_DOOR_RECOVERY.remove(id);
            FOLLOW_WAYPOINTS.remove(id);
            // Check wolf-teleport BEFORE returning — otherwise the early exit
            // prevents the teleport check at the end of this method from ever firing.
            boolean soulBypassEarly = net.wcfcarolina13.GameAI.services.SoulOfEnderService.isActive(bot.getUuid());
            if ((!fixedGoalActive || soulBypassEarly) && allowTeleportPref
                    && shouldWolfTeleport(targetDistSq, absDeltaY, canSee, effectiveStagnant, server)) {
                if (tryWolfTeleport(bot, target, server)) {
                    FOLLOW_LAST_DISTANCE_SQ.remove(id);
                    FOLLOW_STAGNANT_TICKS.remove(id);
                    return true;
                }
            }
            maybeLogFollowDecision(bot, "skip-door-magnet: forcing direct follow dist="
                    + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq))
                    + " sealed=" + botSealed + "/" + commanderSealed);
            return false;
        }

        // If we are executing a door sub-goal (enclosure escape), drive that first.
        FollowDoorPlan activeDoorPlan = FOLLOW_DOOR_PLAN.get(id);
        long nowMs = System.currentTimeMillis();
        if (activeDoorPlan == null) {
            FOLLOW_VERTICAL_DOOR_LOOP_LAST_BASE.remove(id);
            FOLLOW_VERTICAL_DOOR_LOOP_STREAK.remove(id);
        }
        if (activeDoorPlan != null) {
            if (preferVerticalClimb && target != null) {
                FOLLOW_DOOR_PLAN.remove(id);
                FOLLOW_DOOR_LAST_BLOCK.remove(id);
                FOLLOW_DOOR_STUCK_TICKS.remove(id);
                FOLLOW_DOOR_RECOVERY.remove(id);
                avoidDoorFor(id, activeDoorPlan.doorBase(), 3_000L, "prefer-vertical-climb");
                maybeLogFollowDecision(bot, "door-plan cancel: prefer-vertical-climb doorBase="
                        + activeDoorPlan.doorBase().toShortString());
                if (tryVerticalClimbAssist(bot, target, targetDistSq, effectiveStagnant, directBlocked)) {
                    return true;
                }
                return false;
            }
            if (shouldBreakVerticalDoorLoop(bot, target, targetDistSq, targetAboveBot, directBlocked, effectiveStagnant, activeDoorPlan, nowMs)) {
                MovementService.markDoorEscapeFailed(bot, activeDoorPlan.doorBase());
                FOLLOW_DOOR_PLAN.remove(id);
                FOLLOW_DOOR_LAST_BLOCK.remove(id);
                FOLLOW_DOOR_STUCK_TICKS.remove(id);
                FOLLOW_DOOR_RECOVERY.remove(id);
                avoidDoorFor(id, activeDoorPlan.doorBase(), 12_000L, "vertical-door-loop-break");
                maybeLogFollowDecision(bot, "door-loop-break: doorBase=" + activeDoorPlan.doorBase().toShortString()
                        + " targetDy=" + String.format(Locale.ROOT, "%.2f", (target != null ? (target.getY() - bot.getY()) : 0.0D))
                        + " stagnant=" + effectiveStagnant);
                if (!fixedGoalActive && target != null) {
                    requestFollowPathPlan(bot, target, true, "vertical-door-loop-break");
                }
                if (!fixedGoalActive && targetAboveBot && tryVerticalClimbAssist(bot, target, targetDistSq, effectiveStagnant, directBlocked)) {
                    return true;
                }
                return false;
            }
            // If the commander moved away and we now have a clear direct route, stop "lingering" on the doorway
            // and resume normal follow immediately.
            if (canSee && targetDistSq <= 144.0D) { // ~12 blocks
                BlockPos commanderBlock = commanderGoalBlock != null
                        ? commanderGoalBlock
                        : (target != null ? target.getBlockPos() : null);
                boolean blockedToCommander = isDirectRouteBlocked(bot, commanderGoal, commanderBlock);
                if (!blockedToCommander) {
                    FOLLOW_DOOR_PLAN.remove(id);
                    FOLLOW_DOOR_LAST_BLOCK.remove(id);
                    FOLLOW_DOOR_STUCK_TICKS.remove(id);
                    FOLLOW_DOOR_RECOVERY.remove(id);
                    maybeLogFollowDecision(bot, "door-plan cancel: clear-to-commander dist="
                            + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
                    return false;
                }
            }
            if (nowMs >= activeDoorPlan.expiresAtMs()) {
                // Mark this door as failed to prevent oscillation.
                MovementService.markDoorEscapeFailed(bot, activeDoorPlan.doorBase());
                FOLLOW_DOOR_PLAN.remove(id);
                FOLLOW_DOOR_LAST_BLOCK.remove(id);
                FOLLOW_DOOR_STUCK_TICKS.remove(id);
                FOLLOW_DOOR_RECOVERY.remove(id);
            } else {
                boolean inProgress = tickFollowDoorPlan(bot, id, activeDoorPlan);
                if (inProgress) {
                    return true;
                }
            }
        }

        // If we're adjacent to a closed door, try opening it first (cheap, no raycast).
        // Guarded to avoid “door distraction” when the commander is far away and we don't actually need to interact with doors.
        if (!preferVerticalClimb && (directBlocked || effectiveStagnant >= 3 || targetDistSq <= 400.0D) && bot.getEntityWorld() instanceof ServerWorld world) {
            BlockPos base = bot.getBlockPos();
            Direction returnToBaseDoorDir = null;
            if (returningToBase && navGoalBlock != null) {
                returnToBaseDoorDir = approximateToward(base, navGoalBlock);
            }
            for (Direction dir : Direction.Type.HORIZONTAL) {
                if (returningToBase
                        && !botSealed
                        && !directBlocked
                        && effectiveStagnant < 10
                        && returnToBaseDoorDir != null
                        && dir != returnToBaseDoorDir) {
                    continue;
                }
                BlockPos candidate = base.offset(dir);
                if (world.getBlockState(candidate).getBlock() instanceof net.minecraft.block.DoorBlock
                        || world.getBlockState(candidate.up()).getBlock() instanceof net.minecraft.block.DoorBlock
                        || world.getBlockState(candidate).getBlock() instanceof FenceGateBlock
                        || world.getBlockState(candidate.up()).getBlock() instanceof FenceGateBlock) {
                    BlockPos doorBase = normalizeDoorBase(world, candidate);
                    if (doorBase != null) {
                        if (doorBase.equals(currentAvoidDoor(id))) {
                            continue;
                        }
                        // Skip doors that are NOT between bot and goal (avoids side-tracking on unrelated doors).
                        // A door is "between" if stepping through it gets us closer to the goal.
                        BlockPos goalBlock = navGoalBlock != null ? navGoalBlock : (target != null ? target.getBlockPos() : null);
                        if (goalBlock != null) {
                            double currentDistSq = base.getSquaredDistance(goalBlock);
                            FollowDoorPlan probePlan = buildFollowDoorPlan(bot, world, candidate);
                            BlockPos afterPos = probePlan != null ? probePlan.stepThroughPos() : candidate;
                            double afterDoorDistSq = afterPos.getSquaredDistance(goalBlock);
                            // If the door isn't closer to the goal than our current position, skip it
                            // unless we're directly blocked (stuck against something).
                            if (afterDoorDistSq >= currentDistSq && !directBlocked && effectiveStagnant < 6) {
                                continue;
                            }
                            if (returningToBase
                                    && !botSealed
                                    && !directBlocked
                                    && effectiveStagnant < 10
                                    && !isStepMeaningfullyTowardGoal(base, afterPos, goalBlock)) {
                                continue;
                            }
                        }
                        if (targetDistSq >= 900.0D && (MovementService.isDoorRecentlyClosed(id, doorBase)
                                || isNearRecentlyCrossedDoor(id, doorBase, 12_000L, 36.0D))) {
                            avoidDoorFor(id, doorBase, 8_000L, "avoid-adjacent-longrange");
                            maybeLogFollowDecision(bot, "skip-door: avoid adjacent doorBase=" + doorBase.toShortString()
                                    + " dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
                            continue;
                        }
                    }
                    if (MovementService.tryOpenDoorAt(bot, candidate)) {
                        // Reuse the proven "approach → open → step through" behavior whenever we're blocked.
                        if (directBlocked) {
                            FollowDoorPlan plan = buildFollowDoorPlan(bot, world, candidate);
                            if (plan != null) {
                                BlockPos goalForCheck = target != null ? target.getBlockPos() : navGoalBlock;
                                if (isDoorPlanWrongSide(plan.approachPos(), plan.stepThroughPos(), goalForCheck)) {
                                    avoidDoorFor(id, plan.doorBase(), 5_000L, "wrong-side-of-door");
                                    maybeLogFollowDecision(bot, "skip-door-adjacent: wrong side doorBase="
                                            + plan.doorBase().toShortString());
                                    break;
                                }
                                FOLLOW_DOOR_PLAN.put(id, plan);
                                maybeLogFollowDecision(bot, "door-adjacent: plan doorBase=" + plan.doorBase().toShortString()
                                        + " approach=" + plan.approachPos().toShortString()
                                        + " step=" + plan.stepThroughPos().toShortString());
                                return true;
                            }
                        }
                        break;
                    }
                }
            }
        }

        // If the direct route is blocked and we're not making progress, proactively treat a nearby door as an escape
        // objective instead of continuing to push into a fence/glass corner.
        // For fixed-goal (return-to-base), be more conservative to avoid oscillating on nearby doors.
        int escapeThreshold = fixedGoalActive ? 10 : ((targetDistSq >= 900.0D && !canSee) ? 8 : 4);
        if (!preferVerticalClimb && directBlocked && effectiveStagnant >= escapeThreshold && FOLLOW_DOOR_PLAN.get(id) == null && bot.getEntityWorld() instanceof ServerWorld world) {
            long now = System.currentTimeMillis();
            long lastPlanMs = FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.getOrDefault(id, -1L);
            // Use longer cooldown for fixed-goal to prevent rapid door oscillation.
            long planCooldown = fixedGoalActive ? 2000L : 900L;
            if (now - lastPlanMs >= planCooldown) {
                FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.put(id, now);
                BlockPos avoidDoor = currentAvoidDoor(id);
                long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(id, -1L);
                if (lastDoorMs >= 0 && (System.currentTimeMillis() - lastDoorMs) < 5_000L) {
                    avoidDoor = FOLLOW_LAST_DOOR_BASE.get(id);
                }
                MovementService.DoorSubgoalPlan escape = MovementService.findDoorEscapePlan(bot, navGoalBlock, avoidDoor);
                if (escape != null) {
                    if (returningToBase && !botSealed && navGoalBlock != null
                            && !isStepMeaningfullyTowardGoal(bot.getBlockPos(), escape.stepThroughPos(), navGoalBlock)) {
                        avoidDoorFor(id, escape.doorBase(), 8_000L, "return-base-escape-not-toward-goal");
                        maybeLogFollowDecision(bot, "skip-door: return-base escape not toward goal doorBase="
                                + escape.doorBase().toShortString());
                        escape = null;
                    }
                    if (escape != null && targetDistSq >= 900.0D && MovementService.isDoorRecentlyClosed(id, escape.doorBase())) {
                        avoidDoorFor(id, escape.doorBase(), 8_000L, "recently-closed-escape");
                        maybeLogFollowDecision(bot, "skip-door: recently closed escape doorBase=" + escape.doorBase().toShortString()
                                + " dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
                        escape = MovementService.findDoorEscapePlan(bot, navGoalBlock, escape.doorBase());
                    }
                    if (escape != null && targetDistSq >= 900.0D && isNearRecentlyCrossedDoor(id, escape.doorBase(), 12_000L, 36.0D)) {
                        avoidDoorFor(id, escape.doorBase(), 8_000L, "near-last-door-escape");
                        maybeLogFollowDecision(bot, "skip-door: near last-crossed escape doorBase=" + escape.doorBase().toShortString()
                                + " dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
                        escape = MovementService.findDoorEscapePlan(bot, navGoalBlock, escape.doorBase());
                    }
                    if (escape != null && (targetDistSq < 900.0D || !MovementService.isDoorRecentlyClosed(id, escape.doorBase()))) {
                        BlockPos goalForCheck = target != null ? target.getBlockPos() : navGoalBlock;
                        if (isDoorPlanWrongSide(escape.approachPos(), escape.stepThroughPos(), goalForCheck)) {
                            avoidDoorFor(id, escape.doorBase(), 5_000L, "wrong-side-of-door");
                            maybeLogFollowDecision(bot, "skip-door-escape: wrong side doorBase="
                                    + escape.doorBase().toShortString());
                        } else {
                        // Use longer timeout for fixed goal (return-to-base) since bot must reach destination
                        long doorTimeout = fixedGoalActive ? 10_000L : 5_000L;
                        FollowDoorPlan plan = new FollowDoorPlan(
                                escape.doorBase().toImmutable(),
                                escape.approachPos().toImmutable(),
                                escape.stepThroughPos().toImmutable(),
                                System.currentTimeMillis() + doorTimeout,
                                false
                        );
                        FOLLOW_DOOR_PLAN.put(id, plan);
                        maybeLogFollowDecision(bot, "door-escape: directBlocked stagnant=" + effectiveStagnant
                                + " blockedTicks=" + blockedTicks
                                + " doorBase=" + plan.doorBase().toShortString()
                                + " approach=" + plan.approachPos().toShortString()
                                + " step=" + plan.stepThroughPos().toShortString());
                        return true;
                        }
                    }
                }
                if (fixedGoalActive && navGoalBlock != null) {
                    requestFollowPathPlanToGoal(bot, navGoalBlock, true, "direct-blocked-stuck");
                } else {
                    if (target != null) {
                        requestFollowPathPlan(bot, target, true, "direct-blocked-stuck");
                    }
                }
            }
        }

        // Proactively open a door directly between bot and target when close enough.
        // This handles the common "follow while behind a closed door" case even when
        // distanceSq jitters and the stagnant counter doesn't hit exactly.
        if (!preferVerticalClimb && targetDistSq <= 400.0D) { // keep this local; long-range “door ray” tends to create distractions
            Vec3d doorRayGoal = navGoalPos != null ? navGoalPos : (target != null ? target.getEyePos() : null);
            if (doorRayGoal == null) {
                return false;
            }
            BlockPos blockingDoor = BlockInteractionService.findDoorAlongLine(bot, doorRayGoal, 5.5D);
            if (blockingDoor != null) {
                if (bot.getEntityWorld() instanceof ServerWorld world) {
                    BlockPos normalized = normalizeDoorBase(world, blockingDoor);
                    if (normalized != null && normalized.equals(currentAvoidDoor(id))) {
                        return false;
                    }
                }
                MovementService.tryOpenDoorAt(bot, blockingDoor);
                // Commit to stepping through the doorway (non-blocking) to avoid jittering on the threshold.
                if (bot.getEntityWorld() instanceof ServerWorld world) {
                    FollowDoorPlan plan = buildFollowDoorPlan(bot, world, blockingDoor);
                    if (plan != null) {
                        BlockPos goalForCheck = target != null ? target.getBlockPos() : navGoalBlock;
                        if (isDoorPlanWrongSide(plan.approachPos(), plan.stepThroughPos(), goalForCheck)) {
                            avoidDoorFor(id, plan.doorBase(), 5_000L, "wrong-side-of-door");
                            maybeLogFollowDecision(bot, "skip-door-ray: wrong side doorBase="
                                    + plan.doorBase().toShortString());
                            return false;
                        }
                        FOLLOW_DOOR_PLAN.put(id, plan);
                        maybeLogFollowDecision(bot, "door-ray: hit=" + blockingDoor.toShortString()
                                + " plan doorBase=" + plan.doorBase().toShortString()
                                + " approach=" + plan.approachPos().toShortString()
                                + " step=" + plan.stepThroughPos().toShortString());
                        return true;
                    }
                }
            }
        }

        // If the bot isn't making progress for a few ticks, try to open a door directly in the way.
        if (effectiveStagnant == 4) {
            if (navGoalBlock != null) {
                MovementService.tryOpenDoorToward(bot, navGoalBlock);
            } else if (target != null) {
                MovementService.tryOpenDoorToward(bot, target.getBlockPos());
            }
        }

        // If we're still stuck and the target is likely "around the corner", treat a nearby door as a sub-goal.
        if (!preferVerticalClimb && effectiveStagnant >= 6 && FOLLOW_DOOR_PLAN.get(id) == null && bot.getEntityWorld() instanceof ServerWorld world) {
            if (returningToBase && !botSealed && !directBlocked) {
                return false;
            }
            // If the commander is close and visible and we're not directly blocked, never detour through doors.
            // This prevents “door magnet” behavior when the bot is already in the right area.
            if (target != null && canSee && !directBlocked && targetDistSq <= 64.0D) {
                FOLLOW_WAYPOINTS.remove(id);
                return false;
            }
            // Only treat doors as a sub-goal when we have reason to think we need to change rooms (blocked or no LoS).
            if (target != null && !directBlocked && canSee) {
                return false;
            }
            // If the commander is far away, avoid “nearest door” distractions; follow will rely on direct pursuit
            // and (if enabled) wolf-teleport catch-up rather than local door heuristics.
            if (target != null && !directBlocked && !canSee && targetDistSq >= 900.0D) { // ~30 blocks
                return false;
            }
            BlockPos goalBlock = navGoalBlock != null ? navGoalBlock : (target != null ? target.getBlockPos() : null);
            if (goalBlock == null) {
                return false;
            }
            BlockPos avoidDoor = currentAvoidDoor(id);
            long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(id, -1L);
            if (lastDoorMs >= 0 && (System.currentTimeMillis() - lastDoorMs) < 5_000L) {
                avoidDoor = FOLLOW_LAST_DOOR_BASE.get(id);
            }
	            MovementService.DoorSubgoalPlan plan = MovementService.findDoorEscapePlan(bot, goalBlock, avoidDoor);
	            if (plan != null) {
	                if (targetDistSq >= 900.0D && MovementService.isDoorRecentlyClosed(id, plan.doorBase())) {
	                    avoidDoorFor(id, plan.doorBase(), 8_000L, "recently-closed-corner");
	                    maybeLogFollowDecision(bot, "skip-door: recently closed corner doorBase=" + plan.doorBase().toShortString()
	                            + " dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
	                    plan = MovementService.findDoorEscapePlan(bot, goalBlock, plan.doorBase());
	                }
	                if (plan != null && targetDistSq >= 900.0D && isNearRecentlyCrossedDoor(id, plan.doorBase(), 12_000L, 36.0D)) {
	                    avoidDoorFor(id, plan.doorBase(), 8_000L, "near-last-door-corner");
	                    maybeLogFollowDecision(bot, "skip-door: near last-crossed corner doorBase=" + plan.doorBase().toShortString()
	                            + " dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
	                    plan = MovementService.findDoorEscapePlan(bot, goalBlock, plan.doorBase());
	                }
	                if (plan != null) {
	                    BlockPos lastDoor = FOLLOW_LAST_DOOR_BASE.get(id);
	                    boolean sameDoorOscillation = lastDoorMs >= 0
	                            && lastDoor != null
	                            && lastDoor.equals(plan.doorBase())
	                            && (System.currentTimeMillis() - lastDoorMs) < 4_500L;
	                    BlockPos goalForCheck = target != null ? target.getBlockPos() : goalBlock;
	                    boolean wrongSideOfDoor = isDoorPlanWrongSide(plan.approachPos(), plan.stepThroughPos(), goalForCheck);
	                    if (sameDoorOscillation || wrongSideOfDoor) {
	                        if (wrongSideOfDoor) {
	                            avoidDoorFor(id, plan.doorBase(), 5_000L, "wrong-side-of-door");
	                            maybeLogFollowDecision(bot, "skip-door: bot already past door on commander's side doorBase="
	                                    + plan.doorBase().toShortString());
	                        }
	                    } else {
                        // Use longer timeout for fixed goal (return-to-base) since bot must reach destination
                        long doorTimeout = fixedGoalActive ? 10_000L : 4_000L;
                        FollowDoorPlan followPlan = new FollowDoorPlan(
                                plan.doorBase(),
                                plan.approachPos(),
                                plan.stepThroughPos(),
                                System.currentTimeMillis() + doorTimeout,
                                false
                        );
                        FOLLOW_DOOR_PLAN.put(id, followPlan);
                        maybeLogFollowDecision(bot, "door-corner: stagnant=" + effectiveStagnant
                                + " doorBase=" + followPlan.doorBase().toShortString()
                                + " approach=" + followPlan.approachPos().toShortString()
                                + " step=" + followPlan.stepThroughPos().toShortString());
                        return true;
                    }
                }
            }
        }

        if (fixedGoalActive && navGoalBlock != null) {
            maybeRequestFollowPathPlanToGoal(bot, navGoalBlock, canSee, effectiveStagnant);
        } else {
            if (target != null) {
                maybeRequestFollowPathPlan(bot, target, canSee, effectiveStagnant);
            }
        }

        Long replanAfterDoor = FOLLOW_REPLAN_AFTER_DOOR_MS.remove(id);
        if (replanAfterDoor != null && (System.currentTimeMillis() - replanAfterDoor) < 4_500L) {
            ArrayDeque<BlockPos> waypoints = FOLLOW_WAYPOINTS.get(id);
            if (waypoints == null || waypoints.isEmpty()) {
                if (fixedGoalActive && navGoalBlock != null) {
                    requestFollowPathPlanToGoal(bot, navGoalBlock, true, "post-door");
                } else {
                    if (target != null) {
                        requestFollowPathPlan(bot, target, true, "post-door");
                    }
                }
            }
        }

        // Wolf-style teleport catch-up:
        // - only when player has allowed teleport generally,
        // - only when far enough or when we've been stuck for a while,
        // - and never spam (cooldown).
        boolean soulBypass = net.wcfcarolina13.GameAI.services.SoulOfEnderService.isActive(bot.getUuid());
        if ((!fixedGoalActive || soulBypass) && allowTeleportPref && shouldWolfTeleport(targetDistSq, absDeltaY, canSee, effectiveStagnant, server)) {
            if (tryWolfTeleport(bot, target, server)) {
                FOLLOW_LAST_DISTANCE_SQ.remove(id);
                FOLLOW_STAGNANT_TICKS.remove(id);
                return true;
            }
        }

        if (effectiveStagnant == 0) {
            FOLLOW_STAGNANT_TICKS.remove(id);
        } else {
            FOLLOW_STAGNANT_TICKS.put(id, effectiveStagnant);
        }
        return false;
    }

    private static boolean shouldPrioritizeCommanderOverDoors(ServerPlayerEntity bot,
                                                              ServerPlayerEntity target,
                                                              boolean canSee,
                                                              boolean directBlocked,
                                                              double targetDistSq,
                                                              boolean botSealed,
                                                              boolean commanderSealed) {
        if (bot == null || target == null) {
            return false;
        }
        if (botSealed || commanderSealed) {
            return false;
        }
        if (!directBlocked && (canSee || targetDistSq >= 900.0D)) {
            return true;
        }
        return false;
    }

    private static double horizontalDistanceSq(ServerPlayerEntity bot, Vec3d targetPos) {
        if (bot == null || targetPos == null) {
            return Double.MAX_VALUE;
        }
        double dx = targetPos.x - bot.getX();
        double dz = targetPos.z - bot.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Villager-inspired sanity check: the bot should never commit to a door plan that would push
     * it further from its goal. If {@code approachPos} (bot's current side of the door) is already
     * closer to {@code goalBlock} than {@code stepPos} (the other side) is, the door is BEHIND the
     * bot relative to its goal — crossing it would be backwards. Applied to every door-plan
     * creation site so a single missed check can't reintroduce the mirror-image oscillation.
     */
    private static boolean isDoorPlanWrongSide(BlockPos approachPos, BlockPos stepPos, BlockPos goalBlock) {
        if (approachPos == null || stepPos == null || goalBlock == null) {
            return false;
        }
        double approachToGoalSq = approachPos.getSquaredDistance(goalBlock);
        double stepToGoalSq = stepPos.getSquaredDistance(goalBlock);
        return approachToGoalSq <= stepToGoalSq;
    }

    private static boolean isOpenOpenable(BlockState state) {
        if (state == null) {
            return false;
        }
        if (!state.contains(Properties.OPEN) || !Boolean.TRUE.equals(state.get(Properties.OPEN))) {
            return false;
        }
        var block = state.getBlock();
        return block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof TrapdoorBlock;
    }

    private static boolean isDirectRouteBlocked(ServerPlayerEntity bot, Vec3d goalPos, BlockPos goalBlock) {
        if (bot == null || goalPos == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        double[] heights = new double[] { 0.12D, 1.10D };
        boolean anyRayHit = false;
        for (double h : heights) {
            Vec3d from = new Vec3d(bot.getX(), bot.getY() + h, bot.getZ());
            Vec3d to = new Vec3d(goalPos.x, goalPos.y + h, goalPos.z);
            Vec3d delta = to.subtract(from);
            if (delta.lengthSquared() < 1.0E-4) {
                continue;
            }
            for (net.minecraft.world.RaycastContext.ShapeType shape : List.of(
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE
            )) {
                HitResult hit = world.raycast(new net.minecraft.world.RaycastContext(
                        from,
                        to,
                        shape,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE,
                        bot
                ));
                if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) {
                    continue;
                }
                // Open doors/gates/trapdoors present a small OUTLINE shape that the raycast may
                // clip, even though the passage is physically clear. Vanilla villagers route
                // through open doors as a normal path node — so should we. If the ray only hit
                // open openables, don't treat the route as blocked.
                BlockState hitState = world.getBlockState(bhr.getBlockPos());
                if (isOpenOpenable(hitState)) {
                    continue;
                }
                anyRayHit = true;
                // If we hit any block before reaching the goal, consider the direct route blocked.
                // goalBlock may be null (Vec3 goal), so treat any hit as blocked in that case.
                if (goalBlock == null || !bhr.getBlockPos().equals(goalBlock)) {
                    return true;
                }
            }
        }
        // If raycasts show a clear line, do NOT let the wide collision probe force a door plan. This prevents
        // false positives when the bot is close to walls/fences but still has a clear approach to the commander.
        if (!anyRayHit) {
            return false;
        }
        // Otherwise, fall back to a conservative collision-based probe (catches fence "gap raycast" cases).
        return isRouteLikelyBlockedByCollisions(bot, goalPos, goalBlock);
    }

    private static boolean isRouteLikelyBlockedByCollisions(ServerPlayerEntity bot, Vec3d goalPos, BlockPos goalBlock) {
        if (bot == null || goalPos == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        BlockPos botPos = bot.getBlockPos();
        BlockPos goalKey = goalBlock != null ? goalBlock : BlockPos.ofFloored(goalPos);
        Long last = FOLLOW_LAST_BLOCKED_PROBE_MS.get(id);
        BlockPos lastGoal = FOLLOW_LAST_BLOCKED_PROBE_GOAL.get(id);
        BlockPos lastBot = FOLLOW_LAST_BLOCKED_PROBE_BOTPOS.get(id);
        if (last != null && (now - last) < 250L && botPos.equals(lastBot) && goalKey.equals(lastGoal)) {
            return FOLLOW_LAST_BLOCKED_PROBE_RESULT.getOrDefault(id, Boolean.FALSE);
        }

        FOLLOW_LAST_BLOCKED_PROBE_MS.put(id, now);
        FOLLOW_LAST_BLOCKED_PROBE_GOAL.put(id, goalKey.toImmutable());
        FOLLOW_LAST_BLOCKED_PROBE_BOTPOS.put(id, botPos.toImmutable());

        Vec3d from = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d to = new Vec3d(goalPos.x, bot.getY(), goalPos.z);
        Vec3d delta = to.subtract(from);
        double len = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (len < 1.0E-4) {
            FOLLOW_LAST_BLOCKED_PROBE_RESULT.put(id, Boolean.FALSE);
            return false;
        }
        double maxDist = Math.min(8.0D, len);
        Vec3d dir = new Vec3d(delta.x / len, 0, delta.z / len);
        Vec3d perp = new Vec3d(-dir.z, 0, dir.x);

        // For very short distances, avoid wide probes that can falsely detect nearby enclosure walls.
        boolean shortProbe = maxDist <= 2.8D;
        double halfWidth = shortProbe ? 0.30D : Math.max(0.38D, (bot.getWidth() * 0.5D) + 0.10D);
        double height = Math.max(1.8D, bot.getHeight());
        double step = shortProbe ? 0.45D : 0.35D;
        double startT = shortProbe ? 0.85D : 0.45D;
        double[] offsets = shortProbe ? new double[] { 0.0D } : new double[] { 0.0D, 0.28D, -0.28D };

        boolean blocked = false;
        for (double t = startT; t <= maxDist; t += step) {
            Vec3d base = from.add(dir.multiply(t));
            for (double off : offsets) {
                Vec3d p = off == 0.0D ? base : base.add(perp.multiply(off));
                Box box = new Box(
                        p.x - halfWidth,
                        bot.getY(),
                        p.z - halfWidth,
                        p.x + halfWidth,
                        bot.getY() + height,
                        p.z + halfWidth
                );
                if (world.getBlockCollisions(bot, box).iterator().hasNext()) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) break;
        }

        FOLLOW_LAST_BLOCKED_PROBE_RESULT.put(id, blocked);
        return blocked;
    }

    private static boolean tickFollowDoorPlan(ServerPlayerEntity bot, UUID botId, FollowDoorPlan plan) {
        if (bot == null || botId == null || plan == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            FOLLOW_DOOR_PLAN.remove(botId);
            FOLLOW_DOOR_LAST_BLOCK.remove(botId);
            FOLLOW_DOOR_STUCK_TICKS.remove(botId);
            FOLLOW_DOOR_RECOVERY.remove(botId);
            return false;
        }

        FollowDoorRecovery recovery = FOLLOW_DOOR_RECOVERY.get(botId);
        if (recovery != null) {
            if (recovery.remainingTicks() <= 0) {
                FOLLOW_DOOR_RECOVERY.remove(botId);
            } else {
                BlockPos recoveryGoal = recovery.goal();
                if (!isStandable(world, recoveryGoal)) {
                    FOLLOW_DOOR_RECOVERY.remove(botId);
                } else {
                    Vec3d recoveryCenter = Vec3d.ofCenter(recoveryGoal);
                    double distSq = bot.squaredDistanceTo(recoveryCenter);
                    LookController.faceBlock(bot, recoveryGoal);
                    BotActions.sprint(bot, distSq > FOLLOW_SPRINT_DISTANCE_SQ);
                    BotActions.autoJumpIfNeeded(bot);
                    BotActions.applyMovementInput(bot, recoveryCenter, 0.14);
                    if (distSq <= 1.25D) {
                        FOLLOW_DOOR_RECOVERY.remove(botId);
                    } else {
                        FOLLOW_DOOR_RECOVERY.put(botId, new FollowDoorRecovery(recoveryGoal.toImmutable(), recovery.remainingTicks() - 1));
                    }
                    return true;
                }
            }
        }
        BlockPos doorBase = normalizeDoorBase(world, plan.doorBase());
        if (doorBase == null) {
            doorBase = plan.doorBase();
        }
        BlockState doorState = world.getBlockState(doorBase);

        // Keep the door open for the ENTIRE plan, not just the approach phase. If the door
        // auto-closed or the initial open was throttled, re-open each tick while within range.
        // Villager-inspired: they interact opportunistically, not as a one-shot phase gate.
        boolean doorOpen = doorState.contains(Properties.OPEN)
            && Boolean.TRUE.equals(doorState.get(Properties.OPEN));
        if (!doorOpen) {
            double doorDistSq = bot.squaredDistanceTo(Vec3d.ofCenter(doorBase));
            if (doorDistSq <= 4.0D) {
                MovementService.tryOpenDoorAt(bot, doorBase);
                doorState = world.getBlockState(doorBase);
                doorOpen = doorState.contains(Properties.OPEN)
                        && Boolean.TRUE.equals(doorState.get(Properties.OPEN));
            }
        }

        // Keep the plan stable: approach/step are chosen when the plan is built.
        // (Dynamic re-picking can oscillate around hinge corners.)
        BlockPos approachPos = plan.approachPos();
        BlockPos stepPos = plan.stepThroughPos();

        BlockPos goal = plan.stepping() ? stepPos : approachPos;
        Vec3d goalCenter = Vec3d.ofCenter(goal);
        double distSq = bot.squaredDistanceTo(goalCenter);
        boolean sprint = distSq > FOLLOW_SPRINT_DISTANCE_SQ;
        LookController.faceBlock(bot, goal);
        BotActions.sprint(bot, sprint);
        // User-observed asymmetry: the bot passes through doors reliably when the commander
        // is elevated (dy > 0.6 triggers an unconditional jump in applyHumanLikeForwardInput),
        // but stalls on level ground because BotActions.autoJumpIfNeeded explicitly skips
        // door cells ("doors handled elsewhere") and the headSpace probe rejects the door's
        // 3-pixel upper-half strip. Force a jump while the plan is actively stepping through
        // an open doorway — it lifts the bot over any pressure plate / rail / threshold
        // collision sitting inside or adjacent to the door frame. Matches vanilla villager
        // behavior: they don't hesitate at doorways they're actively crossing.
        // Force a jump during two door-plan scenarios that otherwise stall the bot on
        // head/torso-level partial blocks (slabs, stairs, trapdoors, signs, etc. — the
        // tower interior in screenshot 1 has stone brick stairs that partially overhang
        // the doorway threshold). Vanilla autoJumpIfNeeded explicitly skips door cells
        // and its headSpace probe rejects any head-cell with non-empty collision, so it
        // can't help here.
        //   (a) Stepping through an open door — matches the 1.1.9 fix, lifts bot over
        //       the door's 3-pixel leaf collision and any pressure plate at the threshold.
        //   (b) Approaching or stepping but stuck ≥ 8 ticks — a diagnostic timing: the
        //       user's observation is "bot unsticks when I jump a couple times" (commander
        //       Y > 0.6 triggers dy-based jump in applyHumanLikeForwardInput, which works).
        //       Trigger the same jump automatically when stagnant near the doorway.
        // 2026-08-28 field fix: the (a) case — jump on EVERY grounded tick while stepping —
        // is itself a stall under a low ceiling. A 2-high doorway with solid blocks above
        // gives a 1.8-tall bot only 0.2 clearance, so a jumping bot is airborne with its head
        // above the lintel exactly when it reaches the threshold and never enters the door
        // column; it lands, jumps again, and pins in place (observed 5s at a double door by a
        // descending staircase, until the plan TTL + wolf-teleport rescued it). The original
        // (a) motivations — the door's 3-pixel leaf collision, pressure plates, overhanging
        // stair thresholds — are all *stall* scenarios, so jump-on-stagnation covers them a
        // few ticks later; a bot that is actually progressing must never be forced airborne.
        int currentStuck = FOLLOW_DOOR_STUCK_TICKS.getOrDefault(botId, 0);
        if (FollowDoorRules.shouldForceDoorJump(currentStuck) && bot.isOnGround()) {
            BotActions.jump(bot);
        } else {
            BotActions.autoJumpIfNeeded(bot);
        }
        BotActions.applyMovementInput(bot, goalCenter, sprint ? 0.18 : 0.14);

        // Stuck tracking: bot's BlockPos hasn't changed in N ticks.
        // Villager learning: when stuck, vanilla mob AI cancels/replans — it never retreats
        // back through the door it's trying to cross. The old "door-recovery" retreated the
        // bot 2-3 blocks AWAY from the door, which created the follow-mode oscillation the
        // user observed. Old retreat logic was originally added for shelter-escape scenarios
        // where a trapped bot needed to back out of a corner to find an exit door; that use
        // case will need its own, separate handling (see construction-era context), but it
        // should not live in the general follow-mode door plan path.
        BlockPos curBlock = bot.getBlockPos();
        BlockPos prev = FOLLOW_DOOR_LAST_BLOCK.get(botId);
        int stuck = FOLLOW_DOOR_STUCK_TICKS.getOrDefault(botId, 0);
        // Compare X/Z only: a jump in place flips BlockPos Y (65↔66) every airborne tick,
        // and full-equality comparison let that bounce reset the counter — which is why the
        // 24-tick stuck-abort never fired during the observed 5s doorway pin.
        if (FollowDoorRules.samePlanColumn(prev, curBlock)) {
            stuck++;
        } else {
            stuck = 0;
            FOLLOW_DOOR_LAST_BLOCK.put(botId, curBlock.toImmutable());
        }
        FOLLOW_DOOR_STUCK_TICKS.put(botId, stuck);
        if (stuck >= FOLLOW_DOOR_STUCK_ABORT_TICKS) {
            maybeLogFollowDecision(bot, "door-plan abort: stuck=" + stuck
                    + " doorBase=" + doorBase.toShortString()
                    + " goal=" + goal.toShortString()
                    + " stepping=" + plan.stepping());
            avoidDoorFor(botId, doorBase, FOLLOW_DOOR_ABORT_AVOID_MS, "door-plan-stuck-abort");
            FOLLOW_DOOR_PLAN.remove(botId);
            FOLLOW_DOOR_LAST_BLOCK.remove(botId);
            FOLLOW_DOOR_STUCK_TICKS.remove(botId);
            FOLLOW_DOOR_RECOVERY.remove(botId);
            return false;
        }

        if (distSq <= 2.25D) {
            if (!plan.stepping()) {
                // Only flip to stepping when the door is confirmed open. The tick-loop
                // door-open above handles retries; we just check the result here.
                if (!doorOpen) {
                    return true;
                }
                FOLLOW_DOOR_PLAN.put(botId, new FollowDoorPlan(
                        doorBase.toImmutable(),
                        approachPos,
                        stepPos,
                        plan.expiresAtMs(),
                        true
                ));
                return true;
            }

            // Only consider the doorway crossed once we're no longer standing in the door block itself.
            BlockPos botBlock = bot.getBlockPos();
            boolean twoHighDoor = doorState.getBlock() instanceof net.minecraft.block.DoorBlock;
            if (botBlock.equals(doorBase) || (twoHighDoor && botBlock.equals(doorBase.up()))) {
                // Still “in the door”; keep executing the plan (or recovery) instead of oscillating.
                return true;
            }
            FOLLOW_DOOR_PLAN.remove(botId);
            FOLLOW_DOOR_LAST_BLOCK.remove(botId);
            FOLLOW_DOOR_STUCK_TICKS.remove(botId);
            FOLLOW_DOOR_RECOVERY.remove(botId);
            FOLLOW_LAST_DOOR_BASE.put(botId, plan.doorBase());
            FOLLOW_LAST_DOOR_CROSS_MS.put(botId, System.currentTimeMillis());
            FOLLOW_REPLAN_AFTER_DOOR_MS.put(botId, System.currentTimeMillis());
            avoidDoorFor(botId, doorBase, FOLLOW_POST_DOOR_AVOID_MS, "post-door-cross");
            return false;
        }
        return true;
    }

    private static FollowDoorPlan buildFollowDoorPlan(ServerPlayerEntity bot, ServerWorld world, BlockPos doorHit) {
        if (bot == null || world == null || doorHit == null) {
            return null;
        }
        BlockPos doorBase = normalizeDoorBase(world, doorHit);
        if (doorBase == null) {
            return null;
        }
        BlockPos avoidDoor = currentAvoidDoor(bot.getUuid());
        if (avoidDoor != null && avoidDoor.equals(doorBase)) {
            return null;
        }
        BlockState state = world.getBlockState(doorBase);
        boolean isDoor = state.getBlock() instanceof net.minecraft.block.DoorBlock;
        boolean isGate = state.getBlock() instanceof FenceGateBlock;
        if (!isDoor && !isGate) {
            return null;
        }
        if (isDoor && state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
            return null;
        }
        // Prefer true front/back tiles (more reliable than picking an arbitrary standable neighbor near the hinge).
        Direction facing = null;
        if (isDoor && state.contains(net.minecraft.block.DoorBlock.FACING)) {
            facing = state.get(net.minecraft.block.DoorBlock.FACING);
        } else if (isGate && state.contains(FenceGateBlock.FACING)) {
            facing = state.get(FenceGateBlock.FACING);
        }

        if (facing != null) {
            BlockPos front = doorBase.offset(facing);
            BlockPos back = doorBase.offset(facing.getOpposite());
            if (isStandable(world, front) && isStandable(world, back)) {
                BlockPos botPos = bot.getBlockPos();
                boolean botCloserToFront = botPos.getSquaredDistance(front) <= botPos.getSquaredDistance(back);
                BlockPos approach = (botCloserToFront ? front : back).toImmutable();
                BlockPos step = (botCloserToFront ? back : front).toImmutable();
                return new FollowDoorPlan(doorBase.toImmutable(), approach, step, System.currentTimeMillis() + 6_000L, false);
            }
        }
        BlockPos botPos = bot.getBlockPos();
        java.util.ArrayList<BlockPos> standableNeighbors = new java.util.ArrayList<>(4);
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos n = doorBase.offset(dir);
            if (isStandable(world, n)) {
                standableNeighbors.add(n.toImmutable());
            }
        }
        if (standableNeighbors.size() < 2) {
            return null;
        }
        standableNeighbors.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(botPos)));
        BlockPos approach = standableNeighbors.get(0);
        BlockPos step = standableNeighbors.stream()
                .max(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(approach)))
                .orElse(standableNeighbors.get(1));
        if (!isStandable(world, approach) || !isStandable(world, step) || approach.equals(step)) {
            return null;
        }
        return new FollowDoorPlan(doorBase.toImmutable(), approach.toImmutable(), step.toImmutable(), System.currentTimeMillis() + 6_000L, false);
    }

    private static void maybeRequestFollowPathPlan(ServerPlayerEntity bot, ServerPlayerEntity target, boolean canSee, int stagnantTicks) {
        if (bot == null || target == null) {
            return;
        }
        UUID botId = bot.getUuid();
        if (!FollowPathService.shouldPlan(bot, target, canSee, stagnantTicks)) {
            return;
        }
        // If we already have waypoints and aren't badly stuck, keep following them.
        ArrayDeque<BlockPos> existing = FOLLOW_WAYPOINTS.get(botId);
        if (existing != null && !existing.isEmpty() && stagnantTicks < 12) {
            BlockPos currentTarget = target.getBlockPos().toImmutable();
            BlockPos lastTarget = FOLLOW_LAST_PATH_TARGET.get(botId);
            double movedSq = lastTarget != null ? lastTarget.getSquaredDistance(currentTarget) : 0.0D;
            double distSq = bot.getBlockPos().getSquaredDistance(currentTarget);
            // If the commander moved significantly, or is far outside the bounded planner window,
            // drop stale door-centric waypoints so the bot doesn't “stick” to an old door plan.
            if (movedSq >= 256.0D || (!canSee && distSq >= 900.0D)) {
                FOLLOW_WAYPOINTS.remove(botId);
            } else {
                return;
            }
        }
        requestFollowPathPlan(bot, target, stagnantTicks >= 10, "stagnant-" + stagnantTicks);
    }

    private static void maybeRequestFollowPathPlanToGoal(ServerPlayerEntity bot, BlockPos goal, boolean canSee, int stagnantTicks) {
        if (bot == null || goal == null) {
            return;
        }
        if (canSee && stagnantTicks < 6) {
            return;
        }
        UUID botId = bot.getUuid();
        ArrayDeque<BlockPos> existing = FOLLOW_WAYPOINTS.get(botId);
        if (existing != null && !existing.isEmpty() && stagnantTicks < 12) {
            return;
        }
        requestFollowPathPlanToGoal(bot, goal, stagnantTicks >= 10, "stagnant-" + stagnantTicks);
    }

    private static void requestFollowPathPlan(ServerPlayerEntity bot, ServerPlayerEntity target, boolean force, String reason) {
        FollowPlannerService.requestPlanToTarget(LOGGER, bot, target, force, reason);
    }

    private static void requestFollowPathPlanToGoal(ServerPlayerEntity bot, BlockPos goal, boolean force, String reason) {
        noteComeRerouteAttempt(bot, goal, force, reason);
        FollowPlannerService.requestPlanToGoal(LOGGER, bot, goal, force, reason);
    }

    private static void noteComeRerouteAttempt(ServerPlayerEntity bot, BlockPos goal, boolean force, String reason) {
        if (bot == null || goal == null) {
            return;
        }
        BotCommandStateService.State st = stateFor(bot);
        if (st == null || st.followFixedGoal == null || !st.followFixedGoal.equals(goal)) {
            return;
        }
        if (!force && !isStuckDrivenPlanReason(reason)) {
            return;
        }
        MinecraftServer srv = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (srv == null) {
            return;
        }
        long nowTick = srv.getTicks();
        if (st.comeNextRerouteTick > nowTick) {
            if (shouldLogBackoff(bot.getUuid(), nowTick)) {
                LOGGER.info("[FollowAssert] planner-backoff bot={} goal={} reason={} nowTick={} nextRerouteTick={} attempts={}",
                        bot.getName().getString(),
                        goal.toShortString(),
                        reason == null ? "" : reason,
                        nowTick,
                        st.comeNextRerouteTick,
                        st.comeRerouteAttempts);
            }
            return;
        }
        st.comeRerouteAttempts = Math.max(0, st.comeRerouteAttempts) + 1;
        st.comeNextRerouteTick = nowTick + (force ? 30L : 20L);
        if (st.comeRerouteAttempts <= 2 || shouldLogBackoff(bot.getUuid(), nowTick)) {
            LOGGER.info("[FollowAssert] planner-backoff bot={} goal={} reason={} attempts={} nextRerouteTick={}",
                    bot.getName().getString(),
                    goal.toShortString(),
                    reason == null ? "" : reason,
                    st.comeRerouteAttempts,
                    st.comeNextRerouteTick);
        }
    }

    private static boolean shouldLogBackoff(UUID botId, long nowTick) {
        if (botId == null) {
            return false;
        }
        long last = FOLLOW_BACKOFF_LOG_TICK.getOrDefault(botId, Long.MIN_VALUE);
        if ((nowTick - last) < FOLLOW_BACKOFF_LOG_COOLDOWN_TICKS) {
            return false;
        }
        FOLLOW_BACKOFF_LOG_TICK.put(botId, nowTick);
        return true;
    }

    private static boolean isStuckDrivenPlanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.startsWith("stagnant-")
                || reason.equals("direct-blocked-stuck")
                || reason.equals("water-stuck")
                || reason.equals("water-ledge-stuck")
                || reason.startsWith("direct-blocked");
    }

    private static boolean triggerComeRecoverySkill(ServerPlayerEntity bot,
                                                   ServerPlayerEntity commander,
                                                   BlockPos goal,
                                                   Vec3d goalPos,
                                                   double deltaY,
                                                   double horizDistSq,
                                                   MinecraftServer server,
                                                   BotCommandStateService.State state) {
        if (bot == null || goal == null || goalPos == null || server == null || state == null) {
            return false;
        }
        if (bot.isDead() || bot.isRemoved()) {
            return false;
        }
        if (state.comeRecoverySkillInFlight) {
            LOGGER.info("[ComeRecovery] launch-skip bot={} goal={} reason=inflight",
                    bot.getName().getString(),
                    goal.toShortString());
            return false;
        }
        // Cap total recovery skill attempts per come session to prevent infinite dig loops.
        if (state.comeRecoverySkillAttempts >= 3) {
            LOGGER.info("[ComeRecovery] launch-skip bot={} goal={} reason=max-attempts-reached attempts={}",
                    bot.getName().getString(),
                    goal.toShortString(),
                    state.comeRecoverySkillAttempts);
            if (commander != null && state.comeRecoverySkillAttempts == 3) {
                // Announce once on the 3rd attempt hit
                sendBotMessage(bot, "I can't find a safe way to reach you after multiple attempts. Try /bot regroup again when you're closer.");
                state.comeRecoverySkillAttempts++; // increment past 3 so we don't announce again
            }
            return false;
        }

        int dyBlocks = (int) Math.round(deltaY);
        double horizDist = Math.sqrt(Math.max(0.0D, horizDistSq));

        // Pre-check: only probe short local goals. Long synchronous reachability probes on the server thread
        // were causing severe lag during segmented sunset returns.
        if (bot.getBlockPos().getSquaredDistance(goal) <= COME_REACHABILITY_PROBE_RANGE_SQ
                && bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld probeWorld) {
            if (net.wcfcarolina13.PathFinding.PathFinder.canReach(bot.getBlockPos(), goal, probeWorld, COME_REACHABILITY_PROBE_TIMEOUT_MS)) {
                LOGGER.info("[ComeRecovery] surface path exists to {} — forcing replan instead of mining",
                        goal.toShortString());
                requestFollowPathPlanToGoal(bot, goal, true, "come-reachable-replan");
                // Reset counter so we don't spam canReach every tick — wait another cycle
                state.comeTicksSinceBest = 0;
                state.comeNextSkillTick = server.getTicks() + COME_REACHABILITY_PROBE_COOLDOWN_TICKS;
                return false;
            }
        }

        // Priority 0: Pillar-up when bot is below goal with open sky above (shallow hole escape).
        if (dyBlocks >= 3 && horizDist <= 8.0D && bot.getEntityWorld() instanceof ServerWorld world) {
            boolean skyVisible = world.isSkyVisible(bot.getBlockPos().up(2));
            boolean inSafeZone = CompanionSafeZoneService.isProtected(world, bot.getBlockPos(), null);
            if (skyVisible && !inSafeZone) {
                int steps = Math.min(12, Math.max(3, dyBlocks + 1));
                String pillarAnnounce = bot.getName().getString()
                        + " is blocked getting to your last location; attempting to pillar up " + steps + " blocks.";

                state.comeRerouteAttempts = 0;
                state.comeNextRerouteTick = 0L;
                state.comeNextSkillTick = server.getTicks() + 120L;
                state.comeTicksSinceBest = 0;
                state.comeBestGoalDistSq = Double.NaN;
                state.comeRecoverySkillInFlight = true;
                state.comeRecoverySkillStartTick = server.getTicks();
                state.comeRecoverySkillAttempts++;

                LOGGER.info("[ComeRecovery] pillar-up-queued bot={} goal={} steps={} dyBlocks={} horizDist={}",
                        bot.getName().getString(),
                        goal.toShortString(),
                        steps,
                        dyBlocks,
                        String.format(Locale.ROOT, "%.2f", horizDist));

                final int finalSteps = steps;
                TaskService.forceAbort(bot.getUuid(), "\u00a7cInterrupted by pillar-up recovery.");
                try {
                    CompletableFuture.runAsync(() -> {
                        try {
                            server.execute(() -> {
                                if (commander != null) {
                                    ChatUtils.sendSystemMessage(commander.getCommandSource(), pillarAnnounce);
                                }
                            });
                            boolean success = ScaffoldService.pillarUp(bot, finalSteps, false);
                            String resultMsg = success
                                    ? "Pillar-up complete (" + finalSteps + " blocks)."
                                    : "Pillar-up failed — could not place all blocks.";
                            LOGGER.info("[ComeRecovery] pillar-up-result bot={} goal={} success={} steps={}",
                                    bot.getName().getString(),
                                    goal.toShortString(),
                                    success,
                                    finalSteps);
                            server.execute(() -> {
                                state.comeRecoverySkillInFlight = false;
                                state.comeRecoverySkillStartTick = 0L;
                                // Reset stuck state so mine-escape doesn't fire on stale stagnant counter
                                net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.clear(bot.getUuid());
                                if (commander != null) {
                                    ChatUtils.sendSystemMessage(commander.getCommandSource(), resultMsg);
                                }
                            });
                        } catch (Throwable t) {
                            LOGGER.warn("[ComeRecovery] pillar-up-failed bot={} goal={} err={}",
                                    bot.getName().getString(),
                                    goal.toShortString(),
                                    t.getClass().getSimpleName(),
                                    t);
                            server.execute(() -> {
                                state.comeRecoverySkillInFlight = false;
                                state.comeRecoverySkillStartTick = 0L;
                                String msg = "Pillar-up failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                                if (commander != null) {
                                    ChatUtils.sendSystemMessage(commander.getCommandSource(), msg);
                                }
                            });
                        }
                    }, COME_RECOVERY_EXECUTOR);
                } catch (Throwable t) {
                    LOGGER.warn("[ComeRecovery] pillar-up-queue-failed bot={} goal={} err={}",
                            bot.getName().getString(),
                            goal.toShortString(),
                            t.getClass().getSimpleName(),
                            t);
                    state.comeRecoverySkillInFlight = false;
                    state.comeRecoverySkillStartTick = 0L;
                    return false;
                }
                return true;
            }
        }

        // Mining-based recovery (collect_dirt, stripmine) requires inventory space.
        // If the bot's inventory is full, it can't pick up mined blocks and the skill loops
        // forever. Skip mining recovery and inform the player.
        {
            var inv = bot.getInventory();
            int emptySlots = 0;
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isEmpty()) emptySlots++;
            }
            if (emptySlots < 2) {
                LOGGER.info("[ComeRecovery] launch-skip bot={} goal={} reason=inventory-full emptySlots={}",
                        bot.getName().getString(), goal.toShortString(), emptySlots);
                sendBotMessage(bot, "I can't mine my way to you — my inventory is full. Please clear some space or come closer.");
                return false;
            }
        }

        Direction towardGoal = approximateToward(bot.getBlockPos(), goal);
        if (towardGoal == null || !towardGoal.getAxis().isHorizontal()) {
            towardGoal = bot.getHorizontalFacing();
        }

        String skillName = null;
        String rawArgs = null;
        Map<String, Object> params = new HashMap<>();
        params.put("direction", towardGoal);
        // Lock direction so consecutive recovery attempts don't zigzag (ascend west then east).
        params.put("lockDirection", true);

        // When we're vertically separated (common: tunnel below the destination), build stairs first.
        // We allow a moderate horizontal offset because stair-building still helps escape a narrow tunnel.
        if (Math.abs(dyBlocks) >= 3 && horizDist <= 12.0D) {
            skillName = "collect_dirt";
            int blocks = Math.min(12, Math.max(3, Math.abs(dyBlocks)));
            if (dyBlocks > 0) {
                params.put("ascentBlocks", blocks);
                params.put("ascentToSurface", true);
                rawArgs = "ascent " + blocks;
            } else {
                params.put("descentBlocks", blocks);
                rawArgs = "descent " + blocks;
            }
        } else if (horizDist >= 5.0D && horizDist <= 24.0D) {
            // If horizontally offset in a tunnel, carve toward the goal a bit and try again.
            skillName = "stripmine";
            int length = (int) Math.min(14, Math.max(6, Math.ceil(horizDist) + 2));
            params.put("count", length);
            rawArgs = Integer.toString(length);
        } else {
            return false;
        }

        String announce = bot.getName().getString()
                + " is blocked getting to your last location; attempting " + skillName + " (" + rawArgs + ").";

        // Avoid spamming skill launches.
        state.comeRerouteAttempts = 0;
        state.comeNextRerouteTick = 0L;
        state.comeNextSkillTick = server.getTicks() + 120L;
        state.comeTicksSinceBest = 0;
        state.comeBestGoalDistSq = Double.NaN;
        state.comeRecoverySkillInFlight = true;
        state.comeRecoverySkillStartTick = server.getTicks();
        state.comeRecoverySkillAttempts++;

        LOGGER.info("[ComeRecovery] launch-queued bot={} goal={} skill={} args={} dyBlocks={} horizDist={}",
                bot.getName().getString(),
                goal.toShortString(),
                skillName,
                rawArgs,
                dyBlocks,
                String.format(Locale.ROOT, "%.2f", horizDist));

        // Interrupt any active skill, then run the recovery skill asynchronously.
        final String finalSkillName = skillName;
        final String finalRawArgs = rawArgs;
        final Map<String, Object> finalParams = Map.copyOf(params);
        TaskService.forceAbort(bot.getUuid(), "§cInterrupted by /bot come recovery.");
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    SkillContext ctx = new SkillContext(
                            bot.getCommandSource(),
                            SharedStateService.safeSharedState("come-recovery"),
                            finalParams
                    );
                    LOGGER.info("[ComeRecovery] launch-confirmed bot={} goal={} skill={} args={}",
                            bot.getName().getString(),
                            goal.toShortString(),
                            finalSkillName,
                            finalRawArgs);
                    server.execute(() -> {
                        if (commander != null) {
                            ChatUtils.sendSystemMessage(commander.getCommandSource(), announce);
                        } else {
                            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), announce);
                        }
                    });
                    SkillExecutionResult result = SkillManager.runSkill(finalSkillName, ctx);
                    String resultMessage = (result != null && result.message() != null)
                            ? result.message()
                            : "Come recovery completed.";
                    LOGGER.info("[ComeRecovery] skill-result bot={} goal={} skill={} success={} msg='{}'",
                            bot.getName().getString(),
                            goal.toShortString(),
                            finalSkillName,
                            result != null && result.success(),
                            resultMessage);
                    server.execute(() -> {
                        state.comeRecoverySkillInFlight = false;
                        state.comeRecoverySkillStartTick = 0L;
                        // Reset stuck state so mine-escape doesn't fire on stale stagnant counter
                        net.wcfcarolina13.GameAI.services.ReturnBaseStuckService.clear(bot.getUuid());
                        // After successful recovery (especially surface ascent), update the
                        // come goal to the commander's CURRENT position. The old goal was
                        // underground — routing back to it sends the bot down its own tunnel.
                        if (result != null && result.success() && commander != null && !commander.isRemoved()) {
                            BlockPos freshGoal = commander.getBlockPos().toImmutable();
                            if (!freshGoal.equals(state.followFixedGoal)) {
                                LOGGER.info("[ComeRecovery] updating come goal from {} to {} (commander moved)",
                                        state.followFixedGoal != null ? state.followFixedGoal.toShortString() : "null",
                                        freshGoal.toShortString());
                                state.followFixedGoal = freshGoal;
                                state.comeBestGoalDistSq = Double.NaN;
                                state.comeTicksSinceBest = 0;
                                state.comeRerouteAttempts = 0;
                            }
                        }
                        if (commander != null) {
                            ChatUtils.sendSystemMessage(commander.getCommandSource(), resultMessage);
                        } else {
                            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), resultMessage);
                        }
                    });
                } catch (Throwable t) {
                    LOGGER.warn("[ComeRecovery] launch-failed bot={} goal={} skill={} args={} err={}",
                            bot.getName().getString(),
                            goal.toShortString(),
                            finalSkillName,
                            finalRawArgs,
                            t.getClass().getSimpleName(),
                            t);
                    server.execute(() -> {
                        state.comeRecoverySkillInFlight = false;
                        state.comeRecoverySkillStartTick = 0L;
                        String msg = "Come recovery failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                        if (commander != null) {
                            ChatUtils.sendSystemMessage(commander.getCommandSource(), msg);
                        } else {
                            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), msg);
                        }
                    });
                }
            }, COME_RECOVERY_EXECUTOR);
        } catch (Throwable t) {
            LOGGER.warn("[ComeRecovery] launch-queue-failed bot={} goal={} skill={} args={} err={}",
                    bot.getName().getString(),
                    goal.toShortString(),
                    finalSkillName,
                    finalRawArgs,
                    t.getClass().getSimpleName(),
                    t);
            state.comeRecoverySkillInFlight = false;
            state.comeRecoverySkillStartTick = 0L;
            String msg = "Come recovery failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            if (commander != null) {
                ChatUtils.sendSystemMessage(commander.getCommandSource(), msg);
            } else {
                ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), msg);
            }
            return false;
        }
        return true;
    }

    private static void maybeLogFollowDecision(ServerPlayerEntity bot, String message) {
        FollowDebugService.maybeLogDecision(LOGGER, bot, message);
    }

    private static void maybeLogFollowStatus(ServerPlayerEntity bot,
                                            ServerPlayerEntity target,
                                            double targetDistSq,
                                            double horizDistSq,
                                            boolean canSee,
                                            boolean directBlocked,
                                            boolean usingWaypoints,
                                            BlockPos navGoalBlock,
                                            int waypointCount,
                                            boolean botSealed,
                                            boolean commanderSealed) {
        if (bot == null || target == null) {
            return;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();

        FollowDoorPlan doorPlan = FOLLOW_DOOR_PLAN.get(botId);
        String doorPlanStr = "";
        if (doorPlan != null) {
            long remaining = Math.max(0L, doorPlan.expiresAtMs() - now);
            doorPlanStr = " doorPlan=doorBase=" + doorPlan.doorBase().toShortString()
                    + " approach=" + doorPlan.approachPos().toShortString()
                    + " step=" + doorPlan.stepThroughPos().toShortString()
                    + " stepping=" + doorPlan.stepping()
                    + " ttlMs=" + remaining;
        }

        BlockPos lastDoor = FOLLOW_LAST_DOOR_BASE.get(botId);
        long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(botId, -1L);
        String lastDoorStr = "";
        if (lastDoor != null && lastDoorMs >= 0) {
            lastDoorStr = " lastDoor=" + lastDoor.toShortString()
                    + " lastDoorAgeMs=" + (now - lastDoorMs);
        }

        BlockPos avoidDoor = currentAvoidDoor(botId);
        String avoidStr = avoidDoor != null ? (" avoidDoor=" + avoidDoor.toShortString()) : "";

        String navGoalStr = navGoalBlock != null ? navGoalBlock.toShortString() : "";
        FollowDebugService.maybeLogStatus(
                LOGGER,
                bot,
                target,
                targetDistSq,
                horizDistSq,
                canSee,
                directBlocked,
                usingWaypoints,
                waypointCount,
                botSealed,
                commanderSealed,
                navGoalStr,
                doorPlanStr,
                lastDoorStr,
                avoidStr
        );
    }

    private static void updateCommanderLadderHint(ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (bot == null || target == null) {
            return;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        CommanderLadderHint existing = FOLLOW_COMMANDER_LADDER_HINT.get(botId);

        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            FOLLOW_COMMANDER_LADDER_HINT.remove(botId);
            return;
        }

        if (target.isClimbing()) {
            BlockPos targetAnchor = findNearbyClimbableAnchor(world, target.getBlockPos(), 1, -2, 2);
            if (targetAnchor == null && isClimbable(world, target.getBlockPos())) {
                targetAnchor = target.getBlockPos().toImmutable();
            }
            if (targetAnchor != null) {
                BlockPos bottom = climbableColumnBottom(world, targetAnchor, 24);
                if (bottom != null) {
                    int topY = climbableColumnTopY(world, bottom, 24);
                    int direction = existing != null ? existing.recentDirection() : 0;
                    if (existing != null) {
                        double dy = target.getY() - existing.lastCommanderY();
                        if (Math.abs(dy) >= 0.08D) {
                            direction = dy > 0.0D ? 1 : -1;
                        }
                    }
                    long lastOffMs = existing != null ? existing.lastOffMs() : 0L;
                    FOLLOW_COMMANDER_LADDER_HINT.put(botId, new CommanderLadderHint(
                            bottom.getX(),
                            bottom.getZ(),
                            bottom.getY(),
                            topY,
                            now,
                            lastOffMs,
                            target.getY(),
                            direction
                    ));
                    return;
                }
            }
        }

        if (existing == null) {
            return;
        }
        if ((now - existing.lastSeenMs()) > FOLLOW_COMMANDER_LADDER_HINT_TTL_MS) {
            FOLLOW_COMMANDER_LADDER_HINT.remove(botId);
            return;
        }
        FOLLOW_COMMANDER_LADDER_HINT.put(botId, new CommanderLadderHint(
                existing.x(),
                existing.z(),
                existing.bottomY(),
                existing.topY(),
                existing.lastSeenMs(),
                now,
                target.getY(),
                existing.recentDirection()
        ));
    }

    private static CommanderLadderHint getCommanderLadderHint(UUID botId) {
        if (botId == null) {
            return null;
        }
        CommanderLadderHint hint = FOLLOW_COMMANDER_LADDER_HINT.get(botId);
        if (hint == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        boolean staleSeen = (now - hint.lastSeenMs()) > FOLLOW_COMMANDER_LADDER_HINT_TTL_MS;
        boolean staleOff = hint.lastOffMs() <= 0L || (now - hint.lastOffMs()) > FOLLOW_COMMANDER_LADDER_OFF_GRACE_MS;
        if (staleSeen && staleOff) {
            FOLLOW_COMMANDER_LADDER_HINT.remove(botId);
            return null;
        }
        return hint;
    }

    private static boolean isNearHintLadderColumn(BlockPos pos, CommanderLadderHint hint) {
        if (pos == null || hint == null) {
            return false;
        }
        int dx = Math.abs(pos.getX() - hint.x());
        int dz = Math.abs(pos.getZ() - hint.z());
        if (dx > 1 || dz > 1) {
            return false;
        }
        int minY = Math.min(hint.bottomY(), hint.topY()) - 1;
        int maxY = Math.max(hint.bottomY(), hint.topY()) + 1;
        return pos.getY() >= minY && pos.getY() <= maxY;
    }

    private static boolean shouldCommitToHintLadder(ServerPlayerEntity bot,
                                                    ServerPlayerEntity target,
                                                    CommanderLadderHint hint) {
        if (bot == null || target == null || hint == null) {
            return false;
        }
        double verticalGap = target.getY() - bot.getY();
        if (Math.abs(verticalGap) < 1.75D) {
            return false;
        }
        if (!isNearHintLadderColumn(bot.getBlockPos(), hint)) {
            return false;
        }
        double targetDyToColumn = Math.min(
                Math.abs(target.getY() - hint.bottomY()),
                Math.abs(target.getY() - hint.topY()));
        return targetDyToColumn <= 3.0D;
    }

    private static boolean shouldAllowClimbExit(ServerPlayerEntity bot,
                                                ServerPlayerEntity target,
                                                CommanderLadderHint hint,
                                                int stagnantTicks,
                                                boolean conservative) {
        if (bot == null || target == null) {
            return false;
        }
        double verticalGap = target.getY() - bot.getY();
        double distSq = bot.squaredDistanceTo(positionOf(target));
        if (Math.abs(verticalGap) <= 1.0D && distSq <= 2.25D) {
            return true;
        }

        if (hint == null) {
            // Without a remembered ladder column, be conservative for ascent so we don't bail off
            // an active ladder run a block or two too early.
            if (!conservative) {
                if (bot.getEntityWorld() instanceof ServerWorld world) {
                    BlockPos anchor = findNearbyClimbableAnchor(world, bot.getBlockPos(), 1, -1, 2);
                    if (anchor != null) {
                        int topY = climbableColumnTopY(world, anchor, 12);
                        if (topY >= target.getY() - 0.8D && (target.getY() - bot.getY()) > 0.85D) {
                            return false;
                        }
                    }
                }
            }
            double maxGap = conservative ? 1.25D : 1.0D;
            double maxDistSq = conservative ? 4.0D : 8.0D;
            if (Math.abs(verticalGap) <= maxGap && distSq <= maxDistSq) {
                return true;
            }
            return !conservative
                    && stagnantTicks >= (FOLLOW_CLIMB_EXIT_STAGNANT_TICKS + 8)
                    && Math.abs(verticalGap) <= 0.9D;
        }

        double targetY = target.getY();
        double botY = bot.getY();
        boolean targetNearTop = targetY >= (hint.topY() - 1.0D);
        boolean botNearTop = botY >= (hint.topY() - 0.5D);
        boolean targetNearBottom = targetY <= (hint.bottomY() + 1.0D);
        boolean botNearBottom = botY <= (hint.bottomY() + 0.1D);

        if (verticalGap >= 0.75D) {
            return targetNearTop && botNearTop;
        }
        if (verticalGap <= -0.75D) {
            return targetNearBottom && botNearBottom;
        }
        if (hint.recentDirection() > 0) {
            return targetNearTop && botNearTop;
        }
        if (hint.recentDirection() < 0) {
            return targetNearBottom && botNearBottom;
        }
        return (targetNearTop && botNearTop) || (targetNearBottom && botNearBottom);
    }

    private static boolean applyLongRangeFollowOverride(ServerPlayerEntity bot,
                                                        ServerPlayerEntity target,
                                                        double targetDistSq,
                                                        BlockPos navGoalBlock,
                                                        boolean canSee,
                                                        boolean directBlocked,
                                                        boolean botSealed,
                                                        boolean commanderSealed) {
        if (bot == null || target == null || !canSee || directBlocked || targetDistSq < 625.0D || botSealed || commanderSealed) {
            return false;
        }
	        BotStuckService.EnvironmentSnapshot env = BotStuckService.analyzeEnvironment(bot);
	        if (env == null || env.enclosed()) {
	            return false;
	        }
        UUID botId = bot.getUuid();
        boolean hadDoorPlan = FOLLOW_DOOR_PLAN.containsKey(botId);
        boolean hadWaypoints = FOLLOW_WAYPOINTS.containsKey(botId);
        FOLLOW_DOOR_PLAN.remove(botId);
        FOLLOW_DOOR_LAST_BLOCK.remove(botId);
        FOLLOW_DOOR_STUCK_TICKS.remove(botId);
        FOLLOW_DOOR_RECOVERY.remove(botId);
        FOLLOW_AVOID_DOOR_BASE.remove(botId);
        FOLLOW_AVOID_DOOR_UNTIL_MS.remove(botId);
        FOLLOW_WAYPOINTS.remove(botId);
        FOLLOW_LAST_DISTANCE_SQ.remove(botId);
        FOLLOW_STAGNANT_TICKS.remove(botId);
        FOLLOW_DIRECT_BLOCKED_TICKS.remove(botId);
        FOLLOW_POS_STAGNANT_TICKS.remove(botId);
        FOLLOW_LAST_BLOCK_POS.remove(botId);
        FollowStateService.clearVerticalClimbLock(botId);
        FOLLOW_VERTICAL_LOCK_LAST_POS.remove(botId);
        FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.remove(botId);
        FOLLOW_COMMANDER_LADDER_HINT.remove(botId);
        String reason = "long-range override: dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq))
                + " navGoal=" + (navGoalBlock != null ? navGoalBlock.toShortString() : "none")
                + " env.enclosed=" + env.enclosed()
                + " hadDoorPlan=" + hadDoorPlan
                + " hadWaypoints=" + hadWaypoints;
        maybeLogFollowDecision(bot, reason);
        return true;
    }

    private static boolean shouldPreferVerticalClimb(ServerPlayerEntity bot,
                                                     ServerPlayerEntity target,
                                                     boolean targetAboveBot,
                                                     double targetDistSq) {
        if (bot == null || target == null || !targetAboveBot) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (targetDistSq > (60.0D * 60.0D)) {
            return false;
        }
        // If we can likely use a ladder/vine/scaffolding nearby, suppress door-subgoal churn.
        return hasClimbableNearby(world, bot.getBlockPos(), 6, -3, 10)
                || hasClimbableNearby(world, target.getBlockPos(), 6, -6, 6);
    }

    private static boolean hasClimbableNearby(ServerWorld world,
                                              BlockPos center,
                                              int horizRadius,
                                              int minDy,
                                              int maxDy) {
        if (world == null || center == null || horizRadius <= 0) {
            return false;
        }
        for (int dx = -horizRadius; dx <= horizRadius; dx++) {
            for (int dz = -horizRadius; dz <= horizRadius; dz++) {
                for (int dy = minDy; dy <= maxDy; dy++) {
                    BlockPos probe = center.add(dx, dy, dz);
                    if (isClimbable(world, probe)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private record CommanderLadderHint(int x, int z, int bottomY, int topY, long lastSeenMs, long lastOffMs,
                                       double lastCommanderY, int recentDirection) {
    }

    private record ClimbAssistCandidate(BlockPos climbPos, BlockPos standPos, int topY, double score) {
    }

    private static boolean maybeAcquireVerticalClimbLock(ServerPlayerEntity bot,
                                                         ServerPlayerEntity target,
                                                         double targetDistSq,
                                                         boolean targetAboveBot,
                                                         boolean directBlocked,
                                                         int effectiveStagnant) {
        if (bot == null || target == null) {
            return false;
        }
        boolean allowIndependentSummit = directBlocked && effectiveStagnant >= 3;
        double verticalGap = target.getY() - bot.getY();
        if (allowIndependentSummit && verticalGap < -2.0D) {
            return false;
        }
        if (!targetAboveBot && !allowIndependentSummit) {
            return false;
        }
        UUID botId = bot.getUuid();
        if (FollowStateService.getVerticalClimbLock(botId) != null) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        long failCooldownUntil = FOLLOW_VERTICAL_LOCK_FAIL_COOLDOWN_UNTIL_MS.getOrDefault(botId, 0L);
        if (failCooldownUntil > nowMs) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (bot.hasVehicle() || bot.isTouchingWater() || ElytraFlightService.isInFlight(botId)) {
            return false;
        }
        double maxAcquireDistanceSq = targetAboveBot ? (80.0D * 80.0D) : (64.0D * 64.0D);
        if (targetDistSq > maxAcquireDistanceSq) {
            return false;
        }
        if (!directBlocked && effectiveStagnant < 2 && targetDistSq < 36.0D && !targetAboveBot) {
            return false;
        }

        CommanderLadderHint ladderHint = getCommanderLadderHint(botId);
        ClimbAssistCandidate candidate = candidateFromCommanderLadderHint(world, bot.getBlockPos(), target.getBlockPos(), ladderHint, true);
        if (candidate == null) {
            candidate = findBestClimbAssistCandidate(world, bot.getBlockPos(), target.getBlockPos(), 6);
        }
        if (candidate == null) {
            return false;
        }
        if (candidate.topY() <= bot.getY() + 1.5D) {
            return false;
        }

        BlockPos forcedDoorBase = null;
        BlockPos doorHit = BlockInteractionService.findDoorAlongLine(bot, Vec3d.ofCenter(candidate.standPos()), 6.5D);
        if (doorHit != null) {
            forcedDoorBase = normalizeDoorBase(world, doorHit);
        }
        double standDistSq = bot.squaredDistanceTo(Vec3d.ofCenter(candidate.standPos()));
        boolean standRouteBlocked = isDirectRouteBlocked(bot, Vec3d.ofCenter(candidate.standPos()), candidate.standPos());
        // Fallback: if raycast missed a door (diagonal line skips door block), do area scan
        if (forcedDoorBase == null && standRouteBlocked) {
            BlockPos nearDoor = findDoorNearPath(world, bot.getBlockPos(), candidate.standPos(), 2);
            // The area scan returns the nearest door in the bounding box, which can be a door
            // BESIDE the route: the 01:34 log committed Jake to a door two blocks west of a
            // straight-ahead stand, so the step-through pointed away from the goal (20 s loop).
            // Only force a door that lies roughly between the bot and the stand.
            if (nearDoor != null && isStepMeaningfullyTowardGoal(bot.getBlockPos(), nearDoor, candidate.standPos())) {
                forcedDoorBase = normalizeDoorBase(world, nearDoor);
            }
        }
        if (standDistSq > 4.0D && standRouteBlocked && forcedDoorBase == null) {
            FOLLOW_DOOR_PLAN.remove(botId);
            FOLLOW_DOOR_LAST_BLOCK.remove(botId);
            FOLLOW_DOOR_STUCK_TICKS.remove(botId);
            FOLLOW_DOOR_RECOVERY.remove(botId);
            FOLLOW_VERTICAL_LOCK_FAIL_COOLDOWN_UNTIL_MS.put(botId, nowMs + 2_500L);
            long lastReplanMs = FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.getOrDefault(botId, 0L);
            if ((nowMs - lastReplanMs) >= FOLLOW_VERTICAL_LOCK_REPLAN_COOLDOWN_MS) {
                FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.put(botId, nowMs);
                requestFollowPathPlanToGoal(bot, candidate.standPos(), true, "vertical-lock-stand-route-blocked");
            }
            maybeLogFollowDecision(bot, "vertical-lock skip: blocked stand route without forced door stand="
                    + candidate.standPos().toShortString());
            return false;
        }

        VerticalClimbLock lock = new VerticalClimbLock(
                candidate.climbPos().toImmutable(),
                candidate.standPos().toImmutable(),
                candidate.topY(),
                nowMs + FOLLOW_VERTICAL_LOCK_TTL_MS,
                0,
                nowMs,
                forcedDoorBase != null ? forcedDoorBase.toImmutable() : null,
                0
        );
        FollowStateService.setVerticalClimbLock(botId, lock);
        FOLLOW_DOOR_PLAN.remove(botId);
        FOLLOW_DOOR_LAST_BLOCK.remove(botId);
        FOLLOW_DOOR_STUCK_TICKS.remove(botId);
        FOLLOW_DOOR_RECOVERY.remove(botId);
        FOLLOW_VERTICAL_LOCK_LAST_POS.put(botId, bot.getBlockPos().toImmutable());
        MovementService.suppressDoorAutoClose(botId, FOLLOW_VERTICAL_LOCK_TTL_MS + 2_000L);
        maybeLogFollowDecision(bot, "vertical-lock acquire climbable=" + lock.entryClimbPos().toShortString()
                + " stand=" + lock.entryStandPos().toShortString()
                + " topY=" + lock.topY()
                + (lock.forcedDoorBase() != null ? " forcedDoor=" + lock.forcedDoorBase().toShortString() : ""));
        return tickVerticalClimbLock(bot, target, lock, targetDistSq, effectiveStagnant, directBlocked);
    }

    private static boolean tickVerticalClimbLock(ServerPlayerEntity bot,
                                                 ServerPlayerEntity target,
                                                 VerticalClimbLock lock,
                                                 double targetDistSq,
                                                 int effectiveStagnant,
                                                 boolean directBlocked) {
        if (bot == null || target == null || lock == null) {
            return false;
        }
        UUID botId = bot.getUuid();
        long nowMs = System.currentTimeMillis();
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            releaseVerticalClimbLock(bot, lock, "world-unavailable", true);
            return false;
        }

        double verticalGap = target.getY() - bot.getY();
        double remainingTopGap = lock.topY() - bot.getY();
        if (!FollowVerticalAssistPolicyUtil.shouldKeepVerticalLock(
                verticalGap,
                remainingTopGap,
                nowMs,
                lock.expiresAtMs(),
                lock.noProgressTicks(),
                FOLLOW_VERTICAL_LOCK_HARD_FAIL_TICKS)) {
            releaseVerticalClimbLock(bot, lock, "goal-reached-or-expired verticalGap="
                    + String.format(Locale.ROOT, "%.2f", verticalGap)
                    + " remainingTopGap=" + String.format(Locale.ROOT, "%.2f", remainingTopGap), false);
            return false;
        }

        FOLLOW_DOOR_PLAN.remove(botId);
        FOLLOW_DOOR_LAST_BLOCK.remove(botId);
        FOLLOW_DOOR_STUCK_TICKS.remove(botId);
        FOLLOW_DOOR_RECOVERY.remove(botId);
        MovementService.suppressDoorAutoClose(botId, 1_500L);

        BlockPos currentPos = bot.getBlockPos();
        BlockPos lastPos = FOLLOW_VERTICAL_LOCK_LAST_POS.get(botId);
        double currentStandDistSq = currentPos.getSquaredDistance(lock.entryStandPos());
        boolean progressMade = false;
        if (lastPos != null) {
            double previousStandDistSq = lastPos.getSquaredDistance(lock.entryStandPos());
            progressMade = currentPos.getY() > lastPos.getY()
                    || currentStandDistSq + 0.01D < previousStandDistSq;
        } else if (bot.isClimbing()) {
            progressMade = true;
        }
        FOLLOW_VERTICAL_LOCK_LAST_POS.put(botId, currentPos.toImmutable());

        int noProgressTicks = progressMade ? 0 : (lock.noProgressTicks() + 1);
        long lastProgressMs = progressMade ? nowMs : lock.lastProgressMs();
        int doorTraverseAttempts = lock.doorTraverseAttempts();
        if (noProgressTicks >= FOLLOW_VERTICAL_LOCK_NO_PROGRESS_TICKS) {
            long lastReplanMs = FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.getOrDefault(botId, 0L);
            if ((nowMs - lastReplanMs) >= FOLLOW_VERTICAL_LOCK_REPLAN_COOLDOWN_MS) {
                FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.put(botId, nowMs);
                requestFollowPathPlanToGoal(bot, lock.entryStandPos(), true, "vertical-climb-lock-stuck");
                maybeLogFollowDecision(bot, "vertical-lock replan stand=" + lock.entryStandPos().toShortString()
                        + " noProgressTicks=" + noProgressTicks
                        + " stagnant=" + effectiveStagnant
                        + " directBlocked=" + directBlocked
                        + " dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
            }
        }
        if (noProgressTicks >= FOLLOW_VERTICAL_LOCK_HARD_FAIL_TICKS) {
            FOLLOW_VERTICAL_LOCK_FAIL_COOLDOWN_UNTIL_MS.put(botId, nowMs + FOLLOW_VERTICAL_LOCK_FAIL_COOLDOWN_MS);
            releaseVerticalClimbLock(bot, lock, "hard-fail noProgressTicks=" + noProgressTicks, true);
            return false;
        }
        if (lock.forcedDoorBase() == null && currentStandDistSq > 4.0D && noProgressTicks >= FOLLOW_VERTICAL_LOCK_NO_DOOR_ABORT_TICKS) {
            releaseVerticalClimbLock(bot, lock, "no-door-progress-abort noProgressTicks=" + noProgressTicks, false);
            return false;
        }

        VerticalClimbLock updatedLock = new VerticalClimbLock(
                lock.entryClimbPos(),
                lock.entryStandPos(),
                lock.topY(),
                lock.expiresAtMs(),
                noProgressTicks,
                lastProgressMs,
                lock.forcedDoorBase(),
                doorTraverseAttempts
        );

        if (bot.isClimbing()) {
            FollowStateService.setVerticalClimbLock(botId, updatedLock);
            if (tryExitClimbableTowardTarget(bot, target, noProgressTicks, "vertical-lock", false)) {
                return true;
            }
            BlockPos climbAim = remainingTopGap > 0.8D ? updatedLock.entryClimbPos().up(2) : target.getBlockPos();
            Vec3d climbGoal = remainingTopGap > 0.8D ? Vec3d.ofCenter(updatedLock.entryClimbPos()) : positionOf(target);
            LookController.faceBlock(bot, climbAim);
            BotActions.sprint(bot, false);
            BotActions.jump(bot);
            BotActions.applyMovementInput(bot, climbGoal, 0.14D);
            maybeLogFollowDecision(bot, "vertical-lock tick: climbing targetY=" + target.getBlockY()
                    + " remainingTopGap=" + String.format(Locale.ROOT, "%.2f", remainingTopGap)
                    + " noProgressTicks=" + noProgressTicks);
            return true;
        }

        if (currentStandDistSq > 4.0D) {
            if (updatedLock.forcedDoorBase() != null
                    && updatedLock.doorTraverseAttempts() < 2
                    && (updatedLock.doorTraverseAttempts() == 0 || noProgressTicks >= 8)) {
                boolean traversed = MovementService.tryTraverseOpenableToward(
                        bot,
                        updatedLock.forcedDoorBase(),
                        updatedLock.entryStandPos(),
                        "vertical-lock");
                updatedLock = new VerticalClimbLock(
                        updatedLock.entryClimbPos(),
                        updatedLock.entryStandPos(),
                        updatedLock.topY(),
                        updatedLock.expiresAtMs(),
                        updatedLock.noProgressTicks(),
                        updatedLock.lastProgressMs(),
                        updatedLock.forcedDoorBase(),
                        updatedLock.doorTraverseAttempts() + 1
                );
                FollowStateService.setVerticalClimbLock(botId, updatedLock);
                if (traversed) {
                    maybeLogFollowDecision(bot, "vertical-lock tick: traversed forced door="
                            + updatedLock.forcedDoorBase().toShortString());
                    return true;
                }
            }
            Vec3d standCenter = Vec3d.ofCenter(updatedLock.entryStandPos());
            LookController.faceBlock(bot, updatedLock.entryClimbPos());
            BotActions.sprint(bot, false);
            BotActions.autoJumpIfNeeded(bot);
            BotActions.applyMovementInput(bot, standCenter, 0.17D);
            FollowStateService.setVerticalClimbLock(botId, updatedLock);
            maybeLogFollowDecision(bot, "vertical-lock tick: approach stand="
                    + updatedLock.entryStandPos().toShortString()
                    + " noProgressTicks=" + noProgressTicks);
            return true;
        }

        Vec3d climbCenter = Vec3d.ofCenter(updatedLock.entryClimbPos());
        LookController.faceBlock(bot, updatedLock.entryClimbPos().up());
        BotActions.sprint(bot, false);
        BotActions.jump(bot);
        BotActions.applyMovementInput(bot, climbCenter, 0.12D);
        FollowStateService.setVerticalClimbLock(botId, updatedLock);
        maybeLogFollowDecision(bot, "vertical-lock tick: engage climbable="
                + updatedLock.entryClimbPos().toShortString()
                + " topY=" + updatedLock.topY()
                + " targetY=" + target.getBlockY()
                + " noProgressTicks=" + noProgressTicks);
        return true;
    }

    private static void releaseVerticalClimbLock(ServerPlayerEntity bot,
                                                 VerticalClimbLock lock,
                                                 String reason,
                                                 boolean avoidDoor) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUuid();
        FollowStateService.clearVerticalClimbLock(botId);
        FOLLOW_VERTICAL_LOCK_LAST_POS.remove(botId);
        FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.remove(botId);
        FOLLOW_DOOR_PLAN.remove(botId);
        FOLLOW_DOOR_LAST_BLOCK.remove(botId);
        FOLLOW_DOOR_STUCK_TICKS.remove(botId);
        FOLLOW_DOOR_RECOVERY.remove(botId);
        if (avoidDoor && lock != null && lock.forcedDoorBase() != null) {
            avoidDoorFor(botId, lock.forcedDoorBase(), 12_000L, "vertical-lock-release");
        }
        maybeLogFollowDecision(bot, "vertical-lock release reason=" + reason
                + (lock != null && lock.forcedDoorBase() != null ? " doorBase=" + lock.forcedDoorBase().toShortString() : ""));
    }

    private static boolean tryVerticalClimbAssist(ServerPlayerEntity bot,
                                                  ServerPlayerEntity target,
                                                  double targetDistSq,
                                                  int effectiveStagnant,
                                                  boolean directBlocked) {
        if (bot == null || target == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (bot.hasVehicle() || bot.isTouchingWater() || ElytraFlightService.isInFlight(bot.getUuid())) {
            return false;
        }
        UUID id = bot.getUuid();
        CommanderLadderHint ladderHint = getCommanderLadderHint(id);
        boolean commitToHintLadder = shouldCommitToHintLadder(bot, target, ladderHint);
        double verticalGap = target.getY() - bot.getY();
        if (verticalGap < (commitToHintLadder ? 1.75D : 3.0D)) {
            return false;
        }
        if (targetDistSq > (80.0D * 80.0D)) {
            return false;
        }
        if (!commitToHintLadder && !directBlocked && effectiveStagnant < 2 && targetDistSq < 36.0D) {
            return false;
        }

        long nowMs = System.currentTimeMillis();
        long lastAssist = FOLLOW_LAST_VERTICAL_ASSIST_MS.getOrDefault(id, 0L);
        if (!commitToHintLadder && (nowMs - lastAssist) < FOLLOW_VERTICAL_ASSIST_COOLDOWN_MS) {
            return false;
        }

        if (bot.isClimbing()) {
            FOLLOW_LAST_VERTICAL_ASSIST_MS.put(id, nowMs);
            if (shouldAllowClimbExit(bot, target, ladderHint, effectiveStagnant, false)
                    && tryExitClimbableTowardTarget(bot, target, effectiveStagnant, "vertical-climb-assist", false)) {
                return true;
            }
            BlockPos climbAnchor = findNearbyClimbableAnchor(world, bot.getBlockPos(), 1, -1, 1);
            Vec3d climbGoal = climbAnchor != null ? Vec3d.ofCenter(climbAnchor) : positionOf(target);
            LookController.faceBlock(bot, climbAnchor != null ? climbAnchor.up() : target.getBlockPos());
            BotActions.sprint(bot, false);
            BotActions.jump(bot);
            BotActions.applyMovementInput(bot, climbGoal, climbAnchor != null ? 0.10D : 0.14D);
            if (effectiveStagnant >= 2) {
                maybeLogFollowDecision(bot, "vertical-climb-assist: already-climbing dy="
                        + String.format(Locale.ROOT, "%.2f", verticalGap)
                        + (climbAnchor != null ? " anchor=" + climbAnchor.toShortString() : ""));
            }
            return true;
        }

        ClimbAssistCandidate candidate = candidateFromCommanderLadderHint(world, bot.getBlockPos(), target.getBlockPos(), ladderHint, true);
        if (candidate == null) {
            candidate = findBestClimbAssistCandidate(world, bot.getBlockPos(), target.getBlockPos(), 6);
        }
        if (candidate == null) {
            return false;
        }

        FOLLOW_LAST_VERTICAL_ASSIST_MS.put(id, nowMs);
        Vec3d standCenter = Vec3d.ofCenter(candidate.standPos());
        double standDistSq = bot.squaredDistanceTo(standCenter);
        double engageStandDistSq = commitToHintLadder ? (1.6D * 1.6D) : (2.0D * 2.0D);
        if (standDistSq > engageStandDistSq) {
            LookController.faceBlock(bot, candidate.climbPos());
            BotActions.sprint(bot, false);
            BotActions.autoJumpIfNeeded(bot);
            BotActions.applyMovementInput(bot, standCenter, 0.17D);
            if (effectiveStagnant >= 2) {
                maybeLogFollowDecision(bot, "vertical-climb-assist: approach climbable="
                        + candidate.climbPos().toShortString()
                        + " stand=" + candidate.standPos().toShortString()
                        + " topY=" + candidate.topY());
            }
            return true;
        }

        Vec3d climbCenter = Vec3d.ofCenter(candidate.climbPos());
        LookController.faceBlock(bot, candidate.climbPos().up());
        BotActions.sprint(bot, false);
        BotActions.jump(bot);
        BotActions.applyMovementInput(bot, climbCenter, 0.12D);
        maybeLogFollowDecision(bot, "vertical-climb-assist: engage climbable="
                + candidate.climbPos().toShortString()
                + " topY=" + candidate.topY()
                + " targetY=" + target.getBlockY());
        return true;
    }

    private static boolean tryVerticalClimbDescentAssist(ServerPlayerEntity bot,
                                                         ServerPlayerEntity target,
                                                         double targetDistSq,
                                                         int effectiveStagnant,
                                                         boolean directBlocked) {
        if (bot == null || target == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (bot.hasVehicle() || bot.isTouchingWater() || ElytraFlightService.isInFlight(bot.getUuid())) {
            return false;
        }
        UUID id = bot.getUuid();
        CommanderLadderHint ladderHint = getCommanderLadderHint(id);
        boolean commitToHintLadder = shouldCommitToHintLadder(bot, target, ladderHint);
        double verticalDrop = bot.getY() - target.getY();
        if (verticalDrop < (commitToHintLadder ? 1.75D : 3.0D)) {
            return false;
        }
        if (targetDistSq > (80.0D * 80.0D)) {
            return false;
        }
        if (!commitToHintLadder && !directBlocked && effectiveStagnant < 2 && targetDistSq < 36.0D && !bot.isClimbing()) {
            return false;
        }

        long nowMs = System.currentTimeMillis();
        long lastAssist = FOLLOW_LAST_VERTICAL_ASSIST_MS.getOrDefault(id, 0L);
        if (!commitToHintLadder && (nowMs - lastAssist) < FOLLOW_VERTICAL_ASSIST_COOLDOWN_MS) {
            return false;
        }

        if (bot.isClimbing()) {
            FOLLOW_LAST_VERTICAL_ASSIST_MS.put(id, nowMs);
            if (shouldAllowClimbExit(bot, target, ladderHint, effectiveStagnant, true)
                    && tryExitClimbableTowardTarget(bot, target, effectiveStagnant, "vertical-descent-assist", true)) {
                return true;
            }
            BlockPos climbAnchor = findNearbyClimbableAnchor(world, bot.getBlockPos(), 1, -1, 1);
            Vec3d descentGoal = climbAnchor != null ? Vec3d.ofCenter(climbAnchor) : positionOf(target);
            LookController.faceBlock(bot, climbAnchor != null ? climbAnchor.down() : target.getBlockPos());
            BotActions.sprint(bot, false);
            BotActions.applyMovementInput(bot, descentGoal, climbAnchor != null ? 0.08D : 0.12D);
            if (bot.getVelocity().y > -0.08D) {
                Vec3d velocity = bot.getVelocity();
                bot.setVelocity(velocity.x, -0.08D, velocity.z);
                bot.velocityDirty = true;
            }
            if (effectiveStagnant >= 2) {
                maybeLogFollowDecision(bot, "vertical-descent-assist: descending dy="
                        + String.format(Locale.ROOT, "%.2f", verticalDrop)
                        + (climbAnchor != null ? " anchor=" + climbAnchor.toShortString() : ""));
            }
            return true;
        }

        ClimbAssistCandidate candidate = candidateFromCommanderLadderHint(world, bot.getBlockPos(), target.getBlockPos(), ladderHint, false);
        if (candidate == null) {
            candidate = findBestClimbDescentCandidate(world, bot.getBlockPos(), target.getBlockPos(), 8);
        }
        if (candidate == null) {
            return false;
        }

        FOLLOW_LAST_VERTICAL_ASSIST_MS.put(id, nowMs);
        Vec3d standCenter = Vec3d.ofCenter(candidate.standPos());
        double standDistSq = bot.squaredDistanceTo(standCenter);
        double engageStandDistSq = commitToHintLadder ? (1.6D * 1.6D) : (2.0D * 2.0D);
        if (standDistSq > engageStandDistSq) {
            LookController.faceBlock(bot, candidate.climbPos());
            BotActions.sprint(bot, false);
            BotActions.autoJumpIfNeeded(bot);
            BotActions.applyMovementInput(bot, standCenter, 0.17D);
            if (effectiveStagnant >= 2) {
                maybeLogFollowDecision(bot, "vertical-descent-assist: approach climbable="
                        + candidate.climbPos().toShortString()
                        + " stand=" + candidate.standPos().toShortString()
                        + " targetY=" + target.getBlockY());
            }
            return true;
        }

        Vec3d climbCenter = Vec3d.ofCenter(candidate.climbPos());
        LookController.faceBlock(bot, candidate.climbPos());
        BotActions.sprint(bot, false);
        BotActions.autoJumpIfNeeded(bot);
        BotActions.applyMovementInput(bot, climbCenter, 0.12D);
        maybeLogFollowDecision(bot, "vertical-descent-assist: engage climbable="
                + candidate.climbPos().toShortString()
                + " topY=" + candidate.topY()
                + " targetY=" + target.getBlockY());
        return true;
    }

    private static boolean tryExitClimbableTowardTarget(ServerPlayerEntity bot,
                                                         ServerPlayerEntity target,
                                                         int stagnantTicks,
                                                         String context,
                                                         boolean conservative) {
        if (bot == null || target == null || !bot.isClimbing()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (target.isClimbing()) {
            return false;
        }
        double verticalGap = target.getY() - bot.getY();
        double targetDistSq = bot.squaredDistanceTo(positionOf(target));
        double maxCloseGap = conservative ? 1.25D : 2.2D;
        double maxCloseDistSq = conservative ? 4.0D : 12.25D;
        boolean closeEnoughToDismount = Math.abs(verticalGap) <= maxCloseGap && targetDistSq <= maxCloseDistSq;
        boolean stagnantNearLadder = !conservative
                && Math.abs(verticalGap) <= 3.0D
                && stagnantTicks >= FOLLOW_CLIMB_EXIT_STAGNANT_TICKS;
        if (!closeEnoughToDismount && !stagnantNearLadder) {
            return false;
        }

        BlockPos origin = bot.getBlockPos();
        ArrayList<BlockPos> candidates = new ArrayList<>(96);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = -1; dy <= 4; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (origin.getSquaredDistance(candidate) <= 10.0D) {
                        candidates.add(candidate);
                    }
                }
            }
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            if (candidate == null || !isStandable(world, candidate)) {
                continue;
            }
            if (isClimbable(world, candidate)) {
                continue;
            }
            double toTargetSq = candidate.getSquaredDistance(target.getBlockPos());
            double originPenalty = candidate.getSquaredDistance(origin) * 0.25D;
            double verticalPenalty = Math.abs(candidate.getY() - target.getBlockY()) * 2.0D;
            double score = toTargetSq + originPenalty + verticalPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = candidate.toImmutable();
            }
        }

        if (best == null) {
            return false;
        }

        Vec3d bestCenter = Vec3d.ofCenter(best);
        LookController.faceBlock(bot, best);
        BotActions.sprint(bot, false);
        BotActions.jump(bot);
        BotActions.autoJumpIfNeeded(bot);
        BotActions.applyMovementInput(bot, bestCenter, 0.15D);
        if (best.getY() >= origin.getY() + 1) {
            Vec3d velocity = bot.getVelocity();
            bot.setVelocity(velocity.x, Math.max(velocity.y, 0.22D), velocity.z);
            bot.velocityDirty = true;
        }
        maybeLogFollowDecision(bot, "climb-exit: context=" + context
                + " to=" + best.toShortString()
                + " dy=" + String.format(Locale.ROOT, "%.2f", verticalGap)
                + " stagnant=" + stagnantTicks
                + " conservative=" + conservative);
        return true;
    }

    private static ClimbAssistCandidate candidateFromCommanderLadderHint(ServerWorld world,
                                                                         BlockPos origin,
                                                                         BlockPos goal,
                                                                         CommanderLadderHint hint,
                                                                         boolean ascending) {
        if (world == null || origin == null || goal == null || hint == null) {
            return null;
        }
        int columnTop = Math.max(hint.topY(), hint.bottomY());
        int columnBottom = Math.min(hint.bottomY(), hint.topY());
        if (ascending && columnTop < origin.getY() - 1) {
            return null;
        }
        if (!ascending && columnBottom > origin.getY() + 1) {
            return null;
        }

        BlockPos climbPos = resolveHintClimbPosAtY(world, hint, origin.getY());
        if (climbPos == null) {
            return null;
        }

        ClimbAssistCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos standPos = climbPos.offset(dir);
            if (!isStandable(world, standPos)) {
                continue;
            }
            double botDx = standPos.getX() - origin.getX();
            double botDz = standPos.getZ() - origin.getZ();
            double botHorizontalDistSq = (botDx * botDx) + (botDz * botDz);
            if (!FollowVerticalAssistPolicyUtil.isReachableEntryStand(
                    origin.getY(),
                    standPos.getY(),
                    botHorizontalDistSq,
                    1,
                    64.0D)) {
                continue;
            }
            double goalDx = standPos.getX() - goal.getX();
            double goalDz = standPos.getZ() - goal.getZ();
            double goalHorizontalDistSq = (goalDx * goalDx) + (goalDz * goalDz);
            int verticalGap = ascending
                    ? Math.abs(goal.getY() - columnTop)
                    : Math.abs(goal.getY() - columnBottom);
            double score = FollowVerticalAssistPolicyUtil.scoreCandidate(
                    true,
                    verticalGap,
                    botHorizontalDistSq,
                    goalHorizontalDistSq);
            if (score < bestScore) {
                bestScore = score;
                best = new ClimbAssistCandidate(climbPos.toImmutable(), standPos.toImmutable(), columnTop, score);
            }
        }
        return best;
    }

    private static BlockPos resolveHintClimbPosAtY(ServerWorld world, CommanderLadderHint hint, int sampleY) {
        if (world == null || hint == null) {
            return null;
        }
        int minY = Math.min(hint.bottomY(), hint.topY());
        int maxY = Math.max(hint.bottomY(), hint.topY());
        int clampedY = Math.max(minY, Math.min(maxY, sampleY));
        for (int spread = 0; spread <= 4; spread++) {
            int upY = clampedY + spread;
            if (upY <= maxY) {
                BlockPos up = new BlockPos(hint.x(), upY, hint.z());
                if (isClimbable(world, up)) {
                    return up.toImmutable();
                }
            }
            if (spread > 0) {
                int downY = clampedY - spread;
                if (downY >= minY) {
                    BlockPos down = new BlockPos(hint.x(), downY, hint.z());
                    if (isClimbable(world, down)) {
                        return down.toImmutable();
                    }
                }
            }
        }
        for (int y = minY; y <= maxY; y++) {
            BlockPos probe = new BlockPos(hint.x(), y, hint.z());
            if (isClimbable(world, probe)) {
                return probe.toImmutable();
            }
        }
        return null;
    }

    private static ClimbAssistCandidate findBestClimbAssistCandidate(ServerWorld world,
                                                                     BlockPos origin,
                                                                     BlockPos goal,
                                                                     int radius) {
        return findBestClimbAssistCandidate(world, origin, goal, radius, -3, 10, false);
    }

    private static ClimbAssistCandidate findBestClimbAssistCandidate(ServerWorld world,
                                                                     BlockPos origin,
                                                                     BlockPos goal,
                                                                     int radius,
                                                                     int minDy,
                                                                     int maxDy,
                                                                     boolean requireTopNearOrigin) {
        if (world == null || origin == null || goal == null || radius <= 0) {
            return null;
        }
        Set<BlockPos> visitedColumns = new HashSet<>();
        ClimbAssistCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = minDy; dy <= maxDy; dy++) {
                    BlockPos probe = origin.add(dx, dy, dz);
                    if (!isClimbable(world, probe)) {
                        continue;
                    }
                    BlockPos columnBottom = climbableColumnBottom(world, probe, 12);
                    if (columnBottom == null || !visitedColumns.add(columnBottom)) {
                        continue;
                    }
                    int topY = climbableColumnTopY(world, columnBottom, 12);
                    if (requireTopNearOrigin && topY < (origin.getY() - 1)) {
                        continue;
                    }
                    int attachY = Math.max(columnBottom.getY(), Math.min(topY, origin.getY()));
                    BlockPos climbPos = new BlockPos(columnBottom.getX(), attachY, columnBottom.getZ());
                    for (Direction dir : Direction.Type.HORIZONTAL) {
                        BlockPos standPos = climbPos.offset(dir);
                        if (!isStandable(world, standPos)) {
                            continue;
                        }
                        double botDx = standPos.getX() - origin.getX();
                        double botDz = standPos.getZ() - origin.getZ();
                        double botHorizontalDistSq = (botDx * botDx) + (botDz * botDz);
                        if (!FollowVerticalAssistPolicyUtil.isReachableEntryStand(
                                origin.getY(),
                                standPos.getY(),
                                botHorizontalDistSq,
                                1,
                                36.0D)) {
                            continue;
                        }
                        double goalDx = standPos.getX() - goal.getX();
                        double goalDz = standPos.getZ() - goal.getZ();
                        double goalHorizontalDistSq = (goalDx * goalDx) + (goalDz * goalDz);
                        int absGoalTopY = Math.abs(goal.getY() - topY);
                        double attachPenalty = Math.abs(origin.getY() - attachY) * 25.0D;
                        double score = FollowVerticalAssistPolicyUtil.scoreCandidate(
                                true,
                                absGoalTopY,
                                botHorizontalDistSq,
                                goalHorizontalDistSq) + attachPenalty;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new ClimbAssistCandidate(climbPos.toImmutable(), standPos.toImmutable(), topY, score);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static ClimbAssistCandidate findBestClimbDescentCandidate(ServerWorld world,
                                                                      BlockPos origin,
                                                                      BlockPos goal,
                                                                      int radius) {
        if (world == null || origin == null || goal == null || radius <= 0) {
            return null;
        }
        Set<Long> visitedColumns = new HashSet<>();
        ClimbAssistCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 4; dy++) {
                    BlockPos probe = origin.add(dx, dy, dz);
                    if (!isClimbable(world, probe)) {
                        continue;
                    }
                    BlockPos bottom = climbableColumnBottom(world, probe, 16);
                    if (bottom == null) {
                        continue;
                    }
                    long columnKey = BlockPos.asLong(bottom.getX(), 0, bottom.getZ());
                    if (!visitedColumns.add(columnKey)) {
                        continue;
                    }
                    int topY = climbableColumnTopY(world, bottom, 16);
                    if (topY < origin.getY() - 2 || topY > origin.getY() + 3) {
                        continue;
                    }
                    int bottomY = bottom.getY();
                    if (bottomY > goal.getY() + 2) {
                        // This column doesn't descend anywhere near the commander's level.
                        continue;
                    }
                    int attachY = Math.max(bottomY, Math.min(topY, origin.getY()));
                    BlockPos attachPos = new BlockPos(bottom.getX(), attachY, bottom.getZ());
                    for (Direction dir : Direction.Type.HORIZONTAL) {
                        BlockPos standPos = attachPos.offset(dir);
                        if (!isStandable(world, standPos)) {
                            continue;
                        }
                        double botDx = standPos.getX() - origin.getX();
                        double botDz = standPos.getZ() - origin.getZ();
                        double botHorizontalDistSq = (botDx * botDx) + (botDz * botDz);
                        if (!FollowVerticalAssistPolicyUtil.isReachableEntryStand(
                                origin.getY(),
                                standPos.getY(),
                                botHorizontalDistSq,
                                1,
                                64.0D)) {
                            continue;
                        }
                        double goalDx = standPos.getX() - goal.getX();
                        double goalDz = standPos.getZ() - goal.getZ();
                        double goalHorizontalDistSq = (goalDx * goalDx) + (goalDz * goalDz);
                        int absGoalBottomY = Math.abs(goal.getY() - bottomY);
                        double attachPenalty = Math.abs(origin.getY() - attachY) * 40.0D;
                        double score = FollowVerticalAssistPolicyUtil.scoreCandidate(
                                true,
                                absGoalBottomY,
                                botHorizontalDistSq,
                                goalHorizontalDistSq) + attachPenalty;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new ClimbAssistCandidate(attachPos.toImmutable(), standPos.toImmutable(), topY, score);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos climbableColumnBottom(ServerWorld world, BlockPos start, int maxDrop) {
        if (world == null || start == null) {
            return null;
        }
        if (!isClimbable(world, start)) {
            return null;
        }
        BlockPos bottom = start;
        for (int i = 1; i <= Math.max(1, maxDrop); i++) {
            BlockPos down = bottom.down();
            if (!isClimbable(world, down)) {
                break;
            }
            bottom = down;
        }
        return bottom;
    }

    private static int climbableColumnTopY(ServerWorld world, BlockPos start, int maxRise) {
        if (world == null || start == null) {
            return Integer.MIN_VALUE;
        }
        int topY = start.getY();
        for (int i = 1; i <= Math.max(1, maxRise); i++) {
            BlockPos up = start.up(i);
            if (!isClimbable(world, up)) {
                break;
            }
            topY = up.getY();
        }
        return topY;
    }

    private static BlockPos findNearbyClimbableAnchor(ServerWorld world,
                                                      BlockPos origin,
                                                      int horizRadius,
                                                      int minDy,
                                                      int maxDy) {
        if (world == null || origin == null || horizRadius < 0) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -horizRadius; dx <= horizRadius; dx++) {
            for (int dz = -horizRadius; dz <= horizRadius; dz++) {
                for (int dy = minDy; dy <= maxDy; dy++) {
                    BlockPos probe = origin.add(dx, dy, dz);
                    if (!isClimbable(world, probe)) {
                        continue;
                    }
                    double horizontal = (dx * dx) + (dz * dz);
                    double vertical = Math.abs(dy) * 0.5D;
                    double score = horizontal + vertical;
                    if (score < bestScore) {
                        bestScore = score;
                        best = probe.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean isClimbable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return world.getBlockState(pos).isIn(BlockTags.CLIMBABLE);
    }

    private static boolean shouldBreakVerticalDoorLoop(ServerPlayerEntity bot,
                                                       ServerPlayerEntity target,
                                                       double targetDistSq,
                                                       boolean targetAboveBot,
                                                       boolean directBlocked,
                                                       int effectiveStagnant,
                                                       FollowDoorPlan activeDoorPlan,
                                                       long nowMs) {
        if (bot == null || target == null || activeDoorPlan == null || !targetAboveBot) {
            return false;
        }
        if (!directBlocked && effectiveStagnant < 3) {
            return false;
        }
        if (targetDistSq < 4.0D) {
            return false;
        }

        UUID id = bot.getUuid();
        BlockPos doorBase = activeDoorPlan.doorBase().toImmutable();
        BlockPos lastDoor = FOLLOW_VERTICAL_DOOR_LOOP_LAST_BASE.get(id);
        int streak = (lastDoor != null && lastDoor.equals(doorBase))
                ? (FOLLOW_VERTICAL_DOOR_LOOP_STREAK.getOrDefault(id, 0) + 1)
                : 1;
        FOLLOW_VERTICAL_DOOR_LOOP_LAST_BASE.put(id, doorBase);
        FOLLOW_VERTICAL_DOOR_LOOP_STREAK.put(id, streak);
        if (streak < FOLLOW_VERTICAL_DOOR_LOOP_BREAK_STREAK) {
            return false;
        }

        long lastBreak = FOLLOW_VERTICAL_DOOR_LOOP_LAST_BREAK_MS.getOrDefault(id, 0L);
        if ((nowMs - lastBreak) < FOLLOW_VERTICAL_DOOR_LOOP_BREAK_COOLDOWN_MS) {
            return false;
        }
        FOLLOW_VERTICAL_DOOR_LOOP_LAST_BREAK_MS.put(id, nowMs);
        FOLLOW_VERTICAL_DOOR_LOOP_STREAK.put(id, 0);
        return true;
    }

    private static boolean isOpenDoorBetween(ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (bot == null || target == null) {
            return false;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        if (world == null) {
            return false;
        }
        Vec3d goal = positionOf(target);
        BlockPos blockingDoor = BlockInteractionService.findDoorAlongLine(bot, goal, 6.0D);
        if (blockingDoor == null) {
            return false;
        }
        BlockPos doorBase = normalizeDoorBase(world, blockingDoor);
        if (doorBase == null) {
            return false;
        }
        BlockState state = world.getBlockState(doorBase);
        return state.contains(Properties.OPEN) && Boolean.TRUE.equals(state.get(Properties.OPEN));
    }

    private static boolean isSealedSpace(ServerPlayerEntity entity) {
        if (entity == null) {
            return false;
        }
        UUID id = entity.getUuid();
        long now = System.currentTimeMillis();
        long last = FOLLOW_SEALED_STATE_MS.getOrDefault(id, -1L);
        if (last >= 0 && (now - last) < FOLLOW_SEALED_STATE_TTL_MS) {
            return FOLLOW_SEALED_STATE.getOrDefault(id, false);
        }
	        BotStuckService.EnvironmentSnapshot env = BotStuckService.analyzeEnvironment(entity);
	        boolean sealed = env != null && env.enclosed() && !env.hasEscapeRoute() && hasClosedDoorNearby(entity, 4);
	        FOLLOW_SEALED_STATE_MS.put(id, now);
	        FOLLOW_SEALED_STATE.put(id, sealed);
	        return sealed;
    }

    private static boolean hasClosedDoorNearby(ServerPlayerEntity entity, int radius) {
        if (entity == null || radius <= 0) {
            return false;
        }
        ServerWorld world = entity.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        if (world == null) {
            return false;
        }
        BlockPos origin = entity.getBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos probe = origin.add(dx, dy, dz);
                    BlockPos doorBase = normalizeDoorBase(world, probe);
                    if (doorBase == null) {
                        continue;
                    }
                    BlockState state = world.getBlockState(doorBase);
                    if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof FenceGateBlock)) {
                        continue;
                    }
                    if (state.contains(Properties.OPEN) && Boolean.TRUE.equals(state.get(Properties.OPEN))) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos normalizeDoorBase(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.block.DoorBlock) {
            if (state.contains(net.minecraft.block.DoorBlock.HALF)
                    && state.get(net.minecraft.block.DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) {
                return pos.down();
            }
            return pos;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return pos;
        }
        BlockState down = world.getBlockState(pos.down());
        if (down.getBlock() instanceof net.minecraft.block.DoorBlock || down.getBlock() instanceof FenceGateBlock) {
            return normalizeDoorBase(world, pos.down());
        }
        BlockState up = world.getBlockState(pos.up());
        if (up.getBlock() instanceof net.minecraft.block.DoorBlock || up.getBlock() instanceof FenceGateBlock) {
            return normalizeDoorBase(world, pos.up());
        }
        return null;
    }

    /**
     * Area scan fallback for door detection when the single raycast in
     * {@code findDoorAlongLine} misses a door on a diagonal path.
     * Checks blocks within {@code radius} horizontal of the line between
     * {@code from} and {@code to} (and at both Y levels for doors).
     */
    private static BlockPos findDoorNearPath(ServerWorld world, BlockPos from, BlockPos to, int radius) {
        if (world == null || from == null || to == null) return null;
        int minX = Math.min(from.getX(), to.getX()) - radius;
        int maxX = Math.max(from.getX(), to.getX()) + radius;
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY()) + 1; // +1 for upper door half
        int minZ = Math.min(from.getZ(), to.getZ()) - radius;
        int maxZ = Math.max(from.getZ(), to.getZ()) + radius;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.Mutable probe = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    probe.set(x, y, z);
                    var block = world.getBlockState(probe).getBlock();
                    if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
                        double dSq = from.getSquaredDistance(probe);
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            best = probe.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Heuristic: returns true if moving from {@code from} to {@code step} is generally toward {@code goal}.
     * Used to suppress "door magnet" behavior by skipping doors whose step-through tile is sideways/backward
     * relative to the current goal.
     */
    private static boolean isStepMeaningfullyTowardGoal(BlockPos from, BlockPos step, BlockPos goal) {
        if (from == null || step == null || goal == null) {
            return true;
        }
        double ax = (goal.getX() + 0.5D) - (from.getX() + 0.5D);
        double az = (goal.getZ() + 0.5D) - (from.getZ() + 0.5D);
        double bx = (step.getX() + 0.5D) - (from.getX() + 0.5D);
        double bz = (step.getZ() + 0.5D) - (from.getZ() + 0.5D);
        double aLenSq = ax * ax + az * az;
        double bLenSq = bx * bx + bz * bz;
        if (aLenSq < 1.0e-6 || bLenSq < 1.0e-6) {
            return true;
        }
        double dot = (ax * bx + az * bz) / (Math.sqrt(aLenSq) * Math.sqrt(bLenSq));
        if (dot < 0.05D) {
            return false;
        }
        double currentDistSq = from.getSquaredDistance(goal);
        double stepDistSq = step.getSquaredDistance(goal);
        // Allow minor "side-step" increases, but reject steps that clearly move away.
        return stepDistSq <= currentDistSq + 1.0D;
    }

    private static boolean shouldWolfTeleport(double distanceSq,
                                             double absDeltaY,
                                             boolean canSee,
                                             int stagnantTicks,
                                             MinecraftServer server) {
        if (server == null) {
            return false;
        }
        boolean farAndNotVisible = distanceSq >= FOLLOW_TELEPORT_DISTANCE_SQ && !canSee;
        boolean verticalSeparation = absDeltaY >= 10.0D && !canSee && distanceSq >= 49.0D;
        boolean stuckTooLong = stagnantTicks >= FOLLOW_TELEPORT_STUCK_TICKS && distanceSq >= 49.0D;
        // Far + stagnant: teleport even WITH line-of-sight (e.g. across ravines/gaps).
        boolean farAndStagnant = distanceSq >= 400.0D && stagnantTicks >= 20;
        return farAndNotVisible || verticalSeparation || stuckTooLong || farAndStagnant;
    }

    private static boolean tryWolfTeleport(ServerPlayerEntity bot, ServerPlayerEntity target, MinecraftServer server) {
        if (bot == null || target == null || server == null) {
            return false;
        }
        // Vanilla ServerPlayerEntity#teleport dismounts the rider, which would
        // orphan the bot's horse/boat at its current position. RideSync handles
        // catch-up for mounted bots; let it stay in charge.
        if (bot.hasVehicle()) {
            return false;
        }
        UUID id = bot.getUuid();
        Long lastTick = FOLLOW_LAST_TELEPORT_TICK.get(id);
        long nowTick = server.getTicks();
        if (lastTick != null && nowTick - lastTick < FOLLOW_TELEPORT_COOLDOWN_TICKS) {
            return false;
        }
        if (!(target.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos safe = findFollowTeleportPos(world, bot, target);
        if (safe == null) {
            return false;
        }
        // BlockPos here represents a *feet* position (2-block headroom checked); teleport using feet Y.
        Vec3d center = new Vec3d(safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D);
        // Bring the bot's mount along — without this, the horse is orphaned at the source.
        // If the mount can't be placed safely (e.g. target spot in dense forest), defer
        // the wolf-teleport entirely; next tick may pick a clearer follow spot.
        if (!net.wcfcarolina13.GameAI.services.TravelMountHandler.coTeleportSavedMount(bot, world, safe)) {
            return false;
        }
        bot.teleport(world,
                center.x, center.y, center.z,
                EnumSet.noneOf(PositionFlag.class),
                target.getYaw(),
                target.getPitch(),
                true);
        bot.setVelocity(Vec3d.ZERO);
        FOLLOW_LAST_TELEPORT_TICK.put(id, nowTick);
        // Self-teleport: prime the external-teleport detector so next tick's >16-block delta
        // isn't mistaken for a console teleport. Without this, the detector clears the follow
        // goal and the bot stops chasing the commander mid-pursuit (see 1.1.107 log audit).
        notifyTravelArrival(id, center, nowTick);
        LOGGER.info("Follow wolf-teleport: bot={} -> {} (near {})",
                bot.getName().getString(),
                safe.toShortString(),
                target.getBlockPos().toShortString());
        return true;
    }

    private static BlockPos findFollowTeleportPos(ServerWorld world, ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (world == null || bot == null || target == null) {
            return null;
        }
        BlockPos base = target.getBlockPos();
        Direction behind = target.getHorizontalFacing().getOpposite();
        Direction left = behind.rotateYCounterclockwise();
        Direction right = behind.rotateYClockwise();

        List<BlockPos> candidates = new ArrayList<>(12);
        candidates.add(base.offset(behind, 2));
        candidates.add(base.offset(behind, 1));
        candidates.add(base.offset(left, 2));
        candidates.add(base.offset(right, 2));
        candidates.add(base.offset(left, 1));
        candidates.add(base.offset(right, 1));
        candidates.add(base.up(1).offset(behind, 1));
        candidates.add(base.down(1));
        candidates.add(base.down(2));
        candidates.add(base.down(1).offset(behind, 1));
        candidates.add(base.down(1).offset(left, 1));
        candidates.add(base.down(1).offset(right, 1));

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : candidates) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!isStandable(world, pos)) {
                continue;
            }
            double dist = bot.getBlockPos().getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.isAir() || belowState.getCollisionShape(world, below).isEmpty()) {
            return false;
        }
        BlockState body = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        if (!body.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        if (!world.getFluidState(pos).isEmpty() || !world.getFluidState(pos.up()).isEmpty()) {
            return false;
        }
        return true;
    }

    private static Direction approximateToward(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return Direction.NORTH;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static boolean shouldPlanFollow(ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (bot == null || target == null) {
            return false;
        }
        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        BlockPos currentTargetPos = target.getBlockPos();

        BlockPos lastPos = LAST_FOLLOW_TARGET_POS.get(id);
        Long lastTime = LAST_FOLLOW_PLAN_MS.get(id);

        boolean movedEnough = lastPos == null || lastPos.getSquaredDistance(currentTargetPos) > 9; // >3 blocks
        boolean timeElapsed = lastTime == null || now - lastTime > 500; // 0.5s throttle

        if (movedEnough || timeElapsed) {
            LAST_FOLLOW_TARGET_POS.put(id, currentTargetPos.toImmutable());
            LAST_FOLLOW_PLAN_MS.put(id, now);
            return true;
        }
        return false;
    }

    private static void handleFollowPersonalSpace(ServerPlayerEntity bot,
                                                  ServerPlayerEntity target,
                                                  double distanceSq,
                                                  Vec3d targetPos) {
        FollowMovementService.handleFollowPersonalSpace(bot, target, distanceSq, targetPos, FOLLOW_BACKUP_DISTANCE, FOLLOW_BACKUP_TRIGGER_MS);
    }

    private static Entity findNearestItem(ServerPlayerEntity bot, List<Entity> entities, double radius) {
        return entities.stream()
                .filter(entity -> entity instanceof net.minecraft.entity.ItemEntity)
                .filter(entity -> entity.squaredDistanceTo(bot) <= radius * radius)
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(bot)))
                .orElse(null);
    }

    private static Entity findNearestDrop(ServerPlayerEntity bot, double radius) {
        if (bot == null) {
            return null;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        double verticalRange = Math.max(6.0D, radius);
        Box searchBox = bot.getBoundingBox().expand(radius, verticalRange, radius);
        Vec3d eyePos = bot.getEyePos();
        long nowTick = world.getServer().getTicks();
        Map<BlockPos, Long> blacklist =
                BackgroundSweepPolicy.pruneAndGetIdleSweepBlacklist(bot.getUuid(), nowTick);
        return world.getEntitiesByClass(
                        ItemEntity.class,
                        searchBox,
                        drop -> drop.isAlive() && !drop.isRemoved() && drop.squaredDistanceTo(bot) > 1.0D)
                .stream()
                // Skip drops the bot has previously failed to reach (per-bot, time-bounded).
                .filter(drop -> blacklist == null || !blacklist.containsKey(drop.getBlockPos()))
                // Back off from drops near a real player who just broke a block — avoids shoving
                // the commander while they're mining a tunnel.
                .filter(drop -> !net.wcfcarolina13.GameAI.services.CommanderActivityService
                        .isDropNearActiveMiner(world, drop))
                .filter(drop -> {
                    // Skip items behind solid blocks — raycast from bot eye to item position.
                    Vec3d dropPos = new Vec3d(drop.getX(), drop.getY(), drop.getZ());
                    net.minecraft.world.RaycastContext ctx = new net.minecraft.world.RaycastContext(
                            eyePos, dropPos,
                            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                            net.minecraft.world.RaycastContext.FluidHandling.NONE,
                            bot);
                    net.minecraft.util.hit.BlockHitResult hit = world.raycast(ctx);
                    if (hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                        // Ray hit a block before reaching the item — check if the block
                        // is close to the item (item sitting on/beside a surface) or far
                        // (item behind a wall/floor).
                        double hitDistSq = hit.getPos().squaredDistanceTo(dropPos);
                        return hitDistSq <= 4.0; // within 2 blocks of the item = probably reachable
                    }
                    return true; // clear line of sight
                })
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
    }

    private static void blacklistIdleSweepTarget(UUID botId, BlockPos target, long expiryTick) {
        if (botId == null || target == null) return;
        FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST
                .computeIfAbsent(botId, k -> new ConcurrentHashMap<>())
                .put(target.toImmutable(), expiryTick);
    }

    private static List<Entity> findHostilesAround(ServerPlayerEntity player, double radius) {
        return BotThreatService.findHostilesAround(player, radius);
    }

    private static Vec3d randomPointWithin(Vec3d center, double radius) {
        double angle = RANDOM.nextDouble() * Math.PI * 2;
        double distance = RANDOM.nextDouble() * radius;
        double x = center.x + Math.cos(angle) * distance;
        double z = center.z + Math.sin(angle) * distance;
        return new Vec3d(x, center.y, z);
    }

    private static void sendBotMessage(ServerPlayerEntity bot, String message) {
        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), message);
    }

    private static Vec3d positionOf(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    public static boolean rescueFromBurial(ServerPlayerEntity bot) {
        return BotRescueService.rescueFromBurial(bot);
    }

    /**
     * Proactively checks if bot is stuck in blocks and initiates time-based mining to escape.
     * Uses MiningTool.mineBlock() for physical, tool-based breaking.
     */
    public static boolean checkAndEscapeSuffocation(ServerPlayerEntity bot) {
        return BotRescueService.checkAndEscapeSuffocation(bot);
    }

    /**
     * DISABLED: Programmatic block breaking removed.
     * Bot must handle headspace clearance naturally through mining.
     */
    public static boolean ensureHeadspaceClearance(ServerPlayerEntity bot) {
        return BotRescueService.ensureHeadspaceClearance(bot);
    }

    /**
     * Checks if bot spawned inside blocks and proactively starts mining out.
     * Called on spawn to prevent immediate suffocation death.
     */
    private static void checkForSpawnInBlocks(ServerPlayerEntity bot) {
        BotRescueService.checkForSpawnInBlocks(bot);
    }


    public static void tickBurialRescue(MinecraftServer server) {
        BotRescueService.tickBurialRescue(server);
    }

    private static void lowerShieldTracking(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        if (isShieldRaised(bot)) {
            BotActions.lowerShield(bot);
            setShieldRaised(bot, false);
        } else {
            BotActions.lowerShield(bot);
        }
    }

    private void performLearningStep(
            RLAgent rlAgentHook,
            QTable qTable,
            State currentState,
            List<EntityDetails> nearbyEntitiesList,
            List<String> nearbyBlocks,
            double distanceToHostileEntity,
            String time,
            String dimension) throws IOException {

        if (externalOverrideActive) {
            LOGGER.debug("Aborting learning step because external override is active.");
            return;
        }

        LOGGER.info("Starting performLearningStep. Current state hash: {}, hostiles in state: {}",
                currentState.hashCode(),
                currentState.getNearbyEntities() != null ? currentState.getNearbyEntities().stream().filter(EntityDetails::isHostile).count() : 0);

        double riskAppetite = rlAgentHook.calculateRiskAppetite(currentState);
        List<StateActions.Action> potentialActionList = rlAgentHook.suggestPotentialActions(currentState);
        Map<StateActions.Action, Double> riskMap = rlAgentHook.calculateRisk(currentState, potentialActionList);

        Map<StateActions.Action, Double> chosenActionMap = rlAgentHook.chooseAction(currentState, riskAppetite, riskMap);
        Map.Entry<StateActions.Action, Double> entry = chosenActionMap.entrySet().iterator().next();

        StateActions.Action chosenAction = entry.getKey();
        double risk = entry.getValue();

        LOGGER.info("Training step chosen action: {}", chosenAction);

        executeAction(chosenAction);

        BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);
        List<String> updatedBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

        List<EntityDetails> updatedEntities = AutoFaceEntity.detectNearbyEntities(bot, 10).stream()
                .map(entity -> EntityDetails.from(bot, entity))
                .toList();

        double newDistanceToHostile = updatedEntities.stream()
                .filter(EntityDetails::isHostile)
                .mapToDouble(entity -> Math.hypot(entity.getX() - bot.getX(), entity.getZ() - bot.getZ()))
                .min()
                .orElse(distanceToHostileEntity);

        double dangerDistance = DangerZoneDetector.detectDangerZone(bot, 10, 5, 5);
        int botHungerLevel = getPlayerHunger.getBotHungerLevel(bot);
        int botOxygenLevel = getPlayerOxygen.getBotOxygenLevel(bot);
        int botFrostLevel = getFrostLevel.calculateFrostLevel(bot);
        Map<String, ItemStack> armorItems = getArmorStack.getArmorItems(bot);
        ItemStack offhandItem = getOffHandStack.getOffhandItem(bot);
        List<ItemStack> hotBarItems = hotBarUtils.getHotbarItems(bot);
        SelectedItemDetails selectedItem = new SelectedItemDetails(
                hotBarUtils.getSelectedHotbarItemStack(bot).getItem().getName().getString(),
                hotBarUtils.getSelectedHotbarItemStack(bot).getComponents().contains(DataComponentTypes.FOOD),
                isBlockItem.checkBlockItem(hotBarUtils.getSelectedHotbarItemStack(bot))
        );

	        BotStuckService.EnvironmentSnapshot nextEnv = BotStuckService.analyzeEnvironment(bot);
	        boolean confinedNoEscape = nextEnv.enclosed() && !nextEnv.hasEscapeRoute() && !nextEnv.hasHeadroom();
	        if (!confinedNoEscape) {
	            BotStuckService.setLastSafePosition(bot.getUuid(), new Vec3d(bot.getX(), bot.getY(), bot.getZ()));
	        }

        String updatedTime = GetTime.getTimeOfWorld(bot) >= 12000 ? "night" : "day";
        String updatedDimension = bot.getCommandSource().getWorld().getRegistryKey().getValue().toString();

        Map<StateActions.Action, Double> basePodMap = currentState.getPodMap() != null
                ? currentState.getPodMap()
                : new HashMap<>();

        State nextState = new State(
                (int) bot.getX(),
                (int) bot.getY(),
                (int) bot.getZ(),
                updatedEntities,
                updatedBlocks,
                newDistanceToHostile,
                (int) bot.getHealth(),
                dangerDistance,
                hotBarItems,
                selectedItem,
                updatedTime,
                updatedDimension,
                botHungerLevel,
                botOxygenLevel,
                botFrostLevel,
                offhandItem,
                armorItems,
                nextEnv.enclosed(),
                nextEnv.solidNeighborCount(),
                nextEnv.hasHeadroom(),
                nextEnv.hasEscapeRoute(),
                chosenAction,
                riskMap,
                riskAppetite,
                basePodMap
        );

        rlAgentHook.decayEpsilon();
        Map<StateActions.Action, Double> actionPodMap = rlAgentHook.assessRiskOutcome(currentState, nextState, chosenAction);
        nextState.setPodMap(actionPodMap);

        ServerPlayerEntity commander = findEscortPlayer(bot);
        Vec3d commanderPos = commander != null ? new Vec3d(commander.getX(), commander.getY(), commander.getZ()) : null;
        float commanderHealth = commander != null ? commander.getHealth() : -1f;
        Vec3d guardCenterVec = getGuardCenterVec();
        double guardRadiusValue = guardCenterVec != null ? getGuardRadiusValue() : 0.0D;

        ActionHoldTracker.ActionHoldSnapshot holdSnapshot = ActionHoldTracker.snapshot();

        double reward = rlAgentHook.calculateReward(
                (int) bot.getX(),
                (int) bot.getY(),
                (int) bot.getZ(),
                updatedEntities,
                updatedBlocks,
                newDistanceToHostile,
                (int) bot.getHealth(),
                dangerDistance,
                hotBarItems,
                selectedItem,
                updatedTime,
                updatedDimension,
                botHungerLevel,
                botOxygenLevel,
                offhandItem,
                armorItems,
                nextEnv.enclosed(),
                nextEnv.hasHeadroom(),
                nextEnv.hasEscapeRoute(),
                nextEnv.solidNeighborCount(),
                chosenAction,
                risk,
                actionPodMap.getOrDefault(chosenAction, 0.0),
                getCurrentMode(),
                getCombatStyle(),
                commanderPos,
                commanderHealth,
                guardCenterVec,
                guardRadiusValue,
                holdSnapshot
        );

        LOGGER.info("Reward for action {}: {}", chosenAction, reward);

        double qValue = rlAgentHook.calculateQValue(currentState, chosenAction, reward, nextState, qTable);
        qTable.addEntry(currentState, chosenAction, qValue, nextState);

        if (BotRLPersistenceThrottleService.shouldPersistNow(bot)) {
            QTableStorage.saveQTable(qTable, null);
            QTableStorage.saveEpsilon(rlAgentHook.getEpsilon(), qTableDir + "/epsilon.bin");
            LOGGER.info("Persisted Q-table and epsilon (throttled) after action {}", chosenAction);
        } else {
            LOGGER.debug("Skipping Q-table persist due to throttle (action={})", chosenAction);
        }

        BotEventHandler.currentState = nextState;
    }


    public static State createInitialState(ServerPlayerEntity bot) {
        List<ItemStack> hotBarItems = hotBarUtils.getHotbarItems(bot);
        ItemStack selectedItemStack = hotBarUtils.getSelectedHotbarItemStack(bot);

        BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);

        List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

	        BotStuckService.EnvironmentSnapshot environmentSnapshot = BotStuckService.analyzeEnvironment(bot);
	        boolean confinedNoEscape = environmentSnapshot.enclosed() && !environmentSnapshot.hasEscapeRoute() && !environmentSnapshot.hasHeadroom();
	        if (!confinedNoEscape) {
	            BotStuckService.setLastSafePosition(bot.getUuid(), new Vec3d(bot.getX(), bot.getY(), bot.getZ()));
	        }

        SelectedItemDetails selectedItem = new SelectedItemDetails(
                selectedItemStack.getItem().getName().getString(),
                selectedItemStack.getComponents().contains(DataComponentTypes.FOOD),
                isBlockItem.checkBlockItem(selectedItemStack)
        );

        List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 10);
        List<EntityDetails> nearbyEntitiesList = nearbyEntities.stream()
                .map(entity -> EntityDetails.from(bot, entity))
                .toList();

        double dangerDistance = DangerZoneDetector.detectDangerZone(bot, 10, 5, 5);
        int botHungerLevel = getPlayerHunger.getBotHungerLevel(bot);
        int botOxygenLevel = getPlayerOxygen.getBotOxygenLevel(bot);
        int botFrostLevel = getFrostLevel.calculateFrostLevel(bot);
        Map<String, ItemStack> armorItems = getArmorStack.getArmorItems(bot);
        ItemStack offhandItem = getOffHandStack.getOffhandItem(bot);
        String time = GetTime.getTimeOfWorld(bot) >= 12000 ? "night" : "day";
        String dimension = bot.getCommandSource().getWorld().getRegistryKey().getValue().toString();
        Map<StateActions.Action, Double> riskMap = new HashMap<>();

        Map<StateActions.Action, Double> podMap = new HashMap<>(); // blank pod map for now.

        return new State(
                (int) bot.getX(),
                (int) bot.getY(),
                (int) bot.getZ(),
                nearbyEntitiesList,
                nearbyBlocks,
                0.0, // Distance to hostile can be updated dynamically elsewhere
                (int) bot.getHealth(),
                dangerDistance,
                hotBarItems,
                selectedItem,
                time,
                dimension,
                botHungerLevel,
                botOxygenLevel,
                botFrostLevel,
                offhandItem,
                armorItems,
                environmentSnapshot.enclosed(),
                environmentSnapshot.solidNeighborCount(),
                environmentSnapshot.hasHeadroom(),
                environmentSnapshot.hasEscapeRoute(),
                StateActions.Action.STAY,
                riskMap,
                DEFAULT_RISK_APPETITE,
                podMap
        );
    }


    private static void performAction(String action) {
        BotRLActionService.performAction(bot, action, BotEventHandler::debugRL);
    }

    /**
     * Resets all static fields to prevent state from leaking between worlds/servers.
     * Must be called on server stop or when the bot completely disconnects.
     */
    public static void resetAll() {
	        synchronized (monitorLock) {
	            server = null;
	            bot = null;
	            BotLifecycleService.clear();
	            BotRegistry.clear();
		            BotCommandStateService.clearAll();
		            LAST_RL_SAMPLE_TICK.clear();
		            BotRescueService.reset();
		            BotStuckService.resetAll();
		            BotRLPersistenceThrottleService.resetAll();
	            FollowStateService.reset();
	            FollowDebugService.reset();
	            DropSweepService.reset();
                FOLLOW_LAST_VERTICAL_ASSIST_MS.clear();
                FOLLOW_VERTICAL_DOOR_LOOP_LAST_BASE.clear();
                FOLLOW_VERTICAL_DOOR_LOOP_STREAK.clear();
                FOLLOW_VERTICAL_DOOR_LOOP_LAST_BREAK_MS.clear();
                FOLLOW_VERTICAL_LOCK_LAST_POS.clear();
                FOLLOW_VERTICAL_LOCK_LAST_REPLAN_MS.clear();
                FOLLOW_VERTICAL_LOCK_FAIL_COOLDOWN_UNTIL_MS.clear();
                FOLLOW_COMMANDER_LADDER_HINT.clear();
                COMBAT_TARGET.clear();
                TELEPORT_DETECT_LAST_POS.clear();
                TELEPORT_GRACE_UNTIL_TICK.clear();
                STOP_COMMAND_GRACE_UNTIL_TICK.clear();

            isExecuting = false;
            externalOverrideActive = false;
            botDied = false;
            hasRespawned = false;
            botSpawnCount = 0;

		            currentState = null;
		            lastRespawnHandledTick = -1;
		            
		            LOGGER.info("BotEventHandler static state reset successfully.");
		        }
    }
}
