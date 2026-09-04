package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the RAM-shortfall helper behind the model manager's warning. */
class OllamaModelInstallerRamTest {

    private static final long GIB = 1073741824L;

    private static OllamaModelInstaller.KnownModel model(double recommendedRamGb) {
        return new OllamaModelInstaller.KnownModel("test:tag", "Test", "desc", 4.6, recommendedRamGb);
    }

    @Test
    void reportsWholeGbShortfallWhenRamIsBelowRecommendation() {
        assertEquals(4, OllamaModelInstaller.ramShortfallGb(model(12), 8 * GIB));
    }

    @Test
    void unknownRamIsNotAWarning() {
        assertEquals(0, OllamaModelInstaller.ramShortfallGb(model(12), -1));
        assertEquals(0, OllamaModelInstaller.ramShortfallGb(model(12), 0));
    }

    @Test
    void sufficientRamIsNotAWarning() {
        assertEquals(0, OllamaModelInstaller.ramShortfallGb(model(12), 16 * GIB));
        assertEquals(0, OllamaModelInstaller.ramShortfallGb(model(12), 12 * GIB));
    }

    @Test
    void partialShortfallRoundsUp() {
        assertEquals(1, OllamaModelInstaller.ramShortfallGb(model(12), 12 * GIB - 1));
    }

    @Test
    void nullModelIsNotAWarning() {
        assertEquals(0, OllamaModelInstaller.ramShortfallGb(null, 8 * GIB));
    }
}
