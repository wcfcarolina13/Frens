package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure wrapping rule for overhead bot lines (armor stand name tags never wrap and ignore
 * newlines, so a long line runs off screen at close range). Character-count based because
 * the server has no font metrics.
 */
public final class OverheadLinePolicy {

    public static final int DEFAULT_MAX_CHARS = 24;
    public static final int DEFAULT_MAX_LINES = 3;
    public static final String ELLIPSIS = "…";

    private OverheadLinePolicy() {
    }

    public static List<String> wrap(String line) {
        return wrap(line, DEFAULT_MAX_CHARS, DEFAULT_MAX_LINES);
    }

    /**
     * Wraps {@code line} on word boundaries to at most {@code maxChars} characters per line,
     * hard-breaking words longer than that. Keeps at most {@code maxLines} lines; when text is
     * cut, the last kept line ends with "…" and still fits {@code maxChars}.
     * Returns an empty list for null or blank input.
     */
    public static List<String> wrap(String line, int maxChars, int maxLines) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        String text = line.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return out;
        }
        int width = Math.max(2, maxChars);
        int limit = Math.max(1, maxLines);

        List<String> all = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() > 0 && current.length() + 1 + word.length() <= width) {
                current.append(' ').append(word);
                continue;
            }
            if (current.length() > 0) {
                all.add(current.toString());
                current.setLength(0);
            }
            String rest = word;
            while (rest.length() > width) {
                int cut = safeCut(rest, width);
                all.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            current.append(rest);
        }
        if (current.length() > 0) {
            all.add(current.toString());
        }

        if (all.size() <= limit) {
            return all;
        }
        out.addAll(all.subList(0, limit));
        out.set(limit - 1, withEllipsis(out.get(limit - 1), width));
        return out;
    }

    /**
     * {@code index} moved back one char when it would split a surrogate pair (an emoji), so a
     * cut never leaves a lone half that shows as a replacement glyph. Never returns below 1
     * unless {@code index} was already 0, so a hard break always makes progress.
     */
    static int safeCut(String s, int index) {
        int cut = Math.max(0, Math.min(index, s.length()));
        if (cut > 1 && cut < s.length() && Character.isHighSurrogate(s.charAt(cut - 1))
                && Character.isLowSurrogate(s.charAt(cut))) {
            cut--;
        }
        return cut;
    }

    private static String withEllipsis(String last, int width) {
        String base = last;
        if (base.length() + ELLIPSIS.length() > width) {
            base = base.substring(0, safeCut(base, width - ELLIPSIS.length()));
            int space = base.lastIndexOf(' ');
            if (space >= width / 2) {
                base = base.substring(0, space);
            }
        }
        return base.stripTrailing() + ELLIPSIS;
    }
}
