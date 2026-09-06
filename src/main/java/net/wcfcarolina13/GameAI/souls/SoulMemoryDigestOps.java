package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.random.RandomGenerator;

/**
 * Pure, deterministic rules for the player-memory digest: gathering transcript material for a
 * (bot, player) pair, validating the summarizer's output, merging accepted facts into a mind's
 * {@link SoulTypes.PlayerMemory} list, day decay, recall bumps, banter anchors, the prompt
 * {@code ABOUT} block, and reset archival. No LLM, no I/O, no game classes — every method
 * returns a new value (or the same instance when nothing changed) so callers can hand the
 * result straight to {@code SoulStore.updateMind}.
 */
final class SoulMemoryDigestOps {

    // === gathering ===
    /** Below this many player lines since the cursor the digest does not run at all. */
    static final int MIN_PLAYER_LINES = 4;
    /** Newest records considered per (bot, player) material. */
    static final int MAX_RECORDS = 40;
    /** Hard cap on the rendered material handed to the summarizer. */
    static final int MAX_MATERIAL_CHARS = 2_000;

    // === validation ===
    /** More fact lines than this means the model ran away; the whole output is rejected. */
    static final int RUNAWAY_LINES = 8;
    static final int MAX_FACTS = 5;
    static final int MAX_FACT_CHARS = 100;

    // === salience ===
    static final int INITIAL_SALIENCE = 10;
    static final int MAX_SALIENCE = 10;
    /** Salience granted when a scene recalls a memory. */
    static final int RECALL_BUMP = 3;
    /** Salience granted when a new digest restates an existing memory. */
    static final int DUP_BUMP = 2;
    /** Word-set overlap at or above which two facts are the same memory. */
    static final double DUP_JACCARD = 0.6d;
    static final int MAX_PER_PLAYER = 24;
    static final int MAX_ARCHIVED = 100;

    // === injection ===
    static final int MAX_ABOUT_LINES = 5;
    static final int MAX_ABOUT_CHARS = 300;

    /** Fact keys are {@code said:<8 hex>} so an anchor can carry one without an index. */
    static final String FACT_KEY_PREFIX = "said:";

    private static final Pattern THEY = Pattern.compile("\\bthey\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_TOKEN = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Package-private: SoulNoveltyPolicy reuses the same vocabulary rather than duplicating it. */
    static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "to", "of", "in", "on", "is", "are", "was", "were",
            "be", "it", "that", "this", "they", "their", "them");

    private SoulMemoryDigestOps() {
    }

    /**
     * Transcript material for one (bot, player) pair: the records that survived filtering and
     * the caps, the rendered {@code Name: line} text, how many of the kept records were player
     * speech, and the cursor to store once this material has been digested.
     */
    record Material(List<SoulTypes.ConversationRecord> records, String text, int playerLines,
                    SoulTypes.ConversationCursor next) {
        Material {
            records = records == null ? List.of() : List.copyOf(records);
            text = text == null ? "" : text;
        }
    }

    // === gathering ===

