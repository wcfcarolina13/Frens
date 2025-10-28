package net.shasankp000;

import ai.djl.ModelException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.ChatUtils.BERTModel.BertModelManager;
import net.shasankp000.ChatUtils.NLPProcessor;
import net.shasankp000.Commands.configCommand;
import net.shasankp000.Commands.modCommandRegistry;
import net.shasankp000.Database.SQLiteDB;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;

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
import java.util.concurrent.CompletableFuture;

public class AIPlayer implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    public static final ManualConfig CONFIG = ManualConfig.load();
    public static MinecraftServer serverInstance = null; // default for now
    public static BertModelManager modelManager;
    public static boolean loadedBERTModelIntoMemory = false;

    @Override
    public void onInitialize() {

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
        SQLiteDB.createDB();
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

            LOGGER.info("Proceeding to load BERT model into memory");

            // Make BERT optional so the game doesn’t crash if the engine/model isn’t present.
            try {
                modelManager.loadModel();
                loadedBERTModelIntoMemory = true;
                LOGGER.info("BERT model loaded into memory. It will stay in memory as long as any bot stays active in game.");
            } catch (Throwable t) {
                // Catch anything (ModelNotFoundException, runtime wraps, etc.) and keep going.
                loadedBERTModelIntoMemory = false;
                LOGGER.warn("⚠️ BERT unavailable (continuing without it): {}", t.toString());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
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
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity serverPlayer) {
                if (BotEventHandler.bot != null) {
                    if (serverPlayer.getName().getString().equals(BotEventHandler.bot.getName().getString())) {
                        QTableStorage.saveLastKnownState(BotEventHandler.getCurrentState(), BotEventHandler.qTableDir + "/lastKnownState.bin");
                        BotEventHandler.botDied = true; // set flag for bot's death.
                    }
                }
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // Check if the respawned player is the bot
            if (oldPlayer instanceof ServerPlayerEntity && newPlayer instanceof ServerPlayerEntity && oldPlayer.getName().getString().equals(newPlayer.getName().getString())) {
                System.out.println("Bot has respawned. Updating state...");
                BotEventHandler.hasRespawned = true;
                BotEventHandler.botSpawnCount++;
            }
        });
    }
}