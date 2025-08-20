package net.shasankp000.FilingSystem;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.shasankp000.Exception.ollamaNotReachableException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class getLanguageModels {

    public static List<String> get() throws ollamaNotReachableException {
        Set<String> modelSet = new HashSet<>();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI("http://localhost:11434/api/tags"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
                JsonArray modelsArray = jsonObject.getAsJsonArray("models");

                if (modelsArray != null) {
                    for (JsonElement element : modelsArray) {
                        JsonObject modelObject = element.getAsJsonObject();
                        String modelName = modelObject.get("name").getAsString();
                        modelSet.add(modelName);
                    }
                }
            } else {
                throw new ollamaNotReachableException("Ollama Server returned status code: " + response.statusCode());
            }

        } catch (URISyntaxException | IOException | InterruptedException e) {
            throw new ollamaNotReachableException("Error pinging Ollama Server: " + e.getMessage());
        }

        return new ArrayList<>(modelSet);
    }
}