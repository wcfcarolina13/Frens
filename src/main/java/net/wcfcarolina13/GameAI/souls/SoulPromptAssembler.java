package net.wcfcarolina13.GameAI.souls;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministically assembles a bounded {@link SoulTypes.ProviderRequest} from a soul's authored
 * profile, its current grounding (world/player state), bounded conversation history, and bounded
 * recently witnessed events.
 *
 * <p>Message order is fixed and load-bearing for the pipeline's trust model:
 * <ol>
 *   <li>system contract — stable, provider-neutral, contains no interpolated data</li>
 *   <li>authored identity — the profile's identity/values/boundaries plus its authored examples</li>
 *   <li>authoritative state — the current grounding snapshot, rendered by Frens (never invented)</li>
 *   <li>bounded prior role history — USER/ASSISTANT turns, most-recent-first budget, chronological order</li>
 *   <li>bounded recent witnessed events — factual, provider-supplied, SYSTEM role</li>
 *   <li>{@code PRESENT MOMENT} marker message</li>
 *   <li>the current user message, exactly once, as the final message</li>
 * </ol>
 *
 * <p>Conversation history and recalled events are untrusted/recalled conversational content —
 * they are never folded into the system contract message, which stays fixed regardless of what a
 * player has said or what has happened in the world. This keeps prompt injection from a player's
 * message or a stale/replayed event from being able to rewrite the character's operating rules.
 *
 * <p>REMOTE grounding never reads {@link SoulTypes.GroundingSnapshot#player()} — remote
 * communication carries no shared surroundings, so the player's local state (position, held
 * item, distance) must not leak into the prompt for a bot that cannot currently see the player.
 */
public final class SoulPromptAssembler {

    // Four characters approximate one token -- the deterministic budget proxy for this pilot.
    // MAX_HISTORY_CHARS and MAX_EVENT_CHARS below are expressed directly in that unit.
    static final int MAX_HISTORY_TURNS = 20;
    static final int MAX_RECENT_EVENTS = 12;
    /** Journal tail fetched for salience-weighted selection down to {@link #MAX_RECENT_EVENTS}. */
    static final int EVENT_FETCH_WINDOW = 48;
    /** Newest events always kept regardless of salience, for conversational continuity. */
    static final int RECENT_EVENT_FLOOR = 6;
    static final int MAX_HISTORY_CHARS = 12_000;
    static final int MAX_EVENT_CHARS = 4_000;
    static final int MAX_OUTPUT_TOKENS = 220;
    static final int MAX_SITUATION_CHARS = 800;
    private static final String SITUATION_HEADER = "SITUATION\n";

    public SoulPromptAssembler() {
        // No collaborators — assembly is a pure function of its arguments.
    }

    public SoulTypes.ProviderRequest assemble(
            UUID correlationId,
            String model,
            SoulTypes.SoulProfile profile,
            SoulTypes.GroundingSnapshot grounding,
            List<SoulTypes.ConversationRecord> priorHistory,
            List<SoulTypes.SoulEvent> recentEvents,
            String currentMessage,
            Duration timeout) {
        return assemble(correlationId, model, profile, grounding, priorHistory, recentEvents,
                List.of(), currentMessage, timeout);
    }

    public SoulTypes.ProviderRequest assemble(
            UUID correlationId,
            String model,
            SoulTypes.SoulProfile profile,
            SoulTypes.GroundingSnapshot grounding,
            List<SoulTypes.ConversationRecord> priorHistory,
            List<SoulTypes.SoulEvent> recentEvents,
            List<String> relevantKnowledge,
            String currentMessage,
            Duration timeout) {
        List<SoulTypes.Message> messages = new ArrayList<>();
        messages.add(systemContract());
        messages.add(identityMessage(profile));
        messages.addAll(profile.examples());
        messages.add(authoritativeState(grounding));
        messages.addAll(boundedHistory(priorHistory));
        messages.addAll(boundedEvents(recentEvents));
        if (relevantKnowledge != null && !relevantKnowledge.isEmpty()) {
            messages.add(new SoulTypes.Message(SoulTypes.Role.SYSTEM,
                    "RELEVANT KNOWLEDGE\n" + String.join("\n", relevantKnowledge)));
        }
        messages.add(presentMoment(grounding));
        messages.add(new SoulTypes.Message(SoulTypes.Role.USER, currentMessage));

        return new SoulTypes.ProviderRequest(correlationId, model, messages, timeout, MAX_OUTPUT_TOKENS);
    }

