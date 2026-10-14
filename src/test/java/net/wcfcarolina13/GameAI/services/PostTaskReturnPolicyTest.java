package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.GameAI.services.PostTaskReturnPolicy.Action.FAST_TRAVEL;
import static net.wcfcarolina13.GameAI.services.PostTaskReturnPolicy.Action.NONE;
import static net.wcfcarolina13.GameAI.services.PostTaskReturnPolicy.Action.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PostTaskReturnPolicyTest {

    private static double sq(double blocks) {
        return blocks * blocks;
    }

    @Test
    void atDestinationDoesNothing() {
        assertEquals(NONE, PostTaskReturnPolicy.decide(0.0));
    }

    @Test
    void withinArrivedRadiusDoesNothing() {
        assertEquals(NONE, PostTaskReturnPolicy.decide(sq(3.9)));
    }

    @Test
    void exactlyArrivedRadiusDoesNothing() {
        assertEquals(NONE, PostTaskReturnPolicy.decide(sq(PostTaskReturnPolicy.ARRIVED_BLOCKS)));
    }

    @Test
    void justPastArrivedRadiusWalks() {
        assertEquals(WALK, PostTaskReturnPolicy.decide(sq(4.01)));
    }

    @Test
    void fewBlocksAwayWalksNotFastTravel() {
        // The 1.1.233 playthrough case: drop sweep ended ~6 blocks from the player.
        assertEquals(WALK, PostTaskReturnPolicy.decide(sq(6.0)));
    }

    @Test
    void exactlyWalkMaxWalks() {
        assertEquals(WALK, PostTaskReturnPolicy.decide(sq(PostTaskReturnPolicy.WALK_MAX_BLOCKS)));
    }

    @Test
    void justPastWalkMaxFastTravels() {
        assertEquals(FAST_TRAVEL, PostTaskReturnPolicy.decide(sq(24.01)));
    }

    @Test
    void farAwayFastTravels() {
        assertEquals(FAST_TRAVEL, PostTaskReturnPolicy.decide(sq(200.0)));
    }

    @Test
    void customThresholdsAreHonoured() {
        assertEquals(NONE, PostTaskReturnPolicy.decide(sq(2.0), 2.0, 10.0));
        assertEquals(WALK, PostTaskReturnPolicy.decide(sq(10.0), 2.0, 10.0));
        assertEquals(FAST_TRAVEL, PostTaskReturnPolicy.decide(sq(10.5), 2.0, 10.0));
    }

    @Test
    void negativeOrNaNDistanceDoesNothing() {
        assertEquals(NONE, PostTaskReturnPolicy.decide(-1.0));
        assertEquals(NONE, PostTaskReturnPolicy.decide(Double.NaN));
    }

    @Test
    void infiniteDistanceFastTravels() {
        assertEquals(FAST_TRAVEL, PostTaskReturnPolicy.decide(Double.POSITIVE_INFINITY));
    }
}
