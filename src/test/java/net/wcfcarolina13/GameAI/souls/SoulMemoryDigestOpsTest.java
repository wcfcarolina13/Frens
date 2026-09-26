package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.random.RandomGenerator;
import static org.junit.jupiter.api.Assertions.*;

class SoulMemoryDigestOpsTest {
    private static final UUID BOT = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    /** The addressed DM reply to PLAYER: admits PLAYER's own private memories. */
    private static final SoulPrivacyPolicy.Audience DM = SoulPrivacyPolicy.Audience.directMessage(PLAYER);
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

    private static SoulTypes.ConversationRecord spokenPrivate(String content, UUID corr, UUID privateTo) {
        return new SoulTypes.ConversationRecord(corr, 1L, seq++, SoulTypes.TurnKind.SPOKEN, content, Instant.EPOCH,
                "", "", null, null, List.of(), privateTo);
    }

    // === 1.1.224 follow-up: a privately seeded party line keeps the digested memory PRIVATE ===

    @Test void partyDigestWithAPrivatelySeededLineIsPrivate() {
        UUID scene = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> r = List.of(
                rec(SoulTypes.TurnKind.HEARD, "Roti: evening all", scene, List.of(BOT)),
                spokenPrivate("Jake: still scared of the dark, Roti?", scene, PLAYER),
                rec(SoulTypes.TurnKind.HEARD, "Roti: a little", scene, List.of(BOT)));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(
                r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", true, PLAYER);
        assertTrue(m.text().contains("Jake: still scared of the dark, Roti?"), "kept for the owner's own digest");
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE,
                SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, m.records()));
    }

    @Test void partyDigestWithoutMarkedLinesStaysPublic() {
        UUID scene = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> r = List.of(
                rec(SoulTypes.TurnKind.HEARD, "Roti: evening all", scene, List.of(BOT)),
                rec(SoulTypes.TurnKind.SPOKEN, "Jake: evening!", scene, null));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(
                r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", true, PLAYER);
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC,
                SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, m.records()));
    }

    @Test void recordPrivateToSomeoneElseIsWithheldFromTheDigest() {
        UUID scene = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> r = List.of(
                rec(SoulTypes.TurnKind.HEARD, "Roti: evening all", scene, List.of(BOT)),
                spokenPrivate("Jake: Sam told me a secret", scene, other));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(
                r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", true, PLAYER);
        assertFalse(m.text().contains("secret"), m.text());
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC,
                SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, m.records()));
        // The cursor still advances past the withheld record, so it is never re-read.
        assertEquals(new SoulTypes.ConversationCursor(1L, r.get(1).sequence() + 1), m.next());
        // The pre-1.1.224 gather (no digested player) withholds every marked record.
        assertFalse(SoulMemoryDigestOps.gather(r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", true)
                .text().contains("secret"));
    }

    @Test void mergeOfAPrivatelySeededPartyFactIntoAPublicMemoryTurnsItPrivate() {
        SoulTypes.PlayerMemory old = new SoulTypes.PlayerMemory(PLAYER, 1, "Roti is scared of the dark", 5, -1,
                List.of(), SoulTypes.MemoryVisibility.PUBLIC);
        SoulTypes.MemoryVisibility tag = SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY,
                List.of(spokenPrivate("Jake: still scared of the dark, Roti?", UUID.randomUUID(), PLAYER)));
        List<SoulTypes.PlayerMemory> merged = SoulMemoryDigestOps.merge(List.of(old), PLAYER,
                List.of("Roti is scared of the dark"), 2, List.of(), tag);
        assertEquals(1, merged.size());
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE, merged.get(0).visibility());
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
                List.of("Roti really hates the Nether", "Roti wants a farm"), 4, List.of(src),
                SoulTypes.MemoryVisibility.PRIVATE);
        SoulTypes.PlayerMemory bumped = merged.stream().filter(m -> m.fact().equals("Roti hates the Nether")).findFirst().orElseThrow();
        assertEquals(7, bumped.salience());
        assertEquals(List.of(src), bumped.sourceCorrelationIds());
        assertTrue(merged.stream().anyMatch(m -> m.fact().equals("Roti wants a farm") && m.salience() == 10 && m.day() == 4));
        assertTrue(merged.stream().anyMatch(m -> m.playerId().equals(other)));

        List<SoulTypes.PlayerMemory> many = new ArrayList<>();
        for (int i = 0; i < SoulMemoryDigestOps.MAX_PER_PLAYER; i++)
            many.add(new SoulTypes.PlayerMemory(PLAYER, i, "Roti fact number " + i + " alpha" + i, i + 1, -1, List.of()));
        List<SoulTypes.PlayerMemory> capped = SoulMemoryDigestOps.merge(many, PLAYER, List.of("Roti brand new zeta"), 30, List.of(),
                SoulTypes.MemoryVisibility.PRIVATE);
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
        assertTrue(SoulMemoryDigestOps.anchors(recalled, PLAYER, "Roti", 6, rnd, DM).isEmpty()); // cooldown
        List<SoulBanterSeed.Anchor> a = SoulMemoryDigestOps.anchors(recalled, PLAYER, "Roti", 9, rnd, DM);
        assertEquals(1, a.size());
        assertEquals(SoulMindOps.MEMORY_TOPIC_PREFIX + key, a.get(0).topic());
        assertEquals("Roti once said: Roti wants a farm", a.get(0).phrase());
        assertEquals(SoulMindOps.MEMORY_ANCHOR_WEIGHT, a.get(0).weight());

        assertEquals(List.of("- Roti wants a farm"), SoulMemoryDigestOps.aboutLines(recalled, PLAYER, DM));
        assertTrue(SoulMemoryDigestOps.aboutLines(recalled, UUID.randomUUID(), DM).isEmpty());

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

    // === 1.1.224 visibility tags (P2) ===

    @Test void mergeTagsNewMemoriesWithTheSourceVisibility() {
        List<SoulTypes.PlayerMemory> dm = SoulMemoryDigestOps.merge(List.of(), PLAYER,
                List.of("Roti hates the Nether"), 1, List.of(),
                SoulPrivacyPolicy.visibilityFor(SoulTypes.Channel.DIRECT));
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE, dm.get(0).visibility());

        List<SoulTypes.PlayerMemory> party = SoulMemoryDigestOps.merge(List.of(), PLAYER,
                List.of("Roti hates the Nether"), 1, List.of(),
                SoulPrivacyPolicy.visibilityFor(SoulTypes.Channel.PARTY));
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC, party.get(0).visibility());
    }

    @Test void mixedSourceMergeKeepsTheStricterTag() {
        List<SoulTypes.PlayerMemory> publicFirst = SoulMemoryDigestOps.merge(List.of(), PLAYER,
                List.of("Roti hates the Nether"), 1, List.of(), SoulTypes.MemoryVisibility.PUBLIC);
        List<SoulTypes.PlayerMemory> thenDm = SoulMemoryDigestOps.merge(publicFirst, PLAYER,
                List.of("Roti really hates the Nether"), 2, List.of(), SoulTypes.MemoryVisibility.PRIVATE);
        assertEquals(1, thenDm.size());
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE, thenDm.get(0).visibility(), "private wins a PUBLIC+DM merge");

        List<SoulTypes.PlayerMemory> privateFirst = SoulMemoryDigestOps.merge(List.of(), PLAYER,
                List.of("Roti hates the Nether"), 1, List.of(), SoulTypes.MemoryVisibility.PRIVATE);
        List<SoulTypes.PlayerMemory> thenParty = SoulMemoryDigestOps.merge(privateFirst, PLAYER,
                List.of("Roti really hates the Nether"), 2, List.of(), SoulTypes.MemoryVisibility.PUBLIC);
        assertEquals(1, thenParty.size());
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE, thenParty.get(0).visibility(),
                "a later party repeat never un-privates a DM memory");
    }

    @Test void legacyConstructorAndNullTagDefaultToPrivate() {
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE,
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti hates the Nether", 5, -1, List.of()).visibility());
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE,
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti hates the Nether", 5, -1, List.of(), null).visibility());
    }

    @Test void decayAndRecallPreserveTheTag() {
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti wants a farm", 9, -1, List.of(),
                        SoulTypes.MemoryVisibility.PUBLIC)));
        SoulTypes.SoulMind decayed = SoulMemoryDigestOps.decay(mind);
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC, decayed.playerMemories().get(0).visibility());
        SoulTypes.SoulMind recalled = SoulMemoryDigestOps.noteRecalled(decayed,
                SoulMemoryDigestOps.factKey("Roti wants a farm"), 5);
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC, recalled.playerMemories().get(0).visibility());
    }

    @Test void sharedAudienceFiltersAboutLinesAndAnchorsAndCountsWithheld() {
        UUID stranger = UUID.randomUUID();
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti is scared of the dark", 9, -1, List.of(),
                        SoulTypes.MemoryVisibility.PRIVATE),
                new SoulTypes.PlayerMemory(PLAYER, 2, "Roti wants a farm", 5, -1, List.of(),
                        SoulTypes.MemoryVisibility.PUBLIC)));
        SoulPrivacyPolicy.Audience crowd = SoulPrivacyPolicy.Audience.shared(PLAYER, Set.of(PLAYER, stranger));
        assertEquals(List.of("- Roti wants a farm"), SoulMemoryDigestOps.aboutLines(mind, PLAYER, crowd));
        assertEquals(1, SoulMemoryDigestOps.withheldFor(mind, PLAYER, crowd));
        List<SoulBanterSeed.Anchor> anchors = SoulMemoryDigestOps.anchors(mind, PLAYER, "Roti", 9, new Random(1), crowd);
        assertEquals(List.of("Roti once said: Roti wants a farm"), anchors.stream().map(SoulBanterSeed.Anchor::phrase).toList(),
                "the stronger private memory must not seed banter others can hear");

        SoulPrivacyPolicy.Audience alone = SoulPrivacyPolicy.Audience.shared(PLAYER, Set.of(PLAYER));
        assertEquals(List.of("- Roti is scared of the dark", "- Roti wants a farm"),
                SoulMemoryDigestOps.aboutLines(mind, PLAYER, alone));
        assertEquals(0, SoulMemoryDigestOps.withheldFor(mind, PLAYER, alone));
        assertEquals("Roti once said: Roti is scared of the dark",
                SoulMemoryDigestOps.anchors(mind, PLAYER, "Roti", 9, new Random(1), alone).get(0).phrase());

        assertEquals(2, SoulMemoryDigestOps.aboutLines(mind, PLAYER, DM).size());
    }

    @Test void privateMemoryAnchorCarriesItsOwnerAndPublicOneDoesNot() {
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti is scared of the dark", 9, -1, List.of(),
                        SoulTypes.MemoryVisibility.PRIVATE),
                new SoulTypes.PlayerMemory(PLAYER, 2, "Roti wants a farm", 5, -1, List.of(),
                        SoulTypes.MemoryVisibility.PUBLIC)));
        SoulPrivacyPolicy.Audience alone = SoulPrivacyPolicy.Audience.shared(PLAYER, Set.of(PLAYER));
        SoulBanterSeed.Anchor privateAnchor =
                SoulMemoryDigestOps.anchors(mind, PLAYER, "Roti", 9, new Random(1), alone).get(0);
        assertEquals("Roti once said: Roti is scared of the dark", privateAnchor.phrase());
        assertEquals(PLAYER, privateAnchor.privateOwner(), "provenance, not a phrase match, marks the seed private");

        SoulPrivacyPolicy.Audience crowd = SoulPrivacyPolicy.Audience.shared(PLAYER, Set.of(PLAYER, UUID.randomUUID()));
        SoulBanterSeed.Anchor publicAnchor =
                SoulMemoryDigestOps.anchors(mind, PLAYER, "Roti", 9, new Random(1), crowd).get(0);
        assertEquals("Roti once said: Roti wants a farm", publicAnchor.phrase());
        assertEquals(null, publicAnchor.privateOwner());
        // Every other anchor source uses the three-argument constructor: no owner.
        assertEquals(null, new SoulBanterSeed.Anchor("t", "p", 1).privateOwner());
    }

    @Test void aboutLinesCapsLineCountInSalienceDescendingOrder() {
        List<SoulTypes.PlayerMemory> memories = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            memories.add(new SoulTypes.PlayerMemory(PLAYER, i, "Roti fact " + i, i + 1, -1, List.of()));
        }
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), memories);

        List<String> lines = SoulMemoryDigestOps.aboutLines(mind, PLAYER, DM);

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

        List<String> lines = SoulMemoryDigestOps.aboutLines(mind, PLAYER, DM);

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
