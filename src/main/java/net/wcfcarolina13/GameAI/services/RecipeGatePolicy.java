package net.wcfcarolina13.GameAI.services;

/**
 * Pure decision for whether a bot may craft a recipe for the player who asked for it (backlog
 * 1224). It mirrors vanilla's {@code RecipeUnlocker.shouldCraftRecipe} in 1.21.11: a recipe is
 * allowed when it is ignored by the recipe book, or the {@code limitedCrafting} game rule is off,
 * or the crafting player has it unlocked. The bot crafts on its commander's behalf, so the
 * commander's recipe book is the one read. A craft with no human requester (none recorded, or the
 * requester is a bot, as in a self-provisioning craft) is never gated. No Minecraft imports.
 */
public final class RecipeGatePolicy {

    public enum Decision {
        /** Craft it. */
        ALLOW,
        /** The server has no such recipe. */
        UNKNOWN_RECIPE,
        /** limitedCrafting is on and the commander has not unlocked the recipe. */
        LOCKED
    }

    private RecipeGatePolicy() {
    }

    /**
     * @param recipeExists         the server's recipe manager has the recipe
     * @param humanRequester       a real player asked for the craft (not null, not a bot)
     * @param limitedCrafting      the {@code limitedCrafting} game rule in the requester's world
     * @param ignoredInRecipeBook  the recipe is one the recipe book does not track (vanilla never gates these)
     * @param unlocked             the requester's recipe book has the recipe unlocked
     */
    public static Decision decide(boolean recipeExists,
                                  boolean humanRequester,
                                  boolean limitedCrafting,
                                  boolean ignoredInRecipeBook,
                                  boolean unlocked) {
        if (!recipeExists) {
            return Decision.UNKNOWN_RECIPE;
        }
        if (!humanRequester || !limitedCrafting || ignoredInRecipeBook || unlocked) {
            return Decision.ALLOW;
        }
        return Decision.LOCKED;
    }

    /** The line the bot says when it refuses: who has to unlock what, and why it matters. */
    public static String lockedLine(String commanderName, String recipePath) {
        String who = commanderName == null || commanderName.isBlank()
                ? "You haven't"
                : commanderName + " hasn't";
        String what = recipePath == null || recipePath.isBlank()
                ? "that recipe"
                : "the " + recipePath.replace('_', ' ') + " recipe";
        return who + " unlocked " + what + " yet, and the limited_crafting rule is on, so I can't craft it.";
    }
}
