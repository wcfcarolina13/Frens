package net.shasankp000.GameAI.llm;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Simple per-bot queue that lets us stack natural-language commands
 * while a long-running job is still in progress.
 */
public final class LLMActionQueue {

    private static final Map<UUID, Deque<QueuedCommand>> QUEUES = new ConcurrentHashMap<>();

    private LLMActionQueue() {
    }

    public static void enqueue(UUID botId, UUID commanderId, String message) {
        if (botId == null || message == null || message.isBlank()) {
            return;
        }
        QUEUES.computeIfAbsent(botId, id -> new ConcurrentLinkedDeque<>())
                .addLast(new QueuedCommand(commanderId, message));
    }

    public static Optional<QueuedCommand> poll(UUID botId) {
        if (botId == null) {
            return Optional.empty();
        }
        Deque<QueuedCommand> queue = QUEUES.get(botId);
        if (queue == null) {
            return Optional.empty();
        }
        QueuedCommand command = queue.pollFirst();
        if (queue.isEmpty()) {
            QUEUES.remove(botId);
        }
        return Optional.ofNullable(command);
    }

    public static int size(UUID botId) {
        Deque<QueuedCommand> queue = QUEUES.get(botId);
        return queue == null ? 0 : queue.size();
    }

    public static java.util.List<QueuedCommand> snapshot(UUID botId) {
        Deque<QueuedCommand> queue = QUEUES.get(botId);
        if (queue == null) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(queue);
    }

    public record QueuedCommand(UUID commanderId, String message) {
    }
}
