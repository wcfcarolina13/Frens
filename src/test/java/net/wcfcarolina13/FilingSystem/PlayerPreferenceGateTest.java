package net.wcfcarolina13.FilingSystem;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPreferenceGateTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String GEAR = "preserveExpensiveGear";
    private static final String FOOD = "autoAcceptPreciousFoods";

    @Test
    void unchangedRequestIsIgnoredAndNeverDirties() {
        PlayerPreferenceGate gate = new PlayerPreferenceGate();
        for (int i = 0; i < 100; i++) {
            PlayerPreferenceGate.Admission a = gate.admit(ALICE, GEAR, true, true, 1_000L + i);
            assertEquals(PlayerPreferenceGate.Decision.UNCHANGED, a.decision());
            assertFalse(a.risingEdge());
        }
        assertFalse(gate.flushDue(10_000L));
        assertFalse(gate.drainForShutdown());
    }

    @Test
    void risingEdgeOnlyOnFalseToTrue() {
        PlayerPreferenceGate gate = new PlayerPreferenceGate();
        PlayerPreferenceGate.Admission on = gate.admit(ALICE, GEAR, false, true, 0L);
        assertTrue(on.accepted());
        assertTrue(on.risingEdge());
        PlayerPreferenceGate.Admission off = gate.admit(ALICE, GEAR, true, false, 1_000L);
        assertTrue(off.accepted());
        assertFalse(off.risingEdge());
    }

    @Test
    void alternatingFloodIsBoundedByTheInterval() {
        PlayerPreferenceGate gate = new PlayerPreferenceGate();
        boolean value = false;
        int accepted = 0;
        // 2 s of packets every 10 ms, each asking to flip the current value.
        for (long t = 0; t < 2_000L; t += 10L) {
            PlayerPreferenceGate.Admission a = gate.admit(ALICE, GEAR, value, !value, t);
            if (a.accepted()) {
                value = !value;
                accepted++;
            } else {
                assertEquals(PlayerPreferenceGate.Decision.RATE_LIMITED, a.decision());
            }
        }
        assertEquals(4, accepted); // t = 0, 500, 1000, 1500
    }

    @Test
    void playersAndPreferencesAreRateLimitedIndependently() {
        PlayerPreferenceGate gate = new PlayerPreferenceGate();
        assertTrue(gate.admit(ALICE, GEAR, false, true, 0L).accepted());
        assertEquals(PlayerPreferenceGate.Decision.RATE_LIMITED, gate.admit(ALICE, GEAR, true, false, 100L).decision());
        assertTrue(gate.admit(BOB, GEAR, false, true, 100L).accepted());
        assertTrue(gate.admit(ALICE, FOOD, false, true, 100L).accepted());
    }

    @Test
    void manyAcceptedChangesCoalesceIntoFewSaves() {
        PlayerPreferenceGate gate = new PlayerPreferenceGate();
        int saves = 0;
        boolean alice = false;
        boolean bob = false;
        // 4 s of ticks (50 ms), with both players toggling every 500 ms.
        for (long t = 0; t < 4_000L; t += 50L) {
            if (t % 500L == 0L) {
                if (gate.admit(ALICE, GEAR, alice, !alice, t).accepted()) alice = !alice;
                if (gate.admit(BOB, FOOD, bob, !bob, t).accepted()) bob = !bob;
            }
            if (gate.flushDue(t)) {
                saves++;
            }
        }
        // 16 accepted changes -> first flush immediately, then at most one per 2 s.
        assertEquals(2, saves);
        // The changes after the last flush are still pending for the shutdown flush.
        assertTrue(gate.drainForShutdown());
        assertFalse(gate.drainForShutdown());
    }

    @Test
    void firstChangeAfterIdleFlushesOnTheNextTick() {
        PlayerPreferenceGate gate = new PlayerPreferenceGate();
        assertTrue(gate.admit(ALICE, GEAR, false, true, 10_000L).accepted());
        assertTrue(gate.flushDue(10_050L));
        assertFalse(gate.flushDue(10_100L)); // nothing dirty
    }

    @Test
    void shutdownDrainResetsRateLimitHistory() {
        PlayerPreferenceGate gate = new PlayerPreferenceGate();
        assertTrue(gate.admit(ALICE, GEAR, false, true, 0L).accepted());
        assertTrue(gate.drainForShutdown());
        // A reloaded world (clock may be anywhere) starts clean.
        assertTrue(gate.admit(ALICE, GEAR, true, false, 10L).accepted());
    }
}
