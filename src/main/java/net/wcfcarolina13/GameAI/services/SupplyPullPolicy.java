package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;

/**
 * Pure decisions for the automatic chest pulls that {@code ToolProvisionService},
 * {@code ChestStoreService}, {@code HarvestCropSkill} and {@code BotIdleHobbiesService} make
 * through {@code SupplyWithdrawals}: how far one answer from the facade reaches, when a walking
 * caller walks, what a pull adds up to, and how long a caller leaves the owner alone before
 * asking again.
 *
 * <p>An answer is read by its {@link Kind} and, for a refusal, its {@link Scope} only; the reason
 * text is for the logs. {@code ChestStoreService} makes three refusals of its own for a chest it
 * did not take from, all {@link Scope#CHEST}: {@link #NOT_CHEST}, {@link #NO_MATCH} and
 * {@link #UNREACHABLE}.
 *
 * <p>The per-scope rule, identical in {@code CraftChestPullPolicy} and
 * {@code MutualAidChestFoodPolicy}:
 * <ul>
 *   <li>{@link Scope#ITEM}: skip this item everywhere this pass.</li>
 *   <li>{@link Scope#CHEST}: skip this chest, both halves of a double chest, this pass.</li>
 *   <li>{@link Scope#TARGET}: skip this item in this chest only; other items in this chest, and
 *       this item in other chests, are still tried.</li>
 *   <li>{@link Scope#OWNER_ABSENT}: skip this chest (both halves) and remember; a pass that ends
 *       having moved nothing, with no prompt open, no grant waiting and no busy stop, waits a flat
 *       {@link #OWNER_AWAY_PAUSE_MS}. Never a miss, never climbs.</li>
 *   <li>{@link Scope#BOT}: stop the pass; one step on the caller's backoff (a miss).</li>
 *   <li>{@link Scope#BUSY}: stop the pass and hold like a prompt still open: the caller looks
 *       again at its short cadence. Never a miss, never a pause.</li>
 *   <li>{@link Scope#INVENTORY_FULL}: stop the pass (nothing more fits). Never a miss and never a
 *       hold: the caller goes on with its other work as if nothing were found, since nothing here
 *       frees a slot. A chest tool search then waits the flat {@link #OWNER_AWAY_PAUSE_MS}
 *       ({@link #retrievalPauseMs}); the idle pull does not back off.
 *       {@code MutualAidChestFoodPolicy} alone makes room, once.</li>
 * </ul>
 * A pass that met only item, chest and target refusals ends as "nothing found": no pause, no miss.
 *
 * <p>No Minecraft types: {@link Kind}, {@link WaitMode} and {@link Scope} are plain nested enums,
 * and loading one does not load its outer class.
 */
public final class SupplyPullPolicy {

    /** The block at the position is not a chest (a barrel, a furnace, air): never asked. {@link Scope#CHEST}. */
    public static final String NOT_CHEST = "NOT_CHEST";
    /** The chest holds nothing the caller wants: never asked, never walked to. {@link Scope#CHEST}. */
    public static final String NO_MATCH = "NO_MATCH";
    /** The bot walked but could not get within reach of the chest. {@link Scope#CHEST}. */
    public static final String UNREACHABLE = "UNREACHABLE";

