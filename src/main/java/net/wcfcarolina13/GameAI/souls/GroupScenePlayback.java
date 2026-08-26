package net.wcfcarolina13.GameAI.souls;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceGate;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Tick-driven playback of validated group scenes: one line at a time, in speaker order, each
 * text line synced to its speaker's positional audio (or a length-scaled beat when voice is
 * unavailable), fanned out to every player in earshot of the speaking bot at that moment.
 *
 * <p>All Minecraft-facing work (player resolution, chat fan-out, payload sends) happens inside
 * {@link #tick()} on the server thread; synthesis runs on the voice worker via
 * {@link SoulVoiceService#synthesizeLine} and is awaited by polling its future between ticks —
 * never by blocking. The next line's synthesis starts as soon as the previous line is dispatched,
 * so rendering overlaps playback. Per the group-chat spec, a line commits to the party transcript
 * (via {@link LineCommitter#commitLine}) only after its fan-out actually happened; skipped or
 * aborted lines never commit.
 */
public final class GroupScenePlayback {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    /** How far a scene line carries, matching the LOCAL soul-reachability radius. */
    static final double EARSHOT_BLOCKS = 32.0;
    /** Breathing room appended after each voiced line's audio duration. */
    static final long LINE_GAP_MS = 350L;

    /** Per-line delivery decision, in strict precedence order. */
    enum Step { WAIT, DELIVER, SKIP, ABORT, FINISH }

    /** Commit sink — implemented by {@code SoulGroupConversationService}. */
    public interface LineCommitter {
        void commitLine(SoulTypes.TurnToken token, int participantIndex, String taggedLine);

        void sceneFinished(SoulTypes.TurnToken token, int deliveredLines, int totalLines);
    }

    /** A validated scene ready to play. */
    public record PlayableScene(SoulGroupTypes.GroupSceneTurn turn, SoulTypes.TurnToken token,
                                 List<SoulGroupTypes.SceneLine> lines) {
        public PlayableScene {
            Objects.requireNonNull(turn, "turn");
            Objects.requireNonNull(token, "token");
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    private static final class SceneState {
        final PlayableScene scene;
        int lineIndex;
        int delivered;
        long notBeforeMs;
        CompletableFuture<Optional<SoulVoiceService.SynthesizedLine>> synth;
        long synthStartedMs;
        volatile boolean cancelled;

        SceneState(PlayableScene scene) {
            this.scene = scene;
        }
    }

    private final MinecraftServer server;
    /** Reads the CURRENT pipeline's voice service — pipelines are swapped by settings reloads. */
    private final Supplier<SoulVoiceService> voice;
    private final SoulVoiceService.VoiceDelivery voiceDelivery;
    private final LineCommitter committer;
    private final LongSupplier clock;
    /** Ambient-category gates, consulted for ambient (banter, local) scenes only (spec D6); re-read per line. */
    private final java.util.function.BooleanSupplier ambientTextAllowed;
    private final java.util.function.BooleanSupplier ambientVoiceAllowed;
    private final Map<UUID, SceneState> scenes = new ConcurrentHashMap<>();

    public GroupScenePlayback(MinecraftServer server, Supplier<SoulVoiceService> voice,
                               SoulVoiceService.VoiceDelivery voiceDelivery, LineCommitter committer) {
        this(server, voice, voiceDelivery, committer, System::currentTimeMillis,
                () -> true, () -> true);
    }

    public GroupScenePlayback(MinecraftServer server, Supplier<SoulVoiceService> voice,
                               SoulVoiceService.VoiceDelivery voiceDelivery, LineCommitter committer,
                               java.util.function.BooleanSupplier ambientTextAllowed,
                               java.util.function.BooleanSupplier ambientVoiceAllowed) {
        this(server, voice, voiceDelivery, committer, System::currentTimeMillis,
                ambientTextAllowed, ambientVoiceAllowed);
    }

    GroupScenePlayback(MinecraftServer server, Supplier<SoulVoiceService> voice,
                        SoulVoiceService.VoiceDelivery voiceDelivery, LineCommitter committer,
                        LongSupplier clock) {
        this(server, voice, voiceDelivery, committer, clock, () -> true, () -> true);
    }

    GroupScenePlayback(MinecraftServer server, Supplier<SoulVoiceService> voice,
                        SoulVoiceService.VoiceDelivery voiceDelivery, LineCommitter committer,
                        LongSupplier clock, java.util.function.BooleanSupplier ambientTextAllowed,
                        java.util.function.BooleanSupplier ambientVoiceAllowed) {
        this.server = server;
        this.voice = Objects.requireNonNull(voice, "voice");
        this.voiceDelivery = Objects.requireNonNull(voiceDelivery, "voiceDelivery");
        this.committer = Objects.requireNonNull(committer, "committer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ambientTextAllowed = Objects.requireNonNull(ambientTextAllowed, "ambientTextAllowed");
        this.ambientVoiceAllowed = Objects.requireNonNull(ambientVoiceAllowed, "ambientVoiceAllowed");
    }

    /** Thread-safe; called from the group service's provider-completion thread. */
    public void enqueue(PlayableScene scene) {
        SceneState previous = scenes.putIfAbsent(scene.turn().ownerId(), new SceneState(scene));
        if (previous != null) {
            // Guarded upstream by hasActiveScene; defensive only.
            LOGGER.warn("[souls] scene-playback owner={} already has an active scene; dropping",
                    scene.turn().ownerId());
        }
    }

    public boolean hasActiveScene(UUID ownerId) {
        return scenes.containsKey(ownerId);
    }

    /** Scenes currently playing or queued — feeds {@code SoulRuntime.activeGenerations()}. */
    public int activeSceneCount() {
        return scenes.size();
    }

    /** Marks the owner's scene cancelled; remaining lines are aborted on the next tick. */
    public void cancelOwner(UUID ownerId) {
        SceneState state = scenes.get(ownerId);
        if (state != null) {
            state.cancelled = true;
        }
    }

    public void cancelAll() {
        for (SceneState state : scenes.values()) {
            state.cancelled = true;
        }
    }

    /** Server-thread only (registered on END_SERVER_TICK via {@code SoulRuntime.tickScenes}). */
    public void tick() {
        if (scenes.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        for (SceneState state : scenes.values()) {
            advance(state, now);
        }
    }

    private void advance(SceneState state, long now) {
        PlayableScene scene = state.scene;
        List<SoulGroupTypes.SceneLine> lines = scene.lines();
        boolean ambientKind = scene.turn().kind().isAmbient();

        // Synthesis for the current line starts as soon as the line becomes current — it renders
        // while the previous line's audio is still playing out. An ambient line whose voice
        // surface is muted skips the render entirely (no wasted GPU window).
        boolean linesRemain = state.lineIndex < lines.size();
        if (linesRemain && !state.cancelled && state.synth == null) {
            SoulGroupTypes.SceneLine line = lines.get(state.lineIndex);
            SoulGroupTypes.SceneParticipant speaker = scene.turn().roster().get(line.participantIndex());
            state.synth = ambientKind && !ambientVoiceAllowed.getAsBoolean()
                    ? CompletableFuture.completedFuture(Optional.empty())
                    : voice.get().synthesizeLine(speaker.profileId(), line.text());
            state.synthStartedMs = now;
        }

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(scene.turn().ownerId());
        SoulGroupTypes.SceneLine line = linesRemain ? lines.get(state.lineIndex) : null;
        ServerPlayerEntity speakerBot = line == null ? null
                : server.getPlayerManager().getPlayer(scene.turn().roster().get(line.participantIndex()).botId());

        // Stale-facts rule (banter spec §5, extended to local): combat involving the audience or
        // the current speaker cancels the remaining ambient lines; player scenes are unaffected.
        if (ambientCombatAbort(ambientKind, inCombat(owner), inCombat(speakerBot)) && !state.cancelled) {
            state.cancelled = true;
            LOGGER.info("[souls] scene-playback routingId={} outcome=ambient-combat-abort",
                    scene.turn().routingId());
        }

        boolean pacingReady = now >= state.notBeforeMs;
        boolean settled = state.synth != null
                && synthSettled(state.synth.isDone(), state.synthStartedMs, now, voice.get().synthGuardMs());
        boolean botDeliverable = speakerBot != null && !speakerBot.isRemoved() && speakerBot.isAlive();

        Step step = decideStep(state.cancelled, linesRemain, owner != null, botDeliverable,
                pacingReady, settled);
        switch (step) {
            case WAIT -> { }
            case FINISH -> finish(state, "finished");
            case ABORT -> finish(state, state.cancelled ? "cancelled" : "owner-offline");
            case SKIP -> {
                LOGGER.info("[souls] scene-playback routingId={} line={}/{} outcome=skipped-speaker-gone",
                        scene.turn().routingId(), state.lineIndex + 1, lines.size());
                advanceToNextLine(state, now, 0L);
            }
            case DELIVER -> deliver(state, line, speakerBot, now);
        }
    }

    private void deliver(SceneState state, SoulGroupTypes.SceneLine line,
                          ServerPlayerEntity speakerBot, long now) {
        PlayableScene scene = state.scene;
        boolean ambientKind = scene.turn().kind().isAmbient();
        SoulGroupTypes.SceneParticipant speaker = scene.turn().roster().get(line.participantIndex());
        Optional<SoulVoiceService.SynthesizedLine> audio =
                state.synth.getNow(Optional.empty());

        LineSurfaces surfaces = lineSurfaces(ambientKind, ambientTextAllowed.getAsBoolean(),
                ambientVoiceAllowed.getAsBoolean(), audio.isPresent());
        if (surfaces.skip()) {
            // Both ambient surfaces muted mid-scene: the line is neither shown nor committed.
            LOGGER.info("[souls] scene-playback routingId={} line={}/{} outcome=skipped-muted",
                    scene.turn().routingId(), state.lineIndex + 1, scene.lines().size());
            advanceToNextLine(state, now, 0L);
            return;
        }

        List<ServerPlayerEntity> listeners = playersInEarshot(speakerBot);
        if (surfaces.text()) {
            Text chatLine = Text.literal(speaker.displayName() + ": " + line.text());
            for (ServerPlayerEntity listener : listeners) {
                listener.sendMessage(chatLine, false);
            }
        }
        if (surfaces.audio()) {
            UUID groupId = lineGroupId(scene.turn().routingId(), state.lineIndex);
            for (ServerPlayerEntity listener : listeners) {
                voiceDelivery.send(listener.getUuid(), groupId, speaker.botId(),
                        SoulVoiceGate.Mode.POSITIONAL, audio.get().sampleRate(),
                        audio.get().chunks(), groupId, 0);
            }
        }
        committer.commitLine(scene.token(), line.participantIndex(),
                speaker.displayName() + ": " + line.text());
        state.delivered++;

        LOGGER.info("[souls] scene-playback routingId={} line={}/{} speaker={} text={} voiced={} listeners={}",
                scene.turn().routingId(), state.lineIndex + 1, scene.lines().size(),
                speaker.botId(), surfaces.text(), surfaces.audio(), listeners.size());
        advanceToNextLine(state, now,
                lineDurationMs(surfaces.audio() ? audio : Optional.empty(), line.text().length()));
    }

    private static boolean inCombat(ServerPlayerEntity entity) {
        return entity != null && (entity.hurtTime > 0 || entity.getAttacker() != null);
    }

    private void advanceToNextLine(SceneState state, long now, long holdMs) {
        state.lineIndex++;
        state.synth = null;
        state.notBeforeMs = now + holdMs;
    }

    private void finish(SceneState state, String outcome) {
        scenes.remove(state.scene.turn().ownerId());
        LOGGER.info("[souls] scene-playback routingId={} outcome={} delivered={}/{}",
                state.scene.turn().routingId(), outcome, state.delivered, state.scene.lines().size());
        committer.sceneFinished(state.scene.token(), state.delivered, state.scene.lines().size());
    }

    private List<ServerPlayerEntity> playersInEarshot(ServerPlayerEntity speaker) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getEntityWorld() == speaker.getEntityWorld()
                    && player.squaredDistanceTo(speaker) <= EARSHOT_BLOCKS * EARSHOT_BLOCKS) {
                out.add(player);
            }
        }
        return out;
    }

    // === Pure helpers (unit-tested) ===

    /** Text-only pacing: 1.5–2.5s scaled by line length. */
    static long beatMsFor(int textLength) {
        return Math.min(2_500L, 1_500L + 4L * Math.max(0, textLength));
    }

    /** How long the scene holds after dispatching a line before the next may deliver. */
    static long lineDurationMs(Optional<SoulVoiceService.SynthesizedLine> audio, int textLength) {
        return audio.map(line -> line.durationMs() + LINE_GAP_MS).orElseGet(() -> beatMsFor(textLength));
    }

    /**
     * Deterministic per-line voice group id derived from the scene's routingId. Each line gets its
     * OWN group (its own client audio source, positioned at its speaker) — cross-line ordering is
     * enforced here by pacing, not by the client queue. A distinct multiplier from
     * {@link SoulVoiceService#segmentCorrelationId} keeps the two id families disjoint.
     */
    static UUID lineGroupId(UUID routingId, int lineIndex) {
        return new UUID(routingId.getMostSignificantBits() ^ (0xC2B2AE3D27D4EB4FL * (lineIndex + 1)),
                routingId.getLeastSignificantBits() ^ 0x165667B19E3779F9L);
    }

    /** A synthesis future counts as settled once done, or once the wall-clock guard has expired
     *  (queue-full drops never complete their future). */
    static boolean synthSettled(boolean done, long startedAtMs, long nowMs, long guardMs) {
        return done || nowMs - startedAtMs > guardMs;
    }

    /** Which surfaces a line may use. {@code skip} == an ambient kind with neither surface open. */
    record LineSurfaces(boolean text, boolean audio, boolean skip) {}

    /** Player scenes always show text (soul exemption) and voice whatever audio exists; ambient
     *  kinds (banter and local) respect the ambient masks per surface and skip entirely when both
     *  are closed. */
    static LineSurfaces lineSurfaces(boolean ambientKind, boolean textAllowed,
                                     boolean voiceAllowed, boolean audioPresent) {
        if (!ambientKind) {
            return new LineSurfaces(true, audioPresent, false);
        }
        boolean text = textAllowed;
        boolean audio = voiceAllowed && audioPresent;
        return new LineSurfaces(text, audio, !text && !audio);
    }

    /** Ambient-kinds-only (banter and local) stale-facts rule: any combat involving audience or
     *  speaker cancels the scene. */
    static boolean ambientCombatAbort(boolean ambientKind, boolean ownerInCombat, boolean speakerInCombat) {
        return ambientKind && (ownerInCombat || speakerInCombat);
    }

    /**
     * Per-line decision, strict precedence: a cancelled scene aborts; an exhausted scene
     * finishes; pacing and synthesis both gate delivery; a vanished owner aborts the scene; a
     * vanished speaker skips only that line.
     */
    static Step decideStep(boolean cancelled, boolean linesRemain, boolean ownerOnline,
                            boolean botDeliverable, boolean pacingReady, boolean synthSettled) {
        if (cancelled) {
            return Step.ABORT;
        }
        if (!linesRemain) {
            return Step.FINISH;
        }
        if (!pacingReady || !synthSettled) {
            return Step.WAIT;
        }
        if (!ownerOnline) {
            return Step.ABORT;
        }
        if (!botDeliverable) {
            return Step.SKIP;
        }
        return Step.DELIVER;
    }
}
