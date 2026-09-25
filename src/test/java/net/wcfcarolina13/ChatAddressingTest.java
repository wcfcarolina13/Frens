package net.wcfcarolina13;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the pure chat-addressing rules extracted from {@code Frens#resolveChatTargets}:
 * matching stays exactly as the original resolver behaved (normalize tokens, first broadcast
 * keyword or bot name wins), while prompt extraction fixes the leading-name quirk — a leading
 * name still yields the tail after it, but a name matched anywhere later now yields the full
 * trimmed message instead of the (possibly empty, possibly garbled) tail.
 */
class ChatAddressingTest {

    private static final List<String> JAKE_ONLY = List.of("Jake");

    private static ChatAddressing.Resolution resolved(String raw, List<String> names) {
        Optional<ChatAddressing.Resolution> resolution = ChatAddressing.resolve(raw, names);
        assertTrue(resolution.isPresent(), "expected an address match for: " + raw);
        return resolution.get();
    }

    // === Leading-name addressing: unchanged tail-prompt behavior ===

    @Test
    void leadingNameRoutesTheTailAsPrompt() {
        ChatAddressing.Resolution r = resolved("Jake come to the base", JAKE_ONLY);
        assertEquals(0, r.matchedNameIndex());
        assertFalse(r.broadcast());
        assertEquals("come to the base", r.prompt());
    }

    @Test
    void leadingNameMatchIsCaseAndPunctuationInsensitive() {
        ChatAddressing.Resolution r = resolved("jake, how are you?", JAKE_ONLY);
        assertEquals(0, r.matchedNameIndex());
        assertEquals("how are you?", r.prompt());
    }

    @Test
    void punctuationOnlyPrefixTokensStillCountAsLeading() {
        // "!!" normalizes to empty, so Jake is still the first meaningful token — tail prompt.
        ChatAddressing.Resolution r = resolved("!! Jake come here", JAKE_ONLY);
        assertEquals("come here", r.prompt());
    }

    @Test
    void bareNameYieldsAnEmptyPrompt() {
        ChatAddressing.Resolution r = resolved("Jake", JAKE_ONLY);
        assertEquals(0, r.matchedNameIndex());
        assertEquals("", r.prompt());
    }

    // === The quirk fix: non-leading name yields the full message, never the tail ===

    @Test
    void trailingNameRoutesTheFullMessage() {
        // Previously "Ping, Jake" produced an empty tail prompt and never routed anywhere.
        ChatAddressing.Resolution r = resolved("Ping, Jake", JAKE_ONLY);
        assertEquals(0, r.matchedNameIndex());
        assertFalse(r.broadcast());
        assertEquals("Ping, Jake", r.prompt());
    }

    @Test
    void midSentenceNameRoutesTheFullMessageNotTheGarbledTail() {
        // Previously this routed only "to come home" to Jake.
        ChatAddressing.Resolution r = resolved("can you tell Jake to come home", JAKE_ONLY);
        assertEquals("can you tell Jake to come home", r.prompt());
    }

    @Test
    void fullMessagePromptIsTrimmed() {
        ChatAddressing.Resolution r = resolved("  hello there Jake  ", JAKE_ONLY);
        assertEquals("hello there Jake", r.prompt());
    }

    // === Broadcast keywords ===

    @Test
    void leadingBotsKeywordBroadcastsWithTailPrompt() {
        ChatAddressing.Resolution r = resolved("bots follow me", JAKE_ONLY);
        assertTrue(r.broadcast());
        assertEquals(-1, r.matchedNameIndex());
        assertEquals("follow me", r.prompt());
    }

    @Test
    void leadingAllBotsPairBroadcastsWithTailPrompt() {
        ChatAddressing.Resolution r = resolved("all bots follow me", JAKE_ONLY);
        assertTrue(r.broadcast());
        assertEquals("follow me", r.prompt());
    }

    @Test
    void nonLeadingBroadcastKeywordCarriesTheFullMessage() {
        ChatAddressing.Resolution r = resolved("hello bots", JAKE_ONLY);
        assertTrue(r.broadcast());
        assertEquals("hello bots", r.prompt());
    }

    @Test
    void broadcastKeywordBeforeANameWinsAndKeepsTheNameInThePrompt() {
        ChatAddressing.Resolution r = resolved("bots Jake hi", JAKE_ONLY);
        assertTrue(r.broadcast());
        assertEquals("Jake hi", r.prompt());
    }

    @Test
    void nameBeforeABroadcastKeywordWins() {
        ChatAddressing.Resolution r = resolved("Jake bots hi", JAKE_ONLY);
        assertFalse(r.broadcast());
        assertEquals(0, r.matchedNameIndex());
        assertEquals("bots hi", r.prompt());
    }

    // === Name-list mapping ===

    @Test
    void matchedNameIndexPointsAtTheRightRegisteredBot() {
        ChatAddressing.Resolution r = resolved("Jake hi", List.of("Alex", "Jake"));
        assertEquals(1, r.matchedNameIndex());
    }

    // === Multi-name leading runs (group scenes) ===

    private static final List<String> JAKE_AND_SARA = List.of("Jake", "Sara");

    @Test
    void multiNameLeadingRunResolvesAllNames() {
        ChatAddressing.Resolution r = resolved("Jake and Sara, what do you think", JAKE_AND_SARA);
        assertEquals(List.of(0, 1), r.matchedNameIndices());
        assertFalse(r.broadcast());
        assertEquals("what do you think", r.prompt());
    }

    @Test
    void multiNameCommaOnlyRun() {
        ChatAddressing.Resolution r = resolved("Jake, Sara, come look at this", JAKE_AND_SARA);
        assertEquals(List.of(0, 1), r.matchedNameIndices());
        assertEquals("come look at this", r.prompt());
    }

    @Test
    void connectorWithoutSecondNameStopsExtension() {
        ChatAddressing.Resolution r = resolved("Jake and I went mining", JAKE_AND_SARA);
        assertEquals(List.of(0), r.matchedNameIndices());
        assertEquals("and I went mining", r.prompt());
    }

    @Test
    void singleNameCompatAccessorUnchanged() {
        ChatAddressing.Resolution r = resolved("Jake come here", JAKE_ONLY);
        assertEquals(0, r.matchedNameIndex());
        assertEquals(List.of(0), r.matchedNameIndices());
        assertEquals("come here", r.prompt());
    }

    @Test
    void midSentenceNameNeverStartsMultiRun() {
        // Non-leading match keeps today's behavior exactly: first name only, full-message prompt.
        ChatAddressing.Resolution r = resolved("can you help me, Jake and Sara", JAKE_AND_SARA);
        assertEquals(List.of(0), r.matchedNameIndices());
        assertEquals("can you help me, Jake and Sara", r.prompt());
    }

    @Test
    void broadcastKeywordStillWinsOverNames() {
        ChatAddressing.Resolution r = resolved("bots Jake and Sara hello", JAKE_AND_SARA);
        assertTrue(r.broadcast());
        assertTrue(r.matchedNameIndices().isEmpty());
        assertEquals(-1, r.matchedNameIndex());
    }

    @Test
    void duplicateNameInRunKeptOnce() {
        ChatAddressing.Resolution r = resolved("Jake and Jake, hi", JAKE_ONLY);
        assertEquals(List.of(0), r.matchedNameIndices());
        assertEquals("hi", r.prompt());
    }

    // === R2: soft broadcast ===

    private static final List<String> JAKE_AND_WREN = List.of("Jake", "Wren");

    @Test
    void everyoneIsASoftBroadcastWithTailPrompt() {
        ChatAddressing.Resolution r = resolved("everyone meet at the farm", JAKE_AND_WREN);
        assertTrue(r.broadcast());
        assertTrue(r.softBroadcast());
        assertTrue(r.matchedNameIndices().isEmpty());
        assertEquals("meet at the farm", r.prompt());
    }

    @Test
    void eachSoftBroadcastWordResolves() {
        for (String line : List.of("good night everybody", "yall ready to go", "y'all ready to go",
                "morning guys, sun is up", "you two grab the shovels", "are you both hungry")) {
            ChatAddressing.Resolution r = resolved(line, JAKE_AND_WREN);
            assertTrue(r.broadcast(), line);
            assertTrue(r.softBroadcast(), line);
            assertFalse(r.otherAddressee(), line);
        }
    }

    @Test
    void nonLeadingSoftBroadcastCarriesTheFullMessage() {
        assertEquals("good night everybody", resolved("good night everybody", JAKE_AND_WREN).prompt());
    }

    @Test
    void youTwoConsumesBothTokensForTheTailPrompt() {
        assertEquals("grab the shovels", resolved("you two, grab the shovels", JAKE_AND_WREN).prompt());
    }

    @Test
    void guysAfterADeterminerIsNotAnAddress() {
        assertTrue(ChatAddressing.resolve("those guys stole my carrots", JAKE_AND_WREN).isEmpty());
        assertTrue(ChatAddressing.resolve("the guys at the village moved", JAKE_AND_WREN).isEmpty());
        assertTrue(ChatAddressing.resolve("some guys left a chest", JAKE_AND_WREN).isEmpty());
    }

    @Test
    void youWithoutTwoOrBothIsNotAnAddress() {
        assertTrue(ChatAddressing.resolve("you too", JAKE_AND_WREN).isEmpty());
        assertTrue(ChatAddressing.resolve("did you hear that thunder", JAKE_AND_WREN).isEmpty());
    }

    @Test
    void explicitBotsKeywordIsAHardBroadcast() {
        ChatAddressing.Resolution r = resolved("bots stack the logs", JAKE_AND_WREN);
        assertTrue(r.broadcast());
        assertFalse(r.softBroadcast());
        ChatAddressing.Resolution all = resolved("all bots stack the logs", JAKE_AND_WREN);
        assertTrue(all.broadcast());
        assertFalse(all.softBroadcast());
    }

    @Test
    void leadingBotNameBeatsALaterSoftWord() {
        ChatAddressing.Resolution r = resolved("Jake, tell everyone dinner is ready", JAKE_AND_WREN);
        assertEquals(List.of(0), r.matchedNameIndices());
        assertFalse(r.broadcast());
        assertFalse(r.softBroadcast());
    }

    @Test
    void earlierSoftWordBeatsALaterBotName() {
        ChatAddressing.Resolution r = resolved("everyone, Jake found iron", JAKE_AND_WREN);
        assertTrue(r.softBroadcast());
        assertTrue(r.matchedNameIndices().isEmpty());
    }

    @Test
    void aBotNamedLikeASoftWordIsAddressedAsThatBot() {
        ChatAddressing.Resolution r = resolved("guys come here", List.of("Guys"));
        assertEquals(0, r.matchedNameIndex());
        assertFalse(r.softBroadcast());
    }

    @Test
    void softBroadcastDemotionTable() {
        // Honoured only when the sender is the only human online and the party path can route.
        assertFalse(ChatAddressing.shouldDemoteSoftBroadcast(true, false, true));
        assertTrue(ChatAddressing.shouldDemoteSoftBroadcast(true, true, true));
        assertTrue(ChatAddressing.shouldDemoteSoftBroadcast(true, false, false));
        assertTrue(ChatAddressing.shouldDemoteSoftBroadcast(true, true, false));
        // Explicit keywords are never demoted.
        assertFalse(ChatAddressing.shouldDemoteSoftBroadcast(false, true, false));
    }

    // === R3: leading other-human name ===

    private static final List<String> MARLOW = List.of("Marlow");

    private static ChatAddressing.Resolution resolved(String raw, List<String> bots, List<String> humans) {
        Optional<ChatAddressing.Resolution> resolution = ChatAddressing.resolve(raw, bots, humans);
        assertTrue(resolution.isPresent(), "expected a resolution for: " + raw);
        return resolution.get();
    }

    @Test
    void leadingOtherHumanNameIsAnOtherAddressee() {
        ChatAddressing.Resolution r = resolved("Marlow, want to trade wool?", JAKE_AND_WREN, MARLOW);
        assertTrue(r.otherAddressee());
        assertTrue(r.matchedNameIndices().isEmpty());
        assertFalse(r.broadcast());
        assertFalse(r.softBroadcast());
    }

    @Test
    void otherAddresseeNeedsNoCommaForAnOrdinaryName() {
        assertTrue(resolved("marlow want to trade", JAKE_AND_WREN, MARLOW).otherAddressee());
        assertTrue(resolved("@Marlow hi", JAKE_AND_WREN, MARLOW).otherAddressee());
    }

    @Test
    void otherAddresseeSwallowsALaterBotMention() {
        ChatAddressing.Resolution r = resolved("Marlow, ask Jake for wood", JAKE_AND_WREN, MARLOW);
        assertTrue(r.otherAddressee());
        assertTrue(r.matchedNameIndices().isEmpty());
    }

    @Test
    void nonLeadingHumanNameIsNotAnOtherAddressee() {
        assertTrue(ChatAddressing.resolve("want to trade Marlow", JAKE_AND_WREN, MARLOW).isEmpty());
        ChatAddressing.Resolution r = resolved("tell Marlow hi, Jake", JAKE_AND_WREN, MARLOW);
        assertFalse(r.otherAddressee());
        assertEquals(0, r.matchedNameIndex());
    }

    @Test
    void humanNameMatchIsExactNotPrefix() {
        ChatAddressing.Resolution r = resolved("Marlowe, hi Jake", JAKE_AND_WREN, MARLOW);
        assertFalse(r.otherAddressee());
        assertEquals(0, r.matchedNameIndex());
    }

    @Test
    void shortHumanNameNeedsATrailingCommaOrColon() {
        List<String> al = List.of("Al");
        assertTrue(ChatAddressing.resolve("al come look", JAKE_AND_WREN, al).isEmpty());
        assertTrue(resolved("Al, come look", JAKE_AND_WREN, al).otherAddressee());
        assertTrue(resolved("Al: come look", JAKE_AND_WREN, al).otherAddressee());
    }

    @Test
    void stopwordHumanNameNeedsATrailingCommaOrColon() {
        List<String> hey = List.of("Hey");
        ChatAddressing.Resolution greeting = resolved("hey Jake come here", JAKE_AND_WREN, hey);
        assertFalse(greeting.otherAddressee());
        assertEquals(0, greeting.matchedNameIndex());
        assertTrue(resolved("Hey, come here", JAKE_AND_WREN, hey).otherAddressee());
    }

    @Test
    void botNameWinsOverAnIdenticalHumanName() {
        ChatAddressing.Resolution r = resolved("Jake come here", JAKE_AND_WREN, List.of("Jake"));
        assertFalse(r.otherAddressee());
        assertEquals(0, r.matchedNameIndex());
    }

    @Test
    void senderExclusionIsTheCallersJob() {
        // The resolver cannot tell who is speaking: a caller that forgot to drop the sender from
        // the humans list would flag the sender's own name. Frens passes other humans only.
        assertTrue(ChatAddressing.resolve("Marlow, hi", JAKE_AND_WREN, List.of()).isEmpty());
        assertTrue(resolved("Marlow, hi", JAKE_AND_WREN, MARLOW).otherAddressee());
    }

    @Test
    void twoArgOverloadMatchesThreeArgWithNoHumans() {
        for (String line : List.of("Jake come here", "everyone come here", "Marlow, hi Jake", "hello world")) {
            assertEquals(ChatAddressing.resolve(line, JAKE_AND_WREN),
                    ChatAddressing.resolve(line, JAKE_AND_WREN, List.of()), line);
        }
        assertEquals(ChatAddressing.resolve("Jake, status report", JAKE_AND_WREN),
                ChatAddressing.resolve("Jake, status report", JAKE_AND_WREN, null));
    }

    // === R4: trailing vocative ===

    @Test
    void trailingCommaVocativeBeatsAnEarlierMidSentenceName() {
        ChatAddressing.Resolution r = resolved("can Jake bring the wool, Wren", JAKE_AND_WREN);
        assertEquals(List.of(1), r.matchedNameIndices());
        assertEquals("can Jake bring the wool, Wren", r.prompt());
    }

    @Test
    void trailingNameWithoutACommaDoesNotSwitch() {
        assertEquals(List.of(0), resolved("can Jake bring the wool Wren", JAKE_AND_WREN).matchedNameIndices());
    }

    @Test
    void trailingVocativeToleratesTrailingPunctuationTokens() {
        assertEquals(List.of(1), resolved("can Jake bring the wool, Wren ?", JAKE_AND_WREN).matchedNameIndices());
    }

    @Test
    void leadingNameStillBeatsATrailingVocative() {
        assertEquals(List.of(0), resolved("Jake, bring the wool, Wren", JAKE_AND_WREN).matchedNameIndices());
    }

    // === R5: comma runs ===

    @Test
    void nameWithoutConnectorOrPunctuationDoesNotJoinTheRun() {
        ChatAddressing.Resolution r = resolved("Jake Wren come here", JAKE_AND_WREN);
        assertEquals(List.of(0), r.matchedNameIndices());
        assertEquals("Wren come here", r.prompt());
    }

    @Test
    void commaSeparatedNamesAtTheEndStillAddressBoth() {
        ChatAddressing.Resolution r = resolved("Jake, Wren", JAKE_AND_WREN);
        assertEquals(List.of(0, 1), r.matchedNameIndices());
        assertEquals("", r.prompt());
        assertEquals(List.of(0, 1), resolved("Jake, Wren !", JAKE_AND_WREN).matchedNameIndices());
    }

    @Test
    void andConnectedNamesStillAddressBoth() {
        ChatAddressing.Resolution r = resolved("Jake and Wren come here", JAKE_AND_WREN);
        assertEquals(List.of(0, 1), r.matchedNameIndices());
        assertEquals("come here", r.prompt());
    }

    @Test
    void unpunctuatedSecondNameAfterACommaStaysInThePrompt() {
        ChatAddressing.Resolution r = resolved("Jake, Wren come here", JAKE_AND_WREN);
        assertEquals(List.of(0), r.matchedNameIndices());
        assertEquals("Wren come here", r.prompt());
    }

    // === No-match and degenerate inputs ===

    @Test
    void unaddressedChatDoesNotResolve() {
        assertTrue(ChatAddressing.resolve("hello world", JAKE_ONLY).isEmpty());
    }

    @Test
    void nullEmptyAndBlankInputsDoNotResolve() {
        assertTrue(ChatAddressing.resolve(null, JAKE_ONLY).isEmpty());
        assertTrue(ChatAddressing.resolve("", JAKE_ONLY).isEmpty());
        assertTrue(ChatAddressing.resolve("   ", JAKE_ONLY).isEmpty());
        assertTrue(ChatAddressing.resolve("Jake hi", List.of()).isEmpty());
        assertTrue(ChatAddressing.resolve("Jake hi", null).isEmpty());
    }
}
