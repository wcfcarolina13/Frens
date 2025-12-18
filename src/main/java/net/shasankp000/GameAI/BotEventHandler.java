package net.shasankp000.GameAI;

import net.shasankp000.EntityUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
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
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.DangerZoneDetector.DangerZoneDetector;
import net.shasankp000.Database.QTable;
import net.shasankp000.Database.QTableStorage;
import net.shasankp000.GameAI.services.BotPersistenceService;
import net.shasankp000.GameAI.services.BotLifecycleService;
import net.shasankp000.GameAI.services.BotRegistry;
import net.shasankp000.GameAI.services.BotCommandStateService;
import net.shasankp000.GameAI.services.DropSweepService;
import net.shasankp000.GameAI.services.GuardPatrolService;
import net.shasankp000.GameAI.services.HealingService;
import net.shasankp000.GameAI.services.BotRescueService;
import net.shasankp000.GameAI.services.BotThreatService;
import net.shasankp000.Database.StateActionPair;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.Entity.LookController;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.Entity.FaceClosestEntity;
import net.shasankp000.LauncherDetection.LauncherEnvironment;

import java.util.ArrayList;
import net.shasankp000.PlayerUtils.*;
import net.shasankp000.WorldUitls.GetTime;
import net.shasankp000.Entity.EntityDetails;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.FollowPathService;
import net.shasankp000.Entity.createFakePlayer;
import net.shasankp000.WorldUitls.isBlockItem;
import net.shasankp000.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.shasankp000.GameAI.State.isStateConsistent;


public class BotEventHandler {
    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
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
    private static Vec3d lastKnownPosition = null;
    private static int stationaryTicks = 0;
    private static final int STUCK_TICK_THRESHOLD = 12;
    private static final boolean SPARTAN_MODE_ENABLED = false;
    private static boolean spartanModeActive = false;
    private static int failedBlockBreakAttempts = 0;
    private static final int MAX_FAILED_BLOCK_ATTEMPTS = 4;
    private static Vec3d lastSafePosition = null;
    private static final Random RANDOM = new Random();
    // Stage-2 refactor: drop-sweep state moved to DropSweepService.
    // Stage-2 refactor: burial/suffocation rescue moved to BotRescueService.
    private static final Map<UUID, Long> LAST_FOLLOW_PLAN_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> LAST_FOLLOW_TARGET_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_RL_PERSIST_MS = new ConcurrentHashMap<>();
    private static final long RL_PERSIST_MIN_INTERVAL_MS = 15_000L;
    private static final Map<UUID, Long> FOLLOW_TOO_CLOSE_SINCE = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> FOLLOW_LAST_DISTANCE_SQ = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FOLLOW_STAGNANT_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_TELEPORT_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, FollowDoorPlan> FOLLOW_DOOR_PLAN = new ConcurrentHashMap<>();
    private static final Map<UUID, ArrayDeque<BlockPos>> FOLLOW_WAYPOINTS = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<?>> FOLLOW_PATH_INFLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_PATH_PLAN_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_LAST_PATH_TARGET = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_LAST_DOOR_BASE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_DOOR_CROSS_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_PATH_LOG_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_PATH_FAIL_LOG_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_PATH_SKIP_LOG_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_DECISION_LOG_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_STATUS_LOG_MS = new ConcurrentHashMap<>();
    private static final long FOLLOW_STATUS_LOG_INTERVAL_MS = 1_800L;
    private static final Map<UUID, Boolean> FOLLOW_SEALED_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_SEALED_STATE_MS = new ConcurrentHashMap<>();
    private static final long FOLLOW_SEALED_STATE_TTL_MS = 1_000L;
    private static final Map<UUID, Long> FOLLOW_REPLAN_AFTER_DOOR_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_DOOR_LAST_BLOCK = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FOLLOW_DOOR_STUCK_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FOLLOW_DIRECT_BLOCKED_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, FollowDoorRecovery> FOLLOW_DOOR_RECOVERY = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_AVOID_DOOR_BASE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_AVOID_DOOR_UNTIL_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOLLOW_LAST_BLOCKED_PROBE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_LAST_BLOCKED_PROBE_GOAL = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_LAST_BLOCKED_PROBE_BOTPOS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> FOLLOW_LAST_BLOCKED_PROBE_RESULT = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FOLLOW_LAST_BLOCK_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FOLLOW_POS_STAGNANT_TICKS = new ConcurrentHashMap<>();
    private static final double FOLLOW_PERSONAL_SPACE = 1.6D; // prefer at least ~1 block gap
    private static final double FOLLOW_BACKUP_DISTANCE = 1.05D; // trigger backup after linger
    private static final long FOLLOW_BACKUP_TRIGGER_MS = 3_000L;
    private static final double FOLLOW_SPRINT_DISTANCE_SQ = 4.0D; // >2 blocks -> sprint
    private static final double FOLLOW_TELEPORT_DISTANCE_SQ = 225.0D; // ~15 blocks
    private static final int FOLLOW_TELEPORT_STUCK_TICKS = 60; // ~3 seconds @20tps
    private static final int FOLLOW_TELEPORT_COOLDOWN_TICKS = 40; // 2 seconds @20tps
    private static final long FOLLOW_POST_DOOR_AVOID_MS = 6_000L;

    private record FollowDoorPlan(BlockPos doorBase, BlockPos approachPos, BlockPos stepThroughPos, long expiresAtMs, boolean stepping) {
    }

    private record FollowDoorRecovery(BlockPos goal, int remainingTicks) {
    }

