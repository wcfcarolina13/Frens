package net.wcfcarolina13.GameAI.souls;

import java.util.List;
import java.util.Locale;

/**
 * Whether a failed skill's message says the bot did the job badly (EXECUTION) or something else
 * (UNKNOWN). No Minecraft types. Only EXECUTION failures count toward {@code BAD_AT}.
 *
 * <p>Most skill failures are about the surroundings or the setup ("No valid trees nearby",
 * "Missing hoe", "Mining paused: lava detected", "paused due to nearby threat"); counting those
 * would call a bot bad at woodcut because it stood in a desert. So the rule is an allow-list of
 * phrasings the skills use when the bot itself got stuck or could not make progress, read from the
 * {@code SkillExecutionResult.failure(...)} texts in 2026-11. Everything else, including a new
 * phrasing a skill adds later, is UNKNOWN and does not count: a missed BAD_AT costs nothing, a
 * wrong one is a belief the bot repeats in chat. Crashes and "ended unexpectedly" are code faults,
 * not the bot's skill, and are UNKNOWN too.
 */
final class SoulTaskFailurePolicy {

    enum Kind { EXECUTION, UNKNOWN }

    /** The journal fact key and value an EXECUTION failure carries on its TASK_FAILED event. */
    static final String FACT_KEY = "failure";
    static final String EXECUTION_VALUE = "execution";

    private static final List<String> CODE_FAULTS = List.of("crashed", "unexpectedly", "error");

    private static final List<String> EXECUTION_PHRASES = List.of(
            "stuck", "stalled", "failed to advance", "could not reach", "couldn't reach", "can't reach",
            "can't return", "could not return", "timed out", "still in water", "reached step limit",
            "could not finish", "unable to clear", "lost fishing rod");

    private SoulTaskFailurePolicy() {
    }

    static Kind classify(String message) {
        if (message == null || message.isBlank()) {
            return Kind.UNKNOWN;
        }
        String text = message.replaceAll("§.", "").toLowerCase(Locale.ROOT);
        for (String fault : CODE_FAULTS) {
            if (text.contains(fault)) {
                return Kind.UNKNOWN;
            }
        }
        for (String phrase : EXECUTION_PHRASES) {
            if (text.contains(phrase)) {
                return Kind.EXECUTION;
            }
        }
        return Kind.UNKNOWN;
    }

    /** The fact value to journal, empty when the failure does not count. */
    static String factValue(String message) {
        return classify(message) == Kind.EXECUTION ? EXECUTION_VALUE : "";
    }
}
