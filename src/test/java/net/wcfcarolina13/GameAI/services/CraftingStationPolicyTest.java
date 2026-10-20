package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for CraftingHelper.ensureCraftingStation's thread split, plus source pins that the
 * helper routes its walk and carve sites through the policy. No net.minecraft types.
 */
class CraftingStationPolicyTest {

    private static final boolean SERVER = true;
    private static final boolean WORKER = false;
    /** CraftingHelper.STATION_REACH_SQ is 4.5 * 4.5, measured from the bot's feet to the block's center. */
    private static final double STATION_REACH = 4.5D;
    private static final String DECLINE_LINE =
            "craft-station tick-side: no table in reach for {}, not walking (reason={})";
    /** ensureCraftingStation's server-thread split, as the source spells it (its first occurrence). */
    private static final String TICK_GATE = "if (!CraftingStationPolicy.mayWalk(onServerThread))";
    /** The body of ensureCraftingStation (the public two-argument form delegates to it). */
    private static final String STATION_SIGNATURE =
            "private static boolean ensureCraftingStation(ServerPlayerEntity bot, ServerCommandSource source, boolean quiet)";

    // ── section-skipping scan order ──────────────────────────────────────────────────────────

    @Test
    void scanOrderMatchesTheWholeBoxLoopXFastestThenYThenZ() {
        // BlockPos.iterate(min, max): index % sizeX -> x, (index / sizeX) % sizeY -> y, the rest -> z.
        List<int[]> boxOrder = new java.util.ArrayList<>();
        for (int z = -17; z <= 18; z += 5) {
            for (int y = -2; y <= 3; y++) {
                for (int x = 30; x >= -33; x -= 7) {
                    boxOrder.add(new int[]{x, y, z});
                }
            }
        }
        boxOrder.sort((a, b) -> CraftingStationPolicy.compareScanOrder(a[0], a[1], a[2], b[0], b[1], b[2]));
        for (int i = 1; i < boxOrder.size(); i++) {
            int[] a = boxOrder.get(i - 1), b = boxOrder.get(i);
            assertTrue(a[2] < b[2] || (a[2] == b[2] && (a[1] < b[1] || (a[1] == b[1] && a[0] < b[0]))),
                    "cell " + i + " out of iterate order");
        }
        // Section by section, then sorted: a hit in a later chunk column but a lower z comes first.
        List<int[]> hits = new java.util.ArrayList<>(List.of(
                new int[]{20, 64, 3}, new int[]{-5, 64, 3}, new int[]{-5, 70, 1}, new int[]{40, 60, 1}));
        hits.sort((a, b) -> CraftingStationPolicy.compareScanOrder(a[0], a[1], a[2], b[0], b[1], b[2]));
        assertEquals(List.of("40,60,1", "-5,70,1", "-5,64,3", "20,64,3"),
                hits.stream().map(c -> c[0] + "," + c[1] + "," + c[2]).toList());
        assertEquals(0, CraftingStationPolicy.compareScanOrder(1, 2, 3, 1, 2, 3));
    }

    @Test
    void tableAndChestScansReadThroughTheSectionSkippingHelper() throws IOException {
        String source = craftingHelperSource();
        String helper = bodyOf(source, "private static List<BlockPos> findBlocksInBox(");
        assertTrue(helper.contains("new ChunkBlockReader(world)") && helper.contains("section.hasAny(match)"),
                "the box scan reads through one reader and skips sections without a match");
        assertTrue(helper.contains("CraftingStationPolicy.compareScanOrder("), "hits keep the whole-box order");
        assertEquals(3, count(source, "CraftingHelper::isCraftingTable,"), "the table scan, its re-scan and the pre-check");
        assertEquals(2, count(source, "CraftingHelper::isChest,"), "both findNearbyChests overloads");
    }

    // ── walking, nudging and carving ─────────────────────────────────────────────────────────

    @Test
    void workersMayWalkAndCarveTheServerThreadMayDoNeither() {
        assertTrue(CraftingStationPolicy.mayWalk(WORKER));
        assertTrue(CraftingStationPolicy.mayCarve(WORKER));
        assertFalse(CraftingStationPolicy.mayWalk(SERVER));
        assertFalse(CraftingStationPolicy.mayCarve(SERVER));
    }

    // ── room for a craft's output ────────────────────────────────────────────────────────────

    @Test
    void onlyWorkersMayOffloadToAChestForRoom() {
        assertTrue(CraftingStationPolicy.mayOffloadToChest(WORKER));
        assertFalse(CraftingStationPolicy.mayOffloadToChest(SERVER));
    }

