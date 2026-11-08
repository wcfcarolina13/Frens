package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnaceTakeBackPolicyTest {

    @Test
    void aStopTakesTheFoodBack() {
        assertTrue(FurnaceTakeBackPolicy.shouldTakeBack(true, false, true));
        assertFalse(FurnaceTakeBackPolicy.shouldTakeBack(false, false, true));
        assertFalse(FurnaceTakeBackPolicy.shouldTakeBack(true, true, true));
        // A stop before the cycle loaded anything leaves the furnace alone (it may be someone else's cook).
        assertFalse(FurnaceTakeBackPolicy.shouldTakeBack(true, false, false));
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
        int takeBack = src.indexOf("takeBackAfterStop(bot, source, server, worldKey, botId, furnacePos, fuelBaseline.get());", start);
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
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(true, true, 0, 15) == 15);   // empty before: the bot's 15 coal
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, true, 4, 15) == 11);  // 4 of the owner's stay
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, true, 10, 8) == 0);   // burnt down below the owner's
        assertTrue(FurnaceTakeBackPolicy.fuelToTakeBack(false, false, 3, 3) == 0);   // someone else's fuel item
    }
}
