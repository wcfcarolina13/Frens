package net.wcfcarolina13.GameAI.llm;

import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;

import java.util.UUID;

/**
 * Who may drive a bot through the legacy chat routes (LLM orchestrator, inline action parser).
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

    /**
     * Whether a pending chat confirmation may be consumed (and its action run with the bot's
     * elevated source): re-checked when the "yes" arrives, not only when the prompt was raised.
     * The sender must still be online and still {@link #isAuthorized} for the bot, and the bot
     * must still be a registered Frens fake player. Anything else drops the pending action.
     */
    public static boolean mayConsumeConfirmation(boolean senderOnline, boolean botRegistered,
                                                 boolean senderAuthorized) {
        return senderOnline && botRegistered && senderAuthorized;
    }
}
