package net.wcfcarolina13.GameAI.services.construction.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureReasonTest {
    @Test
    void clutterClearingIsAnOccupiedCellRatherThanAnUnknownFailure() {
        for (String reason : new String[]{"clearing-occupied=Snow", "failed-to-clear-occupied",
                "failed-to-clear-occupied=Short Grass", "clearing-busy"}) {
            assertEquals(FailureReason.BLOCKED_BY_SOLID, FailureReason.fromPlaceReason(reason), reason);
        }
    }

    @Test
    void unrelatedReasonsKeepTheirClassification() {
        assertEquals(FailureReason.BLOCKED_BY_SOLID, FailureReason.fromPlaceReason("occupied=Stone"));
        assertEquals(FailureReason.NO_SUPPORT, FailureReason.fromPlaceReason("no-solid-support"));
        assertEquals(FailureReason.UNKNOWN, FailureReason.fromPlaceReason("unexpected"));
    }
}
