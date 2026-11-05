package net.wcfcarolina13.network;

import java.util.List;

/**
 * Pure size and rate limits for the Bases screen's client packets. Free of Minecraft imports so
 * it can be unit-tested.
 *
 * <p>Multiplayer finding (2026-11-05): the base, rename and map-village labels came off the wire
 * with the codec's 32,767-char ceiling and were stored as sent. Villages are listed to every
 * viewer, so one oversized village name made {@code BasesListPayload} overflow its codec and
 * netty disconnected everyone who opened the screen. Labels are now capped at
 * {@link #MAX_LABEL_CHARS} (the Zone Wand's rule) in every apply path, the list drops whole
 * entries from its end when the JSON would not fit ({@link #entriesThatFit}), and the two
 * packets that write world state (save base, map village) share one per-player token bucket
 * ({@link #newWriteThrottle}).
 */
public final class BaseScreenLimitPolicy {

    /** Longest base, wall or village label a client packet may store. Same as the Zone Wand's cap. */
    public static final int MAX_LABEL_CHARS = 32;

    /** The {@code BasesListPayload} codec ceiling, in chars (UTF-8 bytes are checked at 3 per char). */
    public static final int MAX_LIST_JSON_CHARS = 32767;

    /** Burst of world-writing base packets (save base, map village) a player may send at once. */
    public static final int WRITE_THROTTLE_CAPACITY = 3;
    /** One token back every this many ms: a steady one write every two seconds. */
    public static final long WRITE_THROTTLE_REFILL_MS = 2000L;

    private BaseScreenLimitPolicy() {}

    /**
     * {@code label} cut to {@link #MAX_LABEL_CHARS} chars. Null stays null; a short label is
     * returned unchanged. Callers check for blank before and after, as the stores trim.
     */
    public static String capLabel(String label) {
        if (label == null || label.length() <= MAX_LABEL_CHARS) return label;
        int end = MAX_LABEL_CHARS;
        // Never leave half a surrogate pair at the cut.
        if (Character.isHighSurrogate(label.charAt(end - 1))) end--;
        return label.substring(0, end);
    }

    /**
     * How many leading entries of a JSON array fit in {@code maxChars}, when each entry's JSON is
     * {@code entryLengths[i]} chars long and the array is written as {@code [a,b,c]} (two
     * brackets, one comma between entries, no whitespace). An empty array ({@code []}) always
     * fits, so the result is 0 for an empty or null list.
     */
    public static int entriesThatFit(List<Integer> entryLengths, int maxChars) {
        if (entryLengths == null || entryLengths.isEmpty()) return 0;
        long used = 2L;
        int count = 0;
        for (Integer length : entryLengths) {
            long entry = length == null ? 0L : Math.max(0L, length);
            long next = used + entry + (count > 0 ? 1L : 0L);
            if (next > maxChars) break;
            used = next;
            count++;
        }
        return count;
    }

    /** The per-player bucket for save-base and map-village packets. */
    public static BotActionPolicy.Throttle newWriteThrottle() {
        return new BotActionPolicy.Throttle(WRITE_THROTTLE_CAPACITY, WRITE_THROTTLE_REFILL_MS);
    }
}
