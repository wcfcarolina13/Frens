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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import net.shasankp000.Entity.EntityDetails;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.State;
import net.shasankp000.OllamaClient.ollamaClient;
import net.shasankp000.Database.OldSQLiteDB;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.Entity.LookController;
import net.shasankp000.PathFinding.ChartPathToBlock;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.PathFinding.PathTracer;
import net.shasankp000.PlayerUtils.*;
import net.shasankp000.ServiceLLMClients.LLMClient;
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
    private static final Map<String, String> sharedState = new ConcurrentHashMap<>();
    private static UUID playerUUID;
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.DOTALL);
    private static String selectedLM = AIPlayer.CONFIG.selectedLanguageModel();

    public FunctionCallerV2(ServerCommandSource botSource) {

        FunctionCallerV2.botSource = botSource;
        ollamaAPI.setRequestTimeoutSeconds(90);

    }

    public FunctionCallerV2(ServerCommandSource botSource, UUID playerUUID) {

        FunctionCallerV2.botSource = botSource;
        ollamaAPI.setRequestTimeoutSeconds(90);
        FunctionCallerV2.playerUUID = playerUUID;
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
                return;
            }
            getFunctionOutput(GoTo.goTo(botSource, x, y, z, sprint));
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
            var world = bot.getWorld();

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

            BlockPos outputPos = blockDetectionUnit.detectBlocks(Objects.requireNonNull(botSource.getPlayer()), blockType);

            String output = "";

            if (outputPos == null) {
                output = "Block not found!";
            }
            else {
                output = "Block found at " + outputPos.getX() + " " + outputPos.getY() + " " + outputPos.getZ();
            }

            getFunctionOutput(output);
        }

        /** turn: change torso facing direction **/
        private static void turn(String direction) {
            System.out.println("Turning to: " + direction);

            MinecraftServer server = botSource.getServer();
            String botName = botSource.getName();
            server.getCommandManager().executeWithPrefix(botSource, "/player " + botName + " turn " + direction); // choosing the command route instead of calling function to check if the bug still exists.

            getFunctionOutput("Now facing " + direction + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getFacing().getName() + " in " + Objects.requireNonNull(botSource.getPlayer()).getFacing().getAxis().asString() + " axis.");
        }

        /** turn: change head facing direction **/
        private static void look(String cardinalDirection) {
            System.out.println("Looking at: " + cardinalDirection);

            MinecraftServer server = botSource.getServer();
            String botName = botSource.getName();
            server.getCommandManager().executeWithPrefix(botSource, "/player " + botName + " look " + cardinalDirection); // choosing the command route instead of calling function to check if the bug still exists.

            getFunctionOutput("Now facing cardinal direction: " + Objects.requireNonNull(botSource.getPlayer()).getFacing().getName() + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getFacing().getAxis().asString() + " axis.");

        }

        /** mineBlock: break block **/
        private static void mineBlock(int targetX, int targetY, int targetZ) {
            System.out.println("Mining block at: " + targetX + ", " + targetY + ", " + targetZ);
            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    String result = MiningTool.mineBlock(
                            Objects.requireNonNull(botSource.getPlayer()),
                            new BlockPos(targetX, targetY, targetZ)
                    ).get();

                    getFunctionOutput(result);
                } catch (Exception e) {
                    e.printStackTrace();
                    getFunctionOutput("⚠️ Failed to mine block.");
                }
            });
        }

        /** getOxygenLevel: report air level **/
        private static void getOxygenLevel() {
            System.out.println("Getting oxygen level...");
            getFunctionOutput("Oxygen Level: " +  getPlayerOxygen.getBotOxygenLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** getHungerLevel: report hunger **/
        private static void getHungerLevel() {
            System.out.println("Getting hunger level...");
            getFunctionOutput("Hunger Level: " +  getPlayerHunger.getBotHungerLevel(Objects.requireNonNull(botSource.getPlayer())));

        }

        /** getHungerLevel: report health **/
        private static void getHealthLevel() {
            System.out.println("Getting health level...");
            getFunctionOutput("Remaining hearts: " +  getHealth.getBotHealthLevel(Objects.requireNonNull(botSource.getPlayer())));

        }
    }


    private static String toolBuilder() {
        var gson = new Gson();
        List<Object> functions = Collections.singletonList(ToolRegistry.TOOLS.stream().map(tool -> {
            return Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.parameters().stream().map(param -> Map.of(
                            "name", param.name(),
                            "description", param.description(),
                            "required", true
                    )).toList()
            );
        }).toList());

        return gson.toJson(Map.of("functions", functions));
    }



    // This code right here is pure EUREKA moment.

    private static String buildPrompt(String toolString) {
        return """
                You are a first-principles reasoning **function-caller AI agent** for a Minecraft bot.
                
                You will be provided with additional context information of the minecraft bot you are controlling. Use that information well to carefully plan your approach.
                
                Your role is to analyze player prompts carefully and decide which tool or sequence of tools best accomplishes the task. \s
                You must output your decision strictly as JSON, following the required schema.
                
                ---
                
                Key Principles
                
                
                1. **Use only the tools you have.** \s
                   Do not hallucinate new tools. Each tool has clear parameters, a purpose, and trade-offs.
                
                2. **Use the fewest tools possible.** \s
                   When a single tool is enough, use it. \s
                   When multiple tools must be chained, output them as a pipeline in the correct order.
                
                3. **Focus on action verbs.** \s
                   Player prompts contain action verbs that reveal intent: go, walk, navigate, check, search, mine, approach, align, harvest, etcetera. \s
                   Always match these to the most relevant tools.
                
                4. **Use $placeholders for shared state.** \s
                   If a step depends on output from a previous step, use `$lastDetectedBlock.x` (etc.). \s
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
                ✅ Always use the "parameterName" and "parameterValue" fields exactly — do not rename them.
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
                 3. Could you mine some stone and bring them to me? \n
                 4. Craft a set of iron armor. \n
                 5. Please go to coordinates 10 -60 20. \n
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
                 3. The player asked you to mine some stone and then bring the stone to the player. \n
                 4. The player asked you to craft a set of iron armor. \n
                 5. The player asked you to go to coordinates 10 -60 20. You followed the instructions and began movement to the coordinates. \n
               
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
            logger.error("{}", (Object) e.getStackTrace());
        }

        return contextOutput;
    }

    public static String buildLLMBotContext(State state, Map<String, String> sharedState, Map<String, String> surroundingsSummary) {
        StringBuilder sb = new StringBuilder();

        sb.append("Bot current state:\n");
        sb.append("- Position: (").append(state.getBotX()).append(", ").append(state.getBotY()).append(", ").append(state.getBotZ()).append(")\n");

        if (sharedState.containsKey("facing.direction")) {
            sb.append("- Facing: ").append(sharedState.get("facing.direction"));
            if (sharedState.containsKey("facing.facing")) {
                sb.append(" (").append(sharedState.get("facing.facing"));
                if (sharedState.containsKey("facing.axis")) {
                    sb.append(", axis: ").append(sharedState.get("facing.axis"));
                }
                sb.append(")");
            }
            sb.append("\n");
        }
        else {
            // first time call.
                assert botSource.getPlayer() != null;
                Direction facing = botSource.getPlayer().getFacing();
                sb.append("- Facing: ").append(facing.getName());
                sb.append(" (axis: ").append(facing.getAxis().asString()).append(")");


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
            for (Map.Entry<String, String> entry : surroundingsSummary.entrySet()) {
                sb.append("  • ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
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
        Map<String, String> surroundings = map.summarizeSurroundings();

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

            String cleanedResponse = stripThinkBlock(response);

            String jsonPart = extractJson(cleanedResponse);
            logger.info("Extracted JSON: {}", jsonPart);

            executeFunction(userPrompt, jsonPart);

        } catch (Exception e) {
            logger.error("Error in Function Caller: {}", e);
        }
    }

    public static void run(String userPrompt, LLMClient client) {
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        String response;

        State initialState = BotEventHandler.createInitialState(botSource.getPlayer());

        InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
        map.updateMap();
        Map<String, String> surroundings = map.summarizeSurroundings();

        String botContext = buildLLMBotContext(initialState, sharedState, surroundings);


        String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

        try {

            response = client.sendPrompt(fullSystemPrompt, userPrompt);

            logger.info("Raw LLM Response: {}", response);

            String cleanedResponse = stripThinkBlock(response);

            String jsonPart = extractJson(cleanedResponse);
            logger.info("Extracted JSON: {}", jsonPart);

            executeFunction(userPrompt, jsonPart, client);

        } catch (Exception e) {
            logger.error("Error in Function Caller: {}", e);
        }
    }

    private static String extractJson(String response) {
        String stripped = stripThinkBlock(response); // Use fix 1 here

        // Try to locate either a JSON object or array
        int objStart = stripped.indexOf("{");
        int objEnd = stripped.lastIndexOf("}") + 1;
        int arrStart = stripped.indexOf("[");
        int arrEnd = stripped.lastIndexOf("]") + 1;

        // Try full JSON object (most likely case)
        if (objStart != -1 && objEnd != -1 && objEnd > objStart) {
            String candidate = stripped.substring(objStart, objEnd);
            if (isValidJson(candidate)) return candidate;
        }

        // Try JSON array (secondary fallback)
        if (arrStart != -1 && arrEnd != -1 && arrEnd > arrStart) {
            String candidate = stripped.substring(arrStart, arrEnd);
            if (isValidJson(candidate)) return candidate;
        }

        logger.error("❌ Could not extract valid JSON from response:\n{}", response);
        return "{}";
    }

    // Optional helper for sanity check
    private static boolean isValidJson(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    private static String stripThinkBlock(String response) {
        Matcher matcher = THINK_BLOCK.matcher(response);
        if (matcher.find()) {
            return response.replace(matcher.group(0), "").trim(); // Strip the full <think>...</think>
        }
        return response.trim();
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

                if (jsonObject.has("pipeline")) {
                    AutoFaceEntity.setBotExecutingTask(true);

                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");

                    runPipelineLoop(pipeline);


                } else if (jsonObject.has("functionName")) {
                    AutoFaceEntity.setBotExecutingTask(true);

                    String fnName = jsonObject.get("functionName").getAsString();
                    JsonArray paramsArray = jsonObject.get("parameters").getAsJsonArray();

                    Map<String, String> paramMap = new ConcurrentHashMap<>();
                    StringBuilder params = new StringBuilder();

                    for (JsonElement parameter : paramsArray) {
                        JsonObject paramObj = parameter.getAsJsonObject();
                        String paramName = paramObj.get("parameterName").getAsString();
                        String paramValue = paramObj.get("parameterValue").getAsString();
                        paramValue = resolvePlaceholder(paramValue);

                        params.append(paramName).append("=").append(paramValue).append(", ");
                        paramMap.put(paramName, paramValue);
                    }

                    logger.info("Executing: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap).join();

                }

                else if (jsonObject.has("clarification")) {
                    String clarification = jsonObject.get("clarification").getAsString();

                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getName());

                    // Relay to player in-game
                    sendMessageToPlayer(clarification);
                }

                else {
                    throw new IllegalStateException("Response must have either functionName or pipeline.");
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

                if (jsonObject.has("pipeline")) {
                    AutoFaceEntity.setBotExecutingTask(true);

                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");

                    runPipelineLoop(pipeline, client);


                } else if (jsonObject.has("functionName")) {
                    AutoFaceEntity.setBotExecutingTask(true);

                    String fnName = jsonObject.get("functionName").getAsString();
                    JsonArray paramsArray = jsonObject.get("parameters").getAsJsonArray();

                    Map<String, String> paramMap = new ConcurrentHashMap<>();
                    StringBuilder params = new StringBuilder();

                    for (JsonElement parameter : paramsArray) {
                        JsonObject paramObj = parameter.getAsJsonObject();
                        String paramName = paramObj.get("parameterName").getAsString();
                        String paramValue = paramObj.get("parameterValue").getAsString();
                        paramValue = resolvePlaceholder(paramValue);

                        params.append(paramName).append("=").append(paramValue).append(", ");
                        paramMap.put(paramName, paramValue);
                    }

                    logger.info("Executing: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap).join();

                }

                else if (jsonObject.has("clarification")) {
                    String clarification = jsonObject.get("clarification").getAsString();

                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getName());

                    // Relay to player in-game
                    sendMessageToPlayer(clarification);
                }

                else {
                    throw new IllegalStateException("Response must have either functionName or pipeline.");
                }

                // getFunctionResultAndSave(userInput, executionDateTime);

            });
        } catch (JsonSyntaxException | NullPointerException | IllegalStateException e) {
            logger.error("Error processing JSON response: {}", e.getMessage(), e);
        }
    }

    private static void sendMessageToPlayer(String message) {
        ollamaClient.processLLMOutput(message, botSource.getName(), botSource);
    }

    private static void runPipelineLoop(JsonArray pipeline) {
        List<JsonObject> steps = new ArrayList<>();
        List<String> executedSteps = new ArrayList<>();

        for (JsonElement step : pipeline) {
            steps.add(step.getAsJsonObject());
        }

        Deque<JsonObject> pipelineStack = new ArrayDeque<>(steps); // Keep FIFO order

        logger.info("Pipeline stack: {}", pipelineStack);

        OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(selectedLM);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());

        Map<String, BiFunction<Map<String, String>, String, Boolean>> verifierRegistry = Map.of(
                "goTo", ToolVerifiers::verifyGoTo,
                "detectBlocks", ToolVerifiers::verifyDetectBlocks,
                "turn", ToolVerifiers::verifyTurn,
                "mineBlock", ToolVerifiers::verifyMineBlock,
                "getOxygenLevel", ToolVerifiers::verifyGetOxygenLevel,
                "getHungerLevel", ToolVerifiers::verifyGetHungerLevel,
                "getHealthLevel", ToolVerifiers::verifyGetHealthLevel
        );

        final int maxRetries = 3;
        int retryCount = 0;

        while (!pipelineStack.isEmpty()) {
            JsonObject step = pipelineStack.pop();
            String functionName = step.get("functionName").getAsString();
            JsonArray parameters = step.getAsJsonArray("parameters");

            Map<String, String> paramMap = new HashMap<>();
            for (JsonElement param : parameters) {
                JsonObject paramObj = param.getAsJsonObject();
                String paramName = paramObj.get("parameterName").getAsString();
                String paramValue = resolvePlaceholder(paramObj.get("parameterValue").getAsString());
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

                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
                    map.updateMap();
                    Map<String, String> surroundings = map.summarizeSurroundings();

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);


                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;


                    OllamaChatRequestModel requestModel = builder
                            .withMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt)
                            .withMessage(OllamaChatMessageRole.USER, newPrompt)
                            .build();

                    OllamaChatResult result = ollamaAPI.chat(requestModel);
                    String llmResponse = result.getResponse();
                    logger.info("Raw LLM response: {}", llmResponse);

                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);

                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();
                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0; // ✅ Reset retry counter
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps); // Keep original order
                        continue;
                    } else {
                        logger.warn("LLM did not return a pipeline. Exiting.");
                        break;
                    }

                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after unresolved parameters: {}", e);
                    retryCount++;
                    continue;
                }
            }

            logger.info("Running function: " + functionName + " with " + paramMap);
            callFunction(functionName, paramMap).join(); // Sync call
            logger.info("Function output: {}", functionOutput);

            parseOutputValues(functionName, functionOutput);

            // ✅ Run verifier
            BiFunction<Map<String, String>, String, Boolean> verifier = verifierRegistry.get(functionName);
            boolean valid = verifier == null || verifier.apply(paramMap, functionOutput);

            if (valid) {
                logger.info("✅ Verifier passed for {}", functionName);
                executedSteps.add(functionName + ", Output: " + functionOutput);
            } else {
                logger.warn("❌ Verifier failed for {} → triggering LLM fallback...", functionName);

                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to verifier failures. Aborting.");
                    break;
                }

                String newPrompt = "The following steps were executed successfully:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nFunction output: " + functionOutput;

                try {

                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());

                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
                    map.updateMap();
                    Map<String, String> surroundings = map.summarizeSurroundings();

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);


                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;


                    OllamaChatRequestModel requestModel = builder
                            .withMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt)
                            .withMessage(OllamaChatMessageRole.USER, newPrompt)
                            .build();

                    OllamaChatResult result = ollamaAPI.chat(requestModel);
                    String llmResponse = result.getResponse();
                    logger.info("Raw LLM response: {}", llmResponse);

                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);

                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();
                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0; // ✅ Reset retry counter
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps); // Keep original order
                    } else {
                        logger.warn("LLM did not return a pipeline. Exiting.");
                        break;
                    }

                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after verifier failure: {}", e);
                    retryCount++;
                    continue;
                }
            }
        }

        // once the pipeline is fully empty, reset the autoface module.

        blockDetectionUnit.setIsBlockDetectionActive(false);
        PathTracer.flushAllMovementTasks();
        AutoFaceEntity.setBotExecutingTask(false);
        AutoFaceEntity.isBotMoving = false;
        logger.info("✔️ Autoface module has been reset.");

    }

    private static void runPipelineLoop(JsonArray pipeline, LLMClient client) {
        List<JsonObject> steps = new ArrayList<>();
        List<String> executedSteps = new ArrayList<>();

        for (JsonElement step : pipeline) {
            steps.add(step.getAsJsonObject());
        }

        Deque<JsonObject> pipelineStack = new ArrayDeque<>(steps); // Keep FIFO order

        logger.info("Pipeline stack: {}", pipelineStack);


        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());

        Map<String, BiFunction<Map<String, String>, String, Boolean>> verifierRegistry = Map.of(
                "goTo", ToolVerifiers::verifyGoTo,
                "detectBlocks", ToolVerifiers::verifyDetectBlocks,
                "turn", ToolVerifiers::verifyTurn,
                "mineBlock", ToolVerifiers::verifyMineBlock,
                "getOxygenLevel", ToolVerifiers::verifyGetOxygenLevel,
                "getHungerLevel", ToolVerifiers::verifyGetHungerLevel,
                "getHealthLevel", ToolVerifiers::verifyGetHealthLevel
        );

        final int maxRetries = 3;
        int retryCount = 0;

        while (!pipelineStack.isEmpty()) {
            JsonObject step = pipelineStack.pop();
            String functionName = step.get("functionName").getAsString();
            JsonArray parameters = step.getAsJsonArray("parameters");

            Map<String, String> paramMap = new HashMap<>();
            for (JsonElement param : parameters) {
                JsonObject paramObj = param.getAsJsonObject();
                String paramName = paramObj.get("parameterName").getAsString();
                String paramValue = resolvePlaceholder(paramObj.get("parameterValue").getAsString());
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

                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
                    map.updateMap();
                    Map<String, String> surroundings = map.summarizeSurroundings();

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);


                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                    String llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);
                    logger.info("Raw LLM response: {}", llmResponse);

                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);

                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();
                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0; // ✅ Reset retry counter
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps); // Keep original order
                        continue;
                    } else {
                        logger.warn("LLM did not return a pipeline. Exiting.");
                        break;
                    }

                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after unresolved parameters: {}", e);
                    retryCount++;
                    continue;
                }
            }

            logger.info("Running function: " + functionName + " with " + paramMap);
            callFunction(functionName, paramMap).join(); // Sync call
            logger.info("Function output: {}", functionOutput);

            parseOutputValues(functionName, functionOutput);

            // ✅ Run verifier
            BiFunction<Map<String, String>, String, Boolean> verifier = verifierRegistry.get(functionName);
            boolean valid = verifier == null || verifier.apply(paramMap, functionOutput);

            if (valid) {
                logger.info("✅ Verifier passed for {}", functionName);
                executedSteps.add(functionName + ", Output: " + functionOutput);
            } else {
                logger.warn("❌ Verifier failed for {} → triggering LLM fallback...", functionName);

                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to verifier failures. Aborting.");
                    break;
                }

                String newPrompt = "The following steps were executed successfully:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nFunction output: " + functionOutput;

                try {

                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());

                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
                    map.updateMap();
                    Map<String, String> surroundings = map.summarizeSurroundings();

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);


                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                    String llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);

                    logger.info("Raw LLM response: {}", llmResponse);

                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);

                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();
                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0; // ✅ Reset retry counter
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps); // Keep original order
                    } else {
                        logger.warn("LLM did not return a pipeline. Exiting.");
                        break;
                    }

                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after verifier failure: {}", e);
                    retryCount++;
                    continue;
                }
            }
        }

        // once the pipeline is fully empty, reset the autoface module.

        blockDetectionUnit.setIsBlockDetectionActive(false);
        PathTracer.flushAllMovementTasks();
        AutoFaceEntity.setBotExecutingTask(false);
        AutoFaceEntity.isBotMoving = false;
        logger.info("✔️ Autoface module has been reset.");

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
            Map.entry("getHealthLevel", List.of("healthLevel"))
    );



    private static void parseOutputValues(String functionName, String output) {
        List<String> keys = functionStateKeyMap.get(functionName);
        if (keys == null || keys.isEmpty()) return;

        List<String> values = new ArrayList<>();

        switch (functionName) {
            case "goTo" -> {
                Matcher matcher = Pattern.compile("x[:=]\\s*(-?\\d+)\\s*y[:=]\\s*(-?\\d+)\\s*z[:=]\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }

            case "detectBlocks" -> {
                Matcher matcher = Pattern.compile(".*found at (-?\\d+) (-?\\d+) (-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }

            case "turn" -> {
                Matcher matcher = Pattern.compile("Now facing (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1)); // facing.direction
                    values.add(matcher.group(2)); // facing.facing
                    values.add(matcher.group(3)); // facing.axis
                }
            }

            case "look" -> {
                Matcher matcher = Pattern.compile("Now facing cardinal direction (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1)); // facing.direction
                    values.add(matcher.group(2)); // facing.facing
                    values.add(matcher.group(3)); // facing.axis
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
                if (matcher.find()) values.add(matcher.group(1));
            }

            case "getHungerLevel" -> {
                Matcher matcher = Pattern.compile("Hunger Level[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(matcher.group(1));
            }

            case "getHealthLevel" -> {
                Matcher matcher = Pattern.compile("Remaining hearts[:=]\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(matcher.group(1));
            }

            case "faceBlock" -> {
                Matcher matcher = Pattern.compile("Yaw[:=]\\s*([\\d.-]+).*Pitch[:=]\\s*([\\d.-]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                }
            }

            case "faceEntity" -> {
                Matcher matcher = Pattern.compile("Facing entity[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(matcher.group(1));
            }

            case "chartPathToBlock" -> {
                Matcher matcher = Pattern.compile("Bot is at (-?\\d+) (-?\\d+) (-?\\d+)").matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
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
            String resolved = sharedState.get(key);
            if (resolved == null) {
                logger.warn("⚠️ Placeholder '{}' not found in sharedState. Using fallback value '0'", key);
                return "__UNRESOLVED__";
            }
            logger.debug("🔁 Resolved placeholder {} → {}", key, resolved);
            return resolved;
        }
        return value;
    }


    private static void updateState(List<String> keys, List<String> values) {
        for (int i = 0; i < keys.size(); i++) {
            sharedState.put(keys.get(i), values.get(i));
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

                case "turn" -> {
                    String direction = resolvePlaceholder(paramMap.get("direction"));
                    logger.info("Calling method: turn with direction={}", direction);
                    Tools.turn(direction);
                }

                case "look" -> {
                    String cardinalDirection = resolvePlaceholder(paramMap.get("cardinalDirection"));
                    logger.info("Calling method: turn with cardinal direction={}", cardinalDirection);
                    Tools.look(cardinalDirection);
                }


                case "mineBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX")));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY")));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ")));
                    logger.info("Calling method: mineBlock with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.mineBlock(targetX, targetY, targetZ);
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

                case "updateState" -> {
                    String keysRaw = paramMap.get("keys");
                    String valuesRaw = paramMap.get("values");
                    List<String> keys = List.of(keysRaw.split(","));
                    List<String> values = List.of(valuesRaw.split(","));
                    updateState(keys, values);
                    logger.info("Called updateState with keys={} and values={}", keys, values);
                }


                default -> logger.warn("Unknown function: {}", functionName);
            }
        });
    }



}
