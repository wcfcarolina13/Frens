package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningStallPolicyTest {

    @Test
    void gainResetsTheStreak() {
        assertEquals(0, MiningStallPolicy.nextStreak(5, 40, 41));
    }

    @Test
    void noGainExtendsTheStreak() {
        assertEquals(1, MiningStallPolicy.nextStreak(0, 50, 50));
        assertEquals(4, MiningStallPolicy.nextStreak(3, 50, 50));
    }

    @Test
    void endsAfterSixStalledIterations() {
        // The sandbox case: the wall is done, only painted blocks are left, collected stays at 50.
        int streak = 0;
        int iterations = 0;
        while (!MiningStallPolicy.exhausted(streak)) {
            streak = MiningStallPolicy.nextStreak(streak, 50, 50);
            iterations++;
            assertTrue(iterations <= 100, "must terminate");
        }
        assertEquals(MiningStallPolicy.NO_GAIN_ITERATION_LIMIT, iterations);
        assertFalse(MiningStallPolicy.exhausted(MiningStallPolicy.NO_GAIN_ITERATION_LIMIT - 1));
    }

    @Test
    void collectDirtEndsTheJobOnAStall() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java"));
        assertTrue(src.contains("MiningStallPolicy.nextStreak("), "streak tracked per iteration");
        assertTrue(src.contains("MiningStallPolicy.exhausted(noGainStreak)"), "streak ends the job");
        assertTrue(src.contains("\"No more reachable \" + harvestLabel + \" nearby."), "honest end message");
    }
}