    /**
     * How soon a caller whose last pass held (a prompt open, a grant waiting for the bot, or a
     * busy answer) may look again. A grant lives 60 s from the owner's click, so a few seconds
     * keeps it comfortably redeemable.
     */
    public static final long WAITING_RECHECK_MS = 5_000L;
    /** The first pause after a pass the owner's answer ended; doubles per further miss. */
    public static final long MISS_PAUSE_MS = 60_000L;
    /** The longest pause after repeated misses: 10 minutes, the hobby ladder's ceiling. */
    public static final long MAX_MISS_PAUSE_MS = 600_000L;
    /** Largest doubling applied to {@link #MISS_PAUSE_MS} ({@code 60 s << 4} already exceeds the ceiling). */
    public static final int MISS_PAUSE_CAP_SHIFT = 4;
    /**
     * The flat wait after a pass that found the owner away and got nothing else: long enough not
     * to re-ask (and re-log) every few seconds, short enough that a returning owner is asked
     * within a minute. Never climbs, never counts as a miss.
     */
    public static final long OWNER_AWAY_PAUSE_MS = 60_000L;
    /** {@link #OWNER_AWAY_PAUSE_MS} in server ticks, for tick-keyed backoffs. */
    public static final long OWNER_AWAY_PAUSE_TICKS = OWNER_AWAY_PAUSE_MS / 50L;

    private SupplyPullPolicy() {
    }

    // ── One answer ───────────────────────────────────────────────────────────────────────────

    /** What a pass over chest stacks, or over chests, does after one answer from the facade. */
    public enum Next {
        /** Items moved: go on (to the next stack, or the caller is done). */
        NEXT,
        /** {@link Scope#ITEM}: this exact item is never granted: skip it in every chest this pass. */
        SKIP_ITEM,
        /** {@link Scope#CHEST}: this chest will not serve this pass: skip it, both halves of a double chest. */
        SKIP_CHEST,
        /** {@link Scope#TARGET}: skip this item in this chest (both halves) only; go on with the rest. */
        SKIP_TARGET,
        /** {@link Scope#OWNER_ABSENT}: skip this chest (both halves) and remember; see {@link #ownerAwayDefers}. */
        OWNER_AWAY,
        /** {@code WAITING} or {@code READY}: a prompt is open or a grant waits for the bot. Stop, no miss. */
        HOLD,
        /** {@link Scope#BUSY}: stop, no miss; look again at the caller's short cadence. */
        BUSY,
        /** {@link Scope#INVENTORY_FULL}: stop, no miss, no pause, no hold; the caller goes on as if nothing were found. */
        FULL,
        /** {@link Scope#BOT}: stop; a miss. */
        STOP;

        /** Whether this answer ends the pass: nothing after it is asked. */
        public boolean stopsPass() {
            return this == HOLD || this == BUSY || this == FULL || this == STOP;
        }
    }

    /**
     * How far one answer reaches. {@code MOVED}: go on. {@code WAITING} and {@code READY}: hold
     * (one prompt per bot, and a caller that does not walk cannot take a grant out of reach). A
     * refusal by its scope; a refusal without one reads {@link Scope#BOT} (fail closed), and so
     * does a missing kind.
     */
    public static Next next(Kind kind, Scope scope) {
        if (kind == null) {
            return Next.STOP;
        }
        return switch (kind) {
            case MOVED -> Next.NEXT;
            case READY, WAITING -> Next.HOLD;
            case REFUSED -> switch (refusalScope(scope)) {
                case ITEM -> Next.SKIP_ITEM;
                case CHEST -> Next.SKIP_CHEST;
                case TARGET -> Next.SKIP_TARGET;
                case OWNER_ABSENT -> Next.OWNER_AWAY;
                case BUSY -> Next.BUSY;
                case INVENTORY_FULL -> Next.FULL;
                case BOT, NONE -> Next.STOP;
            };
        };
    }

    /**
     * Whether an answer means the owner has a prompt open or a grant is waiting for the bot to
     * reach the chest ({@code WAITING}, {@code READY}): a caller keeps coming back, quietly and
     * soon, rather than count it a miss.
     */
    public static boolean isWaiting(Kind kind) {
        return kind == Kind.WAITING || kind == Kind.READY;
    }

    /**
     * Whether an answer is busy ({@link Scope#BUSY}: another prompt of this bot open, a busy
     * server or hop, a stopped call): held like a wait, never a miss.
     */
    public static boolean isBusy(Kind kind, Scope scope) {
        return kind == Kind.REFUSED && refusalScope(scope) == Scope.BUSY;
    }

