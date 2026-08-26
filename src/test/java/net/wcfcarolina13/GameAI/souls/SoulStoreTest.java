package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulStoreTest {

    @TempDir
    Path worldRoot;

    private ExecutorService executor;
    private SoulStore store;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        store = new SoulStore(worldRoot, executor);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private Path activeFile(UUID bot, UUID player) {
        return worldRoot.resolve("frens/souls/v1")
                .resolve(bot.toString()).resolve("conversations")
                .resolve(player.toString()).resolve("active.jsonl");
    }

    private Path botDir(UUID bot) {
        return worldRoot.resolve("frens/souls/v1").resolve(bot.toString());
    }

    @Test
    void openAtUsesTheExactRootForPartyTranscripts() throws Exception {
        Path partyRoot = worldRoot.resolve("frens/party/v1");
        ExecutorService partyExecutor = Executors.newSingleThreadExecutor();
        SoulStore partyStore = SoulStore.openAt(partyRoot, partyExecutor);
        try {
            UUID owner = UUID.randomUUID();
            var key = new SoulTypes.ConversationKey(owner, owner, SoulTypes.Channel.PARTY);
            partyStore.beginHeardTurn(key, UUID.randomUUID(), "Bradley: hello all", Instant.now())
                    .get(5, SECONDS);
            Path active = partyRoot.resolve(owner.toString())
                    .resolve("conversations").resolve(owner.toString()).resolve("active.jsonl");
            assertTrue(Files.exists(active), "party transcript should live directly under the exact root");
            assertFalse(Files.exists(partyRoot.resolve("frens")),
                    "openAt must not re-nest frens/souls/v1 under the exact root");

            long newEpoch = partyStore.archiveAndReset(key).get(5, SECONDS);
            assertEquals(1L, newEpoch);
            assertFalse(Files.exists(active), "reset should archive the active party transcript");
        } finally {
            partyStore.close();
            partyExecutor.shutdownNow();
        }
    }

    @Test
    void archivesResetEpochAndRejectsStaleSpeech() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        SoulStore store = new SoulStore(worldRoot, Executors.newSingleThreadExecutor());

        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "hello", Instant.EPOCH)
                .get(2, SECONDS);
        long newEpoch = store.archiveAndReset(key).get(2, SECONDS);

        assertEquals(1L, newEpoch);
        assertThrows(ExecutionException.class, () -> store.appendSpoken(
                token, "stale reply", new SoulTypes.ProviderResult(
                        true, "stale reply", null, "ollama", "test-model",
                        10L, null, null, null)).get(2, SECONDS));
        assertTrue(Files.exists(worldRoot.resolve("frens/souls/v1")
                .resolve(bot.toString()).resolve("conversations")
                .resolve(player.toString()).resolve("archive")));

        store.close();
    }

    /**
     * Regression test for a souls-disabled install writing soul.json on every bot death or
     * disconnect: {@code setActive(botId, false)} on a bot with no persisted state must never
     * synthesize and save a default one -- {@code frens/} must never appear on disk.
     */
    @Test
    void setActiveFalseOnAFreshBotCreatesNoStateOnDisk() throws Exception {
        UUID bot = UUID.randomUUID();

        SoulTypes.SoulState result = store.setActive(bot, false).get(2, SECONDS);

        assertFalse(result.active());
        assertEquals("", result.profileId());
        assertFalse(Files.exists(worldRoot.resolve("frens")),
                "deactivating a never-persisted bot must not create frens/ on disk");
    }

    @Test
    void corruptTailIsQuarantinedAndValidRecordsSurvive() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);

        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "hi", Instant.EPOCH).get(2, SECONDS);

        Path active = activeFile(bot, player);
        // Simulate a crash mid-append: a truncated JSON fragment with no closing brace/newline.
        Files.writeString(active, "{\"correlationId\":\"broken-mid-write", StandardOpenOption.APPEND);

        List<SoulTypes.ConversationRecord> records = store.recent(key, 10, 10_000).get(2, SECONDS);

        assertEquals(1, records.size());
        assertEquals("hi", records.get(0).content());

        String remaining = Files.readString(active);
        assertFalse(remaining.contains("broken-mid-write"));

        boolean quarantined;
        try (var stream = Files.list(active.getParent())) {
            quarantined = stream.anyMatch(p -> p.getFileName().toString().startsWith("active.jsonl.corrupt-tail-"));
        }
        assertTrue(quarantined);
    }

    @Test
    void malformedRecordBeforeFinalLineFailsLoadVisibly() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "first", Instant.EPOCH).get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "second", Instant.EPOCH).get(2, SECONDS);

        Path active = activeFile(bot, player);
        List<String> lines = new ArrayList<>(Files.readAllLines(active));
        assertEquals(2, lines.size());
        lines.set(0, "{this is not valid json at all");
        Files.write(active, lines);

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> store.recent(key, 10, 10_000).get(2, SECONDS));
        assertTrue(ex.getCause() instanceof IOException);
    }

    @Test
    void restartRecoversPersistedConversationAndState() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "hello", Instant.EPOCH)
                .get(2, SECONDS);
        store.appendSpoken(token, "hi back", new SoulTypes.ProviderResult(
                true, "hi back", null, "ollama", "test-model", 5L, null, null, null)).get(2, SECONDS);
        store.close();

        ExecutorService executor2 = Executors.newSingleThreadExecutor();
        SoulStore restarted = new SoulStore(worldRoot, executor2);
        try {
            List<SoulTypes.ConversationRecord> records = restarted.recent(key, 10, 10_000).get(2, SECONDS);
            assertEquals(2, records.size());
            assertEquals("hello", records.get(0).content());
            assertEquals("hi back", records.get(1).content());

            SoulTypes.SoulState state = restarted.state(bot).get(2, SECONDS);
            assertEquals("frens:jake", state.profileId());
        } finally {
            restarted.close();
        }
    }

    @Test
    void usesExactWorldLocalPersistencePaths() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "hello", Instant.EPOCH).get(2, SECONDS);

        Path root = worldRoot.resolve("frens").resolve("souls").resolve("v1");
        assertTrue(Files.exists(root.resolve(bot.toString()).resolve("soul.json")));
        assertTrue(Files.exists(activeFile(bot, player)));
    }

    @Test
    void recentIsBoundedByMaxTurns() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        for (int i = 0; i < 5; i++) {
            store.beginHeardTurn(key, UUID.randomUUID(), "msg-" + i, Instant.EPOCH).get(2, SECONDS);
        }

        List<SoulTypes.ConversationRecord> records = store.recent(key, 2, 10_000).get(2, SECONDS);

        assertEquals(2, records.size());
        assertEquals("msg-3", records.get(0).content());
        assertEquals("msg-4", records.get(1).content());
    }

    @Test
    void recentIsBoundedByMaxChars() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "aaaaa", Instant.EPOCH).get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "bbbbb", Instant.EPOCH).get(2, SECONDS);

        List<SoulTypes.ConversationRecord> records = store.recent(key, 10, 5).get(2, SECONDS);

        assertEquals(1, records.size());
        assertEquals("bbbbb", records.get(0).content());
    }

    @Test
    void recentBeforeExcludesTurnAtOrAfterToken() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "first", Instant.EPOCH).get(2, SECONDS);
        SoulTypes.TurnToken second = store.beginHeardTurn(key, UUID.randomUUID(), "second", Instant.EPOCH)
                .get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "third", Instant.EPOCH).get(2, SECONDS);

        List<SoulTypes.ConversationRecord> before = store.recentBefore(second, 10, 10_000).get(2, SECONDS);

        assertEquals(1, before.size());
        assertEquals("first", before.get(0).content());
    }

    @Test
    void appendFailureRecordsFailureCodeAndProvider() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "hello", Instant.EPOCH)
                .get(2, SECONDS);
        store.appendFailure(token, SoulTypes.FailureCode.TIMEOUT, "ollama", "test-model", 250L)
                .get(2, SECONDS);

        List<SoulTypes.ConversationRecord> records = store.recent(key, 10, 10_000).get(2, SECONDS);

        assertEquals(2, records.size());
        SoulTypes.ConversationRecord failure = records.get(1);
        assertEquals(SoulTypes.TurnKind.FAILURE, failure.kind());
        assertEquals(SoulTypes.FailureCode.TIMEOUT, failure.failureCode());
        assertEquals("ollama", failure.provider());
    }

    @Test
    void bindProfileAndSetActivePersistState() throws Exception {
        UUID bot = UUID.randomUUID();
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.setActive(bot, true).get(2, SECONDS);

        assertTrue(store.isActive(bot).get(2, SECONDS));
        SoulTypes.SoulState state = store.state(bot).get(2, SECONDS);
        assertEquals("frens:jake", state.profileId());
        assertTrue(state.active());
    }

    @Test
    void unknownBotReturnsInactiveDefaultState() throws Exception {
        UUID bot = UUID.randomUUID();
        SoulTypes.SoulState state = store.state(bot).get(2, SECONDS);

        assertFalse(state.active());
        assertEquals("", state.profileId());
        assertTrue(state.conversations().isEmpty());
    }

    @Test
    void appendEventAndRecentEventsRoundTrip() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SoulTypes.SoulEvent event = new SoulTypes.SoulEvent(
                UUID.randomUUID(), SoulTypes.EventType.COMBAT_STARTED, actor,
                List.of(actor), "overworld", "plains", Map.of("target", "zombie"),
                SoulTypes.Witness.SELF, 100L, Instant.EPOCH, SoulTypes.Salience.NORMAL);

        store.appendEvent(bot, event).get(2, SECONDS);
        List<SoulTypes.SoulEvent> events = store.recentEvents(bot, 10).get(2, SECONDS);

        assertEquals(1, events.size());
        assertEquals(SoulTypes.EventType.COMBAT_STARTED, events.get(0).type());
        assertEquals("zombie", events.get(0).facts().get("target"));
    }

    @Test
    void closeShutsDownInjectedExecutorLeavingNoThreadAlive() throws Exception {
        String threadName = "soul-store-thread-leak-check";
        ExecutorService taggedExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        SoulStore taggedStore = new SoulStore(worldRoot, taggedExecutor);
        taggedStore.state(UUID.randomUUID()).get(2, SECONDS);
        taggedStore.close();

        boolean stillAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> threadName.equals(t.getName()) && t.isAlive());
        assertFalse(stillAlive, "executor thread should not remain alive after close()");
    }

    // === Crash-window hardening ===

    @Test
    void beginHeardTurnReconcilesSequenceAheadOfPersistedCursor() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "first", Instant.EPOCH).get(2, SECONDS);
        // soul.json cursor is now (epoch=0, nextSequence=1).

        // Simulate a real crash-then-restart: close this store (dropping its in-memory sequence
        // cache, same as a process death would) before hand-writing the "lost" second record
        // directly into active.jsonl. A second record (sequence=1) lands on disk, but soul.json
        // is never advanced past sequence=1 -- exactly the state a process death right after the
        // append, before the cursor write, would leave behind. Reopening a fresh SoulStore against
        // the same worldRoot (rather than reusing `store`, whose warm cache would otherwise trust
        // its own in-memory high-water mark over this out-of-band write) is what makes the restart
        // realistic: only a genuinely fresh store's first touch of this conversation is required to
        // re-scan the transcript.
        store.close();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SoulTypes.ConversationRecord crashedRecord = new SoulTypes.ConversationRecord(
                UUID.randomUUID(), 0L, 1L, SoulTypes.TurnKind.HEARD, "second-crashed",
                Instant.EPOCH, "", "", null, null);
        Files.writeString(activeFile(bot, player),
                mapper.writeValueAsString(crashedRecord) + System.lineSeparator(),
                StandardOpenOption.APPEND);
        store = new SoulStore(worldRoot, Executors.newSingleThreadExecutor());

        SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "third", Instant.EPOCH)
                .get(2, SECONDS);

        assertEquals(2L, token.sequence(), "next sequence must skip the one already on disk");

        List<SoulTypes.ConversationRecord> records = store.recent(key, 10, 10_000).get(2, SECONDS);
        List<Long> sequences = records.stream().map(SoulTypes.ConversationRecord::sequence).toList();
        assertEquals(List.of(0L, 1L, 2L), sequences, "no duplicate sequence numbers on disk");
    }

    @Test
    void reconcilesInterruptedResetFromArchiveArtifact() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        SoulTypes.TurnToken staleToken = store.beginHeardTurn(key, UUID.randomUUID(), "hello", Instant.EPOCH)
                .get(2, SECONDS);

        // Simulate a crash between archiveAndReset's file move and its cursor persist: move
        // active.jsonl into archive/ by hand (as the real move would), but leave soul.json's
        // cursor at epoch=0 — exactly the state a crash right after the move would leave behind.
        Path active = activeFile(bot, player);
        Path archiveDir = active.getParent().resolve("archive");
        Files.createDirectories(archiveDir);
        Files.move(active, archiveDir.resolve("epoch-0-20260101T000000000.jsonl"));

        SoulTypes.TurnToken freshToken = store.beginHeardTurn(key, UUID.randomUUID(), "after reset", Instant.EPOCH)
                .get(2, SECONDS);

        assertEquals(1L, freshToken.epoch(), "interrupted reset must resolve to the next epoch");

        assertThrows(ExecutionException.class, () -> store.appendSpoken(
                staleToken, "should be rejected", new SoulTypes.ProviderResult(
                        true, "should be rejected", null, "ollama", "test-model",
                        10L, null, null, null)).get(2, SECONDS));
    }

    // === Sequence-cache (O(1) reconciliation) ===

    /**
     * Functional check of the cached (warm) path: many sequential turns on the same store
     * instance, mixing heard/spoken/failure appends, must still produce strictly increasing,
     * duplicate-free sequence numbers -- exactly what the pre-caching full-file-scan guaranteed,
     * now backed by the in-memory high-water mark instead of a re-scan on every turn.
     */
    @Test
    void manySequentialTurnsOnSameStoreProduceStrictlyIncreasingSequencesWithNoDuplicates() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);

        for (int i = 0; i < 25; i++) {
            SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "heard-" + i, Instant.EPOCH)
                    .get(2, SECONDS);
            assertEquals(i * 2L, token.sequence(), "heard turn " + i + " landed at the wrong sequence");
            if (i % 2 == 0) {
                store.appendSpoken(token, "spoken-" + i, new SoulTypes.ProviderResult(
                        true, "spoken-" + i, null, "ollama", "test-model", 5L, null, null, null))
                        .get(2, SECONDS);
            } else {
                store.appendFailure(token, SoulTypes.FailureCode.TIMEOUT, "ollama", "test-model", 5L)
                        .get(2, SECONDS);
            }
        }

        List<SoulTypes.ConversationRecord> records = store.recent(key, 100, 1_000_000).get(2, SECONDS);
        List<Long> sequences = records.stream().map(SoulTypes.ConversationRecord::sequence).toList();

        assertEquals(50, sequences.size());
        List<Long> expected = new ArrayList<>();
        for (long i = 0; i < 50; i++) {
            expected.add(i);
        }
        assertEquals(expected, sequences, "sequences must be strictly increasing with no duplicates");
    }

    /**
     * Seed-scan check: a record hand-appended straight to {@code active.jsonl} before this
     * conversation has ever been touched by the store (no {@code bindProfile}, no prior turn --
     * so nothing is cached yet) must still be picked up on the very first {@code beginHeardTurn}
     * call. This is the cold path the sequence cache falls back to on first touch.
     */
    @Test
    void beginHeardTurnOnFreshStoreSeedsFromHandAppendedRecordBeyondCursor() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);

        Path active = activeFile(bot, player);
        Files.createDirectories(active.getParent());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SoulTypes.ConversationRecord preExisting = new SoulTypes.ConversationRecord(
                UUID.randomUUID(), 0L, 5L, SoulTypes.TurnKind.HEARD, "pre-existing",
                Instant.EPOCH, "", "", null, null);
        Files.writeString(active, mapper.writeValueAsString(preExisting) + System.lineSeparator());

        SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "first-touch", Instant.EPOCH)
                .get(2, SECONDS);

        assertEquals(6L, token.sequence(), "first touch must scan and skip the pre-existing sequence");

        List<SoulTypes.ConversationRecord> records = store.recent(key, 10, 10_000).get(2, SECONDS);
        List<Long> sequences = records.stream().map(SoulTypes.ConversationRecord::sequence).toList();
        assertEquals(List.of(5L, 6L), sequences, "no duplicate sequence numbers on disk");
    }

    /**
     * Cache-invalidation check: after {@code archiveAndReset} bumps the epoch, the next turn must
     * start back at sequence 0 in the new epoch, not continue from whatever the pre-reset epoch's
     * cached high-water mark held.
     */
    @Test
    void archiveAndResetThenNewTurnRestartsSequenceAtZeroInNewEpoch() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "before-reset", Instant.EPOCH).get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "before-reset-2", Instant.EPOCH).get(2, SECONDS);

        long newEpoch = store.archiveAndReset(key).get(2, SECONDS);
        assertEquals(1L, newEpoch);

        SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "after-reset", Instant.EPOCH)
                .get(2, SECONDS);

        assertEquals(1L, token.epoch());
        assertEquals(0L, token.sequence(), "sequence must restart at 0 in the new epoch");
    }

    /**
     * Regression test for the review finding that {@code recordWrittenSequence} originally ran
     * AFTER {@code persistCursor}: if {@code appendRecord} succeeds but {@code persistCursor}
     * then throws (an ordinary same-process I/O failure -- the executor survives and keeps
     * serving later calls; this is not a crash/restart), the old ordering left neither the
     * persisted cursor nor the in-memory cache advanced even though the JSONL record was already
     * durably on disk, so the very next ordinary turn would recompute and write that same
     * sequence a second time.
     *
     * <p>Induces a real, same-process {@code persistCursor} failure by making the bot's
     * {@code soul.json} directory read-only: {@code loadState}'s read of the existing file still
     * succeeds (removing write permission from a directory doesn't block reading a file already
     * in it), and {@code appendRecord} still succeeds too (it writes into a sibling
     * {@code conversations/<player>/} subdirectory whose own permissions are untouched) -- but
     * {@code persistCursor}'s {@code saveState} must create a brand-new {@code soul.json.tmp-*}
     * file directly inside the now-read-only directory, which fails.
     */
    @Test
    void persistCursorFailureAfterSuccessfulAppendDoesNotProduceDuplicateSequenceOnNextTurn() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        store.bindProfile(bot, "frens:jake").get(2, SECONDS);
        store.beginHeardTurn(key, UUID.randomUUID(), "first", Instant.EPOCH).get(2, SECONDS);
        // soul.json cursor is now (epoch=0, nextSequence=1); the in-memory cache holds
        // (epoch=0, maxSequence=0).

        Path botDir = botDir(bot);
        boolean madeReadOnly = botDir.toFile().setWritable(false);
        assertTrue(madeReadOnly, "test setup: must be able to make the bot directory read-only");
        try {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> store.beginHeardTurn(key, UUID.randomUUID(), "second", Instant.EPOCH).get(2, SECONDS),
                    "persistCursor must actually fail for this test to exercise the bug it guards against");
            assertTrue(ex.getCause() instanceof IOException,
                    "the induced failure must be persistCursor's IOException, not some other error");
        } finally {
            assertTrue(botDir.toFile().setWritable(true), "test cleanup: must restore write permission");
        }

        // Despite the failed call above, its JSONL append already landed on disk at sequence=1.
        List<SoulTypes.ConversationRecord> afterFailure = store.recent(key, 10, 10_000).get(2, SECONDS);
        assertEquals(List.of(0L, 1L), afterFailure.stream().map(SoulTypes.ConversationRecord::sequence).toList(),
                "the failed call's own append must still have landed on disk");

        SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "third", Instant.EPOCH)
                .get(2, SECONDS);

        assertEquals(2L, token.sequence(),
                "the cache must have advanced past the record that landed during the failed call");

        List<SoulTypes.ConversationRecord> records = store.recent(key, 10, 10_000).get(2, SECONDS);
        List<Long> sequences = records.stream().map(SoulTypes.ConversationRecord::sequence).toList();
        assertEquals(List.of(0L, 1L, 2L), sequences, "no duplicate sequence numbers on disk");
    }
}
