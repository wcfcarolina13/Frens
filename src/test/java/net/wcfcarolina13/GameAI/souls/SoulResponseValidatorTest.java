package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulResponseValidatorTest {

    private final SoulResponseValidator validator = new SoulResponseValidator();

    // === Mandated tests (verbatim from brief) ===

    @Test
    void stripsHiddenReasoningAndSpeakerPrefix() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "<think>private chain</think>\nJake: We should head home.", "Jake");
        assertTrue(result.accepted());
        assertEquals("We should head home.", result.text());
    }

    @Test
    void rejectsToolSyntaxAndExcessiveOutput() {
        assertFalse(validator.validate("```json\n{\"tool\":\"follow\"}\n```", "Jake").accepted());
        assertFalse(validator.validate("x".repeat(1201), "Jake").accepted());
    }

    // === Additional coverage ===

    @Test
    void rejectsBlankText() {
        SoulResponseValidator.ValidationResult result = validator.validate("   \n\t  ", "Jake");
        assertFalse(result.accepted());
        assertEquals(SoulTypes.FailureCode.MALFORMED, result.failureCode());
    }

    @Test
    void rejectsNullText() {
        SoulResponseValidator.ValidationResult result = validator.validate(null, "Jake");
        assertFalse(result.accepted());
        assertEquals(SoulTypes.FailureCode.MALFORMED, result.failureCode());
    }

    @Test
    void stripsAnalysisBlock() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "<analysis>internal scratch notes</analysis>\nHello there.", "Jake");
        assertTrue(result.accepted());
        assertEquals("Hello there.", result.text());
    }

    @Test
    void stripsControlCharactersButKeepsNewlineAndTab() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "Hello\u0007World\nSecond\tLine", "Jake");
        assertTrue(result.accepted());
        assertEquals("HelloWorld\nSecond\tLine", result.text());
    }

    @Test
    void rejectsNulCharacters() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "Hello\u0000World", "Jake");
        assertFalse(result.accepted());
        assertEquals(SoulTypes.FailureCode.MALFORMED, result.failureCode());
    }

    @Test
    void stripsSectionSignFormattingCodes() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "§cWatch out§r for zombies!", "Jake");
        assertTrue(result.accepted());
        assertEquals("Watch out for zombies!", result.text());
    }

    @Test
    void ordinaryMultilineProsePassesThroughIntact() {
        String prose = "We should gather wood.\n\nThen we can build a shelter before nightfall.";
        SoulResponseValidator.ValidationResult result = validator.validate(prose, "Jake");
        assertTrue(result.accepted());
        assertEquals(prose, result.text());
    }

    @Test
    void collapsesMoreThanTwoConsecutiveBlankLines() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "First paragraph.\n\n\n\n\nSecond paragraph.", "Jake");
        assertTrue(result.accepted());
        assertEquals("First paragraph.\n\n\nSecond paragraph.", result.text());
    }

    @Test
    void doesNotStripUnrelatedSpeakerLabel() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "Steve: Watch out!", "Jake");
        assertTrue(result.accepted());
        assertEquals("Steve: Watch out!", result.text());
    }

    @Test
    void acceptsCleanedOutputAtExactLimit() {
        SoulResponseValidator.ValidationResult result = validator.validate("x".repeat(1200), "Jake");
        assertTrue(result.accepted());
        assertEquals(1200, result.text().length());
    }

    // === Review follow-up: unclosed reasoning tags must not leak (Finding 1) ===

    @Test
    void rejectsUnclosedThinkTagWithNoPriorProse() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "<think>partial reasoning that never closes", "Jake");
        assertFalse(result.accepted());
        assertEquals(SoulTypes.FailureCode.MALFORMED, result.failureCode());
    }

    @Test
    void acceptsProseBeforeUnclosedThinkTag() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "Fine by me. <think>truncated reasoning that never closes", "Jake");
        assertTrue(result.accepted());
        assertEquals("Fine by me.", result.text());
    }

    @Test
    void rejectsUnclosedUppercaseThinkTagVariant() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "<THINK>partial reasoning that never closes", "Jake");
        assertFalse(result.accepted());
        assertEquals(SoulTypes.FailureCode.MALFORMED, result.failureCode());
    }

    // === Review follow-up: fenced payload check must not over-reject prose (Finding 2) ===

    @Test
    void acceptsInlineTripleBacktickMidSentence() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "That's like using ```code``` markers randomly.", "Jake");
        assertTrue(result.accepted());
        assertEquals("That's like using ```code``` markers randomly.", result.text());
    }

    // === Review follow-up minors ===

    @Test
    void stripsTrailingLoneSectionSign() {
        SoulResponseValidator.ValidationResult result = validator.validate("Watch out§", "Jake");
        assertTrue(result.accepted());
        assertEquals("Watch out", result.text());
    }

    @Test
    void stripsAnalysisBlockCaseInsensitiveMixedCase() {
        SoulResponseValidator.ValidationResult result = validator.validate(
                "<Analysis>internal notes</Analysis>\nHello there.", "Jake");
        assertTrue(result.accepted());
        assertEquals("Hello there.", result.text());
    }
}
