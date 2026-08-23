package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Crash-tolerant, world-local persistence for the soul-communication domain.
 *
 * <p>Everything lives under {@code <world>/frens/souls/v1}. Each bot owns a {@code soul.json}
 * metadata file (profile binding, active flag, per-conversation cursors) and a
 * {@code conversations/<player-uuid>/active.jsonl} append-only transcript per player it has
 * talked to directly. Resetting a conversation archives the current transcript rather than
 * deleting it, and bumps that conversation's epoch so any turn token issued before the reset
 * is rejected as stale.
 *
 * <p>All filesystem work is funneled through the injected single-writer {@link ExecutorService}
 * so concurrent callers never interleave partial writes. This class is pure Java — no
 * Minecraft/Fabric imports — so it can be unit tested and reused off the server thread.
 */
public final class SoulStore {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    /** Thrown when a {@link SoulTypes.TurnToken} no longer matches the conversation's current epoch. */
    public static final class StaleEpochException extends RuntimeException {
        private final SoulTypes.FailureCode code = SoulTypes.FailureCode.STALE_EPOCH;

        public StaleEpochException(String message) {
            super(message);
        }

        public SoulTypes.FailureCode code() {
            return code;
        }
    }

    private final Path root;
    private final ExecutorService executor;
    private final ObjectMapper mapper;

