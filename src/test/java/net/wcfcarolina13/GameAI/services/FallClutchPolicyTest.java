package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallClutchPolicyTest {

    private static final double EYE = 1.62D;
    private static final double REACH = 4.5D;

    @Test
    void projectedDamageCountsTheWholeFall() {
        assertEquals(7.0D, FallClutchPolicy.projectedDamage(2.0D, 8.0D), 1e-9);
        assertEquals(0.0D, FallClutchPolicy.projectedDamage(1.0D, 1.5D), 1e-9);
    }

    @Test
    void aThirtyBlockDropIsLethalFromTheStart() {
        // Sandbox 2026-11-09: armed only at fallDistance ~27 of 30, too late. Now armed at 2.
        assertTrue(FallClutchPolicy.projectedLethal(2.0D, 28.0D, 20.0D));
    }

    @Test
    void survivableFallsAreLeftAlone() {
        assertFalse(FallClutchPolicy.projectedLethal(2.0D, 8.0D, 20.0D), "10 blocks costs 7 of 20");
        assertFalse(FallClutchPolicy.projectedLethal(2.0D, 3.0D, 3.0D), "a 2-damage fall is never worth a bucket");
        assertTrue(FallClutchPolicy.projectedLethal(2.0D, 8.0D, 6.0D), "10 blocks kills a bot on 6 health");
    }

    @Test
    void placeWindowIsThePlayersReachFromTheEyes() {
        assertTrue(FallClutchPolicy.inPlaceWindow(2.5D, EYE, REACH));
        assertTrue(FallClutchPolicy.inPlaceWindow(REACH - EYE, EYE, REACH));
        assertFalse(FallClutchPolicy.inPlaceWindow(3.0D, EYE, REACH), "out of reach");
        assertFalse(FallClutchPolicy.inPlaceWindow(0.0D, EYE, REACH), "landing this tick");
    }

    /** The bucket goes through the vanilla item use (raycast from the eyes), not useOnBlock. */
    @Test
    void bucketIsUsedLikeAPlayerWould() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotFallSafetyService.java"));
        assertFalse(src.contains("useOnBlock("), "a bucket never overrides useOnBlock");
        assertTrue(src.contains("bot.interactionManager.interactItem(bot, world, stack, Hand.MAIN_HAND)"));
        assertTrue(src.contains("FallClutchPolicy.projectedLethal(bot.fallDistance, landing.heightDelta(), effectiveHealth)"));
        assertTrue(src.contains("FallClutchPolicy.inPlaceWindow(landing.heightDelta(), bot.getStandingEyeHeight(), bot.getBlockInteractionRange())"));
    }
}
