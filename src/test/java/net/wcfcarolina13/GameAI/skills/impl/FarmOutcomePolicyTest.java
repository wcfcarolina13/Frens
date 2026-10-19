package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class FarmOutcomePolicyTest {

    @Test
    void tilledAndPlantedIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(9, 9, 9, true, 0, 0));
    }

    @Test
    void tilledOnlyIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(4, 0, 0, true, 0, 0));
    }

    @Test
    void plantedOnExistingFarmlandIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(0, 6, 6, false, 0, 0));
    }

    @Test
    void alreadyFullyPlantedFarmIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(0, 0, 12, true, 0, 0));
        assertNull(FarmOutcomePolicy.failureReason(0, 0, 1, false, 0, 0));
    }

    @Test
    void nothingDoneWithHoeInReachFails() {
        assertEquals(FarmOutcomePolicy.NOTHING_TILLED_LINE, FarmOutcomePolicy.failureReason(0, 0, 0, true, 0, 0));
    }

    @Test
    void nothingDoneWithoutHoeNamesTheHoe() {
        assertEquals(FarmOutcomePolicy.NO_HOE_LINE, FarmOutcomePolicy.failureReason(0, 0, 0, false, 0, 0));
    }

    @Test
    void lostHoeOnHalfPlantedFarmWithSeedsAndGroundLeftFails() {
        assertEquals(FarmOutcomePolicy.NO_HOE_LINE, FarmOutcomePolicy.failureReason(0, 0, 5, false, 4, 10));
    }

    @Test
    void lostHoeOnHalfPlantedFarmWithoutSeedsIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(0, 0, 5, false, 4, 0));
    }

    @Test
    void lostHoeOnFullyTilledFarmIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(0, 0, 5, false, 0, 10));
    }

    @Test
    void hoeInReachOnHalfPlantedFarmIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(0, 0, 5, true, 4, 10));
    }

    @Test
    void anyWorkDoneIsSuccessEvenWithoutHoeAndGroundLeft() {
        assertNull(FarmOutcomePolicy.failureReason(2, 0, 5, false, 4, 10));
        assertNull(FarmOutcomePolicy.failureReason(0, 3, 5, false, 4, 10));
    }

    @Test
    void playerLinesHaveNoEmDashOrSemicolon() {
        for (String line : new String[] {FarmOutcomePolicy.NO_HOE_LINE, FarmOutcomePolicy.NOTHING_TILLED_LINE}) {
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains(";"), line);
        }
    }
}
