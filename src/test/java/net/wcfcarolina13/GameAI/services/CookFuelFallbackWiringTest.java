package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog 1238: emergency auto-cook and /bot cook run one leaf-litter collection when the bot has
 * no fuel, then check fuel again. Reads the source: these classes touch server types.
 */
class CookFuelFallbackWiringTest {

    private static String read(String rel) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/" + rel));
    }

    private static String slice(String src, String startMarker, String endMarker) {
        int start = src.indexOf(startMarker);
        int end = src.indexOf(endMarker, start + 1);
        assertTrue(start >= 0 && end > start, "markers not found: " + startMarker);
        return src.substring(start, end);
    }

    @Test
    void autoCookGathersOnlyInTheNoFuelBranchAndChecksFuelAgain() throws IOException {
        String src = read("GameAI/services/BotAutoCookingService.java");
        String tick = slice(src, "public static void onServerTick(", "public static void snoozeFor(");
        int gate = tick.indexOf("if (!shouldStartEmergencyAutoCook(hunger, hasCookableFood, hasFuel)) {");
        int gather = tick.indexOf("CookFuelFallbackPolicy.shouldGatherForEmergencyCook(", gate);
        int start = tick.indexOf("startFuelGatherThenCook(server, bot, nowTick)", gather);
        int giveUp = tick.indexOf("NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 120L + RNG.nextInt(80));", start);
        int cook = tick.indexOf("SmeltingService.startBatchCookFoodOnly(bot, source, \"auto\")", giveUp);
        assertTrue(gate > 0 && gather > gate && start > gather, "gather sits inside the no-fuel branch");
        assertTrue(giveUp > start && cook > giveUp, "a bot that does not gather gives up as before");

        String after = slice(src, "private static boolean startFuelGatherThenCook(", "public static void snoozeFor(");
        int run = after.indexOf("BotIdleHobbiesService.startLeafLitterFuelRun(bot, mayContinue -> {");
        int recheck = after.indexOf("SmeltingService.hasFuelForAutoCook(live, liveWorld)", run);
        int decide = after.indexOf("CookFuelFallbackPolicy.shouldCookAfterGather(mayContinue, hasFuelNow)", recheck);
        int eligible = after.indexOf("isEligible(live)", decide);
        int retryCook = after.indexOf("SmeltingService.startBatchCookFoodOnly(live,", eligible);
        assertTrue(run > 0 && recheck > run && decide > recheck, "fuel checked once more after the run");
        assertTrue(eligible > decide && retryCook > eligible, "cook only if still eligible");
        assertTrue(after.indexOf("FUEL_GATHER_IN_FLIGHT.remove(botUuid);") > run, "in-flight mark cleared");
        assertTrue(src.contains("NEXT_FUEL_GATHER_TICK.clear();") && src.contains("FUEL_GATHER_IN_FLIGHT.clear();"),
                "session reset clears fallback state");
    }

    @Test
    void cookCommandGathersBeforeTheUnchangedCook() throws IOException {
        String src = read("Commands/modCommandRegistry.java");
        String body = slice(src, "private static int executeCook(", "private static int executeStoreDeposit(");
        int gate = body.indexOf("CookFuelFallbackPolicy.shouldGatherForCookCommand(");
        int run = body.indexOf("BotIdleHobbiesService.startLeafLitterFuelRun(bot, mayContinue -> {", gate);
        int retry = body.indexOf("SmeltingService.startBatchCook(live, source, itemFilter, fuelSpec);", run);
        int plain = body.indexOf("SmeltingService.startBatchCook(bot, source, itemFilter, fuelSpec);", retry);
        assertTrue(gate > 0 && run > gate && retry > run, "cook retried after the run");
        assertTrue(plain > retry, "without a run the cook starts as before");
    }

    @Test
    void fuelRunUsesTheHobbyLimitsOnAWorkerAndKeepsTheLitter() throws IOException {
        String src = read("GameAI/services/BotIdleHobbiesService.java");
        String run = slice(src, "public static boolean startLeafLitterFuelRun(", "private static boolean hasNearbyLeafLitter(");
        int params = run.indexOf("putLeafLitterRunParams(params, true, forestBiome);");
        int keep = run.indexOf("LeafLitterSkill.KEEP_FOR_FUEL_PARAM, true");
        int submit = run.indexOf("AMBIENT_EXECUTOR.submit(", keep);
        int skill = run.indexOf("SkillManager.runSkill(\"leaf_litter\", skillContext)", submit);
        int abort = run.indexOf("TaskService.isAbortRequested(botUuid)", skill);
        int finallyAt = run.indexOf("} finally {", abort);
        int back = run.indexOf("server.execute(", finallyAt);
        assertTrue(params > 0 && keep > params && submit > keep, "hobby limits, keep flag, then submit");
        assertTrue(skill > submit && abort > skill, "skill runs on the worker, stop sampled after it");
        assertTrue(finallyAt > abort && back > finallyAt, "callback posted to the server thread from finally");
        // The hobby's own leaf_litter run uses the same limits helper.
        assertTrue(src.contains("putLeafLitterRunParams(params, survival, forestBiome);"), "hobby shares the limits");

        String skillSrc = read("GameAI/skills/impl/LeafLitterSkill.java");
        assertTrue(skillSrc.contains("isKeepForFuel(context.parameters()) ? 0 : depositNearbyLeafLitter(source, bot, world)"),
                "fuel run skips the chest deposit");
    }
}
