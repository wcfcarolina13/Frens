package net.wcfcarolina13.GameAI.services.construction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldTeardownPolicyTest {

    private static final List<String> PILLAR = List.of("y1", "y2", "y3", "y4", "y5", "y6", "y7", "y8", "y9", "y10", "y11", "y12");

    @Test
    void stopAfterOneBreakForgetsOnlyThatBlock() {
        // 1.1.234 review #3: /bot stop during Fortify cleanup of a 12-block pillar.
        Set<String> forget = ScaffoldTeardownPolicy.toForget(PILLAR, Set.of("y12"), Set.of());
        assertEquals(Set.of("y12"), forget);
    }

    @Test
    void serverThreadRefusalForgetsNothing() {
        assertTrue(ScaffoldTeardownPolicy.toForget(PILLAR, Set.of(), Set.of()).isEmpty());
        assertTrue(ScaffoldTeardownPolicy.toForget(PILLAR, null, null).isEmpty());
    }

    @Test
    void structureKeptBlocksAreForgottenSoALaterTeardownNeverBreaksThem() {
        Set<String> forget = ScaffoldTeardownPolicy.toForget(PILLAR, Set.of("y12"), Set.of("y1"));
        assertEquals(Set.of("y12", "y1"), forget);
    }

    @Test
    void goneOutsideTheTrackedSetIsIgnored() {
        assertTrue(ScaffoldTeardownPolicy.toForget(List.of("a"), Set.of("b"), Set.of("c")).isEmpty());
        assertTrue(ScaffoldTeardownPolicy.toForget(null, Set.of("a"), Set.of()).isEmpty());
    }

    @Test
    void onlyAirOrReplacedBlocksLeaveWithoutABreak() {
        assertTrue(ScaffoldTeardownPolicy.isGoneWithoutBreaking(ScaffoldTeardownPolicy.Precheck.GONE));
        assertTrue(ScaffoldTeardownPolicy.isGoneWithoutBreaking(ScaffoldTeardownPolicy.Precheck.NOT_SCAFFOLD));
        assertFalse(ScaffoldTeardownPolicy.isGoneWithoutBreaking(ScaffoldTeardownPolicy.Precheck.SKIP));
        assertFalse(ScaffoldTeardownPolicy.isGoneWithoutBreaking(ScaffoldTeardownPolicy.Precheck.BREAK));
    }
}
