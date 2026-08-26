package net.wcfcarolina13;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure token-level chat-address resolution: given a raw public chat line and the registered bot
 * names, decides whether the line addresses a bot (or the "bots"/"all bots" broadcast keyword)
 * and what prompt text that addressee should receive. Extracted from
 * {@code Frens#resolveChatTargets} so the addressing rules are unit-testable without a running
 * server; {@code Frens} maps the returned name index / broadcast flag back onto live
 * {@code ServerPlayerEntity} instances.
 *
 * <p>Matching (unchanged from the original resolver): tokens are normalized by stripping
 * non-alphanumerics and lowercasing; the first token that matches a broadcast keyword
 * ({@code bots}, {@code allbots}, or the pair {@code all bots}) or a registered bot name wins.
 *
 * <p>Prompt extraction (the leading-name quirk fix): when the matched token is
 * <em>leading</em> — every earlier token normalizes to empty, e.g. {@code "Jake come here"} or
 * {@code "!! Jake come here"} — the prompt is the tail after the match, exactly as before. When
 * the match sits anywhere later, the prompt is the <em>full trimmed message</em> with the name
 * left in place: previously {@code "Ping, Jake"} produced an empty tail and never routed, and a
 * mid-sentence {@code "can you help me build, Jake"} routed only the garbled tail after the
 * name. The bot knows its own name, so preserving the whole sentence keeps the meaning intact
 * for every downstream consumer (soul DMs, quest matching, legacy LLM routing).
 */
public final class ChatAddressing {

    /**
     * A resolved address. {@code matchedNameIndices} holds the indices into the {@code botNames}
     * list passed to {@link #resolve} of the explicitly-named bots, in address order — usually a
     * single entry, two or more for a leading multi-name run ({@code "Jake and Sara, ..."}), and
     * empty for a broadcast keyword match. {@code prompt} may be empty (a bare {@code "Jake"}
     * with no content).
     */
    public record Resolution(List<Integer> matchedNameIndices, boolean broadcast, String prompt) {
        public Resolution {
            matchedNameIndices = matchedNameIndices == null ? List.of() : List.copyOf(matchedNameIndices);
        }

        /** Back-compat: the first explicitly named bot's index, or {@code -1} for broadcast/none. */
        public int matchedNameIndex() {
            return matchedNameIndices.isEmpty() ? -1 : matchedNameIndices.get(0);
        }
    }

    private ChatAddressing() {
    }

    public static Optional<Resolution> resolve(String raw, List<String> botNames) {
        if (raw == null || botNames == null || botNames.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String[] tokens = trimmed.split("\\s+");

        int matchTokenIndex = -1;
        int consumed = -1;
        int matchedNameIndex = -1;
        boolean broadcast = false;
        outer:
        for (int i = 0; i < tokens.length; i++) {
            String current = normalizeToken(tokens[i]);
            if (current.isEmpty()) {
                continue;
            }
            if (current.equals("allbots") || current.equals("bots")) {
                broadcast = true;
                matchTokenIndex = i;
                consumed = i + 1;
                break;
            }
            if (current.equals("all") && i + 1 < tokens.length
                    && normalizeToken(tokens[i + 1]).equals("bots")) {
                broadcast = true;
                matchTokenIndex = i;
                consumed = i + 2;
                break;
            }
            for (int n = 0; n < botNames.size(); n++) {
                if (normalizeToken(botNames.get(n)).equals(current)) {
                    matchedNameIndex = n;
                    matchTokenIndex = i;
                    consumed = i + 1;
                    break outer;
                }
            }
        }
        if (consumed < 0) {
            return Optional.empty();
        }

        boolean leading = true;
        for (int i = 0; i < matchTokenIndex; i++) {
            if (!normalizeToken(tokens[i]).isEmpty()) {
                leading = false;
                break;
            }
        }

        List<Integer> indices = new java.util.ArrayList<>();
        if (matchedNameIndex >= 0) {
            indices.add(matchedNameIndex);
        }
        // Leading multi-name run: after "Jake", keep consuming ("and" | punctuation-only)* Name
        // pairs — "Jake and Sara, ..." or "Jake, Sara, ..." address both bots. A connector that
        // is not followed by a further bot name reverts entirely, so "Jake and I went mining"
        // still routes only to Jake with the tail untouched. Non-leading matches never extend:
        // the full-message prompt rule already preserves every name for the single addressee.
        if (matchedNameIndex >= 0 && leading) {
            int cursor = consumed;
            while (cursor < tokens.length) {
                int probe = cursor;
                String norm = normalizeToken(tokens[probe]);
                while (probe < tokens.length && (norm.equals("and") || norm.isEmpty())) {
                    probe++;
                    norm = probe < tokens.length ? normalizeToken(tokens[probe]) : "";
                }
                int nameIdx = -1;
                for (int n = 0; n < botNames.size(); n++) {
                    if (normalizeToken(botNames.get(n)).equals(norm)) {
                        nameIdx = n;
                        break;
                    }
                }
                if (probe >= tokens.length || nameIdx < 0) {
                    break;
                }
                if (!indices.contains(nameIdx)) {
                    indices.add(nameIdx);
                }
                cursor = probe + 1;
                consumed = cursor;
            }
        }

        String prompt;
        if (leading) {
            prompt = consumed >= tokens.length
                    ? ""
                    : String.join(" ", Arrays.copyOfRange(tokens, consumed, tokens.length)).trim();
        } else {
            prompt = trimmed;
        }
        return Optional.of(new Resolution(indices, broadcast, prompt));
    }

    static String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        String cleaned = token.replaceAll("[^a-zA-Z0-9]", "");
        return cleaned.toLowerCase(Locale.ROOT);
    }
}
