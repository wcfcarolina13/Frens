package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;

/**
 * Pure decisions behind {@code CraftingHelper}'s automatic chest pulls, the crafting materials a
 * bot takes from nearby chests through {@code SupplyWithdrawals}: what the pull loop does after
 * each answer, whether a pull earns a pause, and whether that pause is still running.
 *
 * <p>No Minecraft types. {@link Kind} and {@link Scope} are plain enums nested in
 * {@code SupplyWithdrawals} and {@code SupplyWithdrawalPolicy}, and loading one does not load its
 * outer class.
 *
 * <p>A refusal is read by its {@link Scope} alone; the reason string is for the logs. The rule is
 * the one every supply site follows ({@code SupplyPullPolicy} spells it out): {@link Scope#ITEM}
 * skips the item everywhere this pull; {@link Scope#CHEST} skips the chest (both halves of a
 * double chest); {@link Scope#TARGET} skips this item in this chest only; {@link Scope#OWNER_ABSENT}
 * skips the chest (both halves) and, if the pull then ends empty-handed with nothing held, pauses
 * the material a flat {@link #PAUSE_MS}; {@link Scope#BOT} stops the pull and pauses the material;
 * {@link Scope#BUSY} stops the pull without a pause.
 */
public final class CraftChestPullPolicy {

    /**
     * How long a bot stops asking for one material after a pull the owner's decision ended, or
     * that found the owner away and got nothing else. Longer than an unanswered prompt's life
     * plus the bot's prompt cooldown (30 s + 15 s by default), so a caller that retries every tick
     * cannot re-prompt the owner as fast as the ledger allows. It is also the flat 60 s an owner
     * found away earns: a pause here never grows.
     */
    public static final long PAUSE_MS = 60_000L;

    private CraftChestPullPolicy() {
    }

    /** What the pull loop does after one answer from the facade. */
    public enum Next {
        /** Go on to the next chest stack; the loop stops by itself once enough has moved. */
        NEXT,
        /** Off the server thread only: permitted but out of reach, so walk to this chest, then ask again. */
        WALK,
        /** This exact item is never granted: skip it in every other chest too. */
        SKIP_ITEM,
        /** This chest will refuse everything else as well: skip its other stacks, and its other half's. */
        SKIP_CHEST,
        /** This chest cannot grant this item: skip it in this chest (both halves); its other items are still asked. */
        SKIP_TARGET,
        /** The owner is away: skip this chest (both halves), and remember it for the pause ({@link #shouldPause}). */
        OWNER_AWAY,
        /**
         * A ticket for this chest and item is still open: the owner has not answered, or has said
         * yes but the bot cannot reach the chest. Stop without pausing; the next pull asks it first.
         */
        HOLD,
        /** Busy (another prompt open, no room, a busy hop, a stopped call): stop without pausing. */
        BUSY,
        /** The owner decided (a No, an ignored prompt, a cooldown, no owner): stop, and pause the material. */
        STOP
    }

    /**
     * The answer to the first ask for a chest stack: on the server thread the only ask (the bot
     * never walks there), off it the ask before walking. Only a permitted ticket walks: a prompt
     * still open does not ({@link Kind#WAITING} holds), so the bot never walks to a chest the owner
     * may yet refuse.
     */
    public static Next afterAsk(Kind kind, Scope scope, boolean onServerThread) {
        if (kind == null) {
            return Next.STOP;
        }
        return switch (kind) {
            case MOVED -> Next.NEXT;
            case READY -> onServerThread ? Next.HOLD : Next.WALK;
            case WAITING -> Next.HOLD;
            case REFUSED -> onRefusal(scope);
        };
    }

