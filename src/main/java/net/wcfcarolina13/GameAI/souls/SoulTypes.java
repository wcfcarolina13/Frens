package net.wcfcarolina13.GameAI.souls;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable boundary model for the soul-communication domain.
 *
 * <p>These records are the sole contract between the soul-communication pipeline and the rest
 * of the mod. They contain only strings, primitives, UUIDs, instants, durations, and immutable
 * collections — never Minecraft/Fabric classes — so they can cross worker-thread boundaries and
 * be persisted or tested without touching server state.
 *
 * <p>Canonical constructors enforce two defensive rules uniformly:
 * <ul>
 *   <li>Primary identifiers (UUID fields) and enums are required and reject {@code null}.</li>
 *   <li>Nullable strings collapse to {@code ""} and collections are defensively copied via
 *       {@code List.copyOf}/{@code Map.copyOf} so callers can never mutate state held by a
 *       record after construction.</li>
 * </ul>
 * Optional provider metadata (failure code, timing, and token counts) may remain {@code null}
 * when the information is unavailable — those fields are intentionally left unvalidated.
 */
public final class SoulTypes {

    private SoulTypes() {
        // Namespace class — not instantiable.
    }

    public enum Channel { DIRECT, PARTY, LOCAL, BANTER, SYSTEM }

    public enum Reachability { LOCAL, REMOTE, UNREACHABLE }

    public enum Role { SYSTEM, USER, ASSISTANT }

    public enum TurnKind { HEARD, SPOKEN, FAILURE }

    public enum FailureCode {
        DISABLED, UNAUTHORIZED, UNREACHABLE, OVERLOADED, TIMEOUT,
        UNAVAILABLE, MALFORMED, CANCELLED, STALE_EPOCH, INTERNAL
    }

    public enum EventType {
        TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_PAUSED, TASK_CANCELLED,
        BOT_DAMAGE, OWNER_DAMAGE, COMBAT_STARTED, COMBAT_ENDED,
        DEATH, RESPAWN, SLEEP, WAKE, DIMENSION_CHANGED,
        QUEST_STAGE_CHANGED, DIRECT_CONVERSATION,
        MOB_KILLED, SELF_RESCUE, HOBBY_SESSION, HUNT_PROGRESS
    }

    public enum Witness { SELF, LOCAL }

    public enum Salience { LOW, NORMAL, HIGH }

    public record ConversationKey(UUID botId, UUID playerId, Channel channel) {
        public ConversationKey {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(channel, "channel");
        }
    }

    public record ConversationCursor(long epoch, long nextSequence) {}

