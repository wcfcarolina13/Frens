package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;
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

    /**
     * In-memory high-water mark for a conversation's sequence numbers within one epoch, backing
     * {@link #reconciledNextSequence}. See that method's Javadoc for the caching contract.
     */
    private record SequenceCache(long epoch, long maxSequence) {}

    private final Path root;
    private final ExecutorService executor;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<UUID, SoulTypes.SoulState> cachedStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SoulTypes.KnowledgeMemory> cachedKnowledge = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SoulTypes.SoulMind> cachedMinds = new ConcurrentHashMap<>();
    // Only ever read/written from inside submit() tasks, i.e. on this store's single writer
    // thread -- a plain HashMap is race-free here the same way the rest of this class's
    // non-cachedStates fields are, per the class Javadoc's "all filesystem work is funneled
    // through the single-writer executor" invariant. Never exposed outside this class.
    private final Map<String, SequenceCache> sequenceCache = new HashMap<>();
    private volatile boolean closed;

    public SoulStore(Path worldRoot, ExecutorService executor) {
        this(executor, worldRoot.resolve("frens").resolve("souls").resolve("v1"));
    }

    /**
     * Production factory: opens the world-local store on its own dedicated, named daemon writer
     * thread ({@code frens-soul-store}) instead of requiring a caller-supplied executor. The
     * injected-executor constructor above stays the one tests use.
     */
    public SoulStore(Path worldRoot) {
        this(worldRoot, newWriterExecutor());
    }

    /** Root is used verbatim here — the public constructors resolve {@code frens/souls/v1} first. */
    private SoulStore(ExecutorService executor, Path resolvedRoot) {
        this.root = resolvedRoot;
        this.executor = executor;
        // Set -> LinkedHashSet so SoulMind.seen reloads in insertion order (oldest-first eviction).
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new SimpleModule().addAbstractTypeMapping(Set.class, LinkedHashSet.class));
    }

    /**
     * Opens a store whose on-disk root is exactly {@code exactRoot} — no {@code frens/souls/v1}
     * suffix is appended. Used by the party channel, which keeps its shared group transcripts in
     * a fully separate tree ({@code <world>/frens/party/v1}) while reusing this class's epoch,
     * crash-reconciliation, and bounded-history machinery unchanged.
     */
    public static SoulStore openAt(Path exactRoot) {
        return new SoulStore(newWriterExecutor(), exactRoot);
    }

    /** Test seam for {@link #openAt(Path)} with an injected writer executor. */
    public static SoulStore openAt(Path exactRoot, ExecutorService executor) {
        return new SoulStore(executor, exactRoot);
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
            // Decided here, on the single writer thread, so it can never race a concurrent write
            // for the same bot: a fresh (never-persisted) bot being deactivated has nothing to
            // deactivate, and must not synthesize a default soul.json just to report it -- that
            // would create frens/ on disk for a souls-disabled install on every bot death or
            // disconnect (SoulRuntime.cancelBot calls this unconditionally).
            boolean existed = Files.exists(soulJsonFile(botId));
            SoulTypes.SoulState state = loadState(botId);
            SoulTypes.SoulState updated = new SoulTypes.SoulState(
                    state.schemaVersion(), botId, state.profileId(), active, state.conversations());
            if (!active && !existed) {
                return updated;
            }
            saveState(updated);
            return updated;
        });
    }

    public CompletableFuture<Boolean> isActive(UUID botId) {
        return submit(() -> loadState(botId).active());
    }

    public CompletableFuture<SoulTypes.TurnToken> beginHeardTurn(
            SoulTypes.ConversationKey key, UUID correlationId, String content, Instant occurredAt) {
        return beginHeardTurn(key, correlationId, content, occurredAt, List.of());
    }

    /**
     * As {@link #beginHeardTurn(SoulTypes.ConversationKey, UUID, String, Instant)}, but records
     * {@code participants} on the HEARD record — who was present/addressed for this turn, which
     * the party channel needs and the memory digest reads back. Pre-participants records on disk
     * deserialize with an empty list.
     */
    public CompletableFuture<SoulTypes.TurnToken> beginHeardTurn(
            SoulTypes.ConversationKey key, UUID correlationId, String content, Instant occurredAt,
            List<UUID> participants) {
        return submit(() -> {
            SoulTypes.SoulState state = loadState(key.botId());
            String cursorKey = cursorKey(key);
            SoulTypes.ConversationCursor cursor =
                    reconciledCursor(state, key.botId(), key.playerId(), cursorKey);
            long sequence =
                    reconciledNextSequence(cursorKey, cursor, activeFile(key.botId(), key.playerId()));

            SoulTypes.ConversationRecord record = new SoulTypes.ConversationRecord(
                    correlationId, cursor.epoch(), sequence, SoulTypes.TurnKind.HEARD,
                    content, occurredAt, "", "", null, null, participants);
            appendRecord(activeFile(key.botId(), key.playerId()), record);
            // Advance the cache before persisting the cursor: if persistCursor itself throws
            // (an ordinary same-process I/O failure -- the executor survives and keeps serving
            // later calls), the JSONL record above is already durably on disk, so the cache must
            // already reflect it or the next call would recompute this same sequence and write a
            // real duplicate. See recordWrittenSequence's Javadoc.
            recordWrittenSequence(cursorKey, cursor.epoch(), sequence);
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
        return submit(() -> loadBounded(cursorKey(key),
                activeFile(key.botId(), key.playerId()), Long.MAX_VALUE, maxTurns, maxChars));
    }

    public CompletableFuture<List<SoulTypes.ConversationRecord>> recentBefore(
            SoulTypes.TurnToken token, int maxTurns, int maxChars) {
        return submit(() -> loadBounded(cursorKey(token.key()),
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
            // New epoch, fresh (moved-away) active file -- whatever the cache held for the prior
            // epoch no longer applies. The next reconciledNextSequence call re-seeds from scratch.
            sequenceCache.remove(cursorKey);
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

        long sequence = reconciledNextSequence(cursorKey, cursor, activeFile(key.botId(), key.playerId()));
        SoulTypes.ConversationRecord record = new SoulTypes.ConversationRecord(
                token.correlationId(), cursor.epoch(), sequence, kind, content, Instant.now(),
                provider, model, elapsedMillis, code);
        appendRecord(activeFile(key.botId(), key.playerId()), record);
        // Same ordering rationale as beginHeardTurn: advance the cache before the cursor persist
        // can fail, so a persistCursor IOException never leaves the cache behind what's already
        // durably on disk.
        recordWrittenSequence(cursorKey, cursor.epoch(), sequence);
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
            // The epoch just moved out from under whatever the sequence cache thought was
            // current -- reconciledNextSequence's own epoch check would catch this too, but
            // clearing it here makes the invalidation explicit rather than incidental.
            sequenceCache.remove(cursorKey);
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
     * actually on disk in {@code activeFile} -- backed by an in-memory {@link SequenceCache} keyed
     * by {@code cursorKey} so this is O(1) after the first call for a given conversation+epoch in
     * this process, instead of re-scanning the whole transcript on every turn.
     *
     * <p>{@code beginHeardTurn}/{@code appendTurn} write the JSONL record and persist the
     * advanced cursor as two separate steps. A crash-then-restart between them leaves a record on
     * disk at sequence N while {@code soul.json} still reports N as {@code nextSequence};
     * allocating straight from the persisted cursor would then reuse N for a second, different
     * record. Scanning the transcript's actual max sequence for the cursor's epoch and taking
     * {@code max(cursor.nextSequence(), scannedMax + 1)} makes this correct regardless of which
     * side of that gap a crash landed on -- but that scan only needs to happen once per process
     * per conversation+epoch:
     *
     * <ul>
     *   <li><b>Cold path</b> (no cache entry, or a cached entry from a different epoch): this is
     *       either the first time this conversation has been touched since this {@link SoulStore}
     *       was opened, or the epoch just changed underneath it ({@link #archiveAndReset} or a
     *       healed interrupted reset in {@link #reconciledCursor}, both of which explicitly clear
     *       the stale entry). Scans the transcript exactly as before, then seeds the cache from the
     *       result so every subsequent call in this epoch is warm.
     *   <li><b>Warm path</b> (cached entry for the same epoch): every sequence written for this
     *       epoch so far in this process went through {@link #recordWrittenSequence}, all on this
     *       store's single writer thread, so the cached high-water mark is already exactly what a
     *       fresh scan would compute -- {@code max(cursor.nextSequence(), cached.maxSequence() + 1)}
     *       without touching the filesystem at all. {@code beginHeardTurn}/{@code appendTurn} call
     *       {@link #recordWrittenSequence} immediately after the JSONL append and <em>before</em>
     *       {@link #persistCursor} -- deliberately reordered from the two-step description above --
     *       so an ordinary same-process {@code persistCursor} failure (the executor survives; this
     *       is not a crash) can never leave the cache behind a record that is already durably on
     *       disk.
     * </ul>
     *
     * <p>Advisory for this process only: nothing here is persisted, and a fresh {@link SoulStore}
     * (e.g. after a real restart) always starts with an empty cache, so its first touch of any
     * conversation takes the cold path -- identical to this method's pre-caching behavior.
     */
    private long reconciledNextSequence(String cursorKey, SoulTypes.ConversationCursor cursor, Path activeFile)
            throws IOException {
        SequenceCache cached = sequenceCache.get(cursorKey);
        if (cached != null && cached.epoch() == cursor.epoch()) {
            return Math.max(cursor.nextSequence(), cached.maxSequence() + 1);
        }
        long scannedNext = scanNextSequenceFromDisk(cursorKey, cursor, activeFile);
        sequenceCache.put(cursorKey, new SequenceCache(cursor.epoch(), scannedNext - 1));
        return scannedNext;
    }

    /** The pre-caching scan: {@code max(cursor.nextSequence(), scannedMax + 1)} over {@code activeFile}. */
    private long scanNextSequenceFromDisk(String cursorKey, SoulTypes.ConversationCursor cursor, Path activeFile)
            throws IOException {
        if (!Files.exists(activeFile)) {
            return cursor.nextSequence();
        }
        List<SoulTypes.ConversationRecord> records =
                loadTranscript(activeFile, SoulTypes.ConversationRecord.class, cursorKey);
        long maxSequenceForEpoch = -1L;
        for (SoulTypes.ConversationRecord record : records) {
            if (record.epoch() == cursor.epoch() && record.sequence() > maxSequenceForEpoch) {
                maxSequenceForEpoch = record.sequence();
            }
        }
        return Math.max(cursor.nextSequence(), maxSequenceForEpoch + 1);
    }

    /**
     * Updates the warm-path high-water mark after a record has actually been appended at
     * {@code sequence}. Callers must invoke this immediately after the JSONL append and
     * <em>before</em> {@link #persistCursor} -- persisting the cursor can throw (an ordinary
     * same-process I/O failure) even though the append it followed already succeeded, and the
     * cache must reflect what is durably on disk regardless of whether that later persist
     * completes.
     */
    private void recordWrittenSequence(String cursorKey, long epoch, long sequence) {
        sequenceCache.put(cursorKey, new SequenceCache(epoch, sequence));
    }

    private void persistCursor(SoulTypes.SoulState state, String cursorKey, SoulTypes.ConversationCursor cursor)
            throws IOException {
        Map<String, SoulTypes.ConversationCursor> updated = new HashMap<>(state.conversations());
        updated.put(cursorKey, cursor);
        saveState(new SoulTypes.SoulState(
                state.schemaVersion(), state.botId(), state.profileId(), state.active(), updated));
    }

    /**
     * The single source of truth for the persistence cursor-key format used throughout the soul
     * store ({@code soul.json}'s {@code conversations} map is keyed by this string). Package-private
     * so {@link SoulRuntime} and {@link SoulMessageDelivery} can build the exact same key instead of
     * hand-rolling {@code channel + ":" + playerId} themselves -- any drift between independently
     * hand-built copies would silently break the epoch checks that key format backs.
     */
    static String cursorKey(SoulTypes.ConversationKey key) {
        return key.channel().name() + ":" + key.playerId();
    }

    // === Bounded history ===

    private List<SoulTypes.ConversationRecord> loadBounded(
            String cursorKey, Path file, long beforeSequenceExclusive, int maxTurns, int maxChars)
            throws IOException {
        List<SoulTypes.ConversationRecord> all = loadTranscript(file, SoulTypes.ConversationRecord.class, cursorKey);

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

    // === knowledge.json (seen places + told facts; merge policy in SoulKnowledgeMemoryOps) ===

    public CompletableFuture<SoulTypes.KnowledgeMemory> knowledgeMemory(UUID botId) {
        return submit(() -> loadKnowledge(botId));
    }

    public CompletableFuture<Void> recordSightings(UUID botId, List<SoulTypes.KnownPlace> sightings) {
        return submit(() -> {
            if (!sightings.isEmpty()) {
                saveKnowledge(botId, SoulKnowledgeMemoryOps.mergeSightings(loadKnowledge(botId), sightings));
            }
            return null;
        });
    }

    /** Non-blocking peek at the cached knowledge memory (empty until first load this process). */
    public Optional<SoulTypes.KnowledgeMemory> cachedKnowledgeMemory(UUID botId) {
        return Optional.ofNullable(cachedKnowledge.get(botId));
    }

    /** Disproof-on-revisit: removes remembered places whose positions were verified stale. */
    public CompletableFuture<Void> removePlaces(UUID botId, Set<String> positionKeys) {
        return submit(() -> {
            SoulTypes.KnowledgeMemory current = loadKnowledge(botId);
            SoulTypes.KnowledgeMemory pruned = SoulKnowledgeMemoryOps.removePlaces(current, positionKeys);
            if (pruned != current) {
                saveKnowledge(botId, pruned);
            }
            return null;
        });
    }

    public CompletableFuture<Void> recordToldFact(UUID botId, String topic, SoulTypes.ToldFact fact) {
        return submit(() -> {
            saveKnowledge(botId, SoulKnowledgeMemoryOps.mergeToldFact(loadKnowledge(botId), topic, fact));
            return null;
        });
    }

    private SoulTypes.KnowledgeMemory loadKnowledge(UUID botId) throws IOException {
        SoulTypes.KnowledgeMemory cached = cachedKnowledge.get(botId);
        if (cached != null) {
            return cached;
        }
        Path file = knowledgeFile(botId);
        SoulTypes.KnowledgeMemory loaded = Files.exists(file)
                ? mapper.readValue(file.toFile(), SoulTypes.KnowledgeMemory.class)
                : SoulTypes.KnowledgeMemory.empty();
        cachedKnowledge.put(botId, loaded);
        return loaded;
    }

    private void saveKnowledge(UUID botId, SoulTypes.KnowledgeMemory memory) throws IOException {
        Path file = knowledgeFile(botId);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        mapper.writeValue(tmp.toFile(), memory);
        atomicReplace(tmp, file);
        cachedKnowledge.put(botId, memory);
    }

    // === mind.json (stance, open threads, day memories, seen-registry; rules in SoulMindOps) ===

    public CompletableFuture<SoulTypes.SoulMind> mind(UUID botId) {
        return submit(() -> loadMind(botId));
    }

    /** Last loaded/saved mind, readable from any thread; empty until {@link #mind} or {@link #updateMind} ran. */
    public Optional<SoulTypes.SoulMind> cachedMind(UUID botId) {
        return Optional.ofNullable(cachedMinds.get(botId));
    }

    /** Load -> {@code update} -> save when changed; the returned future carries the resulting mind. */
    public CompletableFuture<SoulTypes.SoulMind> updateMind(UUID botId, UnaryOperator<SoulTypes.SoulMind> update) {
        return submit(() -> {
            SoulTypes.SoulMind current = loadMind(botId);
            SoulTypes.SoulMind next = Objects.requireNonNull(update.apply(current), "updateMind returned null");
            if (!next.equals(current)) {
                saveMind(botId, next);
            }
            return next;
        });
    }

    /**
     * Bot ids with an on-disk directory under the store root, i.e. every bot this store has ever
     * persisted anything for. Directory names that are not UUIDs (stray files a user dropped in)
     * are skipped; a store whose root does not exist yet yields an empty list.
     */
    public CompletableFuture<List<UUID>> botDirectories() {
        return submit(() -> {
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>();
            try (Stream<Path> entries = Files.list(root)) {
                for (Path entry : entries.toList()) {
                    if (!Files.isDirectory(entry)) {
                        continue;
                    }
                    try {
                        ids.add(UUID.fromString(entry.getFileName().toString()));
                    } catch (IllegalArgumentException notAUuid) {
                        // Not a bot directory -- ignore.
                    }
                }
            }
            return List.copyOf(ids);
        });
    }

    /** Players {@code botId} has a live transcript for (a {@code conversations/<player>/active.jsonl}). */
    public CompletableFuture<List<UUID>> conversationPlayers(UUID botId) {
        return submit(() -> {
            Path dir = conversationsDir(botId);
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>();
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    if (!Files.isDirectory(entry) || !Files.exists(entry.resolve("active.jsonl"))) {
                        continue;
                    }
                    try {
                        ids.add(UUID.fromString(entry.getFileName().toString()));
                    } catch (IllegalArgumentException notAUuid) {
                        // Not a player directory -- ignore.
                    }
                }
            }
            return List.copyOf(ids);
        });
    }

    /**
     * Records in the conversation's live transcript at or after {@code cursor}, in file order.
     *
     * <p>Only the current epoch is returned: the transcript's highest epoch wins, so a reader
     * holding a cursor from before an {@link #archiveAndReset} sees the whole fresh conversation
     * rather than nothing. Within that epoch, the cursor's {@code nextSequence} filters only when
     * {@code cursor.epoch()} is that same current epoch.
     */
    public CompletableFuture<List<SoulTypes.ConversationRecord>> recordsSince(
            SoulTypes.ConversationKey key, SoulTypes.ConversationCursor cursor) {
        return submit(() -> {
            List<SoulTypes.ConversationRecord> all = loadTranscript(
                    activeFile(key.botId(), key.playerId()), SoulTypes.ConversationRecord.class, cursorKey(key));
            if (all.isEmpty()) {
                return List.of();
            }
            long currentEpoch = all.stream()
                    .mapToLong(SoulTypes.ConversationRecord::epoch).max().orElseThrow();
            boolean applySequence = cursor.epoch() == currentEpoch;
            return all.stream()
                    .filter(record -> record.epoch() == currentEpoch)
                    .filter(record -> !applySequence || record.sequence() >= cursor.nextSequence())
                    .toList();
        });
    }

    /** Journal events that occurred strictly after {@code after}, oldest first. */
    public CompletableFuture<List<SoulTypes.SoulEvent>> eventsSince(UUID botId, Instant after) {
        return submit(() -> loadTranscript(eventsFile(botId), SoulTypes.SoulEvent.class).stream()
                .filter(event -> event.occurredAt().isAfter(after))
                .toList());
    }

    /** Rewrites {@code events.jsonl} atomically keeping only the last {@code keepLast}; returns how many went. */
    public CompletableFuture<Integer> trimEvents(UUID botId, int keepLast) {
        return submit(() -> {
            Path file = eventsFile(botId);
            List<SoulTypes.SoulEvent> all = loadTranscript(file, SoulTypes.SoulEvent.class);
            int keep = Math.max(0, keepLast);
            int removed = Math.max(0, all.size() - keep);
            if (removed == 0) {
                return 0;
            }
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp-" + UUID.randomUUID());
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (SoulTypes.SoulEvent event : all.subList(all.size() - keep, all.size())) {
                    writer.write(mapper.writeValueAsString(event));
                    writer.write(System.lineSeparator());
                }
            }
            atomicReplace(tmp, file);
            return removed;
        });
    }

    private SoulTypes.SoulMind loadMind(UUID botId) throws IOException {
        SoulTypes.SoulMind cached = cachedMinds.get(botId);
        if (cached != null) {
            return cached;
        }
        Path file = mindFile(botId);
        SoulTypes.SoulMind loaded = Files.exists(file)
                ? mapper.readValue(file.toFile(), SoulTypes.SoulMind.class)
                : SoulTypes.SoulMind.empty();
        cachedMinds.put(botId, loaded);
        return loaded;
    }

    private void saveMind(UUID botId, SoulTypes.SoulMind mind) throws IOException {
        Path file = mindFile(botId);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        mapper.writeValue(tmp.toFile(), mind);
        atomicReplace(tmp, file);
        cachedMinds.put(botId, mind);
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
        return loadTranscript(file, type, null);
    }

    /**
     * Same as {@link #loadTranscript(Path, Class)}, but for a conversation transcript that has a
     * sequence-cache entry: {@code cursorKeyForInvalidation} is passed through to
     * {@link #quarantineCorruptTail} so a corrupt-tail rewrite of {@code file} also drops any
     * cached {@link SequenceCache} entry for that conversation. Pass {@code null} (the two-arg
     * overload does) for files with no such entry, e.g. {@code events.jsonl}.
     */
    private <T> List<T> loadTranscript(Path file, Class<T> type, String cursorKeyForInvalidation)
            throws IOException {
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
                    quarantineCorruptTail(file, lines, i, cursorKeyForInvalidation);
                    return records;
                }
                throw new IOException(
                        "Malformed record before final line in " + file + " at line " + (i + 1), parseError);
            }
        }
        return records;
    }

    private void quarantineCorruptTail(Path file, List<String> lines, int corruptIndex,
                                        String cursorKeyForInvalidation) throws IOException {
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

        // The rewritten file no longer has the corrupt line at all, so whatever the sequence
        // cache held for this conversation is best discarded rather than trusted forward -- not
        // because a corrupt (unparseable) tail could itself have skewed a prior max-sequence
        // computation (it never parsed, so it was never counted), but so that guarantee doesn't
        // stay the only thing protecting a cache entry across a file rewrite it wasn't consulted
        // for.
        if (cursorKeyForInvalidation != null) {
            sequenceCache.remove(cursorKeyForInvalidation);
        }
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

    private Path knowledgeFile(UUID botId) {
        return botDir(botId).resolve("knowledge.json");
    }

    private Path mindFile(UUID botId) {
        return botDir(botId).resolve("mind.json");
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