    @Test
    void aStickSubCraftRefusedForAFullPackIsNotReportedAsMissingSticks() throws IOException {
        assertTrue(CraftingStationPolicy.reportStickShortfall(false));
        assertFalse(CraftingStationPolicy.reportStickShortfall(true));
        String source = craftingHelperSource();
        // The refusal before a craft is recorded; the after-craft path does not record one.
        String room = bodyOf(source, "private static boolean makeRoomForOutput(");
        int afterCraft = room.indexOf("if (!afterCraft) {");
        int flag = room.indexOf("LAST_CRAFT_REFUSED_FULL_PACK.set(Boolean.TRUE);");
        assertTrue(afterCraft >= 0 && flag > afterCraft, "only a pre-craft refusal sets the flag");
        assertTrue(room.contains("sendFullPackCraftLine(bot, source, output, quiet);"), "the refusal honours quiet");
        String line = bodyOf(source, "private static void sendFullPackCraftLine(");
        int quietReturn = line.indexOf("if (quiet) {");
        assertTrue(quietReturn >= 0 && quietReturn < line.indexOf("say(source,")
                && quietReturn < line.indexOf("CRAFT_FULL_PACK_LINE.put("), "a quiet refusal neither chats nor eats the throttle");
        // ensureSticks clears the flag before anything else.
        String sticks = bodyOf(source, "private static boolean ensureSticks(ServerPlayerEntity bot, ServerCommandSource source, int needed, boolean quiet)");
        assertTrue(sticks.indexOf("LAST_CRAFT_REFUSED_FULL_PACK.set(Boolean.FALSE);") < sticks.indexOf("craftSticks("));
        // Every stick shortfall line asks first: ladder, torch, fence, tool, fishing rod.
        assertEquals(5, count(source, "CraftingStationPolicy.reportStickShortfall("));
        String tool = bodyOf(source, "private static int craftToolWithMaterial(");
        assertTrue(tool.indexOf("CraftingStationPolicy.reportStickShortfall(packFull)") < tool.indexOf("\"Missing sticks. Need \""));
        assertTrue(tool.contains("shortfall.message = fullPackCraftLine(Items.STICK);"), "a strict craft names the full pack");
        String fence = source.substring(source.indexOf("boolean stickCraftRefused = takeFullPackRefusal();"));
        assertTrue(fence.indexOf("reportStickShortfall(stickCraftRefused)") < fence.indexOf("reportMissing(source, \"fence\""));
        // The stick and table sub-crafts pass quiet through.
        assertTrue(bodyOf(source, "private static int craftWithPlanks(").contains("Map.of(), quiet)"));
    }

    @Test
    void batchCookOffloadsToAChestOnlyOffTheServerThread() throws IOException {
        String smelting = mainSource("src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java");
        String body = bodyOf(smelting, "private static boolean startBatchCookInternal(");
        assertGuarded(body, "CraftingHelper.offloadCheapItemsToNearbyChest(", "CraftingStationPolicy.mayOffloadToChest(");
        assertTrue(body.contains("source.getServer().isOnThread()"));
    }

    @Test
    void makingRoomForAnOutputAsksThePolicyBeforeAnyChestOffload() throws IOException {
        String source = craftingHelperSource();
        String body = bodyOf(source, "private static boolean makeRoomForOutput(");
        assertGuarded(body, "offloadCheapItemsToNearbyChest(", "CraftingStationPolicy.mayOffloadToChest(");
        int gate = body.indexOf("CraftingStationPolicy.mayOffloadToChest(");
        int offload = body.indexOf("offloadCheapItemsToNearbyChest(");
        String serverSide = body.substring(gate, offload);
        assertTrue(serverSide.contains("dropStackForSpace(") && serverSide.contains(", true)"),
                "the server thread drops a cheap stack only");
        assertTrue(serverSide.contains("return false;"), "the server thread refuses when nothing cheap drops");
        assertTrue(serverSide.contains("if (!afterCraft)"), "no refusal line once the ingredients are spent");
        // The class's only chest offload for an output is the gated one.
        assertEquals(1, count(source, "offloadCheapItemsToNearbyChest(bot, source,"));
        assertTrue(bodyOf(source, "private static boolean ensureInventorySpaceForOutput(")
                .contains("ensureInventorySpaceForSubCraft("), "the pre-craft entry shares the sub-craft entry");
        assertTrue(bodyOf(source, "private static boolean ensureInventorySpaceForSubCraft(")
                .contains("makeRoomForOutput("), "the pre-craft entry goes through the gated method");
        String distribute = bodyOf(source, "private static void distributeOutput(");
        assertEquals(2, count(distribute, "makeRoomForOutput("), "both output make-room calls are gated");
        assertEquals(2, count(distribute, "true, true);") + count(distribute, "false, true);"),
                "both are marked afterCraft");
        assertTrue(distribute.contains("bot.dropItem(stack, false, false)"), "an output that does not fit is dropped");
    }

    @Test
    void everyCraftRefusesBeforeUsingIngredientsWhenThereIsNoRoom() throws IOException {
        String source = craftingHelperSource();
        assertFalse(java.util.regex.Pattern.compile("(?m)^\\s+ensureInventorySpaceForOutput\\(").matcher(source).find(),
                "a pre-craft make-room call ignores its answer");
        assertFalse(java.util.regex.Pattern.compile("(?m)^\\s+ensureInventorySpaceForSubCraft\\(").matcher(source).find(),
                "a sub-craft make-room call ignores its answer");
        assertTrue(count(source, "if (!ensureInventorySpaceForOutput(bot, source, ")
                        + count(source, "if (!ensureInventorySpaceForSubCraft(bot, source, ") >= 20,
                "the craft methods check the make-room answer");
    }