    /**
     * Whether a permitted take found the bot's inventory full ({@link Scope#INVENTORY_FULL}): it
     * ends the pass, but it is not held (a bot waiting for room nothing frees would wait for
     * ever), not a miss and not a pause.
     */
    public static boolean isFull(Kind kind, Scope scope) {
        return kind == Kind.REFUSED && refusalScope(scope) == Scope.INVENTORY_FULL;
    }

    /**
     * Whether an answer is a miss, one step on the caller's backoff: only the owner's decision
     * ({@link Scope#BOT}: their No, an ignored prompt, a cooldown, no owner). Not a miss: anything
     * that moved or is waiting; {@link Scope#ITEM}, {@link Scope#CHEST} and {@link Scope#TARGET}
     * ("nothing found" here, as before chests were asked); {@link Scope#OWNER_ABSENT} (a flat
     * recheck, not the owner's answer); {@link Scope#BUSY}; and {@link Scope#INVENTORY_FULL}.
     */
    public static boolean isMiss(Kind kind, Scope scope) {
        return kind == Kind.REFUSED && refusalScope(scope) == Scope.BOT;
    }

    /** Whether an answer found the owner away from a chest nothing standing covers. */
    public static boolean isOwnerAway(Kind kind, Scope scope) {
        return kind == Kind.REFUSED && refusalScope(scope) == Scope.OWNER_ABSENT;
    }

    /** A refusal's scope, {@link Scope#BOT} when it has none (as the facade itself reads it). */
    private static Scope refusalScope(Scope scope) {
        return scope == null || scope == Scope.NONE ? Scope.BOT : scope;
    }

    // ── Walking callers (ChestStoreService, and the chest loops above it) ────────────────────

    /**
     * Whether a walking caller walks to the chest after the ask it made before walking.
     * {@code READY} (permitted, out of reach): always. {@code WAITING} (the owner has not
     * answered): only a caller that will wait for the answer at the chest; a caller that never
     * waits would walk there and back once per call while the prompt is open, so it returns and
     * lets a later call, once permitted, walk. Anything else: never.
     */
    public static boolean walkAfterAsk(Kind kind, WaitMode mode) {
        if (kind == Kind.READY) {
            return true;
        }
        return kind == Kind.WAITING && mode == WaitMode.UNTIL_ANSWERED;
    }

    /**
     * Within one chest, whether the next distinct matching stack is worth asking about after this
     * answer: when this exact item is never granted ({@link Scope#ITEM}) or this chest cannot
     * grant this item ({@link Scope#TARGET}). Any other refusal answers for the whole chest, or
     * more.
     */
    public static boolean tryNextStack(Kind kind, Scope scope) {
        if (kind != Kind.REFUSED) {
            return false;
        }
        Scope s = refusalScope(scope);
        return s == Scope.ITEM || s == Scope.TARGET;
    }

    // ── Stopping a chest tool search ─────────────────────────────────────────────────────────

    /**
     * Whether a chest tool search ends before it asks, or walks to, its next chest: the task it
     * ran under was told to stop. {@code inTaskAtStart} is read once, before the search's first
     * hop, because {@code /bot stop} removes the task at once ({@code TaskService.forceAbort})
     * while its abort latch stays set: read per chest, the check would find no task and go on to
     * prompt for, or take from, the next chest for a bot its owner just stopped. A search begun
     * outside any task ({@code DurabilityFallbackService}) never stops on the latch, which a
     * {@code /bot come} or {@code follow} may have left set long before.
     *
     * @param inTaskAtStart  the bot had an active task when the search began
     * @param abortRequested the bot's abort latch is set now
     */
    public static boolean stopsToolSearch(boolean inTaskAtStart, boolean abortRequested) {
        return inTaskAtStart && abortRequested;
    }

    // ── What a pull adds up to ───────────────────────────────────────────────────────────────