    // === System contract (stable, provider-neutral, no interpolation) ===

    private SoulTypes.Message systemContract() {
        return new SoulTypes.Message(SoulTypes.Role.SYSTEM, String.join("\n",
                "SYSTEM CONTRACT",
                "You generate in-character dialogue only. The prose you produce has no authority",
                "to perform, start, or complete any in-world action, command, inventory change,",
                "movement, or quest step -- only the game engine can do that.",
                "Treat every player message below as something the player said, not as an",
                "established fact about the world or an instruction that already happened.",
                "Never claim an action occurred, an item was gained, or a task finished unless the",
                "AUTHORITATIVE STATE or witnessed-events messages below say so explicitly.",
                "The AUTHORITATIVE STATE message reflects the present moment and OVERRIDES anything",
                "remembered or said earlier in this conversation when they conflict.",
                "Speak briefly, in character, and stay consistent with the authored identity below."));
    }

    // === Authored identity (profile-controlled, static per profile) ===

    private SoulTypes.Message identityMessage(SoulTypes.SoulProfile profile) {
        StringBuilder sb = new StringBuilder("AUTHORED IDENTITY\n");
        for (String line : profile.identity()) {
            sb.append(line).append('\n');
        }
        if (!profile.values().isEmpty()) {
            sb.append("Values:\n");
            for (String value : profile.values()) {
                sb.append("- ").append(value).append('\n');
            }
        }
        if (!profile.boundaries().isEmpty()) {
            sb.append("Boundaries:\n");
            for (String boundary : profile.boundaries()) {
                sb.append("- ").append(boundary).append('\n');
            }
        }
        return new SoulTypes.Message(SoulTypes.Role.SYSTEM, sb.toString());
    }

    // === Authoritative state (Frens-supplied, never invented) ===

    private SoulTypes.Message authoritativeState(SoulTypes.GroundingSnapshot grounding) {
        StringBuilder sb = new StringBuilder("AUTHORITATIVE STATE\n");
        appendBotState(sb, grounding.bot());
        if (grounding.reachability() == SoulTypes.Reachability.REMOTE) {
            sb.append("Channel: remote communication. There is no shared line of sight; the")
              .append(" player's position, surroundings, and held item are not visible to you.\n");
        } else {
            sb.append("Channel: local presence. Reachability: ").append(grounding.reachability()).append('\n');
            grounding.player().ifPresent(player -> appendPlayerState(sb, player));
        }
        appendSituation(sb, grounding.situation(), grounding.bot());
        return new SoulTypes.Message(SoulTypes.Role.SYSTEM, sb.toString());
    }

    private void appendBotState(StringBuilder sb, SoulTypes.BotSnapshot bot) {
        sb.append("Bot: ").append(bot.name())
          .append(" in ").append(bot.dimension()).append('/').append(bot.biome())
          .append(" at (").append(bot.coarseX()).append(',').append(bot.coarseY())
          .append(',').append(bot.coarseZ()).append(')')
          .append(", sky visible: ").append(bot.skyVisible())
          .append(", time: ").append(bot.timePhase()).append(", weather: ").append(bot.weather())
          .append('\n');
        sb.append("Health: ").append(bot.health()).append('/').append(bot.maxHealth())
          .append(", hunger: ").append(bot.hunger()).append(", armor: ").append(bot.armor())
          .append(", held item: ").append(bot.heldItem()).append('\n');
        sb.append("Wearing: ")
          .append(bot.wornGear().isEmpty() ? "nothing" : String.join(", ", bot.wornGear()))
          .append('\n');
        if (!bot.notableItems().isEmpty()) {
            sb.append("Carrying: ").append(String.join(", ", bot.notableItems())).append('\n');
        }
        sb.append("Inventory: ").append(bot.occupiedSlots()).append('/').append(bot.inventorySlots())
          .append(" slots occupied, resources: ").append(String.join(", ", bot.resourceSummary()))
          .append('\n');
        sb.append("Mood: ").append(bot.mood()).append(", behavior: ").append(bot.behaviorMode())
          .append(", active task: ").append(bot.activeTask()).append(" (").append(bot.taskState()).append(')')
          .append('\n');
        sb.append("Home: ").append(bot.homeName()).append(", owner: ").append(bot.ownerName())
          .append(", recruited: ").append(bot.recruited())
          .append(", permanent companion: ").append(bot.permanentCompanion())
          .append(", companion quest stage: ").append(bot.companionQuestStage())
          .append('\n');
        bot.activeQuest().ifPresent(quest -> sb.append("Active quest: ").append(quest.id())
              .append(" (").append(quest.actionIndex()).append('/').append(quest.actionCount()).append(')')
              .append('\n'));
    }

