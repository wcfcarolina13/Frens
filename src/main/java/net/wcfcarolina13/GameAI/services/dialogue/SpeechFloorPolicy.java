package net.wcfcarolina13.GameAI.services.dialogue;

/**
 * Pure-logic policy: the cross-lane "speech floor" that keeps one audience from being talked at
 * by several dialogue lanes at once.
 *
 * <p>No Minecraft imports — the whole decision is expressed over epoch millis and an enum, so it
 * is unit-testable (see {@code TravelWaitPolicy} for the same shape).
 *
 * <p>Background: before this policy every dialogue lane owned its own cooldown, and those
 * cooldowns were keyed by (bot, trigger-pool) only. Nothing measured how recently the
 * <em>audience</em> had last been spoken to, so two companions running two different pools could
 * — and in the 2026-09-06 field log did — land three lines on one player inside a single second,
 * and a soul scene could start nine seconds after the previous one ended. The floor is the one
 * piece of shared state that all lanes consult: while it is closed, nobody speaks to that
 * audience.
 *
 * <p>The floor is deliberately <em>not</em> a queue. A scripted ambient line that arrives while
 * the floor is closed is dropped, not deferred; deferring would only move the pile-up a few
 * seconds later. Scenes outrank scripted flavour: a scene line arms a longer floor than a
 * scripted line, and the end of a scene arms the longest floor of all so the player gets a real
 * beat of quiet before the ambient chatter resumes.
 *
 * <p><b>Two slots per audience (1.1.217).</b> Besides the speech floor itself (a deadline plus
 * the {@link Source} that armed it) an audience carries a separate <em>pending reservation</em>
 * deadline, armed by {@link Source#SOUL_SCENE_PENDING} when a soul scene fires and held while
 * its lines are being generated. The reservation is kept out of the speech slot on purpose: it is
 * not speech, it must be releasable when the generation fails, and releasing it must never erase
 * a real speech floor underneath it — {@code SoulLocalDirector} fires without consulting the
 * floor, so a reservation can be taken in the middle of a post-scene quiet, and folding the two
 * into one monotonic deadline would make "release the reservation" also mean "drop the quiet".
 */
public final class SpeechFloorPolicy {

    /**
     * Which lane armed the floor. The source determines only how long the floor stays closed —
     * every lane reads the same floor, so the policy stays lane-agnostic (an IDLE-lane scene and
     * an ACTIVE-lane scene arm the identical floor, and neither is coupled to the other's toggle).
     */
    public enum Source {
        /** A scripted ambient/reaction line (pet proximity, context reactions). */
        SCRIPTED_AMBIENT("scripted"),
        /** One delivered line of a soul group scene. */
        SOUL_SCENE_LINE("scene-line"),
        /** A soul group scene reaching its finish site. */
        SOUL_SCENE_END("scene-end"),
        /**
         * A soul scene that has fired but has not delivered its first line yet — the 5–16 s of
         * model generation between {@code outcome=fired} and line 1 (1.1.217 gap C1). A
         * reservation, not speech: it lives in its own slot, closes the floor for scripted lanes
         * only (scenes are already serialised by {@code SoulRuntime.isSceneBudgetFree}), and is
         * superseded by the scene's first delivered line or released when the scene fails.
         */
        SOUL_SCENE_PENDING("scene-pending");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /** Stable spelling for logs, e.g. {@code vetoed:speech-floor armedBy=scene-pending}. */
        public String label() {
            return label;
        }
    }

    /**
     * Floor armed by a single scripted ambient line: 4s.
     *
     * <p>Long enough that a second bot's reaction to the same stimulus (both companions notice the
     * same wolf on the same tick) cannot land on top of the first, and long enough to cover the
     * overhead-line display window plus its voice playback for a one-sentence line. Short enough
     * that two genuinely independent reactions a handful of seconds apart both still get through —
     * scripted flavour is meant to feel alive, not rationed.
     */
    public static final long SCRIPTED_LINE_FLOOR_MS = 4_000L;

    /**
     * Minimum floor armed by each delivered soul scene line: 6s.
     *
     * <p>Longer than a scripted line because a scene line is model-written and typically longer
     * than the one-sentence scripted pool entries, and because the scene's own per-line pacing
     * already leaves a gap the scripted lanes must not fill. This is what stops a scripted line
     * from being wedged between two lines of the same conversation. A voiced line that holds the
     * scene longer than this arms a longer floor — see {@link #armDurationMs}.
     */
    public static final long SCENE_LINE_FLOOR_MS = 6_000L;

