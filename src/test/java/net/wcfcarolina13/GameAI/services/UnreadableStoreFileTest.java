package net.wcfcarolina13.GameAI.services;

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
}
