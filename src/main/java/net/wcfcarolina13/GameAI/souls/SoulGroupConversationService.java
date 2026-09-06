package net.wcfcarolina13.GameAI.souls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Orchestrates one group-scene (PARTY channel) turn: heard → one capped orchestration call →
 * roster-verified parse → handoff to the scene playback machine, which then commits each line
 * back through this class only after it was actually delivered.
 *
 * <p>Mirrors {@link SoulConversationService}'s promise-chain discipline against the party store:
 * the owner's message is always recorded HEARD first (speaker-tagged, so party history replays
 * verbatim); exactly one generation is scheduled through the shared 1-slot
 * {@link SoulGenerationScheduler} under the scene's {@code partyKey}; failures append a FAILURE
 * record when the token is still current and send exactly one deterministic plural status line to
 * the owner. Unlike the DM flow, a successful validation does not deliver anything here — the
 * playback machine owns delivery pacing, and SPOKEN records are appended per line via
 * {@link #commitLine} strictly after that line's fan-out (spec: only delivered lines enter the
 * transcript).
 */
public final class SoulGroupConversationService implements GroupScenePlayback.LineCommitter {

    // Dedicated logger, never Frens.LOGGER — same package rule as the rest of the pipeline.
    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    /** Outcome of {@link #submit}: the scene reached playback, or it failed before any line. */
    public enum Submission { SCENE_STARTED, FAILED }

    /** Playback boundary — production is {@link GroupScenePlayback}. */
    public interface ScenePlayer {
        void enqueue(GroupScenePlayback.PlayableScene scene);

        boolean hasActiveScene(UUID ownerId);
    }

    /** Deterministic owner-notice boundary — production forwards to the DM delivery's status path. */
    public interface StatusSink {
        void deliverStatus(UUID playerId, String text);
    }

    private final SoulStore partyStore;
    private final SoulGroupPromptAssembler prompts;
    private final SoulGenerationScheduler scheduler;
    private final SoulModelProvider provider;
    private final SoulGroupResponseValidator validator;
    private final SoulSettings settings;
    private final ScenePlayer player;
    private final StatusSink status;
    /** Scene provider metadata by correlationId, for per-line SPOKEN appends during playback. */
    private final Map<UUID, SoulTypes.ProviderResult> sceneResults = new ConcurrentHashMap<>();
    /**
     * Per-bot ring of recently delivered normalised lines (Phase 3d novelty rejection). Scenes for
     * different owners complete on different provider-future worker threads, so the map is
     * concurrent and each ring synchronizes internally. In-memory only — never persisted.
     */
    private final Map<UUID, SoulNoveltyPolicy.Ring> noveltyRings = new ConcurrentHashMap<>();
    /** Live read of {@code soulNoveltyRejectionEnabled}; default off keeps behaviour unchanged. */
    private final BooleanSupplier noveltyEnabled;

    /** Legacy 8-arg form: novelty rejection disabled (behaviour identical to pre-1.1.211). */
    public SoulGroupConversationService(SoulStore partyStore, SoulGroupPromptAssembler prompts,
                                         SoulGenerationScheduler scheduler, SoulModelProvider provider,
                                         SoulGroupResponseValidator validator, SoulSettings settings,
                                         ScenePlayer player, StatusSink status) {
        this(partyStore, prompts, scheduler, provider, validator, settings, player, status, () -> false);
    }

