package net.wcfcarolina13.network;

/**
 * The "Open Spells" admin permission. It used to hide only an Admin-tab entry, so a player an
 * operator had turned it off for could still cast every spell. The server now checks it before
 * any spell runs; operators and the host always pass (see SurvivalRecruitmentService).
 */
public final class SpellPermissionPolicy {
    public static final String PERMISSION_KEY = "open_spells";
    public static final String REFUSAL = "An operator has turned off spells for you.";

    private SpellPermissionPolicy() {
    }

    /** True for the bot actions the Spells tab sends: Regroup, Summon, Home, Remote Inventory and Soul of Ender. */
    public static boolean isSpellAction(BotActionPolicy.Action action) {
        return action == BotActionPolicy.Action.COMPANION;
    }
}
