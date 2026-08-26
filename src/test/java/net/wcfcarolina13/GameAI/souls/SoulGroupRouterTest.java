package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the pure group-routing rules: the roster projection (profile-bound + authorized +
 * LOCAL, capped, order-preserving) and the coarse route decision table.
 */
class SoulGroupRouterTest {

    private static SoulGroupRouter.Candidate candidate(UUID id, boolean profile, boolean authorized,
                                                        SoulTypes.Reachability reachability) {
        return new SoulGroupRouter.Candidate(id, profile, authorized, reachability);
    }

    @Test
    void rosterFiltersProfileAuthorizationAndReachability() {
        UUID eligible = UUID.randomUUID();
        List<UUID> roster = SoulGroupRouter.eligibleRoster(List.of(
                candidate(UUID.randomUUID(), false, true, SoulTypes.Reachability.LOCAL),
                candidate(UUID.randomUUID(), true, false, SoulTypes.Reachability.LOCAL),
                candidate(UUID.randomUUID(), true, true, SoulTypes.Reachability.REMOTE),
                candidate(UUID.randomUUID(), true, true, SoulTypes.Reachability.UNREACHABLE),
                candidate(eligible, true, true, SoulTypes.Reachability.LOCAL)));
        assertEquals(List.of(eligible), roster);
    }

    @Test
    void rosterPreservesOrderAndCapsAtMaxSceneBots() {
        List<SoulGroupRouter.Candidate> candidates = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < SoulGroupTypes.MAX_SCENE_BOTS + 2; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            candidates.add(candidate(id, true, true, SoulTypes.Reachability.LOCAL));
        }
        List<UUID> roster = SoulGroupRouter.eligibleRoster(candidates);
        assertEquals(SoulGroupTypes.MAX_SCENE_BOTS, roster.size());
        assertEquals(ids.subList(0, SoulGroupTypes.MAX_SCENE_BOTS), roster);
    }

    @Test
    void decideTruthTable() {
        // Master off → legacy, regardless of everything else.
        assertEquals(SoulGroupRouter.RouteOutcome.NOT_SOUL,
                SoulGroupRouter.decide(false, true, true, 3));
        // Party kill switch off → legacy, even with eligible bots.
        assertEquals(SoulGroupRouter.RouteOutcome.NOT_SOUL,
                SoulGroupRouter.decide(true, true, false, 3));
        // Index still loading → consumed (deterministic loading notice).
        assertEquals(SoulGroupRouter.RouteOutcome.CONSUMED,
                SoulGroupRouter.decide(true, false, true, 3));
        // Nobody eligible → consumed (deterministic none-eligible notice).
        assertEquals(SoulGroupRouter.RouteOutcome.CONSUMED,
                SoulGroupRouter.decide(true, true, true, 0));
        // Exactly one eligible → downgrade to the ordinary DM path.
        assertEquals(SoulGroupRouter.RouteOutcome.DOWNGRADE_TO_DM,
                SoulGroupRouter.decide(true, true, true, 1));
        // Two or more → a scene.
        assertEquals(SoulGroupRouter.RouteOutcome.CONSUMED,
                SoulGroupRouter.decide(true, true, true, 2));
        assertTrue(SoulGroupRouter.decide(true, true, true, 4) == SoulGroupRouter.RouteOutcome.CONSUMED);
    }
}
