package net.shasankp000.Network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

public class ConfigJsonUtil {

    public static String configToJson() {
        // Retrieve config values from the generated CONFIG
        List<String> modelList = AIPlayer.CONFIG.getModelList();
        String selectedLanguageModel = AIPlayer.CONFIG.getSelectedLanguageModel();
        Map<String, String> botGameProfile = AIPlayer.CONFIG.getBotGameProfile();

        // Build JSON using Gson's JsonObject and JsonArray
        JsonObject root = new JsonObject();

        // Add modelList as a JSON array
        JsonArray modelsArray = new JsonArray();
        for (String model : modelList) {
            modelsArray.add(model);
        }
        root.add("modelList", modelsArray);

        // Add selectedLanguageModel as a property
        root.addProperty("selectedLanguageModel", selectedLanguageModel);

        // Add BotGameProfile as a JSON object
        JsonObject profileObject = new JsonObject();
        for (Map.Entry<String, String> entry : botGameProfile.entrySet()) {
            profileObject.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("BotGameProfile", profileObject);

        root.addProperty("llmWorldEnabled", AIPlayer.CONFIG.isDefaultLlmWorldEnabled());

        JsonObject botControlObject = new JsonObject();
        for (Map.Entry<String, ManualConfig.BotControlSettings> entry : AIPlayer.CONFIG.getBotControls().entrySet()) {
            ManualConfig.BotControlSettings settings = entry.getValue();
            JsonObject controlJson = new JsonObject();
            controlJson.addProperty("autoSpawn", settings.isAutoSpawn());
            controlJson.addProperty("spawnMode", settings.getSpawnMode());
            controlJson.addProperty("teleportDuringSkills", settings.isTeleportDuringSkills());
            controlJson.addProperty("pauseOnFullInventory", settings.isPauseOnFullInventory());
            controlJson.addProperty("llmEnabled", settings.isLlmEnabled());
            botControlObject.add(entry.getKey(), controlJson);
        }
        root.add("botControls", botControlObject);

        JsonObject ownershipObject = new JsonObject();
        for (Map.Entry<String, ManualConfig.BotOwnership> entry : AIPlayer.CONFIG.getBotOwnership().entrySet()) {
            ManualConfig.BotOwnership ownership = entry.getValue();
            JsonObject ownerJson = new JsonObject();
            ownerJson.addProperty("ownerUuid", ownership.ownerUuid());
            ownerJson.addProperty("ownerName", ownership.ownerName());
            ownershipObject.add(entry.getKey(), ownerJson);
        }
        root.add("botOwnership", ownershipObject);

        JsonObject spawnObject = new JsonObject();
        for (Map.Entry<String, ManualConfig.BotSpawn> entry : AIPlayer.CONFIG.getBotSpawnPoints().entrySet()) {
            ManualConfig.BotSpawn spawn = entry.getValue();
            JsonObject spawnJson = new JsonObject();
            spawnJson.addProperty("dimension", spawn.dimension());
            spawnJson.addProperty("x", spawn.x());
            spawnJson.addProperty("y", spawn.y());
            spawnJson.addProperty("z", spawn.z());
            spawnJson.addProperty("yaw", spawn.yaw());
            spawnJson.addProperty("pitch", spawn.pitch());
            spawnObject.add(entry.getKey(), spawnJson);
        }
        root.add("botSpawns", spawnObject);

        // Return the JSON string (pretty printing optional)
        return root.toString();
    }

    public static void applyConfigJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("modelList")) {
                List<String> models = new ArrayList<>();
                JsonArray arr = root.getAsJsonArray("modelList");
                for (JsonElement element : arr) {
                    models.add(element.getAsString());
                }
                AIPlayer.CONFIG.setModelList(models);
            }
            if (root.has("selectedLanguageModel")) {
                AIPlayer.CONFIG.setSelectedLanguageModel(root.get("selectedLanguageModel").getAsString());
            }
            if (root.has("BotGameProfile")) {
                Map<String, String> profile = new HashMap<>();
                JsonObject profileObject = root.getAsJsonObject("BotGameProfile");
                for (Map.Entry<String, JsonElement> entry : profileObject.entrySet()) {
                    profile.put(entry.getKey(), entry.getValue().getAsString());
                }
                AIPlayer.CONFIG.setBotGameProfile(profile);
            }
            if (root.has("llmWorldEnabled")) {
                AIPlayer.CONFIG.setDefaultLlmWorldEnabled(root.get("llmWorldEnabled").getAsBoolean());
            }
            if (root.has("botControls")) {
                Map<String, ManualConfig.BotControlSettings> controls = new HashMap<>();
                JsonObject controlsObject = root.getAsJsonObject("botControls");
                for (Map.Entry<String, JsonElement> entry : controlsObject.entrySet()) {
                    JsonObject settingsJson = entry.getValue().getAsJsonObject();
                    ManualConfig.BotControlSettings settings = new ManualConfig.BotControlSettings();
                    if (settingsJson.has("autoSpawn")) {
                        settings.setAutoSpawn(settingsJson.get("autoSpawn").getAsBoolean());
                    }
                    if (settingsJson.has("spawnMode")) {
                        settings.setSpawnMode(settingsJson.get("spawnMode").getAsString());
                    }
                    if (settingsJson.has("teleportDuringSkills")) {
                        settings.setTeleportDuringSkills(settingsJson.get("teleportDuringSkills").getAsBoolean());
                    }
                    if (settingsJson.has("pauseOnFullInventory")) {
                        settings.setPauseOnFullInventory(settingsJson.get("pauseOnFullInventory").getAsBoolean());
                    }
                    if (settingsJson.has("llmEnabled")) {
                        settings.setLlmEnabled(settingsJson.get("llmEnabled").getAsBoolean());
                    }
                    controls.put(entry.getKey(), settings);
                }
                AIPlayer.CONFIG.setBotControls(controls);
            }
            if (root.has("botOwnership")) {
                Map<String, ManualConfig.BotOwnership> owners = new HashMap<>();
                JsonObject ownersObject = root.getAsJsonObject("botOwnership");
                for (Map.Entry<String, JsonElement> entry : ownersObject.entrySet()) {
                    JsonObject ownerJson = entry.getValue().getAsJsonObject();
                    ManualConfig.BotOwnership owner = new ManualConfig.BotOwnership(
                            ownerJson.has("ownerUuid") ? ownerJson.get("ownerUuid").getAsString() : "",
                            ownerJson.has("ownerName") ? ownerJson.get("ownerName").getAsString() : ""
                    );
                    owners.put(entry.getKey(), owner);
                }
                AIPlayer.CONFIG.setBotOwnership(owners);
            }
            if (root.has("botSpawns")) {
                Map<String, ManualConfig.BotSpawn> spawns = new HashMap<>();
                JsonObject spawnJson = root.getAsJsonObject("botSpawns");
                for (Map.Entry<String, JsonElement> entry : spawnJson.entrySet()) {
                    JsonObject data = entry.getValue().getAsJsonObject();
                    ManualConfig.BotSpawn spawn = new ManualConfig.BotSpawn(
                            data.has("dimension") ? data.get("dimension").getAsString() : "",
                            data.has("x") ? data.get("x").getAsDouble() : 0,
                            data.has("y") ? data.get("y").getAsDouble() : 0,
                            data.has("z") ? data.get("z").getAsDouble() : 0,
                            data.has("yaw") ? data.get("yaw").getAsFloat() : 0F,
                            data.has("pitch") ? data.get("pitch").getAsFloat() : 0F
                    );
                    spawns.put(entry.getKey(), spawn);
                }
                AIPlayer.CONFIG.setBotSpawnPoints(spawns);
            }
        } catch (Exception e) {
            AIPlayer.LOGGER.warn("Failed to parse config payload JSON: {}", e.getMessage());
        }
    }
}