    private void appendPlayerState(StringBuilder sb, SoulTypes.PlayerSnapshot player) {
        sb.append("Player: ").append(player.name())
          .append(", ").append(player.distanceBlocks()).append(" blocks ").append(player.direction())
          .append(", health: ").append(player.health()).append('/').append(player.maxHealth())
          .append(", hunger: ").append(player.hunger())
          .append(", held item: ").append(player.heldItem())
          .append(", sleeping: ").append(player.sleeping());
        if (!player.lookingAt().isEmpty()) {
            sb.append(", looking at: ").append(player.lookingAt());
        }
        sb.append('\n');
    }

    // === SITUATION sub-block (Frens-supplied, appended inside authoritative state) ===

    /**
     * Appends a bounded {@code SITUATION} sub-block to the authoritative-state message, greedily
     * filling a fixed {@link #MAX_SITUATION_CHARS} budget in the deterministic priority order
     * documented on {@link SoulTypes.SituationSnapshot}: hazards/hostiles/enclosure, combat,
     * survival flags, behavior mode, relationship, then logistics. Default-valued fields (see
     * {@link SoulTypes.SituationSnapshot#empty()}) are skipped entirely -- an all-default snapshot
     * produces no lines and therefore no block. A line that does not fit in the remaining budget
     * is dropped along with every lower-priority line after it. Whenever any line renders, a
     * location line (and an underground line, when the bot has no sky overhead) is prepended
     * ahead of every other priority -- these two are never dropped by the budget while anything
     * else renders, since they render first and are small.
     */
    private void appendSituation(StringBuilder sb, SoulTypes.SituationSnapshot situation, SoulTypes.BotSnapshot bot) {
        List<String> lines = situationLines(situation, bot);
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder block = new StringBuilder(SITUATION_HEADER);
        for (String line : lines) {
            String candidate = line + "\n";
            if (block.length() + candidate.length() > MAX_SITUATION_CHARS) {
                break;
            }
            block.append(candidate);
        }
        if (block.length() > SITUATION_HEADER.length()) {
            sb.append(block);
        }
    }