    /**
     * Filters, caps and renders {@code sinceRecords} (one epoch, file order) into material.
     * FAILUREs and synthetic banter narrator lines are dropped; in {@code party} mode a scene
     * is kept whole only when the bot was in it. The returned cursor advances past every input
     * record — including the dropped ones — so nothing is re-read; an empty input keeps
     * {@code from}.
     */
    static Material gather(List<SoulTypes.ConversationRecord> sinceRecords,
                           SoulTypes.ConversationCursor from, UUID botId, String botName,
                           String playerName, boolean party) {
        List<SoulTypes.ConversationRecord> all = sinceRecords == null ? List.of() : sinceRecords;
        if (all.isEmpty()) {
            return new Material(List.of(), "", 0, from);
        }
        long maxSequence = all.get(0).sequence();
        for (SoulTypes.ConversationRecord record : all) {
            maxSequence = Math.max(maxSequence, record.sequence());
        }
        SoulTypes.ConversationCursor next =
                new SoulTypes.ConversationCursor(all.get(0).epoch(), maxSequence + 1);

        List<SoulTypes.ConversationRecord> usable = new ArrayList<>();
        for (SoulTypes.ConversationRecord record : all) {
            if (record.kind() != SoulTypes.TurnKind.HEARD && record.kind() != SoulTypes.TurnKind.SPOKEN) {
                continue;
            }
            if (record.kind() == SoulTypes.TurnKind.HEARD
                    && record.content().startsWith(SoulGroupPromptAssembler.BANTER_HEARD_PREFIX)) {
                continue;
            }
            usable.add(record);
        }
        if (party) {
            usable = scenesTheBotWasIn(usable, botId, botName);
        }
        if (usable.size() > MAX_RECORDS) {
            usable = new ArrayList<>(usable.subList(usable.size() - MAX_RECORDS, usable.size()));
        }

        List<SoulTypes.ConversationRecord> kept = new ArrayList<>(usable);
        List<String> lines = new ArrayList<>(kept.size());
        for (SoulTypes.ConversationRecord record : kept) {
            lines.add(render(record, botName, playerName, party));
        }
        while (!lines.isEmpty() && length(lines) > MAX_MATERIAL_CHARS) {
            lines.remove(0);
            kept.remove(0);
        }

        int playerLines = 0;
        for (SoulTypes.ConversationRecord record : kept) {
            if (record.kind() == SoulTypes.TurnKind.HEARD) {
                playerLines++;
            }
        }
        return new Material(kept, String.join("\n", lines), playerLines, next);
    }

    /** Party records already carry their speaker tag; DM records are tagged here. */
    private static String render(SoulTypes.ConversationRecord record, String botName,
                                 String playerName, boolean party) {
        if (party) {
            return record.content();
        }
        String speaker = record.kind() == SoulTypes.TurnKind.HEARD ? playerName : botName;
        return speaker + ": " + record.content();
    }

    private static int length(List<String> lines) {
        int total = lines.isEmpty() ? 0 : lines.size() - 1;
        for (String line : lines) {
            total += line.length();
        }
        return total;
    }

    /**
     * Keeps or drops each {@code correlationId} scene whole: the bot was there if its id is in a
     * HEARD record's participants, or (records written before participants existed) it has a
     * SPOKEN line tagged with its name.
     */
    private static List<SoulTypes.ConversationRecord> scenesTheBotWasIn(
            List<SoulTypes.ConversationRecord> records, UUID botId, String botName) {
        Map<UUID, List<SoulTypes.ConversationRecord>> scenes = new LinkedHashMap<>();
        for (SoulTypes.ConversationRecord record : records) {
            scenes.computeIfAbsent(record.correlationId(), k -> new ArrayList<>()).add(record);
        }
        String tag = botName + ": ";
        List<SoulTypes.ConversationRecord> out = new ArrayList<>();
        for (List<SoulTypes.ConversationRecord> scene : scenes.values()) {
            boolean present = false;
            for (SoulTypes.ConversationRecord record : scene) {
                if (record.kind() == SoulTypes.TurnKind.HEARD && record.participants().contains(botId)) {
                    present = true;
                    break;
                }
                if (record.kind() == SoulTypes.TurnKind.SPOKEN && record.content().startsWith(tag)) {
                    present = true;
                    break;
                }
            }
            if (present) {
                out.addAll(scene);
            }
        }
        out.sort(Comparator.comparingLong(SoulTypes.ConversationRecord::sequence));
        return out;
    }

    // === validation ===

