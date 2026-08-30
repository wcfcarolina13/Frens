package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Deterministic banter topic seed (spec §4, topic rotation 2026-08-29): ONE primary anchor the
 * scene is steered onto, up to two supporting facts, a one-line situation summary, the
 * audience player's apparent activity, and — when the director supplies them — the topics of
 * recent scenes as an explicit "do not bring up" list. Pure — no Minecraft imports, randomness
 * injected. The model never chooses topics; this string is the whole steering input.
 *
 * <p>Why rotation: with a quiet journal the old event-first pick surfaced SLEEP + WAKE every
 * time (distinct types, so both survived the dedupe) and the bots talked about napping scene
 * after scene while standing next to the weather, their gear, the animals, their hunger, the
 * biome, their mood and the player's own activity. Anchors now come from the whole grounding,
 * carry a human-readable topic key, and a key used recently is skipped while any other exists.
 */
final class SoulBanterSeed {

    static final int MAX_EVENTS = 3;
    static final int MAX_SEED_CHARS = 400;
    /** A single event phrase (incl. fact suffix) never exceeds this. */
    static final int MAX_PHRASE_CHARS = 60;
    /** Supporting facts after the primary anchor. */
    static final int MAX_SUPPORT = 2;

    /** What the seed steered toward; {@code topic} and {@code act} are what the director remembers. */
    record Seed(String text, String topic, SoulSpeechAct act) {
        Seed(String text, String topic) {
            this(text, topic, null);
        }
    }

    /**
     * Topics that come from journal events, plus the mind's day memories ({@code memory:<topic>})
     * and unanswered threads — all RECALL material (conversation ontology Phase 2).
     */
    static boolean isEventTopic(String topic) {
        if (topic.startsWith("memory:") || topic.equals("unanswered question")) {
            return true;
        }
        return switch (topic) {
            case "the work", "getting hurt", "fighting", "dying", "sleep", "travel", "quests",
                    "getting stuck", "hobbies", "hunting" -> true;
            default -> false;
        };
    }

    /** Topics worth a worry. */
    static boolean isWorryTopic(String topic) {
        return switch (topic) {
            case "danger", "health", "food", "getting hurt", "dying", "fighting", "getting stuck" -> true;
            default -> false;
        };
    }

