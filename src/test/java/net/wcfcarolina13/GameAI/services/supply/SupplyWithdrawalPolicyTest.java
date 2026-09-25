package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Config;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Pos;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.RequestAction;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketStep;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TransferAction;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.WaitDecision;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the decisions behind SupplyWithdrawals — no net.minecraft types, no world. */
class SupplyWithdrawalPolicyTest {

    private static final String WORLD = "New World#1a2b/minecraft:overworld";
    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID BOT = new UUID(2, 1);
    private static final ChestKey CHEST = new ChestKey(WORLD, 10, 64, 10);
    private static final ItemKey COBBLE = ItemKey.plain("minecraft:cobblestone");
    private static final Timings DEFAULTS = Timings.defaults();

    private static RequestFingerprint ticket(ChestKey chest, ItemKey item) {
        return new RequestFingerprint(OWNER, BOT, chest, item, 8);
    }

    // ── pre-filter and wait mode ─────────────────────────────────────────────────────────────

    @Test
    void onlyAnEligibleItemPassesThePreFilter() {
        for (Verdict v : Verdict.values()) {
            assertEquals(v == Verdict.ELIGIBLE, SupplyWithdrawalPolicy.passesPreFilter(v), v.name());
        }
        assertFalse(SupplyWithdrawalPolicy.passesPreFilter(null));
    }