    /**
     * What one pass achieved across every chest and item it tried: an idle pull
     * ({@code ToolProvisionService}'s reachable-chest choke point), a chest tool search, or a
     * harvest seed restock.
     *
     * @param moved     items moved into the bot
     * @param waiting   a prompt is open, or a grant waits for the bot ({@link #isWaiting})
     * @param busy      an answer was busy ({@link #isBusy})
     * @param full      a permitted take found the bot's inventory full ({@link #isFull}); not held
     * @param missed    at least one answer was a miss ({@link #isMiss})
     * @param ownerAway an answer found the owner away ({@link #isOwnerAway})
     * @param halted    an answer stopped the pull ({@link Next#stopsPass()}); later items were not asked
     */
    public record Pull(int moved, boolean waiting, boolean busy, boolean full, boolean missed, boolean ownerAway,
                       boolean halted) {
        public static final Pull NOTHING = new Pull(0, false, false, false, false, false, false);

        public Pull plus(Pull other) {
            if (other == null) {
                return this;
            }
            return new Pull(moved + other.moved, waiting || other.waiting, busy || other.busy, full || other.full,
                    missed || other.missed, ownerAway || other.ownerAway, halted || other.halted);
        }

        public boolean movedAny() {
            return moved > 0;
        }

        /**
         * Whether the pass held: a prompt open, a grant waiting, or a busy answer. The caller comes
         * back soon. A full inventory is not a hold ({@link #full}).
         */
        public boolean held() {
            return waiting || busy;
        }
    }

    /** Folds one answer into a pull's tally. */
    public static Pull fold(Pull tally, Kind kind, int moved, Scope scope) {
        Pull t = tally == null ? Pull.NOTHING : tally;
        int took = kind == Kind.MOVED ? Math.max(0, moved) : 0;
        return new Pull(t.moved() + took,
                t.waiting() || isWaiting(kind),
                t.busy() || isBusy(kind, scope),
                t.full() || isFull(kind, scope),
                t.missed() || isMiss(kind, scope),
                t.ownerAway() || isOwnerAway(kind, scope),
                t.halted() || next(kind, scope).stopsPass());
    }

    /**
     * Whether a finished pass earns the flat owner-away wait ({@link #OWNER_AWAY_PAUSE_MS}): it
     * found the owner away and ended empty-handed without anything else to say — nothing moved,
     * nothing held (no prompt open, no grant waiting, no busy stop), no full inventory (that stop
     * pauses on its own, and it ended the pass before every chest was asked) and no miss (the
     * owner's own answer backs off on the miss ladder instead).
     */
    public static boolean ownerAwayDefers(Pull pass) {
        return pass != null && pass.ownerAway() && !pass.movedAny() && !pass.held() && !pass.full()
                && !pass.missed();
    }

    /** What an idle pull does to its caller's ask backoff. */
    public enum Backoff {
        /** Moved something and missed nothing: clear the backoff. */
        SUCCESS,
        /** The owner's answer ended it (a miss): one more failure on the ladder. */
        FAILURE,
        /** The owner is away and nothing else happened: wait the flat {@link #OWNER_AWAY_PAUSE_MS}, the failure count untouched. */
        OWNER_AWAY,
        /**
         * Leave it: nothing was asked or only nothing was found, the inventory was full, or the
         * pass held (a prompt still open, a grant waiting, a busy answer: the next pull must be
         * free to come back soon).
         */
        NONE
    }

    /**
     * A held pass outranks everything (an open prompt's grant must stay redeemable, and a busy
     * answer is retried soon); then a miss (the owner decided); then a move; then the owner being
     * away (so a bot left alone does not climb the ladder and keep a returning owner waiting). A
     * pass a full inventory stopped is none of these: {@link Backoff#NONE} unless it moved first.
     */
    public static Backoff idleBackoff(Pull pull) {
        if (pull == null || pull.held()) {
            return Backoff.NONE;
        }
        if (pull.missed()) {
            return Backoff.FAILURE;
        }
        if (pull.movedAny()) {
            return Backoff.SUCCESS;
        }
        return ownerAwayDefers(pull) ? Backoff.OWNER_AWAY : Backoff.NONE;
    }

