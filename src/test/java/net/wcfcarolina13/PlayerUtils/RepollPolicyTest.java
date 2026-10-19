package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepollPolicyTest {

    @Test
    void equalRequiredTicksKeepProgress() {
        assertFalse(RepollPolicy.shouldResetProgress(60, 60));
    }

    @Test
    void slowerToolKeepsProgress() {
        assertFalse(RepollPolicy.shouldResetProgress(30, 60));
    }

    @Test
    void fasterToolResetsProgress() {
        assertTrue(RepollPolicy.shouldResetProgress(60, 15));
    }

    @Test
    void fasterToolAlwaysSwaps() {
        assertTrue(RepollPolicy.shouldSwap(1.0f, 4.0f, false));
        assertTrue(RepollPolicy.shouldSwap(1.0f, 4.0f, true));
    }

    @Test
    void equalSpeedSwapsUntilUndone() {
        assertTrue(RepollPolicy.shouldSwap(1.0f, 1.0f, false));
        assertFalse(RepollPolicy.shouldSwap(1.0f, 1.0f, true));
    }

    @Test
    void slowerSwapStopsOnceUndone() {
        assertTrue(RepollPolicy.shouldSwap(2.0f, 1.0f, false));
        assertFalse(RepollPolicy.shouldSwap(2.0f, 1.0f, true));
    }
}
