// Kudos to this guy, Matt Williams, https://www.youtube.com/watch?v=IdPdwQdM9lA, for opening my eyes on function calling.

package net.shasankp000.FunctionCaller;

import com.google.gson.*;

import com.google.gson.stream.JsonReader;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;

import io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException;

import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessageRole;

import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatRequestBuilder;

import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatRequestModel;

import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatResult;

import io.github.amithkoujalgi.ollama4j.core.types.OllamaModelType;

import java.io.IOException;

import java.io.StringReader;

import java.sql.SQLException;

import java.util.*;

import java.util.concurrent.*;

import java.time.format.DateTimeFormatter;

import java.time.LocalDateTime;

import java.util.function.BiFunction;

import java.util.regex.Matcher;

import java.util.regex.Pattern;

import net.minecraft.server.MinecraftServer;

import net.minecraft.server.command.ServerCommandSource;

import net.minecraft.server.network.ServerPlayerEntity;

import net.minecraft.util.math.BlockPos;

import net.minecraft.util.math.Box;

import net.minecraft.util.math.Direction;

import net.shasankp000.AIPlayer;

import net.shasankp000.ChatUtils.ChatContextManager;

import net.shasankp000.ChatUtils.ChatUtils;

import net.shasankp000.CommandUtils;

import net.shasankp000.Entity.EntityDetails;

import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.State;

import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;

import net.shasankp000.Database.OldSQLiteDB;

import net.shasankp000.Entity.AutoFaceEntity;

import net.shasankp000.Entity.LookController;

import net.shasankp000.Overlay.ThinkingStateManager;

import net.shasankp000.PathFinding.ChartPathToBlock;

import net.shasankp000.PathFinding.GoTo;

import net.shasankp000.PathFinding.PathTracer;

import net.shasankp000.PlayerUtils.*;

import net.shasankp000.ServiceLLMClients.LLMClient;
import net.shasankp000.Commands.modCommandRegistry;

import net.shasankp000.WebSearch.WebSearchTool;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import static net.shasankp000.ChatUtils.Helper.JsonUtils.cleanJsonString;

public class FunctionCallerV2 {

    private static final Logger logger = LoggerFactory.getLogger("function-caller");

    private static ServerCommandSource botSource = null;

    private static final String DB_URL = "jdbc:sqlite:" + "./sqlite_databases/" + "memory_agent.db";

    private static final String host = "http://localhost:11434/";

    private static final OllamaAPI ollamaAPI = new OllamaAPI(host);

    private static volatile String functionOutput = null;

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    private static final Map<String, Object> sharedState = new ConcurrentHashMap<>();  // Updated to Map<String, Object>

    private static UUID playerUUID;



    private static String selectedLM = AIPlayer.CONFIG.getSelectedLanguageModel();

    // Constants for JSON keys
    private static final String FUNCTION_NAME_KEY = "functionName";
    private static final String PIPELINE_KEY = "pipeline";
    private static final String CLARIFICATION_KEY = "clarification";
    private static final String PARAMETERS_KEY = "parameters";
    private static final String PARAMETER_NAME_KEY = "parameterName";
    private static final String PARAMETER_VALUE_KEY = "parameterValue";

    public FunctionCallerV2(ServerCommandSource botSource, UUID playerUUID) {
        FunctionCallerV2.botSource = botSource;
        ollamaAPI.setRequestTimeoutSeconds(90);
        FunctionCallerV2.playerUUID = playerUUID;
    }

    public static Map<String, Object> getSharedState() {
        return sharedState;
    }

    private static class ExecutionRecord {
        String timestamp;
        String command;
        List<Double> eventEmbedding;
        List<Double> eventContextEmbedding;
        List<Double> eventResultEmbedding;
        String result;
        String context;

        private ExecutionRecord(String Timestamp, String command, String context, String result, List<Double> eventEmbedding, List<Double> eventContextEmbedding, List<Double> eventResultEmbedding) {
            this.context = context;
            this.timestamp = Timestamp;
            this.command = command;
            this.eventEmbedding = eventEmbedding;
            this.eventContextEmbedding = eventContextEmbedding;
            this.eventResultEmbedding = eventResultEmbedding;
            this.result = result;
        }

        private void updateRecords() {
            try {
                OldSQLiteDB.storeEventWithEmbedding(DB_URL, this.command, this.context, this.result, this.eventEmbedding, this.eventContextEmbedding, this.eventResultEmbedding);
            } catch (SQLException e) {
                logger.error("Caught exception: {} ", (Object) e.getStackTrace());
                throw new RuntimeException(e);
            }
        }
    }