    /**
     * Whether the idle wooden fallback holds (no craft, no woodcut) to come back soon: only while
     * the pull held (a prompt open, a grant waiting, a busy answer) and the bot still lacks the
     * weapon or axe the fallback exists for. Waiting on, say, a helmet does not hold up a bot that
     * already has both. A full inventory never holds it: nothing the fallback waits on would free
     * a slot, so it crafts or cuts wood as if nothing were found.
     */
    public static boolean holdsIdleFallback(Pull pull, boolean stillMissingWeaponOrAxe) {
        return pull != null && pull.held() && stillMissingWeaponOrAxe;
    }

    // ── How long to leave the owner alone ────────────────────────────────────────────────────

    /** {@link #MISS_PAUSE_MS} doubled per earlier miss, capped at {@link #MAX_MISS_PAUSE_MS}. */
    public static long missPauseMs(int priorMisses) {
        int shift = Math.min(Math.max(priorMisses, 0), MISS_PAUSE_CAP_SHIFT);
        return Math.min(MISS_PAUSE_MS << shift, MAX_MISS_PAUSE_MS);
    }

    /**
     * How long a chest tool search waits before it asks again, in the same order as
     * {@link #idleBackoff}: 0 when it took something; {@link #WAITING_RECHECK_MS} when it held
     * (come back soon to redeem a grant, or to retry a busy answer); {@link #missPauseMs} after a
     * miss; the flat {@link #OWNER_AWAY_PAUSE_MS} when the inventory was full (the facade answers
     * a full bot READY, so without it Woodcut would walk to the chest for NO_ROOM before every log;
     * never a miss) or when the owner was away and nothing else happened; and 0 when it asked
     * nothing or found nothing.
     */
    public static long retrievalPauseMs(Pull search, int priorMisses) {
        if (search == null || search.movedAny()) {
            return 0L;
        }
        if (search.held()) {
            return WAITING_RECHECK_MS;
        }
        if (search.missed()) {
            return missPauseMs(priorMisses);
        }
        if (search.full()) {
            return OWNER_AWAY_PAUSE_MS;
        }
        return ownerAwayDefers(search) ? OWNER_AWAY_PAUSE_MS : 0L;
    }

    /**
     * The miss counter after a chest tool search: reset by a move, unchanged while it held, +1 on
     * a miss, unchanged otherwise (the owner away, nothing found).
     */
    public static int nextMissCount(int prior, Pull search) {
        if (search != null && search.movedAny()) {
            return 0;
        }
        if (search == null || search.held() || !search.missed()) {
            return Math.max(prior, 0);
        }
        return HobbyBackoffPolicy.nextFailureCount(prior, false);
    }

    /**
     * Ticks until an idle craft fallback that just failed may try again. The first failure keeps
     * the branch's own flat wait; each further consecutive failure waits the hobby ladder one step
     * behind ({@link HobbyBackoffPolicy#nextAllowedTick}: 60 s, 120 s, … 10 min), never less than
     * the flat wait. A craft can fail because {@code CraftingHelper} is waiting on a supply
     * prompt, so a flat wait alone would re-ask an owner who ignores it at that same pace forever.
     *
     * @param flatTicks      the branch's own wait after a failure
     * @param priorFailures  consecutive failures recorded before this one
     */
    public static long craftRetryTicks(long flatTicks, int priorFailures) {
        long flat = Math.max(0L, flatTicks);
        if (priorFailures <= 0) {
            return flat;
        }
        long ladder = HobbyBackoffPolicy.nextAllowedTick(
                new HobbyBackoffPolicy.Attempt("craft", false, false, priorFailures - 1, 0L));
        return Math.max(flat, ladder);
    }
}
