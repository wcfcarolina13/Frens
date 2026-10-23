package net.wcfcarolina13.GameAI.services;

/**
 * Pure-logic policy: how long an idle hobby must wait after it fails.
 *
 * <p>No Minecraft imports — this is decision arithmetic over ints/longs/strings only.
 *
 * <p><b>The storm this exists to prevent.</b> The idle-hobby scheduler's wooden fallback used a
 * single flat 12 s cooldown. A hobby that fails <em>deterministically</em> — "I have no axe and
 * can't make or find one", where the skill exits inside one server tick without changing anything
 * — therefore restarted every 12 s at best, and, whenever the cooldown was wiped by an unrelated
 * gate, on every second tick. The 2026-09-06 field log caught exactly that: 197 identical
 * "Idle hobby 'woodcut' finished … success=false" lines at 3–19 per second across two bursts.
 * A flat cooldown cannot tell "try again in a moment, the world may have changed" apart from
 * "this will fail identically forever", so this policy grows the wait with each consecutive
 * failure until a bot that genuinely cannot do a hobby retries it at a human pace instead of a
 * tick-rate one.
 *
 * <p><b>Counting convention.</b> {@link Attempt#consecutiveFailures()} is the number of failures
 * recorded <em>before</em> the attempt being described — so the first failure of a fresh hobby
 * carries {@code consecutiveFailures == 0} and earns exactly {@link #BASE_FAILURE_TICKS}. Call
 * sites read the stored counter, build the {@link Attempt} with it, then fold the outcome in with
 * {@link #nextFailureCount(int, boolean)}.
 *
 * <p><b>Aborts are not failures.</b> A run that ended because the user cancelled it
 * ({@code /bot stop}, a threat pause) says nothing about whether the hobby is viable, so it
 * neither adds backoff nor advances the counter.
 */
public final class HobbyBackoffPolicy {

    /**
     * One completed hobby attempt, as seen by the scheduler when the run finishes.
     *
     * @param hobby              normalised hobby name (e.g. {@code "woodcut"}); carried so a caller
     *                           can log which hobby the decision belongs to
     * @param success            whether the skill reported success
     * @param abortRequested     whether the run ended because it was cancelled rather than failed
     * @param consecutiveFailures failures recorded <em>before</em> this attempt (0 on the first one)
     * @param nowTick            server tick at which the attempt finished
     */
    public record Attempt(String hobby, boolean success, boolean abortRequested,
                          int consecutiveFailures, long nowTick) {
    }

    /**
     * Wait after the very first failure: 1200 ticks = 60 s at 20 tps.
     *
     * <p>Chosen to be long enough that a deterministic failure can never be mistaken for ambient
     * chatter in the log (the storm produced up to 19 restarts per second; this is one per minute),
     * and short enough that a genuinely transient failure — a tree that was just felled by the
     * player, a chest that was momentarily out of reach — still gets a prompt second look while the
     * bot is plausibly still standing in the same place.
     */
    public static final long BASE_FAILURE_TICKS = 1200L;

    /**
     * Ceiling on the wait: 12000 ticks = 10 minutes at 20 tps.
     *
     * <p>The backoff is deliberately capped rather than allowed to grow without bound. A hobby that
     * is impossible now can become possible later without any signal the scheduler can observe (the
     * commander drops an axe in a chest, a forest grows in, day breaks), so the bot must keep
     * checking. Ten minutes is roughly half a Minecraft day: long enough that a permanently
     * impossible hobby costs a handful of log lines per session instead of thousands, short enough
     * that a bot notices a restored world within one day/night cycle.
     */
    public static final long MAX_BACKOFF_TICKS = 12_000L;

    /**
     * Largest shift ever applied to {@link #BASE_FAILURE_TICKS}.
     *
     * <p>Four is the smallest shift whose raw product ({@code 1200 << 4 == 19200}) already exceeds
     * {@link #MAX_BACKOFF_TICKS}, so clamping here loses nothing: every larger failure count would
     * be clamped to the same ceiling anyway. Clamping the <em>shift</em> rather than only the
     * product is what makes overflow structurally impossible — an unclamped
     * {@code 1200L << consecutiveFailures} wraps to a negative (and so instantly-elapsed) backoff
     * once the count passes ~53, which would silently re-open the storm for the most persistently
     * failing hobby, the exact case this class exists for.
     */
    public static final int CAP_SHIFT = 4;

    /**
     * Saturation ceiling for the stored failure counter.
     *
     * <p>Counts above {@link #CAP_SHIFT} already produce identical backoffs, so the counter stops
     * climbing a little past the cap instead of running to {@link Integer#MAX_VALUE} over a long
     * session. The small headroom above {@code CAP_SHIFT} keeps the number readable in logs as
     * "well past the cap" rather than sitting exactly on it.
     */
    public static final int MAX_FAILURE_COUNT = 64;

    private HobbyBackoffPolicy() {
    }

    /**
     * Earliest tick at which the described hobby may be started again.
     *
     * <p>A success or an abort returns {@code nowTick} — no backoff at all. A failure returns
     * {@code nowTick} plus {@code BASE_FAILURE_TICKS << min(consecutiveFailures, CAP_SHIFT)},
     * clamped to {@link #MAX_BACKOFF_TICKS}: 60 s, 120 s, 240 s, 480 s, then 600 s forever.
     */
    public static long nextAllowedTick(Attempt a) {
        if (a == null) {
            return 0L;
        }
        if (a.success() || a.abortRequested()) {
            return a.nowTick();
        }
        int shift = a.consecutiveFailures();
        if (shift < 0) {
            shift = 0;
        }
        if (shift > CAP_SHIFT) {
            shift = CAP_SHIFT;
        }
        long backoff = Math.min(BASE_FAILURE_TICKS << shift, MAX_BACKOFF_TICKS);
        return a.nowTick() + backoff;
    }

