package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LineOfSightRedirectPolicyTest {

    @Test
    void parsesBlockerFromMiningToolMessage() {
        assertArrayEquals(new int[]{2299, 118, -1801},
                LineOfSightRedirectPolicy.blockerFrom("⚠️ Cannot mine: no line of sight (blocked by 2299, 118, -1801)."));
    }

    @Test
    void ignoresOtherResults() {
        assertNull(LineOfSightRedirectPolicy.blockerFrom(null));
        assertNull(LineOfSightRedirectPolicy.blockerFrom("Mining complete!"));
        assertNull(LineOfSightRedirectPolicy.blockerFrom("⚠️ Cannot mine: out of reach."));
    }

    @Test
    void redirectsOnlyToAWantedVisibleBlockInReach() {
        assertTrue(LineOfSightRedirectPolicy.shouldRedirect(true, true, false, false, true));
        assertFalse(LineOfSightRedirectPolicy.shouldRedirect(false, true, false, false, true), "same block");
        assertFalse(LineOfSightRedirectPolicy.shouldRedirect(true, false, false, false, true), "not a wanted block");
        assertFalse(LineOfSightRedirectPolicy.shouldRedirect(true, true, true, false, true), "block entity");
        assertFalse(LineOfSightRedirectPolicy.shouldRedirect(true, true, false, true, true), "already excluded");
        assertFalse(LineOfSightRedirectPolicy.shouldRedirect(true, true, false, false, false), "out of reach");
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13", relative));
    }

    @Test
    void miningToolStillWritesTheFormatThePolicyParses() throws IOException {
        assertTrue(source("PlayerUtils/MiningTool.java").contains(
                "\"⚠️ Cannot mine: no line of sight (blocked by \" + hit.getBlockPos().toShortString() + \").\""));
    }

    @Test
    void shovelRedirectsBeforeTheStillPresentCheckAndHandlesHazards() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int parse = shovel.indexOf("LineOfSightRedirectPolicy.blockerFrom(result)");
        int hazard = shovel.indexOf("MiningHazardDetector.detect(player, List.of(blocker), List.of())", parse);
        int hazardFail = shovel.indexOf("return failure(context, hazard.failureMessage());", hazard);
        int mine = shovel.indexOf("mineWithTimeout(player, blocker)", parse);
        int stillPresent = shovel.indexOf("getBlockState(minedPos).getBlock() != Blocks.AIR", parse);
        assertTrue(parse >= 0 && hazard > parse && hazardFail > hazard && mine > hazardFail && stillPresent > mine);
    }

    @Test
    void hiddenTargetGetsOneRetryThenIsExcluded() {
        assertFalse(LineOfSightRedirectPolicy.excludeHidden(1));
        assertTrue(LineOfSightRedirectPolicy.excludeHidden(2));
        assertTrue(LineOfSightRedirectPolicy.excludeHidden(27));
    }

    @Test
    void recognisesProtectedRefusal() {
        assertTrue(LineOfSightRedirectPolicy.isProtectedRefusal("⚠️ Refusing to mine a protected block."));
        assertFalse(LineOfSightRedirectPolicy.isProtectedRefusal("⚠️ Cannot mine: out of reach."));
        assertFalse(LineOfSightRedirectPolicy.isProtectedRefusal(null));
    }

    @Test
    void miningToolStillWritesTheProtectedRefusal() throws IOException {
        assertTrue(source("PlayerUtils/MiningTool.java").contains("\"⚠️ Refusing to mine a protected block.\""));
    }

    @Test
    void approachSkipsAndRecordsRefusedBlocks() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int skip = shovel.indexOf("if (excluded != null && approachClearsAny(approachPlan, excluded)) {");
        int prepare = shovel.indexOf("if (!prepareApproach(source, player, approachPlan, excluded)) {");
        assertTrue(skip >= 0 && prepare > skip);
        assertTrue(shovel.contains("if (refused != null && LineOfSightRedirectPolicy.isProtectedRefusal(result)) {"));
    }

    @Test
    void hiddenBlockerCountsTowardTheRetryLimit() throws IOException {
        assertTrue(source("GameAI/skills/impl/DirtShovelSkill.java")
                .contains("LineOfSightRedirectPolicy.excludeHidden(noteHidden(context, blocker))"));
    }

    @Test
    void hiddenTargetExclusionUsesTheJobCounter() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int stillPresent = shovel.indexOf("getBlockState(minedPos).getBlock() != Blocks.AIR");
        int rule = shovel.indexOf("LineOfSightRedirectPolicy.excludeHidden(noteHidden(context, minedPos))", stillPresent);
        assertTrue(rule > stillPresent);
    }

    @Test
    void refusedBlockerIsExcludedSoItIsNotPickedAgain() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int mine = shovel.indexOf("mineWithTimeout(player, blocker)");
        int exclude = shovel.indexOf("excluded.add(blocker);", mine);
        assertTrue(mine >= 0 && exclude > mine);
        assertTrue(shovel.contains("|| ProtectedStructureBlockHelper.isNeverBreakBlock(blockerState)"));
    }

    @Test
    void lavaTouchingABlockKeepsItUnmined() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int targetCheck = shovel.indexOf("BlockPos lavaNextToTarget = lavaTouching(player, detectedPos);");
        int firstMine = shovel.indexOf("MiningTool.mineBlock(player, detectedPos)");
        assertTrue(targetCheck >= 0 && firstMine > targetCheck, "target lava check runs before it is mined");
        int blockerCheck = shovel.indexOf("BlockPos lavaNextToBlocker = lavaTouching(player, blocker);");
        int blockerMine = shovel.indexOf("mineWithTimeout(player, blocker)");
        assertTrue(blockerCheck >= 0 && blockerMine > blockerCheck, "blocker lava check runs before it is mined");
    }

    @Test
    void candidatesAreOrderedExposedFirst() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int exposed = shovel.indexOf("comparingInt(pos -> exposed.computeIfAbsent(pos, p -> hasOpenFace(player, p)) ? 0 : 1)");
        int score = shovel.indexOf(".thenComparingDouble(pos -> -DirtNavigationPolicy.score(origin, pos))", exposed);
        assertTrue(exposed >= 0 && score > exposed);
    }

    @Test
    void collectRuntimeCapCountsTimeWithoutProgress() throws IOException {
        String collect = source("GameAI/skills/impl/CollectDirtSkill.java");
        assertTrue(collect.contains("System.currentTimeMillis() - lastProgressMs < MAX_RUNTIME_MILLIS"));
        int success = collect.indexOf("collected = Math.max(collected, inventoryCollected);\n                lastProgressMs = System.currentTimeMillis();");
        assertTrue(success >= 0, "a successful iteration resets the progress clock");
    }
}
