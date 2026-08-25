package net.wcfcarolina13.GameAI.llm;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ChatUtils.NLPProcessor;
import net.wcfcarolina13.ServiceLLMClients.LLMClient;
import net.wcfcarolina13.FilingSystem.LLMClientFactory;
import net.wcfcarolina13.ServiceLLMClients.LLMServiceHandler;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central hub for routing chat messages through the LLM pipeline.
 * Phase 1: only handles enablement toggles and a single entry point for intent
 * detection; actual skill execution still flows through existing handlers.
 */
public final class LLMOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("LLMOrchestrator");
    private static final AtomicInteger CHAT_THREAD_ID = new AtomicInteger(0);
    private static final ExecutorService CHAT_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "llm-orchestrator-" + CHAT_THREAD_ID.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final MemoryStore MEMORY_STORE = new MemoryStore();

    private LLMOrchestrator() {
    }

    /**
     * Enablement is read lazily from {@link ManualConfig} — the single source of truth.
     * (Previously push-populated in-memory maps: they made the GUI toggles write-only
     * until restart, keyed the world toggle to the overworld only, and let /bot llm
     * commands diverge from settings.json5.)
     */
    public static boolean isWorldEnabled() {
        return Frens.CONFIG != null && Frens.CONFIG.isDefaultLlmWorldEnabled();
    }

    public static boolean isBotEnabled(ServerPlayerEntity bot) {
        if (bot == null || Frens.CONFIG == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        String worldKey = server != null
                ? net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(server)
                : null;
        ManualConfig.BotControlSettings settings =
                Frens.CONFIG.getEffectiveBotControl(bot.getName().getString(), worldKey);
        return settings != null && settings.isLlmEnabled();
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
        if (!isWorldEnabled()) {
            return false;
        }
        if (!isBotEnabled(bot)) {
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
            String llmProvider = (Frens.CONFIG != null && Frens.CONFIG.getLlmMode() != null && !Frens.CONFIG.getLlmMode().isBlank())
                    ? Frens.CONFIG.getLlmMode()
                    : System.getProperty("frens.llmMode", System.getProperty("aiplayer.llmMode", "ollama"));
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
