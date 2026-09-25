package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the torch-hold light hysteresis. No Minecraft types. */
class TorchHoldPolicyTest {

    /** Mirrors the light gate in BotTorchHoldService.evalHold: reject when light > threshold. */
    private static boolean lightAllowsHold(int light, boolean holding) {
        return light <= TorchHoldPolicy.lightThreshold(holding);
    }

    @Test
    void thresholdsAreSevenToAcquireAndElevenToKeep() {
        assertEquals(7, TorchHoldPolicy.lightThreshold(false));
        assertEquals(11, TorchHoldPolicy.lightThreshold(true));
        assertEquals(TorchHoldPolicy.ACQUIRE_MAX_LIGHT, TorchHoldPolicy.lightThreshold(false));
        assertEquals(TorchHoldPolicy.RELEASE_MAX_LIGHT, TorchHoldPolicy.lightThreshold(true));
    }

    @Test
    void bandIsPositiveSoTheGateCannotFlap() {
        assertTrue(TorchHoldPolicy.RELEASE_MAX_LIGHT > TorchHoldPolicy.ACQUIRE_MAX_LIGHT);
    }

    @Test
    void notHoldingOnlyAcquiresAtSevenOrDarker() {
        for (int light = 0; light <= 7; light++) {
            assertTrue(lightAllowsHold(light, false), "should acquire at light=" + light);
        }
        for (int light = 8; light <= 15; light++) {
            assertFalse(lightAllowsHold(light, false), "should not acquire at light=" + light);
        }
    }

    @Test
    void holdingKeepsTheTorchThroughElevenAndReleasesAtTwelve() {
        for (int light = 0; light <= 11; light++) {
            assertTrue(lightAllowsHold(light, true), "should keep at light=" + light);
        }
        for (int light = 12; light <= 15; light++) {
            assertFalse(lightAllowsHold(light, true), "should release at light=" + light);
        }
    }

    @Test
    void fieldBandEightToElevenIsKeptButNeverAcquired() {
        // Jake's 1.1.215/216 readings clustered at 8–11: the flapping band.
        for (int light = 8; light <= 11; light++) {
            assertFalse(lightAllowsHold(light, false), "must not start holding at light=" + light);
            assertTrue(lightAllowsHold(light, true), "must not drop the torch at light=" + light);
        }
    }

    @Test
    void walkThroughTheFieldTraceHoldsUntilTwelve() {
        // 6 -> 10 -> 12 inside one second was the observed yield; with the band the torch
        // survives 10 and is put away only at 12, then is not re-raised at 8.
        int[] trace = {4, 6, 10, 11, 12, 8, 7, 9};
        boolean holding = false;
        List<Boolean> states = new ArrayList<>();
        for (int light : trace) {
            holding = lightAllowsHold(light, holding);
            states.add(holding);
        }
        assertEquals(List.of(true, true, true, true, false, false, true, true), states);
    }

    @Test
    void slotToPersistKeepsCurrentWhenNotHolding() {
        assertEquals(4, TorchHoldPolicy.slotToPersist(-1, -1, 4));
        assertEquals(0, TorchHoldPolicy.slotToPersist(-1, -1, 0));
    }

    @Test
    void slotToPersistReturnsPreTorchSlotWhileTheHeldTorchIsSelected() {
        // Bot had slot 2 (a sword) selected; the service put the torch up in slot 6.
        assertEquals(2, TorchHoldPolicy.slotToPersist(2, 6, 6));
        assertEquals(0, TorchHoldPolicy.slotToPersist(0, 8, 8));
    }

    @Test
    void slotToPersistLetsAForeignSelectionWinBeforeTheTickDropsTheHold() {
        // Another service selected slot 1 in the few ticks before the hold's foreign-swap check.
        assertEquals(1, TorchHoldPolicy.slotToPersist(2, 6, 1));
        // Even when the foreign selection happens to be the pre-torch slot itself.
        assertEquals(2, TorchHoldPolicy.slotToPersist(2, 6, 2));
    }

    @Test
    void slotToPersistIgnoresAHalfRecordedHold() {
        assertEquals(6, TorchHoldPolicy.slotToPersist(2, -1, 6));
        assertEquals(6, TorchHoldPolicy.slotToPersist(-1, 6, 6));
    }
}
