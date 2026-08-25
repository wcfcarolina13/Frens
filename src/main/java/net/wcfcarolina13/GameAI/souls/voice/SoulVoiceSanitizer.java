package net.wcfcarolina13.GameAI.souls.voice;

import java.util.Optional;

/** Pure text preparation for synthesis: strip markup, collapse whitespace, bound length. */
public final class SoulVoiceSanitizer {

    private SoulVoiceSanitizer() {
    }

    public static Optional<String> sanitize(String text, int maxChars) {
        if (text == null) {
            return Optional.empty();
        }
        String cleaned = text
                .replaceAll("§.", "")            // Minecraft formatting codes
                .replaceAll("[*_`~#>\\[\\]]", "") // markdown-ish markup
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        if (cleaned.length() <= maxChars) {
            return Optional.of(cleaned);
        }
        String head = cleaned.substring(0, maxChars);
        int lastBoundary = Math.max(head.lastIndexOf(". "),
                Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (cleaned.charAt(maxChars - 1) == '.' || cleaned.charAt(maxChars - 1) == '!'
                || cleaned.charAt(maxChars - 1) == '?') {
            return Optional.of(head);
        }
        if (lastBoundary > 0) {
            return Optional.of(head.substring(0, lastBoundary + 1));
        }
        return Optional.of(head);
    }
}
