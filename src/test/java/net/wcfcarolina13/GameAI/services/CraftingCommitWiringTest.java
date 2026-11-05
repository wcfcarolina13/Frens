package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source pins for CraftingHelper's one-hop craft commit: every craft's ingredient takes and its
 * output insert run together in one {@code commitCraft} hop on the server thread, nothing inside a
 * hop waits or walks, and no stack is decremented anywhere but in the server-only bodies.
 * No net.minecraft types.
 */
class CraftingCommitWiringTest {

    private static final String HELPER = "src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java";
    /** The server-only leaf bodies: the only places a live stack is decremented or a slot written. */
    private static final List<String> SERVER_ONLY_BODIES = List.of(
            "private static boolean consumeItemOnServer(",
            "private static boolean consumePlanksOnServer(",
            "private static boolean consumeCobbleOnServer(",
            "private static boolean consumeSlotOnServer(");
    private static final Pattern LEAF_CALL = Pattern.compile(
            "(?<![\\w.])consume(?:Item|Planks|Cobble|Slot)OnServer\\s*\\(");
    /** Anything that waits, walks, talks to a chest or makes room: never inside a hop. */
    private static final Pattern WAITS_OR_WALKS = Pattern.compile(
            "Thread\\s*\\.\\s*sleep|sleepQuiet|moveNearBlock|MovementService|ChestStoreService|SupplyWithdrawals"
                    + "|withdrawFromNearbyChests|ensureItemAvailable|ensurePlanksAvailable|makeRoomForOutput"
                    + "|offloadCheapItemsToNearbyChest|askChestFor|SupplyServerHop|callOnServerThread|TimeUnit");

    @Test
    void theOldHandOutIsGoneAndEveryCraftTailCommitsThroughTheHelper() throws IOException {
        String source = source();
        assertFalse(source.contains("distributeOutput("), "the separate output hand-out is gone");
        assertFalse(Pattern.compile("(?<![\\w.])consume(?:Item|Planks|Cobble)\\s*\\(").matcher(source).find(),
                "the old caller-thread consumes are gone");
        // The craft methods that make something: each commits exactly once (the plank conversions
        // through commitLogsToPlanks).
        List<String> crafts = List.of("craftLadders", "craftBed", "craftWhiteWoolFromString", "craftWithPlanks",
                "craftSticks(ServerPlayerEntity bot, ServerCommandSource source, ServerPlayerEntity commander,\n",
                "craftTorches", "craftDoor", "craftFence", "craftLead", "craftBundle", "craftLeather(", "craftSaddle",
                "craftCarrotOnStick", "craftWarpedFungusOnStick", "craftToolWithMaterial", "craftShield",
                "craftSimple", "craftLeatherArmor", "craftCobbleBlock", "craftFishingRod");
        for (String craft : crafts) {
            String body = method(source, craft);
            assertEquals(1, count(body, "commitCraft("), craft + " commits once");
            assertTrue(body.indexOf("commitCraft(") < body.indexOf("recordCraftHistory("),
                    craft + ": the history is recorded only after the commit went through");
        }
        for (String scan : List.of("craftPlanks", "ensurePlanksFromLogs")) {
            String body = method(source, scan);
            assertEquals(1, count(body, "commitLogsToPlanks("), scan + " converts one slot per commit");
            assertFalse(body.contains(".decrement("), scan + " decrements nothing itself");
            assertTrue(body.contains("CraftCommitPolicy.continueScan(outcome)"), scan + " stops on a stalled server");
        }
        assertTrue(method(source, "commitLogsToPlanks").contains("CraftCommitPolicy.roomNeededBeforeHop("));
    }

    @Test
    void leafConsumesAreCalledOnlyInsideCommitLambdas() throws IOException {
        String source = source();
        String outsideHops = source;
        for (String body : SERVER_ONLY_BODIES) {
            outsideHops = outsideHops.replace(block(source, body), "");
        }
        int hops = 0;
        for (String args : callArguments(source, "commitCraft(")) {
            if (args.contains("()")) { // a lambda; the delegating overload passes `consumes` through
                hops++;
            }
            outsideHops = outsideHops.replace(args, "");
        }
        assertTrue(hops >= 20, "expected every craft tail to carry its lambda, saw " + hops);
        Matcher leaf = LEAF_CALL.matcher(outsideHops);
        List<String> stray = new ArrayList<>();
        while (leaf.find()) {
            int line = 1 + (int) outsideHops.substring(0, leaf.start()).chars().filter(c -> c == '\n').count();
            stray.add(leaf.group() + " near line " + line);
        }
        assertEquals(List.of(), stray, "a consume outside a commitCraft lambda runs off the server thread");
    }

