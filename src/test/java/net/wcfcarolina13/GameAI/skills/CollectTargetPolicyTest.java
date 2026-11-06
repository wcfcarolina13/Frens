package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CollectTargetPolicyTest {

    @Test
    void countsGainSinceStartByDefault() {
        assertFalse(CollectTargetPolicy.reached(100, 30, false, 80));
        assertTrue(CollectTargetPolicy.reached(110, 30, false, 80));
    }

    @Test
    void untilModeCountsWholeInventory() {
        assertTrue(CollectTargetPolicy.reached(80, 30, true, 80));
        assertFalse(CollectTargetPolicy.reached(79, 0, true, 80));
    }

    @Test
    void lostItemsNeverCountNegative() {
        assertFalse(CollectTargetPolicy.reached(10, 30, false, 1));
        assertTrue(CollectTargetPolicy.reached(10, 30, false, 0));
    }

    @Test
    void unboundedTargetIsNeverReached() {
        assertFalse(CollectTargetPolicy.reached(Integer.MAX_VALUE - 1, 0, true, Integer.MAX_VALUE));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13", relative));
    }

    @Test
    void collectLoopPassesTheStopCheck() throws IOException {
        String collect = source("GameAI/skills/impl/CollectDirtSkill.java");
        assertTrue(collect.contains("net.wcfcarolina13.GameAI.skills.CollectTargetPolicy.reached("));
    }

    @Test
    void shovelStopsApproachClearingAndCandidatesOnceReached() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int mineWithinReach = shovel.indexOf("private boolean mineWithinReach(ServerPlayerEntity player, BlockPos blockPos) {");
        int gate = shovel.indexOf("if (targetReached()) {", mineWithinReach);
        int mine = shovel.indexOf("MiningTool.mineBlock(player, blockPos)", mineWithinReach);
        assertTrue(mineWithinReach >= 0 && gate > mineWithinReach && mine > gate, "approach clearing checks before mining");
        int loop = shovel.indexOf("for (BlockPos detectedPos : candidates) {");
        int loopGate = shovel.indexOf("if (targetReached()) {", loop);
        int abort = shovel.indexOf("SkillManager.shouldAbortSkill(player)", loop);
        assertTrue(loop >= 0 && loopGate > loop && abort > loopGate, "each candidate checks first");
        assertTrue(shovel.contains("TARGET_REACHED.set(previousStop);"), "thread-local restored in finally");
    }

    @Test
    void redirectChecksTheTargetBeforeMiningTheBlocker() throws IOException {
        String shovel = source("GameAI/skills/impl/DirtShovelSkill.java");
        int check = shovel.indexOf("not mining past");
        int redirect = shovel.indexOf("is hidden by {}; mining that");
        assertTrue(check > 0 && check < redirect, "the redirect must stop once the amount is reached");
    }
}