    /**
     * Folds one outcome into the stored consecutive-failure counter.
     *
     * <p>Success resets to zero; anything else increments, saturating at
     * {@link #MAX_FAILURE_COUNT}. This two-argument form has no opinion about aborts — callers that
     * can observe an abort must not call it at all (leave the counter untouched), or use
     * {@link #nextFailureCount(int, boolean, boolean)}.
     */
    public static int nextFailureCount(int prior, boolean success) {
        if (success) {
            return 0;
        }
        int base = Math.max(prior, 0);
        return base >= MAX_FAILURE_COUNT ? MAX_FAILURE_COUNT : base + 1;
    }

    /**
     * Abort-aware form of {@link #nextFailureCount(int, boolean)}: a cancelled run leaves the
     * counter exactly as it was, because the user stopping a hobby is not evidence that the hobby
     * is unachievable.
     */
    public static int nextFailureCount(int prior, boolean success, boolean abortRequested) {
        if (abortRequested) {
            return Math.max(prior, 0);
        }
        return nextFailureCount(prior, success);
    }

    /**
     * Ticket-name prefix {@code TaskService.beginSkill} puts in front of every skill name.
     */
    private static final String SKILL_TICKET_PREFIX = "skill:";

    /**
     * Canonical map key for a hobby name.
     *
     * <p>Trims, lower-cases, and — the point of this being shared (1.1.216 review finding 8) —
     * strips the {@code "skill:"} ticket prefix that {@code TaskService.beginSkill} builds into
     * {@code ActiveTaskInfo.name()}. The scheduler's read site already stripped it while the write
     * site did not; that only worked because every caller happened to pass a bare name, and the
     * first caller to pass a ticket name would have written {@code "skill:woodcut"}, never matched
     * the read site, and silently disabled the gate. One normaliser, used by both.
     */
    public static String normalizeHobbyKey(String hobby) {
        if (hobby == null) {
            return "";
        }
        String normalized = hobby.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith(SKILL_TICKET_PREFIX)) {
            normalized = normalized.substring(SKILL_TICKET_PREFIX.length()).trim();
        }
        return normalized;
    }

    /**
     * How often a bot serving out a backoff may re-probe the world for the tool it lacks:
     * 100 ticks = 5 s at 20 tps.
     *
     * <p>The scheduler's flat cooldown early-return runs before anything re-reads the world, so
     * without a probe a bot four failures deep waits out the full ten minutes even after the
     * commander drops an axe in the chest beside it. The probe exists so the field check "put an
     * axe in a chest during a long backoff and the bot retries within seconds" passes.
     *
     * <p>It is throttled because the probe is not free: it pulls from every accessible container
     * around the bot, and running that at tick rate would trade a restart storm for a scan storm.
     * Five seconds is short enough to read as "immediately" to a player standing there and long
     * enough that the scan costs one pass per hundred ticks instead of one per tick.
     */
    public static final long AVAILABILITY_PROBE_TICKS = 100L;

    /**
     * Whether a backed-off hobby may re-probe the world for the tool it lacks on this tick.
     *
     * <p>Three gates, in order: there must actually be a backoff running (a plain in-flight
     * cooldown does not justify a container scan), a bot that has never probed always may, and
     * otherwise at least {@code intervalTicks} must have passed since the last probe. A
     * {@code nowTick} earlier than the recorded probe means the server tick counter restarted
     * under us (an integrated-server world reload), which is treated as "probe now" rather than
     * stranding the bot behind a stale future timestamp.
     *
     * @param backoffRunning whether a failure backoff is currently suppressing the hobby
     * @param lastProbeTick  tick of this bot's previous probe, or {@code null} if it has never probed
     * @param nowTick        current server tick
     * @param intervalTicks  minimum ticks between probes (see {@link #AVAILABILITY_PROBE_TICKS})
     */
    public static boolean shouldProbeAvailability(boolean backoffRunning,
                                                  Long lastProbeTick,
                                                  long nowTick,
                                                  long intervalTicks) {
        if (!backoffRunning) {
            return false;
        }
        if (lastProbeTick == null) {
            return true;
        }
        long last = lastProbeTick.longValue();
        if (nowTick < last) {
            return true;
        }
        return nowTick - last >= Math.max(intervalTicks, 0L);
    }

    /** Inventory-only axe readiness: absent, craftable, or held. */
    public static int axeReadiness(boolean axeHeld, boolean axeCraftable) {
        return axeHeld ? 2 : axeCraftable ? 1 : 0;
    }

    /** A failure with the same or better tools must keep its backoff. */
    public static boolean availabilityImproved(Integer failedReadiness, int currentReadiness) {
        return failedReadiness != null && currentReadiness > failedReadiness;
    }

    /** Human-readable one-liner for the scheduler's "still backing off" log line. */
    public static String describe(String hobby, int consecutiveFailures, long remainingTicks) {
        return String.format("hobby '%s' backing off: failures=%d remaining=%.0fs",
                hobby == null ? "?" : hobby,
                Math.max(consecutiveFailures, 0),
                Math.max(remainingTicks, 0L) / 20.0d);
    }
}
