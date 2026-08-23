package net.wcfcarolina13.GameAI.souls;

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
}
