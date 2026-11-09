package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.GameAI.services.RecipeGatePolicy.Decision.ALLOW;
import static net.wcfcarolina13.GameAI.services.RecipeGatePolicy.Decision.LOCKED;
import static net.wcfcarolina13.GameAI.services.RecipeGatePolicy.Decision.UNKNOWN_RECIPE;
import static net.wcfcarolina13.GameAI.services.RecipeGatePolicy.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeGatePolicyTest {

    @Test
    void ruleOffAlwaysAllowsAKnownRecipe() {
        assertEquals(ALLOW, decide(true, true, false, false, false));
        assertEquals(ALLOW, decide(true, true, false, false, true));
    }

    @Test
    void ruleOnRefusesOnlyALockedRecipeForAHumanRequester() {
        assertEquals(LOCKED, decide(true, true, true, false, false));
        assertEquals(ALLOW, decide(true, true, true, false, true));
    }

    @Test
    void noHumanRequesterIsNeverGated() {
        // An idle or self-provisioning craft (no requester, or the bot itself).
        assertEquals(ALLOW, decide(true, false, true, false, false));
    }

    @Test
    void aRecipeTheBookIgnoresIsNeverGated() {
        // Vanilla's RecipeUnlocker.shouldCraftRecipe passes recipes ignored by the recipe book.
        assertEquals(ALLOW, decide(true, true, true, true, false));
    }

    @Test
    void aMissingRecipeIsUnknownWhateverTheRule() {
        assertEquals(UNKNOWN_RECIPE, decide(false, true, true, false, false));
        assertEquals(UNKNOWN_RECIPE, decide(false, false, false, false, false));
    }

    @Test
    void theLockedLineNamesTheCommanderTheRecipeAndTheRule() {
        assertEquals("Steve hasn't unlocked the white bed recipe yet, and the limited_crafting rule is on, so I can't craft it.",
                RecipeGatePolicy.lockedLine("Steve", "white_bed"));
        assertEquals("You haven't unlocked that recipe yet, and the limited_crafting rule is on, so I can't craft it.",
                RecipeGatePolicy.lockedLine(" ", null));
    }
}
