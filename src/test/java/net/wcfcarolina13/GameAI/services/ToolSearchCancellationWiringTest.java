package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ToolSearchCancellationWiringTest {
    private static final Path SERVICES = Path.of("src/main/java/net/wcfcarolina13/GameAI/services");
    private static final String ABORT_CHECK = "if (abort != null && abort.getAsBoolean())";

    @Test
    void searchCapturesOneOriginalTicketBeforeItsFirstServerHop() throws IOException {
        String search = method("ToolProvisionService.java", "retrieveToolFromChests");
        String capture = "TaskService.TaskTicket taskAtStart = TaskService.getActiveTaskTicket(botUuid).orElse(null);";
        int captured = search.indexOf(capture);
        assertTrue(captured >= 0);
        assertEquals(1, search.split(Pattern.quote("taskAtStart ="), -1).length - 1,
                "a replacement must never refresh the original identity");
        int supplier = search.indexOf("BooleanSupplier abortSearch = () -> SupplyPullPolicy.stopsToolSearch(");
        assertTrue(captured < supplier && supplier < search.indexOf("SupplyServerHop.call("));
        String predicate = search.substring(supplier, search.indexOf(';', supplier));
        assertTrue(predicate.contains("taskAtStart, TaskService.getActiveTaskTicket(botUuid).orElse(null)"));
        assertTrue(predicate.contains("TaskService.isAbortRequested(botUuid)"));
        assertTrue(predicate.contains("taskAtStart != null && taskAtStart.isCancelRequested()"));
        assertFalse(search.contains("TaskService.hasActiveTask("),
                "task presence alone cannot distinguish the original from a replacement");
    }

    @Test
    void queuedRefreshAndEveryChestAttemptUseThatCapturedCancellation() throws IOException {
        String search = method("ToolProvisionService.java", "retrieveToolFromChests");
        String refresh = block(search, "SupplyServerHop.call(server, () ->");
        int refreshGuard = refresh.indexOf("if (abortSearch.getAsBoolean()) return null;");
        assertTrue(refreshGuard >= 0 && refreshGuard < refresh.indexOf("BotChestRegistryService.refreshAllSnapshots("));
        String loop = block(search, "for (BlockPos candidatePos : candidates)");
        int loopGuard = loop.indexOf("if (abortSearch.getAsBoolean()) return false;");
        int withdraw = loop.indexOf("ChestStoreService.withdrawMatchingWalkOnlyCancellable(");
        assertTrue(loopGuard >= 0 && loopGuard < withdraw);
        assertTrue(loop.substring(withdraw, loop.indexOf(';', withdraw))
                .contains("mode, abortSearch)"));
    }

    @Test
    void walkingWithdrawalCarriesAbortAcrossAskWalkAndFinalTake() throws IOException {
        String body = method("ChestStoreService.java", "withdrawMatchingWalkOnlyCancellable");
        int ask = body.indexOf("SupplyAsk ask = SupplyServerHop.call(");
        int walk = body.indexOf("TransferAttemptResult reach = MovementAbortPolicy.withAbort(abort,");
        int take = body.indexOf("return SupplyWithdrawals.withdraw(");
        assertTrue(ask >= 0 && ask < walk && walk < take);
        assertTrue(body.substring(0, ask).contains(ABORT_CHECK));
        assertTrue(body.substring(ask, walk).contains(ABORT_CHECK));
        assertTrue(body.substring(walk, take).contains(ABORT_CHECK));
        assertTrue(body.substring(ask, walk).contains(
                "() -> askBeforeWalking(bot, chestPos, want, matcher, purpose, mode, abort)"));
        assertTrue(body.substring(walk, take).contains(
                "() -> approachChestForTransfer(source, bot, chestPos, WALK_ONLY)"));
        assertTrue(body.substring(take).contains("mode == null ? SupplyWithdrawals.WaitMode.NONE : mode, abort)"));
    }

    @Test
    void queuedAskChecksAbortBeforeReadingChestAndPassesItToTheFacade() throws IOException {
        String ask = method("ChestStoreService.java", "askBeforeWalking");
        int guard = ask.indexOf(ABORT_CHECK);
        assertTrue(guard >= 0 && guard < ask.indexOf("ServerWorld world ="));
        assertTrue(ask.substring(guard, ask.indexOf("ServerWorld world =")).contains("Refusal.ABORTED"));
        int withdraw = ask.indexOf("SupplyWithdrawals.withdraw(");
        assertTrue(withdraw >= 0);
        assertTrue(ask.substring(withdraw, ask.indexOf(';', withdraw))
                .contains("SupplyWithdrawals.WaitMode.NONE, abort)"));
    }

    @Test
    void facadeChecksAbortInsideEachQueuedStepAndWhileWaiting() throws IOException {
        String withdraw = method("supply/SupplyWithdrawals.java", "withdraw");
        assertTrue(withdraw.contains("Supplier<Step> oneStep = () -> abort != null && abort.getAsBoolean()"
                + " ? Step.loud(Result.refused(Refusal.ABORTED)) : step(bot, chestPos, sample, qty, need);"));
        assertTrue(withdraw.contains("outcome = onServer(srv, oneStep)"));
        assertTrue(withdraw.contains("awaitAnswer(srv, bot, abort, oneStep)"));
        String wait = method("supply/SupplyWithdrawals.java", "awaitAnswer");
        assertTrue(wait.contains("boolean aborted = (abort != null && abort.getAsBoolean())"));
        assertTrue(wait.contains("SupplyWithdrawalPolicy.keepWaiting(aborted, pending,"));
        assertTrue(wait.contains("case ABORTED: return Step.loud(Result.refused(Refusal.ABORTED));"));
    }

    private static String method(String relative, String name) throws IOException {
        String source = Files.readString(SERVICES.resolve(relative))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", " ");
        var declaration = Pattern.compile("(?:public|private) (?:static )?[\\w.<>]+ "
                + name + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
        while (declaration.find()) {
            if (!name.equals("retrieveToolFromChests")
                    || declaration.group().contains("SupplyWithdrawals.WaitMode mode")) {
                return block(source, declaration.group());
            }
        }
        throw new AssertionError("Missing method: " + relative + "#" + name);
    }

    private static String block(String source, String marker) {
        int markerAt = source.indexOf(marker);
        assertTrue(markerAt >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', markerAt);
        assertTrue(start >= 0);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }
}
