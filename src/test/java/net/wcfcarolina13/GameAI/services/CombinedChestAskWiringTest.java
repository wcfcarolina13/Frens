package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-reading checks for the one combined chest ask (Bradley 2026-10-23) wired into the tool
 * craft and the other worker crafts (ladders, torches, beds, shields, buckets and shears, the
 * furnace, chests and crafting tables): where it sits in {@code CraftingHelper}, which thread
 * does what, and how the facade and the service carry the bundle. Server types keep this out of
 * a unit test.
 */
class CombinedChestAskWiringTest {

    private static final Path SERVICES = Path.of("src/main/java/net/wcfcarolina13/GameAI/services");
    private static final Path SUPPLY = SERVICES.resolve("supply");

    @Test
    void theToolCraftAsksOnceBeforeTheStationAndTheStickSubStep() throws IOException {
        String body = block(crafting(), "private static int craftToolWithMaterial(");
        int ask = body.indexOf("askChestForToolMaterials(bot, source, kind, mat, amount, headCount, stickCount, shortfall)");
        int station = body.indexOf("ensureCraftingStation(bot, source, shortfall != null)");
        int sticks = body.indexOf("ensureSticks(bot, source, neededSticks, shortfall != null)");
        assertTrue(ask >= 0 && station > ask && sticks > station, "bundle, then station, then sticks");
        assertTrue(body.contains("case STOP_SILENT, STOP_WITH_LINE -> {\n                return SUPPLY_DECLINED;"));
        assertTrue(body.contains("case SKIP_MATERIAL -> {"));
    }

    @Test
    void aDeclinedAskEndsTheWholeCraftWithoutTryingAnotherMaterial() throws IOException {
        String loop = block(crafting(), "private static int craftToolMaterialAware(");
        int declined = loop.indexOf("if (crafts == SUPPLY_DECLINED) {");
        assertTrue(declined >= 0);
        String branch = loop.substring(declined, loop.indexOf("if (shortfall != null && shortfall.packFull)"));
        assertTrue(branch.contains("say(source, shortfall.message);"));
        assertTrue(branch.contains("return 0;"));
    }

    @Test
    void theToolAskIsAThinWrapperOverTheSharedCraftAsk() throws IOException {
        String body = block(crafting(), "private static CraftBundleAskPolicy.Next askChestForToolMaterials(");
        assertTrue(body.contains("CraftBundleAskPolicy.toolRecipe(headKey, headCount, stickCount)"));
        assertTrue(body.contains("CraftBundleAskPolicy.headKeys(headKey)"), "a hopeless head still skips the material");
        assertTrue(body.contains("return askChestForCraftMaterials(bot, source, ask, shortfall);"));
        assertFalse(body.contains("SupplyWithdrawals."), "the wrapper plans nothing and asks nothing itself");
        String known = block(crafting(), "private static CraftBundleAskPolicy.Next askChestForKnownCraft(");
        assertTrue(known.contains("CraftBundleAskPolicy.known(Registries.ITEM.getId(output).toString())"));
        assertTrue(known.contains("if (craft == null) {\n            return CraftBundleAskPolicy.Next.CONTINUE;"),
                "a craft outside the table keeps today's path");
        assertTrue(known.contains("new CraftAsk(input -> craft, match, amount, needsTable, List.of())"),
                "no alternative material: nothing is hopeless, a partial chest is asked for what it has");
    }

