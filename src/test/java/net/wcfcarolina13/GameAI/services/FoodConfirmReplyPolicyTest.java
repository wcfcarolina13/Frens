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
    void cachedApprovalAndDenialCannotCrossAnOwnershipTransfer() {
        var formerOwner = java.util.UUID.randomUUID();
        var currentOwner = java.util.UUID.randomUUID();
        assertFalse(FoodConfirmReplyPolicy.consentMatches(formerOwner, currentOwner));
        assertFalse(FoodConfirmReplyPolicy.consentMatches(currentOwner, formerOwner));
    }

    @Test
    void sameOwnershipRetainsConsentWithoutRequiringAnOnlinePlayer() {
        var owner = java.util.UUID.randomUUID();
        assertTrue(FoodConfirmReplyPolicy.consentMatches(owner, owner));
    }

    @Test
    void explicitOwnerlessHandFeedWorksOnlyUntilOwnershipChanges() {
        var newOwner = java.util.UUID.randomUUID();
        assertTrue(FoodConfirmReplyPolicy.consentMatches(null, null));
        assertFalse(FoodConfirmReplyPolicy.consentMatches(null, newOwner));
        assertFalse(FoodConfirmReplyPolicy.consentMatches(newOwner, null));
        assertFalse(FoodConfirmReplyPolicy.canAnswer(null, null), "ownerless provenance never authorizes a chat answer");
    }

    @Test
    void bothCachesCheckOwnershipAndRemoveOnlyTheObservedStaleRecord() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/FoodConsumptionConfirmationService.java"));
        assertTrue(source.contains("record ApprovedConsumption(String itemId, UUID controllerUuid, long expiresAtMs)"));
        assertTrue(source.contains("record DeniedConsumption(UUID controllerUuid, long expiresAtMs)"));
        String consumption = source.substring(source.indexOf("public static boolean allowConsumptionOrRequest("),
                source.indexOf("public static void preApprove("));
        assertTrue(consumption.contains("resolveControllerUuid(bot.getCommandSource().getServer(), bot)"));
        assertTrue(consumption.contains("FoodConfirmReplyPolicy.consentMatches(approved.controllerUuid, controllerUuid)"));
        assertTrue(consumption.contains("FoodConfirmReplyPolicy.consentMatches(denied.controllerUuid, controllerUuid)"));
        assertTrue(consumption.contains("APPROVED_BY_BOT.remove(botId, approved)"));
        assertTrue(consumption.contains("DENIED_UNTIL_MS_BY_BOT.remove(botId, denied)"));
    }

    @Test
    void handFeedAndChatDecisionsCaptureTheCurrentOwnershipIdentity() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/FoodConsumptionConfirmationService.java"));
        String handFeed = source.substring(source.indexOf("public static void preApprove("),
                source.indexOf("private static void requestConfirmation("));
        assertTrue(handFeed.contains("resolveControllerUuid(bot.getCommandSource().getServer(), bot)"));
        assertTrue(handFeed.contains("new ApprovedConsumption(toItemId(stack), controllerUuid,"));
        String reply = source.substring(source.indexOf("public static boolean tryHandleConfirmation("),
                source.indexOf("private static void pruneExpired("));
        assertTrue(reply.contains("UUID controllerUuid = controller == null ? null : controller.getUuid()"));
        assertTrue(reply.contains("new ApprovedConsumption(state.itemId, controllerUuid,"));
        assertTrue(reply.contains("new DeniedConsumption(controllerUuid,"));
    }

    @Test
    void onlyTheCurrentControllerCanAnswerAPendingFoodPrompt() {
        var controller = java.util.UUID.randomUUID();
        assertTrue(FoodConfirmReplyPolicy.canAnswer(controller, controller));
        assertFalse(FoodConfirmReplyPolicy.canAnswer(java.util.UUID.randomUUID(), controller));
    }

    @Test
    void transferredBotRejectsTheFormerControllersPendingAnswer() {
        var formerOwner = java.util.UUID.randomUUID();
        var currentOwner = java.util.UUID.randomUUID();
        assertTrue(FoodConfirmReplyPolicy.canAnswer(formerOwner, formerOwner));
        assertFalse(FoodConfirmReplyPolicy.canAnswer(formerOwner, currentOwner));
        assertTrue(FoodConfirmReplyPolicy.canAnswer(currentOwner, currentOwner));
    }

    @Test
    void missingControllerOrResponderCannotApproveOrDeny() {
        var player = java.util.UUID.randomUUID();
        assertFalse(FoodConfirmReplyPolicy.canAnswer(player, null));
        assertFalse(FoodConfirmReplyPolicy.canAnswer(null, player));
        assertFalse(FoodConfirmReplyPolicy.canAnswer(null, null));
    }

    @Test
    void serviceRevalidatesBeforeChangingEitherConsumptionDecision() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/FoodConsumptionConfirmationService.java"));
        String reply = source.substring(source.indexOf("public static boolean tryHandleConfirmation("),
                source.indexOf("private static void pruneExpired("));
        assertTrue(reply.contains("PENDING.get(commanderId)"), "only the responder's own pending map is consumed");
        int lookup = reply.indexOf("CompanionCommunicationPolicy.resolveController(server, bot)");
        int gate = reply.indexOf("if (!FoodConfirmReplyPolicy.canAnswer(");
        int notify = reply.indexOf("ChatUtils.sendSystemMessage(commander.getCommandSource()", gate);
        int stop = reply.indexOf("return true;", notify);
        int decision = reply.indexOf("if (decision)");
        assertTrue(lookup >= 0 && lookup < gate && gate < notify && notify < stop && stop < decision);
        assertTrue(decision < reply.indexOf("APPROVED_BY_BOT.put("));
        assertTrue(decision < reply.indexOf("DENIED_UNTIL_MS_BY_BOT.put("));
    }

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
    void pendingPromptLastsTwentySeconds() {
        assertEquals(20_000L, PENDING_TTL_MS);
        long asked = 1_000_000L;
        assertFalse(FoodConfirmReplyPolicy.isExpired(asked, asked));
        assertFalse(FoodConfirmReplyPolicy.isExpired(asked, asked + PENDING_TTL_MS));
        assertTrue(FoodConfirmReplyPolicy.isExpired(asked, asked + PENDING_TTL_MS + 1L));
        // Before the fix a prompt never expired, so the same item could never be asked about again.
        assertTrue(FoodConfirmReplyPolicy.isExpired(asked, asked + 10L * 60_000L));
    }

    @Test
    void aBareAnswerHalfAMinuteLaterIsNoLongerCaptured() {
        // The old 60 s window let an unrelated "no" in chat 30-60 s later answer a stale prompt.
        long asked = 5_000_000L;
        assertTrue(FoodConfirmReplyPolicy.isExpired(asked, asked + 30_000L));
        assertFalse(FoodConfirmReplyPolicy.isExpired(asked, asked + 19_999L));
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
