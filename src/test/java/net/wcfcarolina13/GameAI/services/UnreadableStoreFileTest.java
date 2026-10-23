package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** I3: an unreadable store file is moved aside, never overwritten, so the store can save again. */
class UnreadableStoreFileTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 9, 5, 7);

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void clearSessionProblems() {
        UnreadableStoreFile.clearProblems();
    }

    @Test
    void movesTheFileToATimestampedBackupWithItsContent() throws Exception {
        Path file = dir.resolve("hunt_config.json");
        Files.writeString(file, "{ broken");
        Path backup = UnreadableStoreFile.quarantine(file, NOW, null, "hunt config");
        assertEquals(dir.resolve("hunt_config.json.bad-20261022-090507"), backup);
        assertFalse(Files.exists(file));
        assertEquals("{ broken", Files.readString(backup));
    }

    @Test
    void takenBackupNamesAreNeverOverwritten() throws Exception {
        Path file = dir.resolve("store.json");
        Files.writeString(file, "new bad");
        Files.writeString(dir.resolve("store.json.bad-20261022-090507"), "older");
        Files.writeString(dir.resolve("store.json.bad-20261022-090507-1"), "older 1");
        Path backup = UnreadableStoreFile.quarantine(file, NOW, null, "store");
        assertEquals(dir.resolve("store.json.bad-20261022-090507-2"), backup);
        assertEquals("older", Files.readString(dir.resolve("store.json.bad-20261022-090507")));
        assertEquals("older 1", Files.readString(dir.resolve("store.json.bad-20261022-090507-1")));
        assertEquals("new bad", Files.readString(backup));
    }

    @Test
    void failedMoveReturnsNullSoTheCallerKeepsRefusingToSave() throws Exception {
        assertNull(UnreadableStoreFile.quarantine(dir.resolve("missing.json"), NOW, null, "store"));
        assertNull(UnreadableStoreFile.quarantine(null, NOW, null, "store"));
        Path file = dir.resolve("kept.json");
        Files.writeString(file, "bad");
        assertNull(UnreadableStoreFile.quarantine(file, null, null, "store"));
        assertEquals("bad", Files.readString(file));
    }

    @Test
    void everyBackupNameTakenReturnsNullAndKeepsTheOriginal() throws Exception {
        Path file = dir.resolve("full.json");
        Files.writeString(file, "bad");
        Files.writeString(dir.resolve("full.json.bad-20261022-090507"), "x");
        for (int i = 1; i <= UnreadableStoreFile.MAX_SUFFIX; i++) {
            Files.writeString(dir.resolve("full.json.bad-20261022-090507-" + i), "x");
        }
        assertNull(UnreadableStoreFile.quarantine(file, NOW, null, "store"));
        assertEquals("bad", Files.readString(file));
    }

    @Test
    void aReadErrorLeavesAPossiblyValidFileInPlace() throws Exception {
        Path file = dir.resolve("furnaces.json");
        Files.writeString(file, "{\"worlds\":{}}");
        assertNull(UnreadableStoreFile.quarantineIfCorrupt(file, new java.io.IOException("locked"), NOW, null, "furnaces"));
        assertNull(UnreadableStoreFile.quarantineIfCorrupt(file,
                new com.google.gson.JsonIOException(new java.io.IOException("blip")), NOW, null, "furnaces"));
        assertEquals("{\"worlds\":{}}", Files.readString(file));
    }

    @Test
    void realGsonParseFailuresAreQuarantined() throws Exception {
        Path file = dir.resolve("villages.json");
        Files.writeString(file, "{ \"worlds\": ");
        Exception failure = assertThrows(Exception.class, () -> {
            try (java.io.Reader reader = Files.newBufferedReader(file)) {
                new com.google.gson.Gson().fromJson(reader, java.util.Map.class);
            }
        });
        Path backup = UnreadableStoreFile.quarantineIfCorrupt(file, failure, NOW, null, "villages");
        assertNotNull(backup, "truncated JSON is corrupt content: " + failure);
        assertFalse(Files.exists(file));
        assertTrue(UnreadableStoreFile.isCorruptContent(new com.google.gson.JsonSyntaxException("bad")));
        assertTrue(UnreadableStoreFile.isCorruptContent(new IllegalStateException("Expected BEGIN_OBJECT")));
    }
    @Test
    void aQuarantineIsRecordedWithTheBackupFileNameOnly() throws Exception {
        Path file = dir.resolve("bot_furnace_registry.json");
        Files.writeString(file, "{ broken");
        UnreadableStoreFile.quarantine(file, NOW, null, "furnace registry");
        assertEquals(java.util.List.of(new UnreadableStoreFile.Problem("furnace registry",
                "bot_furnace_registry.json.bad-20261022-090507")), UnreadableStoreFile.problems());
        assertEquals(java.util.List.of("Frens could not read the furnace registry file. It was moved to "
                        + "bot_furnace_registry.json.bad-20261022-090507 and starts empty this session."),
                UnreadableStoreFile.adminNotices());
    }

    @Test
    void aReadErrorIsRecordedAsLeftInPlaceOnce() throws Exception {
        Path file = dir.resolve("hunt_config.json");
        Files.writeString(file, "{}");
        UnreadableStoreFile.quarantineIfCorrupt(file, new java.io.IOException("locked"), NOW, null, "hunt config");
        UnreadableStoreFile.quarantineIfCorrupt(file, new java.io.IOException("locked"), NOW, null, "hunt config");
        assertEquals(java.util.List.of("Frens could not read the hunt config file. It was left in place and "
                + "changes will not be saved this session."), UnreadableStoreFile.adminNotices());
    }

    @Test
    void aFailedMoveIsRecordedAsLeftInPlace() {
        UnreadableStoreFile.quarantine(dir.resolve("missing.json"), NOW, null, "mapped villages");
        assertEquals(java.util.List.of(new UnreadableStoreFile.Problem("mapped villages", null)),
                UnreadableStoreFile.problems());
    }

    @Test
    void nothingIsRecordedWithoutAProblem() {
        UnreadableStoreFile.quarantine(null, NOW, null, "store");
        assertTrue(UnreadableStoreFile.adminNotices().isEmpty());
    }

    @Test
    void noticeTextIsPlainAndToleratesMissingLabels() {
        var lines = UnreadableStoreFile.formatNotices(java.util.Arrays.asList(
                new UnreadableStoreFile.Problem("beehive registry", "bot_beehive_registry.json.bad-20261023-101500"),
                null,
                new UnreadableStoreFile.Problem(null, null)));
        assertEquals(java.util.List.of(
                "Frens could not read the beehive registry file. It was moved to bot_beehive_registry.json.bad-20261023-101500 and starts empty this session.",
                "Frens could not read the store file. It was left in place and changes will not be saved this session."), lines);
        for (String line : lines) {
            assertFalse(line.contains("\u2014") || line.contains(";"), line);
        }
        assertTrue(UnreadableStoreFile.formatNotices(null).isEmpty());
    }
}
