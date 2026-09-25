package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.Access;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Consume;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.OpenResult;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ResponseStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Choice;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Config;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Pos;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Stock;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.OwnerAwayMemo;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Refusal;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.RequestAction;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Ticket;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketBook;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketStep;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TransferAction;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.WaitDecision;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the decisions behind SupplyWithdrawals — no net.minecraft types, no world. */
class SupplyWithdrawalPolicyTest {

    private static final String WORLD = "New World#1a2b/minecraft:overworld";
    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID BOT = new UUID(2, 1);
    private static final UUID OTHER_BOT = new UUID(2, 2);
    private static final ChestKey CHEST = new ChestKey(WORLD, 10, 64, 10);
    private static final ChestKey OTHER_CHEST = new ChestKey(WORLD, 20, 64, 20);
    private static final ItemKey COBBLE = ItemKey.plain("minecraft:cobblestone");
    private static final ItemKey BREAD = ItemKey.plain("minecraft:bread");
    private static final ItemKey WOODEN_SWORD = ItemKey.plain("minecraft:wooden_sword");
    private static final ItemKey WOODEN_AXE = ItemKey.plain("minecraft:wooden_axe");
    private static final ItemKey STONE_AXE = ItemKey.plain("minecraft:stone_axe");
    private static final Timings DEFAULTS = Timings.defaults();

    private static RequestFingerprint ticket(ChestKey chest, ItemKey item) {
        return new RequestFingerprint(OWNER, BOT, chest, item, 8);
    }

    // ── pre-filter and wait mode ─────────────────────────────────────────────────────────────

