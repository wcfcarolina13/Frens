package net.shasankp000.GameAI;

import net.shasankp000.EntityUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.ServerTask;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
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
import net.shasankp000.Database.StateActionPair;
import net.shasankp000.Entity.AutoFaceEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.Entity.FaceClosestEntity;
import net.shasankp000.LauncherDetection.LauncherEnvironment;
import net.shasankp000.PlayerUtils.*;
import net.shasankp000.WorldUitls.GetTime;
import net.shasankp000.Entity.EntityDetails;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.Entity.createFakePlayer;
import net.shasankp000.WorldUitls.isBlockItem;
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
    private static final Set<UUID> REGISTERED_BOTS = ConcurrentHashMap.newKeySet();
    private static UUID registeredBotUuid = null;
    public static final String qTableDir = LauncherEnvironment.getStorageDirectory("qtable_storage");
    private static final Object monitorLock = new Object();
    private static boolean isExecuting = false;
    private static final double DEFAULT_RISK_APPETITE = 0.5; // Default value upon respawn
    public static boolean botDied = false; // Flag to track if the bot died
    public static boolean hasRespawned = false; // flag to track if the bot has respawned before or not
    public static int botSpawnCount = 0;
    private static Vec3d lastSpawnPosition = null;
    private static RegistryKey<World> lastSpawnWorld = null;
    private static float lastSpawnYaw = 0.0F;
    private static float lastSpawnPitch = 0.0F;
    private static String lastBotName = null;
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
    private static final long DROP_SWEEP_COOLDOWN_MS = 4000L;
    private static volatile long lastDropSweepMs = 0L;
    private static final AtomicBoolean dropSweepInProgress = new AtomicBoolean(false);
    private static final long DROP_RETRY_COOLDOWN_MS = 15_000L;
    private static final Map<BlockPos, Long> dropRetryTimestamps = new ConcurrentHashMap<>();
    private static final double RL_MANUAL_NUDGE_DISTANCE_SQ = 4.0D;
    private static final double ALLY_DEFENSE_RADIUS = 12.0D;
    private static final Map<UUID, CommandState> COMMAND_STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_RL_SAMPLE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SUFFOCATION_ALERT_TICK = new ConcurrentHashMap<>();
    private static final long SUFFOCATION_ALERT_COOLDOWN_TICKS = 100L;
    private static long lastBurialScanTick = Long.MIN_VALUE;
    private static volatile boolean externalOverrideActive = false;
    private static volatile boolean pendingBotRespawn = false;
    public enum CombatStyle {
        AGGRESSIVE,
        EVASIVE
    }
    private static CombatStyle combatStyle = CombatStyle.AGGRESSIVE;

    public enum Mode {
        IDLE,
        FOLLOW,
        GUARD,
        STAY,
        RETURNING_BASE
    }
    private static long lastRespawnHandledTick = -1;

    private static void debugRL(String message) {
        if (DEBUG_RL) {
            LOGGER.debug(message);
        }
    }

    private static final class CommandState {
        Mode mode = Mode.IDLE;
        UUID followTargetUuid;
        Vec3d guardCenter;
        double guardRadius = 6.0D;
        Vec3d baseTarget;
        boolean assistAllies;
        boolean shieldRaised;
        long shieldDecisionTick;
    }

    public static boolean throttleTraining(ServerPlayerEntity bot, boolean urgent) {
        if (bot == null || bot.getCommandSource().getServer() == null) {
            return true;
        }
        int interval = urgent ? 2 : 20; // urgent ~0.1s, passive ~1s (assuming 20tps)
        long now = bot.getCommandSource().getServer().getTicks();
        long last = LAST_RL_SAMPLE_TICK.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
        if (now - last < interval) {
            return false;
        }
        LAST_RL_SAMPLE_TICK.put(bot.getUuid(), now);
        return true;
    }

    private static CommandState stateFor(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        return COMMAND_STATES.computeIfAbsent(bot.getUuid(), id -> new CommandState());
    }

    private static CommandState stateFor(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return COMMAND_STATES.computeIfAbsent(uuid, id -> new CommandState());
    }

    private static CommandState primaryState() {
        if (registeredBotUuid != null) {
            return stateFor(registeredBotUuid);
        }
        Iterator<UUID> iterator = REGISTERED_BOTS.iterator();
        if (iterator.hasNext()) {
            return stateFor(iterator.next());
        }
        return null;
    }

    private static void setMode(ServerPlayerEntity bot, Mode mode) {
        CommandState state = stateFor(bot);
        if (state != null) {
            state.mode = mode;
        }
        if (bot != null && registeredBotUuid != null && bot.getUuid().equals(registeredBotUuid)) {
            CommandState primary = primaryState();
            if (primary != null) {
                primary.mode = mode;
            }
        }
    }

    private static Mode getMode(ServerPlayerEntity bot) {
        CommandState state = stateFor(bot);
        return state != null ? state.mode : Mode.IDLE;
    }

    private static void setFollowTarget(ServerPlayerEntity bot, UUID targetUuid) {
        CommandState state = stateFor(bot);
        if (state != null) {
            state.followTargetUuid = targetUuid;
        }
        if (bot != null && registeredBotUuid != null && bot.getUuid().equals(registeredBotUuid)) {
            CommandState primary = primaryState();
            if (primary != null) {
                primary.followTargetUuid = targetUuid;
            }
        }
    }

    private static UUID getFollowTargetFor(ServerPlayerEntity bot) {
        CommandState state = stateFor(bot);
        return state != null ? state.followTargetUuid : null;
    }

    private static void clearState(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        COMMAND_STATES.remove(bot.getUuid());
    }

    private static void setGuardState(ServerPlayerEntity bot, Vec3d center, double radius) {
        CommandState state = stateFor(bot);
        if (state != null) {
            state.guardCenter = center;
            state.guardRadius = radius;
        }
        if (bot != null && registeredBotUuid != null && bot.getUuid().equals(registeredBotUuid)) {
            CommandState primary = primaryState();
            if (primary != null) {
                primary.guardCenter = center;
                primary.guardRadius = radius;
            }
        }
    }

    private static Vec3d getGuardCenter(ServerPlayerEntity bot) {
        CommandState state = stateFor(bot);
        return state != null ? state.guardCenter : null;
    }

    private static double getGuardRadius(ServerPlayerEntity bot) {
        CommandState state = stateFor(bot);
        return state != null ? state.guardRadius : 6.0D;
    }

    private static void setBaseTarget(ServerPlayerEntity bot, Vec3d base) {
        CommandState state = stateFor(bot);
        if (state != null) {
            state.baseTarget = base;
        }
        if (bot != null && registeredBotUuid != null && bot.getUuid().equals(registeredBotUuid)) {
            CommandState primary = primaryState();
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
        CommandState state = stateFor(bot);
        return state != null ? state.baseTarget : null;
    }

    private static void setAssistAllies(ServerPlayerEntity bot, boolean enable) {
        CommandState state = stateFor(bot);
        if (state != null) {
            state.assistAllies = enable;
        }
        if (bot != null && registeredBotUuid != null && bot.getUuid().equals(registeredBotUuid)) {
            CommandState primary = primaryState();
            if (primary != null) {
                primary.assistAllies = enable;
            }
        }
    }

    private static boolean isAssistAllies(ServerPlayerEntity bot) {
        CommandState state = stateFor(bot);
        return state != null && state.assistAllies;
    }

    private static void setShieldRaised(ServerPlayerEntity bot, boolean raised) {
        CommandState state = stateFor(bot);
        if (state != null) {
            state.shieldRaised = raised;
        }
        if (bot != null && registeredBotUuid != null && bot.getUuid().equals(registeredBotUuid)) {
            CommandState primary = primaryState();
            if (primary != null) {
                primary.shieldRaised = raised;
            }
        }
    }

    private static boolean isShieldRaised(ServerPlayerEntity bot) {
        CommandState state = stateFor(bot);
        return state != null && state.shieldRaised;
    }

    private static long getShieldDecisionTick(ServerPlayerEntity bot) {
        CommandState state = stateFor(bot);
        return state != null ? state.shieldDecisionTick : 0L;
    }

    private static void setShieldDecisionTick(ServerPlayerEntity bot, long tick) {
        CommandState state = stateFor(bot);
        if (state != null) {
            state.shieldDecisionTick = tick;
        }
        if (bot != null && registeredBotUuid != null && bot.getUuid().equals(registeredBotUuid)) {
            CommandState primary = primaryState();
            if (primary != null) {
                primary.shieldDecisionTick = tick;
            }
        }
    }

    public BotEventHandler(MinecraftServer server, ServerPlayerEntity bot) {
        if (server != null && bot != null && (registeredBotUuid == null || registeredBotUuid.equals(bot.getUuid()))) {
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
        if (world != null) {
            lastSpawnWorld = world.getRegistryKey();
        }
        lastSpawnPosition = pos;
        lastSpawnYaw = yaw;
        lastSpawnPitch = pitch;
    }

    public static void ensureBotPresence(MinecraftServer srv) {
        if (srv == null || lastBotName == null) {
            return;
        }
        ServerPlayerEntity existing = null;
        if (registeredBotUuid != null) {
            existing = srv.getPlayerManager().getPlayer(registeredBotUuid);
        }
        if (existing == null) {
            Iterator<UUID> iterator = REGISTERED_BOTS.iterator();
            while (existing == null && iterator.hasNext()) {
                UUID candidateId = iterator.next();
                existing = srv.getPlayerManager().getPlayer(candidateId);
                if (existing != null) {
                    registeredBotUuid = candidateId;
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
        if (pendingBotRespawn) {
            return;
        }

        RegistryKey<World> worldKey = lastSpawnWorld != null ? lastSpawnWorld : World.OVERWORLD;
        ServerWorld world = srv.getWorld(worldKey);
        if (world == null) {
            world = srv.getOverworld();
            worldKey = World.OVERWORLD;
        }
        Vec3d spawn = lastSpawnPosition;
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
        final float yaw = lastSpawnYaw;
        final float pitch = lastSpawnPitch;
        pendingBotRespawn = true;
        UUID targetUuid = registeredBotUuid;
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
        REGISTERED_BOTS.add(candidate.getUuid());
        registeredBotUuid = candidate.getUuid();
        BotEventHandler.bot = candidate;
        stateFor(candidate);
        MinecraftServer srv = candidate.getCommandSource().getServer();
        if (srv != null && (BotEventHandler.server == null || BotEventHandler.server == srv)) {
            BotEventHandler.server = srv;
        }
        lastBotName = candidate.getName().getString();
        if (candidate.getEntityWorld() instanceof ServerWorld serverWorld) {
            rememberSpawn(serverWorld, new Vec3d(candidate.getX(), candidate.getY(), candidate.getZ()), candidate.getYaw(), candidate.getPitch());
        }
        pendingBotRespawn = false;
        net.shasankp000.GameAI.services.BotControlApplier.applyToBot(candidate);
    }

    public static void unregisterBot(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        REGISTERED_BOTS.remove(uuid);
        clearState(bot);
        LAST_RL_SAMPLE_TICK.remove(uuid);
        if (registeredBotUuid != null && registeredBotUuid.equals(uuid)) {
            registeredBotUuid = null;
            Iterator<UUID> iterator = REGISTERED_BOTS.iterator();
            if (iterator.hasNext()) {
                registeredBotUuid = iterator.next();
            }
        }
        if (BotEventHandler.bot != null && BotEventHandler.bot.getUuid().equals(uuid)) {
            BotEventHandler.bot = null;
        }
    }

    public static boolean isRegisteredBot(ServerPlayerEntity candidate) {
        return candidate != null && REGISTERED_BOTS.contains(candidate.getUuid());
    }

    public static List<ServerPlayerEntity> getRegisteredBots(MinecraftServer fallback) {
        MinecraftServer srv = server != null ? server : fallback;
        if (srv == null) {
            return List.of();
        }
        List<ServerPlayerEntity> bots = new ArrayList<>();
        for (UUID uuid : REGISTERED_BOTS) {
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
        if (bot != null) {
            engageImmediateThreats(bot);
        }
        if (externalOverrideActive) {
            LOGGER.debug("Skipping detectAndReact because external override is active.");
            return;
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


    public static State getCurrentState() {

        return BotEventHandler.currentState;

    }

    public void detectAndReactPlayMode(RLAgent rlAgentHook, QTable qTable) {
        if (externalOverrideActive) {
            LOGGER.debug("Skipping detectAndReactPlayMode because external override is active.");
            return;
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

        double distanceSq = currentPos.squaredDistanceTo(lastKnownPosition);
        if (distanceSq < 0.04) {
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
                LOGGER.info("Bot enclosed without active threats; attempting dig-out routine.");
                boolean cleared = BotActions.digOut(bot, true);
                if (cleared) {
                    failedBlockBreakAttempts = 0;
                    lastKnownPosition = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
                }
            }
            return;
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
        if (destinationWorld != null) {
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

        CommandState state = stateFor(bot);
        Mode mode = state != null ? state.mode : Mode.IDLE;
        List<Entity> augmentedHostiles = augmentHostiles(bot, hostileEntities);

        switch (mode) {
            case FOLLOW -> {
                return handleFollow(bot, state, server, augmentedHostiles);
            }
            case GUARD -> {
                return handleGuard(bot, state, nearbyEntities, augmentedHostiles);
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

    private static List<Entity> augmentHostiles(ServerPlayerEntity bot, List<Entity> baseHostiles) {
        List<Entity> base = baseHostiles == null ? Collections.emptyList() : baseHostiles;
        if (!isAssistAllies(bot)) {
            return base;
        }
        List<Entity> allyThreats = gatherAllyThreats(bot);
        if (allyThreats.isEmpty()) {
            return base;
        }
        Set<Integer> seen = new HashSet<>();
        List<Entity> combined = new ArrayList<>();
        for (Entity entity : base) {
            if (entity != null && entity.isAlive() && seen.add(entity.getId())) {
                combined.add(entity);
            }
        }
        for (Entity entity : allyThreats) {
            if (entity != null && entity.isAlive() && seen.add(entity.getId())) {
                combined.add(entity);
            }
        }
        return combined;
    }

    private static List<Entity> gatherAllyThreats(ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return Collections.emptyList();
        }
        ServerWorld botWorld = bot.getCommandSource().getWorld();
        if (botWorld == null) {
            return Collections.emptyList();
        }
        List<Entity> threats = new ArrayList<>();
        double radiusSq = ALLY_DEFENSE_RADIUS * ALLY_DEFENSE_RADIUS;
        for (UUID allyId : REGISTERED_BOTS) {
            if (allyId == null || allyId.equals(bot.getUuid())) {
                continue;
            }
            ServerPlayerEntity ally = server.getPlayerManager().getPlayer(allyId);
            if (ally == null || ally.isRemoved()) {
                continue;
            }
            if (ally.getCommandSource().getWorld() != botWorld) {
                continue;
            }
            if (ally.squaredDistanceTo(bot) > radiusSq) {
                continue;
            }
            threats.addAll(findHostilesAround(ally, 8.0D));
        }
        return threats;
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
        setMode(bot, Mode.FOLLOW);
        clearGuard(bot);
        clearBase(bot);
        sendBotMessage(bot, "Following " + target.getName().getString() + ".");
        return "Now following " + target.getName().getString() + ".";
    }

    public static String stopFollowing(ServerPlayerEntity bot) {
        if (bot != null) {
            registerBot(bot);
        }
        boolean wasFollowing = getMode(bot) == Mode.FOLLOW && getFollowTargetFor(bot) != null;
        setFollowTarget(bot, null);
        if (bot != null) {
            BotActions.stop(bot);
        }
        setMode(bot, Mode.IDLE);
        if (bot != null && wasFollowing) {
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
        sendBotMessage(bot, String.format(Locale.ROOT, "Guarding this area (radius %.1f blocks).", getGuardRadius(bot)));
        return "Guarding the area.";
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
        CommandState state = primaryState();
        return state != null ? state.mode : Mode.IDLE;
    }

    public static Mode getCurrentMode(ServerPlayerEntity bot) {
        return getMode(bot);
    }

    public static boolean isPassiveMode() {
        Mode mode = getCurrentMode();
        return mode == Mode.IDLE || mode == Mode.STAY || mode == Mode.GUARD;
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
        CommandState state = primaryState();
        return state != null ? state.guardCenter : null;
    }

    public static double getGuardRadiusValue() {
        CommandState state = primaryState();
        return state != null ? state.guardRadius : 6.0D;
    }

    public static ServerPlayerEntity getFollowTarget() {
        CommandState state = primaryState();
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
        CommandState state = primaryState();
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

    private static boolean handleFollow(ServerPlayerEntity bot, CommandState state, MinecraftServer server, List<Entity> hostileEntities) {
        UUID targetUuid = state != null ? state.followTargetUuid : null;
        ServerPlayerEntity target = targetUuid != null && server != null
                ? server.getPlayerManager().getPlayer(targetUuid)
                : null;
        if (target == null) {
            setMode(bot, Mode.IDLE);
            setFollowTarget(bot, null);
            sendBotMessage(bot, "Follow target lost. Returning to idle.");
            return false;
        }

        List<Entity> augmentedHostiles = new ArrayList<>(hostileEntities);
        if (isAssistAllies(bot)) {
            augmentedHostiles.addAll(findHostilesAround(target, 8.0D));
        }

        if (!augmentedHostiles.isEmpty() && engageHostiles(bot, server, augmentedHostiles)) {
            return true;
        }

        lowerShieldTracking(bot);

        Vec3d targetPos = positionOf(target);
        double distanceSq = bot.squaredDistanceTo(targetPos);
        if (distanceSq > 100) {
            bot.refreshPositionAndAngles(targetPos.x, targetPos.y, targetPos.z, target.getYaw(), target.getPitch());
            return true;
        }
        moveToward(bot, targetPos, 3.0D, true);
        return true;
    }

    public static void collectNearbyDrops(ServerPlayerEntity bot, double radius) {
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
        boolean trainingMode = net.shasankp000.Commands.modCommandRegistry.isTrainingMode;

        if (trainingMode) {
            ItemEntity closest = drops.stream()
                    .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                    .orElse(null);
            if (closest == null) {
                return;
            }
            double distanceSq = bot.squaredDistanceTo(closest);
            if (distanceSq > RL_MANUAL_NUDGE_DISTANCE_SQ) {
                dropRetryTimestamps.put(closest.getBlockPos().toImmutable(), now);
                return;
            }
            if (!dropSweepInProgress.compareAndSet(false, true)) {
                return;
            }
            BlockPos dropPos = closest.getBlockPos().toImmutable();
            boolean nudged = false;
            long completionTime = System.currentTimeMillis();
            try {
                nudged = DropSweeper.attemptManualNudge(bot, closest, dropPos);
                completionTime = System.currentTimeMillis();
                lastDropSweepMs = completionTime;
                if (nudged) {
                    dropRetryTimestamps.remove(dropPos);
                    return;
                }
                dropRetryTimestamps.put(dropPos, completionTime);
                CompletableFuture<Void> sweepFuture = CompletableFuture.runAsync(() -> {
                    try {
                        ServerCommandSource source = bot.getCommandSource().withSilent().withMaxLevel(4);
                        DropSweeper.sweep(source, radius, verticalRange, 2, 3000L);
                    } catch (Exception sweepError) {
                        LOGGER.warn("Training drop sweep failed near {}: {}", dropPos, sweepError.getMessage());
                    }
                });
                try {
                    sweepFuture.get(3500, TimeUnit.MILLISECONDS);
                } catch (Exception waitError) {
                    LOGGER.warn("Training drop sweep wait interrupted: {}", waitError.getMessage());
                }
            } finally {
                dropSweepInProgress.set(false);
            }
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
        boolean trainingModeActive = net.shasankp000.Commands.modCommandRegistry.isTrainingMode;
        boolean needOverride = !trainingModeActive && !isExternalOverrideActive();
        if (needOverride) {
            setExternalOverrideActive(true);
        }
        final boolean activatedOverride = needOverride;
        CompletableFuture<Void> sweepFuture = CompletableFuture.runAsync(() -> {
            try {
                ServerCommandSource source = bot.getCommandSource().withSilent().withMaxLevel(4);
                DropSweeper.sweep(source, radius, verticalRange, 4, 4000L);
            } catch (Exception sweepError) {
                LOGGER.warn("Drop sweep failed: {}", sweepError.getMessage(), sweepError);
            }
        });
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
            if (activatedOverride) {
                setExternalOverrideActive(false);
            }
        }));
    }

    private static boolean handleGuard(ServerPlayerEntity bot, CommandState state, List<Entity> nearbyEntities, List<Entity> hostileEntities) {
        Vec3d center = state != null ? state.guardCenter : null;
        double radius = state != null ? state.guardRadius : 6.0D;
        if (center == null) {
            center = positionOf(bot);
            setGuardState(bot, center, radius);
        }
        MinecraftServer server = bot.getCommandSource().getServer();

        if (!hostileEntities.isEmpty() && engageHostiles(bot, server, hostileEntities)) {
            return true;
        }

        lowerShieldTracking(bot);

        Entity nearestItem = findNearestItem(bot, nearbyEntities, radius);
        if (nearestItem != null) {
            collectNearbyDrops(bot, Math.max(radius, 4.0D));
            return true;
        }

        double distanceFromCenter = positionOf(bot).distanceTo(center);
        if (distanceFromCenter > radius) {
            moveToward(bot, center, 2.0D, false);
            return true;
        }

        if (RANDOM.nextDouble() < 0.02) {
            Vec3d wanderTarget = randomPointWithin(center, radius * 0.6D);
            moveToward(bot, wanderTarget, 2.0D, false);
            return true;
        }

        BotActions.stop(bot);
        return true;
    }

    private static boolean handleReturnToBase(ServerPlayerEntity bot, CommandState state) {
        Vec3d base = state != null ? state.baseTarget : null;
        if (base == null) {
            setMode(bot, Mode.IDLE);
            return false;
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
        BotActions.moveForward(bot);
        if (target.y - pos.y > 0.6D) {
            BotActions.jump(bot);
        } else {
            BotActions.autoJumpIfNeeded(bot);
        }
    }

    private static Entity findNearestItem(ServerPlayerEntity bot, List<Entity> entities, double radius) {
        return entities.stream()
                .filter(entity -> entity instanceof net.minecraft.entity.ItemEntity)
                .filter(entity -> entity.squaredDistanceTo(bot) <= radius * radius)
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(bot)))
                .orElse(null);
    }

    private static List<Entity> findHostilesAround(ServerPlayerEntity player, double radius) {
        return player.getEntityWorld().getOtherEntities(player, player.getBoundingBox().expand(radius), EntityUtil::isHostile);
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
        if (bot == null || bot.isRemoved()) {
            return false;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        if (world == null) {
            return false;
        }
        
        // Only intervene if bot is actively taking suffocation damage
        // Don't break blocks just from collision/proximity
        boolean takingSuffocationDamage = tookRecentSuffocation(bot);
        if (!takingSuffocationDamage) {
            LAST_SUFFOCATION_ALERT_TICK.remove(bot.getUuid());
            return false;
        }

        // Targeted response: break only the block causing suffocation
        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();
        boolean hasTool = ensureRescueTool(bot, world, head);
        boolean cleared = breakSuffocatingBlock(bot, world, head, feet);
        
        if (!hasTool || !cleared) {
            alertSuffocation(bot);
        } else {
            LAST_SUFFOCATION_ALERT_TICK.remove(bot.getUuid());
        }
        return true;
    }
    
    /**
     * Breaks only the specific block causing suffocation, not surrounding blocks.
     * Tries head position first (most common), then feet if still suffocating.
     */
    private static boolean breakSuffocatingBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos head, BlockPos feet) {
        // Try breaking head block first (most common suffocation point)
        BlockState headState = world.getBlockState(head);
        if (!headState.isAir() && !headState.isOf(Blocks.BEDROCK)) {
            if (BotActions.breakBlockAt(bot, head, true)) {
                return true;
            }
        }
        
        // If head is clear but still suffocating, try feet
        BlockState feetState = world.getBlockState(feet);
        if (!feetState.isAir() && !feetState.isOf(Blocks.BEDROCK)) {
            return BotActions.breakBlockAt(bot, feet, true);
        }
        
        return false;
    }

    private static boolean tookRecentSuffocation(ServerPlayerEntity bot) {
        DamageSource recent = bot.getRecentDamageSource();
        if (recent == null) {
            return false;
        }
        RegistryKey<net.minecraft.entity.damage.DamageType> inWall = DamageTypes.IN_WALL;
        return recent.isOf(inWall);
    }

    private static boolean ensureRescueTool(ServerPlayerEntity bot, ServerWorld world, BlockPos center) {
        List<BlockPos> samples = List.of(center, center.up(), center.down());
        for (BlockPos sample : samples) {
            BlockState state = world.getBlockState(sample);
            String keyword = preferredToolKeyword(state);
            if (keyword != null && BotActions.selectBestTool(bot, keyword, "sword")) {
                return true;
            }
        }
        return BotActions.selectBestTool(bot, "pickaxe", "sword")
                || BotActions.selectBestTool(bot, "shovel", "sword")
                || BotActions.selectBestTool(bot, "axe", "sword");
    }

    private static String preferredToolKeyword(BlockState state) {
        if (state == null || state.isAir()) {
            return null;
        }
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return "pickaxe";
        }
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            return "shovel";
        }
        if (state.isIn(BlockTags.AXE_MINEABLE)) {
            return "axe";
        }
        return null;
    }

    private static void alertSuffocation(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        ServerCommandSource source = bot.getCommandSource();
        MinecraftServer srv = source != null ? source.getServer() : null;
        if (srv == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        long now = srv.getTicks();
        long last = LAST_SUFFOCATION_ALERT_TICK.getOrDefault(uuid, Long.MIN_VALUE);
        if (now - last < SUFFOCATION_ALERT_COOLDOWN_TICKS) {
            return;
        }
        ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), "I'm suffocating!");
        LAST_SUFFOCATION_ALERT_TICK.put(uuid, now);
    }

    public static void tickBurialRescue(MinecraftServer server) {
        if (server == null || REGISTERED_BOTS.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        if (lastBurialScanTick == now) {
            return;
        }
        lastBurialScanTick = now;
        for (UUID uuid : REGISTERED_BOTS) {
            ServerPlayerEntity candidate = server.getPlayerManager().getPlayer(uuid);
            if (candidate != null && candidate.isAlive()) {
                rescueFromBurial(candidate);
            }
        }
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

        QTableStorage.saveQTable(qTable, null);
        QTableStorage.saveEpsilon(rlAgentHook.getEpsilon(), qTableDir + "/epsilon.bin");
        LOGGER.info("Persisted Q-table and epsilon after action {}", chosenAction);

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
}
