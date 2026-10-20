package net.wcfcarolina13.GameAI.services;

/**
 * What a mining loop does about hostiles it can see (finding 44, 1.1.276).
 * <p>
 * A mining bot fights the hostiles it would engage and resumes mining afterwards; it mines on
 * past hostiles it won't engage (name-tagged, behind a locked gate, non-aggro while the bot has
 * no ranged weapon). Engaging is capped: after {@link #ENGAGE_WAIT_CAP_MS} of waiting on the
 * same set of engageable hostiles the loop treats them as ignorable, so a fight that never lands
 * cannot stall mining until the skill's own timeout. The cap never applies while an engageable
 * hostile is targeting the bot: mining on beside a creeper or an angry iron golem would walk the
 * bot back into what engageHostiles is fleeing or fighting.
 */
public final class MiningThreatPolicy {

    /** Longest the mining loop waits on one set of engageable hostiles before it mines on. */
    public static final long ENGAGE_WAIT_CAP_MS = 10_000L;

    public enum Decision {
        /** No hostiles in view: mine. */
        NONE,
        /** Hostiles in view the bot would fight: engage, wait, then re-check. */
        ENGAGE,
        /** Hostiles in view the bot won't fight (or the engage budget is spent): mine on. */
        IGNORE
    }

    private MiningThreatPolicy() {
    }

    public static Decision decide(int hostileCount, int engageableCount) {
        if (hostileCount <= 0) {
            return Decision.NONE;
        }
        return engageableCount > 0 ? Decision.ENGAGE : Decision.IGNORE;
    }

    /**
     * Same as {@link #decide(int, int)}, but an ENGAGE that has already waited
     * {@link #ENGAGE_WAIT_CAP_MS} or longer becomes IGNORE, unless an engageable hostile is
     * targeting the bot ({@code targetingBotCount > 0}): that one stays ENGAGE however long it takes.
     *
     * @param targetingBotCount engageable hostiles whose current target is the bot
     */
    public static Decision decide(int hostileCount, int engageableCount, int targetingBotCount, long engagedForMs) {
        Decision d = decide(hostileCount, engageableCount);
        if (d == Decision.ENGAGE && targetingBotCount <= 0 && engagedForMs >= ENGAGE_WAIT_CAP_MS) {
            return Decision.IGNORE;
        }
        return d;
    }
}
