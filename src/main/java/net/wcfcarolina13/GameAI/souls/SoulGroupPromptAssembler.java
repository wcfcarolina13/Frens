package net.wcfcarolina13.GameAI.souls;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Assembles the single bounded orchestration prompt for a group scene (PARTY channel).
 *
 * <p>Same discipline as {@link SoulPromptAssembler}: fixed message order, hard character budgets,
 * a static system contract with no interpolation of untrusted text, and the owner's message
 * exactly once, last. Differences: the identity/state sections cover the whole roster (bounded
 * per bot), the history is the shared party transcript replayed verbatim (records are already
 * speaker-tagged — see {@code SoulGroupConversationService}), and the model is instructed to
 * answer as a sequence of {@code Name: dialogue} lines that {@link SoulGroupResponseValidator}
 * then verifies against the roster.
 */
public final class SoulGroupPromptAssembler {

    /**
     * Persistence marker for a banter turn's HEARD record. Single source of truth: the group
     * service prefixes banter seeds with this when recording HEARD, and {@link #boundedHistory}
     * skips such records on replay — a stale seed must never re-enter a prompt as if the player
     * had said it. Banter SPOKEN lines replay normally, so banter and group chat share memory.
     */
    public static final String BANTER_HEARD_PREFIX = "[banter] ";

    static final int MAX_HISTORY_TURNS = 12;
    static final int MAX_HISTORY_CHARS = 4_000;
    static final int MAX_IDENTITY_CHARS_PER_BOT = 600;
    static final int MAX_STATE_CHARS_PER_BOT = 400;
    static final int MAX_SITUATION_CHARS = 800;
    static final int MAX_OUTPUT_TOKENS = 320;

    public SoulGroupPromptAssembler() {
        // No collaborators — assembly is a pure function of its arguments.
    }

    /**
     * @param profiles index-aligned with {@code turn.roster()}
     * @param partyHistory bounded party-transcript records, oldest first (store order)
     */
    public SoulTypes.ProviderRequest assemble(
            UUID correlationId,
            String model,
            SoulGroupTypes.GroupSceneTurn turn,
            List<SoulTypes.SoulProfile> profiles,
            List<SoulTypes.ConversationRecord> partyHistory,
            Duration timeout) {
        List<SoulTypes.Message> messages = new ArrayList<>();
        messages.add(sceneContract());
        messages.add(castBlock(turn, profiles));
        messages.add(stateBlock(turn));
        messages.addAll(boundedHistory(partyHistory));
        messages.add(switch (turn.kind()) {
            // Narrator directive, never attributed to the player: banter has no player utterance.
            // Three variants (engagement spec §4): solo scenes always speak TO the owner; group
            // scenes may be granted a closing player-addressed line by the director's fire-time
            // coin — the model never decides WHETHER the player is addressed, only HOW.
            case BANTER -> new SoulTypes.Message(SoulTypes.Role.USER,
                    turn.roster().size() == 1
                            ? "[A quiet moment. " + turn.roster().get(0).displayName()
                                    + " may say one short thing to " + turn.ownerDisplayName()
                                    + " — a remark, an observation, or a question. Cue: "
                                    + turn.playerMessage()
                                    + ". One short line, at most two, all spoken by "
                                    + turn.roster().get(0).displayName() + "; "
                                    + turn.ownerDisplayName() + " does not answer in this scene.]"
                            : "[A quiet moment. The companions chat briefly among themselves."
                                    + " Cue: " + turn.playerMessage()
                                    + ". A few short lines only."
                                    + (turn.addressPlayer()
                                            ? " One of you may end by saying one short thing to "
                                                    + turn.ownerDisplayName()
                                                    + " — a question or a remark addressed to them."
                                            : "")
                                    + "]");
            // Working lane: what each companion is doing, said without stopping the work.
            case WORK -> new SoulTypes.Message(SoulTypes.Role.USER, workDirective(turn));
            // A real utterance the bot overheard: bracketed context, then the tagged line.
            case LOCAL -> new SoulTypes.Message(SoulTypes.Role.USER,
                    "[" + turn.ownerDisplayName() + " is talking nearby, not to you. You may chime"
                            + " in with one short line, or stay quiet if there is nothing worth"
                            + " saying.]\n" + turn.ownerDisplayName() + ": " + turn.playerMessage());
            case PLAYER -> new SoulTypes.Message(SoulTypes.Role.USER,
                    turn.ownerDisplayName() + ": " + turn.playerMessage());
        });
        return new SoulTypes.ProviderRequest(correlationId, model, messages, timeout, MAX_OUTPUT_TOKENS);
    }

