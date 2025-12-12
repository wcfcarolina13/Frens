package net.shasankp000;

import ai.djl.ModelException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.ChatUtils.BERTModel.BertModelManager;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.ChatUtils.NLPProcessor;
import net.shasankp000.Commands.configCommand;
import net.shasankp000.Commands.modCommandRegistry;
import net.shasankp000.Database.SQLiteDB;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.FunctionCaller.FunctionCallerV2;
import net.shasankp000.GameAI.services.BotPersistenceService;
import net.shasankp000.GameAI.llm.LLMOrchestrator;

import net.shasankp000.Database.QTableStorage;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.Network.OpenConfigPayload;
import net.shasankp000.Network.SaveAPIKeyPayload;
import net.shasankp000.Network.SaveConfigPayload;
import net.shasankp000.Network.SaveCustomProviderPayload;
import net.shasankp000.Network.configNetworkManager;
import net.shasankp000.WebSearch.AISearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.shasankp000.ui.BotInventoryAccess;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.resource.featuretoggle.FeatureFlags;

public class AIPlayer implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    public static final String MOD_ID = "ai-player";
    public static ScreenHandlerType<net.shasankp000.ui.BotPlayerInventoryScreenHandler> BOT_PLAYER_INV_HANDLER;
    public static final ManualConfig CONFIG = ManualConfig.load();
    public static MinecraftServer serverInstance = null; // default for now
    public static BertModelManager modelManager;
    public static boolean loadedBERTModelIntoMemory = false;
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> LAST_OPEN_MS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService MODEL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bert-loader");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean MODEL_LOAD_ENQUEUED = new AtomicBoolean(false);
    
    // Track bots that need spawn escape checks
    private static final ConcurrentHashMap<UUID, SpawnEscapeCheck> SPAWN_ESCAPE_CHECKS = new ConcurrentHashMap<>();
    
    // Track last IN_WALL damage warning to prevent log flooding
    private static final ConcurrentHashMap<UUID, Long> LAST_IN_WALL_WARNING_TICK = new ConcurrentHashMap<>();
    private static final long IN_WALL_WARNING_COOLDOWN_TICKS = 60L; // 3 seconds between warnings
    
    private static class SpawnEscapeCheck {
        int ticksWaited = 0;
        int attemptsRemaining = 5;
    }

    @Override
    public void onInitialize() {

        // ScreenHandler registration for bot/player UI
        BOT_PLAYER_INV_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                Identifier.of(MOD_ID, "bot_player_inventory"),
                new ScreenHandlerType<>(net.shasankp000.ui.BotPlayerInventoryScreenHandler::clientFactory, FeatureFlags.VANILLA_FEATURES)
        );

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
            // Only react once (MAIN_HAND). OFF_HAND callback will return PASS to avoid double-fire.
            if (hand != net.minecraft.util.Hand.MAIN_HAND) return net.minecraft.util.ActionResult.PASS;
            if (!(entity instanceof net.minecraft.server.network.ServerPlayerEntity bot)) return net.minecraft.util.ActionResult.PASS;

            // Gesture: either empty main hand OR sneaking (to override item-in-hand use)
            boolean openGesture = player.isSneaking() || player.getMainHandStack().isEmpty();
            if (!openGesture) return net.minecraft.util.ActionResult.PASS;

            // Simple debounce per bot to prevent rapid re-open/close edge cases
            long now = System.currentTimeMillis();
            java.util.UUID key = bot.getUuid();
            Long last = LAST_OPEN_MS.getOrDefault(key, 0L);
            if (now - last < 200) return net.minecraft.util.ActionResult.PASS;
            LAST_OPEN_MS.put(key, now);

            if (!net.shasankp000.GameAI.services.InventoryAccessPolicy
                    .canOpen((net.minecraft.server.network.ServerPlayerEntity) player, bot)) {
                return net.minecraft.util.ActionResult.PASS;
            }

            boolean ok = net.shasankp000.ui.BotInventoryAccess.openBotInventory(
                    (net.minecraft.server.network.ServerPlayerEntity) player, bot
            );
            return ok ? net.minecraft.util.ActionResult.SUCCESS : net.minecraft.util.ActionResult.PASS;
        });

        LOGGER.info("Hello Fabric world!");

        LOGGER.debug("Running on environment type: {}", FabricLoader.getInstance().getEnvironmentType());



        String llmProvider = System.getProperty("aiplayer.llmMode", "ollama");
        System.out.println("Using provider: " + llmProvider);

        // Debug: Print ALL system properties to see what's available
        System.out.println("=== ALL SYSTEM PROPERTIES ===");
        System.getProperties().forEach((key, value) -> {
            if (key.toString().contains("aiplayer") || key.toString().contains("llm")) {
                System.out.println(key + " = " + value);
            }
        });
        System.out.println("=== END DEBUG ===");

        // registering the packets on the global entrypoint to recognise them
        PayloadTypeRegistry.playC2S().register(SaveConfigPayload.ID, SaveConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenConfigPayload.ID, OpenConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveAPIKeyPayload.ID, SaveAPIKeyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveCustomProviderPayload.ID, SaveCustomProviderPayload.CODEC);

        modCommandRegistry.register();
        configCommand.register();
        try {
            SQLiteDB.createDB();
        } catch (Exception e) {
            AIPlayer.LOGGER.error("Failed to initialize AI-Player database; running without DB-backed memory.", e);
        }
        QTableStorage.setupQTableStorage();

        CompletableFuture.runAsync(() -> {
            AISearchConfig.setupIfMissing();
            NLPProcessor.ensureLocalNLPModel();
            try {
                Thread.sleep(2000);
                System.out.println("NLP model deployment task complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        modelManager = BertModelManager.getInstance();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            configNetworkManager.registerServerModelNameSaveReceiver(server);
            configNetworkManager.registerServerAPIKeySaveReceiver(server);
            configNetworkManager.registerServerCustomProviderSaveReceiver(server);
            serverInstance = server;
            LOGGER.info("Server instance stored!");

            System.out.println("Server instance is " + serverInstance);

            enqueueBertLoad();
            net.shasankp000.GameAI.services.BotControlApplier.applyPersistentSettings(server);
            
            // Load protected zones for all worlds
            server.getWorlds().forEach(world -> {
                String worldId = world.getRegistryKey().getValue().toString();
                net.shasankp000.GameAI.services.ProtectedZoneService.loadZones(server, worldId);
            });
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BotPersistenceService.saveAll(server));

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BotEventHandler.resetAll();
            AutoFaceEntity.onServerStopped(server);
            try {
                if (modelManager.isModelLoaded() || loadedBERTModelIntoMemory) {
                    modelManager.unloadModel();
                    System.out.println("Unloaded BERT Model from memory");
                } else {
                    System.out.println("BERT Model was not loaded, skipping unloading...");
                }
            } catch (IOException e) {
                LOGGER.error("BERT Model unloading failed!", e);
            }
            MODEL_LOAD_ENQUEUED.set(false);
            net.shasankp000.GameAI.services.BotControlApplier.resetSession();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            BotPersistenceService.onBotJoin(player);
            
            // Check if bot spawned in a wall and needs to dig out
            if (BotEventHandler.isRegisteredBot(player)) {
                // Use a scheduled task that runs on the server thread (sync)
                // This avoids Thread.sleep() issues and ensures proper timing
                scheduleSpawnEscapeCheck(server, player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            BotPersistenceService.onBotDisconnect(player);
        });

        // Register damage event to handle suffocation immediately
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity serverPlayer && BotEventHandler.isRegisteredBot(serverPlayer)) {
                // If bot is taking IN_WALL damage, try to escape immediately
                // BUT: skip aggressive escape if bot is in ascent mode (digging upward)
                if (source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.FLY_INTO_WALL) || source.isOf(DamageTypes.CRAMMING)) {
                    // Record recent obstruction-like damage (2s window gating)
                    BotEventHandler.noteObstructDamage(serverPlayer);

                    if (source.isOf(DamageTypes.IN_WALL)) {
                        if (net.shasankp000.GameAI.services.TaskService.isInAscentMode(serverPlayer.getUuid())) {
                            LOGGER.debug("Bot {} taking IN_WALL damage during ascent - skipping aggressive escape",
                                    serverPlayer.getName().getString());
                        } else {
                            MinecraftServer server = serverPlayer.getCommandSource().getServer();
                            if (server != null) {
                                // Only log warning once every 3 seconds to prevent flooding
                                UUID botUuid = serverPlayer.getUuid();
                                long now = server.getTicks();
                                long lastWarning = LAST_IN_WALL_WARNING_TICK.getOrDefault(botUuid, Long.MIN_VALUE);
                                
                                if (now - lastWarning >= IN_WALL_WARNING_COOLDOWN_TICKS) {
                                    LOGGER.warn("Bot {} taking IN_WALL damage - attempting immediate escape",
                                            serverPlayer.getName().getString());
                                    LAST_IN_WALL_WARNING_TICK.put(botUuid, now);
                                }
                                
                                server.execute(() -> {
                                    if (!serverPlayer.isRemoved()) {
                                        BotEventHandler.checkAndEscapeSuffocation(serverPlayer);
                                    }
                                });
                            }
                        }
                    }
                }
            }
            return true; // Allow the damage
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity serverPlayer) {
                if (BotEventHandler.isRegisteredBot(serverPlayer)) {
                        LOGGER.info("Detected bot death at ({}, {}, {}) damageSource={} alive={}",
                                serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                                damageSource.getType(), serverPlayer.isAlive());
                        QTableStorage.saveLastKnownState(BotEventHandler.getCurrentState(), BotEventHandler.qTableDir + "/lastKnownState.bin");
                        BotEventHandler.botDied = true; // set flag for bot's death.
                        BotEventHandler.ensureRespawnHandled(serverPlayer);
                }
                BotPersistenceService.onBotDeath(serverPlayer);
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // Check if the respawned player is the bot
            if (oldPlayer instanceof ServerPlayerEntity && newPlayer instanceof ServerPlayerEntity && BotEventHandler.isRegisteredBot(newPlayer)) {
                System.out.println("Bot has respawned. Updating state...");
                BotEventHandler.hasRespawned = true;
                BotEventHandler.botSpawnCount++;
                LOGGER.info("AFTER_RESPAWN fired for bot {} (alive flag={})", newPlayer.getName().getString(), alive);
                AutoFaceEntity.handleBotRespawn(newPlayer);
                BotEventHandler.onBotRespawn(newPlayer);
            }
            BotPersistenceService.onBotRespawn(oldPlayer, newPlayer, alive);
            BotEventHandler.ensureBotPresence(newPlayer.getCommandSource().getServer());
        });

        ServerTickEvents.END_SERVER_TICK.register(BotPersistenceService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(BotEventHandler::tickBurialRescue);
        ServerTickEvents.END_SERVER_TICK.register(BotEventHandler::tickHunger);
        ServerTickEvents.END_SERVER_TICK.register(AIPlayer::processSpawnEscapeChecks);

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String raw = message.getContent().getString();
            boolean consumed = FunctionCallerV2.tryHandleConfirmation(sender.getUuid(), raw);
            if (consumed) {
                return;
            }

            SkillResumeService.handleChat(sender, raw);

            ChatTarget target = resolveChatTargets(raw);
            if (!target.bots().isEmpty()) {
                boolean handled = false;
                for (ServerPlayerEntity bot : target.bots()) {
                    if (target.prompt().isEmpty()) {
                        continue;
                    }
                    ServerCommandSource botSource = bot.getCommandSource().withSilent().withMaxLevel(4);
                    ChatUtils.sendChatMessages(botSource, "Processing your message, please wait.");
                    handled |= LLMOrchestrator.handleChat(
                            bot,
                            botSource,
                            sender.getUuid(),
                            target.prompt());
                }
                if (handled) {
                    return;
                }
            }

            // New logic starts here
            String[] parts = raw.split(" ");
            if (parts.length < 2) {
                return;
            }
            String botName = parts[0];
            // A very simple check to see if the first word is a bot name.
            // A better approach would be to get a list of all bots.
            if (BotEventHandler.isRegisteredBot(serverInstance.getPlayerManager().getPlayer(botName))) {
                ServerPlayerEntity bot = serverInstance.getPlayerManager().getPlayer(botName);
                if (bot == null) {
                    return;
                }
                
                String userPrompt = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));

                NLPProcessor.Intent intent = NLPProcessor.getIntention(userPrompt);

                if (intent == NLPProcessor.Intent.REQUEST_ACTION) {
                    FunctionCallerV2 functionCaller = new FunctionCallerV2(bot.getCommandSource(), sender.getUuid());
                    try {
                        functionCaller.run(userPrompt);
                    } finally {
                        FunctionCallerV2.clearContext();
                    }
                }
            }
        });
    }

    private static void enqueueBertLoad() {
        if (!MODEL_LOAD_ENQUEUED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Queueing asynchronous BERT model load...");
        CompletableFuture.runAsync(() -> {
            LOGGER.info("Proceeding to load BERT model into memory");
            try {
                modelManager.loadModel();
                loadedBERTModelIntoMemory = true;
                LOGGER.info("BERT model loaded into memory. It will stay in memory as long as any bot stays active in game.");
            } catch (Throwable t) {
                loadedBERTModelIntoMemory = false;
                LOGGER.warn("⚠️ BERT unavailable (continuing without it): {}", t.toString());
            }
        }, MODEL_EXECUTOR).whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("Asynchronous BERT load failed: {}", error.getMessage());
                loadedBERTModelIntoMemory = false;
                MODEL_LOAD_ENQUEUED.set(false);
            }
        });
    }

    private static ChatTarget resolveChatTargets(String raw) {
        if (serverInstance == null || raw == null) {
            return new ChatTarget(List.of(), "");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new ChatTarget(List.of(), "");
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return new ChatTarget(List.of(), "");
        }
        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(serverInstance);
        if (bots.isEmpty()) {
            return new ChatTarget(List.of(), "");
        }
        int consumed = -1;
        List<ServerPlayerEntity> targets = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String current = normalizeToken(tokens[i]);
            if (current.isEmpty()) {
                continue;
            }
            if (current.equals("allbots") || current.equals("bots")) {
                targets.addAll(bots);
                consumed = i + 1;
                break;
            }
            if (current.equals("all") && i + 1 < tokens.length) {
                String next = normalizeToken(tokens[i + 1]);
                if (next.equals("bots")) {
                    targets.addAll(bots);
                    consumed = i + 2;
                    break;
                }
            }
            for (ServerPlayerEntity bot : bots) {
                if (normalizeToken(bot.getName().getString()).equals(current)) {
                    targets.add(bot);
                    consumed = i + 1;
                    break;
                }
            }
            if (!targets.isEmpty()) {
                break;
            }
        }
        if (targets.isEmpty() || consumed < 0) {
            return new ChatTarget(List.of(), "");
        }
        if (consumed >= tokens.length) {
            return new ChatTarget(targets, "");
        }
        String prompt = String.join(" ", Arrays.copyOfRange(tokens, consumed, tokens.length)).trim();
        return new ChatTarget(targets, prompt);
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        String cleaned = token.replaceAll("[^a-zA-Z0-9]", "");
        return cleaned.toLowerCase(Locale.ROOT);
    }

    /**
     * Schedules a tick-based escape check for bots that spawn inside walls.
     * Uses the server tick event to check periodically without blocking.
     */
    private static void scheduleSpawnEscapeCheck(MinecraftServer server, ServerPlayerEntity bot) {
        SPAWN_ESCAPE_CHECKS.put(bot.getUuid(), new SpawnEscapeCheck());
    }
    
    /**
     * Processes pending spawn escape checks. Called every server tick.
     */
    private static void processSpawnEscapeChecks(MinecraftServer server) {
        if (SPAWN_ESCAPE_CHECKS.isEmpty()) {
            return;
        }
        
        SPAWN_ESCAPE_CHECKS.entrySet().removeIf(entry -> {
            UUID botId = entry.getKey();
            SpawnEscapeCheck check = entry.getValue();
            
            ServerPlayerEntity bot = (ServerPlayerEntity) server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved()) {
                return true; // Remove check - bot is gone
            }
            
            check.ticksWaited++;
            
            // Start checking after 20 ticks (1 second) to let spawn complete
            if (check.ticksWaited < 20) {
                return false; // Keep checking
            }
            
            // Try escape every 10 ticks (0.5 seconds)
            if ((check.ticksWaited - 20) % 10 == 0 && check.attemptsRemaining > 0) {
                check.attemptsRemaining--;
                int attemptNum = 5 - check.attemptsRemaining;
                
                if (BotEventHandler.checkAndEscapeSuffocation(bot)) {
                    LOGGER.info("Bot {} escaped suffocation on attempt {}", bot.getName().getString(), attemptNum);
                    return true; // Success - remove check
                }
                
                if (check.attemptsRemaining == 0) {
                    LOGGER.warn("Bot {} failed to escape after 5 attempts", bot.getName().getString());
                    return true; // Give up - remove check
                }
            }
            
            // Stop after 70 ticks (3.5 seconds) total
            if (check.ticksWaited > 70) {
                return true; // Timeout - remove check
            }
            
            return false; // Keep checking
        });
    }

    private record ChatTarget(List<ServerPlayerEntity> bots, String prompt) {
    }
}
