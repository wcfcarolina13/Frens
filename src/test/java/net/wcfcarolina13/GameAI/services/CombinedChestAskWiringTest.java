package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-reading checks for the one combined chest ask (Bradley 2026-10-23) wired into the tool
 * craft: where it sits in {@code CraftingHelper}, which thread does what, and how the facade and
 * the service carry the bundle. Server types keep this out of a unit test.
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
    void theWorldIsReadOnTheServerThreadAndTheWorkerWaitsForTheAnswer() throws IOException {
        String body = block(crafting(), "private static CraftBundleAskPolicy.Next askChestForToolMaterials(");
        assertTrue(body.contains("boolean onServerThread = server.isOnThread();"));
        // One hop gathers the bot's counts and every chest's grantable counts.
        assertTrue(body.contains("? gatherBundleInput(world, bot, match, head, headIsPlanks, true)\n"
                + "                : SupplyServerHop.call(server, () -> gatherBundleInput(world, bot, match, head, headIsPlanks, false),"));
        // The plan is pure and runs on the caller's thread; the chest choice too.
        assertTrue(body.contains("CraftSupplyNeedPolicy.plan(CraftBundleAskPolicy.CRAFT_SET, 1, recipes, input.have(), chest.pools(),"));
        assertTrue(body.contains("CraftBundleAskPolicy.chooseChest(plans)"));
        // Off the server thread the worker waits for the one answer; on it nothing waits.
        assertTrue(body.contains("? SupplyWithdrawals.WaitMode.NONE : SupplyWithdrawals.WaitMode.UNTIL_ANSWERED;"));
        assertTrue(body.contains("SupplyWithdrawals.withdrawBundle(bot, chest.pos(), asks, purpose,\n"
                + "                missing, mode, abort)"));
        assertTrue(body.contains("abort = () -> TaskService.isAbortRequested(botId);"), "/bot stop ends the wait");
        // A permitted take out of reach walks once, off the server thread only.
        assertTrue(body.contains("if (result.kind() == SupplyWithdrawals.Kind.READY && !onServerThread) {"));
        // Nothing to ask keeps today's path.
        assertTrue(body.contains("if (input == null || input.chests().isEmpty()) {\n"
                + "            return CraftBundleAskPolicy.Next.CONTINUE;"));
        assertFalse(body.contains("Thread.sleep"), "the craft helper never sleeps itself; the facade's wait does");
        String gather = block(crafting(), "private static BundleInput gatherBundleInput(");
        assertTrue(gather.contains("findPullCandidates(world, bot, match, inReachOnly)"));
        assertTrue(gather.contains("SupplyWithdrawals.grantableEstimate(world, candidate.sample(), candidate.count())"));
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
        assertTrue(step.contains("SupplyWithdrawalPolicy.aggregateKinds(kinds)"));
        assertFalse(step.contains("transferNow("), "the take stays in redeem");
        String bundle = block(facade, "public static BundleResult withdrawBundle(");
        assertTrue(bundle.contains("SupplyWithdrawalPolicy.effectiveMode(mode, onServerThread)"));
        assertTrue(bundle.contains("if (effective == WaitMode.UNTIL_ANSWERED && outcome.kind() == Kind.WAITING) {"));
        assertTrue(bundle.contains("awaitAnswer(srv, bot, abort, () -> onServer(srv, oneStep, refused), refused)"));
        assertTrue(bundle.contains("r == Refusal.TIMEOUT ? BundleWhy.NO_ANSWER : BundleWhy.OTHER"));
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