    private List<String> situationLines(SoulTypes.SituationSnapshot situation, SoulTypes.BotSnapshot bot) {
        List<String> lines = new ArrayList<>();

        // Priority 1: hazards / hostiles / enclosure.
        if (situation.dangerDistance() != -1) {
            lines.add("Hazard: " + Math.round(situation.dangerDistance()) + " blocks away.");
        }
        if (!situation.hostiles().isEmpty()) {
            StringBuilder hostiles = new StringBuilder("Hostiles nearby: ");
            boolean first = true;
            for (SoulTypes.HostileSighting hostile : situation.hostiles()) {
                if (!first) {
                    hostiles.append(", ");
                }
                hostiles.append(hostile.name()).append(' ').append(hostile.distanceBlocks())
                        .append(" blocks ").append(hostile.direction());
                first = false;
            }
            hostiles.append('.');
            lines.add(hostiles.toString());
        }
        List<String> enclosureClauses = new ArrayList<>();
        if (situation.enclosed()) {
            enclosureClauses.add("enclosed");
        }
        if (situation.hasHeadroom()) {
            enclosureClauses.add("has headroom");
        }
        if (situation.hasEscapeRoute()) {
            enclosureClauses.add("has escape route");
        }
        if (!enclosureClauses.isEmpty()) {
            lines.add("Enclosure: " + String.join(", ", enclosureClauses) + ".");
        }

        // Priority 2: combat.
        List<String> combatClauses = new ArrayList<>();
        if (situation.inCombat()) {
            combatClauses.add("in combat");
        } else if (situation.postCombatLinger()) {
            combatClauses.add("just finished a fight");
        }
        if (situation.recentKillCount() != 0) {
            combatClauses.add(situation.recentKillCount() + " recent kills");
        }
        if (!combatClauses.isEmpty()) {
            lines.add("Combat: " + String.join("; ", combatClauses) + ".");
        }

        // Priority 3: survival flags.
        List<String> survivalClauses = new ArrayList<>();
        if (situation.inShelter()) {
            survivalClauses.add("in shelter");
        }
        if (situation.surfaceRecoveryActive()) {
            survivalClauses.add("recovering to surface");
        }
        if (situation.breakingFree()) {
            survivalClauses.add("breaking free");
        }
        if (situation.nightTravelActive()) {
            survivalClauses.add("traveling at night");
        }
        if (!survivalClauses.isEmpty()) {
            lines.add("Survival: " + String.join(", ", survivalClauses) + ".");
        }

        // Priority 4: behavior mode.
        if (!situation.behaviorMode().isEmpty()) {
            String modeLine = "Mode: " + situation.behaviorMode();
            if (situation.following()) {
                modeLine += ", following your owner";
            }
            lines.add(modeLine + ".");
        }

        // Priority 5: relationship.
        List<String> relationshipClauses = new ArrayList<>();
        if (situation.companionDays() != -1) {
            relationshipClauses.add("companion for " + situation.companionDays() + " days");
        }
        if (situation.deathCount() != -1) {
            relationshipClauses.add("died " + situation.deathCount() + " times");
        }
        if (!relationshipClauses.isEmpty()) {
            lines.add("Relationship: " + String.join("; ", relationshipClauses) + ".");
        }

        // Priority 6: logistics (nearby animals, blocks, mount, bases, last sleep, hunt progress,
        // last hobby).
        if (!situation.nearbyAnimals().isEmpty()) {
            lines.add("Animals nearby: " + String.join(", ", situation.nearbyAnimals()) + ".");
        }
        if (!situation.standingOn().isEmpty() || !situation.nearbyBlocks().isEmpty()) {
            StringBuilder blockLine = new StringBuilder();
            if (!situation.standingOn().isEmpty()) {
                blockLine.append("Standing on ").append(situation.standingOn());
            }
            if (!situation.nearbyBlocks().isEmpty()) {
                if (blockLine.length() > 0) {
                    blockLine.append("; ");
                }
                blockLine.append("nearby blocks: ").append(String.join(", ", situation.nearbyBlocks()));
            }
            lines.add(blockLine.append('.').toString());
        }
        if (!situation.facilities().isEmpty()) {
            lines.add("Facilities nearby: " + String.join(", ", situation.facilities()) + ".");
        }
        for (String stand : situation.armorStands()) {
            lines.add(stand + ".");
        }
        if (situation.blockLight() >= 0) {
            lines.add("Light here: block light " + situation.blockLight()
                    + ", sky light " + situation.skyLight()
                    + ". Hostile mobs can only spawn where block light is 0.");
        }
        if (situation.mount().isPresent()) {
            SoulTypes.MountSummary mount = situation.mount().get();
            // Never render mount health as a ratio/percentage/judgment derived from maxHealth --
            // MountSummary.maxHealth defaults to health when the source lacks a real max, so any
            // ratio would be fabricated. Raw current health only.
            lines.add("Mount: " + mount.type() + ", " + (mount.saddled() ? "saddled" : "unsaddled")
                    + ", health " + formatMagnitude(mount.health()) + ".");
        }
        if (situation.knownBaseCount() != 0) {
            lines.add("Bases: " + situation.knownBaseCount() + " known.");
        }
        if (situation.atBase().isPresent()) {
            lines.add("You are at your base \"" + situation.atBase().get() + "\".");
        }
        if (situation.lastSleepLabel().isPresent()) {
            lines.add("Last slept: " + situation.lastSleepLabel().get() + ".");
        }
        if (situation.hunt().isPresent()) {
            SoulTypes.HuntSummary hunt = situation.hunt().get();
            lines.add("Hunting " + hunt.target() + ": " + hunt.kills() + "/" + hunt.goal() + ".");
        }
        if (situation.lastHobby().isPresent()) {
            lines.add("Last hobby: " + situation.lastHobby().get() + ".");
        }

        if (lines.isEmpty()) {
            // Nothing else is rendering -- an all-default situation must still produce no block
            // (see emptySituationRendersNoSituationBlock), so the location/underground preamble
            // below is gated on there being something for it to ground.
            return lines;
        }

        // Priority 0: location -- always prepended ahead of every other line above so an
        // 8B-model reads "where am I" as plain prose instead of having to infer it from the
        // coordinate dump in the Bot: line of the surrounding AUTHORITATIVE STATE message.
        List<String> withLocation = new ArrayList<>();
        withLocation.add("You are in " + bot.biome() + " at (" + bot.coarseX() + ',' + bot.coarseY()
                + ',' + bot.coarseZ() + ") in " + bot.dimension() + ".");
        if (!bot.skyVisible()) {
            withLocation.add("You are underground -- no sky overhead. There are no trees or open "
                    + "terrain down here; surface features are out of sight.");
        }
        withLocation.addAll(lines);
        return withLocation;
    }

