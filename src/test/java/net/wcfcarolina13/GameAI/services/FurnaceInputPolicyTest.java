package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FurnaceInputPolicyTest {

    @Test
    void aDifferentSizedStackOfTheSameFoodStillTakesTheBotsFood() {
        assertEquals(8, FurnaceInputPolicy.transfer(true, 5, 64, 8));
        assertEquals(8, FurnaceInputPolicy.transfer(true, 8, 64, 8));
    }

    @Test
    void onlyWhatFitsAndNeverAnotherItem() {
        assertEquals(4, FurnaceInputPolicy.transfer(true, 60, 64, 8));
        assertEquals(0, FurnaceInputPolicy.transfer(true, 64, 64, 8));
        assertEquals(0, FurnaceInputPolicy.transfer(false, 5, 64, 8));
        assertEquals(0, FurnaceInputPolicy.transfer(true, 5, 64, 0));
    }
}