    // ── which tables count ───────────────────────────────────────────────────────────────────

    @Test
    void workersAcceptAnyKnownTableTheServerThreadOnlyOneInReach() {
        assertTrue(CraftingStationPolicy.acceptKnownTable(WORKER, true));
        assertTrue(CraftingStationPolicy.acceptKnownTable(WORKER, false));
        assertTrue(CraftingStationPolicy.acceptKnownTable(SERVER, true));
        assertFalse(CraftingStationPolicy.acceptKnownTable(SERVER, false));
    }

    // ── scan box ─────────────────────────────────────────────────────────────────────────────

    @Test
    void workersKeepTheWideScanTheServerThreadScansReachOnly() {
        assertEquals(40, CraftingStationPolicy.WORKER_SCAN_HORIZONTAL);
        assertEquals(6, CraftingStationPolicy.WORKER_SCAN_VERTICAL);
        assertEquals(CraftingStationPolicy.WORKER_SCAN_HORIZONTAL, CraftingStationPolicy.scanHorizontal(WORKER));
        assertEquals(CraftingStationPolicy.WORKER_SCAN_VERTICAL, CraftingStationPolicy.scanVertical(WORKER));
        assertEquals(CraftingStationPolicy.TICK_SCAN_HORIZONTAL, CraftingStationPolicy.scanHorizontal(SERVER));
        assertEquals(CraftingStationPolicy.TICK_SCAN_VERTICAL, CraftingStationPolicy.scanVertical(SERVER));
        assertTrue(CraftingStationPolicy.scanHorizontal(SERVER) < CraftingStationPolicy.scanHorizontal(WORKER));
    }

    @Test
    void tickScanHoldsEveryBlockInReachFromAnywhereInTheBotsBlock() {
        int h = CraftingStationPolicy.scanHorizontal(SERVER);
        int v = CraftingStationPolicy.scanVertical(SERVER);
        for (int dx = -9; dx <= 9; dx++) {
            for (int dy = -9; dy <= 9; dy++) {
                for (int dz = -9; dz <= 9; dz++) {
                    if (nearestReachSq(dx, dy, dz) <= STATION_REACH * STATION_REACH) {
                        assertTrue(Math.abs(dx) <= h && Math.abs(dy) <= v && Math.abs(dz) <= h,
                                "block at offset " + dx + "," + dy + "," + dz + " can be in reach but is outside the scan");
                    }
                }
            }
        }
    }

    @Test
    void tickScanIsNoWiderThanReach() {
        int h = CraftingStationPolicy.scanHorizontal(SERVER);
        int v = CraftingStationPolicy.scanVertical(SERVER);
        // The outermost layer of the box can still be in reach, so the box is not oversized...
        assertTrue(nearestReachSq(-h, 0, 0) <= STATION_REACH * STATION_REACH);
        assertTrue(nearestReachSq(0, -v, 0) <= STATION_REACH * STATION_REACH);
        // ...and one block further never is.
        assertTrue(nearestReachSq(h + 1, 0, 0) > STATION_REACH * STATION_REACH);
        assertTrue(nearestReachSq(-(h + 1), 0, 0) > STATION_REACH * STATION_REACH);
        assertTrue(nearestReachSq(0, v + 1, 0) > STATION_REACH * STATION_REACH);
        assertTrue(nearestReachSq(0, -(v + 1), 0) > STATION_REACH * STATION_REACH);
    }

    /**
     * The squared distance from the center of the block at this offset to the nearest point a bot
     * whose feet are inside block (0,0,0) can stand at: the closest the table can ever be.
     */
    private static double nearestReachSq(int dx, int dy, int dz) {
        double x = axisGap(dx + 0.5D);
        double y = axisGap(dy + 0.5D);
        double z = axisGap(dz + 0.5D);
        return x * x + y * y + z * z;
    }

    private static double axisGap(double center) {
        if (center < 0.0D) {
            return -center;
        }
        return center > 1.0D ? center - 1.0D : 0.0D;
    }

    // ── cooldown and logging ─────────────────────────────────────────────────────────────────

    @Test
    void onlyWorkersArmTheReachFailCooldown() {
        assertTrue(CraftingStationPolicy.armsReachFailCooldown(WORKER));
        assertFalse(CraftingStationPolicy.armsReachFailCooldown(SERVER));
    }

    @Test
    void stepLinesStayLoudOnWorkersAndDropToDebugOnTheServerThread() {
        assertTrue(CraftingStationPolicy.logAtInfo(WORKER));
        assertFalse(CraftingStationPolicy.logAtInfo(SERVER));
    }

    @Test
    void automaticCraftTableLinesAreThrottled() {
        // 1.1.234 #2: a worker tool craft sent "I couldn't place a crafting table here." once per material.
        assertFalse(CraftingStationPolicy.shouldSendTableLine(false, false, true));
        assertTrue(CraftingStationPolicy.shouldSendTableLine(false, false, false));
    }

