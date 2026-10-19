package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class FarmOutcomePolicyTest {

    @Test
    void tilledAndPlantedIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(9, 9, 9, true));
    }

    @Test
    void tilledOnlyIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(4, 0, 0, true));
    }

    @Test
    void plantedOnExistingFarmlandIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(0, 6, 6, false));
    }

    @Test
    void alreadyFullyPlantedFarmIsSuccess() {
        assertNull(FarmOutcomePolicy.failureReason(0, 0, 12, true));
        assertNull(FarmOutcomePolicy.failureReason(0, 0, 1, false));
    }

    @Test
    void nothingDoneWithHoeInReachFails() {
        assertEquals(FarmOutcomePolicy.NOTHING_TILLED_LINE, FarmOutcomePolicy.failureReason(0, 0, 0, true));
    }

    @Test
    void nothingDoneWithoutHoeNamesTheHoe() {
        assertEquals(FarmOutcomePolicy.NO_HOE_LINE, FarmOutcomePolicy.failureReason(0, 0, 0, false));
    }

    @Test
    void playerLinesHaveNoEmDashOrSemicolon() {
        for (String line : new String[] {FarmOutcomePolicy.NO_HOE_LINE, FarmOutcomePolicy.NOTHING_TILLED_LINE}) {
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains(";"), line);
        }
    }
}