    /**
     * The answer to the ask after walking to the chest. {@link Kind#READY} here means the walk did
     * not reach it: the grant is still live, so the pull holds it for the next pull rather than
     * asking another chest (a second prompt right after a yes) or pausing (the yes would lapse).
     */
    public static Next afterWalk(Kind kind, Scope scope) {
        if (kind == null) {
            return Next.STOP;
        }
        return switch (kind) {
            case MOVED -> Next.NEXT;
            case READY, WAITING -> Next.HOLD;
            case REFUSED -> onRefusal(scope);
        };
    }

    /**
     * How far a refusal reaches: {@link Scope#ITEM} → {@link Next#SKIP_ITEM}; {@link Scope#CHEST}
     * → {@link Next#SKIP_CHEST}; {@link Scope#TARGET} → {@link Next#SKIP_TARGET};
     * {@link Scope#OWNER_ABSENT} → {@link Next#OWNER_AWAY}; {@link Scope#BUSY} → {@link Next#BUSY};
     * {@link Scope#BOT} → {@link Next#STOP}. {@link Scope#NONE} or none at all is not a refusal's
     * scope: fail closed, as {@link Scope#BOT}.
     */
    public static Next onRefusal(Scope scope) {
        if (scope == null) {
            return Next.STOP;
        }
        return switch (scope) {
            case ITEM -> Next.SKIP_ITEM;
            case CHEST -> Next.SKIP_CHEST;
            case TARGET -> Next.SKIP_TARGET;
            case OWNER_ABSENT -> Next.OWNER_AWAY;
            case BUSY -> Next.BUSY;
            case BOT, NONE -> Next.STOP;
        };
    }

    /**
     * Whether the answer for the chest and item a pull asked first — the ticket it held from an
     * earlier pull — settles that ticket, so later pulls stop asking it first: items moved, or a
     * refusal that says something about the owner, the chest or the item (the facade has dropped
     * that ticket). A hold keeps it, and so does a busy answer (another prompt open, no room),
     * which leaves the ticket as it was.
     */
    public static boolean settlesHeldTicket(Kind kind, Scope scope) {
        if (kind == null) {
            return true;
        }
        return switch (kind) {
            case MOVED -> true;
            case READY, WAITING -> false;
            case REFUSED -> scope != Scope.BUSY;
        };
    }

    /**
     * Whether one answer is the owner's decision, which pauses the material: a refusal scoped
     * {@link Scope#BOT}, or one with no scope at all (fail closed). Never an item, chest or target
     * refusal ("nothing found here"), never the owner being away (see {@link #isOwnerAway}), never
     * a busy moment, and never an answer that is not a refusal.
     */
    public static boolean countsTowardPause(Kind kind, Scope scope) {
        return kind == Kind.REFUSED && (scope == null || scope == Scope.BOT || scope == Scope.NONE);
    }

    /** Whether one answer found the owner away from a chest nothing standing covers. */
    public static boolean isOwnerAway(Kind kind, Scope scope) {
        return kind == Kind.REFUSED && scope == Scope.OWNER_ABSENT;
    }

    /**
     * Whether a pull pauses its material for {@link #PAUSE_MS}. Never when it moved something,
     * leaves a ticket of this material open (a prompt waiting, or a yes not yet reached, must stay
     * free for the next pull to redeem), or stopped busy (retried at the caller's own pace). Then
     * when the owner's decision stopped it ({@link #countsTowardPause}), or it found the owner
     * away ({@link #isOwnerAway}): the same flat minute either way. A pull that met only item,
     * chest and target refusals found nothing, and does not pause.
     */
    public static boolean shouldPause(int moved, boolean holdingTicket, boolean stoppedBusy, boolean metOwnerDecision,
                                      boolean ownerAwaySeen) {
        if (moved > 0 || holdingTicket || stoppedBusy) {
            return false;
        }
        return metOwnerDecision || ownerAwaySeen;
    }

    /** Whether a pause running until {@code pausedUntilMs} still holds at {@code nowMs}; the deadline itself has passed. */
    public static boolean isPaused(Long pausedUntilMs, long nowMs) {
        return pausedUntilMs != null && nowMs < pausedUntilMs;
    }
}
