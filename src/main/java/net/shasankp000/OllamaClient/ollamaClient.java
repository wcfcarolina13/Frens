package net.shasankp000.OllamaClient;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.models.chat.*;
import io.github.amithkoujalgi.ollama4j.core.types.OllamaModelType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.shasankp000.CommandUtils;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.shasankp000.AIPlayer;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.ChatUtils.Helper.RAG2;
import net.shasankp000.ChatUtils.NLPProcessor;
import net.shasankp000.Database.SQLiteDB;
import net.shasankp000.Exception.intentMisclassification;
import net.shasankp000.FunctionCaller.FunctionCallerV2;
import net.shasankp000.Overlay.ThinkingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.net.http.HttpTimeoutException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ollamaClient {

    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    private static OllamaChatResult chatResult;
    private static final String host = "http://localhost:11434";
    public static String botName = "";
    public static boolean isInitialized = false;
    public static String initialResponse = "";
    public static final OllamaAPI ollamaAPI = new OllamaAPI(host);
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>([\\s\\S]*?)</think>");
    private static final ExecutorService BOT_TASK_POOL = Executors.newCachedThreadPool();

    public static void runFromChat(String botName, String message, UUID playerUUID) {
        MinecraftServer server = AIPlayer.serverInstance;
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
        if (bot == null) {
            LOGGER.error("Bot {} not online.", botName);
            return;
        }
        ServerCommandSource botSource = bot.getCommandSource().withSilent();

        server.execute(() -> {
            try {
                routeIntent(message, botSource, playerUUID);
            } catch (Exception e) {
                LOGGER.error("Chat processing error: ", e);
                ChatUtils.sendChatMessages(botSource, "⚠️ I'm confused! Please report this.");
            }
        });
    }

    public static void execute(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        botName = EntityArgumentType.getPlayer(context, "bot").getName().getLiteralString();
        String message = StringArgumentType.getString(context, "message");

        MinecraftServer server = context.getSource().getServer();
        ServerCommandSource playerSource = context.getSource();
        ServerCommandSource botSource = Objects.requireNonNull(server.getPlayerManager().getPlayer(botName))
            .getCommandSource().withSilent();

        String formatter = ChatUtils.getRandomColorCode();

        server.execute(() -> {
            CommandUtils.run(playerSource, "say " + formatter + message);
            CommandUtils.run(botSource, "say Processing your message, please wait.");
        });

        server.execute(() -> {
            try {
                routeIntent(message, botSource, Objects.requireNonNull(playerSource.getPlayer()).getUuid());
            } catch (Exception e) {
                LOGGER.error("NLP error: ", e);
                ChatUtils.sendChatMessages(botSource, "⚠️ NLP issue. Report to developer.");
            }
        });
    }

    private static void routeIntent(String message, ServerCommandSource botSource, UUID playerUUID) throws Exception {
        NLPProcessor.Intent intent = NLPProcessor.getIntention(message);

        LOGGER.info("📨 Received intent: {}", intent);

        switch (intent) {
            case GENERAL_CONVERSATION, ASK_INFORMATION -> {
                BOT_TASK_POOL.submit(() -> {
                    Thread.currentThread().setName("RAG2-Worker");
                    LOGGER.info("🧵 Started RAG2 worker thread");
                    RAG2.run(message, botSource, intent);
                    LOGGER.info("✅ Finished RAG2 worker thread");
                });
            }

            case REQUEST_ACTION -> {
                BOT_TASK_POOL.submit(() -> {
                    Thread.currentThread().setName("Function-Caller-Worker");
                    LOGGER.info("🧵 Started FunctionCallerV2 worker thread");
                    try {
                        new FunctionCallerV2(botSource, playerUUID);
                        FunctionCallerV2.run(message);
                    } finally {
                        FunctionCallerV2.clearContext();
                    }
                    LOGGER.info("✅ Finished FunctionCallerV2 worker thread");
                });
            }

            default -> {
                LOGGER.warn("⚠️ Intent unclear, retrying with LLM classification...");
                ChatUtils.sendChatMessages(botSource, "🔍 Reanalyzing...");

                NLPProcessor.Intent retry = retryIntentLLM(message);

                LOGGER.info("📨 Retry intent: {}", retry);

                if (retry == NLPProcessor.Intent.GENERAL_CONVERSATION || retry == NLPProcessor.Intent.ASK_INFORMATION) {
                    BOT_TASK_POOL.submit(() -> {
                        Thread.currentThread().setName("RAG2-Retry-Worker");
                        LOGGER.info("🧵 Started RAG2 retry worker thread");
                        RAG2.run(message, botSource, retry);
                        LOGGER.info("✅ Finished RAG2 retry worker thread");
                    });
                } else if (retry == NLPProcessor.Intent.REQUEST_ACTION) {
                    BOT_TASK_POOL.submit(() -> {
                        Thread.currentThread().setName("Function-Caller-Retry-Worker");
                        LOGGER.info("🧵 Started FunctionCallerV2 retry worker thread");
                        try {
                            new FunctionCallerV2(botSource, playerUUID);
                            FunctionCallerV2.run(message);
                        } finally {
                            FunctionCallerV2.clearContext();
                        }
                        LOGGER.info("✅ Finished FunctionCallerV2 retry worker thread");
                    });
                } else {
                    throw new intentMisclassification("LLM failed to classify intent.");
                }
            }
        }
    }

    private static NLPProcessor.Intent retryIntentLLM(String message) {
        return NLPProcessor.getIntentionFromLLM(message);
    }

    public static boolean pingOllamaServer() {
        boolean reachable = ollamaAPI.ping();
        LOGGER.info("Ollama server alive: {}", reachable);
        return reachable;
    }

    public static void initializeOllamaClient() {
        if (isInitialized) return;

        MinecraftServer server = AIPlayer.serverInstance;
        if (server == null) {
            LOGGER.error("Server instance is null.");
            return;
        }

        ollamaAPI.setRequestTimeoutSeconds(90);
        String selectedLM = AIPlayer.CONFIG.getSelectedLanguageModel();
        LOGGER.info("Connecting to Ollama using model: {}", selectedLM);

        CompletableFuture.runAsync(() -> {
            int retries = 0;
            boolean success = false;

            while (!success && retries < 3) {
                try {
                    OllamaChatRequestModel request = OllamaChatRequestBuilder.getInstance(selectedLM)
                            .withMessage(OllamaChatMessageRole.SYSTEM, generateSystemPrompt())
                            .withMessage(OllamaChatMessageRole.USER, "Initializing chat.")
                            .build();

                    chatResult = ollamaAPI.chat(request);
                    initialResponse = chatResult.getResponse();
                    LOGGER.info("Ollama Client initialized. Initial response: {}", initialResponse);

                    server.execute(() ->
                            server.sendMessage(Text.of("§9" + botName + " is ready!"))
                    );

                    isInitialized = true;
                    success = true;

                } catch (HttpTimeoutException e) {
                    retries++;
                    LOGGER.error("Timeout initializing Ollama (attempt {}/3)", retries);
                } catch (Exception e) {
                    LOGGER.error("Failed initializing Ollama: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }

            if (!success) {
                LOGGER.error("Failed to initialize Ollama after 3 attempts.");
                server.sendMessage(Text.of("§c§lCould not establish uplink."));
            }
        });
    }

    private static String generateSystemPrompt() {

        return
                "You are a Minecraft player named " + botName + " who is connected to Minecraft using a mod. You exist within the Minecraft world and can interact with the player and the environment just like any other player in the game. Your job is to engage in conversations with the player, respond to their questions, offer help, and provide information about the game. Address the player directly and appropriately, responding to their name or as 'Player' if their name is not known. Do not refer to the player as " + botName + ", only address yourself as " + botName + " Keep your responses relevant to Minecraft and make sure to stay in character as a helpful and knowledgeable assistant within the game."
                        +
                        """
                        
                        When the player asks you to perform an action, such as providing information, offering help, or interacting with the game world, such as:
                        
                        Providing game tips or crafting recipes.
                        Giving information about specific Minecraft entities, items, or biomes.
                        Assisting with in-game tasks, like building structures or exploring areas.
                        Interacting with the environment, such as planting crops or fighting mobs.
                       
                        Always ensure your responses are timely and contextually appropriate, enhancing the player's gaming experience. Remember to keep track of the sequence of events and maintain continuity in your responses. If an event is primarily informational or involves internal actions, it may be sufficient just to remember it without a verbal response.
                        
                        If a player uses inappropriate language or discusses inappropriate topics, handle the situation by gently redirecting the conversation or by providing a neutral response that discourages further inappropriate behavior.
                        
                        For example:
                        
                        If a player uses vulgar language, you can respond with: "Let's keep our chat friendly and fun! Is there something else about Minecraft you'd like to discuss?"
                        If a player insists on inappropriate topics, you can say: "I'm here to help with Minecraft-related questions. How about we talk about your latest adventure in the game?"
                        If a player says these words "kill yourself" or "kys", you should respond calmly and normally and tell the player to see the beauty of life.
                        
                        
                        Your pronouns, are by default, to be addressed as the pronouns based on your name's gender (female/male). However if the player decides to address you with different pronouns, you must not object. For now, either introduce yourself or crack a random joke; the joke should be completely family-friendly, or just greet the player.
                        
                        The name Steve has the pronouns: he/him
                        The name Alex has the pronouns: she/her
                        
                        If the player asks you as to why you were put here in the first place: Remember that it was the developer's idea to solve the ever existing problem of loneliness in minecraft as much as possible by making this mod.
                        
                        For now introduce yourself with your name.
                        """;

    }

    public static void sendInitialResponse(ServerCommandSource botSource) {
        MinecraftServer server = botSource.getServer();

        // ✅ Schedule the WHOLE logic back to the main thread
        server.execute(() -> {
            processLLMOutput(initialResponse, botName, botSource);

            List<SQLiteDB.Memory> memories = SQLiteDB.fetchInitialResponse();
            if (memories.isEmpty()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        List<Double> embedding = ollamaAPI.generateEmbeddings(
                                OllamaModelType.NOMIC_EMBED_TEXT,
                                generateSystemPrompt()
                        );
                        SQLiteDB.storeMemory("conversation", generateSystemPrompt(), initialResponse, embedding);
                        LOGGER.info("✅ Saved initial response.");
                    } catch (Exception e) {
                        LOGGER.error("❌ Failed saving initial response: {}", e.getMessage(), e);
                    }
                });
            } else {
                LOGGER.info("🗃️ Initial response already in DB.");
            }
        });
    }

    public static void processLLMOutput(String fullResponse, String botName, ServerCommandSource botSource) {
        Matcher matcher = THINK_BLOCK.matcher(fullResponse);

        if (matcher.find()) {
            String thinking = matcher.group(1).trim();
            String remainder = fullResponse.replace(matcher.group(0), "").trim();

            ThinkingStateManager.start(botName);
            ChatUtils.sendChatMessages(botSource, botName + " is thinking...");

            for (String line : thinking.split("\\n")) {
                ThinkingStateManager.appendThoughtLine(line);
            }

            ThinkingStateManager.end();
            ChatUtils.sendChatMessages(botSource, botName + " is done thinking!");

            if (!remainder.isEmpty()) {
                ChatUtils.sendChatMessages(botSource, botName + ": " + remainder);
            }
        } else {
            ChatUtils.sendChatMessages(botSource, botName + ": " + fullResponse);
        }
    }

}