    @Test
    void everyOtherWorkerCraftAsksOnceBeforeItsStationAndItsPerItemPulls() throws IOException {
        String src = crafting();
        // Each craft: the one ask, then (where the recipe is 3x3) the station, then the per-item sub-steps.
        String ladders = block(src, "private static int craftLadders(");
        assertOrder(ladders, "askChestForKnownCraft(bot, source, Items.LADDER, amountRequested, s -> false, true)",
                "ensureCraftingStation(bot, source)", "ensureSticks(bot, source, neededSticks)");
        assertTrue(ladders.indexOf("hasRecipePermission(") < ladders.indexOf("askChestForKnownCraft("),
                "a recipe the bot may not craft is never asked for");
        String torches = block(src, "private static int craftTorches(");
        assertOrder(torches, "askChestForKnownCraft(bot, source, Items.TORCH, targetTorches, CraftingHelper::isTorchFuel, false)",
                "ensureSticks(bot, source, craftsNeeded)", "ensureItemAvailable(bot, source, Items.COAL, craftsNeeded)");
        assertFalse(torches.contains("ensureCraftingStation("), "a torch is a 2x2 recipe: no table in its ask");
        String shield = block(src, "private static int craftShield(");
        assertOrder(shield, "askChestForKnownCraft(bot, source, Items.SHIELD, amount, s -> s.isOf(Items.IRON_INGOT), true)",
                "ensureCraftingStation(bot, source)", "ensurePlanksAvailable(bot, source, 6 * amount)");
        String simple = block(src, "private static int craftSimple(");
        assertOrder(simple, "askChestForKnownCraft(bot, source, output, amount, s -> s.isOf(input), output.equals(Items.BUCKET))",
                "ensureCraftingStation(bot, source)", "ensureItemAvailable(bot, source, Items.IRON_INGOT, per * amount)");
        String cobble = block(src, "private static int craftCobbleBlock(");
        assertOrder(cobble, "askChestForKnownCraft(bot, source, output, amount, CraftingHelper::isStoneCraftingMaterial,",
                "ensureCraftingStation(bot, source)", "ensureCobbleAvailable(bot, source, per * amount)");
        String planks = block(src, "private static int craftWithPlanks(");
        assertOrder(planks, "if (askChest) {", "askChestForKnownCraft(bot, source, output, amountRequested, s -> false,\n"
                + "                    !output.equals(Items.CRAFTING_TABLE))", "ensureCraftingStation(bot, source, quiet)",
                "ensurePlanksAvailable(bot, source, planksPerItem * amountRequested)");
        // The station sub-step's table never asks; /bot craft chest|crafting_table do.
        String table = block(src, "public static boolean craftCraftingTable(");
        assertTrue(table.contains("Items.CRAFTING_TABLE, amountRequested, quiet, false)"));
        assertTrue(src.contains("Items.CRAFTING_TABLE, amount, false, true);"));
        assertTrue(src.contains("Items.CHEST, amount, false, true);"));
        // A bed picks its colour from what was gathered (the bot, then one chest), wool and planks in one ask.
        String bed = block(src, "private static int craftBed(");
        assertTrue(bed.contains("CraftBundleAskPolicy.chooseBedWool(woolIds, input.have(), input.chestPools(), 3 * bedAmount)"));
        assertTrue(bed.contains("return CraftBundleAskPolicy.bedCraft(Registries.ITEM.getId(recipe.bed()).toString(), wool);"));
        assertOrder(bed, "askChestForCraftMaterials(bot, source, bedAsk, null)", "BedRecipe chosen = null;",
                "ensureCraftingStation(bot, source)", "withdrawFromNearbyChests(bot, source, s -> s.isOf(woolItem)");
        // A declined or unanswered ask ends each craft after the one stop line; everything else goes on.
        for (String body : List.of(ladders, torches, shield, simple, cobble, planks, bed)) {
            assertTrue(body.contains("case STOP_SILENT, STOP_WITH_LINE -> {\n"), "stop after a No");
            assertFalse(body.contains("SKIP_MATERIAL"), "no alternative material to skip to");
        }
    }

    private static void assertOrder(String body, String... needles) {
        int at = -1;
        for (String needle : needles) {
            int next = body.indexOf(needle, at + 1);
            assertTrue(next > at, "expected in order: " + needle);
            at = next;
        }
    }

