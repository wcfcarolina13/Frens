package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Local-Ollama adapter for {@link SoulModelProvider}.
 *
 * <p>Posts non-streaming chat completions to {@code <base>/api/chat}. The pilot makes exactly
 * one provider call per accepted turn — no retry, no fallback. Every ordinary failure mode
 * (non-2xx status, timeout, cancellation, malformed response) is mapped to a typed
 * {@link SoulTypes.FailureCode} and returned as a normal, successful future completion; the
 * raw HTTP response body is never surfaced in dialogue text or logs.
 */
public final class OllamaSoulProvider implements SoulModelProvider {

    /** Test seam: lets tests inject a fake transport instead of a real {@link HttpClient}. */
    @FunctionalInterface
    interface Transport {
        CompletableFuture<HttpResponse<String>> send(HttpRequest request);
    }

    private static final Duration HEALTH_TIMEOUT = Duration.ofMillis(1500);
    private static final double TEMPERATURE = 0.7;
    private static final int NUM_CTX = 8192;

    private final URI baseUri;
    private final String model;
    private final Transport transport;
    private final ObjectMapper mapper;
    private final HttpClient ownedClient;

    /** Production constructor: builds and owns its own {@link HttpClient}. */
    public OllamaSoulProvider(URI baseUri, String model, ObjectMapper mapper) {
        this(baseUri, model, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    private OllamaSoulProvider(URI baseUri, String model, ObjectMapper mapper, HttpClient ownedClient) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.model = model == null ? "" : model;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ownedClient = ownedClient;
        this.transport = request -> ownedClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Package-private test seam: caller supplies the transport, no real HTTP client is created. */
    OllamaSoulProvider(URI baseUri, String model, Transport transport, ObjectMapper mapper) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.model = model == null ? "" : model;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ownedClient = null;
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public Call generate(SoulTypes.ProviderRequest request) {
        long startNanos = System.nanoTime();
        String body;
        try {
            body = buildRequestBody(request);
        } catch (RuntimeException ex) {
            return new Call(CompletableFuture.completedFuture(
                    failureResult(SoulTypes.FailureCode.INTERNAL, 0L)), () -> { });
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/chat"))
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        CompletableFuture<HttpResponse<String>> httpFuture = transport.send(httpRequest);
        CompletableFuture<SoulTypes.ProviderResult> resultFuture = httpFuture.handle((response, throwable) -> {
            long elapsedMillis = elapsedMillis(startNanos);
            if (throwable != null) {
                return mapFailure(throwable, elapsedMillis);
            }
            return mapResponse(response, elapsedMillis);
        });

        return new Call(resultFuture, () -> httpFuture.cancel(false));
    }

    @Override
    public CompletableFuture<Boolean> health() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/api/tags"))
                .timeout(HEALTH_TIMEOUT)
                .GET()
                .build();
        return transport.send(request)
                .handle((response, throwable) -> throwable == null
                        && response.statusCode() >= 200 && response.statusCode() < 300);
    }

    @Override
    public void close() {
        if (ownedClient != null) {
            // shutdownNow() is the non-blocking variant; close()/shutdown() may wait for
            // in-flight exchanges to drain, which this provider's close() must not do.
            ownedClient.shutdownNow();
        }
    }

    // === Request building ===

    String buildRequestBody(SoulTypes.ProviderRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        for (SoulTypes.Message message : request.messages()) {
            ObjectNode messageNode = messages.addObject();
            messageNode.put("role", ollamaRole(message.role()));
            messageNode.put("content", message.content());
        }
        root.put("stream", false);
        // 60m, not the Ollama-default 5m: reloading the model mid-game saturates the
        // GPU/unified memory alongside Minecraft's renderer (observed 2.7s server ticks
        // during a cold load in live testing). Keeping it resident across a play session
        // trades ~5 GB RAM for never paying that load while the game is running.
        root.put("keep_alive", "60m");
        ObjectNode options = root.putObject("options");
        options.put("temperature", TEMPERATURE);
        options.put("num_predict", request.maxOutputTokens());
        // Pin the context window instead of inheriting the server default. An Ollama
        // install configured for a model's maximum context (observed: 131k on llama3.1:8b)
        // allocates a ~74 GB KV cache that spills to CPU and starves the game. The prompt
        // assembler budgets ~5k tokens worst-case, so 8192 is comfortable and keeps the
        // runner small enough to share the GPU with Minecraft.
        options.put("num_ctx", NUM_CTX);
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Ollama chat request", ex);
        }
    }

    private static String ollamaRole(SoulTypes.Role role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    // === Response mapping ===

    private SoulTypes.ProviderResult mapResponse(HttpResponse<String> response, long elapsedMillis) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return failureResult(SoulTypes.FailureCode.UNAVAILABLE, elapsedMillis);
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("message").path("content");
            if (!content.isTextual()) {
                return failureResult(SoulTypes.FailureCode.MALFORMED, elapsedMillis);
            }
            return new SoulTypes.ProviderResult(true, content.asText(), null, id(), model,
                    elapsedMillis, null, null, null);
        } catch (RuntimeException | JsonProcessingException ex) {
            return failureResult(SoulTypes.FailureCode.MALFORMED, elapsedMillis);
        }
    }

    private SoulTypes.ProviderResult mapFailure(Throwable throwable, long elapsedMillis) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
        SoulTypes.FailureCode code;
        if (cause instanceof CancellationException) {
            code = SoulTypes.FailureCode.CANCELLED;
        } else if (cause instanceof HttpTimeoutException) {
            code = SoulTypes.FailureCode.TIMEOUT;
        } else {
            code = SoulTypes.FailureCode.UNAVAILABLE;
        }
        return failureResult(code, elapsedMillis);
    }

    private SoulTypes.ProviderResult failureResult(SoulTypes.FailureCode code, long elapsedMillis) {
        return new SoulTypes.ProviderResult(false, "", code, id(), model, elapsedMillis, null, null, null);
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
