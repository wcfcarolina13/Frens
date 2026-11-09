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
        assertTrue(src.contains("FallClutchPolicy.inPlaceWindow(landing.heightDelta(), bot.getEyeY() - bot.getBoundingBox().minY, bot.getBlockInteractionRange())"));
    }

    @Test
    void scoopWaitsForTheLandingThenTriesUntilTheGiveUpTick() {
        assertEquals(FallClutchPolicy.ScoopStep.WAIT, FallClutchPolicy.scoopStep(0, true), "same tick as placing");
        assertEquals(FallClutchPolicy.ScoopStep.WAIT, FallClutchPolicy.scoopStep(5, false), "still falling");
        assertEquals(FallClutchPolicy.ScoopStep.TRY, FallClutchPolicy.scoopStep(3, true));
        assertEquals(FallClutchPolicy.ScoopStep.GIVE_UP, FallClutchPolicy.scoopStep(60, true));
        assertEquals(FallClutchPolicy.ScoopStep.GIVE_UP, FallClutchPolicy.scoopStep(80, false));
    }

    @Test
    void scoopUsesTheEmptyBucketThroughTheSameVanillaPath() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotFallSafetyService.java"));
        int scoop = src.indexOf("private static void maybeScoopClutchWater(");
        assertTrue(scoop > 0, "scoop step exists");
        String body = src.substring(scoop);
        assertTrue(body.contains("BotActions.ensureHotbarItem(bot, Items.BUCKET)"));
        int guard = body.indexOf("if (!hand.isOf(Items.BUCKET))");
        int use = body.indexOf("bot.interactionManager.interactItem(bot, world, hand, Hand.MAIN_HAND)");
        assertTrue(guard > 0 && use > guard, "uses the item only once the empty bucket is really in hand");
        assertTrue(body.contains("scooped = result.isAccepted() && !isWaterSource(world, waterPos)"),
                "success is judged by the water cell, so a stack of buckets still counts");
        assertTrue(body.contains("authorizeBlockMutation(bot, world, waterPos)"), "territory rules apply to the scoop too");
        assertTrue(src.contains("PENDING_SCOOP.put(bot.getUuid(), new PendingScoop(placedSourcePos(world, landing.placePos())"),
                "armed only by a placement, at the cell that took the source");
    }
}
