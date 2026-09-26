package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.1.223 conversation smoothing: only a line spoken TO the owner ends an ambient scene; a line
 * merely ABOUT them is ordinary bot-to-bot talk the other bot should get to answer.
 */
class OwnerAddressPolicyTest {

    private static final String OWNER = "RotiWokeman";

    // --- field lines from 2026-09-25: third-person mentions, never an address ---

    @Test
    void aQuestionAboutTheOwnerToAnotherBotIsNotAnAddress() {
        assertFalse(OwnerAddressPolicy.isVocative(
                "Hey, Jake, what was RotiWokeman looking for the other day?", OWNER));
    }

    @Test
    void aPossessiveOrContractedMentionIsNotAnAddress() {
        assertFalse(OwnerAddressPolicy.isVocative(
                "RotiWokeman's not even paying attention to us, is he?", OWNER));
        assertFalse(OwnerAddressPolicy.isVocative(
                "Hey, Jake, don't rub it in, I know Roti's got a short fuse.", OWNER));
        assertFalse(OwnerAddressPolicy.isVocative("Roti’s off again.", OWNER), "curly apostrophe");
    }

    @Test
    void theOwnerAsSentenceSubjectIsNotAnAddress() {
        assertFalse(OwnerAddressPolicy.isVocative("RotiWokeman once said we should build a wall.", OWNER));
        assertFalse(OwnerAddressPolicy.isVocative("Jake, Roti wants more wood.", OWNER));
        assertFalse(OwnerAddressPolicy.isVocative("I think Roti went home.", OWNER));
    }

    // --- genuine addresses ---

    @Test
    void aGreetingThenTheNameIsAnAddress() {
        assertTrue(OwnerAddressPolicy.isVocative("Morning, Roti. How's the sleep going?", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("Hey Roti!", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("Good morning Roti, sleep well?", OWNER));
    }

    @Test
    void theNameClosingASentenceAfterACommaIsAnAddress() {
        assertTrue(OwnerAddressPolicy.isVocative("I'm trying my best, RotiWokeman.", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("What do you think, Roti?", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("Thanks, Roti", OWNER), "end of text counts");
    }

    @Test
    void theNameOpeningASentenceBeforePunctuationIsAnAddress() {
        assertTrue(OwnerAddressPolicy.isVocative("Roti, look at this!", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("Nice haul. Roti! Over here.", OWNER));
    }

    @Test
    void theNameSetOffByCommasMidSentenceIsAnAddress() {
        assertTrue(OwnerAddressPolicy.isVocative("Well, Roti, I think we're lost.", OWNER));
    }

    // --- matching rules ---

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(OwnerAddressPolicy.isVocative("hey roti!", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("ROTIWOKEMAN, come here.", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("Roti, look.", "rotiwokeman"), "normalised owner accepted");
    }

    @Test
    void theNameInsideAnotherWordNeverMatches() {
        assertFalse(OwnerAddressPolicy.isVocative("Rotisserie, anyone?", OWNER));
        assertFalse(OwnerAddressPolicy.isVocative("Hey, try the rotisserie!", OWNER));
    }

    @Test
    void abbreviationsNeedFourLettersLikeAddressesOwner() {
        assertFalse(OwnerAddressPolicy.isVocative("Rot, look.", OWNER));
        assertTrue(OwnerAddressPolicy.isVocative("Roti, look.", OWNER));
    }

    @Test
    void underscoreNamesStayOneToken() {
        assertTrue(OwnerAddressPolicy.isVocative("Roti_Wokeman, look at this!", "Roti_Wokeman"));
        assertFalse(OwnerAddressPolicy.isVocative("Roti_Wokeman's axe is dull.", "Roti_Wokeman"));
    }

    @Test
    void blankInputsAreNeverAnAddress() {
        assertFalse(OwnerAddressPolicy.isVocative("", OWNER));
        assertFalse(OwnerAddressPolicy.isVocative(null, OWNER));
        assertFalse(OwnerAddressPolicy.isVocative("Roti, look.", ""));
        assertFalse(OwnerAddressPolicy.isVocative("Roti, look.", null));
    }
}
