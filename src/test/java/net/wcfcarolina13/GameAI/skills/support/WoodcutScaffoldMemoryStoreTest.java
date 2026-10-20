package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for per-bot woodcut scaffold memory (finding 52). No net.minecraft types. */
class WoodcutScaffoldMemoryStoreTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private final UUID alex = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void oneBotsPositionsAreInvisibleToAnother() {
        Map<String, Object> shared = new HashMap<>();
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 42L, 0L);
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 43L, 0L);
        assertTrue(WoodcutScaffoldMemoryStore.contains(shared, alex, OVERWORLD, 42L));
        assertFalse(WoodcutScaffoldMemoryStore.contains(shared, bob, OVERWORLD, 42L));
        assertNull(WoodcutScaffoldMemoryStore.get(shared, bob, OVERWORLD, false));
        Set<Long> bobs = WoodcutScaffoldMemoryStore.get(shared, bob, OVERWORLD, true);
        assertNotNull(bobs);
        assertTrue(bobs.isEmpty());
        assertEquals(Set.of(42L, 43L), WoodcutScaffoldMemoryStore.get(shared, alex, OVERWORLD, false));
    }

    @Test
    void clearingOneBotKeepsTheOthers() {
        Map<String, Object> shared = new HashMap<>();
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 1L, 0L);
        WoodcutScaffoldMemoryStore.record(shared, bob, OVERWORLD, 2L, 0L);
        WoodcutScaffoldMemoryStore.clearForBot(shared, alex);
        assertFalse(WoodcutScaffoldMemoryStore.contains(shared, alex, OVERWORLD, 1L));
        assertTrue(WoodcutScaffoldMemoryStore.contains(shared, bob, OVERWORLD, 2L));
    }

    @Test
    void forgetRemovesOnlyFromThatBot() {
        Map<String, Object> shared = new HashMap<>();
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 7L, 0L);
        WoodcutScaffoldMemoryStore.record(shared, bob, OVERWORLD, 7L, 0L);
        WoodcutScaffoldMemoryStore.forget(shared, bob, OVERWORLD, 7L);
        assertTrue(WoodcutScaffoldMemoryStore.contains(shared, alex, OVERWORLD, 7L));
        assertFalse(WoodcutScaffoldMemoryStore.contains(shared, bob, OVERWORLD, 7L));
    }

    @Test
    void successfulCleanupForgetsOnlyTheMinedPlacement() {
        Map<String, Object> shared = new HashMap<>();
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 7L, 0L);
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 8L, 0L);
        WoodcutScaffoldMemoryStore.record(shared, bob, OVERWORLD, 7L, 0L);
        Set<Long> memory = WoodcutScaffoldMemoryStore.get(shared, alex, OVERWORLD, false);

        assertTrue(WoodcutScaffoldMemoryStore.forgetAfterMine(memory, 7L, true));
        assertEquals(Set.of(8L), memory);
        assertFalse(WoodcutScaffoldMemoryStore.contains(shared, alex, OVERWORLD, 7L));
        assertTrue(WoodcutScaffoldMemoryStore.contains(shared, bob, OVERWORLD, 7L));
    }

    @Test
    void failedCleanupKeepsPlacementForRetryAndMissingMemoryIsSafe() {
        Set<Long> memory = new HashSet<>(Set.of(7L));
        assertFalse(WoodcutScaffoldMemoryStore.forgetAfterMine(memory, 7L, false));
        assertEquals(Set.of(7L), memory);
        assertTrue(WoodcutScaffoldMemoryStore.forgetAfterMine(null, 7L, true));
        assertFalse(WoodcutScaffoldMemoryStore.forgetAfterMine(null, 7L, false));
    }

    @Test
    void aDimensionChangeDropsThatBotsOldSet() {
        Map<String, Object> shared = new HashMap<>();
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 5L, 0L);
        WoodcutScaffoldMemoryStore.record(shared, bob, OVERWORLD, 6L, 0L);
        assertNull(WoodcutScaffoldMemoryStore.get(shared, alex, NETHER, false));
        assertFalse(WoodcutScaffoldMemoryStore.contains(shared, alex, OVERWORLD, 5L));
        assertTrue(WoodcutScaffoldMemoryStore.contains(shared, bob, OVERWORLD, 6L));
    }

    @Test
    void theLegacyGlobalSetIsDroppedNotInherited() {
        Map<String, Object> shared = new HashMap<>();
        shared.put(WoodcutScaffoldMemoryStore.LEGACY_POSITIONS_KEY, new HashSet<>(Set.of(9L)));
        shared.put(WoodcutScaffoldMemoryStore.LEGACY_DIMENSION_KEY, OVERWORLD);
        shared.put(WoodcutScaffoldMemoryStore.LEGACY_UPDATED_AT_KEY, 1L);
        assertFalse(WoodcutScaffoldMemoryStore.contains(shared, alex, OVERWORLD, 9L));
        assertFalse(shared.containsKey(WoodcutScaffoldMemoryStore.LEGACY_POSITIONS_KEY));
        assertFalse(shared.containsKey(WoodcutScaffoldMemoryStore.LEGACY_DIMENSION_KEY));
        assertFalse(shared.containsKey(WoodcutScaffoldMemoryStore.LEGACY_UPDATED_AT_KEY));
    }

    @Test
    void aStrayValueIsReplacedAndTheSetIsCapped() {
        Map<String, Object> shared = new HashMap<>();
        shared.put(WoodcutScaffoldMemoryStore.positionsKey(alex), "junk");
        assertNull(WoodcutScaffoldMemoryStore.get(shared, alex, OVERWORLD, false));
        for (long i = 0; i < WoodcutScaffoldMemoryStore.MAX_POSITIONS + 10; i++) {
            WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, i, 0L);
        }
        assertEquals(WoodcutScaffoldMemoryStore.MAX_POSITIONS,
                WoodcutScaffoldMemoryStore.get(shared, alex, OVERWORLD, false).size());
    }

    @Test
    void missingArgumentsAreSafe() {
        Map<String, Object> shared = new HashMap<>();
        assertNull(WoodcutScaffoldMemoryStore.get(null, alex, OVERWORLD, true));
        assertNull(WoodcutScaffoldMemoryStore.get(shared, null, OVERWORLD, true));
        assertNull(WoodcutScaffoldMemoryStore.get(shared, alex, null, true));
        WoodcutScaffoldMemoryStore.record(shared, null, OVERWORLD, 1L, 0L);
        WoodcutScaffoldMemoryStore.clearForBot(shared, null);
        assertTrue(shared.isEmpty());
    }

    @Test
    void aReadWritesNothing() {
        Map<String, Object> shared = new HashMap<>();
        assertNull(WoodcutScaffoldMemoryStore.get(shared, alex, OVERWORLD, false));
        assertFalse(WoodcutScaffoldMemoryStore.contains(shared, alex, OVERWORLD, 1L));
        WoodcutScaffoldMemoryStore.forget(shared, alex, OVERWORLD, 1L);
        assertTrue(shared.isEmpty());
    }

    @Test
    void aReadInAnotherDimensionLeavesNoDimensionBehind() {
        Map<String, Object> shared = new HashMap<>();
        WoodcutScaffoldMemoryStore.record(shared, alex, OVERWORLD, 5L, 0L);
        assertNull(WoodcutScaffoldMemoryStore.get(shared, alex, NETHER, false));
        assertFalse(shared.containsKey(WoodcutScaffoldMemoryStore.dimensionKey(alex)));
    }
}
