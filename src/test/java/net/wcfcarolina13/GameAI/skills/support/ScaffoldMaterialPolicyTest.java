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
}
