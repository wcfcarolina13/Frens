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
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                assertFalse(ScaffoldMaterialPolicy.mayHandGather(dx, -1, dz));
                assertFalse(ScaffoldMaterialPolicy.mayHandGather(dx, -2, dz));
            }
        }
    }

    @Test
    void handGatherTakesFeetAndHeadLevelBesideTheBotWithinRadiusSix() {
        assertTrue(ScaffoldMaterialPolicy.mayHandGather(1, 0, 0));
        assertTrue(ScaffoldMaterialPolicy.mayHandGather(-3, 1, 3));
        assertTrue(ScaffoldMaterialPolicy.mayHandGather(4, 0, 0), "radius 6 since 1.1.240");
        assertTrue(ScaffoldMaterialPolicy.mayHandGather(-6, 1, 6));
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(0, 0, 0), "the bot's own cell");
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(0, 1, 0), "the bot's head cell");
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(7, 0, 0));
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(0, 0, -7));
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(0, 2, 1));
        assertFalse(ScaffoldMaterialPolicy.mayHandGather(6, -1, 0), "standing row stays off limits at radius 6");
    }

    // ── 1.1.240: unprotected cells first, then nearest, then feet before head ─────────────────

    @Test
    void gatherOrderPutsUnprotectedCellsFirstThenNearest() {
        ScaffoldMaterialPolicy.GatherCandidate nearProtected = new ScaffoldMaterialPolicy.GatherCandidate(1, 0, 0, true);
        ScaffoldMaterialPolicy.GatherCandidate farFree = new ScaffoldMaterialPolicy.GatherCandidate(5, 0, 2, false);
        ScaffoldMaterialPolicy.GatherCandidate midFree = new ScaffoldMaterialPolicy.GatherCandidate(-3, 0, 1, false);
        ScaffoldMaterialPolicy.GatherCandidate midProtected = new ScaffoldMaterialPolicy.GatherCandidate(2, 1, 2, true);
        ScaffoldMaterialPolicy.GatherCandidate nearFreeHead = new ScaffoldMaterialPolicy.GatherCandidate(0, 1, 2, false);
        java.util.List<ScaffoldMaterialPolicy.GatherCandidate> cells = new java.util.ArrayList<>(java.util.List.of(
                nearProtected, farFree, midFree, midProtected, nearFreeHead));
        cells.sort(ScaffoldMaterialPolicy.gatherOrder());
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(nearFreeHead, midFree, farFree, nearProtected, midProtected), cells);
    }

    @Test
    void gatherOrderTakesFeetLevelBeforeHeadLevelAtEqualDistance() {
        ScaffoldMaterialPolicy.GatherCandidate head = new ScaffoldMaterialPolicy.GatherCandidate(0, 1, 2, false);
        ScaffoldMaterialPolicy.GatherCandidate feet = new ScaffoldMaterialPolicy.GatherCandidate(1, 0, 2, false);
        java.util.List<ScaffoldMaterialPolicy.GatherCandidate> cells = new java.util.ArrayList<>(java.util.List.of(head, feet));
        cells.sort(ScaffoldMaterialPolicy.gatherOrder());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(feet, head), cells);
    }
}
