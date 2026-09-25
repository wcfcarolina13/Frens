package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.AlwaysScope;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Consume;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ConsumeStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.OpenResult;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.OpenStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Response;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ResponseStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Revoked;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Choice;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Config;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Stock;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyRequestLedgerTest {

    private static final String WORLD = "New World/minecraft:overworld";
    private static final String NETHER = "New World/minecraft:the_nether";
    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID OTHER_OWNER = new UUID(1, 2);
    private static final UUID STRANGER = new UUID(1, 3);
    private static final UUID BOT = new UUID(2, 1);
    private static final UUID BOT_2 = new UUID(2, 2);
    private static final UUID OTHER_OWNERS_BOT = new UUID(2, 3);
    private static final ChestKey CHEST = new ChestKey(WORLD, 10, 64, 10);
    private static final ChestKey OTHER_CHEST = new ChestKey(WORLD, 20, 64, 20);
    private static final ItemKey COBBLE = ItemKey.plain("minecraft:cobblestone");
    private static final ItemKey TORCH = ItemKey.plain("minecraft:torch");
    private static final ItemKey STONE_AXE = ItemKey.plain("minecraft:stone_axe");
    private static final Stock PLENTY = Stock.of(64);

    private final long[] now = {1_000_000L};
    private long nextId;
    private SupplyRequestLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = newLedger(Config.defaults());
    }

    private SupplyRequestLedger newLedger(Config config) {
        return new SupplyRequestLedger(() -> now[0], () -> new UUID(9, ++nextId), Timings.defaults(), config);
    }

    private void advance(long ms) {
        now[0] += ms;
    }

    private static RequestFingerprint fp(UUID owner, UUID bot, ChestKey chest, ItemKey item, int qty) {
        return new RequestFingerprint(owner, bot, chest, item, qty);
    }

    private static RequestFingerprint cobble(int qty) {
        return fp(OWNER, BOT, CHEST, COBBLE, qty);
    }

    private UUID openOk(RequestFingerprint request) {
        OpenResult r = ledger.open(request, PLENTY, request.qty());
        assertEquals(OpenStatus.OPENED, r.status());
        assertNotNull(r.requestId());
        return r.requestId();
    }

    // ── happy path and replay ────────────────────────────────────────────────────────────────

    @Test
    void allowOnceGrantsExactlyOneConsume() {
        UUID id = openOk(cobble(8));
        assertEquals(ResponseStatus.GRANTED_ONCE, ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE).status());

        Consume first = ledger.consumeGrant(cobble(8), PLENTY, 8);
        assertEquals(ConsumeStatus.ONCE, first.status());
        assertEquals(8, first.quantity());
        assertTrue(first.permitted());

        Consume replay = ledger.consumeGrant(cobble(8), PLENTY, 8);
        assertEquals(ConsumeStatus.NO_GRANT, replay.status());
        assertEquals(0, replay.quantity());
        assertFalse(replay.permitted());
    }

    @Test
    void answeringTwiceFindsNothing() {
        UUID id = openOk(cobble(8));
        assertEquals(ResponseStatus.GRANTED_ONCE, ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE).status());
        assertEquals(ResponseStatus.NOT_FOUND, ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE).status());
        assertEquals(ResponseStatus.NOT_FOUND, ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON).status());
        assertEquals(ResponseStatus.NOT_FOUND, ledger.respond(new UUID(7, 7), OWNER, false, Choice.NO).status());
        assertEquals(ResponseStatus.NOT_FOUND, ledger.respond(null, OWNER, false, Choice.NO).status());
    }

    @Test
    void openCutsTheQuantityToWhatTheReserveAllows() {
        OpenResult r = ledger.open(cobble(10), Stock.of(20), 10);
        assertEquals(OpenStatus.OPENED, r.status());
        assertEquals(4, r.fingerprint().qty());
        ledger.respond(r.requestId(), OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(ConsumeStatus.OVER_GRANT, ledger.consumeGrant(cobble(10), Stock.of(20), 10).status());
        Consume ok = ledger.consumeGrant(cobble(4), Stock.of(20), 10);
        assertEquals(ConsumeStatus.ONCE, ok.status());
        assertEquals(4, ok.quantity());
    }

    @Test
    void smallerConsumeThanGrantedIsAllowedAndSpendsTheGrant() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        Consume partial = ledger.consumeGrant(cobble(5), PLENTY, 8);
        assertEquals(ConsumeStatus.ONCE, partial.status());
        assertEquals(5, partial.quantity());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(3), PLENTY, 8).status());
    }

    // ── exact fingerprint binding ────────────────────────────────────────────────────────────

    @Test
    void mismatchedFingerprintsCannotSpendTheGrant() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);

        ItemKey namedCobble = new ItemKey("minecraft:cobblestone", "fp:named", Set.of("minecraft:custom_name"));
        ItemKey otherFpCobble = new ItemKey("minecraft:cobblestone", "fp:other", Set.of());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT, CHEST, otherFpCobble, 8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT, CHEST, namedCobble, 8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT, CHEST, TORCH, 8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT, OTHER_CHEST, COBBLE, 8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT_2, CHEST, COBBLE, 8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OTHER_OWNER, BOT, CHEST, COBBLE, 8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(null, BOT, CHEST, COBBLE, 8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.OVER_GRANT, ledger.consumeGrant(cobble(9), PLENTY, 9).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(null, PLENTY, 8).status());

        // None of the failed attempts burned the real grant.
        assertEquals(ConsumeStatus.ONCE, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
    }

    @Test
    void sameChestInAnotherWorldDoesNotMatch() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        ChestKey netherTwin = new ChestKey(NETHER, CHEST.x(), CHEST.y(), CHEST.z());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT, netherTwin, COBBLE, 8), PLENTY, 8).status());
    }

    // ── owner binding ────────────────────────────────────────────────────────────────────────

    @Test
    void foreignCallerIsRejectedAndTheOwnerCanStillAnswer() {
        UUID id = openOk(cobble(8));
        Response foreign = ledger.respond(id, STRANGER, false, Choice.ALLOW_ONCE);
        assertEquals(ResponseStatus.FOREIGN_CALLER, foreign.status());
        assertNull(foreign.fingerprint());
        assertEquals(ResponseStatus.FOREIGN_CALLER, ledger.respond(id, STRANGER, false, Choice.NO).status());
        assertEquals(ResponseStatus.FOREIGN_CALLER, ledger.respond(id, null, false, Choice.NO).status());
        assertTrue(ledger.hasPending(BOT));
        // The stranger's No did not arm a rejection cooldown and the grant was not issued.
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(8), PLENTY, 8).status());

        Response owner = ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(ResponseStatus.GRANTED_ONCE, owner.status());
        assertEquals(cobble(8), owner.fingerprint());
        assertEquals(ConsumeStatus.ONCE, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
    }

    @Test
    void operatorMayNotAnswerUnlessConfigured() {
        UUID id = openOk(cobble(8));
        assertEquals(ResponseStatus.FOREIGN_CALLER, ledger.respond(id, STRANGER, true, Choice.ALLOW_ONCE).status());
        assertTrue(ledger.hasPending(BOT));

        ledger = newLedger(Config.defaults().withOperatorMayApprove(true));
        UUID id2 = openOk(cobble(8));
        assertEquals(ResponseStatus.FOREIGN_CALLER, ledger.respond(id2, STRANGER, false, Choice.ALLOW_ONCE).status());
        assertEquals(ResponseStatus.GRANTED_ONCE, ledger.respond(id2, STRANGER, true, Choice.ALLOW_ONCE).status());
    }

    @Test
    void unownedBotCannotRequestOrBeGranted() {
        OpenResult r = ledger.open(fp(null, BOT, CHEST, COBBLE, 8), PLENTY, 8);
        assertEquals(OpenStatus.INELIGIBLE, r.status());
        assertEquals(Verdict.NO_OWNER, r.verdict());
        assertNull(r.requestId());
        assertFalse(ledger.hasPending(BOT));
        Consume c = ledger.consumeGrant(fp(null, BOT, CHEST, COBBLE, 8), PLENTY, 8);
        assertFalse(c.permitted());
        assertEquals(Verdict.NO_OWNER, c.verdict());
    }

    // ── eligibility at open ──────────────────────────────────────────────────────────────────

    @Test
    void ineligibleItemsNeverOpenAPrompt() {
        assertEquals(Verdict.NOT_ALLOWLISTED,
                ledger.open(fp(OWNER, BOT, CHEST, ItemKey.plain("minecraft:diamond"), 1), PLENTY, 1).verdict());
        assertEquals(Verdict.TIER_NOT_ALLOWED,
                ledger.open(fp(OWNER, BOT, CHEST, ItemKey.plain("minecraft:iron_axe"), 1), new Stock(1, 3), 1).verdict());
        ItemKey enchanted = new ItemKey("minecraft:stone_axe", "fp:ench", Set.of("minecraft:enchantments"));
        assertEquals(Verdict.PROTECTED_COMPONENTS,
                ledger.open(fp(OWNER, BOT, CHEST, enchanted, 1), new Stock(1, 3), 1).verdict());
        assertEquals(Verdict.RESERVE_EXHAUSTED, ledger.open(cobble(8), Stock.of(16), 8).verdict());
        assertEquals(Verdict.RESERVE_EXHAUSTED,
                ledger.open(fp(OWNER, BOT, CHEST, STONE_AXE, 1), Stock.of(1), 1).verdict());
        assertEquals(Verdict.NO_NEED, ledger.open(cobble(8), PLENTY, 0).verdict());
        assertFalse(ledger.hasPending(BOT));
    }

    // ── expiry ───────────────────────────────────────────────────────────────────────────────

    @Test
    void anUnansweredRequestExpiresAndGrantsNothing() {
        UUID id = openOk(cobble(8));
        advance(29_999L);
        assertTrue(ledger.hasPending(BOT));
        advance(1L); // exactly the deadline counts as expired
        assertFalse(ledger.hasPending(BOT));
        Response late = ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(ResponseStatus.EXPIRED, late.status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
        assertEquals(ResponseStatus.NOT_FOUND, ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE).status());
    }

    @Test
    void anExpiredGrantCannotBeConsumed() {
        UUID id = openOk(cobble(8));
        advance(10_000L);
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        advance(59_999L); // grant lifetime runs from the answer
        assertEquals(ConsumeStatus.ONCE, ledger.consumeGrant(cobble(1), PLENTY, 8).status());

        UUID id2 = openOk(fp(OWNER, BOT_2, CHEST, COBBLE, 8));
        ledger.respond(id2, OWNER, false, Choice.ALLOW_ONCE);
        advance(60_000L);
        Consume c = ledger.consumeGrant(fp(OWNER, BOT_2, CHEST, COBBLE, 8), PLENTY, 8);
        assertEquals(ConsumeStatus.EXPIRED, c.status());
        assertFalse(c.permitted());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT_2, CHEST, COBBLE, 8), PLENTY, 8).status());
    }

    // ── one prompt at a time, prompt cooldown ────────────────────────────────────────────────

    @Test
    void oneOpenPromptPerBot() {
        openOk(cobble(8));
        assertEquals(OpenStatus.DUPLICATE_PENDING, ledger.open(cobble(8), PLENTY, 8).status());
        assertEquals(OpenStatus.DUPLICATE_PENDING,
                ledger.open(fp(OWNER, BOT, OTHER_CHEST, TORCH, 4), PLENTY, 4).status());
        // Another bot is independent.
        assertEquals(OpenStatus.OPENED, ledger.open(fp(OWNER, BOT_2, CHEST, COBBLE, 8), PLENTY, 8).status());
    }

    @Test
    void promptCooldownRunsFromTheAnswerAndFromTheExpiry() {
        UUID id = openOk(cobble(8));
        advance(2_000L);
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        advance(14_999L);
        assertEquals(OpenStatus.PROMPT_COOLDOWN, ledger.open(fp(OWNER, BOT, CHEST, TORCH, 4), PLENTY, 4).status());
        advance(1L);
        UUID id2 = openOk(fp(OWNER, BOT, CHEST, TORCH, 4));

        // Ignored prompt: expires at +30 s, the bot then waits a further 15 s.
        advance(30_000L);
        assertEquals(OpenStatus.PROMPT_COOLDOWN, ledger.open(fp(OWNER, BOT, CHEST, TORCH, 4), PLENTY, 4).status());
        advance(14_999L);
        assertEquals(OpenStatus.PROMPT_COOLDOWN, ledger.open(fp(OWNER, BOT, CHEST, TORCH, 4), PLENTY, 4).status());
        advance(1L);
        assertEquals(OpenStatus.OPENED, ledger.open(fp(OWNER, BOT, CHEST, TORCH, 4), PLENTY, 4).status());
        assertEquals(ResponseStatus.NOT_FOUND, ledger.respond(id2, OWNER, false, Choice.ALLOW_ONCE).status());
    }

    // ── rejection cooldown ───────────────────────────────────────────────────────────────────

    @Test
    void rejectionCooldownBlocksThenLapsesAndIsNarrow() {
        UUID id = openOk(cobble(8));
        assertEquals(ResponseStatus.REJECTED, ledger.respond(id, OWNER, false, Choice.NO).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(8), PLENTY, 8).status());

        advance(15_000L); // past the prompt cooldown
        assertEquals(OpenStatus.REJECT_COOLDOWN, ledger.open(cobble(8), PLENTY, 8).status());
        // A different component fingerprint of the same item id is still refused.
        ItemKey worn = new ItemKey("minecraft:cobblestone", "fp:x", Set.of());
        assertEquals(OpenStatus.REJECT_COOLDOWN, ledger.open(fp(OWNER, BOT, CHEST, worn, 8), PLENTY, 8).status());
        // Other bot, other item, other chest: unaffected.
        assertEquals(OpenStatus.OPENED, ledger.open(fp(OWNER, BOT_2, CHEST, COBBLE, 8), PLENTY, 8).status());
        UUID torch = openOk(fp(OWNER, BOT, CHEST, TORCH, 4));
        ledger.respond(torch, OWNER, false, Choice.ALLOW_ONCE);
        advance(15_000L);
        assertEquals(OpenStatus.OPENED, ledger.open(fp(OWNER, BOT, OTHER_CHEST, COBBLE, 8), PLENTY, 8).status());
    }

    @Test
    void rejectionCooldownLapsesAfterFiveMinutes() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.NO);
        advance(299_999L);
        assertEquals(OpenStatus.REJECT_COOLDOWN, ledger.open(cobble(8), PLENTY, 8).status());
        advance(1L);
        assertEquals(OpenStatus.OPENED, ledger.open(cobble(8), PLENTY, 8).status());
    }

    // ── always ───────────────────────────────────────────────────────────────────────────────

    @Test
    void alwaysIsScopedToOwnerWorldAndChest() {
        UUID id = openOk(cobble(8));
        Response r = ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON);
        assertEquals(ResponseStatus.GRANTED_ALWAYS, r.status());
        assertTrue(ledger.hasAlways(OWNER, CHEST));

        // Same owner, same chest: another common item and another of the owner's bots need no prompt.
        OpenResult torch = ledger.open(fp(OWNER, BOT, CHEST, TORCH, 4), PLENTY, 4);
        assertEquals(OpenStatus.COVERED_BY_ALWAYS, torch.status());
        assertEquals(4, torch.fingerprint().qty());
        assertEquals(ConsumeStatus.ALWAYS, ledger.consumeGrant(fp(OWNER, BOT_2, CHEST, TORCH, 4), PLENTY, 4).status());

        // Another chest, the same coordinates in another world, another owner: not covered.
        assertFalse(ledger.hasAlways(OWNER, OTHER_CHEST));
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT_2, OTHER_CHEST, TORCH, 4), PLENTY, 4).status());
        ChestKey netherTwin = new ChestKey(NETHER, CHEST.x(), CHEST.y(), CHEST.z());
        assertFalse(ledger.hasAlways(OWNER, netherTwin));
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT_2, netherTwin, TORCH, 4), PLENTY, 4).status());
        assertFalse(ledger.hasAlways(OTHER_OWNER, CHEST));
        assertEquals(OpenStatus.OPENED,
                ledger.open(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, TORCH, 4), PLENTY, 4).status());
        assertEquals(ConsumeStatus.NO_GRANT,
                ledger.consumeGrant(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, TORCH, 4), PLENTY, 4).status());
    }

    @Test
    void alwaysNeverCoversNonAllowlistedOrProtectedItems() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON);
        advance(15_000L);

        RequestFingerprint diamond = fp(OWNER, BOT, CHEST, ItemKey.plain("minecraft:diamond"), 1);
        assertEquals(Verdict.NOT_ALLOWLISTED, ledger.open(diamond, PLENTY, 1).verdict());
        Consume d = ledger.consumeGrant(diamond, PLENTY, 1);
        assertEquals(ConsumeStatus.INELIGIBLE, d.status());
        assertEquals(Verdict.NOT_ALLOWLISTED, d.verdict());

        RequestFingerprint named = fp(OWNER, BOT, CHEST,
                new ItemKey("minecraft:cobblestone", "fp:n", Set.of("minecraft:custom_name")), 8);
        assertEquals(Verdict.PROTECTED_COMPONENTS, ledger.open(named, PLENTY, 8).verdict());
        assertEquals(Verdict.PROTECTED_COMPONENTS, ledger.consumeGrant(named, PLENTY, 8).verdict());

        RequestFingerprint ironAxe = fp(OWNER, BOT, CHEST, ItemKey.plain("minecraft:iron_axe"), 1);
        assertEquals(Verdict.TIER_NOT_ALLOWED, ledger.consumeGrant(ironAxe, new Stock(1, 3), 1).verdict());
        assertFalse(ledger.consumeGrant(ironAxe, new Stock(1, 3), 1).permitted());
    }

    @Test
    void reservesHoldUnderAlwaysAndAgainstChangedStock() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON);

        // The approved request itself: stock fell from 64 to 20 before the transfer.
        Consume approved = ledger.consumeGrant(cobble(8), Stock.of(20), 8);
        assertEquals(ConsumeStatus.ONCE, approved.status());
        assertEquals(4, approved.quantity());

        // Standing permission, repeated withdrawals: each re-reads stock, none dips below 16.
        int stock = 40;
        for (int i = 0; i < 10; i++) {
            Consume c = ledger.consumeGrant(cobble(10), Stock.of(stock), 10);
            stock -= c.quantity();
            assertTrue(stock >= SupplyRequestPolicy.DEFAULT_MATERIAL_RESERVE, "stock=" + stock);
        }
        assertEquals(16, stock);
        Consume dry = ledger.consumeGrant(cobble(10), Stock.of(stock), 10);
        assertEquals(ConsumeStatus.INELIGIBLE, dry.status());
        assertEquals(Verdict.RESERVE_EXHAUSTED, dry.verdict());
        assertEquals(Verdict.RESERVE_EXHAUSTED, ledger.open(cobble(10), Stock.of(stock), 10).verdict());

        // Equipment under always: the last axe of its type stays.
        RequestFingerprint axe = fp(OWNER, BOT, CHEST, STONE_AXE, 1);
        assertEquals(ConsumeStatus.ALWAYS, ledger.consumeGrant(axe, new Stock(1, 2), 1).status());
        assertEquals(Verdict.RESERVE_EXHAUSTED, ledger.consumeGrant(axe, Stock.of(1), 1).verdict());
    }

    @Test
    void onceGrantRechecksTheReserveAndSurvivesARefusal() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        Consume refused = ledger.consumeGrant(cobble(8), Stock.of(16), 8);
        assertEquals(ConsumeStatus.INELIGIBLE, refused.status());
        assertEquals(Verdict.RESERVE_EXHAUSTED, refused.verdict());
        // The owner restocked: the still-live grant now works.
        assertEquals(8, ledger.consumeGrant(cobble(8), PLENTY, 8).quantity());
    }

    @Test
    void alwaysIsRevocable() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON);
        assertTrue(ledger.revokeAlways(OWNER, CHEST));
        assertFalse(ledger.hasAlways(OWNER, CHEST));
        assertFalse(ledger.revokeAlways(OWNER, CHEST));
        // Revoking also withdrew the unspent grant from that answer.
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
        advance(15_000L);
        assertEquals(OpenStatus.OPENED, ledger.open(fp(OWNER, BOT, CHEST, TORCH, 4), PLENTY, 4).status());
    }

    @Test
    void revokeAllWithdrawsEveryPermissionAndGrantOfThatOwnerOnly() {
        ChestKey netherChest = new ChestKey(NETHER, 10, 64, 10);
        ChestKey thirdChest = new ChestKey(WORLD, 30, 64, 30);
        UUID mine = openOk(cobble(8));
        ledger.respond(mine, OWNER, false, Choice.ALWAYS_COMMON);
        ledger.restoreAlways(OWNER, netherChest);
        UUID theirs = openOk(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, COBBLE, 8));
        ledger.respond(theirs, OTHER_OWNER, false, Choice.ALWAYS_COMMON);
        UUID waiting = openOk(fp(OWNER, BOT_2, thirdChest, COBBLE, 8));

        // Two standing permissions, and the one live grant the owner's "always" answer left.
        assertEquals(new Revoked(2, 1), ledger.revokeAllAlways(OWNER));
        assertFalse(ledger.hasAlways(OWNER, CHEST));
        assertFalse(ledger.hasAlways(OWNER, netherChest));
        assertEquals(Set.of(new AlwaysScope(OTHER_OWNER, CHEST)), ledger.alwaysSnapshot());
        // The unspent grant from the owner's "always" answer is gone too; the other owner's stays.
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
        assertEquals(ConsumeStatus.ONCE,
                ledger.consumeGrant(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, COBBLE, 8), PLENTY, 8).status());
        // Not clearOwner: the waiting prompt and the bots' cooldowns are untouched.
        assertTrue(ledger.hasPending(BOT_2));
        assertEquals(OpenStatus.PROMPT_COOLDOWN, ledger.open(fp(OWNER, BOT, thirdChest, TORCH, 4), PLENTY, 4).status());
        assertEquals(ResponseStatus.GRANTED_ONCE, ledger.respond(waiting, OWNER, false, Choice.ALLOW_ONCE).status());

        // The Allow once just given is withdrawn too, and counted; then nothing is left.
        assertEquals(new Revoked(0, 1), ledger.revokeAllAlways(OWNER));
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT_2, thirdChest, COBBLE, 8), PLENTY, 8)
                .status());
        assertTrue(ledger.revokeAllAlways(OWNER).nothing());
        assertEquals(Revoked.NOTHING, ledger.revokeAllAlways(null));
    }

    @Test
    void revokeAllWithNoPermissionStillWithdrawsAndCountsUnspentGrants() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        Revoked revoked = ledger.revokeAllAlways(OWNER);
        assertEquals(new Revoked(0, 1), revoked);
        assertFalse(revoked.nothing(), "so the reply never says there was nothing to revoke");
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
    }

    @Test
    void revokeAllDropsButDoesNotCountAGrantThatAlreadyLapsed() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        advance(60_000L); // now == the grant's deadline: it permits nothing any more
        assertEquals(Revoked.NOTHING, ledger.revokeAllAlways(OWNER));
    }

    // ── the latest answer wins ───────────────────────────────────────────────────────────────

    @Test
    void aNoWithdrawsAnEarlierAllowOnceForTheSameTarget() {
        UUID first = openOk(cobble(8));
        assertEquals(ResponseStatus.GRANTED_ONCE, ledger.respond(first, OWNER, false, Choice.ALLOW_ONCE).status());
        assertTrue(ledger.isPermitted(cobble(8)));
        // The grant goes unspent; after the cooldown the bot asks for the same thing again.
        advance(15_000L);
        UUID again = openOk(cobble(8));
        assertEquals(ResponseStatus.REJECTED, ledger.respond(again, OWNER, false, Choice.NO).status());
        assertFalse(ledger.isPermitted(cobble(8)), "the owner's latest answer was No");
        Consume consume = ledger.consumeGrant(cobble(8), PLENTY, 8);
        assertEquals(ConsumeStatus.NO_GRANT, consume.status());
        assertEquals(0, consume.quantity(), "nothing moves");
    }

    @Test
    void aNoLeavesOtherTargetsGrantsAlone() {
        UUID axe = openOk(fp(OWNER, BOT, CHEST, STONE_AXE, 1));
        ledger.respond(axe, OWNER, false, Choice.ALLOW_ONCE);
        UUID elsewhere = openOk(fp(OWNER, BOT_2, OTHER_CHEST, COBBLE, 8));
        ledger.respond(elsewhere, OWNER, false, Choice.ALLOW_ONCE);
        advance(15_000L);
        UUID cobbleHere = openOk(cobble(8));
        ledger.respond(cobbleHere, OWNER, false, Choice.NO);
        assertTrue(ledger.isPermitted(fp(OWNER, BOT, CHEST, STONE_AXE, 1)), "another item at the same chest");
        assertTrue(ledger.isPermitted(fp(OWNER, BOT_2, OTHER_CHEST, COBBLE, 8)), "another bot and chest");
    }

    // ── isPermitted ──────────────────────────────────────────────────────────────────────────

    @Test
    void aLiveOnceGrantPermitsUpToItsQuantityUntilItsDeadline() {
        UUID id = openOk(cobble(8));
        assertFalse(ledger.isPermitted(cobble(8)), "an unanswered prompt permits nothing");
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        assertTrue(ledger.isPermitted(cobble(8)));
        assertTrue(ledger.isPermitted(cobble(1)));
        assertFalse(ledger.isPermitted(cobble(9)), "more than granted");
        advance(60_000L - 1);
        assertTrue(ledger.isPermitted(cobble(8)));
        advance(1L);
        assertFalse(ledger.isPermitted(cobble(8)), "now == deadline is expired");
    }

    @Test
    void isPermittedNeverSpendsOrDropsAGrant() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        for (int i = 0; i < 3; i++) {
            assertTrue(ledger.isPermitted(cobble(8)));
        }
        assertEquals(ConsumeStatus.ONCE, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
        assertFalse(ledger.isPermitted(cobble(8)), "the grant is spent");
    }

    @Test
    void aRefusedOrExpiredPromptPermitsNothing() {
        UUID refused = openOk(cobble(8));
        ledger.respond(refused, OWNER, false, Choice.NO);
        assertFalse(ledger.isPermitted(cobble(8)));

        UUID ignored = openOk(fp(OWNER, BOT_2, CHEST, COBBLE, 8));
        advance(30_000L);
        assertEquals(ResponseStatus.EXPIRED, ledger.respond(ignored, OWNER, false, Choice.ALLOW_ONCE).status());
        assertFalse(ledger.isPermitted(fp(OWNER, BOT_2, CHEST, COBBLE, 8)));
    }

    @Test
    void aGrantPermitsOnlyItsOwnOwnerBotChestAndItem() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        assertFalse(ledger.isPermitted(fp(OTHER_OWNER, BOT, CHEST, COBBLE, 8)), "a foreign owner");
        assertFalse(ledger.isPermitted(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, COBBLE, 8)), "a foreign owner's bot");
        assertFalse(ledger.isPermitted(fp(OWNER, BOT_2, CHEST, COBBLE, 8)), "another bot");
        assertFalse(ledger.isPermitted(fp(OWNER, BOT, OTHER_CHEST, COBBLE, 8)), "another chest");
        assertFalse(ledger.isPermitted(fp(OWNER, BOT, CHEST, TORCH, 8)), "another item");
        assertFalse(ledger.isPermitted(fp(OWNER, BOT, CHEST,
                new ItemKey("minecraft:cobblestone", "minecraft:damage=1", Set.of("minecraft:damage")), 8)),
                "the same id with other components");
        assertFalse(ledger.isPermitted(null));
    }

    @Test
    void aStandingPermissionPermitsAnyQuantityForThatOwnerAndChestOnly() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON);
        advance(60_000L);
        // The once-grant that came with the answer has lapsed; the standing permission has not.
        assertTrue(ledger.isPermitted(cobble(8)));
        assertTrue(ledger.isPermitted(cobble(40)));
        assertTrue(ledger.isPermitted(fp(OWNER, BOT_2, CHEST, TORCH, 4)), "any of that owner's bots and items");
        assertFalse(ledger.isPermitted(fp(OWNER, BOT, OTHER_CHEST, COBBLE, 8)), "another chest");
        assertFalse(ledger.isPermitted(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, COBBLE, 8)), "a foreign owner");
        assertFalse(ledger.isPermitted(fp(null, BOT, CHEST, COBBLE, 8)), "an un-owned bot");
        assertTrue(ledger.revokeAlways(OWNER, CHEST));
        assertFalse(ledger.isPermitted(cobble(8)));
    }

    // ── always: persistence seams ────────────────────────────────────────────────────────────

    @Test
    void aRestoredPermissionCoversRequestsWithoutAPromptOrAGrant() {
        ledger.restoreAlways(OWNER, CHEST);
        assertTrue(ledger.hasAlways(OWNER, CHEST));

        OpenResult covered = ledger.open(cobble(8), PLENTY, 8);
        assertEquals(OpenStatus.COVERED_BY_ALWAYS, covered.status());
        assertNull(covered.requestId());
        assertEquals(8, covered.fingerprint().qty());
        assertFalse(ledger.hasPending(BOT));

        // No grant was seeded: every consume is ALWAYS, never ONCE, and nothing is spent.
        for (int i = 0; i < 3; i++) {
            Consume c = ledger.consumeGrant(cobble(8), PLENTY, 8);
            assertEquals(ConsumeStatus.ALWAYS, c.status());
            assertEquals(8, c.quantity());
        }
        // Any of that owner's bots; reserves and eligibility still hold.
        assertEquals(ConsumeStatus.ALWAYS, ledger.consumeGrant(fp(OWNER, BOT_2, CHEST, TORCH, 4), PLENTY, 4).status());
        assertEquals(4, ledger.consumeGrant(cobble(8), Stock.of(20), 8).quantity());
        assertEquals(Verdict.RESERVE_EXHAUSTED, ledger.consumeGrant(cobble(8), Stock.of(16), 8).verdict());
        assertEquals(Verdict.NOT_ALLOWLISTED,
                ledger.open(fp(OWNER, BOT, CHEST, ItemKey.plain("minecraft:diamond"), 1), PLENTY, 1).verdict());
        // Scoped like an answered permission: other owners and chests still prompt.
        assertEquals(OpenStatus.OPENED,
                ledger.open(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, COBBLE, 8), PLENTY, 8).status());
        assertEquals(OpenStatus.OPENED, ledger.open(fp(OWNER, BOT, OTHER_CHEST, COBBLE, 8), PLENTY, 8).status());
    }

    @Test
    void restoreRecordsExactlyWhatAnAlwaysAnswerRecords() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON);

        SupplyRequestLedger restored = newLedger(Config.defaults());
        restored.restoreAlways(OWNER, CHEST);
        assertEquals(ledger.alwaysSnapshot(), restored.alwaysSnapshot());
        assertEquals(Set.of(new AlwaysScope(OWNER, CHEST)), restored.alwaysSnapshot());
        // Restoring twice is idempotent, and a restored permission revokes like an answered one.
        restored.restoreAlways(OWNER, CHEST);
        assertEquals(1, restored.alwaysSnapshot().size());
        assertTrue(restored.revokeAlways(OWNER, CHEST));
        assertEquals(Set.of(), restored.alwaysSnapshot());
        assertEquals(OpenStatus.OPENED, restored.open(cobble(8), PLENTY, 8).status());
    }

    @Test
    void restoreLeavesPromptsAndCooldownsAlone() {
        UUID id = openOk(fp(OWNER, BOT, OTHER_CHEST, COBBLE, 8));
        ledger.restoreAlways(OWNER, CHEST);
        assertTrue(ledger.hasPending(BOT));
        assertEquals(ResponseStatus.GRANTED_ONCE, ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE).status());
        // BOT is in its prompt cooldown, and the restored permission did not reset it.
        assertEquals(OpenStatus.PROMPT_COOLDOWN, ledger.open(fp(OWNER, BOT, OTHER_CHEST, TORCH, 4), PLENTY, 4).status());
    }

    @Test
    void restoreIgnoresNullArguments() {
        ledger.restoreAlways(null, CHEST);
        ledger.restoreAlways(OWNER, null);
        assertEquals(Set.of(), ledger.alwaysSnapshot());
        assertThrows(NullPointerException.class, () -> new AlwaysScope(null, CHEST));
        assertThrows(NullPointerException.class, () -> new AlwaysScope(OWNER, null));
    }

    @Test
    void snapshotFollowsAlwaysAnswersAndRevocationsOnly() {
        assertEquals(Set.of(), ledger.alwaysSnapshot());
        UUID once = openOk(cobble(8));
        ledger.respond(once, OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(Set.of(), ledger.alwaysSnapshot());

        advance(15_000L);
        UUID always = openOk(cobble(8));
        ledger.respond(always, OWNER, false, Choice.ALWAYS_COMMON);
        UUID others = openOk(fp(OTHER_OWNER, OTHER_OWNERS_BOT, OTHER_CHEST, COBBLE, 8));
        ledger.respond(others, OTHER_OWNER, false, Choice.ALWAYS_COMMON);
        Set<AlwaysScope> both = ledger.alwaysSnapshot();
        assertEquals(Set.of(new AlwaysScope(OWNER, CHEST), new AlwaysScope(OTHER_OWNER, OTHER_CHEST)), both);

        assertTrue(ledger.revokeAlways(OWNER, CHEST));
        assertEquals(Set.of(new AlwaysScope(OTHER_OWNER, OTHER_CHEST)), ledger.alwaysSnapshot());
        // The earlier snapshot is a copy: immutable and unaffected by the revocation.
        assertEquals(2, both.size());
        assertThrows(UnsupportedOperationException.class, () -> both.add(new AlwaysScope(OWNER, OTHER_CHEST)));
        assertThrows(UnsupportedOperationException.class, both::clear);
    }

    // ── clearing ─────────────────────────────────────────────────────────────────────────────

    @Test
    void clearBotForgetsPromptsGrantsAndCooldowns() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.NO);
        UUID id2 = openOk(fp(OWNER, BOT_2, CHEST, COBBLE, 8));
        ledger.respond(id2, OWNER, false, Choice.ALLOW_ONCE);

        ledger.clearBot(BOT);
        assertEquals(OpenStatus.OPENED, ledger.open(cobble(8), PLENTY, 8).status());
        ledger.clearBot(BOT);
        assertFalse(ledger.hasPending(BOT));

        ledger.clearBot(BOT_2);
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT_2, CHEST, COBBLE, 8), PLENTY, 8).status());
        ledger.clearBot(null);
    }

    @Test
    void clearOwnerForgetsThatOwnersPromptsGrantsAndPermissions() {
        UUID id = openOk(cobble(8));
        ledger.respond(id, OWNER, false, Choice.ALWAYS_COMMON);
        UUID pending = openOk(fp(OWNER, BOT_2, OTHER_CHEST, COBBLE, 8));
        UUID others = openOk(fp(OTHER_OWNER, OTHER_OWNERS_BOT, CHEST, COBBLE, 8));

        ledger.clearOwner(OWNER);
        assertFalse(ledger.hasAlways(OWNER, CHEST));
        assertFalse(ledger.hasPending(BOT_2));
        assertEquals(ResponseStatus.NOT_FOUND, ledger.respond(pending, OWNER, false, Choice.ALLOW_ONCE).status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(cobble(8), PLENTY, 8).status());
        // Another owner's prompt is untouched.
        assertEquals(ResponseStatus.GRANTED_ONCE,
                ledger.respond(others, OTHER_OWNER, false, Choice.ALLOW_ONCE).status());
    }

    @Test
    void sweepDropsExpiredPromptsAndGrants() {
        openOk(cobble(8));
        UUID granted = openOk(fp(OWNER, BOT_2, CHEST, COBBLE, 8));
        ledger.respond(granted, OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(0, ledger.sweep());

        advance(30_000L);
        assertEquals(1, ledger.sweep());
        assertEquals(0, ledger.sweep());
        assertFalse(ledger.hasPending(BOT));

        advance(30_000L);
        ledger.sweep();
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(OWNER, BOT_2, CHEST, COBBLE, 8), PLENTY, 8).status());
        // The expired prompt still spaced the bot's next prompt; by now that has lapsed too.
        assertEquals(OpenStatus.OPENED, ledger.open(cobble(8), PLENTY, 8).status());
    }

    @Test
    void timingsDefaultsAndClamping() {
        Timings t = Timings.defaults();
        assertEquals(30_000L, t.requestLifetimeMs());
        assertEquals(60_000L, t.grantLifetimeMs());
        assertEquals(15_000L, t.promptCooldownMs());
        assertEquals(300_000L, t.rejectCooldownMs());
        assertEquals(0L, new Timings(-1, -1, -1, -1).requestLifetimeMs());
    }
}
