package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.MutualAidChestFoodPolicy.Next;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Refusal;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutualAidChestFoodPolicyTest {

    private static final boolean[] BOTH = {false, true};

    // ── withdrawCount ────────────────────────────────────────────────────────────────────────

    @Test
    void takesTwoPiecesWhenHungryAndThreeWhenStarving() {
        // Bread (nutrition 5): deficit 12 at food 8 needs 3, capped at 2; deficit 17 at food 3 needs 4, capped at 3.
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(8, false, 5, 64));
        assertEquals(3, MutualAidChestFoodPolicy.withdrawCount(3, true, 5, 64));
    }

    @Test
    void neverTakesMoreThanTheDeficitNeeds() {
        // Steak (nutrition 8) at food 14: deficit 6 needs 1.
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(14, false, 8, 64));
        // Starving with a big meal: deficit 16 at nutrition 8 needs 2, under the 3 cap.
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(4, true, 8, 64));
    }

    @Test
    void neverTakesMoreThanTheChestCanGrant() {
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(3, true, 2, 1));
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(3, true, 2, 2));
    }

    @Test
    void unknownNutritionSkipsTheDeficitCap() {
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(19, false, 0, 64));
        assertEquals(3, MutualAidChestFoodPolicy.withdrawCount(1, true, -1, 64));
    }

    @Test
    void alwaysAtLeastOnePiece() {
        // A full bar still counts a deficit of 1, and a zero grant still yields 1 (the caller skips it first).
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(MutualAidChestFoodPolicy.MAX_FOOD_LEVEL, false, 5, 64));
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(10, false, 5, 0));
    }

    // ── askedFirst ───────────────────────────────────────────────────────────────────────────

    @Test
    void theAskedChestMovesToTheFrontKeepingTheRestInOrder() {
        assertEquals(List.of("c", "a", "b", "d"), MutualAidChestFoodPolicy.askedFirst(List.of("a", "b", "c", "d"), "c"));
    }

    @Test
    void withNoAskedChestOrOneNotInReachTheOrderIsUnchanged() {
        List<String> byDistance = List.of("a", "b");
        assertSame(byDistance, MutualAidChestFoodPolicy.askedFirst(byDistance, null));
        assertSame(byDistance, MutualAidChestFoodPolicy.askedFirst(byDistance, "z"));
        assertEquals(List.of("a", "b"), MutualAidChestFoodPolicy.askedFirst(byDistance, "a"));
    }

    // ── afterWithdraw ────────────────────────────────────────────────────────────────────────

    @Test
    void movedIsTakenAndAnUnansweredPromptWaitsWhateverElseIsTrue() {
        for (Scope scope : Scope.values()) {
            for (boolean room : BOTH) {
                for (boolean made : BOTH) {
                    String at = scope + " room=" + room + " made=" + made;
                    assertEquals(Next.TAKEN, MutualAidChestFoodPolicy.afterWithdraw(Kind.MOVED, scope, room, made), at);
                    assertEquals(Next.WAIT, MutualAidChestFoodPolicy.afterWithdraw(Kind.WAITING, scope, room, made), at);
                }
            }
        }
    }

    @Test
    void readyStopsBecauseThisSiteNeverWalks() {
        for (Scope scope : Scope.values()) {
            for (boolean room : BOTH) {
                for (boolean made : BOTH) {
                    assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.READY, scope, room, made),
                            scope + " room=" + room + " made=" + made);
                }
            }
        }
    }

    /** The common per-scope rule, as this site spells it (with room for one piece). */
    private static final Map<Scope, Next> REFUSAL_NEXT = new EnumMap<>(Map.of(
            Scope.ITEM, Next.NEXT_ITEM,
            Scope.CHEST, Next.NEXT_CHEST,
            Scope.TARGET, Next.NEXT_TARGET,
            Scope.OWNER_ABSENT, Next.OWNER_AWAY,
            Scope.BOT, Next.PAUSE,
            Scope.BUSY, Next.STOP,
            Scope.INVENTORY_FULL, Next.STOP, // with room for one piece; without it, room is made once
            Scope.NONE, Next.PAUSE)); // a refusal never carries NONE; if one did, fail closed

    @Test
    void everyRefusalScopeFollowsTheSharedSiteRule() {
        assertEquals(EnumSet.allOf(Scope.class), REFUSAL_NEXT.keySet(), "every Scope needs an expectation");
        for (Scope scope : Scope.values()) {
            assertEquals(REFUSAL_NEXT.get(scope), MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, scope, true, false),
                    scope.name());
        }
    }

    @Test
    void everyKindAndScopeIsDecidedOnce() {
        for (Kind kind : Kind.values()) {
            for (Scope scope : Scope.values()) {
                for (boolean room : BOTH) {
                    for (boolean made : BOTH) {
                        String at = kind + "/" + scope + " room=" + room + " made=" + made;
                        Next expected = switch (kind) {
                            case MOVED -> Next.TAKEN;
                            case WAITING -> Next.WAIT;
                            case READY -> Next.STOP;
                            case REFUSED -> scope == Scope.INVENTORY_FULL && !room && !made
                                    ? Next.MAKE_ROOM_AND_RETRY : REFUSAL_NEXT.get(scope);
                        };
                        assertEquals(expected, MutualAidChestFoodPolicy.afterWithdraw(kind, scope, room, made), at);
                    }
                }
            }
        }
    }

    @Test
    void aRefusalWithoutAScopeOrAMissingKindFailsClosed() {
        assertEquals(Next.PAUSE, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, null, true, false));
        assertEquals(Next.PAUSE, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, Scope.NONE, false, true));
        for (Scope scope : Scope.values()) {
            assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(null, scope, true, false), scope.name());
        }
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(null, null, false, false));
    }

    @Test
    void noRoomMakesRoomOnceThenEndsTheAttempt() {
        // NO_ROOM is INVENTORY_FULL: the ticket is kept, so room is made once and the same food asked again.
        Scope full = SupplyWithdrawalPolicy.transferScope(TransferStatus.NO_ROOM);
        assertEquals(Scope.INVENTORY_FULL, full);
        assertEquals(Next.MAKE_ROOM_AND_RETRY, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, full, false, false));
        // Room was made and still nothing fits: asking another chest could only open a prompt it cannot redeem.
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, full, false, true));
        // The site sees room the facade did not: nothing to drop; stop, the next attempt after the throttle.
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, full, true, false));
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, full, true, true));
    }

    @Test
    void aBusyAnswerNeverDropsAStackToMakeRoom() {
        // Re-review B minor: another of this bot's prompts open (OTHER_REQUEST_PENDING) used to read
        // as "no room" whenever the bot happened to be full, and dropped a stack over it.
        for (Scope busy : List.of(Refusal.OTHER_REQUEST_PENDING.scope(), Refusal.SERVER_BUSY.scope(),
                SupplyWithdrawalPolicy.requestScope(RequestStatus.DUPLICATE_PENDING, null, null))) {
            assertEquals(Scope.BUSY, busy);
            for (boolean room : BOTH) {
                for (boolean made : BOTH) {
                    assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, busy, room, made),
                            "room=" + room + " made=" + made);
                }
            }
        }
    }

    @Test
    void roomOnlyMattersForAFullInventoryAndRoomIsMadeAtMostOnce() {
        for (Kind kind : Kind.values()) {
            for (Scope scope : Scope.values()) {
                Next reference = MutualAidChestFoodPolicy.afterWithdraw(kind, scope, true, false);
                for (boolean room : BOTH) {
                    for (boolean made : BOTH) {
                        Next next = MutualAidChestFoodPolicy.afterWithdraw(kind, scope, room, made);
                        String at = kind + "/" + scope + " room=" + room + " made=" + made;
                        if (made) {
                            assertNotEquals(Next.MAKE_ROOM_AND_RETRY, next, at);
                        }
                        if (next == Next.MAKE_ROOM_AND_RETRY) {
                            assertTrue(kind == Kind.REFUSED && scope == Scope.INVENTORY_FULL && !room, at);
                        }
                        if (kind != Kind.REFUSED || scope != Scope.INVENTORY_FULL) {
                            assertEquals(reference, next, at);
                        }
                    }
                }
            }
        }
    }

    @Test
    void onlyTheOwnersDecisionABusyAnswerAndAFullInventoryEndTheAttemptEarly() {
        Set<Next> goOn = EnumSet.of(Next.NEXT_ITEM, Next.NEXT_TARGET, Next.NEXT_CHEST, Next.OWNER_AWAY);
        for (Scope scope : Scope.values()) {
            Next next = MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, scope, true, false);
            boolean endsTheAttempt = scope == Scope.BOT || scope == Scope.BUSY || scope == Scope.INVENTORY_FULL
                    || scope == Scope.NONE;
            assertEquals(!endsTheAttempt, goOn.contains(next), scope.name());
        }
        // FD concern 1: the owner away skips only that chest, so a farther "always" chest still serves.
        assertEquals(Next.OWNER_AWAY, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, Scope.OWNER_ABSENT, true, false));
        // A food at its reserve in this chest leaves the chest's other foods to ask.
        assertEquals(Next.NEXT_TARGET, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, Scope.TARGET, true, false));
    }

    @Test
    void anAttemptThatFoundTheOwnerAwayAndNothingElseDefersAFlatMinute() {
        assertEquals(Next.DEFER, MutualAidChestFoodPolicy.endOfAttempt(true));
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.endOfAttempt(false), "nothing found: the usual throttle");
        assertEquals(MutualAidChestFoodPolicy.OWNER_AWAY_DEFER_TICKS,
                MutualAidChestFoodPolicy.probeDelayTicks(MutualAidChestFoodPolicy.endOfAttempt(true)));
        assertEquals(MutualAidChestFoodPolicy.THROTTLE_TICKS,
                MutualAidChestFoodPolicy.probeDelayTicks(MutualAidChestFoodPolicy.endOfAttempt(false)));
        // No single answer defers on its own: only the end of an attempt that tried every chest.
        for (Kind kind : Kind.values()) {
            for (Scope scope : Scope.values()) {
                for (boolean room : BOTH) {
                    assertNotEquals(Next.DEFER, MutualAidChestFoodPolicy.afterWithdraw(kind, scope, room, false),
                            kind + "/" + scope);
                }
            }
        }
    }

    // ── probeDelayTicks ──────────────────────────────────────────────────────────────────────

    @Test
    void onlyTheOwnersDecisionHoldsChestsOffSixtySecondsPerAnswerAndEverythingElseKeepsTheThrottle() {
        assertEquals(20L * 8L, MutualAidChestFoodPolicy.THROTTLE_TICKS);
        assertEquals(20L * 60L, MutualAidChestFoodPolicy.BOT_PAUSE_TICKS);
        assertEquals(20L * 60L, MutualAidChestFoodPolicy.OWNER_AWAY_DEFER_TICKS);
        for (Next next : Next.values()) {
            long expected = next == Next.PAUSE ? MutualAidChestFoodPolicy.BOT_PAUSE_TICKS
                    : next == Next.DEFER ? MutualAidChestFoodPolicy.OWNER_AWAY_DEFER_TICKS
                    : MutualAidChestFoodPolicy.THROTTLE_TICKS;
            assertEquals(expected, MutualAidChestFoodPolicy.probeDelayTicks(next), next.name());
        }
        assertEquals(MutualAidChestFoodPolicy.THROTTLE_TICKS, MutualAidChestFoodPolicy.probeDelayTicks(null));
        // Per scope, end to end: only the owner's decision waits a minute; a busy answer (FD concern 2:
        // another prompt open) keeps the 8 s throttle.
        for (Scope scope : Scope.values()) {
            long delay = MutualAidChestFoodPolicy.probeDelayTicks(
                    MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, scope, true, false));
            boolean held = scope == Scope.BOT || scope == Scope.NONE;
            assertEquals(held ? 20L * 60L : MutualAidChestFoodPolicy.THROTTLE_TICKS, delay, scope.name());
        }
    }

    // ── Source pins ──────────────────────────────────────────────────────────────────────────

    @Test
    void theDecisionReadsNoReasonString() {
        for (Method method : MutualAidChestFoodPolicy.class.getDeclaredMethods()) {
            if (method.getName().equals("afterWithdraw")) {
                for (Class<?> type : method.getParameterTypes()) {
                    assertNotEquals(String.class, type, "afterWithdraw classifies by kind and scope only");
                }
            }
        }
    }

    @Test
    void makingRoomForChestFoodOnlyDropsAndTheTakeHopsThroughTheSharedHop() throws IOException {
        String source = mutualAidSource();
        String makeRoom = bodyOf(source, "void ensureInventorySpaceForChestFood(");
        // It runs on the server thread inside the take: never walk, place a chest or deposit into one.
        for (String banned : List.of("offloadCheapItemsToNearbyChest", "depositMatchingWalkOnly", "placeChestNearBot",
                "MovementService", "ChestStoreService")) {
            assertFalse(makeRoom.contains(banned), "make-room reaches " + banned);
        }
        assertTrue(makeRoom.contains("CraftingHelper.dropCheapStackForSpace("), "make-room should drop a stack");
        assertTrue(bodyOf(source, "boolean probeSharedChestFood(").contains("SupplyServerHop.call("),
                "the chest-food attempt should reach the server thread through SupplyServerHop");
        assertFalse(source.contains("CompletableFuture"), "the private server hop copy should be gone");
    }

    @Test
    void makingRoomOnTheServerTickOnlyDropsAndGoesThroughTheMakeRoomPolicy() throws IOException {
        String source = mutualAidSource();
        // The two tick-side make-room calls were its only uses: nothing in mutual aid offloads to a chest.
        assertFalse(source.contains("offloadCheapItemsToNearbyChest"), "mutual aid still offloads to a chest");
        for (String signature : List.of("boolean tryMakeSpaceForNearbyDroppedFood(",
                "boolean ensureInventorySpaceForAidRecipient(")) {
            String makeRoom = bodyOf(source, signature);
            for (String banned : List.of("depositMatchingWalkOnly", "placeChestNearBot", "MovementService",
                    "ChestStoreService")) {
                assertFalse(makeRoom.contains(banned), signature + " reaches " + banned);
            }
            assertTrue(makeRoom.contains("dropCheapStackForSpace"), signature + " should drop a stack");
            assertTrue(makeRoom.contains("MutualAidMakeRoomPolicy."), signature + " should decide through the policy");
        }
    }

    @Test
    void aFailedMakeRoomHoldsOffOnlyItsOwnKind() throws IOException {
        String source = mutualAidSource();
        // A gear drop reserves more than a food pickup does, so its failure must not block the pickup.
        assertTrue(source.contains("Map<UUID, Map<MutualAidMakeRoomPolicy.Kind, Long>> NEXT_MAKE_ROOM_TICK"),
                "the make-room backoff should be keyed by bot and kind");
        assertTrue(source.contains("pruneMap(NEXT_MAKE_ROOM_TICK, knownBots);"), "the backoff should still be pruned");
        assertTrue(bodyOf(source, "boolean tryMakeSpaceForNearbyDroppedFood(")
                        .contains("mayMakeRoom(bot, MutualAidMakeRoomPolicy.Kind.FOOD_PICKUP, nowTick)"),
                "the food pickup should read its own backoff");
        assertTrue(bodyOf(source, "boolean ensureInventorySpaceForAidRecipient(")
                        .contains("mayMakeRoom(recipient, roomKind, nowTick)"),
                "a recipient should read the backoff of the kind it is receiving");
        assertTrue(bodyOf(source, "void holdOffMakeRoom(").contains(".put(kind, "),
                "a failure should hold off only its own kind");
    }

    private static String mutualAidSource() throws IOException {
        String relative = "src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java";
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = dir; at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException(relative + " not found above " + dir);
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
