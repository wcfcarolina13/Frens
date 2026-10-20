package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static net.wcfcarolina13.GameAI.services.FoodConfirmReplyPolicy.PENDING_TTL_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodConfirmReplyPolicyTest {

    private static final Set<String> BOB = Set.of("bob");
    private static final Set<String> BOB_AND_ALICE = Set.of("bob", "alice");

    @Test
    void bareYesAndNoInAnyCase() {
        assertEquals(new FoodConfirmReplyPolicy.Reply(null, true), FoodConfirmReplyPolicy.parse("yes", BOB));
        assertEquals(new FoodConfirmReplyPolicy.Reply(null, true), FoodConfirmReplyPolicy.parse("  Y ", BOB));
        assertEquals(new FoodConfirmReplyPolicy.Reply(null, true), FoodConfirmReplyPolicy.parse("YES!", BOB));
        assertEquals(new FoodConfirmReplyPolicy.Reply(null, false), FoodConfirmReplyPolicy.parse("no", BOB));
        assertEquals(new FoodConfirmReplyPolicy.Reply(null, false), FoodConfirmReplyPolicy.parse("N.", BOB));
    }

    @Test
    void aliasFormsPickThatBot() {
        assertEquals(new FoodConfirmReplyPolicy.Reply("bob", true), FoodConfirmReplyPolicy.parse("Bob yes", BOB_AND_ALICE));
        assertEquals(new FoodConfirmReplyPolicy.Reply("bob", true), FoodConfirmReplyPolicy.parse("Bob: yes", BOB_AND_ALICE));
        assertEquals(new FoodConfirmReplyPolicy.Reply("bob", true), FoodConfirmReplyPolicy.parse("Bob:yes", BOB_AND_ALICE));
        assertEquals(new FoodConfirmReplyPolicy.Reply("alice", false), FoodConfirmReplyPolicy.parse("alice, no", BOB_AND_ALICE));
        assertEquals(new FoodConfirmReplyPolicy.Reply("alice", false), FoodConfirmReplyPolicy.parse("ALICE n", BOB_AND_ALICE));
    }

    @Test
    void aliasMatchUsesTheSameNormalizationAsStorage() {
        // A bot named "Bob_2" is stored as "bob2"; the reply "Bob_2 yes" must find it.
        String stored = FoodConfirmReplyPolicy.normalizeAlias("Bob_2");
        assertEquals("bob2", stored);
        assertEquals(new FoodConfirmReplyPolicy.Reply("bob2", true), FoodConfirmReplyPolicy.parse("Bob_2 yes", Set.of(stored)));
    }

    @Test
    void ordinaryChatIsNotConsumed() {
        assertNull(FoodConfirmReplyPolicy.parse("follow", BOB));
        assertNull(FoodConfirmReplyPolicy.parse("Bob follow", BOB));
        assertNull(FoodConfirmReplyPolicy.parse("Bob: come here", BOB));
        assertNull(FoodConfirmReplyPolicy.parse("yes please", BOB));
        assertNull(FoodConfirmReplyPolicy.parse("yeah", BOB));
        assertNull(FoodConfirmReplyPolicy.parse("Bob", BOB));
        assertNull(FoodConfirmReplyPolicy.parse("", BOB));
        assertNull(FoodConfirmReplyPolicy.parse("   ", BOB));
        assertNull(FoodConfirmReplyPolicy.parse(null, BOB));
    }

    @Test
    void aNameWithoutAPendingPromptIsNotAnAnswer() {
        // "Steve yes" is talk to Steve, not an answer to Bob's prompt.
        assertNull(FoodConfirmReplyPolicy.parse("Steve yes", BOB));
    }

    @Test
    void nothingPendingMeansNothingIsAnAnswer() {
        assertNull(FoodConfirmReplyPolicy.parse("yes", Set.of()));
        assertNull(FoodConfirmReplyPolicy.parse("yes", null));
    }

    @Test
    void pendingPromptLastsSixtySeconds() {
        assertEquals(60_000L, PENDING_TTL_MS);
        long asked = 1_000_000L;
        assertFalse(FoodConfirmReplyPolicy.isExpired(asked, asked));
        assertFalse(FoodConfirmReplyPolicy.isExpired(asked, asked + PENDING_TTL_MS));
        assertTrue(FoodConfirmReplyPolicy.isExpired(asked, asked + PENDING_TTL_MS + 1L));
        // Before the fix a prompt never expired, so the same item could never be asked about again.
        assertTrue(FoodConfirmReplyPolicy.isExpired(asked, asked + 10L * 60_000L));
    }

    @Test
    void clockBehindThePromptCountsAsFresh() {
        assertFalse(FoodConfirmReplyPolicy.isExpired(50_000L, 1_000L));
    }

    @Test
    void bareAnswerGoesToTheMostRecentPrompt() {
        Map<String, Long> asked = new LinkedHashMap<>();
        asked.put("bob", 1_000L);
        asked.put("alice", 2_000L);
        assertEquals("alice", FoodConfirmReplyPolicy.mostRecent(asked));
        // Bob asks again later about a different item: insertion order still says Alice first,
        // creation time says Bob.
        asked.put("bob", 3_000L);
        assertEquals("bob", FoodConfirmReplyPolicy.mostRecent(asked));
    }

    @Test
    void mostRecentOfNothingIsNull() {
        assertNull(FoodConfirmReplyPolicy.mostRecent(new LinkedHashMap<String, Long>()));
        assertNull(FoodConfirmReplyPolicy.mostRecent(null));
    }

    @Test
    void aResumeAnswerBlocksTheFoodPrompt() {
        // Precedence respawn -> resume -> food: a "yes" the skill-resume prompt took is not also
        // a yes to the golden apple.
        assertFalse(FoodConfirmReplyPolicy.foodMayClaim(true));
        assertTrue(FoodConfirmReplyPolicy.foodMayClaim(false));
    }
}
