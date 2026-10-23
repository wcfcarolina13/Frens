package net.wcfcarolina13.GameAI.services;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure rules for the chat answer to a bot's "I can eat Golden Apple, but it's rare... (yes/no)"
 * prompt ({@link FoodConsumptionConfirmationService}).
 *
 * <p>Accepted answers are deliberately strict so ordinary chat is never eaten: {@code yes}, {@code y},
 * {@code no}, {@code n} (any case, optional trailing {@code .} or {@code !}), either bare or after a
 * pending bot's alias ({@code "Bob yes"}, {@code "Bob: no"}, {@code "bob, y"}). Anything else
 * ({@code "follow"}, {@code "Bob follow"}, {@code "yes please"}) is not an answer and passes through.
 *
 * <p>A bare answer goes to the most recent pending prompt for that player. Chat precedence is
 * respawn prompt, then skill-resume prompt, then this one: a line another prompt already answered
 * is not offered here, so one "yes" answers one prompt.
 */
public final class FoodConfirmReplyPolicy {

    /**
     * How long an unanswered prompt stays answerable (and blocks a re-prompt for the same item).
     * Short on purpose: a bare "yes"/"no" is only taken as an answer soon after the prompt, so a
     * "no" typed minutes later in unrelated chat is not swallowed by an old question.
     */
    public static final long PENDING_TTL_MS = 20_000L;

    /** A parsed answer. {@code alias} is the normalized pending alias named, or null for a bare answer. */
    public record Reply(String alias, boolean approve) {
    }

    private FoodConfirmReplyPolicy() {
    }

    /** A stored prompt cannot authorize a player who no longer controls the bot. */
    public static boolean canAnswer(java.util.UUID responder, java.util.UUID currentController) {
        return responder != null && responder.equals(currentController);
    }

    /** Explicit ownerless hand-feeds stay valid while ownerless; any identity change invalidates consent. */
    public static boolean consentMatches(java.util.UUID recordedController, java.util.UUID currentController) {
        return java.util.Objects.equals(recordedController, currentController);
    }

    /**
     * Parses a chat line as an answer to one of the pending prompts.
     *
     * @param pendingAliases normalized aliases ({@link #normalizeAlias}) with a live prompt
     * @return the answer, or null when the line is not one
     */
    public static Reply parse(String raw, Set<String> pendingAliases) {
        if (raw == null || pendingAliases == null || pendingAliases.isEmpty()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int breakIndex = aliasBreakIndex(trimmed);
        if (breakIndex > 0) {
            String alias = normalizeAlias(trimmed.substring(0, breakIndex));
            if (!alias.isEmpty() && pendingAliases.contains(alias)) {
                String rest = trimmed.substring(breakIndex).trim().replaceFirst("^[,:;-]+", "").trim();
                Boolean decision = parseDecision(rest);
                return decision == null ? null : new Reply(alias, decision);
            }
        }
        Boolean decision = parseDecision(trimmed);
        return decision == null ? null : new Reply(null, decision);
    }

    /** {@code yes}/{@code y} is true, {@code no}/{@code n} is false, anything else null. */
    public static Boolean parseDecision(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().toLowerCase(Locale.ROOT).replaceFirst("[.!]+$", "");
        return switch (s) {
            case "yes", "y" -> Boolean.TRUE;
            case "no", "n" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Alias key used both when a prompt is stored and when an answer names a bot. */
    public static String normalizeAlias(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /** True once a prompt created at {@code createdAtMs} is past {@link #PENDING_TTL_MS}. A clock that went back is fresh. */
    public static boolean isExpired(long createdAtMs, long nowMs) {
        return nowMs - createdAtMs > PENDING_TTL_MS;
    }

    /** The key with the latest creation time (first one wins a tie), or null for an empty map. */
    public static <K> K mostRecent(Map<K, Long> createdAtByKey) {
        K best = null;
        long bestAt = Long.MIN_VALUE;
        if (createdAtByKey == null) {
            return null;
        }
        for (Map.Entry<K, Long> e : createdAtByKey.entrySet()) {
            long at = e.getValue() == null ? Long.MIN_VALUE : e.getValue();
            if (best == null || at > bestAt) {
                best = e.getKey();
                bestAt = at;
            }
        }
        return best;
    }

    /** The food prompt may claim a line only when no earlier prompt (skill resume) answered it. */
    public static boolean foodMayClaim(boolean earlierPromptAnswered) {
        return !earlierPromptAnswered;
    }

    /** End of a leading alias token: the first space, or just after a {@code : , ; -}. -1 when none. */
    private static int aliasBreakIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                return i;
            }
            if (c == ':' || c == ',' || c == ';' || c == '-') {
                return i + 1;
            }
        }
        return -1;
    }
}