    @Test
    void onlyAWorkerAsksAndItWaitsForTheAnswer() throws IOException {
        String body = block(crafting(), "private static CraftBundleAskPolicy.Next askChestForCraftMaterials(");
        // A server-thread craft (the idle stone upgrade from onServerTick) cannot wait and would not
        // be back before the 60 s grant expired, so an "Allow once" would never be spent and a new
        // prompt would open on every retry: it keeps the per-item pulls, exactly as before the bundle.
        assertTrue(body.contains("if (server.isOnThread()) {\n"
                + "            // A server-thread craft cannot wait for the answer and would not be back before the\n"
                + "            // grant expired: it keeps the per-item pulls, exactly as before the bundle.\n"
                + "            return CraftBundleAskPolicy.Next.CONTINUE;\n"
                + "        }"));
        assertFalse(body.contains("WaitMode.NONE"), "no caller of the bundle runs without waiting");
        assertFalse(body.contains("onServerThread"), "past the thread check there is one path, the worker's");
        // One hop gathers the bot's counts and every chest's grantable counts.
        assertTrue(body.contains("BundleInput input = SupplyServerHop.call(server, () -> gatherBundleInput(world, bot, match),"));
        // The recipe lookup, the plan and the chest choice are pure and run on the caller's thread.
        assertTrue(body.contains("CraftBundleAskPolicy.craftRecipes(\n                craft, amount, tableMustBeCrafted)"));
        assertTrue(body.contains("CraftSupplyNeedPolicy.plan(CraftBundleAskPolicy.CRAFT_SET, 1, recipes, input.have(), chest.pools(),"));
        assertTrue(body.contains("CraftBundleAskPolicy.chooseChest(plans)"));
        // Only a 3x3 craft adds the table; taken logs become the planks the plan counted on.
        assertTrue(body.contains("boolean tableMustBeCrafted = ask.needsTable() && !tableAvailableWithoutCrafting(bot, source);"));
        assertTrue(body.contains("ensurePlanksFromLogs(bot, CraftBundleAskPolicy.planksWanted(craft.recipe(), amount,"));
        // The worker waits for the one answer.
        assertTrue(body.contains("SupplyWithdrawals.WaitMode mode = SupplyWithdrawals.WaitMode.UNTIL_ANSWERED;"));
        assertTrue(body.contains("SupplyWithdrawals.withdrawBundle(bot, chest.pos(), asks, purpose,\n"
                + "                missing, mode, abort)"));
        assertTrue(body.contains("abort = () -> TaskService.isAbortRequested(botId);"), "/bot stop ends the wait");
        // A permitted take out of reach walks once.
        assertTrue(body.contains("if (result.kind() == SupplyWithdrawals.Kind.READY) {"));
        // Nothing to ask keeps today's path.
        assertTrue(body.contains("if (input == null || input.chests().isEmpty()) {\n"
                + "            return CraftBundleAskPolicy.Next.CONTINUE;"));
        assertFalse(body.contains("Thread.sleep"), "the craft helper never sleeps itself; the facade's wait does");
        String gather = block(crafting(), "private static BundleInput gatherBundleInput(");
        assertTrue(gather.contains("findPullCandidates(world, bot, match, false)"));
        assertTrue(gather.contains("SupplyWithdrawals.grantableEstimate(world, candidate.sample(), candidate.count())"));
        assertTrue(gather.contains("have.merge(bundlePool(stack), stack.getCount(), Integer::sum);"),
                "the bot's counts are pooled the same way as the chest's");
        assertFalse(Pattern.compile("\\.\\s*(?:removeStack|split|setStack|decrement|insertStack)\\s*\\(").matcher(gather).find(),
                "the gather is read-only");
    }

