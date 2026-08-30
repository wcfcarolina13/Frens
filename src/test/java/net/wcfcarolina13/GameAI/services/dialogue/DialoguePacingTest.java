package net.wcfcarolina13.GameAI.services.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialoguePacingTest {

    @Test
    void multiplierEndpointsAndMidpoint() {
        assertEquals(8.0, DialoguePacing.multiplier(0), 1e-9);
        assertEquals(1.0, DialoguePacing.multiplier(50), 1e-9);
        assertEquals(0.125, DialoguePacing.multiplier(100), 1e-9);
        assertEquals(8.0, DialoguePacing.multiplier(-20), 1e-9, "clamped below");
        assertEquals(0.125, DialoguePacing.multiplier(140), 1e-9, "clamped above");
    }

    @Test
    void multiplierIsMonotonicallyDecreasing() {
        double previous = Double.MAX_VALUE;
        for (int rate = 0; rate <= 100; rate++) {
            double m = DialoguePacing.multiplier(rate);
            assertTrue(m < previous, "rate " + rate);
            previous = m;
        }
    }

    @Test
    void cooldownScalesAndChanceInverselyScalesWithClamp() {
        assertEquals(480_000L, DialoguePacing.scaledCooldown(0, 60_000L));
        assertEquals(60_000L, DialoguePacing.scaledCooldown(50, 60_000L));
        assertEquals(7_500L, DialoguePacing.scaledCooldown(100, 60_000L));
        assertEquals(0.075, DialoguePacing.scaledChance(0, 0.6), 1e-9);
        assertEquals(0.6, DialoguePacing.scaledChance(50, 0.6), 1e-9);
        assertEquals(1.0, DialoguePacing.scaledChance(100, 0.6), 1e-9, "clamped to 1");
        assertEquals(0.0, DialoguePacing.scaledChance(100, 0.0), 1e-9);
    }

    @Test
    void describeReadsAsAHumanBand() {
        assertEquals("every ~8–15 min", DialoguePacing.describe(50, 8 * 60_000L, 15 * 60_000L));
        assertEquals("every ~1–2 min", DialoguePacing.describe(100, 8 * 60_000L, 15 * 60_000L));
        assertEquals("every ~64–120 min", DialoguePacing.describe(0, 8 * 60_000L, 15 * 60_000L));
        assertEquals("every ~8–15 s", DialoguePacing.describe(100, 60_000L, 120_000L));
    }
}
