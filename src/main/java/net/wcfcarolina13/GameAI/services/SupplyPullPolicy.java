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
 * <p>The per-scope rule, shared with {@code CraftChestPullPolicy} and
 * {@code MutualAidChestFoodPolicy}: {@link Scope#ITEM} skips the item everywhere this pass;
 * {@link Scope#CHEST} skips the chest (both halves of a double chest); {@link Scope#BOT} stops
 * the pass and the caller pauses; {@link Scope#OWNER_ABSENT} stops the pass for a flat
 * {@link #OWNER_AWAY_PAUSE_MS} that is never a miss; {@link Scope#BUSY} leaves that chest for
 * the next cycle without stopping the rest or counting a miss. {@link Scope#TARGET} reads as
 * {@link Scope#CHEST} for now.
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
     * How soon a caller whose last answer was "waiting for the owner" may look again. A grant
     * lives 60 s from the owner's click, so a few seconds keeps it comfortably redeemable.
     */
    public static final long WAITING_RECHECK_MS = 5_000L;
    /** The first pause after a pass that asked and got nothing; doubles per further miss. */
    public static final long MISS_PAUSE_MS = 60_000L;
    /** The longest pause after repeated misses: 10 minutes, the hobby ladder's ceiling. */
    public static final long MAX_MISS_PAUSE_MS = 600_000L;
    /** Largest doubling applied to {@link #MISS_PAUSE_MS} ({@code 60 s << 4} already exceeds the ceiling). */
    public static final int MISS_PAUSE_CAP_SHIFT = 4;
    /**
     * The flat wait after the owner was found away from a chest nothing standing covers: long
     * enough not to re-ask (and re-log) every few seconds, short enough that a returning owner
     * is asked within a minute. Never climbs, never counts as a miss.
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
        /** {@link Scope#BUSY}: leave this chest for the next cycle; go on with the others. */
        RETRY_LATER,
        /** {@link Scope#BOT}, or a prompt is open or a grant waits for the bot: stop the pass. */
        STOP,
        /** {@link Scope#OWNER_ABSENT}: stop the pass; recheck after {@link #OWNER_AWAY_PAUSE_MS}. */
        OWNER_AWAY;

        /** Whether this answer ends the pass: nothing after it is asked. */
        public boolean stopsPass() {
            return this == STOP || this == OWNER_AWAY;
        }
    }

    /**
     * How far one answer reaches. {@code MOVED}: go on. {@code WAITING} and {@code READY}: stop
     * (one prompt per bot, and a caller that does not walk cannot take a grant out of reach). A
     * refusal by its scope; a refusal without one reads {@link Scope#BOT} (fail closed).
     */
    public static Next next(Kind kind, Scope scope) {
        if (kind == null) {
            return Next.STOP;
        }
        return switch (kind) {
            case MOVED -> Next.NEXT;
            case READY, WAITING -> Next.STOP;
            case REFUSED -> switch (refusalScope(scope)) {
                case ITEM -> Next.SKIP_ITEM;
                case CHEST, TARGET -> Next.SKIP_CHEST;
                case BUSY -> Next.RETRY_LATER;
                case OWNER_ABSENT -> Next.OWNER_AWAY;
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
     * Whether an answer is a miss, one step on the caller's backoff: a refusal for this chest
     * ({@link Scope#CHEST}, {@link Scope#TARGET}) or for everything the bot asks for now
     * ({@link Scope#BOT}). Not a miss: anything that moved or is waiting; {@link Scope#ITEM} (the
     * pre-filter, nothing asked); {@link Scope#OWNER_ABSENT} (a flat recheck, not the owner's
     * answer); and {@link Scope#BUSY} (nothing about the owner, the item or the chest).
     */
    public static boolean isMiss(Kind kind, Scope scope) {
        if (kind != Kind.REFUSED) {
            return false;
        }
        Scope s = refusalScope(scope);
        return s == Scope.CHEST || s == Scope.TARGET || s == Scope.BOT;
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
     * answer: only when this exact item is never granted ({@link Scope#ITEM}). Any other refusal
     * answers for the whole chest, or more.
     */
    public static boolean tryNextStack(Kind kind, Scope scope) {
        return kind == Kind.REFUSED && refusalScope(scope) == Scope.ITEM;
    }

    // ── What a pull adds up to ───────────────────────────────────────────────────────────────

    /**
     * What one pull achieved across every chest and item it tried: an idle pull
     * ({@code ToolProvisionService}'s reachable-chest choke point) or a chest tool search.
     *
     * @param moved     items moved into the bot
     * @param waiting   a prompt is open, or a grant waits for the bot ({@link #isWaiting})
     * @param missed    at least one answer was a miss ({@link #isMiss})
     * @param ownerAway an answer found the owner away ({@link #isOwnerAway})
     * @param halted    an answer stopped the pull ({@link Next#stopsPass()}); later items were not asked
     */
    public record Pull(int moved, boolean waiting, boolean missed, boolean ownerAway, boolean halted) {
        public static final Pull NOTHING = new Pull(0, false, false, false, false);

        public Pull plus(Pull other) {
            if (other == null) {
                return this;
            }
            return new Pull(moved + other.moved, waiting || other.waiting, missed || other.missed,
                    ownerAway || other.ownerAway, halted || other.halted);
        }

        public boolean movedAny() {
            return moved > 0;
        }
    }

    /** Folds one answer into a pull's tally. */
    public static Pull fold(Pull tally, Kind kind, int moved, Scope scope) {
        Pull t = tally == null ? Pull.NOTHING : tally;
        int took = kind == Kind.MOVED ? Math.max(0, moved) : 0;
        return new Pull(t.moved() + took,
                t.waiting() || isWaiting(kind),
                t.missed() || isMiss(kind, scope),
                t.ownerAway() || isOwnerAway(kind, scope),
                t.halted() || next(kind, scope).stopsPass());
    }

    /** What an idle pull does to its caller's ask backoff. */
    public enum Backoff {
        /** Moved something and missed nothing: clear the backoff. */
        SUCCESS,
        /** Asked and got nothing, with no prompt open: one more failure on the ladder. */
        FAILURE,
        /** The owner is away: wait the flat {@link #OWNER_AWAY_PAUSE_MS}, the failure count untouched. */
        OWNER_AWAY,
        /** Leave it: nothing was asked, or a prompt is still open (the next pull must be free to redeem it). */
        NONE
    }

    /**
     * An open prompt outranks everything (its grant must stay redeemable); then the owner being
     * away (so a bot left alone does not climb the ladder and keep a returning owner waiting);
     * then a miss; then a move.
     */
    public static Backoff idleBackoff(Pull pull) {
        if (pull == null || pull.waiting()) {
            return Backoff.NONE;
        }
        if (pull.ownerAway()) {
            return Backoff.OWNER_AWAY;
        }
        if (pull.missed()) {
            return Backoff.FAILURE;
        }
        return pull.movedAny() ? Backoff.SUCCESS : Backoff.NONE;
    }

    /**
     * Whether the idle wooden fallback holds (no craft, no woodcut) to wait for the owner: only
     * while a prompt is open and the bot still lacks the weapon or axe the fallback exists for.
     * Waiting on, say, a helmet does not hold up a bot that already has both.
     */
    public static boolean holdsIdleFallback(Pull pull, boolean stillMissingWeaponOrAxe) {
        return pull != null && pull.waiting() && stillMissingWeaponOrAxe;
    }

    // ── How long to leave the owner alone ────────────────────────────────────────────────────

    /** {@link #MISS_PAUSE_MS} doubled per earlier miss, capped at {@link #MAX_MISS_PAUSE_MS}. */
    public static long missPauseMs(int priorMisses) {
        int shift = Math.min(Math.max(priorMisses, 0), MISS_PAUSE_CAP_SHIFT);
        return Math.min(MISS_PAUSE_MS << shift, MAX_MISS_PAUSE_MS);
    }

    /**
     * How long a chest tool search waits before it asks again, in the same order as
     * {@link #idleBackoff}: {@link #WAITING_RECHECK_MS} when it ended on an open prompt (come back
     * soon to redeem it), {@link #OWNER_AWAY_PAUSE_MS} when the owner was away,
     * {@link #missPauseMs} after a miss, and 0 when it took something or asked nothing.
     */
    public static long retrievalPauseMs(Pull search, int priorMisses) {
        if (search == null || search.movedAny()) {
            return 0L;
        }
        if (search.waiting()) {
            return WAITING_RECHECK_MS;
        }
        if (search.ownerAway()) {
            return OWNER_AWAY_PAUSE_MS;
        }
        return search.missed() ? missPauseMs(priorMisses) : 0L;
    }

    /**
     * The miss counter after a chest tool search: reset by a move, unchanged while waiting or
     * while the owner is away, +1 on a miss.
     */
    public static int nextMissCount(int prior, Pull search) {
        if (search != null && search.movedAny()) {
            return 0;
        }
        if (search == null || search.waiting() || search.ownerAway() || !search.missed()) {
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
