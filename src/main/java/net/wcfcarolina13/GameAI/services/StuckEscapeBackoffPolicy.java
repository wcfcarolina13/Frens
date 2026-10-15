package net.wcfcarolina13.GameAI.services;

/**
 * Backoff for a bot that keeps failing surface recovery with nothing to build with (1.1.239,
 * sandbox finding #17: a bot in a covered stone pocket with 0 blocks and no pickaxe looped
 * surface recovery for 23 minutes).
 *
 * <p>An episode counts failed recovery attempts made with no scaffold blocks and no gain in the
 * bot's on-ground Y. The first {@link #FREE_ATTEMPTS} - 1 failures are free. From failure
 * {@link #FREE_ATTEMPTS} on, the next attempt waits 30 s, doubling per further failure, capped at
 * 10 minutes. The owner is told once per episode, when the first cooldown starts while the owner
 * is online and alive (otherwise at the next failed attempt that finds them reachable).</p>
 *
 * <p>An episode ends on: a successful recovery, a failure while holding scaffold, a change of the
 * bot's on-ground Y, its scaffold count going from 0 to more than 0, a night to day transition, or
 * an owner command.</p>
 *
 * <p>Pure: operates on the immutable {@link State}. The per-bot map lives in
 * {@code BotUndergroundSurvivalService}. No {@code net.minecraft.*} types.</p>
 */
public final class StuckEscapeBackoffPolicy {

    /** The failure count at which the first cooldown starts. */
    public static final int FREE_ATTEMPTS = 2;
    /** First cooldown: 30 s. */
    public static final long BASE_COOLDOWN_TICKS = 600L;
    /** Cooldown cap: 10 minutes. */
    public static final long MAX_COOLDOWN_TICKS = 12_000L;

    public enum Outcome {
        /** No cooldown (episode cleared, or still within the free attempts). */
        NONE,
        /** A cooldown started; the owner was already told this episode. */
        COOLDOWN_STARTED,
        /** A cooldown started and this is the first one of the episode: tell the owner. */
        NOTIFY_ONCE
    }

    /**
     * Per-bot episode state. {@code failures == 0} means no episode ({@link #NONE_STATE}).
     */
    public record State(int failures, int groundY, int scaffoldCount, boolean isDay,
                        long cooldownUntilTick, boolean notified) {
        public boolean active() {
            return failures > 0;
        }
    }

    public static final State NONE_STATE = new State(0, 0, 0, true, 0L, false);

    public record Result(State state, Outcome outcome, long cooldownTicks) {
    }

    private StuckEscapeBackoffPolicy() {
    }

    /** Cooldown before the next attempt after {@code failures} failures in one episode. */
    public static long cooldownTicks(int failures) {
        if (failures < FREE_ATTEMPTS) {
            return 0L;
        }
        int doublings = failures - FREE_ATTEMPTS;
        if (doublings >= 31) {
            return MAX_COOLDOWN_TICKS;
        }
        long ticks = BASE_COOLDOWN_TICKS << doublings;
        return Math.min(ticks, MAX_COOLDOWN_TICKS);
    }

    /**
     * Fold one finished surface-recovery attempt into the episode.
     *
     * @param onGround      whether {@code blockY} is an on-ground reading (an airborne Y is ignored)
     * @param scaffoldCount scaffold blocks the bot holds now
     */
    public static Result recordAttempt(State prior, boolean succeeded, boolean onGround, int blockY,
                                       int scaffoldCount, boolean isDay, long nowTick) {
        return recordAttempt(prior, succeeded, onGround, blockY, scaffoldCount, isDay, nowTick, true);
    }

    /**
     * Whether the owner can receive the stuck line now: online (resolved, not removed) and alive.
     * A dead owner's chat line is dropped, so it must not use up the episode's one notice.
     */
    public static boolean ownerReachable(boolean ownerResolved, boolean ownerRemoved, boolean ownerAlive) {
        return ownerResolved && !ownerRemoved && ownerAlive;
    }

    /**
     * As {@link #recordAttempt(State, boolean, boolean, int, int, boolean, long)}, with the owner's
     * reachability ({@link #ownerReachable}). The episode is marked notified only when the notice
     * can be delivered; otherwise it stays pending and the next failed attempt that starts a
     * cooldown retries it.
     */
    public static Result recordAttempt(State prior, boolean succeeded, boolean onGround, int blockY,
                                       int scaffoldCount, boolean isDay, long nowTick,
                                       boolean ownerReachable) {
        State base = prior != null ? prior : NONE_STATE;
        if (succeeded || scaffoldCount > 0) {
            return new Result(NONE_STATE, Outcome.NONE, 0L);
        }
        if (base.active() && onGround && blockY != base.groundY()) {
            base = NONE_STATE; // the bot moved: a new episode starts with this failure
        }
        int groundY = onGround || !base.active() ? blockY : base.groundY();
        int failures = base.failures() + 1;
        long cooldown = cooldownTicks(failures);
        boolean notify = cooldown > 0 && !base.notified() && ownerReachable;
        State next = new State(failures, groundY, scaffoldCount, isDay,
                cooldown > 0 ? nowTick + cooldown : 0L, base.notified() || notify);
        Outcome outcome = notify ? Outcome.NOTIFY_ONCE
                : cooldown > 0 ? Outcome.COOLDOWN_STARTED : Outcome.NONE;
        return new Result(next, outcome, cooldown);
    }

    /**
     * Periodic observation between attempts. Returns {@link #NONE_STATE} when a reset trigger
     * fired, otherwise the prior state with the day flag and scaffold count refreshed.
     */
    public static State observe(State prior, boolean onGround, int blockY, int scaffoldCount,
                                boolean isDay, boolean ownerCommand) {
        if (prior == null || !prior.active()) {
            return NONE_STATE;
        }
        if (ownerCommand) {
            return NONE_STATE;
        }
        if (onGround && blockY != prior.groundY()) {
            return NONE_STATE;
        }
        if (prior.scaffoldCount() == 0 && scaffoldCount > 0) {
            return NONE_STATE;
        }
        if (!prior.isDay() && isDay) {
            return NONE_STATE;
        }
        if (prior.isDay() == isDay && prior.scaffoldCount() == scaffoldCount) {
            return prior;
        }
        return new State(prior.failures(), prior.groundY(), scaffoldCount, isDay,
                prior.cooldownUntilTick(), prior.notified());
    }

    public static boolean inCooldown(State state, long nowTick) {
        return state != null && state.active() && nowTick < state.cooldownUntilTick();
    }
}
