package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.random.RandomGenerator;
import static org.junit.jupiter.api.Assertions.*;

class SoulMemoryDigestOpsTest {
    private static final UUID BOT = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static long seq = 0;

    private static SoulTypes.ConversationRecord rec(SoulTypes.TurnKind kind, String content, UUID corr, List<UUID> parts) {
        return new SoulTypes.ConversationRecord(corr, 1L, seq++, kind, content, Instant.EPOCH, "", "", null, null, parts);
    }

    @Test void gatherDirectRendersTagsAndDropsFailuresAndNarrator() {
        UUID c = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> r = List.of(
                rec(SoulTypes.TurnKind.HEARD, "I hate the Nether", c, null),
                rec(SoulTypes.TurnKind.SPOKEN, "Noted.", c, null),
                rec(SoulTypes.TurnKind.FAILURE, "", c, null),
                rec(SoulTypes.TurnKind.HEARD, SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + "seed", c, null));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", false);
        assertEquals("Roti: I hate the Nether\nJake: Noted.", m.text());
        assertEquals(1, m.playerLines());
        assertEquals(new SoulTypes.ConversationCursor(1L, r.get(3).sequence() + 1), m.next());
    }

    @Test void gatherPartyKeepsOnlyScenesTheBotWasIn() {
        UUID in = UUID.randomUUID(), out = UUID.randomUUID(), legacy = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> r = List.of(
                rec(SoulTypes.TurnKind.HEARD, "Roti: hello all", in, List.of(BOT)),
                rec(SoulTypes.TurnKind.SPOKEN, "Bob: hey", in, null),
                rec(SoulTypes.TurnKind.HEARD, "Roti: not you", out, List.of(UUID.randomUUID())),
                rec(SoulTypes.TurnKind.HEARD, "Roti: old scene", legacy, null),
                rec(SoulTypes.TurnKind.SPOKEN, "Jake: I was here", legacy, null));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", true);
        assertFalse(m.text().contains("not you"));
        assertTrue(m.text().contains("hello all"));
        assertTrue(m.text().contains("old scene"));
        assertEquals(2, m.playerLines());
    }

    @Test void gatherCapsRecordsAndCharsNewestFirstAndKeepsCursorWhenEmpty() {
        List<SoulTypes.ConversationRecord> r = new ArrayList<>();
        for (int i = 0; i < 60; i++) r.add(rec(SoulTypes.TurnKind.HEARD, "line " + i + " " + "x".repeat(80), UUID.randomUUID(), null));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", false);
        assertTrue(m.text().length() <= SoulMemoryDigestOps.MAX_MATERIAL_CHARS);
        assertTrue(m.text().contains("line 59"));
        assertFalse(m.text().contains("line 0 "));
        SoulTypes.ConversationCursor from = new SoulTypes.ConversationCursor(1L, 7L);
        assertEquals(from, SoulMemoryDigestOps.gather(List.of(), from, BOT, "Jake", "Roti", false).next());
    }

    @Test void validateAcceptsWellFormedLinesOnly() {
        String raw = "- Roti hates the Nether\n- they want to build a farm\n" +
                "not a fact\n- §cbad\n- " + "x".repeat(120) + "\n- nothing about anyone\n- Roti likes cats\n- Roti named the base Home\n- Roti fears creepers";
        List<String> facts = SoulMemoryDigestOps.validate(raw, "Roti");
        assertEquals(List.of("Roti hates the Nether", "they want to build a farm", "Roti likes cats", "Roti named the base Home", "Roti fears creepers"), facts);
        assertTrue(SoulMemoryDigestOps.validate("- none", "Roti").isEmpty());
        assertTrue(SoulMemoryDigestOps.validate("", "Roti").isEmpty());
        StringBuilder runaway = new StringBuilder();
        for (int i = 0; i < 9; i++) runaway.append("- Roti fact ").append(i).append('\n');
        assertTrue(SoulMemoryDigestOps.validate(runaway.toString(), "Roti").isEmpty());
    }