    /**
     * Slack added on top of a scene line's expected hold (1.1.217 gap C2).
     *
     * <p>{@code GroupScenePlayback} holds the next line for the audio duration plus its line gap,
     * but the next line also waits for its own synthesis to settle and for the next server tick,
     * so it lands a little after the hold expires. Without this slack a floor armed for exactly
     * the hold reopens in the instant before the next line — the 8 s line-to-line gap in the
     * 1.1.216 field log reopened a fixed 6 s floor two seconds early.
     */
    public static final long SCENE_LINE_HOLD_MARGIN_MS = 1_500L;

    /**
     * Floor armed when a scene finishes: 20s.
     *
     * <p>The scene occupancy flag ({@code SoulRuntime.isSceneBudgetFree}) clears the instant the
     * scene's finish site runs, which is why a second scene could begin nine seconds after the
     * first ended. This is the post-scene quiet period that occupancy alone never provided: after
     * a conversation the audience gets twenty seconds of silence from every lane before anything
     * else may speak.
     */
    public static final long POST_SCENE_QUIET_MS = 20_000L;

    /**
     * Ceiling on a hold-aware scene-line floor: one post-scene quiet window. A pathological audio
     * duration (a mis-reported sample count) must not mute the scripted lanes for longer than a
     * whole finished conversation would.
     */
    public static final long MAX_SCENE_LINE_FLOOR_MS = POST_SCENE_QUIET_MS;

    /**
     * Ceiling on a pending-scene reservation: 30s.
     *
     * <p>Longer than a typical generation (5–16 s observed) so the reservation normally survives
     * until the first line supersedes it, but fixed from fire time and never re-armed, so a lost
     * release — a future that never completes, a scene dropped at enqueue — holds the scripted
     * lanes off for at most this long. This is the longest single arm any source can make.
     */
    public static final long SCENE_PENDING_FLOOR_MS = 30_000L;

    /**
     * Sanity horizon for a stored deadline: twice the longest single arm
     * ({@link #SCENE_PENDING_FLOOR_MS}).
     *
     * <p>No source can arm further out than {@link #SCENE_PENDING_FLOOR_MS}, so a stored value
     * further into the future than that plus one more such window cannot have been written by
     * this policy against the current clock — it is a wall-clock jump (NTP correction, a suspended
     * laptop resuming, a save carried between machines). Rather than muting an audience for
     * however long the bogus value says, such a value is clamped back to a single quiet window
     * from now.
     */
    public static final long MAX_FLOOR_HORIZON_MS = 2L * SCENE_PENDING_FLOOR_MS;

    private SpeechFloorPolicy() {
    }

    /** How long {@code source} closes the floor for (minimum, for a scene line). Null arms nothing. */
    public static long floorDurationMs(Source source) {
        if (source == null) {
            return 0L;
        }
        return switch (source) {
            case SCRIPTED_AMBIENT -> SCRIPTED_LINE_FLOOR_MS;
            case SOUL_SCENE_LINE -> SCENE_LINE_FLOOR_MS;
            case SOUL_SCENE_END -> POST_SCENE_QUIET_MS;
            case SOUL_SCENE_PENDING -> SCENE_PENDING_FLOOR_MS;
        };
    }

    /**
     * How long {@code source} closes the floor when its speaker is known to hold the audience for
     * {@code holdMs} (1.1.217 gap C2).
     *
     * <p>Only {@link Source#SOUL_SCENE_LINE} uses the hold: the floor covers
     * {@code hold + SCENE_LINE_HOLD_MARGIN_MS}, never less than {@link #SCENE_LINE_FLOOR_MS} and
     * never more than {@link #MAX_SCENE_LINE_FLOOR_MS}. Every other source ignores it. A
     * non-positive hold means "unknown" and yields the fixed per-source duration.
     */
    public static long armDurationMs(Source source, long holdMs) {
        if (source != Source.SOUL_SCENE_LINE) {
            return floorDurationMs(source);
        }
        long hold = Math.min(Math.max(0L, holdMs), MAX_SCENE_LINE_FLOOR_MS);
        return Math.max(SCENE_LINE_FLOOR_MS,
                Math.min(hold + SCENE_LINE_HOLD_MARGIN_MS, MAX_SCENE_LINE_FLOOR_MS));
    }

