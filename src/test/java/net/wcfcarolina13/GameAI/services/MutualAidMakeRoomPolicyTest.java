package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.MutualAidMakeRoomPolicy.Decision;
import net.wcfcarolina13.GameAI.services.MutualAidMakeRoomPolicy.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutualAidMakeRoomPolicyTest {
    @Test
    void activeRecipientDefersGearDropUntilItsTaskFinishes() {
        assertEquals(Decision.DECLINE, MutualAidMakeRoomPolicy.decideForRecipient(Kind.GEAR, false, true, false));
        assertEquals(Decision.DROP, MutualAidMakeRoomPolicy.decideForRecipient(Kind.GEAR, false, false, false));
    }

    @Test
    void activeRecipientCanAcceptAidThatNeedsNoDrop() {
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decideForRecipient(Kind.GEAR, true, true, false));
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decideForRecipient(Kind.FOOD_SHARE, false, true, false));
        assertEquals(Decision.DECLINE, MutualAidMakeRoomPolicy.decideForRecipient(Kind.FLOWER, false, true, false));
    }

    @Test
    void idleHobbyAllowsGearDropWhileACommandedSkillStillDefersIt() {
        assertEquals(Decision.DROP, MutualAidMakeRoomPolicy.decideForRecipient(Kind.GEAR, false, true, true));
        assertEquals(Decision.DECLINE, MutualAidMakeRoomPolicy.decideForRecipient(Kind.GEAR, false, true, false));
    }

    @Test
    void idleHobbyDoesNotChangeFlowerOrFoodHandoffRules() {
        assertEquals(Decision.DECLINE, MutualAidMakeRoomPolicy.decideForRecipient(Kind.FLOWER, false, true, true));
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decideForRecipient(Kind.FOOD_SHARE, false, true, true));
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decideForRecipient(Kind.GEAR, true, true, true));
    }

    // ── decide ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aStarvingBotNextToFoodDropsOnlyWhenFull() {
        assertEquals(Decision.DROP, MutualAidMakeRoomPolicy.decide(Kind.FOOD_PICKUP, false));
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decide(Kind.FOOD_PICKUP, true));
    }

    @Test
    void aGearRecipientDropsOnlyWhenFull() {
        assertEquals(Decision.DROP, MutualAidMakeRoomPolicy.decide(Kind.GEAR, false));
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decide(Kind.GEAR, true));
    }

    @Test
    void aFlowerNeverMakesAFullBotDrop() {
        assertEquals(Decision.DECLINE, MutualAidMakeRoomPolicy.decide(Kind.FLOWER, false));
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decide(Kind.FLOWER, true));
    }

    @Test
    void aFoodShareNeedsNoRoomEvenWhenFull() {
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decide(Kind.FOOD_SHARE, false));
        assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decide(Kind.FOOD_SHARE, true));
    }

    @Test
    void anEmptySlotNeverDropsAndOnlyAFullFoodPickupOrGearRecipientDrops() {
        for (Kind kind : Kind.values()) {
            assertEquals(Decision.NOT_NEEDED, MutualAidMakeRoomPolicy.decide(kind, true), kind.name());
            Decision full = MutualAidMakeRoomPolicy.decide(kind, false);
            assertNotNull(full, kind.name());
            assertEquals(kind == Kind.FOOD_PICKUP || kind == Kind.GEAR, full == Decision.DROP, kind.name());
        }
    }

    // ── backoff ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theRetryWaitIsTenSeconds() {
        assertEquals(200L, MutualAidMakeRoomPolicy.RETRY_TICKS);
        assertEquals(1_200L, MutualAidMakeRoomPolicy.nextAllowedAfterFailure(1_000L));
        assertEquals(200L, MutualAidMakeRoomPolicy.nextAllowedAfterFailure(0L));
    }

    @Test
    void theFirstAttemptIsAllowed() {
        // No failed drop on record reads as next-allowed tick 0.
        assertTrue(MutualAidMakeRoomPolicy.mayAttempt(0L, 0L));
        assertTrue(MutualAidMakeRoomPolicy.mayAttempt(1L, 0L));
        assertTrue(MutualAidMakeRoomPolicy.mayAttempt(123_456L, 0L));
    }

    @Test
    void aFailedDropHoldsOffFor199TicksAndAllowsTheTwoHundredth() {
        long failedAt = 5_000L;
        long next = MutualAidMakeRoomPolicy.nextAllowedAfterFailure(failedAt);
        assertFalse(MutualAidMakeRoomPolicy.mayAttempt(failedAt, next), "same tick as the failure");
        assertFalse(MutualAidMakeRoomPolicy.mayAttempt(failedAt + 1L, next));
        assertFalse(MutualAidMakeRoomPolicy.mayAttempt(failedAt + 199L, next));
        assertTrue(MutualAidMakeRoomPolicy.mayAttempt(failedAt + 200L, next));
        assertTrue(MutualAidMakeRoomPolicy.mayAttempt(failedAt + 201L, next));
    }

    @Test
    void mayAttemptBoundaryIsInclusiveOfTheAllowedTick() {
        assertFalse(MutualAidMakeRoomPolicy.mayAttempt(199L, 200L));
        assertTrue(MutualAidMakeRoomPolicy.mayAttempt(200L, 200L));
    }
}
