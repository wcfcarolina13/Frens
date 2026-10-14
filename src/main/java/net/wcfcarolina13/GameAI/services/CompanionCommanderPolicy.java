package net.wcfcarolina13.GameAI.services;

import java.util.UUID;

/**
 * Who may command the recruited companion in survival-recruitment mode (come, summon, open,
 * soulofender, home) and advance its companion quest: the player it pledged to (the recruiter),
 * the bot's exact config owner, an operator, or the integrated-server host.
 *
 * <p>The owner is the exact {@code ManualConfig.getOwner(alias)} entry. An unowned bot has no
 * owner, so it does not open to everyone. Pure: no Minecraft types.
 */
public final class CompanionCommanderPolicy {
    private CompanionCommanderPolicy() {}

    public static boolean isAuthorized(UUID actor, String recruiterUuid, UUID ownerUuid, boolean isOp, boolean isHost) {
        if (actor == null) {
            return false;
        }
        if (isOp || isHost) {
            return true;
        }
        if (recruiterUuid != null && !recruiterUuid.isBlank()
                && recruiterUuid.trim().equalsIgnoreCase(actor.toString())) {
            return true;
        }
        return actor.equals(ownerUuid);
    }

    /** The owner UUID stored in config, or null when absent or malformed. */
    public static UUID parseOwnerUuid(String ownerUuid) {
        if (ownerUuid == null || ownerUuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(ownerUuid.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
