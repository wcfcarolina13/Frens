package net.wcfcarolina13.GraphicalUserInterface;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Pure layout maths for narrow windows. Vanilla's auto GUI scale only promises a scaled
 * screen of at least 320x240, so every HUD hint and screen panel has to fit that.
 *
 * <p>No Minecraft types here. Text is measured through a {@code ToIntFunction<String>}, so
 * callers pass {@code textRenderer::getWidth} and tests pass a fixed-width fake.
 */
public final class UiFitPolicy {

    public static final String ELLIPSIS = "…";

    /** fitScale never goes below this, so a degenerate window can't produce a zero transform. */
    public static final float MIN_SCALE = 0.25f;

    private UiFitPolicy() {
    }

    /**
     * Word-wraps {@code text} so every line measures at most {@code maxWidth}. Runs of spaces
     * collapse to one, {@code '\n'} forces a break, and a word wider than {@code maxWidth} is
     * hard-broken by character. Returns an empty list for null text and {@code [""]} for
     * empty text. When {@code maxWidth <= 0} nothing can fit, so it returns {@code [""]}.
     */
    public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        if (maxWidth <= 0) {
            out.add("");
            return out;
        }
        String[] paragraphs = text.split("\n", -1);
        for (String paragraph : paragraphs) {
            wrapParagraph(paragraph, maxWidth, width, out);
        }
        return out;
    }

    /**
     * Like {@link #wrap(String, int, ToIntFunction)} but keeps at most {@code maxLines} lines.
     * When text is cut, the last kept line ends in "…" and still fits {@code maxWidth}.
     * {@code maxLines < 1} is treated as 1.
     */
    public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width, int maxLines) {
        List<String> all = wrap(text, maxWidth, width);
        int cap = Math.max(1, maxLines);
        if (all.size() <= cap) {
            return all;
        }
        List<String> out = new ArrayList<>(all.subList(0, cap));
        out.set(cap - 1, withEllipsis(out.get(cap - 1), maxWidth, width));
        return out;
    }

    /**
     * Returns {@code text} unchanged when it fits, otherwise the longest prefix that fits with
     * "…" appended. Returns "" when not even "…" fits, and "" for null.
     */
    public static String elide(String text, int maxWidth, ToIntFunction<String> width) {
        if (text == null) {
            return "";
        }
        if (width.applyAsInt(text) <= maxWidth) {
            return text;
        }
        return withEllipsis(text, maxWidth, width);
    }

    /**
     * Picks the first candidate (pass them longest first) that fits {@code maxWidth}. When none
     * fit, returns the last (shortest) candidate elided. Returns "" for an empty list.
     */
    public static String pickLabel(List<String> candidatesLongestFirst, int maxWidth, ToIntFunction<String> width) {
        if (candidatesLongestFirst == null || candidatesLongestFirst.isEmpty()) {
            return "";
        }
        for (String candidate : candidatesLongestFirst) {
            if (candidate != null && width.applyAsInt(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return elide(candidatesLongestFirst.get(candidatesLongestFirst.size() - 1), maxWidth, width);
    }

    /**
     * Sizes a row of tabs. Each tab needs its label width plus {@code padding} on both sides.
     * When the tabs fit, the spare space is shared evenly (leftmost tabs take the remainder),
     * so the result always sums to exactly {@code available}. Gaps between tabs are the
     * caller's to subtract from {@code available} first.
     *
     * @return one width per label, or {@code null} when the labels can't fit at their natural
     *         width (the caller should try shorter labels via {@link #pickLabel} or elide)
     */
    public static int[] fitTabWidths(List<Integer> labelWidths, int available, int padding) {
        if (labelWidths == null || labelWidths.isEmpty()) {
            return new int[0];
        }
        int n = labelWidths.size();
        int pad = Math.max(0, padding);
        int[] widths = new int[n];
        long natural = 0;
        for (int i = 0; i < n; i++) {
            Integer label = labelWidths.get(i);
            widths[i] = Math.max(0, label == null ? 0 : label) + pad * 2;
            natural += widths[i];
        }
        if (natural > available) {
            return null;
        }
        int spare = (int) (available - natural);
        int share = spare / n;
        int remainder = spare % n;
        for (int i = 0; i < n; i++) {
            widths[i] += share + (i < remainder ? 1 : 0);
        }
        return widths;
    }

    /**
     * Splits {@code available} into a left and right column with {@code gap} between them.
     * The left column aims for {@code leftFraction} of the space, then is clamped so both
     * columns meet their minimums. When the minimums together exceed the space, they shrink
     * in proportion to each other. The two widths always sum to {@code max(0, available - gap)}.
     */
    public static int[] splitColumns(int available, double leftFraction, int minLeft, int minRight, int gap) {
        int inner = Math.max(0, available - Math.max(0, gap));
        int minL = Math.max(0, minLeft);
        int minR = Math.max(0, minRight);
        double fraction = Double.isNaN(leftFraction) ? 0.5 : Math.max(0.0, Math.min(1.0, leftFraction));
        int left;
        if (minL + minR > inner) {
            left = (int) Math.round(inner * (double) minL / (minL + minR));
        } else {
            left = (int) Math.round(inner * fraction);
            left = Math.max(minL, Math.min(inner - minR, left));
        }
        left = Math.max(0, Math.min(inner, left));
        return new int[] {left, inner - left};
    }

    /**
     * The scale (at most 1) that fits a {@code contentW x contentH} panel inside the screen
     * with {@code margin} on every side. Returns 1 for empty content, and never less than
     * {@link #MIN_SCALE}.
     */
    public static float fitScale(int contentW, int contentH, int screenW, int screenH, int margin) {
        if (contentW <= 0 || contentH <= 0) {
            return 1.0f;
        }
        int m = Math.max(0, margin);
        float sx = (float) (screenW - 2 * m) / contentW;
        float sy = (float) (screenH - 2 * m) / contentH;
        float scale = Math.min(1.0f, Math.min(sx, sy));
        return Math.max(MIN_SCALE, scale);
    }

    // ── internals ──

    private static void wrapParagraph(String paragraph, int maxWidth, ToIntFunction<String> width, List<String> out) {
        String[] words = paragraph.trim().split(" +");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0) {
                String candidate = line + " " + word;
                if (width.applyAsInt(candidate) <= maxWidth) {
                    line.append(' ').append(word);
                    continue;
                }
                out.add(line.toString());
                line.setLength(0);
            }
            if (width.applyAsInt(word) <= maxWidth) {
                line.append(word);
                continue;
            }
            // Overlong word: hard-break it, keep the tail open for following words.
            String rest = word;
            while (width.applyAsInt(rest) > maxWidth) {
                int cut = longestFittingPrefix(rest, maxWidth, width);
                if (cut <= 0) {
                    cut = firstCodePointLength(rest);
                }
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        out.add(line.toString());
    }

    /** Longest prefix that, trimmed and followed by "…", fits. Always appends the ellipsis. */
    private static String withEllipsis(String text, int maxWidth, ToIntFunction<String> width) {
        if (width.applyAsInt(ELLIPSIS) > maxWidth) {
            return "";
        }
        String base = text == null ? "" : text.stripTrailing();
        int end = base.length();
        while (end > 0) {
            String candidate = base.substring(0, end).stripTrailing() + ELLIPSIS;
            if (width.applyAsInt(candidate) <= maxWidth) {
                return candidate;
            }
            end = previousBoundary(base, end);
        }
        return ELLIPSIS;
    }

    /** Length of the longest prefix of {@code s} that fits, never splitting a surrogate pair. */
    private static int longestFittingPrefix(String s, int maxWidth, ToIntFunction<String> width) {
        int end = s.length();
        while (end > 0 && width.applyAsInt(s.substring(0, end)) > maxWidth) {
            end = previousBoundary(s, end);
        }
        return end;
    }

    private static int previousBoundary(String s, int end) {
        int prev = end - 1;
        if (prev > 0 && Character.isLowSurrogate(s.charAt(prev)) && Character.isHighSurrogate(s.charAt(prev - 1))) {
            prev--;
        }
        return prev;
    }

    private static int firstCodePointLength(String s) {
        return s.isEmpty() ? 0 : Character.charCount(s.codePointAt(0));
    }
}
