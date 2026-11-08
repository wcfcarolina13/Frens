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
}