    /**
     * Turns the summarizer's raw output into accepted facts: {@code "- "} lines only, ≤
     * {@link #MAX_FACT_CHARS}, free of formatting codes and control characters, and about the
     * player by name or as "they". {@code "- none"} alone, an empty output, or a runaway of more
     * than {@link #RUNAWAY_LINES} fact lines all yield an empty list.
     */
    static List<String> validate(String raw, String playerName) {
        String text = SoulResponseValidator.sanitizeBase(raw == null ? "" : raw);
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        if (lines.size() == 1 && lines.get(0).equalsIgnoreCase("- none")) {
            return List.of();
        }
        List<String> dashed = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("- ")) {
                dashed.add(line);
            }
        }
        if (dashed.size() > RUNAWAY_LINES) {
            return List.of();
        }
        String needle = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
        List<String> facts = new ArrayList<>();
        for (String line : dashed) {
            String body = line.substring(2).trim();
            if (body.isEmpty() || body.length() > MAX_FACT_CHARS || body.indexOf('§') >= 0) {
                continue;
            }
            if (hasControlChar(body)) {
                continue;
            }
            boolean aboutPlayer = (!needle.isEmpty() && body.toLowerCase(Locale.ROOT).contains(needle))
                    || THEY.matcher(body).find();
            if (!aboutPlayer) {
                continue;
            }
            facts.add(body);
            if (facts.size() == MAX_FACTS) {
                break;
            }
        }
        return List.copyOf(facts);
    }

    private static boolean hasControlChar(String body) {
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) < 0x20) {
                return true;
            }
        }
        return false;
    }

    // === merge ===

    /**
     * Merges {@code facts} into {@code existing} for one player: a fact whose word set overlaps
     * an existing memory by {@link #DUP_JACCARD} bumps and re-sources that memory in place,
     * anything else is appended at {@link #INITIAL_SALIENCE}. Past {@link #MAX_PER_PLAYER} the
     * lowest-salience then oldest memory of that player is evicted. Other players' memories and
     * their order are untouched.
     */
    static List<SoulTypes.PlayerMemory> merge(List<SoulTypes.PlayerMemory> existing, UUID playerId,
                                              List<String> facts, int day, List<UUID> sources) {
        List<SoulTypes.PlayerMemory> out = new ArrayList<>(existing == null ? List.of() : existing);
        List<UUID> newSources = dedupe(sources);
        for (String fact : facts == null ? List.<String>of() : facts) {
            if (fact == null || fact.isBlank()) {
                continue;
            }
            Set<String> tokens = tokens(fact);
            int match = -1;
            for (int i = 0; i < out.size(); i++) {
                SoulTypes.PlayerMemory memory = out.get(i);
                if (!memory.playerId().equals(playerId)) {
                    continue;
                }
                if (jaccard(tokens, tokens(memory.fact())) >= DUP_JACCARD) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                SoulTypes.PlayerMemory old = out.get(match);
                List<UUID> merged = new ArrayList<>(old.sourceCorrelationIds());
                for (UUID source : newSources) {
                    if (!merged.contains(source)) {
                        merged.add(source);
                    }
                }
                out.set(match, new SoulTypes.PlayerMemory(old.playerId(), old.day(), old.fact(),
                        Math.min(MAX_SALIENCE, old.salience() + DUP_BUMP), old.lastRecalledDay(), merged));
            } else {
                out.add(new SoulTypes.PlayerMemory(playerId, day, fact, INITIAL_SALIENCE, -1, newSources));
            }
        }
        evict(out, playerId);
        return List.copyOf(out);
    }

    private static void evict(List<SoulTypes.PlayerMemory> memories, UUID playerId) {
        while (countFor(memories, playerId) > MAX_PER_PLAYER) {
            int worst = -1;
            for (int i = 0; i < memories.size(); i++) {
                SoulTypes.PlayerMemory memory = memories.get(i);
                if (!memory.playerId().equals(playerId)) {
                    continue;
                }
                if (worst < 0) {
                    worst = i;
                    continue;
                }
                SoulTypes.PlayerMemory current = memories.get(worst);
                if (memory.salience() < current.salience()
                        || (memory.salience() == current.salience() && memory.day() < current.day())) {
                    worst = i;
                }
            }
            memories.remove(worst);
        }
    }

    private static int countFor(List<SoulTypes.PlayerMemory> memories, UUID playerId) {
        int count = 0;
        for (SoulTypes.PlayerMemory memory : memories) {
            if (memory.playerId().equals(playerId)) {
                count++;
            }
        }
        return count;
    }

    private static List<UUID> dedupe(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    static Set<String> tokens(String fact) {
        String normalized = normalize(fact);
        Set<String> tokens = new HashSet<>();
        for (String token : WHITESPACE.split(normalized)) {
            if (!token.isEmpty() && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Package-private (1.1.213) so {@link SoulRelationOps} can reuse the same overlap test. */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        int shared = 0;
        for (String token : a) {
            if (b.contains(token)) {
                shared++;
            }
        }
        return (double) shared / (a.size() + b.size() - shared);
    }

    static String normalize(String fact) {
        String lower = (fact == null ? "" : fact).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(NON_TOKEN.matcher(lower).replaceAll("")).replaceAll(" ").trim();
    }

    // === salience lifecycle ===

    /** One consolidation day of forgetting: every memory loses a point, and zero is gone. */
    static SoulTypes.SoulMind decay(SoulTypes.SoulMind mind) {
        if (mind.playerMemories().isEmpty()) {
            return mind;
        }
        List<SoulTypes.PlayerMemory> kept = new ArrayList<>();
        for (SoulTypes.PlayerMemory memory : mind.playerMemories()) {
            int salience = memory.salience() - 1;
            if (salience > 0) {
                kept.add(new SoulTypes.PlayerMemory(memory.playerId(), memory.day(), memory.fact(),
                        salience, memory.lastRecalledDay(), memory.sourceCorrelationIds()));
            }
        }
        return SoulMindOps.withPlayerMemories(mind, kept);
    }

    /** {@code "said:"} plus 8 hex of the normalized fact's hash — stable across renderings. */
    static String factKey(String fact) {
        return FACT_KEY_PREFIX + String.format("%08x", normalize(fact).hashCode());
    }

    /** A delivered scene recalled this memory: {@link #RECALL_BUMP} salience, once per day. */
    static SoulTypes.SoulMind noteRecalled(SoulTypes.SoulMind mind, String factKey, int day) {
        if (factKey == null || mind.playerMemories().isEmpty()) {
            return mind;
        }
        boolean changed = false;
        List<SoulTypes.PlayerMemory> out = new ArrayList<>(mind.playerMemories().size());
        for (SoulTypes.PlayerMemory memory : mind.playerMemories()) {
            if (factKey.equals(factKey(memory.fact())) && memory.lastRecalledDay() != day) {
                out.add(new SoulTypes.PlayerMemory(memory.playerId(), memory.day(), memory.fact(),
                        Math.min(MAX_SALIENCE, memory.salience() + RECALL_BUMP), day,
                        memory.sourceCorrelationIds()));
                changed = true;
            } else {
                out.add(memory);
            }
        }
        return changed ? SoulMindOps.withPlayerMemories(mind, out) : mind;
    }

    // === injection ===

    /**
     * At most one banter anchor for the player: the strongest memory not recalled within
     * {@link SoulMindOps#RECALL_COOLDOWN_DAYS}. {@code random} is accepted for symmetry with the
     * other seed sources and to leave room for tie-breaking; the choice is deterministic today.
     */
    static List<SoulBanterSeed.Anchor> anchors(SoulTypes.SoulMind mind, UUID playerId, String playerName,
                                               int currentDay, RandomGenerator random) {
        List<SoulTypes.PlayerMemory> eligible = new ArrayList<>();
        for (SoulTypes.PlayerMemory memory : mind.playerMemories()) {
            if (!memory.playerId().equals(playerId)) {
                continue;
            }
            if (memory.lastRecalledDay() < 0
                    || currentDay - memory.lastRecalledDay() >= SoulMindOps.RECALL_COOLDOWN_DAYS) {
                eligible.add(memory);
            }
        }
        if (eligible.isEmpty()) {
            return List.of();
        }
        eligible.sort(Comparator.comparingInt(SoulTypes.PlayerMemory::salience).reversed());
        SoulTypes.PlayerMemory pick = eligible.get(0);
        return List.of(new SoulBanterSeed.Anchor(SoulMindOps.MEMORY_TOPIC_PREFIX + factKey(pick.fact()),
                playerName + " once said: " + pick.fact(), SoulMindOps.MEMORY_ANCHOR_WEIGHT));
    }

    /** The prompt {@code ABOUT} block: the player's strongest, newest memories as {@code - fact}. */
    static List<String> aboutLines(SoulTypes.SoulMind mind, UUID playerId) {
        List<SoulTypes.PlayerMemory> mine = new ArrayList<>();
        for (SoulTypes.PlayerMemory memory : mind.playerMemories()) {
            if (memory.playerId().equals(playerId)) {
                mine.add(memory);
            }
        }
        if (mine.isEmpty()) {
            return List.of();
        }
        mine.sort(Comparator.comparingInt(SoulTypes.PlayerMemory::salience)
                .thenComparingInt(SoulTypes.PlayerMemory::day).reversed());
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (SoulTypes.PlayerMemory memory : mine) {
            if (lines.size() == MAX_ABOUT_LINES) {
                break;
            }
            String line = "- " + memory.fact();
            int projected = total + line.length() + (lines.isEmpty() ? 0 : 1);
            if (projected > MAX_ABOUT_CHARS) {
                break;
            }
            lines.add(line);
            total = projected;
        }
        return List.copyOf(lines);
    }

    // === reset ===

    /**
     * Reset for one player: their memories move to the front of the archive (newest kept, capped
     * at {@link #MAX_ARCHIVED}) and their digest cursors go, so the next digest starts clean.
     */
    static SoulTypes.SoulMind archiveFor(SoulTypes.SoulMind mind, UUID playerId) {
        List<SoulTypes.PlayerMemory> mine = new ArrayList<>();
        List<SoulTypes.PlayerMemory> rest = new ArrayList<>();
        for (SoulTypes.PlayerMemory memory : mind.playerMemories()) {
            if (memory.playerId().equals(playerId)) {
                mine.add(memory);
            } else {
                rest.add(memory);
            }
        }
        String suffix = ":" + playerId;
        Map<String, SoulTypes.ConversationCursor> cursors = new LinkedHashMap<>();
        for (Map.Entry<String, SoulTypes.ConversationCursor> entry : mind.digestCursors().entrySet()) {
            if (!entry.getKey().endsWith(suffix)) {
                cursors.put(entry.getKey(), entry.getValue());
            }
        }
        if (mine.isEmpty() && cursors.size() == mind.digestCursors().size()) {
            return mind;
        }
        List<SoulTypes.PlayerMemory> archived = new ArrayList<>(mine);
        archived.addAll(mind.archivedPlayerMemories());
        if (archived.size() > MAX_ARCHIVED) {
            archived = new ArrayList<>(archived.subList(0, MAX_ARCHIVED));
        }
        return SoulMindOps.withDigestCursors(
                SoulMindOps.withArchivedPlayerMemories(
                        SoulMindOps.withPlayerMemories(mind, rest), archived),
                cursors);
    }

    // === cursors ===

    /** The stored cursor for {@code cursorKey}, or the start of time when nothing is stored. */
    static SoulTypes.ConversationCursor cursorFor(SoulTypes.SoulMind mind, String cursorKey) {
        SoulTypes.ConversationCursor cursor = mind.digestCursors().get(cursorKey);
        return cursor == null ? new SoulTypes.ConversationCursor(0L, 0L) : cursor;
    }

    static SoulTypes.SoulMind withCursor(SoulTypes.SoulMind mind, String cursorKey,
                                         SoulTypes.ConversationCursor cursor) {
        Map<String, SoulTypes.ConversationCursor> cursors = new LinkedHashMap<>(mind.digestCursors());
        cursors.put(cursorKey, cursor);
        return SoulMindOps.withDigestCursors(mind, cursors);
    }
}
