package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.BundleWhy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketStep;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facade's bundle decisions: one kind for the set, why a refused set was refused, and which
 * lines the step after the wait leaves alone.
 */
class SupplyBundleWithdrawalPolicyTest {

    @Test
    void aWaitingLineMakesTheWholeBundleWait() {
        assertEquals(Kind.WAITING, SupplyWithdrawalPolicy.aggregateKinds(List.of(Kind.MOVED, Kind.WAITING)));
        assertEquals(Kind.WAITING, SupplyWithdrawalPolicy.aggregateKinds(List.of(Kind.REFUSED, Kind.WAITING, Kind.READY)));
    }

    @Test
    void anythingMovedIsAMoveThenReadyThenRefused() {
        assertEquals(Kind.MOVED, SupplyWithdrawalPolicy.aggregateKinds(List.of(Kind.REFUSED, Kind.MOVED)));
        assertEquals(Kind.MOVED, SupplyWithdrawalPolicy.aggregateKinds(List.of(Kind.MOVED, Kind.READY)));
        assertEquals(Kind.READY, SupplyWithdrawalPolicy.aggregateKinds(List.of(Kind.REFUSED, Kind.READY)));
        assertEquals(Kind.REFUSED, SupplyWithdrawalPolicy.aggregateKinds(List.of(Kind.REFUSED, Kind.REFUSED)));
    }

    @Test
    void anEmptyBundleIsRefused() {
        assertEquals(Kind.REFUSED, SupplyWithdrawalPolicy.aggregateKinds(List.of()));
        assertEquals(Kind.REFUSED, SupplyWithdrawalPolicy.aggregateKinds(null));
    }

    @Test
    void aRecentNoAtRequestTimeIsTheOwnersDecisionAndTheRestIsNot() {
        assertEquals(BundleWhy.OWNER_DECLINED, SupplyWithdrawalPolicy.bundleWhyOfRequest(RequestStatus.REJECT_COOLDOWN));
        for (RequestStatus status : RequestStatus.values()) {
            if (status != RequestStatus.REJECT_COOLDOWN) {
                assertEquals(BundleWhy.OTHER, SupplyWithdrawalPolicy.bundleWhyOfRequest(status), status.name());
            }
        }
        assertEquals(BundleWhy.OTHER, SupplyWithdrawalPolicy.bundleWhyOfRequest(null));
    }

    @Test
    void aDroppedTicketOnRejectCooldownWasAnsweredNo() {
        assertEquals(BundleWhy.OWNER_DECLINED,
                SupplyWithdrawalPolicy.bundleWhyOfTicket(TicketStep.DROP_NOT_PERMITTED, true));
    }

    @Test
    void aDroppedTicketWithNoRejectCooldownLapsedUnanswered() {
        // The ledger sets the reject cooldown on an explicit No only, so a dropped ticket without one
        // is a prompt nobody answered: "No answer about …", not "Okay, I won't take …".
        assertEquals(BundleWhy.NO_ANSWER,
                SupplyWithdrawalPolicy.bundleWhyOfTicket(TicketStep.DROP_NOT_PERMITTED, false));
    }

    @Test
    void theOtherTicketStepsAreNobodysDecisionEitherWay() {
        for (TicketStep step : TicketStep.values()) {
            if (step != TicketStep.DROP_NOT_PERMITTED) {
                assertEquals(BundleWhy.OTHER, SupplyWithdrawalPolicy.bundleWhyOfTicket(step, true), step.name());
                assertEquals(BundleWhy.OTHER, SupplyWithdrawalPolicy.bundleWhyOfTicket(step, false), step.name());
            }
        }
    }

    @Test
    void aMovedOrRefusedLineIsSettledBeforeTheWaitAndNotRunAgain() {
        // A line a live grant covered in the first step is already in the pack: the settle step
        // must not ask for it again. A refused line was decided for this call.
        assertTrue(SupplyWithdrawalPolicy.bundleLineSettled(Kind.MOVED));
        assertTrue(SupplyWithdrawalPolicy.bundleLineSettled(Kind.REFUSED));
    }

    @Test
    void aWaitingOrReadyLineIsWhatTheSettleStepIsFor() {
        assertFalse(SupplyWithdrawalPolicy.bundleLineSettled(Kind.WAITING));
        assertFalse(SupplyWithdrawalPolicy.bundleLineSettled(Kind.READY));
        assertFalse(SupplyWithdrawalPolicy.bundleLineSettled(null));
    }

    @Test
    void theOwnersDecisionOutranksNoAnswerWhichOutranksTheRest() {
        assertEquals(BundleWhy.OWNER_DECLINED, SupplyWithdrawalPolicy.strongerWhy(BundleWhy.OTHER, BundleWhy.OWNER_DECLINED));
        assertEquals(BundleWhy.OWNER_DECLINED, SupplyWithdrawalPolicy.strongerWhy(BundleWhy.OWNER_DECLINED, BundleWhy.NO_ANSWER));
        assertEquals(BundleWhy.NO_ANSWER, SupplyWithdrawalPolicy.strongerWhy(BundleWhy.NO_ANSWER, BundleWhy.OTHER));
        assertEquals(BundleWhy.OTHER, SupplyWithdrawalPolicy.strongerWhy(BundleWhy.NONE, BundleWhy.OTHER));
        assertEquals(BundleWhy.NONE, SupplyWithdrawalPolicy.strongerWhy(null, null));
    }
}
