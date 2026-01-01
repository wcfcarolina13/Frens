package io.github.amithkoujalgi.ollama4j.core.models.chat;

/** Minimal compile-time stub builder used by the codebase. */
public class OllamaChatRequestBuilder {
    private final OllamaChatRequestModel model = new OllamaChatRequestModel();

    public OllamaChatRequestBuilder() {}

    public static OllamaChatRequestBuilder getInstance(String modelName) {
        return new OllamaChatRequestBuilder();
    }

    public OllamaChatRequestBuilder withMessage(OllamaChatMessageRole role, String message) {
        // no-op stub: store or ignore
        return this;
    }

    public OllamaChatRequestModel build() { return model; }
}
