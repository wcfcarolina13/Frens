package net.shasankp000.GameAI;

import net.shasankp000.EntityUtil;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.ServerTask;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
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
import net.shasankp000.WorldUitls.isBlockItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static net.shasankp000.GameAI.State.isStateConsistent;


public class BotEventHandler {
    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    private static MinecraftServer server = null;
    public static ServerPlayerEntity bot = null;
    private static UUID registeredBotUuid = null;
    public static final String qTableDir = LauncherEnvironment.getStorageDirectory("qtable_storage");
    private static final Object monitorLock = new Object();
    private static boolean isExecuting = false;
    private static final double DEFAULT_RISK_APPETITE = 0.5; // Default value upon respawn
    public static boolean botDied = false; // Flag to track if the bot died
    public static boolean hasRespawned = false; // flag to track if the bot has respawned before or not
    public static int botSpawnCount = 0;
    private static State currentState = null;
    private static Vec3d lastKnownPosition = null;
    private static int stationaryTicks = 0;
    private static final int STUCK_TICK_THRESHOLD = 12;
    private static boolean spartanModeActive = false;
    private static int failedBlockBreakAttempts = 0;
    private static final int MAX_FAILED_BLOCK_ATTEMPTS = 4;
    private static Vec3d lastSafePosition = null;
    private static final Random RANDOM = new Random();
    private static Mode currentMode = Mode.IDLE;
    private static UUID followTargetUuid = null;
    private static Vec3d guardCenter = null;
    private static double guardRadius = 6.0D;
    private static Vec3d baseTarget = null;
    private static boolean assistAllies = false;
    private static boolean shieldRaised = false;
    private static long shieldDecisionTick = 0L;

    private enum Mode {
        IDLE,
        FOLLOW,
        GUARD,
        STAY,
        RETURNING_BASE
    }
    private static long lastRespawnHandledTick = -1;

    public BotEventHandler(MinecraftServer server, ServerPlayerEntity bot) {
        if (server != null && bot != null && (registeredBotUuid == null || registeredBotUuid.equals(bot.getUuid()))) {
            registerBot(bot);
        }
    }

    public static void registerBot(ServerPlayerEntity candidate) {
        if (candidate == null) {
            return;
        }
        registeredBotUuid = candidate.getUuid();
        BotEventHandler.bot = candidate;
        MinecraftServer srv = candidate.getCommandSource().getServer();
        if (srv != null) {
            BotEventHandler.server = srv;
        }
    }

    public static boolean isRegisteredBot(ServerPlayerEntity candidate) {
        return registeredBotUuid != null && candidate != null && candidate.getUuid().equals(registeredBotUuid);
    }

    private static State initializeBotState(QTable qTable) {
        State initialState = null;

        if (qTable == null || qTable.getTable().isEmpty()) {
            System.out.println("No initial state available. Q-table is empty.");
        } else {
            System.out.println("Loaded Q-table: Total state-action pairs = " + qTable.getTable().size());

            // Get the most recent state from the Q-table
            StateActionPair recentPair = qTable.getTable().keySet().iterator().next();
            initialState = recentPair.getState();

            System.out.println("Setting initial state to: " + initialState);
        }

        return initialState;
    }

    public void detectAndReact(RLAgent rlAgentHook, double distanceToHostileEntity, QTable qTable) throws IOException {
        synchronized (monitorLock) {
            if (isExecuting) {
                System.out.println("Executing detection code");
                return; // Skip if already executing
            } else {
                System.out.println("No immediate threats detected");
                // Reset state when no threats are detected
                BotEventHandler.currentState = createInitialState(bot);
            }
            isExecuting = true;
        }

        try {
            System.out.println("Distance from danger zone: " + DangerZoneDetector.detectDangerZone(bot, 10, 10 , 10) + " blocks");

            List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 10); // Example bounding box size
            List<Entity> hostileEntities = nearbyEntities.stream()
                    .filter(EntityUtil::isHostile)
                    .toList();

            LOGGER.info("detectAndReact triggered: hostiles={}, trainingMode={}, alreadyExecuting={}",
                    hostileEntities.size(), net.shasankp000.Commands.modCommandRegistry.isTrainingMode, isExecuting);

            EnvironmentSnapshot environmentSnapshot = analyzeEnvironment(bot);
            handleSpartanMode(bot, environmentSnapshot);
            updateStuckTracker(bot, environmentSnapshot);


            BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);

            List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

