package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Pure, deterministic rules for a bot's {@link SoulTypes.SoulMind}: the stance ladder toward
 * the player, open-thread lifecycle, day consolidation of the event journal into
 * {@link SoulTypes.DayMemory}s, and the first-sighting registry. No LLM, no I/O, no game
 * classes — every method returns a new mind (or the same instance when nothing changed) so
 * callers can hand the result straight to {@code SoulStore.updateMind}.
 */
final class SoulMindOps {

    static final int MAX_THREADS = 3;
    static final int MAX_MEMORIES = 30;
    static final int MAX_SEEN = 400;
    static final int MAX_QUESTION_CHARS = 120;
    static final int MAX_MEMORY_PHRASE_CHARS = 80;
    static final int MEMORIES_PER_DAY = 3;
    /** Real time the player has to answer a bot's question before it counts as ignored. */
    static final long THREAD_TTL_MS = 600_000L;

    /** Seed anchor weights: grounding 1-3 < memory 4 < change 5 < HIGH event 6. */
    static final int MEMORY_ANCHOR_WEIGHT = 4;
    static final int THREAD_ANCHOR_WEIGHT = 5;
    /** At most this many memory anchors per seed: the strongest, plus one at random. */
    static final int MAX_MEMORY_ANCHORS = 2;
    /** A memory recalled on day N is not offered again until day N + this. */
    static final int RECALL_COOLDOWN_DAYS = 3;

    private static final String TOPIC_SLEEP = "sleep";
    private static final String TOPIC_UNANSWERED = "unanswered question";

    private SoulMindOps() {
    }

    // === seen-registry ===