    @Test
    void noStackIsDecrementedOutsideTheServerOnlyBodies() throws IOException {
        String source = source();
        String rest = source;
        for (String body : SERVER_ONLY_BODIES) {
            String block = block(source, body);
            assertTrue(block.contains(".decrement(") || body.contains("consumeSlot"), body + " decrements its stacks");
            rest = rest.replace(block, "");
        }
        assertFalse(rest.contains(".decrement("), "a decrement outside the server-only bodies");
        // The only slot writes and output inserts are those bodies and the commit hops.
        rest = rest.replace(block(source, "private static CraftCommitPolicy.Outcome commitCraft(ServerPlayerEntity bot,\n"), "");
        rest = rest.replace(block(source, "private static boolean dropStackForSpace("), "");
        assertFalse(rest.contains(".setStack("), "a slot write outside the server-only bodies");
        assertFalse(rest.contains(".insertStack("), "an insert outside the commit hop");
    }

    @Test
    void nothingInsideAHopWaitsWalksOrTouchesAChest() throws IOException {
        String source = source();
        for (String args : callArguments(source, "commitCraft(")) {
            assertNoWaits(args, "commitCraft lambda");
        }
        for (String body : SERVER_ONLY_BODIES) {
            assertNoWaits(block(source, body), body);
        }
        assertNoWaits(block(source, "private static void reachForItem("), "reachForItem");
        // The commit helper's own hops: the make-room calls stay outside them.
        String commit = block(source, "private static CraftCommitPolicy.Outcome commitCraft(ServerPlayerEntity bot,\n");
        for (String hop : callArguments(commit, "BotActions.callOnServerThread(")) {
            assertNoWaits(hop, "commitCraft hop");
        }
        assertEquals(2, callArguments(commit, "BotActions.callOnServerThread(").size(), "one hop, plus the leftover's");
        assertTrue(commit.contains("CraftCommitPolicy.HOP_TIMEOUT_MS, null)"), "a timed-out first hop reads as null");
        assertTrue(commit.indexOf("makeRoomForOutput(") < commit.indexOf("BotActions.callOnServerThread("),
                "room is made on the caller's thread before the hop");
        // The bundle reach in ensurePlanksFromLogs is its own hop and holds nothing else.
        for (String hop : callArguments(block(source, "private static void ensurePlanksFromLogs("),
                "BotActions.callOnServerThread(")) {
            assertNoWaits(hop, "ensurePlanksFromLogs reach hop");
            assertTrue(hop.contains("reachForItem("));
        }
    }

    @Test
    void theHelperReturnsZeroCraftedUnlessTheCommitWentThrough() throws IOException {
        String source = source();
        // The three plank tails keep their "couldn't reserve planks" line for a short consume only.
        for (String craft : List.of("craftBed", "craftWithPlanks",
                "craftSticks(ServerPlayerEntity bot, ServerCommandSource source, ServerPlayerEntity commander,\n")) {
            String body = method(source, craft);
            assertTrue(body.contains("== CraftCommitPolicy.Outcome.ABORTED"), craft + " answers an aborted commit");
            assertTrue(body.contains("I couldn't reserve planks"), craft + " keeps its line");
            assertTrue(body.contains("!= CraftCommitPolicy.Outcome.CRAFTED"), craft + " returns 0 on a timeout too");
        }
        assertFalse(source.contains("private static void consumeItemOnServer("), "every leaf reports a shortfall");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static void assertNoWaits(String text, String where) {
        Matcher m = WAITS_OR_WALKS.matcher(text);
        assertFalse(m.find(), () -> where + " waits, walks or touches a chest: " + m.group());
    }

    /** Every balanced {@code (...)} argument list that follows {@code call} in {@code text}. */
    private static List<String> callArguments(String text, String call) {
        List<String> found = new ArrayList<>();
        for (int at = text.indexOf(call); at >= 0; at = text.indexOf(call, at + 1)) {
            int open = at + call.length() - 1;
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                if (c == ')' && --depth == 0) {
                    found.add(text.substring(open, i + 1));
                    break;
                }
            }
        }
        return found;
    }

    private static String method(String source, String name) {
        Matcher declaration = Pattern.compile("private static [\\w.<>]+ " + Pattern.quote(name)).matcher(source);
        assertTrue(declaration.find(), "Missing method: " + name);
        return block(source, declaration.group());
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(at, i + 1);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static String source() throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = dir; at != null; at = at.getParent()) {
            Path candidate = at.resolve(HELPER);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
            }
        }
        throw new IOException(HELPER + " not found above " + dir);
    }
}