    /**
     * Normalises a stored deadline against the current clock: a non-positive value means
     * "no floor was ever armed", and a value beyond {@link #MAX_FLOOR_HORIZON_MS} is treated as
     * clock corruption and pulled back to one quiet window from now.
     */
    public static long sanitize(long nowMs, long busyUntilMs) {
        if (busyUntilMs <= 0L) {
            return 0L;
        }
        if (busyUntilMs > nowMs + MAX_FLOOR_HORIZON_MS) {
            return nowMs + POST_SCENE_QUIET_MS;
        }
        return busyUntilMs;
    }

    /**
     * True while the audience may be spoken to. The boundary tick counts as open:
     * {@code nowMs == busyUntilMs} means the floor has just expired.
     */
    public static boolean isOpen(long nowMs, long busyUntilMs) {
        return nowMs >= sanitize(nowMs, busyUntilMs);
    }

    /**
     * True for the sources that carry actual soul-scene speech, which outrank scripted flavour.
     * {@link Source#SOUL_SCENE_PENDING} is deliberately excluded: it is a reservation, never a
     * requester, and never speech.
     */
    public static boolean isSceneSource(Source source) {
        return source == Source.SOUL_SCENE_LINE || source == Source.SOUL_SCENE_END;
    }

    /**
     * True for a floor owner that a scene may speak through: scripted flavour, and a pending
     * scene reservation (the reservation exists to hold the scripted lanes off, not other scenes —
     * scene-vs-scene ordering is {@code SoulRuntime.isSceneBudgetFree}'s job, and a reservation
     * must never veto the very scene that took it).
     */
    public static boolean isPreemptibleByScene(Source armedBy) {
        return armedBy == Source.SCRIPTED_AMBIENT || armedBy == Source.SOUL_SCENE_PENDING;
    }

    /**
     * Source-aware floor query over the speech slot alone: may {@code requesting} speak, given a
     * floor armed by {@code armedBy}?
     *
     * <p>Why this exists (1.1.216 review finding 1): {@code SoulBanterDirector} evaluates the
     * banter lanes once every 100 ticks and a floor veto does not consume its
     * {@code nextEligibleAtMs}. A scripted ambient line arms a 4s floor, so a steady scripted
     * stream — two companions taking turns near a pen full of animals — could close the floor at
     * every single 5s evaluation instant and lock the soul lane out indefinitely, logging nothing
     * but {@code vetoed:speech-floor}. A symmetric floor starves the lane it was meant to protect.
     *
     * <p>The ordering is therefore explicit rather than symmetric: <b>a scene preempts scripted
     * flavour.</b> A request from {@link Source#SOUL_SCENE_LINE} or {@link Source#SOUL_SCENE_END}
     * ignores a floor whose owner {@link #isPreemptibleByScene}; a request from
     * {@code SCRIPTED_AMBIENT} respects every armed floor, scene floors included. Note this is
     * only about who may START speaking — the monotonic {@link #armedUntil} is untouched, so a
     * scene's floor still cannot be shortened by a later scripted arm.
     */
    public static boolean isOpenFor(long nowMs, long busyUntilMs, Source armedBy, Source requesting) {
        if (isOpen(nowMs, busyUntilMs)) {
            return true;
        }
        return isSceneSource(requesting) && isPreemptibleByScene(armedBy);
    }

    /**
     * Full floor query: the speech slot as {@link #isOpenFor(long, long, Source, Source)}, plus
     * the pending-scene reservation, which closes the floor for every requester except a scene.
     * A null requester is not a scene and so respects the reservation too.
     */
    public static boolean isOpenFor(long nowMs, long busyUntilMs, Source armedBy,
                                    long pendingUntilMs, Source requesting) {
        if (!isOpenFor(nowMs, busyUntilMs, armedBy, requesting)) {
            return false;
        }
        return isSceneSource(requesting) || isOpen(nowMs, pendingUntilMs);
    }

    /**
     * Which source is holding the floor closed for {@code requesting}, or null when it is open.
     * For the veto log's {@code armedBy=} field. When both the speech slot and the reservation
     * are closed, the one that reopens LATER is named — that is the one the requester is really
     * waiting on. A speech slot whose owner was never recorded also yields null.
     */
    public static Source closedBy(long nowMs, long busyUntilMs, Source armedBy,
                                  long pendingUntilMs, Source requesting) {
        boolean speechClosed = !isOpenFor(nowMs, busyUntilMs, armedBy, requesting);
        boolean pendingClosed = !isSceneSource(requesting) && !isOpen(nowMs, pendingUntilMs);
        if (!pendingClosed) {
            return speechClosed ? armedBy : null;
        }
        if (!speechClosed) {
            return Source.SOUL_SCENE_PENDING;
        }
        return sanitize(nowMs, pendingUntilMs) > sanitize(nowMs, busyUntilMs)
                ? Source.SOUL_SCENE_PENDING
                : armedBy;
    }

