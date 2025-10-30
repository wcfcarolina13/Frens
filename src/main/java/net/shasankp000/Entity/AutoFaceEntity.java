package net.shasankp000.Entity;

import net.shasankp000.EntityUtil;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
// import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Database.QTable;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.RLAgent;
import net.shasankp000.Commands.modCommandRegistry;
import net.shasankp000.Database.QTableStorage;
import net.shasankp000.PlayerUtils.BlockDistanceLimitedSearch;
import net.shasankp000.PlayerUtils.CombatInventoryManager;
import net.shasankp000.PlayerUtils.blockDetectionUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shasankp000.DangerZoneDetector.DangerZoneDetector;
import net.shasankp000.PathFinding.PathTracer;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    private static final Map<ServerPlayerEntity, ScheduledExecutorService> botExecutors = new HashMap<>();
    private static ServerPlayerEntity Bot = null;
    public static boolean isBotMoving = false;

    public static void setBotExecutingTask(boolean value) {
        botExecutingTask = value;
    }

    public static boolean isBotExecutingTask() {
        return botExecutingTask;
    }

    public static void startAutoFace(ServerPlayerEntity bot) {
        // Stop any existing executor for this bot

        ServerPlayerEntity previousBot = Bot;
        if (previousBot != null && previousBot != bot) {
            stopAutoFace(previousBot);
        }

        shutdownExecutorsWithUuid(bot.getUuid());

        stopAutoFace(bot);

        Bot = bot;

        ScheduledExecutorService botExecutor = Executors.newSingleThreadScheduledExecutor();

        botExecutors.put(bot, botExecutor);

        MinecraftServer server = bot.getCommandSource().getServer();

        // Load Q-table from storage
        try {
            qTable = QTableStorage.loadQTable();
            System.out.println("Loaded Q-table from storage.");

        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.err.println("No existing Q-table found. Starting fresh.");
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

                System.err.println("No existing epsilon found. Starting fresh.");

            }

        }


        RLAgent finalRlAgent = rlAgent;
        botExecutor.scheduleAtFixedRate(() -> {
            // Run detection and facing logic

//            System.out.println("Is bot moving: " + PathTracer.getBotMovementStatus() + " " + isBotMoving);

            if (server != null && server.isRunning() && bot.isAlive()) {

                // Detect all entities within the bounding box
                List<Entity> nearbyEntities = detectNearbyEntities(bot, BOUNDING_BOX_SIZE);

                // Filter only hostile entities
                 hostileEntities = nearbyEntities.stream()
                        .filter(EntityUtil::isHostile)
                        .toList();

                if (!hostileEntities.isEmpty()) {
                    lastHostileTick = server.getTicks();
                }

                if (CombatInventoryManager.tryConsumeIfNeeded(bot, hostileEntities)) {
                    return;
                }

                if (BotEventHandler.updateBehavior(bot, server, nearbyEntities, hostileEntities)) {
                    return;
                }

                boolean hasSculkNearby = false;

                BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);

                List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

                hasSculkNearby = nearbyBlocks.stream()
                        .anyMatch(block -> block.contains("Sculk Sensor") || block.contains("Sculk Shrieker"));


                if (!hostileEntities.isEmpty()) {
                    botBusy = true;

                    System.out.println("Hostile entity detected!");

                    // Find the closest hostile entity
                    Entity closestHostile = hostileEntities.stream()
                            .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                            .orElseThrow(); // Use orElseThrow since empty case is already handled

                    double distanceToHostileEntity = Math.sqrt(closestHostile.squaredDistanceTo(bot));

                    if (distanceToHostileEntity <= 10.0) {
                        boolean botWasBusy = PathTracer.BotSegmentManager.getBotMovementStatus()
                                || isBotMoving
                                || blockDetectionUnit.getBlockDetectionStatus()
                                || isBotExecutingTask();

                        if (botWasBusy) {
                            System.out.println("Bot is busy, stopping tasks before reacting to hostile.");
                            isBotMoving = false;
                            setBotExecutingTask(false);
                            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), "Terminating all current tasks due to threat detections");
                        }

                        if (BotEventHandler.isRegisteredBot(bot)) {
                            CombatInventoryManager.ensureCombatLoadout(bot);
                        }

                        FaceClosestEntity.faceClosestEntity(bot, AutoFaceEntity.hostileEntities);

                        // Log details of the detected hostile entity
                        System.out.println("Closest hostile entity: " + closestHostile.getName().getString()
                                + " at distance: " + distanceToHostileEntity);

                        hostileEntityInFront = true;

                        // Trigger the handler
                        if (isHandlerTriggered) {
                            System.out.println("Handler already triggered. Skipping.");
                        } else {
                            System.out.println("Triggering handler for hostile entity.");
                            isHandlerTriggered = true;

                            BotEventHandler eventHandler = new BotEventHandler(server, bot);

                            if (modCommandRegistry.isTrainingMode) {

                                try {
                                    eventHandler.detectAndReact(finalRlAgent, distanceToHostileEntity, qTable);
                                } catch (IOException e) {
                                    System.out.println("Exception occurred in startAutoFace: " + e.getMessage());
                                    throw new RuntimeException(e);

                                }
                            }
                            else {

                                eventHandler.detectAndReactPlayMode(finalRlAgent, qTable);

                            }

                        }
                    }

                }
                else if ((DangerZoneDetector.detectDangerZone(bot, 10, 10 , 10) <= 5 && DangerZoneDetector.detectDangerZone(bot, 10, 10 , 10)!= 0) || hasSculkNearby)  {

                    System.out.println("Triggering handler for danger zone case");
                    isBotMoving = false;
                    setBotExecutingTask(false);



                    botBusy = true;

                    BotEventHandler eventHandler = new BotEventHandler(server, bot);
                    if (BotEventHandler.isRegisteredBot(bot)) {
                        CombatInventoryManager.ensureCombatLoadout(bot);
                    }

                    double distanceToHostileEntity = 0.0;

                    try {

                        // Find the closest hostile entity
                    Entity closestHostile = hostileEntities.stream()
                            .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                            .orElseThrow(); // Use orElseThrow since empty case is already handled

                    distanceToHostileEntity = Math.sqrt(closestHostile.squaredDistanceTo(bot));

                        // Log details of the detected hostile entity
                        System.out.println("Closest hostile entity: " + closestHostile.getName().getString()
                                + " at distance: " + distanceToHostileEntity);

                    } catch (Exception e) {
                        System.out.println("An exception occurred while calculating detecting hostile entities nearby" + e.getMessage());
                        System.out.println(e.getStackTrace());
                    }

                    // first check if bot is moving, and if so, then stop moving.
                    // the hope is that the bot will stop moving ahead of time since the danger zone detector has a wide range.

                    if (PathTracer.BotSegmentManager.getBotMovementStatus() || isBotMoving || isBotExecutingTask()) {
                        System.out.println("Stopping movement since danger zone is detected.");
                        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), "Terminating all current tasks due to threat detections");
                        BotActions.stop(bot);
                        isBotMoving = false;
                        setBotExecutingTask(false);
                    }


                    if (modCommandRegistry.isTrainingMode) {

                        try {
                            eventHandler.detectAndReact(finalRlAgent, distanceToHostileEntity ,qTable);
                        } catch (IOException e) {
                            System.out.println("Exception occurred in startAutoFace: " + e.getMessage());
                            throw new RuntimeException(e);

                        }
                    }
                    else {

                        eventHandler.detectAndReactPlayMode(finalRlAgent, qTable);

                    }

                }

                else {

                    if ((PathTracer.BotSegmentManager.getBotMovementStatus() || isBotMoving) || blockDetectionUnit.getBlockDetectionStatus() || isBotExecutingTask()) {
                        long now = System.currentTimeMillis();
                        if (now - lastBusyLogMs > 2500L) {
                            System.out.println("Bot is busy, skipping facing the closest entity");
                            lastBusyLogMs = now;
                        }
                    }

                    else {
                        botBusy = false; // Clear the flag if no hostile entities are in front
                        lastBusyLogMs = 0L;

                        hostileEntityInFront = false;

                        FaceClosestEntity.faceClosestEntity(bot, nearbyEntities);

                        if (modCommandRegistry.isTrainingMode && !isHandlerTriggered) {
                            isHandlerTriggered = true;
                            BotEventHandler eventHandler = new BotEventHandler(server, bot);
                            try {
                                eventHandler.detectAndReact(finalRlAgent, Double.POSITIVE_INFINITY, qTable);
                            } catch (IOException e) {
                                System.out.println("Exception occurred during passive training: " + e.getMessage());
                                throw new RuntimeException(e);
                            }
                        }

                        maybePerformAmbientEmote(bot, server, nearbyEntities);
                    }

                }


            }

            else if (server != null && !server.isRunning() || bot.isDisconnected()) {

                stopAutoFace(bot);

                try {

                    ServerTickEvents.END_WORLD_TICK.register(world -> {

                        if (!isWorldTickListenerActive) {
                            return; // Skip execution if listener is deactivated
                        }

                    });
                } catch (Exception e) {

                    System.out.println(e.getMessage());
                }


            }


        }, 0, LOOP_INTERVAL_MS, TimeUnit.MILLISECONDS);

    }

    public static void onServerStopped(MinecraftServer minecraftServer) {

        executor3.submit(() -> {
            try {
                stopAutoFace(Bot);
            } catch (Exception e) {
                LOGGER.error("Failed to initialize Ollama client", e);
            }
        });
    }


    public static void stopAutoFace(ServerPlayerEntity bot) {
        ScheduledExecutorService executor = botExecutors.remove(bot);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);

                System.out.println("Autoface stopped.");

            } catch (InterruptedException e) {
                System.out.println("Error shutting down executor for bot: {" + bot.getName().getString() + "}" + " " + e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void shutdownExecutorsWithUuid(UUID uuid) {
        if (uuid == null) {
            return;
        }
        botExecutors.entrySet().removeIf(entry -> {
            ServerPlayerEntity trackedBot = entry.getKey();
            if (trackedBot == null) {
                shutdownExecutor(entry.getValue());
                return true;
            }
            if (uuid.equals(trackedBot.getUuid())) {
                shutdownExecutor(entry.getValue());
                return true;
            }
            return false;
        });
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
        return bot.getEntityWorld().getOtherEntities(bot, searchBox);
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
        if (!BotEventHandler.isPassiveMode() || BotEventHandler.isSpartanModeActive()) {
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


}