    /** One candidate topic: a rotation key, the phrase the model sees, and a pick weight. */
    record Anchor(String topic, String phrase, int weight) {
    }

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
        return buildSeed(rosterGroundings, eventsPerBot, playerName, playerActivity, random, Set.of()).text();
    }

    /** @param recentTopics topic keys of recent scenes for this audience — skipped while any other anchor exists */
    static Seed buildSeed(List<SoulTypes.GroundingSnapshot> rosterGroundings,
                          List<List<SoulTypes.SoulEvent>> eventsPerBot,
                          String playerName, String playerActivity, RandomGenerator random,
                          Set<String> recentTopics) {
        return buildSeed(rosterGroundings, eventsPerBot, playerName, playerActivity, random,
                recentTopics, List.of(), null);
    }

    /**
     * @param changeAnchors what changed since the audience's last scene ({@link SoulSceneDiff});
     *     they join the pool at their own (high) weight so novelty usually leads
     * @param recentActs speech acts of recent scenes — {@code null} keeps the legacy "talk about"
     *     phrasing (no act wheel); otherwise an act is picked and its directive opens the cue
     */
    static Seed buildSeed(List<SoulTypes.GroundingSnapshot> rosterGroundings,
                          List<List<SoulTypes.SoulEvent>> eventsPerBot,
                          String playerName, String playerActivity, RandomGenerator random,
                          Set<String> recentTopics, List<Anchor> changeAnchors,
                          java.util.Collection<SoulSpeechAct> recentActs) {
        return buildSeed(rosterGroundings, eventsPerBot, playerName, playerActivity, random,
                recentTopics, changeAnchors, recentActs, List.of());
    }

    /**
     * @param mindAnchors what the roster's minds contribute ({@link SoulMindOps#anchors}): day
     *     memories at weight 4 (above grounding, below a change) and unanswered threads at 5 —
     *     a HIGH event or a change still wins the primary pick
     */
    static Seed buildSeed(List<SoulTypes.GroundingSnapshot> rosterGroundings,
                          List<List<SoulTypes.SoulEvent>> eventsPerBot,
                          String playerName, String playerActivity, RandomGenerator random,
                          Set<String> recentTopics, List<Anchor> changeAnchors,
                          java.util.Collection<SoulSpeechAct> recentActs,
                          List<Anchor> mindAnchors) {
        Set<String> recent = recentTopics == null ? Set.of() : recentTopics;
        List<Anchor> anchors = new ArrayList<>();
        if (changeAnchors != null) {
            anchors.addAll(changeAnchors);
        }
        if (mindAnchors != null) {
            anchors.addAll(mindAnchors);
        }
        List<SoulTypes.SoulEvent> picked = pickEvents(eventsPerBot, random);
        for (SoulTypes.SoulEvent event : picked) {
            anchors.add(new Anchor(topicOf(event), phraseFor(event), weightOf(event)));
        }
        SoulTypes.GroundingSnapshot first = rosterGroundings.isEmpty() ? null : rosterGroundings.get(0);
        if (first != null) {
            anchors.addAll(groundingAnchors(first));
        }
        if (playerActivity != null && !playerActivity.isBlank()) {
            anchors.add(new Anchor("the player", playerName + " is " + playerActivity, 3));
        }
        if (first != null && !first.overheard().isEmpty()) {
            anchors.add(new Anchor("what was overheard", playerName + " was saying: "
                    + truncatePhrase(first.overheard().get(first.overheard().size() - 1)), 4));
        }

        // Rotation: drop anchors whose topic came up recently, unless that empties the pool.
        List<Anchor> fresh = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (!recent.contains(anchor.topic())) {
                fresh.add(anchor);
            }
        }
        List<Anchor> pool = fresh.isEmpty() ? anchors : fresh;

        Anchor primary = pool.isEmpty() ? null : weightedPick(pool, random);
        SoulSpeechAct act = null;
        if (recentActs != null) {
            boolean hasEvent = false;
            boolean hasWorry = false;
            for (Anchor anchor : anchors) {
                hasEvent |= isEventTopic(anchor.topic());
                hasWorry |= isWorryTopic(anchor.topic());
            }
            act = SoulSpeechAct.pick(hasEvent, hasWorry, recentActs, random);
            // RECALL and WORRY need a matching primary: re-pick the primary among fitting anchors.
            if (primary != null && act == SoulSpeechAct.RECALL && !isEventTopic(primary.topic())) {
                primary = firstMatching(pool, anchors, SoulBanterSeed::isEventTopic, random, primary);
            } else if (primary != null && act == SoulSpeechAct.WORRY && !isWorryTopic(primary.topic())) {
                primary = firstMatching(pool, anchors, SoulBanterSeed::isWorryTopic, random, primary);
            }
        }
        List<String> parts = new ArrayList<>();
        Set<String> usedTopics = new LinkedHashSet<>();
        if (primary != null) {
            String verb = act == null ? "talk about" : act.directive(rosterGroundings.size() <= 1, playerName);
            parts.add(verb + " " + primary.phrase());
            usedTopics.add(primary.topic());
        }
        // Supporting facts: HIGH-salience events always make it in, then other fresh anchors.
        List<Anchor> support = new ArrayList<>(anchors);
        support.sort(Comparator.comparingInt(Anchor::weight).reversed());
        for (Anchor anchor : support) {
            if (usedTopics.size() > MAX_SUPPORT) {
                break;
            }
            if (anchor == primary || usedTopics.contains(anchor.topic())) {
                continue;
            }
            if (recent.contains(anchor.topic()) && anchor.weight() < 6) {
                continue; // recently discussed and not important enough to force back in
            }
            parts.add(anchor.phrase());
            usedTopics.add(anchor.topic());
        }
        if (first != null) {
            parts.add(situationLine(first));
        }
        if (!recent.isEmpty()) {
            List<String> avoid = new ArrayList<>();
            for (String topic : recent) {
                if (!usedTopics.contains(topic)) {
                    avoid.add(topic);
                }
            }
            if (!avoid.isEmpty()) {
                parts.add("do not bring up " + String.join(" or ", avoid) + " again");
            }
        }

        String seed = String.join("; ", parts);
        String text = seed.length() <= MAX_SEED_CHARS ? seed : seed.substring(0, MAX_SEED_CHARS);
        return new Seed(text, primary == null ? "" : primary.topic(), act);
    }

    /** A weighted pick among anchors whose topic passes {@code test} (fresh pool first), else {@code fallback}. */
    private static Anchor firstMatching(List<Anchor> pool, List<Anchor> all,
                                        java.util.function.Predicate<String> test,
                                        RandomGenerator random, Anchor fallback) {
        List<Anchor> fitting = new ArrayList<>();
        for (Anchor anchor : pool) {
            if (test.test(anchor.topic())) {
                fitting.add(anchor);
            }
        }
        if (fitting.isEmpty()) {
            for (Anchor anchor : all) {
                if (test.test(anchor.topic())) {
                    fitting.add(anchor);
                }
            }
        }
        return fitting.isEmpty() ? fallback : weightedPick(fitting, random);
    }

    /** Everything the grounding offers to talk about besides journal events. Weight 2 unless noted. */
    static List<Anchor> groundingAnchors(SoulTypes.GroundingSnapshot grounding) {
        SoulTypes.BotSnapshot bot = grounding.bot();
        SoulTypes.SituationSnapshot situation = grounding.situation();
        List<Anchor> out = new ArrayList<>();
        if (!bot.weather().isEmpty() || !bot.timePhase().isEmpty()) {
            out.add(new Anchor("the weather", "the " + (bot.weather().isEmpty() ? "" : bot.weather().toLowerCase(Locale.ROOT) + " ")
                    + (bot.timePhase().isEmpty() ? "sky" : bot.timePhase()), 2));
        }
        if (!bot.biome().isEmpty()) {
            out.add(new Anchor("the land", "this " + bot.biome() + " country", 2));
        }
        if (!bot.heldItem().isEmpty()) {
            out.add(new Anchor("gear", "the " + bot.heldItem() + " in hand", 2));
        }
        if (bot.hunger() >= 0 && bot.hunger() < 12) {
            out.add(new Anchor("food", "being hungry (" + bot.hunger() + "/20)", 3));
        }
        if (bot.maxHealth() > 0 && bot.health() < bot.maxHealth() * 0.6f) {
            out.add(new Anchor("health", "nursing wounds (" + Math.round(bot.health()) + "/" + Math.round(bot.maxHealth()) + ")", 3));
        }
        if (!bot.mood().isEmpty()) {
            out.add(new Anchor("mood", "feeling " + bot.mood(), 1));
        }
        if (!situation.nearbyAnimals().isEmpty()) {
            out.add(new Anchor("animals", "the " + String.join(" and ", situation.nearbyAnimals().subList(0, Math.min(2, situation.nearbyAnimals().size()))) + " nearby", 2));
        }
        if (!situation.nearbyBlocks().isEmpty()) {
            out.add(new Anchor("terrain", "the " + String.join(", ", situation.nearbyBlocks().subList(0, Math.min(3, situation.nearbyBlocks().size()))) + " around here", 1));
        } else if (!situation.standingOn().isEmpty()) {
            out.add(new Anchor("terrain", "standing on " + situation.standingOn(), 1));
        }
        if (!situation.facilities().isEmpty()) {
            out.add(new Anchor("facilities", "the " + truncatePhrase(situation.facilities().get(0)).toLowerCase(Locale.ROOT), 1));
        }
        if (!bot.notableItems().isEmpty()) {
            out.add(new Anchor("loot", "carrying " + String.join(", ", bot.notableItems().subList(0, Math.min(2, bot.notableItems().size()))), 2));
        } else if (!bot.resourceSummary().isEmpty()) {
            out.add(new Anchor("loot", "the haul so far (" + String.join(", ", bot.resourceSummary().subList(0, Math.min(2, bot.resourceSummary().size()))) + ")", 2));
        }
        if (!bot.wornGear().isEmpty()) {
            out.add(new Anchor("armor", "wearing " + String.join(", ", bot.wornGear().subList(0, Math.min(2, bot.wornGear().size()))), 1));
        }
        situation.lastHobby().ifPresent(h -> out.add(new Anchor("hobbies", "the last hobby (" + h + ")", 2)));
        situation.hunt().ifPresent(h -> out.add(new Anchor("hunting", "the hunt for " + h.target() + " (" + h.kills() + "/" + h.goal() + ")", 3)));
        situation.atBase().ifPresent(b -> out.add(new Anchor("home", "being home at " + b, 2)));
        situation.mount().ifPresent(m -> out.add(new Anchor("the mount", "the " + m.type() + (m.saddled() ? " under saddle" : ""), 2)));
        if (situation.companionDays() > 0) {
            out.add(new Anchor("the journey", situation.companionDays() + " days travelling together", 1));
        }
        if (situation.deathCount() > 0) {
            out.add(new Anchor("deaths", "having died " + situation.deathCount() + (situation.deathCount() == 1 ? " time" : " times"), 1));
        }
        bot.activeQuest().ifPresent(q -> out.add(new Anchor("quests", "the quest (" + q.intent() + ")", 3)));
        return out;
    }

    private static Anchor weightedPick(List<Anchor> pool, RandomGenerator random) {
        int total = 0;
        for (Anchor anchor : pool) {
            total += Math.max(1, anchor.weight());
        }
        int roll = random.nextInt(total);
        for (Anchor anchor : pool) {
            roll -= Math.max(1, anchor.weight());
            if (roll < 0) {
                return anchor;
            }
        }
        return pool.get(pool.size() - 1);
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

    /** SLEEP and WAKE are one topic ("sleep"); the rest group by what they are about. */
    /** A sleep *task* is the same topic as the SLEEP/WAKE journal events, so rotation demotes it too. */
    static String topicOf(SoulTypes.SoulEvent event) {
        return isSleepTask(event) ? "sleep" : topicOf(event.type());
    }

    static String topicOf(SoulTypes.EventType type) {
        return switch (type) {
            case TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_PAUSED, TASK_CANCELLED -> "the work";
            case BOT_DAMAGE, OWNER_DAMAGE -> "getting hurt";
            case COMBAT_STARTED, COMBAT_ENDED, MOB_KILLED -> "fighting";
            case DEATH, RESPAWN -> "dying";
            case SLEEP, WAKE -> "sleep";
            case DIMENSION_CHANGED -> "travel";
            case QUEST_STAGE_CHANGED -> "quests";
            case SELF_RESCUE -> "getting stuck";
            case HOBBY_SESSION -> "hobbies";
            case HUNT_PROGRESS -> "hunting";
            case DIRECT_CONVERSATION -> "conversation";
        };
    }

    static int weightOf(SoulTypes.SoulEvent event) {
        if (event.type() == SoulTypes.EventType.SLEEP || event.type() == SoulTypes.EventType.WAKE
                || isSleepTask(event)) {
            return 1; // routine — never let a nap outrank the world around the bots
        }
        return switch (event.salience()) {
            case HIGH -> 6;
            case NORMAL -> 3;
            case LOW -> 1;
        };
    }

    /**
     * One human phrase per journal event. Facts are rendered per type, never dumped raw: the
     * old generic "(value, value)" suffix put "(skill:sleep, skill)" in front of the model, which
     * promptly invented a game mechanic called "the sleep skill" (1.1.196 field bug).
     */
    static String phraseFor(SoulTypes.SoulEvent event) {
        String task = humanTask(event);
        String phrase = switch (event.type()) {
            case TASK_STARTED -> task.isEmpty() ? "started a task" : "started " + task;
            case TASK_COMPLETED -> task.isEmpty() ? "finished a task" : "finished " + task;
            case TASK_FAILED -> (task.isEmpty() ? "botched a task" : "botched " + task) + reasonSuffix(event);
            case TASK_PAUSED -> task.isEmpty() ? "set a task aside" : "set " + task + " aside";
            case TASK_CANCELLED -> task.isEmpty() ? "dropped a task" : "dropped " + task;
            case BOT_DAMAGE -> "took a beating" + fromSuffix(event, "source");
            case OWNER_DAMAGE -> "saw their friend get hurt";
            case COMBAT_STARTED -> "got into a fight";
            case COMBAT_ENDED -> "came out of a fight";
            case DEATH -> "died recently" + fromSuffix(event, "source");
            case RESPAWN -> "came back from the dead";
            case SLEEP -> "got some sleep";
            case WAKE -> "just woke up";
            case DIMENSION_CHANGED -> "travelled between worlds";
            case QUEST_STAGE_CHANGED -> "moved a quest along";
            case MOB_KILLED -> "slew a " + humanFact(event, "mob", "mob");
            case SELF_RESCUE -> "dug themselves out of trouble";
            case HOBBY_SESSION -> "spent time on a hobby";
            case HUNT_PROGRESS -> "made progress on a hunt";
            case DIRECT_CONVERSATION -> ""; // filtered upstream; defensive
        };
        return phrase.length() <= MAX_PHRASE_CHARS ? phrase : phrase.substring(0, MAX_PHRASE_CHARS);
    }

    private static boolean isSleepTask(SoulTypes.SoulEvent event) {
        String task = event.facts().getOrDefault("task", "").trim().toLowerCase(Locale.ROOT);
        return task.equals("skill:sleep") || task.equals("sleep");
    }

    /** "skill:woodcut" → "woodcutting"; "" when the event carries no task fact. */
    private static String humanTask(SoulTypes.SoulEvent event) {
        String task = event.facts().getOrDefault("task", "").trim();
        if (task.isEmpty()) {
            return "";
        }
        return isSleepTask(event) ? "sleeping" : SoulGroupPromptAssembler.humanizeTask(task);
    }

    /** A fact value as prose: "NO_TOOL" → "no tool", "Zombie" → "zombie"; {@code fallback} when absent. */
    private static String humanFact(SoulTypes.SoulEvent event, String key, String fallback) {
        String value = event.facts().getOrDefault(key, "").trim();
        return value.isEmpty() ? fallback : value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String reasonSuffix(SoulTypes.SoulEvent event) {
        String reason = humanFact(event, "reason", "");
        return reason.isEmpty() ? "" : " (" + reason + ")";
    }

    private static String fromSuffix(SoulTypes.SoulEvent event, String key) {
        String source = humanFact(event, key, "");
        return source.isEmpty() ? "" : " from a " + source;
    }

    /** Caps a raw phrase at {@link #MAX_PHRASE_CHARS}, same bound as {@link #phraseFor}. */
    private static String truncatePhrase(String phrase) {
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