    private static BlockPos currentAvoidDoor(UUID botId) {
        if (botId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Long until = FOLLOW_AVOID_DOOR_UNTIL_MS.get(botId);
        if (until == null || now >= until) {
            FOLLOW_AVOID_DOOR_UNTIL_MS.remove(botId);
            FOLLOW_AVOID_DOOR_BASE.remove(botId);
            return null;
        }
        return FOLLOW_AVOID_DOOR_BASE.get(botId);
    }

    private static void avoidDoorFor(UUID botId, BlockPos doorBase, long durationMs, String reason) {
        if (botId == null || doorBase == null) {
            return;
        }
        long until = System.currentTimeMillis() + Math.max(500L, durationMs);
        FOLLOW_AVOID_DOOR_BASE.put(botId, doorBase.toImmutable());
        FOLLOW_AVOID_DOOR_UNTIL_MS.put(botId, until);
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
        long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(botId, -1L);
        BlockPos lastDoor = FOLLOW_LAST_DOOR_BASE.get(botId);
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
        RETURNING_BASE
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

    private static Vec3d getBaseTarget(ServerPlayerEntity bot) {
        BotCommandStateService.State state = stateFor(bot);
        return state != null ? state.baseTarget : null;
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
        net.shasankp000.GameAI.services.BotControlApplier.applyToBot(candidate);
        
        // Proactively check if bot spawned inside blocks and needs to mine out
        if (srv != null) {
            srv.execute(() -> {
                // Delay check by a few ticks to let spawn complete
                srv.send(new net.minecraft.server.ServerTask(srv.getTicks() + 5, () -> {
                    if (!candidate.isRemoved()) {
                        checkForSpawnInBlocks(candidate);
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
        BotRegistry.unregister(uuid);
        BotPersistenceService.removeBot(bot);
        clearState(bot);
        LAST_RL_SAMPLE_TICK.remove(uuid);
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
        if (!net.shasankp000.Commands.modCommandRegistry.enableReinforcementLearning) {
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
                    hostileEntities.size(), net.shasankp000.Commands.modCommandRegistry.isTrainingMode, isExecuting);

            BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);

            List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

            boolean hasSculkNearby = nearbyBlocks.stream()
                    .anyMatch(block -> block.contains("Sculk Sensor") || block.contains("Sculk Shrieker"));

            EnvironmentSnapshot environmentSnapshot = analyzeEnvironment(bot);
            boolean threatDetected = shouldEnterCombat(!hostileEntities.isEmpty(), dangerDistance, hasSculkNearby);
            handleSpartanMode(bot, environmentSnapshot, threatDetected);
            updateStuckTracker(bot, environmentSnapshot);

            debugRL("Nearby blocks: " + nearbyBlocks);

            int timeofDay = GetTime.getTimeOfWorld(bot);
            String time = (timeofDay >= 12000 && timeofDay < 24000) ? "night" : "day";

            World world = bot.getCommandSource().withSilent().withMaxLevel(4).getWorld();
            RegistryKey<World> dimType = world.getRegistryKey();
            String dimension = dimType.getValue().toString();

            if (!hostileEntities.isEmpty()) {
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

    public static void tickHunger(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity player : getRegisteredBots(server)) {
            HealingService.autoEat(player);
        }
    }


    public static State getCurrentState() {

        return BotEventHandler.currentState;

    }

    public void detectAndReactPlayMode(RLAgent rlAgentHook, QTable qTable) {
        if (!net.shasankp000.Commands.modCommandRegistry.enableReinforcementLearning) {
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
            ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);


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
            case ATTACK -> performAction("attack");
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

    private record EnvironmentSnapshot(boolean enclosed, int solidNeighborCount, boolean hasHeadroom, boolean hasEscapeRoute) {}

    private static EnvironmentSnapshot analyzeEnvironment(ServerPlayerEntity bot) {
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos pos = bot.getBlockPos();

        int solidNeighbors = 0;
        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN || direction == Direction.UP || direction.getAxis().isHorizontal()) {
                BlockPos checkPos = pos.offset(direction);
                if (isSolid(world, checkPos)) {
                    solidNeighbors++;
                }
            }
        }

        boolean headroom = world.getBlockState(pos.up()).isAir();
        boolean escapeRoute = false;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos forward = pos.offset(direction);
            if (world.getBlockState(forward).isAir() && world.getBlockState(forward.up()).isAir()) {
                escapeRoute = true;
                break;
            }
        }

        boolean enclosed = solidNeighbors >= 5;
        return new EnvironmentSnapshot(enclosed, solidNeighbors, headroom, escapeRoute);
    }

    private static boolean isSolid(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
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

    private static boolean isSpartanCandidate(EnvironmentSnapshot snapshot) {
        return snapshot.enclosed() && !snapshot.hasEscapeRoute() && !snapshot.hasHeadroom();
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

    private static void handleSpartanMode(ServerPlayerEntity bot, EnvironmentSnapshot snapshot, boolean threatDetected) {
        if (!SPARTAN_MODE_ENABLED) {
            spartanModeActive = false;
            failedBlockBreakAttempts = 0;
            return;
        }
        boolean environmentTrigger = isSpartanCandidate(snapshot) && threatDetected;
        boolean failureTrigger = threatDetected && failedBlockBreakAttempts >= MAX_FAILED_BLOCK_ATTEMPTS;
        boolean candidate = environmentTrigger || failureTrigger;
        if (candidate && !spartanModeActive) {
            spartanModeActive = true;
            BotActions.sneak(bot, false);
            BotActions.selectBestWeapon(bot);
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
                    bot.getName().getString() + " is going Spartan mode! No escape route detected.");
        } else if (!candidate && spartanModeActive) {
            spartanModeActive = false;
            failedBlockBreakAttempts = 0;
        }
    }

    private static void registerBlockBreakResult(ServerPlayerEntity bot, boolean success) {
        if (success) {
            failedBlockBreakAttempts = 0;
            return;
        }

        failedBlockBreakAttempts = Math.min(MAX_FAILED_BLOCK_ATTEMPTS, failedBlockBreakAttempts + 1);
        if (failedBlockBreakAttempts >= MAX_FAILED_BLOCK_ATTEMPTS) {
            EnvironmentSnapshot snapshot = analyzeEnvironment(bot);
            boolean threatDetected = assessImmediateThreat(bot);
            handleSpartanMode(bot, snapshot, threatDetected);
        }
    }

    private static void updateStuckTracker(ServerPlayerEntity bot, EnvironmentSnapshot environmentSnapshot) {
        Vec3d currentPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        if (lastKnownPosition == null) {
            lastKnownPosition = currentPos;
            stationaryTicks = 0;
            return;
        }

        // Reduced distance threshold to 0.01 (very strict) to catch subtle movements
        double distanceSq = currentPos.squaredDistanceTo(lastKnownPosition);
        if (distanceSq < 0.01) {
            stationaryTicks++;
        } else {
            stationaryTicks = 0;
            lastKnownPosition = currentPos;
        }

        if (isSpartanCandidate(environmentSnapshot)) {
            lastKnownPosition = currentPos;
            stationaryTicks = 0;
            boolean threatDetected = assessImmediateThreat(bot);
            if (!threatDetected) {
                // Programmatic block breaking disabled - bot must mine out manually
                LOGGER.info("Bot enclosed without active threats; bot must mine out manually.");
            }
            return;
        }

        // Check if we are stuck on farmland (partial block)
        // If we've been stationary for a while, try to jump
        // FIXED: Use bot.getCommandSource().getWorld() instead of bot.getWorld()
        BlockState feetState = bot.getCommandSource().getWorld().getBlockState(bot.getBlockPos());
        if (feetState.isOf(Blocks.FARMLAND) && stationaryTicks > 5) {
             BotActions.jump(bot);
        }

        if (stationaryTicks >= STUCK_TICK_THRESHOLD || (environmentSnapshot.enclosed() && !environmentSnapshot.hasEscapeRoute())) {
            LOGGER.info("Escape routine triggered (stationaryTicks={}, enclosed={}, hasEscapeRoute={})",
                    stationaryTicks, environmentSnapshot.enclosed(), environmentSnapshot.hasEscapeRoute());
            BotActions.escapeStairs(bot);
            stationaryTicks = 0;
            lastKnownPosition = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        }
    }



    public static void onBotRespawn(ServerPlayerEntity bot) {
        registerBot(bot);
        spartanModeActive = false;
        stationaryTicks = 0;
        lastKnownPosition = null;
        failedBlockBreakAttempts = 0;

        ServerPlayerEntity escortPlayer = findEscortPlayer(bot);
        Vec3d target = escortPlayer != null
                ? new Vec3d(escortPlayer.getX(), escortPlayer.getY(), escortPlayer.getZ())
                : lastSafePosition;
        MinecraftServer srv = bot.getCommandSource().getServer();
        ServerWorld botWorld = bot.getCommandSource().getWorld();
        ServerWorld destinationWorld = botWorld;

        if (escortPlayer != null) {
            destinationWorld = escortPlayer.getCommandSource().getWorld();
            lastSafePosition = new Vec3d(escortPlayer.getX(), escortPlayer.getY(), escortPlayer.getZ());
        }

        if (target == null) {
            BlockPos anchor = bot.getBlockPos().up(2);
            target = new Vec3d(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        }

        if (destinationWorld != null && destinationWorld != botWorld) {
            bot.teleport(destinationWorld, target.x, target.y, target.z,
                    EnumSet.noneOf(PositionFlag.class),
                    escortPlayer != null ? escortPlayer.getYaw() : bot.getYaw(),
                    escortPlayer != null ? escortPlayer.getPitch() : bot.getPitch(),
                    true);
        } else {
            bot.refreshPositionAndAngles(target.x, target.y, target.z,
                    escortPlayer != null ? escortPlayer.getYaw() : bot.getYaw(),
                    escortPlayer != null ? escortPlayer.getPitch() : bot.getPitch());
        }

        bot.setVelocity(Vec3d.ZERO);
        bot.setInvulnerable(true);
        if (srv != null) {
            srv.send(new ServerTask(srv.getTicks() + 40, () -> bot.setInvulnerable(false)));
            lastRespawnHandledTick = srv.getTicks();
        }

        lastSafePosition = target;
        TaskService.forceAbort(bot.getUuid(), "§cTask aborted due to bot respawn.");
        setExternalOverrideActive(false);
        setMode(bot, Mode.IDLE);
        setAssistAllies(bot, true);        if (destinationWorld != null) {
            rememberSpawn(destinationWorld, target, bot.getYaw(), bot.getPitch());
        }

        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
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
            if (bot.isDead()) {
                bot.setHealth(bot.getMaxHealth());
            }
            onBotRespawn(bot);
        }));
    }

    public static boolean updateBehavior(ServerPlayerEntity bot, MinecraftServer server, List<Entity> nearbyEntities, List<Entity> hostileEntities) {
        if (!isRegisteredBot(bot)) {
            return false;
        }

        BotCommandStateService.State state = stateFor(bot);
        Mode mode = state != null ? state.mode : Mode.IDLE;
        List<Entity> augmentedHostiles = BotThreatService.augmentHostiles(
                bot,
                hostileEntities,
                isAssistAllies(bot),
                server,
                BotRegistry.ids());

        switch (mode) {
            case FOLLOW -> {
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
                BotActions.stop(bot);
                return true;
            }
            case RETURNING_BASE -> {
                if (!augmentedHostiles.isEmpty() && engageHostiles(bot, server, augmentedHostiles)) {
                    return true;
                }
                return handleReturnToBase(bot, state);
            }
            default -> {
                if (!augmentedHostiles.isEmpty()) {
                    return engageHostiles(bot, server, augmentedHostiles);
                }
                return false;
            }
        }
    }

    public static String setFollowMode(ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (isExternalOverrideActive()) {
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
        }
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);
        clearBase(bot);
        // Kick off a follow plan immediately so "around the corner / door enclosures" work even when
        // the commander isn't standing in front of the door. This is async + bounded, so it won't block ticks.
        UUID id = bot.getUuid();
        FOLLOW_DOOR_PLAN.remove(id);
        FOLLOW_WAYPOINTS.remove(id);
        FOLLOW_LAST_DISTANCE_SQ.remove(id);
        FOLLOW_STAGNANT_TICKS.remove(id);
        FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.remove(id);
        FOLLOW_PATH_INFLIGHT.remove(id);
        FOLLOW_LAST_PATH_PLAN_MS.remove(id);
        FOLLOW_LAST_PATH_TARGET.remove(id);
        FOLLOW_LAST_PATH_LOG_MS.remove(id);
        FOLLOW_LAST_PATH_FAIL_LOG_MS.remove(id);
            FOLLOW_LAST_PATH_SKIP_LOG_MS.remove(id);
            FOLLOW_LAST_DECISION_LOG_MS.remove(id);
            FOLLOW_AVOID_DOOR_BASE.remove(id);
            FOLLOW_AVOID_DOOR_UNTIL_MS.remove(id);
            FOLLOW_REPLAN_AFTER_DOOR_MS.remove(id);
            FOLLOW_LAST_BLOCK_POS.remove(id);
            FOLLOW_POS_STAGNANT_TICKS.remove(id);
            FOLLOW_DIRECT_BLOCKED_TICKS.remove(id);
            FOLLOW_DOOR_RECOVERY.remove(id);
        requestFollowPathPlan(bot, target, true, "follow-start");
        sendBotMessage(bot, "Following " + target.getName().getString() + ".");
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
        }
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);
        clearBase(bot);
        UUID id = bot.getUuid();
        FOLLOW_DOOR_PLAN.remove(id);
        FOLLOW_WAYPOINTS.remove(id);
        FOLLOW_LAST_DISTANCE_SQ.remove(id);
        FOLLOW_STAGNANT_TICKS.remove(id);
        FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.remove(id);
        FOLLOW_PATH_INFLIGHT.remove(id);
        FOLLOW_LAST_PATH_PLAN_MS.remove(id);
        FOLLOW_LAST_PATH_TARGET.remove(id);
        FOLLOW_LAST_PATH_LOG_MS.remove(id);
        FOLLOW_LAST_PATH_FAIL_LOG_MS.remove(id);
            FOLLOW_LAST_PATH_SKIP_LOG_MS.remove(id);
            FOLLOW_LAST_DECISION_LOG_MS.remove(id);
            FOLLOW_AVOID_DOOR_BASE.remove(id);
            FOLLOW_AVOID_DOOR_UNTIL_MS.remove(id);
            FOLLOW_REPLAN_AFTER_DOOR_MS.remove(id);
            FOLLOW_LAST_BLOCK_POS.remove(id);
            FOLLOW_POS_STAGNANT_TICKS.remove(id);
            FOLLOW_DIRECT_BLOCKED_TICKS.remove(id);
            FOLLOW_DOOR_RECOVERY.remove(id);
        requestFollowPathPlan(bot, target, true, "follow-start-walk");
        sendBotMessage(bot, "Walking to you.");
        return "Walking to " + target.getName().getString() + ".";
    }

    public static String stopFollowing(ServerPlayerEntity bot) {
        if (bot != null) {
            registerBot(bot);
        }
        boolean wasFollowing = getMode(bot) == Mode.FOLLOW && getFollowTargetFor(bot) != null;
        setFollowTarget(bot, null);
        if (bot != null) {
            UUID id = bot.getUuid();
            FOLLOW_LAST_DISTANCE_SQ.remove(id);
            FOLLOW_STAGNANT_TICKS.remove(id);
            FOLLOW_LAST_TELEPORT_TICK.remove(id);
            FOLLOW_DOOR_PLAN.remove(id);
            FOLLOW_WAYPOINTS.remove(id);
            FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.remove(id);
            FOLLOW_PATH_INFLIGHT.remove(id);
            FOLLOW_LAST_PATH_PLAN_MS.remove(id);
            FOLLOW_LAST_PATH_TARGET.remove(id);
            FOLLOW_LAST_DOOR_BASE.remove(id);
            FOLLOW_LAST_DOOR_CROSS_MS.remove(id);
            FOLLOW_LAST_PATH_LOG_MS.remove(id);
            FOLLOW_LAST_PATH_FAIL_LOG_MS.remove(id);
            FOLLOW_LAST_PATH_SKIP_LOG_MS.remove(id);
            FOLLOW_LAST_DECISION_LOG_MS.remove(id);
            FOLLOW_REPLAN_AFTER_DOOR_MS.remove(id);
            FOLLOW_DOOR_LAST_BLOCK.remove(id);
            FOLLOW_DOOR_STUCK_TICKS.remove(id);
            FOLLOW_LAST_BLOCK_POS.remove(id);
            FOLLOW_POS_STAGNANT_TICKS.remove(id);
            FOLLOW_DIRECT_BLOCKED_TICKS.remove(id);
            FOLLOW_DOOR_RECOVERY.remove(id);
            FOLLOW_AVOID_DOOR_BASE.remove(id);
            FOLLOW_AVOID_DOOR_UNTIL_MS.remove(id);
        }
        BotCommandStateService.State state = stateFor(bot);
        if (state != null) {
            state.followNoTeleport = false;
            state.followStopRange = 0.0D;
        }
        if (bot != null) {
            BotActions.stop(bot);
        }
        setMode(bot, Mode.IDLE);
        setAssistAllies(bot, true);        if (bot != null && wasFollowing) {
            sendBotMessage(bot, "Stopping follow command.");
        }
        return wasFollowing ? "Bot stopped following." : "Bot is not currently following anyone.";
    }

    public static String setGuardMode(ServerPlayerEntity bot, double radius) {
        registerBot(bot);
        setGuardState(bot, positionOf(bot), Math.max(3.0D, radius));
        setMode(bot, Mode.GUARD);
        setFollowTarget(bot, null);
        clearBase(bot);
        GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
        sendBotMessage(bot, String.format(Locale.ROOT, "Guarding this area (radius %.1f blocks).", getGuardRadius(bot)));
        return "Guarding the area.";
    }

    public static String setPatrolMode(ServerPlayerEntity bot, double radius) {
        registerBot(bot);
        setGuardState(bot, positionOf(bot), Math.max(3.0D, radius));
        setMode(bot, Mode.PATROL);
        setFollowTarget(bot, null);
        clearBase(bot);
        GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
        sendBotMessage(bot, String.format(Locale.ROOT, "Patrolling this area (radius %.1f blocks).", getGuardRadius(bot)));
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

    public static String setReturnToBase(ServerPlayerEntity bot, Vec3d base) {
        registerBot(bot);
        if (base == null) {
            return "No base location available.";
        }
        setBaseTarget(bot, base);
        setMode(bot, Mode.RETURNING_BASE);
        setFollowTarget(bot, null);
        setGuardState(bot, null, getGuardRadius(bot));
        sendBotMessage(bot, "Returning to base.");
        return "Bot is returning to base.";
    }

    public static String setReturnToBase(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        ServerWorld world = bot.getCommandSource().getWorld();
        Vec3d base;
        if (commander != null) {
            ServerWorld commanderWorld = commander.getCommandSource().getWorld();
            BlockPos spawn = resolveSpawnPoint(commanderWorld);
            base = Vec3d.ofCenter(spawn);
        } else {
            BlockPos spawn = resolveSpawnPoint(world);
            base = Vec3d.ofCenter(spawn);
        }
        return setReturnToBase(bot, base);
    }

    public static String setReturnToBase(ServerPlayerEntity bot) {
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos spawn = resolveSpawnPoint(world);
        return setReturnToBase(bot, Vec3d.ofCenter(spawn));
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
        Object spawnPoint = world.getSpawnPoint();
        if (spawnPoint != null) {
            Class<?> clazz = spawnPoint.getClass();
            try {
                Object result = clazz.getMethod("pos").invoke(spawnPoint);
                if (result instanceof BlockPos blockPos) {
                    return blockPos;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Object result = clazz.getMethod("toImmutable").invoke(spawnPoint);
                if (result instanceof BlockPos blockPos) {
                    return blockPos;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Field field = clazz.getDeclaredField("pos");
                field.setAccessible(true);
                Object result = field.get(spawnPoint);
                if (result instanceof BlockPos blockPos) {
                    return blockPos;
                }
            } catch (ReflectiveOperationException ignored) {
            }
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
        ServerPlayerEntity target = targetUuid != null && server != null
                ? server.getPlayerManager().getPlayer(targetUuid)
                : null;
        if (target == null) {
            setMode(bot, Mode.IDLE);
        setAssistAllies(bot, true);            setFollowTarget(bot, null);
	            if (bot != null) {
	                UUID id = bot.getUuid();
	                FOLLOW_LAST_DISTANCE_SQ.remove(id);
	                FOLLOW_STAGNANT_TICKS.remove(id);
	                FOLLOW_LAST_TELEPORT_TICK.remove(id);
	                FOLLOW_DOOR_PLAN.remove(id);
	                FOLLOW_WAYPOINTS.remove(id);
                    FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.remove(id);
	                FOLLOW_PATH_INFLIGHT.remove(id);
	                FOLLOW_LAST_PATH_PLAN_MS.remove(id);
	                FOLLOW_LAST_PATH_TARGET.remove(id);
	                FOLLOW_LAST_DOOR_BASE.remove(id);
	                FOLLOW_LAST_DOOR_CROSS_MS.remove(id);
	                    FOLLOW_LAST_PATH_LOG_MS.remove(id);
	                    FOLLOW_LAST_PATH_FAIL_LOG_MS.remove(id);
	                    FOLLOW_LAST_PATH_SKIP_LOG_MS.remove(id);
                        FOLLOW_LAST_DECISION_LOG_MS.remove(id);
	                    FOLLOW_REPLAN_AFTER_DOOR_MS.remove(id);
	                    FOLLOW_DOOR_LAST_BLOCK.remove(id);
	                    FOLLOW_DOOR_STUCK_TICKS.remove(id);
		                FOLLOW_LAST_BLOCK_POS.remove(id);
		                FOLLOW_POS_STAGNANT_TICKS.remove(id);
                        FOLLOW_DIRECT_BLOCKED_TICKS.remove(id);
                        FOLLOW_DOOR_RECOVERY.remove(id);
                        FOLLOW_AVOID_DOOR_BASE.remove(id);
                        FOLLOW_AVOID_DOOR_UNTIL_MS.remove(id);
		            }
	            sendBotMessage(bot, "Follow target lost. Returning to idle.");
	            return false;
	        }

        List<Entity> augmentedHostiles = new ArrayList<>(hostileEntities);
        if (isAssistAllies(bot)) {
            augmentedHostiles.addAll(BotThreatService.findHostilesAround(target, 8.0D));
        }
        if (externalOverrideActive) {
            LOGGER.debug("Skipping follow because external override is active.");
            return false;
        }

        if (!augmentedHostiles.isEmpty() && engageHostiles(bot, server, augmentedHostiles)) {
            return true;
        }

        lowerShieldTracking(bot);

	        Vec3d targetPos = positionOf(target);
	        double distanceSq = bot.squaredDistanceTo(targetPos);
	        double horizDistSq = horizontalDistanceSq(bot, targetPos);
	        handleFollowPersonalSpace(bot, target, horizDistSq, targetPos);
        boolean forceWalk = state != null && state.followNoTeleport;
        double stopRange = state != null ? state.followStopRange : 0.0D;
        boolean allowTeleportPref = SkillPreferences.teleportDuringSkills(bot) && !forceWalk;
        boolean canSee = bot.canSee(target);
        double deltaY = target.getY() - bot.getY();
        double absDeltaY = Math.abs(deltaY);
        MinecraftServer srv = bot.getCommandSource().getServer();
        if (bot.getEntityWorld() != target.getEntityWorld() && srv != null) {
            ServerWorld targetWorld = srv.getWorld(target.getEntityWorld().getRegistryKey());
            if (targetWorld != null) {
                LOGGER.info("Follow dimension handoff: moving {} from {} to {} (target dim {})", bot.getName().getString(), bot.getEntityWorld().getRegistryKey().getValue(), targetWorld.getRegistryKey().getValue(), target.getEntityWorld().getRegistryKey().getValue());
                ChatUtils.sendSystemMessage(bot.getCommandSource(), bot.getName().getString() + " is in a different world. Spawn or move the bot into this world to continue following.");
                return false;
            } else {
                LOGGER.warn("Follow dimension handoff: unable to resolve target world {} for {}", target.getEntityWorld().getRegistryKey().getValue(), bot.getName().getString());
                return false;
            }
        }
        LOGGER.debug("Follow tick: bot={} target={} dist={}/{} dy={} forceWalk={} allowTpPref={} canSee={} stopRange={}",
                bot.getName().getString(),
                target.getName().getString(),
                Math.sqrt(distanceSq),
                target.getBlockPos(),
                String.format(Locale.ROOT, "%.2f", deltaY),
                forceWalk,
                allowTeleportPref,
                canSee,
                stopRange);
        if (stopRange > 0 && horizDistSq <= stopRange * stopRange) {
            stopFollowing(bot);
            return true;
        }

        UUID botId = bot.getUuid();
        BlockPos navGoalBlock = target.getBlockPos();
        Vec3d navGoalPos = targetPos;
        boolean usingWaypoints = false;
        ArrayDeque<BlockPos> waypoints = FOLLOW_WAYPOINTS.get(botId);
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
	                    if (peekState.getBlock() instanceof DoorBlock || peekUpState.getBlock() instanceof DoorBlock) {
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
	                    FOLLOW_LAST_DISTANCE_SQ.remove(botId);
	                    FOLLOW_STAGNANT_TICKS.remove(botId);
                    FOLLOW_LAST_BLOCK_POS.remove(botId);
                    FOLLOW_POS_STAGNANT_TICKS.remove(botId);
                    continue;
                }
                navGoalBlock = peek;
                navGoalPos = Vec3d.ofCenter(peek);
                usingWaypoints = true;
                break;
            }
            if (waypoints.isEmpty()) {
                FOLLOW_WAYPOINTS.remove(botId);
                usingWaypoints = false;
                navGoalBlock = target.getBlockPos();
                navGoalPos = targetPos;
                waypointCount = 0;
            }
        }
	        // If the commander is far away, do not let stale local waypoints keep us orbiting a doorway.
	        // Once we're out of a tight enclosure, prioritise direct pursuit / long-range catch-up.
	        if (usingWaypoints && distanceSq >= 900.0D) { // ~30 blocks
	            EnvironmentSnapshot env = analyzeEnvironment(bot);
	            if (env != null && (!env.enclosed() || env.hasEscapeRoute())) {
	                FOLLOW_WAYPOINTS.remove(botId);
	                FOLLOW_DOOR_PLAN.remove(botId);
	                FOLLOW_DOOR_LAST_BLOCK.remove(botId);
	                FOLLOW_DOOR_STUCK_TICKS.remove(botId);
	                FOLLOW_DOOR_RECOVERY.remove(botId);
	                usingWaypoints = false;
	                navGoalBlock = target.getBlockPos();
	                navGoalPos = targetPos;
	                maybeLogFollowDecision(bot, "drop-waypoints: long-range target dist="
	                        + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq)));
	            }
	        }

        double progressDistSq = bot.getBlockPos().getSquaredDistance(navGoalBlock);
        boolean directBlocked = progressDistSq <= 36.0D && isDirectRouteBlocked(bot, navGoalPos, navGoalBlock);
        boolean botSealed = isSealedSpace(bot);
        boolean commanderSealed = isSealedSpace(target);
        maybeLogFollowStatus(bot, target, distanceSq, horizDistSq, canSee, directBlocked, usingWaypoints, navGoalBlock, waypointCount, botSealed, commanderSealed);

        if (handleFollowObstacles(bot, target, navGoalPos, navGoalBlock, progressDistSq, distanceSq, absDeltaY, canSee, directBlocked, allowTeleportPref, forceWalk, srv)) {
            return true;
        }
        // Personal space applies even when following waypoints; otherwise the bot can "pile onto" the commander
        // when the final waypoint is near the player.
        double personalSpaceSq = FOLLOW_PERSONAL_SPACE * FOLLOW_PERSONAL_SPACE;
        if (canSee && !directBlocked && horizDistSq <= personalSpaceSq) {
            FOLLOW_WAYPOINTS.remove(botId);
            FOLLOW_DOOR_PLAN.remove(botId);
            BotActions.stop(bot);
            LookController.faceBlock(bot, BlockPos.ofFloored(targetPos));
            return true;
        }
        if (usingWaypoints) {
            double sprintDistanceSq = Math.max(distanceSq, progressDistSq);
            boolean sprint = sprintDistanceSq > FOLLOW_SPRINT_DISTANCE_SQ;
            moveToward(bot, navGoalPos, 0.85D, sprint);
        } else {
            // If we're physically blocked (glass/fence/door), don't "settle" just because we're close in Euclidean distance.
            boolean allowCloseStop = canSee && !directBlocked;
            followInputStep(bot, targetPos, horizDistSq, allowCloseStop);
        }
        return true;
    }

    public static void collectNearbyDrops(ServerPlayerEntity bot, double radius) {
        Mode currentMode = getMode(bot);
        boolean trainingMode = net.shasankp000.Commands.modCommandRegistry.isTrainingMode;
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

        if (DropSweepService.isInProgress()) {
            return true;
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

        if (DropSweepService.isInProgress()) {
            return true;
        }

        Entity nearestItem = findNearestDrop(bot, radius);
        if (nearestItem != null) {
            collectNearbyDrops(bot, Math.max(radius, 4.0D));
            return true;
        }

        double distanceFromCenter = positionOf(bot).distanceTo(center);
        if (distanceFromCenter > radius) {
            GuardPatrolService.setPatrolTarget(bot.getUuid(), null);
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
        }

        if (patrolTarget == null && RANDOM.nextDouble() < 0.08) {
            Vec3d next = randomPointWithin(center, radius * 0.85D);
            GuardPatrolService.setPatrolTarget(bot.getUuid(), next);
            moveToward(bot, next, 1.1D, false);
            return true;
        }

        BotActions.stop(bot);
        return true;
    }

    private static boolean handleReturnToBase(ServerPlayerEntity bot, BotCommandStateService.State state) {
        Vec3d base = state != null ? state.baseTarget : null;
        if (base == null) {
            setMode(bot, Mode.IDLE);
        setAssistAllies(bot, true);            return false;
        }

        double distance = positionOf(bot).distanceTo(base);
        if (distance <= 3.0D) {
            setMode(bot, Mode.STAY);
            sendBotMessage(bot, "Arrived at base. Holding position.");
            setBaseTarget(bot, null);
            return true;
        }

        moveToward(bot, base, 2.0D, false);
        return true;
    }

    private static boolean engageHostiles(ServerPlayerEntity bot, MinecraftServer server, List<Entity> hostileEntities) {
        if (hostileEntities.isEmpty()) {
            return false;
        }
        Entity closest = hostileEntities.stream()
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                .orElse(null);
        if (closest == null) {
            return false;
        }
        double distance = Math.sqrt(closest.squaredDistanceTo(bot));
        boolean targetVisible = closest instanceof LivingEntity living && bot.canSee(living);
        boolean hasRanged = targetVisible && BotActions.hasRangedWeapon(bot);
        double verticalDiff = bot.getY() - closest.getY();
        boolean projectileThreat = closest.getType().isIn(EntityTypeTags.SKELETONS) || closest.getName().getString().toLowerCase(Locale.ROOT).contains("pillager");
        boolean creeperThreat = closest.getType() == EntityType.CREEPER;
        boolean multipleThreats = hostileEntities.size() > 1;
        boolean lowHealth = bot.getHealth() <= bot.getMaxHealth() * 0.5F;
        boolean shouldBlock = (projectileThreat || creeperThreat || multipleThreats || lowHealth) && distance <= 4.5D;

        if (combatStyle == CombatStyle.EVASIVE && distance <= 6.0D && verticalDiff > 1.0D) {
            BotActions.moveBackward(bot);
            if (bot.isOnGround() && verticalDiff > 2.0D) {
                BotActions.jump(bot);
            }
            return true;
        }

        if (creeperThreat && distance <= 4.0D) {
            BotActions.raiseShield(bot);
            BotActions.moveBackward(bot);
            return true;
        }

        if (hasRanged && distance >= 5.0D && closest instanceof LivingEntity living) {
            if (BotActions.performRangedAttack(bot, living, server.getTicks())) {
                return true;
            }
        } else {
            BotActions.resetRangedState(bot);
        }

        if (creeperThreat && distance <= 3.0D) {
            long tick = bot.getCommandSource().getServer().getTicks();
            if (!isShieldRaised(bot) && BotActions.raiseShield(bot)) {
                setShieldRaised(bot, true);
                setShieldDecisionTick(bot, tick);
            }
            BotActions.moveBackward(bot);
            return true;
        }

        if (distance > 3.0D) {
            lowerShieldTracking(bot);
            moveToward(bot, positionOf(closest), 2.5D, true);
        } else if (shouldBlock) {
            long now = bot.getCommandSource().getServer().getTicks();
            if (!isShieldRaised(bot)) {
                if (BotActions.raiseShield(bot)) {
                    setShieldRaised(bot, true);
                    setShieldDecisionTick(bot, now);
                }
                return true;
            }

            if (now - getShieldDecisionTick(bot) >= 15) {
                lowerShieldTracking(bot);
                BotActions.selectBestWeapon(bot);
                BotActions.attackNearest(bot, hostileEntities);
                setShieldDecisionTick(bot, now);
            }
            return true;
        } else {
            lowerShieldTracking(bot);
            boolean hasMelee = BotActions.selectBestWeapon(bot);
            if (!hasMelee && hasRanged && closest instanceof LivingEntity living) {
                BotActions.clearForceMelee(bot);
                BotActions.performRangedAttack(bot, living, server.getTicks());
            } else {
                BotActions.attackNearest(bot, hostileEntities);
            }
        }
        return true;
    }

    private static void moveToward(ServerPlayerEntity bot, Vec3d target, double stopDistance, boolean sprint) {
        Vec3d pos = positionOf(bot);
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq <= stopDistance * stopDistance) {
            BotActions.stop(bot);
            return;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);

        lowerShieldTracking(bot);
        BotActions.sprint(bot, sprint);
        if (target.y - pos.y > 0.6D) {
            BotActions.jump(bot);
        } else {
            BotActions.autoJumpIfNeeded(bot);
        }
        BotActions.applyMovementInput(bot, target, sprint ? 0.18 : 0.14);
    }

    private static void simplePursuitStep(ServerPlayerEntity bot, Vec3d targetPos, boolean allowCloseStop) {
        if (bot == null || targetPos == null) {
            return;
        }
        double distanceSq = horizontalDistanceSq(bot, targetPos);
        if (allowCloseStop && distanceSq <= FOLLOW_PERSONAL_SPACE * FOLLOW_PERSONAL_SPACE) {
            BotActions.stop(bot); // close enough, chill
            return;
        }
        LookController.faceBlock(bot, BlockPos.ofFloored(targetPos));
        boolean sprint = distanceSq > FOLLOW_SPRINT_DISTANCE_SQ;
        BotActions.sprint(bot, sprint);
        double dy = targetPos.y - bot.getY();
        if (dy > 0.6D) {
            BotActions.jump(bot);
        } else if (distanceSq > 2.25D) {
            BotActions.autoJumpIfNeeded(bot);
        }
        double impulse = sprint ? 0.22 : 0.16;
        BotActions.applyMovementInput(bot, targetPos, impulse);
    }

    private static void followInputStep(ServerPlayerEntity bot, Vec3d targetPos, double distanceSq, boolean allowCloseStop) {
        // Keep this as a separate helper so follow logic never blocks the server tick thread.
        simplePursuitStep(bot, targetPos, allowCloseStop);
        // Clear transient stuck tracking once we've re-entered close range.
        if (bot != null && distanceSq <= 2.25D) {
            UUID id = bot.getUuid();
            FOLLOW_LAST_DISTANCE_SQ.remove(id);
            FOLLOW_STAGNANT_TICKS.remove(id);
            FOLLOW_LAST_BLOCK_POS.remove(id);
            FOLLOW_POS_STAGNANT_TICKS.remove(id);
            FOLLOW_DIRECT_BLOCKED_TICKS.remove(id);
            FOLLOW_DOOR_RECOVERY.remove(id);
            FOLLOW_AVOID_DOOR_BASE.remove(id);
            FOLLOW_AVOID_DOOR_UNTIL_MS.remove(id);
        }
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
        if (bot == null || target == null) {
            return false;
        }
        boolean botSealed = isSealedSpace(bot);
        boolean commanderSealed = isSealedSpace(target);
        boolean teleportPreferenceEnabled = SkillPreferences.teleportDuringSkills(bot);
        // Only treat "very close" as resolved if we're not physically blocked; otherwise we still need
        // door/waypoint planning to reach the commander through the enclosure.
        if (progressDistSq <= 2.25D && canSee && !directBlocked) {
            UUID id = bot.getUuid();
            FOLLOW_LAST_DISTANCE_SQ.remove(id);
            FOLLOW_STAGNANT_TICKS.remove(id);
            FOLLOW_LAST_BLOCK_POS.remove(id);
            FOLLOW_POS_STAGNANT_TICKS.remove(id);
            return false;
        }

        UUID id = bot.getUuid();
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
        if (directBlocked && posStagnant >= 5 && effectiveStagnant >= 10) {
            BlockPos directionGoal = navGoalBlock != null ? navGoalBlock : target.getBlockPos();
            Direction towardAction = directionGoal != null
                    ? approximateToward(bot.getBlockPos(), directionGoal)
                    : bot.getHorizontalFacing();
            if (MovementService.hasLeafObstruction(bot, towardAction)
                    && MovementService.clearLeafObstruction(bot, towardAction)) {
                maybeLogFollowDecision(bot, "leaf-cleared: toward=" + towardAction + " stagnant=" + effectiveStagnant);
                return true;
            }
        }

        Vec3d commanderGoal = positionOf(target);
        boolean commanderRouteBlocked = isDirectRouteBlocked(bot, commanderGoal, target.getBlockPos());
        if (canSee && commanderRouteBlocked && isOpenDoorBetween(bot, target)) {
            commanderRouteBlocked = false;
        }
        if (canSee && !commanderRouteBlocked) {
            if (!botSealed && !commanderSealed) {
                FOLLOW_DOOR_PLAN.remove(id);
                FOLLOW_DOOR_LAST_BLOCK.remove(id);
                FOLLOW_DOOR_STUCK_TICKS.remove(id);
                FOLLOW_DOOR_RECOVERY.remove(id);
                FOLLOW_WAYPOINTS.remove(id);
                maybeLogFollowDecision(bot, "commander-route clear: dist="
                        + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
                return false;
            } else {
                maybeLogFollowDecision(bot, "commander-route clear but sealed: botSealed="
                        + botSealed + " commanderSealed=" + commanderSealed);
            }
        }

        boolean overrideApplied = applyLongRangeFollowOverride(bot, target, targetDistSq, navGoalBlock, canSee, directBlocked, botSealed, commanderSealed);
        if (overrideApplied) {
            directBlocked = false;
        }

        boolean teleportDesired = shouldWolfTeleport(targetDistSq, absDeltaY, canSee, effectiveStagnant, server);
        if (teleportDesired && !allowTeleportPref) {
            String reason = teleportPreferenceEnabled
                    ? "force-walk"
                    : "teleport-pref-disabled";
            maybeLogFollowDecision(bot, "teleport-suppressed: reason=" + reason
                    + " forceWalk=" + forceWalk
                    + " dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq))
                    + " stagnant=" + effectiveStagnant);
        }
        if (allowTeleportPref && teleportDesired) {
            if (tryWolfTeleport(bot, target, server)) {
                FOLLOW_DOOR_PLAN.remove(id);
                FOLLOW_WAYPOINTS.remove(id);
                FOLLOW_LAST_DISTANCE_SQ.remove(id);
                FOLLOW_STAGNANT_TICKS.remove(id);
                maybeLogFollowDecision(bot, "teleport: long-range regain dist="
                        + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)));
                return true;
            }
        }

        if (shouldPrioritizeCommanderOverDoors(bot, target, canSee, directBlocked, targetDistSq, botSealed, commanderSealed)) {
            FOLLOW_DOOR_PLAN.remove(id);
            FOLLOW_DOOR_LAST_BLOCK.remove(id);
            FOLLOW_DOOR_STUCK_TICKS.remove(id);
            FOLLOW_DOOR_RECOVERY.remove(id);
            FOLLOW_WAYPOINTS.remove(id);
            maybeLogFollowDecision(bot, "skip-door-magnet: forcing direct follow dist="
                    + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq))
                    + " sealed=" + botSealed + "/" + commanderSealed);
            return false;
        }

        // If we are executing a door sub-goal (enclosure escape), drive that first.
        FollowDoorPlan activeDoorPlan = FOLLOW_DOOR_PLAN.get(id);
        long nowMs = System.currentTimeMillis();
        if (activeDoorPlan != null) {
            // If the commander moved away and we now have a clear direct route, stop "lingering" on the doorway
            // and resume normal follow immediately.
            if (canSee && targetDistSq <= 144.0D) { // ~12 blocks
                boolean blockedToCommander = isDirectRouteBlocked(bot, commanderGoal, target.getBlockPos());
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
        if ((directBlocked || effectiveStagnant >= 3 || targetDistSq <= 400.0D) && bot.getEntityWorld() instanceof ServerWorld world) {
            BlockPos base = bot.getBlockPos();
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos candidate = base.offset(dir);
                if (world.getBlockState(candidate).getBlock() instanceof net.minecraft.block.DoorBlock
                        || world.getBlockState(candidate.up()).getBlock() instanceof net.minecraft.block.DoorBlock) {
                    BlockPos doorBase = normalizeDoorBase(world, candidate);
                    if (doorBase != null) {
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
        int escapeThreshold = (targetDistSq >= 900.0D && !canSee) ? 8 : 2;
        if (directBlocked && effectiveStagnant >= escapeThreshold && FOLLOW_DOOR_PLAN.get(id) == null && bot.getEntityWorld() instanceof ServerWorld world) {
            long now = System.currentTimeMillis();
            long lastPlanMs = FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.getOrDefault(id, -1L);
            if (now - lastPlanMs >= 900L) {
                FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.put(id, now);
                BlockPos avoidDoor = currentAvoidDoor(id);
                long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(id, -1L);
                if (lastDoorMs >= 0 && (System.currentTimeMillis() - lastDoorMs) < 5_000L) {
                    avoidDoor = FOLLOW_LAST_DOOR_BASE.get(id);
                }
                MovementService.DoorSubgoalPlan escape = MovementService.findDoorEscapePlan(bot, navGoalBlock, avoidDoor);
                if (escape != null) {
                    if (targetDistSq >= 900.0D && MovementService.isDoorRecentlyClosed(id, escape.doorBase())) {
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
                        FollowDoorPlan plan = new FollowDoorPlan(
                                escape.doorBase().toImmutable(),
                                escape.approachPos().toImmutable(),
                                escape.stepThroughPos().toImmutable(),
                                System.currentTimeMillis() + 5_000L,
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
                requestFollowPathPlan(bot, target, true, "direct-blocked-stuck");
            }
        }

        // Proactively open a door directly between bot and target when close enough.
        // This handles the common "follow while behind a closed door" case even when
        // distanceSq jitters and the stagnant counter doesn't hit exactly.
        if (targetDistSq <= 400.0D) { // keep this local; long-range “door ray” tends to create distractions
            Vec3d doorRayGoal = navGoalPos != null ? navGoalPos : target.getEyePos();
            BlockPos blockingDoor = BlockInteractionService.findDoorAlongLine(bot, doorRayGoal, 5.5D);
            if (blockingDoor != null) {
                MovementService.tryOpenDoorAt(bot, blockingDoor);
                // Commit to stepping through the doorway (non-blocking) to avoid jittering on the threshold.
                if (bot.getEntityWorld() instanceof ServerWorld world) {
                    FollowDoorPlan plan = buildFollowDoorPlan(bot, world, blockingDoor);
                    if (plan != null) {
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
            MovementService.tryOpenDoorToward(bot, navGoalBlock != null ? navGoalBlock : target.getBlockPos());
        }

        // If we're still stuck and the target is likely "around the corner", treat a nearby door as a sub-goal.
        if (effectiveStagnant >= 6 && FOLLOW_DOOR_PLAN.get(id) == null && bot.getEntityWorld() instanceof ServerWorld world) {
            // If the commander is close and visible and we're not directly blocked, never detour through doors.
            // This prevents “door magnet” behavior when the bot is already in the right area.
            if (canSee && !directBlocked && targetDistSq <= 64.0D) {
                FOLLOW_WAYPOINTS.remove(id);
                return false;
            }
            // Only treat doors as a sub-goal when we have reason to think we need to change rooms (blocked or no LoS).
            if (!directBlocked && canSee) {
                return false;
            }
            // If the commander is far away, avoid “nearest door” distractions; follow will rely on direct pursuit
            // and (if enabled) wolf-teleport catch-up rather than local door heuristics.
            if (!directBlocked && !canSee && targetDistSq >= 900.0D) { // ~30 blocks
                return false;
            }
            BlockPos goalBlock = navGoalBlock != null ? navGoalBlock : target.getBlockPos();
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
	                    if (lastDoorMs >= 0 && lastDoor != null && lastDoor.equals(plan.doorBase()) && (System.currentTimeMillis() - lastDoorMs) < 4_500L) {
	                        // Avoid immediate oscillation back through the same door we just crossed.
	                    } else {
                        FollowDoorPlan followPlan = new FollowDoorPlan(
                                plan.doorBase(),
                                plan.approachPos(),
                                plan.stepThroughPos(),
                                System.currentTimeMillis() + 4_000L,
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

        maybeRequestFollowPathPlan(bot, target, canSee, effectiveStagnant);

        Long replanAfterDoor = FOLLOW_REPLAN_AFTER_DOOR_MS.remove(id);
        if (replanAfterDoor != null && (System.currentTimeMillis() - replanAfterDoor) < 4_500L) {
            ArrayDeque<BlockPos> waypoints = FOLLOW_WAYPOINTS.get(id);
            if (waypoints == null || waypoints.isEmpty()) {
                requestFollowPathPlan(bot, target, true, "post-door");
            }
        }

        // Wolf-style teleport catch-up:
        // - only when player has allowed teleport generally,
        // - only when far enough or when we've been stuck for a while,
        // - and never spam (cooldown).
        if (allowTeleportPref && shouldWolfTeleport(targetDistSq, absDeltaY, canSee, effectiveStagnant, server)) {
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
        BotActions.autoJumpIfNeeded(bot);
        BotActions.applyMovementInput(bot, goalCenter, sprint ? 0.18 : 0.14);

        // If we're stuck on a doorway threshold, apply a small lateral nudge to re-align.
        BlockPos curBlock = bot.getBlockPos();
        BlockPos prev = FOLLOW_DOOR_LAST_BLOCK.get(botId);
        int stuck = FOLLOW_DOOR_STUCK_TICKS.getOrDefault(botId, 0);
        if (prev != null && prev.equals(curBlock)) {
            stuck++;
        } else {
            stuck = 0;
            FOLLOW_DOOR_LAST_BLOCK.put(botId, curBlock.toImmutable());
        }
        FOLLOW_DOOR_STUCK_TICKS.put(botId, stuck);
        if (stuck >= 8) {
            // When blocked by a doorway/fence corner, attempt short, local repositioning moves.
            // Choosing recovery relative to the current block works better than using only the approach block.
            BlockPos cur = bot.getBlockPos();
            Direction towardGoal = approximateToward(cur, goal);
            if (!towardGoal.getAxis().isHorizontal()) {
                towardGoal = bot.getHorizontalFacing();
            }

            ArrayList<BlockPos> candidates = new ArrayList<>(10);

            // Prefer a true “double back” on the approach side (more reliable than tiny nudges).
            Direction awayFromDoor = Direction.getFacing(
                    approachPos.getX() - doorBase.getX(),
                    0,
                    approachPos.getZ() - doorBase.getZ());
            if (awayFromDoor.getAxis().isHorizontal()) {
                BlockPos retreat2 = doorBase.offset(awayFromDoor, 2);
                BlockPos retreat3 = doorBase.offset(awayFromDoor, 3);
                if (isStandable(world, retreat2)) {
                    FOLLOW_DOOR_RECOVERY.put(botId, new FollowDoorRecovery(retreat2.toImmutable(), 14));
                    FOLLOW_DOOR_STUCK_TICKS.put(botId, 0);
                    BotActions.stop(bot);
                    maybeLogFollowDecision(bot, "door-recovery: goal=" + retreat2.toShortString()
                            + " doorBase=" + doorBase.toShortString()
                            + " stepping=" + plan.stepping());
                    Vec3d retreatCenter = Vec3d.ofCenter(retreat2);
                    LookController.faceBlock(bot, retreat2);
                    BotActions.applyMovementInput(bot, retreatCenter, 0.14);
                    return true;
                }
                if (isStandable(world, retreat3)) {
                    FOLLOW_DOOR_RECOVERY.put(botId, new FollowDoorRecovery(retreat3.toImmutable(), 16));
                    FOLLOW_DOOR_STUCK_TICKS.put(botId, 0);
                    BotActions.stop(bot);
                    maybeLogFollowDecision(bot, "door-recovery: goal=" + retreat3.toShortString()
                            + " doorBase=" + doorBase.toShortString()
                            + " stepping=" + plan.stepping());
                    Vec3d retreatCenter = Vec3d.ofCenter(retreat3);
                    LookController.faceBlock(bot, retreat3);
                    BotActions.applyMovementInput(bot, retreatCenter, 0.14);
                    return true;
                }
                candidates.add(doorBase.offset(awayFromDoor, 2).offset(awayFromDoor.rotateYClockwise()));
                candidates.add(doorBase.offset(awayFromDoor, 2).offset(awayFromDoor.rotateYCounterclockwise()));
            }

            // Fallback: local sidesteps/backstep (clears hinge/fence corners).
            candidates.add(cur.offset(towardGoal.getOpposite()));
            candidates.add(cur.offset(towardGoal.rotateYClockwise()));
            candidates.add(cur.offset(towardGoal.rotateYCounterclockwise()));

            double bestDist = Double.MAX_VALUE;
            BlockPos best = null;
            for (BlockPos c : candidates) {
                if (c == null || !isStandable(world, c)) {
                    continue;
                }
                double d = c.getSquaredDistance(goal);
                if (d < bestDist) {
                    bestDist = d;
                    best = c.toImmutable();
                }
            }

            if (best != null) {
                FOLLOW_DOOR_RECOVERY.put(botId, new FollowDoorRecovery(best, 12));
                FOLLOW_DOOR_STUCK_TICKS.put(botId, 0);
                BotActions.stop(bot);
                maybeLogFollowDecision(bot, "door-recovery: goal=" + best.toShortString()
                        + " toward=" + towardGoal
                        + " doorBase=" + doorBase.toShortString()
                        + " stepping=" + plan.stepping());
                Vec3d bestCenter = Vec3d.ofCenter(best);
                LookController.faceBlock(bot, best);
                BotActions.applyMovementInput(bot, bestCenter, 0.14);
                return true;
            }

            // If we can't find any safe local reposition, stop committing to this door plan.
            if (stuck >= 16) {
                maybeLogFollowDecision(bot, "door-plan abort: stuck=" + stuck
                        + " doorBase=" + doorBase.toShortString()
                        + " goal=" + goal.toShortString()
                        + " stepping=" + plan.stepping());
                avoidDoorFor(botId, doorBase, 4_000L, "door-plan-abort");
                FOLLOW_DOOR_PLAN.remove(botId);
                FOLLOW_DOOR_LAST_BLOCK.remove(botId);
                FOLLOW_DOOR_STUCK_TICKS.remove(botId);
                FOLLOW_DOOR_RECOVERY.remove(botId);
                return false;
            }
        }

        if (distSq <= 2.25D) {
            if (!plan.stepping()) {
                boolean isOpen = doorState.contains(net.minecraft.block.DoorBlock.OPEN)
                        && Boolean.TRUE.equals(doorState.get(net.minecraft.block.DoorBlock.OPEN));
                boolean opened = isOpen || MovementService.tryOpenDoorAt(bot, doorBase);
                if (!opened) {
                    // Don't commit to stepping through a door we couldn't open; instead, try to re-approach
                    // for a better interaction angle.
                    if (!FOLLOW_DOOR_RECOVERY.containsKey(botId)) {
                        Direction away = Direction.getFacing(
                                approachPos.getX() - doorBase.getX(),
                                0,
                                approachPos.getZ() - doorBase.getZ());
                        if (away.getAxis().isHorizontal()) {
                            BlockPos retreat = approachPos.offset(away).offset(away);
                            if (isStandable(world, retreat)) {
                                FOLLOW_DOOR_RECOVERY.put(botId, new FollowDoorRecovery(retreat.toImmutable(), 10));
                            }
                        }
                    }
                    avoidDoorFor(botId, doorBase, 4_000L, "door-open-failed");
                    maybeLogFollowDecision(bot, "door-open failed: doorBase=" + doorBase.toShortString());
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
            if (botBlock.equals(doorBase) || botBlock.equals(doorBase.up())) {
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
        BlockState state = world.getBlockState(doorBase);
        if (!(state.getBlock() instanceof net.minecraft.block.DoorBlock)) {
            return null;
        }
        if (state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
            return null;
        }
        // Prefer true front/back tiles (more reliable than picking an arbitrary standable neighbor near the hinge).
        if (state.contains(net.minecraft.block.DoorBlock.FACING)) {
            Direction facing = state.get(net.minecraft.block.DoorBlock.FACING);
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

    private static void requestFollowPathPlan(ServerPlayerEntity bot, ServerPlayerEntity target, boolean force, String reason) {
        if (bot == null || target == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            maybeLogFollowPlanSkip(bot.getUuid(), "skip: no server (reason=" + reason + ")");
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld botWorld) || !(target.getEntityWorld() instanceof ServerWorld targetWorld)) {
            maybeLogFollowPlanSkip(bot.getUuid(), "skip: non-server world (reason=" + reason + ")");
            return;
        }
        if (botWorld != targetWorld) {
            maybeLogFollowPlanSkip(bot.getUuid(), "skip: world mismatch (reason=" + reason + ")");
            return;
        }

        UUID botId = bot.getUuid();
        UUID targetId = target.getUuid();
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_PATH_PLAN_MS.getOrDefault(botId, -1L);
        long minInterval = force ? Math.min(650L, FollowPathService.PLAN_COOLDOWN_MS) : FollowPathService.PLAN_COOLDOWN_MS;
        if (last >= 0 && (now - last) < minInterval) {
            maybeLogFollowPlanSkip(botId, "skip: cooldown (" + (now - last) + "ms, reason=" + reason + ")");
            return;
        }
        if (FOLLOW_PATH_INFLIGHT.containsKey(botId)) {
            maybeLogFollowPlanSkip(botId, "skip: inflight (reason=" + reason + ")");
            return;
        }
        BlockPos targetPos = target.getBlockPos().toImmutable();
        BlockPos lastTarget = FOLLOW_LAST_PATH_TARGET.get(botId);
        if (!force && lastTarget != null && lastTarget.getSquaredDistance(targetPos) <= 4.0D
                && last >= 0 && (now - last) < FollowPathService.PLAN_COOLDOWN_MS * 2) {
            maybeLogFollowPlanSkip(botId, "skip: same target (reason=" + reason + ")");
            return;
        }
        FOLLOW_LAST_PATH_PLAN_MS.put(botId, now);
        FOLLOW_LAST_PATH_TARGET.put(botId, targetPos);

        RegistryKey<World> worldKey = botWorld.getRegistryKey();

        long lastLog = FOLLOW_LAST_PATH_LOG_MS.getOrDefault(botId, -1L);
        if (lastLog < 0 || (now - lastLog) >= 5_000L) {
            FOLLOW_LAST_PATH_LOG_MS.put(botId, now);
            LOGGER.info("Follow path planning start: bot={} botPos={} target={} targetPos={} canSee={} force={} reason={}",
                    bot.getName().getString(),
                    bot.getBlockPos().toShortString(),
                    target.getName().getString(),
                    target.getBlockPos().toShortString(),
                    bot.canSee(target),
                    force,
                    reason == null ? "" : reason);
        }

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<FollowPathService.FollowSnapshot> snapFuture = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                        ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
                        ServerWorld world = server.getWorld(worldKey);
                        if (liveBot == null || liveTarget == null || world == null) {
                            snapFuture.complete(null);
                            return;
                        }
                        if (liveBot.getEntityWorld() != world || liveTarget.getEntityWorld() != world) {
                            snapFuture.complete(null);
                            return;
                        }
                        // Capture a window that (when possible) includes both bot and target for direct planning.
                        // If planning fails, we do a second, bot-centered capture for an "escape nearest door" plan.
                        snapFuture.complete(FollowPathService.capture(world, liveBot.getBlockPos(), liveTarget.getBlockPos(), false));
                    } catch (Throwable t) {
                        snapFuture.complete(null);
                    }
                });

                FollowPathService.FollowSnapshot snapshot;
                try {
                    snapshot = snapFuture.get(900, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    snapshot = null;
                }
        if (snapshot == null) {
            maybeLogFollowPlanSkip(botId, "skip: snapshot null (reason=" + (reason == null ? "" : reason) + ")");
            return;
        }
        BlockPos avoidDoor = null;
        long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(botId, -1L);
        if (lastDoorMs >= 0 && (System.currentTimeMillis() - lastDoorMs) < 5_000L) {
            avoidDoor = FOLLOW_LAST_DOOR_BASE.get(botId);
        }
                List<BlockPos> waypoints = FollowPathService.planWaypoints(snapshot, avoidDoor);
                if (waypoints.isEmpty()) {
                    // Second attempt: if the goal-inclusive window misses the enclosure door (common when the correct
                    // first move is away from the target), capture a bot-centered window and plan an escape door.
                    CompletableFuture<FollowPathService.FollowSnapshot> escapeSnapFuture = new CompletableFuture<>();
	                server.execute(() -> {
	                        try {
	                            ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
	                            ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
	                            ServerWorld world = server.getWorld(worldKey);
	                            if (liveBot == null || world == null || liveBot.getEntityWorld() != world) {
	                                escapeSnapFuture.complete(null);
	                                return;
	                            }
	                            BlockPos b = liveBot.getBlockPos();
	                            BlockPos g = liveTarget != null ? liveTarget.getBlockPos() : b;
	                            FollowPathService.FollowSnapshot attempt = FollowPathService.capture(world, b, g, true);
	                            if (attempt == null) {
	                                // Fallback: pass botPos as "goal" to bypass distance gating when the commander is far away.
	                                attempt = FollowPathService.capture(world, b, b, true);
	                            }
	                            escapeSnapFuture.complete(attempt);
	                        } catch (Throwable t) {
	                            escapeSnapFuture.complete(null);
	                        }
	                    });

                    FollowPathService.FollowSnapshot escapeSnapshot;
                    try {
                        escapeSnapshot = escapeSnapFuture.get(900, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        escapeSnapshot = null;
                    }
                    if (escapeSnapshot != null) {
                        waypoints = FollowPathService.planEscapeWaypoints(escapeSnapshot, avoidDoor);
                    }
                }
                if (waypoints.isEmpty()) {
                    long lastFail = FOLLOW_LAST_PATH_FAIL_LOG_MS.getOrDefault(botId, -1L);
                    long nowFail = System.currentTimeMillis();
                    if (lastFail < 0 || (nowFail - lastFail) >= 4_500L) {
                        FOLLOW_LAST_PATH_FAIL_LOG_MS.put(botId, nowFail);
                        LOGGER.info("Follow path planning: no path found (bot={} target={})", botId, targetId);
                    }
                    return;
                }
                final List<BlockPos> plannedWaypoints = List.copyOf(waypoints);
                server.execute(() -> {
                    ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                    ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
                    if (liveBot == null) {
                        return;
                    }
                    BotCommandStateService.State st = stateFor(liveBot);
                    if (getMode(liveBot) != Mode.FOLLOW || st == null || st.followTargetUuid == null || !st.followTargetUuid.equals(targetId)) {
                        return;
                    }
                    FOLLOW_WAYPOINTS.put(botId, new ArrayDeque<>(plannedWaypoints));
                    FOLLOW_DOOR_PLAN.remove(botId);
                    FOLLOW_LAST_DISTANCE_SQ.remove(botId);
                    FOLLOW_STAGNANT_TICKS.remove(botId);
                    LOGGER.info("Follow planned {} waypoint(s): bot={} target={} first={}",
                            plannedWaypoints.size(),
                            liveBot.getName().getString(),
                            liveTarget != null ? liveTarget.getName().getString() : targetId.toString(),
                            plannedWaypoints.get(0).toShortString());
                });
            } catch (Throwable t) {
                // Keep follow resilient; failures just fall back to local steering.
                LOGGER.debug("Follow path plan failed: {}", t.getMessage());
            }
        });

        FOLLOW_PATH_INFLIGHT.put(botId, task);
        task.whenComplete((ignored, err) -> server.execute(() -> FOLLOW_PATH_INFLIGHT.remove(botId)));
    }

    private static void maybeLogFollowPlanSkip(UUID botId, String message) {
        if (botId == null || message == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_PATH_SKIP_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < 5_000L) {
            return;
        }
        FOLLOW_LAST_PATH_SKIP_LOG_MS.put(botId, now);
        LOGGER.info("Follow path planning {}", message);
    }

    private static void maybeLogFollowDecision(ServerPlayerEntity bot, String message) {
        if (bot == null || message == null) {
            return;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_DECISION_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < 2_500L) {
            return;
        }
        FOLLOW_LAST_DECISION_LOG_MS.put(botId, now);
        LOGGER.info("Follow decision: bot={} botPos={} msg={}",
                bot.getName().getString(),
                bot.getBlockPos().toShortString(),
                message);
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
        long last = FOLLOW_LAST_STATUS_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < FOLLOW_STATUS_LOG_INTERVAL_MS) {
            return;
        }

        boolean shouldLog = targetDistSq >= 900.0D
                || directBlocked
                || usingWaypoints
                || FOLLOW_DOOR_PLAN.get(botId) != null;
        if (!shouldLog) {
            return;
        }
        FOLLOW_LAST_STATUS_LOG_MS.put(botId, now);

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
        LOGGER.info("Follow status: bot={} botPos={} target={} targetPos={} dist={} horiz={} canSee={} directBlocked={} usingWaypoints={} wp={} navGoal={} sealed={}/{}{}{}{}",
                bot.getName().getString(),
                bot.getBlockPos().toShortString(),
                target.getName().getString(),
                target.getBlockPos().toShortString(),
                String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)),
                String.format(Locale.ROOT, "%.2f", Math.sqrt(horizDistSq)),
                canSee,
                directBlocked,
                usingWaypoints,
                waypointCount,
                navGoalStr,
                botSealed,
                commanderSealed,
                doorPlanStr,
                lastDoorStr,
                avoidStr);
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
        EnvironmentSnapshot env = analyzeEnvironment(bot);
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
        String reason = "long-range override: dist=" + String.format(Locale.ROOT, "%.2f", Math.sqrt(targetDistSq))
                + " navGoal=" + (navGoalBlock != null ? navGoalBlock.toShortString() : "none")
                + " env.enclosed=" + env.enclosed()
                + " hadDoorPlan=" + hadDoorPlan
                + " hadWaypoints=" + hadWaypoints;
        maybeLogFollowDecision(bot, reason);
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
        return state.contains(DoorBlock.OPEN) && Boolean.TRUE.equals(state.get(DoorBlock.OPEN));
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
        EnvironmentSnapshot env = analyzeEnvironment(entity);
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
                    if (!(state.getBlock() instanceof DoorBlock)) {
                        continue;
                    }
                    if (state.contains(DoorBlock.OPEN) && Boolean.TRUE.equals(state.get(DoorBlock.OPEN))) {
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
        BlockState down = world.getBlockState(pos.down());
        if (down.getBlock() instanceof net.minecraft.block.DoorBlock) {
            return normalizeDoorBase(world, pos.down());
        }
        BlockState up = world.getBlockState(pos.up());
        if (up.getBlock() instanceof net.minecraft.block.DoorBlock) {
            return normalizeDoorBase(world, pos.up());
        }
        return null;
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
        return farAndNotVisible || verticalSeparation || stuckTooLong;
    }

    private static boolean tryWolfTeleport(ServerPlayerEntity bot, ServerPlayerEntity target, MinecraftServer server) {
        if (bot == null || target == null || server == null) {
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
        Vec3d center = Vec3d.ofCenter(safe);
        bot.teleport(world,
                center.x, center.y, center.z,
                EnumSet.noneOf(PositionFlag.class),
                target.getYaw(),
                target.getPitch(),
                true);
        bot.setVelocity(Vec3d.ZERO);
        FOLLOW_LAST_TELEPORT_TICK.put(id, nowTick);
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
        if (bot == null || target == null || targetPos == null) {
            return;
        }
        UUID id = bot.getUuid();
        double closeSq = FOLLOW_BACKUP_DISTANCE * FOLLOW_BACKUP_DISTANCE;
        if (distanceSq <= closeSq) {
            long now = System.currentTimeMillis();
            Long since = FOLLOW_TOO_CLOSE_SINCE.get(id);
            if (since == null) {
                FOLLOW_TOO_CLOSE_SINCE.put(id, now);
            } else if (now - since >= FOLLOW_BACKUP_TRIGGER_MS) {
                stepBack(bot, targetPos);
            }
        } else {
            FOLLOW_TOO_CLOSE_SINCE.remove(id);
        }
    }

    private static void stepBack(ServerPlayerEntity bot, Vec3d targetPos) {
        if (bot == null || targetPos == null) {
            return;
        }
        Vec3d botPos = positionOf(bot);
        Vec3d away = new Vec3d(botPos.x - targetPos.x, 0, botPos.z - targetPos.z);
        if (away.lengthSquared() < 1.0E-4) {
            // Nudge with current facing if overlap
            float yaw = bot.getYaw();
            double dx = -Math.sin(Math.toRadians(yaw));
            double dz = Math.cos(Math.toRadians(yaw));
            away = new Vec3d(dx, 0, dz);
        }
        Vec3d target = botPos.add(away.normalize().multiply(1.8));
        LookController.faceBlock(bot, BlockPos.ofFloored(target));
        BotActions.sprint(bot, false);
        BotActions.applyMovementInput(bot, target, 0.14);
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
        return world.getEntitiesByClass(
                        ItemEntity.class,
                        searchBox,
                        drop -> drop.isAlive() && !drop.isRemoved() && drop.squaredDistanceTo(bot) > 1.0D)
                .stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
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
        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), message);
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

    private static boolean shouldPersistRlNow(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_RL_PERSIST_MS.getOrDefault(id, -1L);
        if (last >= 0 && (now - last) < RL_PERSIST_MIN_INTERVAL_MS) {
            return false;
        }
        LAST_RL_PERSIST_MS.put(id, now);
        return true;
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

        EnvironmentSnapshot nextEnv = analyzeEnvironment(bot);
        if (!isSpartanCandidate(nextEnv)) {
            lastSafePosition = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
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

        if (shouldPersistRlNow(bot)) {
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

        EnvironmentSnapshot environmentSnapshot = analyzeEnvironment(bot);
        if (!isSpartanCandidate(environmentSnapshot)) {
            lastSafePosition = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
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
        switch (action) {
            case "moveForward" -> {
                debugRL("Performing action: move forward");
                BotActions.moveForward(bot);
                AutoFaceEntity.isBotMoving = true;
            }
            case "moveBackward" -> {
                debugRL("Performing action: move backward");
                BotActions.moveBackward(bot);
                AutoFaceEntity.isBotMoving = true;
            }
            case "turnLeft" -> {
                debugRL("Performing action: turn left");
                BotActions.turnLeft(bot);
            }
            case "turnRight" -> {
                debugRL("Performing action: turn right");
                BotActions.turnRight(bot);
            }
            case "jump" -> {
                debugRL("Performing action: jump");
                BotActions.jump(bot);
            }
            case "jumpForward" -> {
                debugRL("Performing action: jump forward");
                BotActions.jumpForward(bot);
            }
            case "sneak" -> {
                debugRL("Performing action: sneak");
                BotActions.sneak(bot, true);
            }
            case "sprint" -> {
                debugRL("Performing action: sprint");
                BotActions.sprint(bot, true);
            }
            case "unsneak" -> {
                debugRL("Performing action: unsneak");
                BotActions.sneak(bot, false);
            }
            case "unsprint" -> {
                debugRL("Performing action: unsprint");
                BotActions.sprint(bot, false);
            }
            case "stopMoving" -> {
                debugRL("Performing action: stop moving");
                BotActions.stop(bot);
                AutoFaceEntity.isBotMoving = false;
            }
            case "useItem" -> {
                debugRL("Performing action: use currently selected item");
                BotActions.useSelectedItem(bot);
            }
            case "attack" -> {
                debugRL("Performing action: attack");
                List<Entity> hostiles = AutoFaceEntity.hostileEntities;
                if (hostiles == null || hostiles.isEmpty()) {
                    hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10).stream()
                            .filter(EntityUtil::isHostile)
                            .toList();
                }
                if (!hostiles.isEmpty()) {
                    FaceClosestEntity.faceClosestEntity(bot, hostiles);
                    BotActions.attackNearest(bot, hostiles);
                } else {
                    debugRL("No hostile entities available to attack.");
                }
            }
            case "hotbar1" -> {
                debugRL("Performing action: Select hotbar slot 1");
                BotActions.selectHotbarSlot(bot, 0);
            }
            case "hotbar2" -> {
                debugRL("Performing action: Select hotbar slot 2");
                BotActions.selectHotbarSlot(bot, 1);
            }
            case "hotbar3" -> {
                debugRL("Performing action: Select hotbar slot 3");
                BotActions.selectHotbarSlot(bot, 2);
            }
            case "hotbar4" -> {
                debugRL("Performing action: Select hotbar slot 4");
                BotActions.selectHotbarSlot(bot, 3);
            }
            case "hotbar5" -> {
                debugRL("Performing action: Select hotbar slot 5");
                BotActions.selectHotbarSlot(bot, 4);
            }
            case "hotbar6" -> {
                debugRL("Performing action: Select hotbar slot 6");
                BotActions.selectHotbarSlot(bot, 5);
            }
            case "hotbar7" -> {
                debugRL("Performing action: Select hotbar slot 7");
                BotActions.selectHotbarSlot(bot, 6);
            }
            case "hotbar8" -> {
                debugRL("Performing action: Select hotbar slot 8");
                BotActions.selectHotbarSlot(bot, 7);
            }
            case "hotbar9" -> {
                debugRL("Performing action: Select hotbar slot 9");
                BotActions.selectHotbarSlot(bot, 8);
            }
            case "breakBlock" -> {
                debugRL("Performing action: break block ahead");
                boolean success = BotActions.breakBlockAhead(bot);
                registerBlockBreakResult(bot, success);
                if (!success) {
                    debugRL("No suitable block to break ahead.");
                }
            }
            case "placeSupportBlock" -> {
                debugRL("Performing action: place support block");
                boolean success = BotActions.placeSupportBlock(bot);
                if (!success) {
                    registerBlockBreakResult(bot, false);
                } else {
                    failedBlockBreakAttempts = 0;
                }
                if (!success) {
                    debugRL("Unable to place support block (no block or blocked space).");
                }
            }
            case "escapeStairs" -> {
                debugRL("Performing action: escape stairs");
                BotActions.escapeStairs(bot);
            }
            default -> debugRL("Invalid action");
        }
    }

    public static boolean isSpartanModeActive() {
        return spartanModeActive;
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
	            LAST_FOLLOW_PLAN_MS.clear();
	            LAST_FOLLOW_TARGET_POS.clear();
	            FOLLOW_TOO_CLOSE_SINCE.clear();
            FOLLOW_LAST_DISTANCE_SQ.clear();
            FOLLOW_STAGNANT_TICKS.clear();
            FOLLOW_LAST_TELEPORT_TICK.clear();
            FOLLOW_DOOR_PLAN.clear();
            FOLLOW_WAYPOINTS.clear();
            FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.clear();
            FOLLOW_PATH_INFLIGHT.clear();
            FOLLOW_LAST_PATH_PLAN_MS.clear();
            FOLLOW_LAST_PATH_TARGET.clear();
            FOLLOW_LAST_DOOR_BASE.clear();
            FOLLOW_LAST_DOOR_CROSS_MS.clear();
	            FOLLOW_LAST_PATH_LOG_MS.clear();
	            FOLLOW_LAST_PATH_FAIL_LOG_MS.clear();
	            FOLLOW_LAST_PATH_SKIP_LOG_MS.clear();
	            FOLLOW_LAST_DECISION_LOG_MS.clear();
	            FOLLOW_AVOID_DOOR_BASE.clear();
	            FOLLOW_AVOID_DOOR_UNTIL_MS.clear();
	            FOLLOW_REPLAN_AFTER_DOOR_MS.clear();
	            FOLLOW_DOOR_LAST_BLOCK.clear();
	            FOLLOW_DOOR_STUCK_TICKS.clear();
	            FOLLOW_DIRECT_BLOCKED_TICKS.clear();
	            FOLLOW_DOOR_RECOVERY.clear();
	            FOLLOW_LAST_BLOCK_POS.clear();
	            FOLLOW_POS_STAGNANT_TICKS.clear();
	            DropSweepService.reset();
            
            isExecuting = false;
            externalOverrideActive = false;
            botDied = false;
            hasRespawned = false;
            botSpawnCount = 0;

            currentState = null;
            lastKnownPosition = null;
            stationaryTicks = 0;
            spartanModeActive = false;
            failedBlockBreakAttempts = 0;
            lastSafePosition = null;
            lastRespawnHandledTick = -1;
            
            LOGGER.info("BotEventHandler static state reset successfully.");
        }
    }
}
