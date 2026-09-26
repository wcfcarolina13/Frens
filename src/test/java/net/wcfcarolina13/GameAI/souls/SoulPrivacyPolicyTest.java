package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.1.224 P2: DM-private memories never reach a prompt others may hear (pure policy matrix). */
class SoulPrivacyPolicyTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static SoulTypes.PlayerMemory memory(SoulTypes.MemoryVisibility visibility) {
        return new SoulTypes.PlayerMemory(OWNER, 1, "Roti is scared of the dark", 5, -1, List.of(), visibility);
    }

    private static final SoulTypes.PlayerMemory PRIVATE = memory(SoulTypes.MemoryVisibility.PRIVATE);
    private static final SoulTypes.PlayerMemory PUBLIC = memory(SoulTypes.MemoryVisibility.PUBLIC);

    @Test void channelDerivation() {
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE, SoulPrivacyPolicy.visibilityFor(SoulTypes.Channel.DIRECT));
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC, SoulPrivacyPolicy.visibilityFor(SoulTypes.Channel.PARTY));
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC, SoulPrivacyPolicy.visibilityFor(SoulTypes.Channel.LOCAL));
        assertEquals(SoulTypes.MemoryVisibility.PUBLIC, SoulPrivacyPolicy.visibilityFor(SoulTypes.Channel.BANTER));
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE, SoulPrivacyPolicy.visibilityFor(SoulTypes.Channel.SYSTEM));
        assertEquals(SoulTypes.MemoryVisibility.PRIVATE, SoulPrivacyPolicy.visibilityFor(null));
    }

    @Test void stricterTagWins() {
        SoulTypes.MemoryVisibility pub = SoulTypes.MemoryVisibility.PUBLIC;
        SoulTypes.MemoryVisibility priv = SoulTypes.MemoryVisibility.PRIVATE;
        assertEquals(pub, SoulPrivacyPolicy.stricter(pub, pub));
        assertEquals(priv, SoulPrivacyPolicy.stricter(pub, priv));
        assertEquals(priv, SoulPrivacyPolicy.stricter(priv, pub));
        assertEquals(priv, SoulPrivacyPolicy.stricter(priv, priv));
        assertEquals(priv, SoulPrivacyPolicy.stricter(pub, null));
    }

    @Test void publicMemoriesAreAlwaysAdmitted() {
        assertTrue(SoulPrivacyPolicy.admits(PUBLIC, SoulPrivacyPolicy.Audience.directMessage(OTHER)));
        assertTrue(SoulPrivacyPolicy.admits(PUBLIC, SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER, OTHER))));
        assertTrue(SoulPrivacyPolicy.admits(PUBLIC, SoulPrivacyPolicy.Audience.shared(OWNER, Set.of())));
    }

    @Test void dmToTheOwnerAdmitsTheirPrivateMemory() {
        assertTrue(SoulPrivacyPolicy.admits(PRIVATE, SoulPrivacyPolicy.Audience.directMessage(OWNER)));
    }

    @Test void dmToAnotherPlayerWithholdsIt() {
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE, SoulPrivacyPolicy.Audience.directMessage(OTHER)));
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE,
                new SoulPrivacyPolicy.Audience(true, OTHER, Set.of(OWNER, OTHER))));
    }

    @Test void groupSceneWithOthersOnlineWithholdsIt() {
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE, SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER, OTHER))));
    }

    @Test void groupSceneAloneOnServerAdmitsIt() {
        assertTrue(SoulPrivacyPolicy.admits(PRIVATE, SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER))));
    }

    @Test void banterWithOthersOnlineWithholdsIt() {
        // Banter is a shared audience addressed at the owner: another human online anywhere withholds.
        SoulPrivacyPolicy.Audience banter = SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OTHER, OWNER));
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE, banter));
        assertTrue(SoulPrivacyPolicy.admits(PUBLIC, banter));
    }

    @Test void unknownOnlineSetFailsClosed() {
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE, SoulPrivacyPolicy.Audience.shared(OWNER, Set.of())));
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE, SoulPrivacyPolicy.Audience.shared(OWNER, null)));
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE, null));
    }

    @Test void onlySomeoneElseOnlineWithholdsIt() {
        // The alone exception is "the owner is the only human", not "exactly one human".
        assertFalse(SoulPrivacyPolicy.admits(PRIVATE, SoulPrivacyPolicy.Audience.shared(OTHER, Set.of(OTHER))));
    }

    @Test void filterKeepsOrderAndCountsWithheld() {
        SoulTypes.PlayerMemory other = new SoulTypes.PlayerMemory(OWNER, 2, "Roti wants a farm", 5, -1, List.of(),
                SoulTypes.MemoryVisibility.PUBLIC);
        SoulPrivacyPolicy.Filtered filtered = SoulPrivacyPolicy.filter(List.of(PRIVATE, other, PUBLIC),
                SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER, OTHER)));
        assertEquals(List.of(other, PUBLIC), filtered.admitted());
        assertEquals(1, filtered.withheld());
    }

    // ── mayDeliverPrivatelySeededLine (per-line playback gate) ─────────────

    @Test void privatelySeededLineDeliversToOwnerAlone() {
        assertTrue(SoulPrivacyPolicy.mayDeliverPrivatelySeededLine(OWNER, Set.of(OWNER)));
    }

    @Test void privatelySeededLineStopsWhenAnotherHumanWouldReceiveIt() {
        assertFalse(SoulPrivacyPolicy.mayDeliverPrivatelySeededLine(OWNER, Set.of(OWNER, OTHER)));
        // The owner out of earshot does not make someone else a safe audience.
        assertFalse(SoulPrivacyPolicy.mayDeliverPrivatelySeededLine(OWNER, Set.of(OTHER)));
    }

    @Test void privatelySeededLineWithNoRecipientsDeliversNothingHarmful() {
        assertTrue(SoulPrivacyPolicy.mayDeliverPrivatelySeededLine(OWNER, Set.of()));
    }

    @Test void privatelySeededLineFailsClosedOnMissingInputs() {
        assertFalse(SoulPrivacyPolicy.mayDeliverPrivatelySeededLine(null, Set.of()));
        assertFalse(SoulPrivacyPolicy.mayDeliverPrivatelySeededLine(OWNER, null));
    }

    // === 1.1.224 follow-up: transcript records from privately seeded scenes ===

    private static SoulTypes.ConversationRecord spoken(UUID privateTo) {
        return new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, 0L, SoulTypes.TurnKind.SPOKEN,
                "Jake: still scared of the dark?", java.time.Instant.EPOCH, "", "", null, null, List.of(), privateTo);
    }

    @Test void unmarkedRecordIsAlwaysReplayed() {
        SoulTypes.ConversationRecord plain = spoken(null);
        assertTrue(SoulPrivacyPolicy.admitsRecord(plain, SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER, OTHER))));
        assertTrue(SoulPrivacyPolicy.admitsRecord(plain, SoulPrivacyPolicy.Audience.shared(OWNER, Set.of())));
        assertTrue(SoulPrivacyPolicy.admitsRecord(plain, null));
    }

    @Test void markedRecordReplaysWhenItsOwnerIsAlone() {
        assertTrue(SoulPrivacyPolicy.admitsRecord(spoken(OWNER), SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER))));
        assertTrue(SoulPrivacyPolicy.admitsRecord(spoken(OWNER), SoulPrivacyPolicy.Audience.directMessage(OWNER)));
    }

    @Test void markedRecordIsSkippedWhenAnotherHumanIsOnlineOrUnknown() {
        assertFalse(SoulPrivacyPolicy.admitsRecord(spoken(OWNER),
                SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER, OTHER))));
        assertFalse(SoulPrivacyPolicy.admitsRecord(spoken(OWNER), SoulPrivacyPolicy.Audience.shared(OWNER, Set.of())));
        assertFalse(SoulPrivacyPolicy.admitsRecord(spoken(OWNER), SoulPrivacyPolicy.Audience.shared(OTHER, Set.of(OTHER))));
        assertFalse(SoulPrivacyPolicy.admitsRecord(spoken(OWNER), SoulPrivacyPolicy.Audience.directMessage(OTHER)));
        assertFalse(SoulPrivacyPolicy.admitsRecord(spoken(OWNER), null));
        assertFalse(SoulPrivacyPolicy.admitsRecord(null, SoulPrivacyPolicy.Audience.directMessage(OWNER)));
    }

    @Test void admittedPrivateRecordOwnerNamesTheSeedOwnerOnlyWhenAdmitted() {
        List<SoulTypes.ConversationRecord> history = List.of(spoken(null), spoken(OWNER));
        assertEquals(OWNER, SoulPrivacyPolicy.admittedPrivateRecordOwner(history,
                SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER))));
        assertEquals(null, SoulPrivacyPolicy.admittedPrivateRecordOwner(history,
                SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER, OTHER))));
        assertEquals(null, SoulPrivacyPolicy.admittedPrivateRecordOwner(List.of(spoken(null)),
                SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER))));
        assertEquals(null, SoulPrivacyPolicy.admittedPrivateRecordOwner(null,
                SoulPrivacyPolicy.Audience.shared(OWNER, Set.of(OWNER))));
    }

    @Test void digestVisibilityStricterTagWinsOverMixedSources() {
        SoulTypes.MemoryVisibility pub = SoulTypes.MemoryVisibility.PUBLIC;
        SoulTypes.MemoryVisibility priv = SoulTypes.MemoryVisibility.PRIVATE;
        assertEquals(pub, SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, List.of(spoken(null), spoken(null))));
        assertEquals(priv, SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, List.of(spoken(null), spoken(OWNER))));
        assertEquals(priv, SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, List.of(spoken(OWNER))));
        assertEquals(pub, SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, List.of()));
        assertEquals(pub, SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.PARTY, null));
        assertEquals(priv, SoulPrivacyPolicy.digestVisibility(SoulTypes.Channel.DIRECT, List.of(spoken(null))));
    }

    @Test void digestibleWithholdsRecordsPrivateToSomeoneElse() {
        assertTrue(SoulPrivacyPolicy.digestible(spoken(null), OWNER));
        assertTrue(SoulPrivacyPolicy.digestible(spoken(OWNER), OWNER));
        assertFalse(SoulPrivacyPolicy.digestible(spoken(OTHER), OWNER));
        assertFalse(SoulPrivacyPolicy.digestible(spoken(OWNER), null));
        assertTrue(SoulPrivacyPolicy.digestible(spoken(null), null));
        assertFalse(SoulPrivacyPolicy.digestible(null, OWNER));
    }
}
