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
        assertTrue(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 1, 125, 118, 0, 3));
        assertTrue(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 3, 125, null, 40, 3), "no commander, last tick allowed");
    }

    @Test
    void itStaysPutWhenTheHoleIsNotASafeWayDown() {
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(false, true, true, 1, 125, 118, 0, 3), "in the air");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, false, true, 1, 125, 118, 0, 3), "nothing open below");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, false, 1, 125, 118, 0, 3), "resting on something other than a leaf");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 4, 125, 118, 0, 3), "4-block drop costs health");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 1, 125, 125, 0, 3), "commander on the canopy too");
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 1, 125, 118, 41, 3), "this hole had its time");
    }

    @Test
    void aLastResortHoleMayBeDeeperButNotUnbounded() {
        assertTrue(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 6, 125, 118, 0, 8));
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, 9, 125, 118, 0, 8));
        assertFalse(TreeStuckDropPolicy.shouldEdgeIntoHole(true, true, true, Integer.MAX_VALUE, 125, 118, 0, 8), "unsafe landing");
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
        assertTrue(src.contains("isIn(BlockTags.LEAVES) && (belowIsSafe || lastResortBreak)"),
                "the leaf under the bot goes only over a safe fall, or after 10 s stuck");
        assertTrue(src.contains("findLeafTowardTrunk(world, bot.getBlockPos(), trunk, belowIsSafe)"),
                "the trunk strategy obeys the same rule for the leaf below");
        assertTrue(src.indexOf("dropCanopyWaypoints(bot);") < src.indexOf("Strategy 1: Find a safe drop edge"),
                "the canopy path is dropped when the bot gets down");
    }

    private static TreeStuckDropPolicy.SideStep step(int dx, int dy, int dz, int fall) {
        return new TreeStuckDropPolicy.SideStep(dx, dy, dz, fall, 0);
    }

    @Test
    void onTheOverhangOfATerraceEdgeTreeItStepsTowardTheHighSide() {
        // Sandbox 2026-11-09: leaf under the bot over the low terrace (fall 6), the leaf a block
        // toward the trunk over the high terrace (fall 3).
        var steps = java.util.List.of(step(-1, 0, 0, 3), step(0, 0, 1, 7), step(-1, 0, 1, 3));
        assertEquals(step(-1, 0, 0, 3), TreeStuckDropPolicy.chooseSideStep(steps, Integer.MAX_VALUE, false, 20F),
                "shallow, straight before diagonal");
        assertEquals(step(-1, 0, 0, 3), TreeStuckDropPolicy.chooseSideStep(steps, 6, true, 20F),
                "also at the last resort");
    }

    @Test
    void aSideStepIsTakenOnlyWhenItBeatsBreakingStraightDown() {
        assertEquals(null, TreeStuckDropPolicy.chooseSideStep(java.util.List.of(step(1, 0, 0, 1)), 2, false, 20F),
                "the leaf below is already safe");
        assertEquals(null, TreeStuckDropPolicy.chooseSideStep(java.util.List.of(step(1, 0, 0, 5)), Integer.MAX_VALUE, false, 20F),
                "an unsafe step waits for the last resort");
        assertEquals(step(1, 0, 0, 5), TreeStuckDropPolicy.chooseSideStep(java.util.List.of(step(1, 0, 0, 5)), 6, true, 20F),
                "last resort: 5 beats 6");
        assertEquals(null, TreeStuckDropPolicy.chooseSideStep(java.util.List.of(step(1, 0, 0, 6)), 6, true, 20F),
                "no better than straight down");
        assertEquals(step(1, 0, 0, 8), TreeStuckDropPolicy.chooseSideStep(java.util.List.of(step(1, 0, 0, 8)), Integer.MAX_VALUE, true, 20F),
                "unsafe landing below, a survivable fall beside");
        assertEquals(null, TreeStuckDropPolicy.chooseSideStep(java.util.List.of(step(1, 0, 0, 9)), Integer.MAX_VALUE, true, 20F),
                "never past the last-resort cap");
        assertEquals(null, TreeStuckDropPolicy.chooseSideStep(java.util.List.of(), Integer.MAX_VALUE, true, 20F));
    }

    @Test
    void aCostlyStepLeavesAtLeastAHeart() {
        var steps = java.util.List.of(step(1, 0, 0, 7));
        assertEquals(step(1, 0, 0, 7), TreeStuckDropPolicy.chooseSideStep(steps, Integer.MAX_VALUE, true, 6F), "6 HP - 4 = 2");
        assertEquals(null, TreeStuckDropPolicy.chooseSideStep(steps, Integer.MAX_VALUE, true, 5F), "would leave half a heart");
        assertEquals(step(-1, 0, 0, 2), TreeStuckDropPolicy.chooseSideStep(java.util.List.of(step(-1, 0, 0, 2)), Integer.MAX_VALUE, false, 1F),
                "a safe step costs nothing");
    }

    @Test
    void tiesGoToALevelStraightStep() {
        var steps = java.util.List.of(step(1, 1, 1, 2), step(1, 1, 0, 2), step(0, 0, -1, 2), step(1, 0, 1, 2));
        assertEquals(step(0, 0, -1, 2), TreeStuckDropPolicy.chooseSideStep(steps, Integer.MAX_VALUE, false, 20F));
    }

    @Test
    void fewerLeavesToBreakWinsAtTheSameFall() {
        var dug = new TreeStuckDropPolicy.SideStep(-1, 0, 0, 3, 2);
        var open = new TreeStuckDropPolicy.SideStep(0, 0, 1, 3, 0);
        assertEquals(open, TreeStuckDropPolicy.chooseSideStep(java.util.List.of(dug, open), Integer.MAX_VALUE, false, 20F));
        assertEquals(dug, TreeStuckDropPolicy.chooseSideStep(java.util.List.of(dug), Integer.MAX_VALUE, false, 20F),
                "in a pocket it digs sideways toward the shallow fall");
    }

    @Test
    void theEscapeTriesASideStepBeforeTheLastResortBreak() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/TreeStuckEscapeService.java"));
        int sideStep = src.indexOf("Strategy 3a: step onto a leaf beside it");
        int lastResortBreak = src.indexOf("boolean lastResortBreak =");
        assertTrue(sideStep > 0 && sideStep < lastResortBreak, "side step is weighed before the deep break");
        assertTrue(src.indexOf("BlockPos sideStep = SIDE_STEP_TARGET.get(botId);")
                        < src.indexOf("// If not on ground and no active target, just wait to land."),
                "the step is steered before the airborne wait and the off-leaf handling");
    }
}
