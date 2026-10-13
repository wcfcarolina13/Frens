package net.wcfcarolina13.GraphicalUserInterface;

import java.util.List;

/**
 * Player-facing text for the [-] context key, which means "open the roster" while no
 * companion is present and "send a companion where you look" once one is out.
 *
 * <p>Lines stay short (the default GUI scale leaves ~427 scaled px) and use no em dashes
 * or semicolons.
 */
public final class ContextKeyPolicy {

    private ContextKeyPolicy() {
    }

    /** Lines for the HUD notice shown while no known companion is present. */
    public static List<String> noBotsHintLines(String contextKey) {
        String key = keyOrDefault(contextKey, "-");
        return List.of(
                "No companions are currently present in this world.",
                "Press [" + key + "] to open the roster. Click [x] to hide this.",
                "With a bot out, [" + key + "] sends it where you look."
        );
    }

    /** Lines for the one-shot notice shown once a companion appears after the roster was used. */
    public static List<String> botsPresentHintLines(String contextKey, String guideKey) {
        String key = keyOrDefault(contextKey, "-");
        String guide = keyOrDefault(guideKey, "]");
        return List.of(
                "Now [" + key + "] sends a companion where you look.",
                "Roster: Guide (" + guide + ") > Admin > Bot Controls > Spawn Bots"
        );
    }

    private static String keyOrDefault(String key, String fallback) {
        return key == null || key.isBlank() ? fallback : key;
    }
}
