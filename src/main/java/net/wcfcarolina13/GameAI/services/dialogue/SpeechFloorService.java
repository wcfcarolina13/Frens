package net.wcfcarolina13.GameAI.services.dialogue;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live state for the cross-lane speech floor. Every decision is delegated to
 * {@link SpeechFloorPolicy}; this class only remembers, per audience, when the floor reopens,
 * which lane armed it, and until when a fired soul scene holds a pending reservation.
 *
 * <p>Keyed by <b>audience</b> — the player the speech is aimed at — not by bot. Two companions
 * addressing the same player share one floor, which is exactly the pile-up being fixed; two
 * companions addressing different players are unaffected.
 *
 * <p><b>A null audience is not a floor.</b> Callers resolve the audience through
 * {@code CompanionCommunicationPolicy.resolveController}; when that yields nobody (an unowned bot,
 * an owner who is offline) there is no audience to protect, so the floor is skipped entirely —
 * neither checked nor armed. An earlier revision keyed such speech onto a shared
 * {@code new UUID(0,0)} sentinel, which had two bugs: the scripted lanes keyed the sentinel while
 * the soul lanes keyed the real player id, so the floor never actually met itself, and every
 * unowned bot on the server shared one floor regardless of who was listening.
 *
 * <p><b>Pending reservation (1.1.217).</b> {@code SoulBanterDirector} and
 * {@code SoulLocalDirector} arm {@link SpeechFloorPolicy.Source#SOUL_SCENE_PENDING} when a scene
 * fires, closing the floor to the scripted lanes for the model-generation window. The
 * reservation is superseded automatically by the scene's first delivered line (any scene-source
 * arm clears it — see {@link SpeechFloorPolicy#pendingAfter}), released by
 * {@link #releasePending} when the submission fails or the scene finishes without a line, and in
 * the worst case expires on its own {@link SpeechFloorPolicy#SCENE_PENDING_FLOOR_MS} ceiling.
 *
 * <p>Threading: the scripted reaction services, {@code SoulBanterDirector.tick},
 * {@code SoulLocalDirector.noteUnaddressedChat} and {@code GroupScenePlayback.tick} all run on the
 * server tick thread, but {@code GroupScenePlayback.enqueue} runs on a provider worker, and the
 * directors' FAILED-submission release runs on whichever thread completes the submission future
 * (a provider worker, or the server thread itself when the future is already complete). So the
 * map is a {@link ConcurrentHashMap}, every write is a single atomic {@code merge} /
 * {@code computeIfPresent}, and this class touches no Minecraft state. The deadline, the source
 * that owns it and the reservation live in one immutable {@link Floor} value so a concurrent
 * write can never publish one lane's deadline under another lane's label.
 *
 * <p>Static, like the reaction services it gates, so it outlives a world: an integrated server
 * keeps mod statics across quit-and-reload in the same JVM. Lifecycle hooks in {@code Frens}:
 * {@link #clearAll()} runs in SERVER_STOPPING, and {@link #clear(UUID)} runs in the DISCONNECT
 * handler for real players only (an audience is the owning player a bot speaks to). Without them a reloaded world could
 * start behind a floor armed in the previous session — at most
 * {@code SCENE_PENDING_FLOOR_MS} (30 s), so this is housekeeping rather than a correctness fix.
 * The map is bounded by the number of audiences ever spoken to and stores one small record each.
 */
public final class SpeechFloorService {

    /**
     * One audience's floor: when the speech slot reopens and which lane armed that deadline, plus
     * the separate pending-scene reservation deadline. Kept as a single immutable value so the
     * triple stays consistent under a concurrent {@code merge} — splitting it into several maps
     * would let a reader see a scene's deadline labelled as scripted (or vice versa) and make
     * exactly the wrong preemption decision.
     */
    private record Floor(long busyUntilMs, SpeechFloorPolicy.Source armedBy, long pendingUntilMs) {
    }

    private static final Floor EMPTY = new Floor(0L, null, 0L);

    private static final ConcurrentHashMap<UUID, Floor> FLOORS = new ConcurrentHashMap<>();

    private SpeechFloorService() {
    }

    /**
     * True while {@code requesting} may speak to {@code audienceId}.
     *
     * <p>Source-aware: a soul scene preempts a floor armed by scripted ambient flavour and ignores
     * a pending-scene reservation, while a scripted line respects every armed floor and every
     * reservation. See {@link SpeechFloorPolicy#isOpenFor(long, long, SpeechFloorPolicy.Source,
     * long, SpeechFloorPolicy.Source)}. A null audience has no floor and is always open.
     */
    public static boolean isFloorOpen(UUID audienceId, SpeechFloorPolicy.Source requesting) {
        if (audienceId == null) {
            return true;
        }
        Floor floor = FLOORS.get(audienceId);
        if (floor == null) {
            return true;
        }
        return SpeechFloorPolicy.isOpenFor(System.currentTimeMillis(), floor.busyUntilMs(),
                floor.armedBy(), floor.pendingUntilMs(), requesting);
    }

    /**
     * Records that something was just said to {@code audienceId} and closes the floor for the
     * duration {@code source} carries. Never shortens a floor already armed by a longer source.
     * {@link SpeechFloorPolicy.Source#SOUL_SCENE_PENDING} arms the reservation slot instead of the
     * speech slot. A null audience arms nothing.
     */
    public static void noteSpeech(UUID audienceId, SpeechFloorPolicy.Source source) {
        noteSpeech(audienceId, source, 0L);
    }

    /**
     * {@link #noteSpeech(UUID, SpeechFloorPolicy.Source)} for a speaker known to hold the audience
     * for {@code holdMs} — a scene line passes its audio duration plus line gap so the floor stays
     * closed until the next line (see {@link SpeechFloorPolicy#armDurationMs}). Sources other than
     * {@link SpeechFloorPolicy.Source#SOUL_SCENE_LINE} ignore the hold.
     */
    public static void noteSpeech(UUID audienceId, SpeechFloorPolicy.Source source, long holdMs) {
        if (audienceId == null || source == null) {
            return;
        }
        long now = System.currentTimeMillis();
        FLOORS.merge(audienceId, arm(now, EMPTY, source, holdMs),
                (existing, ignored) -> arm(now, existing, source, holdMs));
    }

    private static Floor arm(long now, Floor existing, SpeechFloorPolicy.Source source, long holdMs) {
        return new Floor(
                SpeechFloorPolicy.armedUntil(now, existing.busyUntilMs(), source, holdMs),
                SpeechFloorPolicy.armedBySource(now, existing.busyUntilMs(), existing.armedBy(), source, holdMs),
                SpeechFloorPolicy.pendingAfter(now, existing.pendingUntilMs(), source));
    }

    /**
     * Drops {@code audienceId}'s pending-scene reservation, leaving the speech slot untouched — a
     * post-scene quiet or a scripted floor that was already running stays exactly as it was.
     * Called when a fired scene's submission fails and at every scene finish (a scene that
     * delivered nothing never supersedes its own reservation). A null or unknown audience, or one
     * with no reservation, is a no-op.
     */
    public static void releasePending(UUID audienceId) {
        if (audienceId == null) {
            return;
        }
        FLOORS.computeIfPresent(audienceId, (id, floor) -> floor.pendingUntilMs() <= 0L
                ? floor
                : new Floor(floor.busyUntilMs(), floor.armedBy(), 0L));
    }

    /**
     * Log label of whatever is holding {@code audienceId}'s floor closed for {@code requesting}
     * right now — {@code scripted}, {@code scene-line}, {@code scene-end} or {@code scene-pending}
     * — or {@code none} when it is open. For the veto logs' {@code armedBy=} field only.
     */
    public static String armedByLabel(UUID audienceId, SpeechFloorPolicy.Source requesting) {
        if (audienceId == null) {
            return SpeechFloorPolicy.label(null);
        }
        Floor floor = FLOORS.get(audienceId);
        if (floor == null) {
            return SpeechFloorPolicy.label(null);
        }
        return SpeechFloorPolicy.label(SpeechFloorPolicy.closedBy(System.currentTimeMillis(),
                floor.busyUntilMs(), floor.armedBy(), floor.pendingUntilMs(), requesting));
    }

    /**
     * Milliseconds until the floor reopens for {@code audienceId} to every lane — the later of the
     * speech slot and the pending reservation, which is what a vetoed scripted line is waiting
     * on — clamped at 0. Logging only.
     */
    public static long remainingMs(UUID audienceId) {
        if (audienceId == null) {
            return 0L;
        }
        Floor floor = FLOORS.get(audienceId);
        if (floor == null) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        return Math.max(SpeechFloorPolicy.remainingMs(now, floor.busyUntilMs()),
                SpeechFloorPolicy.remainingMs(now, floor.pendingUntilMs()));
    }

    /** Forgets one audience's floor and reservation (the audience may be spoken to immediately). */
    public static void clear(UUID audienceId) {
        if (audienceId == null) {
            return;
        }
        FLOORS.remove(audienceId);
    }

    /** Forgets every floor. */
    public static void clearAll() {
        FLOORS.clear();
    }
}