    /** Log spelling of {@code source}; {@code "none"} for null. */
    public static String label(Source source) {
        return source == null ? "none" : source.label();
    }

    /**
     * The new speech-slot deadline after {@code source} speaks at {@code nowMs}.
     *
     * <p>Never shortens an existing floor: a scripted line arriving in the middle of a 20s
     * post-scene quiet does not cut that quiet down to 4s. A longer floor always wins, which is
     * how "a scene outranks scripted ambient" is enforced without any lane needing to know about
     * the other lanes. {@link Source#SOUL_SCENE_PENDING} is not speech and leaves the speech slot
     * as it was (see {@link #pendingAfter}).
     */
    public static long armedUntil(long nowMs, long currentBusyUntilMs, Source source) {
        return armedUntil(nowMs, currentBusyUntilMs, source, 0L);
    }

    /** Hold-aware {@link #armedUntil(long, long, Source)}; the arm length is {@link #armDurationMs}. */
    public static long armedUntil(long nowMs, long currentBusyUntilMs, Source source, long holdMs) {
        long existing = sanitize(nowMs, currentBusyUntilMs);
        if (source == Source.SOUL_SCENE_PENDING) {
            return existing;
        }
        return Math.max(existing, nowMs + armDurationMs(source, holdMs));
    }

    /**
     * Which source owns the speech slot after {@code incoming} arms at {@code nowMs}.
     *
     * <p>Kept in lockstep with {@link #armedUntil}: whoever set the surviving (later) deadline is
     * the one recorded, so {@link #isOpenFor} can never be handed a deadline that belongs to one
     * source and a label that belongs to another. A tie goes to the incoming source — the newer
     * arm is at least as long, so it fully covers the old one. A null or pending incoming source
     * does not touch the speech slot and changes no label.
     */
    public static Source armedBySource(long nowMs, long currentBusyUntilMs, Source currentArmedBy,
                                       Source incoming) {
        return armedBySource(nowMs, currentBusyUntilMs, currentArmedBy, incoming, 0L);
    }

    /** Hold-aware {@link #armedBySource(long, long, Source, Source)} — pass the same hold as to {@link #armedUntil}. */
    public static Source armedBySource(long nowMs, long currentBusyUntilMs, Source currentArmedBy,
                                       Source incoming, long holdMs) {
        if (incoming == null || incoming == Source.SOUL_SCENE_PENDING) {
            return currentArmedBy;
        }
        long existing = sanitize(nowMs, currentBusyUntilMs);
        long fresh = nowMs + armDurationMs(incoming, holdMs);
        return fresh >= existing ? incoming : currentArmedBy;
    }

    /**
     * The pending-reservation deadline after {@code incoming} arms at {@code nowMs}.
     *
     * <ul>
     *   <li>{@link Source#SOUL_SCENE_PENDING}: a {@link #SCENE_PENDING_FLOOR_MS} reservation from
     *       now (never shortening one already held).</li>
     *   <li>A scene source ({@link #isSceneSource}): 0 — the scene is actually speaking now, so
     *       its own line/end floor supersedes the reservation. This is what stops a 30 s
     *       reservation from outliving the scene's first line.</li>
     *   <li>Anything else (scripted, null): unchanged.</li>
     * </ul>
     */
    public static long pendingAfter(long nowMs, long currentPendingUntilMs, Source incoming) {
        long existing = sanitize(nowMs, currentPendingUntilMs);
        if (incoming == Source.SOUL_SCENE_PENDING) {
            return Math.max(existing, nowMs + SCENE_PENDING_FLOOR_MS);
        }
        if (isSceneSource(incoming)) {
            return 0L;
        }
        return existing;
    }

    /** Milliseconds until the floor reopens, clamped at 0. For logging only. */
    public static long remainingMs(long nowMs, long busyUntilMs) {
        return Math.max(0L, sanitize(nowMs, busyUntilMs) - nowMs);
    }
}
