package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what is testable about {@link SoulMessageDelivery} without a running Minecraft server.
 *
 * <p>{@code net.minecraft.server.MinecraftServer} cannot be constructed or mocked at all outside
 * a running game: it (and its supertypes) carries static initialization that never completes
 * without a real Fabric/Minecraft bootstrap, and Mockito's inline mock maker fails outright
 * trying to instrument it here --
 * {@code org.mockito.exceptions.base.MockitoException: Cannot instrument class
 * net.minecraft.server.MinecraftServer because it or one of its supertypes could not be
 * initialized} -- confirmed by actually attempting it in this test class before writing this
 * comment. That is not a "mock it more shallowly" problem; no test in this repository mocks
 * {@code MinecraftServer}, and this one does not either.
 *
 * <p>Consequently {@link SoulMessageDelivery#deliverReply}, {@link SoulMessageDelivery#deliverStatus},
 * and {@link SoulMessageDelivery.ProductionDeliveryGuard}'s live entity/store resolution are
 * untestable at the unit level in this harness -- they are exercised in-game instead, once wired
 * up in a later task. What unit tests CAN and DO cover here is {@link SoulMessageDelivery#evaluate},
 * the pure six-gate boolean combinator that backs {@code ProductionDeliveryGuard}'s decision and
 * is deliberately free of any Minecraft/Fabric type or {@link SoulStore} I/O, so every gate's
 * fail-closed behavior is exhaustively exercised without touching a server at all.
 */
class SoulMessageDeliveryTest {

    @Test
    void deliversOnlyWhenEveryGatePasses() {
        assertTrue(SoulMessageDelivery.evaluate(true, true, true, true, true, SoulTypes.Reachability.LOCAL));
        assertTrue(SoulMessageDelivery.evaluate(true, true, true, true, true, SoulTypes.Reachability.REMOTE));
    }

    @Test
    void failsClosedWhenMasterDisabled() {
        assertFalse(SoulMessageDelivery.evaluate(false, true, true, true, true, SoulTypes.Reachability.LOCAL));
    }

    @Test
    void failsClosedWhenActiveProfileChanged() {
        assertFalse(SoulMessageDelivery.evaluate(true, false, true, true, true, SoulTypes.Reachability.LOCAL));
    }

    @Test
    void failsClosedWhenCursorEpochNoLongerMatchesTheToken() {
        assertFalse(SoulMessageDelivery.evaluate(true, true, false, true, true, SoulTypes.Reachability.LOCAL));
    }

    @Test
    void failsClosedWhenEitherPartyIsOffline() {
        assertFalse(SoulMessageDelivery.evaluate(true, true, true, false, true, SoulTypes.Reachability.LOCAL));
    }

    @Test
    void failsClosedWhenOwnershipNoLongerExact() {
        assertFalse(SoulMessageDelivery.evaluate(true, true, true, true, false, SoulTypes.Reachability.LOCAL));
    }

    @Test
    void failsClosedWhenReachabilityIsUnreachable() {
        assertFalse(SoulMessageDelivery.evaluate(
                true, true, true, true, true, SoulTypes.Reachability.UNREACHABLE));
    }
}
