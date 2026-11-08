package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-reading check that the hand-dig stair step in {@code CollectDirtSkill} runs the same
 * hazard checks as the pillar path in {@code executeUpwardStep}: the hazard detector before the
 * first mine, with the pause-and-flag failure path, and the falling-block stabilizer after the
 * last mine and before the step-up.
 */
class HandDigStairWiringTest {
    private static final Path SKILL = Path.of(
            "src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java");
    private static final String STEP_SIGNATURE = "private SkillExecutionResult executeHandDigStairStep(";
    private static final String DETECT = "MiningHazardDetector.detect(player, cutVolume, stepTargets)";
    private static final String MINE = "mineStraightStairBlock(player, pos)";
    private static final String STABILIZE = "FallingBlockStabilizer.stabilizeAndReclear(";
    private static final String STEP_UP = "moveTowardCellBounded(player, stand, true, beforeY)";

    @Test
    void hazardDetectorRunsOverThePlannedCutBeforeTheFirstMine() throws IOException {
        String body = block(source(), STEP_SIGNATURE);
        int detect = body.indexOf(DETECT);
        int firstMine = body.indexOf(MINE);
        assertTrue(detect >= 0, "hand-dig step does not call MiningHazardDetector.detect");
        assertTrue(firstMine >= 0, "hand-dig step does not mine");
        assertTrue(detect < firstMine, "detect must run before the first mine");
        // The cut volume the detector inspects is built from every planned cell, torches excluded.
        String beforeDetect = body.substring(0, detect);
        assertTrue(beforeDetect.contains("for (HandDigStairPolicy.Offset c : plan.cellsToMine())"));
        assertTrue(beforeDetect.contains("if (!isTorchBlock(world.getBlockState(pos))) {\n"
                + "                cutVolume.add(pos);"));
        // The step is not counted until the hazard check has passed.
        assertTrue(detect < body.indexOf("handDigSteps[0]++;"));
    }

    @Test
    void blockingHazardTakesThePillarPathsFailureRoute() throws IOException {
        String body = block(source(), STEP_SIGNATURE);
        String hazardBranch = body.substring(body.indexOf("if (hazardCheck.blockingHazard().isPresent())"),
                body.indexOf("handDigSteps[0]++;"));
        assertTrue(hazardBranch.contains("WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());"));
        assertTrue(hazardBranch.contains("SkillResumeService.flagManualResume(player);"));
        assertTrue(hazardBranch.contains("hazard.chatMessage()"));
        assertTrue(hazardBranch.contains("return SkillExecutionResult.failure(hazard.failureMessage());"));
        assertTrue(body.contains("hazardCheck.adjacentWarnings().forEach("));
        // Same shape as the pillar path's step 1.
        String pillar = block(source(), "private SkillExecutionResult executeUpwardStep(");
        for (String line : new String[]{
                "WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());",
                "SkillResumeService.flagManualResume(player);",
                "return SkillExecutionResult.failure(hazard.failureMessage());"}) {
            assertTrue(pillar.contains(line), line);
        }
    }

    @Test
    void fallingBlocksAreStabilizedAfterTheLastMineAndBeforeTheStepUp() throws IOException {
        String body = block(source(), STEP_SIGNATURE);
        int lastMine = body.lastIndexOf(MINE);
        int stabilize = body.indexOf(STABILIZE);
        int stepUp = body.indexOf(STEP_UP);
        assertTrue(stabilize >= 0, "hand-dig step does not call FallingBlockStabilizer");
        assertTrue(stepUp >= 0, "hand-dig step does not step up");
        // The stabilizer's own re-clear lambda mines too, so it is the last mine call in the body;
        // the mine loop must come before the stabilizer and the stabilizer before the step-up.
        int mineLoop = body.indexOf("for (HandDigStairPolicy.Offset c : plan.cellsToMine())",
                body.indexOf("handDigSteps[0]++;"));
        assertTrue(mineLoop >= 0 && mineLoop < stabilize, "mine loop must come before the stabilizer");
        assertTrue(stabilize < stepUp, "stabilizer must run before the step-up");
        assertTrue(lastMine > stabilize && lastMine < stepUp, "the stabilizer re-clears with the same mine helper");
        assertTrue(body.contains("player, source, stand, cutVolume,\n"
                + "                    this::isPassableForStairs, this::isTorchBlock,\n"
                + "                    pos -> mineStraightStairBlock(player, pos), true);"));
        // An unsettled or unclearable refill is no progress, never a step into the cells.
        String afterStabilize = body.substring(stabilize, stepUp);
        assertTrue(afterStabilize.contains("if (!settled) {"));
        assertTrue(afterStabilize.contains("return SkillExecutionResult.success(\"No progress - will retry\");"));
    }

    @Test
    void pillarPathHandsItsCommandSourceToTheHandDigStep() throws IOException {
        String pillar = block(source(), "private SkillExecutionResult executeUpwardStep(");
        assertTrue(pillar.contains("return executeHandDigStairStep(player, direction, source, handDigSteps);"));
        assertFalse(pillar.contains("executeHandDigStairStep(player, direction, handDigSteps)"));
    }

    private static String source() throws IOException {
        return Files.readString(SKILL);
    }

    private static String block(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, "Missing signature: " + signature);
        int start = source.indexOf('{', signatureAt);
        int depth = 1;
        for (int end = start + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, end);
        }
        throw new AssertionError("Unclosed block: " + signature);
    }
}
