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

    // ── walking, nudging and carving ─────────────────────────────────────────────────────────

    @Test
    void workersMayWalkAndCarveTheServerThreadMayDoNeither() {
        assertTrue(CraftingStationPolicy.mayWalk(WORKER));
        assertTrue(CraftingStationPolicy.mayCarve(WORKER));
        assertFalse(CraftingStationPolicy.mayWalk(SERVER));
        assertFalse(CraftingStationPolicy.mayCarve(SERVER));
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
    void placementFailureChatIsThrottledOnlyOnTheServerThread() {
        assertFalse(CraftingStationPolicy.throttlesFailureChat(WORKER));
        assertTrue(CraftingStationPolicy.throttlesFailureChat(SERVER));
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

    // ── source pins ──────────────────────────────────────────────────────────────────────────

    @Test
    void placementNeverCarvesWithoutAskingThePolicy() throws IOException {
        String body = bodyOf(craftingHelperSource(), "static PreparedPlacement prepareNearbyUtilityPlacement(");
        assertGuarded(body, "carvePlacementPocket(", "CraftingStationPolicy.mayCarve(");
        assertGuarded(body, "moveToPlacementStand(", "CraftingStationPolicy.mayWalk(");
    }

    @Test
    void ensureCraftingStationAsksThePolicyBeforeWalkingOrArmingTheCooldown() throws IOException {
        String body = bodyOf(craftingHelperSource(), "public static boolean ensureCraftingStation(");
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
    void theServerThreadSplitComesBeforeAnyWalkAndEndsTheCall() throws IOException {
        String body = bodyOf(craftingHelperSource(), "public static boolean ensureCraftingStation(");
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
        String body = bodyOf(craftingHelperSource(), "public static boolean ensureCraftingStation(");
        String split = blockAfter(body, body.indexOf(TICK_GATE));
        String usable = blockAfter(split, split.indexOf("if (ensureStationInteractable("));
        for (String clear : List.of("CRAFT_TABLE_MSG_COOLDOWN.remove(", "CRAFT_TABLE_REACH_FAILURE.remove(")) {
            assertTrue(usable.contains(clear), clear + " must run once the in-reach table is usable");
            assertEquals(1, count(split, clear),
                    clear + " must not run for a blocked table, or its caller chats every call");
        }
        assertTrue(split.indexOf("logTickSideDecline(") > split.indexOf(usable) + usable.length(),
                "a blocked table is a decline");
    }

    @Test
    void theServerThreadForgetsAGoneRememberedTableBeforeItsReachTest() throws IOException {
        String body = bodyOf(craftingHelperSource(), "public static boolean ensureCraftingStation(");
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
        String relative = "src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java";
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
}