    @Test
    void playerCraftIgnoresTheThrottleButSaysOneTableLinePerCall() {
        // 1.1.234 fix wave #2: an earlier automatic craft's cooldown silenced /bot craft.
        assertTrue(CraftingStationPolicy.shouldSendTableLine(true, false, true));
        assertTrue(CraftingStationPolicy.shouldSendTableLine(true, false, false));
        assertFalse(CraftingStationPolicy.shouldSendTableLine(true, true, false));
        assertFalse(CraftingStationPolicy.shouldSendTableLine(true, true, true));
    }

    @Test
    void playerCraftThatFailedSilentlyStillAnswersOnce() {
        assertTrue(CraftingStationPolicy.needsFallbackLine(0, false, false));
        assertFalse(CraftingStationPolicy.needsFallbackLine(0, true, false), "something was already said");
        assertFalse(CraftingStationPolicy.needsFallbackLine(0, false, true), "stopped by the player");
        assertFalse(CraftingStationPolicy.needsFallbackLine(1, false, false), "crafted");
        assertEquals("I couldn't craft stone axe.", CraftingStationPolicy.fallbackLine("stone_axe"));
        assertEquals("I couldn't craft that.", CraftingStationPolicy.fallbackLine(" "));
    }

    @Test
    void craftCommandGoesThroughThePlayerEntry() throws IOException {
        String registry = mainSource("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java");
        assertTrue(registry.contains("CraftingHelper.craftGenericForPlayer("), "/bot craft must use the explicit entry");
    }

    @Test
    void declineLineIsWrittenOnFirstCallThenAtMostEveryThirtySeconds() {
        assertEquals(30_000L, CraftingStationPolicy.DECLINE_LOG_INTERVAL_MS);
        long last = 1_000_000L;
        assertTrue(CraftingStationPolicy.shouldLogDecline(last, null), "first call for a bot");
        assertTrue(CraftingStationPolicy.shouldLogDecline(0L, null), "first call at time zero");
        assertFalse(CraftingStationPolicy.shouldLogDecline(last, last), "same millisecond");
        assertFalse(CraftingStationPolicy.shouldLogDecline(last + 1_000L, last), "RideSync's next retry");
        assertFalse(CraftingStationPolicy.shouldLogDecline(last + 29_999L, last));
        assertTrue(CraftingStationPolicy.shouldLogDecline(last + 30_000L, last));
        assertTrue(CraftingStationPolicy.shouldLogDecline(last + 90_000L, last));
    }

    @Test
    void tableCraftWaitsOutTheNoPlanksWindowUnlessThePlanksAreHeld() {
        assertEquals(15_000L, CraftingStationPolicy.TABLE_NO_PLANKS_RETRY_MS);
        long now = 1_000_000L;
        long until = now + CraftingStationPolicy.TABLE_NO_PLANKS_RETRY_MS;
        assertTrue(CraftingStationPolicy.mayTryTableCraft(null, now, 0), "never failed: try");
        assertFalse(CraftingStationPolicy.mayTryTableCraft(until, now, 0), "just failed, still no planks");
        assertFalse(CraftingStationPolicy.mayTryTableCraft(until, now + 14_999L, 3), "three planks are not a table");
        assertTrue(CraftingStationPolicy.mayTryTableCraft(until, until, 0), "the window has passed");
        assertTrue(CraftingStationPolicy.mayTryTableCraft(until, until + 60_000L, 0));
        // 1.1.266 C2 field check: given 7 planks, /bot craft crafting_table crafts at once.
        assertTrue(CraftingStationPolicy.mayTryTableCraft(until, now, 7), "held planks bypass the window");
        assertTrue(CraftingStationPolicy.mayTryTableCraft(until, now, 4), "exactly one table's worth");
    }

    // ── source pins ──────────────────────────────────────────────────────────────────────────

    @Test
    void placementNeverCarvesWithoutAskingThePolicy() throws IOException {
        String body = bodyOf(craftingHelperSource(), "static PreparedPlacement prepareNearbyUtilityPlacement(");
        assertGuarded(body, "carvePlacementPocket(", "CraftingStationPolicy.mayCarve(");
        assertGuarded(body, "moveToPlacementStand(", "CraftingStationPolicy.mayWalk(");
    }

    @Test
    void ensureCraftingStationAsksThePolicyBeforeWalkingOrArmingTheCooldown() throws IOException {
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        assertTrue(body.contains("CraftingStationPolicy.scanHorizontal(")
                && body.contains("CraftingStationPolicy.scanVertical("), "the scan box must come from the policy");
        assertEquals(3, count(body, "CraftingStationPolicy.acceptKnownTable("),
                "remembered, commander-looked-at and scanned tables all pass acceptKnownTable");
        assertGuarded(body, "CRAFT_TABLE_REACH_FAILURE.put(", "CraftingStationPolicy.armsReachFailCooldown(");
        assertGuarded(body, "retryCraftingTablePlacementAfterLocalReposition(", "CraftingStationPolicy.mayWalk(");
        assertEquals(2, count(body, "usePlacedTableWithoutWalking("),
                "both placement branches hand the server thread off before the stand walk");
    }

