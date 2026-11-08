package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoulTaskFailurePolicyTest {

    @Test
    void theBotsOwnTroubleIsExecution() {
        for (String msg : List.of("Stuck: could not reach any sheep.", "Stripmine aborted: failed to advance tunnel.",
                "Surface recovery stalled without gaining height.", "Can't return to the fishing spot after storing items.",
                "Timed out after 120s", "Still in water after shore move. Try again from land.",
                "Reached step limit without completing ascent.", "Stripmine paused: unable to clear gravel",
                "Lost fishing rod during reactive sweep.", "§cStuck in a hole")) {
            assertEquals(SoulTaskFailurePolicy.Kind.EXECUTION, SoulTaskFailurePolicy.classify(msg), msg);
        }
    }

    @Test
    void surroundingsSetupAndInterruptionsAreNotCounted() {
        for (String msg : List.of("No valid trees nearby. Try moving closer or adjust radius.", "Missing hoe.",
                "Mining paused: lava detected.", "Mining paused: inventory full.", "woodcut paused due to nearby threat.",
                "No huntable mobs found nearby.", "Inventory full and no reachable chest. Cleared saved fishing spot.",
                "No reachable fence found or placeable.", "Another skill is already running.",
                "Need a fishing rod (3 sticks + 2 strings) before fishing.", "Too late to start wool run.")) {
            assertEquals(SoulTaskFailurePolicy.Kind.UNKNOWN, SoulTaskFailurePolicy.classify(msg), msg);
        }
    }

    @Test
    void codeFaultsAndBlanksAreNotCounted() {
        assertEquals(SoulTaskFailurePolicy.Kind.UNKNOWN, SoulTaskFailurePolicy.classify("Surface recovery crashed: stuck"));
        assertEquals(SoulTaskFailurePolicy.Kind.UNKNOWN, SoulTaskFailurePolicy.classify("Skill 'x' ended unexpectedly."));
        assertEquals(SoulTaskFailurePolicy.Kind.UNKNOWN, SoulTaskFailurePolicy.classify("Woodcut error: stalled"));
        assertEquals(SoulTaskFailurePolicy.Kind.UNKNOWN, SoulTaskFailurePolicy.classify(""));
        assertEquals(SoulTaskFailurePolicy.Kind.UNKNOWN, SoulTaskFailurePolicy.classify(null));
        assertEquals("execution", SoulTaskFailurePolicy.factValue("Stuck."));
        assertEquals("", SoulTaskFailurePolicy.factValue("No grass nearby."));
    }
}
