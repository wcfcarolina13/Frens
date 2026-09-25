package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;

/**
 * Pure decisions behind {@link SupplyWithdrawals}: whether an item is worth asking about at all
 * and roughly how many a chest could grant, what to do with a bot's ticket, what a request's or a
 * transfer's status means for the caller and the ticket, how long a ticket lives, and when a
 * worker stops waiting for the owner.
 *
 * <p>No Minecraft types: the enums it reads are nested in {@link SupplyRequestService} and
 * {@link SupplyWithdrawals} but are plain enums, and loading one does not load its outer class.
 * The facade calls these rather than deciding inline, so every branch it takes is tested here.
 *
 * <p>Deadlines follow the ledger: an instant equal to a deadline counts as passed.
 */
public final class SupplyWithdrawalPolicy {

    /** How far past the prompt's own lifetime an {@link WaitMode#UNTIL_ANSWERED} wait may run. */
    public static final long WAIT_SLACK_MS = 2_000L;

    private SupplyWithdrawalPolicy() {
    }

    // ── Pre-filter ───────────────────────────────────────────────────────────────────────────

    /**
     * Whether an item that {@link SupplyRequestPolicy#classify} judged {@code classified} may be
     * asked about at all. Anything but {@link Verdict#ELIGIBLE} is refused before a request is
     * made, so the owner is never prompted and no request line is logged for it.
     */
    public static boolean passesPreFilter(Verdict classified) {
        return classified == Verdict.ELIGIBLE;
    }

    // ── Availability estimate ────────────────────────────────────────────────────────────────

    /**
     * How many of {@code item} a companion could be granted from {@code countInThisChest} of it:
     * 0 when the item fails the pre-filter under {@code cfg}, otherwise the count less the item's
     * reserve ({@link SupplyRequestPolicy#reserveFor}), never below 0. Advisory, for counting what
     * automatic work may rely on. From the counts alone it never exceeds what a request for this
     * chest could be granted, since a grant counts both halves and, for equipment, the whole type;
     * it can fall short of it. It sees no ledger state, so a cooldown, a refusal or a missing
     * owner can still leave nothing to take.
     */
    public static int grantableEstimate(ItemKey item, SupplyRequestPolicy.Config cfg, int countInThisChest) {
        if (item == null || cfg == null || !passesPreFilter(SupplyRequestPolicy.classify(item, cfg))) {
            return 0;
        }
        return Math.max(0, countInThisChest - SupplyRequestPolicy.reserveFor(item, cfg));
    }

    // ── Wait mode ────────────────────────────────────────────────────────────────────────────

    /**
     * The mode a call actually runs in: {@link WaitMode#UNTIL_ANSWERED} only off the server
     * thread, since waiting there would stall the game; {@code null} is {@link WaitMode#NONE}.
     */
    public static WaitMode effectiveMode(WaitMode requested, boolean onServerThread) {
        if (requested == WaitMode.UNTIL_ANSWERED && !onServerThread) {
            return WaitMode.UNTIL_ANSWERED;
        }
        return WaitMode.NONE;
    }

    // ── Tickets ──────────────────────────────────────────────────────────────────────────────

    /** What one step does with the bot's ticket, before anything is asked, walked to or moved. */
    public enum TicketStep {
        /** No ticket: make a request. */
        REQUEST,
        /** The ticket is for this chest and item and the owner has not answered: wait. */
        WAIT,
        /**
         * The ticket is for this chest and item, nothing is pending, and a grant or standing
         * permission covers it: take now, or report READY so the caller walks there first.
         */
        REDEEM,
        /**
         * The ticket is for this chest and item, nothing is pending, and nothing covers it (the
         * owner said No, the prompt expired, or the grant lapsed): drop it and refuse, so the
         * caller never walks to a chest it may not take from.
         */
        DROP_NOT_PERMITTED,
        /** The ticket is for something else and its prompt is still waiting: refuse, ask nothing. */
        OTHER_PENDING,
        /** The ticket is for something else and nothing is pending: drop it, then make a request. */
        DROP_AND_REQUEST
    }

    /**
     * @param hasTicket the bot holds a live ticket
     * @param matches   that ticket is for this chest and item ({@link #ticketMatches})
     * @param pending   the bot has a prompt waiting for the owner
     * @param permitted a live grant or standing permission covers the ticket's fingerprint;
     *                  read only for a matching ticket with nothing pending
     */
    public static TicketStep ticketStep(boolean hasTicket, boolean matches, boolean pending, boolean permitted) {
        if (!hasTicket) {
            return TicketStep.REQUEST;
        }
        if (matches) {
            if (pending) {
                return TicketStep.WAIT;
            }
            return permitted ? TicketStep.REDEEM : TicketStep.DROP_NOT_PERMITTED;
        }
        return pending ? TicketStep.OTHER_PENDING : TicketStep.DROP_AND_REQUEST;
    }

