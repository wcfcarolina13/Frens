package net.shasankp000.ChatUtils.Helper;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.types.OllamaModelType;
import net.shasankp000.AIPlayer;
import net.minecraft.server.command.ServerCommandSource;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.ChatUtils.NLPProcessor;
import net.shasankp000.Database.SQLiteDB;
import net.shasankp000.Overlay.ThinkingStateManager;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.ServiceLLMClients.LLMClient;
import net.shasankp000.WebSearch.WebSearchTool;
import net.shasankp000.Commands.modCommandRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RAG2 {

    private static final Logger logger = LoggerFactory.getLogger("ai-player");
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>([\\s\\S]*?)</think>");
    private static final int TOP_K = 5;

    private static OllamaAPI createOllamaApi() {
        String baseUrl = (AIPlayer.CONFIG != null)
                ? AIPlayer.CONFIG.getOllamaBaseUrl()
                : "http://127.0.0.1:11434";
        OllamaAPI api = new OllamaAPI(baseUrl);
        api.setRequestTimeoutSeconds(120);
        return api;
    }

    private static String buildPrompt() {
        return "You are a context-aware Minecraft player named " + modCommandRegistry.botName + """
            You can access past conversations and in-game events to help answer the player's current question.
            
            Use the provided context memories ONLY if they are relevant and useful.
            If they are irrelevant or missing, ignore them and respond normally — DO NOT mention that context was missing.
            
            When using context, you must describe it as past events in the PAST TENSE.
      
            📚 MEMORY RULES:
               - You have access to past conversations and events stored in your local database.
               - Use them ONLY if they are relevant to the player's question.
               - Treat them as trusted past experiences inside Minecraft — always refer to them in PAST TENSE.
               - Do not mention that you used "memories" — just naturally blend them in.
         
            🌐 WEB CONTEXT RULES:
               - Sometimes you will be given information retrieved from the official Minecraft wiki or reliable sources like Reddit.
               - Treat this as fresh factual information when provided.
               - If there is a conflict between your own training and the provided web result, trust the web result for factual details (e.g., crafting recipes, item stats).
               - Never hallucinate new information not in the context or your training.
            
            🧭 WHEN CONTEXT IS MISSING OR CONFLICTING:
               - If you have no context or web search data or if the web search fails, fall back on your own Minecraft knowledge.
               - If you have partial context, do your best to answer accurately.
               - If the player specifically asks for real-world or up-to-date Minecraft mechanics, prefer the web search result if given.
            
            Be concise, stay in character as a helpful Minecraft companion, and avoid repeating the context verbatim unless necessary.
            
            Important:
            - If the player asks for game info, use your built-in Minecraft knowledge too.
            - If the context includes a similar question or related event, summarize it naturally.
            - If multiple memories are similar, merge them to answer clearly.
            - Never make up details not in context.
            
            Remember:
            - The player prompt and context are always given separately.
            - You must analyze the player prompt carefully.
            - Do not break character — you are inside the Minecraft world.
            """;
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

    private static String getBestContextAnswer(String userPrompt, List<Double> queryEmbedding) {
        String webAnswer = WebSearchTool.search(userPrompt).trim();
        logger.info("🌐 Web search result: {}", webAnswer);

        List<SQLiteDB.Memory> localMemories = SQLiteDB.findRelevantMemories(queryEmbedding, "conversation", 1);
        boolean hasLocal = !localMemories.isEmpty();
        String localAnswer = hasLocal ? localMemories.get(0).response() : "";
        double localSimilarity = hasLocal ? localMemories.get(0).similarity() : 0.0;

        logger.info("🔍 Local similarity: {}", localSimilarity);

        // Decide which to trust
        String bestAnswer;
        if (!webAnswer.isBlank()) {
            if (!webAnswer.equalsIgnoreCase(localAnswer)) {
                bestAnswer = webAnswer;
                logger.info("✅ Using web answer, overwriting local DB");
                SQLiteDB.storeMemory("conversation", userPrompt, bestAnswer, queryEmbedding);
            } else {
                bestAnswer = localAnswer;
                logger.info("✅ Local and web match, using local");
            }
        } else if (hasLocal && localSimilarity >= 0.8) {
            bestAnswer = localAnswer;
            logger.info("✅ Using local answer, web empty");
        } else {
            bestAnswer = "❌ No relevant info found.";
            logger.warn("⚠️ Both web and local empty or not confident");
        }

        return bestAnswer;
    }


    public static void run(String userPrompt, ServerCommandSource botSource, NLPProcessor.Intent intent, LLMClient client) {
        if (client == null) {
            logger.warn("⚠️ RAG v2 invoked without an LLM client; aborting.");
            ChatUtils.sendChatMessages(botSource, "LLM backend unavailable. Please double-check /bot llm settings.");
            return;
        }
        logger.info("⚡ RAG v2: Running with intent = {} using provider {}", intent, client.getProvider());

        try {
            OllamaAPI embeddingApi = createOllamaApi();
            List<Double> queryEmbedding = embeddingApi.generateEmbeddings(OllamaModelType.NOMIC_EMBED_TEXT, userPrompt);

            StringBuilder contextBuilder = new StringBuilder();

            if (intent == NLPProcessor.Intent.ASK_INFORMATION) {
                ChatUtils.sendChatMessages(botSource, "Running web search....");
                String bestAnswer = getBestContextAnswer(userPrompt, queryEmbedding);

                if (bestAnswer.equalsIgnoreCase("❌ No relevant info found.")) {
                    ChatUtils.sendChatMessages(botSource, "No info found. Either there is no info on this topic or my web search tool is not working properly. Please report this to developer!");
                }
                else {
                    ChatUtils.sendChatMessages(botSource, "Web search complete.");
                }

                contextBuilder.append("Web/Local best answer:\n").append(bestAnswer).append("\n\n");

            } else {
                // 🤝 Just normal local vector recall
                List<SQLiteDB.Memory> localMemories = SQLiteDB.findRelevantMemories(queryEmbedding, "conversation", TOP_K);
                contextBuilder.append("Relevant conversations:\n");
                for (SQLiteDB.Memory m : localMemories) {
                    contextBuilder.append("- Prompt: ").append(m.prompt()).append("\n");
                    contextBuilder.append("  Response: ").append(m.response()).append("\n");
                    contextBuilder.append("  Similarity: ").append(m.similarity()).append("\n\n");
                }
            }

            // 🗃️ Add relevant events in all cases
            List<SQLiteDB.Memory> events = SQLiteDB.findRelevantMemories(queryEmbedding, "event", TOP_K);
            contextBuilder.append("Relevant events:\n");
            for (SQLiteDB.Memory m : events) {
                contextBuilder.append("- Prompt: ").append(m.prompt()).append("\n");
                contextBuilder.append("  Response: ").append(m.response()).append("\n");
                contextBuilder.append("  Similarity: ").append(m.similarity()).append("\n\n");
            }

            // ✨ Final LLM prompt
            String systemPrompt = buildPrompt();
            String finalUserPrompt = "Context:\n" + contextBuilder.toString().trim() + "\n\nUser prompt:\n" + userPrompt;

            String finalResponse = client.sendPrompt(systemPrompt, finalUserPrompt);

            processLLMOutput(finalResponse, botSource.getName(), botSource);

            // 🔒 Always store final response
            SQLiteDB.storeMemory("conversation", userPrompt, finalResponse, queryEmbedding);

            logger.info("✅ RAG v2 finished with intent-aware strategy.");

        } catch (Exception e) {
            logger.error("❌ RAG v2 failed: {}", e.getMessage(), e);
            ChatUtils.sendChatMessages(botSource, "Sorry, I couldn't find enough context. Please try again!");
        }
    }

    // overloaded method for the existing ollama client to work with.

    public static void run(String userPrompt, ServerCommandSource botSource, NLPProcessor.Intent intent) {
        String provider = "ollama";
        if (AIPlayer.CONFIG != null && AIPlayer.CONFIG.getLlmMode() != null && !AIPlayer.CONFIG.getLlmMode().isBlank()) {
            provider = AIPlayer.CONFIG.getLlmMode();
        } else {
            provider = System.getProperty("aiplayer.llmMode", "ollama");
        }

        LLMClient client = LLMClientFactory.createClient(provider);
        if (client == null) {
            logger.warn("⚠️ Unable to create LLM client for provider '{}'", provider);
            ChatUtils.sendChatMessages(botSource, "LLM backend is not configured yet. Try /configman or /bot llm to set it up.");
            return;
        }

        run(userPrompt, botSource, intent, client);
    }
}
