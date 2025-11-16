package net.shasankp000.GameAI.llm;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.AIPlayer;
import net.shasankp000.ChatUtils.NLPProcessor;
import net.shasankp000.ServiceLLMClients.LLMClient;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.ServiceLLMClients.LLMServiceHandler;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.FilingSystem.ManualConfig;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central hub for routing chat messages through the LLM pipeline.
 * Phase 1: only handles enablement toggles and a single entry point for intent
 * detection; actual skill execution still flows through existing handlers.
 */
public final class LLMOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("LLMOrchestrator");
    private static final ExecutorService CHAT_EXECUTOR = Executors.newCachedThreadPool();
    private static final MemoryStore MEMORY_STORE = new MemoryStore();

    private static final Map<String, Boolean> WORLD_TOGGLES = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> BOT_TOGGLES = new ConcurrentHashMap<>();

    static {
        WORLD_TOGGLES.put("default", false);
    }

    private LLMOrchestrator() {
    }

    public static boolean isWorldEnabled(String worldKey) {
        return WORLD_TOGGLES.getOrDefault(worldKey, false);
    }

    public static void setWorldEnabled(String worldKey, boolean enabled) {
        WORLD_TOGGLES.put(worldKey, enabled);
        LOGGER.info("LLM world toggle for {} set to {}", worldKey, enabled);
    }

    public static boolean isBotEnabled(UUID botId) {
        return BOT_TOGGLES.getOrDefault(botId, true);
    }

    public static void setBotEnabled(UUID botId, boolean enabled) {
        BOT_TOGGLES.put(botId, enabled);
        LOGGER.info("LLM bot toggle for {} set to {}", botId, enabled);
    }

    public static boolean handleChat(ServerPlayerEntity bot,
                                  ServerCommandSource botSource,
                                  UUID playerUuid,
                                  String message) {
        if (bot == null || botSource == null || playerUuid == null || message == null) {
            return false;
        }
        MinecraftServer server = botSource.getServer();
        if (server == null) {
            return false;
        }
        String worldKey = worldKey(server, bot);
        if (!isWorldEnabled(worldKey)) {
            return false;
        }
        if (!isBotEnabled(bot.getUuid())) {
            return false;
        }
        CHAT_EXECUTOR.submit(() -> processChat(server, bot, botSource, playerUuid, message));
        return true;
    }

    public static MemoryStore getMemoryStore() {
        return MEMORY_STORE;
    }

    public static String computeWorldKey(MinecraftServer server, ServerPlayerEntity bot) {
        return worldKey(server, bot);
    }

    private static void processChat(MinecraftServer server,
                                    ServerPlayerEntity bot,
                                    ServerCommandSource botSource,
                                    UUID playerUuid,
                                    String message) {
        try {
            String llmProvider = (AIPlayer.CONFIG != null && AIPlayer.CONFIG.getLlmMode() != null && !AIPlayer.CONFIG.getLlmMode().isBlank())
                    ? AIPlayer.CONFIG.getLlmMode()
                    : System.getProperty("aiplayer.llmMode", "ollama");
            LLMClient llmClient = LLMClientFactory.createClient(llmProvider);
            if (llmClient == null) {
                LOGGER.warn("LLM client unavailable for provider {}", llmProvider);
                return;
            }
            String worldKey = worldKey(server, bot);
            String personaPrompt = MEMORY_STORE.buildPersonaPrompt(worldKey, bot);
            NLPProcessor.Intent intent = NLPProcessor.getIntention(message);
            if (intent == NLPProcessor.Intent.REQUEST_ACTION) {
                LLMServiceHandler.routeFromOrchestrator(message, botSource, playerUuid, llmClient);
                MEMORY_STORE.appendMemory(worldKey, bot.getUuid(), "Received command request: \"" + message + "\"");
            } else {
                String reply = llmClient.sendPrompt(personaPrompt, message);
                if (reply != null && !reply.isBlank()) {
                    ChatUtils.sendChatMessages(botSource, reply);
                    MEMORY_STORE.appendMemory(worldKey, bot.getUuid(), "Replied to chat: \"" + reply + "\"");
                }
            }
        } catch (Exception e) {
            LOGGER.error("LLM orchestration failed", e);
        }
    }

    private static String worldKey(MinecraftServer server, ServerPlayerEntity bot) {
        String level = server.getSaveProperties().getLevelName();
        String dimension = bot.getEntityWorld().getRegistryKey().getValue().toString();
        return level + ":" + dimension;
    }
}
