package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelWaitPolicyTest {

    @Test
    void zeroRemainingCooldownTravelsNow() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(0L, true, false, false, true, 0.9f);
        assertEquals(TravelWaitPolicy.Action.TRAVEL_NOW, TravelWaitPolicy.decide(in));
    }

    @Test
    void negativeRemainingCooldownTravelsNow() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(-50L, true, false, false, true, 0.9f);
        assertEquals(TravelWaitPolicy.Action.TRAVEL_NOW, TravelWaitPolicy.decide(in));
    }

    @Test
    void hobbyAlreadyRunningKeepsRunning() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1000L, true, true, false, false, 0.0f);
        assertEquals(TravelWaitPolicy.Action.HOBBY, TravelWaitPolicy.decide(in));
    }

    @Test
    void runningHobbyBeatsOffload() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1000L, true, true, false, true, 0.99f);
        assertEquals(TravelWaitPolicy.Action.HOBBY, TravelWaitPolicy.decide(in));
    }

    @Test
    void nearbyChestAboveThresholdOffloadsWhenNoTask() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1000L, false, false, false, true, 0.90f);
        assertEquals(TravelWaitPolicy.Action.OFFLOAD_EXISTING, TravelWaitPolicy.decide(in));
    }

    @Test
    void noChestNeverOffloadsEvenWhenFull() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1000L, false, false, false, false, 1.0f);
        assertEquals(TravelWaitPolicy.Action.WAIT, TravelWaitPolicy.decide(in));
    }

    @Test
    void hobbiesOnLongEnoughCooldownRunsHobby() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(600L, true, false, false, false, 0.0f);
        assertEquals(TravelWaitPolicy.Action.HOBBY, TravelWaitPolicy.decide(in));
    }

    @Test
    void hobbiesOffNeverRunsHobby() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(5000L, false, false, false, false, 0.0f);
        assertEquals(TravelWaitPolicy.Action.WAIT, TravelWaitPolicy.decide(in));
    }

    @Test
    void shortRemainingCooldownWithHobbiesOnWaits() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(599L, true, false, false, false, 0.0f);
        assertEquals(TravelWaitPolicy.Action.WAIT, TravelWaitPolicy.decide(in));
    }

    @Test
    void taskActiveBlocksHobby() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1000L, true, false, true, false, 0.0f);
        assertEquals(TravelWaitPolicy.Action.WAIT, TravelWaitPolicy.decide(in));
    }

    @Test
    void taskActiveBlocksOffload() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1000L, false, false, true, true, 0.95f);
        assertEquals(TravelWaitPolicy.Action.WAIT, TravelWaitPolicy.decide(in));
    }

    @Test
    void nullInputsWait() {
        assertEquals(TravelWaitPolicy.Action.WAIT, TravelWaitPolicy.decide(null));
    }

    @Test
    void fullnessAboveOneClampsAndStillOffloads() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1000L, false, false, false, true, 1.5f);
        assertEquals(TravelWaitPolicy.Action.OFFLOAD_EXISTING, TravelWaitPolicy.decide(in));
    }

    @Test
    void describeContainsActionName() {
        TravelWaitPolicy.Inputs in = new TravelWaitPolicy.Inputs(1200L, true, false, false, true, 0.90f);
        TravelWaitPolicy.Action action = TravelWaitPolicy.decide(in);
        String description = TravelWaitPolicy.describe(in, action);
        assertTrue(description.contains(action.name()));
    }
}
