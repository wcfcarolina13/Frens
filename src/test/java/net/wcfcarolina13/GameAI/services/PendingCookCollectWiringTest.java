package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pending-batch collect runs before the hunger gate, and the ledger is cleared with the session. */
class PendingCookCollectWiringTest {

    private static String read(String rel) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/" + rel));
    }

    @Test
    void pendingCollectRunsBeforeTheHungryCollectAndTheHungerGate() throws IOException {
        String src = read("GameAI/services/BotAutoCookingService.java");
        int tick = src.indexOf("public static void onServerTick(");
        int pending = src.indexOf("tryCollectPendingBatch(server, bot, world, source, nowTick)", tick);
        int hungry = src.indexOf("shouldCollectReadyFood(hunger,", tick);
        int gate = src.indexOf("if (hunger > COOK_HUNGER_THRESHOLD) {", tick);
        assertTrue(pending > tick && hungry > pending && gate > hungry, "pending collect sits before the hunger rules");
        int reset = src.indexOf("public static void resetSession()");
        int clear = src.indexOf("PendingCookLedger.clear();", reset);
        assertTrue(reset > 0 && clear > reset && clear < src.indexOf("private BotAutoCookingService()"),
                "resetSession clears the ledger");
    }

    @Test
    void everyLoadRecordsAndEveryOtherTakeDebits() throws IOException {
        String src = read("GameAI/services/SmeltingService.java");
        int batch = src.indexOf("private static boolean startBatchCookInternal(");
        int end = src.indexOf("public static List<net.minecraft.util.Identifier> listCookableIds(", batch);
        String body = src.substring(batch, end);
        assertTrue(body.contains("recordPendingFood(liveBot, liveWorld, furnacePos, input.getItem(), topped);"), "top-up records");
        assertTrue(body.contains("recordPendingFood(liveBot, liveWorld, furnacePos, raw, count)"), "fresh load records");
        assertTrue(body.contains("debitPendingFood(liveBot, liveWorld, furnacePos, output.getItem(),"), "pre-load sweep debits");
        int collect = src.indexOf("public static boolean startCollectFinishedFood(");
        int collectEnd = src.indexOf("public static boolean startCollectPendingFood(", collect);
        assertTrue(src.substring(collect, collectEnd).contains("debitPendingFood(liveBot, liveWorld, targetPos, output.getItem(),"),
                "hungry collect debits");
    }

    @Test
    void ownerTakesAreObservedForEveryBotBeforeTheEligibilityGate() throws IOException {
        String src = read("GameAI/services/BotAutoCookingService.java");
        int tick = src.indexOf("public static void onServerTick(");
        int observe = src.indexOf("observePendingFurnaces(server, bot, nowTick);", tick);
        int eligible = src.indexOf("ineligibleReason(bot)", tick);
        assertTrue(observe > tick && eligible > observe, "the observation runs before the eligibility gate");
        int reset = src.indexOf("public static void resetSession()");
        assertTrue(src.indexOf("NEXT_PENDING_OBSERVE_TICK.clear();", reset) > reset, "resetSession clears the throttle");
    }

    @Test
    void stopClearsTheLedgerAndTheHungryCollectWaitsForAPendingTrip() throws IOException {
        String reg = read("Commands/modCommandRegistry.java");
        int stop = reg.indexOf("private static int executeStop(");
        int stopEnd = reg.indexOf("static int executeSkillTargets(", stop);
        assertTrue(reg.substring(stop, stopEnd).contains("PendingCookLedger.clearBot(bot.getUuid());"), "/bot stop clears");
        String smelt = read("GameAI/services/SmeltingService.java");
        int collect = smelt.indexOf("public static boolean startCollectFinishedFood(");
        int find = smelt.indexOf("findReadyFoodFurnace(bot, world);", collect);
        int guard = smelt.indexOf("PendingCookLedger.tripInFlight(bot.getUuid())", collect);
        assertTrue(guard > collect && guard < find, "hungry collect skips while a pending trip is in flight");
    }
}
