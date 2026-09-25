package net.wcfcarolina13.GameAI.services.dialogue;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live state for the cross-lane speech floor. Every decision is delegated to
 * {@link SpeechFloorPolicy}; this class only remembers, per audience, when the floor reopens and
 * which lane armed it.
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
 * <p>Threading: the scripted reaction services, {@code SoulBanterDirector.tick} and
 * {@code GroupScenePlayback.tick} all run on the server tick thread, but
 * {@code GroupScenePlayback.enqueue} runs on a provider worker, so the map is a
 * {@link ConcurrentHashMap} and {@link #noteSpeech} arms through an atomic {@code merge}. The
 * deadline and the source that owns it live in one immutable {@link Floor} value so a concurrent
 * merge can never publish one lane's deadline under another lane's label.
 *
 * <p>Static, like the reaction services it gates, so it outlives a world: an integrated server
 * keeps mod statics across quit-and-reload in the same JVM. Lifecycle hooks in {@code Frens}:
 * {@link #clearAll()} runs in SERVER_STOPPING, and {@link #clear(UUID)} runs in the DISCONNECT
 * handler for real players only (an audience is the owning player a bot speaks to). Without them a reloaded world could
 * start behind a floor armed in the previous session — at most {@code POST_SCENE_QUIET_MS}
 * (20 s), so this is housekeeping rather than a correctness fix. The map is bounded by the number
 * of audiences ever spoken to and stores one small record each.
 */
public final class SpeechFloorService {

    /**
     * One audience's floor: when it reopens, and which lane armed that deadline. Kept as a single
     * immutable value so the pair stays consistent under a concurrent {@code merge} — splitting it
     * into two maps would let a reader see a scene's deadline labelled as scripted (or vice versa)
     * and make exactly the wrong preemption decision.
     */
    private record Floor(long busyUntilMs, SpeechFloorPolicy.Source armedBy) {
    }

    private static final ConcurrentHashMap<UUID, Floor> FLOORS = new ConcurrentHashMap<>();

    private SpeechFloorService() {
    }

    /**
     * True while {@code requesting} may speak to {@code audienceId}.
     *
     * <p>Source-aware: a soul scene preempts a floor armed by scripted ambient flavour, while a
     * scripted line respects every armed floor. See {@link SpeechFloorPolicy#isOpenFor}. A null
     * audience has no floor and is always open.
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
                floor.armedBy(), requesting);
    }

    /**
     * Records that something was just said to {@code audienceId} and closes the floor for the
     * duration {@code source} carries. Never shortens a floor already armed by a longer source.
     * A null audience arms nothing.
     */
    public static void noteSpeech(UUID audienceId, SpeechFloorPolicy.Source source) {
        if (audienceId == null || source == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Floor fresh = new Floor(SpeechFloorPolicy.armedUntil(now, 0L, source), source);
        FLOORS.merge(audienceId, fresh, (existing, ignored) -> new Floor(
                SpeechFloorPolicy.armedUntil(now, existing.busyUntilMs(), source),
                SpeechFloorPolicy.armedBySource(now, existing.busyUntilMs(), existing.armedBy(), source)));
    }

    /** Milliseconds until the floor reopens for {@code audienceId}, clamped at 0. Logging only. */
    public static long remainingMs(UUID audienceId) {
        if (audienceId == null) {
            return 0L;
        }
        Floor floor = FLOORS.get(audienceId);
        return floor == null
                ? 0L
                : SpeechFloorPolicy.remainingMs(System.currentTimeMillis(), floor.busyUntilMs());
    }

    /** Forgets one audience's floor (the audience may be spoken to immediately). */
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
