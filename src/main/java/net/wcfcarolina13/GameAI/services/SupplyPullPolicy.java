package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;

/**
 * Pure decisions for the automatic chest pulls that {@code ToolProvisionService},
 * {@code ChestStoreService}, {@code HarvestCropSkill} and {@code BotIdleHobbiesService} make
 * through {@code SupplyWithdrawals}: how far one answer from the facade reaches, when a walking
 * caller walks, what an idle pull adds up to, and how long a caller leaves the owner alone before
 * asking again.
 *
 * <p>No Minecraft types: {@link Kind} and {@link WaitMode} are plain enums nested in
 * {@code SupplyWithdrawals}, and loading one does not load the outer class.
 *
 * <p>A reason is the facade's machine-readable text: a code, sometimes with a bracketed detail
 * ({@code DENIED(DENY_LOCKED)}, {@code INELIGIBLE(RESERVE_EXHAUSTED)}) or a trailing
 * {@code " id=…"}. Only the code and the bracketed detail are read. {@code ChestStoreService}
 * adds three codes of its own for a chest it did not take from: {@link #NOT_CHEST},
 * {@link #NO_MATCH} and {@link #UNREACHABLE}.
 *
 * <p>{@code CraftChestPullPolicy} reads the same reasons for {@code CraftingHelper}. The one
 * deliberate difference is {@code NOT_PERMITTED}: the owner has just said No to, or ignored, a
 * prompt, so asking any other chest straight away would prompt them again; here it stops the pass.
 */
public final class SupplyPullPolicy {

    /** The block at the position is not a chest (a barrel, a furnace, air): never asked. */
    public static final String NOT_CHEST = "NOT_CHEST";
    /** The chest holds nothing the caller wants: never asked, never walked to. */
    public static final String NO_MATCH = "NO_MATCH";
    /** The bot walked but could not get within reach of the chest. */
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

    private SupplyPullPolicy() {
    }

    // ── One answer ───────────────────────────────────────────────────────────────────────────

    /** What a pass over chest stacks does after one answer from the facade. */
    public enum Next {
        /** Go on to the next stack: items moved, or this chest's stock of this item will not serve. */
        NEXT,
        /** The pre-filter never grants this exact item (no ask was made): skip it in every chest. */
        SKIP_ITEM,
        /** This chest will not serve the bot: skip its other stacks. */
        SKIP_CHEST,
        /** A prompt is open, or nothing more will be granted right now: stop this pass. */
        STOP
    }

    /**
     * How far one answer reaches.
     * <ul>
     *   <li>{@link Next#NEXT}: {@code MOVED}; {@code INELIGIBLE(RESERVE_EXHAUSTED|NO_NEED)},
     *       {@code NO_STOCK}, a move that moved nothing, {@link #NO_MATCH}.</li>
     *   <li>{@link Next#SKIP_ITEM}: the pre-filter's verdicts, {@code NOT_ALLOWLISTED},
     *       {@code PROTECTED_COMPONENTS} and {@code TIER_NOT_ALLOWED}.</li>
     *   <li>{@link Next#SKIP_CHEST}: {@code DENIED(…)}, {@code CHEST_MISMATCH},
     *       {@code OWNER_NOT_NEARBY} (a chest under a standing permission needs no owner nearby,
     *       so another chest may still serve), {@link #NOT_CHEST}, {@link #UNREACHABLE}.</li>
     *   <li>{@link Next#STOP}: {@code WAITING} and {@code READY} (one prompt per bot; asking
     *       elsewhere would only drop the ticket), and every other refusal, unknown ones included:
     *       another prompt pending, {@code NOT_PERMITTED}, either cooldown, a full inventory, no
     *       owner, a timeout or abort, the service or bot gone, a busy hop, an error.</li>
     * </ul>
     */
    public static Next next(Kind kind, String reason) {
        if (kind == null) {
            return Next.STOP;
        }
        return switch (kind) {
            case MOVED -> Next.NEXT;
            case READY, WAITING -> Next.STOP;
            case REFUSED -> onRefusal(reason);
        };
    }

