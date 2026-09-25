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
 * the one every supply site follows: {@link Scope#ITEM} skips the item, {@link Scope#CHEST} the
 * chest (both halves of a double chest), {@link Scope#BOT} and {@link Scope#OWNER_ABSENT} stop the
 * pass and pause it, {@link Scope#TRANSIENT} leaves only this chest stack for a later pull.
 */
public final class CraftChestPullPolicy {

    /**
     * How long a bot stops asking for one material after a pull that met a refusal and got
     * nothing. Longer than an unanswered prompt's life plus the bot's prompt cooldown (30 s + 15 s
     * by default), so a caller that retries every tick cannot re-prompt the owner as fast as the
     * ledger allows. It is also the flat 60 s an owner found away earns: a pause here never grows.
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
        /** Every further ask would be refused the same way, or the owner is away: stop, and pause the material. */
        STOP,
        /**
         * A ticket for this chest and item is still open: the owner has not answered, or has said
         * yes but the bot cannot reach the chest. Stop without pausing; the next pull asks it first.
         */
        HOLD
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
     * → {@link Next#SKIP_CHEST}; {@link Scope#BOT} (the owner's No or an ignored prompt, another
     * prompt pending, a cooldown, no owner) and {@link Scope#OWNER_ABSENT} → {@link Next#STOP};
     * {@link Scope#TRANSIENT} (a busy hop, no room, the service stopping) → {@link Next#NEXT}, this
     * stack only. {@link Scope#NONE} or none at all is not a refusal's scope: fail closed.
     */
    public static Next onRefusal(Scope scope) {
        if (scope == null) {
            return Next.STOP;
        }
        return switch (scope) {
            case ITEM -> Next.SKIP_ITEM;
            case CHEST -> Next.SKIP_CHEST;
            case BOT, OWNER_ABSENT, NONE -> Next.STOP;
            case TRANSIENT -> Next.NEXT;
        };
    }

    /**
     * Whether the answer for the chest and item a pull asked first — the ticket it held from an
     * earlier pull — settles that ticket, so later pulls stop asking it first: items moved, or a
     * refusal that says something about the owner, the chest or the item. A hold keeps it, and so
     * does a transient failure (a busy hop, no room), which leaves the ticket as it was.
     */
    public static boolean settlesHeldTicket(Kind kind, Scope scope) {
        if (kind == null) {
            return true;
        }
        return switch (kind) {
            case MOVED -> true;
            case READY, WAITING -> false;
            case REFUSED -> scope != Scope.TRANSIENT;
        };
    }

    /**
     * Whether one answer counts toward the pull's pause: a refusal from the owner's ledger or its
     * chest ({@link Scope#CHEST}, {@link Scope#BOT}), or the owner found away
     * ({@link Scope#OWNER_ABSENT}). Never an item the policy never grants ({@link Scope#ITEM}: no
     * ask was made) or a transient failure ({@link Scope#TRANSIENT}: it says nothing about the
     * owner), and never an answer that is not a refusal.
     */
    public static boolean countsTowardPause(Kind kind, Scope scope) {
        if (kind != Kind.REFUSED || scope == null) {
            return false;
        }
        return switch (scope) {
            case CHEST, BOT, OWNER_ABSENT -> true;
            case ITEM, TRANSIENT, NONE -> false;
        };
    }

    /**
     * Whether a pull pauses its material for {@link #PAUSE_MS}: only when it moved nothing, met a
     * refusal that {@link #countsTowardPause counts}, and leaves no ticket of this material open. A
     * ticket still open — a prompt waiting, or a yes not yet reached — must stay free for the next
     * pull to redeem.
     */
    public static boolean shouldPause(int moved, boolean holdingTicket, boolean metRefusal) {
        return moved <= 0 && !holdingTicket && metRefusal;
    }

    /** Whether a pause running until {@code pausedUntilMs} still holds at {@code nowMs}; the deadline itself has passed. */
    public static boolean isPaused(Long pausedUntilMs, long nowMs) {
        return pausedUntilMs != null && nowMs < pausedUntilMs;
    }
}