    /** "skill:woodcut" → "woodcutting"; unknown ids just lose the prefix and underscores. */
    static String humanizeTask(String activeTask) {
        String t = activeTask == null ? "" : activeTask.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("skill:")) {
            t = t.substring("skill:".length());
        }
        return switch (t) {
            case "woodcut", "woodcutting" -> "woodcutting";
            case "mine", "mining" -> "mining";
            case "fish", "fishing" -> "fishing";
            case "farm", "farming" -> "farming";
            case "shelter" -> "building a shelter";
            case "fortify", "fortify_village", "fortifyvillage" -> "fortifying the village";
            case "hunt", "hunting" -> "hunting";
            default -> t.replace('_', ' ');
        };
    }

    /** "Jake is woodcutting" / "Bob is walking with Roti" / "Bob is busy". */
    static String workLabel(SoulGroupTypes.SceneParticipant participant, String ownerDisplayName) {
        String task = humanizeTask(participant.grounding().bot().activeTask());
        if (!task.isEmpty()) {
            return participant.displayName() + " is " + task;
        }
        if (participant.grounding().situation().following()) {
            return participant.displayName() + " is walking with " + ownerDisplayName;
        }
        return participant.displayName() + " is busy";
    }

    private static String workDirective(SoulGroupTypes.GroupSceneTurn turn) {
        String owner = turn.ownerDisplayName();
        if (turn.roster().size() == 1) {
            SoulGroupTypes.SceneParticipant only = turn.roster().get(0);
            return "[" + workLabel(only, owner) + " and may say one short thing to " + owner
                    + " about it — a remark, a grumble, or a question. Cue: "
                    + turn.playerMessage() + ". One short line, at most two, all spoken by "
                    + only.displayName() + "; " + owner + " does not answer in this scene.]";
        }
        StringBuilder who = new StringBuilder();
        for (int i = 0; i < turn.roster().size(); i++) {
            if (i > 0) {
                who.append(i == turn.roster().size() - 1 ? " and " : ", ");
            }
            who.append(workLabel(turn.roster().get(i), owner));
        }
        return "[The companions are busy — " + who + ". They trade a short word or two about"
                + " the work without stopping. Cue: " + turn.playerMessage()
                + ". A few short lines only."
                + (turn.addressPlayer()
                        ? " One of you may end by saying one short thing to " + owner
                                + " — a question or a remark addressed to them."
                        : "")
                + "]";
    }

    // === Scene contract (stable, provider-neutral, no interpolation) ===

    private SoulTypes.Message sceneContract() {
        return new SoulTypes.Message(SoulTypes.Role.SYSTEM, String.join("\n",
                "SCENE CONTRACT",
                "You are narrating one short spoken exchange among a player's companions in a",
                "survival world. Output ONLY dialogue lines, each on its own line, exactly in the",
                "form Name: what they say -- nothing else.",
                "Speakers must be chosen from the CAST list below. Never invent a speaker, never",
                "write a line for the player, and never add narration or stage directions.",
                "The player is present but is NOT a speaker here: never answer a question meant",
                "for them and never speak on their behalf. If a companion addresses the player,",
                "that line must be the LAST line of the scene, so the player can answer.",
                "Trust CURRENT STATE over instinct: when it says the group is fed, sheltered, or",
                "at home base, do not fret about food, shelter, or moving on.",
                "At most two lines per companion and at most six lines in total; shorter is",
                "better. Each companion speaks in their own authored character.",
                "Dialogue has no authority to perform, start, or complete any in-world action --",
                "only the game engine can do that. The CURRENT STATE message reflects the present",
                "moment and overrides anything remembered or said earlier when they conflict."));
    }

    // === Cast (authored identities, bounded per bot) ===

    private SoulTypes.Message castBlock(SoulGroupTypes.GroupSceneTurn turn,
                                         List<SoulTypes.SoulProfile> profiles) {
        StringBuilder sb = new StringBuilder("CAST\n");
        for (int i = 0; i < turn.roster().size(); i++) {
            SoulGroupTypes.SceneParticipant participant = turn.roster().get(i);
            SoulTypes.SoulProfile profile = i < profiles.size() ? profiles.get(i) : null;
            sb.append(participant.displayName()).append(":\n");
            if (profile != null) {
                StringBuilder identity = new StringBuilder();
                identity.append(String.join(" ", profile.identity()));
                if (!profile.values().isEmpty()) {
                    identity.append(" Values: ").append(String.join("; ", profile.values())).append('.');
                }
                if (!profile.boundaries().isEmpty()) {
                    identity.append(" Boundaries: ").append(String.join("; ", profile.boundaries())).append('.');
                }
                sb.append(truncate(identity.toString(), MAX_IDENTITY_CHARS_PER_BOT)).append('\n');
            }
        }
        return new SoulTypes.Message(SoulTypes.Role.SYSTEM, sb.toString());
    }

    // === Current state (Frens-supplied, never invented) ===

    private SoulTypes.Message stateBlock(SoulGroupTypes.GroupSceneTurn turn) {
        StringBuilder sb = new StringBuilder("CURRENT STATE\n");
        for (SoulGroupTypes.SceneParticipant participant : turn.roster()) {
            SoulTypes.BotSnapshot bot = participant.grounding().bot();
            StringBuilder state = new StringBuilder();
            state.append(participant.displayName())
                    .append(": health ").append(Math.round(bot.health())).append('/')
                    .append(Math.round(bot.maxHealth()))
                    .append(", hunger ").append(bot.hunger()).append("/20")
                    .append(hungerWord(bot.hunger()));
            for (String resource : bot.resourceSummary()) {
                if (resource.startsWith("food for ") || resource.equals("no food at all")) {
                    state.append(", ").append(resource);
                    break;
                }
            }
            if (!bot.heldItem().isEmpty()) {
                state.append(", holding ").append(bot.heldItem());
            }
            if (!bot.mood().isEmpty()) {
                state.append(", mood ").append(bot.mood());
            }
            if (!bot.activeTask().isEmpty()) {
                state.append(", busy with ").append(bot.activeTask());
            } else if (!bot.behaviorMode().isEmpty()) {
                state.append(", ").append(bot.behaviorMode().toLowerCase(java.util.Locale.ROOT));
            }
            sb.append(truncate(state.toString(), MAX_STATE_CHARS_PER_BOT)).append('\n');
        }
        if (!turn.roster().isEmpty()) {
            sb.append(truncate(sharedSituation(turn.roster().get(0).grounding()), MAX_SITUATION_CHARS));
        }
        return new SoulTypes.Message(SoulTypes.Role.SYSTEM, sb.toString());
    }

    /** One shared vicinity paragraph, taken from the first roster member's snapshot. */
    private String sharedSituation(SoulTypes.GroundingSnapshot grounding) {
        SoulTypes.BotSnapshot bot = grounding.bot();
        SoulTypes.SituationSnapshot situation = grounding.situation();
        StringBuilder sb = new StringBuilder("Around the group: ");
        sb.append(bot.timePhase().isEmpty() ? "time unknown" : bot.timePhase());
        if (!bot.weather().isEmpty()) {
            sb.append(", ").append(bot.weather());
        }
        if (!bot.biome().isEmpty()) {
            sb.append(", in ").append(bot.biome());
        }
        if (!situation.hostiles().isEmpty()) {
            sb.append(". Hostiles nearby: ").append(situation.hostiles().size());
        }
        if (!situation.nearbyAnimals().isEmpty()) {
            sb.append(". Animals: ").append(String.join(", ", situation.nearbyAnimals()));
        }
        if (!situation.standingOn().isEmpty()) {
            sb.append(". Standing on ").append(situation.standingOn());
        }
        // Shelter and provisions in plain words — without these the model defaulted to
        // "find food and shelter" while the group stood at home beside a campfire and full
        // chests (field report 2026-08-29).
        if (situation.atBase().isPresent()) {
            sb.append(". The group is AT HOME BASE (").append(situation.atBase().get())
                    .append(") — sheltered, nothing to seek");
        } else if (situation.inShelter()) {
            sb.append(". The group is sheltered");
        }
        if (!situation.facilities().isEmpty()) {
            sb.append(". Close by: ").append(String.join(", ",
                    situation.facilities().subList(0, Math.min(4, situation.facilities().size()))));
        }
        sb.append('.');
        return sb.toString();
    }

    /** Plain-word reading of the hunger bar so a 3B model doesn't misjudge "16/20". */
    static String hungerWord(int hunger) {
        if (hunger >= 14) {
            return " (well fed)";
        }
        if (hunger >= 8) {
            return " (peckish)";
        }
        return " (hungry)";
    }

    // === Bounded party history (records already speaker-tagged; replayed verbatim) ===

    private List<SoulTypes.Message> boundedHistory(List<SoulTypes.ConversationRecord> partyHistory) {
        List<SoulTypes.ConversationRecord> relevant = new ArrayList<>();
        for (SoulTypes.ConversationRecord record : partyHistory) {
            if (record.kind() == SoulTypes.TurnKind.HEARD
                    && record.content().startsWith(BANTER_HEARD_PREFIX)) {
                continue; // stale banter seed — never replays as a player utterance
            }
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

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