    @Test
    void theEstimateIsTheCountLessTheReserveForAnEligibleItem() {
        Config cfg = Config.defaults();
        int reserve = SupplyRequestPolicy.DEFAULT_MATERIAL_RESERVE;
        assertEquals(20 - reserve, SupplyWithdrawalPolicy.grantableEstimate(COBBLE, cfg, 20));
        assertEquals(1, SupplyWithdrawalPolicy.grantableEstimate(COBBLE, cfg, reserve + 1));
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(COBBLE, cfg, reserve), "the reserve stays");
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(COBBLE, cfg, 3));
        // Equipment keeps its spare.
        ItemKey stoneAxe = ItemKey.plain("minecraft:stone_axe");
        assertEquals(3 - SupplyRequestPolicy.DEFAULT_EQUIPMENT_SPARE,
                SupplyWithdrawalPolicy.grantableEstimate(stoneAxe, cfg, 3));
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(stoneAxe, cfg, 1), "a lone axe is the spare");
        // A worn tool is still common: damage is an allowed component.
        ItemKey wornAxe = new ItemKey("minecraft:stone_axe", "minecraft:damage=5", Set.of("minecraft:damage"));
        assertEquals(1, SupplyWithdrawalPolicy.grantableEstimate(wornAxe, cfg, 2));
    }

    @Test
    void theEstimateIsZeroForAnythingThePreFilterRefuses() {
        Config cfg = Config.defaults();
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(ItemKey.plain("minecraft:diamond"), cfg, 64),
                "not allowlisted");
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(ItemKey.plain("minecraft:iron_axe"), cfg, 5),
                "tier not allowed");
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(new ItemKey("minecraft:cobblestone",
                "minecraft:custom_name=\"x\"", Set.of("minecraft:custom_name")), cfg, 64), "protected components");
        // The same config decides: allowing iron makes the iron axe count.
        assertEquals(4, SupplyWithdrawalPolicy.grantableEstimate(ItemKey.plain("minecraft:iron_axe"),
                cfg.withAllowedTiers(Set.of("iron")), 5));
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(COBBLE, null, 64), "no config: not running");
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(null, cfg, 64));
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(COBBLE, cfg, -5));
    }

    @Test
    void waitingIsForWorkersOnly() {
        assertEquals(WaitMode.UNTIL_ANSWERED, SupplyWithdrawalPolicy.effectiveMode(WaitMode.UNTIL_ANSWERED, false));
        assertEquals(WaitMode.NONE, SupplyWithdrawalPolicy.effectiveMode(WaitMode.UNTIL_ANSWERED, true));
        assertEquals(WaitMode.NONE, SupplyWithdrawalPolicy.effectiveMode(WaitMode.NONE, false));
        assertEquals(WaitMode.NONE, SupplyWithdrawalPolicy.effectiveMode(WaitMode.NONE, true));
        assertEquals(WaitMode.NONE, SupplyWithdrawalPolicy.effectiveMode(null, false));
        assertEquals(WaitMode.NONE, SupplyWithdrawalPolicy.effectiveMode(null, true));
    }

    // ── tickets ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theTicketStepCoversEveryCombination() {
        boolean[] both = {false, true};
        for (boolean matches : both) {
            for (boolean pending : both) {
                for (boolean permitted : both) {
                    // Without a ticket nothing matches: always a fresh request (the ledger refuses a duplicate).
                    assertEquals(TicketStep.REQUEST, SupplyWithdrawalPolicy.ticketStep(false, matches, pending, permitted));
                }
            }
        }
        for (boolean permitted : both) {
            // A waiting prompt wins; permission is read only once nothing is pending.
            assertEquals(TicketStep.WAIT, SupplyWithdrawalPolicy.ticketStep(true, true, true, permitted));
            // A ticket for something else is judged by the pending prompt alone.
            assertEquals(TicketStep.OTHER_PENDING, SupplyWithdrawalPolicy.ticketStep(true, false, true, permitted));
            assertEquals(TicketStep.DROP_AND_REQUEST, SupplyWithdrawalPolicy.ticketStep(true, false, false, permitted));
        }
        assertEquals(TicketStep.REDEEM, SupplyWithdrawalPolicy.ticketStep(true, true, false, true));
        assertEquals(TicketStep.DROP_NOT_PERMITTED, SupplyWithdrawalPolicy.ticketStep(true, true, false, false));
    }

    @Test
    void onlyAPermittedTicketIsRedeemedSoReadyAlwaysMeansPermitted() {
        // REDEEM is the only step that can lead to READY; it needs a match, nothing pending and a permission.
        boolean[] both = {false, true};
        for (boolean hasTicket : both) {
            for (boolean matches : both) {
                for (boolean pending : both) {
                    for (boolean permitted : both) {
                        boolean redeem = SupplyWithdrawalPolicy.ticketStep(hasTicket, matches, pending, permitted)
                                == TicketStep.REDEEM;
                        assertEquals(hasTicket && matches && !pending && permitted, redeem,
                                hasTicket + "/" + matches + "/" + pending + "/" + permitted);
                    }
                }
            }
        }
    }

    @Test
    void aTicketMatchesTheSameChestKeyAndExactItemOnly() {
        RequestFingerprint fp = ticket(CHEST, COBBLE);
        assertTrue(SupplyWithdrawalPolicy.ticketMatches(fp, CHEST, COBBLE));
        // Either half of a double chest yields the same canonical key.
        ChestKey fromOtherHalf = ChestKey.canonical(WORLD, new Pos(11, 64, 10), new Pos(10, 64, 10));
        assertTrue(SupplyWithdrawalPolicy.ticketMatches(fp, fromOtherHalf, COBBLE));
        // The quantity is not part of the match; the owner and bot are the ticket's own.
        assertTrue(SupplyWithdrawalPolicy.ticketMatches(fp.withQty(1), CHEST, COBBLE));

        assertFalse(SupplyWithdrawalPolicy.ticketMatches(fp, new ChestKey(WORLD, 20, 64, 20), COBBLE));
        assertFalse(SupplyWithdrawalPolicy.ticketMatches(fp, new ChestKey("Other#9/minecraft:overworld", 10, 64, 10),
                COBBLE));
        assertFalse(SupplyWithdrawalPolicy.ticketMatches(fp, CHEST, ItemKey.plain("minecraft:dirt")));
        assertFalse(SupplyWithdrawalPolicy.ticketMatches(fp, CHEST,
                new ItemKey("minecraft:cobblestone", "minecraft:custom_name=\"x\"", Set.of("minecraft:custom_name"))));
        assertFalse(SupplyWithdrawalPolicy.ticketMatches(fp, null, COBBLE), "an unreadable chest matches nothing");
        assertFalse(SupplyWithdrawalPolicy.ticketMatches(fp, CHEST, null));
        assertFalse(SupplyWithdrawalPolicy.ticketMatches(null, CHEST, COBBLE));
    }

    @Test
    void aTicketLivesForThePromptPlusTheGrantAndDiesOnTheDeadline() {
        long opened = 1_000_000L;
        long lifetime = DEFAULTS.requestLifetimeMs() + DEFAULTS.grantLifetimeMs();
        assertEquals(90_000L, lifetime);
        assertEquals(lifetime, SupplyWithdrawalPolicy.ticketLifetimeMs(DEFAULTS));
        assertTrue(SupplyWithdrawalPolicy.isTicketLive(opened, opened, DEFAULTS));
        assertTrue(SupplyWithdrawalPolicy.isTicketLive(opened, opened + lifetime - 1, DEFAULTS));
        assertFalse(SupplyWithdrawalPolicy.isTicketLive(opened, opened + lifetime, DEFAULTS), "now == deadline is expired");
        assertFalse(SupplyWithdrawalPolicy.isTicketLive(opened, opened + lifetime + 1, DEFAULTS));

        Timings shortOnes = new Timings(1_000L, 2_000L, 0L, 0L);
        assertTrue(SupplyWithdrawalPolicy.isTicketLive(opened, opened + 2_999L, shortOnes));
        assertFalse(SupplyWithdrawalPolicy.isTicketLive(opened, opened + 3_000L, shortOnes));
        // Zero lifetimes: a ticket is dead the instant it opens.
        assertFalse(SupplyWithdrawalPolicy.isTicketLive(opened, opened, new Timings(0L, 0L, 0L, 0L)));
        // Missing timings fall back to the defaults.
        assertEquals(lifetime, SupplyWithdrawalPolicy.ticketLifetimeMs(null));
    }

    // ── request outcome ──────────────────────────────────────────────────────────────────────

    @Test
    void everyRequestStatusHasAnAction() {
        Map<RequestStatus, RequestAction> expected = new EnumMap<>(RequestStatus.class);
        expected.put(RequestStatus.OPENED, RequestAction.WAIT);
        expected.put(RequestStatus.COVERED_BY_ALWAYS, RequestAction.TAKE);
        for (RequestStatus refused : EnumSet.of(RequestStatus.DUPLICATE_PENDING, RequestStatus.PROMPT_COOLDOWN,
                RequestStatus.REJECT_COOLDOWN, RequestStatus.INELIGIBLE, RequestStatus.OWNER_NOT_NEARBY,
                RequestStatus.DENIED, RequestStatus.INVALID, RequestStatus.NOT_RUNNING, RequestStatus.WRONG_THREAD)) {
            expected.put(refused, RequestAction.REFUSE);
        }
        assertEquals(EnumSet.allOf(RequestStatus.class), expected.keySet(), "a new status needs a decision here");
        for (RequestStatus s : RequestStatus.values()) {
            assertEquals(expected.get(s), SupplyWithdrawalPolicy.onRequest(s), s.name());
        }
        assertEquals(RequestAction.REFUSE, SupplyWithdrawalPolicy.onRequest(null));
    }

    // ── transfer outcome ─────────────────────────────────────────────────────────────────────

    @Test
    void everyTransferStatusMapsToAKindAndATicketFate() {
        Map<TransferStatus, TransferAction> expected = new EnumMap<>(TransferStatus.class);
        expected.put(TransferStatus.MOVED, new TransferAction(Kind.MOVED, false, "MOVED"));
        expected.put(TransferStatus.MOVED_SHORT, new TransferAction(Kind.MOVED, false, "MOVED_SHORT"));
        expected.put(TransferStatus.OUT_OF_REACH, new TransferAction(Kind.READY, true, "OUT_OF_REACH"));
        expected.put(TransferStatus.NO_ROOM, new TransferAction(Kind.REFUSED, true, "NO_ROOM"));
        for (TransferStatus dropped : EnumSet.of(TransferStatus.WRONG_THREAD, TransferStatus.NOT_RUNNING,
                TransferStatus.INVALID, TransferStatus.OWNER_OR_BOT_MISMATCH, TransferStatus.DENIED,
                TransferStatus.CHEST_MISMATCH, TransferStatus.NO_STOCK, TransferStatus.NOT_PERMITTED)) {
            expected.put(dropped, new TransferAction(Kind.REFUSED, false, dropped.name()));
        }
        assertEquals(EnumSet.allOf(TransferStatus.class), expected.keySet(), "a new status needs a decision here");
        for (TransferStatus s : TransferStatus.values()) {
            int moved = s == TransferStatus.MOVED || s == TransferStatus.MOVED_SHORT ? 5 : 0;
            assertEquals(expected.get(s), SupplyWithdrawalPolicy.onTransfer(s, moved), s.name());
        }
    }

    @Test
    void aMoveThatMovedNothingIsARefusalAndTheTicketGoes() {
        assertEquals(new TransferAction(Kind.REFUSED, false, "MOVED_SHORT"),
                SupplyWithdrawalPolicy.onTransfer(TransferStatus.MOVED_SHORT, 0));
        assertEquals(new TransferAction(Kind.REFUSED, false, "MOVED"),
                SupplyWithdrawalPolicy.onTransfer(TransferStatus.MOVED, 0));
        assertEquals(new TransferAction(Kind.REFUSED, false, "INVALID"), SupplyWithdrawalPolicy.onTransfer(null, 3));
    }

    @Test
    void onlyReachAndRoomKeepTheTicket() {
        for (TransferStatus s : TransferStatus.values()) {
            boolean kept = SupplyWithdrawalPolicy.onTransfer(s, 1).keepTicket();
            assertEquals(s == TransferStatus.OUT_OF_REACH || s == TransferStatus.NO_ROOM, kept, s.name());
        }
    }

    // ── waiting ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theWaitBudgetIsThePromptLifetimePlusSlack() {
        assertEquals(32_000L, SupplyWithdrawalPolicy.waitBudgetMs(DEFAULTS));
        assertEquals(DEFAULTS.requestLifetimeMs() + SupplyWithdrawalPolicy.WAIT_SLACK_MS,
                SupplyWithdrawalPolicy.waitBudgetMs(null));
        assertEquals(1_000L + SupplyWithdrawalPolicy.WAIT_SLACK_MS,
                SupplyWithdrawalPolicy.waitBudgetMs(new Timings(1_000L, 0L, 0L, 0L)));
    }

    @Test
    void keepWaitingWhilePendingAndWithinBudgetOnly() {
        long start = 5_000L;
        long budget = SupplyWithdrawalPolicy.waitBudgetMs(DEFAULTS);
        assertEquals(WaitDecision.KEEP_WAITING, SupplyWithdrawalPolicy.keepWaiting(false, true, start, start, DEFAULTS));
        assertEquals(WaitDecision.KEEP_WAITING,
                SupplyWithdrawalPolicy.keepWaiting(false, true, start, start + budget - 1, DEFAULTS));
        assertEquals(WaitDecision.TIMED_OUT,
                SupplyWithdrawalPolicy.keepWaiting(false, true, start, start + budget, DEFAULTS), "now == deadline");
        assertEquals(WaitDecision.TIMED_OUT,
                SupplyWithdrawalPolicy.keepWaiting(false, true, start, start + budget + 1, DEFAULTS));
    }

    @Test
    void aSettledPromptEndsTheWaitEvenPastTheBudget() {
        long start = 5_000L;
        long budget = SupplyWithdrawalPolicy.waitBudgetMs(DEFAULTS);
        assertEquals(WaitDecision.SETTLED, SupplyWithdrawalPolicy.keepWaiting(false, false, start, start, DEFAULTS));
        assertEquals(WaitDecision.SETTLED,
                SupplyWithdrawalPolicy.keepWaiting(false, false, start, start + budget + 10, DEFAULTS));
    }

    @Test
    void abortWinsOverEverything() {
        long start = 5_000L;
        long budget = SupplyWithdrawalPolicy.waitBudgetMs(DEFAULTS);
        for (boolean pending : new boolean[] {false, true}) {
            for (long now : new long[] {start, start + budget - 1, start + budget, start + budget + 1}) {
                assertEquals(WaitDecision.ABORTED, SupplyWithdrawalPolicy.keepWaiting(true, pending, start, now, DEFAULTS),
                        "pending=" + pending + " now=" + now);
            }
        }
    }
}
