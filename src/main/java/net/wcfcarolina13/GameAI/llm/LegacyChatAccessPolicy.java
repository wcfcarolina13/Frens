package net.wcfcarolina13.GameAI.llm;

import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;

import java.util.UUID;

/**
 * Who may address a bot by name and get the one-time "AI chat is off" notice
 * ({@link AiChatOffHintPolicy}). Kept from the retired legacy chat routes.
 * The same rule as private soul chat ({@link CompanionCommunicationPolicy#isPrivateSoulAuthorized}:
 * operators, or the exact recorded owner; an unowned bot is not eligible for non-operators),
 * plus the integrated-server host, who is not in the operator list in singleplayer without
 * cheats -- the same host allowance the chest-registry screen uses.
 */
public final class LegacyChatAccessPolicy {

    private LegacyChatAccessPolicy() {
    }

    public static boolean isAuthorized(boolean host, boolean operator, UUID actorId, UUID ownerId) {
        if (actorId == null) {
            return false;
        }
        return host || CompanionCommunicationPolicy.isPrivateSoulAuthorized(operator, actorId, ownerId);
    }
}
