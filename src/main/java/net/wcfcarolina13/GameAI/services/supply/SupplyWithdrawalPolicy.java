package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules.Access;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure decisions behind {@link SupplyWithdrawals}: whether an item is worth asking about at all
 * and roughly how many a chest could grant, how far a refusal reaches ({@link Scope}), what to do
 * with a bot's tickets, what a request's or a transfer's status means for the caller and the
 * ticket, how long a ticket lives, when an owner found away is asked again, and when a worker
 * stops waiting for the owner.
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

    /**
     * How long a bot does not ask about a chest again after the owner was found away from it
     * ({@code OWNER_NOT_NEARBY}): long enough that retries cost no request and no INFO line, short
     * enough that an owner walking back is asked soon.
     */
    public static final long OWNER_AWAY_MEMO_MS = 15_000L;

    private SupplyWithdrawalPolicy() {
    }

    // ── Refusal scope ────────────────────────────────────────────────────────────────────────

    /**
     * How far a refusal reaches, so a caller can decide what to try next without reading the
     * reason string (which stays for the logs).
     */
    public enum Scope {
        /** Not a refusal: {@link Kind#MOVED}, {@link Kind#READY} and {@link Kind#WAITING} carry this. */
        NONE,
        /** This item is never granted, from any chest: skip the item. */
        ITEM,
        /** This chest only (denied, too little above its reserve, gone, changed): try another chest. */
        CHEST,
        /**
         * Everything this bot might ask for now: another prompt pending, a cooldown, the owner's No
         * or an ignored prompt, no owner at all. Stop the pass.
         */
        BOT,
        /** The owner is away and nothing standing covers the chest: recheck later; not the owner's answer. */
        OWNER_ABSENT,
        /** Nothing about the owner, the item or the chest: the hop, the bot's room or reach, the server. Retry later. */
        TRANSIENT
    }

    /**
     * The facade's own refusals, the ones neither the ledger nor the adapter produced. The reason
     * a caller reads is {@link #name()}.
     */
    public enum Refusal {
        /** The service is not running, or the server is stopping. */
        NOT_RUNNING(Scope.TRANSIENT),
        /** The bot is gone (removed, or never given). */
        BOT_GONE(Scope.TRANSIENT),
        /** A required argument was missing or not positive. */
        INVALID(Scope.TRANSIENT),
        /** The bot has no recorded owner, so nobody may approve anything for it. */
        NO_OWNER(Scope.BOT),
        /**
         * The chest's key could not be read (unloaded, not a chest, or a half whose partner cannot
         * be resolved without loading a chunk). Nothing is matched or dropped.
         */
        CHEST_UNREADABLE(Scope.TRANSIENT),
        /** The owner was found away from this chest moments ago; asked again after {@link #OWNER_AWAY_MEMO_MS}. */
        OWNER_NOT_NEARBY(Scope.OWNER_ABSENT),
        /** Another of this bot's prompts is waiting for the owner. */
        OTHER_REQUEST_PENDING(Scope.BOT),
        /** This chest and item's ticket is no longer covered: the owner said No, the prompt expired, or the grant lapsed. */
        NOT_PERMITTED(Scope.BOT),
        /** An {@link WaitMode#UNTIL_ANSWERED} wait ran out while the prompt was still open. */
        TIMEOUT(Scope.TRANSIENT),
        /** The caller, the thread, the bot or the server stopped the call. */
        ABORTED(Scope.TRANSIENT),
        /** The server thread did not run the step in time. */
        SERVER_BUSY(Scope.TRANSIENT),
        /** The step threw on the server thread. */
        ERROR(Scope.TRANSIENT);

        private final Scope scope;

        Refusal(Scope scope) {
            this.scope = scope;
        }

        public Scope scope() {
            return scope;
        }
    }

    /**
     * The scope of a refused request: a cooldown or a pending prompt stops the bot; an ineligible
     * verdict reaches the item, the chest or the bot ({@link #verdictScope}); a denied chest is
     * that chest's whatever the access; an owner away is {@link Scope#OWNER_ABSENT}; the rest is
     * transient. {@code OPENED} and {@code COVERED_BY_ALWAYS} are refusals only when the ledger gave
     * no fingerprint, which cannot happen: they fail closed, like a {@code null} verdict.
     *
     * @param access  the outcome's access verdict; only a {@code DENIED} carries one, and any value counts
     * @param verdict the outcome's policy verdict; read for {@code INELIGIBLE} only
     */
    public static Scope requestScope(RequestStatus status, Access access, Verdict verdict) {
        if (status == null) {
            return Scope.TRANSIENT;
        }
        return switch (status) {
            case DUPLICATE_PENDING, PROMPT_COOLDOWN, REJECT_COOLDOWN, OPENED, COVERED_BY_ALWAYS -> Scope.BOT;
            case INELIGIBLE -> verdictScope(verdict);
            case OWNER_NOT_NEARBY -> Scope.OWNER_ABSENT;
            case DENIED -> Scope.CHEST;
            case INVALID, NOT_RUNNING, WRONG_THREAD -> Scope.TRANSIENT;
        };
    }

    /**
     * The scope of an ineligible verdict: the item for anything no chest will ever grant it (and a
     * need of nothing), the chest for its reserve, the bot for a missing owner. {@code ELIGIBLE} or
     * {@code null} as a refusal is nonsense and fails closed ({@link Scope#BOT}).
     */
    public static Scope verdictScope(Verdict verdict) {
        if (verdict == null) {
            return Scope.BOT;
        }
        return switch (verdict) {
            case NOT_ALLOWLISTED, PROTECTED_COMPONENTS, TIER_NOT_ALLOWED, NO_NEED -> Scope.ITEM;
            case RESERVE_EXHAUSTED -> Scope.CHEST;
            case NO_OWNER, ELIGIBLE -> Scope.BOT;
        };
    }

    /**
     * The scope of a transfer's status when it is a refusal: the chest for a denied, changed or
     * emptied chest and for a policy refusal at transfer time; the bot for no permission or another
     * owner; transient otherwise. {@code MOVED}/{@code MOVED_SHORT} are refusals only when nothing
     * moved, and {@code OUT_OF_REACH} never is (it reports {@link Kind#READY}).
     */
    public static Scope transferScope(TransferStatus status) {
        if (status == null) {
            return Scope.TRANSIENT;
        }
        return switch (status) {
            case DENIED, CHEST_MISMATCH, NO_STOCK, INELIGIBLE_NOW -> Scope.CHEST;
            case NOT_PERMITTED, OWNER_OR_BOT_MISMATCH -> Scope.BOT;
            case MOVED, MOVED_SHORT, OUT_OF_REACH, NO_ROOM, WRONG_THREAD, NOT_RUNNING, INVALID -> Scope.TRANSIENT;
        };
    }

    /**
     * The scope a result of {@code kind} carries: {@link Scope#NONE} unless it is a refusal; a
     * refusal without a scope fails closed ({@link Scope#BOT}), so a refusal never reads as NONE.
     */
    public static Scope resultScope(Kind kind, Scope scope) {
        if (kind != Kind.REFUSED) {
            return Scope.NONE;
        }
        return scope == null || scope == Scope.NONE ? Scope.BOT : scope;
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

    /** {@link Scope#NONE} when the item passes the pre-filter, else {@link Scope#ITEM}: no chest will grant it. */
    public static Scope preFilterScope(Verdict classified) {
        return passesPreFilter(classified) ? Scope.NONE : Scope.ITEM;
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

    /**
     * What a ticket is for: one chest (its canonical key, so either half of a double chest names
     * it) and one exact item (id and component fingerprint). The quantity is not part of it.
     */
    public record TicketKey(ChestKey chest, String itemId, String componentsFp) {
        /** The key a call names, or {@code null} when the chest's key could not be read. */
        public static TicketKey of(ChestKey chest, ItemKey item) {
            return chest == null || item == null ? null : new TicketKey(chest, item.itemId(), item.componentsFp());
        }

        /** The key a request's fingerprint names. */
        public static TicketKey of(RequestFingerprint fp) {
            return fp == null ? null : new TicketKey(fp.chest(), fp.itemId(), fp.componentsFp());
        }
    }

    /**
     * A request a bot asked, or was covered for, kept between the ask and the take: its
     * fingerprint is the only key the owner's grant can be spent with.
     */
    public record Ticket(RequestFingerprint fp, long openedAtMs) {
        public TicketKey key() {
            return TicketKey.of(fp);
        }
    }

    /**
     * Every bot's tickets, at most one per bot, chest and exact item. A bot may hold several:
     * asking about one chest and item never touches its ticket for another, so an owner's
     * "Allow once" is spent by the call that names that chest and item, whatever the bot asked
     * about in between. Thread-safe.
     */
    public static final class TicketBook {
        private record Id(UUID bot, TicketKey key) {
        }

        private final ConcurrentHashMap<Id, Ticket> tickets = new ConcurrentHashMap<>();

        /**
         * {@code bot}'s live ticket for {@code key}, or {@code null}. A ticket for that key that
         * has outlived {@link #ticketLifetimeMs} is dropped on the way; no other ticket is touched.
         */
        public Ticket find(UUID bot, TicketKey key, long nowMs, Timings timings) {
            if (bot == null || key == null) {
                return null;
            }
            Id id = new Id(bot, key);
            Ticket ticket = tickets.get(id);
            if (ticket != null && !isTicketLive(ticket.openedAtMs(), nowMs, timings)) {
                tickets.remove(id, ticket);
                return null;
            }
            return ticket;
        }

        /** Keeps a ticket for {@code fp}'s bot, chest and item, replacing only a ticket for that same key. */
        public Ticket record(RequestFingerprint fp, long nowMs) {
            Ticket ticket = new Ticket(fp, nowMs);
            tickets.put(new Id(fp.bot(), ticket.key()), ticket);
            return ticket;
        }

        /** Drops exactly this ticket: never another key's, and not a newer ticket recorded for the same key. */
        public void drop(Ticket ticket) {
            if (ticket != null) {
                tickets.remove(new Id(ticket.fp().bot(), ticket.key()), ticket);
            }
        }

        /** Drops every ticket too old to redeem anything. */
        public void sweep(long nowMs, Timings timings) {
            tickets.values().removeIf(t -> !isTicketLive(t.openedAtMs(), nowMs, timings));
        }

        public void clear() {
            tickets.clear();
        }

        /** How many tickets {@code bot} holds, live or not yet swept. */
        public int count(UUID bot) {
            int n = 0;
            for (Id id : tickets.keySet()) {
                if (id.bot().equals(bot)) {
                    n++;
                }
            }
            return n;
        }
    }

    /** What one step does with the ticket for the chest and item it names, before anything is asked, walked to or moved. */
    public enum TicketStep {
        /** No ticket for this chest and item, and no prompt pending: make a request. */
        REQUEST,
        /**
         * The ticket is not covered yet and the bot has a prompt waiting (most likely this
         * ticket's own): wait.
         */
        WAIT,
        /**
         * A live grant or standing permission covers the ticket: take now, or report READY so the
         * caller walks there first. Checked before the pending prompt, which is the bot's and may
         * be for something else.
         */
        REDEEM,
        /**
         * The ticket is not covered and nothing is pending (the owner said No, the prompt expired,
         * or the grant lapsed): drop it and refuse, so the caller never walks to a chest it may
         * not take from.
         */
        DROP_NOT_PERMITTED,
        /** No ticket for this chest and item, and another prompt is waiting: refuse, ask nothing. */
        OTHER_PENDING
    }

    /**
     * @param hasMatchingTicket the bot holds a live ticket for this chest and exact item
     * @param pending           the bot has a prompt waiting for the owner (one per bot, whichever item)
     * @param permitted         a live grant or standing permission covers that ticket's fingerprint
     *                          (false without a ticket)
     */
    public static TicketStep ticketStep(boolean hasMatchingTicket, boolean pending, boolean permitted) {
        if (hasMatchingTicket) {
            if (permitted) {
                return TicketStep.REDEEM;
            }
            return pending ? TicketStep.WAIT : TicketStep.DROP_NOT_PERMITTED;
        }
        return pending ? TicketStep.OTHER_PENDING : TicketStep.REQUEST;
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

    // ── Owner away ───────────────────────────────────────────────────────────────────────────

    /**
     * Which (bot, chest) pairs recently found the owner away, each for {@link #OWNER_AWAY_MEMO_MS}.
     * While a pair is noted, the facade refuses a new request for it quietly instead of asking the
     * ledger again, unless a standing permission covers the chest (which needs no owner nearby).
     * Thread-safe.
     */
    public static final class OwnerAwayMemo {
        private record Id(UUID bot, ChestKey chest) {
        }

        private final ConcurrentHashMap<Id, Long> until = new ConcurrentHashMap<>();

        public void noteAway(UUID bot, ChestKey chest, long nowMs) {
            if (bot != null && chest != null) {
                until.put(new Id(bot, chest), nowMs + OWNER_AWAY_MEMO_MS);
            }
        }

        /** Whether the owner was found away from {@code chest} for {@code bot} within the memo's time. */
        public boolean isAway(UUID bot, ChestKey chest, long nowMs) {
            if (bot == null || chest == null) {
                return false;
            }
            Id id = new Id(bot, chest);
            Long deadline = until.get(id);
            if (deadline == null) {
                return false;
            }
            if (nowMs >= deadline) {
                until.remove(id, deadline);
                return false;
            }
            return true;
        }

        public void sweep(long nowMs) {
            until.values().removeIf(deadline -> nowMs >= deadline);
        }

        public void clear() {
            until.clear();
        }
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
     * @param scope      {@link #transferScope} for a refusal, {@link Scope#NONE} otherwise
     */
    public record TransferAction(Kind kind, boolean keepTicket, String reason, Scope scope) {
    }

    /**
     * Items moved → {@link Kind#MOVED}, ticket dropped (a once-grant is spent; under a standing
     * permission the next call asks afresh). Out of reach → {@link Kind#READY}, ticket kept: walk,
     * then call again. No room → {@link Kind#REFUSED} with the ticket kept, so the caller may make
     * room and call again. Refused by the policy now ({@code INELIGIBLE_NOW}: the reserve drained
     * or the need went while the bot walked) → {@link Kind#REFUSED} with the ticket kept, as the
     * ledger keeps the grant. Anything else → {@link Kind#REFUSED}, ticket dropped. A move that
     * reports success but moved nothing is a refusal: the grant is spent all the same.
     */
    public static TransferAction onTransfer(TransferStatus status, int moved) {
        if (status == null) {
            return action(Kind.REFUSED, false, TransferStatus.INVALID);
        }
        return switch (status) {
            case MOVED, MOVED_SHORT -> action(moved > 0 ? Kind.MOVED : Kind.REFUSED, false, status);
            case OUT_OF_REACH -> action(Kind.READY, true, status);
            case NO_ROOM, INELIGIBLE_NOW -> action(Kind.REFUSED, true, status);
            case WRONG_THREAD, NOT_RUNNING, INVALID, OWNER_OR_BOT_MISMATCH, DENIED, CHEST_MISMATCH, NO_STOCK,
                 NOT_PERMITTED -> action(Kind.REFUSED, false, status);
        };
    }

    private static TransferAction action(Kind kind, boolean keepTicket, TransferStatus status) {
        return new TransferAction(kind, keepTicket, status.name(), resultScope(kind, transferScope(status)));
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
