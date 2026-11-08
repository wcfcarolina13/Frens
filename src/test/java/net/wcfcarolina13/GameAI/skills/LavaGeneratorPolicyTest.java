package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LavaGeneratorPolicyTest {

    @Test
    void cobblestoneBetweenLavaAndWaterIsAGeneratorCell() {
        assertTrue(LavaGeneratorPolicy.isGeneratorCell(true, true, true));
    }

    @Test
    void anythingElseStaysLeftAlone() {
        assertFalse(LavaGeneratorPolicy.isGeneratorCell(false, true, true));
        assertFalse(LavaGeneratorPolicy.isGeneratorCell(true, true, false));
        assertFalse(LavaGeneratorPolicy.isGeneratorCell(true, false, true));
    }
}
