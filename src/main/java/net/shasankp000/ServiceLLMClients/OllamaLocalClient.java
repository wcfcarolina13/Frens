package net.shasankp000.ServiceLLMClients;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessageRole;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatRequestBuilder;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatRequestModel;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Simple LLMClient implementation that talks to a locally running Ollama instance.
 */
public final class OllamaLocalClient implements LLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("ollama-client");

    private final OllamaAPI api;
    private final String baseUrl;
    private final String model;

    public OllamaLocalClient(String baseUrl, String model) {
        String normalizedUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:11434"
                : baseUrl.trim();
        if (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        this.api = new OllamaAPI(normalizedUrl);
        this.api.setRequestTimeoutSeconds(120);
        this.baseUrl = normalizedUrl;
        this.model = (model == null || model.isBlank()) ? "llama3:8b" : model;
    }

    @Override
    public String sendPrompt(String systemPrompt, String userPrompt) {
        try {
            OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(model);
            OllamaChatRequestModel request = builder
                    .withMessage(OllamaChatMessageRole.SYSTEM, systemPrompt == null ? "" : systemPrompt)
                    .withMessage(OllamaChatMessageRole.USER, userPrompt == null ? "" : userPrompt)
                    .build();
            OllamaChatResult result = api.chat(request);
            return result.getResponse();
        } catch (Exception e) {
            LOGGER.error("Failed to execute Ollama prompt: {}", e.getMessage(), e);
            return "⚠️ Ollama backend is unavailable right now. Please verify it is running.";
        }
    }

    @Override
    public boolean isReachable() {
        try {
            URL url = new URL(baseUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("GET");
            connection.connect();
            connection.getResponseCode(); // any response code indicates the service is reachable
            connection.disconnect();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Ollama host {} unreachable: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    @Override
    public String getProvider() {
        return "ollama";
    }
}
