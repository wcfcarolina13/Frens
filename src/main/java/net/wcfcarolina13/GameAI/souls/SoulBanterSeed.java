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

    /** Supporting-topic keys an audience remembers so the same background fact doesn't ride
     *  every seed (two scenes' worth at {@value #MAX_SUPPORT} per seed). */
    static final int RECENT_SUPPORT_MEMORY = 4;

    /** Opens the cue: the ONE subject the scene is about (1.1.223 seed hierarchy). */
    static final String PRIMARY_PREFIX = "Stay on one subject: ";
    /** Frames the support facts as optional colour, not further subjects. */
    static final String BACKGROUND_PREFIX = "(background only, mention at most once: ";
    static final String SETTING_PREFIX = "(setting: ";
    static final String AVOID_PREFIX = "(do not bring up ";

    /**
     * What the seed steered toward; {@code topic}, {@code act} and {@code supportTopics} are
     * what the director remembers ({@code supportTopics} in their own ring, see
     * {@link #RECENT_SUPPORT_MEMORY}).
     */
    record Seed(String text, String topic, SoulSpeechAct act, List<String> supportTopics) {
        Seed {
            supportTopics = supportTopics == null ? List.of() : List.copyOf(supportTopics);
        }

        Seed(String text, String topic, SoulSpeechAct act) {
            this(text, topic, act, List.of());
        }

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
    record Anchor(String topic, String phrase, int weight, java.util.UUID privateOwner) {
        /** An anchor from no one's DM-private memory — every source except a PRIVATE player memory. */
        Anchor(String topic, String phrase, int weight) {
            this(topic, phrase, weight, null);
        }
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
        return buildSeed(rosterGroundings, eventsPerBot, playerName, playerActivity, random,
                recentTopics, changeAnchors, recentActs, mindAnchors, Set.of());
    }

    /**
     * @param recentSupport support-topic keys used by this audience's recent seeds
     *     ({@link Seed#supportTopics}); skipped as support unless HIGH-salience, so a standing
     *     fact ("the last hobby (woodcut)") no longer rides every seed. It never affects the
     *     primary pick or the avoid list — those rotate on {@code recentTopics} alone.
     */
    static Seed buildSeed(List<SoulTypes.GroundingSnapshot> rosterGroundings,
                          List<List<SoulTypes.SoulEvent>> eventsPerBot,
                          String playerName, String playerActivity, RandomGenerator random,
                          Set<String> recentTopics, List<Anchor> changeAnchors,
                          java.util.Collection<SoulSpeechAct> recentActs,
                          List<Anchor> mindAnchors, Set<String> recentSupport) {
        Set<String> recent = recentTopics == null ? Set.of() : recentTopics;
        Set<String> recentSupported = recentSupport == null ? Set.of() : recentSupport;
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
        // 1.1.223 seed hierarchy: small models gave every "; "-joined fragment its own line, so
        // a two-bot scene had competing subjects. Now ONE directive sentence names the subject,
        // support facts are framed as optional background, and setting / avoid-list sit in
        // their own parenthesised clauses. The assembler appends ". A few short lines…", so the
        // seed never ends with a period of its own.
        String primarySentence = null;
        Set<String> usedTopics = new LinkedHashSet<>();
        if (primary != null) {
            String verb = act == null ? "talk about" : act.directive(rosterGroundings.size() <= 1, playerName);
            primarySentence = PRIMARY_PREFIX + verb + " " + primary.phrase();
            usedTopics.add(primary.topic());
        }
        // Supporting facts: HIGH-salience events always make it in, then other fresh anchors.
        List<Anchor> support = new ArrayList<>(anchors);
        support.sort(Comparator.comparingInt(Anchor::weight).reversed());
        List<Anchor> supportPicked = new ArrayList<>();
        for (Anchor anchor : support) {
            if (supportPicked.size() >= MAX_SUPPORT) {
                break;
            }
            if (anchor == primary || usedTopics.contains(anchor.topic())) {
                continue;
            }
            if ((recent.contains(anchor.topic()) || recentSupported.contains(anchor.topic()))
                    && anchor.weight() < 6) {
                continue; // recently discussed or recently background, and not important enough
            }
            supportPicked.add(anchor);
            usedTopics.add(anchor.topic());
        }
        String setting = first == null ? null : SETTING_PREFIX + situationLine(first) + ")";
        String avoidClause = null;
        if (!recent.isEmpty()) {
            List<String> avoid = new ArrayList<>();
            for (String topic : recent) {
                // Mind anchors (memory:…, relation:REL|…) are machine keys the model can't read,
                // and the mind's own recall cooldown already keeps them off the next seeds.
                if (!usedTopics.contains(topic) && isReadableTopic(topic)) {
                    avoid.add(topic);
                }
            }
            if (!avoid.isEmpty()) {
                avoidClause = AVOID_PREFIX + String.join(" or ", avoid) + " again)";
            }
        }

        // Budget: drop whole clauses, lowest value first — support facts (last first), then the
        // setting (the prompt's state block repeats it), then the avoid list — and hard-cut
        // only as a last resort, never leaving an unclosed "(".
        String text = assemble(primarySentence, supportPicked, setting, avoidClause);
        while (text.length() > MAX_SEED_CHARS && !supportPicked.isEmpty()) {
            supportPicked.remove(supportPicked.size() - 1);
            text = assemble(primarySentence, supportPicked, setting, avoidClause);
        }
        if (text.length() > MAX_SEED_CHARS && setting != null) {
            setting = null;
            text = assemble(primarySentence, supportPicked, setting, avoidClause);
        }
        if (text.length() > MAX_SEED_CHARS && avoidClause != null) {
            avoidClause = null;
            text = assemble(primarySentence, supportPicked, setting, avoidClause);
        }
        if (text.length() > MAX_SEED_CHARS) {
            text = text.substring(0, MAX_SEED_CHARS);
            int open = text.lastIndexOf('(');
            if (open > 0 && text.indexOf(')', open) < 0) {
                text = text.substring(0, open).strip();
            }
        }
        List<String> supportTopics = new ArrayList<>(supportPicked.size());
        for (Anchor anchor : supportPicked) {
            supportTopics.add(anchor.topic());
        }
        return new Seed(text, primary == null ? "" : primary.topic(), act, supportTopics);
    }

    /**
     * "Stay on one subject: X. (background only, mention at most once: A; B) (setting: …)
     * (do not bring up … again)" — any part may be absent.
     */
    private static String assemble(String primarySentence, List<Anchor> supports, String setting,
                                   String avoidClause) {
        List<String> clauses = new ArrayList<>();
        if (!supports.isEmpty()) {
            List<String> phrases = new ArrayList<>(supports.size());
            for (Anchor anchor : supports) {
                phrases.add(anchor.phrase());
            }
            clauses.add(BACKGROUND_PREFIX + String.join("; ", phrases) + ")");
        }
        if (setting != null) {
            clauses.add(setting);
        }
        if (avoidClause != null) {
            clauses.add(avoidClause);
        }
        String tail = String.join(" ", clauses);
        if (primarySentence == null) {
            return tail;
        }
        return tail.isEmpty() ? primarySentence : primarySentence + ". " + tail;
    }

    /** A topic key the model can read as words (not a {@code memory:} / {@code relation:} key). */
    static boolean isReadableTopic(String topic) {
        return topic != null && !topic.isBlank() && topic.indexOf(':') < 0 && topic.indexOf('|') < 0;
    }

    /**
     * Appends {@code topics} to a bounded recent-topic ring, oldest evicted first. A topic
     * already present moves to the newest end instead of appearing twice, so the ring always
     * holds up to {@code cap} DISTINCT recent keys. Pure; the caller owns synchronisation.
     */
    static void rememberRecent(java.util.Deque<String> ring, java.util.Collection<String> topics, int cap) {
        if (ring == null || topics == null) {
            return;
        }
        for (String topic : topics) {
            if (topic == null || topic.isEmpty()) {
                continue;
            }
            ring.remove(topic);
            ring.addLast(topic);
        }
        while (ring.size() > Math.max(0, cap)) {
            ring.removeFirst();
        }
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
