package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowVerticalGapPolicyTest {

    @Test
    void twoBlocksIsNotVertical() {
        assertFalse(FollowVerticalGapPolicy.isVerticalSeparation(64, 66));
        assertFalse(FollowVerticalGapPolicy.isVerticalSeparation(66, 64));
    }

    @Test
    void threeBlocksIsVerticalBothWays() {
        assertTrue(FollowVerticalGapPolicy.isVerticalSeparation(64, 67));
        assertTrue(FollowVerticalGapPolicy.isVerticalSeparation(67, 64));
    }

    @Test
    void theHeadlessFourBlockCaseIsVertical() {
        // 2026-11-07 headless run: commander on a 4-high block, regroup prompt at dy=4, no reroute.
        assertTrue(FollowVerticalGapPolicy.isVerticalSeparation(100, 104));
    }

    @Test
    void rerouteAndRegroupPromptShareTheGate() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/FollowPlannerService.java"));
        assertEquals(2, count(src, "FollowVerticalGapPolicy.isVerticalSeparation("),
                "the reroute and the regroup prompt must both use FollowVerticalGapPolicy");
        assertFalse(src.contains("absDeltaY < 6"), "the old 6-block reroute gate is gone");
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    void aRerouteGoalWhereTheBotAlreadyStandsIsSkipped() {
        // Sandbox 2026-11-08: commander on top of a 4-high block at (7405.5, 124, 2500.5), bot at its foot.
        org.junit.jupiter.api.Assertions.assertFalse(FollowVerticalGapPolicy.fallbackGoalGainsOnTarget(
                7403.5, 120, 2500.5, 7405.5, 120, 2500.5, 7405.5, 124, 2500.5));
        org.junit.jupiter.api.Assertions.assertFalse(FollowVerticalGapPolicy.fallbackGoalGainsOnTarget(
                7403.5, 120, 2500.5, 7406.5, 121, 2500.5, 7405.5, 124, 2500.5));
    }

    @Test
    void aRerouteGoalThatBringsTheBotCloserIsKept() {
        // Same block, bot 7 blocks out: the goal at the block's foot is a real reroute.
        org.junit.jupiter.api.Assertions.assertTrue(FollowVerticalGapPolicy.fallbackGoalGainsOnTarget(
                7398.5, 120, 2500.5, 7405.5, 120, 2500.5, 7405.5, 124, 2500.5));
        // An 8-high block: the goal at its foot gains 4 blocks for a bot 8 blocks out.
        org.junit.jupiter.api.Assertions.assertTrue(FollowVerticalGapPolicy.fallbackGoalGainsOnTarget(
                7497.5, 120, 2500.5, 7505.5, 120, 2500.5, 7505.5, 128, 2500.5));
    }

    @Test
    void theRerouteChecksTheGainBeforePlanning() throws java.io.IOException {
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src/main/java/net/wcfcarolina13/GameAI/services/FollowPlannerService.java"));
        int gain = src.indexOf("FollowVerticalGapPolicy.fallbackGoalGainsOnTarget(");
        int plan = src.indexOf("captureSnapshotForGoal(server, worldKey, botId, fallbackGoal, false)");
        org.junit.jupiter.api.Assertions.assertTrue(gain > 0 && gain < plan);
    }

    @Test
    void onlyAPlanToTheCommanderClearsTheNoPathStreak() throws java.io.IOException {
        assertTrue(FollowVerticalGapPolicy.rerouteClearsNoPathStreak(false));
        assertFalse(FollowVerticalGapPolicy.rerouteClearsNoPathStreak(true));
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src/main/java/net/wcfcarolina13/GameAI/services/FollowPlannerService.java"));
        assertTrue(src.contains("if (FollowVerticalGapPolicy.rerouteClearsNoPathStreak(rerouted)) {"));
    }
}