    private static Next onRefusal(String reason) {
        String code = reasonCode(reason);
        switch (code) {
            case "INELIGIBLE": {
                String verdict = reasonDetail(reason);
                if (isNeverGrantedVerdict(verdict)) {
                    return Next.SKIP_ITEM;
                }
                if ("RESERVE_EXHAUSTED".equals(verdict) || "NO_NEED".equals(verdict)) {
                    return Next.NEXT;
                }
                return Next.STOP; // NO_OWNER: nobody may approve anything for this bot
            }
            case "NO_STOCK":
            case "MOVED":
            case "MOVED_SHORT":
            case NO_MATCH:
                return Next.NEXT;
            case "DENIED":
            case "CHEST_MISMATCH":
            case "OWNER_NOT_NEARBY":
            case NOT_CHEST:
            case UNREACHABLE:
                return Next.SKIP_CHEST;
            default:
                return Next.STOP;
        }
    }

    /**
     * Whether an answer means the owner has a prompt open or a grant is waiting for the bot to
     * reach the chest: {@code WAITING}, {@code READY}, or a refusal because another prompt of this
     * bot's is still open. A caller must keep coming back (quietly) rather than count it a miss.
     */
    public static boolean isWaiting(Kind kind, String reason) {
        if (kind == Kind.WAITING || kind == Kind.READY) {
            return true;
        }
        if (kind != Kind.REFUSED) {
            return false;
        }
        String code = reasonCode(reason);
        return "OTHER_REQUEST_PENDING".equals(code) || "DUPLICATE_PENDING".equals(code);
    }

