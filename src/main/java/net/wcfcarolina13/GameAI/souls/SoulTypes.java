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

    /**
     * A profile's generated-voice selection (2026-08-29 per-bot voices). {@code piperModel} is a
     * voice name (resolved in the voices directory) or a path, {@code piperSpeaker} an optional
     * multi-speaker id ({@code -1} = model default); {@code refAudio}/{@code refText} are a
     * Dreamsleeve clone anchor. Blank fields fall back to the globally configured voice.
     */
    public record VoiceSpec(String piperModel, int piperSpeaker, String refAudio, String refText) {
        public static final VoiceSpec EMPTY = new VoiceSpec("", -1, "", "");

        public VoiceSpec {
            piperModel = piperModel == null ? "" : piperModel.trim();
            refAudio = refAudio == null ? "" : refAudio.trim();
            refText = refText == null ? "" : refText;
            if (piperSpeaker < -1) {
                piperSpeaker = -1;
            }
        }

        public boolean isEmpty() {
            return piperModel.isEmpty() && refAudio.isEmpty();
        }

        /** {@code "en_US-ryan-medium#3"} → model + speaker 3; no {@code #} → default speaker. */
        public static VoiceSpec parsePiper(String assignment) {
            String s = assignment == null ? "" : assignment.trim();
            if (s.isEmpty()) {
                return EMPTY;
            }
            int hash = s.lastIndexOf('#');
            if (hash > 0) {
                try {
                    return new VoiceSpec(s.substring(0, hash), Integer.parseInt(s.substring(hash + 1).trim()), "", "");
                } catch (NumberFormatException ignored) {
                    // fall through: treat the whole string as the model name
                }
            }
            return new VoiceSpec(s, -1, "", "");
        }
    }

    public record SoulProfile(String id, String displayName, List<String> identity,
                               List<String> values, List<String> boundaries,
                               List<Message> examples, VoiceSpec voice) {
        public SoulProfile {
            id = id == null ? "" : id;
            displayName = displayName == null ? "" : displayName;
            identity = identity == null ? List.of() : List.copyOf(identity);
            values = values == null ? List.of() : List.copyOf(values);
            boundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
            examples = examples == null ? List.of() : List.copyOf(examples);
            voice = voice == null ? VoiceSpec.EMPTY : voice;
        }

        /** Pre-voice shape: no per-profile voice, the global one applies. */
        public SoulProfile(String id, String displayName, List<String> identity,
                           List<String> values, List<String> boundaries, List<Message> examples) {
            this(id, displayName, identity, values, boundaries, examples, VoiceSpec.EMPTY);
        }
    }

    /**
     * One functional block seen near the bot, reduced to plain data at capture time:
     * {@code idPath} is the block id's path (e.g. "blast_furnace"), {@code name} its display
     * name, {@code x/y/z} its world position (0,0,0 when position was not captured).
     */
    public record RawFacility(String idPath, String name, int x, int y, int z) {
        /** Position-less shape used by pure digest tests. */
        public RawFacility(String idPath, String name) {
            this(idPath, name, 0, 0, 0);
        }

        public RawFacility {
            idPath = idPath == null ? "" : idPath;
            name = name == null ? "" : name;
        }
    }

    /** A facility the bot has personally seen (line-of-sight) at some point, with position. */
    public record KnownPlace(String idPath, String dimension, int x, int y, int z,
                             long lastSeenEpochMs) {
        public KnownPlace {
            idPath = idPath == null ? "" : idPath;
            dimension = dimension == null ? "" : dimension;
        }
    }

    /** Something a player told the bot, stored verbatim against a graph topic. */
    public record ToldFact(String teller, String message, long atEpochMs) {
        public ToldFact {
            teller = teller == null ? "" : teller;
            message = message == null ? "" : message;
        }
    }

    /** Per-bot persistent knowledge memory: seen places + told facts, keyed by topic id path. */
    public record KnowledgeMemory(List<KnownPlace> places, Map<String, List<ToldFact>> toldFacts) {
        public KnowledgeMemory {
            places = places == null ? List.of() : List.copyOf(places);
            toldFacts = toldFacts == null ? Map.of() : Map.copyOf(toldFacts);
        }

        public static KnowledgeMemory empty() {
            return new KnowledgeMemory(List.of(), Map.of());
        }
    }

    /**
     * Plain-data facts about one item stack, extracted from Minecraft types at capture time so
     * every downstream consumer (describer, prompt assembler, tests) stays free of game classes.
     * {@code name} is the display name (custom name when renamed), {@code typeName} the item
     * type's own name; {@code contents} holds the facts of items inside a bundle/shulker;
     * {@code wearFraction} is damage/maxDamage (0 when pristine or not damageable).
     */
    public record ItemFacts(String name, String typeName, int count, int maxCount,
                            List<String> enchantments, List<ItemFacts> contents,
                            double wearFraction) {
        public ItemFacts {
            name = name == null ? "" : name;
            typeName = typeName == null ? "" : typeName;
            enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
            contents = contents == null ? List.of() : List.copyOf(contents);
            wearFraction = Double.isFinite(wearFraction) ? Math.max(0.0, Math.min(1.0, wearFraction)) : 0.0;
        }

        public boolean customNamed() {
            return !name.isEmpty() && !name.equals(typeName);
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
                               int inventorySlots, List<String> resourceSummary,
                               List<String> wornGear, List<String> notableItems,
                               Map<String, Integer> itemCounts, String mood,
                               String behaviorMode, String activeTask, String taskState,
                               String homeName, String ownerName, boolean recruited,
                               int companionQuestStage, boolean permanentCompanion,
                               Optional<QuestSnapshot> activeQuest) {
        /** Pre-gear-awareness shape; defaults {@code wornGear}/{@code notableItems} to empty. */
        public BotSnapshot(UUID botId, String name, String dimension, String biome,
                           int coarseX, int coarseY, int coarseZ, boolean skyVisible,
                           String timePhase, String weather, float health, float maxHealth,
                           int hunger, int armor, String heldItem, int occupiedSlots,
                           int inventorySlots, List<String> resourceSummary, String mood,
                           String behaviorMode, String activeTask, String taskState,
                           String homeName, String ownerName, boolean recruited,
                           int companionQuestStage, boolean permanentCompanion,
                           Optional<QuestSnapshot> activeQuest) {
            this(botId, name, dimension, biome, coarseX, coarseY, coarseZ, skyVisible,
                    timePhase, weather, health, maxHealth, hunger, armor, heldItem, occupiedSlots,
                    inventorySlots, resourceSummary, List.of(), List.of(), Map.of(), mood,
                    behaviorMode, activeTask, taskState, homeName, ownerName, recruited,
                    companionQuestStage, permanentCompanion, activeQuest);
        }

        /** Pre-itemCounts shape; defaults {@code itemCounts} to empty. */
        public BotSnapshot(UUID botId, String name, String dimension, String biome,
                           int coarseX, int coarseY, int coarseZ, boolean skyVisible,
                           String timePhase, String weather, float health, float maxHealth,
                           int hunger, int armor, String heldItem, int occupiedSlots,
                           int inventorySlots, List<String> resourceSummary,
                           List<String> wornGear, List<String> notableItems, String mood,
                           String behaviorMode, String activeTask, String taskState,
                           String homeName, String ownerName, boolean recruited,
                           int companionQuestStage, boolean permanentCompanion,
                           Optional<QuestSnapshot> activeQuest) {
            this(botId, name, dimension, biome, coarseX, coarseY, coarseZ, skyVisible,
                    timePhase, weather, health, maxHealth, hunger, armor, heldItem, occupiedSlots,
                    inventorySlots, resourceSummary, wornGear, notableItems, Map.of(), mood,
                    behaviorMode, activeTask, taskState, homeName, ownerName, recruited,
                    companionQuestStage, permanentCompanion, activeQuest);
        }

        public BotSnapshot {
            Objects.requireNonNull(botId, "botId");
            name = name == null ? "" : name;
            dimension = dimension == null ? "" : dimension;
            biome = biome == null ? "" : biome;
            timePhase = timePhase == null ? "" : timePhase;
            weather = weather == null ? "" : weather;
            heldItem = heldItem == null ? "" : heldItem;
            resourceSummary = resourceSummary == null ? List.of() : List.copyOf(resourceSummary);
            wornGear = wornGear == null ? List.of() : List.copyOf(wornGear);
            notableItems = notableItems == null ? List.of() : List.copyOf(notableItems);
            itemCounts = itemCounts == null ? Map.of() : Map.copyOf(itemCounts);
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
                                  int hunger, String heldItem, boolean sleeping,
                                  String lookingAt, String activity) {
        /** Pre-activity shape; defaults {@code activity} to idle/unknown. */
        public PlayerSnapshot(UUID playerId, String name, int distanceBlocks,
                              String direction, float health, float maxHealth,
                              int hunger, String heldItem, boolean sleeping, String lookingAt) {
            this(playerId, name, distanceBlocks, direction, health, maxHealth,
                    hunger, heldItem, sleeping, lookingAt, "");
        }

        /** Pre-look-target shape; defaults {@code lookingAt} to unknown. */
        public PlayerSnapshot(UUID playerId, String name, int distanceBlocks,
                              String direction, float health, float maxHealth,
                              int hunger, String heldItem, boolean sleeping) {
            this(playerId, name, distanceBlocks, direction, health, maxHealth,
                    hunger, heldItem, sleeping, "");
        }

        public PlayerSnapshot {
            Objects.requireNonNull(playerId, "playerId");
            name = name == null ? "" : name;
            direction = direction == null ? "" : direction;
            heldItem = heldItem == null ? "" : heldItem;
            lookingAt = lookingAt == null ? "" : lookingAt;
            activity = activity == null ? "" : activity;
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
            List<String> facilities,            // functional blocks nearby, described lines, at most 10 kinds
            List<RawFacility> facilitySightings, // LOS-verified sightings with positions; memory + retriever input
            List<String> armorStands,           // "Armor stand displaying: ..." lines, at most 3
            int blockLight,                     // block light at the bot's feet; -1 = unknown
            int skyLight,                       // sky light at the bot's feet; -1 = unknown
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
        /** Pre-facilities shape; defaults {@code facilities} and later additions to empty. */
        public SituationSnapshot(int dangerDistance, List<HostileSighting> hostiles,
                                 List<String> nearbyAnimals, String standingOn, List<String> nearbyBlocks,
                                 boolean enclosed, boolean hasHeadroom, boolean hasEscapeRoute,
                                 String behaviorMode, boolean following,
                                 boolean inCombat, boolean postCombatLinger, int recentKillCount,
                                 boolean inShelter, boolean surfaceRecoveryActive, boolean breakingFree,
                                 boolean nightTravelActive, int companionDays, int deathCount,
                                 Optional<MountSummary> mount, int knownBaseCount,
                                 Optional<String> lastSleepLabel, Optional<String> atBase,
                                 Optional<HuntSummary> hunt, Optional<String> lastHobby) {
            this(dangerDistance, hostiles, nearbyAnimals, standingOn, nearbyBlocks, List.of(),
                    enclosed, hasHeadroom, hasEscapeRoute, behaviorMode, following,
                    inCombat, postCombatLinger, recentKillCount,
                    inShelter, surfaceRecoveryActive, breakingFree, nightTravelActive,
                    companionDays, deathCount, mount, knownBaseCount,
                    lastSleepLabel, atBase, hunt, lastHobby);
        }

        /** Pre-light/armor-stand shape; defaults {@code armorStands} empty and lights unknown. */
        public SituationSnapshot(int dangerDistance, List<HostileSighting> hostiles,
                                 List<String> nearbyAnimals, String standingOn, List<String> nearbyBlocks,
                                 List<String> facilities,
                                 boolean enclosed, boolean hasHeadroom, boolean hasEscapeRoute,
                                 String behaviorMode, boolean following,
                                 boolean inCombat, boolean postCombatLinger, int recentKillCount,
                                 boolean inShelter, boolean surfaceRecoveryActive, boolean breakingFree,
                                 boolean nightTravelActive, int companionDays, int deathCount,
                                 Optional<MountSummary> mount, int knownBaseCount,
                                 Optional<String> lastSleepLabel, Optional<String> atBase,
                                 Optional<HuntSummary> hunt, Optional<String> lastHobby) {
            this(dangerDistance, hostiles, nearbyAnimals, standingOn, nearbyBlocks, facilities,
                    List.of(), List.of(), -1, -1,
                    enclosed, hasHeadroom, hasEscapeRoute, behaviorMode, following,
                    inCombat, postCombatLinger, recentKillCount,
                    inShelter, surfaceRecoveryActive, breakingFree, nightTravelActive,
                    companionDays, deathCount, mount, knownBaseCount,
                    lastSleepLabel, atBase, hunt, lastHobby);
        }

        /** Pre-facilityIds shape; defaults {@code facilityIds} to empty. */
        public SituationSnapshot(int dangerDistance, List<HostileSighting> hostiles,
                                 List<String> nearbyAnimals, String standingOn, List<String> nearbyBlocks,
                                 List<String> facilities, List<String> armorStands,
                                 int blockLight, int skyLight,
                                 boolean enclosed, boolean hasHeadroom, boolean hasEscapeRoute,
                                 String behaviorMode, boolean following,
                                 boolean inCombat, boolean postCombatLinger, int recentKillCount,
                                 boolean inShelter, boolean surfaceRecoveryActive, boolean breakingFree,
                                 boolean nightTravelActive, int companionDays, int deathCount,
                                 Optional<MountSummary> mount, int knownBaseCount,
                                 Optional<String> lastSleepLabel, Optional<String> atBase,
                                 Optional<HuntSummary> hunt, Optional<String> lastHobby) {
            this(dangerDistance, hostiles, nearbyAnimals, standingOn, nearbyBlocks, facilities,
                    List.of(), armorStands, blockLight, skyLight,
                    enclosed, hasHeadroom, hasEscapeRoute, behaviorMode, following,
                    inCombat, postCombatLinger, recentKillCount,
                    inShelter, surfaceRecoveryActive, breakingFree, nightTravelActive,
                    companionDays, deathCount, mount, knownBaseCount,
                    lastSleepLabel, atBase, hunt, lastHobby);
        }

        public SituationSnapshot {
            hostiles = hostiles == null ? List.of() : List.copyOf(hostiles);
            nearbyAnimals = nearbyAnimals == null ? List.of() : List.copyOf(nearbyAnimals);
            standingOn = standingOn == null ? "" : standingOn;
            nearbyBlocks = nearbyBlocks == null ? List.of() : List.copyOf(nearbyBlocks);
            facilities = facilities == null ? List.of() : List.copyOf(facilities);
            facilitySightings = facilitySightings == null ? List.of() : List.copyOf(facilitySightings);
            armorStands = armorStands == null ? List.of() : List.copyOf(armorStands);
            behaviorMode = behaviorMode == null ? "" : behaviorMode;
            mount = mount == null ? Optional.empty() : mount;
            lastSleepLabel = lastSleepLabel == null ? Optional.empty() : lastSleepLabel;
            atBase = atBase == null ? Optional.empty() : atBase;
            hunt = hunt == null ? Optional.empty() : hunt;
            lastHobby = lastHobby == null ? Optional.empty() : lastHobby;
        }

        /** Deduped id paths of the currently sighted facilities (derived, retriever input). */
        public List<String> facilityIds() {
            return facilitySightings.stream().map(RawFacility::idPath)
                    .filter(id -> !id.isBlank()).distinct().toList();
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
                                     Instant capturedAt, List<String> overheard) {
        public GroundingSnapshot {
            Objects.requireNonNull(reachability, "reachability");
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(capturedAt, "capturedAt");
            player = player == null ? Optional.empty() : player;
            situation = situation == null ? SituationSnapshot.empty() : situation;
            overheard = overheard == null ? List.of() : List.copyOf(overheard);
        }

        /** Pre-overhear shape (local-chat spec §4): no overheard lines. */
        public GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                  Optional<PlayerSnapshot> player, SituationSnapshot situation,
                                  Instant capturedAt) {
            this(reachability, bot, player, situation, capturedAt, List.of());
        }

        public GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                  Optional<PlayerSnapshot> player, Instant capturedAt) {
            this(reachability, bot, player, SituationSnapshot.empty(), capturedAt, List.of());
        }
    }

    /**
     * {@code routingId} is the correlation id minted where the turn entered the system (the chat
     * router / whisper surface); {@code SoulConversationService} adopts it as the turn's
     * correlation id instead of minting its own, so the {@code [souls] routing},
     * {@code [souls] turn}, {@code [souls] knowledge}, and {@code [souls] delivery} log lines for
     * one turn all join on a single id.
     */
    public record AcceptedTurn(ConversationKey key, String botDisplayName,
                                String playerDisplayName, String playerMessage,
                                String profileId, GroundingSnapshot grounding,
                                Instant acceptedAt, UUID routingId) {
        public AcceptedTurn {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(grounding, "grounding");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(routingId, "routingId");
            botDisplayName = botDisplayName == null ? "" : botDisplayName;
            playerDisplayName = playerDisplayName == null ? "" : playerDisplayName;
            playerMessage = playerMessage == null ? "" : playerMessage;
            profileId = profileId == null ? "" : profileId;
        }

        /** Convenience for callers without a routing-surface id: mints a fresh one. */
        public AcceptedTurn(ConversationKey key, String botDisplayName, String playerDisplayName,
                             String playerMessage, String profileId, GroundingSnapshot grounding,
                             Instant acceptedAt) {
            this(key, botDisplayName, playerDisplayName, playerMessage, profileId, grounding,
                    acceptedAt, UUID.randomUUID());
        }
    }
}
