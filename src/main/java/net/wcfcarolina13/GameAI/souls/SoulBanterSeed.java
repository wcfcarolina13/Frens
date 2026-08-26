package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Deterministic banter topic seed (spec §4): up to {@value #MAX_EVENTS} salient witnessed events
 * from the roster bots' journals plus a one-line situation summary and the audience player's
 * apparent activity. Pure — no Minecraft imports, randomness injected. The model never chooses
 * topics; this string is the whole steering input.
 */
final class SoulBanterSeed {

    static final int MAX_EVENTS = 3;
    static final int MAX_SEED_CHARS = 400;
    /** A single event phrase (incl. fact suffix) never exceeds this. */
    static final int MAX_PHRASE_CHARS = 60;

    private SoulBanterSeed() {
    }

    /**
     * @param rosterGroundings per-bot groundings; index 0 supplies the shared situation line
     * @param eventsPerBot recent journal tails, one list per roster bot (store order, newest last)
     * @param playerActivity "" when unknown; otherwise e.g. "mining, broke stone 4s ago"
     */
    static String build(List<SoulTypes.GroundingSnapshot> rosterGroundings,
                         List<List<SoulTypes.SoulEvent>> eventsPerBot,
                         String playerName, String playerActivity, RandomGenerator random) {
        List<String> parts = new ArrayList<>();

        List<SoulTypes.SoulEvent> picked = pickEvents(eventsPerBot, random);
        for (SoulTypes.SoulEvent event : picked) {
            parts.add(phraseFor(event));
        }

        if (!rosterGroundings.isEmpty()) {
            parts.add(situationLine(rosterGroundings.get(0)));
        }
        if (playerActivity != null && !playerActivity.isBlank()) {
            parts.add(playerName + " is " + playerActivity);
        }

        String seed = String.join("; ", parts);
        return seed.length() <= MAX_SEED_CHARS ? seed : seed.substring(0, MAX_SEED_CHARS);
    }

    /** Salience-first (HIGH→NORMAL→LOW), newest-first within a tier, deduped by type; when more
     *  than {@value #MAX_EVENTS} candidates survive, the final slot is a random pick from the
     *  remainder so repeated banter doesn't always retell the same third story. */
    private static List<SoulTypes.SoulEvent> pickEvents(List<List<SoulTypes.SoulEvent>> eventsPerBot,
                                                         RandomGenerator random) {
        List<SoulTypes.SoulEvent> candidates = new ArrayList<>();
        Set<SoulTypes.EventType> seenTypes = EnumSet.noneOf(SoulTypes.EventType.class);
        List<SoulTypes.SoulEvent> flattened = new ArrayList<>();
        for (List<SoulTypes.SoulEvent> events : eventsPerBot) {
            if (events != null) {
                flattened.addAll(events);
            }
        }
        flattened.sort(Comparator
                .comparingInt((SoulTypes.SoulEvent e) -> e.salience().ordinal()).reversed()
                .thenComparing(Comparator.comparingLong(SoulTypes.SoulEvent::worldTick).reversed()));
        for (SoulTypes.SoulEvent event : flattened) {
            if (event.type() == SoulTypes.EventType.DIRECT_CONVERSATION) {
                continue;
            }
            if (seenTypes.add(event.type())) {
                candidates.add(event);
            }
        }
        if (candidates.size() <= MAX_EVENTS) {
            return candidates;
        }
        List<SoulTypes.SoulEvent> picked = new ArrayList<>(candidates.subList(0, MAX_EVENTS - 1));
        List<SoulTypes.SoulEvent> remainder = candidates.subList(MAX_EVENTS - 1, candidates.size());
        picked.add(remainder.get(random.nextInt(remainder.size())));
        return picked;
    }

    private static String phraseFor(SoulTypes.SoulEvent event) {
        String base = switch (event.type()) {
            case TASK_STARTED -> "started a task";
            case TASK_COMPLETED -> "finished a task";
            case TASK_FAILED -> "botched a task";
            case TASK_PAUSED -> "set a task aside";
            case TASK_CANCELLED -> "dropped a task";
            case BOT_DAMAGE -> "took a beating";
            case OWNER_DAMAGE -> "saw their friend get hurt";
            case COMBAT_STARTED -> "got into a fight";
            case COMBAT_ENDED -> "came out of a fight";
            case DEATH -> "died recently";
            case RESPAWN -> "came back from the dead";
            case SLEEP -> "got some sleep";
            case WAKE -> "just woke up";
            case DIMENSION_CHANGED -> "travelled between worlds";
            case QUEST_STAGE_CHANGED -> "moved a quest along";
            case MOB_KILLED -> "slew a mob";
            case SELF_RESCUE -> "dug themselves out of trouble";
            case HOBBY_SESSION -> "spent time on a hobby";
            case HUNT_PROGRESS -> "made progress on a hunt";
            case DIRECT_CONVERSATION -> ""; // filtered upstream; defensive
        };
        String factSuffix = event.facts().values().stream().limit(2)
                .reduce((a, b) -> a + ", " + b).map(s -> " (" + s + ")").orElse("");
        String phrase = base + factSuffix;
        return phrase.length() <= MAX_PHRASE_CHARS ? phrase : phrase.substring(0, MAX_PHRASE_CHARS);
    }

    private static String situationLine(SoulTypes.GroundingSnapshot grounding) {
        SoulTypes.BotSnapshot bot = grounding.bot();
        StringBuilder sb = new StringBuilder("it is ");
        sb.append(bot.timePhase().isEmpty() ? "an ordinary hour" : bot.timePhase());
        if (!bot.weather().isEmpty()) {
            sb.append(", ").append(bot.weather().toLowerCase(Locale.ROOT));
        }
        if (!bot.biome().isEmpty()) {
            sb.append(", in ").append(bot.biome());
        }
        return sb.toString();
    }
}
