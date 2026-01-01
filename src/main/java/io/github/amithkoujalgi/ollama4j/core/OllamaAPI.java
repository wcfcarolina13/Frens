package io.github.amithkoujalgi.ollama4j.core;

import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatRequestModel;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatResult;
import io.github.amithkoujalgi.ollama4j.core.types.OllamaModelType;
import java.util.Collections;
import java.util.List;

/** Minimal compile-time stub for OllamaAPI used by the project. */
public class OllamaAPI {
    private final String host;

    public OllamaAPI(String host) {
        this.host = host;
    }

    public OllamaAPI() {
        this.host = "http://localhost";
    }

    public void setRequestTimeoutSeconds(int secs) {
        // no-op for compile
    }

    public OllamaChatResult chat(OllamaChatRequestModel request) throws java.io.IOException, InterruptedException, io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException {
        return new OllamaChatResult();
    }

    public List<Double> generateEmbeddings(OllamaModelType type, String input) throws java.io.IOException, InterruptedException, io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException {
        return Collections.emptyList();
    }

    public boolean ping() {
        return true;
    }
}