    @Test
    void theFacadeOpensOneBundleAndRedeemsEveryLineThroughTheSameTake() throws IOException {
        String facade = Files.readString(SUPPLY.resolve("SupplyWithdrawals.java"));
        String step = block(facade, "private static BundleResult bundleStep(");
        assertEquals(1, count(step, "SupplyRequestService.requestBundle("), "one ask per step");
        assertTrue(step.contains("SupplyWithdrawalPolicy.ticketStep(ticket != null, pending, permitted)"),
                "each line keeps the single withdrawal's ticket logic");
        assertTrue(count(step, "redeem(bot, world, chestPos,") >= 3, "covered, taken and granted lines all redeem");
        assertTrue(step.contains("TICKETS.record(line.approved(), now)"));
        assertTrue(step.contains("return BundleResult.of(results, whys);"));
        assertFalse(step.contains("transferNow("), "the take stays in redeem");
        // The step after the wait carries every line the first step settled (moved or refused): a
        // line a live grant covered is in the pack and must not be asked for again.
        assertTrue(step.contains("if (carried != null && SupplyWithdrawalPolicy.bundleLineSettled(carried.get(i).kind())) {"));
        assertTrue(step.contains("results[i] = carried.get(i);\n"
                + "                whys[i] = before.lineWhys().get(i);\n"
                + "                continue;"));
        // A dropped ticket is the owner's No only while the ledger's reject cooldown says so; else nobody answered.
        assertTrue(step.contains("SupplyWithdrawalPolicy.bundleWhyOfTicket(ticketStep,\n"
                + "                            SupplyRequestService.isRejectCoolingDown(botId, chestKey, item.itemId()))"));
        // The service merges lines by exact item, so its outcomes are matched by item key, never by index.
        assertTrue(step.contains("Map<ItemKey, LineOutcome> outcomes = new HashMap<>();"));
        assertTrue(step.contains("LineOutcome line = outcomes.get(toRequestItems.get(n));"));
        assertFalse(step.contains("outcomes.get(n)"));
        String aggregate = block(facade, "static BundleResult of(Result[] results, BundleWhy[] whys)");
        assertTrue(aggregate.contains("SupplyWithdrawalPolicy.aggregateKinds(kinds)"));
        String bundle = block(facade, "public static BundleResult withdrawBundle(");
        assertTrue(bundle.contains("SupplyWithdrawalPolicy.effectiveMode(mode, onServerThread)"));
        assertTrue(bundle.contains("if (effective == WaitMode.UNTIL_ANSWERED && outcome.kind() == Kind.WAITING) {"));
        assertTrue(bundle.contains("bundleStep(bot, chestPos, lines, purpose, stillMissing, before)"),
                "the settle step is given the step before the wait");
        assertTrue(bundle.contains("awaitAnswer(srv, bot, abort, () -> onServer(srv, () -> oneStep.apply(before), refusedUnsettled),\n"
                + "                        refusedUnsettled)"));
        String refusedAfter = block(facade, "private static BundleResult refusedAfter(");
        assertTrue(refusedAfter.contains("refusal == Refusal.TIMEOUT ? BundleWhy.NO_ANSWER : BundleWhy.OTHER"));
        assertTrue(refusedAfter.contains("BundleResult.unsettledRefused(before, refused, why)"),
                "a timeout or abort after the first step refuses only the lines it left waiting");
        String wait = block(facade, "private static <T> T awaitAnswer(");
        assertTrue(wait.contains("SupplyRequestService.isPending(botId)"));
        assertTrue(wait.contains("Thread.sleep(POLL_INTERVAL_MS)"), "the worker polls; the server thread never waits");
    }

    @Test
    void theServiceSendsOnePromptWithThePolicysWordsAndAnswersInKind() throws IOException {
        String service = Files.readString(SUPPLY.resolve("SupplyRequestService.java"));
        String evaluate = block(service, "private static BundleRequestOutcome evaluateBundleRequest(");
        assertTrue(evaluate.contains("if (offServerThread(\"requestBundle\")) {"));
        assertTrue(evaluate.contains("readChest(srv, world, chestPos, bot, owner)"), "the same access read as a request");
        assertTrue(evaluate.contains("book.openBundle(owner, bot.getUuid(), chest.key(), bundle)"));
        assertEquals(1, count(evaluate, "sendBundlePrompt("));
        String prompt = block(service, "private static void sendBundlePrompt(");
        assertTrue(prompt.contains("SupplyBundlePromptPolicy.promptText(botName, asked, purpose,"));
        assertTrue(prompt.contains("SupplyBundlePromptPolicy.allowOnceHover(botName, asked)"));
        assertEquals(3, count(prompt, "choiceButton("), "the same three answers as a single prompt");
        String reply = block(service, "private static String replyText(");
        assertTrue(reply.contains("if (response.lines().size() > 1) {"));
        assertTrue(reply.contains("SupplyBundlePromptPolicy.grantedOnceReply(bot, shown)"));
        assertTrue(reply.contains("SupplyBundlePromptPolicy.rejectedReply(bot, shown)"));
    }

    private static String crafting() throws IOException {
        return Files.readString(SERVICES.resolve("CraftingHelper.java"));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static String block(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, "Missing signature: " + signature);
        int start = source.indexOf('{', signatureAt);
        int depth = 1;
        for (int end = start + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, end);
        }
        throw new AssertionError("Unbalanced block for " + signature);
    }
}
