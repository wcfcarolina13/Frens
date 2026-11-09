package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCookFurnaceInputPolicyTest {

    @Test
    void aFoodCookLeavesSomeoneElsesOreAlone() {
        assertTrue(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(true, true, false));
    }

    @Test
    void foodInTheInputAndNamedOrMixedCooksKeepTheOldPath() {
        assertFalse(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(true, true, true), "raw food already in: top up as before");
        assertFalse(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(true, false, false), "a named item clears the input as before");
        assertFalse(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(false, true, false), "/bot cook with no item cooks anything");
    }

    @Test
    void theCheckRunsBeforeAnyFuelGoesIn() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java"));
        int busy = src.indexOf("AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(foodOnly, autoCook, inputCooksToFood)");
        int topUp = src.indexOf("int fuelTopped = topUpFuelMatching(liveBot, furnace);");
        int evacuate = src.indexOf("ItemStack remaining = evacuateInput(liveBot, source, input.copy(), STATION_REACH_SQ);");
        assertTrue(busy > 0 && topUp > busy && evacuate > busy, "checked before topping up fuel or clearing the input");
    }
}
