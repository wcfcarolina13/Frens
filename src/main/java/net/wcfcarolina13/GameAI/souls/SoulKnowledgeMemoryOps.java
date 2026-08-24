package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure merge/cap policy for {@link SoulTypes.KnowledgeMemory}. The store persists; this class
 * decides what the memory looks like after a sighting or a told fact, and what counts as a
 * recordable statement. No Minecraft classes, no I/O.
 */
final class SoulKnowledgeMemoryOps {

    private SoulKnowledgeMemoryOps() {
    }

    static final int MAX_PLACES = 200;
    static final int MAX_TOLD_PER_TOPIC = 3;
    static final int MAX_TOLD_TOPICS = 40;
    static final int MAX_TOLD_MESSAGE_CHARS = 160;

    private static final List<String> INTERROGATIVE_STARTS = List.of(
            "who", "what", "when", "where", "why", "how", "which", "whose",
            "can", "could", "do", "does", "did", "is", "are", "was", "were",
            "will", "would", "should", "shall", "may", "might", "have", "has");

    /** Same position updates lastSeen; overflow evicts the oldest sightings. */
    static SoulTypes.KnowledgeMemory mergeSightings(SoulTypes.KnowledgeMemory memory,
                                                    List<SoulTypes.KnownPlace> sightings) {
        Map<String, SoulTypes.KnownPlace> byPosition = new LinkedHashMap<>();
        for (SoulTypes.KnownPlace place : memory.places()) {
            byPosition.put(positionKey(place), place);
        }
        for (SoulTypes.KnownPlace sighting : sightings) {
            byPosition.put(positionKey(sighting), sighting);
        }
        List<SoulTypes.KnownPlace> merged = new ArrayList<>(byPosition.values());
        if (merged.size() > MAX_PLACES) {
            merged.sort(Comparator.comparingLong(SoulTypes.KnownPlace::lastSeenEpochMs).reversed());
            merged = new ArrayList<>(merged.subList(0, MAX_PLACES));
        }
        return new SoulTypes.KnowledgeMemory(merged, memory.toldFacts());
    }

    /** Appends newest-last, capped per topic; overflow evicts topics with the oldest newest-fact. */
    static SoulTypes.KnowledgeMemory mergeToldFact(SoulTypes.KnowledgeMemory memory,
                                                   String topic, SoulTypes.ToldFact fact) {
        String message = fact.message().length() > MAX_TOLD_MESSAGE_CHARS
                ? fact.message().substring(0, MAX_TOLD_MESSAGE_CHARS)
                : fact.message();
        SoulTypes.ToldFact bounded = new SoulTypes.ToldFact(fact.teller(), message, fact.atEpochMs());

        Map<String, List<SoulTypes.ToldFact>> topics = new LinkedHashMap<>();
        memory.toldFacts().forEach((key, value) -> topics.put(key, new ArrayList<>(value)));
        List<SoulTypes.ToldFact> facts = topics.computeIfAbsent(topic, k -> new ArrayList<>());
        facts.add(bounded);
        while (facts.size() > MAX_TOLD_PER_TOPIC) {
            facts.remove(0);
        }
        while (topics.size() > MAX_TOLD_TOPICS) {
            String oldestTopic = topics.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().get(e.getValue().size() - 1).atEpochMs()))
                    .map(Map.Entry::getKey).orElseThrow();
            topics.remove(oldestTopic);
        }
        return new SoulTypes.KnowledgeMemory(memory.places(), topics);
    }

    /**
     * True when a player message reads as information rather than a question — the gate for
     * recording told facts. Questions retrieve; statements teach.
     */
    static boolean isStatement(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.contains("?")) {
            return false;
        }
        String firstWord = trimmed.split("\\s+")[0];
        return !INTERROGATIVE_STARTS.contains(firstWord);
    }

    static String positionKey(SoulTypes.KnownPlace place) {
        return place.dimension() + ':' + place.x() + ':' + place.y() + ':' + place.z();
    }

    /** Disproof-on-revisit: drops remembered places whose position keys were verified stale. */
    static SoulTypes.KnowledgeMemory removePlaces(SoulTypes.KnowledgeMemory memory,
                                                  java.util.Set<String> positionKeys) {
        if (positionKeys.isEmpty()) {
            return memory;
        }
        List<SoulTypes.KnownPlace> kept = memory.places().stream()
                .filter(place -> !positionKeys.contains(positionKey(place)))
                .toList();
        return kept.size() == memory.places().size()
                ? memory
                : new SoulTypes.KnowledgeMemory(kept, memory.toldFacts());
    }
}