    /** Adds {@code newKeys}; once past {@link #MAX_SEEN} the oldest keys (insertion order) go. */
    static SoulTypes.SoulMind withSeen(SoulTypes.SoulMind mind, Set<String> newKeys) {
        if (newKeys == null || newKeys.isEmpty() || mind.seen().containsAll(newKeys)) {
            return mind;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(mind.seen());
        merged.addAll(newKeys);
        Iterator<String> oldest = merged.iterator();
        while (merged.size() > MAX_SEEN) {
            oldest.next();
            oldest.remove();
        }
        return rebuild(mind, mind.playerStance(), mind.threads(), mind.memories(), merged,
                mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay());
    }

    // === open threads ===

    /** Appends a thread (question capped at {@link #MAX_QUESTION_CHARS}); the oldest goes past the cap. */
    static SoulTypes.SoulMind openThread(SoulTypes.SoulMind mind, SoulTypes.OpenThread thread) {
        String question = truncateQuestion(thread.question());
        SoulTypes.OpenThread capped = question.equals(thread.question()) ? thread
                : new SoulTypes.OpenThread(thread.askerBotId(), question, thread.askedAtMs(), thread.expired());
        List<SoulTypes.OpenThread> threads = new ArrayList<>(mind.threads());
        threads.add(capped);
        while (threads.size() > MAX_THREADS) {
            threads.remove(0);
        }
        return withThreads(mind, mind.playerStance(), threads);
    }

    /**
     * The player answered: every thread is closed. Only a still-open (non-expired) thread earns
     * the stance move (trust +1, curiosity -1) — answering after the window is just tidying up.
     */
    static SoulTypes.SoulMind markAnswered(SoulTypes.SoulMind mind) {
        if (mind.threads().isEmpty()) {
            return mind;
        }
        boolean anyOpen = mind.threads().stream().anyMatch(t -> !t.expired());
        SoulTypes.Stance stance = mind.playerStance();
        if (anyOpen) {
            stance = new SoulTypes.Stance(stance.trust() + 1, stance.exasperation(), stance.curiosity() - 1);
        }
        return withThreads(mind, stance, List.of());
    }

    /** Open threads older than {@link #THREAD_TTL_MS} flip to expired; exasperation +1 for each. */
    static SoulTypes.SoulMind expireThreads(SoulTypes.SoulMind mind, long nowMs) {
        List<SoulTypes.OpenThread> threads = new ArrayList<>(mind.threads().size());
        int newlyExpired = 0;
        for (SoulTypes.OpenThread t : mind.threads()) {
            if (!t.expired() && nowMs - t.askedAtMs() > THREAD_TTL_MS) {
                threads.add(new SoulTypes.OpenThread(t.askerBotId(), t.question(), t.askedAtMs(), true));
                newlyExpired++;
            } else {
                threads.add(t);
            }
        }
        if (newlyExpired == 0) {
            return mind;
        }
        SoulTypes.Stance s = mind.playerStance();
        return withThreads(mind, new SoulTypes.Stance(s.trust(), s.exasperation() + newlyExpired, s.curiosity()),
                threads);
    }

    /** Removes expired threads — called once the seed has recalled them. */
    static SoulTypes.SoulMind dropExpired(SoulTypes.SoulMind mind) {
        List<SoulTypes.OpenThread> open = mind.threads().stream().filter(t -> !t.expired()).toList();
        return open.size() == mind.threads().size() ? mind : withThreads(mind, mind.playerStance(), open);
    }

    // === stance rules ===

    /** The player gave this bot a task: trust +1, at most once per Minecraft day. */
    static SoulTypes.SoulMind noteTaskGiven(SoulTypes.SoulMind mind, int day) {
        if (mind.lastTaskTrustDay() == day) {
            return mind;
        }
        SoulTypes.Stance s = mind.playerStance();
        return rebuild(mind, new SoulTypes.Stance(s.trust() + 1, s.exasperation(), s.curiosity()),
                mind.threads(), mind.memories(), mind.seen(), mind.lastConsolidatedAtMs(), mind.lastDay(), day);
    }

    /** The bot saw its player get hurt: curiosity +1. */
    static SoulTypes.SoulMind noteOwnerHurt(SoulTypes.SoulMind mind) {
        SoulTypes.Stance s = mind.playerStance();
        return withStance(mind, new SoulTypes.Stance(s.trust(), s.exasperation(), s.curiosity() + 1));
    }

    /** Marks every memory of {@code topic} as recalled on {@code day} (cools it for {@link #RECALL_COOLDOWN_DAYS}). */
    static SoulTypes.SoulMind noteRecalled(SoulTypes.SoulMind mind, String topic, int day) {
        if (topic == null || topic.isEmpty()) {
            return mind;
        }
        boolean changed = false;
        List<SoulTypes.DayMemory> memories = new ArrayList<>(mind.memories().size());
        for (SoulTypes.DayMemory m : mind.memories()) {
            if (m.topic().equals(topic) && m.lastRecalledDay() != day) {
                memories.add(new SoulTypes.DayMemory(m.day(), m.topic(), m.phrase(), m.place(),
                        m.participants(), m.salience(), day));
                changed = true;
            } else {
                memories.add(m);
            }
        }
        return changed ? withMemories(mind, memories) : mind;
    }

    /**
     * A delivered line becomes an open thread when it ends with {@code ?} and was aimed at the
     * player — either the whole scene was player-addressed, or the line names the owner.
     */
    static Optional<String> extractQuestion(String lastLineText, String ownerName, boolean addressPlayer) {
        if (lastLineText == null) {
            return Optional.empty();
        }
        String text = lastLineText.trim();
        if (text.isEmpty() || text.charAt(text.length() - 1) != '?') {
            return Optional.empty();
        }
        if (!addressPlayer
                && !SoulGroupResponseValidator.addressesOwner(text, SoulGroupResponseValidator.normalize(ownerName))) {
            return Optional.empty();
        }
        return Optional.of(truncateQuestion(text));
    }

    // === day consolidation ===

    /**
     * Distils one Minecraft day's events into at most {@link #MEMORIES_PER_DAY} memories, then
     * ages the mind: every existing memory loses one salience (evicted at 0), the stance walks
     * one step toward {@link SoulTypes.Stance#BASELINE}, and the memory list is capped at
     * {@link #MAX_MEMORIES} (lowest salience dropped first). Threads are untouched here — the
     * runtime ticks {@link #expireThreads} on its own clock.
     *
     * <p>Grouping is by {@link SoulBanterSeed#topicOf}; sleep (SLEEP/WAKE and sleep tasks) and
     * direct conversations (no phrase) never become memories, nor does a topic already held for
     * this day. Groups rank by their strongest single event first, then by score
     * ({@code sum(HIGH 6 / NORMAL 3 / LOW 1) + min(count, 3)}), so one dramatic rescue
     * outranks two ordinary scrapes; the score is what the memory carries as salience.
     */
    static SoulTypes.SoulMind consolidate(SoulTypes.SoulMind mind, List<SoulTypes.SoulEvent> events, int day,
                                          String place, Function<UUID, String> nameOf, long nowMs) {
        Map<String, List<SoulTypes.SoulEvent>> groups = new LinkedHashMap<>();
        for (SoulTypes.SoulEvent event : events) {
            if (event.type() == SoulTypes.EventType.DIRECT_CONVERSATION) {
                continue;
            }
            String topic = SoulBanterSeed.topicOf(event);
            if (topic.equals(TOPIC_SLEEP)) {
                continue;
            }
            groups.computeIfAbsent(topic, k -> new ArrayList<>()).add(event);
        }
        Set<String> heldToday = new LinkedHashSet<>();
        for (SoulTypes.DayMemory m : mind.memories()) {
            if (m.day() == day) {
                heldToday.add(m.topic());
            }
        }

        record Group(String topic, List<SoulTypes.SoulEvent> events, int score, int peak) {
        }
        List<Group> scored = new ArrayList<>();
        for (Map.Entry<String, List<SoulTypes.SoulEvent>> entry : groups.entrySet()) {
            if (heldToday.contains(entry.getKey())) {
                continue;
            }
            int sum = 0;
            int peak = 0;
            for (SoulTypes.SoulEvent e : entry.getValue()) {
                int s = salienceScore(e.salience());
                sum += s;
                peak = Math.max(peak, s);
            }
            scored.add(new Group(entry.getKey(), entry.getValue(),
                    sum + Math.min(entry.getValue().size(), 3), peak));
        }
        scored.sort(Comparator.comparingInt(Group::peak).reversed().thenComparing(
                Comparator.comparingInt(Group::score).reversed()));

        List<SoulTypes.DayMemory> memories = new ArrayList<>();
        for (SoulTypes.DayMemory m : mind.memories()) {
            if (m.salience() - 1 > 0) {
                memories.add(new SoulTypes.DayMemory(m.day(), m.topic(), m.phrase(), m.place(),
                        m.participants(), m.salience() - 1, m.lastRecalledDay()));
            }
        }
        String where = place == null ? "" : place;
        for (Group group : scored.subList(0, Math.min(MEMORIES_PER_DAY, scored.size()))) {
            SoulTypes.SoulEvent latest = group.events().get(0);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (SoulTypes.SoulEvent e : group.events()) {
                if (!e.occurredAt().isBefore(latest.occurredAt())) {
                    latest = e;
                }
                for (UUID id : e.participants()) {
                    String name = nameOf.apply(id);
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
            }
            memories.add(new SoulTypes.DayMemory(day, group.topic(),
                    truncate(SoulBanterSeed.phraseFor(latest), MAX_MEMORY_PHRASE_CHARS),
                    where, new ArrayList<>(names), group.score(), -1));
        }
        while (memories.size() > MAX_MEMORIES) {
            int weakest = 0;
            for (int i = 1; i < memories.size(); i++) {
                if (memories.get(i).salience() < memories.get(weakest).salience()) {
                    weakest = i;
                }
            }
            memories.remove(weakest);
        }

        SoulTypes.Stance s = mind.playerStance();
        SoulTypes.Stance b = SoulTypes.Stance.BASELINE;
        SoulTypes.Stance decayed = new SoulTypes.Stance(stepToward(s.trust(), b.trust()),
                stepToward(s.exasperation(), b.exasperation()), stepToward(s.curiosity(), b.curiosity()));
        return rebuild(mind, decayed, mind.threads(), memories, mind.seen(), nowMs, day, mind.lastTaskTrustDay());
    }

    // === prompt + seed views ===

    /** Word ladder for the prompt's state block; {@code ""} at baseline. */
    static String stanceClause(SoulTypes.Stance s, String playerName) {
        List<String> parts = new ArrayList<>(3);
        if (s.trust() <= 1) {
            parts.add("wary of " + playerName);
        } else if (s.trust() >= 5) {
            parts.add("would follow " + playerName + " anywhere");
        }
        if (s.exasperation() >= 4) {
            parts.add("sulking at being ignored");
        } else if (s.exasperation() >= 2) {
            parts.add("fed up with being ignored");
        }
        if (s.curiosity() >= 5) {
            parts.add("full of questions for " + playerName);
        }
        return String.join(", ", parts);
    }

    /**
     * Seed anchors from the mind: one per expired thread (weight {@link #THREAD_ANCHOR_WEIGHT}),
     * then up to {@link #MAX_MEMORY_ANCHORS} memories (weight {@link #MEMORY_ANCHOR_WEIGHT}) —
     * the strongest plus one at random — skipping any recalled within {@link #RECALL_COOLDOWN_DAYS}.
     */
    static List<SoulBanterSeed.Anchor> anchors(SoulTypes.SoulMind mind, String botName, int currentDay,
                                               RandomGenerator random) {
        List<SoulBanterSeed.Anchor> out = new ArrayList<>();
        for (SoulTypes.OpenThread t : mind.threads()) {
            if (t.expired()) {
                out.add(new SoulBanterSeed.Anchor(TOPIC_UNANSWERED,
                        botName + " never got an answer about \"" + t.question() + "\"", THREAD_ANCHOR_WEIGHT));
            }
        }
        List<SoulTypes.DayMemory> eligible = new ArrayList<>();
        for (SoulTypes.DayMemory m : mind.memories()) {
            if (m.lastRecalledDay() < 0 || currentDay - m.lastRecalledDay() >= RECALL_COOLDOWN_DAYS) {
                eligible.add(m);
            }
        }
        if (eligible.isEmpty()) {
            return out;
        }
        eligible.sort(Comparator.comparingInt(SoulTypes.DayMemory::salience).reversed());
        List<SoulTypes.DayMemory> picked = new ArrayList<>(MAX_MEMORY_ANCHORS);
        picked.add(eligible.get(0));
        if (eligible.size() > 1 && MAX_MEMORY_ANCHORS > 1) {
            picked.add(eligible.get(1 + random.nextInt(eligible.size() - 1)));
        }
        for (SoulTypes.DayMemory m : picked) {
            String when = m.day() < currentDay ? " on day " + m.day() : "";
            out.add(new SoulBanterSeed.Anchor("memory:" + m.topic(),
                    "remember when " + m.phrase() + when, MEMORY_ANCHOR_WEIGHT));
        }
        return out;
    }

    // === helpers ===

    private static int salienceScore(SoulTypes.Salience salience) {
        return switch (salience) {
            case HIGH -> 6;
            case NORMAL -> 3;
            case LOW -> 1;
        };
    }

    private static int stepToward(int value, int target) {
        return value == target ? value : value + Integer.signum(target - value);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** Caps a question at {@link #MAX_QUESTION_CHARS} but keeps its trailing {@code ?}. */
    private static String truncateQuestion(String question) {
        if (question.length() <= MAX_QUESTION_CHARS) {
            return question;
        }
        boolean asks = question.endsWith("?");
        return asks ? question.substring(0, MAX_QUESTION_CHARS - 1) + "?" : question.substring(0, MAX_QUESTION_CHARS);
    }

    private static SoulTypes.SoulMind withStance(SoulTypes.SoulMind mind, SoulTypes.Stance stance) {
        return rebuild(mind, stance, mind.threads(), mind.memories(), mind.seen(),
                mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay());
    }

    private static SoulTypes.SoulMind withThreads(SoulTypes.SoulMind mind, SoulTypes.Stance stance,
                                                  List<SoulTypes.OpenThread> threads) {
        return rebuild(mind, stance, threads, mind.memories(), mind.seen(),
                mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay());
    }

    private static SoulTypes.SoulMind withMemories(SoulTypes.SoulMind mind, List<SoulTypes.DayMemory> memories) {
        return rebuild(mind, mind.playerStance(), mind.threads(), memories, mind.seen(),
                mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay());
    }

    private static SoulTypes.SoulMind rebuild(SoulTypes.SoulMind mind, SoulTypes.Stance stance,
                                              List<SoulTypes.OpenThread> threads, List<SoulTypes.DayMemory> memories,
                                              Set<String> seen, long lastConsolidatedAtMs, int lastDay,
                                              int lastTaskTrustDay) {
        return new SoulTypes.SoulMind(mind.schemaVersion(), stance, threads, memories, seen,
                lastConsolidatedAtMs, lastDay, lastTaskTrustDay);
    }
}
