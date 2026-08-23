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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

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
    private final ConcurrentHashMap<UUID, SoulTypes.SoulState> cachedStates = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SoulStore(Path worldRoot, ExecutorService executor) {
        this.root = worldRoot.resolve("frens").resolve("souls").resolve("v1");
        this.executor = executor;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Production factory: opens the world-local store on its own dedicated, named daemon writer
     * thread ({@code frens-soul-store}) instead of requiring a caller-supplied executor. The
     * injected-executor constructor above stays the one tests use.
     */
    public SoulStore(Path worldRoot) {
        this(worldRoot, newWriterExecutor());
    }

    private static ExecutorService newWriterExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "frens-soul-store");
            thread.setDaemon(true);
            return thread;
        });
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
            SoulTypes.ConversationCursor cursor =
                    reconciledCursor(state, key.botId(), key.playerId(), cursorKey);
            long sequence = reconciledNextSequence(cursor, activeFile(key.botId(), key.playerId()));

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
            SoulTypes.ConversationCursor cursor =
                    reconciledCursor(state, key.botId(), key.playerId(), cursorKey);

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

    /**
     * Loads every bot's current {@link SoulTypes.SoulState} from disk into the in-memory cache
     * backing {@link #cachedState(UUID)}. Never creates {@code root} or any subdirectory — a
     * world where souls have never been enabled is left completely untouched on disk.
     */
    public CompletableFuture<Void> preloadIndex() {
        return submit(() -> {
            loadIndexFromDisk();
            return null;
        });
    }

    /**
     * Synchronous, non-blocking read of the last known {@link SoulTypes.SoulState} for
     * {@code botId}: populated by {@link #preloadIndex()} and kept current by every subsequent
     * write through this store. Empty until the bot has been preloaded or written at least once
     * in this process — callers use that to distinguish "no state yet" from "still loading".
     */
    public Optional<SoulTypes.SoulState> cachedState(UUID botId) {
        return Optional.ofNullable(cachedStates.get(botId));
    }

    /**
     * Rejects any write submitted after this call and starts an orderly shutdown of the single
     * writer thread. Deliberately non-blocking: each append/save is already a complete,
     * self-contained record, so already-queued writes simply drain on their own after this
     * returns rather than being awaited here — a caller on the server thread (e.g.
     * {@code SoulRuntime.stop()}) is never made to wait on filesystem I/O.
     */
    public void close() {
        closed = true;
        executor.shutdown();
    }

    // === Turn append/staleness ===

    private void appendTurn(SoulTypes.TurnToken token, SoulTypes.TurnKind kind, String content,
                             String provider, String model, Long elapsedMillis, SoulTypes.FailureCode code)
            throws IOException {
        SoulTypes.ConversationKey key = token.key();
        SoulTypes.SoulState state = loadState(key.botId());
        String cursorKey = cursorKey(key);

        if (!state.conversations().containsKey(cursorKey)) {
            throw new StaleEpochException("Stale epoch for " + key + ": token epoch " + token.epoch()
                    + " but no conversation cursor exists");
        }

        // Reconcile before comparing epochs: a crash between archiveAndReset's file move and
        // its cursor persist can leave soul.json reporting the pre-reset epoch even though the
        // reset already completed on disk (see reconciledCursor). Without this, a token minted
        // before that interrupted reset would be wrongly accepted as still current.
        SoulTypes.ConversationCursor cursor =
                reconciledCursor(state, key.botId(), key.playerId(), cursorKey);

        if (cursor.epoch() != token.epoch()) {
            throw new StaleEpochException("Stale epoch for " + key + ": token epoch " + token.epoch()
                    + " but current cursor epoch is " + cursor.epoch());
        }

        long sequence = reconciledNextSequence(cursor, activeFile(key.botId(), key.playerId()));
        SoulTypes.ConversationRecord record = new SoulTypes.ConversationRecord(
                token.correlationId(), cursor.epoch(), sequence, kind, content, Instant.now(),
                provider, model, elapsedMillis, code);
        appendRecord(activeFile(key.botId(), key.playerId()), record);

        persistCursor(state, cursorKey, new SoulTypes.ConversationCursor(cursor.epoch(), sequence + 1));
    }

    /**
     * Returns the conversation's true current cursor, self-healing an interrupted
     * {@link #archiveAndReset} if one is detected.
     *
     * <p>{@code archiveAndReset} moves the active transcript into {@code archive/} and only
     * then persists the bumped epoch to {@code soul.json}; a crash landing between those two
     * steps leaves an archive file for the cursor's current epoch already on disk. That is an
     * unambiguous signal the reset's move step already ran (nothing else ever creates an
     * {@code epoch-<N>-*.jsonl} archive file for epoch N), so this reconciles the cursor to
     * epoch+1 and persists the correction before returning it. Looped in case more than one
     * reset was interrupted in a row.
     */
    private SoulTypes.ConversationCursor reconciledCursor(
            SoulTypes.SoulState state, UUID botId, UUID playerId, String cursorKey) throws IOException {
        SoulTypes.ConversationCursor cursor = state.conversations()
                .getOrDefault(cursorKey, new SoulTypes.ConversationCursor(0L, 0L));

        Path archiveDir = archiveDir(botId, playerId);
        if (!Files.isDirectory(archiveDir)) {
            return cursor;
        }

        boolean healed = false;
        while (archiveExistsForEpoch(archiveDir, cursor.epoch())) {
            cursor = new SoulTypes.ConversationCursor(cursor.epoch() + 1, 0L);
            healed = true;
        }
        if (healed) {
            persistCursor(state, cursorKey, cursor);
        }
        return cursor;
    }

    private boolean archiveExistsForEpoch(Path archiveDir, long epoch) throws IOException {
        String prefix = "epoch-" + epoch + "-";
        try (Stream<Path> entries = Files.list(archiveDir)) {
            return entries.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        }
    }

    /**
     * Returns the next sequence to allocate for {@code cursor}, reconciled against whatever is
     * actually on disk in {@code activeFile}.
     *
     * <p>{@code beginHeardTurn}/{@code appendTurn} write the JSONL record and persist the
     * advanced cursor as two separate steps. A crash between them leaves a record on disk at
     * sequence N while {@code soul.json} still reports N as {@code nextSequence}; allocating
     * straight from the persisted cursor would then reuse N for a second, different record.
     * Scanning the transcript's actual max sequence for the cursor's epoch and taking
     * {@code max(cursor.nextSequence(), scannedMax + 1)} makes this correct regardless of which
     * side of that gap a crash landed on, without needing to reorder the two writes.
     */
    private long reconciledNextSequence(SoulTypes.ConversationCursor cursor, Path activeFile) throws IOException {
        if (!Files.exists(activeFile)) {
            return cursor.nextSequence();
        }
        List<SoulTypes.ConversationRecord> records = loadTranscript(activeFile, SoulTypes.ConversationRecord.class);
        long maxSequenceForEpoch = -1L;
        for (SoulTypes.ConversationRecord record : records) {
            if (record.epoch() == cursor.epoch() && record.sequence() > maxSequenceForEpoch) {
                maxSequenceForEpoch = record.sequence();
            }
        }
        return Math.max(cursor.nextSequence(), maxSequenceForEpoch + 1);
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
        cachedStates.put(state.botId(), state);
    }

    // === Index preload (disk -> cache only; never the reverse) ===

    private void loadIndexFromDisk() throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path botDir : (Iterable<Path>) entries.filter(Files::isDirectory)::iterator) {
                UUID botId;
                try {
                    botId = UUID.fromString(botDir.getFileName().toString());
                } catch (IllegalArgumentException notABotDirectory) {
                    continue;
                }
                Path soulJson = botDir.resolve("soul.json");
                if (Files.exists(soulJson)) {
                    cachedStates.put(botId, mapper.readValue(soulJson.toFile(), SoulTypes.SoulState.class));
                }
            }
        }
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
        if (closed) {
            future.completeExceptionally(new RejectedExecutionException("SoulStore is closed"));
            return future;
        }
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }
}
