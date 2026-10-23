package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Source-text checks that the seven fingerprinted stores adopt at start and recover from unreadable files. */
class LegacyStoreAdoptionWiringTest {
    private static final Path MAIN = Path.of("src/main/java/net/wcfcarolina13");
    private static final String[] STORES = {
            "GameAI/services/BotFurnaceRegistryService.java",
            "GameAI/services/BotBeehiveRegistryService.java",
            "GameAI/services/CraftingHistoryService.java",
            "GameAI/services/HuntHistoryService.java",
            "GameAI/services/HuntConfigService.java",
            "GameAI/services/construction/FortificationPersistenceService.java",
            "GameAI/services/MappedVillageService.java",
    };

    private static String source(String file) throws Exception {
        return Files.readString(MAIN.resolve(file));
    }

    @Test
    void frensAdoptsAllSevenStoresRightAfterTheChestRegistry() throws Exception {
        String frens = source("Frens.java");
        int chest = frens.indexOf("BotChestRegistryService.adoptLegacyWorldData(server);");
        int spawnSeed = frens.indexOf("initializeSpawnBaseIfNeeded", chest);
        assertTrue(chest > 0 && spawnSeed > chest);
        for (String store : STORES) {
            String cls = store.substring(store.lastIndexOf('/') + 1, store.length() - ".java".length());
            int call = frens.indexOf(cls + ".adoptLegacyWorldData(server);", chest);
            assertTrue(call > chest && call < spawnSeed, cls + " must adopt right after the chest registry");
        }
    }

    @Test
    void everyStoreAdoptsThroughThePolicyAndQuarantinesUnreadableFiles() throws Exception {
        for (String store : STORES) {
            String src = source(store);
            assertTrue(src.contains("public static void adoptLegacyWorldData(MinecraftServer server)"), store);
            assertTrue(src.contains("LegacyRegistryRecoveryPolicy.adoptLegacy(DATA.worlds, DATA.legacyWorldClaims,"), store);
            assertTrue(src.contains("LegacyRegistryRecoveryPolicy.undo(adoption,"), store);
            assertTrue(src.contains("Map<String, String> legacyWorldClaims"), store);
            assertTrue(src.contains("java.util.Set<String> legacyDeclined"), store);
            assertTrue(src.contains("public static LegacyRegistryRecoveryPolicy.Result importFromSibling("), store);
            assertTrue(src.contains("UnreadableStoreFile.quarantine(file, "), store);
            assertTrue(src.contains("loadFailed = backup == null;"), store);
            assertFalse(src.contains("loadFailed = true;"), store + " must not drop every write after a parse failure");
        }
    }

    @Test
    void worldStateQuarantinesAndClearsThePartialParse() throws Exception {
        String src = source("GameAI/services/BotWorldStateService.java");
        int quarantine = src.indexOf("UnreadableStoreFile.quarantine(file, ");
        assertTrue(quarantine > 0);
        assertTrue(src.indexOf("STATE.clear();", quarantine) > quarantine);
        assertFalse(src.contains("loadFailed = true;"));
    }

    @Test
    void commandOffersSiblingImportBehindTheAdminGate() throws Exception {
        String src = source("Commands/LegacyWorldStateCommand.java");
        assertTrue(src.contains(".requires(Frens::isServerAdmin)"));
        assertTrue(src.contains("CommandManager.literal(\"from\")"));
        assertTrue(src.contains("case INVALID_SOURCE ->"));
        int registry = src.indexOf("private static int registry(");
        assertTrue(src.indexOf("if (!Frens.isServerAdmin(source)) return refuse(source);", registry) > registry);
    }
}
