package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for hand-gathered scaffold material. No net.minecraft types. */
class ScaffoldMaterialPolicyTest {

    @Test
    void dirtFamilyCounts() {
        assertTrue(ScaffoldMaterialPolicy.isHandScaffold(0.5F, false, true, false), "dirt, podzol, coarse, rooted, mud");
        assertTrue(ScaffoldMaterialPolicy.isHandScaffold(0.6F, false, true, false), "grass block, mycelium");
        assertTrue(ScaffoldMaterialPolicy.isHandScaffold(0.1F, false, true, false), "moss block");
    }

    @Test
    void gravityBlocksNever() {
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.5F, true, false, false), "sand, red sand");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.6F, true, false, false), "gravel");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.5F, true, true, false), "a falling block even if tagged dirt");
    }

    @Test
    void notDirtOrNeedsToolOrTooHard() {
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.6F, false, false, false), "clay");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.2F, false, false, true), "snow block");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(2.0F, false, false, true), "cobblestone");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.4F, false, false, true), "netherrack");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.7F, false, true, false), "muddy mangrove roots");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(0.5F, false, true, true), "tagged dirt but needs a tool");
        assertFalse(ScaffoldMaterialPolicy.isHandScaffold(-1.0F, false, true, false), "unbreakable");
    }
    // ── 1.1.234 fix wave #8: the hand gather never digs the ground the bot stands on ──────────

    @Test
    void handGatherNeverDigsTheStandingRowOrBelow() {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                assertFalse(ScaffoldMaterialPolicy.mayHandGather(dx, -1, dz));
                assertFalse(ScaffoldMaterialPolicy.mayHandGather(dx, -2, dz));
            }
        }
    }

    @Test
    void handGatherTakesFeetAndHeadLevelBesideTheBotWithinRadiusThree() {
        assertTrue(ScaffoldMaterialPolicy.mayHandGather(1, 0, 0));
        assertTrue(ScaffoldMaterialPolicy.mayHandGather(-3, 1, 3));
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(0, 0, 0), "the bot's own cell");
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(0, 1, 0), "the bot's head cell");
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(4, 0, 0));
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(0, 2, 1));
    }
}
