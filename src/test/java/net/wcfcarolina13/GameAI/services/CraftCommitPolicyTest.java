package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions around one craft's single server-thread commit. No net.minecraft types. */
class CraftCommitPolicyTest {

    @Test
    void aHopThatNeverRanIsATimeoutAndThePackIsUntouched() {
        assertEquals(CraftCommitPolicy.Outcome.TIMED_OUT, CraftCommitPolicy.outcome(null));
        assertFalse(CraftCommitPolicy.needsSecondHop(null), "nothing was made, so nothing is left to place");
        assertEquals(0, CraftCommitPolicy.crafted(CraftCommitPolicy.Outcome.TIMED_OUT, 12));
    }

    @Test
    void aShortConsumeAbortsBeforeAnyOutput() {
        CraftCommitPolicy.HopResult hop = CraftCommitPolicy.HopResult.ABORTED;
        assertEquals(CraftCommitPolicy.Outcome.ABORTED, CraftCommitPolicy.outcome(hop));
        assertFalse(CraftCommitPolicy.needsSecondHop(hop));
        assertEquals(0, CraftCommitPolicy.crafted(CraftCommitPolicy.Outcome.ABORTED, 3));
        assertEquals(0, hop.leftover(), "an aborted hop inserted nothing");
    }

    @Test
    void anInsertThatFitsIsCraftedInOneHop() {
        CraftCommitPolicy.HopResult hop = CraftCommitPolicy.HopResult.done(0);
        assertEquals(CraftCommitPolicy.Outcome.CRAFTED, CraftCommitPolicy.outcome(hop));
        assertFalse(CraftCommitPolicy.needsSecondHop(hop));
        assertEquals(4, CraftCommitPolicy.crafted(CraftCommitPolicy.Outcome.CRAFTED, 4));
    }

    @Test
    void aLeftoverIsStillCraftedButAsksForTheForcedRoomAndSecondHop() {
        CraftCommitPolicy.HopResult hop = CraftCommitPolicy.HopResult.done(5);
        assertEquals(CraftCommitPolicy.Outcome.CRAFTED, CraftCommitPolicy.outcome(hop),
                "the ingredients are spent and part of the output is in: the craft happened");
        assertTrue(CraftCommitPolicy.needsSecondHop(hop));
        assertEquals(5, hop.leftover());
        assertEquals(0, CraftCommitPolicy.HopResult.done(-3).leftover(), "a negative count never reads as leftover");
    }

    @Test
    void theHopTimeoutIsGenerousSoATimeoutMeansAStalledServer() {
        assertTrue(CraftCommitPolicy.HOP_TIMEOUT_MS >= 5000L);
    }

    @Test
    void craftedNeverReportsANegativeCount() {
        assertEquals(0, CraftCommitPolicy.crafted(CraftCommitPolicy.Outcome.CRAFTED, -1));
    }

    // ── log-to-plank conversions ────────────────────────────────────────────────────────────

    @Test
    void takingTheWholeLogStackLeavesItsSlotForPlanksThatFitOneStack() {
        assertFalse(CraftCommitPolicy.roomNeededBeforeHop(3, 3, 12, 64), "3 logs out, 12 planks into the freed slot");
        assertFalse(CraftCommitPolicy.roomNeededBeforeHop(16, 16, 64, 64), "exactly one stack still fits");
    }

    @Test
    void aPartialTakeOrMoreThanOneStackOfPlanksNeedsRoomFirst() {
        assertTrue(CraftCommitPolicy.roomNeededBeforeHop(10, 3, 12, 64), "7 logs stay: no slot is freed");
        assertTrue(CraftCommitPolicy.roomNeededBeforeHop(17, 17, 68, 64), "68 planks need a second slot");
    }

    @Test
    void aSlotScanStopsOnATimeoutAndSkipsAChangedSlot() {
        assertFalse(CraftCommitPolicy.continueScan(CraftCommitPolicy.Outcome.TIMED_OUT), "a stalled server ends the scan");
        assertTrue(CraftCommitPolicy.continueScan(CraftCommitPolicy.Outcome.ABORTED), "the slot changed: try the next");
        assertTrue(CraftCommitPolicy.continueScan(CraftCommitPolicy.Outcome.CRAFTED));
    }
}
