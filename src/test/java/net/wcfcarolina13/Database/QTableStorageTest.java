package net.wcfcarolina13.Database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QTableStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void corruptQTableIsQuarantinedAndFreshTableReturned() throws IOException {
        Path storageDir = Files.createDirectories(tempDir.resolve("qtable_storage"));
        Path qtable = storageDir.resolve("qtable.bin");
        Files.writeString(qtable, "not-a-java-serialized-object");

        QTable loaded = QTableStorage.loadQTableFromDirectories(new String[]{storageDir.toString()});

        assertNotNull(loaded);
        assertTrue(loaded.getTable().isEmpty());
        assertFalse(Files.exists(qtable));
        assertEquals(1L, countQuarantinedFiles(storageDir));
    }

    @Test
    void quarantinedQTableIsNotRetriedOnNextLoad() throws IOException {
        Path storageDir = Files.createDirectories(tempDir.resolve("qtable_storage"));
        Path qtable = storageDir.resolve("qtable.bin");
        Files.writeString(qtable, "corrupt");

        QTableStorage.loadQTableFromDirectories(new String[]{storageDir.toString()});
        long quarantinedAfterFirstLoad = countQuarantinedFiles(storageDir);

        QTable loadedAgain = QTableStorage.loadQTableFromDirectories(new String[]{storageDir.toString()});

        assertNotNull(loadedAgain);
        assertTrue(loadedAgain.getTable().isEmpty());
        assertEquals(1L, quarantinedAfterFirstLoad);
        assertEquals(1L, countQuarantinedFiles(storageDir));
    }

    private long countQuarantinedFiles(Path storageDir) throws IOException {
        try (Stream<Path> stream = Files.list(storageDir)) {
            List<Path> quarantined = stream
                    .filter(path -> path.getFileName().toString().startsWith("qtable.bin.corrupt-"))
                    .toList();
            return quarantined.size();
        }
    }
}
