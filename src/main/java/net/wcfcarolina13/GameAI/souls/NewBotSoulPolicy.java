package net.wcfcarolina13.GameAI.souls;

/**
 * Whether a bot that just joined gets a soul from the AI setup checklist's new-bot default
 * (Bradley 2026-10-23: "Give new companions a soul when Ollama is ready?"). Pure, so it is
 * unit-tested.
 *
 * <p>"New" means the bot has no soul state file yet. A bot someone switched off has a file
 * (active=false) and is never switched back on here. A bot that existed before the question was
 * answered but was never enabled has no file either, so it counts as new on its next join.
 * Nothing is bound while Soul Chat's master switch is off: no soul.json for a souls-off world,
 * and the bot stays "new" until the switch is on.
 */
public final class NewBotSoulPolicy {

    private NewBotSoulPolicy() {
    }

    /**
     * @param newBotDefault the checklist answer: true, false, or null when not asked yet
     * @param hasSoulState  the bot already has a soul state file (bound, or switched off)
     * @param soulsEnabled  Soul Chat's master switch
     */
    public static boolean shouldEnable(Boolean newBotDefault, boolean hasSoulState, boolean soulsEnabled) {
        return Boolean.TRUE.equals(newBotDefault) && !hasSoulState && soulsEnabled;
    }
}
