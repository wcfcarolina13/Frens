package net.shasankp000.GameAI.llm;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks long-running jobs triggered via the LLM layer so the bots can
 * acknowledge what they are doing and report completion/failure back to chat.
 */
public final class LLMJobTracker {

    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_RESULT = new ConcurrentHashMap<>();

    private LLMJobTracker() {
    }

    public static void reserveJob(UUID botId, UUID commanderId, String description) {
        if (botId == null || description == null || description.isBlank()) {
            return;
        }
        JOBS.computeIfAbsent(botId, id -> new Job(description, commanderId, Instant.now()));
    }

    public static void startJob(UUID botId, UUID commanderId, String description) {
        if (botId == null || description == null || description.isBlank()) {
            return;
        }
        JOBS.compute(botId, (id, existing) -> {
            Instant startedAt = existing != null ? existing.startedAt() : Instant.now();
            UUID owner = commanderId != null ? commanderId : (existing != null ? existing.commanderId() : null);
            return new Job(description, owner, startedAt);
        });
    }

    public static void completeJob(UUID botId, boolean success, String resultMessage) {
        if (botId == null) {
            return;
        }
        JOBS.computeIfPresent(botId, (id, existing) -> existing.complete(success, resultMessage));
        JOBS.remove(botId);
        if (resultMessage != null) {
            LAST_RESULT.put(botId, resultMessage);
        }
    }

    public static Optional<Job> getActiveJob(UUID botId) {
        return Optional.ofNullable(JOBS.get(botId));
    }

    public static Optional<String> getLastResult(UUID botId) {
        return Optional.ofNullable(LAST_RESULT.get(botId));
    }

    public record Job(String description, UUID commanderId, Instant startedAt, boolean success,
                      String completionMessage, Instant completedAt) {

        Job(String description, UUID commanderId, Instant startedAt) {
            this(description, commanderId, startedAt, false, null, null);
        }

        Job withDescription(String newDescription, UUID newCommanderId) {
            if (newDescription == null || newDescription.isBlank()) {
                return this;
            }
            UUID owner = newCommanderId != null ? newCommanderId : commanderId;
            return new Job(newDescription, owner, startedAt, success, completionMessage, completedAt);
        }

        Job complete(boolean success, String completionMessage) {
            return new Job(description, commanderId, startedAt, success, completionMessage, Instant.now());
        }
    }
}