    @Test
    void onlyAnEligibleItemPassesThePreFilter() {
        for (Verdict v : Verdict.values()) {
            assertEquals(v == Verdict.ELIGIBLE, SupplyWithdrawalPolicy.passesPreFilter(v), v.name());
            // A pre-filter refusal is the item's: no chest will grant it.
            assertEquals(v == Verdict.ELIGIBLE ? Scope.NONE : Scope.ITEM, SupplyWithdrawalPolicy.preFilterScope(v),
                    v.name());
        }
        assertFalse(SupplyWithdrawalPolicy.passesPreFilter(null));
        assertEquals(Scope.ITEM, SupplyWithdrawalPolicy.preFilterScope(null));
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
        assertEquals(3 - SupplyRequestPolicy.DEFAULT_EQUIPMENT_SPARE,
                SupplyWithdrawalPolicy.grantableEstimate(STONE_AXE, cfg, 3));
        assertEquals(0, SupplyWithdrawalPolicy.grantableEstimate(STONE_AXE, cfg, 1), "a lone axe is the spare");
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

    // ── ticket step ──────────────────────────────────────────────────────────────────────────

    @Test
    void theTicketStepCoversEveryCombination() {
        // matching ticket: permitted first (pending is the bot's, not the ticket's), then pending, else drop.
        assertEquals(TicketStep.REDEEM, SupplyWithdrawalPolicy.ticketStep(true, false, true));
        assertEquals(TicketStep.REDEEM, SupplyWithdrawalPolicy.ticketStep(true, true, true));
        assertEquals(TicketStep.WAIT, SupplyWithdrawalPolicy.ticketStep(true, true, false));
        assertEquals(TicketStep.DROP_NOT_PERMITTED, SupplyWithdrawalPolicy.ticketStep(true, false, false));
        // no matching ticket: another prompt pending refuses, otherwise a fresh request. Nothing is dropped.
        for (boolean permitted : new boolean[] {false, true}) {
            assertEquals(TicketStep.OTHER_PENDING, SupplyWithdrawalPolicy.ticketStep(false, true, permitted));
            assertEquals(TicketStep.REQUEST, SupplyWithdrawalPolicy.ticketStep(false, false, permitted));
        }
    }

    @Test
    void onlyAPermittedMatchingTicketIsRedeemedSoReadyAlwaysMeansPermitted() {
        boolean[] both = {false, true};
        for (boolean matching : both) {
            for (boolean pending : both) {
                for (boolean permitted : both) {
                    boolean redeem = SupplyWithdrawalPolicy.ticketStep(matching, pending, permitted) == TicketStep.REDEEM;
                    assertEquals(matching && permitted, redeem, matching + "/" + pending + "/" + permitted);
                }
            }
        }
    }

    @Test
    void theOnlyStepThatDropsATicketIsTheCallsOwnUncoveredOne() {
        // No step drops a ticket it did not match: there is no "drop and request" any more.
        assertEquals(EnumSet.of(TicketStep.REQUEST, TicketStep.WAIT, TicketStep.REDEEM, TicketStep.DROP_NOT_PERMITTED,
                TicketStep.OTHER_PENDING), EnumSet.allOf(TicketStep.class));
        for (boolean pending : new boolean[] {false, true}) {
            for (boolean permitted : new boolean[] {false, true}) {
                assertNotEquals(TicketStep.DROP_NOT_PERMITTED, SupplyWithdrawalPolicy.ticketStep(false, pending, permitted));
            }
        }
    }

    // ── ticket keys and the book ─────────────────────────────────────────────────────────────

    @Test
    void aTicketKeyIsTheChestKeyAndTheExactItemOnly() {
        RequestFingerprint fp = ticket(CHEST, COBBLE);
        assertEquals(TicketKey.of(fp), TicketKey.of(CHEST, COBBLE));
        // Either half of a double chest yields the same canonical key.
        ChestKey fromOtherHalf = ChestKey.canonical(WORLD, new Pos(11, 64, 10), new Pos(10, 64, 10));
        assertEquals(TicketKey.of(fp), TicketKey.of(fromOtherHalf, COBBLE));
        // The quantity is not part of the key.
        assertEquals(TicketKey.of(fp), TicketKey.of(fp.withQty(1)));

        assertNotEquals(TicketKey.of(fp), TicketKey.of(OTHER_CHEST, COBBLE));
        assertNotEquals(TicketKey.of(fp), TicketKey.of(new ChestKey("Other#9/minecraft:overworld", 10, 64, 10), COBBLE));
        assertNotEquals(TicketKey.of(fp), TicketKey.of(CHEST, ItemKey.plain("minecraft:dirt")));
        assertNotEquals(TicketKey.of(fp), TicketKey.of(CHEST,
                new ItemKey("minecraft:cobblestone", "minecraft:custom_name=\"x\"", Set.of("minecraft:custom_name"))));
        assertNull(TicketKey.of(null, COBBLE), "an unreadable chest names no ticket");
        assertNull(TicketKey.of(CHEST, null));
        assertNull(TicketKey.of(null));
    }

    @Test
    void aBotKeepsOneTicketPerChestAndItemAndDroppingOneLeavesTheOthers() {
        TicketBook book = new TicketBook();
        long now = 1_000L;
        Ticket axe = book.record(ticket(CHEST, STONE_AXE), now);
        Ticket bread = book.record(ticket(CHEST, BREAD), now);
        Ticket elsewhere = book.record(ticket(OTHER_CHEST, STONE_AXE), now);
        Ticket otherBots = book.record(new RequestFingerprint(OWNER, OTHER_BOT, CHEST, STONE_AXE, 1), now);
        assertEquals(3, book.count(BOT));
        assertEquals(1, book.count(OTHER_BOT));

        book.drop(bread);
        assertNull(book.find(BOT, TicketKey.of(CHEST, BREAD), now, DEFAULTS));
        assertSame(axe, book.find(BOT, TicketKey.of(CHEST, STONE_AXE), now, DEFAULTS));
        assertSame(elsewhere, book.find(BOT, TicketKey.of(OTHER_CHEST, STONE_AXE), now, DEFAULTS));
        assertSame(otherBots, book.find(OTHER_BOT, TicketKey.of(CHEST, STONE_AXE), now, DEFAULTS));

        // A newer ticket for the same key replaces the old one; dropping the old one leaves the new one.
        Ticket newer = book.record(ticket(CHEST, STONE_AXE).withQty(2), now + 5);
        book.drop(axe);
        assertSame(newer, book.find(BOT, TicketKey.of(CHEST, STONE_AXE), now + 5, DEFAULTS));

        assertNull(book.find(BOT, null, now, DEFAULTS), "an unreadable chest finds nothing");
        assertNull(book.find(null, TicketKey.of(CHEST, STONE_AXE), now, DEFAULTS));
        book.drop(null);
        book.clear();
        assertEquals(0, book.count(BOT));
        assertEquals(0, book.count(OTHER_BOT));
    }

    @Test
    void aTicketExpiresOnItsOwnAndTheSweepDropsOnlyDeadOnes() {
        TicketBook book = new TicketBook();
        long lifetime = SupplyWithdrawalPolicy.ticketLifetimeMs(DEFAULTS);
        Ticket old = book.record(ticket(CHEST, STONE_AXE), 0L);
        Ticket young = book.record(ticket(CHEST, BREAD), 10_000L);
        // find() drops only the dead ticket it was asked about.
        assertNull(book.find(BOT, old.key(), lifetime, DEFAULTS), "now == deadline is expired");
        assertEquals(1, book.count(BOT));
        assertSame(young, book.find(BOT, young.key(), lifetime, DEFAULTS));

        Ticket third = book.record(ticket(OTHER_CHEST, COBBLE), 50_000L);
        book.sweep(10_000L + lifetime, DEFAULTS);
        assertNull(book.find(BOT, young.key(), 10_000L + lifetime, DEFAULTS));
        assertSame(third, book.find(BOT, third.key(), 10_000L + lifetime, DEFAULTS));
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

    // ── owner away ───────────────────────────────────────────────────────────────────────────

    @Test
    void theOwnerAwayNoteLastsFifteenSecondsPerBotAndChest() {
        assertEquals(15_000L, SupplyWithdrawalPolicy.OWNER_AWAY_MEMO_MS);
        OwnerAwayMemo memo = new OwnerAwayMemo();
        long t = 5_000L;
        assertFalse(memo.isAway(BOT, CHEST, t));
        memo.noteAway(BOT, CHEST, t);
        assertTrue(memo.isAway(BOT, CHEST, t));
        assertTrue(memo.isAway(BOT, CHEST, t + 14_999L));
        assertFalse(memo.isAway(BOT, OTHER_CHEST, t), "another chest is asked about as usual");
        assertFalse(memo.isAway(OTHER_BOT, CHEST, t), "another bot is asked about as usual");
        assertFalse(memo.isAway(BOT, CHEST, t + 15_000L), "now == deadline has lapsed");
        assertFalse(memo.isAway(BOT, CHEST, t + 1L), "a lapsed note is gone");

        memo.noteAway(BOT, CHEST, t);
        memo.noteAway(OTHER_BOT, CHEST, t + 10_000L);
        memo.sweep(t + 15_000L);
        assertFalse(memo.isAway(BOT, CHEST, t + 1L), "the sweep dropped the lapsed note");
        assertTrue(memo.isAway(OTHER_BOT, CHEST, t + 15_000L));
        memo.clear();
        assertFalse(memo.isAway(OTHER_BOT, CHEST, t + 15_000L));

        memo.noteAway(null, CHEST, t);
        memo.noteAway(BOT, null, t);
        assertFalse(memo.isAway(null, CHEST, t));
        assertFalse(memo.isAway(BOT, null, t));
    }

    // ── refusal scope: every value, no string literals ───────────────────────────────────────

    @Test
    void everyVerdictMapsToExactlyOneScope() {
        Map<Verdict, Scope> expected = new EnumMap<>(Verdict.class);
        expected.put(Verdict.NOT_ALLOWLISTED, Scope.ITEM);
        expected.put(Verdict.TIER_NOT_ALLOWED, Scope.ITEM);
        expected.put(Verdict.PROTECTED_COMPONENTS, Scope.ITEM);
        expected.put(Verdict.NO_NEED, Scope.ITEM);
        expected.put(Verdict.RESERVE_EXHAUSTED, Scope.CHEST);
        expected.put(Verdict.NO_OWNER, Scope.BOT);
        expected.put(Verdict.ELIGIBLE, Scope.BOT); // nonsense as a refusal: fail closed
        assertEquals(EnumSet.allOf(Verdict.class), expected.keySet(), "a new Verdict needs a scope here");
        for (Verdict v : Verdict.values()) {
            assertEquals(expected.get(v), SupplyWithdrawalPolicy.verdictScope(v), v.name());
            // As a request's INELIGIBLE, whatever access rides along.
            for (Access a : accessesAndNull()) {
                assertEquals(expected.get(v), SupplyWithdrawalPolicy.requestScope(RequestStatus.INELIGIBLE, a, v),
                        v + "/" + a);
            }
        }
        assertEquals(Scope.BOT, SupplyWithdrawalPolicy.verdictScope(null));
        assertEquals(Scope.BOT, SupplyWithdrawalPolicy.requestScope(RequestStatus.INELIGIBLE, null, null));
    }

    @Test
    void everyAccessDenialIsTheChestsWhateverTheAccess() {
        for (Access a : accessesAndNull()) {
            for (Verdict v : verdictsAndNull()) {
                assertEquals(Scope.CHEST, SupplyWithdrawalPolicy.requestScope(RequestStatus.DENIED, a, v), a + "/" + v);
            }
        }
    }

    @Test
    void everyRequestStatusMapsToExactlyOneScope() {
        Map<RequestStatus, Scope> expected = new EnumMap<>(RequestStatus.class);
        expected.put(RequestStatus.DUPLICATE_PENDING, Scope.BOT);
        expected.put(RequestStatus.PROMPT_COOLDOWN, Scope.BOT);
        expected.put(RequestStatus.REJECT_COOLDOWN, Scope.BOT);
        expected.put(RequestStatus.OWNER_NOT_NEARBY, Scope.OWNER_ABSENT);
        expected.put(RequestStatus.DENIED, Scope.CHEST);
        expected.put(RequestStatus.INVALID, Scope.TRANSIENT);
        expected.put(RequestStatus.NOT_RUNNING, Scope.TRANSIENT);
        expected.put(RequestStatus.WRONG_THREAD, Scope.TRANSIENT);
        // A refusal only when the ledger gave no fingerprint, which cannot happen: fail closed.
        expected.put(RequestStatus.OPENED, Scope.BOT);
        expected.put(RequestStatus.COVERED_BY_ALWAYS, Scope.BOT);
        // INELIGIBLE depends on the verdict (above); with none attached it fails closed.
        expected.put(RequestStatus.INELIGIBLE, Scope.BOT);
        assertEquals(EnumSet.allOf(RequestStatus.class), expected.keySet(), "a new RequestStatus needs a scope here");
        for (RequestStatus s : RequestStatus.values()) {
            if (s == RequestStatus.INELIGIBLE) {
                assertEquals(expected.get(s), SupplyWithdrawalPolicy.requestScope(s, null, null));
                continue;
            }
            // Only INELIGIBLE reads the verdict and nothing reads the access: every combination agrees.
            for (Access a : accessesAndNull()) {
                for (Verdict v : verdictsAndNull()) {
                    assertEquals(expected.get(s), SupplyWithdrawalPolicy.requestScope(s, a, v), s + "/" + a + "/" + v);
                }
            }
        }
        assertEquals(Scope.TRANSIENT, SupplyWithdrawalPolicy.requestScope(null, null, null));
    }

    @Test
    void everyTransferStatusMapsToExactlyOneScope() {
        Map<TransferStatus, Scope> expected = new EnumMap<>(TransferStatus.class);
        expected.put(TransferStatus.DENIED, Scope.CHEST);
        expected.put(TransferStatus.CHEST_MISMATCH, Scope.CHEST);
        expected.put(TransferStatus.NO_STOCK, Scope.CHEST);
        expected.put(TransferStatus.INELIGIBLE_NOW, Scope.CHEST);
        expected.put(TransferStatus.NOT_PERMITTED, Scope.BOT);
        expected.put(TransferStatus.OWNER_OR_BOT_MISMATCH, Scope.BOT);
        expected.put(TransferStatus.NO_ROOM, Scope.TRANSIENT);
        expected.put(TransferStatus.OUT_OF_REACH, Scope.TRANSIENT); // reported as READY, never as a refusal
        expected.put(TransferStatus.WRONG_THREAD, Scope.TRANSIENT);
        expected.put(TransferStatus.NOT_RUNNING, Scope.TRANSIENT);
        expected.put(TransferStatus.INVALID, Scope.TRANSIENT);
        expected.put(TransferStatus.MOVED, Scope.TRANSIENT);        // a refusal only when nothing moved
        expected.put(TransferStatus.MOVED_SHORT, Scope.TRANSIENT);
        assertEquals(EnumSet.allOf(TransferStatus.class), expected.keySet(), "a new TransferStatus needs a scope here");
        for (TransferStatus s : TransferStatus.values()) {
            assertEquals(expected.get(s), SupplyWithdrawalPolicy.transferScope(s), s.name());
        }
        assertEquals(Scope.TRANSIENT, SupplyWithdrawalPolicy.transferScope(null));
    }

    @Test
    void everyFacadeRefusalHasExactlyOneScope() {
        Map<Refusal, Scope> expected = new EnumMap<>(Refusal.class);
        expected.put(Refusal.NO_OWNER, Scope.BOT);
        expected.put(Refusal.OTHER_REQUEST_PENDING, Scope.BOT);
        expected.put(Refusal.NOT_PERMITTED, Scope.BOT);
        expected.put(Refusal.OWNER_NOT_NEARBY, Scope.OWNER_ABSENT);
        expected.put(Refusal.NOT_RUNNING, Scope.TRANSIENT);
        expected.put(Refusal.BOT_GONE, Scope.TRANSIENT);
        expected.put(Refusal.INVALID, Scope.TRANSIENT);
        expected.put(Refusal.CHEST_UNREADABLE, Scope.TRANSIENT);
        expected.put(Refusal.TIMEOUT, Scope.TRANSIENT);
        expected.put(Refusal.ABORTED, Scope.TRANSIENT);
        expected.put(Refusal.SERVER_BUSY, Scope.TRANSIENT);
        expected.put(Refusal.ERROR, Scope.TRANSIENT);
        assertEquals(EnumSet.allOf(Refusal.class), expected.keySet(), "a new Refusal needs a scope here");
        for (Refusal r : Refusal.values()) {
            assertEquals(expected.get(r), r.scope(), r.name());
        }
    }

    @Test
    void aReasonSpelledTheSameMeansTheSameScopeWhoeverSaysIt() {
        for (Refusal r : Refusal.values()) {
            for (RequestStatus s : RequestStatus.values()) {
                if (s.name().equals(r.name()) && s != RequestStatus.INELIGIBLE) {
                    assertEquals(r.scope(), SupplyWithdrawalPolicy.requestScope(s, null, null), r.name());
                }
            }
            for (TransferStatus s : TransferStatus.values()) {
                if (s.name().equals(r.name())) {
                    assertEquals(r.scope(), SupplyWithdrawalPolicy.transferScope(s), r.name());
                }
            }
            for (Verdict v : Verdict.values()) {
                if (v.name().equals(r.name())) {
                    assertEquals(r.scope(), SupplyWithdrawalPolicy.verdictScope(v), r.name());
                }
            }
        }
    }

    @Test
    void noRefusalReadsAsNoneAndNothingButARefusalCarriesAScope() {
        for (Scope s : scopesAndNull()) {
            for (Kind k : Kind.values()) {
                Scope got = SupplyWithdrawalPolicy.resultScope(k, s);
                if (k == Kind.REFUSED) {
                    assertEquals(s == null || s == Scope.NONE ? Scope.BOT : s, got, k + "/" + s);
                } else {
                    assertEquals(Scope.NONE, got, k + "/" + s);
                }
            }
        }
        for (RequestStatus s : RequestStatus.values()) {
            for (Verdict v : verdictsAndNull()) {
                assertNotEquals(Scope.NONE, SupplyWithdrawalPolicy.requestScope(s, null, v), s + "/" + v);
            }
        }
        for (TransferStatus s : TransferStatus.values()) {
            assertNotEquals(Scope.NONE, SupplyWithdrawalPolicy.transferScope(s), s.name());
        }
        for (Refusal r : Refusal.values()) {
            assertNotEquals(Scope.NONE, r.scope(), r.name());
        }
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
    void everyTransferStatusMapsToAKindATicketFateAndAScope() {
        Map<TransferStatus, TransferAction> expected = new EnumMap<>(TransferStatus.class);
        expected.put(TransferStatus.MOVED, new TransferAction(Kind.MOVED, false, "MOVED", Scope.NONE));
        expected.put(TransferStatus.MOVED_SHORT, new TransferAction(Kind.MOVED, false, "MOVED_SHORT", Scope.NONE));
        expected.put(TransferStatus.OUT_OF_REACH, new TransferAction(Kind.READY, true, "OUT_OF_REACH", Scope.NONE));
        expected.put(TransferStatus.NO_ROOM, new TransferAction(Kind.REFUSED, true, "NO_ROOM", Scope.TRANSIENT));
        // The ledger keeps the grant when the policy refuses at transfer time; so does the ticket.
        expected.put(TransferStatus.INELIGIBLE_NOW,
                new TransferAction(Kind.REFUSED, true, "INELIGIBLE_NOW", Scope.CHEST));
        for (TransferStatus dropped : EnumSet.of(TransferStatus.WRONG_THREAD, TransferStatus.NOT_RUNNING,
                TransferStatus.INVALID, TransferStatus.OWNER_OR_BOT_MISMATCH, TransferStatus.DENIED,
                TransferStatus.CHEST_MISMATCH, TransferStatus.NO_STOCK, TransferStatus.NOT_PERMITTED)) {
            expected.put(dropped, new TransferAction(Kind.REFUSED, false, dropped.name(),
                    SupplyWithdrawalPolicy.transferScope(dropped)));
        }
        assertEquals(EnumSet.allOf(TransferStatus.class), expected.keySet(), "a new status needs a decision here");
        for (TransferStatus s : TransferStatus.values()) {
            int moved = s == TransferStatus.MOVED || s == TransferStatus.MOVED_SHORT ? 5 : 0;
            assertEquals(expected.get(s), SupplyWithdrawalPolicy.onTransfer(s, moved), s.name());
        }
    }

    @Test
    void aMoveThatMovedNothingIsATransientRefusalAndTheTicketGoes() {
        assertEquals(new TransferAction(Kind.REFUSED, false, "MOVED_SHORT", Scope.TRANSIENT),
                SupplyWithdrawalPolicy.onTransfer(TransferStatus.MOVED_SHORT, 0));
        assertEquals(new TransferAction(Kind.REFUSED, false, "MOVED", Scope.TRANSIENT),
                SupplyWithdrawalPolicy.onTransfer(TransferStatus.MOVED, 0));
        assertEquals(new TransferAction(Kind.REFUSED, false, "INVALID", Scope.TRANSIENT),
                SupplyWithdrawalPolicy.onTransfer(null, 3));
    }

    @Test
    void reachRoomAndAPolicyRefusalNowKeepTheTicket() {
        for (TransferStatus s : TransferStatus.values()) {
            boolean kept = SupplyWithdrawalPolicy.onTransfer(s, 1).keepTicket();
            assertEquals(s == TransferStatus.OUT_OF_REACH || s == TransferStatus.NO_ROOM
                    || s == TransferStatus.INELIGIBLE_NOW, kept, s.name());
        }
    }

    // ── named sequences: an "Allow once" survives whatever the bot asks about in between ─────

    /**
     * R1: idle equipment asks, in ascending score, for a lone wooden sword (the chest's spare, so
     * RESERVE_EXHAUSTED) before the wooden axe, whose prompt opened. After "Allow once" the next
     * pass asks about the sword first again; the axe still redeems and the owner sees one prompt.
     */
    @Test
    void r1TheLoneSwordAskedBeforeThePromptedAxeLeavesTheAxesAllowOnce() {
        Model m = new Model();
        Stock oneSword = Stock.of(1);
        Stock twoAxes = Stock.of(2);

        assertEquals(Scope.CHEST, m.withdraw(CHEST, WOODEN_SWORD, oneSword, true).scope(), "the spare stays: next item");
        Outcome asked = m.withdraw(CHEST, WOODEN_AXE, twoAxes, true);
        assertEquals(Kind.WAITING, asked.kind());
        assertEquals(ResponseStatus.GRANTED_ONCE, m.answer(asked, Choice.ALLOW_ONCE));

        m.advance(2_000L);
        Outcome sword = m.withdraw(CHEST, WOODEN_SWORD, oneSword, true);
        assertEquals(Kind.REFUSED, sword.kind());
        assertEquals(Scope.CHEST, sword.scope());
        Outcome axe = m.withdraw(CHEST, WOODEN_AXE, twoAxes, true);
        assertEquals(Kind.MOVED, axe.kind(), "the Allow once is spent by the axe's own call");
        assertEquals(1, axe.moved());
        assertEquals(1, m.prompts, "no second prompt");
        assertEquals(0, m.tickets.count(BOT), "the spent ticket is gone");
    }

    /**
     * R2: best-tier-first chest retrieval tries chest 1's lone stone axe (its spare) before chest
     * 2's wooden axes, whose prompt opened. After the click the next search tries chest 1 first
     * again; chest 2 still redeems, first as READY (walk there), then MOVED.
     */
    @Test
    void r2BestTierFirstRetrievalStillRedeemsTheSecondChestAfterTheClick() {
        Model m = new Model();
        Stock loneStoneAxe = Stock.of(1);
        Stock twoWoodenAxes = Stock.of(2);

        assertEquals(Scope.CHEST, m.withdraw(CHEST, STONE_AXE, loneStoneAxe, false).scope());
        Outcome asked = m.withdraw(OTHER_CHEST, WOODEN_AXE, twoWoodenAxes, false);
        assertEquals(Kind.WAITING, asked.kind());
        m.answer(asked, Choice.ALLOW_ONCE);

        m.advance(3_000L);
        assertEquals(Scope.CHEST, m.withdraw(CHEST, STONE_AXE, loneStoneAxe, false).scope());
        Outcome ready = m.withdraw(OTHER_CHEST, WOODEN_AXE, twoWoodenAxes, false);
        assertEquals(Kind.READY, ready.kind(), "permitted: walk there");
        m.advance(4_000L);
        assertEquals(Scope.CHEST, m.withdraw(CHEST, STONE_AXE, loneStoneAxe, true).scope(), "asked again on the way");
        Outcome taken = m.withdraw(OTHER_CHEST, WOODEN_AXE, twoWoodenAxes, true);
        assertEquals(Kind.MOVED, taken.kind());
        assertEquals(1, m.prompts);
    }

    /**
     * R3: a MutualAid bread ask lands between the owner's "yes" to the axe and the axe's redeem.
     * First inside the prompt cooldown the yes set (refused, bot-wide), then after it, when the
     * bread prompt opens and is still pending: the axe redeems either way, since permitted comes
     * before pending.
     */
    @Test
    void r3ABreadAskBetweenTheAxesYesAndItsRedeemLeavesTheAxeTicket() {
        Model m = new Model();
        Stock axes = Stock.of(3);
        Stock bread = Stock.of(40);

        Outcome asked = m.withdraw(CHEST, STONE_AXE, axes, true);
        m.answer(asked, Choice.ALLOW_ONCE);

        m.advance(1_000L);
        Outcome early = m.withdraw(CHEST, BREAD, bread, true);
        assertEquals(Kind.REFUSED, early.kind());
        assertEquals(Scope.BOT, early.scope(), "PROMPT_COOLDOWN from the owner's answer");
        assertEquals(1, m.tickets.count(BOT), "the axe ticket survives");

        m.advance(15_000L);
        Outcome breadAsked = m.withdraw(CHEST, BREAD, bread, true);
        assertEquals(Kind.WAITING, breadAsked.kind(), "the bread prompt opens");
        assertTrue(m.ledger.hasPending(BOT));
        assertEquals(2, m.tickets.count(BOT));

        Outcome axe = m.withdraw(CHEST, STONE_AXE, axes, true);
        assertEquals(Kind.MOVED, axe.kind(), "permitted first: the bread prompt pending is the bot's, not the axe's");
        assertEquals(2, m.prompts, "the axe was prompted once, the bread once");
        // The bread ticket still waits for its own answer.
        assertEquals(Kind.WAITING, m.withdraw(CHEST, BREAD, bread, true).kind());
    }

    @Test
    void aNoAfterAnEarlierAllowOnceMovesNothing() {
        Model m = new Model();
        Stock axes = Stock.of(3);
        Outcome first = m.withdraw(CHEST, STONE_AXE, axes, true);
        m.answer(first, Choice.ALLOW_ONCE);
        // The grant goes unspent (say the bot wandered off) and its ticket lapses...
        m.tickets.clear();
        m.advance(16_000L);
        Outcome again = m.withdraw(CHEST, STONE_AXE, axes, true);
        assertEquals(Kind.WAITING, again.kind(), "re-asked");
        assertEquals(ResponseStatus.REJECTED, m.answer(again, Choice.NO));
        Outcome after = m.withdraw(CHEST, STONE_AXE, axes, true);
        assertEquals(Kind.REFUSED, after.kind(), "the latest answer wins");
        assertEquals(Scope.BOT, after.scope());
        assertEquals(0, after.moved());
    }

    @Test
    void anotherPromptPendingRefusesWithoutAskingOrDroppingAnything() {
        Model m = new Model();
        Outcome asked = m.withdraw(CHEST, STONE_AXE, Stock.of(3), true);
        assertEquals(Kind.WAITING, asked.kind());
        Outcome bread = m.withdraw(CHEST, BREAD, Stock.of(40), true);
        assertEquals(Kind.REFUSED, bread.kind());
        assertEquals(Scope.BOT, bread.scope());
        assertEquals(1, m.prompts);
        assertEquals(1, m.tickets.count(BOT));
        assertEquals(Kind.WAITING, m.withdraw(CHEST, STONE_AXE, Stock.of(3), true).kind(), "its own ticket waits");
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

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static List<Access> accessesAndNull() {
        List<Access> all = new ArrayList<>(Arrays.asList(Access.values()));
        all.add(null);
        return all;
    }

    private static List<Verdict> verdictsAndNull() {
        List<Verdict> all = new ArrayList<>(Arrays.asList(Verdict.values()));
        all.add(null);
        return all;
    }

    private static List<Scope> scopesAndNull() {
        List<Scope> all = new ArrayList<>(Arrays.asList(Scope.values()));
        all.add(null);
        return all;
    }

    /** What one modelled call reported. */
    private record Outcome(Kind kind, int moved, Scope scope, UUID requestId) {
    }

    /**
     * {@code SupplyWithdrawals.step} past its world reads, for one bot of {@link #OWNER} with the
     * owner nearby: the real ledger on a fake clock, the real ticket book, and the policy's own
     * decisions in the facade's order — find this chest and item's ticket, {@link SupplyWithdrawalPolicy#ticketStep},
     * then request ({@link SupplyWithdrawalPolicy#onRequest}) or redeem (the ledger's consumeGrant,
     * {@link SupplyChestRules#transferRefusal}, {@link SupplyWithdrawalPolicy#onTransfer}).
     */
    private static final class Model {
        final long[] now = {1_000_000L};
        long nextId;
        final SupplyRequestLedger ledger =
                new SupplyRequestLedger(() -> now[0], () -> new UUID(9, ++nextId), DEFAULTS, Config.defaults());
        final TicketBook tickets = new TicketBook();
        int prompts;

        void advance(long ms) {
            now[0] += ms;
        }

        ResponseStatus answer(Outcome asked, Choice choice) {
            assertNotNull(asked.requestId(), "no prompt to answer");
            return ledger.respond(asked.requestId(), OWNER, false, choice).status();
        }

        Outcome withdraw(ChestKey chest, ItemKey item, Stock stock, boolean inReach) {
            long t = now[0];
            Ticket ticket = tickets.find(BOT, TicketKey.of(chest, item), t, DEFAULTS);
            boolean pending = ledger.hasPending(BOT);
            boolean permitted = ticket != null && ledger.isPermitted(ticket.fp());
            switch (SupplyWithdrawalPolicy.ticketStep(ticket != null, pending, permitted)) {
                case REDEEM:
                    return redeem(ticket, stock, inReach);
                case WAIT:
                    return new Outcome(Kind.WAITING, 0, Scope.NONE, null);
                case DROP_NOT_PERMITTED:
                    tickets.drop(ticket);
                    return refused(Refusal.NOT_PERMITTED.scope());
                case OTHER_PENDING:
                    return refused(Refusal.OTHER_REQUEST_PENDING.scope());
                case REQUEST:
                default:
                    break;
            }
            OpenResult opened = ledger.open(new RequestFingerprint(OWNER, BOT, chest, item, 1), stock, 1);
            RequestStatus status = RequestStatus.valueOf(opened.status().name());
            switch (SupplyWithdrawalPolicy.onRequest(status)) {
                case TAKE:
                    return redeem(tickets.record(opened.fingerprint(), t), stock, inReach);
                case WAIT:
                    prompts++;
                    tickets.record(opened.fingerprint(), t);
                    return new Outcome(Kind.WAITING, 0, Scope.NONE, opened.requestId());
                case REFUSE:
                default:
                    return refused(SupplyWithdrawalPolicy.requestScope(status, null, opened.verdict()));
            }
        }

        private Outcome redeem(Ticket ticket, Stock stock, boolean inReach) {
            if (!inReach) {
                return new Outcome(Kind.READY, 0, Scope.NONE, null);
            }
            Consume consume = ledger.consumeGrant(ticket.fp(), stock, 1);
            TransferStatus status = consume.permitted() ? TransferStatus.MOVED
                    : SupplyChestRules.transferRefusal(consume.status());
            TransferAction action = SupplyWithdrawalPolicy.onTransfer(status, consume.quantity());
            if (!action.keepTicket()) {
                tickets.drop(ticket);
            }
            return new Outcome(action.kind(), action.kind() == Kind.MOVED ? consume.quantity() : 0, action.scope(), null);
        }

        private static Outcome refused(Scope scope) {
            return new Outcome(Kind.REFUSED, 0, SupplyWithdrawalPolicy.resultScope(Kind.REFUSED, scope), null);
        }
    }
}