    @Test void mergeDedupesBumpsAndCaps() {
        UUID other = UUID.randomUUID();
        List<SoulTypes.PlayerMemory> existing = new ArrayList<>(List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti hates the Nether", 5, -1, List.of()),
                new SoulTypes.PlayerMemory(other, 1, "Sam likes cats", 5, -1, List.of())));
        UUID src = UUID.randomUUID();
        List<SoulTypes.PlayerMemory> merged = SoulMemoryDigestOps.merge(existing, PLAYER,
                List.of("Roti really hates the Nether", "Roti wants a farm"), 4, List.of(src));
        SoulTypes.PlayerMemory bumped = merged.stream().filter(m -> m.fact().equals("Roti hates the Nether")).findFirst().orElseThrow();
        assertEquals(7, bumped.salience());
        assertEquals(List.of(src), bumped.sourceCorrelationIds());
        assertTrue(merged.stream().anyMatch(m -> m.fact().equals("Roti wants a farm") && m.salience() == 10 && m.day() == 4));
        assertTrue(merged.stream().anyMatch(m -> m.playerId().equals(other)));

        List<SoulTypes.PlayerMemory> many = new ArrayList<>();
        for (int i = 0; i < SoulMemoryDigestOps.MAX_PER_PLAYER; i++)
            many.add(new SoulTypes.PlayerMemory(PLAYER, i, "Roti fact number " + i + " alpha" + i, i + 1, -1, List.of()));
        List<SoulTypes.PlayerMemory> capped = SoulMemoryDigestOps.merge(many, PLAYER, List.of("Roti brand new zeta"), 30, List.of());
        assertEquals(SoulMemoryDigestOps.MAX_PER_PLAYER, capped.size());
        assertFalse(capped.stream().anyMatch(m -> m.fact().endsWith("alpha0")));
    }

    @Test void decayRecallAnchorsAboutAndArchive() {
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti hates the Nether", 1, -1, List.of()),
                new SoulTypes.PlayerMemory(PLAYER, 2, "Roti wants a farm", 9, -1, List.of())));
        SoulTypes.SoulMind decayed = SoulMemoryDigestOps.decay(mind);
        assertEquals(1, decayed.playerMemories().size());
        assertEquals(8, decayed.playerMemories().get(0).salience());

        String key = SoulMemoryDigestOps.factKey("Roti wants a farm");
        assertTrue(key.startsWith("said:"));
        SoulTypes.SoulMind recalled = SoulMemoryDigestOps.noteRecalled(decayed, key, 5);
        assertEquals(10, recalled.playerMemories().get(0).salience());
        assertEquals(5, recalled.playerMemories().get(0).lastRecalledDay());

        RandomGenerator rnd = new Random(1);
        assertTrue(SoulMemoryDigestOps.anchors(recalled, PLAYER, "Roti", 6, rnd).isEmpty()); // cooldown
        List<SoulBanterSeed.Anchor> a = SoulMemoryDigestOps.anchors(recalled, PLAYER, "Roti", 9, rnd);
        assertEquals(1, a.size());
        assertEquals(SoulMindOps.MEMORY_TOPIC_PREFIX + key, a.get(0).topic());
        assertEquals("Roti once said: Roti wants a farm", a.get(0).phrase());
        assertEquals(SoulMindOps.MEMORY_ANCHOR_WEIGHT, a.get(0).weight());

        assertEquals(List.of("- Roti wants a farm"), SoulMemoryDigestOps.aboutLines(recalled, PLAYER));
        assertTrue(SoulMemoryDigestOps.aboutLines(recalled, UUID.randomUUID()).isEmpty());

        SoulTypes.SoulMind withCursor = SoulMemoryDigestOps.withCursor(recalled, "DIRECT:" + PLAYER, new SoulTypes.ConversationCursor(1L, 9L));
        assertEquals(new SoulTypes.ConversationCursor(1L, 9L), SoulMemoryDigestOps.cursorFor(withCursor, "DIRECT:" + PLAYER));
        assertEquals(new SoulTypes.ConversationCursor(0L, 0L), SoulMemoryDigestOps.cursorFor(withCursor, "PARTY:" + PLAYER));
        SoulTypes.SoulMind archived = SoulMemoryDigestOps.archiveFor(withCursor, PLAYER);
        assertTrue(archived.playerMemories().isEmpty());
        assertEquals(1, archived.archivedPlayerMemories().size());
        assertTrue(archived.digestCursors().isEmpty());
    }

    @Test void archiveForOnlyDropsCursorsAndMemoriesForThatPlayer() {
        UUID other = UUID.randomUUID();
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti hates the Nether", 5, -1, List.of()),
                new SoulTypes.PlayerMemory(other, 1, "Sam likes cats", 5, -1, List.of())));
        mind = SoulMemoryDigestOps.withCursor(mind, "DIRECT:" + PLAYER, new SoulTypes.ConversationCursor(1L, 3L));
        mind = SoulMemoryDigestOps.withCursor(mind, "PARTY:" + PLAYER, new SoulTypes.ConversationCursor(1L, 4L));
        mind = SoulMemoryDigestOps.withCursor(mind, "DIRECT:" + other, new SoulTypes.ConversationCursor(1L, 5L));

        SoulTypes.SoulMind archived = SoulMemoryDigestOps.archiveFor(mind, PLAYER);

        assertEquals(List.of("DIRECT:" + other), new ArrayList<>(archived.digestCursors().keySet()));
        assertTrue(archived.playerMemories().stream().anyMatch(m -> m.playerId().equals(other)));
        assertFalse(archived.playerMemories().stream().anyMatch(m -> m.playerId().equals(PLAYER)));
        assertEquals(1, archived.archivedPlayerMemories().size());
        assertEquals(PLAYER, archived.archivedPlayerMemories().get(0).playerId());
    }

    @Test void aboutLinesCapsLineCountInSalienceDescendingOrder() {
        List<SoulTypes.PlayerMemory> memories = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            memories.add(new SoulTypes.PlayerMemory(PLAYER, i, "Roti fact " + i, i + 1, -1, List.of()));
        }
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), memories);

        List<String> lines = SoulMemoryDigestOps.aboutLines(mind, PLAYER);

        assertEquals(SoulMemoryDigestOps.MAX_ABOUT_LINES, lines.size());
        assertEquals(List.of("- Roti fact 7", "- Roti fact 6", "- Roti fact 5", "- Roti fact 4", "- Roti fact 3"), lines);
    }

    @Test void aboutLinesStopsBeforeExceedingCharCap() {
        List<SoulTypes.PlayerMemory> memories = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String fact = "Roti " + ("fact" + i).repeat(1) + " " + "z".repeat(85);
            memories.add(new SoulTypes.PlayerMemory(PLAYER, i, fact, 5 - i, -1, List.of()));
        }
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), memories);

        List<String> lines = SoulMemoryDigestOps.aboutLines(mind, PLAYER);

        assertTrue(lines.size() < 5);
        assertTrue(String.join("\n", lines).length() <= SoulMemoryDigestOps.MAX_ABOUT_CHARS);
    }

    @Test void archiveForKeepsNewestWithinMaxArchivedCap() {
        List<SoulTypes.PlayerMemory> already = new ArrayList<>();
        for (int i = 0; i < SoulMemoryDigestOps.MAX_ARCHIVED; i++) {
            already.add(new SoulTypes.PlayerMemory(UUID.randomUUID(), i, "old fact " + i, 1, -1, List.of()));
        }
        SoulTypes.SoulMind mind = SoulMindOps.withArchivedPlayerMemories(SoulTypes.SoulMind.empty(), already);
        mind = SoulMindOps.withPlayerMemories(mind, List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti new fact one", 5, -1, List.of()),
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti new fact two", 5, -1, List.of())));

        SoulTypes.SoulMind archived = SoulMemoryDigestOps.archiveFor(mind, PLAYER);

        assertEquals(SoulMemoryDigestOps.MAX_ARCHIVED, archived.archivedPlayerMemories().size());
        assertEquals("Roti new fact one", archived.archivedPlayerMemories().get(0).fact());
        assertEquals("Roti new fact two", archived.archivedPlayerMemories().get(1).fact());
    }
}
