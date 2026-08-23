package net.wcfcarolina13.GameAI.souls;

import java.util.concurrent.CompletableFuture;

/**
 * Provider-neutral contract for generating a soul's next line of dialogue.
 *
 * <p>Implementations never throw for ordinary generation failures (timeouts, upstream errors,
 * malformed responses, cancellation) — those are reported as a normal, successfully-completed
 * {@link SoulTypes.ProviderResult} with {@code success=false} and a {@link SoulTypes.FailureCode}.
 * The {@link Call#result()} future should only complete exceptionally for genuinely unexpected
 * programming errors, never for the expected failure modes of talking to a model backend.
 */
public interface SoulModelProvider extends AutoCloseable {

    /** Short, stable identifier for this provider (e.g. {@code "ollama"}). Used for logging/metadata. */
    String id();

    /**
     * Starts one generation call for {@code request}. Returns immediately with a {@link Call}
     * pairing the eventual result with a way to cancel the in-flight request.
     */
    Call generate(SoulTypes.ProviderRequest request);

    /** Cheap reachability probe; never throws, resolves to {@code false} on any failure. */
    CompletableFuture<Boolean> health();

    /**
     * One in-flight generation attempt.
     *
     * @param result the eventual outcome; a well-behaved provider always completes this
     *               normally with a typed {@link SoulTypes.ProviderResult}, even on failure.
     * @param cancel cancels the underlying transport-level call (e.g. the HTTP request).
     *               Idempotent; safe to call more than once or after completion.
     */
    record Call(CompletableFuture<SoulTypes.ProviderResult> result, Runnable cancel) {
        public void cancelNow() {
            cancel.run();
        }
    }

    /** Releases provider-owned resources (e.g. an owned {@code HttpClient}). Must not block. */
    @Override
    void close();
}