    public SoulGroupConversationService(SoulStore partyStore, SoulGroupPromptAssembler prompts,
                                         SoulGenerationScheduler scheduler, SoulModelProvider provider,
                                         SoulGroupResponseValidator validator, SoulSettings settings,
                                         ScenePlayer player, StatusSink status,
                                         BooleanSupplier noveltyEnabled) {
        this.noveltyEnabled = Objects.requireNonNull(noveltyEnabled, "noveltyEnabled");
        this.partyStore = Objects.requireNonNull(partyStore, "partyStore");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.player = Objects.requireNonNull(player, "player");
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Passthrough for party reset — invalidates queued/active scene generations. */
    public void invalidate(SoulTypes.ConversationKey key, long newEpoch) {
        scheduler.invalidate(key, newEpoch);
    }

    public CompletableFuture<Submission> submit(SoulGroupTypes.GroupSceneTurn turn) {
        Objects.requireNonNull(turn, "turn");
        UUID correlationId = turn.routingId();
        CompletableFuture<Submission> outcome = new CompletableFuture<>();

        if (player.hasActiveScene(turn.ownerId())) {
            // An autonomous ambient turn colliding with a live scene fails silently; a player
            // asking mid-scene gets told (they acted, they deserve feedback).
            statusUnlessAmbient(turn, "Your companions are still talking. Give them a moment.");
            outcome.complete(Submission.FAILED);
            return outcome;
        }

        long submitStartNanos = System.nanoTime();
        // BANTER and WORK: a synthetic narrator seed must never replay as a player utterance. A
        // LOCAL turn carries a real thing the player said in earshot, so it takes the ordinary
        // speaker-tagged form and replays normally.
        String taggedMessage = turn.kind().isNarratorSeeded()
                ? SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + turn.playerMessage()
                : turn.ownerDisplayName() + ": " + turn.playerMessage();
        partyStore.beginHeardTurn(turn.key(), correlationId, taggedMessage, turn.acceptedAt(),
                turn.roster().stream().map(SoulGroupTypes.SceneParticipant::botId).toList())
                .whenComplete((token, tokenError) -> {
                    if (tokenError != null) {
                        LOGGER.info("[souls] scene correlationId={} owner={} kind={} outcome=no-token error={}",
                                correlationId, turn.ownerId(), turn.kind(),
                                tokenError.getClass().getSimpleName());
                        statusUnlessAmbient(turn, statusFor(SoulTypes.FailureCode.INTERNAL));
                        outcome.complete(Submission.FAILED);
                        return;
                    }
                    continueAfterHeard(turn, token, correlationId, submitStartNanos, outcome);
                });
        return outcome;
    }

    /** Ambient scenes (banter, local) are system-initiated: their failures never surface to chat. */
    private void statusUnlessAmbient(SoulGroupTypes.GroupSceneTurn turn, String text) {
        if (!turn.kind().isAmbient()) {
            status.deliverStatus(turn.ownerId(), text);
        }
    }

    private void continueAfterHeard(SoulGroupTypes.GroupSceneTurn turn, SoulTypes.TurnToken token,
                                     UUID correlationId, long submitStartNanos,
                                     CompletableFuture<Submission> outcome) {
        partyStore.recentBefore(token, SoulGroupPromptAssembler.MAX_HISTORY_TURNS,
                        SoulGroupPromptAssembler.MAX_HISTORY_CHARS)
                .whenComplete((history, historyError) -> {
                    if (historyError != null) {
                        failTurn(turn, token, correlationId, SoulTypes.FailureCode.INTERNAL,
                                "", "", null, outcome);
                        return;
                    }
                    dispatchProvider(turn, token, correlationId, history, submitStartNanos, outcome);
                });
    }

    private void dispatchProvider(SoulGroupTypes.GroupSceneTurn turn, SoulTypes.TurnToken token,
                                   UUID correlationId, List<SoulTypes.ConversationRecord> history,
                                   long submitStartNanos, CompletableFuture<Submission> outcome) {
        List<SoulTypes.SoulProfile> profiles = new ArrayList<>(turn.roster().size());
        try {
            for (SoulGroupTypes.SceneParticipant participant : turn.roster()) {
                profiles.add(SoulProfileRegistry.require(participant.profileId()));
            }
        } catch (RuntimeException unknownProfile) {
            failTurn(turn, token, correlationId, SoulTypes.FailureCode.INTERNAL, "", "", null, outcome);
            return;
        }

        SoulTypes.ProviderRequest request = prompts.assemble(correlationId, settings.model(), turn,
                profiles, history, settings.timeout());
        int queueDepthAtSubmit = scheduler.queueDepth();
        scheduler.submit(turn.key(), token.epoch(), () -> provider.generate(request))
                .whenComplete((result, providerError) -> handleProviderResult(turn, token, correlationId,
                        result, providerError, queueDepthAtSubmit, submitStartNanos, outcome));
    }

    private void handleProviderResult(SoulGroupTypes.GroupSceneTurn turn, SoulTypes.TurnToken token,
                                       UUID correlationId, SoulTypes.ProviderResult result,
                                       Throwable providerError, int queueDepthAtSubmit,
                                       long submitStartNanos, CompletableFuture<Submission> outcome) {
        if (providerError != null) {
            failTurn(turn, token, correlationId, SoulTypes.FailureCode.INTERNAL, "", "", null, outcome);
            return;
        }
        if (!result.success()) {
            failTurn(turn, token, correlationId, result.failureCode(), result.provider(),
                    result.model(), result.elapsedMillis(), outcome);
            return;
        }

        List<String> rosterNames = new ArrayList<>(turn.roster().size());
        for (SoulGroupTypes.SceneParticipant participant : turn.roster()) {
            rosterNames.add(participant.displayName());
        }
        int maxSceneLines = switch (turn.kind()) {
            case BANTER, WORK -> SoulGroupTypes.BANTER_MAX_SCENE_LINES;
            case LOCAL -> SoulGroupTypes.LOCAL_MAX_SCENE_LINES;
            case PLAYER -> SoulGroupTypes.MAX_SCENE_LINES;
        };
        SoulGroupResponseValidator.SceneParse parse = validator.parse(result.text(), rosterNames, maxSceneLines,
                turn.ownerDisplayName(), turn.kind() != SoulGroupTypes.SceneKind.PLAYER);
        // Raw provider output is not persisted anywhere, so a rejected or solo-roster scene logs
        // it (whitespace-collapsed, capped). The 2026-08-29 self-talk diagnosis had to be
        // inferred from the delivered lines because this was invisible.
        if (!parse.accepted() || rosterNames.size() == 1) {
            LOGGER.info("[souls] scene correlationId={} kind={} rosterSize={} accepted={} raw=\"{}\"",
                    correlationId, turn.kind(), rosterNames.size(), parse.accepted(), rawForLog(result.text()));
        }
        if (!parse.accepted()) {
            failTurn(turn, token, correlationId, parse.failureCode(), result.provider(),
                    result.model(), result.elapsedMillis(), outcome);
            return;
        }

        List<SoulGroupTypes.SceneLine> lines = applyNovelty(turn, parse.lines());

        sceneResults.put(correlationId, result);
        player.enqueue(new GroupScenePlayback.PlayableScene(turn, token, lines));
        LOGGER.info("[souls] scene correlationId={} owner={} kind={} rosterSize={} lines={} queueDepth={} "
                        + "provider={} model={} providerMs={} totalMs={} outcome=scene-started",
                correlationId, turn.ownerId(), turn.kind(), turn.roster().size(), lines.size(),
                queueDepthAtSubmit, result.provider(), result.model(), result.elapsedMillis(),
                elapsedMs(submitStartNanos));
        outcome.complete(Submission.SCENE_STARTED);
    }

    /**
     * Phase 3d: drop scene lines that repeat what the speaker recently said (or that another bot
     * already said inside this same scene). The scene is always enqueued, even when every line is
     * dropped — the zero-delivery path is an explicit part of the {@code sceneDelivered} contract
     * and skipping the enqueue would strand the turn. Kept lines are remembered in their speaker's
     * ring only after they survive the filter. Ring snapshots are taken before filtering, so the
     * speaker's bot id is passed alongside each line and the policy also checks a candidate
     * against what that same bot already kept in this scene. With the toggle off this returns the
     * parsed lines unchanged and touches no state.
     */
    private List<SoulGroupTypes.SceneLine> applyNovelty(SoulGroupTypes.GroupSceneTurn turn,
                                                        List<SoulGroupTypes.SceneLine> parsed) {
        if (parsed.isEmpty() || !noveltyEnabled.getAsBoolean()) {
            return parsed;
        }
        List<String> texts = new ArrayList<>(parsed.size());
        List<List<String>> histories = new ArrayList<>(parsed.size());
        List<SoulNoveltyPolicy.Ring> rings = new ArrayList<>(parsed.size());
        List<Object> speakerKeys = new ArrayList<>(parsed.size());
        for (SoulGroupTypes.SceneLine line : parsed) {
            texts.add(line.text());
            SoulGroupTypes.SceneParticipant speaker = participantFor(turn, line.participantIndex());
            SoulNoveltyPolicy.Ring ring = speaker == null ? null
                    : noveltyRings.computeIfAbsent(speaker.botId(), id -> new SoulNoveltyPolicy.Ring());
            rings.add(ring);
            speakerKeys.add(speaker == null ? null : speaker.botId());
            histories.add(ring == null ? List.of() : ring.snapshot());
        }
        List<SoulNoveltyPolicy.Verdict> verdicts = SoulNoveltyPolicy.filter(texts, histories, speakerKeys);
        List<SoulGroupTypes.SceneLine> kept = new ArrayList<>(parsed.size());
        for (SoulNoveltyPolicy.Verdict verdict : verdicts) {
            SoulGroupTypes.SceneLine line = parsed.get(verdict.index());
            if (!verdict.kept()) {
                SoulGroupTypes.SceneParticipant speaker = participantFor(turn, line.participantIndex());
                LOGGER.info("[souls] novelty dropped bot={} reason={}",
                        speaker == null ? "?" : speaker.displayName(), verdict.reason());
                continue;
            }
            SoulNoveltyPolicy.Ring ring = rings.get(verdict.index());
            if (ring != null) {
                ring.remember(verdict.normalised());
            }
            kept.add(line);
        }
        return kept;
    }

    /** Roster participant for a validated line index, or null if the index is somehow out of range. */
    private static SoulGroupTypes.SceneParticipant participantFor(SoulGroupTypes.GroupSceneTurn turn, int index) {
        List<SoulGroupTypes.SceneParticipant> roster = turn.roster();
        return index >= 0 && index < roster.size() ? roster.get(index) : null;
    }

    private void failTurn(SoulGroupTypes.GroupSceneTurn turn, SoulTypes.TurnToken token,
                           UUID correlationId, SoulTypes.FailureCode code, String providerId,
                           String model, Long elapsedMillis, CompletableFuture<Submission> outcome) {
        String statusText = statusFor(code);
        boolean tokenAlreadyStale =
                code == SoulTypes.FailureCode.CANCELLED || code == SoulTypes.FailureCode.STALE_EPOCH;
        if (tokenAlreadyStale) {
            // Same rule as the DM flow: a reset already bumped the epoch; the store would refuse
            // this append as stale, so skip it rather than attempt-and-fail.
            statusUnlessAmbient(turn, statusText);
            logFailure(correlationId, turn, code, providerId, model);
            outcome.complete(Submission.FAILED);
            return;
        }
        partyStore.appendFailure(token, code, providerId, model, elapsedMillis)
                .whenComplete((v, appendError) -> {
                    statusUnlessAmbient(turn, statusText);
                    logFailure(correlationId, turn, code, providerId, model);
                    outcome.complete(Submission.FAILED);
                });
    }

    /** One log-safe line: newlines shown as " ⏎ ", whitespace collapsed, capped at 600 chars. */
    static String rawForLog(String raw) {
        if (raw == null) {
            return "";
        }
        String flat = raw.replace("\r", "").replace("\n", " ⏎ ").replaceAll("\\s+", " ").strip();
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "…";
    }

    private void logFailure(UUID correlationId, SoulGroupTypes.GroupSceneTurn turn,
                             SoulTypes.FailureCode code, String providerId, String model) {
        LOGGER.info("[souls] scene correlationId={} owner={} kind={} rosterSize={} provider={} model={} outcome=failed:{}",
                correlationId, turn.ownerId(), turn.kind(), turn.roster().size(), providerId, model, code);
    }

    // === LineCommitter (called by the playback machine strictly after a line's fan-out) ===

    @Override
    public void commitLine(SoulTypes.TurnToken token, int participantIndex, String taggedLine) {
        SoulTypes.ProviderResult metadata = sceneResults.getOrDefault(token.correlationId(),
                new SoulTypes.ProviderResult(true, "", null, "", "", 0L, null, null, null));
        partyStore.appendSpoken(token, taggedLine, metadata).exceptionally(appendError -> {
            // The line was already delivered; a stale/failed append must not surface to chat.
            LOGGER.warn("[souls] scene correlationId={} spoken-append failed: {}",
                    token.correlationId(), appendError.toString());
            return null;
        });
    }

    @Override
    public void sceneFinished(SoulTypes.TurnToken token, int deliveredLines, int totalLines) {
        sceneResults.remove(token.correlationId());
        LOGGER.info("[souls] scene correlationId={} outcome=playback-finished delivered={}/{}",
                token.correlationId(), deliveredLines, totalLines);
    }

    // === Deterministic, provider-detail-free plural status text ===

    private static String statusFor(SoulTypes.FailureCode code) {
        return switch (code) {
            case OVERLOADED -> "Your companions are tied up answering something else. Try again in a moment.";
            case TIMEOUT -> "Your companions didn't answer in time.";
            case UNAVAILABLE -> "The local conversation model is unavailable.";
            case MALFORMED -> "Your companions couldn't come up with anything to say.";
            case CANCELLED, STALE_EPOCH -> "The conversation changed before your companions could answer.";
            default -> "Your companions couldn't answer because Frens hit an internal error.";
        };
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
