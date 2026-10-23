package net.wcfcarolina13.GameAI.services;

import java.util.UUID;

/** A respawn answer needs both the original reply route and current authority. */
public final class RespawnReplyPolicy {
    private RespawnReplyPolicy() { }

    public static boolean mayAnswer(UUID responder, UUID promptedOwner, UUID currentController,
                                    boolean administrator) {
        return responder != null && responder.equals(promptedOwner)
                && (administrator || responder.equals(currentController));
    }
}