    private static String getCurrentDateandTime() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();
        return dtf.format(now);
    }

    private static void getFunctionOutput(String method) {
        functionOutput = String.valueOf(method);
    }

    private static class Tools {
        /** goTo tool: path finder + tracer **/
        private static void goTo(int x, int y, int z, boolean sprint) {
            System.out.println("Going to coordinates: " + x + ", " + y + ", " + z + " | Sprint: " + sprint);
            if (botSource == null) {
                System.out.println("Bot not found.");
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // ✅ Call the method and wait for result
                String result = GoTo.goTo(botSource, x, y, z, sprint);
                // ✅ Ensure we have a valid result for parsing
                if (result == null || result.trim().isEmpty()) {
                    // Fallback: get current bot position
                    ServerPlayerEntity bot = botSource.getPlayer();
                    if (bot != null) {
                        BlockPos pos = bot.getBlockPos();
                        result = String.format("Bot position - x: %d y: %d z: %d",
                                pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                getFunctionOutput(result);
            } catch (Exception e) {
                logger.error("Error in goTo: ", e);
                getFunctionOutput("Failed to navigate to coordinates: " + e.getMessage());
            }
        }

        /** chartPathToBlock: final positioning **/
        private static void chartPathToBlock(int targetX, int targetY, int targetZ, String blockType) {
            System.out.println("Charting path to block at: " + targetX + ", " + targetY + ", " + targetZ + " | BlockType: " + blockType);
            getFunctionOutput(ChartPathToBlock.chart(Objects.requireNonNull(botSource.getPlayer()), new BlockPos(targetX, targetY, targetZ), blockType));
        }

        /** faceBlock: look at block **/
        private static void faceBlock(int targetX, int targetY, int targetZ) {
            System.out.println("Facing block at: " + targetX + ", " + targetY + ", " + targetZ);
            getFunctionOutput(LookController.faceBlock(Objects.requireNonNull(botSource.getPlayer()), new BlockPos(targetX, targetY, targetZ)));
        }

        /** faceEntity: look at entity **/
        private static void faceEntity(int targetX, int targetY, int targetZ) {
            System.out.println("Facing entity at: " + targetX + ", " + targetY + ", " + targetZ);
            ServerPlayerEntity bot = Objects.requireNonNull(botSource.getPlayer());
            // Get the world
            var world = bot.getEntityWorld();
            // Create a small bounding box around the coordinates to find nearby entities
            var box = new Box(
                    targetX - 1, targetY - 1, targetZ - 1,
                    targetX + 1, targetY + 1, targetZ + 1
            );
            // Get all entities except the bot itself
            var entities = world.getOtherEntities(bot, box);
            if (entities.isEmpty()) {
                System.out.println("No entity found at given coordinates.");
                getFunctionOutput("No entity found at given coordinates.");
                return;
            }
            // Take the first entity found
            var target = entities.get(0);
            LookController.faceEntity(bot, target);
            getFunctionOutput("Facing entity: " + target.getName().getString());
        }

        /** detectBlocks: raycast with block type filter **/
        private static void detectBlocks(String blockType) {
            System.out.println("Detecting blocks of type: " + blockType);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                BlockPos outputPos = blockDetectionUnit.detectBlocks(
                        Objects.requireNonNull(botSource.getPlayer()), blockType);
                String output;
                if (outputPos == null) {
                    output = "Block not found!";
                } else {
                    SharedStateUtils.setValue(sharedState, "lastDetectedBlock.pos", outputPos);
                    SharedStateUtils.setValue(sharedState, "lastDetectedBlock.x", outputPos.getX());
                    SharedStateUtils.setValue(sharedState, "lastDetectedBlock.y", outputPos.getY());
                    SharedStateUtils.setValue(sharedState, "lastDetectedBlock.z", outputPos.getZ());
                    output = "Block found at " + outputPos.getX() + " " + outputPos.getY() + " " + outputPos.getZ();
                }
                getFunctionOutput(output);
            } catch (Exception e) {
                logger.error("Error in detectBlocks: ", e);
                getFunctionOutput("Failed to detect blocks: " + e.getMessage());
            }
        }

        /** turn: change torso facing direction **/
        private static void turn(String direction) {
            System.out.println("Turning to: " + direction);
            MinecraftServer server = botSource.getServer();
            String botName = botSource.getName();
            if (server == null) {
                getFunctionOutput("Server unavailable. Cannot execute turn command.");
                return;
            }
            CommandUtils.run(botSource, "player " + botName + " turn " + direction);
            getFunctionOutput("Now facing " + direction + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getFacing().asString() + " in " + Objects.requireNonNull(botSource.getPlayer()).getFacing().getAxis().asString() + " axis.");
        }

        /** look: change head facing direction **/
        private static void look(String cardinalDirection) {
            System.out.println("Looking at: " + cardinalDirection);
            MinecraftServer server = botSource.getServer();
            String botName = botSource.getName();
            if (server == null) {
                getFunctionOutput("Server unavailable. Cannot execute look command.");
                return;
            }
            CommandUtils.run(botSource, "player " + botName + " look " + cardinalDirection);
            getFunctionOutput("Now facing cardinal direction: " + Objects.requireNonNull(botSource.getPlayer()).getFacing().asString() + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getFacing().getAxis().asString() + " axis.");
        }

        /** mineBlock: break block **/
        private static void mineBlock(int targetX, int targetY, int targetZ) {
            System.out.println("Mining block at: " + targetX + ", " + targetY + ", " + targetZ);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // ✅ Use CompletableFuture.get() to wait for completion
                CompletableFuture<String> miningFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return MiningTool.mineBlock(
                                Objects.requireNonNull(botSource.getPlayer()),
                                new BlockPos(targetX, targetY, targetZ)
                        ).get();
                    } catch (Exception e) {
                        e.printStackTrace();
                        return "⚠️ Failed to mine block: " + e.getMessage();
                    }
                });
                // Wait for result with timeout
                String result = miningFuture.get(10, TimeUnit.SECONDS);
                getFunctionOutput(result);
            } catch (Exception e) {
                logger.error("Error in mineBlock: ", e);
                getFunctionOutput("⚠️ Failed to mine block: " + e.getMessage());
            }
        }

        /** getOxygenLevel: report air level **/
        private static void getOxygenLevel() {
            System.out.println("Getting oxygen level...");
            getFunctionOutput("Oxygen Level: " + getPlayerOxygen.getBotOxygenLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** getHungerLevel: report hunger **/
        private static void getHungerLevel() {
            System.out.println("Getting hunger level...");
            getFunctionOutput("Hunger Level: " + getPlayerHunger.getBotHungerLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** getHealthLevel: report health **/
        private static void getHealthLevel() {
            System.out.println("Getting health level...");
            getFunctionOutput("Remaining hearts: " + getHealth.getBotHealthLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        private static void followPlayer(String targetName) {
            ServerPlayerEntity bot = Objects.requireNonNull(botSource.getPlayer());
            MinecraftServer server = botSource.getServer();
            if (server == null) {
                getFunctionOutput("Server unavailable.");
                return;
            }
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetName);
            String result = BotEventHandler.setFollowMode(bot, target);
            getFunctionOutput(result);
        }

        private static void guardArea(double radius) {
            ServerPlayerEntity bot = Objects.requireNonNull(botSource.getPlayer());
            String result = BotEventHandler.setGuardMode(bot, radius);
            getFunctionOutput(result);
        }

        private static void stayPut() {
            ServerPlayerEntity bot = Objects.requireNonNull(botSource.getPlayer());
            String result = BotEventHandler.setStayMode(bot);
            getFunctionOutput(result);
        }

        private static void returnToBase() {
            ServerPlayerEntity bot = Objects.requireNonNull(botSource.getPlayer());
            String result = BotEventHandler.setReturnToBase(bot);
            getFunctionOutput(result);
        }

        private static void toggleAssist(String modeRaw) {
            ServerPlayerEntity bot = Objects.requireNonNull(botSource.getPlayer());
            String normalized = modeRaw == null ? "on" : modeRaw.toLowerCase(Locale.ROOT);
            boolean enable;
            switch (normalized) {
                case "off", "false", "no", "disable", "stop" -> enable = false;
                default -> enable = true;
            }
            String result = BotEventHandler.toggleAssistAllies(bot, enable);
            getFunctionOutput(result);
        }

        private static void webSearch(String query) {
            System.out.println("Running web search...");
            getFunctionOutput("Web search result: " + WebSearchTool.search(query));
        }

        private static void sendMessageToChat(String message) {
            System.out.println("Sending message to chat...");
            ChatUtils.sendChatMessages(botSource, message);
        }

        /** cultivateLand: cultivate a dirt block **/
        private static void cultivateLand(int targetX, int targetY, int targetZ) {
            System.out.println("Cultivating land at: " + targetX + ", " + targetY + ", " + targetZ);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // 1. Go to the block
                String goToResult = GoTo.goTo(botSource, targetX, targetY, targetZ, false);
                if (goToResult.contains("Failed")) {
                    getFunctionOutput("Failed to go to block: " + goToResult);
                    return;
                }
                // 2. Use hoe on the block
                boolean useHoeResult = BotActions.useHoe(Objects.requireNonNull(botSource.getPlayer()), new BlockPos(targetX, targetY, targetZ));
                getFunctionOutput(String.valueOf(useHoeResult));
            } catch (Exception e) {
                logger.error("Error in cultivateLand: ", e);
                getFunctionOutput("Failed to cultivate land: " + e.getMessage());
            }
        }

        /** chopWood: chop a wood block **/
        private static void chopWood(String treeType) {
            System.out.println("Chopping wood of type: " + treeType);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // 1. Detect the block
                blockDetectionUnit.detectBlocks(Objects.requireNonNull(botSource.getPlayer()), treeType);
                BlockPos detectedPos = (BlockPos) SharedStateUtils.getValue(sharedState, "lastDetectedBlock.pos");
                if (detectedPos == null) {
                    getFunctionOutput("No " + treeType + " found.");
                    return;
                }
                // 2. Go to the block
                String goToResult = GoTo.goTo(botSource, detectedPos.getX(), detectedPos.getY(), detectedPos.getZ(), false);
                if (goToResult.contains("Failed")) {
                    getFunctionOutput("Failed to go to block: " + goToResult);
                    return;
                }
                // 3. Mine the block
                CompletableFuture<String> miningFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return MiningTool.mineBlock(
                                Objects.requireNonNull(botSource.getPlayer()),
                                detectedPos
                        ).get();
                    } catch (Exception e) {
                        e.printStackTrace();
                        return "⚠️ Failed to mine block: " + e.getMessage();
                    }
                });
                String mineResult = miningFuture.get(10, TimeUnit.SECONDS);
                getFunctionOutput(mineResult);
            } catch (Exception e) {
                logger.error("Error in chopWood: ", e);
                getFunctionOutput("Failed to chop wood: " + e.getMessage());
            }
        }

        /** shovelDirt: detect and mine dirt block **/
        private static void shovelDirt() {
            System.out.println("Shoveling dirt via skill manager...");
            if (botSource == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                SkillExecutionResult result = SkillManager.runSkill(
                        "dirt_shovel",
                        new SkillContext(botSource, sharedState)
                );
                getFunctionOutput(result.message());
            } catch (Exception e) {
                logger.error("Error executing dirt_shovel skill: ", e);
                getFunctionOutput("Failed to shovel dirt: " + e.getMessage());
            }
        }
    }

    private static String toolBuilder() {
        var gson = new Gson();
        List<Map<String, Object>> functions = ToolRegistry.TOOLS.stream().map(tool -> {
            return Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    PARAMETERS_KEY, tool.parameters().stream().map(param -> Map.of(
                            "name", param.name(),
                            "description", param.description(),
                            "required", true
                    )).toList()
            );
        }).toList();
        return gson.toJson(Map.of("functions", functions));
    }

    // This code right here is pure EUREKA moment.
    private static String buildPrompt(String toolString) {
        return """
            You are a first-principles reasoning **function-caller AI agent** for a Minecraft bot.
            
            You will be provided with additional context information of the minecraft bot you are controlling. Use that information well to carefully plan your approach.
            
            Your role is to analyze player prompts carefully and decide which tool or sequence of tools best accomplishes the task. 
            You must output your decision strictly as JSON, following the required schema.
            
            ---
            
            Key Principles
            
            1. **Use only the tools you have.** 
            Do not hallucinate new tools. Each tool has clear parameters, a purpose, and trade-offs.
            
            2. **Use the fewest tools possible.** 
            When a single tool is enough, use it. 
            When multiple tools must be chained, output them as a pipeline in the correct order.
            
            3. **Focus on action verbs.** 
            Player prompts contain action verbs that reveal intent: go, walk, navigate, check, search, mine, approach, align, harvest, etcetera. 
            Always match these to the most relevant tools.
            
            4. **Use $placeholders for shared state.** 
            If a step depends on output from a previous step, use `$lastDetectedBlock.x` (etc.). 
            The runtime will substitute these dynamically.
            
            ---
            
            Execution Loop
            
            After each step, you will be given the output of the previous function.
            You must decide what to do next:
            - Continue with the remaining steps in the pipeline.
            - Retry the same function with adjusted parameters.
            - Abandon the current pipeline and create a completely new pipeline.
            
            ---
            
            ### **Examples**
            
            ✅ **Continue:**
            If you are satisfied, do nothing — the next step will execute automatically.
            
            ✅ **Retry:**
            If the previous function failed or needs adjustments, output:
            {
              "functionName": "goTo",
              "parameters": [
                { "parameterName": "x", "parameterValue": "12" },
                { "parameterName": "y", "parameterValue": "65" },
                { "parameterName": "z", "parameterValue": "-20" },
                { "parameterName": "sprint", "parameterValue": "false" }
              ]
            }
            
            ✅ Rebuild pipeline:
            If the plan must change completely, output:
            {
              "pipeline": [
                {
                  "functionName": "detectBlocks",
                  "parameters": [
                    { "parameterName": "blockType", "parameterValue": "stone" }
                  ]
                },
                {
                  "functionName": "goTo",
                  "parameters": [
                    { "parameterName": "x", "parameterValue": "$lastDetectedBlock.x" },
                    { "parameterName": "y", "parameterValue": "$lastDetectedBlock.y" },
                    { "parameterName": "z", "parameterValue": "$lastDetectedBlock.z" },
                    { "parameterName": "sprint", "parameterValue": "true" }
                  ]
                }
              ]
            }
            
            ---
            
            When to chain tools:
            
            For example if the player outputs: "Can you fetch me some wood?" or "Can you mine some iron?" or "Can you plant the wheat seeds from the chest?"
            
            These type of requests are requests which involve multi-step actions, each action chained in a particular order.
            
            To fulfill these type of requests you need to chain the tools you have at your disposal in a specific order by understanding what each tool does and how each tool works.
            
            ---
            
            How you must output:
            
            If you only need to use one tool, output in this JSON format.
            
            {
              "functionName": "searchBlock",
              "parameters": [
                { "parameterName": "direction", "parameterValue": "front" }
              ]
            }
            
            If you need multiple tools in a sequence, output as follows:
            
            {
              "pipeline": [
                {
                  "functionName": "searchBlock",
                  "parameters": [
                    { "parameterName": "direction", "parameterValue": "front" }
                  ]
                },
                {
                  "functionName": "goTo",
                  "parameters": [
                    { "parameterName": "x", "parameterValue": "$lastDetectedBlock.x" },
                    { "parameterName": "y", "parameterValue": "$lastDetectedBlock.y" },
                    { "parameterName": "z", "parameterValue": "$lastDetectedBlock.z" }
                  ]
                }
              ]
            }
            
            ---
            
            REMEMBER:
            
            ✅ Always use $placeholders when a step depends on values returned by a previous step. The pipeline executor will resolve these placeholders dynamically with the correct output from the previous step.
            
            ✅ Always use the " + PARAMETER_NAME_KEY + " and " + PARAMETER_VALUE_KEY + " fields exactly — do not rename them.
            
            ✅ Do not output any extra words, explanations, or formatting — only valid JSON.
            
            ===
            
            Final reminders:
            
            ✅ Only output valid JSON.
            
            ✅ Do not output any other text.
            
            ✅ Do not change field names.
            
            ✅ Be logical — always use the simplest pipeline that fully achieves the goal.
            
            ✅ The runtime will parse your JSON exactly as you return it.
            
            ---
            
            If the prompt is ambiguous, choose the minimal safe path or ask for clarification.
            
            If you cannot confidently select the correct tools because the prompt is ambiguous or incomplete,
            do NOT guess.
            
            Instead, output JSON like this:
            
            {
              "clarification": "Could you please clarify which type of block I should search for?"
            }
            
            Your clarification should be concise, specific, and related to Minecraft context.
            
            ✅ Never output any other words — only valid JSON.
            
            ✅ The runtime will deliver your clarification question to the player and wait for their answer.
            
            ✅ After receiving the answer, decide on how you should continue things from there onwards. If you again need more clarification, ask again.
            
            ---
            
            Available Tools
            
            Below is your list of tools, each with its name, description, required parameters and the key names:
            
            """ + toolString +
                """
                
                And the correct placeholders to use per tool: \n
                
                """ + functionStateKeyMap + "\n Do remember to add a placeholder symbol: $ in front of each parameter name when designing the pipeline json.";
    }

    private static String generatePromptContext(String userPrompt) {
        String contextOutput = "";
        String sysPrompt = """
        You are a context generation AI agent in terms of minecraft. \n
        This means that you will have a prompt from a user, who is the player and you need to analyze the context of the player's prompts, i.e what the player means by the prompt. \n
        This context information will then be used by a minecraft bot to understand what the user is trying to say. \n
        \n
        Here are some example player prompts you may receive: \n
        1. Could you check if there is a block in front of you? \n
        2. Look around for any hostile mobs, and report to me if you find any. \n
                3. Could you mine some stone and bring them to me? \n\n        5. Please go to coordinates 10 -60 20. \n\n
        \n
        A few more variations of the prompts may be: \n
        "Could you search for blocks in front of you?"
        "Do you see if there is a block in front of you?"
        "Can you mine some stone and bring them to me?
        "Please move to 10 -60 20." or "Please go to the coords 10 -60 20" or "Please go to 10 -60 20" and so on... \n
        \n
        Here are some examples of the format in which you MUST answer.
        \n
        1. The player asked you to check whether there is a block in front of you or not. \n
        2. The player asked you to search for hostile mobs around you, and to report to the player if you find any such hostile mob. \n
                3. The player asked you to mine some stone and then bring the stone to the player. \n\n        4. The player asked you to go to coordinates 10 -60 20. You followed the instructions and began movement to the coordinates. \n\n
        Remember that all the context you generate should be in the past tense, sense it is being recorded after the deed has been done.
        \n
        "Remember that when dealing with prompts that ask the bot to go to a specific set of x y z coordinates, you MUST NOT alter the coordinates, they SHOULD BE the exact same as in the prompt given by the player.
        \n
        Now,remember that you must only generate the context as stated in the examples, nothing more, nothing less. DO NOT add your own opinions/statements/thinking to the context you generate. \n
        Remember that if you generate incorrect context then the bot will not be able to understand what the user has asked of it.
        \s
        \s""";
        OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(selectedLM);
        try {
            OllamaChatRequestModel requestModel = builder
                    .withMessage(OllamaChatMessageRole.SYSTEM, sysPrompt)
                    .withMessage(OllamaChatMessageRole.USER, "Player prompt: " + userPrompt)
                    .build();
            OllamaChatResult chatResult = ollamaAPI.chat(requestModel);
            contextOutput = chatResult.getResponse();
        } catch (OllamaBaseException | IOException | InterruptedException | JsonSyntaxException e) {
            logger.error("Error generating prompt context: {}", e.getMessage(), e);
        }
        return contextOutput;
    }

    public static String buildLLMBotContext(State state, Map<String, Object> sharedState, Map<String, Object> surroundingsSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bot current state:\n");
        sb.append("- Position: (").append(state.getBotX()).append(", ").append(state.getBotY()).append(", ").append(state.getBotZ()).append(")\n");

        Object direction = SharedStateUtils.getValue(sharedState, "facing.direction");
        if (direction != null) {
            sb.append("- Facing: ").append(String.valueOf(direction));
            Object facing = SharedStateUtils.getValue(sharedState, "facing.facing");
            if (facing != null) {
                sb.append(" (").append(String.valueOf(facing));
                Object axis = SharedStateUtils.getValue(sharedState, "facing.axis");
                if (axis != null) {
                    sb.append(", axis: ").append(String.valueOf(axis));
                }
                sb.append(")");
            }
            sb.append("\n");
        } else {
            // first time call.
            assert botSource.getPlayer() != null;
            Direction facingDir = botSource.getPlayer().getFacing();
            sb.append("- Facing: ").append(facingDir.asString());
            sb.append(" (axis: ").append(facingDir.getAxis().asString()).append(")");
        }

        sb.append("- Selected Item: ").append(state.getSelectedItem()).append("\n");
        if (!state.getNearbyBlocks().isEmpty()) {
            sb.append("- Nearby Blocks: ").append(state.getNearbyBlocks().stream().limit(3).toList()).append("\n");
        }

        if (!state.getNearbyEntities().isEmpty()) {
            List<String> nearbyHostiles = state.getNearbyEntities().stream()
                    .filter(EntityDetails::isHostile)
                    .map(EntityDetails::getName)
                    .limit(2)
                    .toList();
            if (!nearbyHostiles.isEmpty()) {
                sb.append("- Nearby Hostile Entities: ").append(nearbyHostiles).append("\n");
            }
        }

        sb.append("- Health: ").append(state.getBotHealth()).append("\n");
        sb.append("- Hunger: ").append(state.getBotHungerLevel()).append("\n");
        sb.append("- Oxygen: ").append(state.getBotOxygenLevel()).append("\n");
        if (state.getDistanceToDangerZone() > 0) {
            sb.append("- Bot is close to a danger zone\n");
        }

        // Inject summarized surroundings
        if (surroundingsSummary != null && !surroundingsSummary.isEmpty()) {
            sb.append("- Immediate surroundings:\n");
            for (Map.Entry<String, Object> entry : surroundingsSummary.entrySet()) {
                sb.append(" • ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    public static void run(String userPrompt) {
        ollamaAPI.setRequestTimeoutSeconds(600);
        OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(selectedLM);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        String response;
        State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
        InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
        map.updateMap();
        // If method returns Map<String, String>
        Map<String, String> surroundingsStr = map.summarizeSurroundings();
        Map<String, Object> surroundings = new HashMap<>();
        for (Map.Entry<String, String> entry : surroundingsStr.entrySet()) {
            surroundings.put(entry.getKey(), entry.getValue());
        }

        String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
        String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
        try {
            OllamaChatRequestModel requestModel = builder
                    .withMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt)
                    .withMessage(OllamaChatMessageRole.USER, userPrompt)
                    .build();
            OllamaChatResult chatResult = ollamaAPI.chat(requestModel);
            response = chatResult.getResponse();
            logger.info("Raw LLM Response: {}", response);
            String jsonPart = extractJson(response);
            logger.info("Extracted JSON: {}", jsonPart);
        } catch (Exception e) {
            logger.error("Error in Function Caller (Ollama): {}", e.getMessage(), e);
        }
    }

    public static void run(String userPrompt, LLMClient client) {
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        String response;
        State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
        InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
        map.updateMap();
        // If method returns Map<String, String>
        Map<String, String> surroundingsStr = map.summarizeSurroundings();
        Map<String, Object> surroundings = new HashMap<>();
        for (Map.Entry<String, String> entry : surroundingsStr.entrySet()) {
            surroundings.put(entry.getKey(), entry.getValue());
        }

        String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
        String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
        try {
            response = client.sendPrompt(fullSystemPrompt, userPrompt);
            logger.info("Raw LLM Response: {}", response);
            String jsonPart = extractJson(response);
            logger.info("Extracted JSON: {}", jsonPart);
            executeFunction(userPrompt, jsonPart, client);
        } catch (Exception e) {
            logger.error("Error in Function Caller (LLMClient): {}", e.getMessage(), e);
        }
    }

    private static String extractJson(String response) {
        // Try to locate either a JSON object or array
        int objStart = response.indexOf("{");
        int objEnd = response.lastIndexOf("}") + 1;
        int arrStart = response.indexOf("[");
        int arrEnd = response.lastIndexOf("]") + 1;
        // Try full JSON object (most likely case)
        if (objStart != -1 && objEnd != -1 && objEnd > objStart) {
            String candidate = response.substring(objStart, objEnd);
            if (isValidJson(candidate)) return candidate;
        }
        // Try JSON array (secondary fallback)
        if (arrStart != -1 && arrEnd != -1 && arrEnd > arrStart) {
            String candidate = response.substring(arrStart, arrEnd);
            if (isValidJson(candidate)) return candidate;
        }
        logger.error("❌ Could not extract valid JSON from response:\n{}", response);
        return "{}";
    }

    private static boolean isValidJson(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void prepareForExternalCommand() {
        if (modCommandRegistry.isTrainingMode) {
            BotEventHandler.setExternalOverrideActive(true);
            logger.info("📴 Pausing training loop for external command");
        }
        PathTracer.flushAllMovementTasks();
        AutoFaceEntity.isBotMoving = false;
        if (botSource != null) {
            ServerPlayerEntity player = botSource.getPlayer();
            if (player != null) {
                BotActions.stop(player);
            }
        }
    }



    private static void executeFunction(String userInput, String response) {
        String executionDateTime = getCurrentDateandTime();
        try {
            executor.submit(() -> {
                String cleanedResponse = cleanJsonString(response);
                logger.info("Cleaned JSON Response: {}", cleanedResponse);
                JsonReader reader = new JsonReader(new StringReader(cleanedResponse));
                reader.setLenient(true);
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has(PIPELINE_KEY)) {
                    prepareForExternalCommand();
                    AutoFaceEntity.setBotExecutingTask(true);
                    JsonArray pipeline = jsonObject.getAsJsonArray(PIPELINE_KEY);
                    try {
                        logger.info("🧭 Executing pipeline with {} step(s)", pipeline.size());
                        System.out.println("[FunctionCaller] Received pipeline with " + pipeline.size() + " steps.");
                        runPipelineLoop(pipeline);
                    } catch (Exception e) {
                        logger.error("Error executing pipeline response: {}", e.getMessage(), e);
                    }
                } else if (jsonObject.has(FUNCTION_NAME_KEY)) {
                    prepareForExternalCommand();
                    String fnName = jsonObject.get(FUNCTION_NAME_KEY).getAsString();
                    JsonArray paramsArray = jsonObject.get(PARAMETERS_KEY).getAsJsonArray();
                    Map<String, String> paramMap = new ConcurrentHashMap<>();
                    StringBuilder params = new StringBuilder();
                    for (JsonElement parameter : paramsArray) {
                        JsonObject paramObj = parameter.getAsJsonObject();
                        String paramName = paramObj.get(PARAMETER_NAME_KEY).getAsString();
                        String paramValue = paramObj.get(PARAMETER_VALUE_KEY).getAsString();
                        paramValue = resolvePlaceholder(paramValue);
                        params.append(paramName).append("=").append(paramValue).append(", ");
                        paramMap.put(paramName, paramValue);
                    }
                    AutoFaceEntity.setBotExecutingTask(true);
                    try {
                        logger.info("Executing: {} with {}", fnName, paramMap);
                        callFunction(fnName, paramMap).join();
                    } catch (CompletionException ce) {
                        logger.error("Function {} execution failed: {}", fnName,
                                ce.getCause() != null ? ce.getCause().getMessage() : ce.getMessage(), ce);
                    } catch (Exception e) {
                        logger.error("Function {} execution failed: {}", fnName, e.getMessage(), e);
                    } finally {
                        AutoFaceEntity.setBotExecutingTask(false);
                        AutoFaceEntity.isBotMoving = false;
                        BotEventHandler.setExternalOverrideActive(false);
                    }
                } else if (jsonObject.has(CLARIFICATION_KEY)) {
                    System.out.println("Detected clarification");
                    String clarification = jsonObject.get(CLARIFICATION_KEY).getAsString();
                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getName());
                    // Relay to player in-game
                    // sendMessageToPlayer(clarification); // Removed as per refactoring
                } else {
                    throw new IllegalStateException("Response must have either " + FUNCTION_NAME_KEY + " or " + PIPELINE_KEY + ".");
                }
                // getFunctionResultAndSave(userInput, executionDateTime);
            });
        } catch (JsonSyntaxException | NullPointerException | IllegalStateException e) {
            logger.error("Error processing JSON response: {}", e.getMessage(), e);
        }
    }

    private static void executeFunction(String userInput, String response, LLMClient client) {
        String executionDateTime = getCurrentDateandTime();
        try {
            executor.submit(() -> {
                String cleanedResponse = cleanJsonString(response);
                logger.info("Cleaned JSON Response: {}", cleanedResponse);
                JsonReader reader = new JsonReader(new StringReader(cleanedResponse));
                reader.setLenient(true);
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has(PIPELINE_KEY)) {
                    prepareForExternalCommand();
                    AutoFaceEntity.setBotExecutingTask(true);
                    JsonArray pipeline = jsonObject.getAsJsonArray(PIPELINE_KEY);
                    try {
                        runPipelineLoop(pipeline, client);
                    } catch (Exception e) {
                        logger.error("Error executing pipeline response: {}", e.getMessage(), e);
                    }
                } else if (jsonObject.has(FUNCTION_NAME_KEY)) {
                    prepareForExternalCommand();
                    String fnName = jsonObject.get(FUNCTION_NAME_KEY).getAsString();
                    JsonArray paramsArray = jsonObject.get(PARAMETERS_KEY).getAsJsonArray();
                    Map<String, String> paramMap = new ConcurrentHashMap<>();
                    StringBuilder params = new StringBuilder();
                    for (JsonElement parameter : paramsArray) {
                        JsonObject paramObj = parameter.getAsJsonObject();
                        String paramName = paramObj.get(PARAMETER_NAME_KEY).getAsString();
                        String paramValue = paramObj.get(PARAMETER_VALUE_KEY).getAsString();
                        paramValue = resolvePlaceholder(paramValue);
                        params.append(paramName).append("=").append(paramValue).append(", ");
                        paramMap.put(paramName, paramValue);
                    }
                    AutoFaceEntity.setBotExecutingTask(true);
                    try {
                        logger.info("Executing: {} with {}", fnName, paramMap);
                        callFunction(fnName, paramMap).join();
                    } catch (CompletionException ce) {
                        logger.error("Function {} execution failed: {}", fnName,
                                ce.getCause() != null ? ce.getCause().getMessage() : ce.getMessage(), ce);
                    } catch (Exception e) {
                        logger.error("Function {} execution failed: {}", fnName, e.getMessage(), e);
                    } finally {
                        AutoFaceEntity.setBotExecutingTask(false);
                        AutoFaceEntity.isBotMoving = false;
                    }
                } else if (jsonObject.has(CLARIFICATION_KEY)) {
                    String clarification = jsonObject.get(CLARIFICATION_KEY).getAsString();
                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getName());
                    // Relay to player in-game
                    // sendMessageToPlayer(clarification); // Removed as per refactoring
                } else {
                    throw new IllegalStateException("Response must have either " + FUNCTION_NAME_KEY + " or " + PIPELINE_KEY + ".");
                }
                // getFunctionResultAndSave(userInput, executionDateTime);
            });
        } catch (JsonSyntaxException | NullPointerException | IllegalStateException e) {
            logger.error("Error processing JSON response: {}", e.getMessage(), e);
        }
    }





    private static void runPipelineLoop(JsonArray pipeline) {
        runPipeline(pipeline, null);
    }

    // overloaded method to handle the other LLM providers
    private static void runPipelineLoop(JsonArray pipeline, LLMClient client) {
        runPipeline(pipeline, client);
    }

    private static void runPipeline(JsonArray pipeline, LLMClient client) {
        try {
            runPipelineLoopInternal(pipeline, client);
        } finally {
            cleanupAfterPipeline();
        }
    }

    private static void cleanupAfterPipeline() {
        blockDetectionUnit.setIsBlockDetectionActive(false);
        PathTracer.flushAllMovementTasks();
        AutoFaceEntity.setBotExecutingTask(false);
        AutoFaceEntity.isBotMoving = false;
        if (modCommandRegistry.isTrainingMode) {
            BotEventHandler.setExternalOverrideActive(false);
        }
        boolean moving = PathTracer.BotSegmentManager.getBotMovementStatus();
        logger.info("✔️ Pipeline cleanup executed. movementStatus={} blockDetectionActive={}", moving, blockDetectionUnit.getBlockDetectionStatus());
        System.out.println("[FunctionCaller] Cleanup complete. movementStatus=" + moving);
    }

    private static void runPipelineLoopInternal(JsonArray pipeline, LLMClient client) {
        List<JsonObject> steps = new ArrayList<>();
        List<String> executedSteps = new ArrayList<>();
        for (JsonElement step : pipeline) {
            steps.add(step.getAsJsonObject());
        }

        Deque<JsonObject> pipelineStack = new ArrayDeque<>(steps); // Keep FIFO order
        int totalSteps = pipelineStack.size();
        logger.info("🚀 Starting pipeline with {} step(s)", totalSteps);
        System.out.println("[FunctionCaller] Starting pipeline with " + totalSteps + " step(s)");
        OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(selectedLM);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        final int maxRetries = 3;
        int retryCount = 0;

        while (!pipelineStack.isEmpty()) {
            JsonObject step = pipelineStack.pop();
            String functionName = step.get(FUNCTION_NAME_KEY).getAsString();
            JsonArray parameters = step.getAsJsonArray(PARAMETERS_KEY);
            Map<String, String> paramMap = new HashMap<>();
            for (JsonElement param : parameters) {
                JsonObject paramObj = param.getAsJsonObject();
                String paramName = paramObj.get(PARAMETER_NAME_KEY).getAsString();
                String paramValue = resolvePlaceholder(paramObj.get(PARAMETER_VALUE_KEY).getAsString());
                paramMap.put(paramName, paramValue);
            }

            boolean hasUnresolved = paramMap.values().stream().anyMatch(v -> v.equals("__UNRESOLVED__"));
            if (hasUnresolved) {
                logger.warn("⚠️ One or more parameters in step '{}' are unresolved. Triggering LLM fallback.", functionName);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to unresolved parameters. Aborting.");
                    break;
                }
                String newPrompt = "The following steps in the pipeline were successfully executed:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nCause: One or more placeholders could not be resolved from shared state.";
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    for (Map.Entry<String, String> entry : surroundingsStr.entrySet()) {
                        surroundings.put(entry.getKey(), entry.getValue());
                    }

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
                    String llmResponse;
                    if (client == null) {
                        OllamaChatRequestModel requestModel = builder
                                .withMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt)
                                .withMessage(OllamaChatMessageRole.USER, newPrompt)
                                .build();
                        OllamaChatResult result = ollamaAPI.chat(requestModel);
                        llmResponse = result.getResponse();
                    } else {
                        llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);
                    }
                    logger.info("Raw LLM response: {}", llmResponse);
                    String jsonPart = extractJson(llmResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has(PIPELINE_KEY)) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray(PIPELINE_KEY);
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                        continue;
                    } else if (llmResponseObj.has(CLARIFICATION_KEY)) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get(CLARIFICATION_KEY).getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getName());
                        // sendMessageToPlayer(clarification); // Removed as per refactoring
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after unresolved parameters: {}", e);
                    retryCount++;
                    continue;
                }
            }

            int currentStep = executedSteps.size() + 1;
            logger.info("▶️ Step {}/{} → {} with {}", currentStep, totalSteps, functionName, paramMap);
            System.out.println("[FunctionCaller] Step " + currentStep + "/" + totalSteps + " executing " + functionName + " params=" + paramMap);
            callFunction(functionName, paramMap).join(); // Sync call
            logger.info("Function output: {}", functionOutput);
            System.out.println("[FunctionCaller] Step " + currentStep + " output=" + functionOutput);
            parseOutputValues(functionName, functionOutput);

            // Short wait for state to settle (e.g., for movement tools)
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.warn("Interrupted during state settle wait");
            }

            // Get bot entity
            ServerPlayerEntity bot = botSource.getPlayer();
            if (bot == null) {
                logger.error("Bot entity not found for verification");
                continue;
            }

            // Use state-based verifier
            ToolVerifiers.StateVerifier verifier = ToolVerifiers.VERIFIER_REGISTRY.get(functionName);
            ToolVerifiers.VerificationResult result = (verifier == null)
                    ? new ToolVerifiers.VerificationResult(true, null)
                    : verifier.verify(paramMap, sharedState, bot);

            if (result.success) {
                logger.info("✅ Verifier passed for {} with data: {}", functionName, result.data);
                System.out.println("[FunctionCaller] Verifier success for " + functionName + " data=" + result.data);
                executedSteps.add(functionName + ", Output: " + functionOutput);
            } else {
                logger.warn("❌ Verifier failed for {} with data: {}", functionName, result.data);
                System.out.println("[FunctionCaller] Verifier failed for " + functionName + " data=" + result.data);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to verifier failures. Aborting.");
                    break;
                }
                String newPrompt = "The following steps were executed successfully:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nFunction output: " + functionOutput
                        + "\nVerification details: " + result.data;
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    for (Map.Entry<String, String> entry : surroundingsStr.entrySet()) {
                        surroundings.put(entry.getKey(), entry.getValue());
                    }

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
                    String llmResponse;
                    if (client == null) {
                        OllamaChatRequestModel requestModel = builder
                                .withMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt)
                                .withMessage(OllamaChatMessageRole.USER, newPrompt)
                                .build();
                        OllamaChatResult llmResult = ollamaAPI.chat(requestModel);
                        llmResponse = llmResult.getResponse();
                    } else {
                        llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);
                    }
                    logger.info("Raw LLM response: {}", llmResponse);
                    String jsonPart = extractJson(llmResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has(PIPELINE_KEY)) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray(PIPELINE_KEY);
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                    } else if (llmResponseObj.has(CLARIFICATION_KEY)) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get(CLARIFICATION_KEY).getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getName());
                        // sendMessageToPlayer(clarification); // Removed as per refactoring
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after verifier failure: {}", e);
                    retryCount++;
                    continue;
                }
            }
        }
        logger.info("🏁 Pipeline finished. Executed {} step(s).", executedSteps.size());
        System.out.println("[FunctionCaller] Pipeline finished. Executed " + executedSteps.size() + " step(s).");
    }

    private static final Map<String, List<String>> functionStateKeyMap = Map.ofEntries(
            Map.entry("detectBlocks", List.of("lastDetectedBlock.x", "lastDetectedBlock.y", "lastDetectedBlock.z")),
            Map.entry("goTo", List.of("botPosition.x", "botPosition.y", "botPosition.z")),
            Map.entry("chartPathToBlock", List.of("finalBlockPos.x", "finalBlockPos.y", "finalBlockPos.z")),
            Map.entry("faceBlock", List.of("facing.yaw", "facing.pitch")),
            Map.entry("faceEntity", List.of("facing.entityName")),
            Map.entry("turn", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("look", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("mineBlock", List.of("lastMineStatus")),
            Map.entry("getOxygenLevel", List.of("oxygenLevel")),
            Map.entry("getHungerLevel", List.of("hungerLevel")),
            Map.entry("getHealthLevel", List.of("healthLevel")),
            Map.entry("cultivateLand", List.of("lastCultivateStatus")),
            Map.entry("chopWood", List.of("lastChopStatus"))
    );

    private static void parseOutputValues(String functionName, String output) {
        List<String> keys = functionStateKeyMap.get(functionName);
        if (keys == null || keys.isEmpty()) return;

        List<Object> values = new ArrayList<>();
        switch (functionName) {
            case "goTo" -> {
                Matcher matcher = Pattern.compile("x[:=]\\s*(-?\\d+)\\s*y[:=]\\s*(-?\\d+)\\s*z[:=]\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
            case "detectBlocks" -> {
                Matcher matcher = Pattern.compile(".*found at (-?\\d+) (-?\\d+) (-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
            case "turn" -> {
                Matcher matcher = Pattern.compile("Now facing (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }
            case "look" -> {
                Matcher matcher = Pattern.compile("Now facing cardinal direction (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }
            case "mineBlock" -> {
                if (output.contains("Mining complete!")) {
                    values.add("success");
                } else if (output.contains("⚠️ Failed to mine block")) {
                    values.add("failed");
                }
            }
            case "getOxygenLevel" -> {
                Matcher matcher = Pattern.compile("Oxygen Level[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
            }
            case "getHungerLevel" -> {
                Matcher matcher = Pattern.compile("Hunger Level[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
            }
            case "getHealthLevel" -> {
                Matcher matcher = Pattern.compile("Remaining hearts[:=]\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Double.parseDouble(matcher.group(1)));
            }
            case "faceBlock" -> {
                Matcher matcher = Pattern.compile("Yaw[:=]\\s*([\\d.-]+).*Pitch[:=]\\s*([\\d.-]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Double.parseDouble(matcher.group(1)));
                    values.add(Double.parseDouble(matcher.group(2)));
                }
            }
            case "faceEntity" -> {
                Matcher matcher = Pattern.compile("Facing entity[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(matcher.group(1));
            }
            case "chartPathToBlock" -> {
                Matcher matcher = Pattern.compile("Bot is at (-?\\d+) (-?\\d+) (-?\\d+)").matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
            case "cultivateLand" -> {
                if (output.contains("Cultivation complete!")) {
                    values.add("success");
                } else if (output.contains("Failed to cultivate land")) {
                    values.add("failed");
                }
            }
            case "chopWood" -> {
                if (output.contains("Mining complete!")) {
                    values.add("success");
                } else if (output.contains("Failed to chop wood")) {
                    values.add("failed");
                }
            }
        }

        if (values.size() == keys.size()) {
            updateState(keys, values);
        } else {
            logger.warn("❌ Mismatch in keys/values for {} → keys: {}, values: {}", functionName, keys, values);
        }
    }

    private static void getFunctionResultAndSave(String userInput, String executionDateTime) {
        try {
            // Generate context synchronously
            String eventContext = generatePromptContext(userInput);
            // Generate event embedding synchronously
            List<Double> eventEmbedding = ollamaAPI.generateEmbeddings(OllamaModelType.NOMIC_EMBED_TEXT, userInput);
            // Generate event context embedding synchronously
            List<Double> eventContextEmbedding = ollamaAPI.generateEmbeddings(OllamaModelType.NOMIC_EMBED_TEXT, eventContext);
            // Wait until functionOutput is a valid string
            while (functionOutput == null || !(functionOutput instanceof String)) {
                try {
                    Thread.sleep(500L); // Check every 500ms
                } catch (InterruptedException e) {
                    logger.error("Couldn't get function call output");
                    throw new RuntimeException(e);
                }
            }
            System.out.println("Received output: " + functionOutput);
            // Generate result embedding based on the function output
            List<Double> resultEmbedding = ollamaAPI.generateEmbeddings(OllamaModelType.NOMIC_EMBED_TEXT, functionOutput);
            // Create execution record and save it
            ExecutionRecord executionRecord = new ExecutionRecord(executionDateTime, userInput, eventContext, functionOutput, eventEmbedding, eventContextEmbedding, resultEmbedding);
            executionRecord.updateRecords();
            // Clear the functionOutput to reset state
            functionOutput = null;
            System.out.println("Event data saved successfully.");
        } catch (IOException | OllamaBaseException | InterruptedException e) {
            // Log or handle the exception
            logger.error("Error occurred while processing the function result: ", e);
            throw new RuntimeException(e);
        }
    }

    private static String resolvePlaceholder(String value) {
        if (value.startsWith("$")) {
            String key = value.substring(1);
            Object resolvedObj = SharedStateUtils.getValue(sharedState, key);
            if (resolvedObj == null) {
                logger.warn("⚠️ Placeholder '{}' not found in sharedState. Using fallback value '0'", key);
                return "0";
            }
            String resolved = resolvedObj.toString();
            logger.debug("🔁 Resolved placeholder {} → {}", key, resolved);
            return resolved;
        }
        return value;
    }

    private static void updateState(List<String> keys, List<Object> values) {
        for (int i = 0; i < keys.size(); i++) {
            SharedStateUtils.setValue(sharedState, keys.get(i), values.get(i));
            logger.info("📌 Updated sharedState: {} → {}", keys.get(i), values.get(i));
        }
    }

    private static CompletableFuture<Void> callFunction(String functionName, Map<String, String> paramMap) {
        return CompletableFuture.runAsync(() -> {
            switch (functionName) {
                case "goTo" -> {
                    int x = Integer.parseInt(resolvePlaceholder(paramMap.get("x")));
                    int y = Integer.parseInt(resolvePlaceholder(paramMap.get("y")));
                    int z = Integer.parseInt(resolvePlaceholder(paramMap.get("z")));
                    boolean sprint = Boolean.parseBoolean(resolvePlaceholder(paramMap.get("sprint")));
                    logger.info("Calling method: goTo with x={} y={} z={} sprint={}", x, y, z, sprint);
                    Tools.goTo(x, y, z, sprint);
                }
                case "chartPathToBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX")));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY")));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ")));
                    String blockType = resolvePlaceholder(paramMap.get("blockType"));
                    logger.info("Calling method: chartPathToBlock with targetX={} targetY={} targetZ={} blockType={}", targetX, targetY, targetZ, blockType);
                    Tools.chartPathToBlock(targetX, targetY, targetZ, blockType);
                }
                case "faceBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX")));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY")));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ")));
                    logger.info("Calling method: faceBlock with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.faceBlock(targetX, targetY, targetZ);
                }
                case "faceEntity" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX")));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY")));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ")));
                    logger.info("Calling method: faceEntity with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.faceEntity(targetX, targetY, targetZ);
                }
                case "detectBlocks" -> {
                    String blockType = resolvePlaceholder(paramMap.get("blockType"));
                    logger.info("Calling method: detectBlocks with blockType={}", blockType);
                    Tools.detectBlocks(blockType);
                }
                case "shovelDirt" -> {
                    logger.info("Calling method: shovelDirt");
                    Tools.shovelDirt();
                }
                case "turn" -> {
                    String direction = resolvePlaceholder(paramMap.get("direction"));
                    logger.info("Calling method: turn with direction={}", direction);
                    Tools.turn(direction);
                }
                case "look" -> {
                    String cardinalDirection = resolvePlaceholder(paramMap.get("cardinalDirection"));
                    logger.info("Calling method: look with cardinal direction={}", cardinalDirection);
                    Tools.look(cardinalDirection);
                }
                case "mineBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX")));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY")));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ")));
                    logger.info("Calling method: mineBlock with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.mineBlock(targetX, targetY, targetZ);
                }
                case "chopWood" -> {
                    String treeType = resolvePlaceholder(paramMap.get("treeType"));
                    logger.info("Calling method: chopWood with treeType={}", treeType);
                    Tools.chopWood(treeType);
                }
                case "getOxygenLevel" -> {
                    logger.info("Calling method: getOxygenLevel");
                    Tools.getOxygenLevel();
                }
                case "getHungerLevel" -> {
                    logger.info("Calling method: getHungerLevel");
                    Tools.getHungerLevel();
                }
                case "getHealthLevel" -> {
                    logger.info("Calling method: getHealthLevel");
                    Tools.getHealthLevel();
                }
                case "followPlayer" -> {
                    String targetName = resolvePlaceholder(paramMap.get("playerName"));
                    logger.info("Calling method: followPlayer with target={} ", targetName);
                    Tools.followPlayer(targetName);
                }
                case "guardArea" -> {
                    String radiusRaw = paramMap.getOrDefault("radius", "6.0");
                    double radius;
                    try {
                        radius = Double.parseDouble(resolvePlaceholder(radiusRaw));
                    } catch (NumberFormatException ex) {
                        radius = 6.0D;
                    }
                    logger.info("Calling method: guardArea with radius={} ", radius);
                    Tools.guardArea(radius);
                }
                case "stayPut" -> {
                    logger.info("Calling method: stayPut");
                    Tools.stayPut();
                }
                case "returnToBase" -> {
                    logger.info("Calling method: returnToBase");
                    Tools.returnToBase();
                }
                case "toggleAssist" -> {
                    String modeRaw = resolvePlaceholder(paramMap.get("mode"));
                    logger.info("Calling method: toggleAssist with mode={}", modeRaw);
                    Tools.toggleAssist(modeRaw);
                }
                case "updateState" -> {
                    String keysRaw = paramMap.get("keys");
                    String valuesRaw = paramMap.get("values");
                    List<String> keys = List.of(keysRaw.split(","));
                    List<String> valueStrings = List.of(valuesRaw.split(","));
                    List<Object> values = new ArrayList<>();
                    for (String v : valueStrings) {
                        try {
                            values.add(Integer.parseInt(v));
                        } catch (NumberFormatException e1) {
                            try {
                                values.add(Double.parseDouble(v));
                            } catch (NumberFormatException e2) {
                                values.add(v);  // Fallback to string
                            }
                        }
                    }
                    updateState(keys, values);
                    logger.info("Called updateState with keys={} and values={}", keys, values);
                }
                // Add this new case to your existing switch statement inside the callFunction method.
                case "webSearch" -> {
                    String query = paramMap.get("query");
                    logger.info("Calling method: webSearch with query='{}'", query);
                    Tools.webSearch(query);
                }
                case "cultivateLand" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX")));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY")));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ")));
                    logger.info("Calling method: cultivateLand with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.cultivateLand(targetX, targetY, targetZ);
                }
                default -> logger.warn("Unknown function: {}", functionName);
            }
        });
    }
}
