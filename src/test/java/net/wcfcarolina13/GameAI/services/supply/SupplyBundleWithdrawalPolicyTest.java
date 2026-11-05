package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.BundleWhy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketStep;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The facade's bundle decisions: one kind for the set, and why a refused set was refused. */
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
    void aDroppedTicketWasAnsweredNoOrLapsed() {
        assertEquals(BundleWhy.OWNER_DECLINED, SupplyWithdrawalPolicy.bundleWhyOfTicket(TicketStep.DROP_NOT_PERMITTED));
        for (TicketStep step : TicketStep.values()) {
            if (step != TicketStep.DROP_NOT_PERMITTED) {
                assertEquals(BundleWhy.OTHER, SupplyWithdrawalPolicy.bundleWhyOfTicket(step), step.name());
            }
        }
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
