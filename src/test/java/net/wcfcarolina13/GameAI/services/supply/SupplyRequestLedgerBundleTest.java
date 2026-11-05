package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.BundleLine;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.BundleOpenResult;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ConsumeStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.LineStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.OpenResult;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.OpenStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Response;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ResponseStatus;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-combined-ask bundle path of {@link SupplyRequestLedger#openBundle}. */
class SupplyRequestLedgerBundleTest {

    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID STRANGER = new UUID(1, 3);
    private static final UUID BOT = new UUID(2, 1);
    private static final ChestKey CHEST = new ChestKey("New World/minecraft:overworld", 10, 64, 10);
    private static final ItemKey PLANKS = ItemKey.plain("minecraft:oak_planks");
    private static final ItemKey STICK = ItemKey.plain("minecraft:stick");
    private static final ItemKey DIAMOND = ItemKey.plain("minecraft:diamond");
    private static final Stock PLENTY = Stock.of(64);

    private final long[] now = {1_000_000L};
    private long nextId;
    private SupplyRequestLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new SupplyRequestLedger(() -> now[0], () -> new UUID(9, ++nextId), Timings.defaults(),
                Config.defaults());
    }

    private static BundleLine line(ItemKey item, int qty) {
        return new BundleLine(item, qty, PLENTY, qty);
    }

    private BundleOpenResult openPickaxeSet() {
        return ledger.openBundle(OWNER, BOT, CHEST, List.of(line(PLANKS, 3), line(STICK, 2)));
    }

    private static RequestFingerprint fp(ItemKey item, int qty) {
        return new RequestFingerprint(OWNER, BOT, CHEST, item, qty);
    }

    @Test
    void oneApprovalCoversEveryLine() {
        BundleOpenResult opened = openPickaxeSet();
        assertEquals(OpenStatus.OPENED, opened.status());
        assertNotNull(opened.requestId());
        assertEquals(List.of(fp(PLANKS, 3), fp(STICK, 2)), opened.asked());

        Response r = ledger.respond(opened.requestId(), OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(ResponseStatus.GRANTED_ONCE, r.status());
        assertEquals(List.of(fp(PLANKS, 3), fp(STICK, 2)), r.lines());
        assertEquals(fp(PLANKS, 3), r.fingerprint());

        assertEquals(ConsumeStatus.ONCE, ledger.consumeGrant(fp(PLANKS, 3), PLENTY, 3).status());
        assertEquals(ConsumeStatus.ONCE, ledger.consumeGrant(fp(STICK, 2), PLENTY, 2).status());
        // Spent: each line's grant is single-use.
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(STICK, 2), PLENTY, 2).status());
    }

    @Test
    void oneNoRefusesTheWholeSetAndCoolsEachItem() {
        BundleOpenResult opened = openPickaxeSet();
        Response r = ledger.respond(opened.requestId(), OWNER, false, Choice.NO);
        assertEquals(ResponseStatus.REJECTED, r.status());
        assertEquals(ConsumeStatus.NO_GRANT, ledger.consumeGrant(fp(PLANKS, 3), PLENTY, 3).status());

        now[0] += Timings.defaults().promptCooldownMs();
        BundleOpenResult again = openPickaxeSet();
        assertEquals(OpenStatus.REJECT_COOLDOWN, again.status());
        assertNull(again.requestId());
        assertTrue(again.lines().stream().allMatch(l -> l.status() == LineStatus.REJECT_COOLDOWN));
        // A single-item ask for a refused item is cooled too.
        assertEquals(OpenStatus.REJECT_COOLDOWN, ledger.open(fp(STICK, 2), PLENTY, 2).status());
    }

    @Test
    void alwaysAnswerCoversTheSetAndLaterBundles() {
        BundleOpenResult opened = openPickaxeSet();
        assertEquals(ResponseStatus.GRANTED_ALWAYS,
                ledger.respond(opened.requestId(), OWNER, false, Choice.ALWAYS_COMMON).status());
        assertTrue(ledger.hasAlways(OWNER, CHEST));
        BundleOpenResult later = ledger.openBundle(OWNER, BOT, CHEST, List.of(line(PLANKS, 5)));
        assertEquals(OpenStatus.COVERED_BY_ALWAYS, later.status());
        assertEquals(List.of(fp(PLANKS, 5)), later.asked());
    }

    @Test
    void foreignClickChangesNothing() {
        BundleOpenResult opened = openPickaxeSet();
        assertEquals(ResponseStatus.FOREIGN_CALLER,
                ledger.respond(opened.requestId(), STRANGER, false, Choice.ALLOW_ONCE).status());
        assertTrue(ledger.hasPending(BOT));
        assertFalse(ledger.isPermitted(fp(PLANKS, 3)));
    }

    @Test
    void ineligibleLinesAreLeftOutAndSayWhy() {
        BundleOpenResult opened = ledger.openBundle(OWNER, BOT, CHEST,
                List.of(line(DIAMOND, 1), line(STICK, 2)));
        assertEquals(OpenStatus.OPENED, opened.status());
        assertEquals(LineStatus.INELIGIBLE, opened.lines().get(0).status());
        assertEquals(Verdict.NOT_ALLOWLISTED, opened.lines().get(0).verdict());
        assertEquals(List.of(fp(STICK, 2)), opened.asked());
        Response r = ledger.respond(opened.requestId(), OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(List.of(fp(STICK, 2)), r.lines());
    }

    @Test
    void allIneligibleOpensNothing() {
        BundleOpenResult opened = ledger.openBundle(OWNER, BOT, CHEST, List.of(line(DIAMOND, 1)));
        assertEquals(OpenStatus.INELIGIBLE, opened.status());
        assertFalse(ledger.hasPending(BOT));
    }

    @Test
    void quantityIsCutToTheReservePerLine() {
        // 20 planks, reserve 16: at most 4.
        BundleOpenResult opened = ledger.openBundle(OWNER, BOT, CHEST,
                List.of(new BundleLine(PLANKS, 10, Stock.of(20), 10), line(STICK, 2)));
        assertEquals(List.of(fp(PLANKS, 4), fp(STICK, 2)), opened.asked());
    }

    @Test
    void liveGrantCoversItsLineAndOnlyTheRestIsAsked() {
        UUID first = ledger.open(fp(PLANKS, 3), PLENTY, 3).requestId();
        ledger.respond(first, OWNER, false, Choice.ALLOW_ONCE);
        now[0] += Timings.defaults().promptCooldownMs();

        BundleOpenResult opened = openPickaxeSet();
        assertEquals(OpenStatus.OPENED, opened.status());
        assertEquals(List.of(fp(PLANKS, 3)), opened.approved(LineStatus.COVERED_BY_GRANT));
        assertEquals(List.of(fp(STICK, 2)), opened.asked());
    }

    @Test
    void everyLineCoveredByGrantNeedsNoPrompt() {
        BundleOpenResult opened = openPickaxeSet();
        ledger.respond(opened.requestId(), OWNER, false, Choice.ALLOW_ONCE);
        BundleOpenResult again = openPickaxeSet();
        assertEquals(OpenStatus.COVERED_BY_GRANT, again.status());
        assertNull(again.requestId());
    }

    @Test
    void stillOnePromptPerBot() {
        openPickaxeSet();
        BundleOpenResult second = ledger.openBundle(OWNER, BOT, CHEST, List.of(line(PLANKS, 1)));
        assertEquals(OpenStatus.DUPLICATE_PENDING, second.status());
        assertEquals(OpenStatus.DUPLICATE_PENDING, ledger.open(fp(STICK, 1), PLENTY, 1).status());
    }

    @Test
    void promptCooldownAppliesAfterAnAnswer() {
        BundleOpenResult opened = openPickaxeSet();
        ledger.respond(opened.requestId(), OWNER, false, Choice.ALLOW_ONCE);
        BundleOpenResult other = ledger.openBundle(OWNER, BOT, CHEST,
                List.of(line(ItemKey.plain("minecraft:coal"), 1)));
        assertEquals(OpenStatus.PROMPT_COOLDOWN, other.status());
    }

    @Test
    void expiredBundleGrantsNothing() {
        BundleOpenResult opened = openPickaxeSet();
        now[0] += Timings.defaults().requestLifetimeMs();
        Response r = ledger.respond(opened.requestId(), OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(ResponseStatus.EXPIRED, r.status());
        assertEquals(2, r.lines().size());
        assertFalse(ledger.isPermitted(fp(PLANKS, 3)));
        assertFalse(ledger.isPermitted(fp(STICK, 2)));
    }

    @Test
    void oneLineBundleDecidesExactlyAsOpen() {
        BundleOpenResult bundle = ledger.openBundle(OWNER, BOT, CHEST, List.of(line(STICK, 2)));
        SupplyRequestLedger other = new SupplyRequestLedger(() -> now[0], () -> new UUID(8, 1),
                Timings.defaults(), Config.defaults());
        OpenResult single = other.open(fp(STICK, 2), PLENTY, 2);
        assertEquals(single.status(), bundle.status());
        assertEquals(List.of(single.fingerprint()), bundle.asked());
    }

    @Test
    void singleItemResponseStillCarriesItsOneLine() {
        UUID id = ledger.open(fp(STICK, 2), PLENTY, 2).requestId();
        Response r = ledger.respond(id, OWNER, false, Choice.ALLOW_ONCE);
        assertEquals(List.of(fp(STICK, 2)), r.lines());
        assertEquals(List.of(), new Response(ResponseStatus.NOT_FOUND, null).lines());
    }

    @Test
    void clearOwnerDropsAPendingBundle() {
        openPickaxeSet();
        ledger.clearOwner(OWNER);
        assertFalse(ledger.hasPending(BOT));
    }

    @Test
    void emptyOrDuplicateLinesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ledger.openBundle(OWNER, BOT, CHEST, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.openBundle(OWNER, BOT, CHEST, List.of(line(STICK, 1), line(STICK, 2))));
        assertFalse(ledger.hasPending(BOT));
    }

    @Test
    void unownedBotGetsNoPrompt() {
        BundleOpenResult opened = ledger.openBundle(null, BOT, CHEST, List.of(line(STICK, 2)));
        assertEquals(OpenStatus.INELIGIBLE, opened.status());
        assertEquals(Verdict.NO_OWNER, opened.lines().get(0).verdict());
    }
}
