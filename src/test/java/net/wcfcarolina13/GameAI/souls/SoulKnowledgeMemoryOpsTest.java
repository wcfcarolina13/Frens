package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulKnowledgeMemoryOpsTest {

    private static SoulTypes.KnownPlace place(String id, int x, long seen) {
        return new SoulTypes.KnownPlace(id, "minecraft:overworld", x, 64, 0, seen);
    }

    @Test
    void sightingAtSamePositionUpdatesLastSeenInsteadOfDuplicating() {
        SoulTypes.KnowledgeMemory memory = SoulKnowledgeMemoryOps.mergeSightings(
                SoulTypes.KnowledgeMemory.empty(),
                List.of(place("enchanting_table", 10, 1000L)));
        memory = SoulKnowledgeMemoryOps.mergeSightings(memory,
                List.of(place("enchanting_table", 10, 2000L)));

        assertEquals(1, memory.places().size());
        assertEquals(2000L, memory.places().get(0).lastSeenEpochMs());
    }

    @Test
    void placesAreCappedByOldestLastSeen() {
        SoulTypes.KnowledgeMemory memory = SoulTypes.KnowledgeMemory.empty();
        for (int i = 0; i < SoulKnowledgeMemoryOps.MAX_PLACES + 20; i++) {
            memory = SoulKnowledgeMemoryOps.mergeSightings(memory,
                    List.of(place("chest", i, 1000L + i)));
        }
        assertEquals(SoulKnowledgeMemoryOps.MAX_PLACES, memory.places().size());
        long oldestKept = memory.places().stream()
                .mapToLong(SoulTypes.KnownPlace::lastSeenEpochMs).min().orElseThrow();
        assertEquals(1000L + 20, oldestKept);
    }

    @Test
    void toldFactsCapPerTopicKeepsNewest() {
        SoulTypes.KnowledgeMemory memory = SoulTypes.KnowledgeMemory.empty();
        for (int i = 0; i < SoulKnowledgeMemoryOps.MAX_TOLD_PER_TOPIC + 2; i++) {
            memory = SoulKnowledgeMemoryOps.mergeToldFact(memory, "barrel",
                    new SoulTypes.ToldFact("Roti", "fact " + i, 1000L + i));
        }
        List<SoulTypes.ToldFact> facts = memory.toldFacts().get("barrel");
        assertEquals(SoulKnowledgeMemoryOps.MAX_TOLD_PER_TOPIC, facts.size());
        assertTrue(facts.get(facts.size() - 1).message().endsWith(
                String.valueOf(SoulKnowledgeMemoryOps.MAX_TOLD_PER_TOPIC + 1)));
    }

    @Test
    void questionsAreNotToldFacts() {
        assertTrue(SoulKnowledgeMemoryOps.isStatement("the spare picks are in the barrel by the gate"));
        assertEquals(false, SoulKnowledgeMemoryOps.isStatement("can you make a torch?"));
        assertEquals(false, SoulKnowledgeMemoryOps.isStatement("where is the barrel"));
        assertEquals(false, SoulKnowledgeMemoryOps.isStatement("   "));
    }
}
