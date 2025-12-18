package net.shasankp000.Entity;

import net.shasankp000.Entity.RayCasting;
import net.shasankp000.EntityUtil;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
// import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Database.QTable;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.HealingService;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.RLAgent;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.Commands.modCommandRegistry;
import net.shasankp000.Database.QTableStorage;
import net.shasankp000.PlayerUtils.BlockDistanceLimitedSearch;
import net.shasankp000.PlayerUtils.CombatInventoryManager;
import net.shasankp000.PlayerUtils.blockDetectionUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shasankp000.DangerZoneDetector.DangerZoneDetector;
import net.shasankp000.PathFinding.PathTracer;

import net.minecraft.registry.tag.FluidTags;
import net.minecraft.fluid.FluidState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoFaceEntity {

    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    private static final double BOUNDING_BOX_SIZE = 10.0; // Detection range in blocks
    private static final long LOOP_INTERVAL_MS = 200; // Interval for behaviour loop
    private static final ExecutorService executor3 = Executors.newSingleThreadExecutor();
    private static final long EMOTE_COOLDOWN_TICKS = 20L * 45; // ~45 seconds
    private static final long HOSTILE_COOLDOWN_TICKS = 20L * 15; // wait 15 seconds after combat
    private static final double EMOTE_MAX_DISTANCE = 6.0D;
    private static final double EMOTE_LOOK_THRESHOLD = 0.65D;
    private static final Random EMOTE_RANDOM = new Random();
    private static final long DANGER_MESSAGE_COOLDOWN_MS = 4000L;
    public static boolean botBusy;
    private static boolean botExecutingTask;
    public static boolean hostileEntityInFront;
    public static boolean isHandlerTriggered;
    private static long lastBusyLogMs = 0L;
    private static long lastEmoteTick = -EMOTE_COOLDOWN_TICKS;
    private static long lastHostileTick = 0L;
    private static boolean isWorldTickListenerActive = true; // Flag to control execution
    private static QTable qTable;
    public static RLAgent rlAgent;
    public static List<Entity> hostileEntities;

    private static final Map<UUID, ScheduledExecutorService> botExecutors = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicBoolean> autoFaceInFlight = new ConcurrentHashMap<>();
    public static boolean isBotMoving = false;
    private static volatile long lastDangerMessageMs = 0L;

    public static void setBotExecutingTask(boolean value) {
        botExecutingTask = value;
    }

    public static boolean isBotExecutingTask() {
        return botExecutingTask;
    }

    public static void startAutoFace(ServerPlayerEntity bot) {
        UUID botId = bot.getUuid();
        shutdownExecutorsWithUuid(botId);
        stopAutoFace(bot);

        ScheduledExecutorService botExecutor = Executors.newSingleThreadScheduledExecutor();

        botExecutors.put(botId, botExecutor);

        MinecraftServer server = bot.getCommandSource().getServer();

        // Load Q-table from storage
        try {
            qTable = QTableStorage.loadQTable();
            LOGGER.debug("Loaded Q-table from storage.");

        } catch (Exception e) {
            LOGGER.warn("No existing Q-table found. Starting fresh.", e);
            qTable = new QTable();
        }


        // RL agent hook
        rlAgent = null;

        if (modCommandRegistry.isTrainingMode) {

            rlAgent = new RLAgent(); // Initialize RL agent (use singleton or DI for reusability)

        }
        else {

            try {

                double epsilon = QTableStorage.loadEpsilon(BotEventHandler.qTableDir + "/epsilon.bin");

                rlAgent = new RLAgent(epsilon, qTable);

            }

            catch (Exception e) {

                LOGGER.warn("No existing epsilon found. Starting fresh.", e);

            }

        }


        RLAgent finalRlAgent = rlAgent;
        autoFaceInFlight.computeIfAbsent(bot.getUuid(), uuid -> new AtomicBoolean(false));

        botExecutor.scheduleAtFixedRate(() -> {
            if (server == null) {
                return;
            }
            AtomicBoolean guard = autoFaceInFlight.computeIfAbsent(bot.getUuid(), uuid -> new AtomicBoolean(false));
            if (!guard.compareAndSet(false, true)) {
                return;
            }
            final MinecraftServer dispatchServer = server;
            dispatchServer.execute(() -> {
                try {
                    runAutoFaceTick(bot, dispatchServer, finalRlAgent);
                } finally {
                    guard.set(false);
                }
            });
        }, 0, LOOP_INTERVAL_MS, TimeUnit.MILLISECONDS);

    }

    private static void keepBotAfloat(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos feet = bot.getBlockPos();
        FluidState feetFluid = world.getFluidState(feet);
        if (!feetFluid.isIn(FluidTags.WATER)) {
            return;
        }
        boolean headSubmerged = world.getFluidState(feet.up()).isIn(FluidTags.WATER);
        Vec3d velocity = bot.getVelocity();
        if (velocity.y < 0.0D) {
            bot.setVelocity(velocity.x, 0.0D, velocity.z);
        }
        double upward = headSubmerged ? 0.06D : 0.03D;
        bot.addVelocity(0.0D, upward, 0.0D);
        bot.velocityDirty = true;
        bot.setSneaking(false);
    }

    private static void runAutoFaceTick(ServerPlayerEntity bot, MinecraftServer server, RLAgent rlAgent) {
        if (server == null || bot == null) {
            return;
        }
        if (!server.isRunning() || bot.isDisconnected()) {
            stopAutoFace(bot);
            try {
                ServerTickEvents.END_WORLD_TICK.register(world -> {
                    if (!isWorldTickListenerActive) {
                        return;
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("Failed to register END_WORLD_TICK listener", e);
            }
            return;
        }
        if (!bot.isAlive()) {
            return;
        }

        keepBotAfloat(bot);

        if (BotEventHandler.rescueFromBurial(bot)) {
            return;
        }

        List<Entity> nearbyEntities = detectNearbyEntities(bot, BOUNDING_BOX_SIZE);
        hostileEntities = nearbyEntities.stream()
                .filter(EntityUtil::isHostile)
                .toList();

        if (!hostileEntities.isEmpty()) {
            lastHostileTick = server.getTicks();
        }

        // Use HealingService for automatic eating
        if (HealingService.autoEat(bot)) {
            return;
        }

        if (BotEventHandler.updateBehavior(bot, server, nearbyEntities, hostileEntities)) {
            return;
        }

        BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);
        List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();
        boolean hasSculkNearby = nearbyBlocks.stream()
                .anyMatch(block -> block.contains("Sculk Sensor") || block.contains("Sculk Shrieker"));

        if (!hostileEntities.isEmpty()) {
            botBusy = true;

            LOGGER.debug("Hostile entity detected!");

            Entity closestHostile = hostileEntities.stream()
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                    .orElseThrow();

            double distanceToHostileEntity = Math.sqrt(closestHostile.squaredDistanceTo(bot));
            boolean botSeesHostile = closestHostile instanceof LivingEntity livingHostile
                    ? bot.canSee(livingHostile)
                    : bot.canSee(closestHostile);
            ServerPlayerEntity followTarget = BotEventHandler.getFollowTarget();
            boolean playerSeesHostile = followTarget != null
                    && (closestHostile instanceof LivingEntity living
                    ? followTarget.canSee(living)
                    : followTarget.canSee(closestHostile));
            if (botSeesHostile && !playerSeesHostile) {
                broadcastDangerAlert(bot);
            }

            if (distanceToHostileEntity <= 10.0) {
                boolean botWasBusy = PathTracer.BotSegmentManager.getBotMovementStatus()
                        || isBotMoving
                        || blockDetectionUnit.getBlockDetectionStatus()
                        || isBotExecutingTask();

                if (botWasBusy) {
                    LOGGER.debug("Bot is busy, stopping tasks before reacting to hostile.");
                    isBotMoving = false;
                    setBotExecutingTask(false);
                    broadcastDangerAlert(bot);
                    SkillManager.requestSkillPause(bot, null);
                    SkillResumeService.requestAutoResume(bot);
                }

                if (BotEventHandler.isRegisteredBot(bot)) {
                    CombatInventoryManager.ensureCombatLoadout(bot);
                }

                FaceClosestEntity.faceClosestEntity(bot, hostileEntities);

                LOGGER.debug("Closest hostile entity: " + closestHostile.getName().getString()
                        + " at distance: " + distanceToHostileEntity);

                hostileEntityInFront = true;

                if (!isHandlerTriggered) {
                    LOGGER.debug("Triggering handler for hostile entity.");
                    isHandlerTriggered = true;

                    BotEventHandler eventHandler = new BotEventHandler(server, bot);

                    if (!modCommandRegistry.enableReinforcementLearning) {
                        LOGGER.debug("RL loop disabled; skipping detectAndReact.");
                    } else if (modCommandRegistry.isTrainingMode) {
                        try {
                            eventHandler.detectAndReact(rlAgent, distanceToHostileEntity, qTable);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        eventHandler.detectAndReactPlayMode(rlAgent, qTable);
                    }
                } else {
                    LOGGER.debug("Handler already triggered. Skipping.");
                }
            }

        } else if ((DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) <= 5
                && DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) != 0) || hasSculkNearby) {

            LOGGER.debug("Triggering handler for danger zone case");
            isBotMoving = false;
            setBotExecutingTask(false);
            botBusy = true;

            BotEventHandler eventHandler = new BotEventHandler(server, bot);
            if (BotEventHandler.isRegisteredBot(bot)) {
                CombatInventoryManager.ensureCombatLoadout(bot);
            }

            double distanceToHostileEntity = 0.0;
            Entity closestHostile = hostileEntities.stream()
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                    .orElse(null);
            if (closestHostile != null) {
                distanceToHostileEntity = Math.sqrt(closestHostile.squaredDistanceTo(bot));
                LOGGER.debug("Closest hostile entity: {} at distance {}", closestHostile.getName().getString(), distanceToHostileEntity);
            } else {
                LOGGER.debug("Danger zone triggered but no hostile entity present.");
            }

            if (PathTracer.BotSegmentManager.getBotMovementStatus() || isBotMoving || isBotExecutingTask()) {
                LOGGER.debug("Stopping movement since danger zone is detected.");
                broadcastDangerAlert(bot);
                SkillManager.requestSkillPause(bot, null);
                SkillResumeService.requestAutoResume(bot);
                BotActions.stop(bot);
                isBotMoving = false;
                setBotExecutingTask(false);
            }

            boolean runHeavyLogic = BotEventHandler.throttleTraining(bot, true);
            if (runHeavyLogic) {
                if (!modCommandRegistry.enableReinforcementLearning) {
                    LOGGER.debug("RL loop disabled; skipping detectAndReact.");
                } else if (modCommandRegistry.isTrainingMode) {
                    try {
                        eventHandler.detectAndReact(rlAgent, distanceToHostileEntity, qTable);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    eventHandler.detectAndReactPlayMode(rlAgent, qTable);
                }
            }

        } else {
            if ((PathTracer.BotSegmentManager.getBotMovementStatus() || isBotMoving)
                    || blockDetectionUnit.getBlockDetectionStatus() || isBotExecutingTask()) {
                long now = System.currentTimeMillis();
                if (now - lastBusyLogMs > 2500L) {
                    LOGGER.info("Bot is busy, skipping facing the closest entity. Status: PathTracer={}, isBotMoving={}, blockDetection={}, isBotExecutingTask={}",
                            PathTracer.BotSegmentManager.getBotMovementStatus(), isBotMoving,
                            blockDetectionUnit.getBlockDetectionStatus(), isBotExecutingTask());
                    lastBusyLogMs = now;
                }
            } else {
                botBusy = false;
                lastBusyLogMs = 0L;
                hostileEntityInFront = false;

                FaceClosestEntity.faceClosestEntity(bot, nearbyEntities);

                boolean runHeavyLogic = BotEventHandler.throttleTraining(bot, false);
                if (runHeavyLogic && modCommandRegistry.isTrainingMode && modCommandRegistry.enableReinforcementLearning && !isHandlerTriggered) {
                    isHandlerTriggered = true;
                    BotEventHandler eventHandler = new BotEventHandler(server, bot);
                    try {
                        eventHandler.detectAndReact(rlAgent, Double.POSITIVE_INFINITY, qTable);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                maybePerformAmbientEmote(bot, server, nearbyEntities);
                SkillResumeService.tryAutoResume(bot);
            }
        }
    }

    public static void onServerStopped(MinecraftServer minecraftServer) {
        executor3.submit(() -> {
            for (UUID uuid : new ArrayList<>(botExecutors.keySet())) {
                shutdownExecutorsWithUuid(uuid);
            }
        });
    }


    public static void stopAutoFace(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID key = bot.getUuid();
        ScheduledExecutorService executor = botExecutors.remove(key);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);

                LOGGER.debug("Autoface stopped.");

            } catch (InterruptedException e) {
                LOGGER.debug("Error shutting down executor for bot: {}", bot.getName().getString(), e);
                Thread.currentThread().interrupt();
            }
        }
        BotActions.resetRangedState(bot);
        autoFaceInFlight.remove(key);
    }

    private static void shutdownExecutorsWithUuid(UUID uuid) {
        if (uuid == null) {
            return;
        }
        ScheduledExecutorService executor = botExecutors.remove(uuid);
        if (executor != null) {
            shutdownExecutor(executor);
            BotActions.resetRangedState(uuid);
        }
        autoFaceInFlight.remove(uuid);
    }

    private static void shutdownExecutor(ScheduledExecutorService executor) {
        if (executor == null) {
            return;
        }
        if (!executor.isShutdown()) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }


    public static void handleBotRespawn(ServerPlayerEntity bot) {
        // Ensure complete cleanup before restart
        TaskService.onBotRespawn(bot);
        stopAutoFace(bot);
        isWorldTickListenerActive = true;

        // Wait briefly to ensure cleanup is complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        startAutoFace(bot);
        LOGGER.info("Bot {} respawned and initialized.", bot.getName().getString());
    }


    public static List<Entity> detectNearbyEntities(ServerPlayerEntity bot, double boundingBoxSize) {
        // Define a bounding box around the bot with the given size
        Box searchBox = bot.getBoundingBox().expand(boundingBoxSize, boundingBoxSize, boundingBoxSize);
        List<Entity> allNearbyEntities = bot.getEntityWorld().getOtherEntities(bot, searchBox);

        // Filter entities to only include those with a line of sight to the bot
        return allNearbyEntities.stream()
                .filter(entity -> RayCasting.hasLineOfSight(bot, entity))
                .toList();
    }

    public static String determineDirectionToBot(ServerPlayerEntity bot, Entity target) {
        double relativeAngle = getRelativeAngle(bot, target);

        // Determine the direction based on relative angle
        if (relativeAngle <= 45 || relativeAngle > 315) {
            return "front"; // Entity is in front of the bot
        } else if (relativeAngle > 45 && relativeAngle <= 135) {
            return "right"; // Entity is to the right
        } else if (relativeAngle > 135 && relativeAngle <= 225) {
            return "behind"; // Entity is behind the bot
        } else {
            return "left"; // Entity is to the left
        }
    }

    private static double getRelativeAngle(Entity bot, Entity target) {
        double botX = bot.getX();
        double botZ = bot.getZ();
        double targetX = target.getX();
        double targetZ = target.getZ();

        // Get bot's facing direction
        float botYaw = bot.getYaw(); // Horizontal rotation (0 = south, 90 = west, etc.)

        // Calculate relative angle to the entity
        double deltaX = targetX - botX;
        double deltaZ = targetZ - botZ;
        double angleToEntity = Math.toDegrees(Math.atan2(deltaZ, deltaX)); // Angle from bot to entity

        // Normalize angles between 0 and 360
        double botFacing = (botYaw + 360) % 360;
        double relativeAngle = (angleToEntity - botFacing + 360) % 360;
        return relativeAngle;
    }

    private static void maybePerformAmbientEmote(ServerPlayerEntity bot, MinecraftServer server, List<Entity> nearbyEntities) {
        if (server == null || bot == null) {
            return;
        }
        if (!BotEventHandler.isPassiveMode()) {
            return;
        }
        if (hostileEntities != null && !hostileEntities.isEmpty()) {
            return;
        }
        int serverTicks = server.getTicks();
        if (serverTicks - lastEmoteTick < EMOTE_COOLDOWN_TICKS) {
            return;
        }
        if (serverTicks - lastHostileTick < HOSTILE_COOLDOWN_TICKS) {
            return;
        }
        if (bot.isUsingItem() || bot.isBlocking() || bot.isSleeping() || bot.isSneaking()) {
            return;
        }
        if (bot.getVelocity().horizontalLengthSquared() > 0.01D) {
            return;
        }
        if (EMOTE_RANDOM.nextDouble() > 0.2D) { // 20% chance each opportunity
            return;
        }

        Optional<ServerPlayerEntity> target = findGazingPlayer(bot, nearbyEntities);
        if (target.isEmpty()) {
            return;
        }

        ServerPlayerEntity player = target.get();
        bot.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos());
        performWave(bot);
        lastEmoteTick = serverTicks;
    }

    private static Optional<ServerPlayerEntity> findGazingPlayer(ServerPlayerEntity bot, List<Entity> nearbyEntities) {
        return nearbyEntities.stream()
                .filter(entity -> entity instanceof ServerPlayerEntity)
                .map(entity -> (ServerPlayerEntity) entity)
                .filter(player -> !player.getUuid().equals(bot.getUuid()))
                .filter(player -> !player.isSpectator())
                .filter(player -> player.getCommandSource().getWorld() == bot.getCommandSource().getWorld())
                .filter(player -> player.squaredDistanceTo(bot) <= EMOTE_MAX_DISTANCE * EMOTE_MAX_DISTANCE)
                .filter(player -> isLookingAtBot(player, bot))
                .findFirst();
    }

    private static boolean isLookingAtBot(ServerPlayerEntity player, ServerPlayerEntity bot) {
        Vec3d eyeToBot = bot.getEyePos().subtract(player.getEyePos());
        double distanceSq = eyeToBot.lengthSquared();
        if (distanceSq < 0.0001D) {
            return false;
        }
        Vec3d direction = eyeToBot.normalize();
        Vec3d playerLook = player.getRotationVec(1.0F).normalize();
        return playerLook.dotProduct(direction) >= EMOTE_LOOK_THRESHOLD;
    }

    private static void performWave(ServerPlayerEntity bot) {
        if (!bot.getOffHandStack().isEmpty()) {
            bot.swingHand(Hand.OFF_HAND, true);
        } else {
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }

    private static void broadcastDangerAlert(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDangerMessageMs < DANGER_MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastDangerMessageMs = now;
        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
                "§cTerminating all current tasks due to threat detections");
    }


}