    /**
     * Whether a ticket's fingerprint is for the chest and item a call names: the same canonical
     * chest key (so either half of a double chest matches) and the same item id and component
     * fingerprint. A chest whose key could not be read ({@code null}) matches nothing.
     */
    public static boolean ticketMatches(RequestFingerprint ticket, ChestKey chestNow, ItemKey itemNow) {
        return ticket != null && chestNow != null && itemNow != null
                && chestNow.equals(ticket.chest())
                && itemNow.itemId().equals(ticket.itemId())
                && itemNow.componentsFp().equals(ticket.componentsFp());
    }

    /**
     * How long a ticket is kept: long enough for the prompt to be answered and the grant that
     * answer made to be spent. Past that, nothing it names can be redeemed.
     */
    public static long ticketLifetimeMs(Timings timings) {
        Timings t = timings == null ? Timings.defaults() : timings;
        return t.requestLifetimeMs() + t.grantLifetimeMs();
    }

    /** Whether a ticket opened at {@code openedAtMs} is still kept at {@code nowMs}. */
    public static boolean isTicketLive(long openedAtMs, long nowMs, Timings timings) {
        return nowMs < openedAtMs + ticketLifetimeMs(timings);
    }

    // ── Request outcome ──────────────────────────────────────────────────────────────────────

    /** What the facade does after a request. */
    public enum RequestAction {
        /** A standing permission covers it: keep a ticket, then take if in reach, else report READY. */
        TAKE,
        /** A prompt went to the owner: keep a ticket and report WAITING. */
        WAIT,
        /** Anything else: report REFUSED with the request's status; no ticket. */
        REFUSE
    }

    public static RequestAction onRequest(RequestStatus status) {
        if (status == null) {
            return RequestAction.REFUSE;
        }
        return switch (status) {
            case COVERED_BY_ALWAYS -> RequestAction.TAKE;
            case OPENED -> RequestAction.WAIT;
            case DUPLICATE_PENDING, PROMPT_COOLDOWN, REJECT_COOLDOWN, INELIGIBLE, OWNER_NOT_NEARBY, DENIED,
                 INVALID, NOT_RUNNING, WRONG_THREAD -> RequestAction.REFUSE;
        };
    }

    // ── Transfer outcome ─────────────────────────────────────────────────────────────────────

    /**
     * What a transfer means for the caller and the ticket.
     *
     * @param kind       what the caller is told
     * @param keepTicket whether the ticket stays for a later call to redeem
     * @param reason     the transfer status's name
     */
    public record TransferAction(Kind kind, boolean keepTicket, String reason) {
    }

    /**
     * Items moved → {@link Kind#MOVED}, ticket dropped (a once-grant is spent; under a standing
     * permission the next call asks afresh). Out of reach → {@link Kind#READY}, ticket kept: walk,
     * then call again. No room → {@link Kind#REFUSED} with the ticket kept, so the caller may make
     * room and call again. Anything else → {@link Kind#REFUSED}, ticket dropped. A move that
     * reports success but moved nothing is a refusal: the grant is spent all the same.
     */
    public static TransferAction onTransfer(TransferStatus status, int moved) {
        if (status == null) {
            return new TransferAction(Kind.REFUSED, false, TransferStatus.INVALID.name());
        }
        return switch (status) {
            case MOVED, MOVED_SHORT -> new TransferAction(moved > 0 ? Kind.MOVED : Kind.REFUSED, false, status.name());
            case OUT_OF_REACH -> new TransferAction(Kind.READY, true, status.name());
            case NO_ROOM -> new TransferAction(Kind.REFUSED, true, status.name());
            case WRONG_THREAD, NOT_RUNNING, INVALID, OWNER_OR_BOT_MISMATCH, DENIED, CHEST_MISMATCH, NO_STOCK,
                 NOT_PERMITTED -> new TransferAction(Kind.REFUSED, false, status.name());
        };
    }

    // ── Waiting for the owner ────────────────────────────────────────────────────────────────

    /** Where an {@link WaitMode#UNTIL_ANSWERED} wait stands after one poll. */
    public enum WaitDecision {
        /** Still pending and within budget: poll again. */
        KEEP_WAITING,
        /** No longer pending — answered, or expired unanswered: take one more step. */
        SETTLED,
        /** Still pending when the budget ran out: stop; the ticket stays and expires on its own. */
        TIMED_OUT,
        /** The caller, the thread, the bot or the server stopped the wait: stop; the ticket stays. */
        ABORTED
    }

    /** The longest a wait runs: the prompt's lifetime plus {@link #WAIT_SLACK_MS}. */
    public static long waitBudgetMs(Timings timings) {
        Timings t = timings == null ? Timings.defaults() : timings;
        return t.requestLifetimeMs() + WAIT_SLACK_MS;
    }

    /**
     * Abort wins over everything; then a settled prompt; then the budget, measured from when the
     * wait began ({@code nowMs} equal to the deadline has timed out).
     */
    public static WaitDecision keepWaiting(boolean aborted, boolean pending, long waitStartedAtMs, long nowMs,
                                           Timings timings) {
        if (aborted) {
            return WaitDecision.ABORTED;
        }
        if (!pending) {
            return WaitDecision.SETTLED;
        }
        if (nowMs >= waitStartedAtMs + waitBudgetMs(timings)) {
            return WaitDecision.TIMED_OUT;
        }
        return WaitDecision.KEEP_WAITING;
    }
}
