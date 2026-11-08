package net.wcfcarolina13.GameAI.services;

/**
 * Which way vanilla takes a bot out of the End, and the {@code seenCredits} value a Frens bot keeps
 * so that every exit takes the same way. No Minecraft types.
 *
 * <p>Vanilla 1.21.11 ({@code EndPortalBlock.onEntityCollision}, read from the yarn-mapped jar): in the
 * End, a {@code ServerPlayerEntity} with {@code seenCredits == false} goes through
 * {@code detachForDimensionChange} (credits, then the client's PERFORM_RESPAWN); otherwise it takes
 * the ordinary portal teleport. {@code detachForDimensionChange} sets {@code seenCredits} to true,
 * {@code copyFrom} carries it to the respawned entity and the player save keeps it.
 *
 * <p>Frens answers the detach path itself ({@code createFakePlayer.detachForDimensionChange}) and its
 * respawn reset treats it as a dimension change (follow and targets kept). The portal teleport path
 * instead reaches {@code teleportTo}, which reads as an external teleport: follow is cleared and the
 * task ends with "External teleport detected." So a bot keeps {@code seenCredits} false, and its
 * second and later exits take the same path as its first. A bot has no client, so the credits
 * screen the flag stands for is never shown either way.
 */
public final class EndCreditsPolicy {

    private EndCreditsPolicy() {
    }

    public enum ExitPath { DETACH_AND_RESPAWN, PORTAL_TELEPORT }

    /** The path vanilla's End portal takes for a server player in the End. */
    public static ExitPath vanillaExitPath(boolean seenCredits) {
        return seenCredits ? ExitPath.PORTAL_TELEPORT : ExitPath.DETACH_AND_RESPAWN;
    }

    /**
     * The {@code seenCredits} value to keep after vanilla set {@code vanillaValue} (on an End exit
     * or when a save is read). A Frens bot always keeps false; any other player keeps vanilla's.
     */
    public static boolean seenCreditsToKeep(boolean frensBot, boolean vanillaValue) {
        return !frensBot && vanillaValue;
    }
}