            boolean hasSculkNearby = nearbyBlocks.stream()
                    .anyMatch(block -> block.contains("Sculk Sensor") || block.contains("Sculk Shrieker"));
            System.out.println("Nearby blocks: " + nearbyBlocks);

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
                        System.out.println("Merged values from last known state.");
                        currentState.setRiskMap(lastKnownState.getRiskMap());
                        currentState.setPodMap(lastKnownState.getPodMap());
                    }
                } else {
                    currentState = initializeBotState(qTable);

                    System.out.println("Created initial state");
                }

                if (botSpawnCount == 0) {
                    currentState = createInitialState(bot);
                }

                performLearningStep(rlAgentHook, qTable, currentState, nearbyEntitiesList, nearbyBlocks,
                        distanceToHostileEntity, time, dimension);

            } else if ((DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) <= 5.0 && DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) > 0.0) || hasSculkNearby) {
                System.out.println("Danger zone detected within 5 blocks");

                System.out.println("Triggered handler for danger zone case.");

                List<EntityDetails> nearbyEntitiesList = nearbyEntities.stream()
                        .map(entity -> EntityDetails.from(bot, entity))
                        .toList();

                State currentState;

                if (hasRespawned && botDied) {
                    State lastKnownState = QTableStorage.loadLastKnownState(qTableDir + "/lastKnownState.bin");
                    currentState = createInitialState(bot);
                    BotEventHandler.botDied = false;

                    if (isStateConsistent(lastKnownState, currentState)) {
                        System.out.println("Merged values from last known state.");
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
                System.out.println("Passive environment detected. Running exploratory step.");

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
                System.out.println("Resetting handler trigger flag to: " + false);
            }
        }
    }


    public static State getCurrentState() {

        return BotEventHandler.currentState;

    }

    public void detectAndReactPlayMode(RLAgent rlAgentHook, QTable qTable) {
        synchronized (monitorLock) {
            if (isExecuting) {
                System.out.println("Already executing detection code, skipping...");
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
                    System.out.println("Play Mode - Chosen action: " + chosenAction);

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
                    System.out.println("Play Mode - Chosen action: " + chosenAction);

                    // Execute action
                    executeAction(chosenAction);
                }


            }
        } finally {
            synchronized (monitorLock) {
                System.out.println("Resetting handler trigger flag.");
                isExecuting = false;
                AutoFaceEntity.isHandlerTriggered = false; // Reset the trigger flag
                AutoFaceEntity.setBotExecutingTask(false);
            }
        }
    }

    private static void executeAction(StateActions.Action chosenAction) {
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
            case STAY -> System.out.println("Performing action: Stay and do nothing");
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

        boolean enclosed = solidNeighbors >= 5 || (!escapeRoute && !headroom);
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

    private static void handleSpartanMode(ServerPlayerEntity bot, EnvironmentSnapshot snapshot) {
        boolean candidate = isSpartanCandidate(snapshot) || failedBlockBreakAttempts >= MAX_FAILED_BLOCK_ATTEMPTS;
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
            handleSpartanMode(bot, snapshot);
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

        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
                bot.getName().getString() + " has regrouped and is ready to re-engage.");
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

        switch (currentMode) {
            case FOLLOW -> {
                return handleFollow(bot, server, hostileEntities);
            }
            case GUARD -> {
                return handleGuard(bot, nearbyEntities, hostileEntities);
            }
            case STAY -> {
                BotActions.stop(bot);
                return true;
            }
            case RETURNING_BASE -> {
                return handleReturnToBase(bot);
            }
            default -> {
                return false;
            }
        }
    }

    public static String setFollowMode(ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (target == null) {
            return "Unable to follow — target not found.";
        }
        registerBot(bot);
        followTargetUuid = target.getUuid();
        currentMode = Mode.FOLLOW;
        guardCenter = null;
        baseTarget = null;
        sendBotMessage(bot, "Following " + target.getName().getString() + ".");
        return "Now following " + target.getName().getString() + ".";
    }

    public static String setGuardMode(ServerPlayerEntity bot, double radius) {
        registerBot(bot);
        guardCenter = positionOf(bot);
        guardRadius = Math.max(3.0D, radius);
        currentMode = Mode.GUARD;
        followTargetUuid = null;
        baseTarget = null;
        sendBotMessage(bot, String.format(Locale.ROOT, "Guarding this area (radius %.1f blocks).", guardRadius));
        return "Guarding the area.";
    }

    public static String setStayMode(ServerPlayerEntity bot) {
        registerBot(bot);
        currentMode = Mode.STAY;
        followTargetUuid = null;
        guardCenter = positionOf(bot);
        baseTarget = null;
        sendBotMessage(bot, "Staying put here.");
        return "Bot will hold position.";
    }

    public static String setReturnToBase(ServerPlayerEntity bot, Vec3d base) {
        registerBot(bot);
        if (base == null) {
            return "No base location available.";
        }
        baseTarget = base;
        currentMode = Mode.RETURNING_BASE;
        followTargetUuid = null;
        guardCenter = null;
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
        assistAllies = enable;
        String message = enable ? "Engaging threats against allies." : "Standing down unless attacked.";
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
        return currentMode;
    }

    public static boolean isPassiveMode() {
        return currentMode == Mode.IDLE || currentMode == Mode.STAY || currentMode == Mode.GUARD;
    }

    private static boolean handleFollow(ServerPlayerEntity bot, MinecraftServer server, List<Entity> hostileEntities) {
        ServerPlayerEntity target = null;
        if (followTargetUuid != null && server != null) {
            target = server.getPlayerManager().getPlayer(followTargetUuid);
        }
        if (target == null) {
            currentMode = Mode.IDLE;
            followTargetUuid = null;
            sendBotMessage(bot, "Follow target lost. Returning to idle.");
            return false;
        }

        List<Entity> augmentedHostiles = new ArrayList<>(hostileEntities);
        if (assistAllies) {
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

    private static boolean handleGuard(ServerPlayerEntity bot, List<Entity> nearbyEntities, List<Entity> hostileEntities) {
        if (guardCenter == null) {
        guardCenter = positionOf(bot);
        }

        if (!hostileEntities.isEmpty() && engageHostiles(bot, server, hostileEntities)) {
            return true;
        }

        lowerShieldTracking(bot);

        Entity nearestItem = findNearestItem(bot, nearbyEntities, guardRadius);
        if (nearestItem != null) {
            moveToward(bot, positionOf(nearestItem), 1.5D, false);
            return true;
        }

        double distanceFromCenter = positionOf(bot).distanceTo(guardCenter);
        if (distanceFromCenter > guardRadius) {
            moveToward(bot, guardCenter, 2.0D, false);
            return true;
        }

        if (RANDOM.nextDouble() < 0.02) {
            Vec3d wanderTarget = randomPointWithin(guardCenter, guardRadius * 0.6D);
            moveToward(bot, wanderTarget, 2.0D, false);
            return true;
        }

        BotActions.stop(bot);
        return true;
    }

    private static boolean handleReturnToBase(ServerPlayerEntity bot) {
        if (baseTarget == null) {
            currentMode = Mode.IDLE;
            return false;
        }

        double distance = positionOf(bot).distanceTo(baseTarget);
        if (distance <= 3.0D) {
            currentMode = Mode.STAY;
            sendBotMessage(bot, "Arrived at base. Holding position.");
            baseTarget = null;
            return true;
        }

        moveToward(bot, baseTarget, 2.0D, false);
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
        boolean projectileThreat = closest.getType().isIn(EntityTypeTags.SKELETONS) || closest.getName().getString().toLowerCase(Locale.ROOT).contains("pillager");
        boolean multipleThreats = hostileEntities.size() > 1;
        boolean lowHealth = bot.getHealth() <= bot.getMaxHealth() * 0.5F;
        boolean shouldBlock = (projectileThreat || multipleThreats || lowHealth) && distance <= 4.5D;

        if (hasRanged && distance >= 4.0D && closest instanceof LivingEntity living) {
            if (BotActions.performRangedAttack(bot, living, server.getTicks())) {
                return true;
            }
        } else {
            BotActions.resetRangedState(bot);
        }

        if (distance > 3.0D) {
            lowerShieldTracking(bot);
            moveToward(bot, positionOf(closest), 2.5D, true);
        } else if (shouldBlock) {
            long now = bot.getCommandSource().getServer().getTicks();
            if (!shieldRaised) {
                if (BotActions.raiseShield(bot)) {
                    shieldRaised = true;
                    shieldDecisionTick = now;
                }
                return true;
            }

            if (now - shieldDecisionTick >= 15) {
                lowerShieldTracking(bot);
                BotActions.selectBestWeapon(bot);
                BotActions.attackNearest(bot, hostileEntities);
                shieldDecisionTick = now;
            }
            return true;
        } else {
            lowerShieldTracking(bot);
            BotActions.selectBestWeapon(bot);
            BotActions.attackNearest(bot, hostileEntities);
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

    private static void lowerShieldTracking(ServerPlayerEntity bot) {
        if (shieldRaised) {
            BotActions.lowerShield(bot);
            shieldRaised = false;
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
                actionPodMap.getOrDefault(chosenAction, 0.0)
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
                System.out.println("Performing action: move forward");
                BotActions.moveForward(bot);
                AutoFaceEntity.isBotMoving = true;
            }
            case "moveBackward" -> {
                System.out.println("Performing action: move backward");
                BotActions.moveBackward(bot);
                AutoFaceEntity.isBotMoving = true;
            }
            case "turnLeft" -> {
                System.out.println("Performing action: turn left");
                BotActions.turnLeft(bot);
            }
            case "turnRight" -> {
                System.out.println("Performing action: turn right");
                BotActions.turnRight(bot);
            }
            case "jump" -> {
                System.out.println("Performing action: jump");
                BotActions.jump(bot);
            }
            case "jumpForward" -> {
                System.out.println("Performing action: jump forward");
                BotActions.jumpForward(bot);
            }
            case "sneak" -> {
                System.out.println("Performing action: sneak");
                BotActions.sneak(bot, true);
            }
            case "sprint" -> {
                System.out.println("Performing action: sprint");
                BotActions.sprint(bot, true);
            }
            case "unsneak" -> {
                System.out.println("Performing action: unsneak");
                BotActions.sneak(bot, false);
            }
            case "unsprint" -> {
                System.out.println("Performing action: unsprint");
                BotActions.sprint(bot, false);
            }
            case "stopMoving" -> {
                System.out.println("Performing action: stop moving");
                BotActions.stop(bot);
                AutoFaceEntity.isBotMoving = false;
            }
            case "useItem" -> {
                System.out.println("Performing action: use currently selected item");
                BotActions.useSelectedItem(bot);
            }
            case "attack" -> {
                System.out.println("Performing action: attack");
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
                    System.out.println("No hostile entities available to attack.");
                }
            }
            case "hotbar1" -> {
                System.out.println("Performing action: Select hotbar slot 1");
                BotActions.selectHotbarSlot(bot, 0);
            }
            case "hotbar2" -> {
                System.out.println("Performing action: Select hotbar slot 2");
                BotActions.selectHotbarSlot(bot, 1);
            }
            case "hotbar3" -> {
                System.out.println("Performing action: Select hotbar slot 3");
                BotActions.selectHotbarSlot(bot, 2);
            }
            case "hotbar4" -> {
                System.out.println("Performing action: Select hotbar slot 4");
                BotActions.selectHotbarSlot(bot, 3);
            }
            case "hotbar5" -> {
                System.out.println("Performing action: Select hotbar slot 5");
                BotActions.selectHotbarSlot(bot, 4);
            }
            case "hotbar6" -> {
                System.out.println("Performing action: Select hotbar slot 6");
                BotActions.selectHotbarSlot(bot, 5);
            }
            case "hotbar7" -> {
                System.out.println("Performing action: Select hotbar slot 7");
                BotActions.selectHotbarSlot(bot, 6);
            }
            case "hotbar8" -> {
                System.out.println("Performing action: Select hotbar slot 8");
                BotActions.selectHotbarSlot(bot, 7);
            }
            case "hotbar9" -> {
                System.out.println("Performing action: Select hotbar slot 9");
                BotActions.selectHotbarSlot(bot, 8);
            }
            case "breakBlock" -> {
                System.out.println("Performing action: break block ahead");
                boolean success = BotActions.breakBlockAhead(bot);
                registerBlockBreakResult(bot, success);
                if (!success) {
                    System.out.println("No suitable block to break ahead.");
                }
            }
            case "placeSupportBlock" -> {
                System.out.println("Performing action: place support block");
                boolean success = BotActions.placeSupportBlock(bot);
                if (!success) {
                    registerBlockBreakResult(bot, false);
                } else {
                    failedBlockBreakAttempts = 0;
                }
                if (!success) {
                    System.out.println("Unable to place support block (no block or blocked space).");
                }
            }
            case "escapeStairs" -> {
                System.out.println("Performing action: escape stairs");
                BotActions.escapeStairs(bot);
            }
            default -> System.out.println("Invalid action");
        }
    }

    public static boolean isSpartanModeActive() {
        return spartanModeActive;
    }
}