    /**
     * Whether an answer is a miss: the owner's ledger was consulted and gave nothing (a refusal,
     * a cooldown, an expired or refused prompt, the reserve, no room, access denied) or the bot
     * walked for it and could not reach the chest. Not a miss: anything that moved or is waiting;
     * the pre-filter's never-granted verdicts and a chest that was not a chest or held nothing
     * wanted (nothing was asked); and the transport failures that say nothing about the owner
     * ({@code NOT_RUNNING}, {@code BOT_GONE}, {@code INVALID}, {@code SERVER_BUSY}, {@code ERROR},
     * {@code ABORTED}, {@code WRONG_THREAD}).
     */
    public static boolean isMiss(Kind kind, String reason) {
        if (kind != Kind.REFUSED || isWaiting(kind, reason)) {
            return false;
        }
        String code = reasonCode(reason);
        if ("INELIGIBLE".equals(code) && isNeverGrantedVerdict(reasonDetail(reason))) {
            return false;
        }
        return switch (code) {
            case NOT_CHEST, NO_MATCH, "NOT_RUNNING", "BOT_GONE", "INVALID", "SERVER_BUSY", "ERROR", "ABORTED",
                 "WRONG_THREAD" -> false;
            default -> true;
        };
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
     * answer: only when this exact stack was refused for its own sake (never granted, the reserve,
     * no stock). A chest-wide or bot-wide refusal answers for every stack.
     */
    public static boolean tryNextStack(Kind kind, String reason) {
        if (kind != Kind.REFUSED) {
            return false;
        }
        Next next = next(kind, reason);
        return next == Next.NEXT || next == Next.SKIP_ITEM;
    }

    /** What a caller walking a list of chests does after one chest's result. */
    public enum ChestLoop {
        /** Items moved: done. */
        DONE,
        /** This chest did not serve: try the next one. */
        NEXT_CHEST,
        /** A prompt is open or nothing more will be granted now: stop asking. */
        STOP
    }

    public static ChestLoop afterChest(Kind kind, int moved, String reason) {
        if (kind == Kind.MOVED && moved > 0) {
            return ChestLoop.DONE;
        }
        return next(kind, reason) == Next.STOP ? ChestLoop.STOP : ChestLoop.NEXT_CHEST;
    }

    // ── Idle pulls (ToolProvisionService's reachable-chest choke point, BotIdleHobbiesService) ─

    /**
     * What one idle pull achieved across every chest and item it tried.
     *
     * @param moved   items moved into the bot
     * @param waiting a prompt is open, or a grant waits for the bot ({@link #isWaiting})
     * @param missed  at least one answer was a miss ({@link #isMiss})
     * @param halted  an answer stopped the pull ({@link Next#STOP}); later items were not asked
     */
    public record Pull(int moved, boolean waiting, boolean missed, boolean halted) {
        public static final Pull NOTHING = new Pull(0, false, false, false);

        public Pull plus(Pull other) {
            if (other == null) {
                return this;
            }
            return new Pull(moved + other.moved, waiting || other.waiting, missed || other.missed,
                    halted || other.halted);
        }

        public boolean movedAny() {
            return moved > 0;
        }
    }

    /** Folds one answer into a pull's tally. */
    public static Pull fold(Pull tally, Kind kind, int moved, String reason) {
        Pull t = tally == null ? Pull.NOTHING : tally;
        int took = kind == Kind.MOVED ? Math.max(0, moved) : 0;
        return new Pull(t.moved() + took,
                t.waiting() || isWaiting(kind, reason),
                t.missed() || isMiss(kind, reason),
                t.halted() || next(kind, reason) == Next.STOP);
    }

    /** What an idle pull does to its caller's ask backoff. */
    public enum Backoff {
        /** Moved something and missed nothing: clear the backoff. */
        SUCCESS,
        /** Asked and got nothing, with no prompt open: one more failure on the ladder. */
        FAILURE,
        /** Leave it: nothing was asked, or a prompt is still open (the next pull must be free to redeem it). */
        NONE
    }

    public static Backoff idleBackoff(Pull pull) {
        if (pull == null || pull.waiting()) {
            return Backoff.NONE;
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
     * How long a chest tool retrieval waits before it asks again: {@link #WAITING_RECHECK_MS}
     * when it ended on an open prompt (come back soon to redeem it), {@link #missPauseMs} after a
     * miss, and 0 when it asked nothing.
     */
    public static long retrievalPauseMs(boolean endedWaiting, boolean missed, int priorMisses) {
        if (endedWaiting) {
            return WAITING_RECHECK_MS;
        }
        return missed ? missPauseMs(priorMisses) : 0L;
    }

    /** The miss counter after a retrieval: reset by a move, unchanged while waiting, +1 on a miss. */
    public static int nextMissCount(int prior, boolean moved, boolean endedWaiting, boolean missed) {
        if (moved) {
            return 0;
        }
        if (endedWaiting || !missed) {
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

    // ── Reason text ──────────────────────────────────────────────────────────────────────────

    /** The reason's code: the text before any bracket or space; empty for {@code null}. */
    static String reasonCode(String reason) {
        if (reason == null) {
            return "";
        }
        int end = reason.length();
        int bracket = reason.indexOf('(');
        if (bracket >= 0) {
            end = bracket;
        }
        int space = reason.indexOf(' ');
        if (space >= 0 && space < end) {
            end = space;
        }
        return reason.substring(0, end).trim();
    }

    /** The bracketed detail, e.g. {@code DENY_LOCKED} in {@code DENIED(DENY_LOCKED)}; {@code null} if none. */
    static String reasonDetail(String reason) {
        if (reason == null) {
            return null;
        }
        int open = reason.indexOf('(');
        int close = reason.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        return reason.substring(open + 1, close).trim();
    }

    private static boolean isNeverGrantedVerdict(String verdict) {
        return "NOT_ALLOWLISTED".equals(verdict) || "PROTECTED_COMPONENTS".equals(verdict)
                || "TIER_NOT_ALLOWED".equals(verdict);
    }
}
