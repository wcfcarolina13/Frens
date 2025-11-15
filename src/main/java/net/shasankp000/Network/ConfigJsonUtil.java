package net.shasankp000.Network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.shasankp000.AIPlayer;

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
        } catch (Exception e) {
            AIPlayer.LOGGER.warn("Failed to parse config payload JSON: {}", e.getMessage());
        }
    }
}
