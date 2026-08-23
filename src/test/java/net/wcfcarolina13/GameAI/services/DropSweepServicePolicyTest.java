package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropSweepServicePolicyTest {

    @Test
    void backgroundSweepIsSuppressedWhileTaskIsActive() {
        assertTrue(DropSweepService.shouldSuppressBackgroundSweep(false, true));
    }

    @Test
    void commandDrivenSweepIsNotSuppressedByActiveTaskPolicy() {
        assertFalse(DropSweepService.shouldSuppressBackgroundSweep(true, true));
    }

    @Test
    void backgroundSweepIsAllowedWhenNoTaskIsActive() {
        assertFalse(DropSweepService.shouldSuppressBackgroundSweep(false, false));
    }
}