    @Test
    void onlyAMadeTableArmsTheTableCraftCooldown() throws IOException {
        // Field bug C2: a craft that failed for want of planks armed the 60 s cooldown, so a retry
        // with enough planks skipped crafting the table without a word.
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        int craft = body.indexOf("craftCraftingTable(");
        int arm = body.indexOf("CRAFT_TABLE_CRAFT_COOLDOWN.put(");
        assertTrue(craft >= 0 && arm > craft, "the cooldown is armed after the table craft, never before it");
        assertEquals(1, count(body, "CRAFT_TABLE_CRAFT_COOLDOWN.put("));
        assertGuarded(body, "CRAFT_TABLE_CRAFT_COOLDOWN.put(", "if (crafted)");
    }

    @Test
    void aCraftThatFoundNoPlanksHoldsBackTheNextTryAndItsChestPulls() throws IOException {
        // M1 (1.1.267): a no-planks call pulled from chests on every station call.
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        int check = body.indexOf("CraftingStationPolicy.mayTryTableCraft(");
        int pull = body.indexOf("ensurePlanksAvailable(");
        int craft = body.indexOf("craftCraftingTable(");
        int arm = body.indexOf("TABLE_CRAFT_NO_PLANKS_UNTIL.put(");
        assertTrue(check >= 0 && check < pull && pull < craft, "the window is checked before the chest pull");
        assertTrue(arm > craft, "the window is armed after the failed craft");
        assertEquals(1, count(body, "TABLE_CRAFT_NO_PLANKS_UNTIL.put("));
        assertGuarded(body, "TABLE_CRAFT_NO_PLANKS_UNTIL.put(", "< CraftingStationPolicy.TABLE_PLANKS");
        String skip = blockAfter(body, check);
        assertTrue(skip.contains("logStationStep(") && skip.contains("declineIfTickSide("),
                "the skip logs and declines like the cooldown skip");
    }

    @Test
    void aQuietStationLeavesTheTableLinesToItsCaller() throws IOException {
        // Field bug C: the tool craft's shortfall line names the table, so the station stays quiet.
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        assertGuarded(body, "tellStationFailure(", "if (!quiet)");
        assertEquals(4, count(body, "tellStationFailure("), "both place-from-inventory and both crafted-table lines");
        assertGuarded(body, "sendCraftTableNeededOnce(", "if (!quiet)");
        String helper = craftingHelperSource();
        assertTrue(helper.contains("public static boolean ensureCraftingStation(ServerPlayerEntity bot, ServerCommandSource source) {\n"
                        + "        return ensureCraftingStation(bot, source, false);"),
                "the public form keeps its chat lines");
    }

    @Test
    void theServerThreadSplitComesBeforeAnyWalkAndEndsTheCall() throws IOException {
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        int gate = body.indexOf(TICK_GATE);
        assertTrue(gate >= 0, "ensureCraftingStation lost its server-thread split");
        for (String walk : List.of("clearPathObstructions(", "MovementService.")) {
            int first = body.indexOf(walk);
            assertTrue(first >= 0, walk + " not found");
            assertTrue(gate < first, "the server-thread split must come before the first " + walk);
        }
        String split = blockAfter(body, gate);
        assertTrue(split.replaceAll("\\s+", " ").endsWith("return false; }"),
                "the server-thread split must return, never fall through to the walks below it");
        for (String walk : List.of("clearPathObstructions(", "MovementService.", "tickAndCheckStuck(")) {
            assertFalse(split.contains(walk), "the server-thread split reaches " + walk);
        }
    }

    @Test
    void theServerThreadClearsTheChatAndReachCooldownsOnlyForAUsableTable() throws IOException {
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        String split = blockAfter(body, body.indexOf(TICK_GATE));
        String usable = blockAfter(split, split.indexOf("if (ensureStationInteractable("));
        for (String clear : List.of("clearTableLineCooldown(bot)", "CRAFT_TABLE_REACH_FAILURE.remove(")) {
            assertTrue(usable.contains(clear), clear + " must run once the in-reach table is usable");
            assertEquals(1, count(split, clear),
                    clear + " must not run for a blocked table, or its caller chats every call");
        }
        assertTrue(split.indexOf("logTickSideDecline(") > split.indexOf(usable) + usable.length(),
                "a blocked table is a decline");
    }

    @Test
    void theServerThreadForgetsAGoneRememberedTableBeforeItsReachTest() throws IOException {
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        int forget = body.indexOf("LAST_KNOWN_CRAFTING_TABLE.remove(");
        assertTrue(forget >= 0 && forget < body.indexOf("CraftingStationPolicy.acceptKnownTable("),
                "a gone remembered table must be forgotten before the reach test, or a miss says far-table");
        String check = body.substring(body.lastIndexOf("if (", forget), forget);
        assertTrue(check.contains("onServerThread") && check.contains("isChunkLoaded(pos)")
                        && check.contains("isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)"),
                "the early check is server-thread only and never loads a chunk: " + check);
    }

