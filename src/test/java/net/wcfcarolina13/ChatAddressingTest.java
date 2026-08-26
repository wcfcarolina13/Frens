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