    public SoulStore(Path worldRoot, ExecutorService executor) {
        this.root = worldRoot.resolve("frens").resolve("souls").resolve("v1");
        this.executor = executor;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // === Public API ===

    public CompletableFuture<SoulTypes.SoulState> state(UUID botId) {
        return submit(() -> loadState(botId));
    }

    public CompletableFuture<SoulTypes.SoulState> bindProfile(UUID botId, String profileId) {
        return submit(() -> {
            SoulTypes.SoulState state = loadState(botId);
            SoulTypes.SoulState updated = new SoulTypes.SoulState(
                    state.schemaVersion(), botId, profileId, state.active(), state.conversations());
            saveState(updated);
            return updated;
        });
    }

    public CompletableFuture<SoulTypes.SoulState> setActive(UUID botId, boolean active) {
        return submit(() -> {
            SoulTypes.SoulState state = loadState(botId);
            SoulTypes.SoulState updated = new SoulTypes.SoulState(
                    state.schemaVersion(), botId, state.profileId(), active, state.conversations());
            saveState(updated);
            return updated;
        });
    }

    public CompletableFuture<Boolean> isActive(UUID botId) {
        return submit(() -> loadState(botId).active());
    }

    public CompletableFuture<SoulTypes.TurnToken> beginHeardTurn(
            SoulTypes.ConversationKey key, UUID correlationId, String content, Instant occurredAt) {
        return submit(() -> {
            SoulTypes.SoulState state = loadState(key.botId());
            String cursorKey = cursorKey(key);
            SoulTypes.ConversationCursor cursor = state.conversations()
                    .getOrDefault(cursorKey, new SoulTypes.ConversationCursor(0L, 0L));
            long sequence = cursor.nextSequence();

            SoulTypes.ConversationRecord record = new SoulTypes.ConversationRecord(
                    correlationId, cursor.epoch(), sequence, SoulTypes.TurnKind.HEARD,
                    content, occurredAt, "", "", null, null);
            appendRecord(activeFile(key.botId(), key.playerId()), record);

            persistCursor(state, cursorKey, new SoulTypes.ConversationCursor(cursor.epoch(), sequence + 1));

            return new SoulTypes.TurnToken(key, correlationId, cursor.epoch(), sequence);
        });
    }

    public CompletableFuture<Void> appendSpoken(
            SoulTypes.TurnToken token, String content, SoulTypes.ProviderResult metadata) {
        return submit(() -> {
            appendTurn(token, SoulTypes.TurnKind.SPOKEN, content,
                    metadata.provider(), metadata.model(), metadata.elapsedMillis(), metadata.failureCode());
            return null;
        });
    }

    public CompletableFuture<Void> appendFailure(
            SoulTypes.TurnToken token, SoulTypes.FailureCode code, String provider, String model,
            Long elapsedMillis) {
        return submit(() -> {
            appendTurn(token, SoulTypes.TurnKind.FAILURE, "", provider, model, elapsedMillis, code);
            return null;
        });
    }

    public CompletableFuture<List<SoulTypes.ConversationRecord>> recent(
            SoulTypes.ConversationKey key, int maxTurns, int maxChars) {
        return submit(() -> loadBounded(
                activeFile(key.botId(), key.playerId()), Long.MAX_VALUE, maxTurns, maxChars));
    }

    public CompletableFuture<List<SoulTypes.ConversationRecord>> recentBefore(
            SoulTypes.TurnToken token, int maxTurns, int maxChars) {
        return submit(() -> loadBounded(
                activeFile(token.key().botId(), token.key().playerId()), token.sequence(), maxTurns, maxChars));
    }

    public CompletableFuture<Long> archiveAndReset(SoulTypes.ConversationKey key) {
        return submit(() -> {
            SoulTypes.SoulState state = loadState(key.botId());
            String cursorKey = cursorKey(key);
            SoulTypes.ConversationCursor cursor = state.conversations()
                    .getOrDefault(cursorKey, new SoulTypes.ConversationCursor(0L, 0L));

            Path active = activeFile(key.botId(), key.playerId());
            Path archiveDir = archiveDir(key.botId(), key.playerId());
            Files.createDirectories(archiveDir);

            String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
            Path archiveFile = archiveDir.resolve("epoch-" + cursor.epoch() + "-" + timestamp + ".jsonl");
            if (Files.exists(active)) {
                Files.move(active, archiveFile, StandardCopyOption.REPLACE_EXISTING);
            }

            long newEpoch = cursor.epoch() + 1;
            persistCursor(state, cursorKey, new SoulTypes.ConversationCursor(newEpoch, 0L));
            return newEpoch;
        });
    }

    public CompletableFuture<Void> appendEvent(UUID botId, SoulTypes.SoulEvent event) {
        return submit(() -> {
            appendLine(eventsFile(botId), mapper.writeValueAsString(event));
            return null;
        });
    }

    public CompletableFuture<List<SoulTypes.SoulEvent>> recentEvents(UUID botId, int maxRecords) {
        return submit(() -> {
            List<SoulTypes.SoulEvent> all = loadTranscript(eventsFile(botId), SoulTypes.SoulEvent.class);
            int from = Math.max(0, all.size() - Math.max(0, maxRecords));
            return List.copyOf(all.subList(from, all.size()));
        });
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // === Turn append/staleness ===

    private void appendTurn(SoulTypes.TurnToken token, SoulTypes.TurnKind kind, String content,
                             String provider, String model, Long elapsedMillis, SoulTypes.FailureCode code)
            throws IOException {
        SoulTypes.ConversationKey key = token.key();
        SoulTypes.SoulState state = loadState(key.botId());
        String cursorKey = cursorKey(key);
        SoulTypes.ConversationCursor cursor = state.conversations().get(cursorKey);

        if (cursor == null || cursor.epoch() != token.epoch()) {
            String current = cursor == null ? "missing" : String.valueOf(cursor.epoch());
            throw new StaleEpochException("Stale epoch for " + key + ": token epoch " + token.epoch()
                    + " but current cursor epoch is " + current);
        }

        long sequence = cursor.nextSequence();
        SoulTypes.ConversationRecord record = new SoulTypes.ConversationRecord(
                token.correlationId(), cursor.epoch(), sequence, kind, content, Instant.now(),
                provider, model, elapsedMillis, code);
        appendRecord(activeFile(key.botId(), key.playerId()), record);

        persistCursor(state, cursorKey, new SoulTypes.ConversationCursor(cursor.epoch(), sequence + 1));
    }

    private void persistCursor(SoulTypes.SoulState state, String cursorKey, SoulTypes.ConversationCursor cursor)
            throws IOException {
        Map<String, SoulTypes.ConversationCursor> updated = new HashMap<>(state.conversations());
        updated.put(cursorKey, cursor);
        saveState(new SoulTypes.SoulState(
                state.schemaVersion(), state.botId(), state.profileId(), state.active(), updated));
    }

    private static String cursorKey(SoulTypes.ConversationKey key) {
        return key.channel().name() + ":" + key.playerId();
    }

    // === Bounded history ===

    private List<SoulTypes.ConversationRecord> loadBounded(
            Path file, long beforeSequenceExclusive, int maxTurns, int maxChars) throws IOException {
        List<SoulTypes.ConversationRecord> all = loadTranscript(file, SoulTypes.ConversationRecord.class);

        List<SoulTypes.ConversationRecord> filtered = new ArrayList<>();
        for (SoulTypes.ConversationRecord record : all) {
            if (record.sequence() < beforeSequenceExclusive) {
                filtered.add(record);
            }
        }

        int from = Math.max(0, filtered.size() - Math.max(0, maxTurns));
        List<SoulTypes.ConversationRecord> windowed = filtered.subList(from, filtered.size());

        int totalChars = 0;
        int cutIndex = 0;
        for (int i = windowed.size() - 1; i >= 0; i--) {
            int len = windowed.get(i).content().length();
            if (totalChars > 0 && totalChars + len > maxChars) {
                cutIndex = i + 1;
                break;
            }
            totalChars += len;
            cutIndex = i;
        }

        return List.copyOf(windowed.subList(cutIndex, windowed.size()));
    }

    // === soul.json ===

    private SoulTypes.SoulState loadState(UUID botId) throws IOException {
        Path file = soulJsonFile(botId);
        if (!Files.exists(file)) {
            return new SoulTypes.SoulState(1, botId, "", false, Map.of());
        }
        return mapper.readValue(file.toFile(), SoulTypes.SoulState.class);
    }

    private void saveState(SoulTypes.SoulState state) throws IOException {
        Path file = soulJsonFile(state.botId());
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        mapper.writeValue(tmp.toFile(), state);
        atomicReplace(tmp, file);
    }

    // === JSONL append ===

    private void appendRecord(Path file, SoulTypes.ConversationRecord record) throws IOException {
        appendLine(file, mapper.writeValueAsString(record));
    }

    private void appendLine(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(json);
            writer.write(System.lineSeparator());
            writer.flush();
        }
    }

    // === JSONL load + corrupt-tail recovery ===

    private <T> List<T> loadTranscript(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        int lastNonBlank = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                lastNonBlank = i;
                break;
            }
        }

