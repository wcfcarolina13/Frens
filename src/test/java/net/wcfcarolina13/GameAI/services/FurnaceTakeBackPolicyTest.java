package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FurnaceTakeBackPolicyTest {

    @Test
    void aStopTakesTheFoodBack() {
        assertTrue(FurnaceTakeBackPolicy.shouldTakeBack(true, false, true));
        assertFalse(FurnaceTakeBackPolicy.shouldTakeBack(false, false, true));
        assertFalse(FurnaceTakeBackPolicy.shouldTakeBack(true, true, true));
        // Stopped before the first load: a shared furnace keeps what is in it.
        assertFalse(FurnaceTakeBackPolicy.shouldTakeBack(true, false, false));
    }

    @Test
    void onlyTheFoodTheBotLoadedComesBack() {
        assertTrue(FurnaceTakeBackPolicy.inputToTakeBack(true, 8, 8, 0) == 8);
        assertTrue(FurnaceTakeBackPolicy.inputToTakeBack(true, 12, 5, 0) == 5);   // 7 of the owner's raw beef stay
        assertTrue(FurnaceTakeBackPolicy.inputToTakeBack(true, 3, 5, 2) == 3);    // part cooked
        assertTrue(FurnaceTakeBackPolicy.inputToTakeBack(false, 6, 5, 0) == 0);   // a food type the bot did not load
    }

    @Test
    void foodSlotsOnlyNeverTheFuel() {
        assertArrayEquals(new int[] { 2, 0 }, FurnaceTakeBackPolicy.SLOTS);
        for (int slot : FurnaceTakeBackPolicy.SLOTS) {
            assertTrue(slot != FurnaceTakeBackPolicy.FUEL_SLOT);
        }
    }

    @Test
    void theCookCycleTakesBackBeforeItReturns() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java"));
        int start = src.indexOf("public static boolean cookAllFoodSync(");
        int takeBack = src.indexOf("takeBackAfterStop(bot, source, server, worldKey, botId, furnacePos, fuelBaseline.get(), fuelAddedByBot,", start);
        int ret = src.indexOf("return anyCooked;", start);
        assertTrue(start > 0 && takeBack > start && takeBack < ret);
    }

    @Test
    void aStopEndsTheLoopBeforeTheNextFoodTypeIsLoaded() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java"));
        int loop = src.indexOf("for (Item rawItem : itemTypes) {");
        int check = src.indexOf("TaskService.isAbortRequested(botId)", loop);
        int load = src.indexOf("insertIntoInput(furnace", loop);
        assertTrue(loop > 0 && check > loop && check < load);
    }

    @Test
    void onlyTheFuelTheBotAddedComesBack() {
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(true, true, 0, 15, 15) == 15);   // empty before: the bot's 15 coal
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, true, 4, 15, 11) == 11);  // 4 of the owner's stay
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, true, 4, 13, 11) == 9);   // 2 burned for the bot's cook
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, true, 10, 8, 3) == 0);    // burnt down below the owner's
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, false, 3, 3, 3) == 0);    // someone else's fuel item
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(true, true, 0, 15, 0) == 0);     // the bot added none
    }

    @Test
    void neverMoreThanTheBotPutIn() {
        // A player topped up 20 of the same coal during the cycle: the bot still takes back only its 6.
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, true, 4, 30, 6) == 6);
    }

    @Test
    void eachRefuelCountsWhatItAdded() {
        assertTrue(FurnaceTakeBackPolicy.refuelAdded(true, 0, 15) == 15);
        assertTrue(FurnaceTakeBackPolicy.refuelAdded(true, 12, 15) == 3);
        assertTrue(FurnaceTakeBackPolicy.refuelAdded(true, 15, 15) == 0);
        assertTrue(FurnaceTakeBackPolicy.refuelAdded(false, 2, 8) == 0);
    }

    @Test
    void cookedFoodCountsAgainstTheBotsRawShare() {
        // Owner had 4 raw beef in the slot, the bot loaded 8, 6 have cooked: 6 raw left.
        // The bot takes back at most 8 - 6 = 2; the owner's 4 stay.
        assertEquals(2, FurnaceTakeBackPolicy.inputToTakeBack(true, 6, 8, 6));
        assertEquals(0, FurnaceTakeBackPolicy.inputToTakeBack(true, 4, 8, 9));
        assertEquals(8, FurnaceTakeBackPolicy.inputToTakeBack(true, 12, 8, 0));
    }

    @Test
    void theBotTakesOnlyAsManyCookedAsItLoadedRaw() {
        // Owner's 5 raw beef + the bot's 8: 13 steaks, the bot takes 8, the owner's 5 stay.
        assertEquals(8, FurnaceTakeBackPolicy.outputAllowance(8, 0));
        assertEquals(3, FurnaceTakeBackPolicy.outputAllowance(8, 5));
        assertEquals(0, FurnaceTakeBackPolicy.outputAllowance(8, 8));
        assertEquals(0, FurnaceTakeBackPolicy.outputAllowance(0, 0));
    }
}
