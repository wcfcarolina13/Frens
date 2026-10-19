package net.wcfcarolina13.GameAI.services;

import java.util.List;
import java.util.UUID;

/**
 * Pure rule for which dead bot the Respawn Anchor ritual brings back when Questing is off
 * (finding 30, Bradley 2026-10-19: resurrection is not Questing-only).
 *
 * <p>With Questing on, {@link CompanionResurrectionService} keeps the recruited-companion path
 * unchanged and never calls this.
 *
 * <p>Free of Minecraft imports so it can be unit-tested; the service gathers the inputs on the
 * server thread.
 */
public final class ResurrectionEligibilityPolicy {

    private ResurrectionEligibilityPolicy() {}

    /**
     * One known bot of this world.
     *
     * @param alias              bot name
     * @param ownerOrNull        configured owner, or null if un-owned
     * @param diedAtEpochMsOrNull time of its last death in this world, or null if it never died here
     * @param online             whether a live player of that name is on the server
     * @param autoRespawnOnDeath the bot's Auto Respawn setting (true when it has no settings)
     */
    public record Candidate(String alias, UUID ownerOrNull, Long diedAtEpochMsOrNull, boolean online,
                            boolean autoRespawnOnDeath) {}

    /**
     * Whether {@code requester} may resurrect this candidate. Same order as
     * {@link BotAccessPolicy#authorize}: owner, operator, host. The candidates come from the Frens
     * bot config, so the not-a-bot branch of that rule does not apply to an offline dead bot.
     */
    public static boolean authorized(UUID requester, UUID ownerOrNull, boolean requesterIsOp, boolean requesterIsHost) {
        return BotAccessPolicy.allowed(BotAccessPolicy.authorize(requester, ownerOrNull, true, requesterIsOp, requesterIsHost));
    }

    /**
     * Whether the candidate is waiting to be brought back: it died in this world, it is not
     * online, and Auto Respawn is off (with it on the bot already came back by itself).
     */
    public static boolean isDeadAndShelved(Candidate c) {
        return c != null
                && c.alias() != null && !c.alias().isBlank()
                && c.diedAtEpochMsOrNull() != null
                && !c.online()
                && !c.autoRespawnOnDeath();
    }

    /**
     * The alias the ritual resurrects, or null when none qualifies: dead and shelved, and the
     * requester may act on it. Several → the latest death; a tie keeps the earlier candidate.
     */
    public static String chooseBot(UUID requester, boolean requesterIsOp, boolean requesterIsHost,
                                   List<Candidate> candidates) {
        if (candidates == null) {
            return null;
        }
        Candidate best = null;
        for (Candidate c : candidates) {
            if (!isDeadAndShelved(c) || !authorized(requester, c.ownerOrNull(), requesterIsOp, requesterIsHost)) {
                continue;
            }
            if (best == null || c.diedAtEpochMsOrNull() > best.diedAtEpochMsOrNull()) {
                best = c;
            }
        }
        return best == null ? null : best.alias();
    }

    /** Owner UUID from its stored string, or null when blank or malformed. */
    public static UUID parseOwner(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
