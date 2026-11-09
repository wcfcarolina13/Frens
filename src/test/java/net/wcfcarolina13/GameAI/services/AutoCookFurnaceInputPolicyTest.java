package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCookFurnaceInputPolicyTest {

    @Test
    void aFoodCookLeavesSomeoneElsesWorkAlone() {
        assertTrue(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(true, true, false, true), "ore or other raw food in the input");
        assertTrue(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(true, true, true, false), "ingots in the output");
    }

    @Test
    void theBotsOwnFoodAndNamedOrMixedCooksKeepTheOldPath() {
        assertFalse(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(true, true, true, true), "empty, or the bot's own food: as before");
        assertFalse(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(true, false, false, false), "a named item clears the furnace as before");
        assertFalse(AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(false, true, false, false), "/bot cook with no item cooks anything");
    }

    @Test
    void theCheckRunsBeforeTheOutputIsSweptOrAnyFuelGoesIn() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java"));
        int busy = src.indexOf("AutoCookFurnaceInputPolicy.furnaceBusyWithOtherWork(foodOnly,");
        int sweep = src.indexOf("ItemStack remaining = evacuateOutput(liveBot, source, output.copy(), STATION_REACH_SQ);");
        int topUp = src.indexOf("int fuelTopped = topUpFuelMatching(liveBot, furnace);");
        int evacuate = src.indexOf("ItemStack remaining = evacuateInput(liveBot, source, input.copy(), STATION_REACH_SQ);");
        assertTrue(busy > 0 && sweep > busy && topUp > busy && evacuate > busy,
                "checked before sweeping the output, topping up fuel or clearing the input");
        assertTrue(src.contains("cookables.containsKey(inputNow.getItem())"), "only the bot's own food counts as loaded");
    }
}
