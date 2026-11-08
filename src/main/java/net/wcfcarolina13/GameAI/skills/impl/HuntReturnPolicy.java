package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Whether a hunt walks back to the bot's base afterwards. The hunt used to say "Returned from the
 * hunt" and then start a no-teleport walk to the nearest base however far it was, and say nothing
 * when the walk gave up (sandbox 2026-11-08: a base 1,340 blocks away, "pursuit failed", silence).
 * Within {@link #WALK_RANGE_BLOCKS} (the sunset return's practical home range) it walks; farther,
 * it goes only when the bot may teleport during skills, and otherwise stays and says why.
 * No Minecraft imports.
 */
public final class HuntReturnPolicy {

    /** Same as BotAutoReturnSunsetService.PRACTICAL_HOME_RANGE_SQ (96 blocks). */
    public static final int WALK_RANGE_BLOCKS = 96;

    public enum Decision { WALK, TELEPORT, TOO_FAR }

    private HuntReturnPolicy() {
    }

    public static Decision decide(int blocksToBase, boolean teleportAllowed) {
        if (blocksToBase <= WALK_RANGE_BLOCKS) {
            return teleportAllowed ? Decision.TELEPORT : Decision.WALK;
        }
        return teleportAllowed ? Decision.TELEPORT : Decision.TOO_FAR;
    }

    public static String tooFarLine(int blocksToBase) {
        return "My base is " + blocksToBase + " blocks away, too far to walk back, so I'm staying here.";
    }

    public static String couldNotReturnLine(int blocksLeft) {
        return "I couldn't find a way back to base (" + blocksLeft + " blocks away), so I'm staying here.";
    }
}