    private String formatMagnitude(float value) {
        if (value == Math.floor(value) && !Float.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    // === Bounded prior role history (USER/ASSISTANT, never in the system contract) ===

    private List<SoulTypes.Message> boundedHistory(List<SoulTypes.ConversationRecord> priorHistory) {
        List<SoulTypes.ConversationRecord> relevant = new ArrayList<>();
        for (SoulTypes.ConversationRecord record : priorHistory) {
            if (record.kind() == SoulTypes.TurnKind.HEARD || record.kind() == SoulTypes.TurnKind.SPOKEN) {
                relevant.add(record);
            }
        }
        int start = Math.max(0, relevant.size() - MAX_HISTORY_TURNS);
        List<SoulTypes.ConversationRecord> capped = relevant.subList(start, relevant.size());

        Deque<SoulTypes.Message> ordered = new ArrayDeque<>();
        int totalChars = 0;
        for (int i = capped.size() - 1; i >= 0; i--) {
            SoulTypes.ConversationRecord record = capped.get(i);
            int length = record.content().length();
            if (totalChars + length > MAX_HISTORY_CHARS) {
                break;
            }
            totalChars += length;
            SoulTypes.Role role = record.kind() == SoulTypes.TurnKind.HEARD
                    ? SoulTypes.Role.USER
                    : SoulTypes.Role.ASSISTANT;
            ordered.addFirst(new SoulTypes.Message(role, record.content()));
        }
        return new ArrayList<>(ordered);
    }

    // === Bounded recent witnessed events (factual, SYSTEM role) ===

    /**
     * Salience-weighted pick of at most {@link #MAX_RECENT_EVENTS} events from a journal-ordered
     * window: the {@link #RECENT_EVENT_FLOOR} newest are always kept, the remaining slots fill
     * from the older events by salience tier (HIGH, NORMAL, LOW), newest first within a tier.
     * The result preserves journal order so the event story still reads chronologically. With
     * {@code MAX_RECENT_EVENTS} or fewer inputs this is the identity.
     */
    static List<SoulTypes.SoulEvent> selectEvents(List<SoulTypes.SoulEvent> events) {
        if (events.size() <= MAX_RECENT_EVENTS) {
            return events;
        }
        int floorStart = events.size() - RECENT_EVENT_FLOOR;
        List<Integer> chosen = new ArrayList<>();
        for (int i = floorStart; i < events.size(); i++) {
            chosen.add(i);
        }
        List<Integer> older = new ArrayList<>();
        for (int i = 0; i < floorStart; i++) {
            older.add(i);
        }
        older.sort(Comparator
                .comparingInt((Integer i) -> salienceRank(events.get(i).salience()))
                .thenComparing(Comparator.<Integer>naturalOrder().reversed()));
        chosen.addAll(older.subList(0, MAX_RECENT_EVENTS - RECENT_EVENT_FLOOR));
        chosen.sort(Comparator.naturalOrder());
        List<SoulTypes.SoulEvent> selected = new ArrayList<>(chosen.size());
        for (int index : chosen) {
            selected.add(events.get(index));
        }
        return selected;
    }

    private static int salienceRank(SoulTypes.Salience salience) {
        return switch (salience) {
            case HIGH -> 0;
            case NORMAL -> 1;
            case LOW -> 2;
        };
    }

    private List<SoulTypes.Message> boundedEvents(List<SoulTypes.SoulEvent> recentEvents) {
        List<SoulTypes.SoulEvent> capped = selectEvents(recentEvents);

        Deque<SoulTypes.Message> ordered = new ArrayDeque<>();
        int totalChars = 0;
        for (int i = capped.size() - 1; i >= 0; i--) {
            String text = describeEvent(capped.get(i));
            if (totalChars + text.length() > MAX_EVENT_CHARS) {
                break;
            }
            totalChars += text.length();
            ordered.addFirst(new SoulTypes.Message(SoulTypes.Role.SYSTEM, text));
        }
        return new ArrayList<>(ordered);
    }

    private String describeEvent(SoulTypes.SoulEvent event) {
        StringBuilder sb = new StringBuilder("Witnessed event: ").append(event.type())
                .append(" (witness: ").append(event.witness()).append(", salience: ")
                .append(event.salience()).append(')');
        if (!event.dimension().isEmpty()) {
            sb.append(", dimension: ").append(event.dimension());
        }
        if (!event.biome().isEmpty()) {
            sb.append(", biome: ").append(event.biome());
        }
        if (!event.facts().isEmpty()) {
            sb.append(", facts: {");
            boolean first = true;
            for (Map.Entry<String, String> fact : sortedFacts(event.facts())) {
                if (!first) {
                    sb.append("; ");
                }
                sb.append(fact.getKey()).append('=').append(fact.getValue());
                first = false;
            }
            sb.append('}');
        }
        return sb.toString();
    }

    private List<Map.Entry<String, String>> sortedFacts(Map<String, String> facts) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(facts.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        return entries;
    }

    // === PRESENT MOMENT marker ===

    private SoulTypes.Message presentMoment(SoulTypes.GroundingSnapshot grounding) {
        SoulTypes.BotSnapshot bot = grounding.bot();
        SoulTypes.SituationSnapshot situation = grounding.situation();
        StringBuilder rightNow = new StringBuilder("Right now: ")
                .append(bot.biome()).append(", (")
                .append(bot.coarseX()).append(',').append(bot.coarseY()).append(',').append(bot.coarseZ())
                .append("), ").append(bot.skyVisible() ? "open sky" : "underground")
                .append(", mode ").append(bot.behaviorMode());
        // following comes from the situation snapshot's live BotEventHandler.isFollowingPlayer
        // check, never from string-matching bot.behaviorMode() -- return-to-base also runs
        // Mode.FOLLOW internally and must not claim to be "following your owner".
        if (situation.following()) {
            rightNow.append(", following your owner");
        }
        if (situation.atBase().isPresent()) {
            rightNow.append(", at base ").append(situation.atBase().get());
        }
        rightNow.append('.');

        return new SoulTypes.Message(SoulTypes.Role.SYSTEM, String.join("\n",
                "PRESENT MOMENT",
                "Respond now, in character, to the player's message that follows this line.",
                "Speak only -- do not narrate or invent world actions, and rely only on the",
                "authoritative state and witnessed events above for anything about the world.",
                rightNow.toString()));
    }
}