    @Test
    void aServerThreadPlacementMissLogsItsSummaryAtDebug() throws IOException {
        String source = craftingHelperSource();
        String body = bodyOf(source, "static PreparedPlacement prepareNearbyUtilityPlacement(");
        int failed = body.indexOf("attemptLog.flush(\"failed\"");
        assertTrue(failed >= 0, "the failed placement summary is gone");
        assertTrue(body.substring(failed, body.indexOf(';', failed)).contains("!CraftingStationPolicy.logAtInfo(onServerThread)"),
                "a server-thread miss (retried every second) must not WARN");
        String flush = bodyOf(source,
                "private void flush(String outcome, PreparedPlacement prepared, BlockPos botPos, boolean warnOnFailure, boolean atDebug)");
        int debug = flush.indexOf("if (atDebug)");
        assertTrue(debug >= 0 && debug < flush.indexOf("LOGGER.warn(") && debug < flush.indexOf("LOGGER.info("),
                "atDebug must win over both the WARN and the INFO summary");
        assertTrue(blockAfter(flush, debug).contains("LOGGER.debug("), "atDebug logs at DEBUG");
    }

    @Test
    void theGreppedDeclineLineIsWrittenOnce() throws IOException {
        String source = craftingHelperSource();
        assertEquals(1, count(source, "\"" + DECLINE_LINE + "\""), "the field checklist greps this exact line");
        String logger = bodyOf(source, "private static void logTickSideDecline(");
        assertTrue(logger.contains("CraftingStationPolicy.shouldLogDecline("), "the line is rate-limited by the policy");
    }

    @Test
    void aStrictToolCraftKeepsTheStickSubStepQuiet() throws IOException {
        String source = craftingHelperSource();
        String sticks = bodyOf(source, "int amount, boolean quiet) {");
        assertGuarded(sticks, "sendCraftTableNeededOnce(", "if (!quiet)");
        assertGuarded(sticks, "say(source,", "if (!quiet)");
        String ensure = bodyOf(source,
                "private static boolean ensureSticks(ServerPlayerEntity bot, ServerCommandSource source, int needed, boolean quiet)");
        assertTrue(ensure.contains("craftSticks(bot, source, source.getPlayer(), missing, quiet)"),
                "ensureSticks passes its quiet flag on");
        assertEquals(1, count(source, "ensureSticks(bot, source, neededSticks, shortfall != null)"),
                "the tool craft's stick step is quiet exactly when it reports a shortfall itself");
        assertTrue(bodyOf(source,
                        "private static boolean ensureSticks(ServerPlayerEntity bot, ServerCommandSource source, int needed) {")
                        .contains("ensureSticks(bot, source, needed, false)"),
                "other callers keep the stick lines");
    }

    /**
     * Every occurrence of {@code call} in {@code body} has a {@code guard} after the previous
     * occurrence of {@code call} and before it, and there is at least one.
     */
    private static void assertGuarded(String body, String call, String guard) {
        int from = 0;
        int seen = 0;
        for (int at = body.indexOf(call); at >= 0; at = body.indexOf(call, at + call.length())) {
            int guardAt = body.lastIndexOf(guard, at);
            assertTrue(guardAt >= from, call + " #" + (seen + 1) + " is not guarded by " + guard);
            from = at + call.length();
            seen++;
        }
        assertTrue(seen > 0, call + " not found");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static String craftingHelperSource() throws IOException {
        return mainSource("src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java");
    }

    private static String mainSource(String relative) throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = dir; at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException(relative + " not found above " + dir);
    }

    /** The block, braces included, that opens at the first brace after {@code from}. */
    private static String blockAfter(String text, int from) {
        assertTrue(from >= 0, "block start not found");
        int open = text.indexOf('{', from);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced block at " + from);
    }

