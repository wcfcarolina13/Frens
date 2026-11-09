package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeStuckDropPolicyTest {

    @Test
    void aSafeDropCostsNoHealth() {
        assertTrue(TreeStuckDropPolicy.isSafeDrop(0));
        assertTrue(TreeStuckDropPolicy.isSafeDrop(3));
        assertFalse(TreeStuckDropPolicy.isSafeDrop(4), "4 blocks cost a heart (sandbox 2026-11-09)");
        assertFalse(TreeStuckDropPolicy.isSafeDrop(-1));
    }

    @Test
    void sidewaysMomentumIsDroppedOnlyOverTheChosenColumn() {
        assertTrue(TreeStuckDropPolicy.holdOverTarget(5.5, 7.5, 5, 7, 0.2, 0.0));
        assertTrue(TreeStuckDropPolicy.holdOverTarget(5.32, 7.65, 5, 7, 0.2, 0.0), "hitbox inside the column");
        assertFalse(TreeStuckDropPolicy.holdOverTarget(5.1, 7.5, 5, 7, 0.2, 0.0), "centre in, hitbox still over the lip");
        assertFalse(TreeStuckDropPolicy.holdOverTarget(4.6, 7.5, 5, 7, 0.2, 0.0), "not there yet");
        assertFalse(TreeStuckDropPolicy.holdOverTarget(5.5, 7.5, 5, 7, 0.0, 0.0), "already still");
    }

    @Test
    void aBotOnTheLipOfItsHoleEdgesOntoIt() {
        // Sandbox 2026-11-09 run C: onGround over air, a leaf one block down, commander 7 below.
        assertTrue(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 1, 125, 118, 0));
        assertTrue(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 3, 125, null, 40), "no commander, last tick allowed");
    }

    @Test
    void itStaysPutWhenTheHoleIsNotASafeWayDown() {
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(false, true, true, 1, 125, 118, 0), "in the air");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, false, true, 1, 125, 118, 0), "nothing open below");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, false, 1, 125, 118, 0), "resting on something other than a leaf");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 4, 125, 118, 0), "4-block drop costs health");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 1, 125, 125, 0), "commander on the canopy too");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 1, 125, 118, 41), "this hole had its time");
    }

    @Test
    void edgingSpeedPointsAtTheColumnCentreAndIsCapped() {
        assertEquals(0.1, TreeStuckDropPolicy.edgeSpeed(44202.0, 44202.5), 1e-9);
        assertEquals(-0.1, TreeStuckDropPolicy.edgeSpeed(44203.0, 44202.5), 1e-9);
        assertEquals(0.05, TreeStuckDropPolicy.edgeSpeed(44202.4, 44202.5), 1e-9);
        assertEquals(0.0, TreeStuckDropPolicy.edgeSpeed(44202.5, 44202.5), 1e-9);
    }

    @Test
    void theEscapeBreaksALeafOnlyOverASafeFallAndLeavesABotWithItsCommanderAlone() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/TreeStuckEscapeService.java"));
        int sameTree = src.indexOf("if (commanderOnSameTree) {");
        int strategy1 = src.indexOf("Strategy 1: Find a safe drop edge");
        assertTrue(sameTree > 0 && sameTree < strategy1, "a bot with its commander on the canopy runs no strategy");
        assertTrue(src.contains("isIn(BlockTags.LEAVES) && (belowIsSafe || lastResort)"),
                "the leaf under the bot goes only over a safe fall, or after 10 s stuck");
        assertTrue(src.contains("findLeafTowardTrunk(world, bot.getBlockPos(), trunk, belowIsSafe || lastResort)"),
                "the trunk strategy obeys the same rule for the leaf below");
        assertTrue(src.indexOf("dropCanopyWaypoints(bot);") < src.indexOf("Strategy 1: Find a safe drop edge"),
                "the canopy path is dropped when the bot gets down");
    }
}
