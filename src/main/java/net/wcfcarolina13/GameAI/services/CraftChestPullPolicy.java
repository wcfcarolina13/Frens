package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;

/**
 * Pure decisions behind {@code CraftingHelper}'s automatic chest pulls, the crafting materials a
 * bot takes from nearby chests through {@code SupplyWithdrawals}: what the pull loop does after
 * each answer, whether a pull earns a pause, and whether that pause is still running.
 *
 * <p>No Minecraft types. {@link Kind} is a plain enum nested in {@code SupplyWithdrawals}, and
 * loading it does not load the outer class.
 *
 * <p>A refusal's reason is the facade's machine-readable text: a code, sometimes followed by a
 * detail in brackets ({@code DENIED(DENY_LOCKED)}, {@code INELIGIBLE(RESERVE_EXHAUSTED)}) or after
 * a space ({@code OPENED id=…}). Only the code and the bracketed detail are read.
 */
public final class CraftChestPullPolicy {

    /**
     * How long a bot stops asking for one material after a pull that asked and got nothing. Longer
     * than an unanswered prompt's life plus the bot's prompt cooldown (30 s + 15 s by default), so
     * a caller that retries every tick cannot re-prompt the owner as fast as the ledger allows.
     */
    public static final long PAUSE_MS = 60_000L;

    private CraftChestPullPolicy() {
    }

    /** What the pull loop does after one answer from the facade. */
    public enum Next {
        /** Go on to the next chest stack; the loop stops by itself once enough has moved. */
        NEXT,
        /** Off the server thread only: walk to this chest, then ask again. */
        WALK,
        /** This exact item is never granted: skip it in every other chest too. */
        SKIP_ITEM,
        /** This chest will refuse everything else as well: skip its other stacks. */
        SKIP_CHEST,
        /** Every further ask for this material would be refused the same way: stop. */
        STOP,
        /** The owner has not answered about this chest and item: stop; a later pull asks for it first. */
        STOP_WAITING
    }

    /**
     * The answer to the first ask for a chest stack: on the server thread the only ask (the bot
     * never walks there), off it the ask before walking.
     */
    public static Next afterAsk(Kind kind, String reason, boolean onServerThread) {
        if (kind == null) {
            return Next.STOP;
        }
        return switch (kind) {
            case MOVED -> Next.NEXT;
            case READY -> onServerThread ? Next.SKIP_CHEST : Next.WALK;
            case WAITING -> onServerThread ? Next.STOP_WAITING : Next.WALK;
            case REFUSED -> onRefusal(reason);
        };
    }

    /** The answer to the ask after walking to the chest. */
    public static Next afterWalk(Kind kind, String reason) {
        if (kind == null) {
            return Next.STOP;
        }
        return switch (kind) {
            case MOVED -> Next.NEXT;
            // Still out of reach (the walk failed): the old pull moved on to the next chest too.
            case READY -> Next.SKIP_CHEST;
            case WAITING -> Next.STOP_WAITING;
            case REFUSED -> onRefusal(reason);
        };
    }

    /**
     * How far a refusal reaches.
     * <ul>
     *   <li>{@link Next#SKIP_ITEM}: the pre-filter's verdicts ({@code NOT_ALLOWLISTED},
     *       {@code PROTECTED_COMPONENTS}, {@code TIER_NOT_ALLOWED}) hold for the item wherever it is.</li>
     *   <li>{@link Next#NEXT}: this chest's stock of this item ({@code RESERVE_EXHAUSTED},
     *       {@code NO_NEED}, {@code NO_STOCK}, a move that moved nothing).</li>
     *   <li>{@link Next#SKIP_CHEST}: the chest ({@code DENIED}, {@code CHEST_MISMATCH}, a
     *       {@code NOT_PERMITTED} ticket), or the owner being away ({@code OWNER_NOT_NEARBY}) — a
     *       chest under a standing permission needs no owner nearby, so the next chest may still
     *       give.</li>
     *   <li>{@link Next#STOP}: everything else, unknown reasons included — another prompt pending,
     *       either cooldown, a full inventory, no owner, the service or the bot gone, a busy or
     *       interrupted hop, an error.</li>
     * </ul>
     */
    public static Next onRefusal(String reason) {
        String code = reasonCode(reason);
        String detail = reasonDetail(reason);
        switch (code) {
            case "INELIGIBLE":
                if (isNeverGranted(detail)) {
                    return Next.SKIP_ITEM;
                }
                if ("RESERVE_EXHAUSTED".equals(detail) || "NO_NEED".equals(detail)) {
                    return Next.NEXT;
                }
                return Next.STOP;
            case "NO_STOCK":
            case "MOVED":
            case "MOVED_SHORT":
                return Next.NEXT;
            case "DENIED":
            case "CHEST_MISMATCH":
            case "NOT_PERMITTED":
            case "OWNER_NOT_NEARBY":
                return Next.SKIP_CHEST;
            default:
                return Next.STOP;
        }
    }

    /**
     * Whether the facade logged this answer at INFO, having made a request or a transfer. Its quiet
     * answers — a pre-filter refusal, a repeat wait on a pending prompt, another prompt pending —
     * cost nothing to repeat, so they never earn a pause.
     */
    public static boolean isLoud(Kind kind, String reason) {
        if (kind == Kind.WAITING) {
            return !"PENDING".equals(reasonCode(reason));
        }
        if (kind == Kind.REFUSED) {
            String code = reasonCode(reason);
            if ("OTHER_REQUEST_PENDING".equals(code)) {
                return false;
            }
            return !("INELIGIBLE".equals(code) && isNeverGranted(reasonDetail(reason)));
        }
        return true;
    }

    /**
     * Whether a pull pauses its material for {@link #PAUSE_MS}: only when it asked (a loud answer)
     * and got nothing. A pull that moved anything, or that ended on a prompt still waiting for the
     * owner (the next pull must be free to redeem it), never pauses.
     */
    public static boolean shouldPause(int moved, boolean endedWaiting, boolean askedLoudly) {
        return moved <= 0 && !endedWaiting && askedLoudly;
    }

    /** Whether a pause running until {@code pausedUntilMs} still holds at {@code nowMs}; the deadline itself has passed. */
    public static boolean isPaused(Long pausedUntilMs, long nowMs) {
        return pausedUntilMs != null && nowMs < pausedUntilMs;
    }

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

    private static boolean isNeverGranted(String verdict) {
        return "NOT_ALLOWLISTED".equals(verdict) || "PROTECTED_COMPONENTS".equals(verdict)
                || "TIER_NOT_ALLOWED".equals(verdict);
    }
}
