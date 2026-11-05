package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Store files are replaced whole or not at all, and no temporary file is left behind either way. */
class AtomicStoreWriterTest {
    @TempDir
    Path dir;

    @Test
    void writesANewFileAndCreatesMissingParents() throws IOException {
        Path target = dir.resolve("frens").resolve("store.json");
        AtomicStoreWriter.writeString(target, "{\"a\":1}");
        assertEquals("{\"a\":1}", Files.readString(target));
        assertEquals(List.of("store.json"), names(target.getParent()));
    }

    @Test
    void replacesAnExistingFileWithTheNewContentOnly() throws IOException {
        Path target = dir.resolve("store.json");
        Files.writeString(target, "old content that is longer than the new one");
        AtomicStoreWriter.writeString(target, "new");
        assertEquals("new", Files.readString(target));
        assertEquals(List.of("store.json"), names(dir));
    }

    @Test
    void writeBytesAndBodyOverloadsProduceTheSameFile() throws IOException {
        Path a = dir.resolve("a.bin");
        Path b = dir.resolve("b.bin");
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        AtomicStoreWriter.writeBytes(a, bytes);
        AtomicStoreWriter.write(b, out -> out.write(bytes));
        assertArrayEquals(bytes, Files.readAllBytes(a));
        assertArrayEquals(bytes, Files.readAllBytes(b));
    }

    @Test
    void aBodyThatClosesItsStreamStillGetsAForcedCompleteFile() throws IOException {
        // NbtIo.writeCompressed closes the stream it is handed; the channel must survive that.
        Path target = dir.resolve("inv.nbt");
        AtomicStoreWriter.write(target, out -> {
            out.write(new byte[]{1, 2, 3});
            out.close();
        });
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target));
        assertEquals(List.of("inv.nbt"), names(dir));
    }

    @Test
    void aFailingBodyLeavesTheTargetUntouchedAndNoTemporaryFile() throws IOException {
        Path target = dir.resolve("store.json");
        Files.writeString(target, "good");
        IOException failure = assertThrows(IOException.class, () -> AtomicStoreWriter.write(target, out -> {
            out.write("half".getBytes(StandardCharsets.UTF_8));
            throw new IOException("disk full");
        }));
        assertEquals("disk full", failure.getMessage());
        assertEquals("good", Files.readString(target));
        assertEquals(List.of("store.json"), names(dir));
    }

    @Test
    void aTargetThatIsADirectoryFailsWithoutLeavingATemporaryFile() throws IOException {
        // The container runs as root, so an unwritable directory does not refuse writes; a
        // directory in the target's place is refused by every platform.
        Path target = dir.resolve("store.json");
        Files.createDirectories(target.resolve("child"));
        assertThrows(IOException.class, () -> AtomicStoreWriter.writeString(target, "x"));
        assertTrue(Files.isDirectory(target));
        assertTrue(Files.exists(target.resolve("child")));
        assertEquals(List.of("store.json"), names(dir));
    }

    @Test
    void aMissingParentThatCannotBeCreatedFails() throws IOException {
        Path file = dir.resolve("not-a-dir");
        Files.writeString(file, "");
        assertThrows(IOException.class, () -> AtomicStoreWriter.writeString(file.resolve("store.json"), "x"));
        assertEquals(List.of("not-a-dir"), names(dir));
    }

    @Test
    void backupIsTakenFromTheIntactPreviousFileOnlyWhenTheNewContentIsReady() throws IOException {
        Path target = dir.resolve("inv.nbt");
        Path backup = dir.resolve("inv.nbt.bak");
        Files.writeString(target, "v1");

        AtomicStoreWriter.writeWithBackup(target, backup, out -> out.write("v2".getBytes(StandardCharsets.UTF_8)), null);
        assertEquals("v2", Files.readString(target));
        assertEquals("v1", Files.readString(backup));

        // A failed write touches neither copy.
        assertThrows(IOException.class, () -> AtomicStoreWriter.writeWithBackup(target, backup, out -> {
            throw new IOException("boom");
        }, null));
        assertEquals("v2", Files.readString(target));
        assertEquals("v1", Files.readString(backup));
        assertEquals(List.of("inv.nbt", "inv.nbt.bak"), names(dir));
    }

    @Test
    void backupIsSkippedWhenThereIsNoPreviousFile() throws IOException {
        Path target = dir.resolve("inv.nbt");
        Path backup = dir.resolve("inv.nbt.bak");
        AtomicStoreWriter.writeWithBackup(target, backup, out -> out.write(1), null);
        assertTrue(Files.exists(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void backupFailureIsReportedAndTheReplacementStillHappens() throws IOException {
        Path target = dir.resolve("inv.nbt");
        Files.writeString(target, "v1");
        Path backup = dir.resolve("bak-dir");
        Files.createDirectories(backup.resolve("occupied"));
        IOException[] seen = new IOException[1];
        AtomicStoreWriter.writeWithBackup(target, backup, out -> out.write("v2".getBytes(StandardCharsets.UTF_8)), e -> seen[0] = e);
        assertNotNull(seen[0]);
        assertEquals("v2", Files.readString(target));
    }

    @Test
    void nullArgumentsAreRefusedAsIoFailures() throws IOException {
        assertThrows(IOException.class, () -> AtomicStoreWriter.write(null, out -> {}));
        assertThrows(IOException.class, () -> AtomicStoreWriter.write(dir.resolve("x"), null));
        assertEquals(List.of(), names(dir));
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
