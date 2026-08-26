package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the overhear recorder's bounds: ring size, TTL, and — the point of the class —
 * that a bot only ever reads lines it actually witnessed.
 */
class SoulLocalMemoryTest {

    private final UUID player = UUID.randomUUID();
    private final UUID jake = UUID.randomUUID();
    private final UUID sara = UUID.randomUUID();

    @BeforeEach
    void reset() {
        SoulLocalMemory.clear();
    }

    @Test
    void witnessedLinesComeBackOldestFirst() {
        SoulLocalMemory.note(player, "heading to the ravine", Set.of(jake), 1_000L);
        SoulLocalMemory.note(player, "bring a bucket", Set.of(jake), 2_000L);

        assertEquals(List.of("heading to the ravine", "bring a bucket"),
                SoulLocalMemory.witnessedBy(jake, player, 3_000L));
    }

    @Test
    void nonWitnessReadsNothing() {
        SoulLocalMemory.note(player, "heading to the ravine", Set.of(jake), 1_000L);

        assertTrue(SoulLocalMemory.witnessedBy(sara, player, 2_000L).isEmpty());
    }

    @Test
    void ringIsBoundedAndDropsOldest() {
        for (int i = 0; i < SoulLocalMemory.MAX_ENTRIES_PER_PLAYER + 3; i++) {
            SoulLocalMemory.note(player, "line " + i, Set.of(jake), 1_000L + i);
        }

        List<String> read = SoulLocalMemory.witnessedBy(jake, player, 2_000L);
        assertEquals(SoulLocalMemory.MAX_ENTRIES_PER_PLAYER, read.size());
        assertEquals("line 3", read.get(0));
        assertEquals("line 10", read.get(read.size() - 1));
    }

    @Test
    void entriesOlderThanTtlAreInvisible() {
        SoulLocalMemory.note(player, "stale", Set.of(jake), 1_000L);

        assertTrue(SoulLocalMemory.witnessedBy(
                jake, player, 1_000L + SoulLocalMemory.TTL_MS + 1).isEmpty());
    }

    @Test
    void blankAndEmptyWitnessSetsAreNotRecorded() {
        SoulLocalMemory.note(player, "   ", Set.of(jake), 1_000L);
        SoulLocalMemory.note(player, "nobody heard this", Set.of(), 1_000L);

        assertTrue(SoulLocalMemory.witnessedBy(jake, player, 2_000L).isEmpty());
    }

    @Test
    void forgetDropsOnePlayerAndClearDropsAll() {
        UUID other = UUID.randomUUID();
        SoulLocalMemory.note(player, "mine", Set.of(jake), 1_000L);
        SoulLocalMemory.note(other, "theirs", Set.of(jake), 1_000L);

        SoulLocalMemory.forget(player);
        assertTrue(SoulLocalMemory.witnessedBy(jake, player, 2_000L).isEmpty());
        assertEquals(List.of("theirs"), SoulLocalMemory.witnessedBy(jake, other, 2_000L));

        SoulLocalMemory.clear();
        assertTrue(SoulLocalMemory.witnessedBy(jake, other, 2_000L).isEmpty());
    }
}
