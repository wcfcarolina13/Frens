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
    /** Seed topic of an expired-thread anchor; the director drops those threads once recalled. */
    static final String TOPIC_UNANSWERED = "unanswered question";
    /** Seed topics of memory anchors are {@code memory:<topic>}; the director marks them recalled. */
    static final String MEMORY_TOPIC_PREFIX = "memory:";

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

    // === peer stance rules (conversation ontology Phase 3a) ===

    /** At most this many peers are remembered; the flattest stance is evicted first. */
    static final int MAX_PEER_STANCES = 6;

    /**
     * Folds one delivered group scene into this bot's peer stances. Pure and game-free: the
     * caller flattens the roster into index-aligned names/ids. Three rules, each at most once
     * per peer per scene:
     *
     * <ol>
     *   <li>a peer asked this bot something (their line names this bot and ends with {@code ?})
     *       → curiosity +1 toward that peer, at most once per Minecraft day per pair;</li>
     *   <li>this bot asked the peer something and the peer never spoke again in the scene
     *       → exasperation +1, sharing the same once-per-day guard;</li>
     *   <li>both of them spoke → trust +1, at most once per Minecraft day per pair.</li>
     * </ol>
     *
     * @param selfIndex this bot's index into {@code rosterIds}
     * @return the same instance when nothing changed
     */
    static SoulTypes.SoulMind notePeerScene(SoulTypes.SoulMind mind, int selfIndex, List<String> rosterNames,
                                            List<UUID> rosterIds, List<SoulGroupTypes.SceneLine> delivered,
                                            int day) {
        if (rosterNames == null || rosterIds == null || delivered == null || delivered.isEmpty()
                || rosterNames.size() != rosterIds.size()
                || selfIndex < 0 || selfIndex >= rosterIds.size()) {
            return mind;
        }
        String selfName = rosterNames.get(selfIndex);
        Map<Integer, Integer> lastLine = new LinkedHashMap<>();
        for (int i = 0; i < delivered.size(); i++) {
            int speaker = delivered.get(i).participantIndex();
            if (speaker >= 0 && speaker < rosterIds.size()) {
                lastLine.put(speaker, i);
            }
        }
        boolean selfSpoke = lastLine.containsKey(selfIndex);

        Map<UUID, SoulTypes.PeerStance> peers = new LinkedHashMap<>(mind.peerStances());
        Set<UUID> touchedIds = new LinkedHashSet<>();
        boolean changed = false;
        for (int p = 0; p < rosterIds.size(); p++) {
            if (p == selfIndex) {
                continue;
            }
            UUID peerId = rosterIds.get(p);
            String peerName = rosterNames.get(p);
            if (peerId == null || peerName == null || peerName.isBlank()) {
                continue;
            }
            SoulTypes.PeerStance current = peers.getOrDefault(peerId, SoulTypes.PeerStance.baseline());
            SoulTypes.Stance s = current.stance();
            int trust = s.trust();
            int exasperation = s.exasperation();
            int curiosity = s.curiosity();
            int lastTrustDay = current.lastTrustDay();
            int lastAskDay = current.lastAskDay();
            boolean touched = false;

            // Both ask rules are evaluated against the OLD lastAskDay so they can still both
            // fire in the same scene, but neither may fire twice in one Minecraft day: without
            // this guard a busy day of scenes would saturate curiosity/exasperation permanently
            // (decay only steps once per day).
            boolean askAllowed = lastAskDay != day;
            boolean askFired = false;
            if (askAllowed && selfName != null && !selfName.isBlank()
                    && lastAskIndex(delivered, p, selfName) >= 0) {
                curiosity++;
                touched = true;
                askFired = true;
            }
            int myAsk = lastAskIndex(delivered, selfIndex, peerName);
            if (askAllowed && myAsk >= 0 && lastLine.getOrDefault(p, -1) < myAsk) {
                exasperation++;
                touched = true;
                askFired = true;
            }
            if (askFired) {
                lastAskDay = day;
            }
            if (selfSpoke && lastLine.containsKey(p) && lastTrustDay != day) {
                trust++;
                lastTrustDay = day;
                touched = true;
            }
            if (!touched) {
                continue;
            }
            SoulTypes.PeerStance updated = new SoulTypes.PeerStance(
                    new SoulTypes.Stance(trust, exasperation, curiosity), lastTrustDay, lastAskDay);
            touchedIds.add(peerId);
            if (!updated.equals(peers.put(peerId, updated))) {
                changed = true;
            }
        }
        if (!changed) {
            return mind;
        }
        evictPeers(peers, touchedIds);
        return withPeerStances(mind, peers);
    }

    /**
     * Index of the last line by {@code speakerIndex} that names {@code targetName}
     * (case-insensitive) and ends with a question mark, or -1 when there is none.
     */
    private static int lastAskIndex(List<SoulGroupTypes.SceneLine> delivered, int speakerIndex, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return -1;
        }
        String needle = targetName.toLowerCase(java.util.Locale.ROOT);
        int found = -1;
        for (int i = 0; i < delivered.size(); i++) {
            SoulGroupTypes.SceneLine line = delivered.get(i);
            if (line.participantIndex() != speakerIndex) {
                continue;
            }
            String text = line.text() == null ? "" : line.text().trim();
            if (text.endsWith("?") && text.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                found = i;
            }
        }
        return found;
    }

    /**
     * Trims {@code peers} to {@link #MAX_PEER_STANCES}, dropping the flattest stance first
     * (smallest Manhattan distance to {@link SoulTypes.Stance#BASELINE}, ties broken by lowest
     * UUID string so eviction is deterministic). Peers in {@code protectedIds} are never evicted.
     */
    private static void evictPeers(Map<UUID, SoulTypes.PeerStance> peers, Set<UUID> protectedIds) {
        while (peers.size() > MAX_PEER_STANCES) {
            UUID victim = null;
            int best = Integer.MAX_VALUE;
            for (Map.Entry<UUID, SoulTypes.PeerStance> entry : peers.entrySet()) {
                if (protectedIds.contains(entry.getKey())) {
                    continue;
                }
                int distance = baselineDistance(entry.getValue().stance());
                if (distance < best
                        || (distance == best && victim != null
                            && entry.getKey().toString().compareTo(victim.toString()) < 0)) {
                    best = distance;
                    victim = entry.getKey();
                }
            }
            if (victim == null) {
                return;
            }
            peers.remove(victim);
        }
    }

    private static int baselineDistance(SoulTypes.Stance s) {
        SoulTypes.Stance b = SoulTypes.Stance.BASELINE;
        return Math.abs(s.trust() - b.trust()) + Math.abs(s.exasperation() - b.exasperation())
                + Math.abs(s.curiosity() - b.curiosity());
    }

    /**
     * Marks every memory of {@code topic} as recalled on {@code day} (cools it for
     * {@link #RECALL_COOLDOWN_DAYS}). A {@code said:} key is a player-memory fact key, not a day
     * topic, so it dispatches to {@link SoulMemoryDigestOps#noteRecalled} instead.
     */
    static SoulTypes.SoulMind noteRecalled(SoulTypes.SoulMind mind, String topic, int day) {
        if (topic == null || topic.isEmpty()) {
            return mind;
        }
        if (topic.startsWith(SoulMemoryDigestOps.FACT_KEY_PREFIX)) {
            return SoulMemoryDigestOps.noteRecalled(mind, topic, day);
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
        // Peer stances decay one step a day too; a pair that reaches baseline is forgotten
        // outright so mind.json never grows a tail of bots this one feels nothing about.
        Map<UUID, SoulTypes.PeerStance> peers = new LinkedHashMap<>();
        for (Map.Entry<UUID, SoulTypes.PeerStance> entry : mind.peerStances().entrySet()) {
            SoulTypes.Stance ps = entry.getValue().stance();
            SoulTypes.Stance stepped = new SoulTypes.Stance(stepToward(ps.trust(), b.trust()),
                    stepToward(ps.exasperation(), b.exasperation()), stepToward(ps.curiosity(), b.curiosity()));
            if (!stepped.equals(b)) {
                peers.put(entry.getKey(), new SoulTypes.PeerStance(stepped, entry.getValue().lastTrustDay(),
                        entry.getValue().lastAskDay()));
            }
        }
        return SoulMemoryDigestOps.decay(withPeerStances(
                rebuild(mind, decayed, mind.threads(), memories, mind.seen(), nowMs, day, mind.lastTaskTrustDay()),
                peers));
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

    /** Word ladder for how this bot feels about ANOTHER bot; {@code ""} at baseline. */
    static String peerStanceClause(SoulTypes.Stance s, String peerName) {
        List<String> parts = new ArrayList<>(3);
        if (s.trust() <= 1) {
            parts.add("wary of " + peerName);
        } else if (s.trust() >= 5) {
            parts.add("thick as thieves with " + peerName);
        }
        if (s.exasperation() >= 4) {
            parts.add("short with " + peerName);
        } else if (s.exasperation() >= 2) {
            parts.add("a little tired of " + peerName);
        }
        if (s.curiosity() >= 5) {
            parts.add("curious about " + peerName);
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
            out.add(new SoulBanterSeed.Anchor(MEMORY_TOPIC_PREFIX + m.topic(),
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

    static SoulTypes.SoulMind withPlayerMemories(SoulTypes.SoulMind mind, List<SoulTypes.PlayerMemory> playerMemories) {
        return new SoulTypes.SoulMind(mind.schemaVersion(), mind.playerStance(), mind.threads(), mind.memories(),
                mind.seen(), mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay(),
                playerMemories, mind.archivedPlayerMemories(), mind.digestCursors(), mind.peerStances());
    }

    static SoulTypes.SoulMind withArchivedPlayerMemories(SoulTypes.SoulMind mind,
                                                          List<SoulTypes.PlayerMemory> archivedPlayerMemories) {
        return new SoulTypes.SoulMind(mind.schemaVersion(), mind.playerStance(), mind.threads(), mind.memories(),
                mind.seen(), mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay(),
                mind.playerMemories(), archivedPlayerMemories, mind.digestCursors(), mind.peerStances());
    }

    static SoulTypes.SoulMind withDigestCursors(SoulTypes.SoulMind mind,
                                                Map<String, SoulTypes.ConversationCursor> digestCursors) {
        return new SoulTypes.SoulMind(mind.schemaVersion(), mind.playerStance(), mind.threads(), mind.memories(),
                mind.seen(), mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay(),
                mind.playerMemories(), mind.archivedPlayerMemories(), digestCursors, mind.peerStances());
    }

    static SoulTypes.SoulMind withPeerStances(SoulTypes.SoulMind mind,
                                              Map<UUID, SoulTypes.PeerStance> peerStances) {
        return new SoulTypes.SoulMind(mind.schemaVersion(), mind.playerStance(), mind.threads(), mind.memories(),
                mind.seen(), mind.lastConsolidatedAtMs(), mind.lastDay(), mind.lastTaskTrustDay(),
                mind.playerMemories(), mind.archivedPlayerMemories(), mind.digestCursors(), peerStances);
    }

    private static SoulTypes.SoulMind rebuild(SoulTypes.SoulMind mind, SoulTypes.Stance stance,
                                              List<SoulTypes.OpenThread> threads, List<SoulTypes.DayMemory> memories,
                                              Set<String> seen, long lastConsolidatedAtMs, int lastDay,
                                              int lastTaskTrustDay) {
        return new SoulTypes.SoulMind(mind.schemaVersion(), stance, threads, memories, seen,
                lastConsolidatedAtMs, lastDay, lastTaskTrustDay, mind.playerMemories(),
                mind.archivedPlayerMemories(), mind.digestCursors(), mind.peerStances());
    }
}