        List<T> records = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(mapper.readValue(line, type));
            } catch (JsonProcessingException parseError) {
                if (i == lastNonBlank) {
                    quarantineCorruptTail(file, lines, i);
                    return records;
                }
                throw new IOException(
                        "Malformed record before final line in " + file + " at line " + (i + 1), parseError);
            }
        }
        return records;
    }

    private void quarantineCorruptTail(Path file, List<String> lines, int corruptIndex) throws IOException {
        String corruptLine = lines.get(corruptIndex);
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        Path corruptFile = file.resolveSibling(file.getFileName() + ".corrupt-tail-" + timestamp);
        Files.writeString(corruptFile, corruptLine + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < corruptIndex; i++) {
            rebuilt.append(lines.get(i)).append(System.lineSeparator());
        }

        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        Files.writeString(tmp, rebuilt.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        atomicReplace(tmp, file);
    }

    private void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // === Paths ===

    private Path botDir(UUID botId) {
        return root.resolve(botId.toString());
    }

    private Path soulJsonFile(UUID botId) {
        return botDir(botId).resolve("soul.json");
    }

    private Path conversationsDir(UUID botId) {
        return botDir(botId).resolve("conversations");
    }

    private Path playerDir(UUID botId, UUID playerId) {
        return conversationsDir(botId).resolve(playerId.toString());
    }

    private Path activeFile(UUID botId, UUID playerId) {
        return playerDir(botId, playerId).resolve("active.jsonl");
    }

    private Path archiveDir(UUID botId, UUID playerId) {
        return playerDir(botId, playerId).resolve("archive");
    }

    private Path eventsFile(UUID botId) {
        return botDir(botId).resolve("events.jsonl");
    }

    // === Executor plumbing ===

    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
