package net.wcfcarolina13.GameAI.services;

/**
 * Pure rule for which bot the Spells key opens (finding 30, Bradley 2026-10-19: the Spells key
 * is not Questing-only).
 *
 * <p>Free of Minecraft imports so it can be unit-tested; {@code FrensClient} gathers the inputs
 * on the client thread.
 */
public final class SpellsKeyPolicy {

    private SpellsKeyPolicy() {}

    /**
     * The alias to send with {@code bot open}, or {@code ""} so the server picks the player's only
     * owned bot (operators and the host keep their remembered bot).
     *
     * <p>Order: the bot the player looks at; else the Questing companion alias, only once that
     * companion is recruited; else {@code ""}. With Questing off the companion alias is a default
     * ("Jake") that need not exist, so it is never sent.
     *
     * @param lookedAt      name of the bot under the crosshair, or null
     * @param questingOn    whether survival recruitment (Questing) is enabled in this world
     * @param recruited     whether the Questing companion has been recruited
     * @param questingAlias the Questing companion alias, may be null
     */
    public static String chooseTarget(String lookedAt, boolean questingOn, boolean recruited, String questingAlias) {
        if (lookedAt != null && !lookedAt.isBlank()) {
            return lookedAt.trim();
        }
        if (questingOn && recruited && questingAlias != null && !questingAlias.isBlank()) {
            return questingAlias.trim();
        }
        return "";
    }
}