    public record TurnToken(ConversationKey key, UUID correlationId, long epoch, long sequence) {
        public TurnToken {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public record Message(Role role, String content) {
        public Message {
            Objects.requireNonNull(role, "role");
            content = content == null ? "" : content;
        }
    }

    public record ProviderRequest(UUID correlationId, String model, List<Message> messages,
                                   Duration timeout, int maxOutputTokens) {
        public ProviderRequest {
            Objects.requireNonNull(correlationId, "correlationId");
            model = model == null ? "" : model;
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record ProviderResult(boolean success, String text, FailureCode failureCode,
                                  String provider, String model, long elapsedMillis,
                                  Long firstOutputMillis, Integer inputTokens, Integer outputTokens) {
        public ProviderResult {
            text = text == null ? "" : text;
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
            // failureCode, firstOutputMillis, inputTokens, outputTokens are optional provider
            // metadata and may legitimately be null when unavailable.
        }
    }

    public record ConversationRecord(UUID correlationId, long epoch, long sequence,
                                      TurnKind kind, String content, Instant occurredAt,
                                      String provider, String model, Long elapsedMillis,
                                      FailureCode failureCode) {
        public ConversationRecord {
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(occurredAt, "occurredAt");
            content = content == null ? "" : content;
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
            // elapsedMillis and failureCode are optional provider metadata and may be null.
        }
    }

    public record SoulEvent(UUID eventId, EventType type, UUID actorId,
                             List<UUID> participants, String dimension, String biome,
                             Map<String, String> facts, Witness witness,
                             long worldTick, Instant occurredAt, Salience salience) {
        public SoulEvent {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(witness, "witness");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(salience, "salience");
            participants = participants == null ? List.of() : List.copyOf(participants);
            dimension = dimension == null ? "" : dimension;
            biome = biome == null ? "" : biome;
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }

    public record SoulState(int schemaVersion, UUID botId, String profileId,
                             boolean active, Map<String, ConversationCursor> conversations) {
        public SoulState {
            Objects.requireNonNull(botId, "botId");
            profileId = profileId == null ? "" : profileId;
            conversations = conversations == null ? Map.of() : Map.copyOf(conversations);
        }
    }

    public record SoulProfile(String id, String displayName, List<String> identity,
                               List<String> values, List<String> boundaries,
                               List<Message> examples) {
        public SoulProfile {
            id = id == null ? "" : id;
            displayName = displayName == null ? "" : displayName;
            identity = identity == null ? List.of() : List.copyOf(identity);
            values = values == null ? List.of() : List.copyOf(values);
            boundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    public record QuestSnapshot(String id, String intent, int actionIndex,
                                 int actionCount, long expiresTick) {
        public QuestSnapshot {
            id = id == null ? "" : id;
            intent = intent == null ? "" : intent;
        }
    }

    public record BotSnapshot(UUID botId, String name, String dimension, String biome,
                               int coarseX, int coarseY, int coarseZ, boolean skyVisible,
                               String timePhase, String weather, float health, float maxHealth,
                               int hunger, int armor, String heldItem, int occupiedSlots,
                               int inventorySlots, List<String> resourceSummary, String mood,
                               String behaviorMode, String activeTask, String taskState,
                               String homeName, String ownerName, boolean recruited,
                               int companionQuestStage, boolean permanentCompanion,
                               Optional<QuestSnapshot> activeQuest) {
        public BotSnapshot {
            Objects.requireNonNull(botId, "botId");
            name = name == null ? "" : name;
            dimension = dimension == null ? "" : dimension;
            biome = biome == null ? "" : biome;
            timePhase = timePhase == null ? "" : timePhase;
            weather = weather == null ? "" : weather;
            heldItem = heldItem == null ? "" : heldItem;
            resourceSummary = resourceSummary == null ? List.of() : List.copyOf(resourceSummary);
            mood = mood == null ? "" : mood;
            behaviorMode = behaviorMode == null ? "" : behaviorMode;
            activeTask = activeTask == null ? "" : activeTask;
            taskState = taskState == null ? "" : taskState;
            homeName = homeName == null ? "" : homeName;
            ownerName = ownerName == null ? "" : ownerName;
            activeQuest = activeQuest == null ? Optional.empty() : activeQuest;
        }
    }

    public record PlayerSnapshot(UUID playerId, String name, int distanceBlocks,
                                  String direction, float health, float maxHealth,
                                  int hunger, String heldItem, boolean sleeping) {
        public PlayerSnapshot {
            Objects.requireNonNull(playerId, "playerId");
            name = name == null ? "" : name;
            direction = direction == null ? "" : direction;
            heldItem = heldItem == null ? "" : heldItem;
        }
    }

    public record HostileSighting(String name, String direction, int distanceBlocks) {
        public HostileSighting {
            name = name == null ? "" : name;
            direction = direction == null ? "" : direction;
        }
    }

    public record MountSummary(String type, float health, float maxHealth, boolean saddled) {
        public MountSummary {
            type = type == null ? "" : type;
        }
    }

    public record HuntSummary(String target, int kills, int goal) {
        public HuntSummary {
            target = target == null ? "" : target;
        }
    }

    public record SituationSnapshot(
            int dangerDistance,                 // blocks to nearest lava/cliff hazard; -1 = none detected
            List<HostileSighting> hostiles,     // nearest-first, at most 5
            List<String> nearbyAnimals,         // non-hostile entities aggregated by name, most-numerous first, at most 4
            String standingOn,                  // block name directly under the bot's feet, "" = unknown
            List<String> nearbyBlocks,          // deduped block-type names in a small box, top 4 by count, air excluded
            boolean enclosed, boolean hasHeadroom, boolean hasEscapeRoute,
            String behaviorMode,                // Mode.name(), e.g. "GUARD"
            boolean following,                  // true only when actively following a player (not return-to-base)
            boolean inCombat, boolean postCombatLinger, int recentKillCount,
            boolean inShelter, boolean surfaceRecoveryActive, boolean breakingFree,
            boolean nightTravelActive,
            int companionDays,                  // -1 = unknown
            int deathCount,                     // -1 = unknown
            Optional<MountSummary> mount,
            int knownBaseCount,
            Optional<String> lastSleepLabel,
            Optional<String> atBase,            // label of the nearest known base within 32 blocks of the bot now
            Optional<HuntSummary> hunt,
            Optional<String> lastHobby) {
        public SituationSnapshot {
            hostiles = hostiles == null ? List.of() : List.copyOf(hostiles);
            nearbyAnimals = nearbyAnimals == null ? List.of() : List.copyOf(nearbyAnimals);
            standingOn = standingOn == null ? "" : standingOn;
            nearbyBlocks = nearbyBlocks == null ? List.of() : List.copyOf(nearbyBlocks);
            behaviorMode = behaviorMode == null ? "" : behaviorMode;
            mount = mount == null ? Optional.empty() : mount;
            lastSleepLabel = lastSleepLabel == null ? Optional.empty() : lastSleepLabel;
            atBase = atBase == null ? Optional.empty() : atBase;
            hunt = hunt == null ? Optional.empty() : hunt;
            lastHobby = lastHobby == null ? Optional.empty() : lastHobby;
        }

        public static SituationSnapshot empty() {
            return new SituationSnapshot(-1, List.of(), List.of(), "", List.of(), false, false, false, "",
                    false, false, false, 0, false, false, false, false,
                    -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }
    }

    public record GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                     Optional<PlayerSnapshot> player, SituationSnapshot situation,
                                     Instant capturedAt) {
        public GroundingSnapshot {
            Objects.requireNonNull(reachability, "reachability");
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(capturedAt, "capturedAt");
            player = player == null ? Optional.empty() : player;
            situation = situation == null ? SituationSnapshot.empty() : situation;
        }

        public GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                  Optional<PlayerSnapshot> player, Instant capturedAt) {
            this(reachability, bot, player, SituationSnapshot.empty(), capturedAt);
        }
    }

    public record AcceptedTurn(ConversationKey key, String botDisplayName,
                                String playerDisplayName, String playerMessage,
                                String profileId, GroundingSnapshot grounding,
                                Instant acceptedAt) {
        public AcceptedTurn {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(grounding, "grounding");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            botDisplayName = botDisplayName == null ? "" : botDisplayName;
            playerDisplayName = playerDisplayName == null ? "" : playerDisplayName;
            playerMessage = playerMessage == null ? "" : playerMessage;
            profileId = profileId == null ? "" : profileId;
        }
    }
}
