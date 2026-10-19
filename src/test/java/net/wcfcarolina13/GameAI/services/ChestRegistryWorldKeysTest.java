package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChestRegistryWorldKeysTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void keyUsesSaveFingerprintAndDimension() {
        assertEquals("New World#1a2b3c:minecraft:overworld",
                ChestRegistryWorldKeys.key("New World#1a2b3c", OVERWORLD));
        assertEquals(WorldStoreKeyPolicy.fingerprintKey("New World#1a2b3c", OVERWORLD),
                ChestRegistryWorldKeys.key("New World#1a2b3c", OVERWORLD));
    }

    @Test
    void twoSavesWithTheSameNameGetDifferentKeys() {
        String a = ChestRegistryWorldKeys.key(WorldStoreKeyPolicy.fingerprint("New World", "aaaa"), OVERWORLD);
        String b = ChestRegistryWorldKeys.key(WorldStoreKeyPolicy.fingerprint("New World", "bbbb"), OVERWORLD);
        assertNotEquals(a, b);
    }

    @Test
    void legacyKeyMatchesWhatOlderBuildsWrote() {
        assertEquals("New World/minecraft:overworld", ChestRegistryWorldKeys.legacyKey("New World", OVERWORLD));
        // Untrimmed: must match the raw level name older builds used.
        assertEquals(" New World /minecraft:the_nether",
                ChestRegistryWorldKeys.legacyKey(" New World ", "minecraft:the_nether"));
        assertEquals("unknown/unknown", ChestRegistryWorldKeys.legacyKey(null, null));
    }

    @Test
    void currentKeyNeverCollidesWithALegacyKey() {
        String fingerprint = WorldStoreKeyPolicy.fingerprint("New World", "aaaa");
        assertNotEquals(ChestRegistryWorldKeys.legacyKey("New World", OVERWORLD),
                ChestRegistryWorldKeys.key(fingerprint, OVERWORLD));
    }

    @Test
    void secondSameNamedSaveStartsEmptyAfterTheFirstAdopted() {
        String first = WorldStoreKeyPolicy.fingerprint("New World", "aaaa");
        String second = WorldStoreKeyPolicy.fingerprint("New World", "bbbb");
        long oldWorld = WorldStoreKeyPolicy.BRAND_NEW_WORLD_MAX_TICKS + 1;
        assertEquals(WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY,
                WorldStoreKeyPolicy.decide(false, true, oldWorld, null, first));
        assertEquals(WorldStoreKeyPolicy.Adoption.START_EMPTY_CLAIMED,
                WorldStoreKeyPolicy.decide(false, true, oldWorld, first, second));
        assertEquals(WorldStoreKeyPolicy.Adoption.START_EMPTY_NEW_WORLD,
                WorldStoreKeyPolicy.decide(false, true, 0L, null, second));
    }
}
