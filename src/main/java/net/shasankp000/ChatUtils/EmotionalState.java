package net.shasankp000.ChatUtils;

/**
 * Represents the emotional state/mood of a bot.
 * 
 * <p>Used to influence dialogue selection, ambient chatter, and greetings.
 * The mood changes based on recent events like combat, damage, hunger,
 * successful tasks, and idle time.
 * 
 * <p>Priority order (highest to lowest):
 * <ol>
 *   <li>STRESSED - Recent combat or significant damage</li>
 *   <li>INJURED - Low health (below 50%)</li>
 *   <li>HUNGRY - Low food (below 8 points)</li>
 *   <li>CONTENT - Healthy, well-fed, no recent combat</li>
 *   <li>NEUTRAL - Default state</li>
 * </ol>
 */
public enum EmotionalState {
    /**
     * Default state when nothing special is happening.
     */
    NEUTRAL("neutral", 0),

    /**
     * Bot is healthy, well-fed, and has had no recent negative events.
     * Triggers more positive/relaxed dialogue.
     */
    CONTENT("content", 1),

    /**
     * Bot is hungry (food level below threshold).
     * Triggers food-related comments.
     */
    HUNGRY("hungry", 2),

    /**
     * Bot is injured (health below threshold).
     * Triggers pain/fatigue comments.
     */
    INJURED("injured", 3),

    /**
     * Bot was recently in combat or took significant damage.
     * Triggers alert/tense dialogue.
     */
    STRESSED("stressed", 4);

    private final String id;
    private final int priority;

    EmotionalState(String id, int priority) {
        this.id = id;
        this.priority = priority;
    }

    /**
     * Get the string identifier for this state.
     */
    public String getId() {
        return id;
    }

    /**
     * Get the priority of this state (higher = takes precedence).
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns true if this state indicates distress (injured, hungry, or stressed).
     */
    public boolean isDistressed() {
        return this == INJURED || this == HUNGRY || this == STRESSED;
    }

    /**
     * Returns true if this state is positive (content).
     */
    public boolean isPositive() {
        return this == CONTENT;
    }
}
