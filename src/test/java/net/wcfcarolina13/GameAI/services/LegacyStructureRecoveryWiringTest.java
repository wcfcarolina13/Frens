package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyStructureRecoveryWiringTest {
    private static final Path ROOT = Path.of("src/main/java/net/wcfcarolina13/GameAI/services");

    private static String source(String file) throws Exception {
        return Files.readString(ROOT.resolve(file));
    }

    private static String importBody(String source) {
        int start = source.indexOf("public static LegacyRegistryRecoveryPolicy.Result importLegacy");
        int end = source.indexOf("private static Path stateFile", start);
        return source.substring(start, end);
    }

    @Test
    void fortificationsCopyBeforeRekeyAndNeverNormalizeLegacyOnLoadOrImport() throws Exception {
        String source = source("construction/FortificationPersistenceService.java");
        String body = importBody(source);
        assertFalse(source.contains("normalizeLoadedData"));
        assertTrue(source.contains("WorldStoreKeyPolicy.fingerprintKey(BotWorldStateService.currentWorldKey(server), dimension)"));
        assertTrue(body.contains("WorldData copied = GSON.fromJson(GSON.toJson(legacy), WorldData.class)"));
        assertTrue(body.contains("for (SavedFortification record : copied.fortifications.values())"));
        assertTrue(body.contains("record.worldKey = targetKey"));
        assertTrue(body.contains("legacy -> copyFor(legacy, targetKey, dimensionId(world))"));
        assertFalse(body.contains("normalizeFortification"));
        assertTrue(body.contains("LegacyRegistryRecoveryPolicy.rollback(DATA.worlds, targetKey, copy.imported())"));
    }

    @Test
    void villagesCopyBeforeRewritingBothEmbeddedWorldFields() throws Exception {
        String source = source("MappedVillageService.java");
        String body = importBody(source);
        assertTrue(source.contains("FortificationPersistenceService.serverWorldKey(server, world)"));
        assertTrue(body.contains("WorldData copied = GSON.fromJson(GSON.toJson(legacy), WorldData.class)"));
        assertTrue(body.contains("for (MappedVillage record : copied.villages.values())"));
        assertTrue(body.contains("record.worldKey = targetKey"));
        assertTrue(body.contains("record.dimension = dimension"));
        assertTrue(body.contains("legacy -> copyFor(legacy, targetKey, dimensionId(world))"));
        assertTrue(body.contains("LegacyRegistryRecoveryPolicy.rollback(DATA.worlds, targetKey, copy.imported())"));
    }

    @Test
    void adoptionAndSiblingImportShareTheRekeyingCopier() throws Exception {
        for (String file : new String[]{"construction/FortificationPersistenceService.java", "MappedVillageService.java"}) {
            String body = importBody(source(file));
            assertTrue(body.contains("private static WorldData copyFor(WorldData legacy, String targetKey, String dimension)"), file);
            assertTrue(body.contains("::copyFor"), file + " adoption must rekey through copyFor");
            assertTrue(body.contains("source -> copyFor(source, targetKey, dimension)"), file + " sibling import must rekey");
        }
    }
}