    /** The body, braces included, of the one declaration starting with {@code signature}. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, signature + " not found");
        assertEquals(-1, source.indexOf(signature, at + 1), signature + " declared more than once");
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError(signature + ": unbalanced body");
    }

    @Test
    void tableCraftedAsAStationSubStepIsQuietOnlyWhenTheStationIs() throws IOException {
        String body = bodyOf(craftingHelperSource(), "public static boolean craftCraftingTable(");
        assertFalse(body.contains("ChatUtils.sendSystemMessage("), "the sub-step table craft has no lines of its own");
        assertFalse(body.contains("say("), "the sub-step table craft has no lines of its own");
        // Review I1: a hard-coded quiet lost the planks line for a non-strict craft, which then
        // only said it needed a table. The planks line comes from craftWithPlanks when not quiet.
        assertTrue(body.contains("Items.CRAFTING_TABLE, amountRequested, quiet);"),
                "the table craft passes the station's quiet through to craftWithPlanks");
        String station = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        assertTrue(station.contains("craftCraftingTable(source, bot, source.getPlayer(), 1, quiet)"),
                "the station hands its own quiet to the table sub-step");
    }

    // ── tool material order ──────────────────────────────────────────────────────────────────

    @Test
    void nonStrictCraftTriesHeldMaterialsFirstThenTheRestInOrder() {
        List<String> order = List.of("diamond", "iron", "stone", "wood");
        List<String> held = List.of("stone", "wood");
        assertEquals(List.of("stone", "wood", "diamond", "iron"),
                CraftingStationPolicy.materialsToTry(order, held::contains, false));
    }

    @Test
    void nonStrictCraftWithNothingHeldTriesEveryMaterialSoAChestPullCanFeedIt() {
        // 1.1.234 fix wave #1: an empty inventory with planks in a chest used to try only diamond.
        List<String> order = List.of("diamond", "iron", "stone", "wood");
        assertEquals(order, CraftingStationPolicy.materialsToTry(order, m -> false, false));
    }

    @Test
    void nonStrictCraftHoldingEverythingKeepsTheUsualOrder() {
        List<String> order = List.of("diamond", "iron", "stone", "wood");
        assertEquals(order, CraftingStationPolicy.materialsToTry(order, m -> true, false));
    }

    @Test
    void strictCraftTriesEveryNamedMaterialHeldOrNot() {
        List<String> order = List.of("cobblestone", "cobbled_deepslate", "blackstone");
        assertEquals(order, CraftingStationPolicy.materialsToTry(order, m -> false, true));
    }

    @Test
    void emptyMaterialListTriesNothing() {
        assertTrue(CraftingStationPolicy.materialsToTry(List.<String>of(), m -> true, false).isEmpty());
        assertTrue(CraftingStationPolicy.materialsToTry((List<String>) null, m -> true, false).isEmpty());
    }

    @Test
    void reachCooldownSkipsOnlyTheFailedTableInsideTheWindow() {
        // Finding 13: a buried table's reach failure hid a reachable table 15 blocks away.
        long failed = 123_456_789L;
        long other = 987_654_321L;
        assertTrue(CraftingStationPolicy.skipForReachCooldown(failed, failed, 0L, 30_000L));
        assertTrue(CraftingStationPolicy.skipForReachCooldown(failed, failed, 29_999L, 30_000L));
        assertFalse(CraftingStationPolicy.skipForReachCooldown(failed, failed, 30_000L, 30_000L), "boundary is expired");
        assertFalse(CraftingStationPolicy.skipForReachCooldown(failed, failed, 45_000L, 30_000L));
        assertFalse(CraftingStationPolicy.skipForReachCooldown(failed, other, 1_000L, 30_000L), "another table is still tried");
        assertFalse(CraftingStationPolicy.skipForReachCooldown(failed, failed, -5L, 30_000L), "clock went back: expired");
    }

    @Test
    void aFailedReachForgetsTheRememberedTableAndEveryPickAsksAboutThatTable() throws IOException {
        String body = bodyOf(craftingHelperSource(), STATION_SIGNATURE);
        int arm = body.indexOf("CRAFT_TABLE_REACH_FAILURE.put(");
        assertTrue(arm >= 0);
        assertTrue(blockAfter(body, body.lastIndexOf("if (", arm)).contains("LAST_KNOWN_CRAFTING_TABLE.remove("),
                "the failed table must not stay remembered, or the next call never scans");
        assertTrue(body.substring(arm, body.indexOf(';', arm)).contains("new ReachFail("), "the failure records its table");
        assertEquals(5, count(body, "skipsFailedTable("),
                "remembered, looked-at, scanned, chosen and no-standable-spot alternative tables");
        assertFalse(body.contains("reachCooldownActive"), "no blanket cooldown left");
    }

    @Test
    void unknownCraftKeyIsOnePerBotAndItemAndSharesTheBotPrefix() {
        // Finding 7: /bot craft spaceship repeated the line on every call.
        String a = CraftingStationPolicy.unknownCraftKey("bot-a", "spaceship");
        assertEquals(a, CraftingStationPolicy.unknownCraftKey("bot-a", " Spaceship "));
        assertFalse(a.equals(CraftingStationPolicy.unknownCraftKey("bot-a", "rocket")));
        assertFalse(a.equals(CraftingStationPolicy.unknownCraftKey("bot-b", "spaceship")));
        String prefix = CraftingStationPolicy.unknownCraftKey("bot-a", "");
        assertTrue(a.startsWith(prefix));
        assertFalse(CraftingStationPolicy.unknownCraftKey("bot-ab", "x").startsWith(prefix));
    }

    @Test
    void aPlayersUnknownCraftLineIsThrottledAndAHeldBackLineStillCountsAsAnAnswer() throws IOException {
        String body = bodyOf(craftingHelperSource(), "private static void sendUnknownCraftNameOnce(");
        int due = body.indexOf("LogThrottle.due(UNKNOWN_CRAFT_EXPLICIT_MSG");
        assertTrue(due >= 0, "the explicit branch goes through LogThrottle.due");
        int elseAt = body.indexOf("} else {", due);
        assertTrue(elseAt > due && body.substring(elseAt, body.indexOf('}', elseAt + 8)).contains("call.saidAnything = true"),
                "a suppressed line must still mark the call answered, or the fallback line replaces it");
        assertFalse(body.contains("explicitCall(bot) != null) {\n            say("), "no unthrottled explicit branch");
    }

    @Test
    void plankEquivalentCountsFourPlanksPerLog() {
        assertEquals(0, CraftingStationPolicy.plankEquivalent(0, 0));
        assertEquals(3, CraftingStationPolicy.plankEquivalent(3, 0));
        assertEquals(6, CraftingStationPolicy.plankEquivalent(2, 1));
        assertEquals(4, CraftingStationPolicy.plankEquivalent(-1, 1));
    }

    @Test
    void bedPlanksCountTheTableWhenOneMustBeCrafted() {
        // Finding 4 follow-up: one log is 4 planks; that passes 3 but not 3 + a 4-plank table.
        assertEquals(7, CraftingStationPolicy.bedPlanksNeeded(1, true));
        assertEquals(3, CraftingStationPolicy.bedPlanksNeeded(1, false));
        assertEquals(10, CraftingStationPolicy.bedPlanksNeeded(2, true));
        assertEquals(6, CraftingStationPolicy.bedPlanksNeeded(2, false));
        assertTrue(CraftingStationPolicy.bedPlanksNeeded(1, true) > 4);
    }

    // ── idle stone upgrade: strict stone, quiet (finding 58, 1.1.279) ──────────────────────

    private record Mat(String name, String item) {
    }

    private static final List<Mat> TOOL_MATERIALS = List.of(
            new Mat("diamond", "diamond"), new Mat("iron", "iron_ingot"), new Mat("stone", "cobblestone"),
            new Mat("stone", "cobbled_deepslate"), new Mat("stone", "blackstone"), new Mat("wood", "oak_planks"));

    @Test
    void strictStoneWithOnlyDiamondHeldTriesOnlyStone() {
        List<Mat> strict = CraftingStationPolicy.strictMaterials(TOOL_MATERIALS, Mat::name, "stone",
                () -> new Mat("gold", "gold_ingot"));
        List<Mat> toTry = CraftingStationPolicy.materialsToTry(strict, m -> m.name().equals("diamond"), true);
        assertEquals(List.of("cobblestone", "cobbled_deepslate", "blackstone"), toTry.stream().map(Mat::item).toList());
        // The non-strict path is what crafted the diamond tools: held diamond goes first.
        assertEquals("diamond", CraftingStationPolicy.materialsToTry(TOOL_MATERIALS, m -> m.name().equals("diamond"), false)
                .get(0).name());
    }

    @Test
    void strictGoldIsOfferedOnlyWhenNamed() {
        assertEquals(List.of(new Mat("gold", "gold_ingot")),
                CraftingStationPolicy.strictMaterials(TOOL_MATERIALS, Mat::name, "gold", () -> new Mat("gold", "gold_ingot")));
        assertTrue(CraftingStationPolicy.strictMaterials(TOOL_MATERIALS, Mat::name, "stone", () -> new Mat("gold", "gold_ingot"))
                .stream().noneMatch(m -> m.name().equals("gold")));
    }

    @Test
    void quietSuppressesTheTableLineForAnAutomaticCraft() {
        assertFalse(CraftingStationPolicy.shouldSendTableLine(false, false, false, true));
        assertTrue(CraftingStationPolicy.shouldSendTableLine(false, false, false, false));
        // A player's own craft is never quiet.
        assertTrue(CraftingStationPolicy.shouldSendTableLine(true, false, false, true));
        assertTrue(CraftingStationPolicy.chatSuppressed(true, false));
        assertFalse(CraftingStationPolicy.chatSuppressed(true, true));
        assertFalse(CraftingStationPolicy.chatSuppressed(false, false));
    }

    @Test
    void onlyAnExplicitCraftClearsTheTableLineThrottle() {
        assertTrue(CraftingStationPolicy.mayClearTableLineCooldown(true));
        assertFalse(CraftingStationPolicy.mayClearTableLineCooldown(false));
    }

    @Test
    void idleStoneUpgradeCraftsNamedStoneToolsQuietly() throws IOException {
        String idle = bodyOf(mainSource("src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java"),
                "private static boolean maybeHandleIdleStoneToolUpgrades(");
        for (String tool : List.of("stone_pickaxe", "stone_sword", "stone_axe", "stone_shovel")) {
            assertTrue(idle.contains("CraftingHelper.craftQuietlyKeeping(source, bot, commander, \"" + tool + "\""),
                    tool + " not crafted through the quiet strict path");
        }
        assertFalse(idle.contains("CraftingHelper.craftGeneric("), "idle upgrade still calls the non-strict craftGeneric");
        assertTrue(idle.contains("StoneUpgradePolicy.batchReserve("), "idle upgrade does not reserve for the batch");
        String helper = craftingHelperSource();
        String quiet = bodyOf(helper, "public static int craftQuietlyKeeping(");
        assertTrue(quiet.contains("QUIET.set(Boolean.TRUE)") && quiet.contains("finally"), "quiet not set with try/finally");
        assertTrue(bodyOf(helper, "private static void say(").contains("QUIET.get()"), "say() ignores quiet");
        assertEquals(1, count(helper, "CRAFT_TABLE_MSG_COOLDOWN.remove("),
                "the table-line throttle is cleared outside clearTableLineCooldown");
        assertTrue(bodyOf(helper, "private static boolean keepsStackForCraft(").contains("keepsStackForBatch("),
                "the make-room drop ignores the batch reserve");
        assertTrue(bodyOf(helper, "private static boolean hasRoomForItem(").contains("PlayerInventory.MAIN_SIZE"),
                "hasRoomForItem counts armor/offhand slots as room");
    }
}
