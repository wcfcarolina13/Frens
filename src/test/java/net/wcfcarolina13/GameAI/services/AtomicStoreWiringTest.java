package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every store listed in the 2026-11-05 durability audit saves through {@link AtomicStoreWriter},
 * tolerates a corrupt file on load (catches {@code RuntimeException}, quarantines it) and refuses
 * to save over a file it could not read.
 */
class AtomicStoreWiringTest {
    private static final String SERVICES = "src/main/java/net/wcfcarolina13/GameAI/services/";

    /** path, save-gate text (the loadFailed check) */
    private static final List<String[]> JSON_STORES = List.of(
            new String[]{"src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java", "if (loadFailed) {"},
            new String[]{SERVICES + "BotHomeService.java", "if (loadFailed) {"},
            new String[]{SERVICES + "BotChestRegistryService.java", "if (loadFailed) {"},
            new String[]{SERVICES + "ProtectedZoneService.java", "if (LOAD_FAILED_WORLDS.contains(worldId)) {"},
            new String[]{SERVICES + "LockableBlockService.java", "if (LOAD_FAILED_WORLDS.contains(worldId)) {"},
            new String[]{SERVICES + "MountPersistenceService.java", "if (loadFailed) {"},
            new String[]{SERVICES + "NavigationArtifactService.java", "if (travelsLoadFailed) {"},
            new String[]{SERVICES + "FishingSessionService.java", "if (loadFailed) {"},
            new String[]{SERVICES + "HuntSessionService.java", "if (loadFailed) {"});

    @Test
    void everyJsonStoreSavesThroughTheAtomicWriter() throws IOException {
        for (String[] store : JSON_STORES) {
            String src = flatten(Path.of(store[0]));
            assertTrue(src.contains("AtomicStoreWriter.writeString("), store[0]);
            assertFalse(src.contains("Files.newBufferedWriter("), store[0] + " still writes in place");
            assertFalse(src.contains("Files.writeString("), store[0] + " still writes in place");
            assertFalse(src.contains("new FileWriter("), store[0] + " still writes in place");
        }
    }

    @Test
    void everyJsonStoreQuarantinesACorruptFileAndGatesSavesOnLoadFailure() throws IOException {
        for (String[] store : JSON_STORES) {
            String src = flatten(Path.of(store[0]));
            assertTrue(src.contains("catch (IOException | RuntimeException e)"), store[0] + " must catch parse errors on load");
            assertTrue(src.contains("UnreadableStoreFile.quarantineIfCorrupt("), store[0] + " must quarantine the bad file");
            assertTrue(src.contains(store[1]), store[0] + " must refuse saves after an unrecoverable load");
            assertFalse(src.contains("loadFailed = true;"), store[0] + " must not drop every write after a quarantined parse failure");
        }
    }

    @Test
    void manualConfigNeverThrowsOutOfStaticInitAndTreatsAnEmptyFileAsCorrupt() throws IOException {
        String src = flatten(Path.of("src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java"));
        String load = block(src, "public static ManualConfig load()");
        assertTrue(load.contains("if (loadedConfig == null) {"));
        assertTrue(load.contains("throw new IllegalStateException(\"settings file is empty\")"));
        assertTrue(load.contains("catch (IOException | RuntimeException e)"));
        assertTrue(load.contains("LOGGER.error("));
        assertTrue(load.contains("loadFailed = backup == null;"));
        // Frens.CONFIG is a static final initialised by load(); the fallback is returned, never thrown.
        assertTrue(load.contains("return fallback;"));
        String frens = flatten(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        assertTrue(frens.contains("public static final ManualConfig CONFIG = ManualConfig.load();"));
    }

    @Test
    void serverStartLoadsCannotAbortStartupOnAParseError() throws IOException {
        // These three run inside the SERVER_STARTED lambda with no try/catch around them.
        String frens = flatten(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        assertTrue(frens.contains("ProtectedZoneService.loadZones(server, worldId);"));
        assertTrue(frens.contains("LockableBlockService.loadForWorld(server, worldId);"));
        assertTrue(frens.contains("NavigationArtifactService.loadPendingTravels(server);"));
        assertTrue(block(flatten(Path.of(SERVICES + "ProtectedZoneService.java")), "public static void loadZones(MinecraftServer server, String worldId)")
                .contains("catch (IOException | RuntimeException e)"));
        assertTrue(block(flatten(Path.of(SERVICES + "LockableBlockService.java")), "public static void loadForWorld(MinecraftServer server, String worldId)")
                .contains("catch (IOException | RuntimeException e)"));
        assertTrue(block(flatten(Path.of(SERVICES + "NavigationArtifactService.java")), "public static void loadPendingTravels(MinecraftServer server)")
                .contains("catch (IOException | RuntimeException e)"));
    }

    @Test
    void sessionStoresSerializeUnderTheirLock() throws IOException {
        for (String name : List.of("FishingSessionService.java", "HuntSessionService.java")) {
            String flush = block(flatten(Path.of(SERVICES + name)), "private static void flush()");
            assertTrue(flush.startsWith(" synchronized (LOCK) {"), name + " flush must serialize DATA under LOCK");
            assertTrue(flush.contains("GSON.toJson(DATA)"), name);
        }
    }

    @Test
    void mountStateIsLoadedAndWrittenUnderALockWithConcurrentMaps() throws IOException {
        String src = flatten(Path.of(SERVICES + "MountPersistenceService.java"));
        assertTrue(src.contains("private static final Object LOCK = new Object();"));
        assertTrue(src.contains("private static volatile boolean loaded = false;"));
        assertTrue(src.contains("STATE = new java.util.concurrent.ConcurrentHashMap<>();"));
        assertFalse(src.contains("STATE.computeIfAbsent(alias, k -> new HashMap<>())"));
        assertTrue(block(src, "private static void ensureLoaded()").contains("synchronized (LOCK) {"));
        assertTrue(block(src, "private static void flush()").contains("synchronized (LOCK) {"));
        // A half-parsed file is never kept and then written back.
        String load = block(src, "private static void loadLocked()");
        assertTrue(load.indexOf("STATE.clear();") < load.indexOf("UnreadableStoreFile.quarantineIfCorrupt("));
    }

    @Test
    void inventorySnapshotIsWrittenToATempFileBeforeTheBackupIsTakenAndTheUnreadableGuardHolds() throws IOException {
        String src = flatten(Path.of(SERVICES + "BotInventoryStorageService.java"));
        assertTrue(src.contains("AtomicStoreWriter.writeWithBackup(path, backup,"));
        assertTrue(src.contains("out -> net.minecraft.nbt.NbtIo.writeCompressed(root, out)"));
        assertFalse(src.contains("NbtIo.writeCompressed(root, path)"), "primary must not be written in place");
        assertFalse(src.contains("Files.copy(path, backup"), "the .bak copy belongs to the writer, after the temp file is durable");
        assertTrue(src.contains("snapshotItemCount(path) != 0"), "an unreadable snapshot (-1) must keep the empty-save guard");
        assertTrue(block(src, "private static int snapshotItemCount(Path path)").contains("return -1;"));
    }

    private static String flatten(Path path) throws IOException {
        return Files.readString(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", " ");
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }
}
